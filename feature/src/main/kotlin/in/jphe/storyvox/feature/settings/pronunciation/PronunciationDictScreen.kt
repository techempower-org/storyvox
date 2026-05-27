package `in`.jphe.storyvox.feature.settings.pronunciation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.data.repository.pronunciation.MatchType
import `in`.jphe.storyvox.data.repository.pronunciation.PronunciationEntry
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Settings → Pronunciation editor screen (issue #135). Phase 1 MVP —
 * a flat list of (pattern → replacement) entries the user has
 * configured to override the engine's default pronunciation. Each
 * row shows pattern + replacement + match type; a tap opens the
 * editor dialog, long-press isn't used (the editor's Delete button
 * is the destructive path).
 *
 * Inspired by epub_to_audiobook's `--search_and_replace_file` flag —
 * same shape (one substitution per row) without the file-format
 * overhead. NVDA's Speech Dictionary editor (`gui/__init__.py`'s
 * `DictionaryDlg`) is the closest analogue in OSS screen-reader land.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PronunciationDictScreen(
    onBack: () -> Unit,
    viewModel: PronunciationDictViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current

    // Editor dialog state — null = closed, EditorTarget.New = adding,
    // EditorTarget.Existing(i, e) = editing entry at i.
    var editor by remember { mutableStateOf<EditorTarget?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pronunciation") },
                // Issues #275 + #276 — standardize the back affordance
                // across all settings sub-screens to the TopAppBar arrow
                // (matching Sessions). The old inline 'Back' BrassButton
                // mid-screen was iOS-style and the only one of its kind.
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                "Replace specific words before they reach the voice engine. " +
                    "Useful for proper nouns the engine mispronounces — " +
                    "spell them how you want them said.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            BrassButton(
                label = "Add entry",
                onClick = { editor = EditorTarget.New },
                variant = BrassButtonVariant.Primary,
            )

            HorizontalDivider()

            if (state.entries.isEmpty()) {
                Text(
                    "No entries yet. Tap Add entry to teach the voice a name.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = spacing.md),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    itemsIndexed(
                        state.entries,
                        key = { i, e -> "$i:${e.pattern}:${e.replacement}" },
                    ) { i, entry ->
                        EntryRow(
                            entry = entry,
                            onClick = { editor = EditorTarget.Existing(i, entry) },
                        )
                    }
                }
            }

            // Issues #275 + #276 — the inline 'Back' BrassButton that
            // used to live here was iOS-modal style and the only such
            // affordance in the settings stack. Back-navigation is now
            // exclusively the TopAppBar arrow above (and OS gesture-back).
        }

        editor?.let { target ->
            EntryEditorDialog(
                initial = (target as? EditorTarget.Existing)?.entry,
                onSave = { pattern, replacement, matchType, caseSens ->
                    when (target) {
                        EditorTarget.New -> viewModel.addEntry(pattern, replacement, matchType, caseSens)
                        is EditorTarget.Existing -> viewModel.updateEntry(
                            target.index, pattern, replacement, matchType, caseSens,
                        )
                    }
                    editor = null
                },
                onDelete = (target as? EditorTarget.Existing)?.let { existing ->
                    {
                        viewModel.deleteEntry(existing.index)
                        editor = null
                    }
                },
                onDismiss = { editor = null },
            )
        }
    }
}

private sealed class EditorTarget {
    data object New : EditorTarget()
    data class Existing(val index: Int, val entry: PronunciationEntry) : EditorTarget()
}

@Composable
private fun EntryRow(
    entry: PronunciationEntry,
    onClick: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.xs),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "${entry.pattern}  →  ${entry.replacement}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                buildString {
                    append(entry.matchType.label())
                    if (entry.caseSensitive) append(" · case-sensitive")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BrassButton(label = "Edit", onClick = onClick, variant = BrassButtonVariant.Secondary)
    }
}

@Composable
private fun EntryEditorDialog(
    initial: PronunciationEntry?,
    onSave: (pattern: String, replacement: String, matchType: MatchType, caseSensitive: Boolean) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var pattern by remember { mutableStateOf(initial?.pattern ?: "") }
    var replacement by remember { mutableStateOf(initial?.replacement ?: "") }
    var matchType by remember { mutableStateOf(initial?.matchType ?: MatchType.WORD) }
    var caseSensitive by remember { mutableStateOf(initial?.caseSensitive ?: false) }
    // Issue #450 — explicit focus plumbing for the two-field dialog so a
    // tap on the Replacement field actually moves focus there (without
    // these, Compose's `bringIntoViewRequester` was keeping the Pattern
    // field focused inside an AlertDialog while the IME was up, and
    // typed characters concatenated into Pattern). The Next IME action
    // is the keyboard-side equivalent.
    val patternFocus = remember { FocusRequester() }
    val replacementFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add entry" else "Edit entry") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = { Text("Pattern (the word as written)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(patternFocus),
                )
                OutlinedTextField(
                    value = replacement,
                    onValueChange = { replacement = it },
                    label = { Text("Replacement (how to say it)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { focusManager.clearFocus() },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(replacementFocus),
                )

                // Match-type picker. Phase 1 exposes WORD (default) +
                // REGEX (power users); the other 5 NVDA modes land in
                // phase 2.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Match: ", style = MaterialTheme.typography.bodyMedium)
                    MatchType.entries.forEach { mt ->
                        val variant = if (matchType == mt) BrassButtonVariant.Primary else BrassButtonVariant.Secondary
                        BrassButton(
                            label = mt.label(),
                            onClick = { matchType = mt },
                            variant = variant,
                        )
                    }
                }

                // a11y (#478): toggleable row so TalkBack announces
                // "Case-sensitive, switch, on/off" as a single node.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = caseSensitive,
                            role = Role.Switch,
                            onValueChange = { caseSensitive = it },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Case-sensitive",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = caseSensitive, onCheckedChange = null)
                }
            }
        },
        confirmButton = {
            BrassButton(
                label = "Save",
                onClick = { onSave(pattern.trim(), replacement, matchType, caseSensitive) },
                variant = BrassButtonVariant.Primary,
            )
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onDelete != null) {
                    BrassButton(
                        label = "Delete",
                        onClick = onDelete,
                        variant = BrassButtonVariant.Secondary,
                    )
                }
                BrassButton(label = "Cancel", onClick = onDismiss, variant = BrassButtonVariant.Text)
            }
        },
    )
}

private fun MatchType.label(): String = when (this) {
    MatchType.WORD -> "Word"
    MatchType.ANYWHERE -> "Anywhere"
    MatchType.START_OF_WORD -> "Starts"
    MatchType.END_OF_WORD -> "Ends"
    MatchType.GLOB -> "Glob"
    MatchType.REGEX -> "Regex"
}
