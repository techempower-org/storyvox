package `in`.jphe.storyvox.feature.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VerticalAlignCenter
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.feature.api.HighlightMode
import `in`.jphe.storyvox.feature.api.UiPlaybackState
import androidx.compose.runtime.withFrameNanos
import `in`.jphe.storyvox.ui.component.SentenceHighlight
import `in`.jphe.storyvox.ui.component.TestTags
import `in`.jphe.storyvox.ui.component.currentWordRange
import `in`.jphe.storyvox.ui.component.sentenceProgress
import `in`.jphe.storyvox.ui.theme.LocalReaderColors
import `in`.jphe.storyvox.ui.theme.LocalReaderTypography
import `in`.jphe.storyvox.ui.theme.LocalSpacing
import `in`.jphe.storyvox.ui.theme.ReaderColors
import `in`.jphe.storyvox.ui.theme.ReaderTypography
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
    /** Issue #993 — reading-theme colours for the chapter surface. Default
     *  is inactive (the reader uses the app theme); when a theme is active
     *  the body background + text + sentence underline are re-tinted. No-op
     *  default keeps preview/test/audiobook callsites unchanged. */
    readerColors: ReaderColors = ReaderColors(),
    /** Issue #992 — reader-surface typography (font / size / line + letter
     *  spacing) provided to the chapter text via [LocalReaderTypography].
     *  Default reproduces the legacy reader style, so preview/test callsites
     *  render unchanged. */
    readerTypography: ReaderTypography = ReaderTypography(),
    /** Issue #994 — reading-highlight mode. [HighlightMode.Word] /
     *  [HighlightMode.Both] drive the per-word karaoke fill; the default
     *  [HighlightMode.Sentence] keeps the legacy underline-only behaviour, so
     *  preview/test callsites are unchanged. */
    highlightMode: HighlightMode = HighlightMode.Sentence,
    /** Issue #994 — custom per-word highlight colour (ARGB int). 0 (default)
     *  derives from the reading-theme accent. */
    wordHighlightArgb: Int = 0,
    /** Issue #999 phase 2 — the loaded chapter's saved highlights to render as
     *  background spans + make tappable for edit/delete. Empty (default) keeps
     *  preview/test/audiobook callsites unchanged. */
    savedHighlights: List<`in`.jphe.storyvox.data.db.entity.Annotation> = emptyList(),
    /** Issue #999 phase 2 — persist a new highlight. Args:
     *  (startOffset, endOffset, quotedText, colorName, note). No-op default. */
    onCreateHighlight: (Int, Int, String, String, String?) -> Unit = { _, _, _, _, _ -> },
    /** Issue #999 phase 2 — edit an existing highlight's colour / note. */
    onUpdateHighlight: (`in`.jphe.storyvox.data.db.entity.Annotation, String, String?) -> Unit = { _, _, _ -> },
    /** Issue #999 phase 2 — delete a highlight by id. */
    onDeleteHighlight: (String) -> Unit = {},
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

    // Issue #999 phase 2 — in-reader highlight state.
    //  - selectionRange: the live drag selection (raw anchor/focus, normalised
    //    on the fly), null when no drag is in progress.
    //  - pendingSelection: the normalised range whose create-toolbar is showing
    //    after the drag lifts.
    //  - editing: the saved Annotation whose edit/delete sheet is open (tapped
    //    an existing highlight).
    var selectionAnchor by remember { mutableIntStateOf(-1) }
    var selectionFocus by remember { mutableIntStateOf(-1) }
    var pendingSelection by remember { mutableStateOf<TextSelectionRange?>(null) }
    var editingHighlight by remember {
        mutableStateOf<`in`.jphe.storyvox.data.db.entity.Annotation?>(null)
    }
    // Live normalised selection range (for the wash + toolbar). Recomputed
    // whenever the drag anchor/focus move.
    val liveSelection: TextSelectionRange? = remember(selectionAnchor, selectionFocus, chapterText) {
        if (selectionAnchor < 0 || selectionFocus < 0) null
        else normalizeSelection(selectionAnchor, selectionFocus, chapterText)
    }

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

    // Issue #998 — in-text search. All state is local to the reader pane:
    // the query, whether the search bar is open, and which match the
    // chevrons last landed on. Matches are derived purely from the
    // already-in-memory chapterText via [findMatches] (no ViewModel
    // round-trip), so the whole affordance is scoped to this composable
    // and the other reader-cluster features rebase cleanly over it.
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var activeMatchIndex by remember { mutableIntStateOf(0) }
    val matches by remember(chapterText, searchQuery) {
        derivedStateOf { findMatches(chapterText, searchQuery) }
    }
    // Keep the active index in range as the query (and thus match count)
    // changes — a narrowing query can shrink the list out from under us.
    LaunchedEffect(matches.size) {
        if (activeMatchIndex >= matches.size) activeMatchIndex = 0
    }
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val searchHint = stringResource(R.string.reader_search)

    // Jump-to-match: when the active match changes (chevrons, or the first
    // hit of a fresh query), scroll it to the 40%-from-top reading line —
    // reusing the exact #919 recenter math — and seek TTS playback to the
    // match's char offset, so the issue's "search a word and move the
    // playhead there" accessibility workflow lands in one tap.
    LaunchedEffect(activeMatchIndex, matches) {
        val match = matches.getOrNull(activeMatchIndex) ?: return@LaunchedEffect
        val layout = textLayout ?: return@LaunchedEffect
        if (chapterText.isEmpty()) return@LaunchedEffect
        if (viewportHeightPx <= 0f) return@LaunchedEffect
        // Suppress auto-scroll fighting us while we reposition.
        lastManualScrollAt = System.currentTimeMillis()
        val safeStart = match.first.coerceIn(0, (chapterText.length - 1).coerceAtLeast(0))
        val line = layout.getLineForOffset(safeStart)
        val lineTopWithinText = layout.getLineTop(line)
        val targetY = (bodyTopPx + lineTopWithinText - viewportHeightPx * HIGHLIGHT_VIEWPORT_FRACTION)
            .roundToInt()
            .coerceIn(0, scroll.maxValue.coerceAtLeast(0))
        scroll.animateScrollTo(targetY)
        onSeekToChar(match.first)
    }

    // #993 — provide the reading-theme colours to the chapter renderer below.
    // When active we paint the theme background on the reader surface and tint
    // the chapter title to the theme foreground; SentenceHighlight reads
    // LocalReaderColors for the body text + underline. Inactive = no change.
    val readerSurface = readerColors.resolved
    val surfaceModifier =
        if (readerSurface != null) modifier.background(readerSurface.background) else modifier
    CompositionLocalProvider(LocalReaderColors provides readerColors) {
    Box(modifier = surfaceModifier.fillMaxSize().onSizeChanged { viewportHeightPx = it.height.toFloat() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // UI-test selector for the scrollable reader body.
                .testTag(TestTags.ReaderBody)
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
                // #993 — tint the title to the theme foreground when a reading
                // theme is active; Color.Unspecified falls back to the app theme.
                color = readerSurface?.foreground ?: Color.Unspecified,
                // #997 — focus mode narrows the column (contentWidthModifier)
                // and fades the title back (titleAlpha).
                modifier = contentWidthModifier
                    .fillMaxWidth()
                    .padding(bottom = spacing.md)
                    .alpha(titleAlpha),
            )
            if (chapterText.isEmpty()) {
                EmptyChapterPlaceholder()
            } else {
                // Issue #992 — provide reader-surface typography to the chapter
                // text (and only the chapter text). SentenceHighlight consumes
                // LocalReaderTypography; app chrome above/around this Provider is
                // unaffected, so enlarging the reading text never blows up the
                // controls.
                CompositionLocalProvider(LocalReaderTypography provides readerTypography) {
                Box(
                    modifier = contentWidthModifier.onGloballyPositioned { coords ->
                        // Position relative to the scrolling Column root: walk up to the parent that holds
                        // the same content as `scroll`. Compose's positionInParent() is enough since the
                        // Column itself is what scrolls — its children move with it.
                        bodyTopPx = coords.positionInParent().y
                    },
                ) {
                    // #994 — per-word karaoke clock. Word/Both modes drive a
                    // proportional highlight (the issue-blessed model: no true
                    // phoneme timing). We anchor wall-clock at each sentence
                    // boundary and estimate the sentence's spoken duration from
                    // its character count at the current speed (~16 chars/s at
                    // 1.0x). currentWordRange maps that progress to a word; the
                    // error self-corrects every boundary when the engine
                    // advances the sentence range. Sentence/Off modes leave
                    // wordStart/wordEnd at -1 → SentenceHighlight no-ops the fill.
                    val wordHighlightActive =
                        (highlightMode == HighlightMode.Word || highlightMode == HighlightMode.Both) &&
                            state.isPlaying
                    var sentenceElapsedMs by remember { mutableLongStateOf(0L) }
                    LaunchedEffect(
                        wordHighlightActive,
                        state.sentenceStart,
                        state.sentenceEnd,
                    ) {
                        sentenceElapsedMs = 0L
                        if (!wordHighlightActive) return@LaunchedEffect
                        val anchorNanos = withFrameNanos { it }
                        while (true) {
                            val nowNanos = withFrameNanos { it }
                            sentenceElapsedMs = (nowNanos - anchorNanos) / 1_000_000L
                        }
                    }
                    // Estimate spoken duration from char count. ~16 chars/s at
                    // 1.0x; faster speed → shorter estimate. Floor of 1 char so
                    // a degenerate range never yields 0ms (handled anyway by
                    // sentenceProgress's durationMs<=0 guard).
                    val sentenceChars = (state.sentenceEnd - state.sentenceStart).coerceAtLeast(1)
                    val speed = state.speed.coerceAtLeast(0.1f)
                    val estDurationMs = (sentenceChars * 1000L / (16f * speed)).toLong()
                    val wordRange = if (wordHighlightActive) {
                        currentWordRange(
                            text = chapterText,
                            sentenceStart = state.sentenceStart,
                            sentenceEnd = state.sentenceEnd,
                            sentenceProgress = sentenceProgress(sentenceElapsedMs, estDurationMs),
                        )
                    } else {
                        null
                    }
                    // #994 — Word-only mode hides the sentence underline (the
                    // word fill IS the indicator); Both keeps both. Off/Sentence
                    // keep today's underline.
                    val underlineSuppressed = highlightMode == HighlightMode.Word ||
                        highlightMode == HighlightMode.Off
                    val customWordFill =
                        if (wordHighlightArgb != 0) Color(wordHighlightArgb) else null

                    // #999 phase 2 — map saved annotations to background
                    // spans. Each annotation's `[startOffset, endOffset)`
                    // becomes the end-inclusive range SentenceHighlight wants
                    // (last = endOffset - 1) + the palette fill colour. Skipped
                    // when the range no longer fits the current body (a
                    // re-fetch shifted the text) — the durable quotedText still
                    // shows on the FictionDetail list, but drawing the wash at
                    // the wrong glyphs would be worse than not drawing it
                    // (per Annotation's range-model note).
                    val savedSpans = remember(savedHighlights, chapterText) {
                        savedHighlights.mapNotNull { ann ->
                            val s = ann.startOffset
                            val e = ann.endOffset
                            if (s in 0..chapterText.length && e in (s + 1)..chapterText.length) {
                                (s..(e - 1)) to `in`.jphe.storyvox.ui.theme.HighlightColor
                                    .fromName(ann.color).fill
                            } else {
                                null
                            }
                        }
                    }

                    SentenceHighlight(
                        text = chapterText,
                        highlightStart = if (underlineSuppressed) 0 else state.sentenceStart,
                        highlightEnd = if (underlineSuppressed) 0 else state.sentenceEnd,
                        // #999 phase 2 — a tap first checks whether it landed
                        // on a saved highlight (open its edit/delete sheet);
                        // only a tap that misses every highlight falls through
                        // to tap-to-seek. The resolution uses the same
                        // char-offset coordinate the highlights were stored in.
                        onTapWord = { charIndex ->
                            val hitId = annotationIdAtOffset(
                                tappedOffset = charIndex,
                                ids = savedHighlights.map { it.id },
                                starts = savedHighlights.map { it.startOffset },
                                ends = savedHighlights.map { it.endOffset },
                            )
                            val hit = hitId?.let { id -> savedHighlights.firstOrNull { it.id == id } }
                            if (hit != null) editingHighlight = hit else onSeekToChar(charIndex)
                        },
                        onLongPressWord = { word -> lookupWord = word },
                        onLayout = { layout -> textLayout = layout },
                        // #998 — paint in-text search hits; the active one
                        // (chevron target) gets the stronger fill.
                        searchMatches = matches,
                        activeMatchIndex = activeMatchIndex,
                        // #994 — per-word karaoke fill.
                        wordStart = wordRange?.first ?: -1,
                        wordEnd = wordRange?.let { it.last + 1 } ?: -1,
                        wordFill = customWordFill,
                        // #999 phase 2 — render saved highlights + the live
                        // selection wash, and capture the drag selection.
                        savedHighlights = savedSpans,
                        activeSelection = liveSelection?.let { it.start..(it.end - 1) },
                        onSelectionDrag = { anchor, focus ->
                            selectionAnchor = anchor
                            selectionFocus = focus
                            // A new drag supersedes any showing toolbar.
                            pendingSelection = null
                        },
                        onSelectionFinished = {
                            pendingSelection = liveSelection
                            selectionAnchor = -1
                            selectionFocus = -1
                        },
                    )
                } // Box
                } // CompositionLocalProvider(LocalReaderTypography) — #992
            }
        }

        // Issue #998 — find-in-text FAB. Tops the brass cluster (search →
        // focus → auto-scroll → recenter, reading down at 296/232/168/104).
        // Toggling it opens the search bar overlay and focuses the field;
        // toggling off closes the bar and clears the query so the match
        // highlights vanish. Only shown when there's chapter text to search.
        if (chapterText.isNotEmpty()) {
            SmallFloatingActionButton(
                onClick = {
                    searchOpen = !searchOpen
                    if (!searchOpen) {
                        searchQuery = ""
                        activeMatchIndex = 0
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = spacing.md, bottom = 296.dp)
                    .semantics { contentDescription = searchHint },
                containerColor = if (searchOpen) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                contentColor = if (searchOpen) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            ) {
                Icon(imageVector = Icons.Outlined.Search, contentDescription = null)
            }
        }

        // Issue #997 — Focused Reading toggle. Sits below the search FAB
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

        // Issue #999 phase 2 — highlight create toolbar. Shown after a
        // selection drag lifts (pendingSelection set). A compact bottom sheet
        // with the colour palette + an optional note field + Highlight /
        // Cancel. Persists via onCreateHighlight with the normalised offsets
        // and quoted text. Dismiss (or save) clears the pending selection so
        // the wash + toolbar both vanish.
        pendingSelection?.let { sel ->
            HighlightCreateSheet(
                quotedText = sel.quotedText,
                onConfirm = { colorName, note ->
                    onCreateHighlight(sel.start, sel.end, sel.quotedText, colorName, note)
                    pendingSelection = null
                },
                onDismiss = { pendingSelection = null },
            )
        }

        // Issue #999 phase 2 — highlight edit/delete sheet. Shown when the
        // user taps a saved highlight (editingHighlight set). Pre-fills the
        // current colour + note; Save edits in place, Delete removes it.
        editingHighlight?.let { ann ->
            HighlightEditSheet(
                annotation = ann,
                onSave = { colorName, note ->
                    onUpdateHighlight(ann, colorName, note)
                    editingHighlight = null
                },
                onDelete = {
                    onDeleteHighlight(ann.id)
                    editingHighlight = null
                },
                onDismiss = { editingHighlight = null },
            )
        }

        // Issue #998 — find-in-text bar. Slides down from the top as a
        // floating overlay (it doesn't reflow the immersive body or break
        // the swipe shell) when the search FAB is toggled on. Holds the
        // query field, a live "n / total" match counter, prev/next
        // chevrons, and a close button. Focus + keyboard are claimed on
        // open so the user can type immediately.
        LaunchedEffect(searchOpen) {
            if (searchOpen) {
                searchFocus.requestFocus()
                keyboard?.show()
            }
        }
        AnimatedVisibility(
            visible = searchOpen,
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
        ) {
            ReaderSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it; activeMatchIndex = 0 },
                matchCount = matches.size,
                activeMatchIndex = activeMatchIndex,
                onPrev = { activeMatchIndex = prevMatchIndex(activeMatchIndex, matches.size) },
                onNext = { activeMatchIndex = nextMatchIndex(activeMatchIndex, matches.size) },
                onClose = {
                    searchOpen = false
                    searchQuery = ""
                    activeMatchIndex = 0
                    keyboard?.hide()
                },
                focusRequester = searchFocus,
            )
        }
    }
    } // CompositionLocalProvider(LocalReaderColors) — #993
}

