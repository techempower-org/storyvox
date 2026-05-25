package `in`.jphe.storyvox.source.wikipedia.net

import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.source.wikipedia.config.WikipediaConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Issue #377 — minimal Wikipedia API client. Talks to two endpoints:
 *
 *  - **opensearch** (MediaWiki action API) — `?action=opensearch` —
 *    cheap title-completion search; returns up to 20 article titles
 *    matching the user's query. Used for the Search tab.
 *  - **REST v1 page/html** — `/api/rest_v1/page/html/<title>` — clean
 *    Parsoid HTML with stable `<section data-mw-section-id="...">`
 *    boundaries. Used to fetch the article body for chapter mapping.
 *  - **REST v1 page/summary** — `/api/rest_v1/page/summary/<title>` —
 *    abstract + thumbnail metadata. Used for the FictionDetail preview.
 *  - **REST v1 feed/featured** — `/api/rest_v1/feed/featured/<Y>/<M>/<D>` —
 *    Today's Featured Article + most-read of yesterday. Used for the
 *    Popular tab.
 *
 * Wikimedia REST API requires a `User-Agent` with project name + contact
 * URL per https://meta.wikimedia.org/wiki/User-Agent_policy — without
 * it Wikipedia 403s anonymous traffic into a restrictive tier.
 *
 * All endpoints are GET, public, no auth. Failures surface as
 * [FictionResult.Failure] variants without leaking OkHttp / MediaWiki-
 * specific exceptions.
 */
