package `in`.jphe.storyvox.sigil

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Calliope (v0.5.00) — version-string gate semantics for the
 * milestone celebration. The gate is fail-closed: any unparseable
 * input returns false so a dev / pre-release sigil never
 * accidentally pops the dialog.
 */
class MilestoneTest {

    @Test fun `qualifies returns true for exact v0_5_00`() {
        assertTrue(Milestone.qualifies("0.5.00"))
    }

    @Test fun `qualifies returns true for newer patch inside the window`() {
        assertTrue(Milestone.qualifies("0.5.1"))
        assertTrue(Milestone.qualifies("0.5.5"))
    }

    // #439 — celebration window closes after v0.5.5. Anyone fresh-
    // installing on a later build never lived through the crossing,
    // so the dialog (which says "storyvox 0.5.00") would read as a
    // dead placeholder. Window-end bound silently retires the dialog
    // for fresh installs on later builds.
    @Test fun `qualifies returns false past the celebration window`() {
        assertFalse(Milestone.qualifies("0.5.17"))
        assertFalse(Milestone.qualifies("0.5.36"))
        assertFalse(Milestone.qualifies("0.6.0"))
        assertFalse(Milestone.qualifies("0.10.0"))
        assertFalse(Milestone.qualifies("1.0.0"))
        assertFalse(Milestone.qualifies("2.3.4"))
    }

    @Test fun `qualifies returns false for prior v0_4_x`() {
        assertFalse(Milestone.qualifies("0.4.97"))
        assertFalse(Milestone.qualifies("0.4.16"))
        assertFalse(Milestone.qualifies("0.4.0"))
    }

    @Test fun `qualifies returns false for prior v0_3_x`() {
        assertFalse(Milestone.qualifies("0.3.99"))
    }

    @Test fun `qualifies tolerates two-part version names`() {
        assertTrue(Milestone.qualifies("0.5"))
        assertFalse(Milestone.qualifies("1.0")) // past window end
        assertFalse(Milestone.qualifies("0.4"))
    }

    @Test fun `qualifies fails closed on garbage input`() {
        assertFalse(Milestone.qualifies(""))
        assertFalse(Milestone.qualifies("dev"))
        assertFalse(Milestone.qualifies("foo.bar.baz"))
    }

    @Test fun `qualifies strips pre-release suffixes`() {
        // 0.5.00-rc1 / 0.5.00+sha — split on `.`/`-`/`+` and compare
        // the first three integer triples.
        assertTrue(Milestone.qualifies("0.5.0-rc1"))
        assertTrue(Milestone.qualifies("0.5.0+abcdef"))
        assertFalse(Milestone.qualifies("0.4.97-rc1"))
    }

    // ── v1.1.0 "read it your way" milestone (Luna) ─────────────────
    // A second, independent celebration window. Per Milestone.kt's
    // kdoc, each milestone gets its own helper + its own DataStore
    // keys; the copy is hand-tuned per release. Window: [1.1.0, 1.2.0)
    // so the celebration retires once 1.2.x ships (fresh installers
    // past the window never lived through the crossing).

    @Test fun `qualifiesV110 returns true for exact v1_1_0`() {
        assertTrue(Milestone.qualifiesV110("1.1.0"))
    }

    @Test fun `qualifiesV110 returns true for patch inside the window`() {
        assertTrue(Milestone.qualifiesV110("1.1.1"))
        assertTrue(Milestone.qualifiesV110("1.1.9"))
        assertTrue(Milestone.qualifiesV110("1.1.99"))
    }

    @Test fun `qualifiesV110 returns false at and past the window end`() {
        // Window end is the 1.2.0 boundary — 1.2.0 itself is OUT (a
        // fresh 1.2 install didn't cross 1.1) and everything above.
        assertFalse(Milestone.qualifiesV110("1.2.0"))
        assertFalse(Milestone.qualifiesV110("1.2.5"))
        assertFalse(Milestone.qualifiesV110("1.3.0"))
        assertFalse(Milestone.qualifiesV110("2.0.0"))
    }

    @Test fun `qualifiesV110 returns false for builds below v1_1_0`() {
        assertFalse(Milestone.qualifiesV110("1.0.0"))
        assertFalse(Milestone.qualifiesV110("1.0.3"))
        assertFalse(Milestone.qualifiesV110("0.5.0"))
        assertFalse(Milestone.qualifiesV110("0.9.99"))
    }

    @Test fun `qualifiesV110 does not collide with the v0_5_00 window`() {
        // The two windows are disjoint: nothing in the 0.5 window
        // qualifies for V110, and nothing in the V110 window qualifies
        // for 0.5.00.
        assertFalse(Milestone.qualifiesV110("0.5.0"))
        assertFalse(Milestone.qualifies("1.1.0"))
    }

    @Test fun `qualifiesV110 tolerates two-part version names`() {
        assertTrue(Milestone.qualifiesV110("1.1"))
        assertFalse(Milestone.qualifiesV110("1.0"))
        assertFalse(Milestone.qualifiesV110("1.2"))
    }

    @Test fun `qualifiesV110 fails closed on garbage input`() {
        assertFalse(Milestone.qualifiesV110(""))
        assertFalse(Milestone.qualifiesV110("dev"))
        assertFalse(Milestone.qualifiesV110("foo.bar.baz"))
    }

    @Test fun `qualifiesV110 strips pre-release suffixes`() {
        assertTrue(Milestone.qualifiesV110("1.1.0-rc1"))
        assertTrue(Milestone.qualifiesV110("1.1.0+abcdef"))
        assertFalse(Milestone.qualifiesV110("1.0.3-rc1"))
    }
}
