package `in`.jphe.storyvox.source.notion

import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.ChapterInfo
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionStatus
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.source.notion.config.NotionConfigState
import `in`.jphe.storyvox.source.notion.config.NotionDefaults
import `in`.jphe.storyvox.source.notion.net.NotionChunkResponse
import `in`.jphe.storyvox.source.notion.net.NotionRecordMap
import `in`.jphe.storyvox.source.notion.net.NotionUnofficialApi
import `in`.jphe.storyvox.source.notion.net.block
import `in`.jphe.storyvox.source.notion.net.contentIds
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #393 / v0.5.25 — anonymous-mode read path with **four fictions**
 * for TechEmpower.
 *
 * Browse → Notion shows one tile per top-level section of the
 * techempower.org navigation:
 *
 *  1. **Guides** ([TechEmpowerFiction.PageList]) — 8 chapters, one per
 *     guide page (How to use, Free internet, EV incentives, EBT
 *     balance, EBT spending, Findhelp, Password manager, Free cell
 *     service). Each chapter renders the linked guide's Notion page.
 *  2. **Resources** ([TechEmpowerFiction.CollectionRows]) — N chapters
 *     (~80), one per row in the Resources database. Each chapter
 *     renders the row's underlying Notion page content.
 *  3. **About** ([TechEmpowerFiction.SinglePage]) — single chapter, the
 *     About page content.
 *  4. **Donate** ([TechEmpowerFiction.SinglePage]) — single chapter, the
 *     Donate page content.
 *
 * Fiction ids are stable strings ("guides", "resources", "about",
 * "donate") encoded into the FictionSummary id via [notionFictionId].
 * Generic (non-TechEmpower) public Notion pages still fall back to the
 * single-tile single-chapter "Contents" rendering so arbitrary
 * notion.site URLs remain readable.
 */
