package `in`.jphe.storyvox.feature.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VerticalAlignCenter
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.feature.api.UiPlaybackState
import `in`.jphe.storyvox.ui.component.SentenceHighlight
import `in`.jphe.storyvox.ui.theme.LocalSpacing
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Manual-scroll grace window. While the user is dragging or for this many millis after,
 * the auto-scroll-to-sentence behavior is suppressed so we don't yank them away from
 * whatever they're reading ahead.
 */
private const val MANUAL_SCROLL_GRACE_MS = 5_000L

/** Where on the viewport the highlighted sentence should land — 40% from the top. */
internal const val HIGHLIGHT_VIEWPORT_FRACTION = 0.40f

/**
 * Issue #997 — Focused Reading mode lands the active line at the vertical
 * centre instead of the 40%-from-top reading perch, so the eye rests in
 * the middle of the page with equal context above and below.
 */
internal const val FOCUS_VIEWPORT_FRACTION = 0.5f

/**
 * Issue #997 — comfortable single-column reading measure for Focused
 * Reading. ~560dp keeps line length in the classic 45–75 character band
 * on phones and tablets; wider screens get centred whitespace rather
 * than over-long lines that tire the eye.
 */
private val FOCUS_MEASURE_MAX = 560.dp

/**
 * Pure target-Y for scrolling so the highlighted line settles
 * [viewportFraction] of the way down the viewport. Shared by the
 * auto-scroll effect and the recenter FAB (#919), and parameterised by
 * the fraction so Focused Reading (#997) can pass [FOCUS_VIEWPORT_FRACTION]
 * to centre the line while normal reading passes [HIGHLIGHT_VIEWPORT_FRACTION].
 *
 * Extracted to a pure fn (no Compose state) so the arithmetic and its two
 * clamp edges are unit-testable without a Compose/Android runtime.
 *
 * @param bodyTopPx Y offset of the chapter body inside the scrolling column.
 * @param lineTopWithinText Top of the highlighted line within the text layout.
 * @param viewportHeightPx Measured viewport height.
 * @param viewportFraction How far down the viewport the line should land (0..1).
 * @param maxScroll [androidx.compose.foundation.ScrollState.maxValue]; a
 *   transient negative value (content shorter than viewport) is treated as 0.
 */
internal fun focusScrollTargetY(
    bodyTopPx: Float,
    lineTopWithinText: Float,
    viewportHeightPx: Float,
    viewportFraction: Float,
    maxScroll: Int,
): Int =
    (bodyTopPx + lineTopWithinText - viewportHeightPx * viewportFraction)
        .roundToInt()
        .coerceIn(0, maxScroll.coerceAtLeast(0))

