package `in`.jphe.storyvox.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.feature.api.UiGitHubAuthState
import `in`.jphe.storyvox.feature.settings.components.StatusPill
import `in`.jphe.storyvox.feature.settings.components.StatusTone
import `in`.jphe.storyvox.feature.sync.DomainStatusRow
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Settings → Account subscreen (follow-up to #440 / #467).
 *
 * Three sections:
 *  1. **Cloud Sync** — sync status, domain grid, passphrase entry,
 *     and a "Sync now" button. Visible only when signed in.
 *  2. **Royal Road** — WebView cookie auth (#91).
 *  3. **GitHub** — Device Flow OAuth + scope toggle (#91 / #203).
 */
@Composable
fun AccountSettingsScreen(
    onBack: () -> Unit,
    onOpenSignIn: () -> Unit,
    onOpenGitHubSignIn: () -> Unit,
    onOpenGitHubRevoke: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
    syncViewModel: AccountSyncViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val syncState by syncViewModel.state.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current

    SettingsSubscreenScaffold(title = "Account", onBack = onBack) { padding ->
        val s = state.settings ?: run {
            SettingsSkeleton(modifier = Modifier.fillMaxSize().padding(padding).padding(spacing.md))
            return@SettingsSubscreenScaffold
        }
        SettingsSubscreenBody(padding) {
            // ── Cloud Sync ──────────────────────────────────────────
            if (syncState.signedInUser != null) {
                SettingsGroupCard {
                    StatusPill(
                        text = if (syncState.anySyncing) "Cloud Sync · syncing" else "Cloud Sync · connected",
                        tone = StatusTone.Connected,
                    )
                    SettingsRow(
                        title = "Signed in as",
                        subtitle = syncState.signedInUser?.email ?: "(no email)",
                    )

                    if (syncState.domainStatuses.isNotEmpty()) {
                        Text(
                            "Domain status",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = spacing.xs),
                        )
                        syncState.domainStatuses.entries
                            .sortedBy { it.key }
                            .forEach { (domain, status) ->
                                DomainStatusRow(domain = domain, status = status)
                            }
                    }

                    Spacer(Modifier.height(spacing.xs))
                    BrassButton(
                        label = if (syncState.anySyncing) "Syncing…" else "Sync now",
                        onClick = syncViewModel::syncNow,
                        variant = BrassButtonVariant.Secondary,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !syncState.anySyncing,
                    )
                }

                // ── Secrets Passphrase ──────────────────────────────
                SettingsGroupCard {
                    StatusPill(
                        text = if (syncState.passphraseSet) "Secrets sync · enabled" else "Secrets sync · not configured",
                        tone = if (syncState.passphraseSet) StatusTone.Connected else StatusTone.Neutral,
                    )
                    Text(
                        "Encrypt API keys and tokens with a passphrase before syncing them to the cloud. " +
                            "The same passphrase is needed on every device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PassphraseEntry(
                        isSet = syncState.passphraseSet,
                        onSet = syncViewModel::setPassphrase,
                        onClear = syncViewModel::clearPassphrase,
                    )
                }
            } else {
                SettingsGroupCard {
                    StatusPill(
                        text = "Cloud Sync · not signed in",
                        tone = StatusTone.Neutral,
                    )
                    SettingsRow(
                        title = "Cloud Sync",
                        subtitle = "Sign in to sync library, follows, positions, and secrets across devices.",
                        trailing = {
                            BrassButton(
                                label = "Sign in",
                                onClick = onOpenSignIn,
                                variant = BrassButtonVariant.Primary,
                            )
                        },
                    )
                }
            }

            // ── Fiction Source Accounts ──────────────────────────────
            SettingsGroupCard {
                StatusPill(
                    text = if (s.isSignedIn) "Royal Road · signed in" else "Royal Road · not signed in",
                    tone = if (s.isSignedIn) StatusTone.Connected else StatusTone.Neutral,
                )
                if (s.isSignedIn) {
                    SettingsRow(
                        title = "Royal Road",
                        subtitle = "Signed in",
                        trailing = {
                            BrassButton(
                                label = "Sign out",
                                onClick = viewModel::signOut,
                                variant = BrassButtonVariant.Secondary,
                            )
                        },
                    )
                    RoyalRoadTagSyncRow()
                } else {
                    SettingsRow(
                        title = "Royal Road",
                        subtitle = "Sign-in unlocks Premium chapters and your Follows list.",
                        trailing = {
                            BrassButton(
                                label = "Sign in",
                                onClick = onOpenSignIn,
                                variant = BrassButtonVariant.Primary,
                            )
                        },
                    )
                }

                StatusPill(
                    text = when (val g = s.github) {
                        UiGitHubAuthState.Anonymous -> "GitHub · not signed in"
                        is UiGitHubAuthState.SignedIn ->
                            g.login?.let { "GitHub · signed in as @$it" }
                                ?: "GitHub · signed in"
                        UiGitHubAuthState.Expired -> "GitHub · session expired"
                    },
                    tone = when (s.github) {
                        UiGitHubAuthState.Anonymous -> StatusTone.Neutral
                        is UiGitHubAuthState.SignedIn -> StatusTone.Connected
                        UiGitHubAuthState.Expired -> StatusTone.Error
                    },
                )
                GitHubSignInRow(
                    state = s.github,
                    privateReposEnabled = s.githubPrivateReposEnabled,
                    onSignIn = onOpenGitHubSignIn,
                    onSignOut = viewModel::signOutGitHub,
                    onOpenRevokePage = onOpenGitHubRevoke,
                    onSetPrivateReposEnabled = viewModel::setGitHubPrivateReposEnabled,
                )
            }
        }
    }
}

@Composable
private fun PassphraseEntry(
    isSet: Boolean,
    onSet: (CharArray) -> Unit,
    onClear: () -> Unit,
) {
    val spacing = LocalSpacing.current
    var input by remember { mutableStateOf("") }

    if (isSet) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Passphrase is set",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            BrassButton(
                label = "Clear",
                onClick = onClear,
                variant = BrassButtonVariant.Secondary,
            )
        }
    } else {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("Sync passphrase") },
            placeholder = { Text("Enter a passphrase for secrets sync") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        BrassButton(
            label = "Set passphrase",
            onClick = {
                if (input.isNotBlank()) {
                    onSet(input.toCharArray())
                    input = ""
                }
            },
            variant = BrassButtonVariant.Primary,
            modifier = Modifier.fillMaxWidth().padding(top = spacing.xs),
            enabled = input.isNotBlank(),
        )
    }
}
