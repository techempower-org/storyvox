package `in`.jphe.storyvox.source.standardebooks.net

import `in`.jphe.storyvox.data.source.model.FictionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #375 — minimal HTTP client + HTML scraper for
 * [Standard Ebooks](https://standardebooks.org/), the curated set of
 * ~900 public-domain classics with hand-polished typography and cover art.
 *
 * **Why scrape HTML instead of consuming the OPDS feed at
 * `/feeds/opds`** — Standard Ebooks gates its full OPDS feed behind
 * Patrons Circle membership (HTTP 401 with `WWW-Authenticate: Basic`
 * on every catalog feed except `/feeds/atom/new-releases`, which is
 * limited to the 15 most-recent releases and not paginable). The public
 * HTML listing at `/ebooks?view=list&per-page=48&page=N&sort=...&query=...`
 * is the same data, paginates correctly, supports search + sort + tag
 * filtering, and embeds `typeof="schema:Book" about="/ebooks/<slug>"`
 * microdata that makes regex extraction reliable.
 *
 * The HTML pages are stable schema.org-marked-up content intended for
 * machine readers; SE explicitly publishes Open Search descriptors and
 * the page is generated server-side with predictable RDFa attributes.
 * This is closer to "structured data via HTML" than "fragile screen
 * scraping". If SE ships a public OPDS feed in the future, swap this
 * over to a `<feed><entry>` parser without touching the source surface.
 *
 * Endpoints:
 *  - `GET /ebooks?view=list&per-page=48&page=N&sort=<key>&query=<q>&tags[]=<t>`
 *    — catalog listing. Drives [popular] / [latestUpdates] / [search] /
 *    [byGenre]. Returns HTML; we extract `<li typeof="schema:Book">`
 *    blocks.
 *  - `GET /ebooks/<author-slug>/<book-slug>` — per-book detail page.
 *    Used to extract the description for [bookDetail].
 *  - `GET /ebooks/<author-slug>/<book-slug>/downloads/<author-slug>_<book-slug>.epub`
 *    — binary EPUB download. Predictable URL pattern (confirmed against
 *    the Recommended-compatible-epub link in the new-releases Atom feed).
 *
 * Sends a polite User-Agent identifying as storyvox with a contact URL
 * so any rate-limit hits can be routed to a real maintainer.
 */
@Singleton
internal open class StandardEbooksApi @Inject constructor(
    private val client: OkHttpClient,
) {

    /** Base URL — `open` so JVM unit tests can point this at a
     *  MockWebServer without restructuring the call sites. */
    internal open val baseUrl: String = BASE_URL

    /** Popular = SE-side "Popularity (most → least)" sort. */
    suspend fun popular(page: Int): FictionResult<SeListPage> =
        getListing(page = page, sort = "popularity")

    /** New releases = SE-side default sort (release date desc). */
    suspend fun latestUpdates(page: Int): FictionResult<SeListPage> =
        getListing(page = page, sort = "default")

    /** Free-form search hits the same listing endpoint with `?query=`. */
    suspend fun search(term: String, page: Int): FictionResult<SeListPage> =
        getListing(page = page, sort = "default", query = term)

    /**
     * Subject-filtered listing. SE's `tags[]=` query param matches its
     * subject taxonomy (fiction, adventure, fantasy, horror, mystery,
     * science-fiction, childrens, …). Listing sorts by SE-default
     * (release date desc) which surfaces the most recently produced
     * titles in each subject — appropriate for a "browse by genre" lane.
     */
    suspend fun byTag(tag: String, page: Int): FictionResult<SeListPage> =
        getListing(page = page, sort = "default", tag = tag)

    /**
     * Fetch the per-book HTML page. Used to pull the long-form
     * description for the FictionDetail card — the listing entry only
     * carries title/author/tags/cover. Returns the raw HTML so the
     * caller can run the focused [parseBookDescription] extractor.
     */
    suspend fun bookPage(authorSlug: String, bookSlug: String): FictionResult<String> =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/ebooks/$authorSlug/$bookSlug"
            fetchString(url)
        }

    /**
     * Binary EPUB download — the "Recommended compatible epub" build,
     * which is the variant SE markets as "All devices and apps except
     * Kindles and Kobos" (i.e., the plain EPUB 3 file).
     *
     * Issue #735 — SE serves an HTML interstitial ("Your Download Has
     * Started!") page at the bare `.../<author>_<book>.epub` URL that
     * relies on a `<meta http-equiv="refresh">` to deliver the actual
     * binary. The interstitial is ~8KB of XHTML and parses as
     * "container.xml missing" downstream. Appending `?source=download`
     * (the query SE's own download buttons emit) bypasses the
     * interstitial and returns the EPUB binary directly. We also
     * sanity-check the local "PK" zip signature so any future regression
     * surfaces as an immediate, actionable error rather than confusing
     * the EPUB parser.
     */
    suspend fun downloadEpub(authorSlug: String, bookSlug: String): FictionResult<ByteArray> =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/ebooks/$authorSlug/$bookSlug/downloads/" +
                "${authorSlug}_${bookSlug}.epub?source=download"
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
                            if (!looksLikeZip(bytes)) {
                                return@withContext FictionResult.NetworkError(
                                    "Standard Ebooks returned non-EPUB content at $url" +
                                        " (got ${bytes.size}B, no PK header)",
                                    IOException("non-EPUB response"),
                                )
                            }
                            FictionResult.Success(bytes)
                        }
                    }
                }
            } catch (e: IOException) {
                FictionResult.NetworkError(e.message ?: "EPUB download failed", e)
            }
        }

    /** All zip files (and therefore all EPUBs) start with the four
     *  bytes `PK\x03\x04`. Lets us distinguish a real EPUB from SE's
     *  HTML interstitial without parsing the response. */
    private fun looksLikeZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 'P'.code.toByte() &&
            bytes[1] == 'K'.code.toByte() &&
            bytes[2] == 0x03.toByte() &&
            bytes[3] == 0x04.toByte()

    /**
     * One catalog page. Composes the query string then parses the
     * resulting HTML into [SeListPage]. The HTML pagination block uses
     * `<a rel="next">` to advertise the next page; we surface that as
     * [SeListPage.hasNext] so the BrowsePaginator drives infinite scroll
     * the same way Gutendex's `next` URL does.
     */
    private suspend fun getListing(
        page: Int,
        sort: String,
        query: String? = null,
        tag: String? = null,
    ): FictionResult<SeListPage> = withContext(Dispatchers.IO) {
        val params = buildList {
            add("view" to "list")
            // 48 is SE's largest server-supported page size; minimizes
            // per-page-fetch overhead during scroll.
            add("per-page" to "48")
            add("page" to page.toString())
            add("sort" to sort)
            if (!query.isNullOrBlank()) add("query" to query)
            if (!tag.isNullOrBlank()) add("tags[]" to tag)
        }.joinToString("&") { (k, v) ->
            val ek = java.net.URLEncoder.encode(k, Charsets.UTF_8)
            val ev = java.net.URLEncoder.encode(v, Charsets.UTF_8)
            "$ek=$ev"
        }
        val url = "$baseUrl/ebooks?$params"
        when (val r = fetchString(url)) {
            is FictionResult.Success -> {
                val parsed = SeHtmlParser.parseListPage(r.value)
                FictionResult.Success(parsed)
            }
            is FictionResult.Failure -> r
        }
    }

    /**
     * Sync OkHttp `execute()` wrapped in `withContext(Dispatchers.IO)`
     * — `suspend` alone doesn't hop off the main thread; without this
     * hop, every call from the UI thread crashes with
     * NetworkOnMainThreadException once StrictMode catches the DNS lookup.
     * Same pattern PR #371 backfilled into `:source-gutenberg`.
     */
    private fun fetchString(url: String): FictionResult<String> {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/xhtml+xml, text/html, */*")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                when {
                    resp.code == 404 -> FictionResult.NotFound("Standard Ebooks: $url not found")
                    !resp.isSuccessful -> FictionResult.NetworkError(
                        "HTTP ${resp.code} from $url",
                        IOException("HTTP ${resp.code}"),
                    )
                    else -> {
                        val text = resp.body?.string()
                            ?: return FictionResult.NetworkError(
                                "empty body",
                                IOException("empty body"),
                            )
                        FictionResult.Success(text)
                    }
                }
            }
        } catch (e: IOException) {
            FictionResult.NetworkError(e.message ?: "fetch failed", e)
        }
    }

    companion object {
        const val BASE_URL = "https://standardebooks.org"

        /**
         * Identifies storyvox to Standard Ebooks' operators. The contact
         * URL routes any rate-limit / abuse signal back to a real human
         * — same etiquette as the Gutenberg client (#237).
         */
        const val USER_AGENT = "storyvox-standardebooks/1.0 (+https://github.com/jphein/storyvox)"
    }
}

// ── Wire types ──────────────────────────────────────────────────────

/** One page of catalog results from `/ebooks?...`. */
internal data class SeListPage(
    val results: List<SeBookEntry>,
    /** True when the HTML pagination footer included `<a rel="next">`. */
    val hasNext: Boolean,
)

/**
 * One book row extracted from the HTML listing. Holds only the fields
 * a list entry exposes — description and chapter list come from the
 * per-book page + the downloaded EPUB.
 */
internal data class SeBookEntry(
    /** URL-path slug under `/ebooks/`, e.g. `dornford-yates`. */
    val authorSlug: String,
    /** Book slug under the author, e.g. `perishable-goods`. The
     *  `{authorSlug}/{bookSlug}` pair is the canonical SE identifier. */
    val bookSlug: String,
    val title: String,
    val author: String,
    val coverUrl: String?,
    /** Subject tags from the listing (`fiction`, `adventure`, …). */
    val tags: List<String>,
)
