package `in`.jphe.storyvox.source.wikisource

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
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.source.wikisource.net.WikisourceApi
import `in`.jphe.storyvox.source.wikisource.net.WikisourceCategoryMember
import `in`.jphe.storyvox.source.wikisource.net.WikisourceSearchHit
import `in`.jphe.storyvox.source.wikisource.net.WikisourceSummary
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #376 — Wikisource as a fiction backend.
 *
 * Wikisource is the Wikimedia project for transcribed public-domain
 * texts: Shakespeare's plays, the King James Bible, 19th-century
 * novels, historical documents, etc. Every text is either public-domain
 * or CC-licensed, so storyvox can narrate them without any
 * third-party-ToS surface — same legal posture as Project Gutenberg
 * and Standard Ebooks, with a transcription pipeline that's been
 * proofread by Wikimedia volunteers rather than OCR'd from a scan.
 *
 * ## Shape
 *
 *  - Each Wikisource **mainspace page** is one fiction.
 *  - Two chapter-splitting strategies, picked at detail-fetch time:
 *     - **Subpage walk**: many works live as `Parent_Title/Chapter_One`,
 *       `Parent_Title/Chapter_Two`, etc. (Examples: War_and_Peace,
 *       The_Adventures_of_Sherlock_Holmes.) We enumerate the subpages
 *       via `list=allpages&apprefix=Parent_Title/` and emit one
 *       chapter per subpage.
 *     - **Heading split fallback**: shorter single-page works (a short
 *       story, a single Shakespeare play scene) have no subpages. We
 *       fall back to the Wikipedia-style top-level `<section>` split
 *       within the Parsoid HTML, mirroring `:source-wikipedia`.
 *
 *  - Browse landing uses `Category:Validated_texts` — the curated
 *    quality tier where pages have been double-proofread. Reads
 *    cleanly through TTS without scanno garbage.
 *  - Search hits the MediaWiki Action API search endpoint restricted
 *    to mainspace (`srnamespace=0`), so the Page:/Index:/Author:
 *    namespace pages don't pollute the result list.
 *
 * ## Fiction IDs
 *
 *  - `wikisource:<title>` — title with spaces encoded as underscores
 *    (matches Wikisource URL shape).
 *  - `wikisource:<title>::sub-<n>` for subpage chapters (n is the
 *    0-based index into the subpage list).
 *  - `wikisource:<title>::section-<n>` for in-page heading splits
 *    (n is the 0-based section index; 0 is the lead).
 */
