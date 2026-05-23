package `in`.jphe.storyvox.source.royalroad

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.filter.FilterDimension
import `in`.jphe.storyvox.data.source.filter.FilterState
import `in`.jphe.storyvox.data.source.plugin.SourceCategory
import `in`.jphe.storyvox.data.source.plugin.SourcePlugin
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.ContentWarning
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionStatus
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.FictionType
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.data.source.model.SearchOrder
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.data.source.model.SortDirection
import `in`.jphe.storyvox.data.source.model.ChapterInfo
import `in`.jphe.storyvox.source.royalroad.model.RoyalRoadIds
import `in`.jphe.storyvox.source.royalroad.model.browseUrl
import `in`.jphe.storyvox.source.royalroad.model.chapterUrl
import `in`.jphe.storyvox.source.royalroad.model.fictionUrl
import `in`.jphe.storyvox.source.royalroad.net.CloudflareAwareFetcher
import `in`.jphe.storyvox.source.royalroad.net.FetchOutcome
import `in`.jphe.storyvox.source.royalroad.net.RateLimitedClient
import `in`.jphe.storyvox.source.royalroad.net.RoyalRoadCookieJar
import `in`.jphe.storyvox.source.royalroad.parser.BrowseParser
import `in`.jphe.storyvox.source.royalroad.parser.ChapterParser
import `in`.jphe.storyvox.source.royalroad.parser.FictionDetailParser
import `in`.jphe.storyvox.source.royalroad.parser.FollowsParser
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * v1 stub. The full RoyalRoadSource impl (with parsers, search, browse, follows)
 * is in `_unintegrated/` pending an integration pass that bridges Oneiros's
 * parser output types to Selene's [FictionSource] interface shapes.
 *
 * The auth/net infrastructure (rate-limited HTTP, Cloudflare-aware fetcher,
 * cookie jar, login WebView, honeypot filter) is wired and ready to be plumbed
 * back in. This stub returns Failure for every read so the build is green
 * end-to-end while the integration is finished separately.
 *
 * See `docs/superpowers/specs/2026-05-05-storyvox-design.md` §5 and
 * `scratch/dreamers/oneiros.md` for the full plan.
 */