@Singleton
internal class AnonymousNotionDelegate @Inject constructor(
    private val api: NotionUnofficialApi,
) {

    /**
     * Browse listing. For TechEmpower's root: returns four tiles, one
     * per [NotionDefaults.techempowerFictions] entry. For any other
     * root page: returns the single-tile fallback that v0.5.25 shipped
     * (the configured root page becomes the one fiction).
     *
     * page > 1 returns empty — none of the fictions paginate at the
     * Browse layer; pagination happens inside the chapter list for
     * CollectionRows fictions instead.
     */
    suspend fun popular(
        state: NotionConfigState,
        page: Int,
    ): FictionResult<ListPage<FictionSummary>> {
        if (page > 1) {
            return FictionResult.Success(ListPage(items = emptyList(), page = page, hasNext = false))
        }
        val compactRoot = state.rootPageId.replace("-", "")
        return if (compactRoot == NotionDefaults.TECHEMPOWER_ROOT_PAGE_ID) {
            FictionResult.Success(
                ListPage(items = buildTechEmpowerTiles(state), page = 1, hasNext = false),
            )
        } else {
            buildGenericRootTile(state)
        }
    }

    /**
     * Fiction detail — chapter list for one of the four TechEmpower
     * fictions (or the generic single-fiction fallback). For
     * CollectionRows fictions (Resources) the chapter list comes from
     * a queryCollection call. For PageList fictions (Guides) the
     * chapter list is the hand-curated `chapters` field — no extra
     * HTTP round-trip needed. For SinglePage fictions (About, Donate)
     * the chapter list is a one-element list pointing at the page.
     */
    suspend fun fictionDetail(
        state: NotionConfigState,
        fictionId: String,
    ): FictionResult<FictionDetail> {
        val sectionId = decodeFictionId(fictionId)
            ?: return FictionResult.NotFound("Notion fiction id not recognized: $fictionId")
        val compactRoot = state.rootPageId.replace("-", "")
        if (compactRoot != NotionDefaults.TECHEMPOWER_ROOT_PAGE_ID) {
            // Generic root: only one fiction exists, and its sectionId
            // is the compact root page id itself.
            if (sectionId != compactRoot) {
                return FictionResult.NotFound("Notion fiction $fictionId is not the configured root")
            }
            return genericRootFictionDetail(state, fictionId)
        }
        val fiction = NotionDefaults.techempowerFictions.firstOrNull { it.id == sectionId }
            ?: return FictionResult.NotFound("TechEmpower section $sectionId not configured")
        // v0.5.66 banner wire-up — resolve the fiction's tile cover
        // alongside the chapter list so FictionDetail (which currently
        // re-fetches a summary) sees the same banner Browse already
        // rendered. One extra loadPageChunk on detail open; cached by
        // NotionUnofficialApi so chapter rendering won't pay it again.
        val cover = resolveCoverUrl(fiction.coverPageId)
        return when (fiction) {
            is TechEmpowerFiction.PageList -> pageListDetail(fictionId, fiction, cover)
            is TechEmpowerFiction.CollectionRows -> collectionDetail(fictionId, fiction, cover)
            is TechEmpowerFiction.SinglePage -> singlePageDetail(fictionId, fiction, cover)
        }
    }

    /**
     * Render one chapter. Each chapter's body comes from a single Notion
     * page — for PageList chapters the page is the curated guide id, for
     * CollectionRows chapters the page is the row id (Notion treats each
     * database row as a page), for SinglePage chapters the page is the
     * About / Donate page. So chapter rendering is uniform: resolve the
     * chapter id back to its Notion page id, loadPageChunk, render
     * body.
     */
    suspend fun chapter(
        state: NotionConfigState,
        fictionId: String,
        chapterId: String,
    ): FictionResult<ChapterContent> {
        val sectionId = decodeFictionId(fictionId)
            ?: return FictionResult.NotFound("Notion fiction id not recognized: $fictionId")
        val sourceChapterId = chapterId.substringAfter("::")
            .takeIf { it.isNotBlank() }
            ?: return FictionResult.NotFound("Notion chapter id not recognized: $chapterId")
        val compactRoot = state.rootPageId.replace("-", "")
        val resolution = if (compactRoot == NotionDefaults.TECHEMPOWER_ROOT_PAGE_ID) {
            resolveTechempowerChapter(sectionId, sourceChapterId)
        } else {
            ChapterResolution(title = "Contents", pageId = sectionId)
        }
        resolution ?: return FictionResult.NotFound(
            "Chapter $chapterId not found in fiction $fictionId",
        )
        val info = ChapterInfo(
            id = chapterId,
            sourceChapterId = sourceChapterId,
            index = 0,
            title = resolution.title,
        )
        return renderPageChapter(info, resolution.pageId)
    }

    /**
     * Search — anonymous tiles aren't a paged, queryable surface, so we
     * do an in-memory filter over the tiles we already built. Keeps the
     * source consistent with the FictionSource contract (returns
     * ListPage even on empty match).
     */
    suspend fun search(
        state: NotionConfigState,
        term: String,
    ): FictionResult<ListPage<FictionSummary>> {
        val popularResult = popular(state, page = 1)
        val all = when (popularResult) {
            is FictionResult.Success -> popularResult.value.items
            is FictionResult.Failure -> return popularResult
        }
        val termLc = term.trim().lowercase()
        val matches = if (termLc.isEmpty()) all else all.filter {
            it.title.lowercase().contains(termLc) ||
                it.description?.lowercase()?.contains(termLc) == true
        }
        return FictionResult.Success(
            ListPage(items = matches, page = 1, hasNext = false),
        )
    }

    // ─── tile builders ────────────────────────────────────────────────

    /**
     * Build the four TechEmpower tiles from [NotionDefaults], populating
     * each tile's [FictionSummary.coverUrl] from the referenced
     * [TechEmpowerFiction.coverPageId] (Notion's `format.page_cover`
     * banner, with the body-image fallback `readBodyImageUrl` provides).
     *
     * v0.5.66 banner wire-up — pre-v0.5.66 this returned monogram-only
     * tiles because `coverUrl` was hardcoded to null. JP authors banners
     * by hitting "Add cover" on each fiction's coverPageId; on next
     * Browse the URL surfaces here automatically.
     *
     * Performance: one `loadPageChunk(rootPageId)` round-trip fetches
     * the welcome page's recordMap, which (for TechEmpower) typically
     * contains the child page blocks for Guides / Resources / About /
     * Donate at the top level of `recordMap.block`. We try to resolve
     * each fiction's cover from that recordMap first (zero extra
     * round-trips). For any coverPageId that isn't in the welcome
     * chunk, we fan out remaining fetches in parallel via `async {}`,
     * capping Browse-grid latency at one extra round-trip wall-clock.
     *
     * If the welcome chunk fails outright we fall back to null covers
     * + monogram tiles (Browse stays usable; the BrandedCoverTile
     * second-tier fallback in :core-ui handles the empty state).
     */
    private suspend fun buildTechEmpowerTiles(
        state: NotionConfigState,
    ): List<FictionSummary> {
        val fictions = NotionDefaults.techempowerFictions
        // 1. Try to pull every cover from the welcome root's recordMap
        //    first. TechEmpower's welcome page is ~290KB and Notion's
        //    CDN serves it with reasonable TTL, so this round-trip is
        //    cheap relative to the alternative of N per-tile fetches.
        val rootRecordMap = when (val r = api.loadPageChunk(state.rootPageId)) {
            is FictionResult.Success -> r.value.recordMap
            is FictionResult.Failure -> null
        }
        // 2. For each fiction, prefer the cover already in
        //    rootRecordMap; track misses for a parallel fan-out.
        val covers = mutableMapOf<String, String?>()
        val misses = mutableListOf<TechEmpowerFiction>()
        for (fiction in fictions) {
            val coverPageId = fiction.coverPageId
            if (coverPageId == null) {
                covers[fiction.id] = null
                continue
            }
            val fromRoot = rootRecordMap?.let { rm ->
                extractCoverFromRecordMap(coverPageId, rm)
            }
            if (fromRoot != null) {
                covers[fiction.id] = fromRoot
            } else {
                misses.add(fiction)
            }
        }
        // 3. Fan-out the misses in parallel — bounded by the 4-fiction
        //    cap, so this is at most a 3-call parallel batch and the
        //    Browse-grid wall-clock stays at one extra HTTP round-trip
        //    beyond the welcome fetch.
        if (misses.isNotEmpty()) {
            val resolved = coroutineScope {
                misses.map { fiction ->
                    async { fiction.id to resolveCoverUrl(fiction.coverPageId) }
                }.awaitAll()
            }
            for ((id, url) in resolved) covers[id] = url
        }
        return fictions.map { fiction ->
            FictionSummary(
                id = notionFictionId(fiction.id),
                sourceId = SourceIds.NOTION_TECHEMPOWER,
                title = fiction.title,
                author = "TechEmpower",
                description = fiction.description,
                coverUrl = covers[fiction.id],
                tags = emptyList(),
                status = FictionStatus.ONGOING,
            )
        }
    }

    /**
     * Resolve one page id's cover URL. Same cascade as the generic
     * root: `format.page_cover` → first body image → null. Shared
     * between [buildTechEmpowerTiles] (Browse) and the three
     * FictionDetail handlers so a banner change in Notion propagates
     * to both surfaces from a single helper.
     *
     * Returns null when the page id is null, the chunk fetch fails,
     * or the page genuinely has no banner and no leading image. Callers
     * pass null straight through to FictionSummary.coverUrl — the
     * :core-ui FictionCoverThumb already falls through to the branded
     * synthetic cover in that case.
     */
    private suspend fun resolveCoverUrl(coverPageId: String?): String? {
        if (coverPageId.isNullOrBlank()) return null
        val chunk = when (val r = api.loadPageChunk(coverPageId)) {
            is FictionResult.Success -> r.value
            is FictionResult.Failure -> return null
        }
        return extractCoverFromRecordMap(coverPageId, chunk.recordMap)
    }

    /** Generic-root single-fiction listing. Used when the user has
     *  pointed `:source-notion` at a non-TechEmpower public page (any
     *  notion.site URL). Returns a one-element ListPage built from the
     *  root page's own title + cover. */
    private suspend fun buildGenericRootTile(
        state: NotionConfigState,
    ): FictionResult<ListPage<FictionSummary>> {
        val rootResult = api.loadPageChunk(state.rootPageId)
        val root = when (rootResult) {
            is FictionResult.Success -> rootResult.value
            is FictionResult.Failure -> return rootResult
        }
        val tile = buildSummaryForGenericRoot(state, root)
            ?: return FictionResult.NotFound(
                "Notion root page has no readable title: ${state.rootPageId}",
            )
        return FictionResult.Success(
            ListPage(items = listOf(tile), page = 1, hasNext = false),
        )
    }

    private fun buildSummaryForGenericRoot(
        state: NotionConfigState,
        root: NotionChunkResponse,
    ): FictionSummary? {
        val compactRoot = state.rootPageId.replace("-", "")
        val block = root.recordMap.findBlock(state.rootPageId) ?: return null
        val title = readTitle(block) ?: return null
        val description = readDescriptionFromBlocks(root.recordMap, block)
        // Cover cascade: page_cover (Notion's banner field) → first body
        // image (most pages without a banner still lead with a hero
        // image). When both are null, FictionCoverThumb falls through to
        // the BrandedCoverTile second-tier fallback in :core-ui.
        val cover = readCoverUrl(block) ?: readBodyImageUrl(root.recordMap, block)
        return FictionSummary(
            id = notionFictionId(compactRoot),
            sourceId = SourceIds.NOTION_TECHEMPOWER,
            title = title,
            author = "Notion",
            description = description,
            coverUrl = cover,
            tags = emptyList(),
            status = FictionStatus.ONGOING,
        )
    }

    // ─── fiction detail per variant ───────────────────────────────────

    private fun pageListDetail(
        fictionId: String,
        fiction: TechEmpowerFiction.PageList,
        coverUrl: String?,
    ): FictionResult<FictionDetail> {
        val chapters = fiction.chapters.mapIndexed { idx, (title, pageId) ->
            ChapterInfo(
                id = chapterIdFor(fictionId, pageId),
                sourceChapterId = pageId,
                index = idx,
                title = title,
                publishedAt = null,
            )
        }
        val summary = FictionSummary(
            id = fictionId,
            sourceId = SourceIds.NOTION_TECHEMPOWER,
            title = fiction.title,
            author = "TechEmpower",
            description = fiction.description,
            coverUrl = coverUrl,
            chapterCount = chapters.size,
            status = FictionStatus.ONGOING,
        )
        return FictionResult.Success(FictionDetail(summary = summary, chapters = chapters))
    }

    private suspend fun collectionDetail(
        fictionId: String,
        fiction: TechEmpowerFiction.CollectionRows,
        coverUrl: String?,
    ): FictionResult<FictionDetail> {
        // Step 1: discover collection_id + view_id from the configured
        // block id.
        val chunkResult = api.loadPageChunk(fiction.collectionBlockId)
        val chunk = when (chunkResult) {
            is FictionResult.Success -> chunkResult.value
            is FictionResult.Failure -> return chunkResult
        }
        val viewBlock = chunk.recordMap.findBlock(fiction.collectionBlockId)
            ?: return FictionResult.NotFound(
                "Collection block ${fiction.collectionBlockId} not in recordMap",
            )
        val collectionId = viewBlock.collectionId()
            ?: return FictionResult.NotFound("Collection has no collection_id")
        val viewId = viewBlock.firstViewId()
            ?: return FictionResult.NotFound("Collection has no view_ids")

        // Step 2: query the rows.
        val rowsResult = api.queryCollection(collectionId, viewId, limit = 200)
        val rowsResp = when (rowsResult) {
            is FictionResult.Success -> rowsResult.value
            is FictionResult.Failure -> return rowsResult
        }
        val rows = collectRows(rowsResp.recordMap)
        val chapters = rows.mapIndexed { idx, (rowId, title) ->
            ChapterInfo(
                id = chapterIdFor(fictionId, rowId),
                sourceChapterId = rowId,
                index = idx,
                title = title,
                publishedAt = null,
            )
        }
        val summary = FictionSummary(
            id = fictionId,
            sourceId = SourceIds.NOTION_TECHEMPOWER,
            title = fiction.title,
            author = "TechEmpower",
            description = fiction.description,
            coverUrl = coverUrl,
            chapterCount = chapters.size,
            status = FictionStatus.ONGOING,
        )
        return FictionResult.Success(FictionDetail(summary = summary, chapters = chapters))
    }

    private fun singlePageDetail(
        fictionId: String,
        fiction: TechEmpowerFiction.SinglePage,
        coverUrl: String?,
    ): FictionResult<FictionDetail> {
        val chapters = listOf(
            ChapterInfo(
                id = chapterIdFor(fictionId, fiction.pageId),
                sourceChapterId = fiction.pageId,
                index = 0,
                title = fiction.title,
                publishedAt = null,
            ),
        )
        val summary = FictionSummary(
            id = fictionId,
            sourceId = SourceIds.NOTION_TECHEMPOWER,
            title = fiction.title,
            author = "TechEmpower",
            description = fiction.description,
            coverUrl = coverUrl,
            chapterCount = 1,
            status = FictionStatus.ONGOING,
        )
        return FictionResult.Success(FictionDetail(summary = summary, chapters = chapters))
    }

    private suspend fun genericRootFictionDetail(
        state: NotionConfigState,
        fictionId: String,
    ): FictionResult<FictionDetail> {
        val rootResult = api.loadPageChunk(state.rootPageId)
        val root = when (rootResult) {
            is FictionResult.Success -> rootResult.value
            is FictionResult.Failure -> return rootResult
        }
        val tile = buildSummaryForGenericRoot(state, root)
            ?: return FictionResult.NotFound(
                "Notion root page has no readable title: ${state.rootPageId}",
            )
        val compactRoot = state.rootPageId.replace("-", "")
        val chapters = listOf(
            ChapterInfo(
                id = chapterIdFor(fictionId, compactRoot),
                sourceChapterId = compactRoot,
                index = 0,
                title = "Contents",
                publishedAt = null,
            ),
        )
        return FictionResult.Success(
            FictionDetail(summary = tile.copy(chapterCount = 1), chapters = chapters),
        )
    }

    // ─── chapter resolution ───────────────────────────────────────────

    /** Look up the (title, pageId) for a chapter inside a TechEmpower
     *  fiction. Returns null when the sectionId/sourceChapterId pair
     *  doesn't match any configured chapter. For CollectionRows the
     *  title is just the row's source id — we re-resolve the human
     *  title from the page block during rendering. */
    private fun resolveTechempowerChapter(
        sectionId: String,
        sourceChapterId: String,
    ): ChapterResolution? {
        val fiction = NotionDefaults.techempowerFictions.firstOrNull { it.id == sectionId }
            ?: return null
        return when (fiction) {
            is TechEmpowerFiction.PageList -> {
                val match = fiction.chapters.firstOrNull { it.second == sourceChapterId }
                    ?: return null
                ChapterResolution(title = match.first, pageId = match.second)
            }
            is TechEmpowerFiction.CollectionRows -> {
                // The chapter title comes from the row page itself; we
                // pass a placeholder here and let renderPageChapter
                // refine it (the ChapterInfo carries the placeholder
                // through, but the rendered HTML's <h1> uses the page's
                // real title).
                ChapterResolution(title = sourceChapterId, pageId = sourceChapterId)
            }
            is TechEmpowerFiction.SinglePage -> {
                if (sourceChapterId != fiction.pageId) return null
                ChapterResolution(title = fiction.title, pageId = fiction.pageId)
            }
        }
    }

    /** Render a chapter that points at a Notion page block. Uniform for
     *  all four fiction variants — each chapter's body is one Notion
     *  page's content. */
    private suspend fun renderPageChapter(
        info: ChapterInfo,
        pageId: String,
    ): FictionResult<ChapterContent> {
        val chunkResult = api.loadPageChunk(pageId)
        val chunk = when (chunkResult) {
            is FictionResult.Success -> chunkResult.value
            is FictionResult.Failure -> return chunkResult
        }
        val pageBlock = chunk.recordMap.findBlock(pageId)
            ?: return FictionResult.NotFound("Notion page $pageId not in recordMap")
        // Refine the chapter title from the page block — important for
        // CollectionRows chapters where the placeholder was the row id.
        val refinedTitle = readTitle(pageBlock)?.takeIf { it.isNotBlank() } ?: info.title
        val refinedInfo = info.copy(title = refinedTitle)
        val (html, plain) = renderPageBody(chunk.recordMap, pageBlock)
        return FictionResult.Success(
            ChapterContent(
                info = refinedInfo,
                htmlBody = html,
                plainBody = plain,
            ),
        )
    }
}

