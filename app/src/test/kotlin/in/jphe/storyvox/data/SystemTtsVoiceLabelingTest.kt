package `in`.jphe.storyvox.data

import `in`.jphe.storyvox.data.SystemTtsVoiceRoster.Companion.labelRawVoices
import `in`.jphe.storyvox.data.SystemTtsVoiceRoster.RawVoice
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #740 — display-name assignment for System TTS voices.
 *
 * Exercises [SystemTtsVoiceRoster.labelRawVoices] without booting the
 * Android TextToSpeech framework. The framework's `Voice.name` slugs
 * like `en-us-x-lob-network` previously surfaced as raw codes ("Lob",
 * "Log", "Lol") in the picker; these tests pin the new behavior: a
 * locale-derived label, a quality tag, and a stable numeric suffix
 * when more than one voice shares the bucket.
 */
class SystemTtsVoiceLabelingTest {

    private fun raw(
        voiceName: String,
        locale: Locale,
        isNetwork: Boolean,
        engineName: String = "com.google.android.tts",
        engineLabel: String = "Google",
    ) = RawVoice(
        engineName = engineName,
        engineLabel = engineLabel,
        voiceName = voiceName,
        locale = locale.toLanguageTag(),
        voiceLocale = locale,
        isNetworkConnectionRequired = isNetwork,
    )

    @Test
    fun `Google en-US network voices get locale label + HD tag + numeric suffix`() {
        val result = labelRawVoices(
            listOf(
                raw("en-us-x-lob-network", Locale.US, isNetwork = true),
                raw("en-us-x-log-network", Locale.US, isNetwork = true),
                raw("en-us-x-lol-network", Locale.US, isNetwork = true),
                raw("en-us-x-lom-network", Locale.US, isNetwork = true),
            ),
        )
        // Same bucket — all get numbered.
        val names = result.map { it.displayName }
        assertEquals(4, names.size)
        // No raw code segments leak through.
        names.forEach { name ->
            assertTrue(
                "Expected human-readable label, got '$name'",
                !name.equals("Lob", ignoreCase = true) &&
                    !name.equals("Log", ignoreCase = true) &&
                    !name.equals("Lol", ignoreCase = true) &&
                    !name.equals("Lom", ignoreCase = true),
            )
        }
        // All four labels are distinct (numbered).
        assertEquals(4, names.toSet().size)
        // Every label carries the (HD) tag.
        names.forEach { assertTrue("'$it' missing (HD)", it.contains("(HD)")) }
        // Every label carries the locale display name.
        val englishName = Locale.US.getDisplayName(Locale.getDefault())
        names.forEach { assertTrue("'$it' missing '$englishName'", it.contains(englishName)) }
    }

    @Test
    fun `single voice in a bucket gets no numeric suffix`() {
        val result = labelRawVoices(
            listOf(raw("en-us-x-iol-network", Locale.US, isNetwork = true)),
        )
        val name = result.single().displayName
        assertTrue("'$name' should not carry '#' suffix", !name.contains("#"))
        assertTrue("'$name' should contain (HD)", name.contains("(HD)"))
    }

    @Test
    fun `local quality tier renders as offline tag`() {
        val result = labelRawVoices(
            listOf(raw("en-us-x-iol-local", Locale.US, isNetwork = false)),
        )
        val name = result.single().displayName
        assertTrue("'$name' should contain (offline)", name.contains("(offline)"))
        assertTrue("'$name' should not contain (HD)", !name.contains("(HD)"))
    }

    @Test
    fun `network and local voices in same locale get separate buckets`() {
        // Both buckets have exactly one entry — neither gets a #N
        // suffix.
        val result = labelRawVoices(
            listOf(
                raw("en-us-x-lob-network", Locale.US, isNetwork = true),
                raw("en-us-x-lob-local", Locale.US, isNetwork = false),
            ),
        )
        val names = result.map { it.displayName }
        assertEquals(2, names.toSet().size)
        assertTrue(names.any { it.contains("(HD)") && !it.contains("#") })
        assertTrue(names.any { it.contains("(offline)") && !it.contains("#") })
    }

    @Test
    fun `different locales never collide`() {
        val result = labelRawVoices(
            listOf(
                raw("en-us-x-lob-network", Locale.US, isNetwork = true),
                raw("en-gb-x-lob-network", Locale.UK, isNetwork = true),
            ),
        )
        val names = result.map { it.displayName }
        assertEquals(2, names.toSet().size)
        // Locale display names differ across en-US and en-GB.
        val us = Locale.US.getDisplayName(Locale.getDefault())
        val gb = Locale.UK.getDisplayName(Locale.getDefault())
        assertNotEquals(us, gb)
        assertTrue(names.any { it.contains(us) })
        assertTrue(names.any { it.contains(gb) })
    }

    @Test
    fun `numbering is stable across enumeration order`() {
        // Same set, different input order — labels should match by
        // voiceName because the labeler sorts internally.
        val first = labelRawVoices(
            listOf(
                raw("en-us-x-lol-network", Locale.US, isNetwork = true),
                raw("en-us-x-lob-network", Locale.US, isNetwork = true),
                raw("en-us-x-log-network", Locale.US, isNetwork = true),
            ),
        ).associate { it.voiceName to it.displayName }
        val second = labelRawVoices(
            listOf(
                raw("en-us-x-log-network", Locale.US, isNetwork = true),
                raw("en-us-x-lol-network", Locale.US, isNetwork = true),
                raw("en-us-x-lob-network", Locale.US, isNetwork = true),
            ),
        ).associate { it.voiceName to it.displayName }
        assertEquals(first, second)
    }

    @Test
    fun `empty roster produces empty output`() {
        assertEquals(emptyList<Any>(), labelRawVoices(emptyList()))
    }

    @Test
    fun `descriptor preserves voiceName for round-trip selection`() {
        // The catalog round-trips voices by their stable `voiceName`
        // — labeling must not mutate that field.
        val result = labelRawVoices(
            listOf(raw("en-us-x-lob-network", Locale.US, isNetwork = true)),
        )
        assertEquals("en-us-x-lob-network", result.single().voiceName)
    }

    @Test
    fun `Samsung-style language voiceName still gets a locale label`() {
        // Samsung TTS emits names like 'en-US-language' rather than
        // the Google '-x-' slug. Both should label sanely.
        val result = labelRawVoices(
            listOf(
                raw(
                    voiceName = "en-US-language",
                    locale = Locale.US,
                    isNetwork = false,
                    engineName = "com.samsung.SMT",
                    engineLabel = "Samsung",
                ),
            ),
        )
        val name = result.single().displayName
        assertTrue("'$name' should not be a raw slug", !name.contains("-x-"))
        assertTrue("'$name' should contain English locale", name.contains("English"))
    }
}
