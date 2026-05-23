package `in`.jphe.storyvox.feature.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.source.rss.templates.CraigslistCategory
import `in`.jphe.storyvox.source.rss.templates.CraigslistRegion
import `in`.jphe.storyvox.source.rss.templates.CraigslistTemplates
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Issue #464 — Craigslist regional-feed template picker, surfaced as a
 * collapsible section inside the RSS-feed management bottom sheet
 * ([BrowseRssManageSheet]). Two chip strips (region → category) feed
 * a live URL preview; tapping Subscribe calls the existing
 * `viewModel.addRssFeed(url)` path so we inherit DataStore persistence,
 * polling cadence, and FictionDetail rendering with zero new plumbing.
 *
 * ## Why a collapsible section, not a dedicated screen
 *
 * The picker is intentionally lightweight — three short chip lists +
 * one preview line + one button. A separate screen would over-build
 * the affordance and force a navigation hop that hides the
 * relationship between "Add by URL" (paste any feed) and "Pick a
 * template" (compose a known-good URL). They're two paths to the same
 * outcome and belong on the same surface.
 *
 * ## Why we don't override the auto-generated title
 *
 * The fiction's display name comes from the RSS feed's own
 * `<channel><title>` once the source hydrates the detail (e.g.
 * "Bay Area free stuff classifieds for sale - craigslist"). That's
 * informative; overriding it with [CraigslistTemplates.friendlyTitle]
 * would mean the storyvox-side label drifts from the source-side one
 * and the user couldn't search the feed by Craigslist's exact phrase.
 * The friendly title is shown in this card's "you just subscribed to"
 * affordance, not stored.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BrowseCraigslistTemplateCard(
    canonicalSubscribedUrls: Set<String>,
    onSubscribe: (url: String) -> Unit,
) {
    val spacing = LocalSpacing.current
    var expanded by remember { mutableStateOf(false) }
    var selectedRegion by remember { mutableStateOf<CraigslistRegion?>(null) }
    var selectedCategory by remember { mutableStateOf<CraigslistCategory?>(null) }
    var lastSubscribedTitle by remember { mutableStateOf<String?>(null) }

    // a11y (#481): Role.Button + label for the Craigslist template expander.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                role = Role.Button,
                onClickLabel = if (expanded) stringResource(R.string.craigslist_collapse) else stringResource(R.string.craigslist_expand),
            ) { expanded = !expanded }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (expanded) {
                stringResource(R.string.craigslist_header_expanded)
            } else {
                stringResource(R.string.craigslist_header_collapsed)
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
    }
    if (!expanded) {
        Text(
            text = stringResource(R.string.craigslist_collapsed_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Text(
            text = stringResource(R.string.craigslist_step_region),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.xs),
        )
        // FlowRow wraps the chip strip — ~50 regions doesn't fit on
        // one line and a horizontally scrollable strip hides options
        // off-screen. The bottom sheet already supports vertical
        // scrolling so a multi-row chip block is the right shape.
        // Bound the height so the strip can't push everything else
        // out of reach in the sheet.
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .verticalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            CraigslistTemplates.REGIONS.forEach { region ->
                FilterChip(
                    selected = selectedRegion?.slug == region.slug,
                    onClick = { selectedRegion = region },
                    label = { Text(region.label) },
                    colors = brassFilterChipColors(),
                )
            }
        }

        Text(
            text = stringResource(R.string.craigslist_step_category),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.sm),
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            CraigslistTemplates.CATEGORIES.forEach { category ->
                FilterChip(
                    selected = selectedCategory?.slug == category.slug,
                    onClick = { selectedCategory = category },
                    label = { Text(category.label) },
                    colors = brassFilterChipColors(),
                )
            }
        }
        selectedCategory?.let { cat ->
            Text(
                text = cat.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Live URL preview — surfaces what we'll send to addRssFeed
        // before the user commits. Resolves the "what does this
        // actually do" question without forcing the user to open
        // Settings → diagnostics.
        val region = selectedRegion
        val category = selectedCategory
        val composedUrl = if (region != null && category != null) {
            CraigslistTemplates.composeFeedUrl(region, category)
        } else {
            null
        }
        val composedTitle = if (region != null && category != null) {
            CraigslistTemplates.friendlyTitle(region, category)
        } else {
            null
        }
        val alreadySubscribed = composedUrl?.lowercase() in canonicalSubscribedUrls

        if (composedUrl != null) {
            Column(modifier = Modifier.padding(top = spacing.sm)) {
                Text(
                    text = composedTitle ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = composedUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Text(
                text = stringResource(R.string.craigslist_pick_preview_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.sm),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = spacing.sm),
            horizontalArrangement = Arrangement.End,
        ) {
            BrassButton(
                label = if (alreadySubscribed) stringResource(R.string.craigslist_already_subscribed) else stringResource(R.string.craigslist_subscribe),
                onClick = {
                    if (composedUrl != null && !alreadySubscribed) {
                        onSubscribe(composedUrl)
                        lastSubscribedTitle = composedTitle
                        // Reset the selection so the user can compose
                        // another template without re-tapping the same
                        // chips.
                        selectedRegion = null
                        selectedCategory = null
                    }
                },
                variant = BrassButtonVariant.Primary,
                enabled = composedUrl != null && !alreadySubscribed,
            )
        }

        lastSubscribedTitle?.let { title ->
            Text(
                text = stringResource(R.string.craigslist_subscribed_confirmation, title),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = spacing.xs),
            )
        }
    }
}

/**
 * Brass-themed colour preset shared with the rest of the Browse-tab
 * filter chips. Kept local to this file so the card doesn't depend on
 * an internal helper from a sibling package — the duplication is one
 * `colors =` line, cheaper than exposing a public utility.
 */
@Composable
private fun brassFilterChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
)
