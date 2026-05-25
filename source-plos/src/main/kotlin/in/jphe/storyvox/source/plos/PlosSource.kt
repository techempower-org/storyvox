package `in`.jphe.storyvox.source.plos

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.filter.FilterDimension
import `in`.jphe.storyvox.data.source.filter.FilterState
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
import `in`.jphe.storyvox.data.source.plugin.SourceCategory
import `in`.jphe.storyvox.data.source.plugin.SourcePlugin
import `in`.jphe.storyvox.source.plos.net.PlosApi
import `in`.jphe.storyvox.source.plos.net.PlosArticleHit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #380 — PLOS (Public Library of Science) as a fiction backend.
 *
 * PLOS publishes open-access peer-reviewed science across seven
 * journals (PLOS ONE, PLOS Biology, PLOS Medicine, PLOS Computational
 * Biology, PLOS Genetics, PLOS Pathogens, PLOS Neglected Tropical
 * Diseases). Every article is released under a Creative Commons
 * license (CC-BY 4.0 in modern years) — about as open as scholarly
 * content gets. Same legal posture as Standard Ebooks / Gutenberg /
 * Wikipedia: a content backend where the source's own license is the
 * permission slip; no scraping required because PLOS publishes a
 * documented JSON search API.
 *
 * Mapping (v1):
 *
 *  - Each article is one [FictionSummary]. The article-id is the DOI
 *    (Digital Object Identifier), e.g. `10.1371/journal.pone.0123456`.
 *  - Each article has **one chapter** in v1. The chapter body is the
 *    article's abstract + the first ~3 body sections (Introduction,
 *    Methods, Results — or whatever the first three top-level
 *    `<h2>`-bounded sections happen to be in the article's HTML).
 *    Splitting on `heading_1` boundaries into multiple chapters lands
 *    in a follow-up PR; v1 keeps the chapter count predictable.
 *
 * Discovery shape:
 *
 *  - **popular()** — no global "top stories" exists; we surface recent
 *    articles in PLOS ONE sorted by publication_date desc, which is
 *    the closest thing PLOS has to a landing page. PLOS ONE alone
 *    publishes ~200 articles/day so the result feels fresh.
 *  - **search()** — same Solr endpoint, free-form query.
 *  - **latestUpdates()** — collapses to popular() since the recent-PLOS-ONE
 *    listing already IS the new-releases view.
 *  - **byGenre()** — out of scope for v1; the genre concept maps to
 *    "journal" here and the seven-journal set is small enough to land
 *    as a filter sheet in a follow-up rather than a flat genre row.
 *
 * Fiction IDs: `plos:<DOI>`. The DOI is URL-safe-ish (carries `/` and
 * `.`) so we keep it as-is in the id; the chapter id appends
 * `::body` so the same id-shape pattern as Wikipedia / Notion holds.
 *
 * Same architectural seam as :source-wikipedia — OkHttp + kotlinx
 * serialization, all calls public + read-only, no auth.
 */
