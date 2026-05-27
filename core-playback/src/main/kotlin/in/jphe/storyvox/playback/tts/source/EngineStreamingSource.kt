package `in`.jphe.storyvox.playback.tts.source

import android.os.Process as AndroidProcess
import `in`.jphe.storyvox.playback.SentenceRange
import `in`.jphe.storyvox.playback.cache.PcmAppender
import `in`.jphe.storyvox.playback.tts.Sentence
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Drives VoxSherpa's [VoiceEngineHandle] on a worker coroutine, putting
 * generated PCM into a bounded queue. The consumer (EnginePlayer's
 * AudioTrack writer thread) pulls from [nextChunk]. Mirrors the producer
 * half of `EnginePlayer.startPlaybackPipeline` pre-PR-A.
 *
 * Why a [LinkedBlockingQueue] not a coroutine [kotlinx.coroutines.channels.Channel]:
 * the EnginePlayer consumer is a pinned OS thread at URGENT_AUDIO; pulling
 * via Channel would force it through the coroutine dispatcher and lose
 * the priority bump (URGENT_AUDIO is per-OS-thread, not per-coroutine).
 * We bridge via [runInterruptible] so the producer can coexist with
 * structured concurrency for shutdown.
 *
 * @param sentences full sentence list for the chapter; the producer
 *  walks from [startSentenceIndex] to the end.
 * @param startSentenceIndex first sentence to generate. Updated by [seekToCharOffset]
 *  by cancelling the producer and restarting from the new index.
 * @param engine the active VoxSherpa-style engine (Piper or Kokoro) wrapped
 *  in a SAM so unit tests can fake it without pulling the AAR.
 * @param speed playback speed; fed to [VoiceEngineHandle.generateAudioPCM]
 *  AND scales the trailing-cadence pause down at faster speeds so a 2× listener
 *  doesn't sit through 700 ms gaps.
 * @param pitch pitch multiplier, fed to engine.
 * @param punctuationPauseMultiplier scales the inter-sentence silence
 *  spliced after each sentence's PCM (issue #90). 0f = no trailing silence
 *  at all; 1f = the audiobook-tuned default in [trailingPauseMs]; >1f
 *  lengthens proportionally. Applied AFTER the speed scaling so the
 *  semantic is "pause length the user wants to hear" — at 2× playback
 *  with multiplier=1f the listener still gets a sensible 175 ms gap, and
 *  at multiplier=0f they get no gap regardless of speed.
 * @param queueCapacity bounded queue depth; producer back-pressures when full.
 *  Defaults to 8 to match the prior EnginePlayer constant.
 * @param pronunciationDictApply user pronunciation-dictionary substitution
 *  (issue #135). Applied to the *spoken* text passed into
 *  [VoiceEngineHandle.generateAudioPCM]; the underlying [Sentence.text]
 *  is left untouched so the highlight ranges (`startChar..endChar` into
 *  the original chapter body) keep working. Defaults to identity so
 *  callers that don't care about pronunciations (tests, pre-#135
 *  integrations) get the unchanged behavior.
 */
