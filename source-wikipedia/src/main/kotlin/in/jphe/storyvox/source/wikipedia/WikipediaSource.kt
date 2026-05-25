package `in`.jphe.storyvox.source.wikipedia

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
import `in`.jphe.storyvox.data.source.model.map
import `in`.jphe.storyvox.source.wikipedia.net.WikipediaApi
import `in`.jphe.storyvox.source.wikipedia.net.WikipediaFeaturedArticle
import `in`.jphe.storyvox.source.wikipedia.net.WikipediaMostReadArticle
import `in`.jphe.storyvox.source.wikipedia.net.WikipediaSearchHit
import `in`.jphe.storyvox.source.wikipedia.net.WikipediaSummary
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #377 — Wikipedia as a non-fiction long-form backend.
 *
 * The first NON-fiction-shaped source in the roster — different shape
 * from the EPUB-backed ones:
 *
 *  - Each Wikipedia **article** is one fiction.
 *  - Each **top-level section** in the article is one chapter:
 *     - Chapter 0 = "Introduction" (the lead, before the first `<h2>`).
 *     - Chapter N = the Nth `<h2>` section ("History", "Geography", ...).
 *  - Sub-sections (`<h3>` and below) render inside their parent
 *    chapter's HTML — keeps the chapter count sensible on long
 *    articles (a biography with 12 top-level sections is plenty;
 *    splitting on `<h3>` would explode it to 60+).
 *
 * Wikipedia-specific cruft (edit links, citation refs, navboxes,
 * infoboxes when in the lead, coord templates) is stripped from
 * chapter HTML before returning — bare `[edit]` markers read
 * particularly poorly through TTS. We also drop the boilerplate
 * end-sections ("References", "External links", "See also", "Notes",
 * "Further reading", "Bibliography") since narrating a citation list
 * is painful and adds no listener value.
 *
 * Same architectural seam as :source-outline — OkHttp + kotlinx
 * serialization, all calls public + read-only, no auth.
 *
 * Fiction IDs: `wikipedia:<title>` where `<title>` is the article's
 * canonical title with spaces encoded as underscores (matches the
 * shape of Wikipedia URLs). Chapter IDs:
 * `wikipedia:<title>::section-<n>` where `n` is the 0-based section
 * index (0 = Introduction).
 */