@SourcePlugin(
    id = SourceIds.PLOS,
    displayName = "PLOS",
    // #436 — fresh-install discoverability: chip on by default.
    defaultEnabled = true,
    category = SourceCategory.Text,
    supportsFollow = false,
    supportsSearch = true,
    description = "Open-access peer-reviewed science · abstract + first sections as single-chapter fictions · Solr search",
    sourceUrl = "https://plos.org",
)
@Singleton
internal class PlosSource @Inject constructor(
    private val api: PlosApi,
) : FictionSource, `in`.jphe.storyvox.data.source.UrlMatcher {

    override val id: String = SourceIds.PLOS
    override val displayName: String = "PLOS"

    /** Issue #472 — PLOS journal article URLs carry the DOI in the
     *  query string: `journals.plos.org/<journal>/article?id=10.1371/journal.<...>`.
     *  We match host + extract id. */
    override fun matchUrl(url: String): `in`.jphe.storyvox.data.source.RouteMatch? {
        val m = PLOS_ARTICLE_URL_PATTERN.matchEntire(url.trim()) ?: return null
        val doi = m.groupValues[1].trim()
        if (doi.isBlank()) return null
        return `in`.jphe.storyvox.data.source.RouteMatch(
            sourceId = SourceIds.PLOS,
            fictionId = plosFictionId(doi),
            confidence = 0.95f,
            label = "PLOS article",
        )
    }

    override fun filterDimensions(): List<FilterDimension> = listOf(
        FilterDimension.Sort(
            options = listOf(
                FilterDimension.SortOption("relevance", "Default"),
                FilterDimension.SortOption("last_update", "Newest"),
                FilterDimension.SortOption("popularity", "Popular"),
            ),
        ),
        FilterDimension.Select(
            key = "category",
            label = "Category",
            options = listOf(
                "Biology", "Medicine", "Computational Biology",
                "Genetics", "Neuroscience", "Public Health",
            ),
        ),
        FilterDimension.DateRange(),
    )

    override fun applyFilters(base: SearchQuery, state: FilterState): SearchQuery {
        var q = base
        state.stringVal("sort")?.let { sortId ->
            q = q.copy(
                orderBy = when (sortId) {
                    "last_update" -> SearchOrder.LAST_UPDATE
                    "popularity" -> SearchOrder.POPULARITY
                    else -> SearchOrder.RELEVANCE
                },
            )
        }
        state.stringVal("category")?.takeIf { it.isNotBlank() }?.let { cat ->
            q = q.copy(genres = q.genres + cat)
        }
        return q
    }

    // ─── browse ────────────────────────────────────────────────────────

    override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> {
        // PLOS Search supports an explicit start/rows pagination pair.
        // We page in 50-row windows so a single Browse pull surfaces a
        // good chunk of recent articles; hasNext is true while the
        // backend returns a full page.
        val rows = ROWS_PER_PAGE
        val start = (page - 1).coerceAtLeast(0) * rows
        return api.searchRecent(start = start, rows = rows).map { resp ->
            val items = resp.response.docs.mapNotNull { it.toSummary() }
            ListPage(
                items = items,
                page = page,
                hasNext = items.size >= rows,
            )
        }
    }

    override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> =
        // PLOS doesn't separate "most-read" from "newest"; the
        // popular() listing is already publication-date desc.
        popular(page)

    override suspend fun byGenre(
        genre: String,
        page: Int,
    ): FictionResult<ListPage<FictionSummary>> =
        // v1: no genre concept surfaced. Follow-up PR adds the
        // per-journal filter and routes journal_key → here.
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    override suspend fun genres(): FictionResult<List<String>> =
        FictionResult.Success(emptyList())

    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> {
        val term = query.term.trim()
        val rows = ROWS_PER_PAGE
        val start = (query.page - 1).coerceAtLeast(0) * rows
        val subject = query.genres.firstOrNull()?.takeIf { it.isNotBlank() }
        val sort = when (query.orderBy) {
            SearchOrder.LAST_UPDATE, SearchOrder.RELEASE_DATE -> "publication_date desc"
            SearchOrder.POPULARITY -> "counter_total_all desc"
            else -> null // Solr default ranking (relevance for non-empty q)
        }
        // Empty term AND no facets → fall back to the popular landing
        // (PLOS ONE recents). Once any filter is active, route through
        // searchQuery so the sort + subject params reach Solr.
        if (term.isEmpty() && subject == null && sort == null) {
            return popular(query.page.coerceAtLeast(1))
        }
        return api.searchQuery(
            term = term,
            start = start,
            rows = rows,
            sort = sort,
            subjectFilter = subject,
        ).map { resp ->
            val items = resp.response.docs.mapNotNull { it.toSummary() }
            ListPage(
                items = items,
                page = query.page.coerceAtLeast(1),
                hasNext = items.size >= rows,
            )
        }
    }

    // ─── detail ────────────────────────────────────────────────────────

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        val doi = fictionId.toPlosDoi()
            ?: return FictionResult.NotFound("PLOS fiction id not recognized: $fictionId")
        // Best-effort metadata via the search API filtered to this DOI —
        // the Solr endpoint will hand back the single matching doc with
        // title/author/abstract populated, which is cheaper than parsing
        // the full article HTML twice (once for the preview card, once
        // for the chapter body).
        val metaResult = api.fetchByDoi(doi)
        val meta = when (metaResult) {
            is FictionResult.Success -> metaResult.value.response.docs.firstOrNull()
            is FictionResult.Failure -> return metaResult
        }
        val summary = meta?.toSummary()?.copy(chapterCount = 1)
            ?: FictionSummary(
                id = fictionId,
                sourceId = SourceIds.PLOS,
                title = doi,
                author = "PLOS",
                description = null,
                status = FictionStatus.COMPLETED,
                chapterCount = 1,
            )
        val chapter = ChapterInfo(
            id = chapterIdFor(fictionId),
            sourceChapterId = BODY_CHAPTER_KEY,
            index = 0,
            title = "Article",
            publishedAt = null,
        )
        return FictionResult.Success(FictionDetail(summary = summary, chapters = listOf(chapter)))
    }

    override suspend fun chapter(
        fictionId: String,
        chapterId: String,
    ): FictionResult<ChapterContent> {
        val doi = fictionId.toPlosDoi()
            ?: return FictionResult.NotFound("PLOS fiction id not recognized: $fictionId")
        if (!chapterId.endsWith("::$BODY_CHAPTER_KEY")) {
            return FictionResult.NotFound("PLOS chapter id not recognized: $chapterId")
        }
        // Pull both abstract (cheap, structured Solr) and article HTML
        // (the body sections). Abstract failure is non-fatal because the
        // HTML body usually carries an abstract paragraph at the top
        // anyway; HTML failure IS fatal — without it we have no chapter
        // content to render.
        val metaDoc = (api.fetchByDoi(doi) as? FictionResult.Success)
            ?.value?.response?.docs?.firstOrNull()
        val articleUrl = articleUrlForDoi(doi, metaDoc?.journal ?: DEFAULT_JOURNAL_SLUG)
        val htmlResult = api.fetchArticleHtml(articleUrl)
        val html = when (htmlResult) {
            is FictionResult.Success -> htmlResult.value
            is FictionResult.Failure -> return htmlResult
        }
        val body = extractArticleBody(
            html = html,
            abstract = metaDoc?.abstract?.firstOrNull(),
            maxSections = MAX_BODY_SECTIONS,
        )
        val info = ChapterInfo(
            id = chapterId,
            sourceChapterId = BODY_CHAPTER_KEY,
            index = 0,
            title = metaDoc?.title ?: "Article",
            publishedAt = null,
        )
        return FictionResult.Success(
            ChapterContent(
                info = info,
                htmlBody = body.html,
                plainBody = body.plain,
            ),
        )
    }

    // ─── auth-gated ─────────────────────────────────────────────────────

    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    override suspend fun setFollowed(
        fictionId: String,
        followed: Boolean,
    ): FictionResult<Unit> = FictionResult.Success(Unit)

    companion object {
        internal const val ROWS_PER_PAGE: Int = 50
        internal const val BODY_CHAPTER_KEY: String = "body"
        internal const val DEFAULT_JOURNAL_SLUG: String = "plosone"
        /** Soft cap on body sections rendered into the single v1
         *  chapter. Three sections (Introduction + Methods + Results
         *  or equivalent) is the sweet-spot length identified in #380:
         *  long enough that listeners get the substantive content,
         *  short enough that an hour-long Discussion section doesn't
         *  blow up the chapter buffer. The split-into-real-chapters
         *  follow-up rebuilds this on heading_1 boundaries. */
        internal const val MAX_BODY_SECTIONS: Int = 3
    }
}