/**
 * Issue #998 — the reader's find-in-text bar. A brass [Surface] holding
 * the query field, a live match counter, prev/next chevrons, and a close
 * button. Pure presentational: all state is hoisted to [ReaderTextView],
 * which owns the query, the derived match list, and the active index.
 *
 * The IME "Search" action and the next chevron both advance to the next
 * match, so a one-handed user can press Enter to walk hits without
 * reaching for the chevron.
 */
@Composable
private fun ReaderSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    matchCount: Int,
    activeMatchIndex: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    focusRequester: FocusRequester,
) {
    val spacing = LocalSpacing.current
    val hasMatches = matchCount > 0
    val counterText = when {
        query.isBlank() -> ""
        !hasMatches -> stringResource(R.string.reader_search_no_matches)
        else -> stringResource(R.string.reader_search_count, activeMatchIndex + 1, matchCount)
    }
    val counterDescription = if (hasMatches) {
        stringResource(R.string.reader_search_count_description, activeMatchIndex + 1, matchCount)
    } else {
        counterText
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.sm, vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                label = { Text(stringResource(R.string.reader_search_field_label)) },
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Outlined.Search, contentDescription = null)
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onNext() }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
            )
            if (counterText.isNotEmpty()) {
                Text(
                    text = counterText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier
                        .padding(horizontal = spacing.xs)
                        .semantics { contentDescription = counterDescription },
                )
            }
            IconButton(onClick = onPrev, enabled = hasMatches) {
                Icon(
                    Icons.Outlined.ExpandLess,
                    contentDescription = stringResource(R.string.reader_search_prev),
                )
            }
            IconButton(onClick = onNext, enabled = hasMatches) {
                Icon(
                    Icons.Outlined.ExpandMore,
                    contentDescription = stringResource(R.string.reader_search_next),
                )
            }
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.reader_search_close),
                )
            }
        }
    }
}

