package `in`.jphe.storyvox.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ripple
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.feature.api.UiPlaybackState
import `in`.jphe.storyvox.feature.api.UiSleepTimerMode
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.component.BrassEmberOverlay
import `in`.jphe.storyvox.ui.component.BrassProgressTrack
import `in`.jphe.storyvox.ui.component.BrassVoiceIcon
import `in`.jphe.storyvox.ui.component.ErrorBlock
import `in`.jphe.storyvox.ui.component.ErrorPlacement
import `in`.jphe.storyvox.ui.component.FictionCoverThumb
import `in`.jphe.storyvox.ui.component.fictionMonogram
import `in`.jphe.storyvox.ui.component.humanizeVoiceLabel
import `in`.jphe.storyvox.ui.component.MagicSkeletonTile
import `in`.jphe.storyvox.ui.component.MagicSpinner
import `in`.jphe.storyvox.ui.theme.LocalMotion
import `in`.jphe.storyvox.ui.theme.LocalReducedMotion
import `in`.jphe.storyvox.ui.theme.LocalSpacing
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudiobookView(
    state: UiPlaybackState,
    onPlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSkipForward: () -> Unit,
    onSkipBack: () -> Unit,
    onNextChapter: () -> Unit,
    onPreviousChapter: () -> Unit,
    /** #120 — step back one sentence boundary. Default no-op so older
     *  callsites (tests, previews) keep compiling. */
    onPreviousSentence: () -> Unit = {},
    /** #120 — step forward one sentence boundary. */
    onNextSentence: () -> Unit = {},
    onPickVoice: () -> Unit,
    onSetSpeed: (Float) -> Unit,
    onPersistSpeed: (Float) -> Unit,
    onSetPitch: (Float) -> Unit,
    onPersistPitch: (Float) -> Unit,
    onStartSleepTimer: (UiSleepTimerMode) -> Unit,
    onCancelSleepTimer: () -> Unit,
    /** Open the Chapter Recap modal. ReaderScreen wires this to
     *  [`in`.jphe.storyvox.feature.reader.ReaderViewModel.requestRecap].
     *  Issue #81. */
    onRequestRecap: () -> Unit = {},
    /** Open the Q&A chat surface for the currently-loaded fiction
     *  (#81 follow-up). HybridReaderScreen guards this against null
     *  fictionId on the playback state, so callees can rely on it
     *  being a fully-routed navigation. */
    onOpenChat: () -> Unit = {},
    /** Open the Settings screen. Kept on the surface for source-compat
     *  with HybridReaderScreen's existing plumbing. The player's leading
     *  gear icon was replaced by a Back arrow in v0.5.40 (#437) — see
     *  [onBack]. Default no-op for previews / tests. */
    onOpenSettings: () -> Unit = {},
    /** Issue #437 — pop the player back to whichever surface launched
     *  it (FictionDetail, Library, Browse). Replaces the leading
     *  Settings gear that TalkBack used to announce as "Settings" while
     *  sighted users perceived it as a back arrow with the wrong label.
     *  Default no-op for previews / tests; production callsites pass a
     *  real `navController.popBackStack()`. */
    onBack: () -> Unit = {},
    /** Issue #278 — loading-phase from the ReaderViewModel. Drives the
     *  soft "Still working…" hint at 10s and the hard timeout/retry
     *  error block at 30s. Defaults to NotLoading so previews / tests
     *  that don't care about the loading lifecycle stay simple. */
    loadingPhase: LoadingPhase = LoadingPhase.NotLoading,
    /** Issue #278 — user tapped Retry on the timed-out error block. */
    onRetryLoading: () -> Unit = {},
    /** Issue #278 — user tapped "Pick a different voice" on the timed-out
     *  error block. Goes to the voice library; the controller will pick
     *  the new voice up next time play() is invoked. */
    onCancelLoading: () -> Unit = {},
    /** Issue #121 — drop a bookmark at the current playhead in the active
     *  chapter. One bookmark per chapter; setting again overwrites. */
    onBookmarkHere: () -> Unit = {},
    /** Issue #121 — seek to the active chapter's bookmark, if any. No-op
     *  when the chapter has none. */
    onJumpToBookmark: () -> Unit = {},
    /**
     * Issue #418 — live inter-sentence pause multiplier (#109) for the
     * magical-voice-icon quick sheet. Defaults to 1× (audiobook-tuned
     * default) so preview/test callsites that don't care about cadence
     * stay simple.
     */
    punctuationPauseMultiplier: Float = 1f,
    /**
     * Issue #418 — high-quality Sonic pitch-interpolation flag (#193)
     * for the quick sheet's "Sonic quality" toggle row. Defaults to
     * `true` to match Settings.
     */
    pitchInterpolationHighQuality: Boolean = true,
    /** Issue #418 — apply the inter-sentence pause multiplier. Wired by
     *  ReaderViewModel into both PlaybackControllerUi (live) +
     *  SettingsRepositoryUi (persist). */
    onSetPunctuationPause: (Float) -> Unit = {},
    /** Issue #418 — toggle Sonic high-quality flag. Persisted via
     *  SettingsRepositoryUi; engine reads at next chapter render. */
    onSetPitchHighQuality: (Boolean) -> Unit = {},
    /**
     * "Why are we waiting?" — typed diagnostic explaining why no audio
     * is reaching the speakers right now. Null when playback is happily
     * flowing (or the engine is idle/paused/errored — those have their
     * own UI surfaces). Default null so older callsites (tests,
     * previews) keep compiling. Drives the brass
     * [WhyAreWeWaitingPanel] above the cover.
     */
    waitReason: `in`.jphe.storyvox.playback.diagnostics.WaitReason? = null,
    chapters: List<`in`.jphe.storyvox.feature.api.UiChapter> = emptyList(),
    onPlayChapter: (chapterId: String) -> Unit = {},
    /**
     * Issue #805 — typed playback error from [EngineState.Error]. Non-null
     * when the engine is in an error state; surfaces a dismissible banner
     * with error-type-specific icon, message, and recovery action.
     */
    playbackError: `in`.jphe.storyvox.playback.EngineState.Error? = null,
    /** Issue #805 — retry the failed playback (calls play() on the controller). */
    onRetryPlayback: () -> Unit = {},
    /** Issue #805 — dismiss the error banner without retrying. */
    onDismissPlaybackError: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val motion = LocalMotion.current
    val reducedMotion = LocalReducedMotion.current
    val haptic = LocalHapticFeedback.current
    // Spinner enter/exit transitions for the warmup state. Honors
    // LocalReducedMotion: when true, visibility flips instantly (reduce
    // motion = absent motion, not shorter motion — same pattern as
    // cascadeReveal). Token vocabulary stays consistent with the rest
    // of Library Nocturne via standardDurationMs + standardEasing.
    val spinnerEnter = if (reducedMotion) EnterTransition.None else
        fadeIn(animationSpec = tween(motion.standardDurationMs, easing = motion.standardEasing))
    val spinnerExit = if (reducedMotion) ExitTransition.None else
        fadeOut(animationSpec = tween(motion.standardDurationMs, easing = motion.standardEasing))
    // Issue #418 — two distinct sheets now: the magical-voice-icon
    // opens the voice quick sheet (speed/pitch/voice/pause/quality +
    // Advanced expander), the ⋮ overflow opens the remaining
    // non-voice items (sentence step, sleep timer, bookmark, recap,
    // chat). Splitting the state ensures one sheet's dismiss-animation
    // doesn't fight the other's open-animation.
    var showVoiceSheet by remember { mutableStateOf(false) }
    var showOverflowSheet by remember { mutableStateOf(false) }
    var showChapterListSheet by remember { mutableStateOf(false) }

    // Issue #278 — full-screen error block when the loading state has
    // been stuck for 30+ seconds. Replaces the eternal conjuring sigil
    // with Retry + Pick voice + an escape via the bottom nav. The
    // underlying load isn't cancelled; if it eventually completes the
    // state flow takes over and we route back to the normal player UI.
    if (loadingPhase == LoadingPhase.TimedOut) {
        ErrorBlock(
            title = stringResource(R.string.reader_couldnt_load_chapter),
            message = stringResource(R.string.reader_couldnt_load_message),
            onRetry = onRetryLoading,
            retryLabel = stringResource(R.string.reader_try_again),
            onBack = onPickVoice,
            backLabel = stringResource(R.string.reader_pick_different_voice),
            placement = ErrorPlacement.FullScreen,
            modifier = modifier,
        )
        return
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Issue #527 follow-up — phone-form-factor chip-row regression.
        //
        // Root cause: the player Column was `fillMaxSize()` with no
        // scroll. The vertical content stack — top bar + cover (330 dp)
        // + title + subtitle + scrubber + transport row (96 dp) + the
        // 3-row [PlayerQuickChips] block — measures around ~770-820 dp
        // tall. On the tablet (R83W80CAFZB, ~1006 dp tall) every row
        // fits with breathing room. On the phone (R5CRB0W66MK, Z Flip3
        // at ~880 dp tall after status / nav insets) the chips' first
        // two rows (Speed presets + Sleep timer presets) get squeezed
        // into the leftover ~13 dp between the transport row and the
        // bottom-nav strip, while the third row (Pick voice) is the
        // only one with measured bounds (it's the "weight(1f)" assist
        // chip child, which still gets to claim its intrinsic width).
        //
        // Fix: wrap the player Column in `verticalScroll`. The cover +
        // transport stay anchored at the top; the chips become
        // reachable by a small scroll on narrow displays. Tablet keeps
        // the existing "everything fits without scrolling" layout —
        // the scroll modifier is a no-op when content fits the
        // viewport. uiautomator's a11y dump now sees all 12 chips on
        // both R5CRB0W66MK (phone) and R83W80CAFZB (tablet).
        val scrollState = rememberScrollState()
        // Modifier order matters: `verticalScroll` BEFORE `fillMaxWidth`.
        // `fillMaxSize` would clash with `verticalScroll` (the latter
        // makes the height unbounded, contradicting fillMaxSize's "fill
        // parent's height"). The pattern is:
        //   1. `fillMaxWidth` — column claims full viewport width
        //   2. `verticalScroll` — vertical axis becomes scrollable
        //   3. `padding` — interior insets, AFTER scroll so the scrollbar
        //      (if shown) tracks the viewport edge, not the padded edge
        // Without this order the chip rows on a narrow phone were
        // measured at zero height (Compose's "infinity max height"
        // warning silently downgrades to a zero-bound layout).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            // Issue #254 — the loading state used to show a bare row with
            // only an overflow button up top, so the user had no idea
            // *what* was loading (no title, no chapter, no escape). A
            // two-line title bar pins identity through every state —
            // loading, warming up, buffering, playing, paused. Title
            // comes from the queued PlaybackItem, available before
            // chapter text loads. A custom Box (not CenterAlignedTopAppBar)
            // so the bar grows with fontScale instead of clipping the
            // second line at the M3 bar's fixed container height.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = spacing.xs),
            ) {
                // Issue #437 — leading top-bar icon was a Settings
                // gear opening the Settings screen, but (a) TalkBack
                // announced it as "Settings" while sighted users
                // perceived it as a back arrow (only nav-shaped icon
                // in the player's top strip), and (b) v0.5.39's nav
                // restructure (#469) moved Settings into primary nav
                // so the gear here is redundant. Swap for a Back
                // arrow: the a11y label matches the icon's perceived
                // shape and there's a fast escape from the player
                // without reaching for the system Back button.
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.CenterStart),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                    )
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        // Issue #418 — bumped from 56 dp to 104 dp on the
                        // trailing side because the top bar now carries
                        // TWO trailing affordances (brass voice icon +
                        // ⋮ overflow). Leading side stays at 56 dp for
                        // the Settings gear. Asymmetric padding via
                        // start/end keeps the title visually centered
                        // across both sides' negative space.
                        .padding(start = 56.dp, end = 104.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = state.fictionTitle.ifBlank { "Loading…" },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    if (state.chapterTitle.isNotBlank()) {
                        Text(
                            text = state.chapterTitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
                // Issue #418 — magical voice settings icon. Tap opens
                // the voice quick sheet; long-press jumps straight to
                // the Voice Library (same gesture as a "I want to
                // change voices entirely" shortcut). Sits to the left
                // of the overflow so the brass glyph reads as the
                // primary affordance and the ⋮ reads as the secondary
                // "more" surface — matching the issue's IA pitch.
                //
                // combinedClickable wraps a Box so we can attach both
                // onClick + onLongClick to the same brass-icon target.
                // IconButton can't host onLongClick directly. We
                // preserve the 48 dp touch target by sizing the Box.
                Row(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val voiceInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .combinedClickable(
                                interactionSource = voiceInteraction,
                                indication = ripple(bounded = false, radius = 24.dp),
                                role = androidx.compose.ui.semantics.Role.Button,
                                onClickLabel = stringResource(R.string.reader_voice_settings),
                                onLongClickLabel = stringResource(R.string.reader_open_voice_library),
                                onClick = { showVoiceSheet = true },
                                onLongClick = onPickVoice,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        BrassVoiceIcon(size = 24.dp)
                    }
                    IconButton(onClick = { showOverflowSheet = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "Player options")
                    }
                }
            }
            // "Why are we waiting?" — magical diagnostic panel. Lives
            // between the top app bar and the cover so it doesn't fight
            // the transport row (chip-ui-fixer owns lines 580-640) and
            // the user can see WHY playback isn't producing sound the
            // moment it stops. AnimatedVisibility inside the panel
            // handles slide-in / slide-out so we don't need to gate the
            // call site itself — passing `waitReason = null` collapses
            // the panel to zero height.
            WhyAreWeWaitingPanel(
                reason = waitReason,
                modifier = Modifier.fillMaxWidth(),
                onRetry = onPlayPause,
                onOpenSettings = onOpenSettings,
            )
            // Issue #805 — typed playback error banner. Surfaces above the
            // cover when the engine is in an error state, with a subtype-
            // specific icon, message, and recovery action. Dismissible via
            // the X button; auto-clears when the engine leaves error state.
            if (playbackError != null) {
                PlaybackErrorBanner(
                    error = playbackError,
                    onRetry = onRetryPlayback,
                    onDismiss = onDismissPlaybackError,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // While the chapter body + voice model are still loading we don't
            // have a cover URL or chapter title yet — show the brass arcane
            // sigil placeholder instead of a "?" thumb. As soon as state
            // fills in we swap to the real cover.
            val coverLoading = state.chapterTitle.isBlank() && state.coverUrl.isNullOrBlank()
            // "Warming up" = chapter loaded, user has hit play, but the TTS
            // engine hasn't produced the first sentence yet (no sentence
            // range emitted). Sherpa-onnx model load + first synth can take
            // 5-15s on modest hardware.
            //
            // Issue #98 Mode A — `isWarmingUp` is gated server-side: when
            // the user has Warm-up Wait off, this is always false even
            // during the genuine warmup window, so the UI doesn't show the
            // spinner. The listener trades visible feedback for silence at
            // chapter start.
            val warmingUp = state.isWarmingUp
            // "Buffering" = streaming pipeline ran out of generated PCM
            // mid-chapter (producer can't keep up — e.g. Piper-high on
            // Tab A7 Lite). Same brass spinner so the visual stays
            // consistent; status label distinguishes the two states so
            // the user knows what's happening.
            //
            // Issue #98 Mode B — `isBuffering` stays false through underrun
            // when Catch-up Pause is off, so the consumer drains through
            // the silence without surfacing a spinner.
            val showSpinner = warmingUp || state.isBuffering
            if (coverLoading) {
                MagicSkeletonTile(
                    modifier = Modifier.size(width = 220.dp, height = 330.dp),
                    shape = MaterialTheme.shapes.large,
                    glyphSize = 96.dp,
                )
            } else {
                // Issue #525 — cover-tap silently toggled play/pause with
                // no visual confirmation. On a 220×330 dp cover with no
                // ripple-on-image and no overlay, an accidental thumb tap
                // dropped audio mid-sentence and the user couldn't tell
                // the cover was the cause. Audit (R5CRB0W66MK, 2026-05-15)
                // logged this as "accidental-pause trap".
                //
                // Fix: keep tap-to-toggle (the muscle memory is correct —
                // Spotify, Apple Music, Pocket Casts all do this), but
                // *always* show a transient centered Play/Pause icon
                // overlay for ~400 ms so the user gets immediate visual
                // confirmation of what their tap did. Long-press stays
                // unbound to avoid fighting a11y gestures.
                //
                // `coverFeedbackAtMs` is the wall-clock stamp of the most
                // recent cover tap; the overlay derives its alpha + the
                // icon-shown-during-the-flash from that stamp. We capture
                // `isPlaying` AT THE TAP MOMENT (`feedbackShowsPause`) so
                // the overlay shows "what just happened" (Pause icon
                // appearing → "you paused") rather than racing the state
                // flow's swap.
                var coverFeedbackAtMs by remember { mutableLongStateOf(0L) }
                var feedbackShowsPause by remember { mutableStateOf(false) }
                // Issue #623 — slow Ken-Burns scale on the cover while the
                // engine warms. Pre-fix the warmup window (1-3s typical,
                // 5-15s on slow voices) showed a static cover + a spinner
                // ring + a "Warming Brian…" subtitle. The static cover
                // read as "did the app freeze?" — even with the spinner,
                // motion on the focal element (the cover) is what
                // confirms "alive, working" at a glance.
                //
                // Fix: 0.95x → 1.05x scale over 4000 ms, EaseInOut,
                // infinite reverse. Drives only while `showSpinner` is
                // true; clamps to 1.0f when not warming so the cover
                // doesn't pulse during normal playback. Honors
                // `LocalReducedMotion` (vestibular-sensitive users get
                // a static cover during warmup too — the spinner +
                // subtitle copy carries the "we're working" signal).
                val coverInfinite = rememberInfiniteTransition(label = "cover-ken-burns")
                val kenBurnsScale by if (reducedMotion || !showSpinner) {
                    remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
                } else {
                    coverInfinite.animateFloat(
                        initialValue = 0.95f,
                        targetValue = 1.05f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 4000, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "ken-burns-scale",
                    )
                }
                Box(contentAlignment = Alignment.Center) {
                    FictionCoverThumb(
                        coverUrl = state.coverUrl,
                        title = state.fictionTitle,
                        monogram = fictionMonogram(author = "", title = state.fictionTitle),
                        modifier = Modifier
                            .size(width = 220.dp, height = 330.dp)
                            // Issue #623 — graphicsLayer is cheap (a
                            // single matrix transform, no relayout) so
                            // we drive the warmup pulse from here. When
                            // not warming the scale snaps to 1f (no
                            // recomposition cost beyond the layer
                            // creation itself, since the value is
                            // remember-stable).
                            .graphicsLayer {
                                scaleX = kenBurnsScale
                                scaleY = kenBurnsScale
                            }
                            .clickable(
                                role = androidx.compose.ui.semantics.Role.Button,
                                onClickLabel = if (state.isPlaying) stringResource(R.string.reader_pause) else stringResource(R.string.reader_play),
                                onClick = {
                                    // Capture the icon BEFORE firing the
                                    // toggle: tapping a playing chapter
                                    // shows "Pause" briefly (what just
                                    // happened); tapping a paused
                                    // chapter shows "Play". Without
                                    // capture-first, the state flow can
                                    // race the overlay and the user sees
                                    // the wrong icon for ~16-100 ms.
                                    feedbackShowsPause = state.isPlaying
                                    coverFeedbackAtMs = System.currentTimeMillis()
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onPlayPause()
                                },
                            ),
                    )
                    // #525 — transient feedback overlay. The icon fades
                    // in/out across [COVER_FEEDBACK_DURATION_MS]; outside
                    // that window the overlay is invisible and the cover
                    // is back to undecorated. AnimatedVisibility owns the
                    // fade so we don't need to drive alpha by hand.
                    CoverTapFeedback(
                        startedAtMs = coverFeedbackAtMs,
                        showPauseIcon = feedbackShowsPause,
                    )
                    // Subtle brass sigil ring orbiting the cover while the
                    // engine is producing the first sentence's audio. Fades
                    // in/out around the warmup transition rather than
                    // popping — visual swap to the playing state stays
                    // continuous instead of abrupt.
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showSpinner,
                        enter = spinnerEnter,
                        exit = spinnerExit,
                    ) {
                        MagicSpinner(modifier = Modifier.size(width = 240.dp, height = 350.dp))
                    }
                    // v1.0 polish (2026-05-16) — layer brass candle-ember
                    // particles over the cover during warming. The Ken-
                    // Burns scale on the cover + the orbiting sigil ring
                    // are both *single-element* motion signals; adding
                    // the ember drift gives the warming window a third
                    // layer (slow scale × medium ring × fast drift) so
                    // it reads as ambient magic gathering around the
                    // chapter — not just one element trying to do all
                    // the work of saying "we're working". 6 embers, 3 dp
                    // max radius, 5.6 s rise period — calm + atmospheric.
                    // Honors LocalReducedMotion (collapses to a single
                    // resting candle-glow ember at the cover bottom)
                    // and LocalAnimationSpeedScale (#589). Hidden when
                    // not warming so playback stays undecorated.
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showSpinner,
                        enter = spinnerEnter,
                        exit = spinnerExit,
                    ) {
                        BrassEmberOverlay(
                            modifier = Modifier.size(width = 220.dp, height = 330.dp),
                            emberCount = 6,
                            radiusDp = 3.dp,
                            riseDurationMs = 5600,
                        )
                    }
                }
            }
            // v1.0 polish (2026-05-16) — when the fiction title hasn't
            // resolved yet ("Conjuring your chapter…"), apply the
            // existing skeleton shimmer alpha so the placeholder text
            // breathes between 0.35..0.85 alpha at the same 1.2 s
            // cadence as every other skeleton on screen. Reads as a
            // magical incantation forming itself, not as plain
            // placeholder text waiting for a network hit. Honors
            // LocalReducedMotion + LocalAnimationSpeedScale (both
            // collapse the shimmer to a static mid-alpha via
            // shimmerAlpha() — see SkeletonShimmer.kt).
            val titleBlank = state.fictionTitle.isBlank()
            val titleShimmerAlpha = if (titleBlank) {
                `in`.jphe.storyvox.ui.component.shimmerAlpha()
            } else 1f
            Text(
                if (titleBlank) "Conjuring your chapter…" else state.fictionTitle,
                style = MaterialTheme.typography.titleLarge,
                color = if (titleBlank) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.alpha(titleShimmerAlpha),
            )
            Text(
                // Issue #166 — when we have a chapter title, KEEP it visible
                // through warmup / mid-chapter buffering instead of replacing
                // it with the state label. The user just tapped a specific
                // chapter and needs that confirmation while audio loads;
                // replacing it costs them their tap-context confirmation
                // during the exact 3-15s window that mistake-recovery
                // matters most. Spinner state is already conveyed two
                // separate ways (the play-button ring + BrassProgressTrack's
                // `loading = showSpinner`), so the subtitle text doesn't
                // need to carry it alone. When title is present + we're in
                // a state tail, append the state to the title with a "·"
                // separator so the user gets both pieces of information.
                //
                // Issue #543 — the warmup message is now voice-aware:
                // "Warming Brian…" instead of "Voice waking up…" so the
                // listener sees which neural voice is loading. Resolves
                // to "Warming voice…" when the voice label is blank
                // (cold launch before VoiceLibrary resolves the active
                // voice), matching the sibling audio-fidelity-fixer's
                // `EngineState.Warming(message)` semantic.
                //
                // Issue #537 — pre-fix the buffering branch said "idle"
                // when the player was paused mid-chapter; that semantic
                // mismatch is handled in DebugOverlay.pipelineStateText.
                // The on-screen subtitle here only ever rendered the
                // chapter title, warming label, or buffering label, so
                // no surface change needed here for #537.
                playerStatusSubtitle(
                    chapterTitle = state.chapterTitle,
                    warmingUp = warmingUp,
                    buffering = state.isBuffering,
                    voiceLabel = state.voiceLabel,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = if (showSpinner) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Issue #278 — soft slow hint: after the loading state has been
            // stuck for 10s the user should know we're still trying. The
            // hint appears under the existing "Loading voice + chapter text"
            // / chapter-title subtitle and disappears as soon as state
            // arrives. At 30s we flip to the full error block above and
            // this hint never renders.
            if (loadingPhase == LoadingPhase.Slow) {
                Text(
                    "Still working… slow voice or network. Hang tight.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(spacing.xs))
            // Issue #448 — for live audio chapters (Radio plugin's
            // streams via Media3) the position/duration counters stay
            // at 0:00/0:00 even though the stream is actively decoding,
            // because there's no end-of-content to measure against.
            // Showing the scrubber reads as "playback is stuck" — the
            // user toggles play/pause to recover, then concludes the
            // app is broken. Replace the scrubber with a "LIVE" badge
            // for live chapters; the rest of the transport (play/pause,
            // chapter prev/next, voice settings) stays the same.
            if (state.isLiveAudioChapter) {
                LiveStreamBadge()
            } else {
                BrassProgressTrack(
                    positionMs = state.positionMs,
                    durationMs = state.durationMs,
                    onSeekTo = onSeekTo,
                    modifier = Modifier.fillMaxWidth(),
                    loading = showSpinner,
                )
            }
            if (state.sleepTimerRemainingMs != null) {
                SleepTimerCountdownChip(remainingMs = state.sleepTimerRemainingMs, onCancel = onCancelSleepTimer)
            }
            Spacer(Modifier.height(spacing.xs))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onPreviousChapter()
                }) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous chapter", modifier = Modifier.size(32.dp))
                }
                IconButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSkipBack()
                }) {
                    // Issue #268 — Replay30 / Forward30 show the literal '30'
                    // inside a curved-arrow glyph, so the seek interval is
                    // legible at a glance instead of just an abstract
                    // double-chevron. The skip-30 duration is hard-coded to
                    // 30s today; if/when a configurable duration lands the
                    // icon will need a swap to a dynamic glyph too.
                    Icon(Icons.Filled.Replay30, contentDescription = "Skip back 30 seconds", modifier = Modifier.size(32.dp))
                }
                // "Warming up" = user has hit play and chapter has loaded, but
                // the TTS engine hasn't produced the first sentence yet (no
                // sentence range emitted). Sherpa-onnx model load + first
                // synth can take 5-15s on modest hardware; without this
                // indicator the play button looks dead during that gap.
                // "Buffering" = mid-chapter underrun on slow voice + slow
                // device; same spinner so the play button stays visually
                // consistent.
                val warmingUp = state.isWarmingUp
                val showSpinner = warmingUp || state.isBuffering
                // Issue #526 — the play/pause button jumped 25 dp on the
                // buffering ⇄ playing transition. Pre-fix the surrounding
                // Box had no fixed size, so it resized to whichever child
                // was largest at that moment: 96 dp when the spinner was
                // visible, 72 dp (the FilledIconButton) when it wasn't.
                // SpaceEvenly distributes around children's measured
                // widths, so every state flip nudged the whole transport
                // row vertically + horizontally by ~12-25 dp.
                //
                // Fix: pin the host Box to 96 dp regardless of which child
                // is visible. The spinner appears / disappears inside that
                // fixed bounds without resizing the row. The play icon
                // itself stays centered via Box(Alignment.Center); the
                // FilledIconButton is still 72 dp inside, so the visual
                // tap target doesn't grow. Crossfade the icon vector
                // inside the button so the Play→Pause swap is smooth
                // instead of a hard pop.
                Box(
                    modifier = Modifier.size(PLAY_BUTTON_HOST_SIZE_DP.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showSpinner,
                        enter = spinnerEnter,
                        exit = spinnerExit,
                    ) {
                        MagicSpinner(modifier = Modifier.size(96.dp))
                    }
                    // Issue #545 — at natural end, tapping the button
                    // should seek-to-0 + play (Replay semantic) rather
                    // than the default play/pause toggle. If we left
                    // onPlayPause alone, the engine would call play()
                    // on a playhead already at durationMs and nothing
                    // would happen — the button would visually do
                    // nothing on tap. Seeking to 0 first restarts the
                    // chapter from the beginning, matching the icon's
                    // visual promise.
                    val isAtNaturalEndForClick = !state.isPlaying &&
                        state.durationMs > 0L &&
                        state.positionMs >= state.durationMs - END_TOLERANCE_MS &&
                        state.chapterId != null
                    FilledIconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            if (isAtNaturalEndForClick) {
                                onSeekTo(0L)
                            }
                            onPlayPause()
                        },
                        modifier = Modifier.size(72.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        // Crossfade the play/pause vector so the swap
                        // doesn't pop. Honors reduced-motion via the
                        // same `spinnerEnter` family above — when
                        // reduced motion is on, the icon swap becomes
                        // instantaneous (matches the rest of Library
                        // Nocturne's reduced-motion fall-back pattern).
                        // Issue #545 — when the playhead is at/past
                        // natural end with !isPlaying, the button labels
                        // itself "Replay" rather than the misleading
                        // "Play" (tapping it loops back to start — it
                        // doesn't resume mid-chapter, because there's
                        // nothing left to resume). For multi-chapter
                        // books the engine's auto-advance flips
                        // isPlaying back to true on advance, so this
                        // branch only fires when there's literally no
                        // more audio to play — single-chapter books or
                        // the last chapter of a multi-chapter book.
                        // Tolerance constant captures both the engine's
                        // duration-estimate slop and the parallel
                        // gapless-investigator's truncation fix window.
                        val playPauseIconState = derivePlayPauseIconState(
                            isPlaying = state.isPlaying,
                            positionMs = state.positionMs,
                            durationMs = state.durationMs,
                            chapterId = state.chapterId,
                        )
                        androidx.compose.animation.Crossfade(
                            targetState = playPauseIconState,
                            animationSpec = tween(
                                if (reducedMotion) 0 else motion.standardDurationMs,
                                easing = motion.standardEasing,
                            ),
                            label = "play-pause-icon",
                        ) { iconState ->
                            Icon(
                                imageVector = iconState.imageVector,
                                contentDescription = iconState.label,
                                modifier = Modifier.size(40.dp),
                            )
                        }
                    }
                }
                IconButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSkipForward()
                }) {
                    Icon(Icons.Filled.Forward30, contentDescription = "Skip forward 30 seconds", modifier = Modifier.size(32.dp))
                }
                IconButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onNextChapter()
                }) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next chapter", modifier = Modifier.size(32.dp))
                }
            }
            // Issue #527 — Spotify / Apple Music / Libby all expose
            // speed, sleep timer, and a quick voice/output picker on
            // the main player surface, one tap away. v0.5.52's player
            // hid all three behind the brass voice icon's quick sheet
            // and the ⋮ overflow; the discoverability cost was a
            // 0-of-5 tap-test failure during the 2026-05-15 audit
            // ("missing: Speed, Sleep timer, Voice picker, Cast,
            // Chapter-list jump"). The PlayerQuickChips row sits
            // directly under the transport so the most-tweaked knobs
            // are flat — preset speed chips (0.75/1.0/1.25/1.5/2.0×),
            // sleep timer presets (Off/5/15/30/60/End), voice chip
            // showing the current voice with a tap-to-open-picker
            // affordance. The detailed slider-based sheets (#418) stay
            // available via the brass voice icon for users who want
            // continuous-knob tuning; the chips are the *fast path*.
            PlayerQuickChips(
                state = state,
                onSetSpeed = { presetSpeed ->
                    onSetSpeed(presetSpeed)
                    // Persist preset speed immediately — discrete presets
                    // are commit-on-tap (the user expressed intent
                    // exactly), unlike the continuous slider where we
                    // only persist on onValueChangeFinished.
                    onPersistSpeed(presetSpeed)
                },
                onStartSleepTimer = onStartSleepTimer,
                onCancelSleepTimer = onCancelSleepTimer,
                onPickVoice = onPickVoice,
                chapterCount = chapters.size,
                onOpenChapterList = { showChapterListSheet = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = spacing.xs),
            )
        }

        // Candlelight scrim — a translucent brass tone composited over
        // the default scrim color, instead of a flat black dim. Reads
        // as "the light's been turned down", not "the screen got eaten".
        // Shared between both sheets so they feel like the same
        // brass-edged "shade-down" affordance, not two different surfaces.
        val brassScrim = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
            .compositeOver(MaterialTheme.colorScheme.scrim.copy(alpha = 0.42f))

        // Issue #418 — magical voice settings sheet. Half-expanded by
        // default (skipPartiallyExpanded = false would force half-state;
        // we want the user to be able to drag UP for the Advanced
        // expander, so we keep true and let the sheet auto-size to its
        // content). The Speed slider is the first row so the most-used
        // knob lands under the user's thumb the instant the sheet opens.
        if (showVoiceSheet) {
            val voiceSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { showVoiceSheet = false },
                sheetState = voiceSheetState,
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                scrimColor = brassScrim,
                tonalElevation = 6.dp,
            ) {
                val voiceScope = rememberCoroutineScope()
                VoiceQuickSheetContent(
                    state = state,
                    punctuationPauseMultiplier = punctuationPauseMultiplier,
                    pitchInterpolationHighQuality = pitchInterpolationHighQuality,
                    onSetSpeed = onSetSpeed,
                    onPersistSpeed = onPersistSpeed,
                    onSetPitch = onSetPitch,
                    onPersistPitch = onPersistPitch,
                    onSetPunctuationPause = onSetPunctuationPause,
                    onSetPitchHighQuality = onSetPitchHighQuality,
                    onPickVoice = {
                        voiceScope.launch { voiceSheetState.hide() }
                        showVoiceSheet = false
                        onPickVoice()
                    },
                    onOpenAdvancedVoice = {
                        voiceScope.launch { voiceSheetState.hide() }
                        showVoiceSheet = false
                        // Deep-link into Voice Library — same destination
                        // as long-press on the icon. The lexicon +
                        // phonemizer pickers live there (VoiceLibraryScreen
                        // lines 364/367/451/454); replicating them here
                        // would force SAF + Kokoro detection into the
                        // player layer for no UX gain.
                        onPickVoice()
                    },
                )
            }
        }

        // Issue #418 — the post-split overflow sheet. Keeps only the
        // non-voice items: sentence-step transport (#120), sleep timer,
        // bookmark drop/jump (#121), recap (#81), AI chat. Voice
        // settings (speed/pitch/voice/pause/quality) have moved out
        // entirely to the voice quick sheet above.
        if (showOverflowSheet) {
            val overflowSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { showOverflowSheet = false },
                sheetState = overflowSheetState,
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                scrimColor = brassScrim,
                tonalElevation = 6.dp,
            ) {
                val overflowScope = rememberCoroutineScope()
                PlayerOverflowSheet(
                    state = state,
                    onStartSleepTimer = onStartSleepTimer,
                    onCancelSleepTimer = onCancelSleepTimer,
                    onPreviousSentence = onPreviousSentence,
                    onNextSentence = onNextSentence,
                    onRequestRecap = {
                        overflowScope.launch { overflowSheetState.hide() }
                        showOverflowSheet = false
                        onRequestRecap()
                    },
                    onOpenChat = {
                        overflowScope.launch { overflowSheetState.hide() }
                        showOverflowSheet = false
                        onOpenChat()
                    },
                    onBookmarkHere = {
                        overflowScope.launch { overflowSheetState.hide() }
                        showOverflowSheet = false
                        onBookmarkHere()
                    },
                    onJumpToBookmark = {
                        overflowScope.launch { overflowSheetState.hide() }
                        showOverflowSheet = false
                        onJumpToBookmark()
                    },
                )
            }
        }

        if (showChapterListSheet) {
            val chapterSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            val chapterScope = rememberCoroutineScope()
            ModalBottomSheet(
                onDismissRequest = { showChapterListSheet = false },
                sheetState = chapterSheetState,
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                scrimColor = brassScrim,
                tonalElevation = 6.dp,
            ) {
                ChapterListSheet(
                    chapters = chapters,
                    currentChapterId = state.chapterId,
                    onChapterSelected = { chapterId ->
                        chapterScope.launch { chapterSheetState.hide() }
                        showChapterListSheet = false
                        onPlayChapter(chapterId)
                    },
                )
            }
        }
    }
}

