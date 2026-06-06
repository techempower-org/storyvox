package `in`.jphe.storyvox.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Luna (v1.1.0) — book-completion streak milestone delta.
 *
 * When the user finishes a book, the completed-book counter ticks up.
 * [bookStreakMilestoneCrossed] decides whether that single increment
 * crossed a celebration tier (1, 5, 10, 25 "books lit") and, if so,
 * which one. It returns the *highest* tier inside the half-open range
 * `(previous, new]` so a delta that vaults past a tier (e.g. a sync
 * that jumps the counter) still fires exactly one celebration for the
 * top tier reached — never a backlog.
 *
 * Pure function, no Android: the streak counter itself is persisted in
 * DataStore (device-local), but the "did we just cross a tier" decision
 * is logic that has to be testable on the JVM. The repo seam computes
 * `(oldCount, oldCount + 1)` and renders [LightMotes] + an "N books
 * lit" badge when this returns non-null.
 */
class BookStreakTest {

    @Test fun `crossing exactly the first book fires tier 1`() {
        assertEquals(1, bookStreakMilestoneCrossed(previous = 0, new = 1))
    }

    @Test fun `a normal increment between tiers fires nothing`() {
        assertNull(bookStreakMilestoneCrossed(previous = 1, new = 2))
        assertNull(bookStreakMilestoneCrossed(previous = 2, new = 3))
        assertNull(bookStreakMilestoneCrossed(previous = 3, new = 4))
        assertNull(bookStreakMilestoneCrossed(previous = 5, new = 6))
        assertNull(bookStreakMilestoneCrossed(previous = 10, new = 11))
    }

    @Test fun `crossing the fifth book fires tier 5`() {
        assertEquals(5, bookStreakMilestoneCrossed(previous = 4, new = 5))
    }

    @Test fun `crossing the tenth book fires tier 10`() {
        assertEquals(10, bookStreakMilestoneCrossed(previous = 9, new = 10))
    }

    @Test fun `crossing the twenty-fifth book fires tier 25`() {
        assertEquals(25, bookStreakMilestoneCrossed(previous = 24, new = 25))
    }

    @Test fun `past the top tier fires nothing`() {
        // No tiers above 25; finishing book 26, 30, 100 is quietly
        // un-celebrated (we don't want every book past 25 to flash).
        assertNull(bookStreakMilestoneCrossed(previous = 25, new = 26))
        assertNull(bookStreakMilestoneCrossed(previous = 30, new = 31))
        assertNull(bookStreakMilestoneCrossed(previous = 99, new = 100))
    }

    @Test fun `a jump that vaults multiple tiers fires only the top tier`() {
        // E.g. a device that syncs a higher completed-count from another
        // device, or a backfill. We fire once, for the highest tier
        // reached, rather than a confetti backlog of 1+5+10.
        assertEquals(10, bookStreakMilestoneCrossed(previous = 0, new = 12))
        assertEquals(25, bookStreakMilestoneCrossed(previous = 4, new = 25))
        assertEquals(5, bookStreakMilestoneCrossed(previous = 0, new = 5))
    }

    @Test fun `no-op or backward deltas fire nothing`() {
        // Defensive: a recompute that doesn't move the counter, or a
        // decrement (book un-completed / deleted), never celebrates.
        assertNull(bookStreakMilestoneCrossed(previous = 5, new = 5))
        assertNull(bookStreakMilestoneCrossed(previous = 10, new = 8))
        assertNull(bookStreakMilestoneCrossed(previous = 1, new = 0))
    }

    @Test fun `the tier set is exactly 1-5-10-25`() {
        // Structural canary: the celebration tiers are a fixed,
        // deliberately-sparse set. If someone adds/removes a tier they
        // must update this list (and the copy + the badge).
        assertEquals(listOf(1, 5, 10, 25), bookStreakTiers)
    }
}