/**
 * Issue #999 phase 2 — the create-highlight bottom sheet. Shown after a
 * selection drag lifts. A brass-tinted [ModalBottomSheet] holding:
 *  - the selected quote (so the user confirms *what* they're highlighting),
 *  - a row of colour-swatch chips (the small palette, reusing the reading
 *    theme's warm tones via [HighlightColor]),
 *  - an optional note field,
 *  - a Highlight (confirm) + Cancel action pair.
 *
 * TalkBack: each swatch chip carries a `contentDescription` naming its colour
 * and selected state, and the confirm button reads "Highlight". The sheet
 * itself is a focus-claiming surface, so the selection action is reachable
 * by swipe (phase-2 accessibility requirement).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun HighlightCreateSheet(
    quotedText: String,
    onConfirm: (colorName: String, note: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
    )
    var selectedColor by remember {
        mutableStateOf(`in`.jphe.storyvox.ui.theme.HighlightColor.Default)
    }
    var note by remember { mutableStateOf("") }

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg, vertical = spacing.md)
                .testTag(TestTags.HighlightSheet),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                text = stringResource(R.string.reader_highlight_create_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "“" + quotedText.trim().take(160) +
                    (if (quotedText.trim().length > 160) "…" else "") + "”",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
            )
            HighlightColorRow(
                selected = selectedColor,
                onSelect = { selectedColor = it },
            )
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text(stringResource(R.string.reader_highlight_note_label)) },
                modifier = Modifier.fillMaxWidth().testTag(TestTags.HighlightNoteField),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.reader_cancel))
                }
                Spacer(Modifier.size(spacing.sm))
                TextButton(
                    onClick = { onConfirm(selectedColor.name, note) },
                    modifier = Modifier.testTag(TestTags.HighlightConfirm),
                ) {
                    Text(stringResource(R.string.reader_highlight_confirm))
                }
            }
        }
    }
}

/**
 * Issue #999 phase 2 — the edit/delete bottom sheet for a saved highlight,
 * opened by tapping an existing highlight. Pre-fills the stored colour + note;
 * Save edits in place (REPLACE-on-id), Delete removes the row. Same brass
 * surface + TalkBack-reachable affordances as the create sheet.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun HighlightEditSheet(
    annotation: `in`.jphe.storyvox.data.db.entity.Annotation,
    onSave: (colorName: String, note: String?) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
    )
    var selectedColor by remember(annotation.id) {
        mutableStateOf(`in`.jphe.storyvox.ui.theme.HighlightColor.fromName(annotation.color))
    }
    var note by remember(annotation.id) { mutableStateOf(annotation.note.orEmpty()) }

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg, vertical = spacing.md)
                .testTag(TestTags.HighlightEditSheet),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                text = stringResource(R.string.reader_highlight_edit_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "“" + annotation.quotedText.trim().take(160) +
                    (if (annotation.quotedText.trim().length > 160) "…" else "") + "”",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
            )
            HighlightColorRow(
                selected = selectedColor,
                onSelect = { selectedColor = it },
            )
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text(stringResource(R.string.reader_highlight_note_label)) },
                modifier = Modifier.fillMaxWidth().testTag(TestTags.HighlightNoteField),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag(TestTags.HighlightDelete),
                ) {
                    Text(stringResource(R.string.reader_highlight_delete))
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.reader_cancel))
                }
                Spacer(Modifier.size(spacing.sm))
                TextButton(
                    onClick = { onSave(selectedColor.name, note) },
                    modifier = Modifier.testTag(TestTags.HighlightSave),
                ) {
                    Text(stringResource(R.string.reader_highlight_save))
                }
            }
        }
    }
}

/**
 * Issue #999 phase 2 — the colour-swatch picker row shared by the create and
 * edit sheets. One tappable circle per [HighlightColor]; the selected one
 * gets a brass ring. Each carries a TalkBack [contentDescription] naming the
 * colour + whether it's selected.
 */
@Composable
private fun HighlightColorRow(
    selected: `in`.jphe.storyvox.ui.theme.HighlightColor,
    onSelect: (`in`.jphe.storyvox.ui.theme.HighlightColor) -> Unit,
) {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier.fillMaxWidth().testTag(TestTags.HighlightPalette),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        `in`.jphe.storyvox.ui.theme.HighlightColor.entries.forEach { color ->
            val isSelected = color == selected
            val ringColor = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                Color.Transparent
            }
            val label = stringResource(
                if (isSelected) {
                    R.string.reader_highlight_color_selected
                } else {
                    R.string.reader_highlight_color_unselected
                },
                color.name,
            )
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = ringColor,
                        shape = CircleShape,
                    )
                    .padding(4.dp)
                    .background(
                        color = color.swatch,
                        shape = CircleShape,
                    )
                    .selectable(
                        selected = isSelected,
                        role = Role.RadioButton,
                        onClick = { onSelect(color) },
                    )
                    .semantics { contentDescription = label },
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
