package `in`.jphe.storyvox.source.notion.net

import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.source.notion.config.NotionConfig
import `in`.jphe.storyvox.source.notion.config.NotionConfigState
import `in`.jphe.storyvox.source.notion.config.NotionDefaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Issue #393 — anonymous reader-mode client for Notion's *unofficial*
 * `www.notion.so/api/v3` surface. Same set react-notion-x's
 * `notion-client` package hits; we hand-craft the JSON bodies because
 * the queryCollection loader shape is deeply nested and full kotlinx-
 * serialization round-tripping is more code than the bodies themselves.
 *
 * Endpoints wrapped:
 *
 *  - **POST `/loadPageChunk`** — fetch a page's blocks (the structural
 *    tree). Body: `{pageId, limit, chunkNumber, cursor:{stack:[]}, verticalColumns:false}`.
 *    The response's recordMap.block[pageId].value.value.content is the
 *    ordered array of child block ids; child block payloads are in the
 *    same recordMap.
 *
 *  - **POST `/queryCollection`** — list rows of a database. Body wraps
 *    `{collection:{id}, collectionView:{id}, source, loader:{reducer...}}`.
 *    The reducer query is the same shape react-notion-x sends, with
 *    `collection_group_results` whose `limit` controls the per-call cap.
 *    The unofficial endpoint reports the full row count under
 *    `result.reducerResults.collection_group_results.total`; when
 *    `total > limit` we re-issue the call with `limit = total` (capped
 *    at [MAX_COLLECTION_ROWS]) so large databases aren't silently
 *    truncated. Issue #698.
 *
 *  - **POST `/syncRecordValuesMain`** — fetch a specific block by id
 *    (used to resolve a child page when its block payload isn't yet
 *    in any in-memory recordMap — typically a deep link from Library).
 *    Body: `{requests:[{id, table:"block", version:-1}]}`.
 *
 *  - **POST `/getPublicPageData`** — verify a page is publicly shared.
 *    Body: `{type:"block-space",name:"page",blockId,saveParent:false,
 *    showMoveTo:false,showWebsiteLogin:true}`. 200 + `publicAccessRole`
 *    populated = public; 4xx (or `requireLogin:true`) = gated. We use
 *    this only as a one-shot diagnostic when a user-supplied root page
 *    id can't be loaded, not on every read.
 *
 * Caching: in-memory `pageId → NotionChunkResponse` map keyed on the
 * page id. Lives for the process lifetime — Android process kill
 * clears it, which is exactly the eviction policy we want (a fresh
 * cold start re-reads current Notion content; a warm session
 * deduplicates round-trips). Concurrent access is rare (Browse +
 * detail are sequential), but we use ConcurrentHashMap to keep the
 * paths thread-safe.
 *
 * Notion may rate-limit the unofficial endpoint without a Retry-After
 * header. We surface 429 as `FictionResult.RateLimited` with retryAfter
 * defaulting to 30s so the caller can back off without guessing.
 */