@Composable
private fun ChapterListSheet(
    chapters: List<`in`.jphe.storyvox.feature.api.UiChapter>,
    currentChapterId: String?,
    onChapterSelected: (chapterId: String) -> Unit,
) {
    val spacing = LocalSpacing.current
    val currentIndex = remember(chapters, currentChapterId) {
        chapters.indexOfFirst { it.id == currentChapterId }.coerceAtLeast(0)
    }
    val listState = rememberLazyListState()
    LaunchedEffect(currentIndex) {
        if (chapters.isNotEmpty()) listState.scrollToItem(currentIndex)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.lg)
            .padding(bottom = spacing.lg),
    ) {
        Text(
            text = stringResource(R.string.reader_chapters),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = spacing.sm),
        )
        if (chapters.isEmpty()) {
            Text(
                text = "No chapters available yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = spacing.md),
            )
        } else {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(spacing.xxs),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(chapters, key = { it.id }) { chapter ->
                    ChapterListRow(
                        chapter = chapter,
                        isCurrent = chapter.id == currentChapterId,
                        onClick = { onChapterSelected(chapter.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChapterListRow(
    chapter: `in`.jphe.storyvox.feature.api.UiChapter,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val bgColor = if (isCurrent) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val fgColor = if (isCurrent) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val rowDescription = buildString {
        append("Chapter ${chapter.number}, ${chapter.title}, ${chapter.durationLabel}")
        if (isCurrent) append(", currently playing")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor, shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = spacing.md, vertical = spacing.sm)
            .semantics(mergeDescendants = true) {
                contentDescription = rowDescription
                role = Role.Button
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = "${chapter.number}",
            style = MaterialTheme.typography.labelLarge,
            color = fgColor,
            modifier = Modifier.widthIn(min = 28.dp),
        )
        Text(
            text = chapter.title.ifBlank { "Chapter ${chapter.number}" },
            style = MaterialTheme.typography.bodyMedium,
            color = fgColor,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = chapter.durationLabel,
            style = MaterialTheme.typography.labelMedium,
            color = if (isCurrent) fgColor else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Issue #448 — pill-shaped "LIVE" indicator for live-audio chapters
 * (Radio plugin / KVMR). Replaces the BrassProgressTrack scrubber on
 * the player surface because position/duration counters don't apply
 * to a live stream — showing the scrubber stuck at 0:00 reads as a
 * stalled playback bug. The pill has a subtle pulsing dot so the
 * user reads "alive" at a glance.
 */
@Composable
private fun LiveStreamBadge() {
    val spacing = LocalSpacing.current
    val reducedMotion = LocalReducedMotion.current
    val infinite = rememberInfiniteTransition(label = "live-pulse")
    val pulseAlpha by infinite.animateFloat(
        initialValue = if (reducedMotion) 0.95f else 0.45f,
        targetValue = if (reducedMotion) 0.95f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "live-pulse-alpha",
    )
    val errorColor = MaterialTheme.colorScheme.error
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.md),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = spacing.lg, vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .alpha(pulseAlpha),
            ) {
                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(errorColor)
                }
            }
            Text(
                "LIVE",
                style = MaterialTheme.typography.labelLarge,
                color = errorColor,
            )
        }
    }
}

@Composable
private fun SleepTimerCountdownChip(remainingMs: Long, onCancel: () -> Unit) {
    val mins = (remainingMs / 60_000L).toInt()
    val secs = ((remainingMs % 60_000L) / 1000L).toInt()
    val reducedMotion = LocalReducedMotion.current
    val motion = LocalMotion.current

    // Last 60s: gentle alpha breath. Last 15s: cross-fade container toward
    // errorContainer hue to signal "almost out of time" without alarm-bell
    // urgency. Reduced motion → static at full alpha + base color.
    val isPulsing = !reducedMotion && remainingMs in 1..60_000
    val isUrgent = remainingMs in 1..15_000

    val pulseAlpha = if (isPulsing) {
        val transition = rememberInfiniteTransition(label = "sleep-timer-breath")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.55f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1200, easing = motion.standardEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "sleep-timer-alpha",
        ).value
    } else 1f

    val baseContainer = MaterialTheme.colorScheme.primaryContainer
    val urgentContainer = MaterialTheme.colorScheme.errorContainer
    val baseLabel = MaterialTheme.colorScheme.onPrimaryContainer
    val urgentLabel = MaterialTheme.colorScheme.onErrorContainer

    val containerColor by animateColorAsState(
        targetValue = if (isUrgent && !reducedMotion) urgentContainer else baseContainer,
        animationSpec = tween(motion.standardDurationMs, easing = motion.standardEasing),
        label = "sleep-timer-container",
    )
    val labelColor by animateColorAsState(
        targetValue = if (isUrgent && !reducedMotion) urgentLabel else baseLabel,
        animationSpec = tween(motion.standardDurationMs, easing = motion.standardEasing),
        label = "sleep-timer-label",
    )

    AssistChip(
        onClick = onCancel,
        label = {
            Text(stringResource(R.string.reader_sleep_sleeping_in, mins, secs), style = MaterialTheme.typography.labelMedium)
        },
        leadingIcon = {
            Icon(Icons.Outlined.Bedtime, contentDescription = null)
        },
        modifier = Modifier.alpha(pulseAlpha),
        colors = AssistChipDefaults.assistChipColors(
            containerColor = containerColor,
            labelColor = labelColor,
            leadingIconContentColor = labelColor,
        ),
    )
}

/**
 * Issue #418 — the post-split overflow sheet (was `PlayerOptionsSheet`).
 * Voice settings (speed / pitch / voice picker) have moved to the
 * VoiceQuickSheet driven by the brass voice icon. This sheet keeps the
 * remaining non-voice surface: sentence-step transport (#120), sleep
 * timer, bookmark (#121), recap (#81), AI chat.
 *
 * Pre-#418 history: this composable was the single "Player options"
 * sheet behind ⋮; the issue body identified the voice-tuning subset as
 * the high-frequency, low-discoverability slice and pulled it out into
 * its own always-visible icon. Splitting keeps the overflow lean.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerOverflowSheet(
    state: UiPlaybackState,
    onStartSleepTimer: (UiSleepTimerMode) -> Unit,
    onCancelSleepTimer: () -> Unit,
    /** #120 — step back one sentence boundary. No-op at sentence 0. */
    onPreviousSentence: () -> Unit = {},
    /** #120 — step forward one sentence boundary. No-op at chapter end. */
    onNextSentence: () -> Unit = {},
    onRequestRecap: () -> Unit = {},
    onOpenChat: () -> Unit = {},
    /** Issue #121 — bookmark the current playback position. */
    onBookmarkHere: () -> Unit = {},
    /** Issue #121 — seek to the chapter's bookmark, if any. */
    onJumpToBookmark: () -> Unit = {},
) {
    val spacing = LocalSpacing.current
    val haptic = LocalHapticFeedback.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .padding(horizontal = spacing.lg, vertical = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        // #120 — sentence-step transport. The main bottom-bar buttons
        // do ±30 s (consistent with audiobook-player muscle memory);
        // these sit in the options sheet for users who want
        // sentence-precision rewind/fast-forward (re-listen the line
        // you just heard, or skip a sentence you didn't want).
        SheetHeader(stringResource(R.string.reader_sleep_step_by_sentence), null)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrassButton(
                label = stringResource(R.string.reader_step_previous),
                onClick = onPreviousSentence,
                variant = BrassButtonVariant.Secondary,
                modifier = Modifier.weight(1f),
            )
            BrassButton(
                label = stringResource(R.string.reader_step_next),
                onClick = onNextSentence,
                variant = BrassButtonVariant.Secondary,
                modifier = Modifier.weight(1f),
            )
        }

        SheetHeader(stringResource(R.string.reader_sleep_timer), null)
        SleepTimerChips(
            activeRemainingMs = state.sleepTimerRemainingMs,
            onStart = onStartSleepTimer,
            onCancel = onCancelSleepTimer,
        )

        // Issue #121 — in-chapter bookmark. Two rows: "Bookmark here"
        // drops a marker at the current playback position; "Jump to
        // bookmark" seeks to it. Both fire-and-forget; the controller
        // no-ops gracefully when nothing is loaded / no bookmark exists.
        SheetHeader(stringResource(R.string.reader_bookmark_header), null)
        // a11y (#481): Role.Button for the action rows in the
        // bookmark / smart-feature sheet — TalkBack reads the title
        // text via merge, plus the Button role + onClickLabel verb.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.reader_bookmark_here), onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onBookmarkHere()
                })
                .padding(vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Icon(
                Icons.Outlined.BookmarkAdd,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.reader_bookmark_here), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.reader_bookmark_here_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.reader_jump_to_bookmark), onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onJumpToBookmark()
                })
                .padding(vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Icon(
                Icons.Outlined.Bookmark,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.reader_jump_to_bookmark), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.reader_jump_to_bookmark_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Issue #418 — the Voice row that used to live here has moved
        // to the dedicated VoiceQuickSheet driven by the brass voice
        // icon in the top bar. The whole voice-tuning surface
        // (speed / pitch / voice picker / pause / sonic quality +
        // Advanced expander) is now one-tap-away on the icon rather
        // than two-tap behind the ⋮.

        // ── Chapter Recap (issue #81) — opens the librarian modal ──
        SheetHeader(stringResource(R.string.reader_smart_features), null)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.reader_recap_so_far), onClick = onRequestRecap)
                .padding(vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Icon(
                Icons.Outlined.AutoStories,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.reader_recap_so_far), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.reader_recap_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ── Q&A chat (#81 follow-up) — opens the librarian chat surface ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.reader_open_librarian_chat), onClick = onOpenChat)
                .padding(vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Icon(
                Icons.Outlined.AutoStories,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.reader_ask_the_ai), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.reader_ask_ai_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(spacing.md))
    }
}

