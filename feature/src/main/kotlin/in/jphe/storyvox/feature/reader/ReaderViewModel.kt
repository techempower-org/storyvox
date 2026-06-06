package `in`.jphe.storyvox.feature.reader

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.data.repository.ContinueListeningEntry
import `in`.jphe.storyvox.data.repository.PlaybackPositionRepository
import `in`.jphe.storyvox.data.repository.playback.PlaybackResumePolicyConfig
import `in`.jphe.storyvox.feature.api.FictionRepositoryUi
import `in`.jphe.storyvox.feature.api.PlaybackControllerUi
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import `in`.jphe.storyvox.feature.api.UiChapter
import `in`.jphe.storyvox.feature.api.UiPlaybackState
import `in`.jphe.storyvox.feature.api.UiRecapPlaybackState
import `in`.jphe.storyvox.feature.api.UiSleepTimerMode
import `in`.jphe.storyvox.playback.PlaybackUiEvent
import `in`.jphe.storyvox.llm.LlmError
import `in`.jphe.storyvox.llm.feature.ChapterRecap
import `in`.jphe.storyvox.ui.component.ReaderView
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class ReaderUiState(
    val playback: UiPlaybackState? = null,
    val chapterText: String = "",
    val activePane: ReaderView = ReaderView.Audiobook,
    /** Issue #278 — how long the player has been stuck in the "no chapter
     *  title + no chapter text" loading state. Drives the soft slow hint
     *  + the hard timeout/retry error path in [AudiobookView]. Reset to
     *  [LoadingPhase.NotLoading] as soon as either piece of state arrives. */
    val loadingPhase: LoadingPhase = LoadingPhase.NotLoading,
    /** Issue #418 — the magical-voice-icon quick sheet shows the live
     *  inter-sentence pause multiplier (#109) alongside speed/pitch so
     *  the user can tune cadence without leaving the player. The value is
     *  the global default from [SettingsRepositoryUi]; the engine reads
     *  it via [PlaybackControllerUi.setPunctuationPauseMultiplier]
     *  (effect lands on the next sentence boundary). */
    val punctuationPauseMultiplier: Float = 1f,
    /** Issue #418 — the magical-voice-icon quick sheet exposes the
     *  high-quality Sonic pitch-interpolation toggle (#193) so users
     *  on slow hardware can flip it off without diving into Settings.
     *  Default true matches the Settings default. */
    val pitchInterpolationHighQuality: Boolean = true,
    /**
     * "Why are we waiting?" — non-null whenever audio is NOT reaching
     * the speakers (warming, buffering, focus lost, device muted, route
     * change, stuck). The reader UI renders the brass diagnostic panel
     * above the cover based on this value. Sourced from
     * [PlaybackControllerUi.waitReason] which forwards
     * AudioOutputMonitor's flow.
     */
    val waitReason: `in`.jphe.storyvox.playback.diagnostics.WaitReason? = null,
    /**
     * Issue #677 — end-of-book latch. Set true when the engine emits
     * [PlaybackUiEvent.BookFinished] (the last chapter ended naturally
     * with no successor). Drives [HybridReaderScreen]'s
     * `BookFinishedOverlay` — pre-fix, the screen rendered nothing
     * after the final sentence drained and the scrubber sat stuck at
     * the end. Cleared via [ReaderViewModel.acknowledgeBookFinished]
     * (overlay dismissal / Back to Library / Browse more) or by a
     * fresh [ReaderViewModel.resume] / `startListening` call so a
     * "Restart from beginning" doesn't immediately re-trip the modal.
     */
    val bookFinished: Boolean = false,
    /**
     * Issue #805 — typed playback error from [EngineState.Error]. Non-null
     * when the engine is in an error state; the reader surfaces a
     * dismissible banner with an error-type-specific message and
     * recovery action (retry, sign-in, etc.). Null when no error.
     */
    val playbackError: `in`.jphe.storyvox.playback.EngineState.Error? = null,
)

