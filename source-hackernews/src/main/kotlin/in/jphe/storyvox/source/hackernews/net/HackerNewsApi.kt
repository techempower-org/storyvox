package `in`.jphe.storyvox.source.hackernews.net

import `in`.jphe.storyvox.data.source.model.FictionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #379 — minimal client for the Hacker News Firebase API + the
 * Algolia HN Search fallback.
 *
 * HN exposes two flavors of endpoint:
 *
 *  - **Catalog lists** (`/v0/topstories.json`, `askstories.json`,
 *    `showstories.json`) — return a JSON array of integer item ids.
 *    Top usually has ~500 ids; Ask / Show are typically ~200 each.
 *  - **Item** (`/v0/item/<id>.json`) — returns one story / comment /
 *    job / poll. We only care about `"type":"story"` for catalog
 *    surfaces, plus the comment subtree (`"type":"comment"`) walked
 *    via `kids[]` for narration body.
 *
 * No auth, no rate limit headers documented; we still send a polite
 * User-Agent so any future blocking has a contact point.
 *
 * Search is NOT in the official HN API — we use the Algolia HN Search
 * API (`https://hn.algolia.com/api/v1/search`) which Y Combinator
 * sanctions for this purpose. Results map cleanly to the same
 * `FictionSummary` shape via [AlgoliaSearchResponse].
 */
@Singleton
internal open class HackerNewsApi @Inject constructor(
    private val client: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /**
     * `GET /v0/topstories.json` — array of ids, highest-ranked first.
     * HN returns ~500 of these; the caller decides how many to surface
     * (the spec takes 50 for the Browse landing).
     */
    suspend fun topStoryIds(): FictionResult<List<Long>> = fetchIdArray(
        "$FIREBASE_BASE/v0/topstories.json",
    )

    /** `GET /v0/askstories.json` — Ask HN ids only. */
    suspend fun askStoryIds(): FictionResult<List<Long>> = fetchIdArray(
        "$FIREBASE_BASE/v0/askstories.json",
    )

    /** `GET /v0/showstories.json` — Show HN ids only. */
    suspend fun showStoryIds(): FictionResult<List<Long>> = fetchIdArray(
        "$FIREBASE_BASE/v0/showstories.json",
    )

    /**
     * `GET /v0/item/<id>.json` — one item (story, comment, job, poll).
     * Returns null inside Success when HN responds with the literal
     * `"null"` body — that happens for ids that were deleted /
     * collapsed (the API doesn't 404, it nulls). The caller treats a
     * null payload as "skip this entry".
     */
    open suspend fun item(id: Long): FictionResult<HnItem?> = withContext(Dispatchers.IO) {
        val url = "$FIREBASE_BASE/v0/item/$id.json"
        try {
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.code == 404) return@withContext FictionResult.NotFound("HN item $id missing")
                if (!resp.isSuccessful) return@withContext FictionResult.NetworkError(
                    "HTTP ${resp.code} fetching HN item $id",
                    IOException("HTTP ${resp.code}"),
                )
                val text = resp.body?.string()
                    ?: return@withContext FictionResult.NetworkError("empty body", IOException("empty"))
                // HN serves the literal four bytes "null" with HTTP 200
                // for missing / deleted items. Treat as "no item",
                // which is distinct from a network failure and from a
                // present-but-flagged item.
                if (text.trim() == "null") return@withContext FictionResult.Success(null)
                FictionResult.Success(parseItem(text))
            }
        } catch (e: IOException) {
            FictionResult.NetworkError(e.message ?: "fetch failed", e)
        } catch (e: kotlinx.serialization.SerializationException) {
            FictionResult.NetworkError("HN item $id returned unexpected JSON shape", e)
        }
    }

    /**
     * `GET https://hn.algolia.com/api/v1/search?query=...&tags=story` —
     * Algolia-indexed full-text search over HN stories. We restrict to
     * `tags=story` so the result set matches the Browse catalog (no
     * comments / polls). Algolia pages with `page=N` zero-indexed; we
     * surface storyvox's 1-indexed page param translated.
     */
    suspend fun searchAlgolia(query: String, page: Int = 1): FictionResult<AlgoliaSearchResponse> =
        withContext(Dispatchers.IO) {
            val q = java.net.URLEncoder.encode(query, Charsets.UTF_8)
            val zeroIdx = (page - 1).coerceAtLeast(0)
            val url = "$ALGOLIA_BASE/search?query=$q&tags=story&page=$zeroIdx"
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext FictionResult.NetworkError(
                        "HTTP ${resp.code} from Algolia",
                        IOException("HTTP ${resp.code}"),
                    )
                    val text = resp.body?.string()
                        ?: return@withContext FictionResult.NetworkError("empty body", IOException("empty"))
                    FictionResult.Success(json.decodeFromString<AlgoliaSearchResponse>(text))
                }
            } catch (e: IOException) {
                FictionResult.NetworkError(e.message ?: "search failed", e)
            } catch (e: kotlinx.serialization.SerializationException) {
                FictionResult.NetworkError("Algolia returned unexpected JSON shape", e)
            }
        }

    private suspend fun fetchIdArray(url: String): FictionResult<List<Long>> =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext FictionResult.NetworkError(
                        "HTTP ${resp.code} from $url",
                        IOException("HTTP ${resp.code}"),
                    )
                    val text = resp.body?.string()
                        ?: return@withContext FictionResult.NetworkError("empty body", IOException("empty"))
                    FictionResult.Success(parseIdArray(text))
                }
            } catch (e: IOException) {
                FictionResult.NetworkError(e.message ?: "fetch failed", e)
            } catch (e: kotlinx.serialization.SerializationException) {
                FictionResult.NetworkError("HN list returned unexpected JSON shape", e)
            }
        }

    // ── parsing helpers ────────────────────────────────────────────

    /**
     * Parse a JSON array of integer ids — `[39000000, 39000001, ...]`.
     * Hoisted into the api class (rather than a free function) so
     * tests can exercise it without spinning up an OkHttp client.
     */
    internal fun parseIdArray(text: String): List<Long> {
        val element = json.parseToJsonElement(text)
        val arr = element as? JsonArray
            ?: throw kotlinx.serialization.SerializationException("expected JSON array, got ${element::class.simpleName}")
        return arr.mapNotNull { (it as? JsonPrimitive)?.longOrNull() }
    }

    /** Parse a single HN item JSON object. Exposed for testability. */
    internal fun parseItem(text: String): HnItem? {
        if (text.trim() == "null") return null
        return json.decodeFromString<HnItem>(text)
    }

    private fun JsonPrimitive.longOrNull(): Long? =
        content.toLongOrNull() ?: intOrNull?.toLong()

    companion object {
        const val FIREBASE_BASE = "https://hacker-news.firebaseio.com"
        const val ALGOLIA_BASE = "https://hn.algolia.com/api/v1"

        /** Polite identifier — gives any future rate-limit hits a contact
         *  point. Matches the pattern used by `:source-gutenberg`. */
        const val USER_AGENT = "storyvox-hackernews/1.0 (+https://github.com/techempower-org/candela)"

        /** Browse landing fetches the first 50 of ~500 top-story ids
         *  per #379's spec. Defined here so the source and tests share
         *  the same constant. */
        const val TOP_STORIES_LIMIT = 50

        /** Cap on the number of comments rendered into a link-story's
         *  chapter body. The recursion-depth cap pairs with this so
         *  long comment threads don't blow up the chapter size on a
         *  popular front-page story. */
        const val MAX_COMMENTS = 50

        /** Max depth the comment-tree walker descends. 4 levels is
         *  enough to show ground-floor responses and one or two
         *  follow-ups without a typical front-page mega-thread
         *  spilling kilobytes of nested rebuttals into a single
         *  chapter body. */
        const val MAX_COMMENT_DEPTH = 4
    }
}