// ─── helpers ──────────────────────────────────────────────────────────

/** Compose a stable PLOS fiction id from a DOI. */
internal fun plosFictionId(doi: String): String = "plos:" + doi.trim()

/** Issue #472 — PLOS journals article URL. The id query parameter is the
 *  DOI; we capture it from `?id=<doi>` and use it as the fictionId payload.
 *  Trailing/leading whitespace + URL-encoded slashes are normalised at
 *  the [PlosSource.matchUrl] site. */
internal val PLOS_ARTICLE_URL_PATTERN: Regex = Regex(
    """^https?://journals\.plos\.org/[\w./-]+/article\?(?:[^=&]*=[^&]*&)*id=([^&]+).*$""",
    RegexOption.IGNORE_CASE,
)

/** Compose the single chapter id for an article: `plos:<doi>::body`. */
internal fun chapterIdFor(fictionId: String): String = "${fictionId}::${PlosSource.BODY_CHAPTER_KEY}"

/** Strip the `plos:` prefix and return the DOI; returns null on ids
 *  that don't carry the prefix. Tolerates a trailing `::...` suffix
 *  (chapter ids are passed through this same helper). */
internal fun String.toPlosDoi(): String? =
    if (startsWith("plos:")) removePrefix("plos:").substringBefore("::").ifBlank { null }
    else null

