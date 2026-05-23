package `in`.jphe.storyvox.feature.library

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.feature.api.UiRouteCandidate
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Paste-anything sheet for adding a fiction by URL. Accepts Royal Road
 * fiction URLs today, with the GitHub branch wired but stubbed at the
 * data layer until step 3 of the GitHub-source plan lands.
 *
 * UX: input field auto-focused, "Paste" button reads the system
 * clipboard (single-tap fill from a copied URL), Add button submits.
 * The error string from the viewmodel surfaces inline below the field
 * so the user can correct without losing what they typed.
 *
 * Issue #700 — when the resolver returns multiple chooser-eligible
 * candidates, the sheet swaps to a picker row listing each backend so
 * the user disambiguates. The picker re-submits via [onChooseSource]
 * with the user's pick; [onCancelChoose] returns to the editable
 * input.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddByUrlSheet(
    state: AddByUrlSheetState,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
    onChooseSource: (String) -> Unit = {},
    onCancelChoose: () -> Unit = {},
) {
    if (state === AddByUrlSheetState.Hidden) return

    val sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val spacing = LocalSpacing.current
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }

    val error: String? = (state as? AddByUrlSheetState.Open)?.error
    val isSubmitting = state === AddByUrlSheetState.Submitting
    val chooser = state as? AddByUrlSheetState.ChooseSource

    ModalBottomSheet(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.md)
                .padding(bottom = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            if (chooser != null) {
                ChooseSourceBody(
                    url = chooser.url,
                    candidates = chooser.candidates,
                    onPick = onChooseSource,
                    onCancel = onCancelChoose,
                )
            } else {
                Text(
                    "Add by URL",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = spacing.sm),
                )
                // Issue #446 — copy used to read "Paste a Royal Road
                // fiction or chapter URL", which implied RR was the only
                // accepted source. Generalised once #472 added the
                // magic-link catch-alls; #700 wires the multi-match
                // chooser on top of that plumbing.
                Text(
                    "Paste a fiction URL — we'll auto-detect the source.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Supported: Royal Road · AO3 · GitHub · Gutenberg · arXiv · " +
                        "RSS · Wikipedia · direct EPUB",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(stringResource(R.string.library_add_by_url_label)) },
                    placeholder = { Text(stringResource(R.string.library_add_by_url_placeholder)) },
                    isError = error != null,
                    singleLine = true,
                    enabled = !isSubmitting,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (error != null) {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BrassButton(
                        label = "Paste",
                        onClick = {
                            readClipboardText(context)?.let { clip ->
                                if (clip.isNotBlank()) input = clip
                            }
                        },
                        variant = BrassButtonVariant.Secondary,
                        enabled = !isSubmitting,
                    )
                    BrassButton(
                        label = if (isSubmitting) "Adding…" else "Add",
                        onClick = { onSubmit(input.trim()) },
                        variant = BrassButtonVariant.Primary,
                        enabled = !isSubmitting && input.isNotBlank(),
                        loading = isSubmitting,
                    )
                }
            }
        }
    }
}

/**
 * Issue #700 — picker body shown when [AddByUrlSheetState.ChooseSource]
 * is the active state. Lists each candidate as a tappable row
 * (label + sourceId chip), with a Cancel button that returns the user
 * to the editable input. The list is intentionally a plain
 * [LazyColumn] capped at a sensible height so a pathological 10+
 * candidate resolution still scrolls inside the sheet instead of
 * exploding past the screen.
 */
@Composable
private fun ChooseSourceBody(
    url: String,
    candidates: List<UiRouteCandidate>,
    onPick: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Text(
        "Where do you want to add this from?",
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(top = spacing.sm),
    )
    Text(
        "Several sources can handle this URL. Pick the one that fits best.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        url,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        items(
            items = candidates,
            key = { it.sourceId + "/" + it.fictionId },
        ) { candidate ->
            CandidateRow(candidate = candidate, onPick = onPick)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        BrassButton(
            label = "Back",
            onClick = onCancel,
            variant = BrassButtonVariant.Secondary,
        )
    }
}

@Composable
private fun CandidateRow(
    candidate: UiRouteCandidate,
    onPick: (String) -> Unit,
) {
    val spacing = LocalSpacing.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPick(candidate.sourceId) }
            .semantics {
                role = Role.Button
                contentDescription = "Add from ${candidate.label}"
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.md, vertical = spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Text(
                candidate.label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                candidate.sourceId,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun readClipboardText(context: Context): String? {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return null
    val clip = cm.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0)?.text?.toString()
}
