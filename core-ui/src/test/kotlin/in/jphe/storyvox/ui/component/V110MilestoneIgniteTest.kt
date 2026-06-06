package `in`.jphe.storyvox.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Candela (v1.1.0) — pins the [remap] window math that sequences the
 * ignite dialog's beats and the structural canary asserting the beats
 * are staggered (not fired at once). The composable's visual result is
 * verified on-device; this guards the timing contract.
 */
class V110MilestoneIgniteTest {

    private val eps = 1e-4f

    @Test fun `remap is zero before the window opens`() {
        assertEquals(0f, remap(0.0f, 0.12f, 0.50f), eps)
        assertEquals(0f, remap(0.11f, 0.12f, 0.50f), eps)
    }

    @Test fun `remap reaches one at and after the window close`() {
        assertEquals(1f, remap(0.50f, 0.12f, 0.50f), eps)
        assertEquals(1f, remap(0.90f, 0.12f, 0.50f), eps)
    }

    @Test fun `remap interpolates linearly inside the window`() {
        // Midpoint of [0.12, 0.50] is 0.31 → 0.5.
        assertEquals(0.5f, remap(0.31f, 0.12f, 0.50f), eps)
        // Quarter point.
        assertEquals(0.25f, remap(0.215f, 0.12f, 0.50f), eps)
    }

    @Test fun `remap degenerate window steps at the boundary`() {
        // start == end: a step function, not a divide-by-zero.
        assertEquals(0f, remap(0.3f, 0.5f, 0.5f), eps)
        assertEquals(1f, remap(0.5f, 0.5f, 0.5f), eps)
        assertEquals(1f, remap(0.7f, 0.5f, 0.5f), eps)
    }

    @Test fun `the ignite beat windows overlap so the sequence crossfades`() {
        // The whole point of the staggered design: at a moment in the
        // early-middle of the intro, MORE than one beat is partially
        // active — flame still drawing AND glow already blooming. If
        // these were sequential (non-overlapping) windows, only one
        // would be in (0,1) at a time.
        val t = 0.20f
        val flame = remap(t, 0.00f, 0.28f)   // beat 1
        val glow = remap(t, 0.12f, 0.50f)    // beat 2
        assertTrue("flame should be mid-draw at t=$t", flame in 0.01f..0.99f)
        assertTrue("glow should be mid-bloom at t=$t", glow in 0.01f..0.99f)
    }

    @Test fun `ignite beats are sequenced canary`() {
        assertTrue(v110IgniteBeatsAreSequenced)
    }

    @Test fun `ignite intro duration is positive and brief`() {
        assertTrue(IGNITE_INTRO_MS in 1000..4000)
    }
}