/** Issue #278 — the player loading screen used to be a silent eternity:
 *  if the chapter fetch or voice-model load hung, the user stared at
 *  "Conjuring your chapter…" forever with no retry, no error, no escape.
 *
 *  This sealed surface tracks progress through the loading window so
 *  [AudiobookView] can layer in:
 *   - a soft "Still working… (slow voice / network)" hint at 10s, and
 *   - a hard error block with Retry / Pick a different voice / Cancel
 *     at 30s.
 *
 *  Once chapter text or a chapter title arrives, the phase snaps back to
 *  [NotLoading] regardless of where we were on the timer. */
enum class LoadingPhase {
    /** Player has chapter title / chapter text — not loading. */
    NotLoading,
    /** First 10s of a fresh load — show the regular sigil + copy. */
    Loading,
    /** 10-30s into a load — show a "Still working…" secondary line so
     *  the user knows we haven't deadlocked. */
    Slow,
    /** 30s+ — surface a friendly error block with Retry / Pick voice /
     *  Cancel. The loading flow itself isn't cancelled; we just give the
     *  user a way out. */
    TimedOut,
}

/** UI state for the Chapter Recap modal. Sealed because the
 *  states are mutually exclusive — at any given moment the modal is
 *  either closed, asking, streaming, done, or in error. */
@Immutable
sealed class RecapUiState {
    /** Modal not visible. */
    object Hidden : RecapUiState()

    /** Modal opened, waiting for the first token from the LLM. */
    object Loading : RecapUiState()

    /** First token arrived; partial response builds up. */
    data class Streaming(val text: String) : RecapUiState()

    /** Stream completed successfully. */
    data class Done(val text: String) : RecapUiState()

    /** Stream failed. The UI shows [message] + the appropriate
     *  recovery action (Settings link, Try again, etc.). */
    data class Error(
        val message: String,
        val kind: ErrorKind,
    ) : RecapUiState()