@Composable
fun ReaderTextView(
    state: UiPlaybackState,
    chapterText: String,
    onPlayPause: () -> Unit,
    onSeekToChar: (Int) -> Unit = {},
    /** Long-press a word in the chapter body → open the chat surface
     *  with `Who is <word>?` pre-filled in the input field. The reader
     *  doesn't know about navigation, so HybridReaderScreen wires this
     *  to `navController.navigate(StoryvoxRoutes.chat(fId, prefill = q))`.
     *  No-op default keeps preview/test callsites working unchanged. */
    onAskAiAbout: (String) -> Unit = {},
    /**
     * Issue #946 — magical auto-scroll toggle. When true, the chapter
     * body smoothly follows the engine's current sentence (the
     * long-standing read-along behavior). When false, the auto-scroll
     * LaunchedEffect short-circuits — the user owns the wheel.
     * Defaulted to true so older callsites (tests, previews) keep the
     * original behavior without opting in.
     */
    autoScrollEnabled: Boolean = true,
    /** Issue #946 — flip the auto-scroll toggle. Wired by
     *  [HybridReaderScreen] to [ReaderViewModel.setAutoScrollEnabled],
     *  which persists via [SettingsRepositoryUi]. No-op default for
     *  preview/test callsites. */
    onToggleAutoScroll: (Boolean) -> Unit = {},
    /**
     * Issue #997 — Focused Reading mode. When true the reader dims the
     * off-focus lines, narrows the text column, centres the active line
     * (vertical 50% instead of the 40% perch) and collapses the bottom
     * chrome + hides the FAB cluster — a distraction-reduced read.
     * Defaulted false so preview/test callsites keep the normal reader.
     */
    focusModeEnabled: Boolean = false,
    /** Issue #997 — flip the Focused Reading toggle. Wired by
     *  [HybridReaderScreen] to [ReaderViewModel.setFocusModeEnabled].
     *  No-op default for preview/test callsites. */
    onToggleFocusMode: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val scroll = rememberScrollState()

    // Y offset (px) of the SentenceHighlight composable's top inside the scrolling Column,
    // measured at the moment the body settles. The Column's contentPadding shifts everything
    // down by spacing.lg + the title's measured height; we capture both via onGloballyPositioned.
    var bodyTopPx by remember { mutableFloatStateOf(0f) }
    var viewportHeightPx by remember { mutableFloatStateOf(0f) }
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }

    // Long-press lookup state. When non-null, the dialog is showing for
    // this word — tapping its primary action navigates to the chat
    // surface with `Who is <word>?` pre-filled. The state lives on
    // ReaderTextView (not the SentenceHighlight) because the SentenceHighlight
    // is a low-level rendering primitive shared with the audiobook view —
    // putting the popup here keeps that primitive stateless.
    var lookupWord by remember { mutableStateOf<String?>(null) }

    // Manual-scroll grace: each time the user starts dragging we stamp wall-clock time;
    // auto-scroll respects the grace window before resuming.
    var lastManualScrollAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.isScrollInProgress }.collect { dragging ->
            if (dragging) lastManualScrollAt = System.currentTimeMillis()
        }
    }

    // Auto-scroll target: when sentence range or layout changes, settle the highlighted
    // line at 40% from the top of the viewport — unless we're inside the manual-scroll
    // grace window, in which case the user has the wheel.
    //
    // Issue #946 — the [autoScrollEnabled] toggle wraps this effect's key set: when the
    // user flips the brass IconButton off, the LaunchedEffect re-keys to a no-op branch
    // and never schedules another animateScrollTo. Flipping back on re-keys and resumes
    // following the next sentence boundary (no jump — only the next emit triggers a
    // scroll, so the user's manual position is preserved until then).
    LaunchedEffect(autoScrollEnabled, focusModeEnabled, state.sentenceStart, state.sentenceEnd, textLayout, viewportHeightPx, bodyTopPx, chapterText) {
        if (!autoScrollEnabled) return@LaunchedEffect
        val layout = textLayout ?: return@LaunchedEffect
        if (chapterText.isEmpty()) return@LaunchedEffect
        if (viewportHeightPx <= 0f) return@LaunchedEffect
        val start = state.sentenceStart
        val end = state.sentenceEnd
        if (end <= start) return@LaunchedEffect
        if (start !in 0..chapterText.length) return@LaunchedEffect
        if (System.currentTimeMillis() - lastManualScrollAt < MANUAL_SCROLL_GRACE_MS) return@LaunchedEffect

        val safeStart = start.coerceIn(0, (chapterText.length - 1).coerceAtLeast(0))
        val line = layout.getLineForOffset(safeStart)
        val lineTopWithinText = layout.getLineTop(line)
        // Focus mode centres the active line (#997); normal reading keeps
        // the 40% perch (#946).
        val targetY = focusScrollTargetY(
            bodyTopPx = bodyTopPx,
            lineTopWithinText = lineTopWithinText,
            viewportHeightPx = viewportHeightPx,
            viewportFraction = if (focusModeEnabled) FOCUS_VIEWPORT_FRACTION else HIGHLIGHT_VIEWPORT_FRACTION,
            maxScroll = scroll.maxValue,
        )

        scroll.animateScrollTo(targetY)
    }

    // Issue #919 — "scroll to current sentence" FAB. Derived state tracks
    // whether the brass-underlined sentence is visible in the viewport.
    // When the user scrolls away (sentence not visible AND playback active),
    // a small FAB appears; tapping it resets the manual-scroll grace and
    // smooth-scrolls to the active sentence. Auto-hides when the sentence
    // is already in view. The 80px margin gives a comfortable buffer so the
    // FAB doesn't flicker when the sentence is right at the edge.
    val scope = rememberCoroutineScope()
    val sentenceOutOfView by remember {
        derivedStateOf {
            val layout = textLayout ?: return@derivedStateOf false
            val start = state.sentenceStart
            val end = state.sentenceEnd
            if (end <= start) return@derivedStateOf false
            if (chapterText.isEmpty()) return@derivedStateOf false
            if (start !in 0..chapterText.length) return@derivedStateOf false
            if (viewportHeightPx <= 0f) return@derivedStateOf false

            val safeStart = start.coerceIn(0, (chapterText.length - 1).coerceAtLeast(0))
            val line = layout.getLineForOffset(safeStart)
            val lineTop = bodyTopPx + layout.getLineTop(line)
            val lineBottom = bodyTopPx + layout.getLineBottom(line)
            val scrollY = scroll.value.toFloat()
            // Sentence is "out of view" when its top line is above the viewport
            // (scrolled past) or below it (not scrolled to yet). An 80px margin
            // avoids flicker when the sentence is at the edge.
            val margin = 80f
            lineBottom < scrollY + margin || lineTop > scrollY + viewportHeightPx - margin
        }
    }
    val showScrollFab = sentenceOutOfView && state.isPlaying

    // Issue #997 — Focused Reading: the chapter title sits outside the
    // active-sentence band, so it fades back in focus mode to keep the
    // body the visual anchor. Animated so the toggle reads as a calm
    // settle, not a hard cut. Reduced-motion users still get the end
    // state; the tween is short enough to be unobtrusive regardless.
    val titleAlpha by animateFloatAsState(
        targetValue = if (focusModeEnabled) 0.35f else 1f,
        animationSpec = tween(durationMillis = 240),
        label = "focus-title-alpha",
    )

    Box(modifier = modifier.fillMaxSize().onSizeChanged { viewportHeightPx = it.height.toFloat() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = spacing.lg)
                .padding(top = spacing.lg, bottom = 96.dp),
            // Focus mode narrows + centres the measure for a comfortable
            // single-column read; normal reading uses the full width.
            horizontalAlignment = if (focusModeEnabled) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            // Focus mode narrows the column to a comfortable reading
            // measure (#997). Applied to title + body alike so they share
            // one centred column.
            val contentWidthModifier =
                if (focusModeEnabled) Modifier.widthIn(max = FOCUS_MEASURE_MAX) else Modifier
            Text(
                state.chapterTitle,
                style = MaterialTheme.typography.headlineSmall,
                modifier = contentWidthModifier
                    .fillMaxWidth()
                    .padding(bottom = spacing.md)
                    .alpha(titleAlpha),
            )
            if (chapterText.isEmpty()) {
                EmptyChapterPlaceholder()
            } else {
                Box(
                    modifier = contentWidthModifier.onGloballyPositioned { coords ->
                        // Position relative to the scrolling Column root: walk up to the parent that holds
                        // the same content as `scroll`. Compose's positionInParent() is enough since the
                        // Column itself is what scrolls — its children move with it.
                        bodyTopPx = coords.positionInParent().y
                    },
                ) {
                    SentenceHighlight(
                        text = chapterText,
                        highlightStart = state.sentenceStart,
                        highlightEnd = state.sentenceEnd,
                        onTapWord = onSeekToChar,
                        onLongPressWord = { word -> lookupWord = word },
                        onLayout = { layout -> textLayout = layout },
                    )
                }
            }
        }

        // Issue #997 — Focused Reading toggle. Top of the brass cluster
        // (above the auto-scroll toggle) and ALWAYS visible — even in
        // focus mode — so it's the one persistent control the user can
        // use to leave the distraction-reduced view. Filled-brass when
        // on, dimmed-surface when off, mirroring the auto-scroll toggle's
        // state-tint language.
        SmallFloatingActionButton(
            onClick = { onToggleFocusMode(!focusModeEnabled) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = spacing.md, bottom = 232.dp)
                .semantics {
                    contentDescription = if (focusModeEnabled) {
                        "Focused Reading on. Tap to turn off."
                    } else {
                        "Focused Reading off. Tap to turn on."
                    }
                },
            containerColor = if (focusModeEnabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            contentColor = if (focusModeEnabled) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        ) {
            Icon(
                imageVector = Icons.Outlined.CenterFocusStrong,
                contentDescription = null,
            )
        }

        // Issue #946 — magical auto-scroll on/off toggle. Sits above the
        // recenter FAB (#919) so the two end-aligned controls form a
        // vertical brass cluster: top = mode (auto-follow vs manual),
        // bottom = one-shot recenter. Always visible so the user can
        // discover the toggle without first scrolling away; the icon's
        // brass-vs-onSurfaceVariant tint communicates state at a glance.
        // Hidden in Focused Reading (#997) — the collapsed chrome trades
        // the control cluster for an undistracted page.
        //
        // The contained icon is `Outlined.AutoStories` (the same
        // open-book glyph the Library tab uses) tinted brass when
        // auto-scroll is on and dimmed-onSurfaceVariant when off. A
        // small `Outlined.AutoAwesome` sparkle overlays the top-right
        // when ON, conveying "magic active" — its alpha cross-fades
        // on toggle so the flip reads as evocative without competing
        // with the chapter body's reading focus.
        val sparkleAlpha by animateFloatAsState(
            targetValue = if (autoScrollEnabled) 0.85f else 0f,
            animationSpec = tween(durationMillis = 280),
            label = "autoscroll-sparkle-alpha",
        )
        if (!focusModeEnabled) {
            SmallFloatingActionButton(
                onClick = { onToggleAutoScroll(!autoScrollEnabled) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = spacing.md, bottom = 168.dp)
                    .semantics {
                        contentDescription = if (autoScrollEnabled) {
                            "Auto-scroll on. Tap to turn off."
                        } else {
                            "Auto-scroll off. Tap to turn on."
                        }
                    },
                containerColor = if (autoScrollEnabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                contentColor = if (autoScrollEnabled) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.AutoStories,
                        contentDescription = null,
                    )
                    // Sparkle overlay — only visible when auto-scroll is
                    // armed. Sits in the upper-right of the FAB so it
                    // reads as "this book is following you, magically."
                    if (sparkleAlpha > 0.01f) {
                        Icon(
                            imageVector = Icons.Outlined.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier
                                .size(12.dp)
                                .align(Alignment.TopEnd)
                                .alpha(sparkleAlpha),
                        )
                    }
                }
            }
        }
        // Issue #919 — floating "scroll to current sentence" button.
        // Positioned above the bottom transport bar (bottom = 104dp to
        // clear the bar + spacing), end-aligned. Uses Material 3
        // SmallFloatingActionButton with the app's primary/onPrimary
        // colors. Enter = fade + slide up; exit = fade + slide down —
        // deliberate and warm, matching the Library Nocturne motion.
        // Suppressed in Focused Reading (#997) — center-lock keeps the
        // active line in view, so the one-shot recenter is redundant.
        AnimatedVisibility(
            visible = showScrollFab && !focusModeEnabled,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = spacing.md, bottom = 104.dp),
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            SmallFloatingActionButton(
                onClick = {
                    // Reset grace so auto-scroll doesn't fight the user.
                    lastManualScrollAt = 0L
                    val layout = textLayout ?: return@SmallFloatingActionButton
                    if (chapterText.isEmpty()) return@SmallFloatingActionButton
                    val start = state.sentenceStart
                    val end = state.sentenceEnd
                    if (end <= start) return@SmallFloatingActionButton
                    if (start !in 0..chapterText.length) return@SmallFloatingActionButton

                    val safeStart = start.coerceIn(0, (chapterText.length - 1).coerceAtLeast(0))
                    val line = layout.getLineForOffset(safeStart)
                    val lineTopWithinText = layout.getLineTop(line)
                    val targetY = focusScrollTargetY(
                        bodyTopPx = bodyTopPx,
                        lineTopWithinText = lineTopWithinText,
                        viewportHeightPx = viewportHeightPx,
                        viewportFraction = HIGHLIGHT_VIEWPORT_FRACTION,
                        maxScroll = scroll.maxValue,
                    )
                    scope.launch { scroll.animateScrollTo(targetY) }
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(
                    imageVector = Icons.Filled.VerticalAlignCenter,
                    contentDescription = "Scroll to current sentence",
                )
            }
        }

        // Issue #997 — Focused Reading collapses the bottom transport
        // bar: the title/subtitle text (a distraction) and the bar's
        // surface + shadow drop away, leaving just the play/pause control
        // floating end-aligned. The button stays so the mode keeps its
        // single TalkBack-reachable transport affordance (accessibility
        // is a first-class goal of #997). Normal reading keeps the full
        // bar with the chapter/fiction labels.
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            color = if (focusModeEnabled) {
                androidx.compose.ui.graphics.Color.Transparent
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            shadowElevation = if (focusModeEnabled) 0.dp else 4.dp,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!focusModeEnabled) {
                    Column(modifier = Modifier.weight(1f).padding(start = spacing.xs)) {
                        Text(
                            state.chapterTitle.ifEmpty { "—" },
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                        )
                        Text(
                            state.fictionTitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                } else {
                    // Push the play/pause button to the end so it mirrors
                    // its normal-mode resting place once the labels vanish.
                    Spacer(modifier = Modifier.weight(1f))
                }
                FilledIconButton(
                    onClick = onPlayPause,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                    )
                }
            }
        }

        // Character-lookup popup (#188). Long-press a word → AlertDialog
        // with the librarian sigil + a primary "Ask AI" action. We use
        // AlertDialog rather than a popup-at-position because the M3 dialog
        // already gives us the brass scrim, dismiss-on-outside-tap, and
        // back-button handling for free; pinning a popup to the press
        // coordinate would ride on top of the moving sentence underline
        // and obscure the very word the user just selected.
        lookupWord?.let { word ->
            AlertDialog(
                onDismissRequest = { lookupWord = null },
                icon = {
                    Icon(
                        Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                title = {
                    Text(
                        text = stringResource(R.string.reader_ask_ai_title, word),
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                text = {
                    Text(
                        text = stringResource(R.string.reader_ask_ai_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val q = "Who is $word?"
                            lookupWord = null
                            onAskAiAbout(q)
                        },
                    ) {
                        Text(stringResource(R.string.reader_ask_ai))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { lookupWord = null }) {
                        Text(stringResource(R.string.reader_cancel))
                    }
                },
            )
        }
    }
}

@Composable
private fun EmptyChapterPlaceholder() {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Magical brass sigil while the chapter body is being fetched + parsed.
        `in`.jphe.storyvox.ui.component.MagicSkeletonTile(
            modifier = Modifier.size(width = 200.dp, height = 280.dp),
            shape = MaterialTheme.shapes.medium,
            glyphSize = 96.dp,
        )
        Spacer(Modifier.height(spacing.lg))
        Text(
            "Conjuring the chapter…",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(spacing.xs))
        Text(
            "The text will appear here as VoxSherpa reads it aloud.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