@SourcePlugin(
    id = SourceIds.WIKIPEDIA,
    displayName = "Wikipedia",
    // #436 — fresh-install discoverability: chip on by default.
    defaultEnabled = true,
    category = SourceCategory.Text,
    supportsFollow = false,
    supportsSearch = true,
    description = "Any Wikipedia article narrated · article = fiction, sections = chapters · per-language",
    sourceUrl = "https://en.wikipedia.org",
)
@Singleton
internal class WikipediaSource @Inject constructor(
    private val api: WikipediaApi,
) : FictionSource, `in`.jphe.storyvox.data.source.UrlMatcher {

    override val id: String = SourceIds.WIKIPEDIA
    override val displayName: String = "Wikipedia"

    /** Issue #472 — claim any `*.wikipedia.org/wiki/<title>` URL. The
     *  language prefix is captured separately so the resolver could
     *  later thread it through to the per-language Wikipedia config;
     *  v1 trusts the source's own existing language wiring and just
     *  uses the title slug as the fictionId payload. */
    override fun matchUrl(url: String): `in`.jphe.storyvox.data.source.RouteMatch? {
        val m = WIKIPEDIA_URL_PATTERN.matchEntire(url.trim()) ?: return null
        val title = m.groupValues[2].trim().ifBlank { return null }
        return `in`.jphe.storyvox.data.source.RouteMatch(
            sourceId = SourceIds.WIKIPEDIA,
            fictionId = wikipediaFictionId(java.net.URLDecoder.decode(title, Charsets.UTF_8)),
            confidence = 0.95f,
            label = "Wikipedia article",
        )
    }

    /**
     * Issue #809 — the prior `language` Select dimension stuffed
     * `language:<code>` into the search term, which became literal
     * search text (MediaWiki has no `language:` keyword). Wikipedia's
     * per-language host is set via [WikipediaConfig] (Settings →
     * Wikipedia language), not the Browse filter, so the dimension was
     * always going to be cosmetic. Replaced with a real sort dimension
     * since the MediaWiki full-text search API supports `srsort`
     * (relevance, newest, oldest).
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
     * Translate the Sort filter into [SearchQuery.orderBy]. The
     * [search] method reads orderBy below to pick `opensearch` (cheap
     * title-completion, no sort) vs `fullTextSearch` (slower, `srsort`-
     * aware). Term is left untouched — pre-fix this was the bug.
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
        // The featured-feed endpoint surfaces yesterday's most-read +
        // today's Today's Featured Article. We default to "yesterday
        // UTC" so the mostread cluster is populated (today's not
        // ranked yet) and the TFA rolled over wherever the user's
        // clock is. Anything paginated past page 1 is empty —
        // Wikipedia's featured feed is one-day-at-a-time, not a
        // multi-page firehose.
        if (page > 1) return FictionResult.Success(ListPage(emptyList(), page, hasNext = false))
        val date = LocalDate.now(ZoneOffset.UTC).minusDays(1)
        return api.featured(date.year, date.monthValue, date.dayOfMonth).map { feed ->
            val items = buildList {
                feed.tfa?.let { tfa -> tfa.toSummary()?.let(::add) }
                feed.mostread?.articles?.forEach { article ->
                    article.toSummary()?.let(::add)
                }
            }.distinctBy { it.id }
            ListPage(items = items, page = 1, hasNext = false)
        }
    }

    override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> =
        // Wikipedia doesn't have a stable "new articles by recency"
        // public listing; the recent-changes feed is dominated by
        // edits not new articles. Reuse the featured listing so
        // Browse → Wikipedia → NewReleases still has something to
        // render, just without a separate fetch.
        popular(page)

    override suspend fun byGenre(
        genre: String,
        page: Int,
    ): FictionResult<ListPage<FictionSummary>> =
        // Wikipedia categories exist but don't surface as a flat
        // "genre" list — every article belongs to dozens of
        // categories. Out of scope for v1.
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    override suspend fun genres(): FictionResult<List<String>> =
        FictionResult.Success(emptyList())

    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> {
        val term = query.term.trim()
        if (term.isEmpty()) return popular(1)
        // Use cheap title-completion for the default RELEVANCE sort —
        // openSearch matches against article titles, which is what the
        // user typically wants. For LAST_UPDATE switch to the full-text
        // search endpoint which is the only one that supports `srsort`.
        val needsSrsort = query.orderBy == `in`.jphe.storyvox.data.source.model.SearchOrder.LAST_UPDATE
        return if (needsSrsort) {
            val sort = when (query.direction) {
                `in`.jphe.storyvox.data.source.model.SortDirection.ASC -> "last_edit_asc"
                `in`.jphe.storyvox.data.source.model.SortDirection.DESC -> "last_edit_desc"
            }
            api.fullTextSearch(term, sort = sort).map { hits ->
                ListPage(items = hits.map { it.toSummary() }, page = 1, hasNext = false)
            }
        } else {
            api.openSearch(term).map { hits ->
                ListPage(items = hits.map { it.toSummary() }, page = 1, hasNext = false)
            }
        }
    }

    // ─── detail ────────────────────────────────────────────────────────

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        val title = fictionId.toArticleTitle()
            ?: return FictionResult.NotFound("Wikipedia fiction id not recognized: $fictionId")
        // Two-call pattern: page/html for chapters + page/summary for
        // the preview card. Both are public REST calls; failures on
        // summary degrade to a blank description rather than failing
        // the whole detail fetch.
        val htmlResult = api.pageHtml(title)
        val html = when (htmlResult) {
            is FictionResult.Success -> htmlResult.value
            is FictionResult.Failure -> return htmlResult
        }
        val sections = splitTopLevelSections(html)
        val chapters = sections.mapIndexed { idx, section ->
            ChapterInfo(
                id = chapterIdFor(fictionId, idx),
                sourceChapterId = "section-$idx",
                index = idx,
                title = section.title,
                publishedAt = null,
            )
        }
        val summary = when (val s = api.summary(title)) {
            is FictionResult.Success -> s.value.toSummary(fictionId, title, chapters.size)
            is FictionResult.Failure -> FictionSummary(
                id = fictionId,
                sourceId = SourceIds.WIKIPEDIA,
                title = title.replace('_', ' '),
                author = "Wikipedia",
                description = null,
                status = FictionStatus.ONGOING,
                chapterCount = chapters.size,
            )
        }
        return FictionResult.Success(FictionDetail(summary = summary, chapters = chapters))
    }

    override suspend fun chapter(
        fictionId: String,
        chapterId: String,
    ): FictionResult<ChapterContent> {
        val title = fictionId.toArticleTitle()
            ?: return FictionResult.NotFound("Wikipedia fiction id not recognized: $fictionId")
        val sectionIndex = chapterId.substringAfterLast("::section-", "")
            .toIntOrNull()
            ?: return FictionResult.NotFound("Wikipedia chapter id not recognized: $chapterId")
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
                val cleanHtml = scrubWikipediaCruft(section.html)
                FictionResult.Success(
                    ChapterContent(
                        info = info,
                        htmlBody = cleanHtml,
                        plainBody = cleanHtml.htmlToPlainText(),
                    ),
                )
            }
            is FictionResult.Failure -> r
        }
    }

    // ─── auth-gated ─────────────────────────────────────────────────────

    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        // Wikipedia has no account-side follow concept for storyvox.
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    override suspend fun setFollowed(
        fictionId: String,
        followed: Boolean,
    ): FictionResult<Unit> = FictionResult.Success(Unit)
}

// ─── helpers ──────────────────────────────────────────────────────────

/** Issue #472 — Wikipedia article URL pattern. Captures the language
 *  subdomain (group 1) so a future per-language route can use it, and
 *  the article title (group 2). Accepts the canonical `/wiki/<title>`
 *  path; `?action=` mirror URLs from the read-only API are out of
 *  scope (they're not paste-shaped). */
internal val WIKIPEDIA_URL_PATTERN: Regex = Regex(
    """^https?://([a-z]{2,3}(?:-[a-z]+)?)\.(?:m\.)?wikipedia\.org/wiki/([^?#]+)(?:[?#].*)?$""",
    RegexOption.IGNORE_CASE,
)

/** Compose a stable Wikipedia fiction id from the article title. */
internal fun wikipediaFictionId(title: String): String =
    "wikipedia:" + title.trim().replace(' ', '_')

/** Compose a chapter id of the form `wikipedia:<title>::section-<n>`. */
internal fun chapterIdFor(fictionId: String, sectionIndex: Int): String =
    "${fictionId}::section-$sectionIndex"

/** Strip the `wikipedia:` prefix and return the underscored title;
 *  returns null on ids that don't carry the prefix. */
internal fun String.toArticleTitle(): String? =
    if (startsWith("wikipedia:")) removePrefix("wikipedia:").substringBefore("::")
    else null

private fun WikipediaSearchHit.toSummary(): FictionSummary = FictionSummary(
    id = wikipediaFictionId(title),
    sourceId = SourceIds.WIKIPEDIA,
    title = cleanWikipediaTitle(title),
    author = "Wikipedia",
    description = description.ifBlank { null },
    status = FictionStatus.ONGOING,
)

private fun WikipediaFeaturedArticle.toSummary(): FictionSummary? {
    val canonical = titles?.canonical ?: return null
    val display = titles.display ?: titles.normalized ?: canonical
    return FictionSummary(
        id = wikipediaFictionId(canonical),
        sourceId = SourceIds.WIKIPEDIA,
        title = cleanWikipediaTitle(display.replace('_', ' ')),
        author = "Wikipedia",
        coverUrl = thumbnail?.source,
        description = description ?: extract,
        status = FictionStatus.ONGOING,
    )
}

private fun WikipediaMostReadArticle.toSummary(): FictionSummary? {
    val canonical = titles?.canonical ?: return null
    val display = titles.display ?: titles.normalized ?: canonical
    return FictionSummary(
        id = wikipediaFictionId(canonical),
        sourceId = SourceIds.WIKIPEDIA,
        title = cleanWikipediaTitle(display.replace('_', ' ')),
        author = "Wikipedia",
        coverUrl = thumbnail?.source,
        description = description ?: extract,
        status = FictionStatus.ONGOING,
    )
}

private fun WikipediaSummary.toSummary(
    fictionId: String,
    title: String,
    chapterCount: Int,
): FictionSummary = FictionSummary(
    id = fictionId,
    sourceId = SourceIds.WIKIPEDIA,
    title = cleanWikipediaTitle((titles?.display ?: titles?.normalized ?: this.title).replace('_', ' ')),
    author = "Wikipedia",
    coverUrl = thumbnail?.source,
    description = description ?: extract,
    status = FictionStatus.ONGOING,
    chapterCount = chapterCount,
)

/**
 * Article section sliced out of the Parsoid HTML.
 *
 * The Parsoid HTML wraps each section in
 * `<section data-mw-section-id="N">...</section>`. Section 0 is the
 * lead (before the first `<h2>`); subsequent sections carry their
 * heading inside.
 */
internal data class WikipediaSection(val title: String, val html: String)

/**
 * Split Parsoid HTML into top-level sections. Uses string scanning
 * over `<section data-mw-section-id="N">` boundaries — robust to
 * Wikipedia's varying whitespace + attribute order because we only
 * key on the `data-mw-section-id` attribute appearing on a
 * `<section ...>` open tag.
 *
 * Recurses one level: each top-level section may contain nested
 * `<section>` wrappers for `<h3>` sub-sections; we keep them inside
 * the parent's html slice (so sub-section content is narrated as
 * part of the parent chapter). The split therefore tracks
 * `<section>` open/close depth.
 *
 * Drops boilerplate end-sections by heading title — "References",
 * "External links", "See also", "Notes", "Further reading",
 * "Bibliography" — because narrating a citation list through TTS is
 * miserable and isn't where listeners are listening for.
 */
internal fun splitTopLevelSections(html: String): List<WikipediaSection> {
    val openRe = Regex(
        """<section\b[^>]*\bdata-mw-section-id="(-?\d+)"[^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    val closeTag = "</section>"
    val openMatches = openRe.findAll(html).toList()
    if (openMatches.isEmpty()) {
        // No section structure — treat whole article as one chapter.
        return listOf(WikipediaSection(title = "Introduction", html = html))
    }
    // Find the **top-level** sections only — those whose nesting depth
    // (count of unclosed <section> tags before them) is zero.
    val tagOpens = openRe.findAll(html).map { it to true }
    val tagCloses = Regex("""</section\s*>""", RegexOption.IGNORE_CASE)
        .findAll(html)
        .map { it to false }
    val events = (tagOpens + tagCloses)
        .sortedBy { it.first.range.first }
        .toList()
    val topLevelStarts = mutableListOf<Pair<MatchResult, Int>>() // (openMatch, depthBefore)
    var depth = 0
    for ((m, isOpen) in events) {
        if (isOpen) {
            if (depth == 0) topLevelStarts.add(m to 0)
            depth++
        } else {
            if (depth > 0) depth--
        }
    }
    // Each top-level open spans until its matching close. Compute the
    // end index by walking events forward from the open until depth
    // returns to zero.
    val sections = mutableListOf<WikipediaSection>()
    for ((openMatch, _) in topLevelStarts) {
        val sectionId = openMatch.groupValues.getOrNull(1) ?: continue
        var localDepth = 0
        var endIdx = -1
        var i = openMatch.range.first
        while (i < html.length) {
            val nextOpen = html.indexOf("<section", i, ignoreCase = true)
            val nextClose = html.indexOf(closeTag, i, ignoreCase = true)
            if (nextClose < 0) break
            if (nextOpen in 0 until nextClose) {
                // Be defensive — only count it as an open if it's the
                // tag form, not a content character that happens to be
                // "<section". Parsoid emits proper tags so a quick
                // ">"-lookup is sufficient.
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
        val sliceStart = openMatch.range.last + 1 // content starts after the open tag
        val rawHtml = html.substring(sliceStart, endIdx - closeTag.length).trim()
        val title = extractSectionTitle(rawHtml, sectionId)
        if (shouldDropSection(title)) continue
        sections.add(WikipediaSection(title = title, html = rawHtml))
    }
    return sections
}

/**
 * Pull the section title from the first `<h2>` or `<h3>` inside the
 * raw section HTML. Falls back to "Introduction" for the lead
 * (section id = 0) since Wikipedia doesn't render a heading for it,
 * and to "Section N" as an absolute last resort.
 */
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
    return if (sectionId == "0") "Introduction" else "Section $sectionId"
}

/**
 * Boilerplate end-sections that aren't worth narrating through TTS.
 * Wikipedia titles vary across language editions; we match on the
 * canonical English titles only — non-en projects keep their
 * end-sections for now. Acceptable v1 trade-off; an i18n table can
 * land later if anyone listens to ja.wikipedia citations.
 */
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
).map { it.lowercase() }.toSet()

private fun shouldDropSection(title: String): Boolean =
    title.trim().lowercase() in DROP_SECTION_TITLES

/**
 * Strip Wikipedia-specific HTML cruft that reads poorly through TTS:
 *
 *  - `<span class="mw-editsection">[edit]</span>` — section edit links
 *  - `<sup class="reference">[1]</sup>` — citation references
 *  - `<table class="navbox">...</table>` — navigation boxes
 *  - `<table class="infobox">...</table>` — fact-sheet sidebars
 *  - `<span class="mw-cite-backlink">^</span>` — back-references
 *  - `<span class="coordinates">...</span>` — coord templates
 *  - `<div role="note">...</div>` — hatnotes ("Not to be confused...")
 *
 * Per #377 acceptance: bare `[edit]` markers + citation refs are the
 * top offenders. The downstream HTML→plain-text stripper handles
 * generic tags fine; this pass removes the cruft *with* its surrounding
 * structure so the plain-text view doesn't leak `[1]` markers.
 */
internal fun scrubWikipediaCruft(html: String): String {
    val patterns = listOf(
        // Edit-section links — most important for TTS UX.
        Regex(
            """<span\b[^>]*class="[^"]*\bmw-editsection\b[^"]*"[^>]*>.*?</span>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ),
        // Citation references like <sup id="cite_ref-1" class="reference">...</sup>
        Regex(
            """<sup\b[^>]*class="[^"]*\breference\b[^"]*"[^>]*>.*?</sup>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ),
        // Navboxes
        Regex(
            """<table\b[^>]*class="[^"]*\bnavbox\b[^"]*"[^>]*>.*?</table>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ),
        // Infoboxes (when they appear inline — TTS doesn't render their tabular shape).
        Regex(
            """<table\b[^>]*class="[^"]*\binfobox\b[^"]*"[^>]*>.*?</table>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ),
        // Hatnotes ("Not to be confused with X" intro panels)
        Regex(
            """<div\b[^>]*role="note"[^>]*>.*?</div>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ),
        // Coord template spans
        Regex(
            """<span\b[^>]*class="[^"]*\bgeo[^"]*"[^>]*>.*?</span>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ),
        Regex(
            """<span\b[^>]*id="coordinates"[^>]*>.*?</span>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ),
    )
    var cleaned = html
    for (p in patterns) {
        cleaned = p.replace(cleaned, "")
    }
    // Bare "[edit]" tokens that survived above patterns (e.g. inside
    // an `<a>` that was the only child of an mw-editsection span we
    // missed). Cheap belt-and-braces strip.
    cleaned = cleaned.replace(Regex("""\[\s*edit\s*\]""", RegexOption.IGNORE_CASE), "")
    return cleaned
}