@SourcePlugin(
    id = SourceIds.ROYAL_ROAD,
    displayName = "Royal Road",
    defaultEnabled = true,
    category = SourceCategory.Text,
    supportsFollow = true,
    supportsSearch = true,
    description = "Web fiction · cookie sign-in for Follows + premium chapters",
    sourceUrl = "https://www.royalroad.com",
)
@Singleton
class RoyalRoadSource @Inject internal constructor(
    private val fetcher: CloudflareAwareFetcher,
    @Suppress("unused") private val client: RateLimitedClient,
    @Suppress("unused") private val cookieJar: RoyalRoadCookieJar,
) : FictionSource {

    override val id: String = RoyalRoadIds.SOURCE_ID
    override val displayName: String = RoyalRoadIds.SOURCE_NAME
    // Issue #382 — RR is the canonical follow-supporting source today.
    // The push path: [setFollowed] POSTs to /fictions/setbookmark with
    // a fresh __RequestVerificationToken; pull is the periodic
    // /my/follows scrape from FollowsViewModel.refreshFollows.
    override val supportsFollow: Boolean = true

    override fun filterDimensions(): List<FilterDimension> = listOf(
        FilterDimension.Sort(
            options = listOf(
                FilterDimension.SortOption("popularity", "Popularity"),
                FilterDimension.SortOption("rating", "Rating"),
                FilterDimension.SortOption("last_update", "Last Updated"),
                FilterDimension.SortOption("release_date", "Release Date"),
                FilterDimension.SortOption("followers", "Followers"),
                FilterDimension.SortOption("length", "Length"),
                FilterDimension.SortOption("views", "Views"),
                FilterDimension.SortOption("title", "Title"),
            ),
        ),
        FilterDimension.TagSet(
            key = "tags",
            label = "Tags",
            options = KnownTagSlugs,
            allowExclude = true,
        ),
        FilterDimension.Select(
            key = "status",
            label = "Status",
            options = listOf("Ongoing", "Completed", "Hiatus", "Stub", "Dropped"),
        ),
        FilterDimension.Select(
            key = "type",
            label = "Type",
            options = listOf("All", "Original", "Fan Fiction"),
        ),
        FilterDimension.NumberRange(
            key = "pages",
            label = "Pages",
            min = 0f,
            max = 10000f,
            step = 100f,
            formatLabel = "pages",
        ),
        FilterDimension.NumberRange(
            key = "rating",
            label = "Rating",
            min = 0f,
            max = 5f,
            step = 0.5f,
        ),
        FilterDimension.TagSet(
            key = "warnings",
            label = "Content warnings",
            options = listOf(
                "Profanity", "Sexual Content", "Graphic Violence",
                "Sensitive Content", "AI Assisted", "AI Generated",
            ),
            allowExclude = true,
        ),
    )

    override fun applyFilters(base: SearchQuery, state: FilterState): SearchQuery {
        var q = base
        state.stringVal("sort")?.let { sortId ->
            q = q.copy(
                orderBy = when (sortId) {
                    "popularity" -> SearchOrder.POPULARITY
                    "rating" -> SearchOrder.RATING
                    "last_update" -> SearchOrder.LAST_UPDATE
                    "release_date" -> SearchOrder.RELEASE_DATE
                    "followers" -> SearchOrder.FOLLOWERS
                    "length" -> SearchOrder.LENGTH
                    "views" -> SearchOrder.VIEWS
                    "title" -> SearchOrder.TITLE
                    else -> SearchOrder.RELEVANCE
                },
            )
        }
        state.stringSetVal("tags")?.let { tags ->
            q = q.copy(tags = tags.included, excludeTags = tags.excluded)
        }
        state.stringVal("status")?.let { status ->
            val fs = when (status) {
                "Ongoing" -> FictionStatus.ONGOING
                "Completed" -> FictionStatus.COMPLETED
                "Hiatus" -> FictionStatus.HIATUS
                "Stub" -> FictionStatus.STUB
                "Dropped" -> FictionStatus.DROPPED
                else -> null
            }
            if (fs != null) q = q.copy(statuses = setOf(fs))
        }
        state.stringVal("type")?.let { type ->
            q = q.copy(
                type = when (type) {
                    "Original" -> FictionType.ORIGINAL
                    "Fan Fiction" -> FictionType.FAN_FICTION
                    else -> FictionType.ALL
                },
            )
        }
        state.rangeVal("pages")?.let { range ->
            q = q.copy(
                minPages = range.min?.toInt(),
                maxPages = range.max?.toInt(),
            )
        }
        state.rangeVal("rating")?.let { range ->
            q = q.copy(minRating = range.min, maxRating = range.max)
        }
        state.stringSetVal("warnings")?.let { warnings ->
            q = q.copy(
                requireWarnings = warnings.included.mapNotNull { warningFromLabel(it) }.toSet(),
                excludeWarnings = warnings.excluded.mapNotNull { warningFromLabel(it) }.toSet(),
            )
        }
        return q
    }

    override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> =
        fetchBrowsePage(browseUrl("/fictions/active-popular", page), page)

    override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> =
        fetchBrowsePage(browseUrl("/fictions/latest-updates", page), page)

    override suspend fun byGenre(genre: String, page: Int): FictionResult<ListPage<FictionSummary>> =
        fetchBrowsePage(
            "${RoyalRoadIds.BASE_URL}/fictions/search?tagsAdd=$genre" + if (page > 1) "&page=$page" else "",
            page,
        )

    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> =
        fetchBrowsePage(buildSearchUrl(query), query.page)

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> =
        when (val outcome = fetcher.fetchHtml(fictionUrl(fictionId))) {
            is FetchOutcome.Body -> runCatching { FictionDetailParser.parse(outcome.html, fictionId) }
                .fold(
                    { FictionResult.Success(it) },
                    { FictionResult.NetworkError(message = "Parser error: ${it.message}", cause = it) },
                )
            FetchOutcome.NotFound -> FictionResult.NotFound("Fiction $fictionId not found")
            is FetchOutcome.CloudflareChallenge -> FictionResult.Cloudflare(outcome.url)
            is FetchOutcome.RateLimited -> FictionResult.RateLimited(retryAfter = outcome.retryAfterSec.seconds)
            is FetchOutcome.HttpError -> FictionResult.NetworkError(
                message = "HTTP ${outcome.code}: ${outcome.message}",
            )
        }
    override suspend fun chapter(fictionId: String, chapterId: String): FictionResult<ChapterContent> =
        when (val outcome = fetcher.fetchHtml(chapterUrl(fictionId, chapterId))) {
            is FetchOutcome.Body -> runCatching {
                ChapterParser.parse(
                    html = outcome.html,
                    info = ChapterInfo(
                        id = chapterId,
                        sourceChapterId = chapterId,
                        index = 0,
                        title = "",
                    ),
                )
            }.fold(
                { FictionResult.Success(it) },
                { FictionResult.NetworkError(message = "Chapter parser error: ${it.message}", cause = it) },
            )
            FetchOutcome.NotFound -> FictionResult.NotFound("Chapter $chapterId not found")
            is FetchOutcome.CloudflareChallenge -> FictionResult.Cloudflare(outcome.url)
            is FetchOutcome.RateLimited -> FictionResult.RateLimited(retryAfter = outcome.retryAfterSec.seconds)
            is FetchOutcome.HttpError -> FictionResult.NetworkError(
                message = "HTTP ${outcome.code}: ${outcome.message}",
            )
        }
    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        // Royal Road's actual endpoint for the logged-in user's follows is
        // `/my/follows` (verified live 2026-05-05). Unauthed → 302 to
        // /account/login?ReturnUrl=/my/follows, which FollowsParser detects
        // and surfaces as AuthRequired.
        when (val outcome = fetcher.fetchHtml("${RoyalRoadIds.BASE_URL}/my/follows" + if (page > 1) "?page=$page" else "")) {
            is FetchOutcome.Body -> when (val res = FollowsParser.parse(outcome.html, outcome.finalUrl)) {
                is FollowsParser.FollowsResult.Ok -> FictionResult.Success(res.items)
                FollowsParser.FollowsResult.NotAuthenticated -> FictionResult.AuthRequired(
                    message = "Sign in to Royal Road to view your follows",
                )
            }
            FetchOutcome.NotFound -> FictionResult.NotFound("Follows page not found")
            is FetchOutcome.CloudflareChallenge -> FictionResult.Cloudflare(outcome.url)
            is FetchOutcome.RateLimited -> FictionResult.RateLimited(retryAfter = outcome.retryAfterSec.seconds)
            is FetchOutcome.HttpError -> when (outcome.code) {
                401, 403 -> FictionResult.AuthRequired(message = "Sign in to Royal Road to view your follows")
                else -> FictionResult.NetworkError(message = "HTTP ${outcome.code}: ${outcome.message}")
            }
        }

    override suspend fun setFollowed(fictionId: String, followed: Boolean): FictionResult<Unit> {
        // Two requests:
        // 1. GET the fiction page to harvest a fresh __RequestVerificationToken
        //    (RR's antiforgery cookie + token pair is per-session-per-page).
        // 2. POST /fictions/setbookmark/{id} with type=follow&mark=True|False
        //    and the token in the form body. Auth cookies attach automatically
        //    from the OkHttp jar.
        val pageHtml = when (val outcome = fetcher.fetchHtml(fictionUrl(fictionId))) {
            is FetchOutcome.Body -> outcome.html
            FetchOutcome.NotFound -> return FictionResult.NotFound("Fiction $fictionId not found")
            is FetchOutcome.CloudflareChallenge -> return FictionResult.Cloudflare(outcome.url)
            is FetchOutcome.RateLimited -> return FictionResult.RateLimited(retryAfter = outcome.retryAfterSec.seconds)
            is FetchOutcome.HttpError -> return FictionResult.NetworkError(message = "HTTP ${outcome.code}: ${outcome.message}")
        }
        val token = extractCsrfToken(pageHtml)
            ?: return FictionResult.AuthRequired(message = "Sign in to follow fictions")

        val body = okhttp3.FormBody.Builder()
            .add("type", "follow")
            .add("mark", if (followed) "True" else "False")
            .add("__RequestVerificationToken", token)
            .build()

        return runCatching {
            client.post(
                "${RoyalRoadIds.BASE_URL}/fictions/setbookmark/$fictionId",
                body,
                extraHeaders = mapOf("Referer" to fictionUrl(fictionId)),
            ).use { resp ->
                when {
                    resp.code == 401 || resp.code == 403 ->
                        FictionResult.AuthRequired(message = "Sign in to follow fictions")
                    resp.isSuccessful || resp.isRedirect ->
                        FictionResult.Success(Unit)
                    else ->
                        FictionResult.NetworkError(message = "HTTP ${resp.code}: ${resp.message}")
                }
            }
        }.getOrElse { FictionResult.NetworkError(message = "POST failed: ${it.message}", cause = it) }
    }

    /** Pulls the CSRF token from the bookmark-form hidden input on the fiction page. */
    private fun extractCsrfToken(html: String): String? {
        val doc = org.jsoup.Jsoup.parse(html)
        return doc.selectFirst("input[name=__RequestVerificationToken]")
            ?.attr("value")
            ?.takeIf { it.isNotBlank() }
    }
    override suspend fun genres(): FictionResult<List<String>> = FictionResult.Success(KnownTagSlugs)

    private suspend fun fetchBrowsePage(url: String, page: Int): FictionResult<ListPage<FictionSummary>> =
        when (val outcome = fetcher.fetchHtml(url)) {
            is FetchOutcome.Body -> runCatching { BrowseParser.parse(outcome.html, page) }
                .fold(
                    { FictionResult.Success(it) },
                    { FictionResult.NetworkError(message = "Parser error: ${it.message}", cause = it) },
                )
            FetchOutcome.NotFound -> FictionResult.NotFound("Page not found")
            is FetchOutcome.CloudflareChallenge -> FictionResult.Cloudflare(outcome.url)
            is FetchOutcome.RateLimited -> FictionResult.RateLimited(retryAfter = outcome.retryAfterSec.seconds)
            is FetchOutcome.HttpError -> FictionResult.NetworkError(
                message = "HTTP ${outcome.code}: ${outcome.message}",
            )
        }

    private fun <T> unimplemented(): FictionResult<T> =
        FictionResult.NetworkError(
            message = "RoyalRoadSource integration pending — see source-royalroad/_unintegrated/",
            cause = NotImplementedError("RoyalRoadSource v1 stub"),
        )

    /**
     * Build `/fictions/search` URL from a SearchQuery, encoding all RR-supported
     * filter parameters. Empty/default fields are omitted so the URL stays clean.
     *
     * Multi-value params (`tagsAdd`, `tagsRemove`, `status`) are repeated once
     * per element — RR's TomSelect form posts them that way.
     */
    private fun buildSearchUrl(query: SearchQuery): String {
        val builder = "${RoyalRoadIds.BASE_URL}/fictions/search".toHttpUrl().newBuilder()
        if (query.term.isNotBlank()) builder.addQueryParameter("title", query.term)
        // Genres + extra tags both go into tagsAdd — RR doesn't distinguish on the wire.
        (query.genres + query.tags).forEach { builder.addQueryParameter("tagsAdd", it) }
        query.excludeTags.forEach { builder.addQueryParameter("tagsRemove", it) }
        query.statuses.forEach { builder.addQueryParameter("status", it.toRrStatus()) }
        query.requireWarnings.forEach { builder.addQueryParameter("warning", it.toRrSlug()) }
        // RR's form has no separate "exclude warning" param; the negative chip uses the
        // `warningsRemove` field. We map it that way and let RR ignore it if unsupported.
        query.excludeWarnings.forEach { builder.addQueryParameter("warningsRemove", it.toRrSlug()) }
        query.minPages?.let { builder.addQueryParameter("minPages", it.toString()) }
        query.maxPages?.let { builder.addQueryParameter("maxPages", it.toString()) }
        query.minRating?.let { builder.addQueryParameter("minRating", it.toString()) }
        query.maxRating?.let { builder.addQueryParameter("maxRating", it.toString()) }
        if (query.type != FictionType.ALL) {
            builder.addQueryParameter("type", query.type.toRrSlug())
        }
        if (query.orderBy != SearchOrder.RELEVANCE) {
            builder.addQueryParameter("orderBy", query.orderBy.toRrSlug())
        }
        if (query.direction != SortDirection.DESC) {
            builder.addQueryParameter("dir", "asc")
        }
        if (query.page > 1) builder.addQueryParameter("page", query.page.toString())
        return builder.build().toString()
    }

    private companion object {
        /** RR's tag slug taxonomy — surfaces something for the genre picker until the live impl lands. */
        val KnownTagSlugs = listOf(
            "loop", "adventure", "fantasy", "mystery", "magic", "litrpg", "progression",
            "cultivation", "xianxia", "gamelit", "harem", "villainous_lead", "martial_arts",
            "mythos", "dungeon_core", "dystopia", "post_apocalyptic", "reincarnation",
            "time_travel", "wuxia", "super_heroes", "school_life", "slice_of_life",
            "psychological", "tragedy", "urban_fantasy", "low_fantasy", "high_fantasy",
            "gender_bender", "multiple_lead", "attractive_lead", "non-human_lead", "strong_lead",
            "weak_to_strong", "summoned_hero", "anti-hero_lead", "male_lead", "female_lead",
            "secret_identity", "space_opera", "steampunk", "historical", "mythical_beasts",
            "magical_realism", "contemporary", "satire", "tutorial", "xuanhuan",
        )
    }
}