/** One chapter's resolved (title, pageId) — used internally by
 *  AnonymousNotionDelegate's chapter() flow. */
private data class ChapterResolution(val title: String, val pageId: String)

// ─── fiction spec ─────────────────────────────────────────────────────

/**
 * Structural description of one TechEmpower fiction. Each variant maps
 * to a chapter-list strategy:
 *  - [PageList]: chapter list is the hand-curated `(title, pageId)`
 *    pairs (Guides — 8 of them).
 *  - [CollectionRows]: chapter list comes from a `queryCollection` call
 *    against the named block (Resources — N rows, each chapter is one
 *    row's page).
 *  - [SinglePage]: chapter list is one element pointing at the page
 *    (About, Donate).
 */
internal sealed class TechEmpowerFiction {
    abstract val id: String
    abstract val title: String
    abstract val description: String

    /**
     * v0.5.66 (banner wire-up) — compact 32-hex Notion page id whose
     * `format.page_cover` (and body-image fallback) drives this tile's
     * cover URL. Null means "no remote cover" — FictionCoverThumb will
     * fall through to the BrandedCoverTile / Monogram synthetic cover
     * for that fiction. JP authors banners by hitting "Add cover" on
     * the referenced Notion page; on next Browse load the URL flows
     * through automatically.
     *
     * Defaults in [NotionDefaults.techempowerFictions] point each
     * fiction at a sensible page:
     *  - PageList → first chapter's page (Guides → "How to use…")
     *  - CollectionRows → the collection block itself (Resources DB)
     *  - SinglePage → the fiction's own pageId (About / Donate)
     */
    abstract val coverPageId: String?

