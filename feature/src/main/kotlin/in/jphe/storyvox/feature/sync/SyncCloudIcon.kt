package `in`.jphe.storyvox.feature.sync

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.ui.component.MagicSpinner

/**
 * Issue #500 — brass cloud-icon affordance for the Library top-app-
 * bar. Replaces the buried "Settings → Account → sync" path with a
 * one-tap surface from the primary home tab.
 *
 * Three visual states drive off [SyncStatusViewModel.indicator]:
 *  - [SyncIndicator.SignedIn] → [Icons.Filled.CloudDone] (cloud with
 *    integrated check). Brass primary tint.
 *  - [SyncIndicator.Syncing] → [Icons.Filled.Cloud] with a small
 *    [MagicSpinner] overlay at the bottom-right corner. Brass tint;
 *    the spinner reads as "ongoing work."
 *  - [SyncIndicator.SignedOut] → [Icons.Filled.CloudQueue] (the
 *    outlined cloud — feels like "potential, not committed").
 *    onSurfaceVariant tint so it doesn't visually claim primary
 *    attention while still being legible. Tap routes to the sign-in
 *    surface so the user can opt in mid-session.
 *
 * Why three Material icons rather than a single Path with overlays:
 * the issue's "candlelit cloud" aesthetic is a documented follow-up;
 * shipping with semantic Material icons is the lowest-risk path that
 * gets the discoverability win today. The brass tint + the wrapping
 * IconButton's contentDescription does most of the felt work; the
 * cloud silhouette is the disambiguator.
 *
 * Tap behavior:
 *  - SignedIn / Syncing → open the [SyncStatusSheet] bottom sheet
 *    (status detail + sign-out path)
 *  - SignedOut → open the [SyncStatusSheet] in its "you're not
 *    signed in" mode, which surfaces the [BrassButton] CTA into
 *    [SyncAuthScreen]
 */
@Composable
fun SyncCloudIcon(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SyncStatusViewModel = hiltViewModel(),
) {
    val indicator by viewModel.indicator.collectAsStateWithLifecycle()
    SyncCloudIconContent(
        indicator = indicator,
        onClick = onClick,
        modifier = modifier,
    )
}

/** Stateless variant for tests / previews. Drives off the explicit
 *  [SyncIndicator] rather than a VM. */
@Composable
internal fun SyncCloudIconContent(
    indicator: SyncIndicator,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Issue #611 — pre-fix the label described status ("Signed in to
    // sync") rather than the action TalkBack should announce on tap.
    // TalkBack reads contentDescription as "what this control IS plus
    // what tapping it does" — so an icon button needs an action verb
    // up front ("Manage sync"), with the status as a clarifying suffix.
    // Sighted users don't see the label at all; only TalkBack does.
    val a11yLabel = when (indicator) {
        SyncIndicator.SignedIn -> "Manage sync — currently signed in"
        SyncIndicator.Syncing -> "Manage sync — currently syncing"
        SyncIndicator.SignedOut -> "Manage sync — not signed in"
    }
    IconButton(onClick = onClick, modifier = modifier) {
        Box(contentAlignment = Alignment.Center) {
            when (indicator) {
                SyncIndicator.SignedIn -> Icon(
                    imageVector = Icons.Filled.CloudDone,
                    contentDescription = a11yLabel,
                    tint = MaterialTheme.colorScheme.primary,
                )
                SyncIndicator.Syncing -> {
                    Icon(
                        imageVector = Icons.Filled.Cloud,
                        contentDescription = a11yLabel,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    // Spinner offset toward the bottom-right of the
                    // cloud silhouette so it reads as "in flight"
                    // rather than overlapping the cloud's body. The
                    // 8.dp offset matches the cloud icon's bottom-
                    // right negative space on the Material asset.
                    MagicSpinner(
                        modifier = Modifier
                            .size(10.dp)
                            .offset(x = 8.dp, y = 8.dp),
                        strokeWidth = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                SyncIndicator.SignedOut -> Icon(
                    imageVector = Icons.Filled.CloudQueue,
                    contentDescription = a11yLabel,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
