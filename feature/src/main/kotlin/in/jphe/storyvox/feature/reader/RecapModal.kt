package `in`.jphe.storyvox.feature.reader

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.feature.api.UiRecapPlaybackState
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.component.MagicSpinner
import `in`.jphe.storyvox.ui.theme.LocalReducedMotion
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * The Chapter Recap modal. Streams the librarian's response into a
 * scrollable text body; brass cursor blinks at the end while the
 * stream is active. Cancel / Try again / Close buttons depending on
 * state.
 *
 * State machine — see [RecapUiState]:
 *  - Hidden — modal not rendered.
 *  - Loading — "Asking the librarian…" + spinner; Cancel is live.
 *  - Streaming — partial text + blinking cursor; Cancel is live.
 *  - Done — full text + Close button + Read-aloud icon button (#189).
 *  - Error — error message + recovery action (Settings link / Try
 *    again / Close).
 *
 * The Read-aloud icon button (issue #189) appears in the Done state.
 * Tap toggles between Play and Pause according to [recapPlayback]
 * (Idle → tap → Speaking; Speaking → tap → Idle). The TTS pipeline
 * lives in :core-playback so the audio survives modal recomposition;
 * the button is just the toggle UI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecapModal(
    state: RecapUiState,
    recapPlayback: UiRecapPlaybackState,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleReadAloud: () -> Unit,
) {
    if (state is RecapUiState.Hidden) return

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
    )
    val spacing = LocalSpacing.current

    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            // Header — subtitle copy switches with state so the
            // empty/error case doesn't read as "we're working on it"
            // when the body literally says "set up AI first" (#153).
            // Loading + Streaming use the present-progressive in-flight
            // copy; Done describes what's now on screen; Error /
            // unconfigured falls back to a neutral descriptor of the
            // feature itself.
            Column {
                Text(
                    text = stringResource(R.string.reader_recap_so_far),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(subtitleResFor(state)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Body
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                contentAlignment = Alignment.TopStart,
            ) {
                when (state) {
                    is RecapUiState.Loading -> LoadingBody()
                    is RecapUiState.Streaming -> StreamingBody(state.text)
                    is RecapUiState.Done -> Text(
                        text = state.text,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    is RecapUiState.Error -> ErrorBody(state)
                    RecapUiState.Hidden -> Unit  // unreachable
                }
            }

            // Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                when (state) {
                    is RecapUiState.Loading,
                    is RecapUiState.Streaming -> {
                        BrassButton(
                            label = stringResource(R.string.reader_cancel),
                            onClick = onCancel,
                            variant = BrassButtonVariant.Secondary,
                        )
                    }
                    is RecapUiState.Done -> {
                        BrassButton(
                            label = stringResource(R.string.reader_close),
                            onClick = onCancel,
                            variant = BrassButtonVariant.Secondary,
                        )
                        ReadAloudIconButton(
                            playbackState = recapPlayback,
                            onClick = onToggleReadAloud,
                        )
                    }
                    is RecapUiState.Error -> {
                        when (state.kind) {
                            RecapUiState.ErrorKind.NotConfigured,
                            RecapUiState.ErrorKind.AuthFailed -> {
                                BrassButton(
                                    label = stringResource(R.string.reader_open_settings),
                                    onClick = onOpenSettings,
                                    variant = BrassButtonVariant.Primary,
                                )
                                BrassButton(
                                    label = stringResource(R.string.reader_close),
                                    onClick = onCancel,
                                    variant = BrassButtonVariant.Secondary,
                                )
                            }
                            RecapUiState.ErrorKind.Transport,
                            RecapUiState.ErrorKind.ProviderError -> {
                                BrassButton(
                                    label = stringResource(R.string.reader_try_again),
                                    onClick = onRetry,
                                    variant = BrassButtonVariant.Primary,
                                )
                                BrassButton(
                                    label = stringResource(R.string.reader_close),
                                    onClick = onCancel,
                                    variant = BrassButtonVariant.Secondary,
                                )
                            }
                        }
                    }
                    RecapUiState.Hidden -> Unit
                }
            }
        }
    }
}

