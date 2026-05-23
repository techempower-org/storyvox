package `in`.jphe.storyvox.feature.library

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import `in`.jphe.storyvox.data.db.entity.Shelf
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Issue #116 — chip strip above the library grid. Four chips: All
 * (default) + the three predefined shelves. Tapping a chip filters the
 * grid; All restores the un-filtered view.
 *
 * Horizontally scrollable to match the [VoiceLibraryScreen] chip row
 * pattern — Flip3 portrait can fit all four today but new shelves in v2
 * would otherwise force a layout rethink. Brass palette via
 * `primaryContainer` for the selected state, same as the rest of
 * Library Nocturne's chip surfaces.
 */
@Composable
fun ShelfChipRow(
    selected: ShelfFilter,
    onSelect: (ShelfFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = spacing.md, vertical = spacing.xxs),
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = selected is ShelfFilter.All,
            onClick = { onSelect(ShelfFilter.All) },
            label = { Text(stringResource(R.string.library_shelf_all)) },
            colors = brassFilterChipColors(),
        )
        Shelf.ALL.forEach { shelf ->
            val isSelected = selected is ShelfFilter.OneShelf && selected.shelf == shelf
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(ShelfFilter.OneShelf(shelf)) },
                label = { Text(shelf.displayName) },
                colors = brassFilterChipColors(),
            )
        }
    }
}

@Composable
private fun brassFilterChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
)
