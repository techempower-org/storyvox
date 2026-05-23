package `in`.jphe.storyvox.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.feature.debug.DebugOverlay
import `in`.jphe.storyvox.feature.debug.DebugViewModel
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.component.HybridReaderShell
import `in`.jphe.storyvox.ui.component.MagicCircularProgress
import `in`.jphe.storyvox.ui.component.MilestoneConfetti
import `in`.jphe.storyvox.ui.theme.LocalSpacing

@Composable
fun HybridReaderScreen(
    onPickVoice: () -> Unit,
    /** Open Settings → AI when the recap modal hits a NotConfigured /
     *  AuthFailed state. **AppNav must wire this** — leaving the default
     *  no-op causes the recap empty-state's "Open Settings" CTA to look
     *  primary but be a dead button (issue #152, fixed in this PR by
     *  wiring all three [HybridReaderScreen] composables in
     *  `StoryvoxNavHost.kt`). The default stays for preview/test use,
     *  but any new production callsite *must* pass a real navigation
     *  callback or the unconfigured-AI user has no path forward. */
    onOpenAiSettings: () -> Unit = {},
    /** Open the Q&A chat surface for the currently-loaded fiction
     *  (#81 follow-up). No-op default is preview/test-only — production
     *  callsites pass a real
     *  `navController.navigate(chat(fictionId, prefill))`.
     *  Surfaced in the player-options sheet's "Smart features" group, and
     *  by long-press character-lookup in the reader (#188), which passes
     *  a non-null prefill of the form `Who is X?`. Pass `null` for
     *  prefill from non-lookup entry points. */
    onOpenChat: (fictionId: String, prefill: String?) -> Unit = { _, _ -> },
    /**
     * Route the empty-empty Resume prompt's "Browse the realms" CTA to
     * the Browse tab. Default no-op for previews/tests; production
     * callsites pass a real `navController.navigate(BROWSE)`. The
     * populated Resume prompt's two buttons don't need any nav — they
     * load the chapter into the playback controller, and the state flow
     * naturally swaps the prompt for the player view in place.
     */
    onBrowse: () -> Unit = {},
    /** Open the Settings screen. Kept on the surface for source-compat
     *  with existing call sites; the player's top-bar gear icon was
     *  replaced by a Back arrow in v0.5.40 (#437) and Settings now
     *  lives in primary nav (#469). Default no-op for previews/tests. */
    onOpenSettings: () -> Unit = {},
    /** Issue #437 — pop the player back to whichever surface launched
     *  it (FictionDetail, Library, Browse, Follows, History). Wired
     *  by [`in`.jphe.storyvox.navigation.StoryvoxNavHost] to
     *  `navController.popBackStack()` with a fallback to LIBRARY when
     *  the back stack is empty (deep-link / cold-launch into the
     *  player). Default no-op for previews. */
    onBack: () -> Unit = {},
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val recapState by viewModel.recap.collectAsStateWithLifecycle()
    val recapPlayback by viewModel.recapPlayback.collectAsStateWithLifecycle()
    val resumeEntry by viewModel.resumeEntry.collectAsStateWithLifecycle()
    val chapters by viewModel.chapters.collectAsStateWithLifecycle()
    val playback = state.playback

    // Calliope (v0.5.00) — first-natural-chapter-completion confetti.
    // The VM's confettiTrigger fires Unit once per qualifying event;
    // we flip a local visible flag that drives the [MilestoneConfetti]
    // overlay, then close the gate persistently via markConfettiShown
    // when the overlay tells us it's done.
    var celebrationVisible by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel) {
        viewModel.confettiTrigger.collect {
            celebrationVisible = true
        }
    }

    // Vesper (v0.4.97) — debug overlay. The DebugViewModel pulls the
    // master switch from SettingsRepositoryUi so toggling in Settings →
    // Developer immediately reflects here without a navController round-
    // trip. The overlay is mounted INSIDE the shell so the reader's
    // playback controls still respond to taps (the overlay only takes
    // pointer events on its own bounding box). Hoisting outside the
    // shell would intercept reader gestures.
    //
    // Issue #529 follow-up (v0.5.58): the overlay is intentionally
    // available in the release variant. JP relies on the live "sent
    // #N · queue X/12" strip + Voice-Roster / Playback-Speed metrics
    // for diagnostic gold on the same APK end users run. The Settings
    // → Advanced → "Show debug overlay" toggle (defaulting OFF — see
    // [UiSettings.showDebugOverlay] = false) is the SOLE gate. We
    // dropped the earlier `BuildConfig.DEBUG` compile-time gate
    // because, with `release` now being the shipped variant, that
    // would have permanently hidden the overlay even for users who
    // explicitly opted in — exactly the opposite of what we want.
    // Pre-existing default of `false` means a fresh install still
    // shows nothing; only the Settings toggle reveals the strip.
    val debugVm: DebugViewModel = hiltViewModel()
    val debugEnabled by debugVm.overlayEnabled.collectAsStateWithLifecycle()
    val debugOverlayVisible = debugEnabled

    // Playing-tab "no chapter loaded" path — replace the bare
    // "No chapter loaded." stub with the magical Resume prompt. Two
    // sub-cases:
    //  - we have a most-recent continue-listening entry → ResumePrompt
    //    (cover + sigil ring + brass-shimmer Resume CTA + "From the
    //    start"). Tapping Resume loads via the playback controller; the
    //    state flow flips `playback` non-null and the prompt naturally
    //    dissolves into the player view — no nav transition.
    //  - no entry at all (first launch, wiped data) → ResumeEmptyPrompt
    //    with a "Browse the realms" CTA into the Browse tab.
    //
    // We also short-circuit through the same prompt when the loader hit
    // [LoadingPhase.TimedOut] AND we have a resume entry — same user
    // outcome (give them a clean way back into their book) without the
    // generic error-block surface. The retry path inside AudiobookView
    // still fires for the case where there's no resume entry to fall
    // back on.
    val timedOut = state.loadingPhase == LoadingPhase.TimedOut
    // Show the Resume prompt whenever we don't have a real chapter to
    // render — three cases:
    //  (a) playback is null (cold-start, app-killed)
    //  (b) playback exists but its fictionId/chapterId are still null
    //      (controller initialized but no chapter queued yet)
    //  (c) the loading timer hit TimedOut AND we have a resume entry to
    //      fall back on (otherwise AudiobookView's friendlier
    //      Retry/Pick-voice error block handles the dead-end case).
    //
    // We compute the cases below in two steps so Kotlin's smart-cast
    // on `playback != null` stays usable for the rest of the screen.
    val showPromptForNullPlayback = playback == null
    val showPromptForBlankIds = playback != null &&
        playback.fictionId == null &&
        playback.chapterId == null
    val showPromptForTimedOutWithEntry =
        playback != null && timedOut && resumeEntry != null
    // Issue #638 (v1.0 blocker) — when the Reader was navigated to
    // with explicit /reader/{fictionId}/{chapterId} args (drill-down
    // from FictionDetail's Play button), the playback flow has not
    // yet emitted by the time the composable first runs. Pre-fix the
    // screen would fall through to ResumeEmptyPrompt and render the
    // "Your library awaits / Browse the realms →" CTA — making it
    // look exactly like the Library tab's empty state and convincing
    // JP (and any user) that the Play tap had bounced them back to
    // Library. Suppress the prompt branch entirely while the
    // controller is still warming up on an explicit-args entry —
    // the regular spinner inside AudiobookView (LoadingPhase.Loading)
    // owns the loading affordance from here, just as it does for
    // every other chapter-load path. The Playing tab (/playing, no
    // args) still falls through to the prompt as before, which is
    // the desired empty-state behaviour for that surface.
    // Issue #638 — the explicit-args loading window covers BOTH the
    // pre-emission case (showPromptForNullPlayback) AND the
    // post-emission-but-blank-ids case (showPromptForBlankIds, where
    // PlaybackController has emitted its default state with fictionId
    // = null / chapterId = null but the controller's play() call
    // hasn't reached the engine yet). The full sequence on a cold
    // Play tap from FictionDetail:
    //   t=0   :  user taps Play → listen(c) → startListening()
    //   t=0+  :  screen navigates to /reader; ReaderVM constructs;
    //            playback flow has not emitted → playback = null;
    //            showPromptForNullPlayback = true
    //   t=43ms:  playback flow emits default UiPlaybackState
    //            (fictionId = null, chapterId = null); the null case
    //            flips false BUT showPromptForBlankIds flips true,
    //            and pre-fix that re-routed straight back to
    //            ResumeEmptyPrompt — a 43ms flash of "Your library
    //            awaits" was the bug. Including blank-ids here keeps
    //            the explicit-args loading surface up through the
    //            whole controller cold-start window, until the
    //            controller fills in ids on the first chapter-load
    //            tick. The TimedOut sub-branch within
    //            [ExplicitArgsLoadingPrompt] surfaces a Retry/Back
    //            block on a 30s wall — same affordance shape as the
    //            in-AudiobookView TimedOut path, but rendered here
    //            because we never reach AudiobookView with no ids.
    val isExplicitArgsLoading = viewModel.hasExplicitChapterArgs &&
        (showPromptForNullPlayback || showPromptForBlankIds)
    if (isExplicitArgsLoading) {
        // Brass loading-card surface while the controller warms up.
        // Pre-fix this branch fell through to ResumeEmptyPrompt and
        // rendered the "Your library awaits / Browse the realms →"
        // CTA — visually indistinguishable from the Library empty
        // state, and exactly what the bug report mistook for "the
        // bottom-dock intercepted my Play tap." The real fix is here:
        // show a typed loading state for the explicit-args path so
        // the user sees "your chapter is loading," not "you have no
        // library." Once the playback flow flips non-null the screen
        // re-composes through the normal AudiobookView branch below.
        Box(modifier = Modifier.fillMaxSize()) {
            ExplicitArgsLoadingPrompt(
                loadingPhase = state.loadingPhase,
                onRetry = viewModel::retryLoading,
                onBack = onBack,
            )
            if (debugOverlayVisible) {
                DebugOverlay(viewModel = debugVm)
            }
        }
        return
    }
    if (showPromptForNullPlayback || showPromptForBlankIds ||
        showPromptForTimedOutWithEntry
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val entry = resumeEntry
            if (entry != null) {
                ResumePrompt(
                    entry = entry,
                    onResume = { viewModel.resume(fromStart = false) },
                    onFromStart = { viewModel.resume(fromStart = true) },
                )
            } else {
                ResumeEmptyPrompt(onBrowse = onBrowse)
            }
            // Debug overlay still mounts on top so the inspector can see
            // the loading-phase state machine even before a chapter is
            // loaded. Same gating as below.
            if (debugOverlayVisible) {
                DebugOverlay(viewModel = debugVm)
            }
        }
        return
    }
    // Past here, `playback` is guaranteed non-null — the compound
    // predicate above covered the null case. The local `playback` val
    // doesn't smart-cast through that, so explicitly bind a non-null
    // alias here. `playbackState` for clarity at call sites (the
    // existing AudiobookView arg is called `state`).
    val playbackState = requireNotNull(playback) {
        "playback should be non-null past the resume-prompt branch"
    }

    Box(modifier = Modifier.fillMaxSize()) {
    HybridReaderShell(
        current = state.activePane,
        onViewChange = viewModel::setActivePane,
        audiobookContent = {
            AudiobookView(
                state = playbackState,
                onPlayPause = viewModel::playPause,
                onSeekTo = viewModel::seekTo,
                onSkipForward = viewModel::skipForward,
                onSkipBack = viewModel::skipBack,
                onNextChapter = viewModel::nextChapter,
                onPreviousChapter = viewModel::previousChapter,
                onPreviousSentence = viewModel::previousSentence,
                onNextSentence = viewModel::nextSentence,
                onPickVoice = onPickVoice,
                onSetSpeed = viewModel::setSpeed,
                onPersistSpeed = viewModel::persistSpeed,
                onSetPitch = viewModel::setPitch,
                onPersistPitch = viewModel::persistPitch,
                onStartSleepTimer = viewModel::startSleepTimer,
                onCancelSleepTimer = viewModel::cancelSleepTimer,
                onRequestRecap = viewModel::requestRecap,
                onOpenChat = {
                    playbackState.fictionId?.let { onOpenChat(it, null) }
                },
                onOpenSettings = onOpenSettings,
                onBack = onBack,
                // Issue #278 — surface loading-phase + retry path. The
                // view decides what to render based on phase (regular /
                // slow-hint at 10s / full error block at 30s).
                loadingPhase = state.loadingPhase,
                onRetryLoading = viewModel::retryLoading,
                // Issue #121 — bookmark drop / jump. The controller side
                // resolves "current chapter" + char-offset from the
                // playback state, so the UI just forwards the verbs.
                onBookmarkHere = viewModel::bookmarkHere,
                onJumpToBookmark = viewModel::jumpToBookmark,
                // Issue #418 — magical voice icon's quick sheet
                // surfaces the live pause multiplier (#109) + Sonic
                // high-quality flag (#193). State sourced from
                // SettingsRepositoryUi via ReaderViewModel's combine;
                // setters dual-write to PlaybackControllerUi (immediate
                // engine apply) + SettingsRepositoryUi (persistence).
                punctuationPauseMultiplier = state.punctuationPauseMultiplier,
                pitchInterpolationHighQuality = state.pitchInterpolationHighQuality,
                onSetPunctuationPause = viewModel::setPunctuationPauseMultiplier,
                onSetPitchHighQuality = viewModel::setPitchInterpolationHighQuality,
                // "Why are we waiting?" — pipe AudioOutputMonitor's
                // diagnostic through the view so the brass panel above
                // the cover surfaces a typed reason whenever no audio
                // is reaching the speakers.
                waitReason = state.waitReason,
                chapters = chapters,
                onPlayChapter = viewModel::playChapter,
            )
        },
        readerContent = {
            ReaderTextView(
                state = playbackState,
                chapterText = state.chapterText,
                onPlayPause = viewModel::playPause,
                onSeekToChar = viewModel::seekToChar,
                onAskAiAbout = { question ->
                    // Long-press character lookup (#188): forward the
                    // prebuilt "Who is X?" question as a chat prefill.
                    // The chat surface auto-fills the input — the user
                    // can edit before sending or send as-is.
                    playbackState.fictionId?.let { onOpenChat(it, question) }
                },
            )
        },
    )

    // Recap modal — overlays everything when not Hidden. Driven by
    // ReaderViewModel.requestRecap().
    RecapModal(
        state = recapState,
        recapPlayback = recapPlayback,
        onCancel = viewModel::cancelRecap,
        onRetry = viewModel::requestRecap,
        onOpenSettings = {
            viewModel.cancelRecap()
            onOpenAiSettings()
        },
        onToggleReadAloud = viewModel::toggleRecapAloud,
    )

    // Debug overlay — sits on top of everything (including the recap
    // modal) when enabled. Pinned to the top of the screen via
    // statusBarsPadding inside DebugOverlay itself, so the player
    // controls at the bottom stay free.
    if (debugOverlayVisible) {
        DebugOverlay(viewModel = debugVm)
    }

    // Calliope (v0.5.00) — confetti easter-egg, drifts across the
    // player for ~3.5s then fades. Sits ABOVE the debug overlay so
    // power users still see the celebration; the overlay can wait.
    // markConfettiShown persists the one-time flag so this never
    // reappears for this install. Non-blocking — no pointer
    // interception, just a Canvas drawing on top.
    if (celebrationVisible) {
        MilestoneConfetti(
            onFinished = {
                celebrationVisible = false
                viewModel.markConfettiShown()
            },
        )
    }

    // Issue #677 — end-of-book overlay. Engine emits BookFinished
    // when the last chapter's last sentence drains and no successor
    // exists. Pre-fix the screen rendered nothing — scrubber sat
    // stuck with no surface explaining the book is over.
    if (state.bookFinished) {
        BookFinishedOverlay(
            fictionTitle = state.playback?.fictionTitle,
            onBackToLibrary = {
                viewModel.acknowledgeBookFinished()
                onBack()
            },
            onBrowseMore = {
                viewModel.acknowledgeBookFinished()
                onBrowse()
            },
            onDismiss = { viewModel.acknowledgeBookFinished() },
        )
    }
    }
}

