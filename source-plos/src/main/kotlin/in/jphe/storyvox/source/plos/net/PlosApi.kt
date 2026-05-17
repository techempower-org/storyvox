package `in`.jphe.storyvox.source.plos.net

import `in`.jphe.storyvox.data.source.model.FictionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Issue #380 — minimal PLOS API client.
 *
 * Talks to two endpoints:
 *
 *  - **Solr search** — `https://api.plos.org/search?q=...&wt=json` —
 *    drives both the Search tab (`q=<term>`) and the Popular tab
 *    (`q=*:*&fq=journal_key:PLOSONE&sort=publication_date+desc`).
 *    Solr is a tiered query API — pagination is `start` + `rows`
 *    against the result window.
 *  - **Article HTML page** — `journals.plos.org/<journal>/article?id=<doi>` —
 *    used to pull the article body for the chapter renderer.
 *
 * The Solr API is anonymous + rate-limited at a generous per-IP
 * threshold; we route every call through a single OkHttp client that
 * carries an identifying `User-Agent` (per #380 acceptance "be a
 * polite client"). PLOS doesn't publish a hard limit, but they
 * publicly suggest no more than 7 requests / minute / IP sustained
 * for bulk harvesting — well above what Browse can saturate.
 *
 * All endpoints are GET, public, no auth. Failures surface as
 * [FictionResult.Failure] variants without leaking OkHttp / Solr-
 * specific exceptions.
 */
@Singleton
internal class PlosApi @Inject constructor(
    private val client: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ─── search (Solr) ─────────────────────────────────────────────────

    /**
     * Recent articles in PLOS ONE — the Browse → Popular landing.
     * PLOS doesn't expose a "top stories" feed; sorting PLOS ONE
     * (their biggest journal by volume) by publication date desc
     * gives a stable + fresh listing.
     *
     * Filter shape (post-#664): `fq=journal:"PLOS ONE"`. The legacy
     * `journal_key:PLOSONE` field is no longer indexed — Solr returns
     * `numFound=0` for it, surfacing as a silent empty Browse tab. The
     * `journal` field carries the human-readable name and remains the
     * stable filter today.
     */
    suspend fun searchRecent(
        start: Int = 0,
        rows: Int = 50,
    ): FictionResult<PlosSearchResponse> {
        val url = "$BASE_SEARCH" +
            "?q=" + URLEncoder.encode("*:*", "UTF-8") +
            // Issue #664 — PLOS retired the `journal_key` field; queries
            // against it return numFound=0 now. Two fq's together:
            //  1. `journal:"PLOS ONE"` pins PLOS ONE (case-insensitive
            //     — Solr matches "PLoS ONE" as well; we keep the
            //     all-caps form in the query for human readability).
            //  2. `doc_type:full` drops the per-section fragment docs
            //     (`/title`, `/abstract`, `/body`, `/references`) that
            //     would otherwise surface as separate "fictions" in
            //     Browse — each real article is indexed N+1 times in
            //     PLOS's Solr (one full, one per section). Without this
            //     filter Browse → PLOS shows the parent article AND its
            //     5+ sub-docs as duplicate cards with cryptic DOI-path
            //     titles. ~3.4M total → ~330k full-article docs.
            // Curl-confirmed at
            //   https://api.plos.org/search?q=*:*&fq=journal:"PLOS ONE"&fq=doc_type:full
            "&fq=" + URLEncoder.encode("journal:\"PLOS ONE\"", "UTF-8") +
            "&fq=" + URLEncoder.encode("doc_type:full", "UTF-8") +
            "&sort=" + URLEncoder.encode("publication_date desc", "UTF-8") +
            "&start=$start" +
            "&rows=$rows" +
            "&fl=" + URLEncoder.encode(DEFAULT_FIELDS, "UTF-8") +
            "&wt=json"
        return getJson<PlosSearchResponse>(url)
    }

    /**
     * Free-form search. Term is passed through to Solr's `q` parameter
     * untouched (so Boolean operators like `AND` / `OR` and field-
     * scoped queries like `title:climate` work as documented in the
     * PLOS Search API spec). Results sort by relevance — caller's
     * `start`/`rows` paginate.
     */
    suspend fun searchQuery(
        term: String,
        start: Int = 0,
        rows: Int = 50,
    ): FictionResult<PlosSearchResponse> {
        val url = "$BASE_SEARCH" +
            "?q=" + URLEncoder.encode(term, "UTF-8") +
            "&start=$start" +
            "&rows=$rows" +
            "&fl=" + URLEncoder.encode(DEFAULT_FIELDS, "UTF-8") +
            "&wt=json"
        return getJson<PlosSearchResponse>(url)
    }

    /**
     * Cheap one-doc fetch for a known DOI — drives [FictionDetail].
     * Filtering on `id:<doi>` is the documented Solr way to pin a
     * single article; the Solr `id` field IS the DOI in PLOS's index.
     * The DOI string contains slashes and dots so we URL-encode the
     * whole value.
     */
    suspend fun fetchByDoi(doi: String): FictionResult<PlosSearchResponse> {
        val q = "id:\"$doi\""
        val url = "$BASE_SEARCH" +
            "?q=" + URLEncoder.encode(q, "UTF-8") +
            "&rows=1" +
            "&fl=" + URLEncoder.encode(DEFAULT_FIELDS, "UTF-8") +
            "&wt=json"
        return getJson<PlosSearchResponse>(url)
    }

    // ─── article HTML ─────────────────────────────────────────────────

    /**
     * Pull the article's HTML page. The caller composes the URL via
     * [PlosSource]'s `articleUrlForDoi`; we just GET it as text so the
     * source layer can slice the abstract + body sections.
     */
    suspend fun fetchArticleHtml(url: String): FictionResult<String> = getRaw(url)

    // ─── transport ────────────────────────────────────────────────────

    private suspend inline fun <reified T> getJson(url: String): FictionResult<T> =
        when (val raw = getRaw(url)) {
            is FictionResult.Success -> try {
                FictionResult.Success(json.decodeFromString<T>(raw.value))
            } catch (e: kotlinx.serialization.SerializationException) {
                FictionResult.NetworkError("PLOS returned unexpected JSON shape", e)
            }
            is FictionResult.Failure -> raw
        }

    /**
     * Issue #585 — sync OkHttp `execute()` on `Dispatchers.IO`. See
     * `ArxivApi.getRaw` kdoc for full context; same crash class.
     */
    private suspend fun getRaw(url: String): FictionResult<String> =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json, text/html, */*")
                    .header("Accept-Language", "en")
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    when {
                        resp.code == 404 ->
                            FictionResult.NotFound("PLOS article or query not found")
                        resp.code == 429 ->
                            FictionResult.RateLimited(
                                retryAfter = resp.header("Retry-After")
                                    ?.toLongOrNull()
                                    ?.seconds,
                                message = "PLOS rate-limited the request",
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
        /** PLOS Search API endpoint. Documented at
         *  https://api.plos.org/solr/search-fields/ — Solr-backed, anonymous
         *  GET, returns JSON with `wt=json`. */
        const val BASE_SEARCH: String = "https://api.plos.org/search"

        /** Subset of Solr fields we ask for in every search. Keeps the
         *  response payload small (PLOS articles have ~100 fields total,
         *  most of which we'd discard). `abstract` comes back as a
         *  single-element array which we flatten on the source side. */
        const val DEFAULT_FIELDS: String =
            "id,title,abstract,author_display,journal,publication_date"

        /** Polite-client User-Agent. PLOS doesn't strictly require it
         *  (anonymous traffic works without one), but their public docs
         *  ask harvesters to identify themselves so an op can reach out
         *  if a client misbehaves. */
        const val USER_AGENT: String =
            "storyvox-plos/1.0 (https://github.com/jphein/storyvox; jp@jphein.com)"
    }
}

