package `in`.jphe.storyvox.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #997 — Focused Reading mode. The reader's auto-scroll and the
 * recenter FAB (#919) both land the highlighted sentence a fixed
 * fraction of the way down the viewport. That target-Y arithmetic used
 * to be duplicated inline in two places; [focusScrollTargetY] is the
 * single pure seam it now lives in, parameterised by the viewport
 * fraction so Focused Reading can pull the active line to the vertical
 * centre (0.5) while normal reading keeps the long-standing 0.40 perch.
 *
 * These tests pin the arithmetic and the two clamp edges. They are pure
 * JVM — no Compose, no Android, no Robolectric — because the formula is
 * just float math over already-measured pixel inputs.
 */
class FocusScrollTargetTest {

    @Test
    fun `normal reading perches the line 40 percent down the viewport`() {
        // bodyTop 100, lineTop 400 → absolute line top 500. At a 0.40
        // fraction over a 1000px viewport we subtract 400, landing the
        // scroll at 100. Well inside [0, maxScroll], so no clamping.
        val y = focusScrollTargetY(
            bodyTopPx = 100f,
            lineTopWithinText = 400f,
            viewportHeightPx = 1000f,
            viewportFraction = HIGHLIGHT_VIEWPORT_FRACTION,
            maxScroll = 5000,
        )
        assertEquals(100, y)
    }

    @Test
    fun `focus mode pulls the line to the vertical centre`() {
        // Same geometry as above, but the focus fraction (0.5) subtracts
        // 500 instead of 400 — the line settles higher up the page, i.e.
        // dead-centre. 500 - 500 = 0 here, which is also the low clamp
        // edge, so this doubles as the "centre never scrolls negative" guard.
        val y = focusScrollTargetY(
            bodyTopPx = 100f,
            lineTopWithinText = 400f,
            viewportHeightPx = 1000f,
            viewportFraction = FOCUS_VIEWPORT_FRACTION,
            maxScroll = 5000,
        )
        assertEquals(0, y)
    }

    @Test
    fun `focus centre lands halfway for a deeper line`() {
        // bodyTop 0, lineTop 1200 → absolute 1200. Centre fraction over a
        // 1000px viewport subtracts 500 → 700. Confirms the 0.5 fraction
        // is genuinely "half the viewport above the line," not a magic
        // constant that happens to clamp.
        val y = focusScrollTargetY(
            bodyTopPx = 0f,
            lineTopWithinText = 1200f,
            viewportHeightPx = 1000f,
            viewportFraction = FOCUS_VIEWPORT_FRACTION,
            maxScroll = 5000,
        )
        assertEquals(700, y)
    }

    @Test
    fun `target never goes below zero`() {
        // A line near the very top of the body would compute a negative
        // target (you can't scroll above the start). The low clamp pins
        // it to 0 so animateScrollTo gets a legal offset.
        val y = focusScrollTargetY(
            bodyTopPx = 0f,
            lineTopWithinText = 10f,
            viewportHeightPx = 1000f,
            viewportFraction = HIGHLIGHT_VIEWPORT_FRACTION,
            maxScroll = 5000,
        )
        assertEquals(0, y)
    }

    @Test
    fun `target never exceeds maxScroll`() {
        // A line deep in a long chapter computes a target past the end of
        // the scroll range. The high clamp pins it to maxScroll so the
        // view doesn't try to over-scroll past the last line.
        val y = focusScrollTargetY(
            bodyTopPx = 0f,
            lineTopWithinText = 9_000f,
            viewportHeightPx = 1000f,
            viewportFraction = HIGHLIGHT_VIEWPORT_FRACTION,
            maxScroll = 5000,
        )
        assertEquals(5000, y)
    }

    @Test
    fun `negative maxScroll is treated as zero`() {
        // Defensive: a content-shorter-than-viewport chapter can report a
        // negative ScrollState.maxValue transiently. coerceAtLeast(0) on
        // the upper bound means the only legal target is 0, not a negative
        // ceiling that would invert the clamp range.
        val y = focusScrollTargetY(
            bodyTopPx = 0f,
            lineTopWithinText = 4_000f,
            viewportHeightPx = 1000f,
            viewportFraction = HIGHLIGHT_VIEWPORT_FRACTION,
            maxScroll = -1,
        )
        assertEquals(0, y)
    }
}
