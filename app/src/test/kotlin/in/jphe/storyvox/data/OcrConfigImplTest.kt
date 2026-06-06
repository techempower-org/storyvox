package `in`.jphe.storyvox.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import `in`.jphe.storyvox.source.ocr.config.OcrPage
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Issue #995 — OcrConfigImpl persistence round-trip over a temp-file
 * DataStore (no Robolectric — same temp-file PreferenceDataStoreFactory
 * pattern the SettingsRepository tests use). Verifies a scanned
 * document survives encode→decode, ordering is newest-first, and a
 * delete removes only the targeted document.
 */
class OcrConfigImplTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun newConfig(now: () -> Long, nonce: () -> String): OcrConfigImpl {
        val file = File(tmp.newFolder(), "ocr.preferences_pb")
        val store = PreferenceDataStoreFactory.create { file }
        return OcrConfigImpl.forTesting(store = store, now = now, nonce = nonce)
    }

    @Test
    fun `saved document round-trips with its pages`() = runTest {
        var clock = 100L
        val config = newConfig(now = { clock }, nonce = { "n" })

        val id = config.save(
            title = "My letter",
            pages = listOf(
                OcrPage(0, "Page 1", "Dear friend, hello."),
                OcrPage(1, "Page 2", "Yours, me."),
            ),
        )

        val loaded = requireNotNull(config.document(id)) { "document should round-trip" }
        assertEquals("My letter", loaded.title)
        assertEquals(2, loaded.pages.size)
        assertEquals("Dear friend, hello.", loaded.pages[0].text)
        assertEquals("Yours, me.", loaded.pages[1].text)
        assertTrue(id.startsWith("ocr:"))
    }

    @Test
    fun `documents are listed newest-first`() = runTest {
        var clock = 0L
        val config = newConfig(now = { clock }, nonce = { clock.toString() })

        clock = 1L
        val first = config.save("First", listOf(OcrPage(0, "P", "a")))
        clock = 2L
        val second = config.save("Second", listOf(OcrPage(0, "P", "b")))

        val docs = config.documents()
        assertEquals(listOf(second, first), docs.map { it.fictionId })
        assertEquals("Second", docs[0].title)
    }

    @Test
    fun `delete removes only the targeted document`() = runTest {
        var clock = 0L
        val config = newConfig(now = { clock }, nonce = { clock.toString() })
        clock = 1L
        val keep = config.save("Keep", listOf(OcrPage(0, "P", "x")))
        clock = 2L
        val drop = config.save("Drop", listOf(OcrPage(0, "P", "y")))

        config.delete(drop)

        val docs = config.documents()
        assertEquals(1, docs.size)
        assertEquals(keep, docs[0].fictionId)
        assertNull(config.document(drop))
    }

    @Test
    fun `empty store lists no documents`() = runTest {
        val config = newConfig(now = { 0L }, nonce = { "x" })
        assertTrue(config.documents().isEmpty())
        assertNull(config.document("ocr:anything"))
    }
}
