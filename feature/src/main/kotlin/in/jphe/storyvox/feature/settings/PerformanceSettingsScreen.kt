package `in`.jphe.storyvox.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.feature.api.CacheQuotaOptions
import `in`.jphe.storyvox.feature.api.UiNetworkPatience
import `in`.jphe.storyvox.feature.api.formatBytes
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Settings → Performance & buffering subscreen (follow-up to #440 / #467).
 *
 * Daily-tunable knobs at the top of the card; rarely-touched / expert
 * controls tucked behind [AdvancedExpander]. Mirrors the legacy
 * [SettingsScreen] "Performance & buffering" section row-for-row so
 * users searching from "All settings" find the same controls in the
 * same order.
 *
 * Top of card:
 *  - Catch-up Pause switch (#77) — pause+resume cleanly on underrun.
 *  - Buffer slider — colored amber/red past the recommended tick.
 *
 * PCM cache PR-G (#86):
 *  - Full Pre-render switch (Mode C) — opt-in aggressive caching.
 *  - Audio cache size selector — 500 MB / 2 GB / 5 GB / Unlimited.
 *  - Currently used + Clear cache row.
 *
 * Behind "More":
 *  - Warm-up Wait switch (#98 Mode A).
 *  - Voice Determinism preset (#85).
 *  - Tier-3 parallel-synth sliders (#88) — engines × threads.
 */
@Composable
fun PerformanceSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current

    SettingsSubscreenScaffold(title = stringResource(R.string.settings_performance_title), onBack = onBack) { padding ->
        val s = state.settings ?: run {
            SettingsSkeleton(modifier = Modifier.fillMaxSize().padding(padding).padding(spacing.md))
            return@SettingsSubscreenScaffold
        }
        SettingsSubscreenBody(padding) {
            SettingsGroupCard {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_performance_catchup_title),
                    subtitle = if (s.catchupPause) {
                        stringResource(R.string.settings_performance_catchup_on)
                    } else {
                        stringResource(R.string.settings_performance_catchup_off)
                    },
                    checked = s.catchupPause,
                    onCheckedChange = viewModel::setCatchupPause,
                )

                BufferSlider(
                    chunks = s.playbackBufferChunks,
                    onChunksChange = viewModel::setPlaybackBufferChunks,
                )

                // PCM cache PR-G (#86) — Mode C toggle. OFF by default;
                // ON expands the background pre-render scheduler (#503)
                // from "chapters 1-3 on add + N+2 on natural end" to
                // "every chapter of every library fiction." Subtitle
                // makes the disk-cost tradeoff explicit so users don't
                // flip it blind.
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_performance_full_prerender_title),
                    subtitle = if (s.fullPrerender) {
                        stringResource(R.string.settings_performance_full_prerender_on)
                    } else {
                        stringResource(R.string.settings_performance_full_prerender_off)
                    },
                    checked = s.fullPrerender,
                    onCheckedChange = viewModel::setFullPrerender,
                )

                // PCM cache PR-G (#86) — discrete quota selector.
                // Four BrassButton tiles in a Row; the currently-selected
                // tier renders Primary, the others Secondary so the
                // selection state reads at a glance.
                CacheSizeSelector(
                    quotaBytes = s.cacheQuotaBytes,
                    onQuotaChange = viewModel::setCacheQuotaBytes,
                )

                // PCM cache PR-G (#86) — live indicator + destructive
                // "Clear cache" affordance. The indicator polls
                // CacheStatsRepository at 5 s while this screen is open;
                // post-Clear the next emission re-reads from the wiped
                // cache root and the row drops to "0 B / 2 GB".
                CacheUsageRow(
                    usedBytes = s.cacheUsedBytes,
                    quotaBytes = s.cacheQuotaBytes,
                    onClearCache = viewModel::clearCache,
                )

                // Issue #596 — PCM-cache pre-render window. Caches
                // the next N chapters ahead of the current position.
                // Pre-#596 hardcoded at 5; users on tight disk can
                // shrink to 1-2, users with Wi-Fi-only Notion guides
                // can keep the wider window.
                val prerenderOptions = listOf(1, 2, 3, 5)
                val prerenderIndex = prerenderOptions
                    .indexOfFirst { it == s.prerenderChapterCount }
                    .let { if (it < 0) prerenderOptions.indexOf(5) else it }
                SettingsSegmentedBlock(
                    title = stringResource(R.string.settings_performance_prerender_window_title),
                    subtitle = stringResource(R.string.settings_performance_prerender_window_subtitle),
                    options = prerenderOptions.map { stringResource(R.string.settings_performance_prerender_window_option, it) },
                    selectedIndex = prerenderIndex,
                    onSelected = { idx ->
                        viewModel.setPrerenderChapterCount(prerenderOptions[idx])
                    },
                )

                // Issue #597 — network patience preset. Controls HTTP
                // timeouts in source-module OkHttp clients (royalroad,
                // notion, rss in v1; other modules adopt
                // incrementally). Aggressive favors fast-fail on
                // flaky cellular; Patient is forgiving on slow Notion
                // walks or large EPUB downloads.
                val patienceOptions = listOf(
                    UiNetworkPatience.Aggressive to stringResource(R.string.settings_performance_network_patience_aggressive),
                    UiNetworkPatience.Default to stringResource(R.string.settings_performance_network_patience_default),
                    UiNetworkPatience.Patient to stringResource(R.string.settings_performance_network_patience_patient),
                )
                val patienceIndex = patienceOptions
                    .indexOfFirst { it.first == s.networkPatience }
                    .let { if (it < 0) 1 else it }
                SettingsSegmentedBlock(
                    title = stringResource(R.string.settings_performance_network_patience_title),
                    subtitle = stringResource(R.string.settings_performance_network_patience_subtitle),
                    options = patienceOptions.map { it.second },
                    selectedIndex = patienceIndex,
                    onSelected = { idx ->
                        viewModel.setNetworkPatience(patienceOptions[idx].first)
                    },
                )

                var perfAdvancedOpen by remember { mutableStateOf(false) }
                AdvancedExpander(
                    titlesPreview = listOf(
                        stringResource(R.string.settings_performance_advanced_warmup),
                        stringResource(R.string.settings_performance_advanced_determinism),
                        stringResource(R.string.settings_performance_advanced_parallel),
                    ),
                    expanded = perfAdvancedOpen,
                    onToggle = { perfAdvancedOpen = !perfAdvancedOpen },
                ) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_performance_warmup_title),
                        subtitle = if (s.warmupWait) {
                            stringResource(R.string.settings_performance_warmup_on)
                        } else {
                            stringResource(R.string.settings_performance_warmup_off)
                        },
                        checked = s.warmupWait,
                        onCheckedChange = viewModel::setWarmupWait,
                    )
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_performance_determinism_title),
                        subtitle = if (s.voiceSteady) {
                            stringResource(R.string.settings_performance_determinism_on)
                        } else {
                            stringResource(R.string.settings_performance_determinism_off)
                        },
                        checked = s.voiceSteady,
                        onCheckedChange = viewModel::setVoiceSteady,
                    )
                    ParallelSynthSliders(
                        instances = s.parallelSynthInstances,
                        threadsPerInstance = s.synthThreadsPerInstance,
                        onInstancesChange = viewModel::setParallelSynthInstances,
                        onThreadsChange = viewModel::setSynthThreadsPerInstance,
                    )
                }
            }
        }
    }
}

