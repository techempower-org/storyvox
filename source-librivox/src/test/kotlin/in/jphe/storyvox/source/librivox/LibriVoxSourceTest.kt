package `in`.jphe.storyvox.source.librivox

import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.filter.FilterDimension
import `in`.jphe.storyvox.data.source.filter.FilterState
import `in`.jphe.storyvox.data.source.filter.FilterValue
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.source.librivox.net.LibriVoxApi
import `in`.jphe.storyvox.source.librivox.net.LibriVoxSection
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1015 — :source-librivox unit tests covering the pure, offline
 * surface of the source: identity, the filter contract (dimensions +
 * the sentinel-prefix stashing [LibriVoxSource.applyFilters] does), the
 * id-shape helpers, and the always-empty browse facets.
 *
 * Network-driven paths (popular / search / fictionDetail / chapter)
 * round-trip the LibriVox HTTP API and are verified at integration time
 * on the tablet; the JSON-shape contract those paths depend on is pinned
 * offline in [LibriVoxApiTest] against captured fixtures. This matches
 * the convention the other always-online sources (:source-gutenberg)
 * follow — test the pure seams, fixture-test the wire shape, manual-test
 * the round-trip.
 *
 * The no-op [LibriVoxApi] (a real client over a bare OkHttp) is never
 * actually called by these tests — every assertion exercises a code path
 * that returns before any network call.
 */
class LibriVoxSourceTest {

    private val noopApi = LibriVoxApi(OkHttpClient())

    private fun source(): LibriVoxSource = LibriVoxSource(noopApi)

    @Test fun `id and displayName use the LIBRIVOX source`() {
        val src = source()
        assertEquals(SourceIds.LIBRIVOX, src.id)
        assertEquals("LibriVox", src.displayName)
    }

    @Test fun `SourceIds LIBRIVOX is the canonical literal`() {
        // Pin the literal so a refactor that renames the constant fails
        // loudly — persisted fictions carry sourceId = "librivox".
        assertEquals("librivox", SourceIds.LIBRIVOX)
    }

    @Test fun `filterDimensions exposes title author and language as text inputs`() {
        val dims = source().filterDimensions()
        assertEquals(3, dims.size)
        val keys = dims.map { it.key }
        assertTrue(keys.containsAll(listOf("title", "author", "language")))
        assertTrue("all dimensions are free-text", dims.all { it is FilterDimension.Text })
    }

    @Test fun `applyFilters stashes title author and language under sentinel prefixes`() {
        val state = FilterState(
            mapOf(
                "title" to FilterValue.StringVal("Monte Cristo"),
                "author" to FilterValue.StringVal("Dumas"),
                "language" to FilterValue.StringVal("French"),
            ),
        )
        val q = source().applyFilters(SearchQuery(), state)
        assertTrue(q.tags.contains("${LibriVoxSource.TITLE_PREFIX}Monte Cristo"))
        assertTrue(q.tags.contains("${LibriVoxSource.AUTHOR_PREFIX}Dumas"))
        assertTrue(q.tags.contains("${LibriVoxSource.LANGUAGE_PREFIX}French"))
    }

    @Test fun `applyFilters ignores blank values`() {
        val state = FilterState(
            mapOf(
                "title" to FilterValue.StringVal("   "),
                "author" to FilterValue.StringVal(""),
            ),
        )
        val q = source().applyFilters(SearchQuery(), state)
        assertTrue("blank filter values produce no tags", q.tags.isEmpty())
    }

    @Test fun `applyFilters leaves the base query untouched when state is empty`() {
        val base = SearchQuery(term = "hello")
        val q = source().applyFilters(base, FilterState())
        assertEquals(base, q)
    }

    @Test fun `chapterIdFor builds bookId colon sectionNumber`() {
        val section = LibriVoxSection(id = "121010", sectionNumber = "1", title = "Ch 1")
        assertEquals("47:1", LibriVoxSource.chapterIdFor("47", section))
    }

    @Test fun `chapterIdFor falls back to section id when number is blank`() {
        val section = LibriVoxSection(id = "121010", sectionNumber = "", title = "Ch 1")
        assertEquals("47:121010", LibriVoxSource.chapterIdFor("47", section))
    }

    @Test fun `bookIdFromFictionId is identity (trimmed)`() {
        assertEquals("47", LibriVoxSource.bookIdFromFictionId("47"))
        assertEquals("47", LibriVoxSource.bookIdFromFictionId("  47  "))
    }

    @Test fun `LibriVox does not need a source URL to rebuild`() {
        // The book id IS the fiction id, so MetadataBackfillWorker can
        // rebuild a synced placeholder from the id alone — LibriVox must
        // NOT be in the URL-needed set (#989).
        assertFalse(SourceIds.idNeedsSourceUrlToRebuild(SourceIds.LIBRIVOX))
    }

    @Test fun `byGenre returns empty`() = runTest {
        val result = source().byGenre("anything", page = 1) as FictionResult.Success
        assertTrue(result.value.items.isEmpty())
    }

    @Test fun `genres returns empty list`() = runTest {
        val result = source().genres() as FictionResult.Success
        assertTrue(result.value.isEmpty())
    }

    @Test fun `followsList is empty`() = runTest {
        val result = source().followsList() as FictionResult.Success
        assertTrue(result.value.items.isEmpty())
    }

    @Test fun `setFollowed is a no-op success`() = runTest {
        val result = source().setFollowed("47", followed = true)
        assertTrue(result is FictionResult.Success)
    }

    @Test fun `user-agent identifies storyvox-librivox`() {
        assertTrue(LibriVoxSource.USER_AGENT.contains("storyvox-librivox"))
        assertTrue(LibriVoxSource.USER_AGENT.contains("github.com/techempower-org/storyvox"))
    }
}
