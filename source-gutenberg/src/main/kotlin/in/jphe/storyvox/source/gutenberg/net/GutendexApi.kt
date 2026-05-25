package `in`.jphe.storyvox.source.gutenberg.net

import `in`.jphe.storyvox.data.source.model.FictionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #237 — minimal client for [Gutendex](https://gutendex.com/),
 * the community-maintained JSON wrapper over Project Gutenberg's
 * 70,000+ public-domain catalog.
 *
 * Two endpoints cover the storyvox surface:
 *  - `GET /books?...` — paginated catalog with search/topic/language
 *    filters. Drives [popular], [latestUpdates], [search].
 *  - `GET /books/{id}` — single-title detail (same shape as a list
 *    entry). Drives [fictionDetail] when we don't already have the row.
 *
 * No auth required. Gutendex honors PG's
 * [robot policy](https://www.gutenberg.org/policy/robot_access.html):
 * we send a polite User-Agent and lean on Gutendex's caching layer for
 * the catalog itself; only EPUB downloads hit gutenberg.org directly.
 */
@Singleton
internal class GutendexApi @Inject constructor(
    private val client: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /**
     * `GET /books?page=N&sort=popular` — default Gutendex sort is by
     * `download_count desc` which maps cleanly to storyvox's "Popular"
     * tab. 32 results per page is Gutendex's hardcoded page size.
     */
    suspend fun popular(page: Int): FictionResult<GutendexListPage> =
        get(booksPath(sort = "popular", page = page))

    /**
     * `GET /books?sort=descending` — sorts by Gutenberg id descending,
     * which is the closest the catalog gets to "newest". PG ingests
     * roughly in chronological order so the highest ids are recent
     * additions. Not a true publish date, but the right proxy for
     * "what's been added lately".
     */
    suspend fun latestUpdates(page: Int): FictionResult<GutendexListPage> =
        get(booksPath(sort = "descending", page = page))

    /**
     * `GET /books?search=...` — Gutendex matches against title +
     * authors. The query is URL-encoded so search terms with spaces
     * land verbatim.
     */
    suspend fun search(term: String, page: Int): FictionResult<GutendexListPage> =
        get(booksPath(search = term, page = page))

    /**
     * Composite `/books` query — wraps every Gutendex filter axis the
     * storyvox Browse sheet exposes (search, topic, languages, sort).
     * `null`/blank/empty params drop out of the URL so the legacy
     * single-axis call shapes (popular / latestUpdates / term-only
     * search) round-trip identically.
     *
     * Gutendex param reference: https://gutendex.com/
     *  - `search` matches title + authors (free-form)
     *  - `topic` matches against subjects + bookshelves (free-form)
     *  - `languages` comma-joined ISO 639-1 codes (e.g. "en,fr")
     *  - `sort` is one of popular | ascending | descending
     */
    suspend fun books(
        search: String? = null,
        topic: String? = null,
        languages: Set<String> = emptySet(),
        sort: String? = null,
        page: Int = 1,
    ): FictionResult<GutendexListPage> =
        get(booksPath(
            search = search,
            topic = topic,
            languages = languages,
            sort = sort,
            page = page,
        ))

    /**
     * `GET /books/{id}` — single-row detail. Returns the same shape
     * as a list entry; we wrap it in [GutendexListPage] so callers
     * can pass it through the same mapper.
     */
    suspend fun bookById(id: Int): FictionResult<GutendexBook> =
        getSingle("/books/$id")

    // ── transport ──────────────────────────────────────────────────

    private suspend fun get(path: String): FictionResult<GutendexListPage> =
        request(path) { body -> json.decodeFromString<GutendexListPage>(body) }

    private suspend fun getSingle(path: String): FictionResult<GutendexBook> =
        request(path) { body -> json.decodeFromString<GutendexBook>(body) }

    /**
     * Sync OkHttp `execute()` is wrapped in `withContext(Dispatchers.IO)`
     * because `suspend` alone doesn't move the call off the main thread —
     * it just makes the call pause-able. Without this hop, every Gutendex
     * fetch from the UI thread crashes with `NetworkOnMainThreadException`
     * (StrictMode catches the DNS lookup). Pattern mirrors `:source-outline`
     * and the rest of storyvox's network sources.
     */
    private suspend fun <T> request(
        path: String,
        parse: (String) -> T,
    ): FictionResult<T> = withContext(Dispatchers.IO) {
        val url = BASE_URL + path
        try {
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                when {
                    resp.code == 404 -> FictionResult.NotFound("Gutendex: $path not found")
                    !resp.isSuccessful -> FictionResult.NetworkError(
                        "HTTP ${resp.code} from $url",
                        IOException("HTTP ${resp.code}"),
                    )
                    else -> {
                        val text = resp.body?.string()
                            ?: return@withContext FictionResult.NetworkError("empty body", IOException("empty body"))
                        FictionResult.Success(parse(text))
                    }
                }
            }
        } catch (e: IOException) {
            FictionResult.NetworkError(e.message ?: "fetch failed", e)
        } catch (e: kotlinx.serialization.SerializationException) {
            FictionResult.NetworkError("Gutendex returned unexpected JSON shape", e)
        }
    }

    /**
     * Direct EPUB download from gutenberg.org — pulled out of the JSON
     * client because the response body is a binary zip, not JSON.
     * Returns the raw bytes for [in.jphe.storyvox.source.epub.parse.EpubParser]
     * to consume.
     */
    suspend fun downloadEpub(url: String): FictionResult<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/epub+zip")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                when {
                    resp.code == 404 -> FictionResult.NotFound("EPUB not available at $url")
                    !resp.isSuccessful -> FictionResult.NetworkError(
                        "HTTP ${resp.code} downloading EPUB",
                        IOException("HTTP ${resp.code}"),
                    )
                    else -> {
                        val bytes = resp.body?.bytes()
                            ?: return@withContext FictionResult.NetworkError(
                                "empty EPUB body",
                                IOException("empty body"),
                            )
                        FictionResult.Success(bytes)
                    }
                }
            }
        } catch (e: IOException) {
            FictionResult.NetworkError(e.message ?: "EPUB download failed", e)
        }
    }

    companion object {
        const val BASE_URL = "https://gutendex.com"

        /**
         * Identifies storyvox in the User-Agent per PG's robot policy
         * — the contact URL gives operators a way to reach us if our
         * traffic ever looks misbehaved.
         */
        const val USER_AGENT = "storyvox-gutenberg/1.0 (+https://github.com/jphein/storyvox)"

        /**
         * Build the `/books` query string with whichever params are
         * non-null/non-blank. Params emit in a deterministic order
         * (search → topic → languages → sort → page) so URL-shape
         * tests can pin exact strings without flake. Exposed
         * package-private for unit-test pinning.
         */
        internal fun booksPath(
            search: String? = null,
            topic: String? = null,
            languages: Set<String> = emptySet(),
            sort: String? = null,
            page: Int = 1,
        ): String {
            val params = mutableListOf<Pair<String, String>>()
            search?.takeIf { it.isNotBlank() }?.let { params += "search" to it }
            topic?.takeIf { it.isNotBlank() }?.let { params += "topic" to it }
            if (languages.isNotEmpty()) {
                params += "languages" to languages.toSortedSet().joinToString(",")
            }
            sort?.takeIf { it.isNotBlank() }?.let { params += "sort" to it }
            if (page > 1) {
                params += "page" to page.toString()
            }
            if (params.isEmpty()) return "/books"
            val qs = params.joinToString("&") { (k, v) ->
                "$k=" + java.net.URLEncoder.encode(v, Charsets.UTF_8)
            }
            return "/books?$qs"
        }
    }
}

