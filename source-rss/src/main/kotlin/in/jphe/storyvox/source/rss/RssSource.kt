package `in`.jphe.storyvox.source.rss

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.filter.FilterDimension
import `in`.jphe.storyvox.data.source.filter.FilterState
import `in`.jphe.storyvox.data.source.plugin.SourceCategory
import `in`.jphe.storyvox.data.source.plugin.SourcePlugin
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.ChapterInfo
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionStatus
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.data.source.model.SearchOrder
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.source.rss.config.RssConfig
import `in`.jphe.storyvox.source.rss.config.RssSubscription
import `in`.jphe.storyvox.source.rss.net.FetchResult
import `in`.jphe.storyvox.source.rss.net.RssFetcher
import `in`.jphe.storyvox.source.rss.parse.ParseException
import `in`.jphe.storyvox.source.rss.parse.RssFeed
import `in`.jphe.storyvox.source.rss.parse.RssItem
import `in`.jphe.storyvox.source.rss.parse.RssParser
import `in`.jphe.storyvox.source.rss.templates.CraigslistTemplates
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #236 — RSS / Atom feeds as a fiction backend. Each
 * subscribed feed URL maps to one fiction; each `<item>` / `<entry>`
 * is one chapter.
 *
 * "Browse" surfaces in this source are the user's own subscription
 * list — there's no global catalog to crawl. `popular` and `latestUpdates`
 * both return the user's feeds (sorted by most-recent-item-first for
 * `latestUpdates`). `search` filters by title substring. `byGenre`
 * is a no-op (RSS feeds don't carry genre metadata in any consistent
 * way).
 *
 * Caching: this impl is stateless w.r.t. parsed content — every
 * call re-fetches and re-parses. The repository layer above caches
 * the chapter cards. For the small N (a few feeds × a few hundred
 * items each) the cost is negligible; if it becomes a concern, an
 * in-memory map keyed by fictionId + last fetch's ETag is the
 * follow-up.
 */
