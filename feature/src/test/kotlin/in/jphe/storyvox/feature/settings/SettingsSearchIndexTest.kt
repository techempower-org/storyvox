package `in`.jphe.storyvox.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #802 — contract for the legacy settings search index.
 *
 * [SettingsSearchSections] is the source of truth for which sections the
 * legacy [SettingsScreen] filters. Mirrors [SettingsHubSectionsTest]'s
 * data-list approach: this module ships no Compose-test infra, so the
 * filter is exercised through its pure [matchesSettingsQuery] logic
 * rather than the composable. The cases here pin every behaviour the
 * issue calls out: blank-query-shows-all, substring match, keyword
 * match, fuzzy subsequence match, and the "TTS surfaces only Voice"
 * snapshot.
 */
class SettingsSearchIndexTest {

    private fun matchingLabels(query: String): List<String> =
        SettingsSearchSections.filter { matchesSettingsQuery(query, it) }.map { it.label }

    @Test
    fun `index covers all ten legacy sections in fixed order`() {
        val expected = listOf(
            "Voice & Playback",
            "Reading",
            "Performance & buffering",
            "AI",
            "Library & Sync",
            "Account",
            "Memory Palace",
            "Cloud voices",
            "Developer",
            "About",
        )
        assertEquals(expected, SettingsSearchSections.map { it.label })
    }

    @Test
    fun `section labels are unique`() {
        val labels = SettingsSearchSections.map { it.label.lowercase() }
        val dupes = labels.groupingBy { it }.eachCount().filterValues { it > 1 }
        assertTrue("Duplicate section labels: $dupes", dupes.isEmpty())
    }

    @Test
    fun `blank query matches every section`() {
        assertEquals(SettingsSearchSections.size, matchingLabels("").size)
        assertEquals(SettingsSearchSections.size, matchingLabels("   ").size)
    }

    @Test
    fun `searching TTS surfaces only Voice and Playback`() {
        // The snapshot the issue body calls out: "TTS" hits the Voice
        // section's keyword list and nothing else — Account / About /
        // Plugins all collapse away.
        assertEquals(listOf("Voice & Playback"), matchingLabels("TTS"))
    }

    @Test
    fun `keyword match surfaces the right section by engine name`() {
        assertEquals(listOf("Voice & Playback"), matchingLabels("kokoro"))
        assertTrue(matchingLabels("claude").contains("AI"))
        assertTrue(matchingLabels("discord").contains("Library & Sync"))
    }

    @Test
    fun `substring match against label and descriptor`() {
        assertTrue(matchingLabels("account").contains("Account"))
        // "sigil" lives only in the About keyword list, not its label.
        assertEquals(listOf("About"), matchingLabels("sigil"))
    }

    @Test
    fun `fuzzy subsequence match tolerates dropped characters`() {
        // #773's contract: "pass" matches "Sync Passphrase". Here the
        // subsequence fallback lets "kkr" reach the "kokoro" keyword.
        assertTrue(matchingLabels("kkr").contains("Voice & Playback"))
    }

    @Test
    fun `match is case insensitive`() {
        assertEquals(matchingLabels("AZURE"), matchingLabels("azure"))
        assertTrue(matchingLabels("AZURE").contains("Cloud voices"))
    }

    @Test
    fun `nonsense query matches nothing`() {
        assertTrue(matchingLabels("zzzqqxwv").isEmpty())
    }

    @Test
    fun `every section carries a non-blank label and descriptor`() {
        for (section in SettingsSearchSections) {
            assertFalse("Blank label", section.label.isBlank())
            assertFalse("Blank descriptor for ${section.label}", section.descriptor.isBlank())
        }
    }
}
