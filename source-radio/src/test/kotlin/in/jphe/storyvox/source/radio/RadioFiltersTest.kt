package `in`.jphe.storyvox.source.radio

import `in`.jphe.storyvox.data.source.filter.FilterDimension
import `in`.jphe.storyvox.data.source.filter.FilterState
import `in`.jphe.storyvox.data.source.filter.FilterValue
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.source.radio.config.RadioConfig
import `in`.jphe.storyvox.source.radio.net.RadioBrowserApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Issue #795 — `:source-radio` filterDimensions + the multi-facet
 * search path through Radio Browser.
 *
 * Covers:
 *  - [RadioSource.filterDimensions] declares country / language / tag
 *    as free-text inputs (Radio Browser's facet taxonomies are
 *    enormous + free-form, no select picker).
 *  - [RadioSource.applyFilters] stashes filter values under sentinel
 *    prefixes in `SearchQuery.tags` so the universal query model can
 *    carry them through to [RadioSource.search].
 *  - The peeled filter values narrow the *local* match list (curated
 *    + starred) by country / language / tag — the remote network call
 *    is exercised at integration time, not here.
 *
 * Network calls are bypassed by injecting a no-op
 * [RadioBrowserApi] (the production class with an empty OkHttpClient —
 * any call would fail-fast and the source's catch-all maps that to
 * `emptyList()`, so local matches remain the only observed output).
 */
class RadioFiltersTest {

    private val noopApi = RadioBrowserApi(OkHttpClient())

    private fun fakeConfig(starred: List<RadioStation> = emptyList()) = object : RadioConfig {
        private val state = MutableStateFlow(starred)
        override val starredStations: Flow<List<RadioStation>> = state.asStateFlow()
        override suspend fun snapshot(): List<RadioStation> = state.value
        override suspend fun star(station: RadioStation) { state.value = state.value + station }
        override suspend fun unstar(stationId: String) {
            state.value = state.value.filterNot { it.id == stationId }
        }
        override suspend fun isStarred(stationId: String): Boolean =
            state.value.any { it.id == stationId }
    }

    private fun source(config: RadioConfig = fakeConfig()): RadioSource =
        RadioSource(noopApi, config)

    // ─── filterDimensions ─────────────────────────────────────────────

    @Test fun `filterDimensions exposes country language and tag as text inputs`() {
        val dims = source().filterDimensions()
        assertEquals(3, dims.size)
        val keys = dims.map { it.key }.toSet()
        assertEquals(setOf("country", "language", "tags"), keys)
        // All three are free-form text — Radio Browser's facet
        // taxonomies are too large for a select picker.
        assertTrue(dims.all { it is FilterDimension.Text })
    }

    // ─── applyFilters ──────────────────────────────────────────────────

    @Test fun `applyFilters stashes country under sentinel prefix`() {
        val q = source().applyFilters(
            base = SearchQuery(),
            state = FilterState(
                values = mapOf("country" to FilterValue.StringVal("France")),
            ),
        )
        assertTrue(
            "country must land in tags with the country: prefix",
            q.tags.contains("${RadioSource.COUNTRY_PREFIX}France"),
        )
    }

    @Test fun `applyFilters stashes language under sentinel prefix`() {
        val q = source().applyFilters(
            base = SearchQuery(),
            state = FilterState(
                values = mapOf("language" to FilterValue.StringVal("french")),
            ),
        )
        assertTrue(q.tags.contains("${RadioSource.LANGUAGE_PREFIX}french"))
    }

    @Test fun `applyFilters stashes tag under sentinel prefix`() {
        val q = source().applyFilters(
            base = SearchQuery(),
            state = FilterState(
                values = mapOf("tags" to FilterValue.StringVal("jazz")),
            ),
        )
        assertTrue(q.tags.contains("${RadioSource.TAG_PREFIX}jazz"))
    }

    @Test fun `applyFilters stacks all three axes onto SearchQuery tags`() {
        val q = source().applyFilters(
            base = SearchQuery(),
            state = FilterState(
                values = mapOf(
                    "country" to FilterValue.StringVal("Germany"),
                    "language" to FilterValue.StringVal("german"),
                    "tags" to FilterValue.StringVal("classical"),
                ),
            ),
        )
        assertTrue(q.tags.contains("${RadioSource.COUNTRY_PREFIX}Germany"))
        assertTrue(q.tags.contains("${RadioSource.LANGUAGE_PREFIX}german"))
        assertTrue(q.tags.contains("${RadioSource.TAG_PREFIX}classical"))
    }

    @Test fun `applyFilters drops blank inputs`() {
        val q = source().applyFilters(
            base = SearchQuery(),
            state = FilterState(
                values = mapOf(
                    "country" to FilterValue.StringVal("   "),
                    "language" to FilterValue.StringVal(""),
                ),
            ),
        )
        assertTrue("blank filters must not pollute tags", q.tags.isEmpty())
    }

    // ─── search filter behavior ───────────────────────────────────────

    @Test fun `search with US country filter matches curated US stations`() = runTest {
        val q = SearchQuery(
            term = "",
            tags = setOf("${RadioSource.COUNTRY_PREFIX}United States"),
        )
        val result = source().search(q) as FictionResult.Success
        // All curated stations are US — filter is permissive.
        assertTrue(
            "US country filter should keep curated stations",
            result.value.items.any { it.id == "kvmr:live" },
        )
    }

    @Test fun `search with non-matching country drops every curated station`() = runTest {
        val q = SearchQuery(
            term = "",
            tags = setOf("${RadioSource.COUNTRY_PREFIX}France"),
        )
        val result = source().search(q) as FictionResult.Success
        // No curated station is in France, so the local list is empty.
        // The remote call fails (noop api with no network) so the
        // combined list is also empty — this is the negative path.
        assertFalse(
            "French filter must not surface KVMR",
            result.value.items.any { it.id == "kvmr:live" },
        )
    }

    @Test fun `search with tag filter narrows to matching curated stations`() = runTest {
        val q = SearchQuery(
            term = "",
            tags = setOf("${RadioSource.TAG_PREFIX}ambient"),
        )
        val result = source().search(q) as FictionResult.Success
        // SomaFM Groove Salad has the ambient tag.
        assertTrue(
            "ambient tag filter should surface SomaFM Groove Salad",
            result.value.items.any { it.id == "somafm-groove-salad:live" },
        )
        // KVMR is community radio — no "ambient" tag — should drop.
        assertFalse(
            "ambient tag filter should drop KVMR (no ambient tag)",
            result.value.items.any { it.id == "kvmr:live" },
        )
    }

    @Test fun `search term plus tag filter is AND-composed locally`() = runTest {
        // Term "KVMR" matches the KVMR station name locally; adding an
        // "ambient" tag filter narrows out KVMR (no ambient tag).
        val q = SearchQuery(
            term = "KVMR",
            tags = setOf("${RadioSource.TAG_PREFIX}ambient"),
        )
        val result = source().search(q) as FictionResult.Success
        assertFalse(
            "KVMR-the-station does not have an ambient tag — both filters AND",
            result.value.items.any { it.id == "kvmr:live" },
        )
    }

    @Test fun `search empty term and no filters mirrors popular`() = runTest {
        // Existing contract preserved — empty everything still surfaces
        // the popular roster so the Search tab isn't blank-on-arrival.
        val src = source()
        val popular = (src.popular() as FictionResult.Success).value.items
        val searched = (src.search(SearchQuery(term = "")) as FictionResult.Success).value.items
        assertEquals(popular.map { it.id }, searched.map { it.id })
    }
}