@SourcePlugin(
    id = SourceIds.WIKISOURCE,
    displayName = "Wikisource",
    // #436 — fresh-install discoverability: chip on by default.
    defaultEnabled = true,
    category = SourceCategory.Text,
    supportsFollow = false,
    supportsSearch = true,
    description = "Transcribed public-domain texts · multi-part works become per-chapter playlists",
    sourceUrl = "https://en.wikisource.org",
)
@Singleton
internal class WikisourceSource @Inject constructor(
    private val api: WikisourceApi,
) : FictionSource, `in`.jphe.storyvox.data.source.UrlMatcher {

    override val id: String = SourceIds.WIKISOURCE
    override val displayName: String = "Wikisource"

    /** Issue #472 — `*.wikisource.org/wiki/<title>` URL. Same shape as
     *  the Wikipedia matcher (above), different host. */
    override fun matchUrl(url: String): `in`.jphe.storyvox.data.source.RouteMatch? {
        val m = WIKISOURCE_URL_PATTERN.matchEntire(url.trim()) ?: return null
        val title = m.groupValues[2].trim().ifBlank { return null }
        return `in`.jphe.storyvox.data.source.RouteMatch(
            sourceId = SourceIds.WIKISOURCE,
            fictionId = wikisourceFictionId(java.net.URLDecoder.decode(title, Charsets.UTF_8)),
            confidence = 0.95f,
            label = "Wikisource work",
        )
    }

    /**
     * Issue #809 — the prior `language` Select dimension stuffed
     * `language:<code>` into the search term, which became literal
     * search text (MediaWiki has no `language:` keyword). Wikisource
     * has no app-side language-switching infra anyway — it's hard-coded
     * to en.wikisource.org via [WikisourceApi.BASE_URL]. Replaced with
     * a real sort dimension since MediaWiki full-text search supports
     * `srsort` (relevance, newest, oldest).
     */
    override fun filterDimensions(): List<FilterDimension> = listOf(
        FilterDimension.Sort(
            options = listOf(
                FilterDimension.SortOption("relevance", "Most relevant"),
                FilterDimension.SortOption("newest", "Newest"),
                FilterDimension.SortOption("oldest", "Oldest"),
            ),
        ),
    )

    /**
     * Translate the Sort filter into [SearchQuery.orderBy] / [direction].
     * The [search] method reads them below to thread `srsort` through
     * to the MediaWiki search API. Term is left untouched — pre-fix
     * this was the bug.
     */
    override fun applyFilters(base: SearchQuery, state: FilterState): SearchQuery {
        val sort = state.stringVal("sort") ?: return base
        return when (sort) {
            "newest" -> base.copy(
                orderBy = `in`.jphe.storyvox.data.source.model.SearchOrder.LAST_UPDATE,
                direction = `in`.jphe.storyvox.data.source.model.SortDirection.DESC,
            )
            "oldest" -> base.copy(
                orderBy = `in`.jphe.storyvox.data.source.model.SearchOrder.LAST_UPDATE,
                direction = `in`.jphe.storyvox.data.source.model.SortDirection.ASC,
            )
            else -> base // relevance is the default
        }
    }

    // ─── browse ────────────────────────────────────────────────────────

    override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> {
        // Validated_texts isn't paginated for v1; a 50-entry first page
        // covers the curated landing comfortably. Pages > 1 return empty
        // so the paginator stops cleanly without a second network hop.
        if (page > 1) return FictionResult.Success(ListPage(emptyList(), page, hasNext = false))
        return when (val r = api.categoryMembers()) {
            is FictionResult.Success ->
                FictionResult.Success(
                    ListPage(
                        items = r.value.map { it.toSummary() },
                        page = 1,
                        hasNext = false,
                    ),
                )
            is FictionResult.Failure -> r
        }
    }

    override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> =
        // Wikisource has no stable "new texts by recency" listing —
        // recent changes is dominated by edits, not new transcribed
        // works. Reuse the curated landing so Browse → Wikisource →
        // NewReleases still has something to render.
        popular(page)

    override suspend fun byGenre(
        genre: String,
        page: Int,
    ): FictionResult<ListPage<FictionSummary>> =
        // Wikisource categories are dense (every work belongs to many)
        // and not user-facing genres. Out of scope for v1.
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    override suspend fun genres(): FictionResult<List<String>> =
        FictionResult.Success(emptyList())

    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> {
        val term = query.term.trim()
        if (term.isEmpty()) return popular(1)
        // Translate orderBy → MediaWiki srsort value. Only LAST_UPDATE
        // varies the default (relevance) — every other SearchOrder
        // collapses back to relevance because Wikisource search has no
        // FOLLOWERS / VIEWS / RATING concept.
        val sort = when (query.orderBy) {
            `in`.jphe.storyvox.data.source.model.SearchOrder.LAST_UPDATE ->
                if (query.direction == `in`.jphe.storyvox.data.source.model.SortDirection.ASC) {
                    "last_edit_asc"
                } else {
                    "last_edit_desc"
                }
            else -> "relevance"
        }
        return when (val r = api.search(term, sort = sort)) {
            is FictionResult.Success ->
                FictionResult.Success(
                    ListPage(
                        items = r.value.map { it.toSummary() },
                        page = 1,
                        hasNext = false,
                    ),
                )
            is FictionResult.Failure -> r
        }
    }

    // ─── detail ────────────────────────────────────────────────────────

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        val title = fictionId.toWikisourceTitle()
            ?: return FictionResult.NotFound("Wikisource fiction id not recognized: $fictionId")

        // Subpage probe first — most Wikisource multi-chapter works live
        // as a parent page with `/Chapter_N` subpages. If the probe
        // returns any subpages, each one becomes a chapter. Otherwise
        // we fall back to in-page heading splits via the Parsoid HTML.
        val subpages = when (val r = api.subpages(title)) {
            is FictionResult.Success -> r.value
            is FictionResult.Failure -> emptyList() // degrade gracefully — fall through to heading-split
        }
        val chapters: List<ChapterInfo> = if (subpages.isNotEmpty()) {
            subpages.mapIndexed { idx, subTitle ->
                ChapterInfo(
                    id = subpageChapterId(fictionId, idx),
                    sourceChapterId = "sub-$idx",
                    index = idx,
                    title = subpageDisplayName(parentTitle = title, subpageTitle = subTitle),
                    publishedAt = null,
                )
            }
        } else {
            // Fall back to single-page heading splits.
            val htmlResult = api.pageHtml(title)
            val html = when (htmlResult) {
                is FictionResult.Success -> htmlResult.value
                is FictionResult.Failure -> return htmlResult
            }
            val sections = splitTopLevelSections(html)
            sections.mapIndexed { idx, section ->
                ChapterInfo(
                    id = sectionChapterId(fictionId, idx),
                    sourceChapterId = "section-$idx",
                    index = idx,
                    title = section.title,
                    publishedAt = null,
                )
            }
        }

        val summary = when (val s = api.summary(title)) {
            is FictionResult.Success -> s.value.toSummary(fictionId, title, chapters.size)
            is FictionResult.Failure -> FictionSummary(
                id = fictionId,
                sourceId = SourceIds.WIKISOURCE,
                title = title.replace('_', ' '),
                author = "Wikisource",
                description = null,
                status = FictionStatus.COMPLETED, // transcribed texts are by definition complete works
                chapterCount = chapters.size,
            )
        }
        return FictionResult.Success(FictionDetail(summary = summary, chapters = chapters))
    }

    override suspend fun chapter(
        fictionId: String,
        chapterId: String,
    ): FictionResult<ChapterContent> {
        val title = fictionId.toWikisourceTitle()
            ?: return FictionResult.NotFound("Wikisource fiction id not recognized: $fictionId")

        return when {
            "::sub-" in chapterId -> chapterFromSubpage(title, chapterId)
            "::section-" in chapterId -> chapterFromSection(title, chapterId)
            else -> FictionResult.NotFound("Wikisource chapter id not recognized: $chapterId")
        }
    }

    private suspend fun chapterFromSubpage(
        parentTitle: String,
        chapterId: String,
    ): FictionResult<ChapterContent> {
        val subIndex = chapterId.substringAfterLast("::sub-").toIntOrNull()
            ?: return FictionResult.NotFound("Wikisource chapter id not recognized: $chapterId")
        // Re-resolve the subpage title — we don't carry the full subpage
        // title in the chapter id (which would bloat it and double-encode
        // the slash). Same trade-off as Wikipedia's "look up by index"
        // strategy: a freshly-edited work that adds/removes subpages
        // between detail fetch and chapter fetch will surprise us, but
        // the cache is short and the edit cadence on validated texts is
        // glacial.
        val subpages = when (val r = api.subpages(parentTitle)) {
            is FictionResult.Success -> r.value
            is FictionResult.Failure -> return r
        }
        val subTitle = subpages.getOrNull(subIndex)
            ?: return FictionResult.NotFound("Subpage $subIndex not found for $parentTitle")
        return when (val r = api.pageHtml(subTitle)) {
            is FictionResult.Success -> {
                val cleaned = scrubWikisourceCruft(r.value)
                val info = ChapterInfo(
                    id = chapterId,
                    sourceChapterId = "sub-$subIndex",
                    index = subIndex,
                    title = subpageDisplayName(parentTitle = parentTitle, subpageTitle = subTitle),
                )
                FictionResult.Success(
                    ChapterContent(
                        info = info,
                        htmlBody = cleaned,
                        plainBody = cleaned.htmlToPlainText(),
                    ),
                )
            }
            is FictionResult.Failure -> r
        }
    }

    private suspend fun chapterFromSection(
        title: String,
        chapterId: String,
    ): FictionResult<ChapterContent> {
        val sectionIndex = chapterId.substringAfterLast("::section-").toIntOrNull()
            ?: return FictionResult.NotFound("Wikisource chapter id not recognized: $chapterId")
        return when (val r = api.pageHtml(title)) {
            is FictionResult.Success -> {
                val sections = splitTopLevelSections(r.value)
                val section = sections.getOrNull(sectionIndex)
                    ?: return FictionResult.NotFound(
                        "Section $sectionIndex not found in $title",
                    )
                val info = ChapterInfo(
                    id = chapterId,
                    sourceChapterId = "section-$sectionIndex",
                    index = sectionIndex,
                    title = section.title,
                )
                val cleaned = scrubWikisourceCruft(section.html)
                FictionResult.Success(
                    ChapterContent(
                        info = info,
                        htmlBody = cleaned,
                        plainBody = cleaned.htmlToPlainText(),
                    ),
                )
            }
            is FictionResult.Failure -> r
        }
    }

    // ─── auth-gated (no-op) ────────────────────────────────────────────

    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    override suspend fun setFollowed(
        fictionId: String,
        followed: Boolean,
    ): FictionResult<Unit> = FictionResult.Success(Unit)
}

