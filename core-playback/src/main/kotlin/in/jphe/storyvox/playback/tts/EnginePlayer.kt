package `in`.jphe.storyvox.playback.tts

import android.content.Context
import android.media.AudioAttributes as PlatformAudioAttributes
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Looper
import android.os.Process as AndroidProcess
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.CodeBySonu.VoxSherpa.KittenEngine
import com.CodeBySonu.VoxSherpa.KokoroEngine
import com.CodeBySonu.VoxSherpa.VoiceEngine
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import `in`.jphe.storyvox.data.repository.ChapterRepository
import `in`.jphe.storyvox.data.repository.HistoryRepository
import `in`.jphe.storyvox.data.repository.PlaybackPositionRepository
import `in`.jphe.storyvox.data.repository.playback.NOISE_SCALE_EXPRESSIVE
import `in`.jphe.storyvox.data.repository.playback.NOISE_SCALE_STEADY
import `in`.jphe.storyvox.data.repository.playback.NOISE_SCALE_W_EXPRESSIVE
import `in`.jphe.storyvox.data.repository.playback.NOISE_SCALE_W_STEADY
import `in`.jphe.storyvox.data.repository.playback.PlaybackBufferConfig
import `in`.jphe.storyvox.data.repository.playback.PlaybackChapter
import `in`.jphe.storyvox.data.repository.playback.PlaybackModeConfig
import `in`.jphe.storyvox.data.repository.playback.VoiceTuningConfig
import `in`.jphe.storyvox.data.repository.pronunciation.PronunciationDict
import `in`.jphe.storyvox.data.repository.pronunciation.PronunciationDictRepository
import `in`.jphe.storyvox.playback.EngineSampleRateCache
import `in`.jphe.storyvox.playback.PlaybackError
import `in`.jphe.storyvox.playback.PlaybackState
import `in`.jphe.storyvox.playback.PlaybackUiEvent
import `in`.jphe.storyvox.playback.SPEED_BASELINE_CHARS_PER_SECOND
import `in`.jphe.storyvox.playback.SentenceRange
import `in`.jphe.storyvox.playback.SleepTimer
import `in`.jphe.storyvox.playback.TtsVolumeRamp
import `in`.jphe.storyvox.playback.cache.EngineMutex
import `in`.jphe.storyvox.playback.cache.PcmAppender
import `in`.jphe.storyvox.playback.cache.PcmCache
import `in`.jphe.storyvox.playback.cache.PcmCacheKey
import `in`.jphe.storyvox.playback.cache.PrerenderTriggers
import `in`.jphe.storyvox.playback.tts.source.CacheFileSource
import `in`.jphe.storyvox.playback.tts.source.EngineStreamingSource
import `in`.jphe.storyvox.playback.tts.source.PcmSource
import `in`.jphe.storyvox.playback.voice.EngineType
import `in`.jphe.storyvox.playback.voice.VoiceManager
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * In-process Media3 [Player] that bypasses Android's [android.speech.tts.TextToSpeech]
 * framework. Talks directly to VoxSherpa's [VoiceEngine] (via the engine-lib AAR
 * pulled from JitPack) and manages its own [AudioTrack] with a fat ~2-second buffer.
 *
 * Why this exists: the framework path's `audioAvailable()` chunks land in an
 * AudioTrack whose buffer is sized by the framework — typically small enough
 * that on modest hardware (Galaxy Tab A7 Lite) Piper inference for the next
 * sentence can't finish before the current sentence's buffer drains, producing
 * an audible silence between every sentence. With our own AudioTrack we set
 * a buffer big enough that audio of N keeps playing while we generate N+1 on
 * a worker thread. Seek/pause/skip become AudioTrack operations.
 *
 * Producer/consumer model:
 *  - **Producer** ([generationJob]): walks the sentence list from
 *    [currentSentenceIndex], calls [VoiceEngine.generateAudioPCM] for each,
 *    pushes the resulting PCM byte[] onto [pcmQueue] together with the
 *    sentence range. Blocks on [java.util.concurrent.LinkedBlockingQueue.put]
 *    if the queue is full (back-pressure).
 *  - **Consumer** ([consumerThread]): pulls from [pcmQueue], writes PCM to
 *    the AudioTrack via [AudioTrack.write] (blocking write — returns when
 *    the AudioTrack accepts the bytes). Before writing, fires a
 *    `scope.launch` to Main that surfaces the new sentence range to the UI.
 *
 * Pause/seek tear down the AudioTrack and re-create on resume; cheaper than
 * trying to keep state coherent across a `pause()` call.
 */
/**
 * Issue #189 — playback state for the one-shot recap-aloud TTS pipeline.
 * Distinct from [PlaybackState] because the recap is a transient utterance
 * with its own AudioTrack; conflating the two would force every chapter
 * playback observer to also reason about recap state.
 */
enum class RecapPlaybackState { Idle, Speaking }

