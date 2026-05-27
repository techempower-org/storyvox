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
 * Settings → Memory Palace subscreen (follow-up to #440 / #467).
 *
 * Hosts the daemon host / API-key / Test-connection trio inside the
 * shared [MemoryPalaceSection] composable. The palace is a LAN-only
 * fiction source (#79 spec) and lives in its own card on the legacy
 * long page; this subscreen surfaces the same form behind a
 * dedicated route.
 *
 * Status pill above the fields shows the last probe result; user-
 * typed edits clear the previous status (the address changed, the
 * verdict isn't authoritative any more).
 */
@Composable
fun MemoryPalaceSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current

    SettingsSubscreenScaffold(title = stringResource(R.string.settings_memory_palace_title), onBack = onBack) { padding ->
        val s = state.settings ?: run {
            SettingsSkeleton(modifier = Modifier.fillMaxSize().padding(padding).padding(spacing.md))
            return@SettingsSubscreenScaffold
        }
        SettingsSubscreenBody(padding) {
            SettingsGroupCard {
                MemoryPalaceSection(
                    palace = s.palace,
                    probe = state.palaceProbe,
                    probing = state.palaceProbing,
                    onSetHost = viewModel::setPalaceHost,
                    onSetApiKey = viewModel::setPalaceApiKey,
                    onClear = viewModel::clearPalaceConfig,
                    onTest = viewModel::testPalaceConnection,
                )
            }
        }
    }
}
