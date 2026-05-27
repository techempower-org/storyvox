package `in`.jphe.storyvox.playback.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * Issue #676 — adapter from Android's framework `TextToSpeech` to the
 * `EngineStreamingSource.VoiceEngineHandle` interface that `EnginePlayer`
 * consumes.
 *
 * Architecturally a twin of `AzureVoiceEngine`: instance-based (each
 * active voice gets its own `TextToSpeech`), async-init lifecycle
 * (TextToSpeech.onInit callback can take 100-500ms), output is a temp
 * WAV file we strip to raw PCM.
 *
 * Key constants:
 *  - WAV header is fixed-format 44 bytes for the canonical 16-bit mono
 *    LE output `TextToSpeech.synthesizeToFile` emits. Sample rate lives at
 *    offset 24-27 (little-endian uint32). We parse it on first synth
 *    and cache.
 *  - Engine package binding: passing engineName as the third arg of
 *    `TextToSpeech(context, OnInitListener, engineName)` pins which
 *    installed TTS provider services the request. Without it the OS
 *    falls back to whichever is set as user default.
 *  - Speed/pitch domain: `TextToSpeech.setSpeechRate(1.0f)` is normal,
 *    range [0.1, 5.0]. `setPitch` is the same. We pass storyvox's speed
 *    + pitch sliders through directly with a clamp.
 *  - Sentence-level synth: `synthesizeToFile()` is async with an
 *    `UtteranceProgressListener` completion callback. We block-await
 *    each sentence on a [CompletableDeferred] so the existing
 *    `EnginePlayer` sentence-queue keeps its synchronous contract.
 *
 * **Lifecycle.** [loadModel] is called once when this voice becomes
 * active; [generateAudioPCM] is called per sentence; [shutdown] is
 * called when the active voice changes or the player tears down.
 * Calling shutdown twice is safe; calling generateAudioPCM after
 * shutdown returns null.
 *
 * **Thread safety.** A single per-engine `UtteranceProgressListener`
 * dispatches utterance-completion events into a
 * [ConcurrentHashMap]-backed registry of pending deferreds keyed by
 * utteranceId. Multiple in-flight syntheses are technically supported
 * (the framework serializes them internally anyway), but storyvox's
 * pipeline today drives one sentence at a time so this is conservative
 * future-proofing rather than a hot path.
 */