class EngineStreamingSource(
    private val sentences: List<Sentence>,
    startSentenceIndex: Int,
    private val engine: VoiceEngineHandle,
    private val speed: Float,
    private val pitch: Float,
    /** Shared with EnginePlayer.loadAndPlay so loadModel() can wait for any
     *  in-flight generateAudioPCM to finish before tearing the model down.
     *  Without this shared mutex, a Piper-to-Piper voice swap can call
     *  loadModel().destroy() while the prior source's generator is still
     *  inside the JNI generate(...) call, corrupting native state. */
    private val engineMutex: Mutex,
    /**
     * PR-D (#86) — optional write-through to the on-disk PCM cache. When
     * non-null, every sentence the producer generates is mirrored into
     * this appender via [PcmAppender.appendSentence]. On [close] (which
     * fires on user pause + pipeline teardown, voice swap, and
     * seek-induced restart) the appender is [PcmAppender.abandon]'d so
     * the partial files don't lie around looking like a complete cache.
     * On natural end-of-chapter (the consumer thread reaches the
     * END_PILL), the consumer calls [finalizeCache] which writes the
     * index sidecar and marks the cache complete for PR-E's
     * `CacheFileSource` to pick up on next play.
     *
     * Null in tests that don't care about the cache, and in pre-cache
     * environments where the feature flag is off (PR-F gates).
     *
     * Note: NOT a `val` — we shadow it as a private mutable field below
     * so that abandon-on-seek / abandon-on-close can null it out and
     * subsequent tee writes from a restarted producer no-op safely.
     * Reassigning a constructor `val` parameter is a compile error in
     * Kotlin, hence the shadow.
     */
    cacheAppender: PcmAppender? = null,
    private val punctuationPauseMultiplier: Float = 1f,
    /**
     * Accessibility scaffold Phase 2 (#486 / #488, v0.5.43) — extra
     * silence (ms) spliced after each sentence's PCM IN ADDITION to
     * the punctuation-pause. Already gated by TalkBack-active by the
     * upstream `A11yPacingConfig`; outside TalkBack this is 0 and
     * the pause behavior is unchanged from v0.5.42.
     *
     * Scales with speed the same way [punctuationPauseMultiplier]
     * does (a 2× listener gets a proportionally shorter pad), so
     * the slider value reads as "pause length the user wants to
     * hear at 1× speed".
     */
    private val extraA11ySilenceMs: Int = 0,
    private val queueCapacity: Int = 8,
    private val pronunciationDictApply: (String) -> String = { it },
    /**
     * Tier 3 (#88) — list of secondary engine handles for parallel
     * synth. When non-empty, the producer fans out across the
     * primary [engine] PLUS each secondary, so [secondaryEngines.size + 1]
     * sentences can be in flight at once. Empty list = serial path
     * (Tier 2 single-thread URGENT_AUDIO producer).
     *
     * The slider in Settings → Performance & buffering → "Parallel
     * synth" controls how many engines storyvox loads. Each engine
     * has its own onnxruntime session (constructed via VoxSherpa's
     * public constructor in v2.7.8+ for Piper, v2.7.9+ for Kokoro),
     * so calls into them run truly in parallel without serializing
     * on the engineMutex.
     *
     * Memory cost is per-instance: ~150 MB for Piper, ~325 MB for
     * Kokoro. The slider tops out at 8 — beyond that, OS scheduling
     * overhead dominates and instance memory cost becomes pathological.
     *
     * The sequencer in [startParallelProducer] keeps queue order even
     * though completions arrive out-of-order; the consumer side sees
     * the same monotonic stream of sentence indices either way.
     */
    private val secondaryEngines: List<VoiceEngineHandle> = emptyList(),
    /**
     * Issue #801 — when `true`, the producer thread(s) run at
     * [AndroidProcess.THREAD_PRIORITY_BACKGROUND] instead of
     * [AndroidProcess.THREAD_PRIORITY_URGENT_AUDIO]. The consumer
     * thread is NOT affected — it keeps URGENT_AUDIO because audio
     * glitches (underruns) are worse than battery drain. The net
     * effect is that TTS synthesis yields more readily to other
     * processes, reducing CPU contention while the OS is explicitly
     * trying to conserve battery.
     *
     * Snapshot at construction time from [PowerSaveMonitor.isPowerSaveMode];
     * a mid-pipeline power-save toggle takes effect on next pipeline
     * rebuild (matches the other live-config knobs like speed/pitch).
     */
    private val powerSaveMode: Boolean = false,
) : PcmSource {

    /** SAM-style handle so tests can fake the engine without pulling the
     *  VoxSherpa AAR onto the JVM unit-test classpath. EnginePlayer wraps
     *  the singleton VoiceEngine / KokoroEngine in this. */
    interface VoiceEngineHandle {
        val sampleRate: Int
        fun generateAudioPCM(text: String, speed: Float, pitch: Float): ByteArray?
    }

    /**
     * Issue #801 — thread priority for the producer side. In power-save
     * mode the producer drops to BACKGROUND so TTS synthesis yields to
     * other processes; normally it stays at URGENT_AUDIO for minimum
     * inter-sentence jitter.
     */
    private val producerThreadPriority: Int =
        if (powerSaveMode) AndroidProcess.THREAD_PRIORITY_BACKGROUND
        else AndroidProcess.THREAD_PRIORITY_URGENT_AUDIO

    override val sampleRate: Int = engine.sampleRate

    /**
     * #573 — Gapless: set true by the producer the moment it has
     * iterated every sentence in [sentences] AND is about to push the
     * END_PILL. The consumer reads this when [nextChunk] returns null
     * to know "this was a natural chapter end" without inferring from
     * the racy `pipelineRunning` flag. Volatile because the producer
     * (on its dedicated executor) writes and the consumer (on the
     * audio thread) reads. See [PcmSource.producedAllSentences] doc
     * for the race this closes.
     */
    @Volatile private var producedAll: Boolean = false
    override val producedAllSentences: Boolean get() = producedAll

    /**
     * PR-7-bonus / Tier 2 (#87) — dedicated single-thread executor for
     * the producer. Pre-Tier-2 the producer ran on `Dispatchers.IO`,
     * a shared coroutine pool that migrates threads on every suspend.
     * `Process.setThreadPriority(URGENT_AUDIO)` is per-OS-thread, so
     * any priority bump leaked across resumptions when the coroutine
     * landed on a different IO worker.
     *
     * Pinning to a single thread keeps the URGENT_AUDIO priority for
     * the entire pipeline lifetime — same shape as the consumer
     * thread that EnginePlayer's startPlaybackPipeline already pins.
     * Reduces inter-sentence scheduling jitter, which was Hazel's
     * Tier 2 recommendation after the multi-core pass (#86) closed
     * the throughput gap. Closed in [close] so the executor doesn't
     * leak across pipeline rebuilds.
     */
    private val producerExecutor = run {
        // Tier 3 (#88): N parallel workers when secondaries are wired,
        // single thread otherwise (Tier 2 shape). Pool size for the
        // parallel path = workers (1 + secondaries) + 2 for the
        // sequencer + feeder coroutines. Without those extra two
        // slots the sequencer can starve when all worker threads are
        // blocked in JNI generateAudioPCM calls — produced chunks
        // get stuck in the completed map and never reach the
        // consumer queue → zero audio output despite pegged CPU.
        // (Bug surfaced 2026-05-10 on tablet w/ instances=2,2: 2
        // workers occupied both threads, sequencer never dispatched.)
        val workerCount = 1 + secondaryEngines.size
        val poolSize = if (secondaryEngines.isEmpty()) 1 else workerCount + 2
        val counter = java.util.concurrent.atomic.AtomicInteger(0)
        Executors.newFixedThreadPool(poolSize) { r ->
            Thread(r, "storyvox-tts-producer-${counter.incrementAndGet()}").apply {
                isDaemon = true
            }
        }
    }
    private val scope = CoroutineScope(
        SupervisorJob() + producerExecutor.asCoroutineDispatcher(),
    )
    private val running = AtomicBoolean(true)
    private val queue = LinkedBlockingQueue<Item>(queueCapacity)

    /**
     * #906 — the chunk most recently handed to the consumer by [nextChunk]
     * that has NOT yet been balanced by a [decrementHeadroomForChunk] call.
     * The producer increments [_bufferHeadroomMs] on enqueue; the consumer
     * is supposed to decrement after it finishes writing the chunk to
     * AudioTrack. But the consumer can abort between dequeue and decrement
     * (pipeline teardown on voice swap / seek / chapter advance flips
     * `pipelineRunning` false mid-write-loop, exiting the consumer before
     * the decrement at EnginePlayer's post-write point). The increment then
     * has no matching decrement and headroom drifts upward — bounded per
     * pipeline lifetime today, but #540's fast-pause reuses the same source
     * across pause-resume cycles, so the drift accumulates.
     *
     * We record the in-flight chunk here at dequeue and settle the account
     * in [close] if the consumer never completed it. Guarded by [headroomLock]
     * so the normal-path decrement and the close-path decrement can't both
     * fire for the same chunk (idempotency the kdoc on
     * [decrementHeadroomForChunk] promised but the prior `coerceAtLeast(0L)`
     * floor didn't actually provide).
     *
     * Volatile + identity-compared under the lock: the consumer thread reads
     * and clears it via [decrementHeadroomForChunk]; [close] runs on the
     * caller thread.
     */
    @Volatile private var inFlightChunk: PcmChunk? = null
    private val headroomLock = Any()

    /**
     * PR-D (#86) — mutable shadow of the constructor's `cacheAppender`
     * param so [seekToCharOffset] and [close] can null it out after
     * abandon. Volatile because the producer reads it on the dedicated
     * producer thread while [seekToCharOffset] / [close] runs on the
     * caller's thread.
     */
    @Volatile private var cacheAppender: PcmAppender? = cacheAppender

    private val _bufferHeadroomMs = MutableStateFlow(0L)

    private val _cacheTeeErrors = MutableStateFlow(0)

    /**
     * PR-D (#86) — count of cache-tee write failures since this source
     * was constructed. Exposed for diagnostic logging — the cache writes
     * are best-effort (a write failure doesn't block playback) so a
     * non-zero value indicates the on-disk cache for THIS chapter run
     * won't be usable. The next play will see `isComplete = false` and
     * re-render fresh.
     *
     * Stays at 0 in normal operation; spikes signal full storage or a
     * permissions regression on the cache directory.
     */
    val cacheTeeErrors: StateFlow<Int> = _cacheTeeErrors.asStateFlow()

    /**
     * Total ms of audio currently buffered in the queue (sum of pcm
     * playback duration + cadence-silence duration across queued chunks).
     * Updated atomically on every producer put and consumer take.
     *
     * The EnginePlayer consumer pauses AudioTrack and surfaces a
     * "Buffering..." UI state when this drops below an underrun threshold,
     * resumes at a higher hysteresis threshold. Lets the listener
     * experience the gap as a clean pause rather than dribbling silence
     * out of an underrunning AudioTrack ring buffer.
     */
    override val bufferHeadroomMs: StateFlow<Long> = _bufferHeadroomMs.asStateFlow()

    /**
     * Issue #290 — PcmSource interface overrides. Surfaces queue depth +
     * configured capacity to the Debug overlay's Audio section. O(1)
     * on LinkedBlockingQueue (delegates to ConcurrentLinkedQueue's
     * size counter); safe to call from any thread.
     */
    override fun producerQueueDepth(): Int = queue.size
    override fun producerQueueCapacity(): Int = queueCapacity

    /** ms of audio represented by [bytes] of PCM at this source's sample rate.
     *  16-bit signed mono → 2 bytes per sample, 1 channel. */
    private fun pcmDurationMs(bytes: Int): Long =
        bytes.toLong() * 1000L / (sampleRate.toLong() * 2L)

    private var producerJob: Job = startProducer(startSentenceIndex)

    override suspend fun nextChunk(): PcmChunk? = runInterruptible {
        val item = queue.take()
        if (item === END_PILL) return@runInterruptible null
        // 2026-05-09 — Argus Fix B (#79): the headroom decrement used
        // to fire here at dequeue time, but the listener hasn't yet
        // heard this audio (it's about to enter AudioTrack's hardware
        // ring buffer). Decrementing at dequeue made `bufferHeadroomMs`
        // reflect "audio in the queue" rather than "audio not yet
        // heard," which fired the underrun trigger one chunk-duration
        // earlier than the listener actually needed. The consumer
        // now calls [decrementHeadroomForChunk] after the AudioTrack
        // write loop exits — see [EnginePlayer]'s consumer.
        //
        // #906 — record this chunk as in-flight so [close] can settle its
        // headroom if the consumer aborts before the matching decrement.
        synchronized(headroomLock) { inFlightChunk = item.chunk }
        item.chunk
    }

    /**
     * Argus Fix B (#79) — called by the consumer AFTER it has finished
     * writing this chunk's PCM + trailing silence to AudioTrack. The
     * decrement happens late so [bufferHeadroomMs] reflects "audio the
     * listener hasn't heard yet" (queue + writes-in-flight), not "audio
     * still in the queue."
     *
     * #906 — idempotent via an identity guard against [inFlightChunk]:
     * the producer-side increment in [startProducer] is balanced by
     * exactly one decrement per dequeued chunk, no matter how many times
     * (or from how many threads) this is called for the same chunk. The
     * consumer's normal post-write call and [close]'s settle-on-abort call
     * race harmlessly — whichever runs first clears [inFlightChunk] and the
     * other no-ops. This is what keeps [bufferHeadroomMs] from drifting
     * upward across #540 fast-pause-resume cycles, where the same source is
     * reused and a mid-write teardown would otherwise leave a phantom
     * increment on the books.
     */
    override fun decrementHeadroomForChunk(chunk: PcmChunk) {
        synchronized(headroomLock) {
            // #906 — only decrement if this chunk is still the recorded
            // in-flight one. Identity guard makes the call idempotent: a
            // second call for the same chunk (or a [close] that races the
            // consumer's decrement) sees inFlightChunk already cleared and
            // no-ops, so the producer's single increment can never be
            // decremented twice.
            if (inFlightChunk !== chunk) return
            inFlightChunk = null
            val durMs = pcmDurationMs(chunk.pcm.size) +
                pcmDurationMs(chunk.trailingSilenceBytes)
            _bufferHeadroomMs.update { (it - durMs).coerceAtLeast(0L) }
        }
    }

    override suspend fun seekToCharOffset(charOffset: Int) {
        val target = sentences.indexOfLast { it.startChar <= charOffset }
            .takeIf { it >= 0 } ?: 0
        producerJob.cancel()
        queue.clear()
        // PR-D (#86) — abandon the in-progress cache. A sparse cache
        // (sentences 0-3 then 12+ because the user seeked forward) is
        // worse than no cache; PR-E's CacheFileSource expects sequential
        // byte offsets. Null the field so the restarted producer below
        // sees no appender and the tee no-ops.
        runCatching { cacheAppender?.abandon() }
        cacheAppender = null
        producerJob = startProducer(target)
    }

    override suspend fun close() {
        running.set(false)
        // #906 — settle the in-flight chunk's headroom. If the consumer
        // aborted between dequeue and its post-write decrement (pipeline
        // torn down mid-write on voice swap / seek / chapter advance), the
        // producer's enqueue increment is still on the books with no
        // matching decrement. Balance it here. The identity guard inside
        // [decrementHeadroomForChunk] makes this safe against a consumer
        // that completes the same chunk concurrently — whichever fires
        // first clears inFlightChunk and the other no-ops.
        inFlightChunk?.let { decrementHeadroomForChunk(it) }
        producerJob.cancel()
        queue.clear()
        // Wake any consumer blocked in take() so nextChunk returns null.
        queue.offer(END_PILL)
        // PR-D (#86) — abandon any partial cache. If the consumer
        // already called [finalizeCache] on natural end, the appender
        // has been nulled out and abandon() is a no-op. Idempotent
        // either way — [PcmAppender.abandon] no-ops on a closed
        // appender too.
        runCatching { cacheAppender?.abandon() }
        cacheAppender = null
        scope.cancel()
        // Tier 2 (#87) — shut the dedicated producer executor down so
        // the daemon thread exits and isn't leaked across pipeline
        // rebuilds (next chapter / seek / voice swap each spin a
        // fresh EngineStreamingSource → fresh executor).
        producerExecutor.shutdownNow()
        // #89 / #886 — block until the executor's threads actually
        // finish. shutdownNow() interrupts but doesn't wait; if a
        // worker is mid-JNI generateAudioPCM the interrupt is queued
        // (JNI calls don't observe Java interrupts — they run to
        // completion) and the thread keeps running until the call
        // returns. Without this await, EnginePlayer.loadAndPlay races
        // ahead and destroy()s the secondary engines while a producer
        // thread is still inside generateAudioPCM on them — JNI
        // use-after-free on the native tts pointer.
        //
        // This await IS the JNI-safety gatekeeper the secondaries
        // rely on: their workers run with useEngineMutex=false (see
        // [runParallelWorker]), so engineMutex does NOT serialize a
        // secondary's in-flight synth against loadAndPlay's destroy().
        // The only thing that does is loadAndPlay calling
        // stopPlaybackPipeline() (→ this close()) and blocking on it
        // BEFORE entering the destroy loop. So if this await returns
        // early while a worker is still in JNI, the destroy that
        // follows is a use-after-free.
        //
        // #886 — budget raised 5s → 15s. The 5s budget was below the
        // worst case the #89 comment itself described: Piper-high on
        // Helio P22T runs ~3.5× realtime → ~7s for a 2s sentence, so
        // a voice swap landing mid-long-sentence blew the 5s window
        // and leaked a worker holding a JNI handle into a
        // soon-destroyed engine. 15s covers that worst case with
        // margin; the voice-swap UX already pays ~30s for a Kokoro
        // load, so the extra in-flight-synth wait is acceptable.
        val drained = runCatching {
            producerExecutor.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS)
        }.getOrDefault(false)
        if (!drained) {
            // #886 — exceeded even the 15s budget. The worker(s) still
            // running hold a JNI handle to an engine EnginePlayer is
            // about to destroy(); we can't stop a thread mid-JNI, so
            // log the live thread state for post-mortem (which engine,
            // which sentence) and let EnginePlayer proceed. The daemon
            // worker dies with the process; the danger is the
            // intervening destroy(), which we've already given the
            // longest practical grace window.
            runCatching {
                val live = Thread.getAllStackTraces().keys
                    .filter { it.name.startsWith("storyvox-tts-producer-") && it.isAlive }
                android.util.Log.w(
                    "EngineStreamingSource",
                    "#886 awaitTermination exceeded 15s — ${live.size} producer " +
                        "thread(s) still live, likely mid-JNI generateAudioPCM. " +
                        "EnginePlayer's secondary destroy() that follows risks a " +
                        "JNI use-after-free. Live workers: " +
                        live.joinToString { it.name },
                )
            }
        }
    }

    /**
     * PR-D (#86) — mark the cache entry for this run complete. Called
     * from the consumer thread's natural-end-of-chapter branch in
     * [`in`.jphe.storyvox.playback.tts.EnginePlayer.startPlaybackPipeline]'s
     * consumer loop, AFTER the AudioTrack write loop has drained the
     * last chunk and the END_PILL surfaced from [nextChunk]. Idempotent
     * — calling on a null appender (no cache configured, or after a
     * previous finalize / abandon nulled it out) is a no-op.
     *
     * MUST be called BEFORE [close] for the cache write to land. After
     * [close] (or after the abandoning behavior triggered by
     * [seekToCharOffset]), the appender has been nulled out and this
     * method silently no-ops.
     *
     * Wrapped in runCatching for the same reason the tee write is —
     * a finalize-time disk failure (e.g. atomic rename can't write the
     * `.tmp` due to ENOSPC) shouldn't break a chapter that the listener
     * just heard end-to-end. Next play will see incomplete cache + re-render.
     */
    override fun finalizeCache() {
        val ap = cacheAppender ?: return
        cacheAppender = null
        // Issue #581 — `complete()` (was `finalize()`) sidesteps the
        // Object.finalize() shadow that produced uncaught
        // IllegalStateExceptions when the GC reclaimed a leaked
        // already-completed appender. The Tee surface here keeps the
        // name `finalizeCache` (matches the producer's pipeline-end
        // semantics); only the inner call to PcmAppender changes.
        runCatching { ap.complete() }
            .onFailure { _cacheTeeErrors.update { it + 1 } }
    }

    private fun startProducer(fromIndex: Int): Job =
        when {
            secondaryEngines.isNotEmpty() -> startParallelProducer(fromIndex)
            else -> startSerialProducer(fromIndex)
        }

    /**
     * Tier 3 (#88) — two-engine parallel producer. Sentences fan out
     * via a [Channel]; two workers each grab the next index and synth
     * concurrently. A sequencer drains a `ConcurrentHashMap<Int,
     * PcmChunk>` in monotonic sentence order to the existing
     * LinkedBlockingQueue, so consumers see the same in-order stream
     * they would in serial mode.
     *
     * Headroom accounting fires only when the sequencer pushes a
     * chunk to the queue — same point the serial producer increments
     * — so the underrun threshold sees consistent values regardless
     * of mode.
     */
    private fun startParallelProducer(fromIndex: Int): Job = scope.launch {
        val jobChan = kotlinx.coroutines.channels.Channel<Int>(
            kotlinx.coroutines.channels.Channel.UNLIMITED,
        )
        val completed = java.util.concurrent.ConcurrentHashMap<Int, PcmChunk>()
        val signal = MutableStateFlow(0L)

        // Feeder: walks sentence indices into the worker channel.
        val feeder = scope.launch {
            try {
                for (i in fromIndex until sentences.size) {
                    if (!running.get()) break
                    jobChan.send(i)
                }
            } finally {
                jobChan.close()
            }
        }

        // Tier 3 N-instance: 1 primary + N-1 secondaries. Primary
        // acquires engineMutex (so EnginePlayer.loadAndPlay's voice-
        // swap can safely destroy the model); secondaries don't —
        // each owns its own VoxSherpa instance with an independent
        // synchronized monitor at the JVM level (VoxSherpa v2.7.8+
        // for VoiceEngine, v2.7.9+ for KokoroEngine).
        val workers = mutableListOf<Job>()
        workers += scope.launch {
            runParallelWorker(engine, jobChan, completed, signal, useEngineMutex = true)
        }
        secondaryEngines.forEach { secondary ->
            workers += scope.launch {
                runParallelWorker(secondary, jobChan, completed, signal, useEngineMutex = false)
            }
        }

        // Sequencer: drain in order. Blocks on [signal] until the
        // next-expected sentence index has been completed.
        try {
            var next = fromIndex
            while (next < sentences.size && running.get()) {
                // #901 — simplified predicate. Pre-fix was
                //   `signal.first { it != signal.value || completed.containsKey(next) }`
                // The `it != signal.value` branch is a dead comparison:
                // StateFlow.first {} receives the current value on
                // subscribe, at which point `it == signal.value`, so the
                // predicate only passes via the second disjunct anyway.
                // On subsequent emissions `it` IS the new value and
                // `signal.value` has already been CAS'd to the same
                // value, so the comparison is racy and can wake
                // spuriously (the sequencer re-enters the inner while,
                // finds the map still empty, and re-subscribes — wasted
                // work on the producer dispatcher). Dropping the dead
                // branch means the sequencer only wakes when the target
                // sentence is actually ready.
                while (running.get() && !completed.containsKey(next)) {
                    signal.first { completed.containsKey(next) }
                }
                if (!running.get()) break
                val chunk = completed.remove(next) ?: continue
                runInterruptible { queue.put(Item(chunk)) }
                _bufferHeadroomMs.update {
                    it + pcmDurationMs(chunk.pcm.size) +
                        pcmDurationMs(chunk.trailingSilenceBytes)
                }
                // PR-D (#86) — tee write FROM THE SEQUENCER. Workers in
                // [runParallelWorker] complete out of order (sentence 3
                // may finish before sentence 1); writing the cache from
                // a worker would record non-monotonic byte offsets and
                // produce a sparse / mis-ordered PCM file. The sequencer
                // drains the `completed` map in monotonic `next` order,
                // matching the order the consumer sees, which is the
                // same invariant PR-E's CacheFileSource will expect on
                // read-back.
                //
                // Best-effort like the serial path — disk failures
                // increment cacheTeeErrors and the cache for this run
                // won't complete, but playback continues.
                if (running.get()) {
                    cacheAppender?.let { ap ->
                        val s = sentences[next]
                        val totalPauseMs = pcmDurationMs(chunk.trailingSilenceBytes).toInt()
                        runCatching { ap.appendSentence(s, chunk.pcm, totalPauseMs) }
                            .onFailure { _cacheTeeErrors.update { it + 1 } }
                    }
                }
                next++
            }
            // #573 — Gapless: see startSerialProducer for the rationale.
            producedAll = true
            android.util.Log.i(
                "EngineStreamingSource",
                "parallel producer: natural end (all ${sentences.size} sentences sequenced), " +
                    "pushing END_PILL",
            )
            // Natural end — push pill once all workers are drained.
            runInterruptible { queue.put(END_PILL) }
        } catch (t: Throwable) {
            // Issue #588 — pre-fix this swallowed every Throwable
            // silently, which would mask a non-cancellation failure
            // (e.g. JNI fault on a secondary engine, OutOfMemory on a
            // long sentence, etc.) the same way as the serial path.
            if (t is kotlinx.coroutines.CancellationException) {
                runCatching {
                    android.util.Log.d(
                        "EngineStreamingSource",
                        "#588 parallel producer: cancelled — silent exit",
                    )
                }
            } else {
                runCatching {
                    android.util.Log.w(
                        "EngineStreamingSource",
                        "#588 parallel producer: UNCAUGHT ${t.javaClass.simpleName} mid-chapter " +
                            "(${t.message}) — pushing END_PILL so consumer can exit",
                        t,
                    )
                }
                runCatching {
                    runInterruptible { queue.put(END_PILL) }
                }
            }
        } finally {
            feeder.cancel()
            workers.forEach { it.cancel() }
        }
    }

    private suspend fun runParallelWorker(
        workerEngine: VoiceEngineHandle,
        jobChan: kotlinx.coroutines.channels.ReceiveChannel<Int>,
        completed: java.util.concurrent.ConcurrentHashMap<Int, PcmChunk>,
        signal: MutableStateFlow<Long>,
        useEngineMutex: Boolean,
    ) {
        // Bump priority on the worker's OS thread. The fixed-thread
        // pool guarantees this thread is dedicated; calls into the
        // engine don't suspend (they're synchronized JNI calls), so
        // the priority sticks for the worker's lifetime.
        //
        // Issue #801 — in power-save mode, use BACKGROUND priority
        // instead of URGENT_AUDIO so parallel workers yield to other
        // processes while the OS is conserving battery.
        runCatching {
            AndroidProcess.setThreadPriority(producerThreadPriority)
        }
        try {
            for (i in jobChan) {
                if (!running.get()) break
                val s = sentences[i]
                val spokenText = pronunciationDictApply(s.text)
                val pcm = if (useEngineMutex) {
                    engineMutex.withLock {
                        if (!running.get()) return@withLock null
                        workerEngine.generateAudioPCM(spokenText, speed, pitch)
                    }
                } else {
                    workerEngine.generateAudioPCM(spokenText, speed, pitch)
                } ?: continue
                if (!running.get()) break
                val mult = punctuationPauseMultiplier.coerceAtLeast(0f)
                val basePauseMs = (trailingPauseMs(s.text) * mult) / speed.coerceAtLeast(0.5f)
                // #486 / #488 — TalkBack a11y pad (already gated by
                // `isTalkBackActive` upstream; outside TalkBack this
                // is 0 and the result equals the v0.5.42 math).
                val a11yPadMs = extraA11ySilenceMs.toFloat() / speed.coerceAtLeast(0.5f)
                val silenceBytes = silenceBytesFor((basePauseMs + a11yPadMs).toInt(), sampleRate)
                completed[i] = PcmChunk(
                    sentenceIndex = i,
                    range = SentenceRange(s.index, s.startChar, s.endChar),
                    pcm = pcm,
                    trailingSilenceBytes = silenceBytes,
                )
                signal.update { it + 1 }
            }
        } catch (_: Throwable) {
            // Cancelled — silent.
        }
    }

    private fun startSerialProducer(fromIndex: Int): Job = scope.launch {
        // Tier 2 (#87) — bump priority on the dedicated producer
        // thread. setThreadPriority sticks because the dispatcher is
        // single-threaded; coroutine suspends resume on the same OS
        // thread so the priority survives the engineMutex.withLock
        // and queue.put suspension points.
        //
        // Issue #801 — in power-save mode, use BACKGROUND priority
        // instead of URGENT_AUDIO so TTS synthesis yields to other
        // processes while the OS is conserving battery.
        runCatching {
            AndroidProcess.setThreadPriority(producerThreadPriority)
        }
        try {
            for (i in fromIndex until sentences.size) {
                if (!running.get()) return@launch
                val s = sentences[i]
                // Issue #135: substitute *only* the text fed to the
                // engine. `s.text` and the highlight char-range stay
                // unchanged — the user sees the original sentence in
                // the reader while the synthesizer reads the
                // phonetic respelling.
                val spokenText = pronunciationDictApply(s.text)
                val pcm = engineMutex.withLock {
                    if (!running.get()) return@withLock null
                    engine.generateAudioPCM(spokenText, speed, pitch)
                } ?: continue
                if (!running.get()) return@launch
                // Issue #90: the user-facing punctuation-pause selector
                // (Off/Normal/Long) lands here as a multiplier. 0× kills
                // the silence entirely, 1× preserves the pre-#90 default,
                // 1.75× ("Long") stretches it. Speed scaling still applies
                // on top so a 2× listener doesn't sit through long gaps
                // even on Long. coerceAtLeast(0f) defends against a
                // negative slipping in from a bad caller — silenceBytesFor
                // already coerces non-positive durations to 0 but we also
                // want the toInt() floor to behave.
                val mult = punctuationPauseMultiplier.coerceAtLeast(0f)
                val basePauseMs =
                    (trailingPauseMs(s.text) * mult) / speed.coerceAtLeast(0.5f)
                // #486 / #488 — TalkBack a11y inter-sentence pad.
                // Already gated by `isTalkBackActive` upstream so the
                // value is 0 outside TalkBack and v0.5.42's math is
                // preserved bit-identical for sighted listeners.
                val a11yPadMs = extraA11ySilenceMs.toFloat() / speed.coerceAtLeast(0.5f)
                val totalPauseMs = (basePauseMs + a11yPadMs).toInt()
                val silenceBytes = silenceBytesFor(totalPauseMs, sampleRate)
                val chunk = PcmChunk(
                    sentenceIndex = i,
                    range = SentenceRange(s.index, s.startChar, s.endChar),
                    pcm = pcm,
                    trailingSilenceBytes = silenceBytes,
                )
                runInterruptible { queue.put(Item(chunk)) }
                _bufferHeadroomMs.update {
                    it + pcmDurationMs(pcm.size) + pcmDurationMs(silenceBytes)
                }
                // PR-D (#86) — tee write. Mirror every generated
                // sentence into the on-disk cache. Synchronous, on the
                // producer's dedicated thread. The appender's
                // FileOutputStream.write+flush is microseconds compared
                // to the generateAudioPCM call (Piper-high synthesis is
                // the slow path; disk I/O on internal flash is
                // >100 MB/s). Wrapped in runCatching because a transient
                // I/O failure (storage full, parent dir wiped) shouldn't
                // take down the playback pipeline — the listener still
                // hears the sentence; the cache simply won't complete
                // this run.
                //
                // `totalPauseMs` is the same value passed to
                // silenceBytesFor — recorded here so PR-E's
                // CacheFileSource can replay the cadence without
                // recomputing trailingPauseMs.
                if (!running.get()) return@launch
                cacheAppender?.let { ap ->
                    runCatching { ap.appendSentence(s, pcm, totalPauseMs) }
                        .onFailure { _cacheTeeErrors.update { it + 1 } }
                }
            }
            // #573 — Gapless: stamp the authoritative "producer walked
            // every sentence" flag BEFORE pushing END_PILL. The consumer
            // reads `producedAllSentences` when nextChunk returns null;
            // setting it pre-push guarantees the consumer never sees a
            // null+false pair on a natural end (which would otherwise
            // race against stopPlaybackPipeline's close() pill).
            producedAll = true
            android.util.Log.i(
                "EngineStreamingSource",
                "serial producer: natural end (all ${sentences.size} sentences emitted), " +
                    "pushing END_PILL",
            )
            // Natural end-of-chapter: push the pill so the consumer's
            // next nextChunk() returns null.
            runInterruptible { queue.put(END_PILL) }
        } catch (t: Throwable) {
            // Issue #588 — pre-fix this was a silent swallow ("Cancelled
            // (close, seek, voice swap) — silent"). The silent path
            // masked any non-cancellation failure: if generateAudioPCM
            // or queue.put threw an unchecked Throwable mid-chapter we
            // exited the producer WITHOUT setting producedAll=true and
            // WITHOUT pushing END_PILL, so the consumer's next
            // nextChunk() parked on queue.take() forever and the
            // chapter-end auto-advance never fired. The watchdog
            // ALSO never fired because isBuffering only flips true
            // inside handleChapterDone, which the consumer never
            // reached. Net result: stuck at chapter-end forever.
            //
            // Log every Throwable so the bug we're hunting (#588) is
            // visible the next time it reproduces. CancellationException
            // is logged at debug grain (expected on close/seek/voice
            // swap); anything else is a warning the chapter-end auto-
            // advance is at risk.
            if (t is kotlinx.coroutines.CancellationException) {
                // runCatching around the logger so a logger fault (e.g.
                // an unmocked android.util.Log call on a pure-JVM unit
                // test) can't itself escape from this catch block.
                runCatching {
                    android.util.Log.d(
                        "EngineStreamingSource",
                        "#588 serial producer: cancelled (close/seek/voice swap) — silent exit",
                    )
                }
            } else {
                runCatching {
                    android.util.Log.w(
                        "EngineStreamingSource",
                        "#588 serial producer: UNCAUGHT ${t.javaClass.simpleName} mid-chapter " +
                            "(${t.message}) — producedAll NOT set, END_PILL NOT pushed; " +
                            "consumer will park on queue.take() forever unless we push END_PILL here",
                        t,
                    )
                }
                // Issue #588 — push END_PILL even on producer crash so
                // the consumer can exit cleanly via the null-chunk
                // branch. producedAll is still false (we didn't finish
                // every sentence) so naturalEnd will be false at the
                // consumer's nextChunk-returned-null check, and the
                // consumer takes the non-natural-end path. The
                // controller's #553 watchdog can then fire because
                // isBuffering=false at end-of-pcm → handleChapterDone
                // doesn't run, but the pipeline-state engine surfaces
                // the stop and the next manual nudge (or watchdog)
                // recovers.
                runCatching {
                    runInterruptible { queue.put(END_PILL) }
                }
            }
        }
    }

    /** Wrapper so the END_PILL identity check via `===` is type-safe and
     *  the data class equals isn't tempted to compare the empty pcm. */
    private class Item(val chunk: PcmChunk)

    private companion object {
        val END_PILL = Item(PcmChunk(-1, SentenceRange(-1, -1, -1), ByteArray(0), 0))
    }
}