@Singleton
internal class WikipediaApi @Inject constructor(
    private val client: OkHttpClient,
    private val config: WikipediaConfig,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ─── opensearch ────────────────────────────────────────────────────

    /**
     * MediaWiki opensearch — `?action=opensearch&search=<q>&limit=20`.
     * Returns a heterogeneous JSON array shaped as
     * `[<input>, [titles], [descs], [urls]]`. We project that into a
     * list of [WikipediaSearchHit] rows.
     */
    suspend fun openSearch(term: String, limit: Int = 20): FictionResult<List<WikipediaSearchHit>> {
        val q = term.trim()
        if (q.isEmpty()) return FictionResult.Success(emptyList())
        val state = config.state.first()
        val url = state.baseUrl +
            "/w/api.php?action=opensearch" +
            "&search=" + URLEncoder.encode(q, "UTF-8") +
            "&limit=$limit&namespace=0&format=json"
        return getRaw(url).let { res ->
            when (res) {
                is FictionResult.Success -> runCatching {
                    val arr: JsonArray = json.parseToJsonElement(res.value).jsonArray
                    // [0] = echoed input, [1] = titles[], [2] = descs[], [3] = urls[]
                    val titles = arr.getOrNull(1)?.jsonArray ?: JsonArray(emptyList())
                    val descs = arr.getOrNull(2)?.jsonArray ?: JsonArray(emptyList())
                    val urls = arr.getOrNull(3)?.jsonArray ?: JsonArray(emptyList())
                    val hits = titles.mapIndexed { i, t ->
                        WikipediaSearchHit(
                            title = t.contentOrEmpty(),
                            description = descs.getOrNull(i)?.contentOrEmpty().orEmpty(),
                            url = urls.getOrNull(i)?.contentOrEmpty().orEmpty(),
                        )
                    }.filter { it.title.isNotBlank() }
                    FictionResult.Success<List<WikipediaSearchHit>>(hits)
                }.getOrElse { e ->
                    FictionResult.NetworkError(
                        "Wikipedia returned unexpected opensearch JSON shape",
                        e,
                    )
                }
                is FictionResult.Failure -> res
            }
        }
    }

    // ─── full-text search (sortable) ──────────────────────────────────

    /**
     * MediaWiki `list=search` (full-text) — supports the [srsort]
     * parameter (`relevance`, `last_edit_desc`, `last_edit_asc`,
     * `create_timestamp_desc`, etc.) that `opensearch` does not.
     *
     * Used by the source when the user picked a non-relevance sort
     * via the Browse filter sheet (#809). `opensearch` is cheaper for
     * the relevance default — it returns the same article titles as
     * full-text search but with title-completion shape — so we keep
     * it as the no-filter fast path.
     *
     * Restricted to mainspace (`srnamespace=0`) so user/talk pages
     * don't pollute the result list.
     */
    suspend fun fullTextSearch(
        term: String,
        sort: String = "relevance",
        limit: Int = 20,
    ): FictionResult<List<WikipediaSearchHit>> {
        val q = term.trim()
        if (q.isEmpty()) return FictionResult.Success(emptyList())
        val state = config.state.first()
        val url = state.baseUrl +
            "/w/api.php?action=query" +
            "&list=search" +
            "&srsearch=" + URLEncoder.encode(q, "UTF-8") +
            "&srnamespace=0" +
            "&srlimit=$limit" +
            "&srsort=$sort" +
            "&format=json"
        return getJson<WikipediaSearchQueryResponse>(url).let { res ->
            when (res) {
                is FictionResult.Success ->
                    FictionResult.Success(
                        res.value.query?.search.orEmpty().map { hit ->
                            WikipediaSearchHit(
                                title = hit.title,
                                // strip MediaWiki's <span class="searchmatch"> highlighting
                                description = hit.snippet.replace(Regex("<[^>]+>"), "").trim(),
                                url = "",
                            )
                        },
                    )
                is FictionResult.Failure -> res
            }
        }
    }

    // ─── featured / popular ───────────────────────────────────────────

    /**
     * Wikimedia REST featured feed — Today's Featured Article + the
     * "mostread" cluster. Drives the Popular tab. Date defaults to
     * yesterday (UTC) because the "mostread" cluster is the previous
     * day's traffic — today's isn't ranked yet — and "tfa" for the
     * current day rotates around UTC midnight, which can be a few
     * hours behind the user's local clock at the start of the day.
     */
    suspend fun featured(year: Int, month: Int, day: Int): FictionResult<WikipediaFeatured> {
        val state = config.state.first()
        val mm = month.toString().padStart(2, '0')
        val dd = day.toString().padStart(2, '0')
        val url = state.baseUrl + "/api/rest_v1/feed/featured/$year/$mm/$dd"
        return getJson<WikipediaFeatured>(url)
    }

    // ─── page summary ─────────────────────────────────────────────────

    /** Article abstract + thumbnail — feeds the FictionDetail preview card. */
    suspend fun summary(title: String): FictionResult<WikipediaSummary> {
        val state = config.state.first()
        val encoded = URLEncoder.encode(title, "UTF-8").replace("+", "%20")
        val url = state.baseUrl + "/api/rest_v1/page/summary/$encoded"
        return getJson<WikipediaSummary>(url)
    }

    // ─── article HTML ─────────────────────────────────────────────────

    /**
     * Parsoid HTML for the article body. Returns the raw HTML string —
     * the source layer slices it on `<section data-mw-section-id="...">`
     * boundaries and emits one chapter per top-level section.
     */
    suspend fun pageHtml(title: String): FictionResult<String> {
        val state = config.state.first()
        val encoded = URLEncoder.encode(title, "UTF-8").replace("+", "%20")
        val url = state.baseUrl + "/api/rest_v1/page/html/$encoded"
        return getRaw(url)
    }

    // ─── transport ────────────────────────────────────────────────────

    private suspend inline fun <reified T> getJson(url: String): FictionResult<T> =
        when (val raw = getRaw(url)) {
            is FictionResult.Success -> try {
                FictionResult.Success(json.decodeFromString<T>(raw.value))
            } catch (e: kotlinx.serialization.SerializationException) {
                FictionResult.NetworkError("Wikipedia returned unexpected JSON shape", e)
            }
            is FictionResult.Failure -> raw
        }

    private suspend fun getRaw(url: String): FictionResult<String> = withContext(Dispatchers.IO) {
        try {
            // Accept-Language mirrors the host's language code so any
            // edge-cached payload comes back in the user's chosen
            // Wikipedia rather than negotiating into a different one.
            //
            // #419 — `execute()` is a blocking OkHttp call; the whole
            // method MUST be off the main thread, hence the suspend
            // signature + `withContext(Dispatchers.IO)` wrapper. The
            // original non-suspend shape crashed Browse → Wikipedia
            // with NetworkOnMainThreadException on first chip tap.
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("Accept-Language", extractLanguageFromUrl(url))
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                when {
                    resp.code == 404 ->
                        FictionResult.NotFound("Wikipedia article not found")
                    resp.code == 429 ->
                        FictionResult.RateLimited(
                            retryAfter = resp.header("Retry-After")
                                ?.toLongOrNull()
                                ?.seconds,
                            message = "Wikipedia rate-limited the request",
                        )
                    !resp.isSuccessful ->
                        FictionResult.NetworkError(
                            "HTTP ${resp.code} from $url",
                            IOException("HTTP ${resp.code}"),
                        )
                    else -> {
                        val text = resp.body?.string()
                            ?: return@withContext FictionResult.NetworkError("empty body", IOException("empty body"))
                        FictionResult.Success(text)
                    }
                }
            }
        } catch (e: IOException) {
            FictionResult.NetworkError(e.message ?: "fetch failed", e)
        }
    }

    /** Pull the `<lang>` out of `https://<lang>.wikipedia.org/...` so
     *  the request can carry a matching `Accept-Language` header
     *  without re-suspending on [WikipediaConfig.state] for every
     *  request. Falls back to `en` on parse failure — same default
     *  as [WikipediaConfigState]. */
    private fun extractLanguageFromUrl(url: String): String {
        val host = url.substringAfter("://").substringBefore('/')
        val prefix = host.substringBefore(".wikipedia.org", missingDelimiterValue = "")
        return if (prefix.isNotBlank() && !prefix.contains('.')) prefix else "en"
    }

    companion object {
        /** Wikimedia User-Agent policy
         *  (https://meta.wikimedia.org/wiki/User-Agent_policy):
         *  identify the project + give a contact. Without this the
         *  REST API returns 403 for anonymous-tier traffic. */
        const val USER_AGENT: String =
            "storyvox-wikipedia/1.0 (https://github.com/jphein/storyvox; jp@jphein.com)"
    }
}