class SystemTtsEngine(
    private val context: Context,
    /** Package id of the OS engine to bind (e.g. `com.google.android.tts`).
     *  Null means "use the OS default engine" — the same engine
     *  TalkBack uses. */
    private val engineName: String?,
    /** Framework `Voice.name` to select after init succeeds. */
    private val voiceName: String,
) {

    private var tts: TextToSpeech? = null

    @Volatile private var ready: Boolean = false

    /**
     * Parsed sample rate from the WAV header on the first successful
     * synth. Stays 0 until [generateAudioPCM] reads its first file;
     * callers should fall back to [DEFAULT_SAMPLE_RATE] when this is 0.
     */
    @Volatile private var cachedSampleRate: Int = 0

    /** Per-utterance completion routing — UtteranceProgressListener
     *  fires once per synthesizeToFile() and we route by utteranceId.
     *  The gate's [CompletionGate.awaitBlocking] parks the calling thread —
     *  #876 routes System TTS synth on the URGENT_AUDIO producer thread via
     *  the blocking path, while the suspend [generateAudioPCM] reaches the
     *  same await through `runInterruptible` so coroutine callers keep the
     *  suspend contract. */
    private val pending = ConcurrentHashMap<String, CompletionGate>()

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) { /* not used */ }

        override fun onDone(utteranceId: String?) {
            utteranceId ?: return
            pending.remove(utteranceId)?.complete(true)
        }

        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onError(utteranceId: String?) {
            utteranceId ?: return
            pending.remove(utteranceId)?.complete(false)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            utteranceId ?: return
            Log.w(TAG, "synth error utteranceId=$utteranceId code=$errorCode")
            pending.remove(utteranceId)?.complete(false)
        }
    }

    /** Sample rate of synthesized PCM. The cached value comes from the
     *  WAV header we parse on the first synth; before then we return
     *  [DEFAULT_SAMPLE_RATE] so AudioTrack construction can proceed. */
    val sampleRate: Int
        get() = cachedSampleRate.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE

    /**
     * Boot the underlying TextToSpeech and wait for onInit SUCCESS
     * before returning. Selects the requested voice by name on success.
     *
     * Returns true on success, false if:
     *  - the engine package isn't installed,
     *  - onInit reports STATUS_ERROR,
     *  - the requested voice isn't enumerable in the chosen engine.
     */
    suspend fun loadModel(): Boolean = withContext(Dispatchers.IO) {
        if (ready) return@withContext true
        // #903 — sweep any systemtts-*.wav that survived a previous
        // process death between synthesizeToFile and the per-utterance
        // finally-delete. cacheDir has no guaranteed eviction interval
        // and PcmCache.evictToQuota doesn't know about these files, so
        // without this they'd accumulate until the user manually clears
        // the app cache.
        cleanupStaleTempFiles(context)
        val initDeferred = CompletableDeferred<Int>()
        val instance = try {
            if (engineName.isNullOrBlank()) {
                TextToSpeech(context) { status -> initDeferred.complete(status) }
            } else {
                TextToSpeech(
                    context,
                    { status -> initDeferred.complete(status) },
                    engineName,
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "TextToSpeech ctor threw for engine=$engineName: ${t.message}")
            return@withContext false
        }
        tts = instance

        val status = initDeferred.await()
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TextToSpeech.onInit returned non-success status=$status engine=$engineName")
            runCatching { instance.shutdown() }
            tts = null
            return@withContext false
        }

        // Register the utterance listener once at init time. The
        // framework guarantees a single listener registration per
        // TextToSpeech instance; subsequent setOnUtteranceProgressListener
        // calls replace the previous listener (we never reassign).
        runCatching { instance.setOnUtteranceProgressListener(progressListener) }

        // Select the requested voice. The framework enumerates voices
        // lazily on the first .voices access; do it now so a bad
        // voiceName fails loadModel rather than the first synth.
        val voice = runCatching { instance.voices?.firstOrNull { it.name == voiceName } }
            .getOrNull()
        if (voice == null) {
            Log.w(TAG, "voiceName=$voiceName not found in engine=$engineName voices")
            // Don't fail — the framework's default voice for the engine
            // is a graceful fallback (the user still gets audio in some
            // locale rather than nothing). Future versions can tighten.
        } else {
            runCatching { instance.voice = voice }
        }

        ready = true
        true
    }

    /**
     * Synthesize one sentence to PCM. Returns null on:
     *  - Blank text (no point round-tripping the engine for whitespace).
     *  - Engine not ready (loadModel not called or returned false).
     *  - synthesizeToFile returning an error code.
     *  - UtteranceProgressListener firing onError.
     *  - Output file truncated below the WAV header length.
     *
     * Suspend variant for coroutine callers — dispatches to
     * [Dispatchers.IO] so it never blocks the caller's thread. The
     * EnginePlayer producer path uses [generateAudioPCMBlocking] instead
     * (#876); this entry point remains for non-producer callers that
     * want the suspend contract.
     */
    suspend fun generateAudioPCM(
        text: String,
        speed: Float,
        pitch: Float,
    ): ByteArray? = withContext(Dispatchers.IO) {
        // runInterruptible so coroutine cancellation interrupts the
        // gate's blocking await rather than orphaning the synth.
        runInterruptible { synthesizeBlocking(text, speed, pitch) }
    }

    /**
     * #876 — synchronous synth on the **calling thread**, with no
     * dispatcher hop.
     *
     * The EnginePlayer producer worker is pinned at
     * `THREAD_PRIORITY_URGENT_AUDIO`; the previous `runBlocking { … }`
     * wrapper around the suspend [generateAudioPCM] dispatched the synth
     * onto a `Dispatchers.IO` pool thread running at DEFAULT priority,
     * so System TTS was the only engine whose synth missed URGENT_AUDIO
     * scheduling (and the producer thread parked uselessly). This variant
     * runs `synthesizeToFile` + the completion await directly on the
     * URGENT_AUDIO producer thread.
     *
     * Same null-return contract as [generateAudioPCM]. The completion
     * await is bounded by [SYNTH_TIMEOUT_MS] via a [CountDownLatch] so a
     * framework engine that swallows the utterance (no onDone/onError)
     * can't park the producer thread forever.
     */
    fun generateAudioPCMBlocking(
        text: String,
        speed: Float,
        pitch: Float,
    ): ByteArray? = synthesizeBlocking(text, speed, pitch)

    /**
     * Shared synth core. Runs entirely on the calling thread (the
     * framework's `synthesizeToFile` is itself async and posts back via
     * the [progressListener], so this thread only blocks awaiting the
     * [CompletionGate]). Callers that need an off-thread guarantee wrap
     * this in their own dispatcher; the producer path (#876) calls it
     * directly to inherit URGENT_AUDIO priority.
     */
    private fun synthesizeBlocking(
        text: String,
        speed: Float,
        pitch: Float,
    ): ByteArray? {
        if (text.isBlank()) return null
        val instance = tts ?: return null
        if (!ready) return null

        // Clamp into Android TTS's documented range. The framework
        // clamps for us in newer versions, but older OEMs occasionally
        // misbehave outside the documented range — be defensive.
        val rate = min(max(speed, MIN_RATE), MAX_RATE)
        val pitchClamped = min(max(pitch, MIN_PITCH), MAX_PITCH)
        runCatching {
            instance.setSpeechRate(rate)
            instance.setPitch(pitchClamped)
        }

        val utteranceId = UUID.randomUUID().toString()
        val gate = CompletionGate()
        pending[utteranceId] = gate

        val outFile = File(context.cacheDir, "$TEMP_WAV_PREFIX$utteranceId$TEMP_WAV_SUFFIX")
        // Defensive cleanup if a previous run with the same UUID
        // somehow left a leftover file (UUID collision odds are
        // astronomical, but mkdir-ing into a stale handle would be
        // a confusing failure mode).
        runCatching { outFile.delete() }

        val rc = runCatching {
            instance.synthesizeToFile(text, /*params=*/ null, outFile, utteranceId)
        }.getOrElse { t ->
            Log.w(TAG, "synthesizeToFile threw: ${t.message}")
            pending.remove(utteranceId)
            return null
        }
        if (rc != TextToSpeech.SUCCESS) {
            Log.w(TAG, "synthesizeToFile returned non-success rc=$rc text.len=${text.length}")
            pending.remove(utteranceId)
            runCatching { outFile.delete() }
            return null
        }

        // #903 — once synthesizeToFile has accepted the request, the temp
        // WAV exists on disk and MUST be cleaned up on every exit path
        // (timeout, engine error, truncated output, success, or an
        // unexpected throw). A single try/finally around the whole
        // post-accept lifecycle is the only way to guarantee that; the
        // earlier scattered per-branch deletes missed the truncated-output
        // early returns and leaked header-only WAVs into cacheDir.
        try {
            // #716/#902 — bound the await so an engine that swallows the
            // utterance (no onDone, no onError — observed on stock Samsung
            // TTS firmware after a mid-synth engine-package crash) can't
            // park this thread forever. SYNTH_TIMEOUT_MS (30 s) is sized
            // for the slowest devices we support — Samsung TTS on Helio P22T
            // tablets takes 8-15 s for ~500-char sentences — so a real
            // sentence never trips it; only a genuinely stuck engine does.
            // See the constant's kdoc for why this is decoupled from the
            // 12 s buffering watchdog in PlaybackController.
            val synthStart = System.currentTimeMillis()
            val ok = gate.awaitBlocking(SYNTH_TIMEOUT_MS)
            if (ok == null) {
                val elapsed = System.currentTimeMillis() - synthStart
                Log.w(
                    TAG,
                    "synth timed out after ${elapsed}ms (limit ${SYNTH_TIMEOUT_MS}ms) " +
                        "text.len=${text.length} — sentence skipped",
                )
                return null
            }
            if (!ok) {
                return null
            }
            if (!outFile.exists() || outFile.length() <= WAV_HEADER_BYTES) {
                Log.w(TAG, "synthesizeToFile produced no audio body file=${outFile.length()}b")
                return null
            }
            val bytes = outFile.readBytes()
            if (bytes.size <= WAV_HEADER_BYTES) {
                Log.w(TAG, "synthesizeToFile output too short to contain audio (${bytes.size}b)")
                return null
            }
            // Cache the sample rate from the WAV header on first successful synth.
            if (cachedSampleRate == 0) {
                val rateFromHeader = parseWavSampleRate(bytes)
                if (rateFromHeader > 0) {
                    cachedSampleRate = rateFromHeader
                    Log.i(TAG, "cached sample rate from WAV header: $rateFromHeader Hz")
                }
            }
            // Strip the 44-byte header — the rest is signed 16-bit little-endian PCM,
            // matching the contract Piper/Kokoro/Kitten/Azure all return.
            return bytes.copyOfRange(WAV_HEADER_BYTES, bytes.size)
        } finally {
            pending.remove(utteranceId)
            runCatching { outFile.delete() }
        }
    }

    /**
     * Tear down the underlying TextToSpeech. Safe to call multiple
     * times; further [generateAudioPCM] calls after shutdown return
     * null. Any in-flight syntheses get their gates completed `false`
     * so block-awaiting callers wake up promptly.
     */
    fun shutdown() {
        ready = false
        val instance = tts
        tts = null
        if (instance != null) {
            runCatching { instance.stop() }
            runCatching { instance.shutdown() }
        }
        // Wake every block-awaiting caller so they don't hang on a
        // gate whose listener is now detached.
        val drained = pending.keys.toList()
        for (id in drained) {
            pending.remove(id)?.complete(false)
        }
    }

    /**
     * One-shot completion primitive routed by utteranceId. The
     * [progressListener] fires [complete] exactly once (onDone → true,
     * onError → false); [awaitBlocking] parks the calling thread on a
     * [CountDownLatch] until then or the timeout elapses.
     *
     * Why a latch and not a `CompletableDeferred`: #876 needs the synth
     * to run on the URGENT_AUDIO producer thread without a coroutine
     * dispatcher hop, so the await must be a plain blocking call. A
     * `CountDownLatch.await(timeout)` is interruptible, so coroutine
     * callers reach it through [runInterruptible] and cancellation still
     * propagates.
     */
    private class CompletionGate {
        private val latch = CountDownLatch(1)
        @Volatile private var result: Boolean = false

        fun complete(value: Boolean) {
            // CountDownLatch.countDown() past zero is a no-op, so a
            // duplicate listener event (shouldn't happen — the map
            // removes on first fire — but be defensive) is harmless.
            result = value
            latch.countDown()
        }

        /**
         * Block the calling thread until [complete] fires or [timeoutMs]
         * elapses. Returns the completion value, or null on timeout. A
         * thread interrupt (coroutine cancellation via runInterruptible)
         * surfaces as an [InterruptedException] to the caller.
         */
        fun awaitBlocking(timeoutMs: Long): Boolean? =
            if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) result else null
    }

    companion object {
        private const val TAG = "SystemTtsEngine"

        /** Filename prefix for the per-utterance temp WAVs in cacheDir. */
        private const val TEMP_WAV_PREFIX = "systemtts-"
        private const val TEMP_WAV_SUFFIX = ".wav"

        /**
         * #903 — delete any leftover `systemtts-*.wav` temp files in
         * cacheDir. Called from [loadModel] to reclaim files orphaned by
         * a previous process death between synth and the finally-delete.
         */
        private fun cleanupStaleTempFiles(context: Context) {
            runCatching {
                context.cacheDir
                    .listFiles { f ->
                        f.name.startsWith(TEMP_WAV_PREFIX) && f.name.endsWith(TEMP_WAV_SUFFIX)
                    }
                    ?.forEach { runCatching { it.delete() } }
            }
        }

        /**
         * Default sample rate used until the first WAV header parse
         * succeeds. Google TTS emits 24 kHz; Samsung TTS varies. 24 kHz
         * matches the rate Kokoro/Azure already use so AudioTrack
         * construction lines up before the cache populates.
         */
        const val DEFAULT_SAMPLE_RATE: Int = 24_000

        /** Canonical RIFF/WAVE 16-bit mono LE header size in bytes. */
        const val WAV_HEADER_BYTES: Int = 44

        /**
         * Upper bound on how long we'll wait for the
         * `UtteranceProgressListener` to fire `onDone`/`onError` for a
         * single sentence (#716, #902).
         *
         * Originally 10 s, chosen to land just before the
         * `BUFFERING_STUCK_WATCHDOG_END_OF_CHAPTER_MS` (12 s) horizon in
         * `PlaybackController`. That coupling turned out to be wrong: on
         * low-spec hardware, System TTS legitimately takes far longer than
         * the "healthy <500 ms" assumption. Samsung TTS on Helio P22T
         * tablets needs 8-15 s to synthesize a ~500-char sentence, so a
         * 10 s cap fired on *real* sentences — the engine then returned
         * null and the sentence was silently skipped (audible gap +
         * highlight jump), the exact symptom in #902.
         *
         * Now 30 s — matching the chapter-body-wait timeout used elsewhere
         * — and deliberately *decoupled* from the 12 s buffering watchdog.
         * This is a hung-engine backstop, not a pacing knob: it should only
         * fire when an engine has genuinely swallowed the utterance, never
         * on a slow-but-progressing synth. The buffering watchdog upstream
         * handles the audio-stall UX on its own shorter horizon; letting
         * synth run past it is fine because a 12 s stall on a 15 s sentence
         * is buffering-bound, not a dead engine, and recovering by skipping
         * the sentence (the old behavior) is worse than waiting it out.
         * When this 30 s limit *does* trip, the call site logs length +
         * elapsed time so the skip is visible rather than silent.
         */
        const val SYNTH_TIMEOUT_MS: Long = 30_000

        /** Android TTS documented setSpeechRate bounds. */
        private const val MIN_RATE: Float = 0.1f
        private const val MAX_RATE: Float = 5.0f

        /** Android TTS documented setPitch bounds. */
        private const val MIN_PITCH: Float = 0.1f
        private const val MAX_PITCH: Float = 5.0f

        /**
         * Parse the sample rate field from a canonical 44-byte WAV
         * header. Returns 0 if the buffer is too short or the magic
         * bytes don't match the RIFF/WAVE format
         * `synthesizeToFile` emits.
         *
         * Layout (offsets in bytes):
         *  - 0..3  "RIFF"
         *  - 4..7  file size - 8 (little-endian uint32)
         *  - 8..11 "WAVE"
         *  - 12..15 "fmt "
         *  - 16..19 fmt chunk size (16 for PCM)
         *  - 20..21 audio format (1 = PCM)
         *  - 22..23 num channels
         *  - 24..27 sample rate (little-endian uint32) ← what we want
         */
        fun parseWavSampleRate(bytes: ByteArray): Int {
            if (bytes.size < WAV_HEADER_BYTES) return 0
            // Sanity: confirm the RIFF/WAVE magic. A non-WAVE response
            // would be a framework bug (TextToSpeech.synthesizeToFile
            // is documented to emit WAV) but we'd rather return 0 than
            // misread random bytes as a sample rate.
            val isRiff = bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()
            val isWave = bytes[8] == 'W'.code.toByte() && bytes[9] == 'A'.code.toByte() &&
                bytes[10] == 'V'.code.toByte() && bytes[11] == 'E'.code.toByte()
            if (!isRiff || !isWave) return 0
            val b0 = bytes[24].toInt() and 0xFF
            val b1 = bytes[25].toInt() and 0xFF
            val b2 = bytes[26].toInt() and 0xFF
            val b3 = bytes[27].toInt() and 0xFF
            return (b0) or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        }
    }
}