    data class PageList(
        override val id: String,
        override val title: String,
        override val description: String,
        /** Ordered list of (chapter title, Notion page id) for the
         *  curated chapter spine. Page ids are compact 32-hex. */
        val chapters: List<Pair<String, String>>,
        override val coverPageId: String? = null,
    ) : TechEmpowerFiction()

    data class CollectionRows(
        override val id: String,
        override val title: String,
        override val description: String,
        /** Block id of the collection_view that wraps the database. */
        val collectionBlockId: String,
        override val coverPageId: String? = null,
    ) : TechEmpowerFiction()

    data class SinglePage(
        override val id: String,
        override val title: String,
        override val description: String,
        /** Compact 32-hex Notion page id. */
        val pageId: String,
        override val coverPageId: String? = null,
    ) : TechEmpowerFiction()
}

// ─── recordMap helpers ────────────────────────────────────────────────

/** Find a block in a recordMap by id. Accepts hyphenated or compact
 *  ids and tries both forms (recordMap keys are hyphenated; storyvox's
 *  internal id is compact). */
internal fun NotionRecordMap.findBlock(rawId: String): JsonObject? {
    val hyphenated = NotionUnofficialApi.hyphenatePageId(rawId)
    val direct = block[hyphenated] ?: block[rawId]
    return direct?.block()
}