/**
 * Issue #672 — clean a Wikipedia title for display. Decodes entities
 * FIRST, then strips the now-decoded <i>...</i> tags. Order matters:
 * htmlToPlainText() does tag-strip-then-decode, which leaves
 * entity-encoded tags visible. Many Wikipedia Popular entries have
 * italicized titles (films, albums, books) and were rendering as raw
 * "&lt;i&gt;Iceman&lt;/i&gt; (Drake album)" pre-fix.
 */
private fun cleanWikipediaTitle(raw: String): String {
    var out = raw
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
    out = out.replace(Regex("<[^>]+>"), "")
    return out.trim()
}

/**
 * Naive HTML → plain-text stripper for the TTS-side `plainBody`. The
 * playback layer applies further normalization downstream (sentence
 * segmentation, abbreviation expansion), so this only needs to remove
 * tags + decode the most common entities — anything fancier would
 * duplicate work.
 */
internal fun String.htmlToPlainText(): String {
    // Drop comments + script/style blocks first; they have no narrating
    // value and stripping tags leaves their content behind.
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
    // Block-level tags → paragraph break.
    out = out.replace(
        Regex("</?(p|div|section|h[1-6]|li|tr|br)\\b[^>]*>", RegexOption.IGNORE_CASE),
        "\n",
    )
    // Strip every remaining tag.
    out = out.replace(Regex("<[^>]+>"), "")
    // Decode the half-dozen entities Wikipedia actually emits.
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
    // Collapse runs of whitespace + blank lines.
    out = out.replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
    return out
}
