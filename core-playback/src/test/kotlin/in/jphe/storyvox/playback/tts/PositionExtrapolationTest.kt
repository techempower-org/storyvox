package `in`.jphe.storyvox.playback.tts

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #974 (Part A) — unit coverage for the pure `getTimestamp`
 * extrapolation math used by [EnginePlayer.currentPositionMs].
 *
 * `AudioTrack.getTimestamp` hands back a `(framePosition, nanoTime)`
 * pair anchored to the moment those frames crossed the DAC. Between
 * 50 ms position polls we extrapolate forward to "frames played
 * through the DAC right now" by adding the wall-clock elapsed since
 * the anchor, scaled to frames:
 *
 *   extrapolated = anchorFrames + (nowNanos - anchorNanos) * sampleRate / 1e9
 *
 * These tests pin that arithmetic — and the defensive clamps the issue
 * calls out — without an Android `AudioTrack`. The framework call that
 * fills the timestamp struct stays on the (untested) device path; only
 * the math lives here, mirroring how the rest of the playback suite
 * tests derived position values rather than the framework.
 */
class PositionExtrapolationTest {

    private val SR = 24_000 // sherpa-onnx default; representative

    @Test
    fun `zero elapsed returns the anchor frame exactly`() {
        // now == anchor: no time has passed, so the playhead is exactly
        // where the DAC anchor says it is.
        val frames = extrapolateFrames(
            anchorFrames = 48_000L,
            anchorNanos = 1_000_000_000L,
            nowNanos = 1_000_000_000L,
            sampleRate = SR,
        )
        assertEquals(48_000L, frames)
    }

    @Test
    fun `one second elapsed advances by exactly one sampleRate of frames`() {
        // 1.0 s after the anchor, the DAC has drained sampleRate more
        // frames. 48000 + 24000 = 72000.
        val frames = extrapolateFrames(
            anchorFrames = 48_000L,
            anchorNanos = 1_000_000_000L,
            nowNanos = 2_000_000_000L, // +1.0 s
            sampleRate = SR,
        )
        assertEquals(72_000L, frames)
    }

    @Test
    fun `fifty ms elapsed advances by the poll-cadence worth of frames`() {
        // The post-#970 poll cadence is 50 ms. At 24 kHz that's
        // 0.050 * 24000 = 1200 frames of forward extrapolation — the
        // gap this fix exists to close.
        val frames = extrapolateFrames(
            anchorFrames = 0L,
            anchorNanos = 0L,
            nowNanos = 50_000_000L, // 50 ms in nanos
            sampleRate = SR,
        )
        assertEquals(1_200L, frames)
    }

    @Test
    fun `a stale anchor in the future never rewinds the playhead`() {
        // getTimestamp can hand back an anchor whose nanoTime is
        // slightly AHEAD of our System.nanoTime read when called
        // rapidly (different clock reads). A negative delta must clamp
        // to zero rather than subtract frames — the monotonic latch in
        // currentPositionMs is the outer guard, but we don't want this
        // helper feeding it a value below the anchor either.
        val frames = extrapolateFrames(
            anchorFrames = 48_000L,
            anchorNanos = 2_000_000_000L,
            nowNanos = 1_999_000_000L, // 1 ms BEFORE the anchor
            sampleRate = SR,
        )
        assertEquals(48_000L, frames)
    }

    @Test
    fun `non-positive sampleRate returns the anchor unscaled`() {
        // Defensive: a 0 / negative sampleRate (uninitialized pipeline,
        // corrupt read) must not divide-by-zero or produce garbage —
        // fall back to the anchor frame count.
        val frames = extrapolateFrames(
            anchorFrames = 12_345L,
            anchorNanos = 0L,
            nowNanos = 1_000_000_000L,
            sampleRate = 0,
        )
        assertEquals(12_345L, frames)
    }
}