@UnstableApi
class EnginePlayer @AssistedInject constructor(
    @Assisted private val context: Context,
    private val chunker: SentenceChunker,
    private val chapterRepo: ChapterRepository,
    private val positionRepo: PlaybackPositionRepository,
    /**
     * Issue #158 — reading-history breadcrumb. Written on every
     * chapter-load inside [loadAndPlay] and stamped `completed = true`
     * inside [handleChapterDone]. Forever retention; powers the
     * Library "History" sub-tab.
     */
    private val historyRepo: HistoryRepository,
    private val sleepTimer: SleepTimer,
    private val voiceManager: VoiceManager,
    private val volumeRamp: TtsVolumeRamp,
    private val bufferConfig: PlaybackBufferConfig,
    private val modeConfig: PlaybackModeConfig,
    private val voiceTuningConfig: VoiceTuningConfig,
    private val pronunciationDictRepo: PronunciationDictRepository,
    /** PR-4 (#183) — Azure HD voices BYOK plumbing. The credentials
     *  store is the gate (no key → can't activate); the engine adapter
     *  satisfies the same `VoiceEngineHandle` contract Piper/Kokoro
     *  use, with HTTPS round-trips replacing JNI calls. */
    private val azureCredentials: `in`.jphe.storyvox.source.azure.AzureCredentials,
    private val azureVoiceEngine: `in`.jphe.storyvox.source.azure.AzureVoiceEngine,
    /** PR-6 (#185) — Azure offline-fallback. Read at error-time inside
     *  observeAzureErrors; observed as a flow to keep the snapshot
     *  fresh without forcing every error-path to suspend. */
    private val azureFallbackConfig: `in`.jphe.storyvox.data.repository.playback.AzureFallbackConfig,
    /** Tier 3 (#88) — experimental parallel-synth toggle. Snapshotted
     *  at pipeline-construction time inside loadAndPlay/startPlayback
     *  so a mid-chapter flip doesn't half-construct a second engine
     *  with no cleanup; takes effect on next pipeline rebuild. */
    private val parallelSynthConfig: `in`.jphe.storyvox.data.repository.playback.ParallelSynthConfig,
    /** Accessibility scaffold Phase 2 (#486 / #488, v0.5.43) — extra
     *  inter-sentence silence applied when TalkBack is active. The
     *  flow is already gated by `isTalkBackActive`; outside TalkBack
     *  it emits 0 and the producer's existing punctuation-pause path
     *  keeps the audiobook-tuned default. */
    private val a11yPacingConfig: `in`.jphe.storyvox.data.repository.playback.A11yPacingConfig,
    /** PR-D (#86) — on-disk PCM cache. EnginePlayer is the only thing
     *  that knows the (chapter, voice, speed, pitch, dict) identity at
     *  pipeline-construction time, so it owns key construction here.
     *  The cache itself is a `@Singleton` injected by Hilt. */
    private val pcmCache: PcmCache,
    /** PR-F (#86) — process-wide engine mutex hoisted to a `@Singleton`
     *  so the background [`in`.jphe.storyvox.playback.cache.ChapterRenderJob]
     *  worker takes the SAME instance the foreground player uses.
     *  Without sharing, a worker render could call `generateAudioPCM`
     *  concurrent with `loadAndPlay`'s `loadModel` — the issue #11
     *  SIGSEGV race. */
    private val engineMutexHolder: EngineMutex,
    /** PR-F (#86) — chapter-natural-end trigger source. The streaming
     *  pipeline's consumer thread calls [handleChapterDone] when the
     *  end-of-stream pill arrives; that path forwards to
     *  [PrerenderTriggers.onChapterCompleted] so the scheduler enqueues
     *  the N+2 render (N+1 was scheduled when N started or is already
     *  in flight). */
    private val prerenderTriggers: PrerenderTriggers,
    /** Issue #560 (stuck-state-fixer) — audio-focus claim around every
     *  AudioTrack write loop. Without this the AudioTrack silently
     *  parks at the framework level and the user sees "PLAYING" with
     *  no sound — the audit's stuck-state symptom (see
     *  AudioFocusController kdoc for the dumpsys evidence). Singleton
     *  so chapter transitions reuse the same focus request rather than
     *  thrashing the stack. */
    private val audioFocus: `in`.jphe.storyvox.playback.AudioFocusController,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    @AssistedFactory
    interface Factory {
        fun create(context: Context): EnginePlayer
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var sentences: List<Sentence> = emptyList()
    private var currentSentenceIndex: Int = 0
    private var currentSpeed: Float = 1.0f
    private var currentPitch: Float = 1.0f

    /** Issue #90 — multiplier on the inter-sentence silence the producer
     *  splices after each sentence's PCM. 0f = no trailing silence at all,
     *  1f = the audiobook-tuned default, >1f lengthens. Wired through to
     *  [EngineStreamingSource] on every pipeline rebuild (same lifecycle
     *  as [currentSpeed] and [currentPitch] — changing it via
     *  [setPunctuationPauseMultiplier] rebuilds the pipeline if playing,
     *  so the new value takes effect on the next sentence boundary). */
    private var currentPunctuationPauseMultiplier: Float = 1f

    /** Engine type for the currently-loaded voice. Set in [loadAndPlay]
     *  after a successful model load; read by the producer in
     *  [startPlaybackPipeline] to decide which engine drives generation. */
    private var activeEngineType: EngineType? = null

    /** Voice id whose model is currently loaded. Used by the active-voice
     *  watcher (see [observeActiveVoice]) to decide whether a DataStore
     *  emission represents a real change worth reloading for, and by
     *  [resume] to detect a "voice changed while paused" situation that
     *  needs a full reload before playback can continue with the new model. */
    private var loadedVoiceId: String? = null

    /** Flagged when the user picks a different voice while playback is
     *  paused. The flag tells [resume] to route through [loadAndPlay] for
     *  a fresh model load instead of restarting the existing pipeline. */
    private var voiceReloadPending: Boolean = false

    /**
     * Issue #569 — last loaded model's parallel-synth state. Combined
     * with [loadedVoiceId] and [activeEngineType] this gives us a
     * compound cache key for "is the engine already loaded with the
     * exact configuration this chapter needs?". When the key matches
     * we skip the loadModel call entirely — measured 1.2 s saved per
     * chapter open on Z Flip3 (Tier 3 Piper ONNX init).
     *
     * Reset to null when the voice changes (via [observeActiveVoice])
     * or the engine is released (via [releaseEngine]) so the next
     * loadAndPlay correctly re-loads.
     */
    private var loadedParallelInstances: Int = 0
    private var loadedThreadsPerInstance: Int = 0

    /**
     * Live-cached pre-synth queue depth. Driven by [bufferConfig.playbackBufferChunks];
     * read by [startPlaybackPipeline] when constructing each new
     * [EngineStreamingSource]. The cache exists because pipeline construction is a
     * synchronous code path (called from [SimpleBasePlayer] handlers) and the
     * underlying DataStore flow is suspending. Volatile because the writer is the
     * collector coroutine and the readers are pipeline threads.
     */
    @Volatile
    private var cachedBufferChunks: Int = 8 // BUFFER_DEFAULT_CHUNKS — duplicated to avoid feature dep

    /** Snapshot of [ParallelSynthConfig.parallelSynthState.instances]
     *  read at sync construction sites (Tier 3 secondary handle
     *  building inside [startPlaybackPipeline]). Same volatile-cache
     *  pattern as [cachedBufferChunks] — the underlying flow is
     *  suspending, the consumer is sync. Defaults to 1 (serial). */
    @Volatile
    private var cachedParallelSynthInstances: Int = 1

    /** Tier 3 (#88) — list of secondary VoiceEngine / KokoroEngine
     *  instances for parallel synth. Sized at (instances-1) so the
     *  primary singleton + N-1 secondaries = N total engines.
     *  Owned by EnginePlayer so loadAndPlay's voice-swap can destroy
     *  them cleanly on engine-type change. Empty list = serial mode. */
    @Volatile
    private var secondaryPiperEngines: List<com.CodeBySonu.VoxSherpa.VoiceEngine> = emptyList()

    @Volatile
    private var secondaryKokoroEngines: List<com.CodeBySonu.VoxSherpa.KokoroEngine> = emptyList()

    /** Issue #119 — Kitten secondaries for the Tier 3 parallel-synth
     *  slider. Each loaded Kitten session is small (~60–80 MB resident
     *  on the fp16 nano model), so an 8-way fan-out fits comfortably
     *  even on a 2 GB device — Kitten is the friendliest engine for the
     *  parallel slider on low-end hardware. Owned per-engine-type like
     *  the other two; voice swap away from Kitten frees this list. */
    @Volatile
    private var secondaryKittenEngines: List<com.CodeBySonu.VoxSherpa.KittenEngine> = emptyList()

    /**
     * Issue #98 — Mode B (Catch-up Pause) live cache. Gates the consumer
     * thread's pause-buffer-resume branches in [startPlaybackPipeline].
     * Volatile because the writer is the collector coroutine and the reader
     * is the URGENT_AUDIO consumer thread; flips take effect on the next
     * iteration of the consumer loop with no pipeline rebuild.
     *
     * Default true preserves PR #77's pause-buffer-resume contract; reading
     * `true` until the first DataStore emission is the safe bias.
     */
    @Volatile
    private var cachedCatchupPause: Boolean = true

    /**
     * Issue #85 — Voice-Determinism preset live cache. Mirrors the
     * persisted DataStore Boolean (`true` = Steady, `false` = Expressive).
     * Default `true` matches the persisted-default + the calmed VITS
     * defaults VoxSherpa has shipped pre-#85, so a fresh install with no
     * DataStore emission yet hits the same noise_scale values the engine
     * defaults to (`VoiceEngine.DEFAULT_NOISE_SCALE` = 0.35).
     *
     * Applied to the live engine via [applyVoiceTuning] when the flow
     * emits, NOT in the consumer/producer hot paths. Volatile because
     * the writer is the collector coroutine on `scope` and the reader
     * is [applyVoiceTuning] (also on `scope`); the cache is purely a
     * "did this actually change?" gate so repeat emissions are cheap.
     */
    @Volatile
    private var cachedVoiceSteady: Boolean = true

    /**
     * Issue #135 — live cache of the user's pronunciation dictionary.
     * Read by [startPlaybackPipeline] when constructing each new
     * [EngineStreamingSource]; the lambda passed to the source closes
     * over the dict instance so substitution applies the most-recent
     * dict at pipeline-construction time. Mid-pipeline edits take
     * effect on the next pipeline rebuild (next chapter / seek / voice
     * swap / speed/pitch change), which matches every other DataStore-
     * driven knob in this class.
     *
     * Volatile because the writer is the collector coroutine and the
     * readers are pipeline construction (Main) + the producer worker.
     * Default is [PronunciationDict.EMPTY] — an identity substitution
     * — so the first pipeline before DataStore hydrates renders
     * exactly like pre-#135.
     */
    @Volatile
    private var cachedPronunciationDict: PronunciationDict = PronunciationDict.EMPTY

    /**
     * Accessibility scaffold Phase 2 (#486 / #488, v0.5.43) — current
     * extra inter-sentence silence in ms. Folds the TalkBack-active
     * signal with the user's slider; the [A11yPacingConfig] flow
     * emits 0 whenever TalkBack is off (so the producer's existing
     * punctuation-pause is the only gap during sighted playback).
     *
     * Volatile because the writer is the collector coroutine on
     * `scope` and the reader is [EngineStreamingSource] (producer
     * worker thread). Mid-pipeline flips take effect on the next
     * sentence boundary — same lifecycle as [currentPunctuationPauseMultiplier].
     */
    @Volatile
    private var cachedA11yExtraSilenceMs: Int = 0

    init {
        observeActiveVoice()
        observeBufferConfig()
        observeModeConfig()
        observeVoiceTuningConfig()
        observePronunciationDict()
        observeAzureErrors()
        observeAzureFallbackConfig()
        observeA11yPacing()
    }

    /**
     * #486 / #488 — keep [cachedA11yExtraSilenceMs] fresh so the
     * producer's per-sentence pause adds the TalkBack pad without
     * suspending. The flow only emits non-zero when TalkBack is
     * actually active; outside TalkBack we stay at 0 and the
     * existing punctuation-pause math is unchanged.
     */
    private fun observeA11yPacing() {
        scope.launch {
            a11yPacingConfig.extraSilenceMs.collect { ms ->
                cachedA11yExtraSilenceMs = ms.coerceIn(0, 1500)
            }
        }
    }

    /** PR-6 (#185) — keep [azureFallbackEnabled] / [azureFallbackVoiceId]
     *  fresh so observeAzureErrors can read them without awaiting a
     *  flow tick inside the error-path. */
    private fun observeAzureFallbackConfig() {
        scope.launch {
            azureFallbackConfig.state.collect { s ->
                azureFallbackEnabled = s.enabled
                azureFallbackVoiceId = s.fallbackVoiceId
            }
        }
    }

    /**
     * PR-5 (#184) — bridge [AzureVoiceEngine.lastError] to
     * [PlaybackState.error]. Each Azure error type maps to a distinct
     * [PlaybackError] subclass so the UI can render different copy
     * (auth-error → re-paste prompt; throttled → quota-hint; offline
     * → "network required"). Null clears the error.
     */
    private fun observeAzureErrors() {
        scope.launch {
            azureVoiceEngine.lastError.collect { err ->
                val mapped: PlaybackError? = when (err) {
                    null -> null
                    is `in`.jphe.storyvox.source.azure.AzureError.AuthFailed ->
                        PlaybackError.AzureAuthFailed
                    is `in`.jphe.storyvox.source.azure.AzureError.Throttled ->
                        PlaybackError.AzureThrottled(err.message ?: "Azure throttled.")
                    is `in`.jphe.storyvox.source.azure.AzureError.NetworkError ->
                        PlaybackError.AzureNetworkUnavailable(
                            err.message ?: "Network error reaching Azure.",
                        )
                    is `in`.jphe.storyvox.source.azure.AzureError.ServerError ->
                        PlaybackError.AzureServerError(
                            httpCode = err.httpCode,
                            message = err.message ?: "Azure server error.",
                        )
                    is `in`.jphe.storyvox.source.azure.AzureError.BadRequest ->
                        // Bad SSML is rare and usually engine-side; surface
                        // as a generic Azure server error so the user
                        // doesn't see an "azure rejected SSML" message
                        // they can't act on. Logs catch the detail.
                        PlaybackError.AzureServerError(
                            httpCode = err.httpCode,
                            message = err.message ?: "Azure rejected request.",
                        )
                }
                _observableState.update { it.copy(error = mapped) }

                // #251 (v0.4.88) — terminal errors must stop the pipeline.
                // Pre-fix: AuthFailed and BadRequest threw from
                // AzureVoiceEngine.synthesize, the producer's catch-all
                // swallowed them silently, and the consumer parked on
                // queue.take forever (no END_PILL pushed). For BadRequest
                // specifically, the OLD code returned null per sentence —
                // so the producer kept retrying ~30 req/s, the chapter
                // synthesized to entirely-empty, and the consumer's "all
                // sentences null" path was misread as "natural end of
                // chapter" → spam-advanced through chapters at ~1/sec
                // burning the user's Azure quota for zero audio.
                //
                // Fix: when the engine emits a terminal AzureError
                // (AuthFailed or BadRequest), stop the pipeline
                // explicitly. The fallback-swap path below still
                // handles non-terminal errors (Throttled, Network,
                // ServerError) the same way.
                val isTerminal = err is `in`.jphe.storyvox.source.azure.AzureError.AuthFailed ||
                    err is `in`.jphe.storyvox.source.azure.AzureError.BadRequest
                if (isTerminal && activeEngineType is EngineType.Azure) {
                    _observableState.update { it.copy(isPlaying = false) }
                    stopPlaybackPipeline()
                }

                // PR-6 (#185) — offline-fallback. Auth errors are NOT
                // fall-back-able (a bad key won't recover by switching
                // voice; the user has to re-paste). BadRequest also not
                // fall-back-able as of #251 — a different voice in the
                // same Azure region might 400 too, and the live-roster
                // pivot in v0.4.84 should prevent the wrong-voice-name
                // root cause anyway. Other errors fall-back-able if
                // the toggle is on AND a fallback voice id is set AND
                // we haven't already swapped this chapter.
                if (err != null &&
                    !isTerminal &&
                    activeEngineType is EngineType.Azure &&
                    azureFallbackEnabled &&
                    azureFallbackVoiceId != null &&
                    !azureFallbackEmittedThisChapter
                ) {
                    val fallbackId = azureFallbackVoiceId!!
                    val fallbackEntry = `in`.jphe.storyvox.playback.voice.VoiceCatalog.byId(fallbackId)
                    val label = fallbackEntry?.displayName ?: fallbackId
                    azureFallbackEmittedThisChapter = true
                    _uiEvents.tryEmit(PlaybackUiEvent.AzureFellBack(label))
                    scope.launch { voiceManager.setActive(fallbackId) }
                }
            }
        }
    }

    /** PR-6 (#185) — Azure offline-fallback config snapshot, refreshed
     *  whenever the settings flow ticks. Read at error-time inside
     *  [observeAzureErrors] (no flow-collect race because the snapshot
     *  is updated on the same scope). */
    @Volatile
    private var azureFallbackEnabled: Boolean = false

    @Volatile
    private var azureFallbackVoiceId: String? = null

    /** Per-chapter dedupe so the fallback toast doesn't fire on every
     *  failed sentence — once the swap has happened, subsequent Azure
     *  errors in the same chapter are silently ignored (the active
     *  voice is no longer Azure anyway, after the swap). Reset on
     *  chapter change. */
    @Volatile
    private var azureFallbackEmittedThisChapter: Boolean = false

    /** Internal hook for [observeFallbackConfig] to update the snapshot. */
    internal fun setAzureFallbackSnapshot(enabled: Boolean, voiceId: String?) {
        azureFallbackEnabled = enabled
        azureFallbackVoiceId = voiceId
    }

    private fun observeBufferConfig() {
        scope.launch {
            // Rebuild the pipeline whenever the buffer-chunks slider
            // changes mid-listen so the new queueCapacity takes effect
            // on the next sentence — without this the cache would
            // update but [EngineStreamingSource] would keep its old
            // capacity until the next chapter load. Same shape as
            // setPunctuationPauseMultiplier's mid-listen seam. The
            // initial hydration emission lands before isPlaying flips
            // true, so there's no spurious rebuild on launch.
            bufferConfig.playbackBufferChunks.collect { v ->
                if (cachedBufferChunks == v) return@collect
                cachedBufferChunks = v
                if (_observableState.value.isPlaying) startPlaybackPipeline()
            }
        }
        scope.launch {
            // Keep [cachedParallelSynthInstances] in sync with the
            // user's parallel-synth slider for the synchronous Azure
            // lookahead path inside [startPlaybackPipeline]. Piper /
            // Kokoro read parallelState directly from suspend
            // contexts; Azure can't because pipeline construction is
            // sync. Snapshot pattern matches [cachedBufferChunks].
            parallelSynthConfig.parallelSynthState.collect { state ->
                cachedParallelSynthInstances = state.instances.coerceIn(1, 8)
            }
        }
    }

    private fun observeModeConfig() {
        scope.launch {
            modeConfig.catchupPause.collect { v ->
                cachedCatchupPause = v
            }
        }
    }

    /** Issue #135 — collect dictionary edits into [cachedPronunciationDict]
     *  so the next pipeline construction picks up the latest entries.
     *  The collector is alive for the player's lifetime; cancellation
     *  follows [scope] when the player is torn down. */
    private fun observePronunciationDict() {
        scope.launch {
            pronunciationDictRepo.dict.collect { v ->
                cachedPronunciationDict = v
            }
        }
    }

    /**
     * Issue #85 — Watches the persisted Voice-Determinism preset and
     * applies it to the live VoxSherpa engine on every change.
     *
     * Each emission lands on the Main dispatcher (via `scope`'s default),
     * but the actual setter call hops to [Dispatchers.IO] because
     * `VoiceEngine.setNoiseScale*()` may destroy + reconstruct
     * `OfflineTts` (a JNI sherpa-onnx call that takes ~1-3 s on Piper).
     * We hold [engineMutex] during that work so it serializes against
     * any in-flight `generateAudioPCM` in the producer — without it the
     * producer's JNI generate could be running while we free `tts`,
     * producing a SIGSEGV (the same hazard `loadModel` already handles
     * by holding [engineMutex] in [loadAndPlay]).
     *
     * If no model is loaded yet, the setters still apply: VoxSherpa
     * stores the values internally and applies them on the next
     * `loadModel()`.
     *
     * Kokoro models ignore noise_scale (they're not VITS). The setters
     * are no-ops on the Kokoro engine, but we still call them through
     * `VoiceEngine` because `VoiceEngine` holds the cached config that
     * applies to the next Piper voice the user picks.
     */
    private fun observeVoiceTuningConfig() {
        scope.launch {
            voiceTuningConfig.voiceSteady.collect { steady ->
                if (cachedVoiceSteady == steady) return@collect
                cachedVoiceSteady = steady
                applyVoiceTuning(steady)
            }
        }
    }

    /**
     * Hops to IO + holds [engineMutex] while pushing the (noise_scale,
     * noise_scale_w) preset down to VoxSherpa's `VoiceEngine`. The setter
     * itself decides whether a model reload is needed (no-op when the new
     * value matches the active value, full destroy + reconstruct
     * otherwise).
     *
     * The first emission on a fresh install carries the default `true`
     * (Steady) which matches `VoiceEngine.DEFAULT_NOISE_SCALE` already —
     * `cachedVoiceSteady` defaults to `true` and [observeVoiceTuningConfig]
     * gates on inequality, so we never trigger a redundant first-pass
     * reload.
     */
    private suspend fun applyVoiceTuning(steady: Boolean) {
        val noiseScale = if (steady) NOISE_SCALE_STEADY else NOISE_SCALE_EXPRESSIVE
        val noiseScaleW = if (steady) NOISE_SCALE_W_STEADY else NOISE_SCALE_W_EXPRESSIVE
        withContext(Dispatchers.IO) {
            engineMutex.withLock {
                // VoiceEngine.setNoiseScale* are synchronized internally and
                // each will reload the active model exactly once if the value
                // changed. Calling both in sequence under engineMutex means
                // worst-case we trigger two reloads — Piper goes 0.35→0.667
                // then 0.667→0.8 — in practice still ≤6 s of warm-up. Could
                // be batched into a single OfflineTts swap upstream later, but
                // not worth the API surface in v1.
                VoiceEngine.getInstance().setNoiseScale(noiseScale)
                VoiceEngine.getInstance().setNoiseScaleW(noiseScaleW)
            }
        }
    }

    /**
     * Watches [VoiceManager.activeVoice] and reacts to user-driven voice
     * changes. The catalog of cases:
     *
     *  - Active voice changes mid-playback → reload the engine and resume
     *    from the current char offset so the listener hears the new voice
     *    immediately (issue #8).
     *  - Active voice changes while paused → flag a pending reload and stop
     *    the existing pipeline; the next [resume] call will do a full
     *    [loadAndPlay] with the new voice.
     *  - Active voice changes before any chapter is loaded → no-op; the
     *    next [loadAndPlay] reads activeVoice itself.
     *
     *  De-dup: we track [loadedVoiceId] so re-emissions of the same id
     *  (e.g. after [setActive] writes the same value, or first-launch
     *  hydration) are filtered out.
     */
    private fun observeActiveVoice() {
        scope.launch {
            voiceManager.activeVoice.collect { active ->
                val newId = active?.id ?: return@collect
                if (newId == loadedVoiceId) {
                    // No-op flip — typically the user re-activated the same
                    // voice, or DataStore re-emitted the persisted value. If
                    // a previous flip had armed [voiceReloadPending] for a
                    // different id and the user has now flipped *back* to
                    // the loaded voice before resuming, clear the pending
                    // flag so we don't force a needless model reload (a
                    // 30 s Kokoro warm-up) on the next [resume].
                    voiceReloadPending = false
                    return@collect
                }
                val s = _observableState.value
                val fictionId = s.currentFictionId
                val chapterId = s.currentChapterId
                if (fictionId == null || chapterId == null) return@collect
                if (s.isPlaying) {
                    // Live swap. Tear down the current pipeline FIRST so the
                    // old generator can't keep pushing old-voice PCM into
                    // the (about-to-be-replaced) queue while loadAndPlay
                    // sits in loadModel for ~30 s on Kokoro. Without this,
                    // the user hears 5–10 s of stale audio before silence
                    // and finally the new voice.
                    stopPlaybackPipeline()
                    loadAndPlay(fictionId, chapterId, s.charOffset)
                } else {
                    voiceReloadPending = true
                    stopPlaybackPipeline()
                    activeEngineType = null
                    // Issue #569 — invalidate the load cache so the
                    // reload-while-paused path takes the slow load.
                    loadedParallelInstances = 0
                    loadedThreadsPerInstance = 0
                }
            }
        }
    }

    /** AudioTrack for the active chapter. Recreated on play/seek/sample-rate change. */
    private var audioTrack: AudioTrack? = null

    /** PCM source feeding the consumer thread. Currently always the streaming
     *  engine impl; PR-E adds a CacheFileSource branch when a complete cache
     *  file exists for `(chapterId, voiceId, speed, pitch, chunkerVersion)`. */
    private var pcmSource: PcmSource? = null

    /**
     * Issue #290 — running tally of audio frames written to the main
     * AudioTrack since the last pipeline build. One frame = one PCM
     * sample on the single mono channel; at 24 kHz that's 1/24000 s
     * per frame. The Debug overlay's `audio buffered` row computes
     * `(framesWritten - track.playbackHeadPosition) * 1000 / sampleRate`
     * to express buffered ms.
     *
     * Updated from the consumer thread inside the write loop. Volatile
     * read on the snapshot path; the consumer is single-threaded so no
     * atomic increment is needed. Reset to 0 on every new pipeline
     * build so the delta against playbackHeadPosition stays meaningful.
     */
    @Volatile private var totalFramesWritten: Long = 0L

    /** Inter-chunk gap measurement (Tab A7 Lite TTS perf lane). Off by
     *  default — reads a marker file at every chunkStart so a developer
     *  can `adb shell touch /data/data/in.jphe.storyvox/files/chunk-gap-log`
     *  and start collecting numbers without a build flip. See [ChunkGapLogger]. */
    private val chunkGapLogger = ChunkGapLogger(context)

    /** Dedicated playback thread. The agent that gets URGENT_AUDIO and never
     *  yields back to a coroutine pool — see the comment on [startPlaybackPipeline]
     *  for why this can't be a coroutine. */
    private var consumerThread: Thread? = null

    /** Per-pipeline run flag. Flipped to false by [stopPlaybackPipeline]; the
     *  consumer checks it inside both the inter-sentence and intra-sentence
     *  loops so it can bail out of long [AudioTrack.write] sequences without
     *  waiting for the buffer to drain. */
    private val pipelineRunning = AtomicBoolean(false)

    /**
     * Issue #540 — user-initiated pause flag. Set true by [pauseTts] when
     * the user taps the play-screen Pause; cleared by [resume]. The
     * consumer thread polls this each iteration and, when true, parks the
     * AudioTrack (track.pause()) and sleeps until the flag clears. The
     * producer keeps generating until its queue fills, then back-pressures.
     *
     * Why an atomic flag instead of the previous "tear-down + rebuild"
     * pattern: rebuilding the AudioTrack on every pause→resume cost ~1-3 s
     * on Samsung tablets (the AudioFlinger track-attach handshake on
     * STREAM_MUSIC). Measured 2.5 s gap on R83W80CAFZB in v0.5.52 — see
     * #540. Holding the track parked and pre-filled lets resume() restart
     * audio within ~150 ms, matching Spotify / Apple Music.
     *
     * Volatile reads from the consumer thread (URGENT_AUDIO priority); the
     * AtomicBoolean's compareAndSet semantics keep state-flips visible
     * without an explicit memory barrier on every loop iteration.
     */
    private val userPaused = AtomicBoolean(false)

    /**
     * Issue #539 — frame counter snapshot at the moment the consumer
     * thread last wrote into the AudioTrack. Combined with the track's
     * [AudioTrack.getPlaybackHeadPosition] gives us "frames the listener
     * has actually heard" — the truthful position the scrubber thumb
     * should track.
     *
     * Read from any thread via [currentPositionMs]; written from the
     * consumer thread inside the write loop.
     */
    @Volatile
    private var pipelineStartCharOffset: Int = 0

    /**
     * Issue #555 — speed at the moment [startPlaybackPipeline] ran. The
     * AudioTrack PCM was synthesized at this speed; each played frame
     * represents (1 / sampleRate) wall-clock seconds AND (speed /
     * sampleRate) seconds of chapter-text content. We multiply
     * `headFrames / sampleRate` by [pipelineStartSpeed] inside
     * [currentPositionMs] to convert wall-clock-played into media-time
     * (the speed-1 axis), so the displayed position stays constant when
     * the user changes speed mid-chapter. Without this snapshot the
     * playhead jumps visibly: at speed=1.5 the same charOffset
     * represents 2/3 the displayed seconds as at speed=1.
     *
     * Constant within a pipeline lifetime — [setSpeed] tears down +
     * rebuilds the pipeline, capturing the new speed here on the
     * rebuild. Defaults to 1.0 so a fresh EnginePlayer with no pipeline
     * yet computes positions on the 1× axis.
     */
    @Volatile
    private var pipelineStartSpeed: Float = 1.0f

    /**
     * Issue #539 — sample rate of the currently-live AudioTrack. Captured
     * at pipeline-construction time so [currentPositionMs] can compute
     * `playbackHeadPosition * 1000 / sampleRate` without holding the
     * track reference itself (which races with [stopPlaybackPipeline]).
     * Volatile because the writer is the construction path on Main and
     * the reader is the position-poll loop on the controller scope.
     */
    @Volatile
    private var pipelineSampleRate: Int = DEFAULT_SAMPLE_RATE

    /**
     * Issue #536 — last truthful position computed by [currentPositionMs].
     * Whenever the AudioTrack reports a regression (e.g. track.flush
     * happened mid-poll, sampling raced with pipeline teardown), we serve
     * this last-known value instead of dropping to 0. Always advances
     * monotonically within a single chapter; resets to 0 only on chapter
     * change.
     */
    @Volatile
    private var lastTruthfulPositionMs: Long = 0L

    @Volatile
    private var lastTruthfulPositionChapter: String? = null

    /**
     * Issue #554 — pinned displayed position while the user has paused.
     * Set in [pauseTts] to the value [currentPositionMs] returns at the
     * moment of pause; cleared in [resume] and on any pipeline rebuild.
     * While non-null, [currentPositionMs] returns this verbatim — so the
     * scrubber thumb cannot regress between the pause-tap and the user
     * resuming.
     *
     * Defense against subtle backward jumps where a paused AudioTrack
     * briefly reports a stale [AudioTrack.getPlaybackHeadPosition], or a
     * pipeline rebuild on the pause-edge resets the monotonic latch. The
     * audit reported a ~3 s regression on cover-tap pause (0:27 → 0:24);
     * pinning makes it impossible by construction.
     */
    @Volatile
    private var pinnedPausePositionMs: Long? = null

    /**
     * Issue #555 — handoff char-offset across a speed-change pipeline
     * rebuild. [setSpeed] computes the truthful audible position via
     * [currentPositionMs] BEFORE tearing the pipeline down, converts it
     * to a char-offset on the speed-invariant baseline axis, and stores
     * it here. [startPlaybackPipeline] reads + clears this when present,
     * using it for [pipelineStartCharOffset] instead of
     * `state.charOffset` (which can lag the audible head by many seconds
     * because the consumer thread's state updates are queued through
     * Main). Without this handoff the new pipeline anchors its position
     * axis at a stale charOffset and the scrubber jumps visibly
     * backward — the exact "1:21 → 1:02" the audit reported on a 1× →
     * 1.5× tap.
     *
     * `null` outside the [setSpeed] → [startPlaybackPipeline] window so
     * other rebuild paths (seek, voice swap, chapter advance) use their
     * own correct value of `state.charOffset` (which for those is set
     * synchronously inline, not via the lagging consumer thread).
     */
    @Volatile
    private var speedHandoffCharOffset: Int? = null

    /** Serializes [VoiceEngine.generateAudioPCM] / [KokoroEngine.generateAudioPCM]
     *  calls. The VoxSherpa engines are process-singletons and the underlying
     *  Sonic/onnxruntime state isn't safe across concurrent threads. Every
     *  pipeline-restart event ([setSpeed], [setPitch], [seekToCharOffset],
     *  voice swap) cancels the old generator coroutine, but cancellation only
     *  fires at suspension points — a JNI call already in flight runs to
     *  completion. Without this mutex, the new pipeline's generator can call
     *  the engine *while the old one is still inside it*, corrupting the
     *  internal state and producing garbled PCM.
     *
     *  PR-F (#86) — was a private `Mutex()` here pre-PR-F; hoisted to a
     *  Hilt `@Singleton` so the background [`in`.jphe.storyvox.playback.cache.ChapterRenderJob]
     *  shares the same instance. Implementation is unchanged — the same
     *  `kotlinx.coroutines.sync.Mutex`, same `withLock` callsites — just
     *  read through the holder so production + background workers see
     *  the same lock. */
    private val engineMutex: Mutex get() = engineMutexHolder.mutex

    private val _observableState = MutableStateFlow(PlaybackState())
    val observableState: StateFlow<PlaybackState> = _observableState.asStateFlow()

    private val _uiEvents = MutableSharedFlow<PlaybackUiEvent>(extraBufferCapacity = 4)
    val uiEvents: SharedFlow<PlaybackUiEvent> = _uiEvents.asSharedFlow()

    /** Issue #189 — one-shot recap-aloud TTS pipeline state. Distinct from
     *  the chapter pipeline because the recap is a transient utterance, not
     *  a chapter: it doesn't update charOffset, doesn't bind to a fiction
     *  id, doesn't persist a position. The two pipelines share the engine
     *  (via [engineMutex]) but have independent AudioTrack + consumer-thread
     *  state. The chapter pipeline must be paused before [speak] starts —
     *  the caller does that, this state is purely for the recap UI's
     *  play/pause toggle. */
    private val _recapPlayback = MutableStateFlow(RecapPlaybackState.Idle)
    val recapPlayback: StateFlow<RecapPlaybackState> = _recapPlayback.asStateFlow()

    /** Recap-only AudioTrack. Lives independently from [audioTrack] so a
     *  recap doesn't tear down chapter playback state. Released by the
     *  recap consumer thread on its own finally block, matching the
     *  chapter-pipeline shape (release-from-writer-thread, no JNI race). */
    private var recapAudioTrack: AudioTrack? = null
    private var recapPcmSource: PcmSource? = null
    private var recapConsumerThread: Thread? = null
    private val recapPipelineRunning = AtomicBoolean(false)

    override fun getState(): State {
        val s = _observableState.value
        return State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder()
                    .addAll(
                        Player.COMMAND_PLAY_PAUSE,
                        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                        Player.COMMAND_GET_METADATA,
                        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_SET_SPEED_AND_PITCH,
                    )
                    .build(),
            )
            .setPlayWhenReady(s.isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            // Issue #557 — surface BUFFERING to MediaSession whenever
            // the engine is buffering. Pre-fix this returned STATE_READY
            // whenever a chapter id was set; combined with
            // setPlayWhenReady(isPlaying) that meant MediaSession's
            // computed state was PLAYING the entire time the engine was
            // physically silent in a chapter-transition or
            // catch-up-pause window. The tablet audit saw `state=PLAYING,
            // position=N frozen` ticking every 3 s in this state. With
            // STATE_BUFFERING the MediaSession surface (Bluetooth tile,
            // Wear OS, Android Auto, the system media notification) all
            // render the buffering indicator and stop claiming playback
            // when none is happening.
            .setPlaybackState(
                when {
                    s.currentChapterId == null -> Player.STATE_IDLE
                    s.isBuffering -> Player.STATE_BUFFERING
                    else -> Player.STATE_READY
                },
            )
            .setPlaylist(buildPlaylist(s))
            .setCurrentMediaItemIndex(0)
            // Issue #536 — Media3 polls this Supplier on every state
            // change to refresh PlaybackState.position. Pre-fix this
            // returned (charOffset / charsPerSec) which can be 0 during
            // a chapter-load state transition (currentChapterId just
            // landed, charOffset still 0). Routing through
            // currentPositionMs() uses the monotonic latch in
            // [lastTruthfulPositionMs], which only ever advances within
            // a chapter, so MediaSession + Bluetooth tiles + Wear / Auto
            // never see the spurious position=0 glitch.
            .setContentPositionMs { currentPositionMs() }
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    // CONTENT_TYPE_MUSIC, not _SPEECH — see createAudioTrack().
                    // Mirrors the AudioTrack-level fix; otherwise the
                    // MediaSession descriptor still advertises speech to
                    // AudioFlinger and Samsung's session-metadata routing
                    // can apply speech-DSP independently of the AudioTrack.
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setPlaybackParameters(PlaybackParameters(currentSpeed, currentPitch))
            .build()
    }

    private fun buildPlaylist(s: PlaybackState): ImmutableList<MediaItemData> {
        val chapterId = s.currentChapterId ?: return ImmutableList.of()
        return ImmutableList.of(
            MediaItemData.Builder(chapterId)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(s.chapterTitle ?: "Chapter")
                        .setAlbumTitle(s.bookTitle)
                        .setArtworkUri(s.coverUri?.let { android.net.Uri.parse(it) })
                        .build(),
                )
                .setDurationUs(s.durationEstimateMs * 1000L)
                .build(),
        )
    }

    private fun charsPerSecondLong(): Long {
        val v = SPEED_BASELINE_CHARS_PER_SECOND * currentSpeed
        return v.toLong().coerceAtLeast(1L)
    }

    /**
     * Issue #539 — current truthful playback position in ms within the
     * loaded chapter. Driven by the AudioTrack's
     * [AudioTrack.getPlaybackHeadPosition] (frame counter against the
     * hardware ring buffer) rather than wall-clock interpolation.
     *
     * Position formula:
     *   `pipelineStartOffsetMs + (playbackHeadFrames * 1000 / sampleRate)`
     *
     * where:
     *  - `pipelineStartOffsetMs` is the chapter-time of `charOffset` at
     *    the moment this pipeline started (recomputed on every fresh
     *    [startPlaybackPipeline]),
     *  - `playbackHeadFrames` is what the AudioTrack has *actually
     *    drained through the speaker* (not what we've handed it).
     *
     * Issue #536 — never returns 0 transiently. When the AudioTrack is
     * null (paused-and-rebuilt fall-back, chapter just loaded, audio
     * stream chapter) we serve [lastTruthfulPositionMs] which is the
     * highest value we've ever returned for this chapter. New chapters
     * reset the latch.
     *
     * Safe to call from any thread.
     */
    fun currentPositionMs(): Long {
        val state = _observableState.value
        val chapterId = state.currentChapterId ?: return 0L

        // Chapter changed since the last measurement — reset the
        // monotonic latch.
        if (chapterId != lastTruthfulPositionChapter) {
            lastTruthfulPositionChapter = chapterId
            lastTruthfulPositionMs = 0L
            // Pinned pause position belongs to a prior chapter; drop it.
            pinnedPausePositionMs = null
        }

        // Issue #554 — pause-pin trumps everything else. While the user
        // has paused, the displayed position MUST equal the value we
        // computed at the moment of pause. Even a transient AudioTrack
        // glitch or a latch reset on a stale path can't punch through
        // this. Cleared by [resume] / [seekToCharOffset] / pipeline
        // restart so live playback resumes serving fresh values.
        pinnedPausePositionMs?.let { return it }

        // Audio-stream chapters route through ExoPlayer, which owns
        // its own currentPosition. Defer to it; the UI scrubber for
        // live streams isn't useful anyway (#373 hides the rail).
        if (state.isLiveAudioChapter) {
            val pos = audioStreamPlayer?.currentPosition ?: 0L
            if (pos > lastTruthfulPositionMs) lastTruthfulPositionMs = pos
            return lastTruthfulPositionMs
        }

        val track = audioTrack
        val sr = pipelineSampleRate
        // Issue #555 — position and duration both live on the speed-1
        // (media-time) axis. `startMs` converts the pipeline's start
        // char-offset to media-time using the speed-invariant baseline
        // (no `* speed`). `playedMs` then converts wall-clock-frames-
        // played into media-time by scaling with [pipelineStartSpeed]:
        // at speed=2 the PCM was synthesized so each played frame
        // represents 2 ms of chapter-text content, not 1.
        //
        // Result: displayed position stays put when the user changes
        // speed mid-chapter (the audit reported 1:21 → 1:02 on a 1× →
        // 1.5× speed-chip tap; this formula keeps position rock-stable
        // because neither the axis nor the conversion changes). Duration
        // (see [estimateDurationMs]) is also speed-invariant on this
        // axis, so the rail length stays put too — exactly the Spotify/
        // Apple Music behavior where the bar stops in place and the
        // audio underneath gets faster/slower.
        val baselineCharsPerSec = SPEED_BASELINE_CHARS_PER_SECOND
            .coerceAtLeast(0.001f)
        val startMs = ((pipelineStartCharOffset / baselineCharsPerSec) * 1000f).toLong()
        // Issue #566 — pin to start char offset during Warming (voice
        // cold-start, no first sentence emitted yet). On Samsung Z Flip3
        // we observed the scrubber advancing ~16 s in 4 s wall-clock
        // before the first PCM landed in the AudioTrack. Root cause:
        // [pipelineRunning] flips to true synchronously inside
        // [startPlaybackPipeline] but the consumer thread hasn't run
        // its firstSentence block yet (no `track.play()`, no `track.write`).
        // On the affected device `playbackHeadPosition` reports stale
        // frames from a recently-released previous track's underlying
        // AudioFlinger slot until the new track is `play()`-ed; the
        // monotonic clamp then locks that stale value in. Solution:
        // skip the AudioTrack-derived branch entirely until the
        // producer has emitted at least one sentence range
        // (currentSentenceRange != null), which is the same gate
        // PlaybackController's `_warmingUp` latch uses. Once a
        // sentence has landed, the consumer has definitely called
        // `track.play()` and `playbackHeadPosition` is meaningful.
        val firstSentenceEmitted = state.currentSentenceRange != null
        if (track != null && sr > 0 && pipelineRunning.get() && firstSentenceEmitted) {
            val headFrames = runCatching { track.playbackHeadPosition.toLong() }.getOrDefault(0L)
            // Wall-clock-ms-of-PCM-played, then scaled up by the speed
            // the PCM was synthesized at to get media-time-ms.
            val wallClockMs = (headFrames * 1000L) / sr.coerceAtLeast(1)
            val playedMediaMs = (wallClockMs * pipelineStartSpeed).toLong()
            val candidate = startMs + playedMediaMs
            // Monotonic clamp — never serve a regression. Covers the
            // pause→resume window where the AudioTrack head can briefly
            // appear to roll backward as the framework rebuilds the
            // streaming pointer.
            if (candidate > lastTruthfulPositionMs) {
                lastTruthfulPositionMs = candidate
            }
            return lastTruthfulPositionMs
        }

        // No live AudioTrack — fall back to the char-offset estimate so
        // the scrubber still has a sensible value before play() lands a
        // pipeline (e.g. while warming up). Same speed-invariant axis
        // as the live branch above.
        val fallback = ((state.charOffset / baselineCharsPerSec) * 1000f).toLong()
        if (fallback > lastTruthfulPositionMs) {
            lastTruthfulPositionMs = fallback
        }
        return lastTruthfulPositionMs
    }

    /**
     * Issue #543 — fire-and-forget engine pre-warm. Called from a UI
     * surface (Library mount, voice-picker open) so the first Listen
     * tap doesn't hit a 5-10 s sherpa-onnx loadModel.
     *
     * Best-effort:
     *  - No-op if a chapter is already playing (pipeline owns the
     *    engines; we don't want to fight it).
     *  - No-op if no voice is active yet (the [observeActiveVoice]
     *    collector will fire on the first DataStore emission anyway).
     *  - Hops to IO; engineMutex serializes against any
     *    [loadAndPlay] call that races us.
     *
     * Idempotent across repeated calls.
     */
    fun prewarmEngine() {
        if (_observableState.value.isPlaying) return
        scope.launch {
            runCatching {
                val active = voiceManager.activeVoice.first() ?: return@launch
                // The active voice flow may emit a "no voice" entry on
                // a fresh install. Loading would fail anyway; bail.
                if (active.id.isBlank()) return@launch
                android.util.Log.i(
                    "EnginePlayer",
                    "prewarm: scheduling load for voice=${active.id} engineType=${active.engineType}",
                )
                // We don't actually call loadModel here — that's the
                // expensive path and we don't want to fight a future
                // loadAndPlay. The cheaper warm is to ensure the engine
                // singleton is constructed (the sherpa-onnx
                // VoiceEngine.getInstance() does a small native init on
                // first call) so the first chapter's loadModel doesn't
                // pay the singleton's construction cost on top of the
                // model load. Reading sampleRate is the canonical "wake
                // the singleton" trigger.
                withContext(Dispatchers.IO) {
                    when (active.engineType) {
                        is EngineType.Kokoro ->
                            runCatching { KokoroEngine.getInstance().sampleRate }
                        is EngineType.Kitten ->
                            runCatching { KittenEngine.getInstance().sampleRate }
                        is EngineType.Azure -> {
                            // Azure has no JNI singleton to warm; the
                            // HTTPS client lazy-inits on first request
                            // anyway. No-op.
                        }
                        else ->
                            runCatching { VoiceEngine.getInstance().sampleRate }
                    }
                    // Issue #582 — seed the @Volatile sample-rate cache
                    // off the engine on this IO prewarm pass. Any
                    // subsequent UI read (speed-chip cycle re-entering
                    // startPlaybackPipeline mid-loadModel) hits the
                    // volatile rather than the contended engine lock.
                    EngineSampleRateCache.refreshFromEngine()
                }
            }.onFailure { t ->
                android.util.Log.w("EnginePlayer", "prewarm: best-effort warm failed", t)
            }
        }
    }

    // ----- Storyvox-internal API -----

    suspend fun loadAndPlay(fictionId: String, chapterId: String, charOffset: Int) {
        // PR-6 (#185) — fallback toast dedupe is per-chapter; reset
        // here so the next chapter's first Azure failure can re-fire
        // the toast. Cheap (one volatile write) so we don't gate it
        // on whether the chapter actually changed.
        azureFallbackEmittedThisChapter = false
        val chapter: PlaybackChapter = chapterRepo.getChapter(chapterId) ?: run {
            // Issue #530 — when chapter fetch fails, do NOT leave the
            // previously-playing chapter in [PlaybackState] — that's
            // the "routes to previously-playing item" bug (player stays
            // pointed at the OLD chapter, error never surfaces in the
            // UI because the engineState mapping prefers
            // currentChapterId == null → Idle over the error band).
            //
            // Clear the chapter pointer + cover + sentence range so the
            // sibling reader UI bails to its empty-state and renders the
            // error banner. Preserve fictionId so a Retry button can
            // resolve which book the user intended without round-trip
            // through navigation state.
            _observableState.update {
                it.copy(
                    currentFictionId = fictionId,
                    currentChapterId = null,
                    charOffset = 0,
                    isPlaying = false,
                    bookTitle = null,
                    chapterTitle = null,
                    coverUri = null,
                    currentSentenceRange = null,
                    durationEstimateMs = 0L,
                    error = PlaybackError.ChapterFetchFailed(
                        "Chapter not ready (id=$chapterId). Download may still be queued.",
                    ),
                )
            }
            // Stop any stale pipeline so the OLD chapter's audio doesn't
            // keep streaming under an empty player surface.
            stopPlaybackPipeline()
            invalidateState()
            return
        }

        // Issue #373 — audio-stream backend (KVMR community radio + future
        // LibriVox / Internet Archive). When the chapter carries a
        // Media3-routable URL, fork off to the ExoPlayer-backed code path
        // and bypass TTS entirely. The TTS pipeline below assumes a voice
        // model + text body; neither applies to a live stream.
        if (chapter.audioUrl != null) {
            loadAndPlayAudioStream(fictionId, chapterId, chapter)
            return
        }

        // Issue #373 — coming back to a text chapter from an audio
        // chapter (KVMR → Royal Road) needs to tear down the sibling
        // ExoPlayer so it doesn't keep streaming under the TTS
        // playback. The clear-isLiveAudioChapter flag re-enables the
        // pitch slider UI in the same emit.
        if (_observableState.value.isLiveAudioChapter) {
            stopAudioStreamPlayer()
            _observableState.update { it.copy(isLiveAudioChapter = false) }
        }

        val active = voiceManager.activeVoice.first()
        if (active == null) {
            _observableState.update {
                it.copy(isPlaying = false, error = PlaybackError.EngineUnavailable)
            }
            return
        }

        // Surface the chapter + isPlaying=true BEFORE the model loads so the
        // UI's "warming up" state (sentenceEnd == 0 && isPlaying) shows the
        // brass spinner immediately. Sherpa-onnx Kokoro init can take 30+s
        // on modest hardware; without this the screen sits blank that long.
        val text = chapter.text
        sentences = chunker.chunk(text)
        // Issue #442 — Gutenberg-derived plain text can be "stripTags(htmlBody)"-
        // empty for spine entries that are pure-HTML wrappers (front-matter,
        // PG header pages, image-only inserts). When that happens the
        // chapter row carries non-empty text from getChapter()'s
        // is-not-empty guard (so we got here) but the sentence chunker
        // emits zero sentences — the producer loop then iterates 0
        // entries, pushes END_PILL immediately, the consumer treats
        // that as naturalEnd, and the user sees state=PLAYING +
        // position=0 forever because `isPlaying=true` was already
        // surfaced above. Surface a typed error and bail before the
        // pipeline spins up so the UI can render "Couldn't read this
        // chapter aloud" rather than buffering indefinitely. The
        // brass spinner clears on isPlaying=false; the error renders
        // in the player's error band.
        if (sentences.isEmpty()) {
            android.util.Log.w(
                "EnginePlayer",
                "loadAndPlay: chapter $chapterId yielded zero sentences " +
                    "(text.length=${text.length}, first 80=${text.take(80)}); " +
                    "surfacing typed error — see #442",
            )
            _observableState.update {
                it.copy(
                    isPlaying = false,
                    error = PlaybackError.ChapterFetchFailed(
                        "This chapter has no readable text — try the next chapter.",
                    ),
                )
            }
            invalidateState()
            return
        }
        currentSentenceIndex = sentences.indexOfFirst { charOffset <= it.endChar }
            .takeIf { it >= 0 } ?: 0
        // Issue #442 — synth event log on the hot path. When playback
        // hangs in production we have no per-chapter visibility into
        // which step stalled (chunker / engine load / first synth /
        // queue handoff). One info-level line per pipeline start gives
        // us a consistent breadcrumb at logcat capture time without
        // adding any cost in the common path.
        android.util.Log.i(
            "EnginePlayer",
            "loadAndPlay: chapter=$chapterId sentences=${sentences.size} " +
                "fromIndex=$currentSentenceIndex textChars=${text.length}",
        )
        _observableState.update {
            it.copy(
                currentFictionId = fictionId,
                currentChapterId = chapterId,
                charOffset = charOffset,
                isPlaying = true,
                // Issue #524 — clear the chapter-advance buffering flag
                // we set in advanceChapter while waiting for the next
                // chapter's body. The new pipeline takes over and the
                // sibling UI rolls back to Playing (or Warming until the
                // first sentence range emits).
                isBuffering = false,
                bookTitle = chapter.bookTitle,
                chapterTitle = chapter.title,
                coverUri = chapter.coverUrl,
                durationEstimateMs = estimateDurationMs(text),
                currentSentenceRange = null,
                error = null,
            )
        }
        // Issue #158 — stamp the History breadcrumb right after the state
        // flips to a new currentChapterId. We log the open BEFORE the
        // pipeline starts because the row should land even if the user
        // taps a chapter and immediately backs out before audio starts
        // — that still counts as "opened" in the History tab's sense.
        // Upsert semantics mean re-opens move the row to the top
        // without creating dupes. No try/catch: a Room write failure
        // here would already crash the player on the next position-save,
        // and history is non-load-bearing for playback.
        historyRepo.logOpen(fictionId, chapterId)
        invalidateState()

        // #89 — stop the OLD pipeline FIRST, before we destroy any of
        // its engines. The pre-#89 code did `engineMutex.withLock {
        // destroy old secondaries; load new }` and only THEN called
        // startPlaybackPipeline (which internally calls
        // stopPlaybackPipeline first). That meant the old pipeline's
        // producer threads were still alive — and possibly inside a
        // JNI generateAudioPCM call on a secondary engine — when we
        // destroyed those secondaries. JNI use-after-free on the
        // native tts pointer.
        //
        // Stopping first ensures the old producer pool's
        // awaitTermination (#89 in EngineStreamingSource.close)
        // blocks until in-flight JNI calls return, so subsequent
        // destroy() calls run on idle instances. Primary singleton
        // is still protected by engineMutex (the in-loadModel
        // destroy + reload path), so this only matters for the
        // secondary instances.
        stopPlaybackPipeline()

        // Issue #569 — skip the (expensive) loadModel call when the
        // engine is already loaded with the exact configuration this
        // chapter needs. Measured 1.2 s saved per chapter open on Z
        // Flip3 (Tier 3 Piper ONNX init). The compound cache key is
        // (voiceId, engineType, parallel-state). Mid-listen voice
        // changes invalidate via [observeActiveVoice] which clears
        // [loadedVoiceId], so a same-voice re-open of a different
        // chapter takes the short path here without bypassing the
        // legitimate reload triggers.
        val pendingParallelState = parallelSynthConfig.currentParallelSynthState()
        val canSkipLoad = loadedVoiceId == active.id &&
            activeEngineType == active.engineType &&
            loadedParallelInstances == pendingParallelState.instances &&
            loadedThreadsPerInstance == pendingParallelState.threadsPerInstance &&
            // Azure never carries a JNI model so the cache contract
            // is trivially satisfied; the credentials check still
            // belongs in the load path below.
            active.engineType !is EngineType.Azure
        if (canSkipLoad) {
            android.util.Log.i(
                "EnginePlayer",
                "#569 loadAndPlay: skipping loadModel — engine already loaded with " +
                    "voice=${active.id} engineType=${active.engineType} " +
                    "instances=${pendingParallelState.instances} " +
                    "threads=${pendingParallelState.threadsPerInstance}",
            )
            // Skip straight to pipeline construction; reused engines
            // are still warm in the singleton + secondaries lists.
            voiceReloadPending = false
            _observableState.update { it.copy(voiceId = active.id, error = null) }
            startPlaybackPipeline()
            invalidateState()
            return
        }

        val loadResult: String = withContext(Dispatchers.IO) {
            // Critical: serialize loadModel against in-flight generateAudioPCM
            // by holding engineMutex (issue #11). Without it, a Piper-to-Piper
            // swap can call loadModel().destroy() and free the native `tts`
            // pointer while the prior generator's JNI generate(...) is still
            // dereferencing it on another thread → SIGSEGV. The producer
            // coroutine takes engineMutex around every generateAudioPCM, so
            // withLock here waits for the in-flight call to finish before
            // we tear the model down.
            engineMutex.withLock {
                when (active.engineType) {
                    EngineType.Piper -> {
                        // Voice swap AWAY from Kokoro/Kitten → free
                        // their secondaries. Tier 3 (#88) honors the
                        // slider for all in-process engine families
                        // now; secondaries are owned per-engine-type
                        // and torn down on type-change.
                        secondaryKokoroEngines.forEach {
                            runCatching { it.destroy() }
                        }
                        secondaryKokoroEngines = emptyList()
                        // Issue #119 — Kitten secondaries.
                        secondaryKittenEngines.forEach {
                            runCatching { it.destroy() }
                        }
                        secondaryKittenEngines = emptyList()

                        val voiceDir = voiceManager.voiceDirFor(active.id)
                        val onnx = File(voiceDir, "model.onnx").absolutePath
                        val tokens = File(voiceDir, "tokens.txt").absolutePath
                        val parallelState = parallelSynthConfig.currentParallelSynthState()
                        val nt = parallelState.threadsPerInstance
                        // PR-Tier3-Diag — log slider snapshot at pipeline-
                        // construction time so we can confirm the read is
                        // actually returning the user's value (vs. a stale
                        // 1). If you see "Tier 3 init Piper instances=N
                        // threadsPerInstance=M" in logcat with N>=2, the
                        // loop below WILL execute; if you also see no
                        // "Tier 3 secondary K loaded" lines, the loadModel
                        // is returning a non-Success status silently.
                        android.util.Log.i(
                            "EnginePlayer",
                            "Tier 3 init Piper instances=${parallelState.instances} " +
                                "threadsPerInstance=$nt onnx=${onnx.takeLast(60)}",
                        )
                        val primaryResult = VoiceEngine.getInstance()
                            .loadModel(context, onnx, tokens, nt)
                        android.util.Log.i(
                            "EnginePlayer",
                            "Tier 3 primary Piper load: result=$primaryResult",
                        )
                        // Tier 3 (#88) — slider replaces the boolean
                        // toggle. Construct (instances-1) secondaries
                        // when instances >= 2. Each gets its own
                        // OrtSession via VoxSherpa v2.7.8+'s public
                        // constructor → calls run truly in parallel.
                        // Tear down any previously-allocated set first
                        // (voice swap re-runs this path).
                        secondaryPiperEngines.forEach { runCatching { it.destroy() } }
                        secondaryPiperEngines = emptyList()
                        val secondaries = mutableListOf<com.CodeBySonu.VoxSherpa.VoiceEngine>()
                        for (i in 1 until parallelState.instances) {
                            android.util.Log.i(
                                "EnginePlayer",
                                "Tier 3 attempting secondary Piper $i",
                            )
                            val secondary = com.CodeBySonu.VoxSherpa.VoiceEngine()
                            // Propagate noiseScale settings so all
                            // instances render with the same prosody.
                            // Without this, secondaries use default
                            // 0.35/0.667 (Steady) while primary uses
                            // whatever cachedVoiceSteady dictates,
                            // causing audible mismatch between
                            // sentences depending on which instance
                            // rendered them.
                            val ns = if (cachedVoiceSteady) NOISE_SCALE_STEADY
                                     else NOISE_SCALE_EXPRESSIVE
                            val nsW = if (cachedVoiceSteady) NOISE_SCALE_W_STEADY
                                      else NOISE_SCALE_W_EXPRESSIVE
                            secondary.setNoiseScale(ns)
                            secondary.setNoiseScaleW(nsW)
                            val r = secondary.loadModel(context, onnx, tokens, nt)
                            if (r == "Success") {
                                secondaries += secondary
                                android.util.Log.i(
                                    "EnginePlayer",
                                    "Tier 3 secondary Piper $i loaded ok",
                                )
                            } else {
                                runCatching { secondary.destroy() }
                                android.util.Log.w(
                                    "EnginePlayer",
                                    "Tier 3 secondary $i (Piper) load failed: " +
                                        "$r — capping at ${secondaries.size + 1} instances.",
                                )
                                break
                            }
                        }
                        secondaryPiperEngines = secondaries
                        primaryResult ?: "Error: load returned null"
                    }
                    is EngineType.Kokoro -> {
                        // Voice swap AWAY from Piper/Kitten → free their
                        // secondaries. Tier 3 secondaries are
                        // engine-type-specific.
                        secondaryPiperEngines.forEach {
                            runCatching { it.destroy() }
                        }
                        secondaryPiperEngines = emptyList()
                        // Issue #119 — Kitten secondaries.
                        secondaryKittenEngines.forEach {
                            runCatching { it.destroy() }
                        }
                        secondaryKittenEngines = emptyList()
                        // All 53 Kokoro speakers share a single ~325MB fp32
                        // multi-speaker model. Switching speakers reuses the
                        // loaded engine; first load takes 30+s as sherpa-onnx
                        // builds the onnxruntime session and runs a warm-up
                        // generate.
                        val sharedDir = voiceManager.kokoroSharedDir()
                        val onnx = File(sharedDir, "model.onnx").absolutePath
                        val tokens = File(sharedDir, "tokens.txt").absolutePath
                        val voicesBin = File(sharedDir, "voices.bin").absolutePath
                        KokoroEngine.getInstance().setActiveSpeakerId(
                            (active.engineType as EngineType.Kokoro).speakerId,
                        )
                        // #196 — drive Kokoro's within-sentence comma
                        // pause from the same punctuation-cadence
                        // multiplier we use for between-sentence
                        // silence. 0.2f baseline = engine default; the
                        // multiplier scales it linearly so a 0× user
                        // collapses commas, a 2× user stretches them
                        // to ~0.4f. Field on the engine is read at
                        // config-build time inside loadModel, so set
                        // before loadModel — not after.
                        KokoroEngine.getInstance().setSilenceScale(
                            KOKORO_SILENCE_SCALE_BASELINE * currentPunctuationPauseMultiplier,
                        )
                        val parallelState = parallelSynthConfig.currentParallelSynthState()
                        val nt = parallelState.threadsPerInstance
                        val primaryResult = KokoroEngine.getInstance()
                            .loadModel(context, onnx, tokens, voicesBin, nt)
                        // Tier 3 (#88) — Kokoro N-instance support.
                        // Each loaded Kokoro session is ~325 MB; on
                        // an 8 GB device 8 instances ≈ 2.6 GB which
                        // fits but is heavy. Construct sequentially —
                        // Kokoro's first-load takes ~30 s, so 8
                        // instances ≈ 4 min first-launch penalty
                        // (acceptable for an explicit opt-in;
                        // subsequent chapters reuse loaded instances).
                        secondaryKokoroEngines.forEach { runCatching { it.destroy() } }
                        secondaryKokoroEngines = emptyList()
                        val kokoroSecondaries = mutableListOf<com.CodeBySonu.VoxSherpa.KokoroEngine>()
                        for (i in 1 until parallelState.instances) {
                            val secondary = com.CodeBySonu.VoxSherpa.KokoroEngine()
                            secondary.setActiveSpeakerId(
                                (active.engineType as EngineType.Kokoro).speakerId,
                            )
                            secondary.setSilenceScale(
                                KOKORO_SILENCE_SCALE_BASELINE *
                                    currentPunctuationPauseMultiplier,
                            )
                            val r = secondary.loadModel(context, onnx, tokens, voicesBin, nt)
                            if (r == "Success") {
                                kokoroSecondaries += secondary
                            } else {
                                runCatching { secondary.destroy() }
                                android.util.Log.w(
                                    "EnginePlayer",
                                    "Tier 3 secondary $i (Kokoro) load failed: " +
                                        "$r — capping at ${kokoroSecondaries.size + 1} instances.",
                                )
                                break
                            }
                        }
                        secondaryKokoroEngines = kokoroSecondaries
                        primaryResult ?: "Error: load returned null"
                    }
                    is EngineType.Kitten -> {
                        // Issue #119 — Kitten parallels Kokoro: all 8
                        // Kitten speakers share a single ~25 MB fp16 ONNX
                        // multi-speaker model. Switching speakers reuses
                        // the loaded engine via setActiveSpeakerId; first
                        // load is fast (~2–4 s) because the model is tiny.
                        secondaryPiperEngines.forEach {
                            runCatching { it.destroy() }
                        }
                        secondaryPiperEngines = emptyList()
                        secondaryKokoroEngines.forEach {
                            runCatching { it.destroy() }
                        }
                        secondaryKokoroEngines = emptyList()
                        val sharedDir = voiceManager.kittenSharedDir()
                        val onnx = File(sharedDir, "model.onnx").absolutePath
                        val tokens = File(sharedDir, "tokens.txt").absolutePath
                        val voicesBin = File(sharedDir, "voices.bin").absolutePath
                        KittenEngine.getInstance().setActiveSpeakerId(
                            (active.engineType as EngineType.Kitten).speakerId,
                        )
                        val parallelState = parallelSynthConfig.currentParallelSynthState()
                        val nt = parallelState.threadsPerInstance
                        val primaryResult = KittenEngine.getInstance()
                            .loadModel(context, onnx, tokens, voicesBin, nt)
                        // Tier 3 (#88) — Kitten N-instance support.
                        // Each loaded Kitten session is small (~60–80 MB
                        // resident on the fp16 nano model), so even an
                        // 8-way fan-out fits in 1 GB. Kitten is the
                        // friendliest engine for the parallel slider on
                        // low-end hardware.
                        secondaryKittenEngines.forEach { runCatching { it.destroy() } }
                        secondaryKittenEngines = emptyList()
                        val kittenSecondaries = mutableListOf<com.CodeBySonu.VoxSherpa.KittenEngine>()
                        for (i in 1 until parallelState.instances) {
                            val secondary = com.CodeBySonu.VoxSherpa.KittenEngine()
                            secondary.setActiveSpeakerId(
                                (active.engineType as EngineType.Kitten).speakerId,
                            )
                            val r = secondary.loadModel(context, onnx, tokens, voicesBin, nt)
                            if (r == "Success") {
                                kittenSecondaries += secondary
                            } else {
                                runCatching { secondary.destroy() }
                                android.util.Log.w(
                                    "EnginePlayer",
                                    "Tier 3 secondary $i (Kitten) load failed: " +
                                        "$r — capping at ${kittenSecondaries.size + 1} instances.",
                                )
                                break
                            }
                        }
                        secondaryKittenEngines = kittenSecondaries
                        primaryResult ?: "Error: load returned null"
                    }
                    is EngineType.Azure -> {
                        // Tier 3 (#88) — voice swap AWAY from local
                        // engines: free all local secondaries (Piper,
                        // Kokoro, Kitten) to recover memory.
                        secondaryPiperEngines.forEach { runCatching { it.destroy() } }
                        secondaryPiperEngines = emptyList()
                        secondaryKokoroEngines.forEach { runCatching { it.destroy() } }
                        secondaryKokoroEngines = emptyList()
                        // Issue #119 — Kitten secondaries.
                        secondaryKittenEngines.forEach { runCatching { it.destroy() } }
                        secondaryKittenEngines = emptyList()
                        // PR-4 (#183) — cloud voice activation. Nothing
                        // to load JNI-side; the "model" is the remote
                        // synthesis endpoint. Credentials gate is the
                        // only check we run here. The voices/list
                        // verify ping that PR-3's Test-connection
                        // button uses is *not* run here — synthesis
                        // itself will surface 401 on a bad key, and
                        // we don't want to add an extra HTTP round
                        // trip to every chapter start. PR-5's error
                        // handling pass elevates synth failures to
                        // typed PlaybackState errors.
                        if (!azureCredentials.isConfigured) {
                            "Error: Azure key not configured. " +
                                "Open Settings → Cloud voices to paste a key."
                        } else {
                            "Success"
                        }
                    }
                }
            }
        }
        if (loadResult != "Success") {
            _observableState.update {
                it.copy(
                    isPlaying = false,
                    error = PlaybackError.ChapterFetchFailed("Voice load failed: $loadResult"),
                )
            }
            invalidateState()
            return
        }
        activeEngineType = active.engineType
        loadedVoiceId = active.id
        voiceReloadPending = false
        // Issue #582 — populate the @Volatile sample-rate cache now that
        // loadModel has returned (the engine's intrinsic monitor is no
        // longer held by the loader). Any UI-thread read of sampleRate
        // from here on (e.g. a speed-chip cycle that re-enters
        // startPlaybackPipeline) hits the volatile field instead of
        // contending on the engine lock — that 2.054 s `Long monitor
        // contention` the stress test captured can't fire from this
        // path anymore.
        EngineSampleRateCache.refreshFromEngine()
        // Issue #569 — record the parallel state we just loaded for so
        // the next loadAndPlay can skip the loadModel call when nothing
        // changed. Pin both `instances` and `threadsPerInstance` —
        // either change requires rebuilding secondaries.
        loadedParallelInstances = pendingParallelState.instances
        loadedThreadsPerInstance = pendingParallelState.threadsPerInstance
        // Issue #561 (stuck-state-fixer) — surface the active voice id
        // into PlaybackState so the debug overlay's "name" / "voice"
        // rows + the snapshot's tier descriptor have the right input.
        // Pre-fix `loadedVoiceId` was an internal field only and
        // RealDebugRepositoryUi read `PlaybackState.voiceId` which never
        // got set, so the overlay rendered "—" while the engine was
        // actively rendering Piper Lessac. The audit captured this
        // exact symptom on R5CRB0W66MK.
        _observableState.update { it.copy(voiceId = active.id) }

        // (state was already pushed above so the spinner could show during
        // model load — refresh durationEstimate now that the active engine
        // type is known but otherwise leave state alone.)
        _observableState.update {
            it.copy(
                error = null,
            )
        }
        // Issue #558 — pre-fetch the next N chapter BODIES (NOT PCM)
        // as soon as this chapter is loaded, so that auto-advance to
        // the next chapter finds the body already in Room rather than
        // having to cold-fetch over the network inside its 60s wait.
        //
        // Pre-fix, library-add's PCM-render scheduling implicitly drove
        // chapter body downloads (the render worker retries until the
        // body lands), but for SUBSCRIBE-mode Notion fictions there's
        // no eager-body trigger past chapter 1 — bodies arrived
        // exactly when `advanceChapter` queued them at the boundary,
        // which on a slow Notion API call (5-10s per page) blew past
        // the 30s timeout (now 60s) for chapter 5+. The phone audit
        // captured the 30s stall verbatim:
        //
        //   18:42:35.558 handleChapterDone (ch4)
        //   ...silence...
        //   18:43:05.558 advanceChapter: next chapter ... not ready
        //                within 30000ms; clearing buffering + surfacing error
        //
        // Wrapped in runCatching so a chapter-graph hiccup doesn't
        // block playback — the prefetch is best-effort housekeeping.
        runCatching { prerenderTriggers.onChapterOpened(fictionId, chapterId) }
            .onFailure {
                android.util.Log.w(
                    "EnginePlayer",
                    "loadAndPlay: onChapterOpened body prefetch failed " +
                        "(${it.javaClass.simpleName}: ${it.message}) — continuing",
                )
            }
        startPlaybackPipeline()
        invalidateState()
    }

    /**
     * Spin up the producer/consumer pair. Mirrors VoxSherpa standalone's
     * playback shape exactly because deviating from it has measurably
     * fuzzy output on Tab A7 Lite (issue #6):
     *
     *  - **Consumer = pinned [Thread]**, not a coroutine. `Process.set-`
     *    `ThreadPriority(URGENT_AUDIO)` is per-OS-thread; coroutines on
     *    [Dispatchers.IO] migrate threads on every suspend, so any priority
     *    bump leaks across resumptions. A dedicated thread keeps URGENT_AUDIO
     *    for the entire pipeline lifetime.
     *  - **Queue = [LinkedBlockingQueue]**, not [kotlinx.coroutines.channels.Channel].
     *    `take()` blocks the OS thread directly, no coroutine state-machine
     *    overhead, no risk of dispatcher work-stealing introducing scheduling
     *    jitter between sentences.
     *  - **Buffer = [AudioTrack.getMinBufferSize]** (set in [createAudioTrack]).
     *    Larger buffers route through AudioFlinger's deep-buffer mixer, which
     *    on Samsung tablets uses a different sample-rate-conversion path than
     *    the fast-track mixer used for `minBufferSize` tracks. Empirically the
     *    deep-buffer path adds the residual fuzz that survives the legacy
     *    `STREAM_MUSIC` constructor swap.
     *  - **[engineMutex] around `generateAudioPCM`** so a stop-then-start
     *    sequence (slider drag, voice swap, seek) never has two threads
     *    inside the singleton VoxSherpa engine at once.
     *
     * Pre-fetching is implicit via the queue's capacity — generation runs
     * ahead of playback by however many slots are free.
     */
    private fun startPlaybackPipeline() {
        // Make sure any previous run is fully stopped.
        stopPlaybackPipeline()

        // Issue #560 — claim audio focus BEFORE creating the AudioTrack.
        // Pre-fix the AudioTrack was created and `write()`-ed against
        // with no focus held; on Samsung Z Flip3 the framework silently
        // refused to drain the track and the audit captured the
        // resulting "PLAYING + no audio" stuck state. AudioManager's
        // AUDIOFOCUS_REQUEST_GRANTED is the green light to actually
        // emit PCM. On denial we still build the pipeline (so the UI
        // state stays consistent) but the user will hear nothing — the
        // log warning is the breadcrumb for that path. Idempotent, so
        // a chapter advance reusing the same focus is free.
        val focusGranted = runCatching { audioFocus.acquire() }.getOrDefault(false)
        if (!focusGranted) {
            android.util.Log.w(
                "EnginePlayer",
                "#560 startPlaybackPipeline: audio focus was NOT granted; pipeline will be silent " +
                    "until another app yields focus",
            )
        }

        val engineType = activeEngineType
        // Issue #582 — ANR-grade lock contention guard. The native
        // VoxSherpa engines (Piper/Kokoro/Kitten) share a single
        // intrinsic monitor between `loadModel()` and the property
        // accessors like `sampleRate`. `startPlaybackPipeline()` is
        // called from main-thread Compose handlers (e.g. speed-chip
        // cycling — #3280, #3287) so a direct `VoiceEngine.getInstance()
        // .sampleRate` read here can block the UI thread for the full
        // duration of an in-flight `loadModel` (2.05 s captured on
        // Z Flip 3). Route through the @Volatile cache instead — the
        // post-loadModel callsite below already populated it with the
        // engine's actual rate, so this is a lock-free read on every
        // pipeline rebuild after the first.
        val sampleRate = when (engineType) {
            is EngineType.Kokoro -> EngineSampleRateCache.kokoroRate()
            // Issue #119 — Kitten native sample rate is 24 kHz (same as
            // Kokoro) but the runtime accessor is the source of truth.
            is EngineType.Kitten -> EngineSampleRateCache.kittenRate()
            is EngineType.Azure -> azureVoiceEngine.sampleRate
            else -> EngineSampleRateCache.piperRate()
        }.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE
        val track = createAudioTrack(sampleRate)
        audioTrack = track

        // Snapshot the user's configured queue depth at pipeline-construction
        // time. Mid-pipeline slider movements take effect on the next
        // construction (next chapter / seek / voice swap); the bounded queue
        // can't be resized live. Issue #84 — this is the LMK probe knob.
        // Cap matches BUFFER_MAX_CHUNKS (3000). The previous 1500 cap
        // silently truncated slider values above 1500, contradicting
        // the slider's 3000-chunk max — JP set the slider to 3000 to
        // probe the gap and got 1500 in practice. Lifted to 3000 so
        // the configured value reaches the queue verbatim.
        val queueCapacity = cachedBufferChunks.coerceIn(2, 3000)
        // Issue #135: snapshot the dict at construction time. The
        // capture is by-value (the dict is an immutable data class) so
        // a mid-chapter edit doesn't mutate the active pipeline's
        // substitution table — that's intentional, swapping the
        // dictionary mid-sentence would shift the pre-rendered
        // sentence text and the cache key on the next sentence,
        // producing audible drift. Edits take effect on the next
        // pipeline rebuild (seek / chapter change / voice swap /
        // speed/pitch change), exactly like the buffer-chunks knob.
        val pronunciationDict = cachedPronunciationDict
        // Tier 3 (#88) — wrap each secondary instance in the
        // VoiceEngineHandle SAM. Piper and Kokoro hand out N-1 local
        // engine instances (memory-bounded). Azure hands out N-1
        // synthetic handles that all delegate to the same singleton
        // [AzureVoiceEngine] — Azure parallelism is HTTPS-bounded, not
        // memory-bounded, so the same engine instance can fan out N
        // concurrent requests via OkHttp's connection pool. Hides
        // per-sentence latency: while sentence N plays, sentences
        // N+1..N+secondaries are synthesizing in parallel server-side.
        val secondaryHandles: List<EngineStreamingSource.VoiceEngineHandle> = when (engineType) {
            EngineType.Piper -> secondaryPiperEngines.map { eng ->
                object : EngineStreamingSource.VoiceEngineHandle {
                    // Issue #582 — route secondary-engine sample-rate
                    // reads through the volatile cache too. Each
                    // secondary is a separate VoiceEngine instance with
                    // its own intrinsic monitor, so a per-secondary
                    // loadModel that's still in flight when this main-
                    // thread construction runs would contend on THAT
                    // instance the same way the singleton did. All
                    // Piper engines share one model file and therefore
                    // one sample rate within a process, so the
                    // engine-type-scoped cache is the correct answer.
                    override val sampleRate: Int =
                        EngineSampleRateCache.piperRate()
                            .takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE
                    override fun generateAudioPCM(
                        text: String, speed: Float, pitch: Float,
                    ): ByteArray? {
                        AndroidProcess.setThreadPriority(
                            AndroidProcess.THREAD_PRIORITY_URGENT_AUDIO,
                        )
                        return eng.generateAudioPCM(text, speed, pitch)
                    }
                }
            }
            is EngineType.Kokoro -> secondaryKokoroEngines.map { eng ->
                object : EngineStreamingSource.VoiceEngineHandle {
                    // Issue #582 — see Piper branch above. Kokoro is
                    // architecturally 24 kHz across every speaker.
                    override val sampleRate: Int =
                        EngineSampleRateCache.kokoroRate()
                            .takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE
                    override fun generateAudioPCM(
                        text: String, speed: Float, pitch: Float,
                    ): ByteArray? {
                        AndroidProcess.setThreadPriority(
                            AndroidProcess.THREAD_PRIORITY_URGENT_AUDIO,
                        )
                        return eng.generateAudioPCM(text, speed, pitch)
                    }
                }
            }
            // Issue #119 — Kitten secondaries. Same wrapping shape as
            // Kokoro because both engines are multi-speaker singletons
            // with the same `generateAudioPCM(text, speed, pitch)` Java
            // signature.
            is EngineType.Kitten -> secondaryKittenEngines.map { eng ->
                object : EngineStreamingSource.VoiceEngineHandle {
                    // Issue #582 — see Piper branch above. Kitten is
                    // architecturally 24 kHz across every speaker.
                    override val sampleRate: Int =
                        EngineSampleRateCache.kittenRate()
                            .takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE
                    override fun generateAudioPCM(
                        text: String, speed: Float, pitch: Float,
                    ): ByteArray? {
                        AndroidProcess.setThreadPriority(
                            AndroidProcess.THREAD_PRIORITY_URGENT_AUDIO,
                        )
                        return eng.generateAudioPCM(text, speed, pitch)
                    }
                }
            }
            is EngineType.Azure -> {
                // Reuse the parallelSynthInstances knob as Azure
                // lookahead depth — local engines and Azure both gain
                // from the same "fan out N concurrent producers"
                // pattern, even though their costs differ (Azure is
                // HTTPS-bounded, not memory-bounded). A future PR
                // could split the slider if the tradeoffs diverge
                // visibly.
                val lookaheadCount = (cachedParallelSynthInstances - 1).coerceAtLeast(0)
                val voiceName = engineType.voiceName
                List(lookaheadCount) {
                    object : EngineStreamingSource.VoiceEngineHandle {
                        override val sampleRate: Int =
                            azureVoiceEngine.sampleRate.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE
                        override fun generateAudioPCM(
                            text: String, speed: Float, pitch: Float,
                        ): ByteArray? {
                            AndroidProcess.setThreadPriority(
                                AndroidProcess.THREAD_PRIORITY_URGENT_AUDIO,
                            )
                            return azureVoiceEngine.synthesize(text, voiceName, speed, pitch)
                        }
                    }
                }
            }
            else -> emptyList()
        }
        // PR-D (#86) — build the cache key for this (chapter, voice,
        // speed, pitch, dict) tuple. All five pieces of identity must
        // be known; if any is null we skip the cache write entirely
        // (the source gets cacheAppender = null and behaves as
        // pre-PR-D). Captured here at pipeline-construction time so a
        // mid-pipeline state mutation can't shift the key out from
        // under the live appender.
        val chapterIdForCache = _observableState.value.currentChapterId
        val voiceIdForCache = loadedVoiceId
        val cacheKey: PcmCacheKey? = if (
            chapterIdForCache != null && voiceIdForCache != null
        ) {
            PcmCacheKey(
                chapterId = chapterIdForCache,
                voiceId = voiceIdForCache,
                speedHundredths = PcmCacheKey.quantize(currentSpeed),
                pitchHundredths = PcmCacheKey.quantize(currentPitch),
                chunkerVersion = CHUNKER_VERSION,
                pronunciationDictHash = pronunciationDict.contentHash,
            )
        } else null

        // PR-E (#86) — cache-hit dispatch. If the cache for this key
        // is COMPLETE (the .idx.json sidecar landed from a previous
        // play's natural end), open a CacheFileSource and skip the
        // engine pipeline entirely. The consumer thread treats the
        // source uniformly via the PcmSource interface; cached
        // chapters get instant first-byte + zero inter-sentence gaps.
        //
        // Touch the .pcm mtime so LRU eviction in PcmCache.evictTo
        // prefers genuinely-cold entries (sort key: mtime ascending
        // within (isAzure) groups).
        //
        // On CacheFileSource.open failure (corrupt index, truncated
        // pcm) we fall through to the streaming path. The next
        // natural-end finalize will overwrite the bad entry with
        // fresh bytes; a corrupt cache is a re-render trigger, not
        // a crash. We log the failure so the cache-hit/miss ratio
        // observability stays meaningful in logcat — adb run-as is
        // gone post-isDebuggable=false, so logcat is the primary
        // verification surface for cache behavior on the tablet.
        val cacheHitSource: PcmSource? = cacheKey?.let { key ->
            runBlocking {
                if (!pcmCache.isComplete(key)) return@runBlocking null
                pcmCache.touch(key)
                runCatching {
                    CacheFileSource.open(
                        pcmFile = pcmCache.pcmFileFor(key),
                        indexFile = pcmCache.indexFileFor(key),
                        startSentenceIndex = currentSentenceIndex,
                    )
                }.onSuccess {
                    android.util.Log.i(
                        "EnginePlayer",
                        "pcm-cache HIT chapter=${key.chapterId} voice=${key.voiceId} " +
                            "speed=${key.speedHundredths} pitch=${key.pitchHundredths} " +
                            "fromSentence=$currentSentenceIndex base=${key.fileBaseName().take(12)}",
                    )
                }.onFailure { t ->
                    android.util.Log.w(
                        "EnginePlayer",
                        "pcm-cache hit-open FAILED chapter=${key.chapterId} " +
                            "base=${key.fileBaseName().take(12)} — falling back to streaming",
                        t,
                    )
                }.getOrNull()
            }
        }

        val source: PcmSource = if (cacheHitSource != null) {
            cacheHitSource
        } else {
            // Cache miss (or hit-open failed) — streaming source +
            // tee appender path (PR-D). If a partial entry exists
            // from a prior killed render (meta.json on disk,
            // idx.json absent), wipe it first; PR-D's resume policy
            // is "abandon, restart".
            //
            // runBlocking is acceptable here: startPlaybackPipeline
            // is already called synchronously from a coroutine
            // (or from suspend functions like loadAndPlay), so the
            // blocking wait for pcmCache.delete is brief (a few
            // File.delete syscalls on Dispatchers.IO — single-digit
            // ms).
            val appender: PcmAppender? = cacheKey?.let { key ->
                runBlocking {
                    if (pcmCache.metaFileFor(key).exists() && !pcmCache.isComplete(key)) {
                        // Stale partial — wipe before opening fresh.
                        pcmCache.delete(key)
                    }
                    pcmCache.appender(key, sampleRate = sampleRate)
                }
            }
            cacheKey?.let { key ->
                android.util.Log.i(
                    "EnginePlayer",
                    "pcm-cache MISS chapter=${key.chapterId} voice=${key.voiceId} " +
                        "speed=${key.speedHundredths} pitch=${key.pitchHundredths} " +
                        "fromSentence=$currentSentenceIndex base=${key.fileBaseName().take(12)}",
                )
            }
            EngineStreamingSource(
                sentences = sentences,
                startSentenceIndex = currentSentenceIndex,
                engine = activeVoiceEngineHandle(engineType),
                speed = currentSpeed,
                pitch = currentPitch,
                engineMutex = engineMutex,
                cacheAppender = appender,
                punctuationPauseMultiplier = currentPunctuationPauseMultiplier,
                // #486 / #488 — TalkBack inter-sentence pad. Snapshot
                // at pipeline-construction time; mid-listen slider
                // edits take effect on next rebuild (matches the
                // other live-config knobs). The volatile cache is
                // kept in sync by [observeA11yPacing].
                extraA11ySilenceMs = cachedA11yExtraSilenceMs,
                queueCapacity = queueCapacity,
                pronunciationDictApply = pronunciationDict::apply,
                secondaryEngines = secondaryHandles,
            )
        }
        pcmSource = source
        pipelineRunning.set(true)
        // Issue #540 — every fresh pipeline starts unpaused. Tear-down
        // paths (stopPlaybackPipeline) also clear userPaused so a
        // pipeline rebuild after a hard stop (voice swap, seek, etc.)
        // doesn't inherit a stale pause flag.
        userPaused.set(false)
        // Issue #539 — snapshot the AudioTrack's sample rate + chapter
        // base offset so currentPositionMs can compute "frames the
        // listener has heard" without holding the track reference. The
        // base offset is the charOffset at the moment this pipeline
        // started; frames-played-since-start is added on top.
        pipelineSampleRate = sampleRate
        // Issue #555 — prefer the speed-handoff char-offset if setSpeed
        // captured one (truthful audible position at the moment of the
        // speed tap). Falls back to state.charOffset for all other
        // rebuild paths (seek, voice swap, chapter advance) where the
        // value is set inline by the caller and is therefore current.
        pipelineStartCharOffset = speedHandoffCharOffset
            ?: _observableState.value.charOffset
        speedHandoffCharOffset = null
        // Issue #555 — snapshot the speed too. PCM in the AudioTrack was
        // synthesized at this speed; [currentPositionMs] uses it to
        // convert wall-clock-frames-played into media-time. setSpeed
        // tears down + rebuilds the pipeline, so this is constant
        // within a pipeline lifetime even when the user pumps the
        // speed-chips.
        pipelineStartSpeed = currentSpeed
        // Issue #539 — reset the truthful-position monotonic latch on
        // every pipeline rebuild. A seek-backward path stops + starts
        // the pipeline; without this reset the latch would clamp the
        // scrubber at the pre-seek position and the user would see the
        // thumb refuse to move backward on a backward seek.
        lastTruthfulPositionMs = 0L
        // Issue #554 — clear the pause-pin on every fresh pipeline. A
        // pipeline restart unambiguously means we're moving (resume,
        // seek, speed change, chapter advance), so a stale pin from a
        // previous pause shouldn't gag the live position feed.
        pinnedPausePositionMs = null
        // Issue #290 — reset the frames-written tally so the debug
        // overlay's `audio buffered ms` reads against the fresh
        // AudioTrack's playbackHeadPosition rather than a stale total
        // from the previous pipeline.
        totalFramesWritten = 0L
        // Clear the prev-chunk-end anchor so the first chunk of this
        // pipeline lifetime doesn't get a "gap" attributed to user
        // pause time, seek time, or model load time.
        chunkGapLogger.resetForNewPipeline()

        consumerThread = Thread({
            AndroidProcess.setThreadPriority(AndroidProcess.THREAD_PRIORITY_URGENT_AUDIO)
            var naturalEnd = false
            var firstSentence = true
            // Track AudioTrack pause state so the buffer-low check below can
            // toggle play/pause without thrashing JNI on every iteration.
            // Consumer is the only thread that calls track.play / track.pause
            // for streaming-source playback (user-initiated pauses go through
            // stopPlaybackPipeline, which tears the track down).
            var paused = false
            // Live track volume mirror. Scoped to the consumer thread (not
            // per-sentence) so the per-write change-detection skips the
            // setVolume JNI call when the ramp is idle, which is the steady
            // state. Seeded in the firstSentence block below.
            var lastVol = -1f
            try {
                while (pipelineRunning.get()) {
                    // Issue #540 — fast-pause park. When the user taps
                    // Pause, [pauseTts] sets userPaused=true and pauses
                    // the AudioTrack. The consumer parks here until
                    // either userPaused clears (resume tap) or the
                    // pipeline shuts down (voice swap / seek / etc.).
                    //
                    // We sleep in short 50 ms increments rather than
                    // parking on a Condition so a teardown can interrupt
                    // us by setting pipelineRunning=false; the inner
                    // check exits the park promptly. The sleep is also
                    // why the user-perceived resume latency is ≤50 ms +
                    // hardware ring buffer drain.
                    while (userPaused.get() && pipelineRunning.get()) {
                        try {
                            Thread.sleep(50L)
                        } catch (_: InterruptedException) {
                            // stopPlaybackPipeline raised the
                            // interrupt; the outer while will exit.
                            Thread.currentThread().interrupt()
                            break
                        }
                    }
                    if (!pipelineRunning.get()) break

                    // BEFORE pulling the next chunk, check whether buffer
                    // recovered above the resume threshold. If so, kick
                    // AudioTrack alive again so the next write lands in a
                    // playing track rather than a paused-then-played one.
                    //
                    // Issue #98 — Mode B gate. With Catch-up Pause off, this
                    // branch never fires (paused is never true; see the
                    // matching gate on the underrun pause branch below). The
                    // `paused &&` short-circuit is the same fast-path either
                    // way; the cached read is just for symmetry with the
                    // pause branch.
                    if (cachedCatchupPause &&
                        !source.isStreaming &&
                        paused &&
                        source.bufferHeadroomMs.value >= BUFFER_RESUME_THRESHOLD_MS) {
                        runCatching { track.play() }
                        paused = false
                        scope.launch {
                            _observableState.update { it.copy(isBuffering = false) }
                        }
                    }

                    // runBlocking on the dedicated audio thread — same shape
                    // as the prior runInterruptible bridge. The thread stays
                    // pinned at URGENT_AUDIO; nextChunk's runInterruptible
                    // dispatches the take() to Dispatchers.IO under the hood,
                    // but because the consumer thread is the only thing
                    // waiting for the result, runBlocking just parks it
                    // exactly as queue.take() did pre-PR-A.
                    val chunk = try {
                        runBlocking { source.nextChunk() }
                    } catch (t: Throwable) {
                        // Issue #588 — pre-fix this was a silent
                        // `catch (_: Throwable)`. Log the exception so a
                        // future producer-side failure (the one that
                        // caused the v0.5.60 regression in the first
                        // place) leaves a breadcrumb instead of an
                        // unobservable "chapter just sits there"
                        // symptom. Wrapped in runCatching so a logger
                        // fault can't itself crash the consumer thread.
                        runCatching {
                            android.util.Log.w(
                                "EnginePlayer",
                                "#588 consumer-thread: nextChunk threw " +
                                    "(${t.javaClass.simpleName}: ${t.message}) — treating as null",
                                t,
                            )
                        }
                        null
                    }
                    if (chunk == null) {
                        // null = source exhausted (chapter end) OR closed
                        // by stopPlaybackPipeline. Distinguish via the
                        // source's authoritative
                        // [PcmSource.producedAllSentences] flag — the
                        // producer (EngineStreamingSource) sets it true
                        // RIGHT BEFORE pushing END_PILL on natural end;
                        // close() / cancellation paths never set it.
                        // The cache source sets it true when its cursor
                        // walks past the last sentence (also right
                        // before returning null).
                        //
                        // #573 — Pre-fix this line read
                        // `pipelineRunning.get()` instead, which was
                        // racy: stopPlaybackPipeline could flip the
                        // flag between this read and the post-finally
                        // gate at the bottom of the thread, silently
                        // skipping handleChapterDone. The
                        // producer-set flag is monotonic across that
                        // window — once true, always true for the
                        // source's lifetime.
                        naturalEnd = source.producedAllSentences
                        android.util.Log.i(
                            "EnginePlayer",
                            "end-of-pcm: naturalEnd=$naturalEnd " +
                                "producedAll=${source.producedAllSentences} " +
                                "pipelineRunning=${pipelineRunning.get()} " +
                                "userPaused=${userPaused.get()}",
                        )
                        break
                    }

                    // Surface the new sentence range BEFORE writing. Fire-
                    // and-forget on Main — withContext would force this
                    // thread to coordinate with the coroutine dispatcher,
                    // which is the whole reason we left coroutines.
                    scope.launch {
                        currentSentenceIndex = chunk.sentenceIndex
                        _observableState.update {
                            it.copy(
                                currentSentenceRange = chunk.range,
                                charOffset = chunk.range.startCharInChapter,
                            )
                        }
                    }

                    if (firstSentence) {
                        val v = volumeRamp.current
                        runCatching { track.setVolume(v) }
                        lastVol = v
                        runCatching { track.play() }
                        firstSentence = false
                    }

                    // After the take, headroom dropped by this chunk's audio
                    // duration. If we just crossed below the underrun
                    // threshold AND we're not already paused, pause the
                    // AudioTrack and surface the buffering UI BEFORE we
                    // start writing — the OS hardware buffer is large
                    // enough on this device (~2-3 s deep) that the next
                    // write would land seconds before the listener hears
                    // silence, so the buffering UI needs to lead the audio
                    // by that margin.
                    //
                    // Issue #98 — Mode B gate. With Catch-up Pause off, the
                    // consumer drains through underruns without pausing:
                    // listener may hear dead air, but never sees the
                    // "Buffering…" UI. EngineStreamingSource is untouched.
                    //
                    // Issue #642 — also bail out of the underrun pause when
                    // the producer has already emitted every sentence
                    // (END_PILL is in the queue). Pre-fix the gate fired
                    // even on the chapter's LAST real chunk: producer
                    // signalled `producedAllSentences=true` then pushed
                    // END_PILL (queue depth = 1, audio depth = 0), the
                    // consumer dequeued the last chunk, then the underrun
                    // check saw headroom == 0 and pausing-then-parked on
                    // [bufferHeadroomMs.first]. The Flow re-emits only when
                    // the producer puts a new chunk; with the producer
                    // already done, no more emissions ever land, and the
                    // park inside [bufferHeadroomMs.first] is stuck until
                    // the OUTER stopPlaybackPipeline interrupts the
                    // consumer thread — which on auto-advance only fires
                    // when the watchdog finally trips at the 12 s
                    // end-of-chapter threshold. Net symptom: chapter N's
                    // last sentence is never written to AudioTrack, the
                    // listener never hears it, and chapter N+1's pipeline
                    // doesn't start until the watchdog kicks. Reproduced
                    // on R5CRB0W66MK Z Flip 3 in v0.5.63, captured in the
                    // #642 logcat. Adding `!producedAllSentences` to the
                    // gate keeps every legitimate intra-chapter underrun
                    // pause intact (producedAll flips true ONLY at the
                    // very end of the producer) while skipping the
                    // doomed park on the final chunk.
                    if (cachedCatchupPause &&
                        !source.isStreaming &&
                        !paused &&
                        !source.producedAllSentences &&
                        source.bufferHeadroomMs.value < BUFFER_UNDERRUN_THRESHOLD_MS) {
                        runCatching { track.pause() }
                        paused = true
                        scope.launch {
                            _observableState.update { it.copy(isBuffering = true) }
                        }
                    }

                    // While paused, DO NOT write into the AudioTrack —
                    // a paused MODE_STREAM track does not drain its ring
                    // buffer, so AudioTrack.write() returns 0 once the
                    // buffer is full and the inner write loop spins at
                    // URGENT_AUDIO priority forever (regression introduced
                    // in PR #77, only surfaces with sub-realtime voices
                    // like Piper-high on slow CPUs where headroom drops
                    // below the underrun threshold). Park the consumer on
                    // the headroom flow until the producer queues enough
                    // audio to cross the resume threshold, then resume
                    // the track and proceed with the write.
                    //
                    // Issue #642 — extra exit predicate: also resume when
                    // the producer has flipped [producedAllSentences]
                    // true. The [bufferHeadroomMs] flow re-evaluates the
                    // predicate ONLY on a new emission, and the producer
                    // doesn't emit again after END_PILL (END_PILL contains
                    // 0 PCM bytes and goes through queue.put, not the
                    // headroom flow). Without this extra predicate the
                    // park sat forever even when the producer was done —
                    // see the long comment on the underrun gate above
                    // for the full repro trace. The new gate is
                    // belt-and-suspenders: the underrun gate's
                    // !producedAllSentences clause now blocks entry to
                    // this branch on the last chunk, so in practice we
                    // never reach the pause-park with the producer
                    // already done. The defensive predicate guards the
                    // race where producedAllSentences flips true AFTER
                    // we entered the park (paused=true via a legitimate
                    // mid-chapter underrun, producer races to finish
                    // before listener catches back up). To make
                    // `first { }` re-evaluate when the flag flips, we
                    // call out to a helper that combines the headroom
                    // flow with a producedAllSentences poll bridge —
                    // simpler than wiring a SharedFlow on the source
                    // and keeps the surface area for #642 minimal.
                    if (paused) {
                        try {
                            runBlocking {
                                waitForResumeOrTerminalState(source)
                            }
                        } catch (_: Throwable) {
                            // Interrupted by stopPlaybackPipeline (interrupt +
                            // close). The pipelineRunning check below will
                            // skip the resume; the outer while will exit.
                        }
                        if (pipelineRunning.get()) {
                            runCatching { track.play() }
                            paused = false
                            scope.launch {
                                _observableState.update { it.copy(isBuffering = false) }
                            }
                        }
                    }

                    // Stamp the start of the AudioTrack-write phase for this
                    // chunk. Combined with the chunkEnd() below, this lets
                    // the perf lane log gap_ms = startN - endNm1, which is
                    // the audible silence between adjacent chunks (modulo
                    // the constant ~130 ms minBuffer latency, see
                    // ChunkGapLogger doc). No-op unless the marker file is
                    // present, so this is free in normal operation.
                    val gapVoiceId = loadedVoiceId ?: "unknown"
                    chunkGapLogger.chunkStart(gapVoiceId, chunk.sentenceIndex)

                    // Apply the SleepTimer fade-out ramp to the live track.
                    // Polled per write iteration; AudioTrack.setVolume is a
                    // cheap JNI call but the lastVol guard skips it entirely
                    // when the ramp is idle (steady state).
                    var written = 0
                    while (written < chunk.pcm.size && pipelineRunning.get()) {
                        // Issue #540 — if the user paused mid-chunk-write,
                        // bail out of the write loop so we park at the top
                        // of the outer loop. AudioTrack.write on a paused
                        // track blocks (the ring buffer never drains
                        // through speakers), which would freeze the
                        // consumer here for the duration of the pause.
                        if (userPaused.get()) break
                        val v = volumeRamp.current
                        if (v != lastVol) {
                            runCatching { track.setVolume(v) }
                            lastVol = v
                        }
                        val n = track.write(chunk.pcm, written, chunk.pcm.size - written)
                        if (n < 0) break // error code from AudioTrack
                        written += n
                        // Issue #290 — frames written tally for the
                        // debug overlay's "audio buffered ms" row.
                        // 16-bit mono PCM = 2 bytes per frame.
                        if (n > 0) totalFramesWritten += n / 2
                    }
                    // Issue #540 — if we bailed because of user pause,
                    // we may have written part of this chunk's PCM into
                    // the AudioTrack ring buffer. That audio is still
                    // queued and will be heard the moment resume() calls
                    // track.play(). The remainder (chunk.pcm[written..])
                    // is dropped on the floor — but since we're parked,
                    // the SAME chunk will be retried on the next outer
                    // loop iteration via a fresh nextChunk()... no, wait:
                    // we already consumed this chunk from the queue.
                    // Continuing past the trailing-silence branch below
                    // is correct — the listener will hear the partial
                    // chunk on resume, then the next chunk picks up
                    // mid-sentence (~ 200 ms quantum, indistinguishable
                    // from a clean pause to the listener).
                    // Spool trailing silence from a shared zero-filled
                    // buffer (no per-sentence allocation).
                    var remaining = chunk.trailingSilenceBytes
                    while (remaining > 0 && pipelineRunning.get()) {
                        if (userPaused.get()) break // #540 — same logic as PCM loop.
                        val v = volumeRamp.current
                        if (v != lastVol) {
                            runCatching { track.setVolume(v) }
                            lastVol = v
                        }
                        val sz = remaining.coerceAtMost(SILENCE_CHUNK.size)
                        val n = track.write(SILENCE_CHUNK, 0, sz)
                        if (n < 0) break
                        remaining -= n
                        // Silence frames count toward the buffer too.
                        if (n > 0) totalFramesWritten += n / 2
                    }

                    // End-of-chunk: AudioTrack has accepted every byte of
                    // pcm + trailing silence. The next iteration's blocking
                    // queue.take() is where a slow producer shows up as a
                    // logged gap.
                    chunkGapLogger.chunkEnd(gapVoiceId, chunk.sentenceIndex)

                    // Argus Fix B (#79) — decrement the source's headroom
                    // tracker NOW, not when we dequeued. The chunk just
                    // entered AudioTrack's hardware ring buffer; the
                    // listener is about to hear it (or already hearing
                    // it). Decrementing here makes `bufferHeadroomMs`
                    // reflect "audio not yet heard," which is what the
                    // underrun threshold actually wants to compare
                    // against. Pre-Fix-B the decrement fired at dequeue,
                    // making the trigger pessimistic by ~one chunk.
                    source.decrementHeadroomForChunk(chunk)

                    // #573 — Gapless: post-write early-end detection.
                    //
                    // BEFORE this check: the consumer fell through to
                    // the top of the while(pipelineRunning) loop and
                    // blocked in nextChunk() to dequeue END_PILL — but
                    // if track.write() for THIS chunk parked the
                    // consumer for the full duration of the audio
                    // (~3-5 s for a long sentence's PCM through a
                    // small AudioTrack ring buffer), the consumer
                    // didn't reach nextChunk() until seconds after
                    // the producer pushed END_PILL. During that
                    // window the controller-level watchdog (fires on
                    // isBuffering=true at chapter boundary) hit its
                    // 1.5 s threshold and tore the pipeline down via
                    // stopPlaybackPipeline → pcmSource.close() → the
                    // consumer's track.write() unblocked when
                    // track.pause() flushed the AudioTrack. The
                    // consumer then exited the outer loop with
                    // naturalEnd STILL false (it never dequeued
                    // END_PILL — close() set pipelineRunning=false
                    // first), and the natural-end fanout was
                    // silently skipped. The 1.5 s watchdog drove
                    // every Notion auto-advance instead of the
                    // engine's primary path. (Symptom: zero
                    // `handleChapterDone` log lines at chapter end on
                    // v0.5.58.)
                    //
                    // AFTER this check: as soon as the producer
                    // signals "all sentences emitted" AND the only
                    // queue item left is END_PILL (depth == 1), we
                    // know this just-written chunk was the LAST real
                    // chunk of the chapter. Skip the redundant
                    // nextChunk() round-trip; jump straight to the
                    // natural-end exit. The HW ring buffer still has
                    // the chunk's PCM queued; the finally block
                    // drains it before releasing the track.
                    //
                    // Why `queue.size == 1` (not == 0): the producer
                    // sets producedAll=true BEFORE pushing END_PILL,
                    // so by the time we observe producedAll, END_PILL
                    // may be in the queue (size 1) or about to be
                    // (size 0, transient — the producer is between
                    // `producedAll = true` and `queue.put(END_PILL)`).
                    // We accept both: depth <= 1 captures both
                    // states. Depth > 1 means there are still real
                    // chunks queued — keep consuming through the
                    // normal path.
                    if (source.producedAllSentences &&
                        source.producerQueueDepth() <= 1
                    ) {
                        naturalEnd = true
                        android.util.Log.i(
                            "EnginePlayer",
                            "end-of-pcm (post-write fast path): naturalEnd=true " +
                                "producedAll=true queueDepth=${source.producerQueueDepth()} " +
                                "pipelineRunning=${pipelineRunning.get()} — " +
                                "skipping the END_PILL round-trip (#573 gapless)",
                        )
                        break
                    }
                }
            } finally {
                // #573 — Gapless: fire the natural-end fanout FIRST,
                // BEFORE we tear the AudioTrack down. Pre-fix the
                // order was:
                //
                //   1. track.pause() / flush() / release()   ← drops HW buffer tail (~130 ms)
                //   2. if (naturalEnd && pipelineRunning) { handleChapterDone() }
                //
                // That order introduced two problems:
                //   a) track.flush() killed ~130 ms of trailing audio
                //      that was already queued in the AudioTrack ring
                //      buffer — the listener heard the chapter end
                //      slightly truncated.
                //   b) The second `pipelineRunning.get()` check (~50 ms
                //      after the first) raced against
                //      stopPlaybackPipeline and could silently skip the
                //      chapter-done fanout entirely (Notion symptom:
                //      handleChapterDone NEVER logged at chapter end on
                //      v0.5.58).
                //
                // Post-fix: dispatch handleChapterDone IMMEDIATELY based
                // on [PcmSource.producedAllSentences] (set by the
                // producer right before END_PILL, so it's stable across
                // any pipelineRunning race), let it run in parallel
                // with the HW-buffer drain wait below, then tear the
                // track down. The next chapter's body fetch (~50-300 ms
                // for a cached Notion chapter) overlaps the HW drain;
                // when stopPlaybackPipeline calls join() on this
                // thread, the thread has already exited cleanly.
                //
                // Net result: chapter N's last sentence audibly
                // completes (HW buffer drain finishes naturally) AND
                // chapter N+1's audio starts ≤300 ms later — Spotify-
                // style gapless on cached Notion sources.
                if (naturalEnd) {
                    android.util.Log.i(
                        "EnginePlayer",
                        "post-finally: naturalEnd=true producedAll=${source.producedAllSentences} " +
                            "pipelineRunning=${pipelineRunning.get()} — dispatching handleChapterDone " +
                            "and draining HW buffer (#573 gapless)",
                    )
                    // PR-D (#86) — finalize the cache on natural end so
                    // the index sidecar lands and the cache is complete
                    // for next play. Must happen BEFORE the chapter-done
                    // coroutine because handleChapterDone advances and
                    // calls loadAndPlay → startPlaybackPipeline, which
                    // constructs a NEW source and overwrites the field.
                    runCatching { source.finalizeCache() }
                    scope.launch {
                        runCatching {
                            pcmCache.evictToQuota(
                                pinnedBasenames = cacheKey?.let { setOf(it.fileBaseName()) }
                                    ?: emptySet(),
                            )
                        }
                    }
                    // #573 — Dispatchers.Main.immediate so that if Main
                    // is idle we resume on this thread immediately and
                    // dispatch handleChapterDone with zero queueing
                    // delay. The default Main dispatcher queues through
                    // the Choreographer (~16 ms vsync grain under heavy
                    // compositing); .immediate skips that when feasible.
                    // Without .immediate the chapter-N+1 download
                    // doesn't even START until the next Choreographer
                    // tick, adding ~16-60 ms to the perceived gap.
                    scope.launch(Dispatchers.Main.immediate) {
                        sleepTimer.signalChapterEnd()
                        handleChapterDone()
                    }
                    // #573 — drain the AudioTrack HW buffer before
                    // releasing. AudioTrack.playbackHeadPosition counts
                    // frames the hardware has actually consumed
                    // (delivered to the speaker). When it catches up to
                    // totalFramesWritten, the audible audio has fully
                    // played. Poll at 20 ms grain (the OS audio
                    // callback period is ~10-20 ms on Samsung devices,
                    // so any finer is noise) with a 2 s hard cap as a
                    // safety net — a stalled HW buffer (rare but
                    // possible during audio focus transitions) must not
                    // leak the consumer thread.
                    //
                    // While we drain, the launched handleChapterDone
                    // above is racing: it suspends inside advanceChapter
                    // on the chapter-body wait. The two coroutines run
                    // concurrently — first to finish wakes its
                    // continuation. On a cached chapter the wait is
                    // <50 ms, well under the typical HW drain (~130 ms
                    // of trailing PCM the consumer wrote before END_PILL).
                    val drainDeadlineMs = System.nanoTime() / 1_000_000L + 2_000L
                    val targetFrames = totalFramesWritten
                    while (System.nanoTime() / 1_000_000L < drainDeadlineMs) {
                        // Exit immediately if stopPlaybackPipeline
                        // beat us here (next chapter's
                        // loadAndPlay → stopPlaybackPipeline raced
                        // ahead). That call paused+flushed our track
                        // already, so further draining is pointless;
                        // we'd just spin to the deadline. The release
                        // below is still safe — releasing a paused
                        // track is idempotent w.r.t. additional
                        // pause/flush from main.
                        if (!pipelineRunning.get()) break
                        val played = runCatching { track.playbackHeadPosition.toLong() }
                            .getOrDefault(targetFrames)
                        // playbackHeadPosition is unsigned 32-bit
                        // wrapped to signed Int; on a 24 kHz mono track
                        // that's ~24 h before wrap, so we don't need
                        // wrap-handling for chapter-length playback.
                        if (played >= targetFrames) break
                        try {
                            Thread.sleep(20L)
                        } catch (_: InterruptedException) {
                            // stopPlaybackPipeline interrupted us
                            // (unlikely on natural end — it should be
                            // gated behind handleChapterDone →
                            // advanceChapter → loadAndPlay, all of
                            // which see this same consumer thread
                            // exited and skip the join wait). Either
                            // way, abort the drain and proceed to
                            // release.
                            Thread.currentThread().interrupt()
                            break
                        }
                    }
                    runCatching { track.pause() }
                    runCatching { track.flush() }
                    runCatching { track.release() }
                    android.util.Log.i(
                        "EnginePlayer",
                        "post-finally: HW buffer drained, AudioTrack released " +
                            "(target=$targetFrames frames, naturalEnd path)",
                    )
                    return@Thread
                }
                // Non-natural-end path (user pause, voice swap, seek,
                // book-end). Tear the AudioTrack down from this thread
                // for the JNI-safety reason in the pre-#573 comment:
                // releasing from Main while a write() is in flight on
                // this consumer thread races and can JNI-crash.
                runCatching { track.pause() }
                runCatching { track.flush() }
                runCatching { track.release() }
                if (paused) {
                    scope.launch {
                        _observableState.update { it.copy(isBuffering = false) }
                    }
                }
                android.util.Log.i(
                    "EnginePlayer",
                    "post-finally: naturalEnd=false producedAll=${source.producedAllSentences} " +
                        "pipelineRunning=${pipelineRunning.get()} — non-natural exit " +
                        "(pause/swap/seek), AudioTrack released",
                )
            }
        }, "storyvox-audio-out").apply {
            isDaemon = true
            start()
        }
    }

    /** Bridge to the singleton VoxSherpa engines via the [EngineStreamingSource]
     *  SAM. Lives here (not in the source module) so EnginePlayer can switch
     *  on the active engine type and EngineStreamingSource stays
     *  test-friendly without depending on VoxSherpa AARs. */
    private fun activeVoiceEngineHandle(engineType: EngineType?): EngineStreamingSource.VoiceEngineHandle =
        when (engineType) {
            is EngineType.Azure -> azureStreamingHandle(engineType)
            else -> object : EngineStreamingSource.VoiceEngineHandle {
                // Issue #582 — same lock-contention guard as
                // startPlaybackPipeline's sampleRate read; this object
                // is constructed on the call to startPlaybackPipeline
                // (main thread in the speed-chip path), so the lock-
                // free volatile read prevents a 2 s UI stall.
                override val sampleRate: Int = when (engineType) {
                    is EngineType.Kokoro -> EngineSampleRateCache.kokoroRate()
                    // Issue #119 — Kitten dispatch.
                    is EngineType.Kitten -> EngineSampleRateCache.kittenRate()
                    else -> EngineSampleRateCache.piperRate()
                }.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE

                override fun generateAudioPCM(text: String, speed: Float, pitch: Float): ByteArray? {
                    AndroidProcess.setThreadPriority(AndroidProcess.THREAD_PRIORITY_URGENT_AUDIO)
                    return when (engineType) {
                        is EngineType.Kokoro -> KokoroEngine.getInstance()
                            .generateAudioPCM(text, speed, pitch)
                        // Issue #119 — Kitten dispatch.
                        is EngineType.Kitten -> KittenEngine.getInstance()
                            .generateAudioPCM(text, speed, pitch)
                        else -> VoiceEngine.getInstance()
                            .generateAudioPCM(text, speed, pitch)
                    }
                }
            }
        }

    /**
     * Streaming-capable Azure handle. Implements the streaming
     * variant so [EngineStreamingSource] can take the
     * startStreamingSerialProducer path when no Tier 3 secondaries
     * are wired (i.e. parallelSynthInstances == 1). With secondaries
     * the parallel path runs the non-streaming generateAudioPCM —
     * lookahead wins over streaming when both could apply (the user
     * gets sentences N+1..N+k pre-rendered in parallel; streaming
     * helps sentence 1 alone).
     */
    private fun azureStreamingHandle(
        engineType: EngineType.Azure,
    ): EngineStreamingSource.StreamingVoiceEngineHandle =
        object : EngineStreamingSource.StreamingVoiceEngineHandle {
            override val sampleRate: Int = azureVoiceEngine.sampleRate
                .takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE

            override fun generateAudioPCM(text: String, speed: Float, pitch: Float): ByteArray? {
                AndroidProcess.setThreadPriority(AndroidProcess.THREAD_PRIORITY_URGENT_AUDIO)
                return azureVoiceEngine.synthesize(text, engineType.voiceName, speed, pitch)
            }

            override fun generateAudioPCMStream(
                text: String, speed: Float, pitch: Float,
            ): kotlinx.coroutines.flow.Flow<ByteArray> {
                AndroidProcess.setThreadPriority(AndroidProcess.THREAD_PRIORITY_URGENT_AUDIO)
                return azureVoiceEngine.synthesizeStreaming(
                    text, engineType.voiceName, speed, pitch,
                )
            }
        }

    /**
     * Issue #642 — wait for the consumer's underrun pause to resolve.
     *
     * Three exit conditions, any of which clears the park:
     *   1. `bufferHeadroomMs >= BUFFER_RESUME_THRESHOLD_MS`: the producer
     *      caught back up; resume the AudioTrack and keep writing.
     *   2. `!pipelineRunning`: the outer pipeline is being torn down
     *      (stopPlaybackPipeline). Skip the resume; the next iteration
     *      of the consumer's outer while will see pipelineRunning=false
     *      and exit cleanly.
     *   3. `source.producedAllSentences`: the producer has emitted the
     *      LAST sentence and pushed END_PILL. No further headroom
     *      emissions will ever arrive (the producer's only headroom
     *      writes are inside its per-sentence loop). Without this exit,
     *      `bufferHeadroomMs.first { ... }` would park forever waiting
     *      for an emission that never comes — exactly the #642 stall
     *      reproduced on R5CRB0W66MK.
     *
     * Implementation: race the headroom flow against a poll loop on
     * [PcmSource.producedAllSentences]. The flow is the fast path
     * (every legitimate intra-chapter underrun resolves through an
     * emission); the poll is the safety net that fires when the
     * producer finishes during the park window. 50 ms poll cadence
     * matches the user-pause park grain elsewhere in the consumer —
     * imperceptible latency for the listener, cheap to run.
     *
     * Why not a SharedFlow on the source: keeps the surface area of
     * the #642 fix minimal. A SharedFlow would be cleaner long-term;
     * we can fold this into [EngineStreamingSource]'s public API in a
     * follow-up if more callsites need to observe the terminal
     * transition. For now the polling helper is private to
     * [EnginePlayer] and used by exactly one consumer-thread branch.
     */
    private suspend fun waitForResumeOrTerminalState(source: PcmSource) {
        kotlinx.coroutines.coroutineScope {
            val headroomJob = launch {
                runCatching {
                    source.bufferHeadroomMs.first {
                        it >= BUFFER_RESUME_THRESHOLD_MS || !pipelineRunning.get()
                    }
                }
            }
            val terminalPollJob = launch {
                // Poll producedAllSentences + pipelineRunning at the
                // same cadence as the user-pause park (Thread.sleep(50)
                // in the outer consumer loop). 50 ms is below the
                // perceived-gap threshold for resumed audio playback
                // and well under the AudioTrack ring buffer drain
                // time (~130 ms minBuffer on Samsung devices), so any
                // race the predicate observes resolves before the
                // listener notices.
                while (
                    pipelineRunning.get() &&
                    !source.producedAllSentences &&
                    source.bufferHeadroomMs.value < BUFFER_RESUME_THRESHOLD_MS
                ) {
                    kotlinx.coroutines.delay(50L)
                }
            }
            // Whichever job completes first wakes the park; cancel the
            // other so we don't leak a launched coroutine after the
            // consumer thread proceeds.
            kotlinx.coroutines.selects.select<Unit> {
                headroomJob.onJoin { terminalPollJob.cancel() }
                terminalPollJob.onJoin { headroomJob.cancel() }
            }
        }
    }

    private fun stopPlaybackPipeline() {
        // Shutdown handshake:
        //  1. Signal stop via the run flag.
        //  2. Pause + flush the AudioTrack so any currently-blocked
        //     write() returns immediately (ring buffer has space again).
        //  3. Close the source (cancels its producer; offers the END
        //     pill so the consumer's nextChunk returns null promptly).
        //  4. Interrupt + join the consumer thread (so a runBlocking
        //     parked inside nextChunk wakes up).
        //  5. The consumer's own finally block does the AudioTrack
        //     release — *not* main thread — so we can't race a write().
        //     If join times out we still don't release ourselves; the
        //     consumer will finish its current write and clean up
        //     whenever it gets there.
        pipelineRunning.set(false)
        // Issue #540 — also clear the user-pause flag so the consumer's
        // park branch exits promptly. The pipelineRunning=false above
        // already covers the inner break, but the park loop polls
        // userPaused so clearing it lets the outer iteration land on the
        // pipelineRunning check immediately.
        userPaused.set(false)

        val track = audioTrack
        audioTrack = null
        track?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
        }

        val src = pcmSource
        pcmSource = null
        if (src != null) {
            // close() is suspending but synchronous in body — runBlocking
            // here is fine; we're on Main and the close path doesn't
            // dispatch.
            runBlocking { src.close() }
        }

        val t = consumerThread
        consumerThread = null
        if (t != null && t !== Thread.currentThread()) {
            t.interrupt()
            // 2 s upper bound — plenty for a paused+flushed track to
            // finish its current write iteration. If we somehow exceed
            // this the consumer keeps living until it's done; the OLD
            // track's release is its responsibility.
            try { t.join(2_000) } catch (_: InterruptedException) {}
        }
    }

    /** Build an AudioTrack that mirrors the standalone VoxSherpa demo exactly:
     *  legacy `STREAM_MUSIC` constructor, `getMinBufferSize()` buffer (no
     *  multiplier), `MODE_STREAM`. This is deliberate even though it costs us
     *  the seconds-long pre-render cushion the bigger buffer provided.
     *
     *  Why minBuffer specifically: AudioFlinger has two media mixer paths.
     *  Tracks created with a buffer ≈ minBufferSize qualify for the
     *  fast-track (low-latency) mixer; larger buffers route through the
     *  deep-buffer mixer. On Samsung tablets the deep-buffer mixer uses a
     *  different sample-rate-conversion chain that introduces audible
     *  distortion on 22050/24000Hz mono speech PCM. The bigger buffer
     *  bought us ~2 seconds of generator headroom but lost us clean output;
     *  the producer now keeps the generator queue full ([QUEUE_CAPACITY]
     *  sentences ahead) so the small buffer never empties between writes.
     *
     *  We also keep the legacy constructor (vs `AudioTrack.Builder`) because
     *  on Samsung the Builder path with `USAGE_MEDIA + CONTENT_TYPE_MUSIC`
     *  goes through the AudioAttributes routing layer, which on some firmware
     *  applies SoundAlive/Atmos. The legacy ctor advertises `STREAM_MUSIC`
     *  directly to AudioFlinger and bypasses those effects on the affected
     *  devices. VoxSherpa standalone does the same.
     *
     *  ROADMAP A/B: temporarily routable through [createAudioTrackBuilder]
     *  via a runtime toggle. Touch the file at
     *  `${context.filesDir}/audiotrack-builder` (e.g.
     *  `adb shell touch /data/data/in.jphe.storyvox/files/audiotrack-builder`)
     *  to switch to the Builder + AudioAttributes path on the next pipeline
     *  rebuild. Remove the file to flip back. The toggle is checked on
     *  every [createAudioTrack] call — every play()/seek/setSpeed/voice-swap
     *  rebuilds the AudioTrack — so JP can A/B in-place without an app
     *  restart, and definitely without a rebuild. The Builder path will
     *  ship as the default once we confirm clean output on Tab A7 Lite;
     *  this entire dual-path block is deleted in that follow-up PR. */
    @Suppress("DEPRECATION")
    private fun createAudioTrack(sampleRate: Int): AudioTrack {
        if (isBuilderPathEnabled()) return createAudioTrackBuilder(sampleRate)
        val channelMask = AndroidAudioFormat.CHANNEL_OUT_MONO
        val encoding = AndroidAudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        return AudioTrack(
            AudioManager.STREAM_MUSIC,
            sampleRate,
            channelMask,
            encoding,
            bufferSize,
            AudioTrack.MODE_STREAM,
        )
    }

    /** Modern AudioTrack.Builder + AudioAttributes(USAGE_MEDIA,
     *  CONTENT_TYPE_MUSIC) path. Same channel mask / encoding / buffer size
     *  as the legacy ctor so the only behavioural difference under test is
     *  the AudioAttributes routing layer.
     *
     *  Why CONTENT_TYPE_MUSIC and not CONTENT_TYPE_SPEECH: on Tab A7 Lite,
     *  CONTENT_TYPE_SPEECH triggered Samsung's speech-DSP path back in
     *  v0.4.9 — we documented the symptom (audible distortion) and moved
     *  off it. The legacy STREAM_MUSIC ctor already advertises music to
     *  AudioFlinger, and we're trying to match that behaviour through the
     *  modern API.
     *
     *  Outcome 1 — Builder sounds clean on Tab A7 Lite: the cleanup PR
     *  deletes the legacy ctor + the @Suppress("DEPRECATION") + this entire
     *  toggle block, and the `createAudioTrack(sampleRate)` body becomes
     *  just the Builder call.
     *
     *  Outcome 2 — Builder reintroduces fuzz: this dual-path stays as a
     *  diagnostic for future devices, but the default toggle stays off,
     *  and we file an issue documenting WHICH USAGE × CONTENT_TYPE combos
     *  we tested. The legacy ctor stays load-bearing. */
    private fun createAudioTrackBuilder(sampleRate: Int): AudioTrack {
        val channelMask = AndroidAudioFormat.CHANNEL_OUT_MONO
        val encoding = AndroidAudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        val attrs = PlatformAudioAttributes.Builder()
            .setUsage(PlatformAudioAttributes.USAGE_MEDIA)
            .setContentType(PlatformAudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val format = AndroidAudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()
        return AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /** Runtime toggle for the AudioTrack.Builder A/B test. Checked on every
     *  AudioTrack rebuild so flipping the marker file takes effect on the
     *  next play()/seek/setSpeed/voice-swap with no app restart needed.
     *  Marker lives in app-private storage so no permissions are required:
     *
     *  ```
     *  adb shell touch /data/data/in.jphe.storyvox/files/audiotrack-builder
     *  ```
     *
     *  Remove the file to flip back to the legacy ctor.
     *
     *  Failure-tolerant: if `filesDir` is somehow unavailable (it never
     *  is in practice — Android creates it on first context use) we fall
     *  back to the legacy path. The whole toggle is dead code once the A/B
     *  resolves. */
    private fun isBuilderPathEnabled(): Boolean = try {
        java.io.File(context.filesDir, "audiotrack-builder").exists()
    } catch (_: Throwable) {
        false
    }

    /**
     * Routed pause — first user-facing surface (play-screen button →
     * PlaybackController.pause()). For audio-stream chapters (#373,
     * KVMR live + the v0.5.32 radio backend), this drops `playWhenReady`
     * on the sibling ExoPlayer so the stream actually pauses. For TTS
     * chapters it tears down the TTS pipeline via [pauseTts].
     *
     * The TTS-only path used to be the sole pause surface — clicking
     * Pause while listening to radio would tear down the (idle) TTS
     * pipeline and the ExoPlayer would happily keep streaming on top
     * of it. The Media3 `handleSetPlayWhenReady` command already did
     * the routing correctly, but the play-screen Pause button bypassed
     * it via PlaybackController → player.pauseTts(). Bug surfaced on
     * v0.5.36 on tablet/Flip3 with the Radio backend.
     */
    /**
     * Note: not named `pause()` — that collides with `BasePlayer.pause()`
     * which Media3 routes through `handleSetPlayWhenReady(false)`. Using
     * `pauseRouted()` keeps PlaybackController off the Media3 command-
     * dispatch path while preserving the same routing semantics.
     */
    fun pauseRouted() {
        if (_observableState.value.isLiveAudioChapter) {
            // Issue #554 — pin position on live-radio pause too. The
            // ExoPlayer-backed branch has its own currentPosition feed
            // and isn't as bouncy, but pinning is cheap defense.
            pinnedPausePositionMs = currentPositionMs()
            audioStreamPlayer?.let { p ->
                p.playWhenReady = false
                _observableState.update { it.copy(isPlaying = false) }
                invalidateState()
            }
            return
        }
        pauseTts()
    }

    fun pauseTts() {
        // Issue #540 / #564 — fast pause path. Keep the AudioTrack alive +
        // pre-filled so resume() restarts audio within ~150 ms (down
        // from the 2.5 s rebuild we measured on R83W80CAFZB pre-fix).
        // Tear-down ONLY happens on hard stops (voice swap, seek,
        // speed/pitch change, chapter advance) — those have legitimate
        // pipeline-invalidation reasons.
        //
        // #564 — log which branch fires so the next phone audit can
        // pinpoint why the Z Flip3 was creating fresh AudioTracks per
        // transport tap. Pre-#564 the only way to differentiate "fast
        // pause" from "fall-through teardown" was to count audio
        // sessions in dumpsys; this gives a single grep-target.
        //
        // The consumer thread polls [userPaused] each iteration and,
        // when set, calls track.pause() to park the hardware ring
        // buffer. We also call it from here so the pause takes effect
        // before the consumer's next poll (~10-200 ms window depending
        // on which AudioTrack.write the consumer is parked in).
        //
        // Issue #554 / #568 — snapshot the displayed position BEFORE
        // flipping any flags. [currentPositionMs] computes the truthful
        // value against the still-running AudioTrack; once we've
        // captured it, the pause-pin makes that the canonical value the
        // PlaybackController polls until [resume] clears the pin.
        //
        // #568 (phone -6s regression): on Samsung Z Flip3 the
        // AudioTrack.playbackHeadPosition can lag the displayed
        // scrubber by several seconds (deep-buffer reporting + a small
        // monotonic-latch race where lastTruthfulPositionMs gets
        // re-clamped to a freshly-low playbackHeadPosition reading
        // during the pause handshake). To make pause-pin regression
        // impossible by construction on every device, we pin to
        // `max(currentPositionMs, sentenceStartMs)` — the sentence-
        // aligned char-offset converted to media-time is what the
        // consumer thread has most recently WRITTEN into the
        // AudioTrack, so it's always >= the audible head and >= the
        // value the scrubber was showing on the most recent poll.
        // Forward-biased pin is fine: the user sees the scrubber stop
        // at "where the next sentence will resume", and resume() picks
        // up audibly from the same point because the AudioTrack
        // continues from its paused playbackHeadPosition (which by
        // construction is <= the sentence start). Tablet (#554) was
        // already pinned safely via currentPositionMs; the max() is a
        // strict superset of that contract.
        val baselineCharsPerSec = SPEED_BASELINE_CHARS_PER_SECOND
            .coerceAtLeast(0.001f)
        val sentenceStartMs = ((_observableState.value.charOffset /
            baselineCharsPerSec) * 1000f).toLong()
        val livePosition = currentPositionMs()
        val pinPosition = maxOf(livePosition, sentenceStartMs)
        pinnedPausePositionMs = pinPosition
        // Update the monotonic latch too — otherwise a subsequent
        // currentPositionMs() call would re-clamp downward if the pin
        // got cleared by a seek before the consumer thread had a
        // chance to advance the latch. Belt-and-suspenders against
        // any path that bypasses the pin.
        if (pinPosition > lastTruthfulPositionMs) {
            lastTruthfulPositionMs = pinPosition
        }
        val haveTrack = audioTrack != null
        val running = pipelineRunning.get()
        if (haveTrack && running) {
            userPaused.set(true)
            // Parking the track from Main is safe — AudioTrack.pause()
            // is atomic against in-flight writes (the write returns
            // immediately when the track is paused; documented
            // behavior). The consumer's blocked write completes, the
            // user-paused flag check kicks the consumer into the park
            // branch.
            runCatching { audioTrack?.pause() }
            _observableState.update { it.copy(isPlaying = false) }
            android.util.Log.i(
                "EnginePlayer",
                "#564 pauseTts: fast-path (track alive, parked) pin=${pinPosition}ms",
            )
            scope.launch { persistPosition() }
            invalidateState()
            return
        }
        // No live pipeline — fall back to the legacy teardown path
        // (idempotent, matches pre-#540 behavior on a cold pause).
        android.util.Log.w(
            "EnginePlayer",
            "#564 pauseTts: SLOW-path (teardown) — haveTrack=$haveTrack " +
                "pipelineRunning=$running userPaused=${userPaused.get()} " +
                "pin=${pinPosition}ms (this is the path that recreates the AudioTrack on resume)",
        )
        _observableState.update { it.copy(isPlaying = false) }
        stopPlaybackPipeline()
        scope.launch { persistPosition() }
        invalidateState()
    }

    fun resume() {
        // Issue #554 — clear the pause-pin so the live position feed
        // takes over again on resume. Cleared up-front (before any
        // audio-stream / voice-reload guard returns) so EVERY resume
        // path drops the pin.
        pinnedPausePositionMs = null
        // Audio-stream chapter — bring ExoPlayer back online. Mirror of
        // the Media3 handleSetPlayWhenReady(true) branch so the play-
        // screen Play button works on radio chapters too. Returns early
        // before the TTS-specific guards below; sentences[] is empty
        // for audio-stream chapters by design.
        if (_observableState.value.isLiveAudioChapter) {
            audioStreamPlayer?.let { p ->
                p.playWhenReady = true
                _observableState.update { it.copy(isPlaying = true) }
                invalidateState()
            }
            return
        }
        if (sentences.isEmpty()) return
        // If the user activated a different voice while paused (#8), the
        // existing engine model is the wrong one — route through loadAndPlay
        // to swap it before any audio comes out.
        if (voiceReloadPending) {
            val s = _observableState.value
            val fictionId = s.currentFictionId
            val chapterId = s.currentChapterId
            if (fictionId != null && chapterId != null) {
                voiceReloadPending = false
                scope.launch { loadAndPlay(fictionId, chapterId, s.charOffset) }
                return
            }
            voiceReloadPending = false
        }
        // Issue #540 — fast resume. If the AudioTrack is still alive (we
        // hit the fast-pause path above), just clear the user-paused flag
        // and kick the track back into play. The consumer thread will
        // notice the flag clear on its next iteration and resume writes
        // from the already-queued PCM. No new AudioTrack handshake, no
        // sherpa-onnx warm-up, no producer restart.
        val rHaveTrack = audioTrack != null
        val rRunning = pipelineRunning.get()
        val rUserPaused = userPaused.get()
        // #564 — broaden the fast-resume gate. Pre-#564 we required
        // userPaused==true to take the fast path. On Z Flip3 the audit
        // captured `userPaused` getting cleared between pause and resume
        // (suspected race with a stray stopPlaybackPipeline trigger;
        // the parked consumer was still alive though). With the
        // broadened gate, ANY live pipeline (track + running flag)
        // accepts the fast path: track.play() is idempotent against an
        // already-playing track, and the consumer's userPaused flag
        // gets cleared anyway. Worst case is a redundant track.play()
        // JNI call (~µs); best case (the observed bug) we skip a full
        // AudioTrack rebuild + sherpa-onnx warm-up.
        if (rHaveTrack && rRunning) {
            _observableState.update { it.copy(isPlaying = true, error = null) }
            userPaused.set(false)
            runCatching { audioTrack?.play() }
            android.util.Log.i(
                "EnginePlayer",
                "#564 resume: fast-path (unpause existing track) " +
                    "userPausedWas=$rUserPaused",
            )
            invalidateState()
            return
        }
        android.util.Log.w(
            "EnginePlayer",
            "#564 resume: SLOW-path (full pipeline rebuild) — haveTrack=$rHaveTrack " +
                "pipelineRunning=$rRunning userPaused=$rUserPaused " +
                "(this creates a NEW AudioTrack; cold pause or pipeline torn down)",
        )
        _observableState.update { it.copy(isPlaying = true, error = null) }
        startPlaybackPipeline()
        invalidateState()
    }

    /**
     * #120 — step to the previous (direction=-1) or next (direction=+1)
     * sentence boundary. Wraps the same seek path used by
     * [seekToCharOffset] / tap-to-seek; the producer restarts at the
     * new sentence, the brass underline + reader auto-scroll move
     * with it. Clamps at chapter boundaries — no-op if we're on
     * sentence 0 and direction=-1, same for last sentence + direction=+1.
     */
    fun seekSentence(direction: Int) {
        if (sentences.isEmpty()) return
        val targetIndex = (currentSentenceIndex + direction)
            .coerceIn(0, sentences.size - 1)
        if (targetIndex == currentSentenceIndex) return
        val target = sentences[targetIndex]
        seekToCharOffset(target.startChar)
    }

    fun seekToCharOffset(offset: Int) {
        if (sentences.isEmpty()) return
        val clamped = offset.coerceAtLeast(0)
        val target = sentences.indexOfLast { it.startChar <= clamped }
            .takeIf { it >= 0 } ?: 0
        currentSentenceIndex = target
        val s = sentences[target]
        // Issue #7: also update currentSentenceRange so the brass underline
        // and auto-scroll move to the tapped sentence — previously only
        // charOffset moved, leaving the visual highlight stale.
        _observableState.update {
            it.copy(
                charOffset = s.startChar,
                currentSentenceRange = SentenceRange(s.index, s.startChar, s.endChar),
            )
        }
        // Issue #554 — seek MOVES the playhead; a stale pause-pin would
        // freeze the display at the pre-seek value. Clear it so the live
        // position feed (or the seek's pipeline-rebuild path) takes
        // over. Backward seeks also reset the monotonic latch to allow
        // the scrubber to retreat.
        pinnedPausePositionMs = null
        lastTruthfulPositionMs = 0L
        if (_observableState.value.isPlaying) startPlaybackPipeline()
        invalidateState()
    }

    suspend fun advanceChapter(direction: Int) {
        val current = _observableState.value.currentChapterId ?: run {
            android.util.Log.w(
                "EnginePlayer",
                "advanceChapter(dir=$direction): no currentChapterId, bailing — #553 ",
            )
            return
        }
        val fiction = _observableState.value.currentFictionId ?: run {
            android.util.Log.w(
                "EnginePlayer",
                "advanceChapter(dir=$direction): no currentFictionId, bailing — #553",
            )
            return
        }
        android.util.Log.i(
            "EnginePlayer",
            "advanceChapter dir=$direction from=$current fiction=$fiction",
        )
        val nextId = if (direction >= 0) {
            chapterRepo.getNextChapterId(current)
        } else {
            chapterRepo.getPreviousChapterId(current)
        } ?: run {
            android.util.Log.i(
                "EnginePlayer",
                "advanceChapter: no $direction-neighbor of $current — end of book",
            )
            if (direction >= 0) {
                // Issue #524 — book finished. Flip isPlaying off so the
                // sibling UI's engineState rolls to Completed instead of
                // sitting on "Playing" with no audio. Pre-fix isPlaying
                // stayed true after a final-chapter natural end and the
                // play button looked active while the engine was idle.
                //
                // Issue #553 — also clear isBuffering, in case it was
                // set by a prior advanceChapter attempt that resolved
                // the next-id null AFTER setting the buffering latch.
                // Defensive: the natural-end path never sets isBuffering
                // before this branch, but a controller-driven retry
                // could.
                _observableState.update {
                    it.copy(
                        isPlaying = false,
                        isBuffering = false,
                        currentSentenceRange = null,
                    )
                }
                invalidateState()
                _uiEvents.tryEmit(PlaybackUiEvent.BookFinished)
            }
            return
        }
        // Issue #524 — surface a Buffering state while we wait for the
        // next chapter's body to land in the DB. Pre-fix the engine sat
        // on isPlaying=true with no audio output during this wait, which
        // looked like the player "stalled at end-of-chapter." With
        // isBuffering=true the sibling UI renders a spinner so the user
        // knows the engine is doing something (Spotify-style behavior
        // when crossing a track boundary). The next loadAndPlay clears
        // isBuffering as soon as the new pipeline starts.
        _observableState.update { it.copy(isBuffering = true) }
        invalidateState()
        android.util.Log.i(
            "EnginePlayer",
            "advanceChapter: targeting next=$nextId, queueing download + waiting for body",
        )
        chapterRepo.queueChapterDownload(fiction, nextId, requireUnmetered = false)
        // Issue #553 — wait for the chapter body, but with a hard cap.
        // Pre-fix `observeChapter(nextId).filterNotNull().first()` could
        // park indefinitely if the row never lands (download stuck,
        // network down, scheduler queue blocked). The audit's stall
        // symptom (isBuffering=true forever, no audio, MediaSession
        // state=PLAYING) is exactly what an indefinite park produces.
        //
        // 30 s is generous for an in-library Notion chapter on LAN
        // (typically <1 s) and short enough that the user can recover
        // by tapping skip-next manually before the buffer-watchdog in
        // PlaybackController fires its own retry (~5 s).
        val readyChapter = try {
            kotlinx.coroutines.withTimeoutOrNull(
                CHAPTER_BODY_WAIT_TIMEOUT_MS,
            ) {
                chapterRepo.observeChapter(nextId).filterNotNull().first()
            }
        } catch (t: Throwable) {
            android.util.Log.w(
                "EnginePlayer",
                "advanceChapter: observeChapter($nextId) failed (${t.javaClass.simpleName}) — surfacing error",
            )
            null
        }
        if (readyChapter == null) {
            android.util.Log.w(
                "EnginePlayer",
                "advanceChapter: next chapter $nextId not ready within " +
                    "${CHAPTER_BODY_WAIT_TIMEOUT_MS}ms; clearing buffering + surfacing error",
            )
            _observableState.update {
                it.copy(
                    isPlaying = false,
                    isBuffering = false,
                    error = PlaybackError.ChapterFetchFailed(
                        "Next chapter is still downloading. Tap retry or wait a moment.",
                    ),
                )
            }
            invalidateState()
            return
        }
        android.util.Log.i(
            "EnginePlayer",
            "advanceChapter: body ready for $nextId, calling loadAndPlay",
        )
        loadAndPlay(fiction, nextId, charOffset = 0)
        // Issue #287 / #563 (stuck-state-fixer) — persist the new
        // chapter's id immediately so the Library "Continue listening"
        // join sees the freshly-loaded chapter on its next emission.
        // Without this the playback_position row stays pointed at the
        // PREVIOUS chapter until the next save tick (e.g. user pauses,
        // or next sentence boundary triggers a persistPosition), and
        // the Resume card paints the new chapter's title alongside the
        // old chapter's index/number — a confusing mismatch every
        // auto-advance.
        //
        // #563 — wrap in NonCancellable. The buffering-stuck watchdog's
        // `withContext(Main) { advanceChapter(1) }` runs on a coroutine
        // that the watchdog itself cancels once the state transition
        // completes (the chapter id flips so `distinctUntilChanged`
        // re-emits and the watchdog's collect loop re-arms). If that
        // cancellation lands BEFORE this line, the suspending
        // persistPosition is skipped and the DB row stays pointing at
        // the old chapter — which is exactly the "Resume card stale
        // after watchdog auto-advance" phone audit symptom (#563). The
        // NonCancellable block lets persistPosition finish even if the
        // parent is cancelling.
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            persistPosition()
        }
        _uiEvents.tryEmit(PlaybackUiEvent.ChapterChanged(nextId))
        android.util.Log.i(
            "EnginePlayer",
            "advanceChapter: complete, now playing $nextId",
        )
    }

    private suspend fun handleChapterDone() {
        val chapterId = _observableState.value.currentChapterId
        val fictionId = _observableState.value.currentFictionId
        android.util.Log.i(
            "EnginePlayer",
            "handleChapterDone: chapter=$chapterId fiction=$fictionId — starting natural-end housekeeping",
        )
        // Issue #557 (stuck-state-fixer batch) — flip into the
        // chapter-transition buffering state BEFORE the housekeeping fans
        // out. The natural-end branch already released the AudioTrack in
        // the consumer thread's `finally`, so the speakers are physically
        // silent the moment we get here. Pre-fix the only state mutation
        // in this window was `isBuffering=true` inside `advanceChapter`
        // (line ~2617), which runs AFTER several runCatching-wrapped
        // suspend steps; if any of those throws CancellationException
        // (the parent scope was cancelled — e.g. service shutdown, user
        // navigated away, pipelineRunning was just set to false by
        // stopPlaybackPipeline mid-housekeeping), the rest of the
        // function short-circuits and the engine sits on
        // `isPlaying=true / isBuffering=false / currentSentenceRange=...
        // (stale)` forever. That's exactly the audit's symptom — the UI
        // claims playing, the MediaSession heartbeat shows
        // `state=PLAYING, position=57621 frozen` every 3s, but no PCM is
        // moving.
        //
        // Keep `isPlaying=true` here on purpose: the controller's
        // `engineState` mapping requires `isPlaying && isBuffering` to
        // surface Buffering; dropping isPlaying would route us through
        // Paused for this brief window. Clearing `currentSentenceRange`
        // is what makes the debug overlay's "warming up" indicator
        // honest — `isWarmingUp = isPlaying && currentSentenceRange ==
        // null` in RealDebugRepositoryUi, and the post-fix invariant is
        // that while we hold that combo we're truly in a transition,
        // not stuck.
        //
        // `getState()` was also updated (#557) to emit
        // `Player.STATE_BUFFERING` whenever `isBuffering=true`, so the
        // MediaSession surface stops reporting PLAYING while we're
        // physically silent.
        _observableState.update {
            it.copy(
                isBuffering = true,
                currentSentenceRange = null,
            )
        }
        invalidateState()
        // Issue #553 — wrap each pre-advance step in runCatching so a
        // single Room write hiccup or a missing repo dep can't strand us
        // BEFORE advanceChapter even runs. Pre-fix any uncaught throw
        // here would surface only via the consumer thread's catch-and-
        // swallow, leaving the user on isBuffering=false / isPlaying=true
        // forever (the natural-end never emits Buffering before
        // advanceChapter — only advanceChapter sets it). The audit's
        // "stuck in Buffering" symptom is consistent with advanceChapter
        // having run its prologue and parked on the chapter-body wait;
        // belt-and-suspenders runCatching here makes sure we always at
        // LEAST reach advanceChapter.
        runCatching { persistPosition() }
            .onFailure {
                android.util.Log.w(
                    "EnginePlayer",
                    "handleChapterDone: persistPosition failed (${it.message}) — continuing",
                )
            }
        if (chapterId != null) {
            runCatching { chapterRepo.markChapterPlayed(chapterId) }
                .onFailure {
                    android.util.Log.w(
                        "EnginePlayer",
                        "handleChapterDone: markChapterPlayed failed (${it.message}) — continuing",
                    )
                }
        }
        // PR-F (#86) — schedule chapter N+2's background render. N+1 is
        // either already cached, in flight as the previous chapter-done
        // trigger's enqueue, or about to be teed by PR-D as the user
        // taps Next. Wrapped in runCatching so a scheduler hiccup
        // doesn't block the natural-end flow (advanceChapter, history
        // marker, ChapterDone event).
        if (chapterId != null) {
            runCatching { prerenderTriggers.onChapterCompleted(chapterId) }
                .onFailure {
                    android.util.Log.w(
                        "EnginePlayer",
                        "handleChapterDone: prerender hook failed (${it.message}) — continuing",
                    )
                }
        }
        // Issue #158 — piggyback the History `completed` flag on the
        // existing end-of-chapter event. Mirrors `markChapterPlayed`
        // above; both fire on the same trigger (chapter naturally
        // ended, not user-skipped via Next). `fractionRead = 1f`
        // because we reached end-of-chapter — the only call-site that
        // passes a partial fraction would be a future "user skipped
        // 90% of the way through" path, which isn't in scope for this
        // issue.
        if (fictionId != null && chapterId != null) {
            runCatching { historyRepo.markCompleted(fictionId, chapterId, fraction = 1f) }
                .onFailure {
                    android.util.Log.w(
                        "EnginePlayer",
                        "handleChapterDone: history.markCompleted failed (${it.message}) — continuing",
                    )
                }
        }
        // Calliope (v0.5.00) — distinguish "chapter naturally finished" from
        // "user tapped Next chapter". Emit BEFORE advanceChapter so the
        // confetti overlay's observer sees ChapterDone first; the
        // subsequent ChapterChanged (from inside advanceChapter) is a
        // separate axis. UI is responsible for the one-time gate; the
        // engine just announces facts.
        if (chapterId != null) {
            _uiEvents.tryEmit(PlaybackUiEvent.ChapterDone(chapterId))
        }
        // advanceChapter now persists the new chapter's id internally
        // (issue #287 — see the comment on advanceChapter).
        // Issue #553 — wrap advanceChapter so an unexpected throw can't
        // strand us in Buffering. On failure we surface a typed error
        // and clear the latches so the user can recover via the
        // controller-level watchdog or by tapping skip-next manually.
        runCatching { advanceChapter(direction = 1) }
            .onFailure { t ->
                android.util.Log.e(
                    "EnginePlayer",
                    "handleChapterDone: advanceChapter threw (${t.javaClass.simpleName}: " +
                        "${t.message}) — surfacing error so the UI can prompt retry",
                    t,
                )
                _observableState.update {
                    it.copy(
                        isPlaying = false,
                        isBuffering = false,
                        error = PlaybackError.ChapterFetchFailed(
                            "Couldn't load the next chapter (${t.javaClass.simpleName}). Tap retry.",
                        ),
                    )
                }
                invalidateState()
            }
        android.util.Log.i(
            "EnginePlayer",
            "handleChapterDone: complete",
        )
    }

    private suspend fun persistPosition() {
        val s = _observableState.value
        val fictionId = s.currentFictionId ?: return
        val chapterId = s.currentChapterId ?: return
        positionRepo.save(
            fictionId = fictionId,
            chapterId = chapterId,
            charOffset = s.charOffset,
            durationEstimateMs = s.durationEstimateMs,
        )
    }

    fun setSpeed(speed: Float) {
        // Issue #555 — capture the truthful audible position BEFORE the
        // pipeline rebuild so we can carry it across as the new
        // pipelineStartCharOffset. Without this, `state.charOffset` (set
        // by the consumer via `scope.launch` on Main) can lag the
        // audible head by many seconds because the consumer's state
        // updates are queued behind the foreground work; setSpeed would
        // see a stale charOffset from 30-50 s ago and the new pipeline
        // would restart its position axis there — exactly the audit's
        // "1:21 → 1:02 jump backward" symptom.
        //
        // We compute the audible position via [currentPositionMs] (which
        // uses the live AudioTrack frame counter — the only truthful
        // value at this moment) and store its CHAR-OFFSET equivalent for
        // the new pipeline to consume in [startPlaybackPipeline]. Both
        // sides speak the speed-invariant baseline axis, so this is a
        // clean handoff.
        val priorAudibleMs = currentPositionMs()
        val handoffChar = ((priorAudibleMs / 1000.0) *
            SPEED_BASELINE_CHARS_PER_SECOND).toInt()
        speedHandoffCharOffset = handoffChar
        currentSpeed = speed
        // Issue #555 — also write the truthful audible char-offset into
        // PlaybackState BEFORE the rebuild so the UI's position
        // interpolation (which reads state.charOffset and computes
        // baseMs = charOffset / BASELINE * 1000) anchors at the right
        // value. The consumer thread's `scope.launch { _observableState
        // .update { ... charOffset = chunk.range.startCharInChapter } }`
        // updates are queued on Main and can land BEFORE we get here —
        // setting charOffset = handoffChar here ensures the next UI
        // composition reads the truthful value, not a sentence-start
        // value that lags by one chunk.
        //
        // Coerced to be ≥ the current state.charOffset so we don't
        // accidentally rewind the chapter cursor when handoff happens
        // to round below it. Bookkeeping invariant: charOffset is
        // monotonic within a chapter except across explicit seeks.
        val anchorChar = handoffChar.coerceAtLeast(_observableState.value.charOffset)
        _observableState.update { it.copy(speed = speed, charOffset = anchorChar) }
        if (_observableState.value.isPlaying) startPlaybackPipeline() // rebuild with new speed
        invalidateState()
    }

    fun setPitch(pitch: Float) {
        currentPitch = pitch
        _observableState.update { it.copy(pitch = pitch) }
        if (_observableState.value.isPlaying) startPlaybackPipeline()
        invalidateState()
    }

    /**
     * Issue #90 — set the inter-sentence silence multiplier. 0f disables
     * the splice entirely; 1f restores the default. Coerced to [0, 4] to
     * defend against bad callers (the UI hard-codes Off=0 / Normal=1 /
     * Long=1.75 today, but a future slider could pass arbitrary values).
     *
     * Mirrors [setSpeed] / [setPitch]: stores the new value, then rebuilds
     * the pipeline so the next sentence the producer generates picks up
     * the new multiplier. The currently-playing sentence finishes with
     * whatever silence it was queued with — we don't try to retro-edit
     * audio already in the AudioTrack ring buffer.
     */
    fun setPunctuationPauseMultiplier(multiplier: Float) {
        currentPunctuationPauseMultiplier = multiplier.coerceIn(0f, 4f)
        // #196 — push the new scale into the Kokoro engine immediately
        // so within-sentence comma pauses take effect on the next
        // generated sentence. The setter triggers an OfflineTts
        // rebuild via _reloadIfActive (VoxSherpa v2.7.6+); the active
        // sentence finishes with the old scale, the next one picks up
        // the new value. No-op on Piper voices — VoiceEngine doesn't
        // expose silenceScale because the issue is Kokoro-specific.
        KokoroEngine.getInstance().setSilenceScale(
            KOKORO_SILENCE_SCALE_BASELINE * currentPunctuationPauseMultiplier,
        )
        if (_observableState.value.isPlaying) startPlaybackPipeline()
        invalidateState()
    }

    fun setTtsVolume(v: Float) {
        volumeRamp.set(v)
        runCatching { audioTrack?.setVolume(v.coerceIn(0f, 1f)) }
    }

    private fun estimateDurationMs(text: String): Long {
        // Issue #555 — duration on the media-time (speed-1) axis. Stable
        // across speed changes so the scrubber rail length doesn't jump
        // when the user taps a different speed chip mid-chapter. The
        // wall-clock time-to-completion DOES shrink at higher speeds —
        // that's surfaced separately (in the "X min left" caption when
        // wired) without disturbing the rail. [currentPositionMs] uses
        // the same axis, so position-over-duration is the consumption
        // fraction regardless of speed.
        val charsPerSec = SPEED_BASELINE_CHARS_PER_SECOND
        if (charsPerSec <= 0f) return 0L
        return ((text.length / charsPerSec) * 1000f).toLong()
    }

    // ----- SimpleBasePlayer command handlers -----

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        // Issue #373 — audio-stream mode (KVMR live + future LibriVox
        // tracks) routes play/pause through the sibling ExoPlayer, not
        // the TTS pipeline. The TTS pause/resume path tears down the
        // PCM producer + AudioTrack; doing that to a live-stream
        // chapter would silently lose the stream connection.
        if (_observableState.value.isLiveAudioChapter) {
            val p = audioStreamPlayer
            if (p != null) {
                p.playWhenReady = playWhenReady
                _observableState.update { it.copy(isPlaying = playWhenReady) }
                invalidateState()
            }
            return Futures.immediateVoidFuture()
        }
        if (playWhenReady) resume() else pauseTts()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        // Issue #373 — stop both pipelines defensively; whichever was
        // running drops, the other is a cheap no-op.
        stopPlaybackPipeline()
        stopAudioStreamPlayer()
        // Issue #560 — handleStop is the user-driven stop (notification
        // dismiss, transport stop button) — abandon focus so other apps
        // can take it. Transport pause via handleSetPlayWhenReady(false)
        // does NOT abandon (we want to hold focus while paused so a
        // subsequent resume doesn't have to re-acquire).
        runCatching { audioFocus.abandon() }
        _observableState.update {
            it.copy(
                isPlaying = false,
                currentSentenceRange = null,
                isLiveAudioChapter = false,
            )
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        setSpeed(playbackParameters.speed)
        setPitch(playbackParameters.pitch)
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        releaseEngine()
        return Futures.immediateVoidFuture()
    }

    fun releaseEngine() {
        kotlinx.coroutines.runBlocking { persistPosition() }
        stopRecapPipeline()
        stopPlaybackPipeline()
        stopAudioStreamPlayer()
        // Issue #560 — release audio focus so other media apps can
        // resume playback when storyvox is dismissed. The transport-
        // level pause/resume doesn't abandon focus (we keep it for the
        // chapter the user is paused in, matching audiobook UX); only
        // full release / handleStop drops it.
        runCatching { audioFocus.abandon() }
        scope.cancel()
    }

    // ─── audio-stream playback (#373) ─────────────────────────────────

    /**
     * Issue #373 — sibling Media3 [androidx.media3.exoplayer.ExoPlayer]
     * for audio-stream chapters (KVMR community radio + future
     * LibriVox / Internet Archive). Lazily constructed on first
     * audio-routed `loadAndPlay` and reused across chapter swaps; torn
     * down on [handleRelease] / [handleStop].
     *
     * Lives alongside the TTS pipeline rather than replacing it because
     * the bulk of EnginePlayer's machinery (sentence chunker, VoxSherpa
     * engines, Sonic pitch shifting, AudioTrack lifecycle) is purely
     * text-narration concerns that don't apply to a pre-rendered audio
     * stream. The two paths are mutually exclusive on a single chapter
     * load — guarded by [PlaybackState.isLiveAudioChapter] on every
     * branch in [handleSetPlayWhenReady] / [handleStop] / playback
     * controllers that share this player instance.
     */
    private var audioStreamPlayer: androidx.media3.exoplayer.ExoPlayer? = null

    /** Listener attached to [audioStreamPlayer] so isPlaying flips on
     *  the MediaSession surface follow real player events (buffer →
     *  ready → playing). Held so [stopAudioStreamPlayer] can detach
     *  before releasing the player and avoid the leaked-callback
     *  warning on a fresh chapter load. */
    private var audioStreamListener: androidx.media3.common.Player.Listener? = null

    /**
     * Issue #373 — load and play an audio-stream chapter through
     * Media3's [androidx.media3.exoplayer.ExoPlayer]. Bypasses the TTS
     * pipeline entirely; the URL goes straight to ExoPlayer's
     * MediaItem builder, which handles AAC / MP3 / OGG / streaming
     * containers natively. Caller has already verified
     * `chapter.audioUrl != null`.
     *
     * No sentence chunker / charOffset semantics — a live stream has
     * no positional addressing the user can scrub against. The
     * `charOffset` argument from [loadAndPlay] is ignored for audio
     * chapters; resume after pause restarts the stream from "now".
     */
    private suspend fun loadAndPlayAudioStream(
        fictionId: String,
        chapterId: String,
        chapter: PlaybackChapter,
    ) {
        val url = chapter.audioUrl
            ?: return // shouldn't happen — caller guards on non-null
        // Tear down any in-flight TTS pipeline; the two paths can't
        // coexist on a single MediaSession.
        stopPlaybackPipeline()
        sentences = emptyList()
        currentSentenceIndex = 0

        historyRepo.logOpen(fictionId, chapterId)

        _observableState.update {
            it.copy(
                currentFictionId = fictionId,
                currentChapterId = chapterId,
                charOffset = 0,
                isPlaying = true,
                bookTitle = chapter.bookTitle,
                chapterTitle = chapter.title,
                coverUri = chapter.coverUrl,
                // Live streams have unknown duration — surface a
                // 0-length so the UI renders "live" / "—:—" instead
                // of a misleading scrub bar.
                durationEstimateMs = 0L,
                currentSentenceRange = null,
                error = null,
                isLiveAudioChapter = true,
            )
        }
        invalidateState()

        ensureAudioStreamPlayer().run {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }

    /** Construct the ExoPlayer lazily; subsequent calls reuse the
     *  existing instance. Attaches a listener that bridges ExoPlayer's
     *  playback state into the storyvox [PlaybackState] flow so
     *  MediaSession / notification / lockscreen see the right
     *  isPlaying value when the stream buffers or stalls. */
    private fun ensureAudioStreamPlayer(): androidx.media3.exoplayer.ExoPlayer {
        audioStreamPlayer?.let { return it }
        val player = androidx.media3.exoplayer.ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .build()
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _observableState.update { it.copy(isPlaying = isPlaying) }
                invalidateState()
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                _observableState.update {
                    it.copy(
                        isPlaying = false,
                        error = PlaybackError.ChapterFetchFailed(
                            "Audio stream error: ${error.message ?: "unknown"}",
                        ),
                    )
                }
                invalidateState()
            }
            override fun onPlaybackStateChanged(state: Int) {
                val buffering = state == androidx.media3.common.Player.STATE_BUFFERING
                _observableState.update { it.copy(isBuffering = buffering) }
                invalidateState()
            }
        }
        player.addListener(listener)
        audioStreamListener = listener
        audioStreamPlayer = player
        return player
    }

    /** Idempotent teardown — called from [handleStop] / [releaseEngine]
     *  and on every fresh TTS chapter load to make sure the ExoPlayer
     *  doesn't keep streaming audio under a text chapter. */
    private fun stopAudioStreamPlayer() {
        val p = audioStreamPlayer ?: return
        audioStreamListener?.let { p.removeListener(it) }
        audioStreamListener = null
        runCatching { p.stop() }
        runCatching { p.release() }
        audioStreamPlayer = null
    }

    // ----- Issue #189: one-shot recap-aloud TTS -----

    /**
     * Issue #189 — synthesize and play [text] as a one-shot utterance via
     * the active voice. Used by the chapter-recap modal to read the
     * AI-generated recap aloud. Distinct from [loadAndPlay] in that:
     *  - No fiction/chapter binding; doesn't move charOffset.
     *  - Uses a dedicated [recapAudioTrack] + consumer thread; chapter
     *    pipeline state is left untouched (chapter pause is the caller's
     *    job — see ReaderViewModel.toggleRecapAloud).
     *  - When playback ends naturally, [recapPlayback] flips to Idle. The
     *    caller decides whether to auto-resume the fiction (we don't —
     *    the UX is "fiction stays paused so the listener can absorb").
     *
     * Reuses [engineMutex] so the engine isn't entered twice — a recap
     * starting while a chapter generator's JNI call is in flight will
     * wait for it to complete (or for the caller's pause to tear it down).
     *
     * No-op if a voice can't be activated (returns silently; the UI will
     * see [recapPlayback] never leaves Idle and can choose its own
     * fallback). Existing recap playback is stopped before a new one
     * starts.
     */
    suspend fun speak(text: String) {
        if (text.isBlank()) return
        stopRecapPipeline()
        if (!ensureVoiceLoaded()) return
        val recapSentences = chunker.chunk(text)
        if (recapSentences.isEmpty()) return
        startRecapPipeline(recapSentences)
    }

    /** Issue #189 — stop an in-flight recap utterance. Idempotent. */
    fun stopSpeaking() {
        stopRecapPipeline()
    }

    /**
     * Issue #290 — point-in-time snapshot of the producer queue +
     * AudioTrack buffer state. Read by the Debug overlay at its 1Hz
     * snapshot cadence; off the hot path entirely.
     *
     * Returns zeros when no pipeline is active (queue/track not bound).
     * `audioBufferMs` is best-effort: AudioTrack's playbackHeadPosition
     * wraps every 2^31 / sampleRate seconds (~24 hours at 24 kHz) — the
     * mask-to-unsigned-int handles single wraps; longer-running pipelines
     * with no rebuild would need an explicit overflow counter (deferred
     * — current chapters rebuild the pipeline on chapter-end well before
     * the wrap window).
     */
    fun bufferTelemetry(): `in`.jphe.storyvox.playback.BufferTelemetry {
        val src = pcmSource
        val track = audioTrack
        val audioBufferMs = if (track != null && track.sampleRate > 0) {
            val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            val pendingFrames = (totalFramesWritten - head).coerceAtLeast(0)
            pendingFrames * 1000L / track.sampleRate
        } else {
            0L
        }
        return `in`.jphe.storyvox.playback.BufferTelemetry(
            producerQueueDepth = src?.producerQueueDepth() ?: 0,
            producerQueueCapacity = src?.producerQueueCapacity() ?: 0,
            audioBufferMs = audioBufferMs,
        )
    }

    /**
     * Issue #189 — extracted from [loadAndPlay]'s model-load path so
     * [speak] can warm up the active voice without going through the
     * chapter-binding flow. Returns true if a model is loaded and the
     * engine is ready to generate; false if no active voice or load
     * failed (caller renders the failure however it wants — for recap
     * we silently bail and the UI's Read-aloud button stays in its
     * pre-tap state).
     *
     * Skips the load if the same voice is already loaded — the chapter
     * pipeline's [loadedVoiceId] is the canonical signal.
     */
    private suspend fun ensureVoiceLoaded(): Boolean {
        val active = voiceManager.activeVoice.first() ?: return false
        // Already loaded? Nothing to do — the engine is hot.
        if (loadedVoiceId == active.id && activeEngineType != null) return true

        val loadResult: String = withContext(Dispatchers.IO) {
            engineMutex.withLock {
                when (active.engineType) {
                    EngineType.Piper -> {
                        val voiceDir = voiceManager.voiceDirFor(active.id)
                        val onnx = File(voiceDir, "model.onnx").absolutePath
                        val tokens = File(voiceDir, "tokens.txt").absolutePath
                        VoiceEngine.getInstance().loadModel(context, onnx, tokens)
                            ?: "Error: load returned null"
                    }
                    is EngineType.Kokoro -> {
                        val sharedDir = voiceManager.kokoroSharedDir()
                        val onnx = File(sharedDir, "model.onnx").absolutePath
                        val tokens = File(sharedDir, "tokens.txt").absolutePath
                        val voicesBin = File(sharedDir, "voices.bin").absolutePath
                        KokoroEngine.getInstance().setActiveSpeakerId(
                            (active.engineType as EngineType.Kokoro).speakerId,
                        )
                        // #196 — drive Kokoro's within-sentence comma
                        // pause from the same punctuation-cadence
                        // multiplier we use for between-sentence
                        // silence. 0.2f baseline = engine default; the
                        // multiplier scales it linearly so a 0× user
                        // collapses commas, a 2× user stretches them
                        // to ~0.4f. Field on the engine is read at
                        // config-build time inside loadModel, so set
                        // before loadModel — not after.
                        KokoroEngine.getInstance().setSilenceScale(
                            KOKORO_SILENCE_SCALE_BASELINE * currentPunctuationPauseMultiplier,
                        )
                        KokoroEngine.getInstance().loadModel(context, onnx, tokens, voicesBin)
                            ?: "Error: load returned null"
                    }
                    is EngineType.Kitten -> {
                        // Issue #119 — Kitten recap path. Tier 3
                        // secondaries are NOT spun up here (recap is a
                        // one-off short read; the primary engine is
                        // sufficient). KittenEngine doesn't expose a
                        // silence-scale knob — it produces cleaner
                        // punctuation cadence at baseline than Kokoro.
                        val sharedDir = voiceManager.kittenSharedDir()
                        val onnx = File(sharedDir, "model.onnx").absolutePath
                        val tokens = File(sharedDir, "tokens.txt").absolutePath
                        val voicesBin = File(sharedDir, "voices.bin").absolutePath
                        KittenEngine.getInstance().setActiveSpeakerId(
                            (active.engineType as EngineType.Kitten).speakerId,
                        )
                        KittenEngine.getInstance().loadModel(context, onnx, tokens, voicesBin)
                            ?: "Error: load returned null"
                    }
                    is EngineType.Azure -> return@withContext "Error: Azure unsupported in recap"
                }
            }
        }
        if (loadResult != "Success") return false
        activeEngineType = active.engineType
        loadedVoiceId = active.id
        return true
    }

    /**
     * Issue #189 — recap-only producer/consumer pair. Lifted shape from
     * [startPlaybackPipeline] but stripped to the essentials:
     *  - No buffering UI (a 200-word recap is short; underrun is fine).
     *  - No catchup-pause / sleep-timer plumbing.
     *  - On natural end, flips [recapPlayback] back to Idle. No chapter
     *    advance, no position persistence.
     */
    private fun startRecapPipeline(recapSentences: List<Sentence>) {
        val engineType = activeEngineType
        // Issue #582 — startRecapPipeline is reachable from main-thread
        // Compose handlers (the "play recap" button in the reader). Use
        // the @Volatile cache so a concurrent loadModel on a background
        // dispatcher can't stall the UI through the engine monitor.
        val sampleRate = when (engineType) {
            is EngineType.Kokoro -> EngineSampleRateCache.kokoroRate()
            // Issue #119 — Kitten recap sample rate.
            is EngineType.Kitten -> EngineSampleRateCache.kittenRate()
            else -> EngineSampleRateCache.piperRate()
        }.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE
        val track = createAudioTrack(sampleRate)
        recapAudioTrack = track

        val source = EngineStreamingSource(
            sentences = recapSentences,
            startSentenceIndex = 0,
            engine = activeVoiceEngineHandle(engineType),
            speed = currentSpeed,
            pitch = currentPitch,
            engineMutex = engineMutex,
            punctuationPauseMultiplier = currentPunctuationPauseMultiplier,
            // #486 / #488 — recap utterances honor the same TalkBack pad
            // (a TalkBack user hearing "Here's where you left off…"
            // benefits from the same inter-sentence breathing room).
            extraA11ySilenceMs = cachedA11yExtraSilenceMs,
            queueCapacity = cachedBufferChunks.coerceIn(2, 3000),
            pronunciationDictApply = cachedPronunciationDict::apply,
        )
        recapPcmSource = source
        recapPipelineRunning.set(true)
        _recapPlayback.value = RecapPlaybackState.Speaking

        recapConsumerThread = Thread({
            AndroidProcess.setThreadPriority(AndroidProcess.THREAD_PRIORITY_URGENT_AUDIO)
            var firstSentence = true
            try {
                runCatching { track.play() }
                while (recapPipelineRunning.get()) {
                    val chunk = try {
                        runBlocking { source.nextChunk() }
                    } catch (_: Throwable) {
                        null
                    } ?: break

                    if (firstSentence) {
                        runCatching { track.setVolume(1f) }
                        firstSentence = false
                    }

                    var written = 0
                    while (written < chunk.pcm.size && recapPipelineRunning.get()) {
                        val n = track.write(chunk.pcm, written, chunk.pcm.size - written)
                        if (n < 0) break
                        written += n
                    }
                    var remaining = chunk.trailingSilenceBytes
                    while (remaining > 0 && recapPipelineRunning.get()) {
                        val sz = remaining.coerceAtMost(SILENCE_CHUNK.size)
                        val n = track.write(SILENCE_CHUNK, 0, sz)
                        if (n < 0) break
                        remaining -= n
                    }
                    // Argus Fix B (#79) — see the main consumer loop
                    // above. Recap pipeline mirrors the same headroom
                    // accounting; decrement after the AudioTrack write
                    // so the underrun threshold is checked against
                    // "audio not yet heard," not "audio in the queue."
                    source.decrementHeadroomForChunk(chunk)
                }
            } finally {
                runCatching { track.pause() }
                runCatching { track.flush() }
                runCatching { track.release() }
                // Only flip back to Idle if we're still the active recap
                // pipeline — a stop-then-start race could otherwise have
                // the dying old thread reset state for the new one.
                if (recapPipelineRunning.compareAndSet(true, false)) {
                    scope.launch { _recapPlayback.value = RecapPlaybackState.Idle }
                }
            }
        }, "storyvox-recap-out").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopRecapPipeline() {
        recapPipelineRunning.set(false)
        val track = recapAudioTrack
        recapAudioTrack = null
        track?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
        }
        val src = recapPcmSource
        recapPcmSource = null
        if (src != null) runBlocking { src.close() }
        val t = recapConsumerThread
        recapConsumerThread = null
        if (t != null && t !== Thread.currentThread()) {
            t.interrupt()
            try { t.join(2_000) } catch (_: InterruptedException) {}
        }
        _recapPlayback.value = RecapPlaybackState.Idle
    }

    private companion object {
        /** Fallback when the engine reports a non-positive sample rate (model
         *  not loaded yet). Piper voices are 22050Hz; Kokoro is 24000Hz. */
        const val DEFAULT_SAMPLE_RATE = 22050

        /** Issue #553 / #558 — hard cap on the wait for the next
         *  chapter's body to land in the DB during auto-advance.
         *  Beyond this we bail with a typed
         *  [PlaybackError.ChapterFetchFailed] rather than letting the
         *  user sit on a frozen Buffering state.
         *
         *  v0.5.64 (#558) — bumped 30 s → 60 s. The body-prefetch
         *  scheduling in [`in`.jphe.storyvox.playback.cache.PrerenderTriggers.onChapterOpened]
         *  is the primary defense (bodies should already be in
         *  cache by auto-advance time), but on a cold-start where
         *  the user resumes mid-fiction without playing through
         *  prior chapters — or on a particularly slow Notion API
         *  walk — the body fetch CAN take 30-45 s. 60 s keeps the
         *  user from hitting the error path on those edge cases
         *  while still being short enough that a truly dead
         *  network surfaces an actionable error before the user
         *  walks away from the device. */
        const val CHAPTER_BODY_WAIT_TIMEOUT_MS = 60_000L

        /** Issue #196 — Kokoro's previously-hardcoded silence scale
         *  (0.2f) is the multiplier=1.0 baseline. We linearly scale
         *  with the user's punctuation-cadence multiplier so a 0×
         *  user collapses commas and a 2× user stretches them to
         *  ~0.4f, matching Thalia's recommended Off / Normal / Long
         *  curve from the VoxSherpa knobs research doc. */
        const val KOKORO_SILENCE_SCALE_BASELINE = 0.2f

        /** When buffered audio falls below this, pause AudioTrack and surface
         *  a "Buffering..." UI state.
         *
         *  Calibrated for the Helio P22T worst case (Piper-high "cori" at
         *  0.285× realtime = 3.5× slower than playback). The original 2s
         *  threshold was designed for a near-realtime engine; on a 3.5×
         *  slow producer, the consumer would resume on 4s of headroom,
         *  drain the first chunk, then block on `queue.take()` for ~7s
         *  while the producer finished generating the next 2s sentence —
         *  the audible gap JP reports on the tablet (#79).
         *
         *  Bumping to 7s pauses earlier so the producer has more runway
         *  before the consumer empties the queue; combined with the
         *  raised resume threshold below, the consumer resumes only when
         *  there's enough audio queued to outlast the next generation
         *  cycle. Trade-off: the buffering spinner appears more often
         *  on slow-engine + low-buffer setups. Acceptable — silent
         *  gaps are worse than visible spinners. */
        const val BUFFER_UNDERRUN_THRESHOLD_MS = 7_000L

        /** Hysteresis. Don't resume until we have this much queued or we'll
         *  thrash pause/play on every chunk transition.
         *
         *  Sized to outlast one full producer-generation cycle on the
         *  Helio P22T worst case. With Piper-high at 3.5× realtime, a 2s
         *  sentence takes ~7s of CPU time to render; we want the
         *  consumer to have at least one full cycle of headroom on
         *  resume so it doesn't immediately re-stall. 10s = 7s
         *  generation budget + 3s slack for trailing silence + GC
         *  pauses. Pre-#79 value was 4s, which was sized for near-
         *  realtime engines and produced audible gaps with all
         *  Performance & Buffering toggles ON at buffer=3000. */
        const val BUFFER_RESUME_THRESHOLD_MS = 10_000L

        /** Shared zero-filled buffer the consumer writes from to spool
         *  inter-sentence silence. Sized for one chunk @ 24 kHz mono 16-bit
         *  ≈ 350 ms, which is the longest silence we ever emit; longer
         *  silences chain multiple writes from the same buffer. Static so
         *  every sentence reuses the same allocation. */
        val SILENCE_CHUNK: ByteArray = ByteArray(24_000 * 2 * 350 / 1000)
    }
}
