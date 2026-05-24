package `in`.jphe.storyvox.source.notion

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.RouteMatch
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.UrlMatcher
import `in`.jphe.storyvox.data.source.filter.FilterDimension
import `in`.jphe.storyvox.data.source.filter.FilterState
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.data.source.model.SearchOrder
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.data.source.plugin.SourceCategory
import `in`.jphe.storyvox.data.source.plugin.SourcePlugin
import `in`.jphe.storyvox.source.notion.config.NotionConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #770 — TechEmpower anonymous-mode Notion reader, split from the
 * old dual-mode [NotionSource]. Delegates entirely to
 * [AnonymousNotionDelegate] — no PAT-mode code paths at all.
 *
 * Surfaces TechEmpower guides, resources, and about pages via the
 * unofficial Notion API, zero configuration required.
 */
@SourcePlugin(
    id = SourceIds.NOTION_TECHEMPOWER,
    displayName = "TechEmpower",
    defaultEnabled = true,
    category = SourceCategory.Text,
    supportsFollow = false,
    supportsSearch = true,
    description = "TechEmpower guides, resources & about pages",
    sourceUrl = "https://www.notion.so",
)
@Singleton
internal class NotionTechEmpowerSource @Inject constructor(
    private val anonymous: AnonymousNotionDelegate,
    private val config: NotionConfig,
) : FictionSource, UrlMatcher {

    override val id: String = SourceIds.NOTION_TECHEMPOWER
    override val displayName: String = "TechEmpower"

    /** Issue #472 — `*.notion.so/<workspace>/<slug-with-32-hex-id>` or
     *  bare `notion.so/<32-hex-id>`. The pageId is the trailing 32-char
     *  hex blob with hyphens stripped. */
    override fun matchUrl(url: String): RouteMatch? {
        val m = NOTION_URL_PATTERN.matchEntire(url.trim()) ?: return null
        val pageId = m.groupValues[1].replace("-", "")
        return RouteMatch(
            sourceId = SourceIds.NOTION_TECHEMPOWER,
            fictionId = "${SourceIds.NOTION}:$pageId",
            confidence = 0.85f,
            label = "Notion page",
        )
    }

    override fun filterDimensions(): List<FilterDimension> = listOf(
        FilterDimension.Sort(
            options = listOf(
                FilterDimension.SortOption("relevance", "Default"),
                FilterDimension.SortOption("last_update", "Newest"),
                FilterDimension.SortOption("title", "Title"),
            ),
        ),
    )

    override fun applyFilters(base: SearchQuery, state: FilterState): SearchQuery {
        var q = base
        state.stringVal("sort")?.let { sortId ->
            q = q.copy(
                orderBy = when (sortId) {
                    "last_update" -> SearchOrder.LAST_UPDATE
                    "title" -> SearchOrder.TITLE
                    else -> SearchOrder.RELEVANCE
                },
            )
        }
        return q
    }

    // ─── browse ────────────────────────────────────────────────────────

    override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> {
        val state = config.current()
        return anonymous.popular(state, page)
    }

    override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> =
        popular(page)

    override suspend fun byGenre(
        genre: String,
        page: Int,
    ): FictionResult<ListPage<FictionSummary>> =
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    override suspend fun genres(): FictionResult<List<String>> =
        FictionResult.Success(emptyList())

    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> {
        val state = config.current()
        return anonymous.search(state, query.term)
    }

    // ─── detail ────────────────────────────────────────────────────────

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        val state = config.current()
        return anonymous.fictionDetail(state, fictionId)
    }

    override suspend fun chapter(
        fictionId: String,
        chapterId: String,
    ): FictionResult<ChapterContent> {
        val state = config.current()
        return anonymous.chapter(state, fictionId, chapterId)
    }

    // ─── auth-gated ─────────────────────────────────────────────────────

    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    override suspend fun setFollowed(
        fictionId: String,
        followed: Boolean,
    ): FictionResult<Unit> = FictionResult.Success(Unit)
}