/** Read the block type discriminator. */
internal fun JsonObject.blockType(): String? =
    (this["type"] as? JsonPrimitive)?.contentOrNull

/** Read a Notion page block's title from its `properties.title` array. */
internal fun readTitle(block: JsonObject): String? {
    val props = block["properties"] as? JsonObject ?: return null
    val titleArr = props["title"] as? JsonArray ?: return null
    return joinDecorationArray(titleArr).ifBlank { null }
}

/** Pull a page block's cover URL from `format.page_cover`. */
internal fun readCoverUrl(block: JsonObject): String? {
    val fmt = block["format"] as? JsonObject ?: return null
    val cover = (fmt["page_cover"] as? JsonPrimitive)?.contentOrNull
    return cover?.takeIf { it.isNotBlank() }
}

/**
 * v0.5.66 banner wire-up — pure-function cover-resolution helper.
 * Looks up [coverPageId] in [recordMap] and returns the cover URL via
 * the standard cascade: `format.page_cover` (Notion's banner) →
 * `readBodyImageUrl` (first leading image). Returns null when the
 * page isn't in the recordMap or carries neither a banner nor a
 * leading image.
 *
 * Pulled out as a file-level helper so it's:
 *  - testable without HTTP (JVM tests can hand it a recordMap and
 *    assert the URL extraction without spinning a network),
 *  - shareable between [AnonymousNotionDelegate.buildTechEmpowerTiles]
 *    (extracts covers from the welcome-root recordMap inline) and
 *    [AnonymousNotionDelegate.resolveCoverUrl] (loads a per-page
 *    chunk, then re-uses this helper).
 */
