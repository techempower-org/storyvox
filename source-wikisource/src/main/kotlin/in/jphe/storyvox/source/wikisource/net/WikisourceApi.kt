package `in`.jphe.storyvox.source.wikisource.net

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
 * Issue #376 — minimal Wikisource API client.
 *
 * Wikisource is a Wikimedia project and exposes the same API surface
 * as Wikipedia. We use two distinct surfaces:
 *
 *  - **MediaWiki Action API** (`/w/api.php`) for discovery — search,
 *    category enumeration, and subpage walking. Returns small JSON
 *    payloads that are cheap to fan out across the browse / detail
 *    flows.
 *  - **REST v1 `page/html`** (`/api/rest_v1/page/html/<title>`) for
 *    body content — Parsoid HTML with stable `<section
 *    data-mw-section-id="...">` boundaries we can split on when a work
 *    lives as a single page rather than as named subpages.
 *
 * Wikimedia REST + Action endpoints require a `User-Agent` per
 * https://meta.wikimedia.org/wiki/User-Agent_policy — anonymous traffic
 * without one is 403'd into a restrictive tier.
 *
 * All endpoints are GET, public, no auth. Errors surface as
 * [FictionResult.Failure] variants without leaking OkHttp/MediaWiki
 * specifics.
 */
@Singleton
internal class WikisourceApi @Inject constructor(
    private val client: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    // ─── browse landing ────────────────────────────────────────────────

    /**
     * MediaWiki `list=categorymembers` for the curated browse landing.
     *
     * `Category:Validated_texts` is the Wikisource quality tier where
     * every page has been double-proofread by two distinct editors. It's
     * the closest analogue to Wikipedia's "Featured Articles" for our
     * narration use case — these are the texts that read cleanly without
     * scanno garbage, missing chapters, or half-transcribed pages.
     *
     * `cmtype=page` filters to mainspace pages only (Wikisource has many
     * namespaces — Page:, Index:, Author:, etc. — and the per-page
     * facsimile pages aren't narratable). `cmlimit=50` matches Wikipedia's
     * featured-feed payload size; a single page works fine for the
     * non-paginated landing.
     */
    suspend fun categoryMembers(
        category: String = "Category:Validated_texts",
        limit: Int = 50,
    ): FictionResult<List<WikisourceCategoryMember>> {
        val url = BASE_URL +
            "/w/api.php?action=query" +
            "&list=categorymembers" +
            "&cmtitle=" + URLEncoder.encode(category, "UTF-8") +
            "&cmtype=page" +
            "&cmlimit=$limit" +
            "&format=json"
        return getJson<WikisourceCategoryQueryResponse>(url).let { res ->
            when (res) {
                is FictionResult.Success ->
                    FictionResult.Success(res.value.query?.categorymembers.orEmpty())
                is FictionResult.Failure -> res
            }
        }
    }

    // ─── search ────────────────────────────────────────────────────────

    /**
     * MediaWiki `list=search` against mainspace (`srnamespace=0`).
     *
     * Wikisource's body content lives in mainspace; the per-page
     * facsimile work happens in `Page:` and `Index:` namespaces that
     * are not narratable as audiobooks (they're transcription scaffolds
     * with image references). Restricting to ns0 gives us the parent
     * work pages users actually want to listen to.
     */
    suspend fun search(
        term: String,
        sort: String = "relevance",
        limit: Int = 20,
    ): FictionResult<List<WikisourceSearchHit>> {
        val q = term.trim()
        if (q.isEmpty()) return FictionResult.Success(emptyList())
        val url = BASE_URL +
            "/w/api.php?action=query" +
            "&list=search" +
            "&srsearch=" + URLEncoder.encode(q, "UTF-8") +
            "&srnamespace=0" +
            "&srlimit=$limit" +
            "&srsort=$sort" +
            "&format=json"
        return getJson<WikisourceSearchQueryResponse>(url).let { res ->
            when (res) {
                is FictionResult.Success ->
                    FictionResult.Success(res.value.query?.search.orEmpty())
                is FictionResult.Failure -> res
            }
        }
    }

    // ─── subpage discovery ─────────────────────────────────────────────

    /**
     * Walk the subpages of a Wikisource work via `list=allpages` with
     * a title prefix. Wikisource convention is to put a multi-volume
     * work at `War_and_Peace` with chapters at `War_and_Peace/Book_One`,
     * `War_and_Peace/Book_Two`, etc.
     *
     * MediaWiki `list=allpages` returns titles in **codepoint
     * (lexicographic) order**, which is NOT reading order for the
     * dominant en.wikisource convention of bare chapter numbers
     * (`/Chapter_1`, `/Chapter_10`, `/Chapter_2`) — lexicographic puts
     * `Chapter_10` before `Chapter_2`. We apply [naturalSubpageOrder]
     * here so both callers — index assignment in
     * `WikisourceSource.fictionDetail` and the index→title re-derivation
     * in `chapterFromSubpage` — consume the same numerically-sorted list
     * and can never disagree (issue #1060). Single-page works return an
     * empty list and the caller falls back to heading-based splitting on
     * the parent page.
     *
     * `apprefix` parameter expects the prefix relative to the
     * namespace; we pass the underscored title followed by `/`.
     */
    suspend fun subpages(parentTitle: String, limit: Int = 200): FictionResult<List<String>> {
        val prefix = parentTitle.replace(' ', '_') + "/"
        val url = BASE_URL +
            "/w/api.php?action=query" +
            "&list=allpages" +
            "&apprefix=" + URLEncoder.encode(prefix, "UTF-8") +
            "&apnamespace=0" +
            "&aplimit=$limit" +
            "&format=json"
        return getJson<WikisourceAllPagesResponse>(url).let { res ->
            when (res) {
                is FictionResult.Success ->
                    FictionResult.Success(naturalSubpageOrder(res.value.query?.allpages.orEmpty().map { it.title }))
                is FictionResult.Failure -> res
            }
        }
    }

    // ─── page body ─────────────────────────────────────────────────────

    /**
     * Parsoid HTML for a page body. Returns the raw HTML string — the
     * source layer either slices it on `<section data-mw-section-id>`
     * boundaries (single-page works) or treats the whole page as one
     * chapter's body (per-subpage works where each subpage IS a
     * chapter).
     */
    suspend fun pageHtml(title: String): FictionResult<String> {
        val encoded = URLEncoder.encode(title, "UTF-8").replace("+", "%20")
        val url = BASE_URL + "/api/rest_v1/page/html/$encoded"
        return getRaw(url)
    }

    /**
     * REST page summary — used to pull a description / thumbnail for
     * the FictionDetail preview card. Wikisource's summary endpoint
     * mirrors Wikipedia's shape; failures degrade to a synthesized
     * blank summary in the caller, so we don't fail detail fetches
     * when the upstream omits it.
     */
    suspend fun summary(title: String): FictionResult<WikisourceSummary> {
        val encoded = URLEncoder.encode(title, "UTF-8").replace("+", "%20")
        val url = BASE_URL + "/api/rest_v1/page/summary/$encoded"
        return getJson<WikisourceSummary>(url)
    }

    // ─── transport ────────────────────────────────────────────────────

    private suspend inline fun <reified T> getJson(url: String): FictionResult<T> =
        when (val raw = getRaw(url)) {
            is FictionResult.Success -> try {
                FictionResult.Success(json.decodeFromString<T>(raw.value))
            } catch (e: kotlinx.serialization.SerializationException) {
                FictionResult.NetworkError("Wikisource returned unexpected JSON shape", e)
            }
            is FictionResult.Failure -> raw
        }

    // #419 — `execute()` is blocking; without the suspend + Dispatchers.IO
    // wrapper the whole method runs on whatever coroutine context called
    // it, which is the main thread in Compose. Crashed Browse → Wikisource
    // with NetworkOnMainThreadException on first chip tap.
    private suspend fun getRaw(url: String): FictionResult<String> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("Accept-Language", "en")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                when {
                    resp.code == 404 ->
                        FictionResult.NotFound("Wikisource page not found")
                    resp.code == 429 ->
                        FictionResult.RateLimited(
                            retryAfter = resp.header("Retry-After")
                                ?.toLongOrNull()
                                ?.seconds,
                            message = "Wikisource rate-limited the request",
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

    companion object {
        /** English Wikisource host. Per #376 scope, we ship en-only for
         *  v1; the multi-language story (de.wikisource.org, fr.wikisource.org,
         *  etc.) is a follow-up that mirrors Wikipedia's language picker.
         *  Keeping the host as a const avoids the WikipediaConfig
         *  DataStore plumbing for a single-language launch. */
        const val BASE_URL: String = "https://en.wikisource.org"

        /** Wikimedia User-Agent policy — identify the project + give a
         *  contact URL. Without this the REST API returns 403 for
         *  anonymous-tier traffic. */
        const val USER_AGENT: String =
            "storyvox-wikisource/1.0 (https://github.com/jphein/storyvox; jp@jphein.com)"
    }
}

// ─── chapter ordering ──────────────────────────────────────────────────

/**
 * Natural (numeric-aware) order for Wikisource subpage titles (#1060).
 *
 * MediaWiki `list=allpages` hands back codepoint order, which sequences
 * bare-numbered chapters wrong: `Chapter_1, Chapter_10, Chapter_11,
 * Chapter_2, …`. For works that zero-pad (`Chapter_01`) the lexicographic
 * order happens to be correct, but the dominant en.wikisource convention
 * is the bare form, so ≥10-chapter books play out of reading order.
 *
 * We sort on each subpage's **leaf name** (the segment after the last
 * `/`) so a parent title that itself ends in a number (e.g. `Henry_V`)
 * doesn't leak into the per-chapter ordinal. The sort key is:
 *
 *  1. numbered leaves first (those with a trailing integer run), ordered
 *     by that integer — this is the chapter ordinal;
 *  2. then prose-named leaves (no trailing number — `Preface`,
 *     `Appendix`), ordered lexicographically and grouped after the
 *     numbered ones so a stray `/Preface` can't land mid-book.
 *
 * The trailing-number rule targets the ordinal precisely: leaves are
 * shaped `Chapter_10`, `Part_2`, `Scene_12`, where the discriminating
 * integer is always at the end. The sort is **stable**, so equal keys
 * (and already-correct zero-padded works) keep their incoming order.
 *
 * Roman numerals and spelled-out numbers (`Chapter_XII`, `Book_One`)
 * fall into the prose-named bucket and sort lexicographically — a known
 * follow-up, called out in the issue, not regressed by this change.
 */
internal fun naturalSubpageOrder(titles: List<String>): List<String> {
    val trailingNumber = Regex("""(\d+)\s*$""")
    return titles.sortedWith(
        compareBy(
            // Stable partition: numbered leaves (key 0) before prose (key 1).
            { title ->
                val leaf = title.substringAfterLast('/')
                if (trailingNumber.containsMatchIn(leaf)) 0 else 1
            },
            // Numbered leaves order by the trailing integer; prose leaves
            // share Long.MAX_VALUE here and fall through to the tiebreak.
            { title ->
                val leaf = title.substringAfterLast('/')
                trailingNumber.find(leaf)?.groupValues?.get(1)?.toLongOrNull() ?: Long.MAX_VALUE
            },
            // Lexicographic tiebreak — orders the prose bucket and keeps
            // the whole sort deterministic for duplicate numeric keys.
            { it },
        ),
    )
}

// ─── wire types ───────────────────────────────────────────────────────

@Serializable
internal data class WikisourceCategoryQueryResponse(
    val query: WikisourceCategoryQuery? = null,
)

@Serializable
internal data class WikisourceCategoryQuery(
    val categorymembers: List<WikisourceCategoryMember> = emptyList(),
)

@Serializable
internal data class WikisourceCategoryMember(
    val pageid: Long? = null,
    val ns: Int? = null,
    val title: String,
)

@Serializable
internal data class WikisourceSearchQueryResponse(
    val query: WikisourceSearchQuery? = null,
)

@Serializable
internal data class WikisourceSearchQuery(
    val search: List<WikisourceSearchHit> = emptyList(),
)

@Serializable
internal data class WikisourceSearchHit(
    val title: String,
    val pageid: Long? = null,
    val snippet: String = "",
    val size: Int? = null,
    val wordcount: Int? = null,
)

@Serializable
internal data class WikisourceAllPagesResponse(
    val query: WikisourceAllPagesQuery? = null,
)

@Serializable
internal data class WikisourceAllPagesQuery(
    val allpages: List<WikisourceAllPagesEntry> = emptyList(),
)

@Serializable
internal data class WikisourceAllPagesEntry(
    val pageid: Long? = null,
    val ns: Int? = null,
    val title: String,
)

@Serializable
internal data class WikisourceSummary(
    val title: String,
    val description: String? = null,
    val extract: String? = null,
    val thumbnail: WikisourceThumbnail? = null,
    val titles: WikisourceTitles? = null,
)

@Serializable
internal data class WikisourceTitles(
    val canonical: String,
    val normalized: String? = null,
    val display: String? = null,
)

@Serializable
internal data class WikisourceThumbnail(
    val source: String,
    val width: Int? = null,
    val height: Int? = null,
)