/**
 * Map a PLOS DOI to its canonical article URL. DOIs at PLOS follow
 * the pattern `10.1371/journal.<slug>.<id>` where `<slug>` identifies
 * the journal (`pone` for PLOS ONE, `pbio` for PLOS Biology, ...).
 * The article URL needs the journal's directory name on the
 * journals.plos.org host, which doesn't quite match the DOI slug:
 *
 *  - `pone` → `plosone`
 *  - `pbio` → `plosbiology`
 *  - `pmed` → `plosmedicine`
 *  - `pcbi` → `ploscompbiol`
 *  - `pgen` → `plosgenetics`
 *  - `ppat` → `plospathogens`
 *  - `pntd` → `plosntds`
 *
 * The caller may pass a fallback [journalHint] (e.g. the `journal`
 * field from the Solr response) when the DOI's slug isn't in our
 * mapping table. If we can't pick a journal we fall back to PLOS ONE
 * — the URL just emits a 404 in the unlikely case the article isn't
 * actually there, and the fetch surfaces that as [FictionResult.NotFound].
 */
internal fun articleUrlForDoi(doi: String, journalHint: String? = null): String {
    val slug = doi.substringAfter("journal.", "")
        .substringBefore(".", "")
        .takeIf { it.isNotBlank() }
    val journalDir = when (slug) {
        "pone" -> "plosone"
        "pbio" -> "plosbiology"
        "pmed" -> "plosmedicine"
        "pcbi" -> "ploscompbiol"
        "pgen" -> "plosgenetics"
        "ppat" -> "plospathogens"
        "pntd" -> "plosntds"
        else -> journalHintToDir(journalHint) ?: "plosone"
    }
    // PLOS uses a query-string id rather than a path segment so the
    // DOI's slash doesn't need URL-encoding.
    return "https://journals.plos.org/$journalDir/article?id=$doi"
}

/** Best-effort fallback from the Solr `journal` field (e.g. "PLOS ONE",
 *  "PLOS Biology") to the article-URL directory. Returns null if the
 *  hint doesn't match anything we know about. */
private fun journalHintToDir(hint: String?): String? {
    if (hint.isNullOrBlank()) return null
    val normalized = hint.lowercase().replace(Regex("[^a-z]"), "")
    return when {
        normalized.contains("plosone") -> "plosone"
        normalized.contains("plosbiology") -> "plosbiology"
        normalized.contains("plosmedicine") -> "plosmedicine"
        normalized.contains("ploscompbiology") || normalized.contains("ploscomputationalbiology") -> "ploscompbiol"
        normalized.contains("plosgenetics") -> "plosgenetics"
        normalized.contains("plospathogens") -> "plospathogens"
        normalized.contains("plosneglected") || normalized.contains("plosntd") -> "plosntds"
        else -> null
    }
}

private fun PlosArticleHit.toSummary(): FictionSummary? {
    val doi = id?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return FictionSummary(
        id = plosFictionId(doi),
        sourceId = SourceIds.PLOS,
        title = (title?.trim()?.takeIf { it.isNotBlank() } ?: doi).trimHtml(),
        author = authorDisplay()?.takeIf { it.isNotBlank() } ?: "PLOS",
        description = abstract?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }?.trimHtml(),
        // Articles are published-once, not serialized — completed is
        // the right shape (matches Gutenberg / Standard Ebooks).
        status = FictionStatus.COMPLETED,
    )
}

private fun PlosArticleHit.authorDisplay(): String? {
    val list = authorDisplay ?: return null
    if (list.isEmpty()) return null
    return when {
        list.size == 1 -> list.first()
        list.size == 2 -> "${list[0]} and ${list[1]}"
        else -> "${list.first()} et al."
    }
}

private fun String.trimHtml(): String = htmlToPlainText().trim()

// ─── article-body extraction ──────────────────────────────────────────

internal data class PlosArticleBody(val html: String, val plain: String)

/**
 * Pull the abstract + first [maxSections] top-level body sections out
 * of a PLOS article's HTML page. Returns one combined HTML/plain pair
 * for the single v1 chapter.
 *
 * PLOS article pages render the JATS XML as semantic HTML with these
 * landmarks:
 *
 *  - `<div class="abstract">...</div>` — the abstract (sometimes
 *    `<div class="abstract abstract-type-abstract">`).
 *  - `<div class="section" id="section1">...</div>` — body sections,
 *    each opened by `<h2>` (Introduction, Methods, Results, ...).
 *  - References, supplementary materials, author contributions live
 *    below the body sections in their own divs we don't include.
 *
 * The combined output keeps the abstract first (so a TTS listener
 * hears the punchline up-front), then the first [maxSections] body
 * sections in document order, each with its `<h2>` heading preserved
 * as an anchor.
 *
 * If neither abstract nor body sections are present, we fall back to
 * a naive whole-page strip so the chapter is at least non-empty —
 * better than a "Chapter body unavailable" empty state on a page that
 * clearly has content, just in HTML the regex didn't recognize.
 */