internal fun extractCoverFromRecordMap(
    coverPageId: String,
    recordMap: NotionRecordMap,
): String? {
    val block = recordMap.findBlock(coverPageId) ?: return null
    return readCoverUrl(block) ?: readBodyImageUrl(recordMap, block)
}

/**
 * Extract the source URL of a Notion `image` block.
 *
 * Notion's unofficial API stores the image source in one of two
 * places depending on how the user added the image:
 *
 *  - `format.display_source` — set when Notion has rewritten the URL
 *    to a signed AWS S3 link (uploaded files, Unsplash). This is the
 *    URL Notion's own renderer uses, so prefer it when present.
 *  - `properties.source[0][0]` — set when the image was added as an
 *    external URL; Notion stores the raw URL in the standard
 *    decoration-array form like every other Notion text property.
 *
 * Returns null when the block isn't an image or carries neither
 * source — callers fall through to the body-image walk's next
 * candidate or the branded synthetic cover.
 */
internal fun readImageBlockSource(block: JsonObject): String? {
    if (block.blockType() != "image") return null
    val fmt = block["format"] as? JsonObject
    val display = (fmt?.get("display_source") as? JsonPrimitive)?.contentOrNull
    if (!display.isNullOrBlank()) return display
    val props = block["properties"] as? JsonObject ?: return null
    val sourceArr = props["source"] as? JsonArray ?: return null
    return joinDecorationArray(sourceArr).ifBlank { null }
}

