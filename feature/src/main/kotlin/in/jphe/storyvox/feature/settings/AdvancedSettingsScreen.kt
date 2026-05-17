package `in`.jphe.storyvox.feature.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Settings → Advanced subscreen (v1 settings polish bundle).
 *
 * Home for power-user knobs that aren't a fit for Voice & Playback,
 * Appearance, Performance, or Accessibility — typically integration
 * surfaces (Android Auto / Wear / future widgets) and other rarely-
 * touched preferences. v1 ships one row:
 *
 *  - **Android Auto items per category** (#598) — controls how many
 *    items each Auto category tile exposes (Library / Follows /
 *    Recent / New). Default 6 matches Google's HMI guideline; users
 *    with very large libraries can widen to 8/12, and small-display
 *    head units may prefer 4.
 *
 * Future Advanced rows: Wear bridge tunables, browser-tree depth,
 * developer "force-classic UI" toggle.
 */
@Composable
fun AdvancedSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current

    SettingsSubscreenScaffold(title = "Advanced", onBack = onBack) { padding ->
        val s = state.settings ?: run {
            SettingsSkeleton(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(spacing.md),
            )
            return@SettingsSubscreenScaffold
        }
        SettingsSubscreenBody(padding) {
            SettingsGroupCard {
                // Issue #598 — Android Auto bucket size. Each Auto
                // category (Library / Follows / Recent / New) caps
                // its children at this many items. Pre-fix this was
                // hardcoded at 6 (StoryvoxAutoBrowserService.MAX_PER_CATEGORY,
                // matching Google's HMI default).
                val autoOptions = listOf(4, 6, 8, 12)
                val autoIndex = autoOptions
                    .indexOfFirst { it == s.autoItemsPerCategory }
                    .let { if (it < 0) autoOptions.indexOf(6) else it }
                SettingsSegmentedBlock(
                    title = "Android Auto: items per category",
                    subtitle = "How many items each Auto category exposes " +
                        "(Library / Follows / Recent / New). 6 matches Google's " +
                        "head-unit guideline; large libraries may prefer 8 or 12.",
                    options = autoOptions.map { "$it" },
                    selectedIndex = autoIndex,
                    onSelected = { idx ->
                        viewModel.setAutoItemsPerCategory(autoOptions[idx])
                    },
                )
            }
        }
    }
}