// ─── id encoding ──────────────────────────────────────────────────────

/** Issue #472 — Wikisource URL pattern. Same shape as
 *  [WIKIPEDIA_URL_PATTERN] but anchored on `*.wikisource.org`. */
internal val WIKISOURCE_URL_PATTERN: Regex = Regex(
    """^https?://([a-z]{2,3}(?:-[a-z]+)?)\.(?:m\.)?wikisource\.org/wiki/([^?#]+)(?:[?#].*)?$""",
    RegexOption.IGNORE_CASE,
)

/** Compose a stable Wikisource fiction id from the page title.
 *  Mirrors `wikipediaFictionId` — spaces → underscores so the id
 *  looks like the URL slug. */
internal fun wikisourceFictionId(title: String): String =
    "wikisource:" + title.trim().replace(' ', '_')

/** Subpage chapter id of the form `wikisource:<title>::sub-<n>`. */
internal fun subpageChapterId(fictionId: String, subIndex: Int): String =
    "${fictionId}::sub-$subIndex"

/** Heading-split chapter id of the form `wikisource:<title>::section-<n>`. */
internal fun sectionChapterId(fictionId: String, sectionIndex: Int): String =
    "${fictionId}::section-$sectionIndex"

/** Strip the `wikisource:` prefix and return the underscored title.
 *  Returns null on ids that don't carry the prefix. */
