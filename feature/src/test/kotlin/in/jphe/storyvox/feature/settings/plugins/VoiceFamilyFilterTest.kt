package `in`.jphe.storyvox.feature.settings.plugins

import `in`.jphe.storyvox.playback.voice.VoiceEngineFamily
import `in`.jphe.storyvox.playback.voice.VoiceFamilyDescriptor
import `in`.jphe.storyvox.playback.voice.VoiceFamilyIds
import `in`.jphe.storyvox.playback.voice.VoiceFamilyRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plugin manager (#501) — voice-family filter logic.
 *
 * Twin of [PluginManagerLogicTest] for the Voice bundles section.
 * Covers:
 * - [filterVoiceFamilies] with each filter chip and search query.
 * - Placeholder family exclusion from On/Off filter buckets.
 * - [VoiceFamilyRegistry] ships the expected family ids and counts.
 */
class VoiceFamilyFilterTest {

    private val piper = row(VoiceFamilyIds.PIPER, "Piper", "Local Piper", enabled = true)
    private val kokoro = row(VoiceFamilyIds.KOKORO, "Kokoro", "Local Kokoro multi-speaker", enabled = true)
    private val kitten = row(VoiceFamilyIds.KITTEN, "KittenTTS", "Local lightweight", enabled = false)
    private val azure = row(
        id = VoiceFamilyIds.AZURE,
        displayName = "Azure HD voices",
        description = "Cloud BYOK",
        enabled = false,
        engineFamily = VoiceEngineFamily.Cloud,
        requiresConfiguration = true,
    )
    private val placeholder = row(
        id = VoiceFamilyIds.VOXSHERPA_UPSTREAMS,
        displayName = "VoxSherpa upstreams",
        description = "Future engine-lib families",
        enabled = false,
        isPlaceholder = true,
    )

    private val all = listOf(piper, kokoro, kitten, azure, placeholder)

    // ─── filterVoiceFamilies ─────────────────────────────────────────

    @Test fun `On chip keeps only enabled families and excludes placeholders`() {
        val filtered = filterVoiceFamilies(all, query = "", chip = PluginFilterChip.On)
        assertEquals(
            listOf(VoiceFamilyIds.PIPER, VoiceFamilyIds.KOKORO),
            filtered.map { it.descriptor.id },
        )
    }

    @Test fun `Off chip keeps disabled families and excludes placeholders`() {
        val filtered = filterVoiceFamilies(all, query = "", chip = PluginFilterChip.Off)
        assertEquals(
            listOf(VoiceFamilyIds.KITTEN, VoiceFamilyIds.AZURE),
            filtered.map { it.descriptor.id },
        )
    }

    @Test fun `All chip keeps everything including the placeholder`() {
        val filtered = filterVoiceFamilies(all, query = "", chip = PluginFilterChip.All)
        assertEquals(all.size, filtered.size)
        assertTrue(filtered.any { it.descriptor.isPlaceholder })
    }

    @Test fun `search matches display name substring case-insensitively`() {
        val filtered = filterVoiceFamilies(all, query = "AZURE", chip = PluginFilterChip.All)
        assertEquals(listOf(VoiceFamilyIds.AZURE), filtered.map { it.descriptor.id })
    }

    @Test fun `search matches description`() {
        val filtered = filterVoiceFamilies(all, query = "multi-speaker", chip = PluginFilterChip.All)
        assertEquals(listOf(VoiceFamilyIds.KOKORO), filtered.map { it.descriptor.id })
    }

    @Test fun `search matches family id`() {
        val filtered = filterVoiceFamilies(all, query = "voice_kitten", chip = PluginFilterChip.All)
        assertEquals(listOf(VoiceFamilyIds.KITTEN), filtered.map { it.descriptor.id })
    }

    @Test fun `blank search keeps everything`() {
        val filtered = filterVoiceFamilies(all, query = "  ", chip = PluginFilterChip.All)
        assertEquals(all.size, filtered.size)
    }

    @Test fun `On + cloud yields only enabled cloud families`() {
        // Azure (cloud) is disabled, Piper/Kokoro (local) are enabled —
        // "cloud" only matches Azure description, but Azure is off.
        val filtered = filterVoiceFamilies(all, query = "cloud", chip = PluginFilterChip.On)
        assertTrue(filtered.isEmpty())
    }

    // ─── VoiceFamilyRegistry contract ────────────────────────────────

    @Test fun `registry ships the four installed families plus the placeholder`() {
        val registry = VoiceFamilyRegistry()
        val ids = registry.descriptors.map { it.id }
        assertTrue("Piper missing", VoiceFamilyIds.PIPER in ids)
        assertTrue("Kokoro missing", VoiceFamilyIds.KOKORO in ids)
        assertTrue("Kitten missing", VoiceFamilyIds.KITTEN in ids)
        assertTrue("Azure missing", VoiceFamilyIds.AZURE in ids)
        assertTrue("VoxSherpa placeholder missing", VoiceFamilyIds.VOXSHERPA_UPSTREAMS in ids)
    }

    @Test fun `toggleableIds excludes the placeholder`() {
        val registry = VoiceFamilyRegistry()
        assertFalse(
            "Placeholder must not be toggleable",
            VoiceFamilyIds.VOXSHERPA_UPSTREAMS in registry.toggleableIds,
        )
        // #676 — five installed families now: System TTS + Piper +
        // Kokoro + Kitten + Azure are all toggleable.
        assertEquals(5, registry.toggleableIds.size)
    }

    @Test fun `Azure family defaults OFF and the local families default ON`() {
        val registry = VoiceFamilyRegistry()
        val piperDesc = registry.byId(VoiceFamilyIds.PIPER)
        val kokoroDesc = registry.byId(VoiceFamilyIds.KOKORO)
        val kittenDesc = registry.byId(VoiceFamilyIds.KITTEN)
        val azureDesc = registry.byId(VoiceFamilyIds.AZURE)
        assertNotNull(piperDesc)
        assertNotNull(kokoroDesc)
        assertNotNull(kittenDesc)
        assertNotNull(azureDesc)
        assertTrue("Piper should default ON", piperDesc!!.defaultEnabled)
        assertTrue("Kokoro should default ON", kokoroDesc!!.defaultEnabled)
        assertTrue("Kitten should default ON", kittenDesc!!.defaultEnabled)
        // Azure defaults OFF — a fresh install shouldn't pretend the
        // BYOK family is "ready" until credentials are configured.
        assertFalse("Azure should default OFF", azureDesc!!.defaultEnabled)
    }

    @Test fun `Azure family carries the requiresConfiguration flag`() {
        val registry = VoiceFamilyRegistry()
        val azureDesc = registry.byId(VoiceFamilyIds.AZURE)
        assertNotNull(azureDesc)
        assertTrue(azureDesc!!.requiresConfiguration)
    }

    @Test fun `Piper Kokoro and Kitten are Local Azure is Cloud`() {
        val registry = VoiceFamilyRegistry()
        assertEquals(VoiceEngineFamily.Local, registry.byId(VoiceFamilyIds.PIPER)?.engineFamily)
        assertEquals(VoiceEngineFamily.Local, registry.byId(VoiceFamilyIds.KOKORO)?.engineFamily)
        assertEquals(VoiceEngineFamily.Local, registry.byId(VoiceFamilyIds.KITTEN)?.engineFamily)
        assertEquals(VoiceEngineFamily.Cloud, registry.byId(VoiceFamilyIds.AZURE)?.engineFamily)
    }

    // ─── fixtures ────────────────────────────────────────────────────

    private fun row(
        id: String,
        displayName: String,
        description: String,
        enabled: Boolean,
        engineFamily: VoiceEngineFamily = VoiceEngineFamily.Local,
        requiresConfiguration: Boolean = false,
        isPlaceholder: Boolean = false,
    ): VoiceFamilyRow = VoiceFamilyRow(
        descriptor = VoiceFamilyDescriptor(
            id = id,
            displayName = displayName,
            description = description,
            sourceUrl = "",
            license = "",
            sizeHint = "",
            requiresConfiguration = requiresConfiguration,
            isPlaceholder = isPlaceholder,
            defaultEnabled = enabled,
            engineFamily = engineFamily,
        ),
        enabled = enabled,
        voiceCount = 0,
        isConfigured = !requiresConfiguration,
        activeVoiceId = null,
    )
}