// ── Wire types ──────────────────────────────────────────────────

/**
 * One HN item from `/v0/item/<id>.json`. Per the docs every field is
 * optional because the type discriminator is the `type` field
 * (`"story"`, `"comment"`, `"job"`, `"poll"`, `"pollopt"`); a "story"
 * carries `title` / `by` / `url`-or-`text`, a "comment" carries `text`
 * / `by` / `parent` / `kids`.
 *
 * `dead` and `deleted` are set when a moderator removed or flagged
 * the item; we skip those rows when rendering the comment subtree.
 */
@Serializable
internal data class HnItem(
    val id: Long,
    val type: String? = null,
    val by: String? = null,
    val time: Long? = null,
    val title: String? = null,
    val url: String? = null,
    val text: String? = null,
    val score: Int? = null,
    val descendants: Int? = null,
    val kids: List<Long> = emptyList(),
    val parent: Long? = null,
    val deleted: Boolean = false,
    val dead: Boolean = false,
)

/**
 * One result from `/api/v1/search?tags=story`. Algolia returns a lot
 * more metadata (highlight ranges, `_tags`, `numComments`) than
 * storyvox uses for the FictionSummary mapping; only the four fields
 * the summary actually consumes are decoded.
 */
@Serializable
internal data class AlgoliaSearchResponse(
    val hits: List<AlgoliaHit> = emptyList(),
    val nbPages: Int = 1,
    val page: Int = 0,
)

@Serializable
internal data class AlgoliaHit(
    @kotlinx.serialization.SerialName("objectID")
    val objectId: String,
    val title: String? = null,
    val author: String? = null,
    val url: String? = null,
    @kotlinx.serialization.SerialName("story_text")
    val storyText: String? = null,
    val points: Int? = null,
    @kotlinx.serialization.SerialName("num_comments")
    val numComments: Int? = null,
)