@Composable
private fun SheetHeader(title: String, valueLabel: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        if (valueLabel != null) {
            Text(valueLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SleepTimerChips(
    activeRemainingMs: Long?,
    onStart: (UiSleepTimerMode) -> Unit,
    onCancel: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val isActive = activeRemainingMs != null

    // #249 — FlowRow instead of Row so the 6 chips wrap to a second
    // line on phone-narrow screens (Flip3 = 1080 px) instead of
    // squashing the rightmost chip to ~67 px wide. FlowRow preserves
    // natural chip widths and breaks at chip boundaries; on tablets
    // and unfolded foldables they still render in one row.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
        modifier = Modifier.fillMaxWidth(),
    ) {
        FilterChip(
            selected = !isActive,
            onClick = { onCancel() },
            label = { Text(stringResource(R.string.reader_sleep_off)) },
            colors = brassFilterChipColors(),
        )
        listOf(15, 30, 45, 60).forEach { minutes ->
            FilterChip(
                selected = false,
                onClick = { onStart(UiSleepTimerMode.Duration(minutes)) },
                label = { Text(stringResource(R.string.reader_sleep_minutes, minutes)) },
                colors = brassFilterChipColors(),
            )
        }
        FilterChip(
            selected = false,
            onClick = { onStart(UiSleepTimerMode.EndOfChapter) },
            label = { Text(stringResource(R.string.reader_sleep_end)) },
            colors = brassFilterChipColors(),
        )
    }
}

/**
 * Brass-themed [FilterChipDefaults.filterChipColors] used across the
 * player surface chip rows (PlayerQuickChips) and the voice quick
 * sheet's chip presets ([VoiceQuickSheetContent]). Selected state
 * uses the brass primary-container fill so the active preset reads
 * as the "currently set" anchor; unselected stays subtle so a row of
 * 5-6 chips doesn't visually shout.
 *
 * Exposed `internal` so [VoiceQuickSheetContent] can use the same
 * palette as [PlayerQuickChips] without duplicating the definition.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun brassFilterChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
)

/**
 * Issue #284 — format the voiceId carried on [UiPlaybackState.voiceLabel]
 * into a human-readable `[engine] · [voice]` string for the player-
 * options sheet.
 *
 *  - `piper:en_US-amy-medium`            → `Piper · en_US-amy-medium`
 *  - `azure:en-US-AvaMultilingualNeural` → `Azure · en-US-AvaMultilingualNeural`
 *  - `voxsherpa:tier3/narrator-warm`     → `VoxSherpa · tier3/narrator-warm`
 *  - `Default` (no active voice yet)     → `Default`
 *  - bare strings without `:` prefix     → returned untouched
 *
 *  We deliberately don't try to resolve the voice's *display name* from
 *  the voice catalog here — that would couple this composable to the
 *  voicelibrary module + force a Hilt-injected lookup at every Player
 *  Options sheet open. The raw voice id is already engine + voice name;
 *  reformatting the prefix is enough for the QA / verification use case
 *  the issue calls out.
 */
internal fun formatVoiceLabel(raw: String): String {
    if (raw.isBlank() || !raw.contains(':')) return raw
    val (engineId, voiceId) = raw.split(':', limit = 2)
    val engine = when (engineId.lowercase()) {
        "piper" -> "Piper"
        "azure" -> "Azure"
        "voxsherpa", "sherpa", "kokoro" -> "VoxSherpa"
        "android", "system" -> "System TTS"
        else -> engineId.replaceFirstChar { it.uppercase() }
    }
    return "$engine · $voiceId"
}

/**
 * Issue #609 (v1.0 blocker) — humanize a raw engine:voiceId for TalkBack.
 *
 * The visual chip can show `Piper · piper_vctk_en_GB_medium` (a
 * power-user-readable identifier) without confusing sighted users —
 * they parse the engine prefix at a glance. TalkBack on R5CRB0W66MK
 * reads that string out *literally*, character by character — "piper
 * underscore vctk underscore en underscore GB underscore medium" —
 * which is a 12-syllable bedlam for what should be one phrase.
 *
 * Humanization rules, ordered to match real catalog ids:
 *  1. Strip the engine prefix already separated by the colon.
 *  2. Replace underscores / hyphens / dots with spaces so words break.
 *  3. Recognise common locale tokens (`en_US`, `en-GB`, `en`, `pt_BR`)
 *     and rewrite them to "English (United States)" / "English (United
 *     Kingdom)" / "English" / "Portuguese (Brazil)".
 *  4. Recognise voice-tier qualifiers (`medium`, `high`, `low`,
 *     `multilingual`, `studio`, `turbo`, `hd`, `dragon`) and keep
 *     them as separate words.
 *  5. Title-case each remaining token (catalog names like `vctk`,
 *     `ljspeech`, `aria` become `Vctk`, `Ljspeech`, `Aria` — a small
 *     loss for the catalog-acronym case, but readable for narrator
 *     names which are the common case).
 *  6. Prepend the engine name from [formatVoiceLabel].
 *
 * The output is a TalkBack-only string; the visible chip label still
 * uses [formatVoiceLabel] so power users keep their identifier
 * preview. Test coverage in AudiobookViewSemanticsTest.
 */
internal fun humanizeVoiceLabel(raw: String): String {
    if (raw.isBlank()) return "no voice selected"
    if (raw.equals("Default", ignoreCase = true)) return "Default"
    // Two-shape support:
    //  - "piper:en_US-amy-medium" (engine:voiceId form, what the API
    //    surfaces when an engine prefix is present)
    //  - "piper_en_US_amy_medium" (the same voice arriving as a flat
    //    catalog id — what `AppBindings.voiceLabel = voiceId` actually
    //    passes through when the upstream `voiceId` is the bare
    //    catalog identifier, observed on R83W80CAFZB 2026-05-16).
    // We detect the colon-free shape and treat the first
    // recognisably-engine token as the engine prefix.
    val (engineId, voiceId) = if (raw.contains(':')) {
        raw.split(':', limit = 2).let { it[0] to it[1] }
    } else {
        // Heuristic: if the first underscore/hyphen-separated token
        // matches a known engine, treat it as the engine prefix.
        // Otherwise we pass the whole raw string through the
        // tokenizer (no engine prefix preserved).
        val firstSplit = raw.split('_', '-', '.', limit = 2)
        val first = firstSplit.firstOrNull()?.lowercase()
        if (first in setOf("piper", "azure", "voxsherpa", "sherpa", "kokoro", "android", "system")) {
            first!! to (firstSplit.getOrNull(1) ?: "")
        } else {
            "" to raw
        }
    }
    val engine = when (engineId.lowercase()) {
        "piper" -> "Piper"
        "azure" -> "Azure"
        "voxsherpa", "sherpa", "kokoro" -> "VoxSherpa"
        "android", "system" -> "System TTS"
        "" -> ""
        else -> engineId.replaceFirstChar { it.uppercase() }
    }

    // Step 1 — split on the catalog separators. Keep tokens lowercased
    // until the locale + tier rewrites have run; case-restore is the
    // last step so the rewrite tables don't have to know about case.
    val rawTokens = voiceId
        .replace('_', ' ')
        .replace('-', ' ')
        .replace('.', ' ')
        .split(' ')
        .filter { it.isNotBlank() }

    // Step 2 — locale + tier rewrite. We walk the tokens, peek for
    // common two-segment locales (`en us` from `en_US`, etc.) so we
    // can rewrite them into one human phrase before per-token
    // title-casing fires.
    val humanized = mutableListOf<String>()
    var i = 0
    while (i < rawTokens.size) {
        val t = rawTokens[i].lowercase()
        val pair = rawTokens.getOrNull(i + 1)?.lowercase()
        val locale = when (t to pair) {
            "en" to "us" -> "English (United States)"
            "en" to "gb" -> "English (United Kingdom)"
            "en" to "au" -> "English (Australia)"
            "en" to "ca" -> "English (Canada)"
            "en" to "in" -> "English (India)"
            "en" to "ie" -> "English (Ireland)"
            "es" to "es" -> "Spanish (Spain)"
            "es" to "mx" -> "Spanish (Mexico)"
            "pt" to "br" -> "Portuguese (Brazil)"
            "pt" to "pt" -> "Portuguese (Portugal)"
            "zh" to "cn" -> "Mandarin (China)"
            "zh" to "tw" -> "Mandarin (Taiwan)"
            "fr" to "fr" -> "French (France)"
            "fr" to "ca" -> "French (Canada)"
            "de" to "de" -> "German"
            "ja" to "jp" -> "Japanese"
            "ko" to "kr" -> "Korean"
            "ru" to "ru" -> "Russian"
            "it" to "it" -> "Italian"
            "nl" to "nl" -> "Dutch"
            else -> null
        }
        if (locale != null) {
            humanized.add(locale)
            i += 2
            continue
        }
        // Single-token locale fallback. Bare "en" / "es" / "fr".
        val singleLocale = when (t) {
            "en" -> "English"
            "es" -> "Spanish"
            "pt" -> "Portuguese"
            "fr" -> "French"
            "de" -> "German"
            "ja" -> "Japanese"
            "ko" -> "Korean"
            "ru" -> "Russian"
            "it" -> "Italian"
            "nl" -> "Dutch"
            "zh" -> "Mandarin"
            else -> null
        }
        if (singleLocale != null) {
            humanized.add(singleLocale)
            i++
            continue
        }
        // Tier rewrite — keep canonical casing.
        val tier = when (t) {
            "medium" -> "Medium quality"
            "high" -> "High quality"
            "low" -> "Low quality"
            "multilingual" -> "Multilingual"
            "studio" -> "Studio"
            "turbo" -> "Turbo"
            "hd" -> "HD"
            "dragon" -> "Dragon"
            else -> null
        }
        if (tier != null) {
            humanized.add(tier)
            i++
            continue
        }
        // Default — title-case the token. `vctk` → `Vctk`,
        // `aria` → `Aria`, `narratorwarm` → `Narratorwarm`. Narrator
        // names are the common case; acronyms losing their all-caps
        // appearance is the acceptable trade.
        humanized.add(t.replaceFirstChar { it.uppercase() })
        i++
    }
    val body = humanized.joinToString(" ")
    return when {
        engine.isBlank() && body.isBlank() -> "no voice selected"
        engine.isBlank() -> body
        body.isBlank() -> engine
        else -> "$engine $body"
    }
}

// ─── Issue #526 — play-button host sizing ────────────────────────────
/**
 * Pin the play/pause host Box to this size in dp regardless of whether
 * the warmup/buffering spinner is currently visible inside it. The
 * spinner is 96 dp; the FilledIconButton is 72 dp. Pre-#526 the host
 * Box wrapped to whichever child was visible, shifting the transport
 * row layout by ~25 dp on every state transition.
 *
 * Exposed `internal` so [AudiobookViewLayoutTest] can pin the value
 * without rendering the composable; pre-#526 a future "make play
 * button bigger" change would re-introduce the layout shift if the
 * host size doesn't grow alongside.
 */
internal const val PLAY_BUTTON_HOST_SIZE_DP: Int = 96

// ─── Issue #545 — natural-end detection tolerance ────────────────────
/**
 * Issue #545 — how close to durationMs the playhead has to be for the
 * Play button to relabel itself "Replay." 5.5s tolerance accounts for
 * the truncation bug being fixed in parallel by the gapless-investigator
 * agent (the PCM stream apparently drops ~5s before the actual chapter
 * end on some chapters) AND general durationEstimateMs imprecision —
 * the engine's duration is computed from sentence token counts, not
 * from rendering the audio, so it carries 1-3s of estimation slop.
 *
 * If the gapless-investigator's truncation fix lands and tightens the
 * actual playback-end-vs-duration gap, this tolerance can shrink to
 * ~1500 ms without losing the "Replay button is correct" UX. Until
 * then 5.5s captures both modes.
 */
internal const val END_TOLERANCE_MS: Long = 5_500L

/**
 * Issue #545 — three-state icon enum for the play/pause button.
 * `Replay` is the new state added in this patch: surfaces when the
 * playhead is at natural end of the loaded chapter and !isPlaying.
 * Carries both the imageVector and the TalkBack content description
 * inline so the Crossfade content-lambda stays trivial.
 *
 * Exposed `internal` so a future Crossfade key test (or
 * AudiobookViewLayoutTest) can pin which state renders for given
 * (isPlaying, positionMs, durationMs) tuples.
 */
internal enum class PlayPauseIconState(
    val imageVector: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String,
) {
    Play(Icons.Filled.PlayArrow, "Play"),
    Pause(Icons.Filled.Pause, "Pause"),
    Replay(Icons.Filled.Replay, "Replay"),
}

/**
 * Issue #545 — pure derivation of the play/pause/replay state from
 * the [UiPlaybackState] surface fields. Extracted from the composable
 * so the (isPlaying, positionMs, durationMs, chapterId) → state map
 * can be unit-tested without Compose.
 *
 * Rules (precedence top-to-bottom):
 *  1. `isPlaying` → Pause.
 *  2. `!isPlaying && chapterId != null && durationMs > 0 &&
 *     positionMs >= durationMs - [END_TOLERANCE_MS]` → Replay.
 *  3. otherwise → Play.
 *
 * The `chapterId != null` gate keeps the Idle/no-chapter state on
 * Play instead of Replay (positionMs is 0 in that case but durationMs
 * is also 0, so the inequality folds to "0 >= -5500" which is true
 * without the chapter-id gate).
 */
internal fun derivePlayPauseIconState(
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    chapterId: String?,
): PlayPauseIconState = when {
    isPlaying -> PlayPauseIconState.Pause
    chapterId != null &&
        durationMs > 0L &&
        positionMs >= durationMs - END_TOLERANCE_MS -> PlayPauseIconState.Replay
    else -> PlayPauseIconState.Play
}

// ─── Issue #525 — cover-tap transient feedback ───────────────────────
/**
 * Duration (ms) of the centered Play/Pause icon overlay that fades in
 * + out on a cover tap. Long enough to register (~one read) but short
 * enough not to obstruct the cover when the user is intentionally
 * watching the chapter title or scrubber.
 *
 * 400 ms is the Spotify/Pocket Casts default for this same pattern —
 * the audit reference benchmark.
 */
internal const val COVER_FEEDBACK_DURATION_MS: Long = 400L

/**
 * Issue #525 — transient overlay icon that appears on cover tap. The
 * caller stamps [startedAtMs] with `System.currentTimeMillis()` at the
 * tap moment and tells us which icon (Play or Pause) reflects what
 * just happened. The composable fades in for ~120 ms, holds for ~160
 * ms, fades out for ~120 ms, then unmounts. Outside the window the
 * overlay is invisible (alpha = 0) and the cover renders undecorated.
 *
 * The overlay sits inside the same Box that hosts the cover and the
 * MagicSpinner (the cover-area Box in [AudiobookView]); we don't
 * intercept pointer events here — the cover's `clickable` still owns
 * taps, and this composable is presentation-only.
 */
@Composable
private fun CoverTapFeedback(
    startedAtMs: Long,
    showPauseIcon: Boolean,
) {
    val motion = LocalMotion.current
    val reducedMotion = LocalReducedMotion.current

    // Track visibility off a local recomposition trigger. We use a
    // LaunchedEffect keyed on startedAtMs so each tap restarts the
    // fade cycle from scratch even if the previous one was still in
    // flight (rapid double-tap). The effect flips `visible` true,
    // waits the full duration, then flips false; AnimatedVisibility
    // owns the fade curve.
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(startedAtMs) {
        if (startedAtMs == 0L) return@LaunchedEffect
        visible = true
        delay(COVER_FEEDBACK_DURATION_MS)
        visible = false
    }

    val enter = if (reducedMotion) EnterTransition.None else fadeIn(
        animationSpec = tween(motion.standardDurationMs, easing = motion.standardEasing),
    )
    val exit = if (reducedMotion) ExitTransition.None else fadeOut(
        animationSpec = tween(motion.standardDurationMs, easing = motion.standardEasing),
    )
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        enter = enter,
        exit = exit,
    ) {
        // Brass-tinted circle backdrop so the icon reads against any
        // cover artwork. Alpha 0.62 keeps the cover faintly visible
        // through the overlay — matches the candlelight-dim aesthetic
        // for the bottom-sheet scrim.
        Box(
            modifier = Modifier.size(96.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .alpha(0.62f)
                    .background(
                        color = MaterialTheme.colorScheme.scrim,
                        shape = androidx.compose.foundation.shape.CircleShape,
                    ),
            )
            Icon(
                imageVector = if (showPauseIcon) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = null, // a11y: the cover's onClickLabel already announces the action.
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(56.dp),
            )
        }
    }
}

// ─── Issue #543 — voice warming indicator ────────────────────────────
/**
 * Pure helper for the player's status-subtitle text. Encodes the
 * label-vs-spinner branches outside the composable so [AudiobookViewTextTest]
 * can pin the wording without an Android renderer.
 *
 * The voice-aware "Warming Brian…" copy (#543) requires the active
 * voice's display name; we derive it from [UiPlaybackState.voiceLabel]
 * via [warmingMessageForVoice] so the label flips to "Warming Brian…"
 * once a Brian voice is selected and "Warming voice…" before any
 * voice resolves (cold launch / blank label).
 *
 * Future contract bridge (sibling agent's [in.jphe.storyvox.playback.PlaybackController]):
 *  when the engine exposes a real [EngineState.Warming(message, progress)]
 *  upstream, the message should override our derived string here. Until
 *  the upstream message lands, deriving the name from voiceLabel keeps
 *  the UX honest with zero coupling.
 *
 * TODO(audio-fidelity-fixer): once `EngineState.Warming(message, progress)`
 *  publishes, plumb the upstream `message` through UiPlaybackState
 *  (new field) and prefer it over [warmingMessageForVoice] when present.
 *  Progress, when non-null, drives the thin progress bar described in
 *  the spec — currently absent because we have no signal to feed it.
 */
internal fun playerStatusSubtitle(
    chapterTitle: String,
    warmingUp: Boolean,
    buffering: Boolean,
    voiceLabel: String,
): String = when {
    chapterTitle.isBlank() -> "Loading voice + chapter text"
    warmingUp -> "$chapterTitle · ${warmingMessageForVoice(voiceLabel)}"
    buffering -> "$chapterTitle · Buffering…"
    else -> chapterTitle
}

/**
 * Derive a voice-aware warming message from the voiceLabel. The label
 * arrives as `engine:voiceId` (see [formatVoiceLabel]); we extract a
 * proper-noun name out of the voiceId where one is recognizable
 * (Brian, Ava, Amy, Ryan, Lessac, Aria, etc.) and otherwise fall back
 * to a generic "Warming voice…".
 *
 * Voice-name extraction is best-effort regex: voice ids look like
 *   `en-US-BrianNeural`, `en-US-AvaMultilingualNeural`, `en_US-amy-medium`,
 *   `en_US-lessac-low`, `tier3/narrator-warm`.
 *
 * The first capitalized-token-with-2+-letters after the locale prefix
 * is the speaker name in the BCP-47/Azure naming convention; for Piper
 * the lowercase token between two dashes is the name (`amy`, `lessac`).
 * For VoxSherpa custom voices (`tier3/narrator-warm`) we don't try to
 * extract a name — those are descriptive not first-name — and fall
 * back to "Warming voice…".
 *
 * Exposed `internal` so [AudiobookViewTextTest] can pin per-voice cases.
 */
internal fun warmingMessageForVoice(voiceLabel: String): String {
    if (voiceLabel.isBlank()) return "Warming voice…"
    // Engine prefix split: "azure:en-US-BrianNeural" → "en-US-BrianNeural"
    val voiceId = if (voiceLabel.contains(':')) {
        voiceLabel.substringAfter(':')
    } else voiceLabel

    // Azure-style: locale tokens separated by '-', last token PascalCase
    // and ends with "Neural" or similar — speaker name is the first
    // capitalized chunk after the locale.
    //   en-US-BrianNeural               → Brian
    //   en-US-AvaMultilingualNeural     → Ava
    //   en-GB-RyanNeural                → Ryan
    val azureMatch = Regex("""^[a-z]{2,3}-[A-Z]{2}-([A-Z][a-z]+)""").find(voiceId)
    if (azureMatch != null) {
        val name = azureMatch.groupValues[1]
        return "Warming $name…"
    }

    // Piper-style: locale_REGION-name-quality
    //   en_US-amy-medium                → Amy
    //   en_US-lessac-low                → Lessac
    //   en_GB-alan-medium               → Alan
    val piperMatch = Regex("""^[a-z]{2,3}_[A-Z]{2}-([a-z]+)-""").find(voiceId)
    if (piperMatch != null) {
        val raw = piperMatch.groupValues[1]
        // Skip generic words that aren't proper-noun names so we don't
        // surface "Warming Narrator…" — sounds robotic.
        if (raw !in GENERIC_VOICE_TOKENS) {
            return "Warming ${raw.replaceFirstChar { it.uppercaseChar() }}…"
        }
    }

    return "Warming voice…"
}

/** Voice-id tokens that aren't real speaker names — they're voice
 *  descriptors. Skip them so we don't say "Warming Narrator…". */
private val GENERIC_VOICE_TOKENS = setOf(
    "narrator",
    "voice",
    "default",
    "generic",
    "neutral",
    "warm",
    "cool",
)

// ─── Issue #527 — player quick-chips overflow row ────────────────────
/**
 * Discrete preset chips for speed, sleep timer, and voice — the three
 * controls the 2026-05-15 audit (R5CRB0W66MK, R83W80CAFZB) flagged as
 * missing from the main player surface.
 *
 * Layout: two rows of brass-tinted FilterChips wrapped in a FlowRow so
 * narrow phones (Flip3 360 dp) break to extra lines instead of
 * squashing chips. Selected state mirrors the current playback state:
 * the chip whose preset matches the live `state.speed` (within ε) is
 * brass-filled; the rest are outlined. Same for sleep timer — when
 * `state.sleepTimerRemainingMs == null` the "Off" chip is selected,
 * otherwise no chip is selected because the active duration is shown
 * separately by the SleepTimerCountdownChip above the transport row.
 *
 * Tap behavior is commit-on-tap: a preset chip persists the value
 * immediately (unlike the continuous slider, where we only persist
 * on `onValueChangeFinished`). The user expressed exact intent.
 *
 * The Voice chip is a single chip showing the current voice name with
 * a chevron — tap routes to the Voice Library. This matches the Voice
 * row in the existing brass-icon quick sheet but lifts it onto the
 * main surface for one-tap reachability.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerQuickChips(
    state: UiPlaybackState,
    onSetSpeed: (Float) -> Unit,
    onStartSleepTimer: (UiSleepTimerMode) -> Unit,
    onCancelSleepTimer: () -> Unit,
    onPickVoice: () -> Unit,
    chapterCount: Int = 0,
    onOpenChapterList: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        // Row 1 — speed presets. Brass icon + presets list. Sleep timer
        // and voice chip go to row 2 so a narrow phone gets a clean
        // two-row layout instead of a 9-chip wrap.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Icon(
                Icons.Outlined.Speed,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                SPEED_PRESETS.forEach { preset ->
                    val selected = isSpeedPresetSelected(state.speed, preset)
                    // Issue #607 (v1.0 blocker) — pre-fix this row attached
                    // an explicit `contentDescription = "Playback speed
                    // 1.25×"` via Modifier.semantics. That string OVERRODE
                    // FilterChip's built-in `selected` + Role.RadioButton
                    // semantics, so TalkBack on R5CRB0W66MK announced
                    // "Playback speed 1.25×" but NEVER said "selected" /
                    // "not selected" for the active preset — the listener
                    // couldn't tell which speed was current. The
                    // contentDescription must NOT be set when FilterChip
                    // owns the label; let the chip's default semantics
                    // (label text + selected state + tab role) do the
                    // work. TalkBack now reads "1.25×, selected" /
                    // "1.25×, not selected" depending on `selected`.
                    FilterChip(
                        selected = selected,
                        onClick = { onSetSpeed(preset) },
                        label = { Text(formatSpeedPreset(preset)) },
                        colors = brassFilterChipColors(),
                    )
                }
            }
        }
        // Row 2 — sleep timer presets + voice chip. Same brass-icon
        // anchor so the icons read as "what the chips control".
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Icon(
                Icons.Outlined.Bedtime,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                val sleepActive = state.sleepTimerRemainingMs != null
                FilterChip(
                    selected = !sleepActive,
                    onClick = { onCancelSleepTimer() },
                    label = { Text(stringResource(R.string.reader_sleep_off)) },
                    colors = brassFilterChipColors(),
                )
                SLEEP_TIMER_PRESETS_MIN.forEach { minutes ->
                    FilterChip(
                        selected = false,
                        onClick = { onStartSleepTimer(UiSleepTimerMode.Duration(minutes)) },
                        label = { Text(stringResource(R.string.reader_sleep_minutes, minutes)) },
                        colors = brassFilterChipColors(),
                    )
                }
                FilterChip(
                    selected = false,
                    onClick = { onStartSleepTimer(UiSleepTimerMode.EndOfChapter) },
                    label = { Text(stringResource(R.string.reader_sleep_end)) },
                    colors = brassFilterChipColors(),
                )
            }
        }
        // Row 3 — voice quick-switch. Single chip so the voice name has
        // room to breathe on narrow phones; tapping deep-links to the
        // Voice Library where the full picker lives.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Icon(
                Icons.Outlined.RecordVoiceOver,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            // Issue #619 — humanize the raw `engine:voiceId` into a
            // chip-friendly `"Brian · US English"` / `"Amy · medium
            // quality"`. The accessibility label still includes the
            // engine prefix via [formatVoiceLabel] so a TalkBack user
            // can disambiguate Piper-Amy vs Azure-Amy when both ship.
            val humanized = humanizeVoiceLabel(state.voiceLabel)
            AssistChip(
                onClick = onPickVoice,
                label = {
                    Text(
                        humanized.ifBlank { "Pick a voice" },
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                },
                trailingIcon = {
                    Icon(
                        Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                modifier = Modifier
                    .weight(1f)
                    .semantics {
                        // Issue #609 (v1.0 blocker) — TalkBack used to read
                        // the raw catalog id ("piper_vctk_en_GB_medium")
                        // literally, one syllable per underscore. The
                        // humanized form ("Piper Vctk English (United
                        // Kingdom) Medium quality") is what a sighted
                        // user mentally translates the chip label into.
                        // Sighted chip text stays formatVoiceLabel() —
                        // power users keep their identifier preview.
                        contentDescription = "Pick voice. Current: ${humanizeVoiceLabel(state.voiceLabel)}"
                    },
            )
        }
        if (chapterCount > 0) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.FormatListBulleted,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                AssistChip(
                    onClick = onOpenChapterList,
                    label = {
                        Text(
                            formatChapterChipLabel(chapterCount),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    },
                    trailingIcon = {
                        Icon(
                            Icons.Outlined.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .semantics {
                            contentDescription = "Open chapter list. $chapterCount chapters available."
                        },
                )
            }
        }
    }
}

/**
 * Discrete speed presets surfaced on the main player surface. The
 * continuous slider in the brass-voice-icon sheet still covers
 * arbitrary values; these chips are the "common values, one tap"
 * shortcut. 0.75 / 1.0 / 1.25 / 1.5 / 2.0 matches Spotify, Apple
 * Music, Pocket Casts, and Libby — the audit reference set.
 *
 * Exposed `internal` so PlayerQuickChipsTest can iterate them.
 */
internal val SPEED_PRESETS: List<Float> = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

/**
 * Sleep-timer duration presets in minutes. 5/15/30/60 covers the bulk
 * of bedtime-listener use cases (the 2026-05-15 audit suggested
 * 5/15/30/60). The "End of chapter" chip is a separate
 * [UiSleepTimerMode.EndOfChapter] mode rendered alongside.
 */
internal val SLEEP_TIMER_PRESETS_MIN: List<Int> = listOf(5, 15, 30, 60)

/**
 * Issue #736 — label for the "Chapters" quick chip on the player
 * surface. Format: `"Chapters · 12"`. Singular variant for the
 * one-chapter case so a fiction with a single chapter doesn't read
 * "Chapters · 1" — minor polish, picked up from how
 * [`in`.jphe.storyvox.ui.component.BrowseSourceCarousel`]'s "N
 * fictions" label handles the same axis.
 *
 * Exposed `internal` so [PlayerQuickChipsTest] can pin the format
 * without rendering chips.
 */
internal fun formatChapterChipLabel(count: Int): String =
    if (count == 1) "Chapters · 1" else "Chapters · $count"

/**
 * Speed-preset selection epsilon. Comparing floats directly would
 * miss a 1.25× chip when the live speed is `1.2500001f` (float
 * rounding from the slider). 0.01 is a third of the gap between
 * adjacent presets so adjacent presets can't both flash selected.
 */
private const val SPEED_PRESET_EPSILON = 0.01f

/**
 * Pure-logic selection check — exposed `internal` so the test can
 * pin the epsilon contract without rendering chips. Returns true when
 * `state.speed` rounds to `preset` within [SPEED_PRESET_EPSILON].
 */
internal fun isSpeedPresetSelected(current: Float, preset: Float): Boolean =
    kotlin.math.abs(current - preset) < SPEED_PRESET_EPSILON

/**
 * Format a preset float as a user-facing label: 0.75 → "0.75×",
 * 1.0 → "1×", 1.25 → "1.25×". The "1×" special-case avoids the
 * awkward "1.00×" reading; whole-number presets always strip the
 * decimal.
 */
internal fun formatSpeedPreset(preset: Float): String {
    return if (preset == preset.toInt().toFloat()) {
        "${preset.toInt()}×"
    } else {
        "${"%.2f".format(preset).trimEnd('0').trimEnd('.')}×"
    }
}

/**
 * Pitch presets surfaced as chips in [VoiceQuickSheetContent]. The
 * continuous slider's range is 0.6×..1.4× (Sonic's "musical" zone —
 * outside that band the voice sounds chipmunky or muddy). The chip
 * set picks five anchors: neutral 1×, two "warmer/deeper" steps and
 * two "brighter/higher" steps. 0.15 spacing keeps adjacent presets
 * audibly distinct (the JND threshold for pitch shifts on a male
 * narrator voice in informal A/B is ≈ 0.05-0.08; 0.15 keeps every
 * step a clearly-different reading).
 *
 * Exposed `internal` so [PlayerQuickChipsTest] / new chip tests can
 * iterate them without instantiating Compose.
 */
internal val PITCH_PRESETS: List<Float> = listOf(0.7f, 0.85f, 1.0f, 1.15f, 1.3f)

/**
 * Pitch preset selection epsilon. Adjacent presets are 0.15× apart;
 * 0.02 is well under that (a third of the spacing) so floats from the
 * slider can't flash two chips at once. Mirrors [SPEED_PRESET_EPSILON]
 * but at the looser pitch scale.
 */
private const val PITCH_PRESET_EPSILON = 0.02f

/**
 * Pure-logic selection check for [PITCH_PRESETS]. Exposed `internal`
 * so the unit test can pin the epsilon contract without rendering.
 */
internal fun isPitchPresetSelected(current: Float, preset: Float): Boolean =
    kotlin.math.abs(current - preset) < PITCH_PRESET_EPSILON

/**
 * Format a pitch preset as a user-facing label: 1.0 → "1×",
 * 0.7 → "0.7×", 1.15 → "1.15×". Same shape as [formatSpeedPreset]
 * (whole numbers strip the decimal) but slightly looser since pitch
 * presets aren't all on a clean 0.25 grid like speed.
 */
internal fun formatPitchPreset(preset: Float): String {
    return if (preset == preset.toInt().toFloat()) {
        "${preset.toInt()}×"
    } else {
        "${"%.2f".format(preset).trimEnd('0').trimEnd('.')}×"
    }
}

/**
 * Sentence-pause / "voice cadence" presets surfaced as chips in
 * [VoiceQuickSheetContent]. The continuous slider's range is 0×..4×
 * (matches the engine's clamp at [`punctuationPauseMultiplier`]).
 *
 *  - 0×: rapid listening (no trailing silence — speedrun)
 *  - 0.5×: brisk (tight cadence, podcast style)
 *  - 1×: audiobook-tuned default (matches the legacy slider default)
 *  - 2×: slow narrator (more breathing room between sentences)
 *  - 3×: very slow (a11y / language-learning cadence)
 *
 * The 4× extreme is reachable only through the "Custom…" slider —
 * the chip set picks five values that span the practical listening
 * range and skips the asymptote where each sentence is followed by
 * a noticeable silent gap.
 */
internal val PUNCTUATION_PAUSE_PRESETS: List<Float> = listOf(0f, 0.5f, 1f, 2f, 3f)

/**
 * Selection epsilon for [PUNCTUATION_PAUSE_PRESETS]. Adjacent presets
 * are 0.5×..1× apart (the gap between 1× and 2× is the widest at
 * 1.0); 0.05 keeps the chips from flashing two-at-once near the
 * boundaries.
 */
private const val PUNCTUATION_PAUSE_PRESET_EPSILON = 0.05f

/**
 * Pure-logic selection check for [PUNCTUATION_PAUSE_PRESETS].
 */
internal fun isPunctuationPausePresetSelected(current: Float, preset: Float): Boolean =
    kotlin.math.abs(current - preset) < PUNCTUATION_PAUSE_PRESET_EPSILON

/**
 * Format a cadence preset as a user-facing label. Same "1×" / "0.5×"
 * shape as the other preset formatters; whole numbers strip the
 * decimal so the chip row reads "0× / 0.5× / 1× / 2× / 3×" instead
 * of "0.00× / 0.50× / 1.00× / 2.00× / 3.00×".
 */
internal fun formatPunctuationPausePreset(preset: Float): String {
    return if (preset == preset.toInt().toFloat()) {
        "${preset.toInt()}×"
    } else {
        "${"%.2f".format(preset).trimEnd('0').trimEnd('.')}×"
    }
}
