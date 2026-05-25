package `in`.jphe.storyvox.source.gutenberg

import `in`.jphe.storyvox.source.gutenberg.net.GutendexApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pin the Gutendex `/books` URL shape across the filter axes declared
 * in [GutenbergSource.filterDimensions] — sort, topic (category),
 * languages, and free-form search. Guards two prior bugs:
 *
 *  - language filter prepended the literal string `"language:en"` to
 *    [SearchQuery.term] and let Gutendex's `search` field interpret
 *    it as part of the title query, which poisoned every result
 *  - category was written to `SearchQuery.genres` which the search
 *    adapter dropped on the floor
 *
 * URL-pinning rather than fake-API end-to-end keeps the test fast and
 * makes future encoding tweaks surface as a single loud red line.
 */
class GutendexBooksPathTest {

    @Test
    fun `booksPath with no params returns plain books path`() {
        assertEquals("/books", GutendexApi.booksPath())
    }

    @Test
    fun `booksPath search-only matches legacy shape`() {
        // Note: legacy was `/books?search=foo&page=1`; we drop the
        // `&page=1` because Gutendex treats no page param as page 1.
        // Semantically equivalent.
        val path = GutendexApi.booksPath(search = "frankenstein")
        assertEquals("/books?search=frankenstein", path)
    }

    @Test
    fun `booksPath emits sort=popular for popular tab`() {
        assertEquals("/books?sort=popular", GutendexApi.booksPath(sort = "popular"))
    }

    @Test
    fun `booksPath emits sort=descending for newest tab`() {
        assertEquals("/books?sort=descending", GutendexApi.booksPath(sort = "descending"))
    }

    @Test
    fun `booksPath skips page=1 but emits higher pages`() {
        assertFalse(
            "page=1 must not appear in URL",
            GutendexApi.booksPath(search = "foo", page = 1).contains("page="),
        )
        assertTrue(
            "page>1 must appear",
            GutendexApi.booksPath(search = "foo", page = 3).endsWith("&page=3"),
        )
    }

    @Test
    fun `booksPath URL-encodes spaces in search term`() {
        val path = GutendexApi.booksPath(search = "war and peace")
        // Default URLEncoder encodes spaces as '+'.
        assertTrue("must encode space as +", path.contains("search=war+and+peace"))
    }

    @Test
    fun `booksPath emits topic for category filter`() {
        val path = GutendexApi.booksPath(topic = "Science Fiction")
        assertTrue(
            "topic=Science+Fiction must be in URL",
            path.contains("topic=Science+Fiction"),
        )
    }

    @Test
    fun `booksPath skips blank topic`() {
        val path = GutendexApi.booksPath(search = "x", topic = "  ")
        assertFalse("blank topic must drop", path.contains("topic="))
    }

    @Test
    fun `booksPath joins languages with comma in sorted order`() {
        val path = GutendexApi.booksPath(languages = setOf("fr", "en", "de"))
        // Sorted alphabetically, comma-joined; comma URL-encodes to %2C.
        assertTrue(
            "languages must be sorted comma-joined",
            path.contains("languages=de%2Cen%2Cfr"),
        )
    }

    @Test
    fun `booksPath skips empty languages set`() {
        val path = GutendexApi.booksPath(search = "x", languages = emptySet())
        assertFalse("empty languages must drop", path.contains("languages="))
    }

    @Test
    fun `booksPath stacks every filter axis into a single URL`() {
        // Integration shape — every dimension wired at once with a
        // deterministic param order: search → topic → languages →
        // sort → page.
        val path = GutendexApi.booksPath(
            search = "frankenstein",
            topic = "Horror",
            languages = setOf("en", "fr"),
            sort = "popular",
            page = 2,
        )
        val expected = "/books?" +
            "search=frankenstein" +
            "&topic=Horror" +
            "&languages=en%2Cfr" +
            "&sort=popular" +
            "&page=2"
        assertEquals(expected, path)
    }

    @Test
    fun `booksPath does not poison search term with language tokens`() {
        // The original bug: `applyFilters` prepended `language:en` to
        // [SearchQuery.term]. With the new flow the term is the raw
        // user query — language travels through its own `languages`
        // param — so no `language:` literal can survive into the URL.
        val path = GutendexApi.booksPath(
            search = "shelley",
            languages = setOf("en"),
        )
        assertFalse(
            "search term must not carry the literal 'language:' marker",
            path.contains("language%3A"),
        )
        assertTrue("languages param must carry the code", path.contains("languages=en"))
        assertTrue("search must stay clean", path.contains("search=shelley"))
    }
}