/**
 * Issue #189 — brass-tinted icon button toggling the recap-aloud TTS.
 * Renders a Play icon when [playbackState] is Idle, Pause when Speaking.
 * Driven by `ReaderViewModel.toggleRecapAloud`; the audio pipeline lives
 * in :core-playback so the button is purely a state toggle.
 */
@Composable
private fun ReadAloudIconButton(
    playbackState: UiRecapPlaybackState,
    onClick: () -> Unit,
) {
    val isSpeaking = playbackState == UiRecapPlaybackState.Speaking
    val brass = MaterialTheme.colorScheme.primary
    val (icon, label) = if (isSpeaking) {
        Icons.Filled.Pause to stringResource(R.string.reader_pause_read_aloud)
    } else {
        Icons.Filled.PlayArrow to stringResource(R.string.reader_read_aloud)
    }
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .clip(CircleShape)
            .semantics { role = Role.Button },
        colors = IconButtonDefaults.iconButtonColors(
            // Tonal brass background so the button reads as a primary
            // action without competing with the BrassButton "Close"
            // sibling (which is already brass-outlined Secondary).
            containerColor = brass.copy(alpha = 0.16f),
            contentColor = brass,
        ),
    ) {
        Icon(imageVector = icon, contentDescription = label)
    }
}

@Composable
private fun LoadingBody() {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = spacing.lg),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MagicSpinner()
        Text(
            text = stringResource(R.string.reader_recap_asking_librarian),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Streaming text + a blinking brass cursor. The cursor is a unicode
 * block character "▌" that fades in and out via an infinite alpha
 * animation; we honor LocalReducedMotion (no animation = static
 * cursor at full opacity) per the rest of Library Nocturne.
 */
@Composable
private fun StreamingBody(text: String) {
    val reducedMotion = LocalReducedMotion.current
    val cursorAlpha = if (reducedMotion) {
        1f
    } else {
        val infinite = rememberInfiniteTransition(label = "recap-cursor")
        val alpha by infinite.animateFloat(
            initialValue = 0.2f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 700),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "alpha",
        )
        alpha
    }

    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "▌",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.alpha(cursorAlpha),
        )
    }
}

/**
 * Subtitle copy for the [RecapModal] header, picked by state. Issue #153 —
 * the legacy hard-coded "Asking the librarian about the last few chapters."
 * read as in-progress regardless of state, which clashed with the body's
 * "Set up AI in Settings to use Recap." in the unconfigured/error case.
 *
 * - **Loading / Streaming** — present-progressive, matches the spinner /
 *   blinking-cursor in-flight visual.
 * - **Done** — past tense, matches the "this is the recap" body.
 * - **Error** (NotConfigured / AuthFailed / Transport / ProviderError) —
 *   neutral feature-descriptor, so the user reads the body's recovery copy
 *   without a contradictory "we're already on it" subtitle.
 *
 * Internal so [`PunctuationPauseTickPlacementTest`-style] unit testing
 * could cover the mapping if it grows beyond five branches.
 */
internal fun subtitleResFor(state: RecapUiState): Int = when (state) {
    is RecapUiState.Loading,
    is RecapUiState.Streaming -> R.string.reader_recap_subtitle_in_flight
    is RecapUiState.Done -> R.string.reader_recap_subtitle_done
    is RecapUiState.Error -> R.string.reader_recap_subtitle_error
    RecapUiState.Hidden -> R.string.empty_string  // unreachable — modal returns early when Hidden
}

@Composable
private fun ErrorBody(state: RecapUiState.Error) {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = state.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
    }
    // Suppress "unused parameter" — Color is used via colorScheme.
    @Suppress("UNUSED_VARIABLE") val unused: Color = MaterialTheme.colorScheme.error
}
