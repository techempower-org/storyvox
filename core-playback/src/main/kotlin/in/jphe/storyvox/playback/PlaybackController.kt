package `in`.jphe.storyvox.playback

import `in`.jphe.storyvox.playback.diagnostics.AudioOutputMonitor
import `in`.jphe.storyvox.playback.diagnostics.WaitReason
import `in`.jphe.storyvox.playback.tts.EnginePlayer
import `in`.jphe.storyvox.playback.tts.RecapPlaybackState
import `in`.jphe.storyvox.playback.tts.SentenceChunker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

interface PlaybackController {
    val state: StateFlow<PlaybackState>
    val events: SharedFlow<PlaybackUiEvent>

    /**
     * Issues #524 #530 #536 #539 #543 (audio-fidelity-fixer batch) — the
     * Spotify/Apple-Music-style single-source-of-truth UI state. Derived
     * from [state] inside the default controller. See
     * [EngineState] and `scratch/audio-fidelity-fixer/CONTRACT.md`.
     */
    val engineState: StateFlow<EngineState>

    /**
     * Issue #539 — truthful playback position in ms inside the current
     * chapter, advanced by the AudioTrack frame counter rather than
     * wall-clock interpolation between sentence boundaries. Drives the
     * Spotify-style scrubber thumb directly; tap-to-seek round-trips
     * through this number so visual ↔ audio stay synced.
     *
     * Falls back to a charOffset-based estimate when no AudioTrack is
     * live (paused, seek-in-flight, chapter just loaded). 0 when no
     * chapter is loaded.
     */
    val playbackPositionMs: StateFlow<Long>

    /**
     * Issue #539 — chapter duration estimate in ms. Mirrors
     * [PlaybackState.durationEstimateMs] so the sibling UI doesn't have to
     * project through the full state object to draw the scrubber rail.
     */
    val chapterDurationMs: StateFlow<Long>

    /** Issue #189 — recap-aloud TTS pipeline state. Idle until [speakText]
     *  is called; flips to Speaking while the one-shot utterance is playing,
     *  back to Idle when it finishes naturally or [stopSpeaking] is called. */
    val recapPlayback: StateFlow<RecapPlaybackState>

    /**
     * "Why are we waiting?" — the magical user-facing diagnostic that
     * answers the core question "is audio actually coming out of the
     * speakers, and if not, why?". Null when audio is genuinely flowing
     * (or playback is idle/paused/errored — those have dedicated UI
     * surfaces). Non-null with a typed [WaitReason] otherwise.
     *
     * Fed by [AudioOutputMonitor], which polls the truthful audio
     * position from EnginePlayer's AudioTrack head-position counter +
     * cross-references audio focus state, device volume, and route
     * changes. See WaitReason.kt for the variant catalogue.
     */
    val waitReason: StateFlow<WaitReason?>

    suspend fun play(fictionId: String, chapterId: String, charOffset: Int = 0)
    fun pause()
    fun resume()
    fun togglePlayPause()
    fun seekTo(charOffset: Int)
    /**
     * Issue #531 — seek by position-in-chapter (milliseconds). Mirror of
     * [seekTo] for the seekbar tap path: UI thinks in ms, the engine
     * indexes in chars. Converted via the speed-aware
     * [SPEED_BASELINE_CHARS_PER_SECOND] so a tap at 50 % of the rail lands
     * at the audio-time 50 % matches.
     */
    fun seekToPositionMs(positionMs: Long)
    fun skipForward30s()
    fun skipBack30s()
    /**
     * Issue #543 — fire-and-forget engine pre-warm. Safe to call when the
     * Library tab opens so the first Listen tap doesn't hit a 5-10 s
     * voice-load. Idempotent; no-op when a chapter is already playing.
     * Implementation may schedule a no-text loadModel on the active voice
     * — this is a best-effort hook; failure is logged but invisible.
     */
    fun prewarmEngine()
    /** #120 — step to the next sentence boundary. No-op when already
     *  on the last sentence of the chapter. */
    fun nextSentence()
    /** #120 — step to the previous sentence boundary. No-op when
     *  already on sentence 0. */
    fun previousSentence()
    suspend fun nextChapter()
    suspend fun previousChapter()
    suspend fun jumpToChapter(chapterId: String)

    fun setSpeed(speed: Float)
    fun setPitch(pitch: Float)
    /** Issue #90 — see [in.jphe.storyvox.playback.tts.EnginePlayer.setPunctuationPauseMultiplier]. */
    fun setPunctuationPauseMultiplier(multiplier: Float)

    fun startSleepTimer(mode: SleepTimerMode)
    fun cancelSleepTimer()
    fun toggleSleepTimer()

    /** Issue #150 — push the user's shake-to-extend setting into the
     *  state stream so [StoryvoxPlaybackService] can gate sensor
     *  registration on it without taking a feature-module dep. */
    fun setShakeToExtendEnabled(enabled: Boolean)

    /** Issue #189 — synthesize and play [text] as a one-shot utterance via
     *  the active voice. Used by the chapter-recap modal to read the
     *  AI-generated recap aloud. The caller is responsible for pausing
     *  active fiction playback before calling — the engine is shared and
     *  overlapping audio would muddy the listener experience. */
    suspend fun speakText(text: String)

