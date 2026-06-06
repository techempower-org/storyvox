package `in`.jphe.storyvox.playback.tts

/**
 * Issue #1001 — paragraph-level navigation primitives.
 *
 * Paragraph heads are derived from the engine's existing ICU [Sentence]
 * list (chunked once per chapter in EnginePlayer) rather than from a
 * separate scan of the raw text. This is deliberate (#1001 design,
 * option B): a paragraph seek then targets the SAME `startChar` the
 * engine already seeks against via `seekToCharOffset`, so the playhead,
 * the brass underline (#7), and auto-scroll can't drift. A standalone
 * `\n\n` scan would yield offsets that land mid-sentence and disagree
 * with where audio resumes.
 *
 * These functions are pure (no engine, no Android) so the boundary
 * logic is unit-testable in isolation — see ParagraphBoundariesTest.
 */

/** Issue #1001 — structural canary. Asserts paragraph navigation still
 *  derives its targets from the sentence model (so a paragraph seek hits
 *  a real sentence `startChar`). A refactor that re-introduces a separate
 *  paragraph-offset model must flip this to false, which the contract
 *  test catches. */
const val paragraphNavDerivesHeadsFromSentences: Boolean = true

/**
 * Returns the indices (into [sentences]) of the sentences that begin a
 * paragraph, ascending. Sentence 0 is always a head; a later sentence is
 * a head when the chapter [text] between the previous sentence's end and
 * this sentence's start contains a blank line (two newlines separated by
 * optional spaces/tabs). A lone newline is a soft wrap, not a break.
 *
 * The separator is the whitespace run immediately preceding a sentence's
 * `startChar` (which the chunker sets to the first non-whitespace char of
 * that sentence). Because #900 makes boundaries contiguous —
 * `prev.endChar == cur.startChar` — the trimmed inter-sentence whitespace
 * lives inside the *previous* sentence's span, so we scan backward from
 * `cur.startChar` over the whitespace run rather than the (empty) gap
 * between the two spans.
 */
fun paragraphHeadIndices(text: String, sentences: List<Sentence>): List<Int> {
    if (sentences.isEmpty()) return emptyList()
    val heads = ArrayList<Int>()
    heads += 0 // the first sentence always opens the first paragraph
    for (i in 1 until sentences.size) {
        val start = sentences[i].startChar.coerceIn(0, text.length)
        if (blankLinePrecedes(text, start)) heads += i
    }
    return heads
}

/** True iff the whitespace run ending just before [index] in [text]
 *  contains a blank line — i.e. two newlines separated only by spaces,
 *  tabs, or carriage returns. A lone newline (soft wrap) is not a break.
 *  Scans backward from [index] - 1 and stops at the first non-whitespace
 *  char (the end of the previous sentence's prose). */
private fun blankLinePrecedes(text: String, index: Int): Boolean {
    var newlines = 0
    var i = index - 1
    while (i >= 0) {
        when (val ch = text[i]) {
            '\n' -> {
                newlines++
                if (newlines >= 2) return true
            }
            ' ', '\t', '\r' -> { /* part of the separating whitespace run */ }
            else -> return false // hit the previous sentence's prose
        }
        i--
    }
    return false
}

/**
 * Given the [current] sentence index, the paragraph [heads], and a
 * [direction] (+1 next, -1 previous), returns the sentence index to seek
 * to, or null when the move is a no-op (clamped at chapter bounds).
 *
 * - next (+1): the first head strictly after the current paragraph, i.e.
 *   the start of the following paragraph. Null in the last paragraph.
 * - previous (-1): if [current] is not already a head, the head of the
 *   paragraph it sits in (restart the current paragraph — matches media
 *   "previous" muscle memory, cf. SkipPrevious rewind-to-start #594);
 *   if [current] is already a head, the previous paragraph's head. Null
 *   at the first head.
 */
fun paragraphTargetIndex(current: Int, heads: List<Int>, direction: Int): Int? {
    if (heads.isEmpty()) return null
    // The head that opens the paragraph containing `current`.
    val currentParagraphHead = heads.lastOrNull { it <= current } ?: heads.first()
    return if (direction > 0) {
        // First head strictly after the current paragraph's head.
        heads.firstOrNull { it > currentParagraphHead }
    } else {
        if (current > currentParagraphHead) {
            // Mid-paragraph → restart this paragraph.
            currentParagraphHead
        } else {
            // Already at this paragraph's head → previous paragraph's head.
            heads.lastOrNull { it < currentParagraphHead }
        }
    }
}
