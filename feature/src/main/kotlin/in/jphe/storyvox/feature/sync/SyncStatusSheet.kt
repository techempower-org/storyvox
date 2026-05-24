package `in`.jphe.storyvox.feature.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle

import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.sync.coordinator.SyncStatus
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Issue #500 — bottom sheet opened by the [SyncCloudIcon] tap.
 *
 * Two layouts driven by [SyncStatusViewModel.indicator]:
 *  - **Signed out** — brass hero copy ("Your library, woven into the
 *    night"), the [PassphraseVisualizer] running its decorative word
 *    reveal, a primary "Sign in to sync" CTA, and a "Learn more"
 *    text button that re-opens [SyncOnboardingHost] via
 *    [SyncOnboardingViewModel.openManually]. This makes the cloud
 *    icon the universal re-entry surface — once the user dismissed
 *    the first-launch card, this is how they come back.
 *  - **Signed in / syncing** — email + per-domain status grid sourced
 *    from [SyncCoordinator.status], plus a "Sign out" secondary
 *    button that routes to [SyncAuthScreen] (which renders its own
 *    signed-in panel with the destructive action).
 *
 * Why one sheet, two layouts (not two sheets): the cloud icon's
 * single tap should land the user in one place regardless of state.
 * A `when` inside the sheet body keeps the call site simple
 * (`if (open) SyncStatusSheet(...)`) and the surface honest about its
 * dual purpose: "this IS the sync surface."
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncStatusSheet(
    onDismiss: () -> Unit,
    onOpenSignIn: () -> Unit,
    onLearnMore: () -> Unit,
    viewModel: SyncStatusViewModel = hiltViewModel(),
) {
    val indicator by viewModel.indicator.collectAsStateWithLifecycle()
    val domainStatuses by viewModel.domainStatuses.collectAsStateWithLifecycle()
    val user by viewModel.signedInUser.collectAsStateWithLifecycle()

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val spacing = LocalSpacing.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            when (indicator) {
                SyncIndicator.SignedOut -> SignedOutBody(
                    onOpenSignIn = {
                        onDismiss()
                        onOpenSignIn()
                    },
                    onLearnMore = {
                        onDismiss()
                        onLearnMore()
                    },
                )
                SyncIndicator.SignedIn,
                SyncIndicator.Syncing -> SignedInBody(
                    email = user?.email ?: "(no email on file)",
                    indicator = indicator,
                    domainStatuses = domainStatuses,
                    onManage = {
                        onDismiss()
                        onOpenSignIn()
                    },
                )
            }
            Spacer(Modifier.height(spacing.sm))
        }
    }
}

@Composable
private fun SignedOutBody(
    onOpenSignIn: () -> Unit,
    onLearnMore: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Text(
        text = "Your library, woven into the night",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    Text(
        text = "Sign in to sync your library, follows, reading positions, " +
            "and pronunciation dictionary across devices.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(spacing.xs))
    PassphraseVisualizer(
        words = DEMO_PASSPHRASE_WORDS,
        modifier = Modifier.padding(vertical = spacing.xs),
    )
    Spacer(Modifier.height(spacing.xs))
    BrassButton(
        label = "Sign in to sync",
        onClick = onOpenSignIn,
        variant = BrassButtonVariant.Primary,
        modifier = Modifier.fillMaxWidth(),
    )
    BrassButton(
        label = "Learn more",
        onClick = onLearnMore,
        variant = BrassButtonVariant.Text,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SignedInBody(
    email: String,
    indicator: SyncIndicator,
    domainStatuses: Map<String, SyncStatus>,
    onManage: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = when (indicator) {
                SyncIndicator.Syncing -> Icons.Filled.Sync
                else -> Icons.Filled.CheckCircle
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.size(spacing.xs))
        Text(
            text = when (indicator) {
                SyncIndicator.Syncing -> "Syncing in progress"
                else -> "Signed in"
            },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    Text(
        text = email,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )

    if (domainStatuses.isNotEmpty()) {
        Spacer(Modifier.height(spacing.xs))
        Text(
            text = "Domains",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Sorted by name for stable rendering — the multibound Set is
        // unordered, so a re-emission can reshuffle the keys and
        // visually jitter the list otherwise. Display names are
        // already user-friendly snake_case-free domain identifiers
        // from each syncer's `name` property.
        domainStatuses.entries
            .sortedBy { it.key }
            .forEach { (domain, status) ->
                DomainStatusRow(domain = domain, status = status)
            }
    }

    Spacer(Modifier.height(spacing.xs))
    BrassButton(
        label = "Manage sync",
        onClick = onManage,
        variant = BrassButtonVariant.Secondary,
        modifier = Modifier.fillMaxWidth(),
    )
}

// DomainStatusRow, DomainStatusIcon, describeSyncStatus are in
// SyncStatusComposables.kt (shared with AccountSettingsScreen).
