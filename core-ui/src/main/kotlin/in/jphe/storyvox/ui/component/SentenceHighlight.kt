package `in`.jphe.storyvox.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.ui.theme.LocalMotion
import `in`.jphe.storyvox.ui.theme.LocalReaderColors
import `in`.jphe.storyvox.ui.theme.LocalReaderTypography
import `in`.jphe.storyvox.ui.theme.LocalReducedMotion

/**
 * Renders chapter text with a brass underline that animates over the currently-spoken sentence.
 *
 * @param text full chapter text
 * @param highlightStart UTF-16 char index where the current sentence begins
 * @param highlightEnd UTF-16 char index where the current sentence ends (exclusive)
 * @param onTapWord optional — invoked with char index of the word the user tapped (for "start TTS from here")
 * @param onLongPressWord optional — invoked with the long-pressed word (extracted via `TextLayoutResult.getWordBoundary`).
 *                        Used by the reader for the "Ask AI: who is X?" character-lookup affordance (#188); the long-press
 *                        does NOT fire `onTapWord`, so a deliberate long-press never accidentally seeks playback.
 * @param onLayout optional — emits the text layout each time it changes; reader uses it to auto-scroll
 *                the highlighted sentence into view.
 * @param searchMatches optional (#998) — UTF-16 char ranges of in-text search hits, painted with a
 *                translucent brass background fill. Independent of the spoken-sentence underline: the
 *                underline tracks what's being *read*; these tint what was *found*. Empty (the default)
 *                keeps every existing callsite — AudiobookView, previews, tests — a no-op.
 * @param activeMatchIndex optional (#998) — index into [searchMatches] of the hit the next/prev chevrons
 *                last landed on; it gets a stronger fill so the reader sees which match is focused. `-1`
 *                (default) means no active match — every hit paints with the dimmer fill.
 * @param wordStart optional (#994) — UTF-16 char index of the word currently being spoken, for the
 *                per-word "karaoke" fill. `-1` (default) = no word fill, so every existing callsite
 *                renders pixel-identically. Drawn as a translucent draw-layer rect *behind* the
 *                spoken-sentence underline, so the underline stays visible over the wash.
 * @param wordEnd optional (#994) — exclusive UTF-16 end of the karaoke word. The fill paints only when
 *                `wordEnd > wordStart && wordStart >= 0`.
 * @param wordFill optional (#994) — colour for the per-word fill. Default null lets it derive from the
 *                active reading-theme accent (or MaterialTheme.primary when inactive); a non-null value
 *                is the user's custom highlight colour (issue #994 "customizable highlight color").
 * @param savedHighlights optional (#999 phase 2) — persisted user highlights for this chapter, each a
 *                UTF-16 char range + the (already-translucent) fill colour to paint behind it. Drawn as
 *                *background* spans, orthogonal to the spoken-sentence underline (what's being *read*),
 *                the #998 search fills (what was *found*), and the #994 word fill. End offset of each
 *                range is end-inclusive (matching #998), so a saved `[startOffset, endOffset)` annotation
 *                is passed as `startOffset..(endOffset - 1)`. Empty (the default) keeps every existing
 *                callsite — AudiobookView, previews, tests — a no-op.
 */
