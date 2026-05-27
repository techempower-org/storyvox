package `in`.jphe.storyvox.playback.tts.source

import `in`.jphe.storyvox.playback.SentenceRange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Source of PCM chunks for the EnginePlayer consumer to write to AudioTrack.
 *
 * Two impls:
 *  - [EngineStreamingSource] runs the VoxSherpa engine on a worker
 *    coroutine, putting generated PCM into a queue. [nextChunk] blocks
 *    on `queue.take`. Subject to producer-can't-keep-up underrun on
 *    slow voice + slow device combos (Piper-high on Tab A7 Lite).
 *  - [CacheFileSource] (PR-E) mmap-reads a pre-rendered chapter PCM
 *    file. Never blocks meaningfully.
 *
 * The consumer treats both uniformly via this sealed interface — the
 * dispatch from streaming → cache lives in
 * [`in`.jphe.storyvox.playback.tts.EnginePlayer.startPlaybackPipeline].
 * When the source is exhausted (chapter end), [nextChunk] returns null.
 */
sealed interface PcmSource {

    /** Sample rate of every chunk this source yields. Stable for the
     *  source's lifetime; the consumer sizes its AudioTrack from this. */
    val sampleRate: Int

    /**
     * Pull the next chunk. Suspends if the source has no chunk ready
     * (streaming impl: producer hasn't generated the next sentence yet;
     * cache impl: never blocks meaningfully). Returns null when the
     * chapter is fully drained.
     *
     * Cancellation: if the calling coroutine is cancelled, blocked
     * impls must throw [kotlinx.coroutines.CancellationException]
     * promptly so the consumer can shut down. The streaming impl
     * achieves this via [kotlinx.coroutines.runInterruptible] around
     * the underlying `queue.take`.
     */
    suspend fun nextChunk(): PcmChunk?

    /**
     * Re-anchor the source to the sentence containing [charOffset].
     * Streaming impl cancels the producer and restarts at the new
     * sentence index. Cache impl seeks the underlying file via the
     * sidecar index.
     */
    suspend fun seekToCharOffset(charOffset: Int)

    /** Release any resources (cancel producer, close mmap, etc).
     *  Wakes any consumer blocked in [nextChunk] so it can exit. */
    suspend fun close()

    /**
     * Argus Fix B (#79) — called by the consumer AFTER it has finished
     * writing this chunk's PCM + trailing silence to AudioTrack. The
     * streaming impl uses this to decrement its `bufferHeadroomMs`
     * counter so the underrun trigger fires when audio actually runs
     * out, not one chunk earlier (the dequeue-time decrement was
     * pessimistic by ~one chunk duration).
     *
     * The cache-file impl has no queue or headroom; it overrides this
     * to a no-op. Default empty body keeps the seam non-breaking for
     * future PcmSource implementations.
     */
    fun decrementHeadroomForChunk(chunk: PcmChunk) = Unit

    /**
     * Issue #290 — current count of chunks waiting in the producer-side
     * queue. Streaming sources back-fill this from their bounded queue;
     * cache sources have no queue and report 0. Read by the Debug
     * overlay's Audio section. Default 0 keeps the seam non-breaking
     * for sources that have no queue concept.
     */
    fun producerQueueDepth(): Int = 0

    /** Companion: configured queue capacity (the bound). Streaming
     *  sources report their `queueCapacity`; cache sources report 0
     *  (no queue). The overlay renders `depth/capacity`. */
    fun producerQueueCapacity(): Int = 0

    /**
     * PR-E (#86) — total ms of audio queued but not yet consumed.
     * [EngineStreamingSource] tracks this dynamically as the producer
     * puts and consumer takes; [CacheFileSource] reports
     * [Long.MAX_VALUE] (cached chapters can't underrun, so the
     * buffer-low UI gating in `EnginePlayer.startPlaybackPipeline`
     * never fires for them).
     *
     * Hoisted to the interface in PR-E so the consumer thread can
     * type its `source` reference as [PcmSource] and read this
     * property without an `is`/`as` cast in the hot loop.
     *
     * Default impl returns a [StateFlow] holding [Long.MAX_VALUE].
     * Subclasses with real producer/consumer flow accounting
     * override with a live flow.
     */
    val bufferHeadroomMs: StateFlow<Long>
        get() = MAX_HEADROOM

    /**
     * PR-E (#86) — mark the in-progress cache write (if any) complete.
     * [EngineStreamingSource] uses this to land its index sidecar on
     * natural end-of-chapter (PR-D); [CacheFileSource] has no cache
     * write to finalize, so its impl is a no-op.
     *
     * Called from `EnginePlayer.startPlaybackPipeline`'s consumer
     * thread on the natural-end branch. Idempotent — multiple calls
     * are safe; the streaming impl nulls out its appender field
     * after the first call.
     */
    fun finalizeCache() {}

    /**
     * #573 — Gapless: authoritative "did the producer emit every
     * sentence then push END_PILL?" flag, set BEFORE the END_PILL is
     * pushed. The consumer reads this when [nextChunk] returns null to
     * disambiguate:
     *
     *   - true = producer walked the whole sentence list naturally, then
     *     pushed END_PILL → this IS a chapter-end; the consumer should
     *     fire `handleChapterDone` regardless of any concurrent
     *     `pipelineRunning` flip.
     *   - false = END_PILL came from [close] (stopPlaybackPipeline),
     *     producer was cancelled mid-sentence, or the source has no
     *     end-of-stream concept (CacheFileSource → see override).
     *
     * Pre-#573 the consumer inferred this from
     * `pipelineRunning.get()` captured at END_PILL dequeue time, then
     * re-checked it AGAIN in the finally block ~100 ms later — opening
     * a race window where `stopPlaybackPipeline` mid-finally would
     * silently skip the natural-end fanout. The producer-set flag is
     * a stable, monotonic signal across that window.
     *
     * Default false keeps the seam non-breaking; sources without an
     * intrinsic end (live audio, recap loop) just leave it false.
     */
    val producedAllSentences: Boolean get() = false

    private companion object {
        /** Shared singleton "infinity" headroom for non-streaming sources.
         *  Allocated once at class load so the default getter doesn't
         *  churn a StateFlow per source instance. */
        val MAX_HEADROOM: StateFlow<Long> = MutableStateFlow(Long.MAX_VALUE)
    }
}

/**
 * One chunk of PCM tagged with its sentence range. The trailing
 * silence is intentional cadence; the consumer spools this many bytes
 * of zero-PCM after the audible PCM to give sentences breathing
 * room. See `EngineStreamingSource.trailingPauseMs` for the
 * punctuation-driven sizing.
 */
data class PcmChunk(
    val sentenceIndex: Int,
    val range: SentenceRange,
    val pcm: ByteArray,
    val trailingSilenceBytes: Int,
) {
    /** Equality is identity-based to keep equals cheap; the consumer
     *  never compares chunks, and the default equals on a data class
     *  with a ByteArray field is structurally wrong (compares array
     *  references) AND an O(N) cliff if we ever fix it to compare
     *  contents. Identity is correct and trivial. */
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = sentenceIndex
}
