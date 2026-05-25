package `in`.jphe.storyvox.source.notion.net

import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.source.notion.config.NotionConfig
import `in`.jphe.storyvox.source.notion.config.NotionConfigState
import `in`.jphe.storyvox.source.notion.config.NotionDefaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Issue #233 — minimal Notion REST API client. Three endpoints:
 *
 *  - **POST `/v1/databases/{database_id}/query`** — list pages in a
 *    database. Each page becomes one storyvox fiction.
 *  - **GET `/v1/pages/{page_id}`** — single page metadata. Used by
 *    [fictionDetail] when the user resumes a Library row without
 *    going through Browse first.
 *  - **GET `/v1/blocks/{block_id}/children?page_size=100`** — child
 *    blocks under a page. Used to render chapters: blocks are split
 *    into chapters on each `heading_1` boundary.
 *
 * Notion's REST API:
 *  - Requires `Authorization: Bearer <integration_token>` on every
 *    call. No anonymous tier.
 *  - Requires a `Notion-Version` header (we pin to 2022-06-28 per
 *    [NotionDefaults.API_VERSION]).
 *  - Returns paginated bodies with `has_more` + `next_cursor`. We
 *    page through internally where it makes sense (block children
 *    must be fully assembled for a chapter; database query returns
 *    one page at a time and we let Browse's paginator drive
 *    next-page fetches).
 *  - Returns structured error JSON on 4xx/5xx (`{ code, message }`);
 *    we decode that into [NotionError] for human-readable surfacing.
 */