private fun FictionStatus.toRrStatus(): String = when (this) {
    FictionStatus.ONGOING -> "ONGOING"
    FictionStatus.COMPLETED -> "COMPLETED"
    FictionStatus.HIATUS -> "HIATUS"
    FictionStatus.STUB -> "STUB"
    FictionStatus.DROPPED -> "DROPPED"
}

private fun ContentWarning.toRrSlug(): String = when (this) {
    ContentWarning.PROFANITY -> "profanity"
    ContentWarning.SEXUAL_CONTENT -> "sexuality"
    ContentWarning.GRAPHIC_VIOLENCE -> "graphic_violence"
    ContentWarning.SENSITIVE_CONTENT -> "sensitive"
    ContentWarning.AI_ASSISTED -> "ai_assisted"
    ContentWarning.AI_GENERATED -> "ai_generated"
}

private fun FictionType.toRrSlug(): String = when (this) {
    FictionType.ALL -> "ALL"
    FictionType.ORIGINAL -> "original"
    FictionType.FAN_FICTION -> "fanfiction"
}

private fun warningFromLabel(label: String): ContentWarning? = when (label) {
    "Profanity" -> ContentWarning.PROFANITY
    "Sexual Content" -> ContentWarning.SEXUAL_CONTENT
    "Graphic Violence" -> ContentWarning.GRAPHIC_VIOLENCE
    "Sensitive Content" -> ContentWarning.SENSITIVE_CONTENT
    "AI Assisted" -> ContentWarning.AI_ASSISTED
    "AI Generated" -> ContentWarning.AI_GENERATED
    else -> null
}

private fun SearchOrder.toRrSlug(): String = when (this) {
    SearchOrder.RELEVANCE -> "relevance"
    SearchOrder.POPULARITY -> "popularity"
    SearchOrder.RATING -> "rating"
    SearchOrder.LAST_UPDATE -> "last_update"
    SearchOrder.RELEASE_DATE -> "release_date"
    SearchOrder.FOLLOWERS -> "followers"
    SearchOrder.LENGTH -> "length"
    SearchOrder.VIEWS -> "views"
    SearchOrder.TITLE -> "title"
    SearchOrder.AUTHOR -> "author"
}
