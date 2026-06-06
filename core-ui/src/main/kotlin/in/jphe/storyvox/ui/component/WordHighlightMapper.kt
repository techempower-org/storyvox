package `in`.jphe.storyvox.ui.component

/**
 * Issue #994 — per-word "karaoke" highlight.
 *
 * Maps the audio playhead's progress through the current sentence
 * ([sentenceProgress], 0..1) to the char range of the word being spoken,
 * so the reader can draw a background highlight that follows the voice.
 *
 * This is the *proportional* model the issue blesses — no true phoneme
 * timing. Each word's share of the sentence's spoken time is weighted by
 * its character length (longer words take longer to say), which matches
 * how most Android TTS "karaoke" implementations approximate word timing.
 * The error self-corrects every sentence boundary, when the engine's real
 * sentence range advances and the progress clock resets.
 *
 * Pure function (no Compose / Android types) so it unit-tests under plain
 * JUnit and the composable stays a dumb renderer: [SentenceHighlight]
 * resolves the returned [IntRange] to pixels via its [TextLayoutResult].
 *
 * @param text full chapter text
 * @param sentenceStart UTF-16 char index where the current sentence begins
 * @param sentenceEnd UTF-16 char index where the current sentence ends (exclusive)
 * @param sentenceProgress 0..1 fraction of the way through the sentence;
 *        clamped, so callers needn't guard against overshoot/undershoot.
 * @return the current word's char range (inclusive `first`..`last`) in the
 *         same absolute coordinate space as [text], or null when the
 *         sentence window holds no word (empty / whitespace-only).
 */
fun currentWordRange(
    text: String,
    sentenceStart: Int,
    sentenceEnd: Int,
    sentenceProgress: Float,
): IntRange? {
    val start = sentenceStart.coerceIn(0, text.length)
    val end = sentenceEnd.coerceIn(start, text.length)
    if (end <= start) return null

    // Tokenize into words = maximal non-whitespace runs, keeping each
    // word's absolute inclusive char range (first..last) in the chapter
    // text. `wordStart until i` yields an IntRange whose `.last` is the
    // last non-whitespace char (i is the exclusive scan position).
    val words = ArrayList<IntRange>()
    var i = start
    while (i < end) {
        while (i < end && text[i].isWhitespace()) i++
        if (i >= end) break
        val wordStart = i
        while (i < end && !text[i].isWhitespace()) i++
        words.add(wordStart until i)
    }
    if (words.isEmpty()) return null

    // Each word's weight = its char length = (last - first + 1).
    val totalWeight = words.sumOf { it.last - it.first + 1 }.toFloat()
    val progress = sentenceProgress.coerceIn(0f, 1f)

    // Walk cumulative length-weighted bands; the current word is the one
    // whose [cumStart, cumEnd) fraction band contains `progress`. The last
    // word owns progress == 1.0 (the `< bandEnd` test would otherwise miss
    // the exact endpoint), so we fall through to it.
    var cum = 0f
    for (w in words) {
        val weight = (w.last - w.first + 1).toFloat()
        val bandEnd = (cum + weight) / totalWeight
        if (progress < bandEnd) return w
        cum += weight
    }
    return words.last()
}

/**
 * Issue #994 — fraction (0..1) of the way through the current sentence,
 * for feeding [currentWordRange]'s `sentenceProgress`.
 *
 * The reader accumulates [elapsedMs] of (speed-scaled) playback time since
 * the engine published the current sentence and divides by the sentence's
 * [durationMs] estimate. Kept pure so the composable's per-frame
 * [withFrameNanos] loop stays a thin call site and the timing math is
 * unit-tested under plain JUnit.
 *
 * @param elapsedMs playback time elapsed within the current sentence; may
 *        briefly go negative across a clock-reset race (clamped to 0).
 * @param durationMs the sentence's duration estimate. When <= 0 (not yet
 *        estimated, or a zero-length sentence) we return 1f — "treat as
 *        done", highlighting the last word — rather than dividing by zero;
 *        this self-corrects on the next sentence boundary.
 * @return progress in [0f, 1f].
 */
fun sentenceProgress(elapsedMs: Long, durationMs: Long): Float {
    if (durationMs <= 0L) return 1f
    return (elapsedMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}
