package `in`.jphe.storyvox.feature.reader

/**
 * Issue #999 phase 2 — pure selection ↔ char-offset logic for the in-reader
 * highlight overlay. Compose-free so the offset mapping (the crux of the
 * feature) is unit-testable without rendering [ReaderTextView] or standing
 * up a Compose/Android runtime — the same split #998's [findMatches] and
 * #997's [focusScrollTargetY] already use.
 *
 * ## Coordinate system
 * Every reader feature — the bookmark ([Chapter.bookmarkCharOffset]), the
 * spoken-sentence underline, #998 search hits, #994 word fill, and now these
 * highlights — speaks one coordinate: **UTF-16 char offsets into the chapter
 * body** (`chapterText` == `Chapter.plainBody`). The drag-selection gesture
 * resolves a pointer position to an offset via
 * `TextLayoutResult.getOffsetForPosition`; this file turns the raw
 * (anchor, focus) pair that gesture produces into the normalised
 * `[startOffset, endOffset)` half-open range the [Annotation] entity stores,
 * plus the [quotedText] snapshot it denormalises.
 */

/**
 * A normalised text selection in chapter-body char-offset space.
 *
 * [start] is inclusive, [end] exclusive — the exact half-open shape
 * [Annotation.startOffset]/[Annotation.endOffset] persist, so a [TextSelectionRange]
 * maps to a stored annotation with no off-by-one translation. [quotedText] is
 * the substring `chapterText.substring(start, end)`, captured so the caller can
 * denormalise it into the row ([Annotation.quotedText]) without re-reading the
 * body.
 */
data class TextSelectionRange(
    val start: Int,
    val end: Int,
    val quotedText: String,
)

/**
 * Normalise a raw drag selection into a clamped, ordered char range.
 *
 * The drag gesture hands us two offsets: [anchorOffset] (where the long-press
 * landed) and [focusOffset] (where the finger currently is). Dragging
 * *backwards* (right-to-left / bottom-to-top) makes focus < anchor, so we
 * order them here rather than forcing the gesture layer to. Both are clamped
 * into `0..textLength` so a fling past the body edge (which Compose can report
 * as an offset == length, or briefly out of range) can never produce an
 * out-of-bounds substring.
 *
 * Returns `null` for an empty or degenerate selection (start == end, or empty
 * text) — there's nothing to highlight, and an empty annotation would render
 * as an invisible zero-width span and export as an empty quote. The caller
 * uses the null to keep the highlight toolbar suppressed until the user has
 * actually selected a glyph.
 *
 * @param anchorOffset char offset where the selection started.
 * @param focusOffset char offset where the selection currently ends.
 * @param text the chapter body the offsets index into.
 */
fun normalizeSelection(
    anchorOffset: Int,
    focusOffset: Int,
    text: String,
): TextSelectionRange? {
    if (text.isEmpty()) return null
    val a = anchorOffset.coerceIn(0, text.length)
    val b = focusOffset.coerceIn(0, text.length)
    val start = minOf(a, b)
    val end = maxOf(a, b)
    if (end <= start) return null
    return TextSelectionRange(
        start = start,
        end = end,
        quotedText = text.substring(start, end),
    )
}

/**
 * Resolve which saved highlight (if any) covers the [tappedOffset], so a tap
 * on an existing highlight opens its edit/delete sheet (phase-2 requirement 2).
 *
 * The reader observes this chapter's annotations start-offset ordered
 * (`AnnotationDao.observeForChapter`). A tap resolves to a char offset via the
 * same `getOffsetForPosition` the highlight-create gesture uses; this picks the
 * annotation whose half-open `[startOffset, endOffset)` contains it.
 *
 * When highlights overlap (two devices highlighting crossing ranges, then
 * LWW-synced — see [Annotation]'s identity note), the tap lands on the
 * **shortest** covering range. The shortest span is the one the user most
 * likely meant — a precise short highlight nested inside a broad one is
 * otherwise unreachable, since the broad one would always swallow the tap.
 * Ties (equal length) resolve to the earliest in the list, which is
 * start-offset order, so the choice is deterministic.
 *
 * @param ids parallel id list (the annotation primary keys).
 * @param starts parallel inclusive start offsets.
 * @param ends parallel exclusive end offsets.
 * @return the id of the covering annotation, or null if the tap missed every
 *   highlight (a plain tap-to-seek should handle it instead).
 */
fun annotationIdAtOffset(
    tappedOffset: Int,
    ids: List<String>,
    starts: List<Int>,
    ends: List<Int>,
): String? {
    require(ids.size == starts.size && ids.size == ends.size) {
        "annotationIdAtOffset: parallel lists must be the same length"
    }
    var bestId: String? = null
    var bestLen = Int.MAX_VALUE
    for (i in ids.indices) {
        val s = starts[i]
        val e = ends[i]
        if (tappedOffset in s until e) {
            val len = e - s
            if (len < bestLen) {
                bestLen = len
                bestId = ids[i]
            }
        }
    }
    return bestId
}
