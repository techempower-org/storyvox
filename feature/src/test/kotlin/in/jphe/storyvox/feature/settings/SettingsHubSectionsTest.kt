package `in`.jphe.storyvox.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #440 — smoke contract for the Settings hub row catalog.
 *
 * The hub composable [SettingsHubScreen] renders one row per
 * [SettingsHubSection] in [SettingsHubSections]. The list is the
 * source of truth for what shows up on the gear-icon landing page;
 * removing a row here (or breaking its title/subtitle) silently
 * drops a navigation entry, so this test pins the shape.
 *
 * Tests intentionally exercise the data list rather than the
 * composable. A full UI smoke test would need Compose-test
 * infrastructure that this module doesn't yet ship; the data-list
 * check catches every regression we care about today:
 *  - row count
 *  - presence of each named section the QA spec calls out (#440)
 *  - no duplicate titles (a duplicate would render two
 *    indistinguishable rows on the hub)
 */
class SettingsHubSectionsTest {

    @Test
    fun `hub catalog renders sixteen sections in fixed order`() {
        // 15 named sections + 1 escape hatch ("All settings"). The
        // count bumped 13 → 14 in v0.5.42 (Accessibility scaffold);
        // 14 → 15 in v0.5.59 (Appearance / book cover style); 15 →
        // 16 in the v1 settings-bundle-7 (Advanced subscreen — #598
        // Android Auto bucket size and future integration tunables).
        // Adding a new section requires updating both this assertion
        // AND the composable's row list — that drift is the point of
        // pinning.
        assertEquals(16, SettingsHubSections.size)
    }

    @Test
    fun `hub catalog covers every section called out in issue 440`() {
        // The issue body lists these as the expected section cards.
        // Each must appear by title (case-insensitive) so the QA
        // matrix coverage holds. New sections may be ADDED; the named
        // ones cannot silently disappear.
        val expectedSectionTitles = listOf(
            "Voice & Playback",
            "Reading",
            "Performance",
            "AI",
            // Phase 1 scaffold landed in v0.5.42; the hub row is the
            // entry point. Pin it here so a regression that drops the
            // row surfaces in this suite too.
            "Accessibility",
            // v0.5.59 — Appearance (book cover style toggle).
            "Appearance",
            "Plugins",
            "Account",
            "Memory Palace",
            // v1 settings-bundle-7 — Advanced subscreen (#598
            // Android Auto bucket size + future integration tunables).
            "Advanced",
            "Developer",
            "About",
        )
        val actual = SettingsHubSections.map { it.title.lowercase() }.toSet()
        for (expected in expectedSectionTitles) {
            assertTrue(
                "Settings hub is missing the $expected card — see #440",
                actual.contains(expected.lowercase()),
            )
        }
    }

    @Test
    fun `every section row carries a non-blank title and subtitle`() {
        // The hub leans on the subtitle to preview each card's
        // contents (the kdoc spec calls this out explicitly). A blank
        // subtitle would render as an empty bodySmall row, which
        // looks like a layout glitch rather than navigation.
        for (section in SettingsHubSections) {
            assertTrue(
                "Hub row '${section.title}' has a blank title",
                section.title.isNotBlank(),
            )
            assertTrue(
                "Hub row '${section.title}' has a blank subtitle",
                section.subtitle.isNotBlank(),
            )
        }
    }

    @Test
    fun `hub section titles are unique`() {
        // Two rows with the same title render as twins on the hub —
        // the user has no way to know which one to tap. Pin uniqueness.
        val titles = SettingsHubSections.map { it.title.lowercase() }
        val duplicates = titles.groupingBy { it }.eachCount().filterValues { it > 1 }
        assertTrue(
            "Hub catalog has duplicate titles: $duplicates",
            duplicates.isEmpty(),
        )
    }

    @Test
    fun `Voice and Playback is the first row for most-touched ordering`() {
        // The kdoc commits to "most-touched first" — Voice & Playback
        // leads the hub. If a reorder ever buries it, this test fails
        // first so the change is intentional.
        assertEquals("Voice & Playback", SettingsHubSections.first().title)
    }

    @Test
    fun `All settings escape hatch is present and last`() {
        // The legacy long-scroll SettingsScreen remains reachable via
        // an explicit row labelled "All settings". It sits at the
        // bottom — escape hatches go below the structured catalog so
        // they don't compete visually with the curated cards.
        val last = SettingsHubSections.last()
        assertEquals("All settings", last.title)
        assertNotNull(last.subtitle)
        assertFalse(last.subtitle.isBlank())
    }
}
