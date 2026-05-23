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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import `in`.jphe.storyvox.data.source.filter.FilterDimension
import `in`.jphe.storyvox.data.source.filter.FilterState
import `in`.jphe.storyvox.data.source.filter.FilterValue
import `in`.jphe.storyvox.ui.theme.LocalSpacing
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DynamicFilterSheet(
    sourceLabel: String,
    dimensions: List<FilterDimension>,
    state: FilterState,
    onApply: (FilterState) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val spacing = LocalSpacing.current
    var local by remember { mutableStateOf(state) }

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
                "Filter $sourceLabel",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = spacing.sm),
            )

            dimensions.forEach { dim ->
                when (dim) {
                    is FilterDimension.Sort -> SortSection(dim, local) { local = it }
                    is FilterDimension.Select -> SelectSection(dim, local) { local = it }
                    is FilterDimension.TagSet -> TagSetSection(dim, local) { local = it }
                    is FilterDimension.NumberRange -> NumberRangeSection(dim, local) { local = it }
                    is FilterDimension.DateRange -> DateRangeSection(dim, local) { local = it }
                    is FilterDimension.Toggle -> ToggleSection(dim, local) { local = it }
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
                ) { Text("Reset") }
                Button(
                    onClick = { onApply(local) },
                    modifier = Modifier.weight(2f),
                ) { Text("Apply") }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SortSection(
    dim: FilterDimension.Sort,
    state: FilterState,
    onChange: (FilterState) -> Unit,
) {
    val spacing = LocalSpacing.current
    val selected = state.stringVal(dim.key) ?: dim.default.id
    SectionLabel(dim.label)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        dim.options.forEach { option ->
            DimChip(
                label = option.label,
                selected = selected == option.id,
                onClick = { onChange(state.with(dim.key, FilterValue.StringVal(option.id))) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectSection(
    dim: FilterDimension.Select,
    state: FilterState,
    onChange: (FilterState) -> Unit,
) {
    val spacing = LocalSpacing.current
    val selected = state.stringVal(dim.key)
    SectionLabel(dim.label)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        DimChip(
            label = "Any",
            selected = selected == null,
            onClick = { onChange(state.without(dim.key)) },
        )
        dim.options.forEach { option ->
            DimChip(
                label = option,
                selected = selected == option,
                onClick = {
                    onChange(
                        if (selected == option) state.without(dim.key)
                        else state.with(dim.key, FilterValue.StringVal(option)),
                    )
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagSetSection(
    dim: FilterDimension.TagSet,
    state: FilterState,
    onChange: (FilterState) -> Unit,
) {
    val spacing = LocalSpacing.current
    val current = state.stringSetVal(dim.key)
    val included = current?.included.orEmpty()
    val excluded = current?.excluded.orEmpty()

    SectionLabel(dim.label)
    if (dim.options.isNotEmpty()) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            dim.options.forEach { tag ->
                val isIncluded = tag in included
                val isExcluded = tag in excluded
                FilterChip(
                    selected = isIncluded || isExcluded,
                    onClick = {
                        val newIncluded: Set<String>
                        val newExcluded: Set<String>
                        when {
                            isIncluded && dim.allowExclude -> {
                                newIncluded = included - tag
                                newExcluded = excluded + tag
                            }
                            isExcluded -> {
                                newIncluded = included
                                newExcluded = excluded - tag
                            }
                            else -> {
                                newIncluded = included + tag
                                newExcluded = excluded
                            }
                        }
                        if (newIncluded.isEmpty() && newExcluded.isEmpty()) {
                            onChange(state.without(dim.key))
                        } else {
                            onChange(
                                state.with(
                                    dim.key,
                                    FilterValue.StringSetVal(newIncluded, newExcluded),
                                ),
                            )
                        }
                    },
                    label = {
                        Text(
                            when {
                                isExcluded -> "- $tag"
                                isIncluded -> "+ $tag"
                                else -> tag
                            },
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = if (isExcluded) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        },
                        selectedLabelColor = if (isExcluded) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        },
                    ),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NumberRangeSection(
    dim: FilterDimension.NumberRange,
    state: FilterState,
    onChange: (FilterState) -> Unit,
) {
    val current = state.rangeVal(dim.key)
    val rangeMin = current?.min ?: dim.min
    val rangeMax = current?.max ?: dim.max
    val suffix = if (dim.formatLabel.isNotEmpty()) " ${dim.formatLabel}" else ""

    SectionLabel(dim.label)
    Text(
        "${formatNumber(rangeMin, dim.step)}$suffix — ${formatNumber(rangeMax, dim.step)}$suffix",
        style = MaterialTheme.typography.bodyMedium,
    )
    RangeSlider(
        value = rangeMin..rangeMax,
        onValueChange = { range ->
            val snappedMin = snapToStep(range.start, dim.step, dim.min)
            val snappedMax = snapToStep(range.endInclusive, dim.step, dim.min)
            if (snappedMin <= dim.min && snappedMax >= dim.max) {
                onChange(state.without(dim.key))
            } else {
                onChange(
                    state.with(
                        dim.key,
                        FilterValue.RangeVal(
                            min = if (snappedMin <= dim.min) null else snappedMin,
                            max = if (snappedMax >= dim.max) null else snappedMax,
                        ),
                    ),
                )
            }
        },
        valueRange = dim.min..dim.max,
        steps = ((dim.max - dim.min) / dim.step).toInt() - 1,
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DateRangeSection(
    dim: FilterDimension.DateRange,
    state: FilterState,
    onChange: (FilterState) -> Unit,
) {
    val spacing = LocalSpacing.current
    val selected = state.stringVal(dim.key)
    SectionLabel(dim.label)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        dim.presets.forEach { preset ->
            DimChip(
                label = preset.label,
                selected = if (selected == null) preset.id == "any" else selected == preset.id,
                onClick = {
                    if (preset.id == "any") onChange(state.without(dim.key))
                    else onChange(state.with(dim.key, FilterValue.StringVal(preset.id)))
                },
            )
        }
    }
}

@Composable
private fun ToggleSection(
    dim: FilterDimension.Toggle,
    state: FilterState,
    onChange: (FilterState) -> Unit,
) {
    val checked = state.boolVal(dim.key) ?: dim.default
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(dim.label, style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = checked,
            onCheckedChange = { value ->
                if (value == dim.default) onChange(state.without(dim.key))
                else onChange(state.with(dim.key, FilterValue.BoolVal(value)))
            },
        )
    }
}

@Composable
private fun DimChip(label: String, selected: Boolean, onClick: () -> Unit) {
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

private fun snapToStep(value: Float, step: Float, base: Float): Float {
    val steps = ((value - base) / step).roundToInt()
    return base + steps * step
}

private fun formatNumber(value: Float, step: Float): String =
    if (step >= 1f) value.roundToInt().toString()
    else "%.1f".format(value)