internal fun String.toWikisourceTitle(): String? =
    if (startsWith("wikisource:")) removePrefix("wikisource:").substringBefore("::")
    else null

/** Drop the parent title prefix from a subpage title for display.
 *  `War_and_Peace/Book_One` → `Book One`. Falls back to the full
 *  subpage title (spaces decoded) when the parent prefix doesn't
 *  match. */
internal fun subpageDisplayName(parentTitle: String, subpageTitle: String): String {
    val parentPrefix = parentTitle + "/"
    val short = if (subpageTitle.startsWith(parentPrefix)) {
        subpageTitle.removePrefix(parentPrefix)
    } else {
        subpageTitle
    }
    return short.replace('_', ' ')
}

private fun WikisourceCategoryMember.toSummary(): FictionSummary = FictionSummary(
    id = wikisourceFictionId(title),
    sourceId = SourceIds.WIKISOURCE,
    title = title,
    author = "Wikisource",
    description = null,
    status = FictionStatus.COMPLETED,
)

private fun WikisourceSearchHit.toSummary(): FictionSummary = FictionSummary(
    id = wikisourceFictionId(title),
    sourceId = SourceIds.WIKISOURCE,
    title = title,
    author = "Wikisource",
    // The search snippet contains HTML highlighting (`<span class="searchmatch">...</span>`);
    // strip it for the preview line so the snippet reads cleanly in the
    // result row. The plain-text stripper handles the tag removal.
    description = snippet.ifBlank { null }?.htmlToPlainText()?.ifBlank { null },
    status = FictionStatus.COMPLETED,
)

