package `in`.jphe.storyvox.feature.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.feature.api.SuggestedFeed
import `in`.jphe.storyvox.feature.api.SuggestedFeedKind
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Issue #459 — structural marker for [BrowseRssAddSubmitTest]. Set to
 * `true` when the RSS Add-by-URL surface stacks the Add button BELOW
 * the URL field (rather than the regressed pre-#459 Row layout where
 * field + button shared a single row with `Modifier.weight(1f)` on the
 * field). The regressed shape had a hit-target overlap on the Z Flip3:
 * taps on the Add button were routed to the EditText and inserted
 * digits at the cursor.
 */
internal const val rssAddButtonStackedBelowField: Boolean = true

/**
 * Issue #247 — RSS feed management bottom sheet, opened by the FAB on
 * Browse → RSS. Replaces the inline "RSS feeds" rows that used to
 * live in Settings → Library & Sync (the source on/off toggle stays
 * in Settings — that's a different control).
 *
 * Three sections:
 *  1. Add by URL — `OutlinedTextField` + `Add` button.
 *  2. Subscribed feeds — flat list with per-row `Remove` action.
 *  3. Suggested feeds — collapsible curated list grouped by category,
 *     one-tap subscribe.
 *
 * Voice and rhythm mirror the existing `AddByUrlSheet` for Library so
 * the two add-affordances feel like the same family — the user is
 * paste-something-and-tap-Add in both surfaces.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrowseRssManageSheet(
    viewModel: BrowseViewModel,
    onDismiss: () -> Unit,
) {
    val sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val spacing = LocalSpacing.current
    val subs by viewModel.rssSubscriptions.collectAsStateWithLifecycle()
    val suggested by viewModel.suggestedRssFeeds.collectAsStateWithLifecycle()

    var draftUrl by remember { mutableStateOf("") }
    var suggestionsExpanded by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    // Issue #459 — the Add button + Feed URL field used to share a single
    // Row(verticalAlignment = CenterVertically) with `Modifier.weight(1f)`
    // on the field and the button at the right. On the Z Flip3, taps in
    // the button's visible region (x=882-960) were routed to the
    // OutlinedTextField — the field's hit-target was extending past its
    // visible bounds, eating taps and inserting digits at the cursor.
    // Two-part fix: (a) stack the button BELOW the field on its own row
    // so the hit-targets can't overlap; (b) wire `ImeAction.Done` +
    // `onDone = submit()` so the keyboard's Go / enter key also submits,
    // matching `AddByUrlSheet`'s pattern from #200.
    fun submitDraft() {
        val trimmed = draftUrl.trim()
        if (trimmed.isNotEmpty()) {
            viewModel.addRssFeed(trimmed)
            draftUrl = ""
            focusManager.clearFocus()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.md)
                .padding(bottom = spacing.xl)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                stringResource(R.string.rss_manage_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = spacing.sm),
            )
            Text(
                stringResource(R.string.rss_manage_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ── Add by URL ───────────────────────────────────────────
            // Issue #459 — field on its own row, button on the next row.
            // The previous Row(field + button + CenterVertically) had a
            // hit-target overlap that ate button taps on the Flip3.
            OutlinedTextField(
                value = draftUrl,
                onValueChange = { draftUrl = it },
                label = { Text(stringResource(R.string.rss_feed_url_label)) },
                placeholder = { Text(stringResource(R.string.rss_feed_url_placeholder)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                ),
                keyboardActions = KeyboardActions(onDone = { submitDraft() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                BrassButton(
                    label = stringResource(R.string.rss_add),
                    onClick = { submitDraft() },
                    variant = BrassButtonVariant.Primary,
                    enabled = draftUrl.isNotBlank(),
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = spacing.xs))

            // ── Subscribed feeds ─────────────────────────────────────
            Text(
                text = if (subs.isEmpty()) stringResource(R.string.rss_no_subscriptions) else stringResource(R.string.rss_your_feeds_count, subs.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (subs.isEmpty()) {
                Text(
                    stringResource(R.string.rss_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                subs.forEach { url ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = url,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(onClick = { viewModel.removeRssFeedByUrl(url) }) {
                            Text(stringResource(R.string.rss_remove))
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = spacing.xs))

            // ── Local marketplace · Craigslist (collapsible) ─────────
            // Issue #464 — the curated regional-feed picker. Composes
            // a known-good Craigslist `?format=rss` URL from a
            // (region, category) chip selection and hands it to
            // `addRssFeed` — same path as the manual Add-by-URL flow
            // above, so the picker inherits persistence, polling, and
            // FictionDetail rendering for free.
            BrowseCraigslistTemplateCard(
                canonicalSubscribedUrls = subs.map { it.lowercase() }.toSet(),
                onSubscribe = { viewModel.addRssFeed(it) },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = spacing.xs))

            // ── Suggested feeds (collapsible) ────────────────────────
            // Collapsed by default so users who already have their own
            // feeds don't trip over a long curated list. Tap header to
            // expand.
            // a11y (#481): Role.Button + label for the suggestions expander.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        role = Role.Button,
                        onClickLabel = if (suggestionsExpanded) stringResource(R.string.rss_suggested_collapse) else stringResource(R.string.rss_suggested_expand),
                    ) { suggestionsExpanded = !suggestionsExpanded }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (suggestionsExpanded) stringResource(R.string.rss_suggested_expanded_header) else stringResource(R.string.rss_suggested_collapsed_header),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
            }
            if (!suggestionsExpanded) {
                Text(
                    text = stringResource(R.string.rss_suggested_collapsed_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                SuggestedFeedsList(
                    suggested = suggested,
                    canonicalSubs = subs.map { it.lowercase() }.toSet(),
                    onAdd = viewModel::addRssFeed,
                )
            }
        }
    }
}

@Composable
private fun SuggestedFeedsList(
    suggested: List<SuggestedFeed>,
    canonicalSubs: Set<String>,
    onAdd: (String) -> Unit,
) {
    val spacing = LocalSpacing.current
    val byCategory = suggested.groupBy { it.category }
    byCategory.forEach { (category, suggestions) ->
        Text(
            text = category,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.sm, bottom = 2.dp),
        )
        suggestions.forEach { feed ->
            val alreadyAdded = feed.url.lowercase() in canonicalSubs
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = feed.title,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = feed.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(when (feed.kind) {
                                SuggestedFeedKind.Text -> R.string.rss_kind_text
                                SuggestedFeedKind.AudioPodcast -> R.string.rss_kind_audio_podcast
                            }),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                    if (alreadyAdded) {
                        Text(
                            text = stringResource(R.string.rss_already_added),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = spacing.sm, end = spacing.sm),
                        )
                    } else {
                        BrassButton(
                            label = stringResource(R.string.rss_add),
                            onClick = { onAdd(feed.url) },
                            variant = BrassButtonVariant.Text,
                            modifier = Modifier.padding(start = spacing.sm),
                        )
                    }
                }
            }
        }
    }
}
