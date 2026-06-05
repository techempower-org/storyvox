package `in`.jphe.storyvox.source.radio

import `in`.jphe.storyvox.data.source.SourceIds
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #417 — :source-radio unit tests. Covers:
 *
 *  - Rename round-trip: `SourceIds.RADIO` resolves; `SourceIds.KVMR`
 *    alias preserved.
 *  - Curated-list contract: every seed station has non-blank required
 *    fields + an HTTPS stream URL.
 *  - User-starred persistence: a starred station shows up in popular()
 *    alongside the curated set.
 *  - Migration shape: the v0.5.20+ `kvmr:live` fictionId still
 *    resolves through the renamed source (no library regression).
 *
 * Radio Browser HTTP calls aren't exercised here — that's covered by
 * [RadioBrowserApiTest] against fixture JSON. The source-level tests
 * pass a no-op API stub so the unit shape stays fast and offline.
 */
class RadioSourceTest {

    private val noopApi = RadioBrowserApi(OkHttpClient())

    private fun fakeConfig(starred: List<RadioStation> = emptyList()) = object : RadioConfig {
        private val state = MutableStateFlow(starred)
        override val starredStations: Flow<List<RadioStation>> = state.asStateFlow()
        override suspend fun snapshot(): List<RadioStation> = state.value
        override suspend fun star(station: RadioStation) {
            state.value = state.value + station
        }
        override suspend fun unstar(stationId: String) {
            state.value = state.value.filterNot { it.id == stationId }
        }
        override suspend fun isStarred(stationId: String): Boolean =
            state.value.any { it.id == stationId }
    }

    private fun source(config: RadioConfig = fakeConfig()): RadioSource =
        RadioSource(noopApi, config)

    @Test fun `id and displayName use the renamed RADIO source`() {
        val src = source()
        assertEquals(SourceIds.RADIO, src.id)
        assertEquals("Radio", src.displayName)
    }

    @Test fun `SourceIds RADIO is the new canonical id and KVMR is preserved as alias`() {
        // Pin the literals so a refactor that accidentally removes
        // either constant fails this test loudly.
        assertEquals("radio", SourceIds.RADIO)
        assertEquals("kvmr", SourceIds.KVMR)
    }

    @Test fun `curated list has the v1 seed stations with non-blank required fields`() = runTest {
        val curated = RadioStations.curated
        // v0.5.32 seed roster: KVMR, KXPR, KQED, KCSB, SomaFM Groove
        // Salad — five stations. KNCO was trimmed during curation
        // (no stable stream URL — documented in RadioStations).
        assertEquals(5, curated.size)
        for (station in curated) {
            assertTrue("displayName blank for ${station.id}", station.displayName.isNotBlank())
            assertTrue("description blank for ${station.id}", station.description.isNotBlank())
            assertTrue("streamUrl not HTTPS for ${station.id}", station.streamUrl.startsWith("https://"))
            assertTrue("streamCodec blank for ${station.id}", station.streamCodec.isNotBlank())
            assertTrue("homepage blank for ${station.id}", station.homepage.isNotBlank())
            assertTrue("tags empty for ${station.id}", station.tags.isNotEmpty())
        }
    }

    @Test fun `curated list contains the legacy KVMR station id for migration`() {
        // Cardinal migration invariant: the curated station with
        // id="kvmr" MUST exist so persisted "kvmr:live" fictionIds
        // from v0.5.20..0.5.31 keep resolving after the rename.
        val kvmr = RadioStations.byId("kvmr")
        assertNotNull("KVMR must remain in the curated list for migration", kvmr)
        assertTrue(kvmr!!.streamUrl.contains("kvmr.org"))
    }

    @Test fun `popular returns the curated list on page 1`() = runTest {
        val result = source().popular(page = 1) as FictionResult.Success
        assertEquals(5, result.value.items.size)
        // First item is KVMR — preserved-from-v0.5.20 entry sits at the
        // top of the picker for predictability.
        assertEquals("kvmr:live", result.value.items.first().id)
        assertEquals(SourceIds.RADIO, result.value.items.first().sourceId)
        assertFalse(result.value.hasNext)
    }

    @Test fun `popular returns empty past page 1`() = runTest {
        val result = source().popular(page = 2) as FictionResult.Success
        assertTrue(result.value.items.isEmpty())
        assertFalse(result.value.hasNext)
    }

    @Test fun `radio summaries report Live radio as author (not the country field)`() = runTest {
        // Issue #449 — the previous mapping used the Radio Browser
        // `country` field as the author label, producing "by United
        // States" on Library / Resume / History cards (and on the
        // FictionDetail byline). This test pins the new shape: every
        // radio summary's author is the literal "Live radio" so
        // bylines downstream never claim a country authored the
        // station. A regression to `country.ifBlank { … }` fails here
        // before users see "by United States" on the Flip3.
        val result = source().popular(page = 1) as FictionResult.Success
        assertTrue(result.value.items.isNotEmpty())
        for (summary in result.value.items) {
            assertEquals(
                "Radio summary $summary should report Live radio author",
                "Live radio",
                summary.author,
            )
        }
    }

