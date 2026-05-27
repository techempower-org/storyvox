package `in`.jphe.storyvox.feature.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.playback.EngineState
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.theme.LibraryNocturneTheme
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Issue #805 — typed playback-error banner. Displays above the player
 * controls when [EngineState.Error] is active, with an icon, message,
 * and recovery action tailored to the error subtype.
 *
 * The banner is dismissible (X button) and auto-clears when the engine
 * leaves the error state (e.g. after a successful retry). The error
 * message from [EngineState.Error.message] is already render-ready
 * (produced by PlaybackController.errorMessage) and includes subtype-
 * specific copy:
 *  - Auth failure (Azure key rejected) -> lock icon, no retry
 *  - Network unavailable -> wifi-off icon, retry
 *  - Throttled (429) -> schedule icon, retry
 *  - Server error (5xx) -> cloud-off icon, retry
 *  - Engine unavailable -> error icon, no retry (install needed)
 *  - TTS speak failed -> error icon, retry
 *  - Chapter fetch failed -> error icon, retry
 *
 * The [onRetry] callback fires playback.play() which clears the engine
 * error and re-attempts. The [onDismiss] callback records the dismissed
 * message so the ViewModel suppresses re-display of the same error.
 */
@Composable
fun PlaybackErrorBanner(
    error: EngineState.Error,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val classification = classifyError(error.message)

    AnimatedVisibility(
        visible = true,
        enter = expandVertically(),
        exit = shrinkVertically(),
    ) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.md, vertical = spacing.xs)
                .semantics { liveRegion = LiveRegionMode.Polite },
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
            shape = MaterialTheme.shapes.medium,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = spacing.md, top = spacing.sm, bottom = spacing.sm, end = spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = classification.icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(spacing.sm))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = classification.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = error.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (error.retryable) {
                    BrassButton(
                        label = stringResource(R.string.reader_error_retry),
                        onClick = onRetry,
                        variant = BrassButtonVariant.Text,
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.reader_error_dismiss),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

// ---- Error classification ----

/**
 * Local classification of the render-ready error message back into UX
 * categories. We pattern-match on the message strings produced by
 * PlaybackController.errorMessage() rather than leaking PlaybackError
 * subtypes into the feature layer. The strings are stable (we own them)
 * and the fallback is always the generic treatment.
 */
private data class ErrorClassification(
    val icon: ImageVector,
    val title: String,
)

@Composable
private fun classifyError(message: String): ErrorClassification {
    val lower = message.lowercase()
    return when {
        // Azure auth — "Azure subscription key was rejected"
        lower.contains("subscription key") || lower.contains("key was rejected") ->
            ErrorClassification(
                icon = Icons.Outlined.Lock,
                title = stringResource(R.string.reader_error_title_auth),
            )
        // Network unavailable — Azure or general network errors
        lower.contains("network") || lower.contains("connection") ||
        lower.contains("dns") || lower.contains("timeout") ->
            ErrorClassification(
                icon = Icons.Outlined.WifiOff,
                title = stringResource(R.string.reader_error_title_network),
            )
        // Throttled — "quota", "rate", "too many"
        lower.contains("quota") || lower.contains("throttl") ||
        lower.contains("rate limit") || lower.contains("too many") ->
            ErrorClassification(
                icon = Icons.Outlined.Schedule,
                title = stringResource(R.string.reader_error_title_throttled),
            )
        // Azure server error / outage
        lower.contains("server") || lower.contains("azure") ||
        lower.contains("outage") || lower.contains("5xx") ->
            ErrorClassification(
                icon = Icons.Outlined.CloudOff,
                title = stringResource(R.string.reader_error_title_server),
            )
        // Voice engine not installed
        lower.contains("engine") || lower.contains("installed") ->
            ErrorClassification(
                icon = Icons.Outlined.ErrorOutline,
                title = stringResource(R.string.reader_error_title_engine),
            )
        // Generic fallback
        else -> ErrorClassification(
            icon = Icons.Outlined.ErrorOutline,
            title = stringResource(R.string.reader_error_title_generic),
        )
    }
}

// region Previews

@Preview(name = "Auth error (dark)", widthDp = 360)
@Composable
private fun PreviewAuthDark() = LibraryNocturneTheme(darkTheme = true) {
    PlaybackErrorBanner(
        error = EngineState.Error(
            "Azure subscription key was rejected. Paste a fresh key in Settings → Cloud voices.",
            retryable = false,
        ),
        onRetry = {},
        onDismiss = {},
    )
}

@Preview(name = "Network error (dark)", widthDp = 360)
@Composable
private fun PreviewNetworkDark() = LibraryNocturneTheme(darkTheme = true) {
    PlaybackErrorBanner(
        error = EngineState.Error(
            "Network required for cloud voices.",
            retryable = true,
        ),
        onRetry = {},
        onDismiss = {},
    )
}

@Preview(name = "Throttled (light)", widthDp = 360)
@Composable
private fun PreviewThrottledLight() = LibraryNocturneTheme(darkTheme = false) {
    PlaybackErrorBanner(
        error = EngineState.Error(
            "Azure quota exceeded. Switch to a local voice or wait for monthly reset.",
            retryable = true,
        ),
        onRetry = {},
        onDismiss = {},
    )
}

@Preview(name = "Server error (dark)", widthDp = 360)
@Composable
private fun PreviewServerDark() = LibraryNocturneTheme(darkTheme = true) {
    PlaybackErrorBanner(
        error = EngineState.Error(
            "Azure server error (503). Try again in a moment.",
            retryable = true,
        ),
        onRetry = {},
        onDismiss = {},
    )
}

@Preview(name = "Generic error (dark)", widthDp = 360)
@Composable
private fun PreviewGenericDark() = LibraryNocturneTheme(darkTheme = true) {
    PlaybackErrorBanner(
        error = EngineState.Error(
            "Voice failed mid-utterance (code 3). Tap to retry.",
            retryable = true,
        ),
        onRetry = {},
        onDismiss = {},
    )
}

// endregion