/**
 * Issue #638 (v1.0 blocker) — loading affordance shown when the Reader
 * was navigated to with explicit `/reader/{fictionId}/{chapterId}` args
 * (drill-down from FictionDetail's Play button) but the playback flow
 * hasn't emitted yet. Pre-fix the screen fell through to
 * [ResumeEmptyPrompt] in this window, which made it look exactly like
 * the Library tab's empty state — convincing the user (and JP) that
 * the Play tap had bounced them back to Library instead of starting
 * playback. This prompt holds the user on a typed "your chapter is
 * loading" surface until the controller flips the state-flow.
 *
 * Honours [LoadingPhase] from the ReaderViewModel so the same
 * slow-hint at 10s and timed-out retry path the AudiobookView uses
 * is reproduced here — symmetry between the two surfaces matters for
 * users who happen to time a slow voice cold-start against a slow
 * chapter download.
 */
@Composable
private fun ExplicitArgsLoadingPrompt(
    loadingPhase: LoadingPhase,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (loadingPhase) {
            LoadingPhase.TimedOut -> {
                // 30s wall — give the user a Retry + Back rather than
                // a silent spinner. Same affordance shape as the
                // AudiobookView timed-out block, just rendered here
                // because we're pre-controller-init.
                Text(
                    text = "Couldn't start this chapter",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(spacing.xs))
                Text(
                    text = stringResource(R.string.reader_voice_engine_slow_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(spacing.lg))
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    BrassButton(
                        label = stringResource(R.string.reader_back),
                        onClick = onBack,
                        variant = BrassButtonVariant.Secondary,
                    )
                    BrassButton(
                        label = stringResource(R.string.reader_retry),
                        onClick = onRetry,
                        variant = BrassButtonVariant.Primary,
                    )
                }
            }
            LoadingPhase.Slow -> {
                // v1.0 polish (2026-05-16) — JP audit flagged the
                // Material CircularProgressIndicator's sweeping arc
                // as "weird" against the rest of the Library Nocturne
                // motion vocabulary. Swap to MagicCircularProgress: a
                // layered brass sigil (outer dashed ring + inner six-
                // pointed star) that matches MagicSkeletonTile's
                // geometry so the loading + skeleton surfaces read as
                // one realm-language.
                MagicCircularProgress(
                    modifier = Modifier.size(56.dp),
                )
                Spacer(Modifier.height(spacing.md))
                Text(
                    text = stringResource(R.string.reader_still_loading),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(spacing.xs))
                Text(
                    text = stringResource(R.string.reader_still_loading_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            else -> {
                // v1.0 polish — same swap as the Slow branch. See
                // comment above. ExplicitArgsLoadingPrompt is the
                // surface JP sees the moment he taps a chapter from
                // FictionDetail and the controller hasn't emitted yet
                // (~200-2000 ms typical), so this is one of the most
                // load-bearing animations in the app for "is this
                // working?" confidence.
                MagicCircularProgress(
                    modifier = Modifier.size(56.dp),
                )
                Spacer(Modifier.height(spacing.md))
                Text(
                    text = stringResource(R.string.reader_loading_chapter),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * Issue #677 — end-of-book modal. Renders when the engine has
 * emitted BookFinished and the user hasn't acknowledged yet.
 * Material 3 AlertDialog — TalkBack-friendly (focus-claiming surface,
 * headline reads immediately), matches the recap modal pattern.
 */
@Composable
private fun BookFinishedOverlay(
    fictionTitle: String?,
    onBackToLibrary: () -> Unit,
    onBrowseMore: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (fictionTitle.isNullOrBlank()) {
                    stringResource(R.string.reader_book_finished_no_title)
                } else {
                    stringResource(R.string.reader_book_finished_with_title, fictionTitle)
                },
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Text(
                text = stringResource(R.string.reader_book_finished_body),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onBrowseMore) {
                Text(stringResource(R.string.reader_browse_realms))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onBackToLibrary) {
                Text(stringResource(R.string.reader_back_to_library))
            }
        },
    )
}