    /** Issue #189 — cancel an in-flight recap-aloud utterance. Idempotent. */
    fun stopSpeaking()

    /**
     * Issue #290 — point-in-time snapshot of the active player's
     * producer queue + AudioTrack buffer state. Returns zeros when no
     * player is bound. Read by the Debug overlay at 1Hz.
     */
    fun bufferTelemetry(): BufferTelemetry

    /**
     * Issue #121 — bookmark the current playback position into the
     * currently-loaded chapter. No-op when no chapter is loaded. The
     * bookmark is persisted to the chapter row so it survives app
     * restarts and the playhead moving past it.
     */
    suspend fun bookmarkHere()

    /**
     * Issue #121 — clear the currently-loaded chapter's bookmark, if
     * any. No-op when no chapter is loaded or no bookmark exists.
     */
    suspend fun clearBookmark()

    /**
     * Issue #121 — seek to the currently-loaded chapter's bookmark. No-op
     * when no chapter is loaded or no bookmark exists. Returns true if
     * the seek fired, false otherwise — callers can use this to surface
     * "no bookmark to jump to" feedback.
     */
    suspend fun jumpToBookmark(): Boolean
}

/**
 * The single source of truth for playback. Wires together:
 * - the [EnginePlayer] (which actually speaks — VoxSherpa engine path),
 * - the chapter repository (to fetch text and resolve next/prev),
 * - the position repository (to persist resume points),
 * - the sleep timer.
 *
 * Service-bound: lives in [SingletonComponent] so all callers share state.
 * The [EnginePlayer] is created and lifecycle-managed by [StoryvoxPlaybackService] —
 * the service injects this controller and calls [bindPlayer] / [unbindPlayer]
 * during its lifecycle. Until a player is bound, transport calls are no-ops.
 */