private fun WikisourceSummary.toSummary(
    fictionId: String,
    title: String,
    chapterCount: Int,
): FictionSummary = FictionSummary(
    id = fictionId,
    sourceId = SourceIds.WIKISOURCE,
    title = (titles?.display ?: titles?.normalized ?: this.title).replace('_', ' '),
    author = "Wikisource",
    coverUrl = thumbnail?.source,
    description = description ?: extract,
    status = FictionStatus.COMPLETED,
    chapterCount = chapterCount,
)

// ─── section splitting (single-page works) ─────────────────────────────

/**
 * Section sliced out of Parsoid HTML — same shape as
 * `:source-wikipedia`'s WikipediaSection. Kept distinct here so the
 * two modules don't grow a shared parsing dependency we'd have to
 * unwind when their heuristics diverge.
 */
internal data class WikisourceSection(val title: String, val html: String)

/**
 * Top-level Parsoid `<section data-mw-section-id="N">` splitter, used
 * for single-page works that don't have subpages. Mirrors
 * `:source-wikipedia` — same depth-tracking algorithm so nested
 * `<h3>`-level sub-sections fold into their parent's html slice.
 *
 * Drops boilerplate end-sections by heading title — "Notes",
 * "References", "External links", "See also", etc. — because narrating
 * a citation list through TTS is miserable. Same drop list as
 * `:source-wikipedia` minus Wikisource-specific Author / License
 * footer headings.
 */
