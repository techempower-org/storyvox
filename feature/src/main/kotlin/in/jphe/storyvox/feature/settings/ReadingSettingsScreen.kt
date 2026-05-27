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
import `in`.jphe.storyvox.feature.api.ThemeOverride
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Settings → Reading subscreen (follow-up to #440 / #467).
 *
 * Visual reading knobs:
 *  - Theme override (System / Dark / Light, shipped in v0.5.32 #427).
 *  - Shake-to-extend sleep timer (#150).
 *
 * The legacy long-scroll [SettingsScreen] still renders the same two
 * rows under the "Reading" section heading; this subscreen is the
 * curated single-purpose surface reached from the hub.
 */
@Composable
fun ReadingSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current

    SettingsSubscreenScaffold(title = stringResource(R.string.settings_reading_title), onBack = onBack) { padding ->
        val s = state.settings ?: run {
            SettingsSkeleton(modifier = Modifier.fillMaxSize().padding(padding).padding(spacing.md))
            return@SettingsSubscreenScaffold
        }
        SettingsSubscreenBody(padding) {
            SettingsGroupCard {
                SettingsSegmentedBlock(
                    title = stringResource(R.string.settings_reading_theme_title),
                    subtitle = stringResource(R.string.settings_reading_theme_subtitle),
                    options = ThemeOverride.entries.map { it.name },
                    selectedIndex = ThemeOverride.entries.indexOf(s.themeOverride).coerceAtLeast(0),
                    onSelected = { idx -> viewModel.setTheme(ThemeOverride.entries[idx]) },
                )

                SettingsSwitchRow(
                    title = stringResource(R.string.settings_reading_shake_extend_title),
                    subtitle = stringResource(R.string.settings_reading_shake_extend_subtitle),
                    checked = s.sleepShakeToExtendEnabled,
                    onCheckedChange = viewModel::setSleepShakeToExtendEnabled,
                )
            }
        }
    }
}
