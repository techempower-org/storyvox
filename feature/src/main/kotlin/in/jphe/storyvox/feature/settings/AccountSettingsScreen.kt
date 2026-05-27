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
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.feature.R
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

    SettingsSubscreenScaffold(title = stringResource(R.string.settings_account_title), onBack = onBack) { padding ->
        val s = state.settings ?: run {
            SettingsSkeleton(modifier = Modifier.fillMaxSize().padding(padding).padding(spacing.md))
            return@SettingsSubscreenScaffold
        }
        SettingsSubscreenBody(padding) {
            // ── Cloud Sync ──────────────────────────────────────────
            if (syncState.signedInUser != null) {
                SettingsGroupCard {
                    StatusPill(
                        text = if (syncState.anySyncing) stringResource(R.string.settings_account_cloud_sync_syncing) else stringResource(R.string.settings_account_cloud_sync_connected),
                        tone = StatusTone.Connected,
                    )
                    SettingsRow(
                        title = stringResource(R.string.settings_account_signed_in_as),
                        subtitle = syncState.signedInUser?.email ?: stringResource(R.string.settings_account_no_email),
                    )

                    if (syncState.domainStatuses.isNotEmpty()) {
                        Text(
                            stringResource(R.string.settings_account_domain_status),
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
                        label = if (syncState.anySyncing) stringResource(R.string.settings_account_syncing) else stringResource(R.string.settings_account_sync_now),
                        onClick = syncViewModel::syncNow,
                        variant = BrassButtonVariant.Secondary,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !syncState.anySyncing,
                    )
                }

                // ── Secrets Passphrase ──────────────────────────────
                SettingsGroupCard {
                    StatusPill(
                        text = if (syncState.passphraseSet) stringResource(R.string.settings_account_secrets_enabled) else stringResource(R.string.settings_account_secrets_not_configured),
                        tone = if (syncState.passphraseSet) StatusTone.Connected else StatusTone.Neutral,
                    )
                    Text(
                        stringResource(R.string.settings_account_secrets_body),
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
                        text = stringResource(R.string.settings_account_cloud_sync_not_signed_in),
                        tone = StatusTone.Neutral,
                    )
                    SettingsRow(
                        title = stringResource(R.string.settings_account_cloud_sync_title),
                        subtitle = stringResource(R.string.settings_account_cloud_sync_signin_subtitle),
                        trailing = {
                            BrassButton(
                                label = stringResource(R.string.settings_sign_in),
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
                    text = if (s.isSignedIn) stringResource(R.string.settings_account_royal_road_signed_in) else stringResource(R.string.settings_account_royal_road_not_signed_in),
                    tone = if (s.isSignedIn) StatusTone.Connected else StatusTone.Neutral,
                )
                if (s.isSignedIn) {
                    SettingsRow(
                        title = stringResource(R.string.settings_account_royal_road_title),
                        subtitle = stringResource(R.string.settings_account_royal_road_signed_in_subtitle),
                        trailing = {
                            BrassButton(
                                label = stringResource(R.string.settings_sign_out),
                                onClick = viewModel::signOut,
                                variant = BrassButtonVariant.Secondary,
                            )
                        },
                    )
                    RoyalRoadTagSyncRow()
                } else {
                    SettingsRow(
                        title = stringResource(R.string.settings_account_royal_road_title),
                        subtitle = stringResource(R.string.settings_account_royal_road_signin_subtitle),
                        trailing = {
                            BrassButton(
                                label = stringResource(R.string.settings_sign_in),
                                onClick = onOpenSignIn,
                                variant = BrassButtonVariant.Primary,
                            )
                        },
                    )
                }

                StatusPill(
                    text = when (val g = s.github) {
                        UiGitHubAuthState.Anonymous -> stringResource(R.string.settings_account_github_not_signed_in)
                        is UiGitHubAuthState.SignedIn ->
                            g.login?.let { stringResource(R.string.settings_account_github_signed_in_as, it) }
                                ?: stringResource(R.string.settings_account_github_signed_in)
                        UiGitHubAuthState.Expired -> stringResource(R.string.settings_account_github_expired)
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
                stringResource(R.string.settings_account_passphrase_set),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            BrassButton(
                label = stringResource(R.string.settings_clear),
                onClick = onClear,
                variant = BrassButtonVariant.Secondary,
            )
        }
    } else {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text(stringResource(R.string.settings_account_passphrase_label)) },
            placeholder = { Text(stringResource(R.string.settings_account_passphrase_placeholder)) },
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
            label = stringResource(R.string.settings_account_passphrase_set_button),
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
