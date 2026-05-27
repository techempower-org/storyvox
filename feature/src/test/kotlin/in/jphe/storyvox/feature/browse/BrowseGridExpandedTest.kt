package `in`.jphe.storyvox.feature.browse

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #783 — pins the Browse cover-grid adaptive minimum across the
 * Tablet and Expanded breakpoints. 140dp on tablet/phone (matches the
 * Library grid #452 tuning); 180dp on Expanded (>=840dp) so covers grow
 * on Tab S-class landscape instead of over-packing the row. A retune of
 * either value must bump this test in lock-step.
 */
class BrowseGridExpandedTest {

    @Test
    fun `tablet and phone keep the 140dp cover minimum`() {
        assertTrue(
            "Non-expanded Browse grid minimum must stay 140dp",
            browseGridMinSizeDp(expanded = false) == 140,
        )
    }

    @Test
    fun `expanded bumps the cover minimum to 180dp`() {
        assertTrue(
            "Expanded Browse grid minimum must be 180dp (issue #783)",
            browseGridMinSizeDp(expanded = true) == 180,
        )
        assertTrue(
            "Expanded minimum must be strictly larger than the tablet minimum",
            browseGridMinSizeDp(expanded = true) > browseGridMinSizeDp(expanded = false),
        )
    }

    @Test
    fun `expanded minimum keeps a sane column count on a Tab S-class width`() {
        // Tab S9 landscape content width ~1180dp. At 180dp that yields
        // ~6 columns of larger covers; the 140dp value over-packs to ~8.
        val expandedWidth = 1180
        val cols = expandedWidth / browseGridMinSizeDp(expanded = true)
        assertTrue(
            "Expanded Browse grid on a Tab S-class width must render 4..7 columns (got $cols)",
            cols in 4..7,
        )
    }
}
