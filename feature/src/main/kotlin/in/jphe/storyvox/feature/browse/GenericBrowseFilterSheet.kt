package `in`.jphe.storyvox.feature.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.feature.api.GenericBrowseFilter
import `in`.jphe.storyvox.feature.api.GenericDateRange
import `in`.jphe.storyvox.feature.api.GenericFilterCapabilities
import `in`.jphe.storyvox.feature.api.GenericSortOrder
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Issue #693 — generic browse-filter sheet shared by Gutenberg / arXiv
 * / HackerNews / RSS / Wikipedia / Wikisource / AO3 / Standard Ebooks
 * / Notion / PLOS / Outline.
 *
 * The sheet reads [GenericFilterCapabilities] for the active source
 * and renders only the rows that source actually supports — arXiv
 * hides the language row (papers are all English; category multi-
 * select is the meaningful axis), Wikipedia hides the sort row (the
 * Wikipedia API has no order knob), and so on.
 *
 * Source-specific shapes (Royal Road `BrowseFilter`, GitHub
 * `GitHubSearchFilter`, MemPalace `MemPalaceFilter`) continue to use
 * their dedicated sheets — this one covers the broad "search +
 * sort + category + language + date" common case.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GenericBrowseFilterSheet(
    sourceLabel: String,
    filter: GenericBrowseFilter,
    capabilities: GenericFilterCapabilities,
    onApply: (GenericBrowseFilter) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val spacing = LocalSpacing.current
    var local by remember { mutableStateOf(filter) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.md)
                .padding(bottom = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Text(
                stringResource(R.string.generic_filter_title, sourceLabel),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = spacing.sm),
            )

            if (capabilities.supportsSortOrder) {
                SectionLabel(stringResource(R.string.filter_sort_by_section))
                SortDropdown(
                    available = capabilities.availableSortOrders,
                    value = local.sortOrder,
                    onChange = { local = local.copy(sortOrder = it) },
                )
                HorizontalDivider()
            }

            if (capabilities.supportsCategory) {
                SectionLabel(stringResource(R.string.filter_category_section))
                if (capabilities.availableCategories.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                        verticalArrangement = Arrangement.spacedBy(spacing.xs),
                    ) {
                        ChipOption(
                            label = stringResource(R.string.generic_filter_chip_any),
                            selected = local.category == null,
                            onClick = { local = local.copy(category = null) },
                        )
                        capabilities.availableCategories.forEach { cat ->
                            ChipOption(
                                label = cat,
                                selected = local.category == cat,
                                onClick = {
                                    local = local.copy(
                                        category = if (local.category == cat) null else cat,
                                    )
                                },
                            )
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = local.category.orEmpty(),
                        onValueChange = { v ->
                            local = local.copy(category = v.trim().takeIf { it.isNotEmpty() })
                        },
                        placeholder = { Text(stringResource(R.string.filter_tags_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                HorizontalDivider()
            }

            if (capabilities.supportsLanguage) {
                SectionLabel(stringResource(R.string.filter_language_section))
                if (capabilities.availableLanguages.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                        verticalArrangement = Arrangement.spacedBy(spacing.xs),
                    ) {
                        ChipOption(
                            label = stringResource(R.string.generic_filter_chip_any),
                            selected = local.language == null,
                            onClick = { local = local.copy(language = null) },
                        )
                        capabilities.availableLanguages.forEach { lang ->
                            ChipOption(
                                label = lang,
                                selected = local.language == lang,
                                onClick = {
                                    local = local.copy(
                                        language = if (local.language == lang) null else lang,
                                    )
                                },
                            )
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = local.language.orEmpty(),
                        onValueChange = { v ->
                            local = local.copy(language = v.trim().takeIf { it.isNotEmpty() })
                        },
                        placeholder = { Text(stringResource(R.string.filter_language_iso_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                HorizontalDivider()
            }

            if (capabilities.supportsDateRange) {
                SectionLabel(stringResource(R.string.filter_recent_section))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    GenericDateRange.entries.forEach { range ->
                        ChipOption(
                            label = stringResource(range.uiLabelRes()),
                            selected = local.dateRange == range,
                            onClick = { local = local.copy(dateRange = range) },
                        )
                    }
                }
                HorizontalDivider()
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = spacing.md),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                OutlinedButton(
                    onClick = onReset,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.filter_reset)) }
                Button(
                    onClick = { onApply(local) },
                    modifier = Modifier.weight(2f),
                ) { Text(stringResource(R.string.filter_apply)) }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChipOption(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortDropdown(
    available: List<GenericSortOrder>,
    value: GenericSortOrder,
    onChange: (GenericSortOrder) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = stringResource(value.uiLabelRes()),
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            available.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.uiLabelRes())) },
                    onClick = {
                        onChange(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun GenericSortOrder.uiLabelRes(): Int = when (this) {
    GenericSortOrder.Default -> R.string.generic_sort_default
    GenericSortOrder.Newest -> R.string.generic_sort_newest
    GenericSortOrder.Popular -> R.string.generic_sort_popular
    GenericSortOrder.Title -> R.string.generic_sort_title
}

private fun GenericDateRange.uiLabelRes(): Int = when (this) {
    GenericDateRange.Any -> R.string.generic_range_any
    GenericDateRange.Last7Days -> R.string.generic_range_last_7_days
    GenericDateRange.Last30Days -> R.string.generic_range_last_30_days
    GenericDateRange.Last90Days -> R.string.generic_range_last_90_days
    GenericDateRange.LastYear -> R.string.generic_range_last_year
}

/** True when [filter] has any non-default knob set. Drives the
 *  filter-button badge on Browse → (generic) sources. */
fun GenericBrowseFilter.isActive(): Boolean =
    sortOrder != GenericSortOrder.Default ||
        !language.isNullOrBlank() ||
        !category.isNullOrBlank() ||
        dateRange != GenericDateRange.Any

fun GenericBrowseFilter.activeCount(): Int {
    var n = 0
    if (sortOrder != GenericSortOrder.Default) n++
    if (!language.isNullOrBlank()) n++
    if (!category.isNullOrBlank()) n++
    if (dateRange != GenericDateRange.Any) n++
    return n
}