    @Test fun `popular surfaces starred imports after the curated set`() = runTest {
        val starred = RadioStation(
            id = "rb:test-uuid",
            displayName = "WFMU 91.1",
            description = "Freeform community radio · Jersey City",
            streamUrl = "https://stream0.wfmu.org/freeform-128k",
            streamCodec = "MP3",
            country = "United States",
            language = "English",
            tags = listOf("freeform", "community"),
            homepage = "https://wfmu.org",
        )
        val result = source(fakeConfig(starred = listOf(starred)))
            .popular(page = 1) as FictionResult.Success

        // 5 curated + 1 starred = 6.
        assertEquals(6, result.value.items.size)
        // Starred entry appears at the end — curated keeps its
        // predictable top-of-list position.
        assertEquals("rb:test-uuid:live", result.value.items.last().id)
    }

    @Test fun `fictionDetail resolves legacy kvmr live id`() = runTest {
        // Critical migration test: v0.5.20+ persisted fictionId
        // "kvmr:live" MUST resolve through the renamed source. If this
        // test fails, every existing user's KVMR library row 404s
        // after upgrade.
        val result = source().fictionDetail(RadioSource.LEGACY_KVMR_FICTION_ID) as FictionResult.Success
        assertEquals(1, result.value.chapters.size)
        assertEquals("Live", result.value.chapters.first().title)
    }

    @Test fun `fictionDetail returns NotFound for unknown station id`() = runTest {
        val result = source().fictionDetail("nonexistent:live")
        assertTrue(result is FictionResult.NotFound)
    }

    @Test fun `chapter returns stream URL with empty bodies for legacy KVMR id`() = runTest {
        // The audio-stream backend invariant — audioUrl is set, both
        // text bodies are empty so EnginePlayer routes through Media3.
        val result = source().chapter(
            RadioSource.LEGACY_KVMR_FICTION_ID,
            RadioSource.LEGACY_KVMR_CHAPTER_ID,
        ) as FictionResult.Success
        val content = result.value
        assertNotNull(content.audioUrl)
        assertTrue(content.audioUrl!!.startsWith("https://sslstream.kvmr.org"))
        assertEquals("", content.htmlBody)
        assertEquals("", content.plainBody)
    }

    @Test fun `chapter returns NotFound for unknown chapter id`() = runTest {
        val result = source().chapter(RadioSource.LEGACY_KVMR_FICTION_ID, "kvmr:live:bogus")
        assertTrue(result is FictionResult.NotFound)
    }

    @Test fun `chapter returns NotFound when station id missing`() = runTest {
        val result = source().chapter("nonexistent:live", "nonexistent:live:0")
        assertTrue(result is FictionResult.NotFound)
    }

    @Test fun `latestUpdates mirrors popular`() = runTest {
        val popular = (source().popular() as FictionResult.Success).value.items
        val latest = (source().latestUpdates() as FictionResult.Success).value.items
        assertEquals(popular, latest)
    }

    @Test fun `byGenre returns empty`() = runTest {
        val result = source().byGenre("anything", page = 1) as FictionResult.Success
        assertTrue(result.value.items.isEmpty())
    }

    @Test fun `genres returns empty list`() = runTest {
        val result = source().genres() as FictionResult.Success
        assertTrue(result.value.isEmpty())
    }

    @Test fun `search with empty term mirrors popular`() = runTest {
        val empty = (source().search(SearchQuery(term = "")) as FictionResult.Success).value.items
        val popular = (source().popular() as FictionResult.Success).value.items
        assertEquals(popular.map { it.id }, empty.map { it.id })
    }

    @Test fun `search matches curated stations against display name`() = runTest {
        val result = source().search(SearchQuery(term = "KVMR")) as FictionResult.Success
        assertTrue(
            "KVMR should match its display name 'KVMR 89.3 FM'",
            result.value.items.any { it.id == "kvmr:live" },
        )
    }

    @Test fun `search matches curated stations against tags`() = runTest {
        val result = source().search(SearchQuery(term = "ambient")) as FictionResult.Success
        // SomaFM Groove Salad has the "ambient" tag.
        assertTrue(result.value.items.any { it.id == "somafm-groove-salad:live" })
    }

    @Test fun `followsList is empty`() = runTest {
        val result = source().followsList() as FictionResult.Success
        assertTrue(result.value.items.isEmpty())
    }

    @Test fun `setFollowed is a no-op success`() = runTest {
        val result = source().setFollowed(RadioSource.LEGACY_KVMR_FICTION_ID, followed = true)
        assertTrue(result is FictionResult.Success)
    }

    @Test fun `stationIdFromFictionId handles both fiction and chapter ids`() {
        // Both shapes (`<id>:live` and `<id>:live:0`) reduce to the
        // same station id — that's what makes
        // RadioSource.chapter() resolve consistently.
        assertEquals("kvmr", RadioSource.stationIdFromFictionId("kvmr:live"))
        assertEquals("kvmr", RadioSource.stationIdFromFictionId("kvmr:live:0"))
        assertEquals("rb:abc", RadioSource.stationIdFromFictionId("rb:abc:live"))
    }

    @Test fun `fictionIdFor and liveChapterIdFor build stable ids`() {
        val kvmr = RadioStations.byId("kvmr")!!
        assertEquals("kvmr:live", RadioSource.fictionIdFor(kvmr))
        assertEquals("kvmr:live:0", RadioSource.liveChapterIdFor(kvmr))
    }

    @Test fun `user-agent identifies storyvox-radio`() {
        assertTrue(RadioSource.USER_AGENT.contains("storyvox-radio"))
        assertTrue(RadioSource.USER_AGENT.contains("github.com/techempower-org/candela"))
    }
}