private fun JsonElement.contentOrEmpty(): String =
    runCatching { jsonPrimitive.contentOrNull }.getOrNull().orEmpty()

// ─── wire types ───────────────────────────────────────────────────────

@Serializable
internal data class WikipediaSearchHit(
    val title: String,
    val description: String = "",
    val url: String = "",
)

/**
 * Subset of the `/api/rest_v1/feed/featured/...` response we care
 * about. Wikimedia's full response also includes `news`, `image`,
 * `onthisday` etc. — those are out of scope for the Popular tab
 * (which surfaces TFA + the most-read cluster only).
 */
@Serializable
internal data class WikipediaFeatured(
    val tfa: WikipediaFeaturedArticle? = null,
    val mostread: WikipediaMostRead? = null,
)

@Serializable
internal data class WikipediaFeaturedArticle(
    val titles: WikipediaFeaturedTitles? = null,
    val extract: String? = null,
    val description: String? = null,
    val thumbnail: WikipediaThumbnail? = null,
)

@Serializable
internal data class WikipediaFeaturedTitles(
    val canonical: String,
    val normalized: String? = null,
    val display: String? = null,
)

@Serializable
internal data class WikipediaMostRead(
    val articles: List<WikipediaMostReadArticle> = emptyList(),
)

@Serializable
internal data class WikipediaMostReadArticle(
    val titles: WikipediaFeaturedTitles? = null,
    val extract: String? = null,
    val description: String? = null,
    val thumbnail: WikipediaThumbnail? = null,
    val views: Long? = null,
)

@Serializable
internal data class WikipediaThumbnail(
    val source: String,
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
internal data class WikipediaSummary(
    val title: String,
    val description: String? = null,
    val extract: String? = null,
    val thumbnail: WikipediaThumbnail? = null,
    val titles: WikipediaFeaturedTitles? = null,
)

@Serializable
internal data class WikipediaSearchQueryResponse(
    val query: WikipediaSearchQuery? = null,
)

@Serializable
internal data class WikipediaSearchQuery(
    val search: List<WikipediaFullTextHit> = emptyList(),
)

@Serializable
internal data class WikipediaFullTextHit(
    val title: String,
    val snippet: String = "",
)