@Composable
fun SentenceHighlight(
    text: String,
    highlightStart: Int,
    highlightEnd: Int,
    modifier: Modifier = Modifier,
    onTapWord: ((Int) -> Unit)? = null,
    onLongPressWord: ((String) -> Unit)? = null,
    onLayout: ((TextLayoutResult) -> Unit)? = null,
    searchMatches: List<IntRange> = emptyList(),
    activeMatchIndex: Int = -1,
    wordStart: Int = -1,
    wordEnd: Int = -1,
    wordFill: androidx.compose.ui.graphics.Color? = null,
    savedHighlights: List<Pair<IntRange, androidx.compose.ui.graphics.Color>> = emptyList(),
    /**
     * Issue #999 phase 2 — long-press-drag text selection for creating a
     * highlight. When non-null, a long-press anchors a selection at the word
     * under the press, and dragging extends it; each move emits
     * `(anchorOffset, focusOffset)` as raw UTF-16 char offsets (un-ordered —
     * the reader normalises them via `normalizeSelection`). Drives the
     * in-progress selection wash drawn behind the text. Null (the default)
     * disables selection so the audiobook view / previews keep today's
     * long-press-to-look-up gesture. When both this and [onLongPressWord] are
     * set, a long-press-*drag* selects while a long-press-*tap* (no drag) still
     * looks up — the two don't collide because they live on separate detectors.
     */
    onSelectionDrag: ((anchorOffset: Int, focusOffset: Int) -> Unit)? = null,
    /** Issue #999 phase 2 — fired when the selection drag lifts, signalling the
     *  reader to show the highlight toolbar for the current selection. */
    onSelectionFinished: (() -> Unit)? = null,
    /** Issue #999 phase 2 — the in-progress selection's UTF-16 char range
     *  (end-inclusive, like [savedHighlights]) to wash behind the text while
     *  the user drags. Null = no active selection. */
    activeSelection: IntRange? = null,
) {
    // #993 — when a reading theme is active, the chapter text uses the
    // theme's foreground and the sentence underline uses its accent; otherwise
    // we fall back to MaterialTheme so the default reader is pixel-identical.
    val readerColors = LocalReaderColors.current.resolved
    val brass = readerColors?.accent ?: MaterialTheme.colorScheme.primary
    val onSurface = readerColors?.foreground ?: MaterialTheme.colorScheme.onSurface
    val motion = LocalMotion.current

    // #994 — per-word karaoke fill colour. A user-set custom colour wins;
    // otherwise we derive from the same accent the sentence underline uses
    // (brass), so out of the box the word fill harmonises with the active
    // reading theme (#993). 0.25 alpha = a soft wash the dark text reads
    // through. Distinct draw layer from #998's search-hit *span* fills.
    val wordFillColor = (wordFill ?: brass).copy(alpha = 0.25f)

    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    // Build the highlighted AnnotatedString without copying the chapter body.
    // The previous shape was three `text.substring(...)` calls per sentence
    // change (pre-highlight prefix, highlighted span, post-highlight tail) —
    // that's two ~10-50KB allocations every ~3 s while playback runs, just
    // to throw the substrings into a freshly built AnnotatedString.
    //
    // Instead, hold a single shared `AnnotatedString` over the full text
    // (memoized by `text` alone — the chapter body changes only on chapter
    // switch) and overlay the highlight span via the public AnnotatedString
    // constructor's `spanStyles` list. That constructor takes the SpanStyle
    // ranges by reference; no character copying happens on a sentence change.
    // #998 — translucent brass background fills for in-text search hits.
    // The active hit (the one the chevrons landed on) gets a stronger
    // alpha so it stands out from the other matches. These are *background*
    // spans, orthogonal to the spoken-sentence *underline* drawn below.
    val matchFill = brass.copy(alpha = 0.25f)
    val activeMatchFill = brass.copy(alpha = 0.5f)

    val baseAnnotated: AnnotatedString = remember(text) { AnnotatedString(text) }
    val annotated: AnnotatedString = remember(
        baseAnnotated, highlightStart, highlightEnd, onSurface,
        searchMatches, activeMatchIndex, matchFill, activeMatchFill,
        savedHighlights,
    ) {
        val spans = ArrayList<AnnotatedString.Range<SpanStyle>>(
            searchMatches.size + savedHighlights.size + 1,
        )

        // #999 phase 2 — persisted highlight background fills, added FIRST so
        // the search-hit fills and the spoken-sentence span layer on top of a
        // saved highlight when they coincide. Each range is end-inclusive
        // (#998 convention), so the AnnotatedString end (exclusive) is last + 1.
        savedHighlights.forEach { (range, fillColor) ->
            val start = range.first.coerceIn(0, text.length)
            val end = (range.last + 1).coerceIn(start, text.length)
            if (end > start) {
                spans.add(
                    AnnotatedString.Range(
                        item = SpanStyle(background = fillColor),
                        start = start,
                        end = end,
                    ),
                )
            }
        }

        // Sentence-being-read span (bold, on-surface). Unchanged from before.
        if (highlightEnd > highlightStart &&
            highlightStart in 0..text.length &&
            highlightEnd in highlightStart..text.length
        ) {
            spans.add(
                AnnotatedString.Range(
                    item = SpanStyle(color = onSurface, fontWeight = FontWeight.Medium),
                    start = highlightStart,
                    end = highlightEnd,
                ),
            )
        }

        // Search-hit background fills. AnnotatedString end offset is
        // exclusive; our IntRange is end-inclusive, so end = last + 1.
        searchMatches.forEachIndexed { i, range ->
            val start = range.first.coerceIn(0, text.length)
            val end = (range.last + 1).coerceIn(start, text.length)
            if (end > start) {
                spans.add(
                    AnnotatedString.Range(
                        item = SpanStyle(
                            background = if (i == activeMatchIndex) activeMatchFill else matchFill,
                        ),
                        start = start,
                        end = end,
                    ),
                )
            }
        }

        if (spans.isEmpty()) baseAnnotated else AnnotatedString(text = text, spanStyles = spans)
    }

    val reducedMotion = LocalReducedMotion.current

    // The underline used to snap from one sentence's bounds to the next.
    // Now we animate the bounds themselves: `animatedStart` and
    // `animatedEnd` glide between consecutive sentence ranges over
    // `sentenceDurationMs` (180ms via `sentenceEasing`). The drawBehind
    // resolves these animated offsets to line positions, so within a line
    // the underline literally slides its edges; crossing a line boundary
    // reads as a continuous traversal of the offset space rather than a
    // jump cut.
    //
    // Reduced motion: skip the int animations entirely, fall back to the
    // raw values. Same contract as cascadeReveal / NavHost / spinner /
    // sleep timer — absent motion, not shorter motion.
    val animatedStart by animateIntAsState(
        targetValue = highlightStart,
        animationSpec = androidx.compose.animation.core.tween(motion.sentenceDurationMs, easing = motion.sentenceEasing),
        label = "sentenceUnderlineStart",
    )
    val animatedEnd by animateIntAsState(
        targetValue = highlightEnd,
        animationSpec = androidx.compose.animation.core.tween(motion.sentenceDurationMs, easing = motion.sentenceEasing),
        label = "sentenceUnderlineEnd",
    )

    // Fade the underline in on first appearance / out on disappearance.
    // Held at 1f throughout sentence-to-sentence transitions because the
    // underline is *moving*, not appearing — fading the brass would obscure
    // the glide.
    val target = if (highlightEnd > highlightStart) 1f else 0f
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = androidx.compose.animation.core.tween(motion.sentenceDurationMs, easing = motion.sentenceEasing),
        label = "sentenceUnderline",
    )

    val drawStart = if (reducedMotion) highlightStart else animatedStart
    val drawEnd = if (reducedMotion) highlightEnd else animatedEnd

    // #999 phase 2 — latest selection callbacks funnelled through
    // rememberUpdatedState (hoisted to the composable body so the remember is
    // unconditional), letting the selection pointerInput key only on `text`
    // and never restart mid-drag when these lambdas change identity.
    val currentSelectionDrag = rememberUpdatedState(onSelectionDrag)
    val currentSelectionFinished = rememberUpdatedState(onSelectionFinished)

    Text(
        text = annotated,
        // Reader-surface typography (#992): overlay the user's font / size /
        // line + letter spacing onto the base reader style. Default
        // LocalReaderTypography reproduces bodyLarge exactly, so un-provided
        // call sites render identically to before.
        style = LocalReaderTypography.current.toTextStyle(MaterialTheme.typography.bodyLarge),
        color = onSurface,
        onTextLayout = {
            layout = it
            onLayout?.invoke(it)
        },
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            // Tap-to-seek + long-press-to-look-up. Convert pointer position
            // into a UTF-16 offset via the captured TextLayoutResult and
            // forward to the appropriate callback. The pointerInput is keyed
            // on `text` AND both callbacks so a chapter switch (which changes
            // both text content and length) re-installs the gesture handler
            // with fresh closures — otherwise tap/press offsets would be
            // clamped against the previous chapter's length.
            //
            // Long-press resolves the word boundary at the press position via
            // `TextLayoutResult.getWordBoundary` (a `TextRange`) and passes
            // the substring to onLongPressWord. detectTapGestures dispatches
            // either onTap OR onLongPress for a given gesture, never both, so
            // a long-press never accidentally seeks.
            .then(
                if (onTapWord != null || onLongPressWord != null) {
                    Modifier.pointerInput(onTapWord, onLongPressWord, text) {
                        detectTapGestures(
                            onTap = if (onTapWord != null) {
                                { tap ->
                                    val l = layout
                                    if (l != null) {
                                        val charIndex = l.getOffsetForPosition(tap)
                                            .coerceIn(0, text.length)
                                        onTapWord(charIndex)
                                    }
                                }
                            } else null,
                            onLongPress = if (onLongPressWord != null) {
                                { press ->
                                    val l = layout
                                    if (l != null && text.isNotEmpty()) {
                                        val charIndex = l.getOffsetForPosition(press)
                                            .coerceIn(0, (text.length - 1).coerceAtLeast(0))
                                        val range = l.getWordBoundary(charIndex)
                                        val safeStart = range.start.coerceIn(0, text.length)
                                        val safeEnd = range.end.coerceIn(safeStart, text.length)
                                        val word = text.substring(safeStart, safeEnd).trim()
                                        if (word.isNotEmpty()) onLongPressWord(word)
                                    }
                                }
                            } else null,
                        )
                    }
                } else {
                    Modifier
                }
            )
            // #999 phase 2 — long-press-drag text selection for creating a
            // highlight. Separate detector from the tap/long-press above so
            // the two never collide: a long-press that does NOT drag falls
            // through to the look-up (#188); a long-press that DOES drag
            // selects a range. Anchored at the word boundary under the press
            // (so a clumsy press still grabs a whole word), then extended to
            // the dragged char offset. All coordinates resolve through the
            // same captured `TextLayoutResult.getOffsetForPosition` the rest
            // of the reader uses, so the emitted offsets are in chapter-body
            // UTF-16 space — the exact coordinate `Annotation` stores. Keyed
            // on `text` so a chapter switch re-installs with fresh length
            // clamps.
            .then(
                if (onSelectionDrag != null) {
                    // Key the pointerInput ONLY on `text` (so a chapter switch
                    // re-installs with fresh length clamps); the latest
                    // callbacks come from the hoisted rememberUpdatedState
                    // refs, so a drag-driven recomposition never restarts the
                    // gesture detector mid-drag.
                    Modifier.pointerInput(text) {
                        var anchorOffset = 0
                        detectDragGesturesAfterLongPress(
                            onDragStart = { start ->
                                val l = layout
                                if (l != null && text.isNotEmpty()) {
                                    val pressed = l.getOffsetForPosition(start)
                                        .coerceIn(0, (text.length - 1).coerceAtLeast(0))
                                    // Anchor at the word boundary so the
                                    // selection begins on a whole word, not
                                    // mid-glyph.
                                    val word = l.getWordBoundary(pressed)
                                    anchorOffset = word.start.coerceIn(0, text.length)
                                    val focus = word.end.coerceIn(0, text.length)
                                    currentSelectionDrag.value?.invoke(anchorOffset, focus)
                                }
                            },
                            onDrag = { change, _ ->
                                val l = layout
                                if (l != null && text.isNotEmpty()) {
                                    val focus = l.getOffsetForPosition(change.position)
                                        .coerceIn(0, text.length)
                                    currentSelectionDrag.value?.invoke(anchorOffset, focus)
                                }
                                change.consume()
                            },
                            onDragEnd = { currentSelectionFinished.value?.invoke() },
                            onDragCancel = { currentSelectionFinished.value?.invoke() },
                        )
                    }
                } else {
                    Modifier
                }
            )
            .drawBehind {
                val l = layout ?: return@drawBehind

                // #999 phase 2 — in-progress selection wash. Drawn FIRST
                // (under the word fill + sentence underline) as a translucent
                // accent rect spanning the active selection, segmented per
                // visible line exactly like the underline. Null selection =
                // no-op, so non-selecting callsites are unaffected.
                activeSelection?.let { sel ->
                    val sStart = sel.first.coerceIn(0, text.length)
                    val sEnd = (sel.last + 1).coerceIn(sStart, text.length)
                    if (sEnd > sStart) {
                        val selFill = brass.copy(alpha = 0.35f)
                        val sFirst = l.getLineForOffset(sStart)
                        val sLast = l.getLineForOffset(sEnd.coerceAtLeast(sStart + 1) - 1)
                        for (line in sFirst..sLast) {
                            val lineStart = l.getLineStart(line)
                            val lineEnd = l.getLineEnd(line, visibleEnd = true)
                            val segStart = maxOf(sStart, lineStart)
                            val segEnd = minOf(sEnd, lineEnd)
                            if (segEnd <= segStart) continue
                            val xStart = l.getHorizontalPosition(segStart, usePrimaryDirection = true)
                            val xEnd = l.getHorizontalPosition(segEnd, usePrimaryDirection = true)
                            val top = l.getLineTop(line)
                            val bottom = l.getLineBottom(line)
                            drawRect(
                                color = selFill,
                                topLeft = Offset(xStart, top),
                                size = Size(xEnd - xStart, bottom - top),
                            )
                        }
                    }
                }

                // #994 — per-word karaoke fill, drawn FIRST so the spoken-
                // sentence underline (below) sits on top of the wash. Full
                // line-height rect behind the current word, segmented per
                // visible line exactly like the underline. No `animated`
                // fade: the word fill should track the voice crisply, not
                // breathe. Paints only for a valid word range — `-1`
                // defaults make this a no-op for every non-karaoke callsite.
                if (wordEnd > wordStart && wordStart >= 0) {
                    val wStart = wordStart.coerceIn(0, text.length)
                    val wEnd = wordEnd.coerceIn(wStart, text.length)
                    if (wEnd > wStart) {
                        val wFirst = l.getLineForOffset(wStart)
                        val wLast = l.getLineForOffset(wEnd.coerceAtLeast(wStart + 1) - 1)
                        for (line in wFirst..wLast) {
                            val lineStart = l.getLineStart(line)
                            val lineEnd = l.getLineEnd(line, visibleEnd = true)
                            val segStart = maxOf(wStart, lineStart)
                            val segEnd = minOf(wEnd, lineEnd)
                            if (segEnd <= segStart) continue
                            val xStart = l.getHorizontalPosition(segStart, usePrimaryDirection = true)
                            val xEnd = l.getHorizontalPosition(segEnd, usePrimaryDirection = true)
                            val top = l.getLineTop(line)
                            val bottom = l.getLineBottom(line)
                            drawRect(
                                color = wordFillColor,
                                topLeft = Offset(xStart, top),
                                size = Size(xEnd - xStart, bottom - top),
                            )
                        }
                    }
                }

                if (drawEnd <= drawStart) return@drawBehind
                val safeStart = drawStart.coerceIn(0, text.length)
                val safeEnd = drawEnd.coerceIn(safeStart, text.length)
                val firstLine = l.getLineForOffset(safeStart)
                val lastLine = l.getLineForOffset(safeEnd.coerceAtLeast(safeStart + 1) - 1)
                for (line in firstLine..lastLine) {
                    val lineStart = l.getLineStart(line)
                    val lineEnd = l.getLineEnd(line, visibleEnd = true)
                    val segStart = maxOf(safeStart, lineStart)
                    val segEnd = minOf(safeEnd, lineEnd)
                    if (segEnd <= segStart) continue
                    val xStart = l.getHorizontalPosition(segStart, usePrimaryDirection = true)
                    val xEnd = l.getHorizontalPosition(segEnd, usePrimaryDirection = true)
                    val y = l.getLineBottom(line) - 2.dp.toPx()
                    val width = (xEnd - xStart) * animated
                    drawRect(
                        color = brass,
                        topLeft = Offset(xStart, y),
                        size = Size(width, 2.dp.toPx()),
                    )
                }
            },
    )

}