@Singleton
internal class NotionUnofficialApi @Inject constructor(
    private val client: OkHttpClient,
    private val config: NotionConfig,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    private val mediaTypeJson = "application/json".toMediaType()

    /**
     * Process-lifetime cache of pageId → loadPageChunk response. Same
     * keying as react-notion-x: hyphenated page id strings. We never
     * evict — Notion content changes on the order of hours/days, well
     * past a typical Browse session.
     */
    private val pageCache = ConcurrentHashMap<String, NotionChunkResponse>()

    /**
     * Per-pageId Mutex set. Issue #964 — same TOCTOU shape #942 fixed
     * in GutenbergSource: the [pageCache] read-then-write pattern
     * (`cache[id]?.let { return it }` … suspending fetch … `cache[id] =
     * fetched`) is check-then-act, so two concurrent reads for the
     * same page both miss, both POST `loadPageChunk`, both write.
     * Holding a per-id Mutex around the read+fetch+write makes the
     * sequence atomic per page while letting different pages proceed
     * in parallel. `computeIfAbsent` on ConcurrentHashMap is the only
     * truly atomic construction path (`getOrPut` is itself check-then-
     * act); the lambda is cheap so contention on the locks map is a
     * non-issue. Locks accumulate one entry per page seen this
     * process — Notion pages are bounded in any realistic session, so
     * the map is effectively as small as [pageCache].
     */
    private val fetchLocks = ConcurrentHashMap<String, Mutex>()

    private fun lockFor(hyphenatedId: String): Mutex =
        fetchLocks.computeIfAbsent(hyphenatedId) { Mutex() }

    /**
     * Fetch a page's blocks via `loadPageChunk`. Returns the full
     * recordMap (with the requested page at `recordMap.block[pageId]`).
     * Cached per process.
     *
     * Issue #964 — fast-path read is lock-free; if the cache misses
     * we serialize the fetch+write per pageId under [lockFor], then
     * double-check inside the lock so the loser of the race returns
     * the winner's cached response instead of issuing a duplicate
     * `loadPageChunk` POST.
     */
    suspend fun loadPageChunk(pageId: String): FictionResult<NotionChunkResponse> {
        val hyphenated = hyphenatePageId(pageId)
        pageCache[hyphenated]?.let { return FictionResult.Success(it) }
        return lockFor(hyphenated).withLock {
            pageCache[hyphenated]?.let { return@withLock FictionResult.Success(it) }
            val state = config.current()
            val body = """{"pageId":"$hyphenated","limit":100,"chunkNumber":0,"cursor":{"stack":[]},"verticalColumns":false}"""
            postEndpoint<NotionChunkResponse>(state.unofficialBaseUrl, "loadPageChunk", body)
                .also { result ->
                    if (result is FictionResult.Success) {
                        pageCache[hyphenated] = result.value
                    }
                }
        }
    }

    /**
     * Query a database (collection) for rows. The loader shape is a
     * verbatim port of the body react-notion-x sends — type:"reducer"
     * with a single `collection_group_results` reducer.
     *
     * Pagination (#698): the unofficial endpoint reports the full row
     * count under `result.reducerResults.collection_group_results.total`
     * but caps a single response at the loader's `limit`. We issue the
     * first call at the caller's [limit]; if the server says there are
     * more rows than we received, we re-issue with `limit = total`
     * (capped at [MAX_COLLECTION_ROWS]) so [collectRows] sees the whole
     * recordMap. One refetch covers any realistic Notion collection;
     * larger-than-cap collections are truncated to [MAX_COLLECTION_ROWS]
     * deterministically rather than silently at 100.
     */
    suspend fun queryCollection(
        collectionId: String,
        collectionViewId: String,
        limit: Int = 100,
    ): FictionResult<NotionQueryCollectionResponse> {
        val first = queryCollectionPage(collectionId, collectionViewId, limit)
        if (first !is FictionResult.Success) return first
        val total = first.value.rowsTotal() ?: return first
        val returned = first.value.rowsReturned()
        if (total <= returned) return first
        val widened = total.coerceAtMost(MAX_COLLECTION_ROWS)
        if (widened <= returned) return first
        return queryCollectionPage(collectionId, collectionViewId, widened)
    }

    private suspend fun queryCollectionPage(
        collectionId: String,
        collectionViewId: String,
        limit: Int,
    ): FictionResult<NotionQueryCollectionResponse> {
        val state = config.current()
        val collId = hyphenatePageId(collectionId)
        val viewId = hyphenatePageId(collectionViewId)
        val safeLimit = limit.coerceIn(1, MAX_COLLECTION_ROWS)
        val body = buildString {
            append('{')
            append("\"collection\":{\"id\":\"").append(collId).append("\"},")
            append("\"collectionView\":{\"id\":\"").append(viewId).append("\"},")
            append("\"source\":{\"type\":\"collection\",\"id\":\"").append(collId).append("\"},")
            append("\"loader\":{")
            append("\"type\":\"reducer\",")
            append("\"reducers\":{\"collection_group_results\":{\"type\":\"results\",\"limit\":")
                .append(safeLimit).append(",\"loadContentCover\":true}},")
            append("\"sort\":[],")
            append("\"filter\":{\"filters\":[],\"operator\":\"and\"},")
            append("\"searchQuery\":\"\",")
            append("\"userTimeZone\":\"America/Los_Angeles\"")
            append('}')
            append('}')
        }
        return postEndpoint<NotionQueryCollectionResponse>(state.unofficialBaseUrl, "queryCollection", body)
    }

    /**
     * Fetch a specific block by id via `syncRecordValuesMain`. Used to
     * resolve a child page's block payload when it isn't present in any
     * cached recordMap — typically when a deep link drops the user
     * straight into Library without going through Browse first.
     *
     * The spec doc lists this as `getRecordValues`, but that endpoint
     * has been returning `502 MemcachedCrossCellError` for
     * cross-workspace requests since at least 2024; `syncRecordValuesMain`
     * is the working successor and what react-notion-x v7 now hits.
     */
    suspend fun syncRecordValues(blockIds: List<String>): FictionResult<NotionChunkResponse> {
        if (blockIds.isEmpty()) return FictionResult.Success(NotionChunkResponse())
        val state = config.current()
        val body = buildString {
            append("{\"requests\":[")
            for ((i, id) in blockIds.withIndex()) {
                if (i > 0) append(',')
                append("{\"id\":\"").append(hyphenatePageId(id))
                append("\",\"table\":\"block\",\"version\":-1}")
            }
            append("]}")
        }
        return postEndpoint<NotionChunkResponse>(state.unofficialBaseUrl, "syncRecordValuesMain", body)
    }

    /**
     * Probe a page's public-share status via `getPublicPageData`. Used
     * for one-shot diagnostics when a configured root page id fails to
     * load — lets us tell the user "this page isn't shared publicly"
     * vs. "Notion is unreachable" without guessing from a 4xx code.
     */
    suspend fun getPublicPageData(pageId: String): FictionResult<NotionPublicPageData> {
        val state = config.current()
        val hyphenated = hyphenatePageId(pageId)
        val body = """{"type":"block-space","name":"page","blockId":"$hyphenated","saveParent":false,"showMoveTo":false,"showWebsiteLogin":true}"""
        return postEndpoint<NotionPublicPageData>(state.unofficialBaseUrl, "getPublicPageData", body)
    }

    /** Wipe the in-memory page cache. Hook for tests + a future
     *  "refresh content" affordance in Settings. */
    fun clearCache() {
        pageCache.clear()
    }

    // ─── transport ────────────────────────────────────────────────────

    /**
     * Run the network call on the IO dispatcher. The FictionSource
     * contract is suspend-and-callable-from-anywhere, but storyvox's
     * Browse paginator dispatches some suspend points on the Main
     * thread (Compose state updates ride alongside the source call),
     * so a `suspend` function that issues a synchronous OkHttp request
     * will NPE with [android.os.NetworkOnMainThreadException]. Wrap
     * every HTTP call here so callers don't have to think about it.
     */
    private suspend inline fun <reified T> postEndpoint(
        baseUrl: String,
        endpoint: String,
        body: String,
    ): FictionResult<T> = withContext(Dispatchers.IO) {
        when (val raw = doPost("$baseUrl/$endpoint", body)) {
            is FictionResult.Success -> try {
                FictionResult.Success(json.decodeFromString<T>(raw.value))
            } catch (e: kotlinx.serialization.SerializationException) {
                FictionResult.NetworkError(
                    "Notion (unofficial) returned unexpected JSON shape — endpoint may have changed",
                    e,
                )
            }
            is FictionResult.Failure -> raw
        }
    }

    private fun doPost(url: String, body: String): FictionResult<String> {
        return try {
            val reqBuilder = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                // Notion's edge accepts our project-specific UA on the
                // unofficial endpoint — same string the official-API
                // client uses for consistency. Spoofing a browser UA
                // would be misleading and the unofficial endpoint
                // doesn't gate on it anyway.
                .header("User-Agent", NotionDefaults.USER_AGENT)
                .post(body.toRequestBody(mediaTypeJson))
            client.newCall(reqBuilder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                // The unofficial endpoint sometimes returns HTTP 200 with
                // an `isNotionError:true` envelope (Cloudflare/edge errors
                // come back this way). We sniff for that shape before
                // claiming success so the caller sees the real error.
                val notionError = decodeNotionError(text)
                when {
                    notionError != null -> mapNotionError(resp.code, notionError, text)
                    resp.code == 401 || resp.code == 403 ->
                        FictionResult.AuthRequired(
                            "Notion page is not publicly shared (or share was revoked)",
                        )
                    resp.code == 404 ->
                        FictionResult.NotFound(
                            "Notion page not found via unofficial API",
                        )
                    resp.code == 429 ->
                        FictionResult.RateLimited(
                            retryAfter = (resp.header("Retry-After")
                                ?.toLongOrNull() ?: 30L).seconds,
                            message = "Notion rate-limited the unofficial endpoint",
                        )
                    !resp.isSuccessful ->
                        FictionResult.NetworkError(
                            "HTTP ${resp.code} from Notion (unofficial)",
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
     * Decode a structured `isNotionError` envelope if the body looks
     * like one. Returns null on any non-error JSON shape (success
     * payloads also parse permissively, so we gate on the
     * `isNotionError` boolean).
     */
    private fun decodeNotionError(body: String): NotionUnofficialError? = runCatching {
        if (body.isBlank() || !body.contains("\"isNotionError\"")) return null
        val err = json.decodeFromString<NotionUnofficialError>(body)
        if (err.isNotionError) err else null
    }.getOrNull()

    private fun <T> mapNotionError(
        httpCode: Int,
        err: NotionUnofficialError,
        rawBody: String,
    ): FictionResult<T> {
        val msg = err.message ?: err.debugMessage ?: "Notion error"
        val nameLower = err.name?.lowercase(Locale.ROOT) ?: ""
        return when {
            nameLower.contains("unauthorized") || nameLower.contains("permission") ->
                FictionResult.AuthRequired(msg)
            nameLower.contains("notfound") || nameLower.contains("not_found") ->
                FictionResult.NotFound(msg)
            nameLower.contains("ratelimit") || httpCode == 429 ->
                FictionResult.RateLimited(retryAfter = 30.seconds, message = msg)
            else -> FictionResult.NetworkError(msg, IOException(rawBody.take(200)))
        }
    }

    companion object {
        /**
         * Upper bound on rows we'll request in a single queryCollection
         * call when widening for pagination (#698). A collection larger
         * than this gets truncated deterministically rather than
         * silently at the default 100. Sized for the largest realistic
         * Notion database the unofficial endpoint will return without
         * 5xx-ing; can be raised if a user reports truncation.
         */
        internal const val MAX_COLLECTION_ROWS: Int = 5000

        /**
         * Notion's unofficial API requires page ids in the hyphenated
         * UUID form (8-4-4-4-12). We accept compact 32-hex or
         * already-hyphenated forms and normalize.
         *
         * - "0959e44599984143acabc80187305001" → "0959e445-9998-4143-acab-c80187305001"
         * - "0959e445-9998-4143-acab-c80187305001" → unchanged
         * - Other inputs returned as-is so the API can surface its
         *   own error if the id is genuinely malformed.
         */
        internal fun hyphenatePageId(id: String): String {
            if (id.isEmpty()) return id
            if (id.contains('-')) return id
            if (id.length != 32) return id
            return buildString(36) {
                append(id, 0, 8)
                append('-')
                append(id, 8, 12)
                append('-')
                append(id, 12, 16)
                append('-')
                append(id, 16, 20)
                append('-')
                append(id, 20, 32)
            }
        }
    }
}
