package `in`.jphe.storyvox.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Candela (v1.1.0) — pins the pure alpha envelopes that shape the
 * [LightMotes] burst. The composable itself can't be unit-tested in
 * this source set (no Robolectric), so the testable contract is the
 * timing math: a mote fades in, holds, and fades out; the warm bloom
 * swells early and is gone before the motes finish. Verified
 * on-device for the visual result.
 */
class LightMotesTest {

    private val eps = 1e-4f

    // ── moteAlpha envelope ─────────────────────────────────────────

    @Test fun `mote starts fully transparent`() {
        assertEquals(0f, moteAlpha(0f), eps)
    }

    @Test fun `mote fades in over the first fifth`() {
        // Halfway through the fade-in (0.09 of 0.18) → half of peak 0.9.
        assertEquals(0.45f, moteAlpha(0.09f), eps)
    }

    @Test fun `mote holds at peak through the middle`() {
        assertEquals(0.9f, moteAlpha(0.18f), eps)
        assertEquals(0.9f, moteAlpha(0.45f), eps)
        assertEquals(0.9f, moteAlpha(0.69f), eps)
    }

    @Test fun `mote fades out to nothing by the end`() {
        // Just inside the end the alpha is small but positive; at/after
        // 1.0 it is exactly zero.
        assertTrue(moteAlpha(0.95f) in 0.001f..0.45f)
        assertEquals(0f, moteAlpha(1f), eps)
        assertEquals(0f, moteAlpha(1.2f), eps)
    }

    @Test fun `mote alpha never exceeds peak and never goes negative`() {
        var p = 0f
        while (p <= 1.2f) {
            val a = moteAlpha(p)
            assertTrue("alpha $a out of range at p=$p", a in 0f..0.9001f)
            p += 0.01f
        }
    }

    // ── moteBloomAlpha envelope ────────────────────────────────────

    @Test fun `bloom swells from zero`() {
        assertEquals(0f, moteBloomAlpha(0f), eps)
        // Halfway through the swell (0.15 of 0.30) → half of peak 0.45.
        assertEquals(0.225f, moteBloomAlpha(0.15f), eps)
    }

    @Test fun `bloom holds briefly at peak`() {
        assertEquals(0.45f, moteBloomAlpha(0.30f), eps)
        assertEquals(0.45f, moteBloomAlpha(0.54f), eps)
    }

    @Test fun `bloom is gone before the motes finish`() {
        // By 0.85 the bloom has fully faded; the motes keep rising
        // afterward, so the glow leaves lingering warmth, not a flash
        // that outlasts the sparks.
        assertEquals(0f, moteBloomAlpha(0.85f), eps)
        assertEquals(0f, moteBloomAlpha(1f), eps)
    }

    @Test fun `bloom alpha stays in range`() {
        var p = 0f
        while (p <= 1.0f) {
            val a = moteBloomAlpha(p)
            assertTrue("bloom $a out of range at p=$p", a in 0f..0.4501f)
            p += 0.01f
        }
    }

    // ── shared-engine canary ───────────────────────────────────────

    @Test fun `LightMotes is the shared celebration engine`() {
        // If a refactor forks a second particle system this must be
        // consciously flipped — the whole point is ONE luminous
        // language across milestone / egg / streak / chapter-complete.
        assertTrue(lightMotesIsSharedCelebrationEngine)
    }

    @Test fun `burst lifetime is positive and brief`() {
        assertTrue(LIGHT_MOTES_LIFETIME_MS in 1000..5000)
    }
}