/**
 * Walk a page's content blocks for the first usable image source.
 *
 * Used as a second-tier cover fallback when [readCoverUrl] returned
 * null (most Notion pages don't have `format.page_cover` set, but
 * many lead with a hero image as the first body block). We scan up
 * to the first 12 content blocks — far enough to cover a callout +
 * lead paragraph pattern, short enough to avoid pulling a random
 * inline screenshot from deep in the page.
 *
 * Returns null when no image block lives near the top of the page.
 */
internal fun readBodyImageUrl(rm: NotionRecordMap, pageBlock: JsonObject): String? {
    for (childId in pageBlock.contentIds().take(12)) {
        val child = rm.findBlock(childId) ?: continue
        val alive = (child["alive"] as? JsonPrimitive)?.booleanOrNull
        if (alive == false) continue
        val src = readImageBlockSource(child)
        if (!src.isNullOrBlank()) return src
    }
    return null
}

/**
 * Read a one-line description for a page from the recordMap. Walks
 * the first few text-bearing blocks under the page to find one that
 * isn't whitespace; returns null if none exists.
 */
internal fun readDescriptionFromBlocks(
    rm: NotionRecordMap,
    pageBlock: JsonObject,
): String? {
    for (childId in pageBlock.contentIds().take(5)) {
        val child = rm.findBlock(childId) ?: continue
        val type = child.blockType()
        // Skip embedded subpages / collection_views / dividers in the
        // search for a description — they don't carry narratable text.
        if (type != "text" && type != "callout" && type != "quote") continue
        val text = plainTextOfBlock(child)
        if (text.isNotBlank()) return text.take(280)
    }
    return null
}

/** Pull collection_view's underlying collection id. */
internal fun JsonObject.collectionId(): String? =
    (this["collection_id"] as? JsonPrimitive)?.contentOrNull

/** Pull the first view id from a collection_view's view_ids array. */
internal fun JsonObject.firstViewId(): String? {
    val arr = this["view_ids"] as? JsonArray ?: return null
    val first = arr.firstOrNull() as? JsonPrimitive ?: return null
    return first.contentOrNull
}

/**
 * Concatenate a Notion decoration array into plain text. Each entry is
 * `[plain_text, decoration[]?]`; we take element 0.
 */
internal fun joinDecorationArray(arr: JsonArray): String {
    val sb = StringBuilder()
    for (entry in arr) {
        val inner = entry as? JsonArray ?: continue
        val first = inner.firstOrNull() as? JsonPrimitive ?: continue
        val s = first.contentOrNull ?: continue
        sb.append(s)
    }
    return sb.toString()
}

/**
 * Pull a block's plain-text content. For text/header/quote/callout
 * blocks the content lives at `properties.title` (Notion's name for
 * the inline text of any block, regardless of type — confusing but
 * stable). For other blocks we return "".
 */
internal fun plainTextOfBlock(block: JsonObject): String {
    val props = block["properties"] as? JsonObject ?: return ""
    val title = props["title"] as? JsonArray ?: return ""
    return joinDecorationArray(title)
}

/** Render a Notion unofficial-API block to HTML. Mirrors the official
 *  toHtml() but maps the v3 block types (`header`, `sub_header`,
 *  `sub_sub_header`, `text`, `bulleted_list`, `numbered_list`, ...).
 *
 *  Internal heading_1s render as `<h2>` instead of `<h1>` — the chapter
 *  reader already shows the chapter title as `<h1>`, so demoting inline
 *  headings keeps the document outline tidy. */
