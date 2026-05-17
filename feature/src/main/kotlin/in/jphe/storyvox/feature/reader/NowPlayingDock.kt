package `in`.jphe.storyvox.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.feature.api.UiPlaybackState
import `in`.jphe.storyvox.ui.component.FictionCoverThumb
import `in`.jphe.storyvox.ui.component.fictionMonogram
import `in`.jphe.storyvox.ui.layout.isAtLeastTablet

/**
 * Issue #678 — persistent mini-dock surfacing "what's currently playing"
 * outside the Reader. Lives above the bottom-nav strip on phones and
 * pinned to the bottom of the content area on tablets.
 *
 * Visibility is gated by the host (see [`in`.jphe.storyvox.navigation.StoryvoxNavHost]):
 *  - `currentChapterId != null && currentFictionId != null` — a chapter
 *    is loaded (even when paused), so we have something meaningful to
 *    surface,
 *  - AND the current route is NOT a reader route (the Reader IS the
 *    full surface — a dock on top of it would be redundant).
 *
 * Tap-target split: the card body navigates to the Reader; the play/
 * pause button toggles transport in place without navigating. The next-
 * chapter button (tablet-only) advances without leaving the current
 * surface.
 *
 * Out of scope for v1.0 (#678 explicitly defers to v1.x):
 *  - swipe-up gesture to expand into a Spotify-style player,
 *  - scrubbable progress (today's bar is read-only).
 */
@Composable
fun NowPlayingDock(
    onOpenReader: (fictionId: String, chapterId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NowPlayingDockViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val fictionId = state.fictionId
    val chapterId = state.chapterId
    // Why: the host already gates on a chapter being loaded, but
    // re-checking here keeps the composable usable in isolation (e.g.
    // previews) without leaning on the host's gate. Without these
    // non-null ids we cannot build the reader nav route, so we bail.
    if (fictionId == null || chapterId == null) return
    NowPlayingDockContent(
        state = state,
        modifier = modifier,
        onOpenReader = { onOpenReader(fictionId, chapterId) },
        onTogglePlayPause = viewModel::togglePlayPause,
        onNextChapter = viewModel::nextChapter,
    )
}

/**
 * Presentation-only inner so previews / tests can drive it with a fake
 * [UiPlaybackState] without standing up a Hilt graph.
 */
@Composable
internal fun NowPlayingDockContent(
    state: UiPlaybackState,
    onOpenReader: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNextChapter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isTablet = isAtLeastTablet()
    val fictionTitle = state.fictionTitle.ifBlank { "Now playing" }
    val chapterTitle = state.chapterTitle
    val cardDescription =
        "Now playing: $fictionTitle. $chapterTitle. Tap to open reader."
    val playPauseLabel = if (state.isPlaying) "Pause" else "Play"
    // Why: progress is read-only in v1.0; clamp to [0, 1] so a stale
    // duration (e.g. mid-load) can't produce an out-of-range value the
    // LinearProgressIndicator would log-warn about.
    val progress = if (state.durationMs > 0L) {
        (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Surface(
        modifier = modifier
            .fillMaxWidth()
            // Why: liveRegion=Polite tells TalkBack to read chapter-title
            // updates as they change without interrupting the user's
            // current focus — surfaces auto-advance to the next chapter
            // even when the reader isn't focused.
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
            },
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    // Card body is the tap target for "open reader".
                    // The play/pause IconButton below sits inside this
                    // Row but consumes its own touches (Compose's
                    // default Material3 IconButton click handling
                    // stops the event), so the card click only fires
                    // on hits to the thumbnail/title area.
                    .clickable(
                        role = Role.Button,
                        onClickLabel = "Open reader",
                        onClick = onOpenReader,
                    )
                    .semantics { contentDescription = cardDescription }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FictionCoverThumb(
                    coverUrl = state.coverUrl,
                    title = fictionTitle,
                    monogram = fictionMonogram(author = "", title = fictionTitle),
                    modifier = Modifier
                        .size(width = 40.dp, height = 56.dp)
                        .clip(RoundedCornerShape(4.dp)),
                )
                Column(
                    modifier = Modifier
                        .weight(1f),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = fictionTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (chapterTitle.isNotBlank()) {
                        Text(
                            text = chapterTitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(
                    onClick = onTogglePlayPause,
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = playPauseLabel },
                ) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                if (isTablet) {
                    // Why: tablets have horizontal room for a secondary
                    // transport control; phones stay at one button so the
                    // 40dp thumb + ellipsized two-line title can breathe.
                    IconButton(
                        onClick = onNextChapter,
                        modifier = Modifier
                            .size(48.dp)
                            .semantics { contentDescription = "Next chapter" },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SkipNext,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // Why: a small trailing spacer on phones balances the
                // missing tablet button so the play/pause control stays
                // visually centered with its neighbors rather than
                // hugging the card edge.
                if (!isTablet) {
                    Spacer(modifier = Modifier.width(4.dp))
                }
            }
            // Slim bottom-edge chapter-progress bar. 2dp matches the
            // spec; LinearProgressIndicator defaults to 4dp so we pin
            // the height explicitly. Track is transparent so it reads
            // as "just a colored sliver", not "an empty pill".
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    strokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
                )
            }
        }
    }
}