// ── Wire types ──────────────────────────────────────────────────

@Serializable
internal data class GutendexListPage(
    val count: Int,
    val next: String? = null,
    val previous: String? = null,
    val results: List<GutendexBook>,
)

@Serializable
internal data class GutendexBook(
    val id: Int,
    val title: String,
    val authors: List<GutendexPerson> = emptyList(),
    val translators: List<GutendexPerson> = emptyList(),
    val subjects: List<String> = emptyList(),
    val bookshelves: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val copyright: Boolean? = null,
    @kotlinx.serialization.SerialName("media_type")
    val mediaType: String = "Text",
    /**
     * Map keyed by MIME type. Common keys we care about:
     *   - `"application/epub+zip"` — what we download
     *   - `"image/jpeg"` — cover (sometimes; not always present)
     *
     * Some entries include trailing `; charset=utf-8` or `.images`
     * suffix variants; we accept whatever EPUB URL Gutendex hands us.
     */
    val formats: Map<String, String> = emptyMap(),
    @kotlinx.serialization.SerialName("download_count")
    val downloadCount: Long = 0,
) {
    /**
     * Return the best EPUB download URL for this book. Gutendex
     * sometimes lists `application/epub+zip` and other times
     * `application/epub+zip; charset=utf-8` or variants with
     * `.images` / `.noimages` suffixes — accept any EPUB-typed entry.
     */
    fun epubUrl(): String? =
        formats.entries.firstOrNull { (k, _) -> k.startsWith("application/epub") }?.value

    /** First listed cover image — `image/jpeg` typically. Null if PG
     *  has no cover scan for this entry. */
    fun coverUrl(): String? =
        formats.entries.firstOrNull { (k, _) -> k.startsWith("image/") }?.value

    /** Comma-joined author display. Most entries have one author; a
     *  couple anthologies have multiple. PG formats authors as
     *  `"Last, First"` which we leave as-is — it reads correctly
     *  alphabetized in Library. */
    fun authorDisplay(): String =
        authors.joinToString(", ") { it.name }
}

@Serializable
internal data class GutendexPerson(
    val name: String,
    @kotlinx.serialization.SerialName("birth_year")
    val birthYear: Int? = null,
    @kotlinx.serialization.SerialName("death_year")
    val deathYear: Int? = null,
)