internal fun blockToHtml(rm: NotionRecordMap, block: JsonObject): String {
    val type = block.blockType() ?: return ""
    val raw = plainTextOfBlock(block)
    val text = htmlEscape(raw)
    return when (type) {
        "header" -> if (text.isBlank()) "" else "<h2>$text</h2>"
        "sub_header" -> if (text.isBlank()) "" else "<h3>$text</h3>"
        "sub_sub_header" -> if (text.isBlank()) "" else "<h4>$text</h4>"
        "header_4" -> if (text.isBlank()) "" else "<h5>$text</h5>"
        "text" -> if (text.isBlank()) "" else "<p>$text</p>"
        "bulleted_list" -> if (text.isBlank()) "" else "<li>$text</li>"
        "numbered_list" -> if (text.isBlank()) "" else "<li>$text</li>"
        "quote" -> if (text.isBlank()) "" else "<blockquote>$text</blockquote>"
        "callout" -> if (text.isBlank()) "" else "<aside>$text</aside>"
        "code" -> if (text.isBlank()) "" else "<pre><code>$text</code></pre>"
        "divider" -> "<hr/>"
        "to_do" -> if (text.isBlank()) "" else "<p>$text</p>"
        "toggle" -> if (text.isBlank()) "" else "<p>$text</p>"
        // Embedded page links (child pages, mentions) — emit a
        // bridge paragraph so the chapter narrates "See also: <Title>"
        // rather than going silent on the page's internal navigation.
        // We can't render the linked content here (would balloon the
        // chapter), but a visible reference is better than nothing.
        "page" -> {
            val pageTitle = htmlEscape(readTitle(block).orEmpty())
            if (pageTitle.isBlank()) "" else "<p><strong>$pageTitle</strong></p>"
        }
        else -> ""
    }
}

/** Plain-text projection of a block, for TTS. Mirrors [blockToHtml]
 *  but strips markup; headings are read aloud as plain text. */
internal fun blockToPlain(block: JsonObject): String {
    val type = block.blockType() ?: return ""
    return when (type) {
        "divider" -> ""
        "page" -> readTitle(block).orEmpty()
        else -> plainTextOfBlock(block)
    }
}

/**
 * Render a single page's content as one chapter body. Returns a pair
 * (htmlBody, plainBody). All `alive:false` tombstones are filtered.
 * Page blocks embedded in the content (sub-pages) render as bridge
 * paragraphs ("<strong>Sub-page title</strong>") rather than being
 * expanded inline.
 */
internal fun renderPageBody(
    rm: NotionRecordMap,
    pageBlock: JsonObject,
): Pair<String, String> {
    val html = StringBuilder()
    val plain = StringBuilder()
    for (id in pageBlock.contentIds()) {
        val block = rm.findBlock(id) ?: continue
        val alive = (block["alive"] as? JsonPrimitive)?.booleanOrNull
        if (alive == false) continue
        val htmlPart = blockToHtml(rm, block)
        if (htmlPart.isNotEmpty()) {
            if (html.isNotEmpty()) html.append('\n')
            html.append(htmlPart)
        }
        val plainPart = blockToPlain(block)
        if (plainPart.isNotEmpty()) {
            if (plain.isNotEmpty()) plain.append("\n\n")
            plain.append(plainPart)
        }
    }
    return html.toString().trim() to plain.toString().trim()
}

/**
 * Pull (rowId, title) pairs out of a queryCollection recordMap. Every
 * `page` block in the recordMap is a row; we project to (id, title)
 * and filter to titled, non-tombstoned rows. Returned in id-sorted
 * order so the chapter list is stable across calls.
 */
internal fun collectRows(rm: NotionRecordMap): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    for ((id, env) in rm.block) {
        val block = env.block() ?: continue
        if (block.blockType() != "page") continue
        val alive = (block["alive"] as? JsonPrimitive)?.booleanOrNull
        if (alive == false) continue
        val title = readTitle(block) ?: continue
        // Compact the id (strip hyphens) so chapter ids match the
        // storage convention used elsewhere in :source-notion.
        val compact = id.replace("-", "")
        out.add(compact to title)
    }
    // Sort by title for a predictable, alphabetical chapter list.
    out.sortBy { it.second.lowercase() }
    return out
}