    enum class ErrorKind {
        /** AI not configured, or "Send chapter text to AI" toggle off
         *  → route to Settings. */
        NotConfigured,
        /** Provider key invalid → route to Settings, flag the field. */
        AuthFailed,
        /** Network/transport — recoverable. */
        Transport,
        /** Provider returned a non-auth 4xx/5xx. */
        ProviderError,
    }
}

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val playback: PlaybackControllerUi,
    private val settings: SettingsRepositoryUi,
    private val chapterRecap: ChapterRecap,
    /**
     * Source for the Playing-tab Resume prompt (#? Lyra). Same flow
     * LibraryViewModel reads for its "Continue listening" tile — keeps
     * the two surfaces visually in lock-step (same fiction, same chapter,
     * same updatedAt).
     */
    private val positionRepo: PlaybackPositionRepository,
    /**
     * #90 — the smart-resume policy. When the user explicitly paused
     * before, the Resume CTA should load-but-not-auto-play. When the app
     * was killed mid-playback (no explicit pause), the flag stays true
     * and Resume auto-plays. Identical wiring to
     * [`in`.jphe.storyvox.feature.library.LibraryViewModel.resume].
     */
    private val resumePolicy: PlaybackResumePolicyConfig,
    private val fictionRepo: FictionRepositoryUi,
    savedState: SavedStateHandle,
) : ViewModel() {

    /**
     * Issue #638 (v1.0 blocker) — distinguishes the "Reader screen
     * cold-launched from FictionDetail with explicit chapter args"
     * path from the "Playing tab cold-launch with no recent activity"
     * path. Both share [HybridReaderScreen], but only the second
     * should fall back to [ResumeEmptyPrompt] when `playback == null`.
     *
     * The Reader route is `/reader/{fictionId}/{chapterId}` — when
     * navigated to from FictionDetail's Play button, both args land
     * in SavedStateHandle, [hasExplicitChapterArgs] is true, and the
     * screen renders a loading state until the playback flow flips
     * non-null (which happens after `startListening` finishes the
     * chapter-download wait inside the controller). The Playing
     * route is `/playing` — no args, [hasExplicitChapterArgs] is
     * false, and the empty Resume prompt fires as before.
     *
     * Pre-fix the Reader screen unconditionally fired the empty
     * prompt on `playback == null`, which is the state-flow's
     * initial emission. The user tapped Play on FictionDetail, was
     * routed to /reader, the playback flow hadn't emitted yet, and
     * the bottom-dock-anchored "Your library awaits / Browse the
     * realms" empty state appeared, defeating PR #633's
     * discoverability fix. See feature/.../HybridReaderScreen.kt
     * for how this flag gates the prompt branch.
     */
    val hasExplicitChapterArgs: Boolean = run {
        val f = savedState.get<String>("fictionId")
        val c = savedState.get<String>("chapterId")
        !f.isNullOrBlank() && !c.isNullOrBlank()
    }

    private val _activePane = MutableStateFlow(ReaderView.Audiobook)
    private val _recap = MutableStateFlow<RecapUiState>(RecapUiState.Hidden)

    /** Recap modal state. Reader UI collects this and renders the
     *  modal when not [RecapUiState.Hidden]. */
    val recap: StateFlow<RecapUiState> = _recap.asStateFlow()

    /** Issue #189 — recap-aloud TTS pipeline state, surfaced from the
     *  PlaybackController so the modal's Read-aloud button can render the
     *  right play/pause icon. The chapter-recap modal collects this
     *  alongside [recap] (the modal-content state) — they're independent
     *  axes: the modal can be Done while the audio is Idle (button shows
     *  Play), or Done while Speaking (button shows Pause). */
    val recapPlayback: StateFlow<UiRecapPlaybackState> = playback.recapPlayback
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiRecapPlaybackState.Idle)

    /** The currently in-flight recap stream, or null when no recap is
     *  running. Cancelling this Job cancels the underlying OkHttp
     *  Call (TCP RST to the provider; they stop generating; we stop
     *  billing). */
    private var recapJob: Job? = null

    /** Issue #278 — derive a `isLoading` boolean directly from the
     *  playback state so we can drive a timer off it. "Loading" matches
     *  the same condition AudiobookView already uses for the brass
     *  spinner: no chapter title yet AND no chapter text yet. As soon as
     *  either arrives the flow flips false and the timer below resets. */
    private val isLoading: StateFlow<Boolean> = combine(
        playback.state,
        playback.chapterText,
    ) { state, text ->
        state.chapterTitle.isBlank() && text.isBlank()
    }.distinctUntilChanged().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), true,
    )

    /** Issue #278 — phase tracker. When the loading edge flips true we
     *  fire a coroutine that walks Loading → Slow (10s) → TimedOut (30s).
     *  When it flips false we cancel the timer and reset to NotLoading.
     *
     *  The thresholds match the values called out in the issue body:
     *  10s for the soft slow hint, 30s for the hard error path. They're
     *  intentionally non-private so the unit test can pin them. */
    private val _loadingPhase = MutableStateFlow(LoadingPhase.Loading)
    private var loadingTimerJob: Job? = null

    /**
     * Calliope (v0.5.00) — one-shot signal for the chapter-complete
     * confetti easter-egg. Conflated capacity 1 so a missed observer
     * (e.g. mid-config-change) sees the latest signal on next collect
     * but can't accumulate a backlog of celebrations. The collector
     * in [HybridReaderScreen] mounts a [MilestoneConfetti] overlay
     * for each Unit it pulls off the channel.
     *
     * Fires when ALL of these are true on a [PlaybackUiEvent.ChapterDone]:
     *  - build qualifies (VERSION_NAME ≥ 0.5.00),
     *  - the confetti flag is still false (one-time gate),
     *  - the event is a natural ChapterDone, not a ChapterChanged
     *    (the engine emits ChapterDone before ChapterChanged on the
     *    auto-advance path; manual nav skips ChapterDone).
     *
     * The flag flip-to-true happens from the UI side via
     * `markMilestoneConfettiShown()` when the overlay finishes
     * fading — keeps the side-effect path serialized through the
     * SettingsRepositoryUi seam rather than racing on a VM-local
     * boolean.
     */
    private val _confettiTrigger = Channel<Unit>(capacity = Channel.CONFLATED)
    val confettiTrigger: Flow<Unit> = _confettiTrigger.receiveAsFlow()

    /** Issue #805 — user-dismissed error message. When the user dismisses
     *  the playback error banner, we record the error message here. The
     *  combine below suppresses the banner when the current error message
     *  matches the dismissed one. A new/different error re-arms the banner
     *  automatically (different message = cleared latch). */
    private val _dismissedErrorMessage = MutableStateFlow<String?>(null)

    /** Issue #677 — end-of-book latch backing the
     *  [ReaderUiState.bookFinished] projection. Engine-driven set in the
     *  [PlaybackUiEvent.BookFinished] arm of the player-events collector;
     *  cleared by [acknowledgeBookFinished] when the user dismisses the
     *  overlay, and by [resume] before starting a fresh listen so a
     *  "Restart from beginning" doesn't immediately re-trip the modal. */
    private val _bookFinished = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            isLoading.collect { loading ->
                loadingTimerJob?.cancel()
                if (!loading) {
                    _loadingPhase.value = LoadingPhase.NotLoading
                    return@collect
                }
                _loadingPhase.value = LoadingPhase.Loading
                loadingTimerJob = launch {
                    delay(LOADING_SLOW_HINT_MS)
                    _loadingPhase.value = LoadingPhase.Slow
                    delay(LOADING_TIMEOUT_MS - LOADING_SLOW_HINT_MS)
                    _loadingPhase.value = LoadingPhase.TimedOut
                }
            }
        }
        // Calliope (v0.5.00) — observe player events for the
        // one-time confetti trigger. We re-check the persisted flag
        // on each ChapterDone (not just at init) so a celebration
        // fired in a different process / install state doesn't
        // double-fire on a quick chapter-complete after reopening.
        viewModelScope.launch {
            playback.events.collect { ev ->
                when (ev) {
                    is PlaybackUiEvent.ChapterDone -> {
                        val ms = settings.milestoneState.first()
                        if (ms.qualifies && !ms.confettiShown) {
                            _confettiTrigger.trySend(Unit)
                        }
                    }
                    // Issue #677 — engine signaled the last chapter ended
                    // naturally with no successor. Flip the end-of-book latch
                    // so HybridReaderScreen renders the BookFinishedOverlay.
                    is PlaybackUiEvent.BookFinished -> {
                        _bookFinished.value = true
                    }
                    else -> Unit
                }
            }
        }
    }

    /** Calliope — called by [HybridReaderScreen] when the confetti
     *  overlay fades out, so the one-time flag flips and the
     *  celebration never replays. The collector branch above also
     *  reads the flag on every ChapterDone, so this method
     *  effectively closes the gate. */
    fun markConfettiShown() {
        viewModelScope.launch { settings.markMilestoneConfettiShown() }
    }

    /** Issue #677 — user dismissed the end-of-book overlay (Back to
     *  Library, Browse more, or backdrop tap). Clear the latch so the
     *  modal doesn't re-trip the next time the screen recomposes. */
    fun acknowledgeBookFinished() {
        _bookFinished.value = false
    }

    /** Issue #805 — user swiped away / tapped X on the playback error
     *  banner. Records the dismissed message so the combine suppresses
     *  the same error. A new/different error re-arms the banner. */
    fun dismissPlaybackError() {
        val current = uiState.value.playbackError
        _dismissedErrorMessage.value = current?.message
    }

    val uiState: StateFlow<ReaderUiState> = combine(
        playback.state,
        playback.chapterText,
        _activePane,
        _loadingPhase,
        // Issue #418 — read the live pause-multiplier + Sonic high-quality
        // flag from SettingsRepositoryUi so the voice quick-sheet renders
        // the same values the Settings screen would, without owning a
        // separate persistence path. Both knobs are global (not per-voice)
        // today, so projecting them onto the per-screen UI state stays
        // sound — when the user flips one in the sheet, the
        // SettingsRepositoryUi flow updates and the next emission flows
        // straight back through this `combine`.
        settings.settings,
        // "Why are we waiting?" — pipe the AudioOutputMonitor flow
        // through ReaderUiState so AudiobookView can render the brass
        // diagnostic panel without taking another flow subscription.
        playback.waitReason,
        // Issue #677 — end-of-book latch; HybridReaderScreen mounts the
        // BookFinishedOverlay when this flips true.
        _bookFinished,
        // Issue #805 — typed engine state for error surfacing. The reader
        // projects EngineState.Error onto ReaderUiState.playbackError so
        // AudiobookView can render a typed error banner with specific
        // messages and recovery actions per error subtype.
        playback.engineState,
        // Issue #805 — dismiss latch for the error banner. When the user
        // swipes away the banner, we record the message; same message =
        // suppressed; new/different message = re-armed.
        _dismissedErrorMessage,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val state = values[0] as UiPlaybackState
        @Suppress("UNCHECKED_CAST")
        val text = values[1] as String
        @Suppress("UNCHECKED_CAST")
        val pane = values[2] as ReaderView
        @Suppress("UNCHECKED_CAST")
        val phase = values[3] as LoadingPhase
        val settingsSnapshot = values[4] as `in`.jphe.storyvox.feature.api.UiSettings
        @Suppress("UNCHECKED_CAST")
        val waitReason = values[5] as? `in`.jphe.storyvox.playback.diagnostics.WaitReason
        val bookFinished = values[6] as Boolean
        val engineState = values[7] as `in`.jphe.storyvox.playback.EngineState
        val dismissedMsg = values[8] as? String
        val error = engineState as? `in`.jphe.storyvox.playback.EngineState.Error
        ReaderUiState(
            playback = state,
            chapterText = text,
            activePane = pane,
            loadingPhase = phase,
            punctuationPauseMultiplier = settingsSnapshot.punctuationPauseMultiplier,
            pitchInterpolationHighQuality = settingsSnapshot.pitchInterpolationHighQuality,
            waitReason = waitReason,
            bookFinished = bookFinished,
            // Suppress the banner when the user already dismissed this
            // exact error message; a new/different error re-arms it.
            playbackError = if (error != null && error.message != dismissedMsg) error else null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderUiState())

    /**
     * Most-recent continue-listening entry. Drives the magical Resume
     * prompt that [HybridReaderScreen] renders when the player has no
     * chapter loaded yet, or when the loading timer hit [LoadingPhase.TimedOut]
     * with stale ids. Null while the DAO row is unset (first-launch /
     * wiped-data) — in that case the screen renders [ResumeEmptyPrompt]
     * instead. Same flow LibraryViewModel reads for its tile so both
     * surfaces stay in lock-step.
     */
    val resumeEntry: StateFlow<ContinueListeningEntry?> = positionRepo
        .observeMostRecentContinueListening()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val chapters: StateFlow<List<UiChapter>> = playback.state
        .map { it.fictionId }
        .distinctUntilChanged()
        .flatMapLatest { id ->
            if (id.isNullOrBlank()) flowOf(emptyList()) else fictionRepo.chaptersFor(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Issue #946 — magical reader auto-scroll toggle. Default true so
     * existing users keep their read-along behavior; flipping to false
     * frees the chapter body for manual scrolling while audio continues.
     * Backed by [SettingsRepositoryUi.readerAutoScrollEnabled] so the
     * preference rides across sessions and across the InstantDB sync
     * allowlist if/when wired through.
     */
    val autoScrollEnabled: StateFlow<Boolean> = settings.readerAutoScrollEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /**
     * Issue #997 — Focused Reading mode toggle. Default false (new
     * opt-in mode, unlike auto-scroll). Backed by
     * [SettingsRepositoryUi.readerFocusModeEnabled]; device-local (not
     * in the sync allowlist). [ReaderTextView] observes this to dim
     * off-focus lines, narrow the column, centre the active line and
     * collapse the bottom chrome.
     */
    val focusModeEnabled: StateFlow<Boolean> = settings.readerFocusModeEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Issue #278 — user-initiated retry from the timed-out error block.
     *  Re-invokes the playback `play()` path; the underlying controller
     *  will re-fetch the chapter / re-prime the voice. We also reset the
     *  loading phase so the UI immediately shows the regular spinner
     *  instead of staying on the error block.
     *
     *  No fictionId/chapterId check — the playback controller is the
     *  source of truth for what's "currently loaded"; if neither is set,
     *  play() is a documented no-op. */
    fun retryLoading() {
        _loadingPhase.value = LoadingPhase.Loading
        playback.play()
    }

    /**
     * Playing-tab Resume CTA. Loads the chapter referenced by
     * [resumeEntry], seeks to [ContinueListeningEntry.charOffset] (or to
     * the chapter head when [fromStart] is true), and — per the smart-
     * resume policy (#90) — auto-plays iff the user's last play/pause
     * intent was play. App-killed-mid-playback leaves the flag at true,
     * so the dominant "phone died, keep going" case still auto-plays.
     *
     * No screen transition: the playback state flow drives the
     * Playing-tab content swap from [ResumePrompt] to the normal
     * AudiobookView the moment `playback.state.fictionId` flips
     * non-null. The user feels the prompt dissolve into the player
     * rather than a discrete navigation.
     *
     * Also resets [_loadingPhase] back to `Loading` so the timed-out
     * error path's escape hatch (Lyra: ResumePrompt as the recovery UI
     * for TimedOut) restarts the 30 s timer cleanly — if the new load
     * also stalls, the user gets the same friendly error block on the
     * next round, not an immediate re-fire of the stale `TimedOut` from
     * the previous attempt.
     */
    fun resume(fromStart: Boolean = false) {
        val entry = resumeEntry.value ?: return
        _loadingPhase.value = LoadingPhase.Loading
        // Issue #677 — clear the end-of-book latch on a fresh listen so
        // the overlay doesn't re-trip when the user taps "Restart from
        // beginning" after finishing the book.
        _bookFinished.value = false
        viewModelScope.launch {
            val autoPlay = resumePolicy.currentLastWasPlaying()
            playback.startListening(
                fictionId = entry.fiction.id,
                chapterId = entry.chapter.id,
                charOffset = if (fromStart) 0 else entry.charOffset,
                autoPlay = autoPlay,
            )
        }
    }

    fun setActivePane(pane: ReaderView) { _activePane.value = pane }

    fun playPause() {
        val state = uiState.value.playback ?: return
        if (state.isPlaying) playback.pause() else playback.play()
    }

    fun seekTo(ms: Long) = playback.seekTo(ms)
    fun seekToChar(charOffset: Int) = playback.seekToChar(charOffset)
    fun skipForward() = playback.skipForward()
    fun skipBack() = playback.skipBack()
    fun nextChapter() = playback.nextChapter()
    fun previousChapter() = playback.previousChapter()

    fun playChapter(chapterId: String) {
        val fictionId = uiState.value.playback?.fictionId ?: return
        _loadingPhase.value = LoadingPhase.Loading
        _bookFinished.value = false
        playback.startListening(fictionId = fictionId, chapterId = chapterId)
    }
    fun nextSentence() = playback.nextSentence()
    fun previousSentence() = playback.previousSentence()

    fun setSpeed(speed: Float) {
        playback.setSpeed(speed)
    }

    fun persistSpeed(speed: Float) {
        viewModelScope.launch { settings.setDefaultSpeed(speed) }
    }

    fun setPitch(pitch: Float) {
        playback.setPitch(pitch)
    }

    fun persistPitch(pitch: Float) {
        viewModelScope.launch { settings.setDefaultPitch(pitch) }
    }

    fun startSleepTimer(mode: UiSleepTimerMode) = playback.startSleepTimer(mode)
    fun cancelSleepTimer() = playback.cancelSleepTimer()

    /**
     * Issue #418 — set the inter-sentence pause multiplier from the
     * magical-voice-icon quick sheet. Mirrors the Settings → Voice &
     * Playback "Punctuation pause" slider (#109): the engine clamps to
     * [0..4×], the change takes effect on the next sentence boundary.
     * We hit both seams — [PlaybackControllerUi.setPunctuationPauseMultiplier]
     * for the immediate live-apply (engine re-reads on next sentence),
     * and [SettingsRepositoryUi.setPunctuationPauseMultiplier] for
     * persistence so the value survives app restart. The Settings screen
     * uses the same dual-write pattern for speed/pitch via
     * `persistSpeed`/`persistPitch`.
     */
    fun setPunctuationPauseMultiplier(multiplier: Float) {
        playback.setPunctuationPauseMultiplier(multiplier)
        viewModelScope.launch { settings.setPunctuationPauseMultiplier(multiplier) }
    }

    /**
     * Issue #418 — toggle the Sonic high-quality pitch-interpolation
     * flag from the voice quick sheet. Mirrors the Settings switch
     * (#193). Persisted-only — the engine reads the flag at the
     * start of each chapter render, so a mid-chapter flip applies to
     * the next chapter rather than immediately. The quick-sheet UI
     * surface still calls out "applies on next chapter" via subtitle
     * copy so the listener isn't surprised when the current chapter
     * doesn't change tone.
     */
    fun setPitchInterpolationHighQuality(enabled: Boolean) {
        viewModelScope.launch { settings.setPitchInterpolationHighQuality(enabled) }
    }

    /**
     * Issue #946 — persist the reader auto-scroll toggle. Mirrored
     * shape to the other setting setters: fire-and-forget into the
     * viewModelScope, the StateFlow above re-emits, the
     * [ReaderTextView] observes and re-gates its scroll
     * LaunchedEffect.
     */
    fun setAutoScrollEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setReaderAutoScrollEnabled(enabled) }
    }

    /** Issue #997 — persist the Focused Reading toggle. Same
     *  fire-and-forget shape as [setAutoScrollEnabled]; the StateFlow
     *  re-emits and [ReaderTextView] re-renders in focus mode. */
    fun setFocusModeEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setReaderFocusModeEnabled(enabled) }
    }

    // Issue #121 — in-chapter bookmark fan-out. ReaderViewModel stays
    // the single seam ChapterView talks to; controller delegate handles
    // the suspend-vs-fire-and-forget seam internally.
    fun bookmarkHere() = playback.bookmarkHere()
    fun clearBookmark() = playback.clearBookmark()
    fun jumpToBookmark() = playback.jumpToBookmark()

    // ── Chapter Recap (issue #81) ──────────────────────────────────

    /** Open the modal and stream a recap for the current chapter.
     *  No-op if the playback state isn't ready (no fictionId/chapterId
     *  to recap). */
    fun requestRecap() {
        // Cancel any in-flight recap so we don't double-stream into
        // the buffer.
        recapJob?.cancel()
        val state = uiState.value.playback ?: return
        val fictionId = state.fictionId ?: return
        val chapterId = state.chapterId ?: return

        _recap.value = RecapUiState.Loading
        val buf = StringBuilder()
        recapJob = viewModelScope.launch {
            chapterRecap.recap(fictionId, chapterId)
                .catch { e ->
                    _recap.value = mapErrorToUi(e)
                }
                .onCompletion { cause ->
                    if (cause == null && _recap.value is RecapUiState.Streaming) {
                        _recap.value = RecapUiState.Done(buf.toString())
                    } else if (cause == null && _recap.value === RecapUiState.Loading) {
                        // Stream completed without emitting anything
                        // (e.g. no chapters in window).
                        _recap.value = RecapUiState.Done(buf.toString())
                    }
                    // Cancelled (cause == CancellationException) →
                    // leave whatever state we were in; the caller
                    // (cancelRecap) flips us to Hidden directly.
                }
                .collect { delta ->
                    buf.append(delta)
                    _recap.value = RecapUiState.Streaming(buf.toString())
                }
        }
    }

    /** Hide the modal. Cancels the in-flight stream — partial recap
     *  is discarded. Also stops any in-flight recap-aloud utterance
     *  (#189) so closing the modal silences the audio. */
    fun cancelRecap() {
        recapJob?.cancel()
        recapJob = null
        playback.stopSpeaking()
        _recap.value = RecapUiState.Hidden
    }

    /**
     * Issue #189 — toggle the recap-aloud TTS. Tapped from the Read-aloud
     * button in [RecapModal] when the recap is in [RecapUiState.Done].
     *
     * Behaviour:
     *  - If a recap utterance is already speaking, stop it. (Button
     *    rendered as a Pause icon — second tap silences.)
     *  - Otherwise, pause the active fiction (so the recap and the
     *    chapter audio don't overlap), then synthesize the recap text
     *    via the active voice. Per the spec we leave fiction paused
     *    when the recap finishes — auto-resume would feel aggressive.
     */
    fun toggleRecapAloud() {
        if (recapPlayback.value == UiRecapPlaybackState.Speaking) {
            playback.stopSpeaking()
            return
        }
        val text = (recap.value as? RecapUiState.Done)?.text ?: return
        if (text.isBlank()) return
        // Pause active fiction first — engine is shared, overlapping audio
        // would be muddy.
        if (uiState.value.playback?.isPlaying == true) playback.pause()
        viewModelScope.launch {
            playback.speakText(text)
        }
    }

    companion object {
        /** Issue #278 — soft hint threshold. After 10s of being stuck in
         *  the loading state, the AudiobookView adds a "Still working…
         *  (slow voice / network)" secondary line under the conjuring
         *  copy. The threshold is conservative — real warmups on Flip3
         *  routinely take 5-8s, so 10s is the cutoff between "expected"
         *  and "starting to feel off." */
        const val LOADING_SLOW_HINT_MS = 10_000L

        /** Issue #278 — hard timeout threshold. At 30s we flip to a
         *  user-actionable error: Retry / Pick voice / Cancel. The
         *  underlying load isn't cancelled — if it eventually completes
         *  the regular state flow takes over again — but the user has
         *  a way out of the conjuring screen instead of staring forever. */
        const val LOADING_TIMEOUT_MS = 30_000L
    }

    private fun mapErrorToUi(e: Throwable): RecapUiState.Error = when (e) {
        is LlmError.NotConfigured -> RecapUiState.Error(
            message = "Set up AI in Settings to use Recap.",
            kind = RecapUiState.ErrorKind.NotConfigured,
        )
        is LlmError.AuthFailed -> RecapUiState.Error(
            message = "${e.provider} key is invalid — check Settings.",
            kind = RecapUiState.ErrorKind.AuthFailed,
        )
        is LlmError.Transport -> RecapUiState.Error(
            message = "Couldn't reach the AI — check your connection and try again.",
            kind = RecapUiState.ErrorKind.Transport,
        )
        is LlmError.ProviderError -> RecapUiState.Error(
            message = "AI service error (${e.status}). Try again in a moment.",
            kind = RecapUiState.ErrorKind.ProviderError,
        )
        else -> RecapUiState.Error(
            message = e.message ?: "Recap failed.",
            kind = RecapUiState.ErrorKind.Transport,
        )
    }
}
