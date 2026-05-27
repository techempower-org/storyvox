package `in`.jphe.storyvox.feature.sync

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Issue #500 — the magical first-launch InstantDB sign-in card.
 *
 * Visual layered like a brass-edged Dialog over the rest of the app
 * (same pattern as [`in.jphe.storyvox.ui.component.MilestoneDialog`]).
 * Mounted at the navigation host root by
 * [SyncOnboardingHost] so it shows on top of whatever the first launch
 * landed on (today: Library).
 *
 * Card surface:
 *  - Cloud-sync icon in brass primary
 *  - Headline "Carry your library across devices" (Library Nocturne copy)
 *  - Animated [PassphraseVisualizer] illustrating the cross-device sync
 *    concept (decorative; the actual auth is email + magic code, see
 *    the kdoc on [DEMO_PASSPHRASE_WORDS])
 *  - Three CTAs:
 *      1. **Sign in to sync** — primary brass button, routes to
 *         [SyncAuthScreen] (the existing email + magic-code flow)
 *      2. **Skip for now** — secondary; persists the dismissed flag
 *         via [SyncOnboardingViewModel.dismiss] so this never re-
 *         prompts (per the issue's "Skip is fully respected" rule)
 *
 * The "Generate new" CTA from the issue's spec is elided in v1: the
 * underlying InstantDB auth is email-bound (the email IS the user
 * identity), so there's nothing to "generate" at the sign-in layer.
 * The passphrase-as-pure-recovery flow is the issue's documented
 * "Out of scope (later)" item. When that lands the third button
 * becomes a real "Generate passphrase" path with the visualizer
 * showing the actual generated words.
 *
 * Why a Dialog, not a Bottom sheet: the issue says "before landing
 * in Library," which is *not* a tab transition. A Dialog
 * dimming-the-app-and-presenting matches the felt experience of
 * "the realm is welcoming you in" better than a sheet, which feels
 * like the user is reading a notification. Same call as Calliope's
 * MilestoneDialog made for the v0.5.00 moment.
 */
@Composable
fun SyncOnboardingHost(
    onOpenSignIn: () -> Unit,
    viewModel: SyncOnboardingViewModel = hiltViewModel(),
) {
    val show by viewModel.shouldShow.collectAsStateWithLifecycle()
    if (show) {
        SyncOnboardingDialog(
            onDismiss = viewModel::dismiss,
            onOpenSignIn = {
                // Mark dismissed BEFORE opening sign-in so a back-press from
                // the auth screen doesn't bounce the user back into the
                // onboarding card. The mental model: "I made my choice."
                viewModel.dismiss()
                onOpenSignIn()
            },
        )
    }
}

@Composable
private fun SyncOnboardingDialog(
    onDismiss: () -> Unit,
    onOpenSignIn: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Dialog(
        onDismissRequest = onDismiss,
        // Issue #500 — once shown, the card requires an explicit
        // choice (Sign-in or Skip). Outside-tap STILL dismisses (it's
        // a tacit "skip"), but back-press is captured by the dialog
        // and routed through [onDismiss] for the same permanence:
        // there is no "show this later" state, only "deferred forever
        // unless the user re-opens it from the cloud-icon sheet."
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            // No usePlatformDefaultWidth — let the Card size itself.
            usePlatformDefaultWidth = false,
        ),
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier
                .widthIn(min = 280.dp, max = 360.dp)
                .padding(horizontal = spacing.lg),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Brass cloud-sync icon — using Material's
                // Icons.Filled.CloudSync at primary tint until a
                // bespoke candlelit cloud asset lands. The issue
                // calls for a "candlelit style" cloud; the brass
                // tint over the warm surface already lands much of
                // that aesthetic. A custom Path-drawn cloud is a
                // documented follow-up.
                Icon(
                    imageVector = Icons.Filled.CloudSync,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp),
                )
                Spacer(Modifier.height(spacing.md))

                Text(
                    text = "Carry your library across devices",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(spacing.sm))

                Text(
                    text = "Sign in to weave your library, follows, " +
                        "reading positions, and pronunciation dictionary " +
                        "into the night. Pick up where you left off on " +
                        "every device — encrypted end-to-end before it leaves.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(spacing.lg))

                // The magical word-by-word reveal — illustrative only
                // (the actual sign-in is email + magic code; the
                // passphrase concept lives at the encrypted-secrets
                // layer and is out of scope for v1, see
                // [DEMO_PASSPHRASE_WORDS] kdoc).
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    PassphraseVisualizer(words = DEMO_PASSPHRASE_WORDS)
                }
                Spacer(Modifier.height(spacing.lg))

                BrassButton(
                    label = "Sign in to sync",
                    onClick = onOpenSignIn,
                    variant = BrassButtonVariant.Primary,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(spacing.sm))
                BrassButton(
                    label = "Skip for now",
                    onClick = onDismiss,
                    variant = BrassButtonVariant.Text,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(spacing.xs))
                Text(
                    text = "You can sign in any time from the cloud icon in your library.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
