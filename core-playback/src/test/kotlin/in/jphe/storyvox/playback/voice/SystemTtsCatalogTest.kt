package `in`.jphe.storyvox.playback.voice

import `in`.jphe.storyvox.data.source.SystemTtsVoiceDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #676 — projection tests for [VoiceCatalog.systemTtsEntriesFromRoster].
 *
 * Mirrors the shape of [AzureCatalogTest]: feed a fake roster, assert
 * the catalog rows that come out the other side carry the right
 * engineType, displayName, locale, and id.
 */
class SystemTtsCatalogTest {

    @Test fun `empty roster produces no catalog entries`() {
        assertEquals(emptyList<CatalogEntry>(), VoiceCatalog.systemTtsEntriesFromRoster(emptyList()))
    }

    @Test fun `roster entries project to SystemTts CatalogEntry rows`() {
        val roster = listOf(
            SystemTtsVoiceDescriptor(
                engineName = "com.google.android.tts",
                engineLabel = "Google",
                voiceName = "en-us-x-iol-network",
                displayName = "Iol",
                locale = "en-US",
                isNetworkConnectionRequired = true,
            ),
            SystemTtsVoiceDescriptor(
                engineName = "com.samsung.SMT",
                engineLabel = "Samsung",
                voiceName = "en-US-language",
                displayName = "Language",
                locale = "en-US",
                isNetworkConnectionRequired = false,
            ),
        )
        val entries = VoiceCatalog.systemTtsEntriesFromRoster(roster)
        assertEquals(2, entries.size)

        val google = entries.first()
        assertEquals("en_US", google.language)
        assertEquals("Iol", google.displayName)
        val gType = google.engineType as EngineType.SystemTts
        assertEquals("com.google.android.tts", gType.engineName)
        assertEquals("en-us-x-iol-network", gType.voiceName)

        val samsung = entries[1]
        val sType = samsung.engineType as EngineType.SystemTts
        assertEquals("com.samsung.SMT", sType.engineName)
        assertEquals("en-US-language", sType.voiceName)
    }

    @Test fun `entry IDs sanitize special characters to underscores`() {
        // Both `.` (in package names) and `-` (in voice names) collapse
        // to underscores. Keeps the catalog ID greppable and survives
        // any code path that splits on `:` / `.`.
        val roster = listOf(
            SystemTtsVoiceDescriptor(
                engineName = "com.google.android.tts",
                engineLabel = "Google",
                voiceName = "en-us-x-iol-network",
                displayName = "Iol",
                locale = "en-US",
                isNetworkConnectionRequired = true,
            ),
        )
        val entry = VoiceCatalog.systemTtsEntriesFromRoster(roster).single()
        // Underscores everywhere — no dots, no dashes.
        assertTrue(
            "Expected sanitized id but got ${entry.id}",
            entry.id.startsWith("system_tts_") &&
                !entry.id.contains('.') &&
                !entry.id.contains('-'),
        )
    }

    @Test fun `entries carry zero sizeBytes — no download payload`() {
        val entry = VoiceCatalog.systemTtsEntriesFromRoster(
            listOf(
                SystemTtsVoiceDescriptor(
                    engineName = "com.google.android.tts",
                    engineLabel = "Google",
                    voiceName = "en-us",
                    displayName = "Default",
                    locale = "en-US",
                    isNetworkConnectionRequired = false,
                ),
            ),
        ).single()
        assertEquals(0L, entry.sizeBytes)
    }

    @Test fun `entries carry Medium quality tier by default`() {
        // The framework doesn't expose a quality grade so the catalog
        // projection plants every entry at Medium — see kdoc on
        // [VoiceCatalog.systemTtsEntriesFromRoster].
        val entry = VoiceCatalog.systemTtsEntriesFromRoster(
            listOf(
                SystemTtsVoiceDescriptor(
                    engineName = "com.google.android.tts",
                    engineLabel = "Google",
                    voiceName = "en-us",
                    displayName = "Default",
                    locale = "en-US",
                    isNetworkConnectionRequired = false,
                ),
            ),
        ).single()
        assertEquals(QualityLevel.Medium, entry.qualityLevel)
    }

    @Test fun `voicesWithAzureAndSystemTts surfaces SystemTts at the top`() {
        // The combined catalog ordering must surface SystemTts FIRST
        // so the picker shows zero-download voices ahead of the
        // download-required neural engines. This is the central UX
        // promise of #676.
        val systemRoster = listOf(
            SystemTtsVoiceDescriptor(
                engineName = "com.google.android.tts",
                engineLabel = "Google",
                voiceName = "en-us-x-iol-network",
                displayName = "Iol",
                locale = "en-US",
                isNetworkConnectionRequired = true,
            ),
        )
        val combined = VoiceCatalog.voicesWithAzureAndSystemTts(
            azureRoster = emptyList(),
            systemTtsRoster = systemRoster,
        )
        // The very first entry must be a SystemTts row.
        assertTrue(
            "Expected SystemTts entry first but got ${combined.firstOrNull()?.engineType}",
            combined.firstOrNull()?.engineType is EngineType.SystemTts,
        )
    }

    @Test fun `byIdWithAzureAndSystemTts resolves a SystemTts entry by id`() {
        val roster = listOf(
            SystemTtsVoiceDescriptor(
                engineName = "com.google.android.tts",
                engineLabel = "Google",
                voiceName = "en-us-x-iol-network",
                displayName = "Iol",
                locale = "en-US",
                isNetworkConnectionRequired = true,
            ),
        )
        val entry = VoiceCatalog.systemTtsEntriesFromRoster(roster).single()
        val looked = VoiceCatalog.byIdWithAzureAndSystemTts(
            entry.id,
            azureRoster = emptyList(),
            systemTtsRoster = roster,
        )
        assertNotNull(looked)
        assertEquals(entry.id, looked!!.id)
        assertTrue(looked.engineType is EngineType.SystemTts)
    }

    @Test fun `byId in the static catalog does NOT find SystemTts entries`() {
        // SystemTts entries live in the runtime roster, not the static
        // catalog. byId() (the static lookup) should return null;
        // callers that need to resolve runtime ids must use
        // byIdWithAzureAndSystemTts.
        assertNull(VoiceCatalog.byId("system_tts_com_google_android_tts_en_us"))
    }
}
