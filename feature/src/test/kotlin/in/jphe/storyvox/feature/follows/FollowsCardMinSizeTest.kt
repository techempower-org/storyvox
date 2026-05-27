package `in`.jphe.storyvox.feature.follows

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #783 — pins the Follows adaptive card-grid minimum across the
 * Tablet and Expanded breakpoints. The tablet value (320dp) yields a
 * 2-column layout on a small tablet; the Expanded value (300dp) is
 * tightened just enough that a third column fits on a Tab S-class
 * landscape canvas. A PR that retunes either value has to bump this
 * test in lock-step, same contract as the Library grid guard (#452).
 */
class FollowsCardMinSizeTest {

    @Test
    fun `tablet keeps the 320dp card minimum`() {
        assertTrue(
            "Tablet (non-expanded) Follows card minimum must stay 320dp",
            followsCardMinSizeDp(expanded = false) == 320,
        )
    }

    @Test
    fun `expanded tightens the card minimum to 300dp`() {
        assertTrue(
            "Expanded Follows card minimum must be 300dp (issue #783)",
            followsCardMinSizeDp(expanded = true) == 300,
        )
        assertTrue(
            "Expanded minimum must be <= tablet minimum so more columns fit",
            followsCardMinSizeDp(expanded = true) <= followsCardMinSizeDp(expanded = false),
        )
    }

    @Test
    fun `expanded minimum yields three columns on a foldable-unfolded width`() {
        // Galaxy Z Fold unfolded landscape content width ~932dp. At
        // 300dp minimum that is 3 columns; the 320dp tablet value would
        // only fit 2. Verify the Expanded value actually buys the third.
        val unfoldedWidth = 932
        assertTrue(
            "Expanded Follows grid must reach 3 columns at ~932dp",
            unfoldedWidth / followsCardMinSizeDp(expanded = true) >= 3,
        )
    }
}