@Singleton
internal class NotionApi @Inject constructor(
    private val client: OkHttpClient,
    private val config: NotionConfig,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    private val mediaTypeJson = "application/json".toMediaType()

    /**
     * Query a Notion database for pages. Paginated — pass `cursor` from
     * the previous response's `nextCursor` to fetch the next page; pass
     * null on the first call. `pageSize` is clamped at 100 (Notion's
     * server-side max).
     *
     * Returns a [FictionResult] so the source layer's standard
     * Success / NotFound / RateLimited / NetworkError matches can flow
     * up to the Browse paginator without translation. Pulls the
     * database id + auth token from [NotionConfig] at call time so a
     * runtime config change applies on the next fetch.
     */
    suspend fun queryDatabase(
        cursor: String? = null,
        pageSize: Int = 100,
    ): FictionResult<NotionPageList> {
        val state = config.current()
        requireConfigured<NotionPageList>(state)?.let { return it }
        val safe = pageSize.coerceIn(1, 100)
        val body = buildString {
            append('{')
            append("\"page_size\":").append(safe)
            if (!cursor.isNullOrBlank()) {
                append(",\"start_cursor\":\"").append(escapeJson(cursor)).append("\"")
            }
            // Notion sorts by `last_edited_time` desc by default when no
            // sort is supplied — that's our Popular ordering (most-recent
            // first). If we ever want explicit ordering we add it here.
            append('}')
        }
        val url = state.baseUrl + "/v1/databases/${state.databaseId}/query"
        return postJson<NotionPageList>(url, body, state)
    }

    /**
     * Single-page fetch. Used when the user opens a saved fiction whose
     * page id is in Library but the page details aren't in our query
     * cache yet (cold start, library row).
     */
    suspend fun page(pageId: String): FictionResult<NotionPage> {
        val state = config.current()
        requireConfigured<NotionPage>(state)?.let { return it }
        val url = state.baseUrl + "/v1/pages/$pageId"
        return getJson<NotionPage>(url, state)
    }

    /**
     * Fetch a page's block children, transparently following
     * `has_more / next_cursor` pagination so the caller gets the full
     * block list as one flat array. Notion caps `page_size` at 100;
     * pages with thousands of blocks (long-form articles) compose into
     * 10-20 round-trips. Acceptable for chapter rendering — the alternative
     * (streaming chapter assembly) adds complexity Browse doesn't need.
     */
    suspend fun pageBlocks(pageId: String): FictionResult<List<NotionBlock>> {
        val state = config.current()
        requireConfigured<List<NotionBlock>>(state)?.let { return it }
        val accumulated = mutableListOf<NotionBlock>()
        var cursor: String? = null
        // Safety cap — if Notion ever returns a paginated cycle we don't
        // want an infinite loop. 50 pages × 100 blocks = 5,000 blocks per
        // chapter; well past anything a sane Notion page contains.
        repeat(50) {
            val url = buildString {
                append(state.baseUrl)
                append("/v1/blocks/").append(pageId).append("/children")
                append("?page_size=100")
                if (cursor != null) append("&start_cursor=").append(cursor)
            }
            when (val r = getJson<NotionBlockList>(url, state)) {
                is FictionResult.Success -> {
                    accumulated.addAll(r.value.results)
                    if (!r.value.hasMore || r.value.nextCursor.isNullOrBlank()) {
                        return FictionResult.Success(accumulated.toList())
                    }
                    cursor = r.value.nextCursor
                }
                is FictionResult.Failure -> return r
            }
        }
        return FictionResult.Success(accumulated.toList())
    }

    // ─── transport ────────────────────────────────────────────────────

    private suspend inline fun <reified T> getJson(url: String, state: NotionConfigState): FictionResult<T> =
        when (val raw = doRequest(url, state, body = null, method = "GET")) {
            is FictionResult.Success -> try {
                FictionResult.Success(json.decodeFromString<T>(raw.value))
            } catch (e: kotlinx.serialization.SerializationException) {
                FictionResult.NetworkError("Notion returned unexpected JSON shape", e)
            }
            is FictionResult.Failure -> raw
        }

    private suspend inline fun <reified T> postJson(
        url: String,
        body: String,
        state: NotionConfigState,
    ): FictionResult<T> =
        when (val raw = doRequest(url, state, body = body, method = "POST")) {
            is FictionResult.Success -> try {
                FictionResult.Success(json.decodeFromString<T>(raw.value))
            } catch (e: kotlinx.serialization.SerializationException) {
                FictionResult.NetworkError("Notion returned unexpected JSON shape", e)
            }
            is FictionResult.Failure -> raw
        }

    /**
     * Issue #585 — sync OkHttp `execute()` on `Dispatchers.IO`. See
     * `ArxivApi.getRaw` kdoc for full context; same crash class.
     */
    private suspend fun doRequest(
        url: String,
        state: NotionConfigState,
        body: String?,
        method: String,
    ): FictionResult<String> = withContext(Dispatchers.IO) {
        try {
            val reqBuilder = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${state.apiToken}")
                .header("Notion-Version", state.apiVersion)
                .header("Accept", "application/json")
                .header("User-Agent", NotionDefaults.USER_AGENT)
            when (method) {
                "POST" -> reqBuilder.post((body ?: "{}").toRequestBody(mediaTypeJson))
                else -> reqBuilder.get()
            }
            client.newCall(reqBuilder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                when {
                    resp.code == 401 || resp.code == 403 ->
                        FictionResult.AuthRequired(
                            decodeErrorMessage(text)
                                ?: "Notion rejected the integration token",
                        )
                    resp.code == 404 ->
                        FictionResult.NotFound(
                            decodeErrorMessage(text) ?: "Notion resource not found",
                        )
                    resp.code == 429 ->
                        FictionResult.RateLimited(
                            retryAfter = resp.header("Retry-After")
                                ?.toLongOrNull()
                                ?.seconds,
                            message = decodeErrorMessage(text)
                                ?: "Notion rate-limited the request",
                        )
                    !resp.isSuccessful ->
                        FictionResult.NetworkError(
                            decodeErrorMessage(text) ?: "HTTP ${resp.code} from Notion",
                            IOException("HTTP ${resp.code}"),
                        )
                    else -> FictionResult.Success(text)
                }
            }
        } catch (e: IOException) {
            FictionResult.NetworkError(e.message ?: "fetch failed", e)
        }
    }

    /**
     * Decode a Notion structured error to a human string. Notion is
     * good about returning `{ "code", "message" }` envelopes; the
     * runCatching guards a non-JSON body (gateway HTML errors, empty
     * bodies on the worst transient failures).
     */
    private fun decodeErrorMessage(body: String): String? = runCatching {
        if (body.isBlank()) return null
        val err = json.decodeFromString<NotionError>(body)
        err.message
    }.getOrNull()

    /**
     * If [state] isn't usable for API calls, return the appropriate
     * pre-baked [FictionResult.Failure] so the source layer can fast-
     * fail without bothering Notion. `null` means "good to go".
     */
    private fun <T> requireConfigured(state: NotionConfigState): FictionResult<T>? {
        // v0.5.23 shipped with a TODO placeholder database id and this
        // method rejected it as "not configured". v0.5.24 replaced the
        // placeholder with TechEmpower's real Resources DB id, but the
        // rejection check stayed — silently breaking the bundled
        // default for anyone with an integration token shared to the
        // Resources DB. v0.5.25 (#393) removes the equality check; the
        // anonymous-mode path runs without a token, and PAT mode is
        // gated by token presence alone.
        if (state.apiToken.isBlank()) {
            return FictionResult.AuthRequired(
                "Notion integration token not configured",
            )
        }
        if (state.databaseId.isBlank()) {
            return FictionResult.NotFound(
                "Notion database id not configured",
            )
        }
        return null
    }
}

/**
 * Minimal JSON string-content escape — only what we actually emit in
 * the database/query body (backslash, double-quote, control chars).
 * Notion cursor strings are URL-safe base64 in practice but we don't
 * assume that; this stays correct for any cursor.
 */
internal fun escapeJson(s: String): String = buildString(s.length + 4) {
    for (c in s) when (c) {
        '\\' -> append("\\\\")
        '"' -> append("\\\"")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> append(c)
    }
}

