package `in`.jphe.storyvox.source.arxiv.net

import `in`.jphe.storyvox.data.source.model.FictionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Issue #378 — minimal arXiv API client. Talks to two surfaces:
 *
 *  - **Query API** (`export.arxiv.org/api/query`) — the public Atom-feed
 *    search/listing endpoint. We use it for the browse landing
 *    (category-filtered, sorted by submittedDate desc) and free-form
 *    search (`all:<term>`). Documented at
 *    https://info.arxiv.org/help/api/user-manual.html.
 *  - **Abstract page** (`arxiv.org/abs/<id>`) — the per-paper HTML
 *    landing page. We fetch this once per fictionDetail/chapter call
 *    and extract the title + authors + abstract + comments via
 *    [ArxivAbstractParser]. Skipping the full-PDF text-extraction is
 *    an explicit v1 trade-off (#378 acceptance) — the abstract is the
 *    "morning digest of new AI papers" use case.
 *
 * Both endpoints are GET, public, no auth, and have generous rate
 * limits (arXiv's published policy is "1 request every 3 seconds"
 * for the query API; storyvox's per-user fetch cadence is well under
 * that). User-Agent identifies the project per arXiv's request to
 * tag automated traffic.
 *
 * Failures surface as [FictionResult.Failure] without leaking
 * OkHttp / arXiv-specific exceptions.
 */
@Singleton
internal class ArxivApi @Inject constructor(
    private val client: OkHttpClient,
) {

    // ─── browse / search via the Atom query API ────────────────────────

    /**
     * Recent papers in a category, newest first. The browse landing
     * defaults to [DEFAULT_CATEGORY] (`cs.AI`) — JP's space. A category
     * picker is a v2 follow-up (#378 lists it explicitly out of scope).
     */
    suspend fun recent(
        category: String = DEFAULT_CATEGORY,
        start: Int = 0,
        maxResults: Int = DEFAULT_PAGE_SIZE,
    ): FictionResult<ArxivAtomFeed> {
        val url = composeRecentUrl(category, start, maxResults)
        return getAtom(url)
    }

    /**
     * Free-form search. Builds an `all:<term>` query — `all` matches the
     * paper's title, abstract, authors, and comments. We keep the
     * sort-order at submittedDate desc so the user sees the most recent
     * matches first; arXiv's relevance-sort isn't exposed via the
     * public API (only `submittedDate`, `lastUpdatedDate`, and
     * `relevance` with the latter known to be flaky).
     */
    suspend fun search(
        term: String,
        start: Int = 0,
        maxResults: Int = DEFAULT_PAGE_SIZE,
    ): FictionResult<ArxivAtomFeed> {
        val url = composeSearchUrl(term, start, maxResults)
        return getAtom(url)
    }

    /**
     * Fetch a single paper by its arXiv id via the `id_list` parameter.
     * Returns the same Atom-feed shape as the listing call but with
     * exactly one entry. Used by [fictionDetail] to pull the full
     * metadata if the abstract-page HTML parse comes up short.
     */
    suspend fun byId(arxivId: String): FictionResult<ArxivAtomFeed> {
        val url = "$QUERY_BASE?id_list=" + URLEncoder.encode(arxivId, "UTF-8")
        return getAtom(url)
    }

    // ─── per-paper abstract page (HTML) ─────────────────────────────────

    /**
     * Raw HTML of the arXiv abstract page (`arxiv.org/abs/<id>`). The
     * caller passes it through [ArxivAbstractParser.parse] to extract a
     * narrating-friendly chapter body. Skipping the PDF body itself is
     * the explicit v1 scope cut from #378.
     */
    suspend fun absPage(arxivId: String): FictionResult<String> {
        val encoded = URLEncoder.encode(arxivId, "UTF-8").replace("+", "%20")
        val url = "$ABS_BASE/$encoded"
        return getRaw(url)
    }

    // ─── transport ─────────────────────────────────────────────────────

    private suspend fun getAtom(url: String): FictionResult<ArxivAtomFeed> =
        when (val raw = getRaw(url)) {
            is FictionResult.Success -> try {
                FictionResult.Success(ArxivAtomFeed.parse(raw.value))
            } catch (e: Throwable) {
                FictionResult.NetworkError("arXiv returned unparseable Atom feed", e)
            }
            is FictionResult.Failure -> raw
        }

    /**
     * Issue #585 — synchronous OkHttp `execute()` MUST run off the main
     * thread. Pre-fix this method was a plain `fun`, and the suspend
     * call sites in [ArxivSource] inherited the caller's dispatcher
     * (`BrowseViewModel`'s `viewModelScope` is `Dispatchers.Main.immediate`).
     * Rapid source-chip cycling on the Z Flip 3 captured a fatal
     * `NetworkOnMainThreadException` from `client.newCall(req).execute()`
     * → `Dns.lookupHostByName` on the Activity thread, killing the
     * process with the system "app stopped" dialog (R5CRB0W66MK, 2/2
     * repros within ~60 s).
     *
     * The fix wraps the OkHttp call in [withContext]`(Dispatchers.IO)`
     * at the lowest-level transport seam so every public entry point
     * (`recent`, `search`, `byId`, `absPage`) is automatically protected
     * — no per-call audit needed, and the dispatcher pin doesn't leak
     * past the network boundary (the parser and result mapping still
     * run on the original dispatcher).
     */
    private suspend fun getRaw(url: String): FictionResult<String> =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("Accept", "application/atom+xml, text/html;q=0.9, */*;q=0.5")
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    when {
                        resp.code == 404 ->
                            FictionResult.NotFound("arXiv paper not found")
                        resp.code == 429 ->
                            FictionResult.RateLimited(
                                retryAfter = resp.header("Retry-After")
                                    ?.toLongOrNull()
                                    ?.seconds,
                                message = "arXiv rate-limited the request",
                            )
                        !resp.isSuccessful ->
                            FictionResult.NetworkError(
                                "HTTP ${resp.code} from $url",
                                IOException("HTTP ${resp.code}"),
                            )
                        else -> {
                            val text = resp.body?.string()
                                ?: return@use FictionResult.NetworkError(
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
        /** Default browse landing category. cs.AI per #378 — JP's space.
         *  Other categories ride alongside this; a v2 follow-up will
         *  surface a Settings-side picker so the user can swap defaults. */
        const val DEFAULT_CATEGORY: String = "cs.AI"

        /** Page size for the listing/search responses. arXiv accepts up
         *  to 2000 per call but recommends keeping it small for
         *  responsiveness; 50 matches their suggested default in the
         *  API user manual. */
        const val DEFAULT_PAGE_SIZE: Int = 50

        /** Atom-query endpoint host. arXiv directs automated clients to
         *  `export.arxiv.org` rather than the user-facing `arxiv.org`
         *  to keep CDN cache pressure off the read path. HTTPS is
         *  required — Android's default network-security-config blocks
         *  cleartext to every host except `palace.jphe.in`, so the
         *  pre-2026-05 `http://` value silently failed at the OS layer
         *  before OkHttp could even follow arXiv's 301→https redirect.
         *  Arxiv's HTTPS endpoint returns HTTP/2 200 directly. */
        const val QUERY_BASE: String = "https://export.arxiv.org/api/query"

        /** Per-paper abstract page host. */
        const val ABS_BASE: String = "https://arxiv.org/abs"

        /** arXiv requests an identifying User-Agent on automated traffic
         *  (see https://info.arxiv.org/help/robots.html). */
        const val USER_AGENT: String =
            "storyvox-arxiv/1.0 (https://github.com/techempower-org/storyvox; jp@jphein.com)"
    }
}

/**
 * Compose the URL for the "recent in category" browse landing call.
 * Pure function so unit tests can pin the query-string shape without
 * spinning up an OkHttp client.
 */
internal fun composeRecentUrl(
    category: String,
    start: Int = 0,
    maxResults: Int = ArxivApi.DEFAULT_PAGE_SIZE,
): String {
    val cat = category.trim().ifBlank { ArxivApi.DEFAULT_CATEGORY }
    val query = "cat:" + URLEncoder.encode(cat, "UTF-8")
    return ArxivApi.QUERY_BASE +
        "?search_query=" + query +
        "&sortBy=submittedDate&sortOrder=descending" +
        "&start=$start&max_results=$maxResults"
}

/**
 * Compose the URL for a free-form `all:<term>` search call. Pure
 * function for test pinning — see [ArxivApiUrlTest].
 */
internal fun composeSearchUrl(
    term: String,
    start: Int = 0,
    maxResults: Int = ArxivApi.DEFAULT_PAGE_SIZE,
): String {
    // `all:` matches title + abstract + authors + comments. Trim before
    // URL-encoding so a stray newline / leading space doesn't break the
    // query syntax on arXiv's side.
    val q = "all:" + URLEncoder.encode(term.trim(), "UTF-8")
    return ArxivApi.QUERY_BASE +
        "?search_query=" + q +
        "&sortBy=submittedDate&sortOrder=descending" +
        "&start=$start&max_results=$maxResults"
}