@SourcePlugin(
    id = SourceIds.RSS,
    displayName = "RSS / Atom feeds",
    defaultEnabled = true,
    category = SourceCategory.Text,
    supportsFollow = false,
    supportsSearch = false,
    description = "Any RSS/Atom feed · one feed = one fiction · items become chapters",
    sourceUrl = "",
)
@Singleton
internal class RssSource @Inject constructor(
    private val config: RssConfig,
    private val fetcher: RssFetcher,
) : FictionSource, `in`.jphe.storyvox.data.source.UrlMatcher {

    override val id: String = SourceIds.RSS
    override val displayName: String = "RSS"

    /** Issue #472 — RSS / Atom feed URLs. Matched by path/extension
     *  heuristics: `.rss`, `.atom`, `.xml`, or paths containing
     *  `/feed/` or `/rss/`. Confidence 0.7 — host+path matchers from
     *  other backends always win, but a URL that looks like a feed
     *  outranks the Readability fallback.
     *
     *  Issue #464 — also recognises `<region>.craigslist.org/...` URLs
     *  at the same 0.7 confidence. Craigslist URLs don't naturally
     *  carry `/feed/` or `?format=rss` in shared / pasted links, but
     *  every public Craigslist URL has an `?format=rss` variant; the
     *  RSS source is the only backend in storyvox that knows how to
     *  fetch one. The route label surfaces the region so the magic-
     *  link chooser modal reads `Craigslist (SF Bay Area)`. */
    override fun matchUrl(url: String): `in`.jphe.storyvox.data.source.RouteMatch? {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) return null
        val lower = trimmed.lowercase()
        val looksLikeFeed = lower.contains("/feed") ||
            lower.contains("/rss") ||
            lower.contains("/atom") ||
            lower.endsWith(".rss") ||
            lower.endsWith(".atom") ||
            lower.endsWith(".xml")

        // Craigslist match — host-based. Detected before the generic
        // feed-pattern path because a CL URL like
        // `https://sfbay.craigslist.org/search/zip` doesn't carry any
        // of the generic feed markers.
        val craigslistRegion = runCatching {
            val host = java.net.URI(trimmed).host ?: return@runCatching null
            CraigslistTemplates.regionFromHost(host)
        }.getOrNull()

        if (!looksLikeFeed && craigslistRegion == null) return null

        // Hash the URL so the fictionId is stable across re-paste.
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(trimmed.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)
        val label = if (craigslistRegion != null) {
            "Craigslist (${craigslistRegion.label})"
        } else {
            "RSS / Atom feed"
        }
        return `in`.jphe.storyvox.data.source.RouteMatch(
            sourceId = SourceIds.RSS,
            fictionId = "${SourceIds.RSS}:$hash",
            confidence = 0.7f,
            label = label,
        )
    }

    /**
     * Sort is the only honest dimension on a feed listing: the source
     * has no global catalog, no genre taxonomy, no dates on the
     * un-hydrated summary cards (those require a full per-feed
     * fetch — see [listSubscriptions]). DateRange was previously
     * declared here but dropped on the floor by both [applyFilters]
     * and [search] because the summary cards carry no date — surfacing
     * it as a filter chip lied to the user. Removed.
     */
    override fun filterDimensions(): List<FilterDimension> = listOf(
        FilterDimension.Sort(
            options = listOf(
                FilterDimension.SortOption("relevance", "Default"),
                FilterDimension.SortOption("title", "Title"),
            ),
        ),
    )

    override fun applyFilters(base: SearchQuery, state: FilterState): SearchQuery {
        var q = base
        state.stringVal("sort")?.let { sortId ->
            q = q.copy(
                orderBy = when (sortId) {
                    "title" -> SearchOrder.TITLE
                    else -> SearchOrder.RELEVANCE
                },
            )
        }
        return q
    }

    // ─── browse ────────────────────────────────────────────────────────

    override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> =
        listSubscriptions(sortByMostRecent = false)

    override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> =
        listSubscriptions(sortByMostRecent = true)

    override suspend fun byGenre(
        genre: String,
        page: Int,
    ): FictionResult<ListPage<FictionSummary>> =
        // RSS feeds don't carry genre metadata in a standardized way.
        // Surfacing nothing is more honest than fabricating.
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    override suspend fun genres(): FictionResult<List<String>> =
        FictionResult.Success(emptyList())

    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> {
        val term = query.term.trim().lowercase()
        val all = subscriptionSummaries()
        val filtered = if (term.isEmpty()) all
            else all.filter { it.title.lowercase().contains(term) }
        val sorted = applySort(filtered, query.orderBy)
        return FictionResult.Success(ListPage(items = sorted, page = 1, hasNext = false))
    }

    /**
     * Apply the user-selected sort to the (already term-filtered)
     * subscription list. Only TITLE is meaningful on the un-hydrated
     * summary cards — the source intentionally avoids fetching every
     * feed on a Browse-open (see [listSubscriptions]'s rationale), so
     * there's no per-feed date to honor a LAST_UPDATE sort against.
     * RELEVANCE and any unknown sort fall back to the snapshot order
     * the user configured in Settings, which is the right "default."
     */
    private fun applySort(
        items: List<FictionSummary>,
        order: SearchOrder,
    ): List<FictionSummary> = when (order) {
        SearchOrder.TITLE -> items.sortedBy { it.title.lowercase() }
        else -> items
    }

    private suspend fun listSubscriptions(
        sortByMostRecent: Boolean,
    ): FictionResult<ListPage<FictionSummary>> {
        val all = subscriptionSummaries()
        // We don't actually fetch every feed for this listing call —
        // it'd be a network storm on every Browse open. The summary
        // shows the URL until the user opens the fiction; the detail
        // call hydrates with parsed feed data. This matches GitHub's
        // pattern (the registry shows summaries; fictionDetail
        // hydrates from the repo).
        return FictionResult.Success(ListPage(items = all, page = 1, hasNext = false))
    }

    /**
     * Thin summaries for each subscription — title is the URL host
     * until [fictionDetail] hydrates with the real feed title. Avoids
     * fetching every feed on Browse open (would be a network storm).
     */
    private suspend fun subscriptionSummaries(): List<FictionSummary> =
        config.snapshot().map { sub ->
            FictionSummary(
                id = sub.fictionId,
                sourceId = SourceIds.RSS,
                title = displayLabelForUrl(sub.url),
                author = "",
                description = sub.url,
                status = FictionStatus.ONGOING,
            )
        }

    /** Best-effort short label for a feed URL — host + first path
     *  segment, e.g. `wanderinginn.com / podcast`. */
    private fun displayLabelForUrl(url: String): String {
        return runCatching {
            val u = java.net.URI(url)
            val host = u.host?.removePrefix("www.") ?: url
            host
        }.getOrDefault(url)
    }

    // ─── detail ────────────────────────────────────────────────────────

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        val sub = config.snapshot().firstOrNull { it.fictionId == fictionId }
            ?: return FictionResult.NotFound("Feed not subscribed: $fictionId")

        val feed = fetchAndParse(sub.url) ?: return FictionResult.NetworkError(
            "Failed to fetch feed", null,
        )

        // #236 instrumentation
        android.util.Log.i(
            "RssSource",
            "fictionDetail(fictionId=$fictionId) feed.items.size=${feed.items.size} " +
                "first 3 ids=${feed.items.take(3).joinToString { it.id.take(40) }}",
        )

        val chapters = feed.items.mapIndexed { idx, item -> item.toChapterInfo(idx, fictionId) }

        // Authors-of-feed = first item's author or feed-level author.
        // Falls back to URL host so the field isn't blank.
        val author = feed.author
            ?: feed.items.firstOrNull()?.author
            ?: displayLabelForUrl(sub.url)

        val summary = FictionSummary(
            id = fictionId,
            sourceId = SourceIds.RSS,
            title = feed.title.ifBlank { displayLabelForUrl(sub.url) },
            author = author,
            description = feed.description ?: feed.link,
            status = FictionStatus.ONGOING,
            chapterCount = chapters.size,
        )
        val lastUpdatedAt = feed.items.mapNotNull { it.publishedAtEpochMs }.maxOrNull()
        return FictionResult.Success(
            FictionDetail(summary = summary, chapters = chapters, lastUpdatedAt = lastUpdatedAt),
        )
    }

    override suspend fun chapter(
        fictionId: String,
        chapterId: String,
    ): FictionResult<ChapterContent> {
        val sub = config.snapshot().firstOrNull { it.fictionId == fictionId }
            ?: return FictionResult.NotFound("Feed not subscribed: $fictionId")

        val feed = fetchAndParse(sub.url) ?: return FictionResult.NetworkError(
            "Failed to fetch feed", null,
        )

        // #236 instrumentation: log every chapter request so we can see
        // which chapterId comes in vs which item gets matched. Surfaces
        // the routing bug Shawna reported (every tap played item 0).
        val candidates = feed.items.mapIndexed { i, it ->
            i to it.toChapterId(fictionId)
        }
        android.util.Log.i(
            "RssSource",
            "chapter(fictionId=$fictionId, chapterId=$chapterId) → " +
                "candidates=${candidates.joinToString { (i, cid) -> "$i:$cid" }}",
        )

        val (idx, item) = feed.items.withIndex()
            .firstOrNull { (_, it) -> it.toChapterId(fictionId) == chapterId }
            ?: return FictionResult.NotFound("Chapter not in feed: $chapterId")
        android.util.Log.i(
            "RssSource",
            "chapter resolved idx=$idx title=${item.title.take(50)}",
        )

        val info = item.toChapterInfo(idx, fictionId)
        return FictionResult.Success(
            ChapterContent(
                info = info,
                htmlBody = item.htmlBody,
                plainBody = item.htmlBody.stripHtml(),
            ),
        )
    }

    // ─── auth-gated ─────────────────────────────────────────────────────

    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        // RSS has no source-side concept of "follows" beyond the user's
        // own subscription list. Returning the subscriptions here
        // matches the user mental model: "follows = feeds I'm tracking."
        listSubscriptions(sortByMostRecent = true)

    override suspend fun setFollowed(
        fictionId: String,
        followed: Boolean,
    ): FictionResult<Unit> {
        // Toggling follow on RSS = toggling subscription. We do NOT
        // implement add-via-this-call (storyvox-side: addFeed is
        // explicit via Settings). Unfollowing removes the subscription.
        if (!followed) {
            config.removeFeed(fictionId)
        }
        return FictionResult.Success(Unit)
    }

    // ─── helpers ────────────────────────────────────────────────────────

    private suspend fun fetchAndParse(url: String): RssFeed? {
        val result = fetcher.fetch(url)
        if (result !is FictionResult.Success) return null
        val body = (result.value as? FetchResult.Body) ?: return null
        return try {
            RssParser.parse(body.xml)
        } catch (_: ParseException) {
            null
        }
    }
}

/**
 * Each item's storyvox-internal chapter id is the feed's fictionId
 * plus the item's stable id. This keeps chapter ids unique across
 * feeds even when two feeds happen to use the same `<guid>`.
 */
private fun RssItem.toChapterId(fictionId: String): String =
    "${fictionId}::${id.hashCode().toUInt().toString(16)}"

private fun RssItem.toChapterInfo(index: Int, fictionId: String): ChapterInfo {
    val cid = toChapterId(fictionId)
    return ChapterInfo(
        id = cid,
        sourceChapterId = id,
        index = index,
        title = title.ifBlank { "Chapter ${index + 1}" },
        publishedAt = publishedAtEpochMs,
    )
}

/** Naive HTML strip for the TTS plaintext. EngineStreamingSource
 *  applies further normalization downstream — this just removes
 *  tags and collapses whitespace so the engine doesn't speak
 *  `<p>...</p>`. */
private fun String.stripHtml(): String =
    replace(Regex("<[^>]+>"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