/**
 * Length of trailing silence to splice after a sentence's PCM, picked
 * by terminal punctuation. Neural TTS engines barely breathe between
 * sentences; without padding the listener hears one continuous block
 * of speech. Tuned for audiobook-style cadence at 1.0× speed; the
 * caller scales by 1/speed so a 2× listener doesn't sit through 700 ms
 * gaps.
 *
 * Robust against:
 *  - **Closing punctuation** wrapping a sentence (`"He left."`, `(yes!)`)
 *    — strip trailing closers + whitespace before looking at terminal char.
 *  - **Multi-character ellipsis** (`...` written as three dots, common in
 *    HTML-decoded chapter text) — mapped to the single-`…` bucket.
 *  - **Dash variants** — en-dash, em-dash, ASCII hyphen as cesura.
 *
 * Lifted verbatim from the pre-PR-A `EnginePlayer.trailingPauseMs`; this
 * is a refactor commit, no logic changes.
 */
internal fun trailingPauseMs(sentenceText: String): Int {
    val closePunct: Set<Char> = setOf('"', '\'', ')', ']', '}', '”', '’', '»', '」')
    var end = sentenceText.length
    while (end > 0 && (sentenceText[end - 1].isWhitespace() ||
            sentenceText[end - 1] in closePunct)) end--
    if (end == 0) return 60
    // Ellipsis (three-dot ASCII or Unicode U+2026) gets a longer pause than
    // a plain '.' — narrators audibly trail off on "Wait..." vs "Wait."
    // (Thalia's VoxSherpa P0 #3, 2026-05-08; matches AudioEmotionHelper's
    // 380 ms ellipsis bucket.)
    if (end >= 3 && sentenceText.regionMatches(end - 3, "...", 0, 3)) return 380
    return when (sentenceText[end - 1]) {
        '…' -> 380
        '.', '!', '?' -> 350
        ';', ':' -> 200
        ',', '—', '–', '-' -> 120
        // Sentences ending in a closer like ')' get the closer + whitespace
        // stripped above, so a parenthetical like "(yes)" falls through to
        // the inner content's terminal char ('s' here → 60ms fallback).
        // Documented behavior; if narrators want a longer parenthetical
        // pause, that's a separate enhancement (e.g. detect outermost
        // closer before strip).
        else -> 60
    }
}

/** Bytes of zero-PCM = `durationMs × sampleRate × 2` (16-bit mono).
 *  Lifted from EnginePlayer.silenceBytesFor; refactor commit. */
internal fun silenceBytesFor(durationMs: Int, sampleRate: Int): Int {
    if (durationMs <= 0) return 0
    return ((sampleRate.toLong() * durationMs / 1000L).toInt() * 2).coerceAtLeast(0)
}
