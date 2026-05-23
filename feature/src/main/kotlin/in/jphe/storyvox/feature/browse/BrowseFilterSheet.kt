package `in`.jphe.storyvox.feature.browse

import androidx.compose.ui.unit.dp
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
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.feature.api.BrowseFilter
import `in`.jphe.storyvox.feature.api.UiContentWarning
import `in`.jphe.storyvox.feature.api.UiFictionStatus
import `in`.jphe.storyvox.feature.api.UiFictionType
import `in`.jphe.storyvox.feature.api.UiSearchOrder
import `in`.jphe.storyvox.feature.api.UiSortDirection
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Royal Road filter bottom sheet. Mirrors `/fictions/search` form. Local state
 * lets the user tweak many knobs before tapping Apply, so the search isn't
 * hammered on every chip toggle.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BrowseFilterSheet(
    filter: BrowseFilter,
    onApply: (BrowseFilter) -> Unit,
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
        // Issue #259 — Apply / Reset used to sit at the very bottom of
        // the scrollable Column, past 50+ chips of Include tags and
        // another 50+ for Exclude. On Flip3 inner display that meant
        // three+ swipes to reach the primary CTA. Lift Apply / Reset
        // out of the scrollable area so they stay pinned at the bottom
        // of the sheet — any change is one tap from confirmation.
        androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.md)
                    // Reserve room at the bottom of the scroll content so
                    // the last section isn't hidden by the sticky CTA bar.
                    .padding(bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
            Text(
                stringResource(R.string.royalroad_filter_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = spacing.sm),
            )

            // Sort by
            SortRow(
                orderBy = local.orderBy,
                direction = local.direction,
                onOrderBy = { local = local.copy(orderBy = it) },
                onDirection = { local = local.copy(direction = it) },
            )

            HorizontalDivider()

            // Status
            SectionLabel(stringResource(R.string.filter_status_section))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                UiFictionStatus.entries.forEach { status ->
                    val selected = status in local.statuses
                    FilterChip(
                        selected = selected,
                        onClick = {
                            local = local.copy(
                                statuses = if (selected) local.statuses - status else local.statuses + status,
                            )
                        },
                        label = { Text(stringResource(status.labelRes)) },
                        colors = brassFilterChipColors(),
                    )
                }
            }

            // Type
            SectionLabel(stringResource(R.string.filter_type_section))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                UiFictionType.entries.forEach { type ->
                    FilterChip(
                        selected = local.type == type,
                        onClick = { local = local.copy(type = type) },
                        label = { Text(stringResource(type.labelRes)) },
                        colors = brassFilterChipColors(),
                    )
                }
            }

            HorizontalDivider()

            // Tags include
            SectionLabel(stringResource(R.string.filter_include_tags_section))
            TagSelector(
                selected = local.tagsInclude,
                onChanged = { local = local.copy(tagsInclude = it) },
            )

            // Tags exclude
            SectionLabel(stringResource(R.string.filter_exclude_tags_section))
            TagSelector(
                selected = local.tagsExclude,
                onChanged = { local = local.copy(tagsExclude = it) },
            )

            HorizontalDivider()

            // Length (pages)
            SectionLabel(stringResource(R.string.filter_length_pages_section, local.pagesLabel()))
            val pagesRange = (local.minPages?.toFloat() ?: 0f)..(local.maxPages?.toFloat() ?: PAGES_MAX)
            RangeSlider(
                value = pagesRange,
                onValueChange = { range ->
                    local = local.copy(
                        minPages = range.start.toInt().takeIf { it > 0 },
                        maxPages = range.endInclusive.toInt().takeIf { it < PAGES_MAX },
                    )
                },
                valueRange = 0f..PAGES_MAX,
                steps = 0,
                // TalkBack #160 — RangeSlider announces a raw float pair
                // by default. State description mirrors the visible
                // pagesLabel() (e.g. "100 to 5000 pages") for parity.
                modifier = Modifier.semantics {
                    contentDescription = "Filter by page count"
                    stateDescription = local.pagesLabel()
                },
            )

            // Rating
            SectionLabel(stringResource(R.string.filter_rating_section, local.ratingLabel()))
            val ratingRange = (local.minRating ?: 0f)..(local.maxRating ?: 5f)
            RangeSlider(
                value = ratingRange,
                onValueChange = { range ->
                    local = local.copy(
                        minRating = range.start.takeIf { it > 0f },
                        maxRating = range.endInclusive.takeIf { it < 5f },
                    )
                },
                valueRange = 0f..5f,
                steps = 9,
                // TalkBack #160 — same rationale as the pages slider above.
                modifier = Modifier.semantics {
                    contentDescription = "Filter by star rating"
                    stateDescription = local.ratingLabel()
                },
            )

            HorizontalDivider()

            // Content warnings
            SectionLabel(stringResource(R.string.filter_content_warnings_exclude_section))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                UiContentWarning.entries.forEach { warning ->
                    val selected = warning in local.warningsExclude
                    FilterChip(
                        selected = selected,
                        onClick = {
                            local = local.copy(
                                warningsExclude = if (selected) local.warningsExclude - warning else local.warningsExclude + warning,
                                // Mutually exclusive with require — toggling exclude clears require for the same warning.
                                warningsRequire = local.warningsRequire - warning,
                            )
                        },
                        label = { Text(stringResource(warning.labelRes)) },
                        colors = brassFilterChipColors(),
                    )
                }
            }

        }

            // Issue #259 — sticky Apply / Reset bar pinned at the bottom
            // of the sheet. The Column above scrolls under this, so the
            // primary CTA is always one tap away regardless of how many
            // tags the user has expanded. surfaceContainerHigh + a small
            // shadow elevates the bar above the scrolling content so the
            // separation reads visually.
            androidx.compose.material3.Surface(
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.BottomCenter)
                    .fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 6.dp,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(spacing.md),
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
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SortRow(
    orderBy: UiSearchOrder,
    direction: UiSortDirection,
    onOrderBy: (UiSearchOrder) -> Unit,
    onDirection: (UiSortDirection) -> Unit,
) {
    val spacing = LocalSpacing.current
    SectionLabel(stringResource(R.string.filter_sort_by_section))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.weight(2f),
        ) {
            OutlinedTextField(
                value = stringResource(orderBy.labelRes),
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
                UiSearchOrder.entries.forEach { order ->
                    DropdownMenuItem(
                        text = { Text(stringResource(order.labelRes)) },
                        onClick = {
                            onOrderBy(order)
                            expanded = false
                        },
                    )
                }
            }
        }
        FilterChip(
            selected = direction == UiSortDirection.Desc,
            onClick = {
                onDirection(if (direction == UiSortDirection.Desc) UiSortDirection.Asc else UiSortDirection.Desc)
            },
            label = { Text(stringResource(if (direction == UiSortDirection.Desc) R.string.filter_sort_direction_desc else R.string.filter_sort_direction_asc)) },
            colors = brassFilterChipColors(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TagSelector(
    selected: Set<String>,
    onChanged: (Set<String>) -> Unit,
) {
    val spacing = LocalSpacing.current
    var query by remember { mutableStateOf("") }
    val matches = remember(query) {
        if (query.isBlank()) RoyalRoadTags.ALL.take(24)
        else RoyalRoadTags.ALL.filter { it.contains(query, ignoreCase = true) }.take(40)
    }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text(stringResource(R.string.filter_tags_label)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
    )
    if (selected.isNotEmpty()) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
            modifier = Modifier.padding(top = spacing.xs),
        ) {
            selected.forEach { tag ->
                FilterChip(
                    selected = true,
                    onClick = { onChanged(selected - tag) },
                    label = { Text(tag.replace('_', ' ')) },
                    colors = brassFilterChipColors(),
                )
            }
        }
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
        modifier = Modifier.padding(top = spacing.xs),
    ) {
        matches.forEach { tag ->
            val isSelected = tag in selected
            FilterChip(
                selected = isSelected,
                onClick = {
                    onChanged(if (isSelected) selected - tag else selected + tag)
                },
                label = { Text(tag.replace('_', ' ')) },
                colors = brassFilterChipColors(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun brassFilterChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
)

private const val PAGES_MAX = 3000f

private fun BrowseFilter.pagesLabel(): String {
    val lo = minPages ?: 0
    val hi = maxPages ?: PAGES_MAX.toInt()
    return "$lo – $hi"
}

private fun BrowseFilter.ratingLabel(): String {
    val lo = "%.1f".format(minRating ?: 0f)
    val hi = "%.1f".format(maxRating ?: 5f)
    return "$lo – $hi"
}

internal val UiFictionStatus.labelRes: Int
    get() = when (this) {
        UiFictionStatus.Ongoing -> R.string.fiction_status_ongoing
        UiFictionStatus.Completed -> R.string.fiction_status_completed
        UiFictionStatus.Hiatus -> R.string.fiction_status_hiatus
        UiFictionStatus.Stub -> R.string.fiction_status_stub
        UiFictionStatus.Dropped -> R.string.fiction_status_dropped
    }

internal val UiFictionType.labelRes: Int
    get() = when (this) {
        UiFictionType.All -> R.string.fiction_type_all
        UiFictionType.Original -> R.string.fiction_type_original
        UiFictionType.FanFiction -> R.string.fiction_type_fanfiction
    }

internal val UiContentWarning.labelRes: Int
    get() = when (this) {
        UiContentWarning.Profanity -> R.string.content_warning_profanity
        UiContentWarning.SexualContent -> R.string.content_warning_sexual
        UiContentWarning.GraphicViolence -> R.string.content_warning_violence
        UiContentWarning.SensitiveContent -> R.string.content_warning_sensitive
        UiContentWarning.AiAssisted -> R.string.content_warning_ai_assisted
        UiContentWarning.AiGenerated -> R.string.content_warning_ai_generated
    }

internal val UiSearchOrder.labelRes: Int
    get() = when (this) {
        UiSearchOrder.Relevance -> R.string.search_order_relevance
        UiSearchOrder.Popularity -> R.string.search_order_popularity
        UiSearchOrder.Rating -> R.string.search_order_rating
        UiSearchOrder.LastUpdate -> R.string.search_order_last_update
        UiSearchOrder.ReleaseDate -> R.string.search_order_release_date
        UiSearchOrder.Followers -> R.string.search_order_followers
        UiSearchOrder.Length -> R.string.search_order_length
        UiSearchOrder.Views -> R.string.search_order_views
        UiSearchOrder.Title -> R.string.search_order_title
    }