@Singleton
class DefaultPlaybackController @Inject constructor(
    private val chunker: SentenceChunker,
    private val sleepTimer: SleepTimer,
    /** #90 — write the user's last play/pause intent so the
     *  Library Resume CTA can decide whether to auto-start. */
    private val resumePolicy: `in`.jphe.storyvox.data.repository.playback.PlaybackResumePolicyConfig,
    /** #121 — per-chapter bookmark read/write. Controller persists +
     *  jumps to bookmark positions; the player layer needs no
     *  awareness of bookmarks (they're a chapter-metadata concern). */
    private val chapterRepo: `in`.jphe.storyvox.data.repository.ChapterRepository,
    /** "Why are we waiting?" — Lazy break of the Singleton-graph cycle
     *  (monitor depends on controller, controller exposes monitor's
     *  flow). Resolved once on first [waitReason] access. */
    private val audioOutputMonitor: dagger.Lazy<AudioOutputMonitor>,
    /** Issues #593 / #594 — user-tunable skip distance + rewind-to-start
     *  threshold. Snapshot-read inside [skipForward30s] / [skipBack30s] /
     *  [previousChapter]. Lazy break: we don't need it at construction
     *  time, and a snapshot read is fine because skip taps are user-
     *  driven and inherently bursty (not a hot loop). */
    private val skipConfig: `in`.jphe.storyvox.data.repository.playback.PlaybackSkipConfig,
) : PlaybackController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<PlaybackUiEvent>(extraBufferCapacity = 8)
    override val events: SharedFlow<PlaybackUiEvent> = _events.asSharedFlow()

    /**
     * #543 — most-recent "we expect first audio shortly" hint. Set true
     * when [play] is invoked; cleared when [PlaybackState.currentSentenceRange]
     * starts emitting (i.e. the producer pushed the first sentence). Read
     * by [engineState] to decide whether to surface [EngineState.Warming].
     */
    private val _warmingUp = MutableStateFlow(false)

    /**
     * #543 — message rendered alongside [EngineState.Warming]. Updated by
     * [play] with the active voice's display label so the UI shows
     * "Warming Brian…" rather than a generic "Loading…".
     */
    private val _warmingMessage = MutableStateFlow("Warming voice…")

    /**
     * #524 — completion latch set when the producer reports book-finished
     * (no next chapter). Surfaces as [EngineState.Completed] so the UI can
     * show an end-of-book card instead of the chapter-finished overlay
     * spinning on a non-existent successor.
     */
    private val _completed = MutableStateFlow(false)

    /**
     * #539 — truthful audio-position feed from [EnginePlayer.bytesPlayed].
     * Mirrors what the user actually hears, not what we hope is playing.
     */
    private val _playbackPositionMs = MutableStateFlow(0L)
    override val playbackPositionMs: StateFlow<Long> = _playbackPositionMs.asStateFlow()

    override val chapterDurationMs: StateFlow<Long> = _state
        .map { it.durationEstimateMs }
        .stateIn(scope, SharingStarted.Eagerly, 0L)

    /**
     * #524 / #530 / #536 / #543 — derived UI status. Recomputed on every
     * underlying signal change so the sibling reader UI only has to
     * `engineState.collect { … }` and route on cases. Order of precedence
     * is intentional:
     *  1. `error != null` → [EngineState.Error] (winner; surfaces dropped
     *     chapter loads even while paused).
     *  2. `completed=true` → [EngineState.Completed] (end-of-book).
     *  3. `warmingUp=true && isPlaying` → [EngineState.Warming] (voice
     *     loading or first-sentence not yet produced).
     *  4. `isBuffering=true && isPlaying` → [EngineState.Buffering].
     *  5. `isPlaying=true` → [EngineState.Playing].
     *  6. `currentChapterId != null` → [EngineState.Paused] (loaded but
     *     not running).
     *  7. otherwise → [EngineState.Idle].
     */
    override val engineState: StateFlow<EngineState> =
        kotlinx.coroutines.flow.combine(
            _state,
            _warmingUp,
            _warmingMessage,
            _completed,
        ) { s, warming, msg, completed ->
            val err = s.error
            when {
                err != null -> EngineState.Error(errorMessage(err), retryable = isRetryable(err))
                completed -> EngineState.Completed
                warming && s.isPlaying -> EngineState.Warming(msg)
                s.isBuffering && s.isPlaying -> EngineState.Buffering
                s.isPlaying -> EngineState.Playing
                s.currentChapterId != null -> EngineState.Paused
                else -> EngineState.Idle
            }
        }.stateIn(scope, SharingStarted.Eagerly, EngineState.Idle)

    private var player: EnginePlayer? = null

    /** Issue #189 — mirror of the bound player's recap-aloud state. Idle
     *  before any player binds (and between bindings); reflects the
     *  active player's flow while bound. */
    private val _recapPlayback = MutableStateFlow(RecapPlaybackState.Idle)
    override val recapPlayback: StateFlow<RecapPlaybackState> = _recapPlayback.asStateFlow()

    /** "Why are we waiting?" — exposed via the monitor singleton. The
     *  Lazy resolves on first access; consumers see a permanent
     *  StateFlow that the monitor writes into. */
    override val waitReason: StateFlow<WaitReason?> by lazy {
        audioOutputMonitor.get().waitReason
    }

    /**
     * Issues #593 / #594 — hot-cached snapshot of the user's skip
     * distance + rewind-to-start threshold. Updated on every Flow
     * emission below; read at skip-tap time without an extra suspend.
     * Defaults match the seed UiSettings defaults (30s / 3s) so an
     * early skip tap before the Flow emits still does the right thing.
     */
    @Volatile private var cachedSkipDistanceSec: Int = 30
    @Volatile private var cachedRewindToStartThresholdSec: Int = 3

    init {
        scope.launch {
            sleepTimer.remainingMs.collect { remaining ->
                _state.value = _state.value.copy(sleepTimerRemainingMs = remaining)
            }
        }
        // #593 / #594 — keep the cached snapshots fresh so skip taps
        // pick up the user's pref the moment they move the chip.
        scope.launch {
            skipConfig.skipDistanceSec.collect { v -> cachedSkipDistanceSec = v }
        }
        scope.launch {
            skipConfig.rewindToStartThresholdSec.collect { v ->
                cachedRewindToStartThresholdSec = v
            }
        }
    }

    fun bindPlayer(p: EnginePlayer) {
        player = p
        scope.launch {
            p.observableState.collect { update ->
                val prev = _state.value
                // #543 — clear the warmup latch the moment the producer
                // emits its first sentence range. Independent of
                // `isPlaying` (which flipped true at play() time before
                // the engine had a chance to render anything).
                if (update.currentSentenceRange != null && _warmingUp.value) {
                    _warmingUp.value = false
                }
                // #530 — when a chapter load errors out, the engine
                // leaves currentChapterId pointing at the failed id +
                // isPlaying=false. The default Spotify-style behavior is
                // to surface the failure prominently (handled by
                // EngineState.Error mapping in engineState) AND not let a
                // subsequent play() reuse the stale chapter id without an
                // explicit retry. Don't clear the id here — the sibling
                // UI uses it to render "Couldn't play '$chapterTitle'."
                _state.value = update.copy(sleepTimerRemainingMs = sleepTimer.remainingMs.value)
                // #524 — if the engine reports BookFinished via the events
                // channel (collected below), the listener pipeline goes idle.
                // We additionally watch for an authoritative isPlaying=false
                // with currentChapterId still set + no error: that's the
                // natural-end signal when the chapter was the last one. The
                // _completed latch flips back to false on the next play().
                if (
                    prev.isPlaying && !update.isPlaying &&
                    update.error == null &&
                    update.currentChapterId != null &&
                    _completed.value.not()
                ) {
                    // Don't auto-set Completed here — the engine's own
                    // BookFinished event below is the canonical source.
                    // Keeping this branch as a documentation anchor for
                    // why we don't.
                }
            }
        }
        scope.launch {
            p.recapPlayback.collect { _recapPlayback.value = it }
        }
        // #524 — listen for the engine's BookFinished signal. When the
        // last chapter ends naturally and there's no next chapter,
        // advanceChapter emits BookFinished + does NOT load a new chapter,
        // leaving isPlaying=false. Flip the Completed latch so engineState
        // emits Completed; the next play() clears it.
        scope.launch {
            p.uiEvents.collect { ev ->
                if (ev is PlaybackUiEvent.BookFinished) {
                    _completed.value = true
                }
            }
        }
        // #539 — poll the engine for the truthful audio position. Polls
        // every 100 ms while a chapter is loaded; lower than that and the
        // scrubber jitters from the AudioTrack frame-counter granularity
        // (sherpa-onnx is 24 kHz, so 1 ms = 24 frames; the poll cadence
        // dominates jitter, not the counter). Idle when no chapter
        // loaded — we skip emissions equal to the last value so a paused
        // chapter doesn't burn CPU. Bound to the controller's scope so
        // it dies with bindPlayer/unbindPlayer pairs.
        scope.launch {
            while (true) {
                val live = player ?: break
                val pos = live.currentPositionMs()
                if (pos != _playbackPositionMs.value) {
                    _playbackPositionMs.value = pos
                }
                kotlinx.coroutines.delay(100L)
            }
        }
        // Calliope (v0.5.00) — bridge the player's internal uiEvents
        // SharedFlow into the controller's public `events` flow. Pre-
        // Calliope this bridge was missing entirely: BookFinished,
        // ChapterChanged, EngineMissing, and AzureFellBack fired
        // inside EnginePlayer but never reached any external observer
        // because nothing collected `p.uiEvents`. The :app debug
        // surface read `controller.events` directly and silently
        // received nothing — only this commit's confetti
        // requirement caught it. New event consumers should rely on
        // this bridge being live for the lifetime of a player binding.
        scope.launch {
            p.uiEvents.collect { _events.tryEmit(it) }
        }
        // Issue #553 — buffering-stuck watchdog. The EnginePlayer's own
        // auto-advance from handleChapterDone → advanceChapter now has
        // a 30 s timeout on the chapter-body wait + per-step
        // runCatching wrappers, so the "isBuffering=true forever"
        // symptom from the audit should be impossible by construction.
        // This watchdog is belt-and-suspenders: if the underlying
        // engine flow somehow gets stuck on isBuffering for a full 8 s
        // (a chapter-boundary spinner normally clears within <1 s on
        // LAN), fire ONE retry through the same path the manual skip-
        // next button uses. The retry sets `currentChapterId` to the
        // new chapter via `loadAndPlay` and the buffering flag
        // naturally clears.
        //
        // 8 s threshold: longer than the longest plausible legitimate
        // buffer spinner (a slow N+1 download), short enough that the
        // user doesn't notice. One retry only — if it ALSO stalls
        // (e.g. WiFi truly down), the EnginePlayer's own 30 s timeout
        // will surface a typed error and the user can intervene.
        scope.launch { runBufferingWatchdog(p) }
    }

    /**
     * Issue #553 — buffering-stuck watchdog. See [bindPlayer] for the
     * rationale. Collects [PlaybackState] and arms a 8-second timer
     * whenever `isBuffering` flips on with a non-null chapter id; if
     * the same (chapter id, isBuffering) pair holds when the timer
     * fires, kick `nextChapter()` once to drive the same path the
     * manual skip button uses. State changes (chapter advances, error
     * surfaces, user pauses) cancel the timer naturally.
     *
     * Lives in its own method so the watchdog logic is auditable +
     * unit-testable in isolation. Bound to controller scope; dies on
     * unbindPlayer along with the rest of the bind-launches.
     */
    private suspend fun runBufferingWatchdog(p: EnginePlayer) {
        var watchdogJob: kotlinx.coroutines.Job? = null
        // Issue #640 — two-tier watchdog. Distinguishes intra-chapter
        // underrun pauses (false-positive) from genuinely stuck states
        // (legitimate fire) by the presence of `currentSentenceRange`,
        // and runs each on its own threshold.
        //
        // Before this disambiguation, the consumer's catch-up-pause branch
        // ([EnginePlayer] line ~2161, the
        // `bufferHeadroomMs < BUFFER_UNDERRUN_THRESHOLD_MS` path) set
        // `isBuffering = true` mid-chapter without clearing
        // `currentSentenceRange`. On Piper-low / Z Flip 3 (sub-realtime
        // synth), that underrun pause held for >1.5 s as the producer
        // caught up — the watchdog fired in the middle of chapter 1,
        // called `advanceChapter(1)`, which interrupted the still-running
        // consumer mid-write, and stranded chapter 2's pipeline
        // half-constructed. The user saw "Reconnecting — please wait"
        // (AudioOutputStuck) with audio focus held but no PCM moving.
        //
        // The two legitimate "buffering" states are now disambiguated by
        // `currentSentenceRange`:
        //
        //   - **Chapter-transition buffering** (`currentSentenceRange == null`):
        //     set by handleChapterDone / advanceChapter / loadAndPlay
        //     before the new pipeline emits its first sentence. This IS
        //     the case the watchdog is designed for — natural-end advance
        //     failed to land, fire a fallback advance. Fast threshold
        //     (`BUFFERING_STUCK_WATCHDOG_MS` = 1.5 s, original behaviour)
        //     so the gap between chapters stays imperceptible.
        //
        //   - **Intra-chapter underrun / end-of-chapter without natural-end**
        //     (`currentSentenceRange != null`): consumer is parked on the
        //     bufferHeadroom flow with the last-played sentence range
        //     still in state. On healthy CPU the producer catches up
        //     within a few hundred ms; on Piper-low / slow CPU it can
        //     hold for several seconds; in the worst case (the natural-
        //     end branch silently failing to fire on Notion sources)
        //     it holds forever. Slow threshold
        //     (`BUFFERING_STUCK_WATCHDOG_END_OF_CHAPTER_MS` = 12 s) gives
        //     legitimate underruns ample time to resolve naturally, then
        //     kicks the same fallback advance for the truly-stuck case.
        //
        // Triple is `(transitionBuffering, endOfChapterBuffering, chapterId)`.
        // distinctUntilChanged emits on any transition; the collect
        // cancels the prior watchdog and arms a new one with the right
        // threshold for whichever state we landed in.
        p.observableState
            .map {
                val buffering = it.isBuffering && it.currentChapterId != null
                val transition = buffering && it.currentSentenceRange == null
                val endOfChapter = buffering && it.currentSentenceRange != null
                Triple(transition, endOfChapter, it.currentChapterId)
            }
            .distinctUntilChanged()
            .collect { (transition, endOfChapter, chapterId) ->
                watchdogJob?.cancel()
                watchdogJob = null
                if ((!transition && !endOfChapter) || chapterId == null) return@collect
                val armedFor = chapterId
                val threshold = if (transition) {
                    BUFFERING_STUCK_WATCHDOG_MS
                } else {
                    BUFFERING_STUCK_WATCHDOG_END_OF_CHAPTER_MS
                }
                watchdogJob = scope.launch {
                    kotlinx.coroutines.delay(threshold)
                    val nowState = p.observableState.value
                    // Only fire if we're STILL buffering AND still on
                    // the same chapter id (i.e. nothing else has
                    // happened — no advance, no pause, no error).
                    if (
                        nowState.isBuffering &&
                        nowState.currentChapterId == armedFor &&
                        nowState.error == null
                    ) {
                        // Issue #726 — mirror the in-flight direction
                        // instead of hardcoding +1. Pre-fix a slow
                        // Previous-chapter tap (advanceChapter(-1)
                        // parked on the body-wait flow.first()) was
                        // recovered with advanceChapter(+1), turning
                        // Previous into a forward two-skip. The pure
                        // resolver on the companion handles the latch
                        // semantics (mirror when set, fall back to +1
                        // for the original #553 case where the engine
                        // is stuck without an active advance call).
                        val watchdogDir = watchdogRecoveryDirection(
                            p.inFlightAdvanceDirection,
                        )
                        android.util.Log.w(
                            "PlaybackController",
                            "#553/#726 watchdog: isBuffering stuck on $armedFor for " +
                                "${threshold}ms; firing fallback advance " +
                                "direction=$watchdogDir (in-flight=${p.inFlightAdvanceDirection})",
                        )
                        // #553 follow-up — Media3's Player object is
                        // thread-confined to the application's Main
                        // looper; calling advanceChapter from the
                        // controller's Default-dispatcher scope threw
                        // "Player is accessed on the wrong thread"
                        // (observed on R83W80CAFZB). Hop to Main before
                        // dispatching the fallback advance — that's the
                        // same dispatcher the manual skip-next button
                        // uses via ReaderViewModel.viewModelScope.
                        runCatching {
                            kotlinx.coroutines.withContext(Dispatchers.Main) {
                                p.advanceChapter(direction = watchdogDir)
                            }
                        }.onFailure { t ->
                            // Issue #567 (stuck-state-fixer) — filter
                            // CancellationException. The watchdog's
                            // parent coroutine is intentionally cancelled
                            // when the state transitions cleanly after
                            // the advance (chapter id flips so the
                            // outer collect re-arms), and that
                            // cancellation propagates into the inflight
                            // withContext. Logging it as ERROR pollutes
                            // logcat on every happy-path auto-advance.
                            // Real failures (network down, body wait
                            // timeout) surface as typed errors via the
                            // engine's own runCatching wrappers; this
                            // catch-all only ever catches the
                            // cooperative cancellation now.
                            if (t is kotlinx.coroutines.CancellationException) {
                                android.util.Log.d(
                                    "PlaybackController",
                                    "#553 watchdog advance cancelled cleanly (chapter transitioned)",
                                )
                            } else {
                                android.util.Log.e(
                                    "PlaybackController",
                                    "#553 watchdog fallback advance threw: ${t.message}",
                                    t,
                                )
                            }
                        }
                    }
                }
            }
    }

    fun unbindPlayer() {
        player = null
        _recapPlayback.value = RecapPlaybackState.Idle
        _playbackPositionMs.value = 0L
        _warmingUp.value = false
        _completed.value = false
    }

    /**
     * #543 — message rendered alongside [EngineState.Warming]. Renders the
     * voice's friendly label ("Warming Brian…") when available, otherwise
     * a generic fallback. We look at the most-recent state emission's
     * `voiceId` because the active voice flow lives in :feature/voice and
     * pulling a dep here is wrong-layer. The label uplift to
     * "Warming Brian…" specifically happens in the sibling :feature
     * agent — at this layer we only know the voice id string.
     */
    private fun warmingMessageForCurrentVoice(): String {
        val id = _state.value.voiceId
        // Heuristic — Brian, Lessac, etc. are typically `kokoro-en-US-brian`
        // or `piper-en-US-lessac-medium`. Pull out the human-ish segment.
        val label = id
            ?.substringAfterLast("-", missingDelimiterValue = "")
            ?.takeIf { it.isNotBlank() && it.length in 3..16 }
            ?.replaceFirstChar { it.uppercaseChar() }
            ?: "voice"
        return "Warming $label…"
    }

    /**
     * #530 — render-ready error copy. Today the engine has eight
     * [PlaybackError] subtypes; we collapse them to one message line for
     * the sibling UI. The UI's error band displays the message verbatim
     * and offers a Retry button when [isRetryable] returns true.
     */
    private fun errorMessage(err: PlaybackError): String = when (err) {
        is PlaybackError.ChapterFetchFailed -> err.message
        is PlaybackError.EngineUnavailable ->
            "Voice engine isn't installed yet. Open Voices to download one."
        is PlaybackError.TtsSpeakFailed ->
            "Voice failed mid-utterance (code ${err.errorCode}). Tap to retry."
        is PlaybackError.AzureAuthFailed ->
            "Azure subscription key was rejected. Paste a fresh key in Settings → Cloud voices."
        is PlaybackError.AzureThrottled -> err.message
        is PlaybackError.AzureNetworkUnavailable -> err.message
        is PlaybackError.AzureServerError -> err.message
    }

    /** #530 — which errors should surface a Retry button. */
    private fun isRetryable(err: PlaybackError): Boolean = when (err) {
        PlaybackError.AzureAuthFailed -> false // user has to re-paste key
        is PlaybackError.EngineUnavailable -> false // install required
        else -> true
    }

    override suspend fun play(fictionId: String, chapterId: String, charOffset: Int) {
        // #90 — record the play intent BEFORE loadAndPlay (which can take
        // 30+s to return on a cold engine load). If the user kills the
        // app mid-load we want resume-on-reopen to autoplay.
        scope.launch { runCatching { resumePolicy.setLastWasPlaying(true) } }
        // #543 — flip the warmup latch + render-ready message BEFORE
        // suspending into loadAndPlay. The sibling UI polls engineState;
        // we want Warming to surface within one frame of the play tap,
        // not 30 s later when sherpa-onnx finishes loading. Cleared in
        // the observableState collector when the first sentence range
        // surfaces.
        _completed.value = false
        _warmingMessage.value = warmingMessageForCurrentVoice()
        _warmingUp.value = true
        player?.loadAndPlay(fictionId, chapterId, charOffset)
    }

    override fun pause() {
        // #90 — explicit pause is a user intent. Persist so the next
        // Library Resume CTA respects it (load chapter, stay paused).
        scope.launch { runCatching { resumePolicy.setLastWasPlaying(false) } }
        // Route via the audio-stream-aware pauseRouted() (post-v0.5.36
        // radio-pause bug fix), not the TTS-only pauseTts() — radio
        // chapters need the ExoPlayer's playWhenReady=false branch.
        // (Not named `pause()` on EnginePlayer to avoid colliding with
        // the BasePlayer override.)
        player?.pauseRouted()
    }

    override fun resume() {
        scope.launch { runCatching { resumePolicy.setLastWasPlaying(true) } }
        player?.resume()
    }

    override fun togglePlayPause() {
        if (state.value.isPlaying) pause() else resume()
    }

    override fun seekTo(charOffset: Int) { player?.seekToCharOffset(charOffset) }

    override fun seekToPositionMs(positionMs: Long) {
        // #531 / #555 — UI gives us a ms position on the scrubber rail
        // (clamped to [0, chapterDuration] by the seekbar widget). Both
        // rail and position now live on the speed-invariant media-time
        // axis (see EnginePlayer.estimateDurationMs / currentPositionMs),
        // so the conversion uses the baseline rate without applying
        // speed: `charOffset = positionMs / 1000 * baseline`. A tap at
        // 50 % of the displayed rail lands at the 50 % char-offset
        // regardless of speed, AND the post-seek displayed position
        // matches the tap (no jump on a speed change because the axis
        // is invariant).
        //
        // Pre-fix this method (the legacy `seekTo(ms: Long)` in
        // RealPlaybackControllerUi) used a speed-aware formula. After
        // PR #552 the displayed rail was speed-aware too, so the seek
        // math was internally consistent — but a speed change mid-
        // chapter would silently relabel the axis, surfacing as the
        // ~19 s position jump #555 reported. The media-time axis kills
        // that whole class of glitch.
        val targetChar = ((positionMs.coerceAtLeast(0L) / 1000.0) *
            SPEED_BASELINE_CHARS_PER_SECOND).toInt()
        player?.seekToCharOffset(targetChar)
    }

    override fun skipForward30s() {
        // #593 — function name is preserved for binary-compat with
        // the MediaSession / widget call sites that wired against
        // `skipForward30s()` pre-#593. The "30s" in the name is now
        // a historical anchor: actual seek distance reads the user's
        // pref (10/15/30/45/60 chip, default 30s) via the
        // [PlaybackSkipConfig] snapshot below.
        //
        // #550 / #555 / #565 — seek by the configured number of
        // seconds on the media-time rail. Both position and duration
        // live on the speed-invariant axis now, so "N s of rail" =
        // `baseline * N` chars regardless of speed. At speed=2 the
        // user hears those N s of chapter content in N/2 wall-clock
        // seconds; the scrubber thumb jumps the same N s either way,
        // matching Spotify's "+N s" on a sped-up podcast (which is
        // "N s of media-time" not "N s of wall-clock").
        //
        // #565 — anchor the skip to the truthful audible position
        // (`playbackPositionMs`) rather than `state.charOffset`. See
        // the kdoc on the pre-#593 version for the slop-band
        // rationale.
        val seconds = cachedSkipDistanceSec.toFloat()
        val anchorMs = playbackPositionMs.value
        val anchorChars = positionMsToCharOffset(anchorMs, state.value.speed)
        val target = (anchorChars + skipDeltaChars(seconds, state.value.speed))
            .coerceAtLeast(0)
        player?.seekToCharOffset(target)
    }

    override fun skipBack30s() {
        // #593 — see [skipForward30s]. Function name preserved for
        // binary compat with MediaSession / widget call sites; actual
        // distance reads the user pref.
        val seconds = cachedSkipDistanceSec.toFloat()
        val anchorMs = playbackPositionMs.value
        val anchorChars = positionMsToCharOffset(anchorMs, state.value.speed)
        val target = (anchorChars - skipDeltaChars(seconds, state.value.speed))
            .coerceAtLeast(0)
        player?.seekToCharOffset(target)
    }

    override fun prewarmEngine() {
        // #543 — fire-and-forget hook. EnginePlayer is the right owner
        // (it knows the active voice + sherpa state); the controller
        // just plumbs the call so a UI mount can request a warm without
        // taking a core-playback dep on the voice manager directly. The
        // engine's implementation is best-effort: skipped if a chapter
        // is already playing, idempotent across repeated calls, no error
        // surfaces if the voice isn't installed.
        runCatching { player?.prewarmEngine() }
    }

    override fun nextSentence() { player?.seekSentence(direction = 1) }
    override fun previousSentence() { player?.seekSentence(direction = -1) }

    override suspend fun nextChapter() { player?.advanceChapter(direction = 1) }
    override suspend fun previousChapter() {
        // #285 / #594 — standard media-player UX, now user-tunable.
        // Pressing Previous past N seconds into the current chapter
        // rewinds to its start; pressing Previous *while within N
        // seconds of the start* jumps to the previous chapter.
        //
        // N comes from the user's [PlaybackSkipConfig.rewindToStartThresholdSec]
        // (chip-row in Settings → Voice & Playback: 1/3/5/10/Off).
        // Setting N = 0 disables rewind-to-start entirely: SkipPrevious
        // *always* jumps to the previous chapter. Useful for radio /
        // podcast users on short-chapter content who want fast prev-
        // track navigation. The Apple Music / Spotify default is 3s.
        //
        // Without this, a stray tap during chapter 2 silently dumps
        // the user back to chapter 1, with no confirm, no animation,
        // and lost reading position.
        val thresholdSec = cachedRewindToStartThresholdSec
        if (thresholdSec <= 0) {
            // Off — always go to the previous chapter.
            player?.advanceChapter(direction = -1)
            return
        }
        val s = state.value
        val charsPerSec = SPEED_BASELINE_CHARS_PER_SECOND * s.speed
        val rewindThresholdChars = (charsPerSec * thresholdSec).toInt()
        if (s.charOffset > rewindThresholdChars) {
            player?.seekToCharOffset(0)
        } else {
            player?.advanceChapter(direction = -1)
        }
    }
    override suspend fun jumpToChapter(chapterId: String) {
        val fictionId = state.value.currentFictionId ?: return
        play(fictionId, chapterId, charOffset = 0)
    }

    override fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 3.0f)
        player?.setSpeed(clamped)
    }

    override fun setPitch(pitch: Float) {
        val clamped = pitch.coerceIn(0.5f, 2.0f)
        player?.setPitch(clamped)
    }

    override fun setPunctuationPauseMultiplier(multiplier: Float) {
        player?.setPunctuationPauseMultiplier(multiplier)
    }

    override fun startSleepTimer(mode: SleepTimerMode) {
        sleepTimer.start(mode)
    }

    override fun cancelSleepTimer() {
        sleepTimer.cancel()
    }

    override fun toggleSleepTimer() {
        if (state.value.sleepTimerRemainingMs != null) cancelSleepTimer()
        else startSleepTimer(SleepTimerMode.Duration(15))
    }

    override fun setShakeToExtendEnabled(enabled: Boolean) {
        if (_state.value.shakeToExtendEnabled == enabled) return
        _state.value = _state.value.copy(shakeToExtendEnabled = enabled)
    }

    override suspend fun speakText(text: String) {
        player?.speak(text)
    }

    override fun stopSpeaking() {
        player?.stopSpeaking()
    }

    override fun bufferTelemetry(): BufferTelemetry =
        player?.bufferTelemetry() ?: BufferTelemetry()

    override suspend fun bookmarkHere() {
        val chapterId = state.value.currentChapterId ?: return
        val offset = state.value.charOffset
        chapterRepo.setChapterBookmark(chapterId, offset)
    }

    override suspend fun clearBookmark() {
        val chapterId = state.value.currentChapterId ?: return
        chapterRepo.setChapterBookmark(chapterId, null)
    }

    override suspend fun jumpToBookmark(): Boolean {
        val chapterId = state.value.currentChapterId ?: return false
        val offset = chapterRepo.chapterBookmark(chapterId) ?: return false
        player?.seekToCharOffset(offset)
        return true
    }

    internal fun emitEvent(event: PlaybackUiEvent) {
        _events.tryEmit(event)
    }

    companion object {
        /** Issue #285 / #594 — seconds-from-start threshold for the
         *  SkipPrevious rewind-to-start UX. **Default** value (now
         *  user-tunable via [PlaybackSkipConfig] / Settings → Voice &
         *  Playback). Past this point in a chapter, tapping
         *  SkipPrevious seeks to char 0 of the current chapter; under
         *  it goes to the previous chapter. 3 seconds is the de-facto
         *  standard across Apple Music, Spotify, Pocket Casts, and the
         *  Android MediaSession default behavior. Kept here as a
         *  default-anchor constant; the live value comes from
         *  `cachedRewindToStartThresholdSec` populated by the
         *  [skipConfig.rewindToStartThresholdSec] Flow. */
        internal const val REWIND_TO_START_THRESHOLD_SEC = 3f

        /** Issue #553 — buffering-stuck watchdog threshold for the
         *  **chapter-transition** path (sentence range cleared, new
         *  pipeline hasn't emitted yet). Kept fast (1.5 s) so the gap
         *  between chapters stays imperceptible — Spotify/Apple
         *  Music-grade pacing. Issue #640 added a structural gate on
         *  `currentSentenceRange == null` (see [runBufferingWatchdog])
         *  so the false-positive case (intra-chapter underrun) routes
         *  to [BUFFERING_STUCK_WATCHDOG_END_OF_CHAPTER_MS] instead. */
        internal const val BUFFERING_STUCK_WATCHDOG_MS = 1_500L

        /** Issue #640 — buffering-stuck watchdog threshold for the
         *  **intra-chapter / end-of-chapter** path (sentence range still
         *  set). Longer (12 s) because legitimate underrun pauses on
         *  slow CPUs (Piper-low / Z Flip 3) can hold for several
         *  seconds before the producer catches up, and firing a
         *  chapter advance mid-chapter is catastrophic — interrupts
         *  the still-running consumer, strands the new chapter's
         *  pipeline half-constructed, and leaves the user on
         *  "Reconnecting — please wait" with audio focus held but no
         *  PCM moving.
         *
         *  This path also covers the worst-case scenario where the
         *  consumer's natural-end branch silently fails to fire (the
         *  original #553 root cause): the producer has emitted every
         *  sentence but END_PILL never surfaced through `nextChunk()`
         *  on Notion sources for reasons that still resist
         *  reproduction in unit tests. After 12 s of stuck-with-
         *  sentence-range we conclude the chapter is genuinely over
         *  and fire the fallback advance — same recovery path the
         *  manual skip-next button uses. */
        internal const val BUFFERING_STUCK_WATCHDOG_END_OF_CHAPTER_MS = 12_000L

        /**
         * Issue #726 — pure-function resolver for the watchdog's
         * recovery direction. Mirrors the in-flight advance direction
         * when one is set (non-zero), falls back to +1 otherwise.
         *
         * The +1 fallback covers the original #553 case where the
         * engine is stuck on isBuffering without any active
         * `advanceChapter` call out (e.g. natural end-of-chapter never
         * fired END_PILL through the consumer). The user was listening
         * forward, so the natural recovery is forward.
         *
         * When the latch IS set (because the user tapped Previous or
         * Next and the body-wait is parked), this returns whichever
         * direction the user originally tapped — preserving the
         * "Previous never moves forward" invariant.
         *
         * Inputs are normalized to {-1, 0, +1} so callers can pass the
         * raw [EnginePlayer.inFlightAdvanceDirection] field without
         * worrying about stale values from earlier API revisions.
         */
        fun watchdogRecoveryDirection(inFlightDirection: Int): Int = when {
            inFlightDirection > 0 -> 1
            inFlightDirection < 0 -> -1
            else -> 1
        }

        /**
         * #531 / #550 — pure-function exports of the seek math so JVM unit
         * tests can verify the controller-side conversions without
         * needing an EnginePlayer (which requires sherpa-onnx AARs +
         * Android context).
         */

        /** #531 / #555 — convert a scrubber-rail position in ms to a
         *  chapter char offset. The rail lives on the speed-invariant
         *  media-time axis (see [in.jphe.storyvox.playback.tts.EnginePlayer.estimateDurationMs]),
         *  so the conversion is speed-independent. The [speed] parameter
         *  is preserved for binary-compat with existing tests but
         *  IGNORED — kept here so the contract is auditable.
         */
        @Suppress("UNUSED_PARAMETER")
        fun positionMsToCharOffset(positionMs: Long, speed: Float): Int {
            val charsPerSec = SPEED_BASELINE_CHARS_PER_SECOND
            return ((positionMs.coerceAtLeast(0L) / 1000.0) * charsPerSec).toInt()
        }

        /** #550 / #555 — convert "N s on the rail" to a char delta.
         *  Rail is media-time so the answer is speed-invariant. [speed]
         *  kept for binary-compat, IGNORED. */
        @Suppress("UNUSED_PARAMETER")
        fun skipDeltaChars(seconds: Float, speed: Float): Int {
            val charsPerSec = SPEED_BASELINE_CHARS_PER_SECOND
            return (charsPerSec * seconds).toInt()
        }
    }
}