internal fun splitTopLevelSections(html: String): List<WikisourceSection> {
    val openRe = Regex(
        """<section\b[^>]*\bdata-mw-section-id="(-?\d+)"[^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    val closeTag = "</section>"
    val openMatches = openRe.findAll(html).toList()
    if (openMatches.isEmpty()) {
        return listOf(WikisourceSection(title = "Text", html = html))
    }
    val tagOpens = openRe.findAll(html).map { it to true }
    val tagCloses = Regex("""</section\s*>""", RegexOption.IGNORE_CASE)
        .findAll(html)
        .map { it to false }
    val events = (tagOpens + tagCloses)
        .sortedBy { it.first.range.first }
        .toList()
    val topLevelStarts = mutableListOf<MatchResult>()
    var depth = 0
    for ((m, isOpen) in events) {
        if (isOpen) {
            if (depth == 0) topLevelStarts.add(m)
            depth++
        } else {
            if (depth > 0) depth--
        }
    }
    val sections = mutableListOf<WikisourceSection>()
    for (openMatch in topLevelStarts) {
        val sectionId = openMatch.groupValues.getOrNull(1) ?: continue
        var localDepth = 0
        var endIdx = -1
        var i = openMatch.range.first
        while (i < html.length) {
            val nextOpen = html.indexOf("<section", i, ignoreCase = true)
            val nextClose = html.indexOf(closeTag, i, ignoreCase = true)
            if (nextClose < 0) break
            if (nextOpen in 0 until nextClose) {
                val gt = html.indexOf('>', nextOpen)
                if (gt < 0) break
                localDepth++
                i = gt + 1
            } else {
                localDepth--
                i = nextClose + closeTag.length
                if (localDepth <= 0) {
                    endIdx = i
                    break
                }
            }
        }
        if (endIdx < 0) endIdx = html.length
        val sliceStart = openMatch.range.last + 1
        val rawHtml = html.substring(sliceStart, endIdx - closeTag.length).trim()
        val title = extractSectionTitle(rawHtml, sectionId)
        if (shouldDropSection(title)) continue
        sections.add(WikisourceSection(title = title, html = rawHtml))
    }
    return sections
}

private fun extractSectionTitle(rawHtml: String, sectionId: String): String {
    val headingRe = Regex(
        """<h([23])\b[^>]*>(.*?)</h\1>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    val match = headingRe.find(rawHtml)
    if (match != null) {
        val inner = match.groupValues[2]
        val text = inner.htmlToPlainText().trim()
        if (text.isNotEmpty()) return text
    }
    // Section 0 on Wikisource pages is the lead (front-matter — title
    // page, author note, license notice). The Wikipedia analog uses
    // "Introduction" here; on Wikisource that label is misleading for
    // a transcribed text where the actual prose starts in section 0.
    // "Text" reads neutrally for both cases.
    return if (sectionId == "0") "Text" else "Section $sectionId"
}

/** Boilerplate end-sections that don't read well through TTS. Same
 *  list as `:source-wikipedia` (validated against actual Wikisource
 *  pages — the Notes / References / External links / Author / License
 *  trailing-sections show up across Shakespeare plays, 19th-century
 *  novels, and historical documents). */
private val DROP_SECTION_TITLES: Set<String> = setOf(
    "references",
    "external links",
    "see also",
    "notes",
    "further reading",
    "bibliography",
    "footnotes",
    "citations",
    "sources",
    // Wikisource-specific footer headings.
    "license",
    "copyright",
).map { it.lowercase() }.toSet()

private fun shouldDropSection(title: String): Boolean =
    title.trim().lowercase() in DROP_SECTION_TITLES

// ─── HTML scrubbing ───────────────────────────────────────────────────

/**
 * Strip Wikisource-specific HTML cruft that reads poorly through TTS:
 *
 *  - `<span class="mw-editsection">[edit]</span>` — section edit links
 *    (Wikisource pages have these too; same mw- machinery as Wikipedia).
 *  - `<sup class="reference">[1]</sup>` — footnote refs
 *  - `<span class="pagenum">...</span>` — Wikisource-specific page-number
 *    callouts injected by the Page:/Index: transclusion (e.g. `[123]`
 *    markers that map the rendered prose to the facsimile page numbers).
 *    Useful for editors, noise for listeners.
 *  - `<div class="noprint">...</div>` — header/footer chrome around the
 *    transcluded text.
 *  - `<table class="header_notes">` / `<table class="ws-noexport">` —
 *    Wikisource header/footer tables (title page, license box, etc.).
 */
internal fun scrubWikisourceCruft(html: String): String {
    val patterns = listOf(
        Regex(
            """<span\b[^>]*class="[^"]*\bmw-editsection\b[^"]*"[^>]*>.*?</span>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ),
        Regex(
            """<sup\b[^>]*class="[^"]*\breference\b[^"]*"[^>]*>.*?</sup>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ),
        // Wikisource page-number callouts. The class is `pagenum` on
        // most works; also `ws-pagenum` on newer transclusions.
        Regex(
            """<span\b[^>]*class="[^"]*\b(?:ws-)?pagenum\b[^"]*"[^>]*>.*?</span>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ),
        Regex(
            """<div\b[^>]*class="[^"]*\bnoprint\b[^"]*"[^>]*>.*?</div>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ),
        Regex(
            """<table\b[^>]*class="[^"]*\bheader_notes\b[^"]*"[^>]*>.*?</table>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ),
        Regex(
            """<table\b[^>]*class="[^"]*\bws-noexport\b[^"]*"[^>]*>.*?</table>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ),
    )
    var cleaned = html
    for (p in patterns) {
        cleaned = p.replace(cleaned, "")
    }
    // Bare `[edit]` belt-and-braces (matches what survives nested links).
    cleaned = cleaned.replace(Regex("""\[\s*edit\s*\]""", RegexOption.IGNORE_CASE), "")
    return cleaned
}

/**
 * Naive HTML → plain-text stripper for the TTS-side `plainBody`. Same
 * shape as `:source-wikipedia.htmlToPlainText` — kept as a local
 * function to avoid coupling the two source modules. The playback
 * layer applies further normalization downstream (sentence
 * segmentation, abbreviation expansion).
 */
internal fun String.htmlToPlainText(): String {
    var out = this
        .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        .replace(
            Regex("<script\\b.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
            "",
        )
        .replace(
            Regex("<style\\b.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
            "",
        )
    out = out.replace(
        Regex("</?(p|div|section|h[1-6]|li|tr|br)\\b[^>]*>", RegexOption.IGNORE_CASE),
        "\n",
    )
    out = out.replace(Regex("<[^>]+>"), "")
    out = out
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&mdash;", "—")
        .replace("&ndash;", "–")
        .replace("&hellip;", "…")
    out = out.replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
    return out
}
