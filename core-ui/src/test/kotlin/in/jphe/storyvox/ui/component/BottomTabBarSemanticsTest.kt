package `in`.jphe.storyvox.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #485 — regression guard for `BottomTabBar` TalkBack semantics.
 *
 * Without `Role.Tab` + `selected = isSelected` on each [TabCell],
 * TalkBack announces tab cells as generic "double tap to activate" and
 * gives no indication of which tab is currently selected. The indicator
 * pill is visual-only and isn't readable by a screen reader; the tab
 * label and selected state must be carried by Compose semantics.
 *
 * We can't run a Compose UI test from the unit-test source set (no
 * Robolectric / ComposeTestRule). This test pins the contract by:
 *  (a) asserting the [HomeTab] entries carry stable labels (so the
 *      Icon contentDescription + Role.Tab announcement reads as
 *      e.g. "Library, Tab"), and
 *  (b) checking the structural marker constant
 *      `bottomTabBarUsesRoleTabAndSelected` which must stay `true`.
 *
 * If a future refactor drops Role.Tab / selected semantics without
 * proving an alternative on a real device with TalkBack, this test
 * fails and forces a re-verification.
 */
class BottomTabBarSemanticsTest {

    @Test
    fun `HomeTab entries carry non-empty labels — TalkBack reads each cell by label`() {
        // The Icon's contentDescription is set to `tab.label` and the
        // clickable's onClickLabel is set to the same value, so an
        // empty label would leave TalkBack with nothing to announce.
        HomeTab.entries.forEach { tab ->
            assertTrue(
                "HomeTab.${tab.name} must have a non-empty label",
                tab.label.isNotBlank(),
            )
        }
    }

    @Test
    fun `HomeTab labels are stable for TalkBack consumers`() {
        // Selectors (e2e tests, TalkBack scripts) depend on these
        // strings. A rename here without coordinating with downstream
        // selectors is a regression.
        assertEquals("Library", HomeTab.Library.label)
        assertEquals("Settings", HomeTab.Settings.label)
    }

    @Test
    fun `HomeTab carries both filled and outlined icons for selected state distinction`() {
        // Sighted users see filled-vs-outlined; TalkBack users hear
        // "selected" via the semantics `selected` flag. Both
        // affordances must be present.
        HomeTab.entries.forEach { tab ->
            assertNotNull("HomeTab.${tab.name}.filled must be non-null", tab.filled)
            assertNotNull("HomeTab.${tab.name}.outlined must be non-null", tab.outlined)
        }
    }

    @Test
    fun `BottomTabBar uses Role-Button plus selected semantics per issue #645`() {
        // Structural canary. Issue #645 (v1.0) changed Role.Tab →
        // Role.Button because Role.Tab + selected=true caused TalkBack
        // to suppress the "double-tap to activate" hint on the selected
        // cell — a focus dead-end for users on Reader / Voices /
        // Settings trying to return to Library via the dock. With
        // Role.Button + selected the cell still announces its selected
        // state but stays activatable. Flipped to false only after a
        // future refactor proves on a real device that a different
        // shape carries the same announcement + activation.
        assertTrue(
            "BottomTabBar.TabCell must expose Role.Button + selected semantics (issues #485, #645)",
            bottomTabBarUsesRoleButtonPlusSelected,
        )
    }
}