/**
 * PCM cache PR-G (#86) — discrete quota tile row. Four
 * [BrassButton]s in a Row, selected tile renders [BrassButtonVariant.Primary]
 * and the others [BrassButtonVariant.Secondary] so the selection state
 * is obvious from any distance.
 *
 * Spec calls these "radio buttons"; the BrassButton row is the codebase's
 * existing idiom for discrete-option pickers (matches the Theme override
 * row in the legacy SettingsScreen).
 */
@Composable
private fun CacheSizeSelector(
    quotaBytes: Long,
    onQuotaChange: (Long) -> Unit,
) {
    val spacing = LocalSpacing.current
    Column(modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.sm)) {
        Text(stringResource(R.string.settings_performance_cache_size_title), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(R.string.settings_performance_cache_size_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(spacing.xs))
        Row(
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            modifier = Modifier.fillMaxWidth(),
        ) {
            CacheQuotaOptions.all.forEach { opt ->
                val variant = if (opt == quotaBytes) {
                    BrassButtonVariant.Primary
                } else {
                    BrassButtonVariant.Secondary
                }
                BrassButton(
                    label = CacheQuotaOptions.label(opt),
                    onClick = { onQuotaChange(opt) },
                    variant = variant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * PCM cache PR-G (#86) — "Currently used: X / Y" indicator + the
 * destructive Clear cache action. Confirmation [AlertDialog] gates the
 * wipe — spec doesn't explicitly mandate confirmation but losing the
 * cache forces a re-render on next play, so we err toward the
 * standard destructive-action pattern.
 *
 * For [CacheQuotaOptions.UNLIMITED] the quota side renders "no cap" so
 * the user doesn't see an "X / 9223372036854 GB" scientific-notation
 * mess.
 */
@Composable
private fun CacheUsageRow(
    usedBytes: Long,
    quotaBytes: Long,
    onClearCache: () -> Unit,
) {
    val spacing = LocalSpacing.current
    var showConfirm by rememberSaveable { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.md, vertical = spacing.sm),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_performance_cache_used_title), style = MaterialTheme.typography.bodyLarge)
            val quotaLabel = if (quotaBytes == CacheQuotaOptions.UNLIMITED) {
                stringResource(R.string.settings_performance_cache_no_cap)
            } else {
                formatBytes(quotaBytes)
            }
            Text(
                stringResource(R.string.settings_performance_cache_used_value, formatBytes(usedBytes), quotaLabel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BrassButton(
            label = stringResource(R.string.settings_performance_clear_cache),
            onClick = { showConfirm = true },
            variant = BrassButtonVariant.Secondary,
        )
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.settings_performance_clear_cache_confirm_title)) },
            text = {
                Text(
                    stringResource(R.string.settings_performance_clear_cache_confirm_body, formatBytes(usedBytes)),
                )
            },
            confirmButton = {
                BrassButton(
                    label = stringResource(R.string.settings_clear),
                    onClick = {
                        onClearCache()
                        showConfirm = false
                    },
                    variant = BrassButtonVariant.Primary,
                )
            },
            dismissButton = {
                BrassButton(
                    label = stringResource(R.string.settings_cancel),
                    onClick = { showConfirm = false },
                    variant = BrassButtonVariant.Secondary,
                )
            },
        )
    }
}