internal fun extractArticleBody(
    html: String,
    abstract: String? = null,
    maxSections: Int = PlosSource.MAX_BODY_SECTIONS,
): PlosArticleBody {
    val parts = mutableListOf<String>()
    // Abstract — preferred source is the Solr-side `abstract` field
    // (clean, no markup); fall back to the in-HTML abstract block.
    val abstractHtml = abstract?.takeIf { it.isNotBlank() }?.let {
        "<section><h2>Abstract</h2>${escapeInlineParagraphs(it)}</section>"
    } ?: extractAbstractFromHtml(html)
    if (abstractHtml != null) parts.add(abstractHtml)

    // Body sections — each `<div class="section" id="sectionN">...`.
    val sections = extractBodySections(html, maxSections)
    parts.addAll(sections)

    // Fallback: strip the whole page if we found nothing structured.
    if (parts.isEmpty()) {
        val raw = html.htmlToPlainText().trim()
        return PlosArticleBody(html = raw, plain = raw)
    }

    val combinedHtml = parts.joinToString(separator = "\n")
    return PlosArticleBody(
        html = combinedHtml,
        plain = combinedHtml.htmlToPlainText(),
    )
}

/** Pull the first `<div class="abstract...">` block, if present. */
private fun extractAbstractFromHtml(html: String): String? {
    val re = Regex(
        """<div\b[^>]*class="[^"]*\babstract\b[^"]*"[^>]*>(.*?)</div>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    val match = re.find(html) ?: return null
    val inner = match.groupValues[1].trim()
    if (inner.isBlank()) return null
    return "<section><h2>Abstract</h2>$inner</section>"
}

/** Pull the first [max] `<div class="section" id="sectionN">` blocks,
 *  in document order. */
private fun extractBodySections(html: String, max: Int): List<String> {
    if (max <= 0) return emptyList()
    val opens = Regex(
        """<div\b[^>]*class="[^"]*\bsection\b[^"]*"[^>]*>""",
        RegexOption.IGNORE_CASE,
    ).findAll(html).toList()
    if (opens.isEmpty()) return emptyList()
    val out = mutableListOf<String>()
    for (open in opens) {
        if (out.size >= max) break
        val startIdx = open.range.last + 1
        val endIdx = findMatchingClose(html, startIdx, "<div", "</div>")
        if (endIdx < 0) continue
        val sliced = html.substring(startIdx, endIdx).trim()
        if (sliced.isNotBlank()) out.add("<section>$sliced</section>")
    }
    return out
}

/**
 * Walk forward from [from] in [html] keeping a depth counter on
 * [openMarker] / [closeMarker] occurrences and return the index of the
 * `</...>` that closes the level the slice opened at. Returns -1 if
 * the markers never balance (truncated input, unclosed div).
 */
private fun findMatchingClose(
    html: String,
    from: Int,
    openMarker: String,
    closeMarker: String,
): Int {
    var depth = 1
    var i = from
    while (i < html.length) {
        val nextOpen = html.indexOf(openMarker, i, ignoreCase = true)
        val nextClose = html.indexOf(closeMarker, i, ignoreCase = true)
        if (nextClose < 0) return -1
        if (nextOpen in 0 until nextClose) {
            depth++
            i = nextOpen + openMarker.length
        } else {
            depth--
            if (depth == 0) return nextClose
            i = nextClose + closeMarker.length
        }
    }
    return -1
}

/** Wrap each newline-separated chunk of a plain-text abstract in
 *  `<p>...</p>` so the renderer keeps paragraph breaks. PLOS sends
 *  abstracts as a single string in Solr; the wire-side abstract from
 *  HTML is already paragraphed. */
private fun escapeInlineParagraphs(plain: String): String =
    plain.split(Regex("\\n{2,}"))
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .joinToString(separator = "") { "<p>$it</p>" }

/**
 * Naive HTML → plain-text stripper. Same shape as the Wikipedia and
 * Notion strippers — the TTS pipeline downstream handles sentence
 * segmentation, abbreviation expansion, etc., so this only needs to
 * drop tags and decode the half-dozen entities PLOS actually emits.
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
