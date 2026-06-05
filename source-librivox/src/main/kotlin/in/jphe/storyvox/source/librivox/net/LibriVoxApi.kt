package `in`.jphe.storyvox.source.librivox.net

import `in`.jphe.storyvox.data.source.model.FictionResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.time.Duration.Companion.seconds

/**
 * Issue #1015 — minimal LibriVox catalog API client.
 *
 * LibriVox (https://librivox.org/) is an all-volunteer project that
 * records public-domain books (Project Gutenberg texts, mostly) and
 * releases the recordings into the public domain. The catalog is exposed
 * as a read-only JSON/XML feed at
 * `https://librivox.org/api/feed/audiobooks/` — no auth, no API key. The
 * per-section audio files live on archive.org (HTTPS MP3), which Media3
 * streams directly via the audio-stream backend (issue #373) — exactly
 * the path `:source-radio` uses, no TTS involved.
 *
 * ## Endpoints used
 *
 * A single endpoint with different query params:
 *
 * - **`?format=json&limit=…&offset=…`** — catalog listing, drives
 *   [`popular`][in.jphe.storyvox.source.librivox.LibriVoxSource.popular].
 * - **`?title=…` / `?author=…`** — title / author search (substring
 *   match server-side). Drives Browse → LibriVox → Search.
 * - **`?id=<book id>&extended=1`** — single-book fetch. The plain
 *   listing **omits the `sections` array** (verified against the live
 *   API 2026-06-05); only `extended=1` returns per-section audio URLs.
 *   So [byId] always sets `extended=1` — it's the only call that needs
 *   the chapter-level data, and it's the call
 *   [`fictionDetail`][in.jphe.storyvox.source.librivox.LibriVoxSource.fictionDetail]
 *   /  [`chapter`][in.jphe.storyvox.source.librivox.LibriVoxSource.chapter]
 *   route through.
 *
 * ## Why not request `extended=1` on the list calls?
 *
 * `extended=1` fans each book out to its full section list (Count of
 * Monte Cristo alone is 128 sections). For a 50-book browse page that's
 * a multi-megabyte response the user never sees — the browse cards only
 * need title / author / language / section-count, all present in the
 * lean listing. We pay the per-section cost lazily, once, when the user
 * actually opens a book.
 *
 * ## Failure mapping
 *
 * - LibriVox returns `{"error":"Audiobooks could not be found"}` (HTTP
 *   200) for an empty result set rather than `[]` — [getBooks] maps that
 *   sentinel to `Success(emptyList())` so a no-results search reads as
 *   "nothing matched", not an error toast.
 * - 404 → [FictionResult.NotFound].
 * - 429 → [FictionResult.RateLimited].
 * - Network / IO errors → [FictionResult.NetworkError] with `cause`.
 * - JSON parse failures → `NetworkError` preserving the original
 *   SerializationException as `cause` for debug-overlay surfacing.
 */