// ─── wire types ───────────────────────────────────────────────────────

/**
 * Top-level Solr response shape. We keep only the `response` block
 * (where the docs live) — `responseHeader` carries echoed query
 * params and timing we don't need; `facet_counts` etc. only appear
 * on facet queries (we don't issue any in v1).
 */
@Serializable
internal data class PlosSearchResponse(
    val response: PlosResponseBlock = PlosResponseBlock(),
)

@Serializable
internal data class PlosResponseBlock(
    val numFound: Int = 0,
    val start: Int = 0,
    val docs: List<PlosArticleHit> = emptyList(),
)

/**
 * One article row from a Solr search response. PLOS exposes many
 * more fields (subject, financial_disclosure, copyright, ...) but
 * v1 only consumes the discovery essentials.
 *
 * The `abstract` field comes back as an array — Solr models the
 * abstract as a multi-valued string field even though every doc
 * has exactly one. We mirror that shape rather than trying to
 * collapse on deserialization (kotlinx-serialization's "string OR
 * array" is awkward; the source layer handles `.firstOrNull()`).
 */
@Serializable
internal data class PlosArticleHit(
    val id: String? = null,
    val title: String? = null,
    val abstract: List<String>? = null,
    @kotlinx.serialization.SerialName("author_display")
    val authorDisplay: List<String>? = null,
    val journal: String? = null,
    @kotlinx.serialization.SerialName("publication_date")
    val publicationDate: String? = null,
)
