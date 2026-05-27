package `in`.jphe.storyvox.feature.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Settings → Cloud Voices subscreen (#712, follow-up to #440 / #467 /
 * #702).
 *
 * Hosts the Azure BYOK key / region / test-connection trio plus the
 * offline-fallback toggle inside the shared [AzureSection] composable.
 * Reached from the PluginManagerScreen's Azure plugin row "configure"
 * CTA — before this screen existed, that tap dumped the user onto the
 * 3,600-line legacy [SettingsScreen] long-scroll page, mirroring the
 * #704 bug pattern that #702 fixed for `onOpenAiSettings`.
 *
 * Same lean-screen pattern as [MemoryPalaceSettingsScreen]: shared
 * Hilt-injected [SettingsViewModel], the section composable itself
 * lives in [SettingsScreen.kt] and is reused verbatim so the legacy
 * long-scroll page and this focused subscreen stay byte-identical.
 *
 * Phase 3 / Plugin manager (#404) wired the Azure plugin row's
 * configure action to `onOpenAzureSettings`; this screen is its
 * destination.
 */
@Composable
fun CloudVoicesSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current

    SettingsSubscreenScaffold(title = stringResource(R.string.settings_cloud_voices_title), onBack = onBack) { padding ->
        val s = state.settings ?: run {
            SettingsSkeleton(modifier = Modifier.fillMaxSize().padding(padding).padding(spacing.md))
            return@SettingsSubscreenScaffold
        }
        SettingsSubscreenBody(padding) {
            SettingsGroupCard {
                AzureSection(
                    azure = s.azure,
                    probe = state.azureProbe,
                    probing = state.azureProbing,
                    onSetKey = viewModel::setAzureKey,
                    onSetRegion = viewModel::setAzureRegion,
                    onClear = viewModel::clearAzureCredentials,
                    onTest = viewModel::testAzureConnection,
                    fallbackEnabled = s.azureFallbackEnabled,
                    fallbackVoiceId = s.azureFallbackVoiceId,
                    installedVoices = state.voices,
                    onSetFallbackEnabled = viewModel::setAzureFallbackEnabled,
                    onSetFallbackVoice = viewModel::setAzureFallbackVoiceId,
                )
            }
        }
    }
}