@Singleton
internal class LibriVoxApi @Inject constructor(
    private val client: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /**
     * Catalog listing, paginated via `offset` / `limit`. Books are
     * returned in the API's default order (roughly by id). Sections are
     * NOT included — call [byId] to hydrate a single book's chapters.
     */
    suspend fun list(
        offset: Int = 0,
        limit: Int = PAGE_SIZE,
    ): FictionResult<List<LibriVoxBook>> {
        val url = "$BASE_URL/api/feed/audiobooks/"
            .toHttpUrl().newBuilder()
            .addQueryParameter("format", "json")
            .addQueryParameter("limit", limit.coerceIn(1, 100).toString())
            .addQueryParameter("offset", offset.coerceAtLeast(0).toString())
            .build()
            .toString()
        return getBooks(url)
    }

    /**
     * Title / author search. At least one of [title] / [author] should
     * be non-blank; if both are blank this falls back to [list] so the
     * Search tab isn't blank-on-arrival.
     *
     * LibriVox does a substring match on `title` / `author`; we don't
     * anchor with `^` so "monte" matches "Count of Monte Cristo".
     * Sections are NOT included — same lazy-hydrate contract as [list].
     */
    suspend fun search(
        title: String? = null,
        author: String? = null,
        limit: Int = PAGE_SIZE,
    ): FictionResult<List<LibriVoxBook>> {
        val t = title?.trim()?.takeIf { it.isNotBlank() }
        val a = author?.trim()?.takeIf { it.isNotBlank() }
        if (t == null && a == null) {
            return list(offset = 0, limit = limit)
        }
        val url = "$BASE_URL/api/feed/audiobooks/"
            .toHttpUrl().newBuilder()
            .apply {
                t?.let { addQueryParameter("title", it) }
                a?.let { addQueryParameter("author", it) }
                addQueryParameter("format", "json")
                addQueryParameter("limit", limit.coerceIn(1, 100).toString())
            }
            .build()
            .toString()
        return getBooks(url)
    }

    /**
     * Fetch one book by its LibriVox id, with `extended=1` so the
     * `sections` array (per-chapter audio URLs) is populated. Returns
     * [FictionResult.NotFound] when the id matches no book.
     */
    suspend fun byId(bookId: String): FictionResult<LibriVoxBook> {
        val url = "$BASE_URL/api/feed/audiobooks/"
            .toHttpUrl().newBuilder()
            .addQueryParameter("id", bookId)
            .addQueryParameter("extended", "1")
            .addQueryParameter("format", "json")
            .build()
            .toString()
        return when (val result = getBooks(url)) {
            is FictionResult.Success ->
                result.value.firstOrNull()
                    ?.let { FictionResult.Success(it) }
                    ?: FictionResult.NotFound("LibriVox book not found: $bookId")
            is FictionResult.Failure -> result
        }
    }

    // ─── transport ────────────────────────────────────────────────────

    /**
     * Run the request, peel off the `{ "books": [...] }` envelope, and
     * special-case the `{"error":"…"}` no-results sentinel as an empty
     * success. The `coerceInputValues` JSON flag tolerates the API's
     * habit of returning `null` for fields our model types as strings
     * (e.g. `file_name`).
     */
    private fun getBooks(url: String): FictionResult<List<LibriVoxBook>> =
        when (val raw = doRequest(url)) {
            is FictionResult.Success -> try {
                // No-results sentinel: LibriVox returns a 200 with an
                // `error` field rather than an empty `books` array.
                val element = json.parseToJsonElement(raw.value)
                val obj = element as? kotlinx.serialization.json.JsonObject
                if (obj != null && obj.containsKey("error") && !obj.containsKey("books")) {
                    FictionResult.Success(emptyList())
                } else {
                    FictionResult.Success(
                        json.decodeFromString<LibriVoxFeed>(raw.value).books,
                    )
                }
            } catch (e: kotlinx.serialization.SerializationException) {
                FictionResult.NetworkError(
                    "LibriVox returned unexpected JSON shape",
                    e,
                )
            }
            is FictionResult.Failure -> raw
        }

    private fun doRequest(url: String): FictionResult<String> {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                // LibriVox / archive.org ask clients to identify
                // themselves; the repo URL doubles as the contact channel
                // and lets their ops contact abusive callers directly.
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                when {
                    resp.code == 404 ->
                        FictionResult.NotFound(
                            "LibriVox endpoint not found (HTTP 404)",
                        )
                    resp.code == 429 ->
                        FictionResult.RateLimited(
                            retryAfter = resp.header("Retry-After")
                                ?.toDoubleOrNull()
                                ?.let { ceil(it).toLong().seconds },
                            message = "LibriVox rate-limited the request",
                        )
                    !resp.isSuccessful ->
                        FictionResult.NetworkError(
                            "HTTP ${resp.code} from LibriVox",
                            IOException("HTTP ${resp.code}"),
                        )
                    else -> FictionResult.Success(text)
                }
            }
        } catch (e: IOException) {
            FictionResult.NetworkError(e.message ?: "fetch failed", e)
        }
    }

    companion object {
        const val BASE_URL: String = "https://librivox.org"

        /** Browse / search page size. LibriVox caps `limit` server-side;
         *  50 fills a phone list comfortably without an over-large
         *  response. */
        const val PAGE_SIZE: Int = 50

        const val USER_AGENT: String =
            "storyvox-librivox/1.0 (+https://github.com/techempower-org/storyvox)"
    }
}

/** Envelope wrapping the `books` array LibriVox returns. */
@Serializable
internal data class LibriVoxFeed(
    val books: List<LibriVoxBook> = emptyList(),
)

/**
 * Wire shape of one LibriVox audiobook record. Only the fields storyvox
 * surfaces are decoded; `ignoreUnknownKeys = true` tolerates the ~10
 * other URL/metadata fields the feed returns per book.
 *
 * `sections` is empty on the lean listing/search calls and populated
 * only on the `extended=1` single-book fetch — see [LibriVoxApi.byId].
 */
@Serializable
internal data class LibriVoxBook(
    val id: String = "",
    val title: String = "",
    val description: String = "",
    val language: String = "",
    @SerialName("copyright_year") val copyrightYear: String = "",
    @SerialName("num_sections") val numSections: String = "",
    @SerialName("url_librivox") val urlLibrivox: String = "",
    @SerialName("url_text_source") val urlTextSource: String = "",
    @SerialName("totaltimesecs") val totalTimeSecs: Long = 0,
    val authors: List<LibriVoxAuthor> = emptyList(),
    val sections: List<LibriVoxSection> = emptyList(),
) {
    /** "First Last" for the first credited author, or "Various" /
     *  "Unknown" fallbacks so the byline is never blank. LibriVox books
     *  with multiple authors (anthologies) collapse to the first; the
     *  rest surface in the description via the source mapper. */
    fun authorLabel(): String {
        if (authors.isEmpty()) return "Unknown author"
        val first = authors.first()
        val name = listOf(first.firstName, first.lastName)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        return name.ifBlank { "Unknown author" }
    }
}

/** Author sub-record. `dob`/`dod` and `id` are ignored. */
@Serializable
internal data class LibriVoxAuthor(
    @SerialName("first_name") val firstName: String = "",
    @SerialName("last_name") val lastName: String = "",
)

/**
 * One section (= one storyvox chapter). [listenUrl] is the archive.org
 * MP3 the audio-stream backend plays; [playtime] is the duration in
 * seconds (string in the wire, parsed lazily where needed).
 */
@Serializable
internal data class LibriVoxSection(
    val id: String = "",
    @SerialName("section_number") val sectionNumber: String = "",
    val title: String = "",
    @SerialName("listen_url") val listenUrl: String = "",
    val language: String = "",
    val playtime: String = "",
    val readers: List<LibriVoxReader> = emptyList(),
)

/** Reader (narrator) sub-record. Only the display name is surfaced. */
@Serializable
internal data class LibriVoxReader(
    @SerialName("display_name") val displayName: String = "",
)
