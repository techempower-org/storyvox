package `in`.jphe.storyvox.playback.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Issue #1001 — paragraph-level navigation. The boundary logic is pure
 * (no engine, no Android) so it's unit-testable in isolation, mirroring
 * [SentenceChunkerTest]. We derive paragraph heads from the existing
 * ICU sentence list rather than re-scanning the raw text, so a
 * paragraph seek snaps to the SAME `startChar` the engine already seeks
 * against (no playhead/highlight drift — see #1001 design note).
 */
class ParagraphBoundariesTest {

    private val chunker = SentenceChunker()
    private val locale = Locale.US

    // ── paragraphHeadIndices ────────────────────────────────────────

    @Test fun `empty sentence list has no paragraph heads`() {
        assertEquals(emptyList<Int>(), paragraphHeadIndices("", emptyList()))
    }

    @Test fun `single paragraph (no blank lines) has exactly one head at sentence 0`() {
        val text = "First sentence. Second sentence. Third sentence."
        val sentences = chunker.chunk(text, locale)
        assertEquals(listOf(0), paragraphHeadIndices(text, sentences))
    }

    @Test fun `blank line between sentences marks the following sentence as a head`() {
        // Two paragraphs: P1 = [s0, s1], P2 = [s2].
        val text = "First. Second.\n\nThird sentence here."
        val sentences = chunker.chunk(text, locale)
        assertEquals(3, sentences.size)
        // s0 is always a head; s2 follows a blank line, so it's a head; s1 is not.
        assertEquals(listOf(0, 2), paragraphHeadIndices(text, sentences))
    }

    @Test fun `multiple paragraphs each contribute one head`() {
        val text = "A one. A two.\n\nB one.\n\nC one. C two. C three."
        val sentences = chunker.chunk(text, locale)
        val heads = paragraphHeadIndices(text, sentences)
        // Paragraph starts: s0 (A one), s2 (B one), s3 (C one).
        assertEquals(listOf(0, 2, 3), heads)
    }

    @Test fun `blank line with intervening spaces and tabs still counts as a break`() {
        // The gap is "\n \t \n" — a paragraph break even with trailing
        // whitespace on the blank line.
        val text = "First.\n \t \nSecond paragraph."
        val sentences = chunker.chunk(text, locale)
        assertEquals(listOf(0, 1), paragraphHeadIndices(text, sentences))
    }

    @Test fun `a single newline (soft wrap) is NOT a paragraph break`() {
        // One newline = a wrapped line within the same paragraph, not a
        // new paragraph. Only a blank line (two newlines) breaks.
        val text = "First line.\nStill same paragraph."
        val sentences = chunker.chunk(text, locale)
        assertEquals(listOf(0), paragraphHeadIndices(text, sentences))
    }

    @Test fun `sentence 0 is always a head even when text starts with blank lines`() {
        val text = "\n\nFirst. Second."
        val sentences = chunker.chunk(text, locale)
        val heads = paragraphHeadIndices(text, sentences)
        assertTrue("sentence 0 must always be a head", heads.contains(0))
        assertEquals(listOf(0), heads)
    }

    // ── paragraphTargetIndex ────────────────────────────────────────
    //
    // direction = +1 → next paragraph head strictly AFTER the current
    //                  sentence's paragraph (the next paragraph's start).
    // direction = -1 → previous paragraph head:
    //                  - if not already at a paragraph head, the head of
    //                    the CURRENT paragraph (restart this paragraph);
    //                  - if already at a head, the PREVIOUS paragraph's head.
    // Returns null when there's nowhere to go (clamp / no-op).

    private val heads = listOf(0, 2, 5) // 3 paragraphs: [0,1] [2,3,4] [5,...]

    @Test fun `next from inside first paragraph goes to second paragraph head`() {
        assertEquals(2, paragraphTargetIndex(current = 1, heads = heads, direction = 1))
    }

    @Test fun `next from a paragraph head goes to the following head`() {
        assertEquals(5, paragraphTargetIndex(current = 2, heads = heads, direction = 1))
    }

    @Test fun `next from the last paragraph returns null (clamp at chapter end)`() {
        assertNull(paragraphTargetIndex(current = 6, heads = heads, direction = 1))
        assertNull(paragraphTargetIndex(current = 5, heads = heads, direction = 1))
    }

    @Test fun `previous from mid-paragraph restarts the current paragraph`() {
        // current = 4 is inside paragraph starting at 2 → go to 2.
        assertEquals(2, paragraphTargetIndex(current = 4, heads = heads, direction = -1))
    }

    @Test fun `previous from a paragraph head goes to the previous paragraph head`() {
        assertEquals(0, paragraphTargetIndex(current = 2, heads = heads, direction = -1))
    }

    @Test fun `previous from the first paragraph head returns null (clamp at chapter start)`() {
        assertNull(paragraphTargetIndex(current = 0, heads = heads, direction = -1))
    }

    @Test fun `previous from inside the first paragraph restarts it (to head 0)`() {
        assertEquals(0, paragraphTargetIndex(current = 1, heads = heads, direction = -1))
    }

    @Test fun `single-paragraph chapter clamps both directions`() {
        val one = listOf(0)
        assertNull(paragraphTargetIndex(current = 0, heads = one, direction = 1))
        assertNull(paragraphTargetIndex(current = 3, heads = one, direction = 1))
        // previous from mid-paragraph restarts to head 0...
        assertEquals(0, paragraphTargetIndex(current = 3, heads = one, direction = -1))
        // ...but previous from the only head is a no-op.
        assertNull(paragraphTargetIndex(current = 0, heads = one, direction = -1))
    }

    @Test fun `empty heads list is a no-op in both directions`() {
        assertNull(paragraphTargetIndex(current = 0, heads = emptyList(), direction = 1))
        assertNull(paragraphTargetIndex(current = 0, heads = emptyList(), direction = -1))
    }

    // ── structural canary ───────────────────────────────────────────

    @Test fun `paragraph navigation derives heads from the sentence model`() {
        // Pins the design decision (#1001 option B): paragraph heads are
        // a subset of sentence indices, so a paragraph seek targets a
        // real sentence startChar. A refactor that decouples paragraph
        // offsets from the sentence list must flip this.
        assertTrue(paragraphNavDerivesHeadsFromSentences)
    }
}
