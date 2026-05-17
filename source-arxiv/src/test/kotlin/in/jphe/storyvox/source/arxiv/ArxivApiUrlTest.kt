package `in`.jphe.storyvox.source.arxiv

import `in`.jphe.storyvox.source.arxiv.net.ArxivApi
import `in`.jphe.storyvox.source.arxiv.net.composeRecentUrl
import `in`.jphe.storyvox.source.arxiv.net.composeSearchUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #378 — pins the URL composition for the two arXiv query
 * surfaces. The constants live on [ArxivApi] (`QUERY_BASE`,
 * `DEFAULT_CATEGORY`, `DEFAULT_PAGE_SIZE`); a quiet drift here would
 * break the browse landing and search at CI time, which is exactly
 * where we want a regression to surface.
 */
class ArxivApiUrlTest {

    @Test
    fun `composeRecentUrl defaults to cs_AI and 50 results`() {
        val url = composeRecentUrl(category = ArxivApi.DEFAULT_CATEGORY)
        // Pins the sort-order shape arXiv's user manual documents
        // (https://info.arxiv.org/help/api/). URLEncoder leaves `:`
        // alone since it's an unreserved sub-delim in query strings;
        // arXiv accepts both `cat:` and `cat%3A`.
        assertEquals(
            "https://export.arxiv.org/api/query" +
                "?search_query=cat:cs.AI" +
                "&sortBy=submittedDate&sortOrder=descending" +
                "&start=0&max_results=50",
            url,
        )
    }

    @Test
    fun `composeRecentUrl paginates with start offset`() {
        val url = composeRecentUrl(category = "cs.LG", start = 100, maxResults = 25)
        assertTrue("starts with QUERY_BASE", url.startsWith(ArxivApi.QUERY_BASE))
        assertTrue("category present", url.contains("search_query=cat:cs.LG"))
        assertTrue("start offset wired", url.contains("&start=100"))
        assertTrue("max_results override wired", url.contains("&max_results=25"))
    }

    @Test
    fun `composeSearchUrl encodes the user term`() {
        val url = composeSearchUrl(term = "attention is all you need")
        // Spaces encode as `+` in URLEncoder default; arXiv accepts
        // both `+` and `%20`. The `:` in the qualifier prefix stays
        // literal — see [composeRecentUrl] test for the same call out.
        assertEquals(
            "https://export.arxiv.org/api/query" +
                "?search_query=all:attention+is+all+you+need" +
                "&sortBy=submittedDate&sortOrder=descending" +
                "&start=0&max_results=50",
            url,
        )
    }

    @Test
    fun `composeSearchUrl trims leading and trailing whitespace`() {
        val url = composeSearchUrl(term = "  transformer  ")
        assertTrue(url.contains("search_query=all:transformer&"))
        // Internal sentinel check — the trimmed term doesn't leak a
        // trailing encoded space into the URL.
        assertTrue("no trailing space encoded", !url.contains("transformer+&"))
    }
}
