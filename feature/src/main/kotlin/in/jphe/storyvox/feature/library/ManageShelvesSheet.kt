package `in`.jphe.storyvox.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import `in`.jphe.storyvox.data.db.entity.Shelf
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Issue #116 — bottom sheet opened by long-pressing a library card.
 * One row per [Shelf] with a toggle; flipping a toggle calls
 * [onToggle] which adds or removes the membership through
 * [LibraryViewModel.toggleShelf].
 *
 * Display labels come from [Shelf.displayName] — the data-layer owns
 * the user-facing strings so every shelf-aware surface shows the same
 * word (chip row, sheet, future "where is this book?" hint).
 *
 * Issue #828 — destructive "Remove from library" row at the bottom,
 * separated from the shelf toggles by a [HorizontalDivider]. Tapping
 * it does NOT remove inline — it surfaces [onRemoveRequest] so the
 * host (LibraryScreen) can gate the action behind an AlertDialog. The
 * read-progress-loss warning mirrors the existing #169 confirm flow
 * on [FictionDetailScreen]; we share strings + UX so users see one
 * voice for "you're about to drop this book."
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageShelvesSheet(
    state: ManageShelvesSheetState,
    onToggle: (String, Shelf) -> Unit,
    onRemoveRequest: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (state !is ManageShelvesSheetState.Open) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val spacing = LocalSpacing.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                text = "Manage shelves",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (state.fictionTitle.isNotBlank()) {
                Text(
                    text = state.fictionTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Shelf.ALL.forEach { shelf ->
                val isMember = shelf in state.memberOf
                // a11y (#478): toggleable row so TalkBack announces
                // "<shelf>, switch, on/off" as one Role.Switch node.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = isMember,
                            role = Role.Switch,
                            onValueChange = { onToggle(state.fictionId, shelf) },
                        )
                        .padding(vertical = spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = shelf.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Switch(
                        checked = isMember,
                        onCheckedChange = null,
                    )
                }
            }

            // Issue #828 — destructive "Remove from library" action.
            // Divider sets it apart from the additive shelf toggles so
            // it doesn't read as just another switch. Tinted with
            // colorScheme.error to signal destructiveness; host gates
            // the actual removal behind a confirm dialog.
            HorizontalDivider(modifier = Modifier.padding(vertical = spacing.sm))
            // #940 — padding before clickable so the padded area is part of
            // the tap target. The visible row (text + its vertical padding)
            // should all respond to a tap; otherwise edge-of-row taps miss
            // and the row falls short of the 48dp tap-target guideline.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = spacing.sm)
                    .clickable(role = Role.Button) {
                        onRemoveRequest(state.fictionId, state.fictionTitle)
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Remove from library",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
