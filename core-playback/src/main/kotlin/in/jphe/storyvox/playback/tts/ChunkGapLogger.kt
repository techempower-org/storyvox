package `in`.jphe.storyvox.playback.tts

import android.os.SystemClock
import android.util.Log
import `in`.jphe.storyvox.data.log.DebugLog

/**
 * Inter-chunk gap instrumentation for the EnginePlayer consumer thread.
 *
 * Measures the dead time between the *last* AudioTrack.write of chunk N and
 * the *first* AudioTrack.write of chunk N+1 — that is, the audible silence
 * a listener experiences if the producer can't keep the queue full. On
 * Tab A7 Lite with Piper-high or Kokoro voices, this gap is the bug
 * Aurelia is hunting; this class is the measuring stick.
 *
 * # Gate: DebugLog.isEnabled
 * Issue #861 — the original marker-file gate
 * (`/data/data/in.jphe.storyvox/files/chunk-gap-log`) was unreachable on
 * release builds: `run-as` is blocked and the data dir is inaccessible
 * without root, making the most useful playback diagnostic completely
 * inaccessible to users hitting the bug. The toggle is now bound to
 * [DebugLog.isEnabled], the same process-wide flag driven by Settings →
 * Developer → "Verbose logging" (issue #823). Flip the setting, the next
 * chunk boundary starts emitting; flip it off, the next chunk boundary
 * stops. No marker file, no `adb`, no root.
 *
 * Hot-path cost: `DebugLog.isEnabled()` is a single volatile read
 * ([java.util.concurrent.atomic.AtomicBoolean.get]), cheaper than the
 * previous `File.exists()` stat() call.
 *
 * # Why we don't measure "audio actually emitted from speaker"
 * The truthful answer would require AAudio's underrun callback or a mic
 * + acoustic analysis, both heavy. AudioTrack.write returns when the
 * ring buffer has accepted the bytes, which on minBufferSize tracks
 * (see EnginePlayer.createAudioTrack) is ~130 ms ahead of the speaker.
 * That 130 ms latency is constant across chunks, so it cancels out of
 * gap_ms = chunk_start_ms[N+1] - chunk_end_ms[N]. The gap we log is
 * the gap the listener will hear, modulo a fixed offset.
 *
 * # API
 * `start(voiceId, chunkIndex)` is called immediately before the inner
 * write loop for a chunk; `end()` is called immediately after the
 * trailing-silence spool finishes. The logger computes the gap from
 * the previous chunk's `end()` to the current `start()` and emits it
 * via `Log.i` with tag `ChunkGap`. Tag includes the active voice id
 * so a single logcat session can be split-by-voice with `grep voice=`.
 *
 * Pipeline boundaries (a fresh `startPlaybackPipeline()` call after a
 * pause, seek, voice swap, or the first chunk of a chapter) reset the
 * "previous end" anchor to -1 so the first chunk of a pipeline run
 * doesn't get a misleading multi-second "gap" attributed to user
 * pause time.
 */
class ChunkGapLogger {

    // Issue #928 — `resetForNewPipeline` runs on Main; `chunkStart` /
    // `chunkEnd` run on the consumer thread. Without @Volatile the
    // consumer can read a stale anchor, and on 32-bit JVMs a non-volatile
    // Long read is also non-atomic (high/low halves torn).
    @Volatile private var prevChunkEndMs: Long = -1L
    @Volatile private var currentChunkStartMs: Long = -1L

    /**
     * Reset between pipeline lifetimes (start of a pause/resume, seek,
     * voice swap, chapter advance). Without this, the gap between the
     * last chunk before a pause and the first chunk after resume gets
     * logged as the user's pause duration — useless noise.
     */
    fun resetForNewPipeline() {
        prevChunkEndMs = -1L
        currentChunkStartMs = -1L
    }

    /**
     * Record the start-of-write timestamp for chunk [chunkIndex] played
     * with [voiceId]. If the previous chunk's end timestamp is set,
     * emits the gap in ms via Log.i. Returns immediately if verbose
     * logging is disabled.
     */
    fun chunkStart(voiceId: String, chunkIndex: Int) {
        if (!DebugLog.isEnabled()) return
        currentChunkStartMs = SystemClock.elapsedRealtime()
        val prev = prevChunkEndMs
        if (prev > 0) {
            val gap = currentChunkStartMs - prev
            Log.i(
                TAG,
                "voice=$voiceId chunk=$chunkIndex gap_ms=$gap " +
                    "prev_end_ms=$prev start_ms=$currentChunkStartMs",
            )
        } else {
            Log.i(
                TAG,
                "voice=$voiceId chunk=$chunkIndex gap_ms=- " +
                    "start_ms=$currentChunkStartMs (pipeline-start, no prev)",
            )
        }
    }

    /**
     * Record the end-of-write timestamp for the chunk most recently
     * passed to [chunkStart]. Stores it as the anchor for the next
     * chunk's gap computation. Also logs the per-chunk wall-clock
     * duration (start→end) so a reader can sanity-check that the
     * AudioTrack write loop didn't itself stall.
     */
    fun chunkEnd(voiceId: String, chunkIndex: Int) {
        if (!DebugLog.isEnabled()) return
        val end = SystemClock.elapsedRealtime()
        val start = currentChunkStartMs
        prevChunkEndMs = end
        if (start > 0) {
            Log.i(
                TAG,
                "voice=$voiceId chunk=$chunkIndex write_duration_ms=${end - start} end_ms=$end",
            )
        }
    }

    private companion object {
        const val TAG = "ChunkGap"
    }
}
