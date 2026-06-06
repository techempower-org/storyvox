package `in`.jphe.storyvox.playback.tts

/**
 * Issue #974 (Part A) — pure DAC-anchor extrapolation used by
 * [EnginePlayer.currentPositionMs].
 *
 * `AudioTrack.getTimestamp(AudioTimestamp)` returns a
 * `(framePosition, nanoTime)` pair anchored to the instant those frames
 * crossed the DAC — i.e. the frame the *listener* heard at `nanoTime`,
 * which on Samsung deep-mixer paths trails [AudioTrack.getPlaybackHeadPosition]
 * (the userspace ring-buffer counter) by 30–80 ms. Between position
 * polls we extrapolate that anchor forward to "frames the DAC has played
 * by `nowNanos`":
 *
 *   anchorFrames + (nowNanos - anchorNanos) * sampleRate / 1_000_000_000
 *
 * Kept as a free function so the arithmetic is unit-testable without an
 * Android `AudioTrack` (the framework call that fills the struct stays
 * on the device path). [EnginePlayer.currentPositionMs] feeds the result
 * through its existing speed-invariant axis and monotonic clamp
 * unchanged.
 *
 * Defensive clamps (see #974 care points):
 *  - A negative elapsed delta (a stale anchor whose `nanoTime` reads
 *    slightly ahead of our `System.nanoTime`, possible on rapid calls)
 *    clamps to zero so the helper never rewinds below the anchor.
 *  - A non-positive [sampleRate] (uninitialized pipeline, corrupt read)
 *    returns the anchor unscaled rather than dividing by zero.
 *
 * The multiply is done in [Long] after splitting nanos to avoid
 * overflow: `deltaNanos` is small (one poll cadence, ~5e7 ns) and
 * `sampleRate` is ~2.4e4, so `deltaNanos * sampleRate` (~1.2e12) fits
 * comfortably in a Long before the 1e9 divide.
 */
internal fun extrapolateFrames(
    anchorFrames: Long,
    anchorNanos: Long,
    nowNanos: Long,
    sampleRate: Int,
): Long {
    if (sampleRate <= 0) return anchorFrames
    val deltaNanos = (nowNanos - anchorNanos).coerceAtLeast(0L)
    val extraFrames = deltaNanos * sampleRate / 1_000_000_000L
    return anchorFrames + extraFrames
}
