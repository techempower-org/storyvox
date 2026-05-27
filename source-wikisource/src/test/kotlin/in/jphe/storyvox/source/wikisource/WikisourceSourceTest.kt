package `in`.jphe.storyvox.source.wikisource

import `in`.jphe.storyvox.data.source.filter.FilterDimension
import `in`.jphe.storyvox.data.source.filter.FilterState
import `in`.jphe.storyvox.data.source.filter.FilterValue
import `in`.jphe.storyvox.data.source.model.SearchOrder
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.data.source.model.SortDirection
import `in`.jphe.storyvox.source.wikisource.net.WikisourceApi
import `in`.jphe.storyvox.source.wikisource.net.WikisourceAllPagesEntry
import `in`.jphe.storyvox.source.wikisource.net.WikisourceAllPagesQuery
import `in`.jphe.storyvox.source.wikisource.net.WikisourceAllPagesResponse
import `in`.jphe.storyvox.source.wikisource.net.WikisourceCategoryMember
import `in`.jphe.storyvox.source.wikisource.net.WikisourceCategoryQuery
import `in`.jphe.storyvox.source.wikisource.net.WikisourceCategoryQueryResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Wikisource (#376).
 *
 * Covers the three behaviors the issue calls out as risk surfaces:
 *
 *  1. The category-members JSON parse for the browse landing
 *     (`Category:Validated_texts`).
 *  2. Subpage discovery — the `list=allpages&apprefix=Parent/` shape
 *     and the subpage display-name truncation.
 *  3. Chapter-split fallback for single-page works that don't have
 *     subpages (Parsoid `<section data-mw-section-id>` shape, same
 *     splitter shape as `:source-wikipedia`).
 *
 * No network — these are pure parsing/string tests against fixture
 * JSON or fixture HTML.
 */
class WikisourceSourceTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ─── 1. Featured / curated landing parse ──────────────────────────

    @Test
    fun `categoryMembers parses Validated_texts JSON into category entries`() {
        // Shape borrowed from a real Wikisource Action API response —
        // pageid + ns + title for each member, wrapped under
        // `query.categorymembers`.
        val payload = """
            {
              "batchcomplete": "",
              "query": {
                "categorymembers": [
                  {"pageid": 1234, "ns": 0, "title": "Hamlet"},
                  {"pageid": 5678, "ns": 0, "title": "Pride and Prejudice"},
                  {"pageid": 9012, "ns": 0, "title": "The Adventures of Sherlock Holmes"}
                ]
              }
            }
        """.trimIndent()

        val parsed = json.decodeFromString<WikisourceCategoryQueryResponse>(payload)
        val members = parsed.query?.categorymembers.orEmpty()

        assertEquals(3, members.size)
        assertEquals("Hamlet", members[0].title)
        assertEquals("Pride and Prejudice", members[1].title)
        assertEquals("The Adventures of Sherlock Holmes", members[2].title)

        // Spot-check the fiction-id encoding off the parsed title —
        // spaces become underscores so the id mirrors the Wikisource URL.
        assertEquals("wikisource:Pride_and_Prejudice", wikisourceFictionId(members[1].title))
        assertEquals(
            "wikisource:The_Adventures_of_Sherlock_Holmes",
            wikisourceFictionId(members[2].title),
        )
    }

    @Test
    fun `categoryMembers parses empty result without crashing`() {
        // The query.categorymembers field is optional in the wire model
        // (we default it to empty in the @Serializable data class). A
        // missing category or upstream rate-limit-and-truncate scenario
        // should yield an empty result rather than throwing.
        val payload = """{"batchcomplete": "", "query": {"categorymembers": []}}"""
        val parsed = json.decodeFromString<WikisourceCategoryQueryResponse>(payload)
        assertTrue(parsed.query?.categorymembers.orEmpty().isEmpty())
    }

    // ─── 2. Subpage discovery ─────────────────────────────────────────

    @Test
    fun `subpages parses allpages JSON and ids decode to readable titles`() {
        // War_and_Peace is the canonical multi-volume example called
        // out in the issue. The wire shape is
        // `query.allpages: [{pageid, ns, title}]` with the prefix-
        // matched parent included in every title. We rely on
        // alphabetical ordering by MediaWiki for chapter-order
        // approximation; verify the parse preserves that order.
        val payload = """
            {
              "batchcomplete": "",
              "query": {
                "allpages": [
                  {"pageid": 1, "ns": 0, "title": "War and Peace/Book One"},
                  {"pageid": 2, "ns": 0, "title": "War and Peace/Book Two"},
                  {"pageid": 3, "ns": 0, "title": "War and Peace/Book Three"}
                ]
              }
            }
        """.trimIndent()

        val parsed = json.decodeFromString<WikisourceAllPagesResponse>(payload)
        val titles = parsed.query?.allpages.orEmpty().map { it.title }

        assertEquals(3, titles.size)
        assertEquals("War and Peace/Book One", titles[0])

        // Subpage display-name truncation drops the parent prefix and
        // restores spaces — what the user sees as the chapter title in
        // the FictionDetail listing.
        assertEquals(
            "Book One",
            subpageDisplayName(parentTitle = "War_and_Peace", subpageTitle = titles[0].replace(' ', '_')),
        )
        assertEquals(
            "Book Two",
            subpageDisplayName(parentTitle = "War_and_Peace", subpageTitle = titles[1].replace(' ', '_')),
        )
    }

    @Test
    fun `subpageDisplayName preserves full title when prefix doesn't match`() {
        // Defensive — if MediaWiki ever returns a subpage that doesn't
        // start with the parent prefix (impossible with apprefix= but
        // belt-and-braces), the display name shouldn't be empty.
        assertEquals(
            "Some Unrelated Title",
            subpageDisplayName(parentTitle = "War_and_Peace", subpageTitle = "Some_Unrelated_Title"),
        )
    }

    @Test
    fun `subpage chapter id encoding round-trips through parser`() {
        val fictionId = wikisourceFictionId("War and Peace")
        assertEquals("wikisource:War_and_Peace", fictionId)
        val chapterId = subpageChapterId(fictionId, 2)
        assertEquals("wikisource:War_and_Peace::sub-2", chapterId)
        // The parser pulls the parent title off the chapter id (before
        // the `::` delimiter); the sub-N index is read off the suffix.
        assertEquals("War_and_Peace", fictionId.toWikisourceTitle())
        assertEquals("War_and_Peace", chapterId.toWikisourceTitle())
    }

    // ─── 3. Chapter-split fallback (single-page works) ────────────────

    @Test
    fun `single-page work with sections produces one chapter per section`() {
        // A Shakespeare-play-like work transcribed as one mainspace
        // page with `<section data-mw-section-id>` boundaries marking
        // acts/scenes. Heading splits map cleanly to chapter rows
        // when no subpages exist.
        val html = """
            <section data-mw-section-id="0">
              <p>Dramatis Personae.</p>
            </section>
            <section data-mw-section-id="1">
              <h2>Act I</h2>
              <p>Scene 1 dialogue.</p>
            </section>
            <section data-mw-section-id="2">
              <h2>Act II</h2>
              <p>Scene 1 dialogue.</p>
            </section>
        """.trimIndent()

        val sections = splitTopLevelSections(html)
        assertEquals(3, sections.size)
        // Section 0 on Wikisource uses "Text" as the default label
        // (not "Introduction" — most validated texts start their prose
        // in section 0 rather than carrying a separate intro).
        assertEquals("Text", sections[0].title)
        assertEquals("Act I", sections[1].title)
        assertEquals("Act II", sections[2].title)
        assertTrue(sections[1].html.contains("Scene 1 dialogue"))
    }

    @Test
    fun `single-page work with no section tags becomes one Text chapter`() {
        val html = "<p>A short story body with no Parsoid section wrappers.</p>"
        val sections = splitTopLevelSections(html)
        assertEquals(1, sections.size)
        assertEquals("Text", sections[0].title)
        assertTrue(sections[0].html.contains("short story body"))
    }

    @Test
    fun `boilerplate footer sections are dropped from the split`() {
        // Wikisource pages frequently have a "Notes" or "License"
        // footer section that's not part of the narrative text.
        val html = """
            <section data-mw-section-id="0"><p>Prose.</p></section>
            <section data-mw-section-id="1"><h2>Chapter I</h2><p>Once upon a time.</p></section>
            <section data-mw-section-id="2"><h2>Notes</h2><p>[1] editorial note</p></section>
            <section data-mw-section-id="3"><h2>License</h2><p>Public domain.</p></section>
        """.trimIndent()

        val sections = splitTopLevelSections(html)
        val titles = sections.map { it.title }
        assertTrue(titles.contains("Text"))
        assertTrue(titles.contains("Chapter I"))
        assertFalse(titles.contains("Notes"))
        assertFalse(titles.contains("License"))
    }

    // ─── HTML scrubbing ──────────────────────────────────────────────

    @Test
    fun `cruft scrubber removes pagenum and edit links`() {
        // pagenum is Wikisource-specific (page-number callouts injected
        // by the Page:/Index: transclusion). Reads as `[123]` markers
        // through TTS, which is noise for listeners.
        val html = """
            <p>A passage of prose<span class="pagenum">[123]</span> continues.</p>
            <h2>Chapter Two<span class="mw-editsection">[<a>edit</a>]</span></h2>
            <p>Next chapter starts.<sup id="cite_ref-1" class="reference">[1]</sup></p>
        """.trimIndent()

        val cleaned = scrubWikisourceCruft(html)
        assertFalse(cleaned.contains("mw-editsection"))
        assertFalse(cleaned.contains("pagenum"))
        assertFalse(cleaned.contains("[123]"))
        assertFalse(cleaned.contains("[1]"))
    }

    @Test
    fun `htmlToPlainText handles entities and block tags`() {
        val html = "<p>O Romeo &mdash; Romeo&nbsp;Romeo.</p><p>Wherefore &amp; thou.</p>"
        val plain = html.htmlToPlainText()
        assertTrue(plain.contains("O Romeo — Romeo Romeo."))
        assertTrue(plain.contains("Wherefore & thou."))
        assertFalse(plain.contains("&nbsp;"))
        assertFalse(plain.contains("&mdash;"))
    }

    @Test
    fun `fictionId round-trips through title extraction`() {
        val id = wikisourceFictionId("Hamlet")
        assertEquals("wikisource:Hamlet", id)
        assertEquals("Hamlet", id.toWikisourceTitle())

        val sectionChapter = sectionChapterId(id, 5)
        assertEquals("wikisource:Hamlet::section-5", sectionChapter)
        // The title extractor stops at the `::` delimiter so chapter
        // ids carry the parent title cleanly.
        assertEquals("Hamlet", sectionChapter.toWikisourceTitle())
    }

    @Test
    fun `toWikisourceTitle returns null for non-wikisource ids`() {
        assertEquals(null, "wikipedia:Hamlet".toWikisourceTitle())
        assertEquals(null, "royalroad:12345".toWikisourceTitle())
        assertEquals(null, "".toWikisourceTitle())
    }

    // ─── Filter dimensions & resolution (#797) ───────────────────────

    // filterDimensions / applyFilters don't touch the network — a source
    // backed by a bare API client is enough to exercise them.
    private fun source(): WikisourceSource =
        WikisourceSource(WikisourceApi(okhttp3.OkHttpClient()))

    @Test
    fun `filterDimensions exposes Sort, Form, Author and proofread toggle`() {
        val dims = source().filterDimensions()
        val keys = dims.map { it.key }
        assertTrue(keys.contains("sort"))
        assertTrue(keys.contains(FILTER_FORM))
        assertTrue(keys.contains(FILTER_AUTHOR))
        assertTrue(keys.contains(FILTER_INCLUDE_PROOFREAD))

        val form = dims.first { it.key == FILTER_FORM } as FilterDimension.Select
        assertEquals(
            listOf("Plays", "Novels", "Poetry", "Short_stories", "Essays", "Letters"),
            form.options,
        )
        val author = dims.first { it.key == FILTER_AUTHOR }
        assertTrue(author is FilterDimension.Text)
        val proofread = dims.first { it.key == FILTER_INCLUDE_PROOFREAD } as FilterDimension.Toggle
        assertFalse(proofread.default)
    }

    @Test
    fun `applyFilters threads form selection into tags`() {
        val state = FilterState().with(FILTER_FORM, FilterValue.StringVal("Poetry"))
        val q = source().applyFilters(SearchQuery(), state)
        assertTrue(q.tags.contains("form:Poetry"))
    }

    @Test
    fun `applyFilters slugifies author and threads it into tags`() {
        val state = FilterState().with(FILTER_AUTHOR, FilterValue.StringVal("Walt Whitman"))
        val q = source().applyFilters(SearchQuery(), state)
        assertTrue(q.tags.contains("author:Walt_Whitman"))
    }

    @Test
    fun `applyFilters threads proofread toggle into tags only when on`() {
        val on = source().applyFilters(
            SearchQuery(),
            FilterState().with(FILTER_INCLUDE_PROOFREAD, FilterValue.BoolVal(true)),
        )
        assertTrue(on.tags.contains("proofread"))

        val off = source().applyFilters(
            SearchQuery(),
            FilterState().with(FILTER_INCLUDE_PROOFREAD, FilterValue.BoolVal(false)),
        )
        assertFalse(off.tags.contains("proofread"))
    }

    @Test
    fun `applyFilters still maps Sort newest and oldest to LAST_UPDATE`() {
        val newest = source().applyFilters(
            SearchQuery(),
            FilterState().with("sort", FilterValue.StringVal("newest")),
        )
        assertEquals(SearchOrder.LAST_UPDATE, newest.orderBy)
        assertEquals(SortDirection.DESC, newest.direction)

        val oldest = source().applyFilters(
            SearchQuery(),
            FilterState().with("sort", FilterValue.StringVal("oldest")),
        )
        assertEquals(SearchOrder.LAST_UPDATE, oldest.orderBy)
        assertEquals(SortDirection.ASC, oldest.direction)
    }

    @Test
    fun `applyFilters with no active filters leaves the query untouched`() {
        val base = SearchQuery(term = "hamlet")
        assertEquals(base, source().applyFilters(base, FilterState()))
    }

    @Test
    fun `resolveFilterCategories maps form to a single category`() {
        assertEquals(
            listOf("Category:Plays"),
            resolveFilterCategories(setOf("form:Plays")),
        )
    }

    @Test
    fun `resolveFilterCategories unions quality tiers when only proofread is set`() {
        assertEquals(
            listOf("Category:Validated_texts", "Category:Proofread_texts"),
            resolveFilterCategories(setOf("proofread")),
        )
    }

    @Test
    fun `resolveFilterCategories favors form over proofread when both present`() {
        // Form already spans both tiers, so the proofread toggle is a
        // no-op alongside an explicit form selection.
        assertEquals(
            listOf("Category:Novels"),
            resolveFilterCategories(setOf("form:Novels", "proofread")),
        )
    }

    @Test
    fun `resolveFilterCategories ignores author and returns empty for full-text routing`() {
        // Author has no reliable category — it must fall through to
        // full-text search, so the category resolver returns nothing.
        assertTrue(resolveFilterCategories(setOf("author:Walt_Whitman")).isEmpty())
        assertTrue(resolveFilterCategories(emptySet()).isEmpty())
    }

    @Test
    fun `resolveAuthorTerm un-slugifies the author back to a search term`() {
        assertEquals("Walt Whitman", resolveAuthorTerm(setOf("author:Walt_Whitman")))
        assertEquals(null, resolveAuthorTerm(setOf("form:Poetry")))
        assertEquals(null, resolveAuthorTerm(emptySet()))
    }

    @Test
    fun `end-to-end form plus author resolves to form category walk`() {
        // Select form=Poetry + author=Whitman. Form + Author can't
        // intersect server-side, so the form category walk wins and the
        // author is dropped (issue #797 scope note). Verify the resolved
        // SearchQuery the search path would act on.
        val state = FilterState()
            .with(FILTER_FORM, FilterValue.StringVal("Poetry"))
            .with(FILTER_AUTHOR, FilterValue.StringVal("Whitman"))
        val q = source().applyFilters(SearchQuery(), state)
        assertEquals(listOf("Category:Poetry"), resolveFilterCategories(q.tags))
        // The author token is still present for completeness, but the
        // category walk short-circuits before it's consulted.
        assertEquals("Whitman", resolveAuthorTerm(q.tags))
    }

    // ─── Wire-model defaults ─────────────────────────────────────────

    @Test
    fun `wire models default to empty collections when query field is missing`() {
        // Smoke test the @Serializable defaults — covers the case
        // where MediaWiki returns an error payload without a `query`
        // field at all. Should yield an empty list rather than NPE.
        val payload = """{"batchcomplete": ""}"""
        val parsed = json.decodeFromString<WikisourceAllPagesResponse>(payload)
        assertTrue(parsed.query?.allpages.orEmpty().isEmpty())
    }

    @Test
    fun `category member and allpages entry models accept null pageid`() {
        // Wikimedia occasionally returns category members without a
        // pageid (deleted-since-cached entries). The data classes
        // default pageid to null so the parse doesn't fail.
        val m = WikisourceCategoryMember(title = "Hamlet")
        assertEquals(null, m.pageid)
        val a = WikisourceAllPagesEntry(title = "Hamlet")
        assertEquals(null, a.pageid)
        // Smoke test the wrapper types instantiate at all.
        val q = WikisourceAllPagesQuery(allpages = listOf(a))
        val r = WikisourceAllPagesResponse(query = q)
        val c = WikisourceCategoryQuery(categorymembers = listOf(m))
        val cr = WikisourceCategoryQueryResponse(query = c)
        assertEquals(1, r.query?.allpages.orEmpty().size)
        assertEquals(1, cr.query?.categorymembers.orEmpty().size)
    }
}
