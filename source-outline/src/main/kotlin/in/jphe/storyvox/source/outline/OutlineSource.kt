package `in`.jphe.storyvox.source.outline

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
import `in`.jphe.storyvox.data.source.model.map
import `in`.jphe.storyvox.source.outline.net.OutlineApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #245 — Outline (self-hosted wiki) as a fiction backend.
 *
 *  - Collections → fictions (each collection = one fiction).
 *  - Documents → chapters (each document = one chapter).
 *  - Chapter body = the document's markdown text. The TTS pipeline
 *    treats it as plaintext (storyvox's HTML-strip handles markdown
 *    by stripping the few inline tags markdown-renderers also emit
 *    — `<em>`, `<strong>`, etc. — and leaves prose intact).
 *
 * Same architectural pattern as MemPalace (#79): user's own
 * self-hosted infra, host + API token in Settings, zero ToS surface
 * because the user authorized themselves.
 */
@SourcePlugin(
    id = SourceIds.OUTLINE,
    displayName = "Outline wiki",
    // #436 — fresh-install discoverability: chip on by default; the
    // backend stays inert until the user configures a host in Settings,
    // but the chip teaches users this surface exists.
    defaultEnabled = true,
    category = SourceCategory.Text,
    supportsFollow = false,
    supportsSearch = false,
    description = "Self-hosted Outline wiki · collections = fictions, documents = chapters",
    sourceUrl = "https://www.getoutline.com",
)
@Singleton
internal class OutlineSource @Inject constructor(
    private val api: OutlineApi,
    private val config: `in`.jphe.storyvox.source.outline.config.OutlineConfig,
) : FictionSource, `in`.jphe.storyvox.data.source.UrlMatcher {

    override val id: String = SourceIds.OUTLINE
    override val displayName: String = "Outline"

    /** Issue #472 — Outline doc URLs are `<configured-host>/doc/<slug>`.
     *  Returns null when no host is configured — the Readability
     *  catch-all (0.1) then takes the URL. */
    override fun matchUrl(url: String): `in`.jphe.storyvox.data.source.RouteMatch? {
        val host = runCatching {
            kotlinx.coroutines.runBlocking { config.current().host }
        }.getOrNull()?.trim()
            ?.removePrefix("https://")
            ?.removePrefix("http://")
            ?.trimEnd('/')
            ?: return null
        if (host.isBlank()) return null
        val pattern = Regex(
            """^https?://${Regex.escape(host)}/doc/([\w-]+)(?:[?#].*)?$""",
            RegexOption.IGNORE_CASE,
        )
        val m = pattern.matchEntire(url.trim()) ?: return null
        return `in`.jphe.storyvox.data.source.RouteMatch(
            sourceId = SourceIds.OUTLINE,
            fictionId = "${SourceIds.OUTLINE}:${m.groupValues[1]}",
            confidence = 0.85f,
            label = "Outline document",
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

    override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> =
        api.collections().map { cols ->
            ListPage(items = cols.map { it.toSummary() }, page = 1, hasNext = false)
        }

    override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> =
        popular(page) // Outline doesn't sort collections by recency; same listing.

    override suspend fun byGenre(
        genre: String,
        page: Int,
    ): FictionResult<ListPage<FictionSummary>> =
        // Outline collections don't have genres in any standardized form.
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    override suspend fun genres(): FictionResult<List<String>> =
        FictionResult.Success(emptyList())

    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> {
        val term = query.term.trim().lowercase()
        return api.collections().map { cols ->
            val filtered = if (term.isEmpty()) {
                cols.map { it.toSummary() }
            } else {
                cols.filter { it.name.lowercase().contains(term) }
                    .map { it.toSummary() }
            }
            val sorted = when (query.orderBy) {
                SearchOrder.TITLE -> filtered.sortedBy { it.title.lowercase() }
                SearchOrder.LAST_UPDATE ->
                    // The OutlineCollection API doesn't expose updatedAt
                    // to this layer (CollectionDto strips it before the
                    // summary mapper), so the closest stable proxy is
                    // collection id descending — newest collections in
                    // Outline get higher monotonic ids.
                    filtered.sortedByDescending { it.id }
                else -> filtered
            }
            ListPage(items = sorted, page = 1, hasNext = false)
        }
    }

    // ─── detail ────────────────────────────────────────────────────────

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        val collectionId = fictionId.removePrefix("outline:")
        val collection = when (val cs = api.collections()) {
            is FictionResult.Success -> cs.value.firstOrNull { it.id == collectionId }
                ?: return FictionResult.NotFound("Collection not found: $collectionId")
            is FictionResult.Failure -> return cs
        }
        val docs = when (val r = api.documents(collectionId)) {
            is FictionResult.Success -> r.value
            is FictionResult.Failure -> return r
        }
        val chapters = docs.mapIndexed { idx, doc ->
            ChapterInfo(
                id = chapterIdFor(fictionId, doc.id),
                sourceChapterId = doc.id,
                index = idx,
                title = doc.title,
                publishedAt = parseEpoch(doc.publishedAt ?: doc.updatedAt),
            )
        }
        val summary = FictionSummary(
            id = fictionId,
            sourceId = SourceIds.OUTLINE,
            title = collection.name,
            author = "",
            description = collection.description,
            status = FictionStatus.ONGOING,
            chapterCount = chapters.size,
        )
        return FictionResult.Success(FictionDetail(summary = summary, chapters = chapters))
    }

    override suspend fun chapter(
        fictionId: String,
        chapterId: String,
    ): FictionResult<ChapterContent> {
        val docId = chapterId.substringAfterLast("::").let { hash ->
            // chapterId encoded as `${fictionId}::${docId}`; the docId
            // is the raw Outline UUID after the double-colon. The hash
            // intermediary used by RSS isn't needed here — Outline
            // ids are already unique strings.
            chapterId.substringAfter("::", chapterId)
        }
        return when (val r = api.documentInfo(docId)) {
            is FictionResult.Success -> {
                val doc = r.value
                val info = ChapterInfo(
                    id = chapterId,
                    sourceChapterId = doc.id,
                    index = 0,
                    title = doc.title,
                    publishedAt = parseEpoch(doc.publishedAt ?: doc.updatedAt),
                )
                FictionResult.Success(
                    ChapterContent(
                        info = info,
                        htmlBody = doc.text,
                        plainBody = doc.text.stripMarkdown(),
                    ),
                )
            }
            is FictionResult.Failure -> r
        }
    }

    // ─── auth-gated ─────────────────────────────────────────────────────

    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        // Outline has no upstream "follows" concept — return the
        // user's collections list (matches MemPalace's pattern).
        popular(page)

    override suspend fun setFollowed(
        fictionId: String,
        followed: Boolean,
    ): FictionResult<Unit> = FictionResult.Success(Unit)
}

private fun `in`.jphe.storyvox.source.outline.net.OutlineCollection.toSummary(): FictionSummary =
    FictionSummary(
        id = "outline:$id",
        sourceId = SourceIds.OUTLINE,
        title = name,
        author = "",
        description = description,
        status = FictionStatus.ONGOING,
    )

/** Compose chapter id = `${fictionId}::${documentId}` so chapter
 *  lookups can recover the doc id without a separate map. */
private fun chapterIdFor(fictionId: String, docId: String): String =
    "${fictionId}::${docId}"

/** Strip the most common markdown-as-text artifacts so the engine
 *  doesn't read out `**` or `[link](url)` literally. The TTS pipeline
 *  applies further normalization downstream — this is just the
 *  cheapest pass that prevents obvious mispronunciation. */
private fun String.stripMarkdown(): String =
    replace(Regex("`{3}.*?`{3}", RegexOption.DOT_MATCHES_ALL), " ") // fenced code blocks
        .replace(Regex("`([^`]+)`"), "$1") // inline code
        .replace(Regex("!\\[([^\\]]*)\\]\\([^\\)]*\\)"), "$1") // images → alt text
        .replace(Regex("\\[([^\\]]+)\\]\\([^\\)]*\\)"), "$1") // links → label
        .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1") // bold
        .replace(Regex("\\*([^*]+)\\*"), "$1") // italic
        .replace(Regex("__([^_]+)__"), "$1")
        .replace(Regex("_([^_]+)_"), "$1")
        .replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "") // headings
        .replace(Regex("^>\\s+", RegexOption.MULTILINE), "") // blockquotes
        .replace(Regex("\\n{3,}"), "\n\n") // collapse runs
        .trim()

/** Outline timestamps are ISO 8601 (`2026-05-09T22:00:00.000Z`).
 *  Returns null on parse failure rather than throwing. */
private fun parseEpoch(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    return runCatching { java.time.Instant.parse(raw).toEpochMilli() }.getOrNull()
}
