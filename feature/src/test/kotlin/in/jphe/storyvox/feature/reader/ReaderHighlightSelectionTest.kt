package `in`.jphe.storyvox.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #999 phase 2 — pins the pure selection ↔ char-offset core the
 * in-reader highlight overlay rests on. Getting this mapping right is the
 * crux of the feature: the offsets a drag produces must round-trip exactly
 * with the `[startOffset, endOffset)` half-open range [Annotation] stores
 * (the same coordinate the bookmark, the sentence underline, and #998 search
 * all use), or a saved highlight renders at the wrong glyphs after reload.
 *
 * Pure (no Compose / Android) by design, mirroring #998's
 * [ReaderTextSearchTest] and #997's `focusScrollTargetY` test split.
 */
class ReaderHighlightSelectionTest {

    private val text = "The quick brown fox jumps over the lazy dog."
    //                  0123456789...                              ^len = 44

    // ── normalizeSelection ───────────────────────────────────────────────

    @Test
    fun `forward selection keeps start before end and quotes the right text`() {
        // "quick" = offsets 4..9 (end exclusive at 9).
        val sel = normalizeSelection(anchorOffset = 4, focusOffset = 9, text = text)!!
        assertEquals(4, sel.start)
        assertEquals(9, sel.end)
        assertEquals("quick", sel.quotedText)
    }

    @Test
    fun `backward drag is normalised to ordered start before end`() {
        // Dragging right-to-left: anchor past, focus before. Same range.
        val sel = normalizeSelection(anchorOffset = 9, focusOffset = 4, text = text)!!
        assertEquals(4, sel.start)
        assertEquals(9, sel.end)
        assertEquals("quick", sel.quotedText)
    }

    @Test
    fun `zero-width selection (anchor == focus) is null`() {
        assertNull(normalizeSelection(anchorOffset = 7, focusOffset = 7, text = text))
    }

    @Test
    fun `empty text yields null`() {
        assertNull(normalizeSelection(anchorOffset = 0, focusOffset = 0, text = ""))
    }

    @Test
    fun `offsets past the body edge are clamped, not out-of-bounds`() {
        // A fling can report focus == length or beyond; must not throw.
        val sel = normalizeSelection(anchorOffset = 40, focusOffset = 9999, text = text)!!
        assertEquals(40, sel.start)
        assertEquals(text.length, sel.end)
        assertEquals("dog.", sel.quotedText)
    }

    @Test
    fun `negative anchor is clamped to zero`() {
        val sel = normalizeSelection(anchorOffset = -5, focusOffset = 3, text = text)!!
        assertEquals(0, sel.start)
        assertEquals(3, sel.end)
        assertEquals("The", sel.quotedText)
    }

    @Test
    fun `selection covering the whole body round-trips the full string`() {
        val sel = normalizeSelection(anchorOffset = 0, focusOffset = text.length, text = text)!!
        assertEquals(0, sel.start)
        assertEquals(text.length, sel.end)
        assertEquals(text, sel.quotedText)
    }

    // ── annotationIdAtOffset ─────────────────────────────────────────────

    @Test
    fun `tap inside a highlight returns its id`() {
        val id = annotationIdAtOffset(
            tappedOffset = 6,
            ids = listOf("a"),
            starts = listOf(4),
            ends = listOf(9),
        )
        assertEquals("a", id)
    }

    @Test
    fun `tap on the exclusive end edge misses (half-open)`() {
        // end is exclusive: offset 9 is NOT inside 4..9).
        assertNull(
            annotationIdAtOffset(
                tappedOffset = 9,
                ids = listOf("a"),
                starts = listOf(4),
                ends = listOf(9),
            ),
        )
    }

    @Test
    fun `tap on the inclusive start edge hits`() {
        assertEquals(
            "a",
            annotationIdAtOffset(
                tappedOffset = 4,
                ids = listOf("a"),
                starts = listOf(4),
                ends = listOf(9),
            ),
        )
    }

    @Test
    fun `tap outside every highlight returns null`() {
        assertNull(
            annotationIdAtOffset(
                tappedOffset = 20,
                ids = listOf("a"),
                starts = listOf(4),
                ends = listOf(9),
            ),
        )
    }

    @Test
    fun `overlapping highlights resolve to the shortest covering range`() {
        // Broad "a" 0..20 fully contains short "b" 4..9; a tap at 6 is in
        // both, but the nested short one is what the user can otherwise
        // never reach, so it wins.
        val id = annotationIdAtOffset(
            tappedOffset = 6,
            ids = listOf("a", "b"),
            starts = listOf(0, 4),
            ends = listOf(20, 9),
        )
        assertEquals("b", id)
    }

    @Test
    fun `equal-length overlap is deterministic (first in list wins)`() {
        val id = annotationIdAtOffset(
            tappedOffset = 6,
            ids = listOf("first", "second"),
            starts = listOf(4, 5),
            ends = listOf(9, 10),
        )
        assertEquals("first", id)
    }
}
