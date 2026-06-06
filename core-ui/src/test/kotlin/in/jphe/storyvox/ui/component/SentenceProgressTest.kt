package `in`.jphe.storyvox.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #994 — the per-word "karaoke" highlight needs a 0..1 progress
 * fraction through the current sentence to feed [currentWordRange]. The
 * reader drives a per-frame clock ([withFrameNanos]) that accumulates the
 * elapsed wall-clock time since the engine published the current sentence,
 * scaled by playback speed, and divides by the sentence's duration
 * estimate. That division is the pure math pinned here so the composable's
 * frame loop stays a thin call site.
 *
 * Edge cases that matter during live playback:
 *  - a not-yet-estimated or zero-length sentence ([durationMs] <= 0) must
 *    never divide-by-zero; it returns 1f ("treat as done" → highlight the
 *    last word), which self-corrects on the next sentence boundary;
 *  - overshoot (the clock ran past the estimate before the engine advanced
 *    the sentence) and undershoot (negative elapsed from a clock reset
 *    race) are clamped to [0, 1].
 */
class SentenceProgressTest {

    @Test
    fun `zero elapsed is start of sentence`() {
        assertEquals(0.0f, sentenceProgress(elapsedMs = 0L, durationMs = 1000L), 1e-6f)
    }

    @Test
    fun `half elapsed is halfway through`() {
        assertEquals(0.5f, sentenceProgress(elapsedMs = 500L, durationMs = 1000L), 1e-6f)
    }

    @Test
    fun `full elapsed is end of sentence`() {
        assertEquals(1.0f, sentenceProgress(elapsedMs = 1000L, durationMs = 1000L), 1e-6f)
    }

    @Test
    fun `overshoot past the estimate clamps to 1`() {
        assertEquals(1.0f, sentenceProgress(elapsedMs = 1500L, durationMs = 1000L), 1e-6f)
    }

    @Test
    fun `negative elapsed from a clock-reset race clamps to 0`() {
        assertEquals(0.0f, sentenceProgress(elapsedMs = -200L, durationMs = 1000L), 1e-6f)
    }

    @Test
    fun `zero duration estimate returns 1 instead of dividing by zero`() {
        assertEquals(1.0f, sentenceProgress(elapsedMs = 0L, durationMs = 0L), 1e-6f)
    }

    @Test
    fun `negative duration estimate returns 1 instead of going negative`() {
        assertEquals(1.0f, sentenceProgress(elapsedMs = 100L, durationMs = -50L), 1e-6f)
    }
}
