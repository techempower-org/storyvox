package `in`.jphe.storyvox.feature.browse

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocalLibrary
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.plugin.SourcePluginDescriptor
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.ui.theme.LocalSpacing
import kotlinx.coroutines.launch

/**
 * v0.5.72 — magical hero source carousel for the first-class Browse tab.
 *
 * Replaces the flat `FilterChip` strip that the v0.5.40 restructure shipped.
 * Each enabled backend gets its own brass-edged hero card with a source-
 * appropriate icon glyph, a one-line display name, and a one-line tagline.
 * The selected card grows ~6 dp taller and lights its border in brass
 * primary, with the unselected cards holding back at a softer outline so
 * the active source reads at a glance.
 *
 * Why a richer carousel instead of the chip strip:
 *  - Browse is now a top-level destination, so "what sources does this app
 *    even have?" is a discovery question. The chip strip read as a search
 *    facet; the carousel reads as "pick your realm".
 *  - The taglines turn the picker into an informational surface — a fresh-
 *    install user can scan once and learn that Royal Road = web serials,
 *    Gutenberg = classics, Wikipedia = encyclopedia, etc., without first
 *    tapping each chip.
 *  - The icon + brass selection treatment gives the bar a visual identity
 *    that pairs with the fantasy-realm aesthetic JP's been pushing.
 *
 * The carousel auto-scrolls the selected card into view whenever
 * [selectedId] changes, so deep-links / sub-tab returns always land with
 * the active source visible.
 *
 * ## v0.5.76 — long-press favorites + hide
 *
 * Long-press any card to surface a [SourceContextSheet] with two actions:
 * - **Star** the source so it pins to the front of the row (in registry
 *   order within favorites). Tap the star row again to unstar.
 * - **Hide** the source from the carousel. Settings → Plugins re-enables
 *   it; the sheet's footer copy names that path so the user always has a
 *   way back.
 *
 * Favorited cards render a small filled star in the top-right corner,
 * persistent across selected/unselected states, in `primary` color.
 */
@Composable
internal fun BrowseSourceCarousel(
    selectedId: String,
    onSelect: (String) -> Unit,
    visibleSources: List<SourcePluginDescriptor>,
    favoriteSourceIds: Set<String> = emptySet(),
    onToggleFavorite: (String) -> Unit = {},
    onHideSource: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    if (visibleSources.isEmpty()) return

    val listState = rememberLazyListState()
    val selectedIndex = remember(selectedId, visibleSources) {
        visibleSources.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    }
    // Auto-scroll the selected card into view. The viewport stays anchored
    // on the active source so a user returning from a drill-down or a
    // deep-link never has to hunt for "which one is selected?".
    LaunchedEffect(selectedIndex, visibleSources.size) {
        if (selectedIndex in visibleSources.indices) {
            listState.animateScrollToItem(selectedIndex)
        }
    }

    // v0.5.76 — long-press target. Null when the sheet is dismissed.
    // Held at the carousel level (rather than per-card) so dismiss flow
    // and the sheet share one state surface and the sheet only mounts
    // once at a time.
    var contextSheetFor by remember { mutableStateOf<SourcePluginDescriptor?>(null) }

    LazyRow(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .height(CAROUSEL_HEIGHT),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        contentPadding = PaddingValues(horizontal = spacing.md, vertical = spacing.xs),
    ) {
        items(visibleSources, key = { it.id }) { descriptor ->
            BrowseSourceCard(
                descriptor = descriptor,
                isSelected = descriptor.id == selectedId,
                isFavorite = descriptor.id in favoriteSourceIds,
                onClick = { onSelect(descriptor.id) },
                onLongClick = { contextSheetFor = descriptor },
            )
        }
    }

    contextSheetFor?.let { descriptor ->
        SourceContextSheet(
            descriptor = descriptor,
            isFavorite = descriptor.id in favoriteSourceIds,
            onToggleFavorite = {
                onToggleFavorite(descriptor.id)
                contextSheetFor = null
            },
            onHide = {
                onHideSource(descriptor.id)
                contextSheetFor = null
            },
            onDismiss = { contextSheetFor = null },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BrowseSourceCard(
    descriptor: SourcePluginDescriptor,
    isSelected: Boolean,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    // Hoist into a local so the `Modifier.semantics { selected = ... }`
    // lambda below isn't confused by Compose's `selected` property name —
    // referring to `isSelected` inside the lambda is unambiguous.
    val selected = isSelected
    val spacing = LocalSpacing.current
    val primary = MaterialTheme.colorScheme.primary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    // Spring-animated lift so the selected card grows into focus instead
    // of snapping. Same lively curve as the BottomTabBar pill indicator.
    val cardHeight by animateDpAsState(
        targetValue = if (selected) CARD_HEIGHT_SELECTED else CARD_HEIGHT_BASE,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "browse-source-card-lift",
    )
    val borderAlpha by animateColorAsState(
        targetValue = if (selected) primary else primary.copy(alpha = 0.20f),
        animationSpec = tween(280, easing = FastOutSlowInEasing),
        label = "browse-source-card-border",
    )
    val labelColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else onSurfaceVariant,
        animationSpec = tween(280, easing = FastOutSlowInEasing),
        label = "browse-source-card-label",
    )

    val label = BrowseSourceUi.chipLabel(descriptor.id, descriptor.displayName)
    val tagline = sourceTagline(descriptor.id)
    val glyph = sourceGlyph(descriptor.id)
    // Soft brass-tinted gradient on the selected card. Pulls the active
    // surface out of the row without resorting to a saturated fill that
    // would clash with the rest of Library Nocturne.
    val gradient = if (selected) {
        Brush.verticalGradient(
            colors = listOf(
                MaterialTheme.colorScheme.primaryContainer,
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
            ),
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                MaterialTheme.colorScheme.surfaceContainerHigh,
                MaterialTheme.colorScheme.surfaceContainer,
            ),
        )
    }

    Surface(
        modifier = Modifier
            .width(CARD_WIDTH)
            .height(cardHeight)
            // a11y — every source card is a button with selected state so
            // TalkBack announces "Royal Road, web serials, selected".
            // Role.Button (not Role.Tab) keeps the "double-tap to activate"
            // hint intact even on the selected cell — same rationale as
            // BottomTabBar #645.
            .semantics { this.selected = selected }
            // v0.5.76 — combinedClickable adds long-press without losing
            // the click semantics or role. The onLongClickLabel is read by
            // TalkBack on long-press hint announce ("Double-tap and hold
            // for options").
            .combinedClickable(
                role = Role.Button,
                onClickLabel = label,
                onLongClickLabel = stringResource(R.string.source_options),
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        border = BorderStroke(if (selected) 2.dp else 1.dp, borderAlpha),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(14.dp))
                .background(gradient)
                .padding(horizontal = spacing.sm, vertical = spacing.xs),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                Icon(
                    imageVector = glyph,
                    contentDescription = null,
                    tint = if (selected) primary else onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = labelColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = tagline,
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor.copy(alpha = 0.78f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // v0.5.76 — favorite star overlay. Top-right corner, primary
            // color, soft container behind it so it reads on both gradient
            // states (selected primaryContainer + unselected surface). The
            // overlay is decorative — the card's selected-state semantics
            // already include the source name; TalkBack doesn't need to
            // announce "starred" separately for every favorite card. The
            // long-press sheet is where star state is read and changed.
            if (isFavorite) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = null,
                        tint = primary,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
    }
}

/**
 * v0.5.76 — long-press context sheet for a source card.
 *
 * Renders as a [ModalBottomSheet] anchored to the bottom of the screen,
 * with a compact header (icon + display name + tagline) and two action
 * rows: toggle favorite, hide-from-Browse. A footer line names the
 * Settings → Plugins re-enable path so disabling the source never
 * feels like a one-way door.
 *
 * Two rows is intentionally narrow scope. Future actions (per-source
 * sign-in, "open in WebView", reorder) belong here too, but only after
 * the data layer can express them; piling rows in pre-emptively would
 * just make the favorite/hide affordance harder to find.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceContextSheet(
    descriptor: SourcePluginDescriptor,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onHide: () -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val label = BrowseSourceUi.chipLabel(descriptor.id, descriptor.displayName)
    val tagline = sourceTagline(descriptor.id)
    val glyph = sourceGlyph(descriptor.id)

    fun dismissThen(action: () -> Unit) {
        // Animate-then-act so the user perceives the sheet acknowledging
        // their tap before the underlying state shifts. Mirrors the
        // Material 3 sample for action-then-dismiss flows.
        scope.launch {
            sheetState.hide()
        }.invokeOnCompletion {
            action()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("source-context-sheet"),
    ) {
        Column(modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.sm)) {
            // Header — icon + name + tagline. Smaller than the carousel
            // card, but visually identical so the user reads "this sheet
            // is acting on THIS source".
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Icon(
                    imageVector = glyph,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (tagline.isNotBlank()) {
                        Text(
                            text = tagline,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            HorizontalDivider()

            // Star / unstar row.
            SheetActionRow(
                icon = if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                iconTint = if (isFavorite) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                title = if (isFavorite) stringResource(R.string.source_remove_from_favorites) else stringResource(R.string.source_add_to_favorites),
                subtitle = if (isFavorite) {
                    stringResource(R.string.source_favorite_subtitle_remove)
                } else {
                    stringResource(R.string.source_favorite_subtitle_add)
                },
                titleColor = MaterialTheme.colorScheme.onSurface,
                testTag = "source-context-sheet-favorite",
                onClick = { dismissThen(onToggleFavorite) },
            )

            // Hide row.
            SheetActionRow(
                icon = Icons.Filled.VisibilityOff,
                iconTint = MaterialTheme.colorScheme.error,
                title = stringResource(R.string.source_hide_title),
                subtitle = stringResource(R.string.source_hide_subtitle),
                titleColor = MaterialTheme.colorScheme.error,
                testTag = "source-context-sheet-hide",
                onClick = { dismissThen(onHide) },
            )

            Spacer(modifier = Modifier.height(spacing.sm))
        }
    }
}

@Composable
private fun SheetActionRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    titleColor: Color,
    testTag: String,
    onClick: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
            .semantics(mergeDescendants = true) { }
            .clip(RoundedCornerShape(8.dp))
            // Clickable AFTER clip so the ripple stays inside the
            // rounded corners; mergeDescendants gives TalkBack one
            // composite announcement per row.
            .androidxClickable(
                onClickLabel = title,
                onClick = onClick,
            )
            .padding(horizontal = spacing.xs, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = titleColor,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Tiny helper so the sheet row Modifier chain reads top-to-bottom.
 *  Wraps Modifier.clickable so the sheet rows stay readable without a
 *  block-level `.clickable { ... }` mid-chain. */
private fun Modifier.androidxClickable(onClickLabel: String, onClick: () -> Unit): Modifier =
    this.clickable(
        enabled = true,
        onClickLabel = onClickLabel,
        role = Role.Button,
        onClick = onClick,
    )

/**
 * Short, plain-English tagline rendered under the source name in the
 * carousel. Kept to ~24 characters so it fits two lines comfortably at
 * labelSmall on a narrow phone. New backends can fall through to the
 * descriptor's category text or stay blank — the carousel doesn't crash
 * if a source has no row here, it just hides the tagline line.
 */
@Composable
private fun sourceTagline(id: String): String {
    val res = when (id) {
        SourceIds.ROYAL_ROAD -> R.string.source_tagline_royal_road
        SourceIds.AO3 -> R.string.source_tagline_ao3
        SourceIds.GUTENBERG -> R.string.source_tagline_gutenberg
        SourceIds.STANDARD_EBOOKS -> R.string.source_tagline_standard_ebooks
        SourceIds.WIKIPEDIA -> R.string.source_tagline_wikipedia
        SourceIds.WIKISOURCE -> R.string.source_tagline_wikisource
        SourceIds.GITHUB -> R.string.source_tagline_github
        SourceIds.RSS -> R.string.source_tagline_rss
        SourceIds.EPUB -> R.string.source_tagline_epub
        SourceIds.OUTLINE -> R.string.source_tagline_outline
        SourceIds.MEMPALACE -> R.string.source_tagline_mempalace
        SourceIds.RADIO, SourceIds.KVMR -> R.string.source_tagline_radio
        SourceIds.NOTION_TECHEMPOWER -> R.string.source_tagline_techempower
        SourceIds.NOTION_PAT -> R.string.source_tagline_notion
        SourceIds.HACKERNEWS -> R.string.source_tagline_hackernews
        SourceIds.ARXIV -> R.string.source_tagline_arxiv
        SourceIds.PLOS -> R.string.source_tagline_plos
        SourceIds.DISCORD -> R.string.source_tagline_discord
        SourceIds.MATRIX -> R.string.source_tagline_matrix
        SourceIds.TELEGRAM -> R.string.source_tagline_telegram
        SourceIds.SLACK -> R.string.source_tagline_slack
        SourceIds.PALACE -> R.string.source_tagline_palace
        SourceIds.READABILITY -> R.string.source_tagline_readability
        else -> return ""
    }
    return stringResource(res)
}

/**
 * Material icon glyph that fits the source's metaphor. Books for book-
 * shaped sources, antennas for streaming, chat bubbles for chat platforms,
 * a generic compass for "everything else". The same icon set ships with
 * material-icons-extended, already on the classpath via the BottomTabBar
 * compass import.
 */
private fun sourceGlyph(id: String): ImageVector = when (id) {
    SourceIds.ROYAL_ROAD -> Icons.Filled.AutoStories
    SourceIds.AO3 -> Icons.Filled.LibraryBooks
    SourceIds.GUTENBERG -> Icons.Filled.MenuBook
    SourceIds.STANDARD_EBOOKS -> Icons.Filled.MenuBook
    SourceIds.WIKIPEDIA -> Icons.Filled.Public
    SourceIds.WIKISOURCE -> Icons.Filled.Description
    SourceIds.GITHUB -> Icons.Filled.Workspaces
    SourceIds.RSS -> Icons.Filled.RssFeed
    SourceIds.EPUB -> Icons.Filled.FolderOpen
    SourceIds.OUTLINE -> Icons.Filled.Description
    SourceIds.MEMPALACE -> Icons.Filled.LocalLibrary
    SourceIds.RADIO, SourceIds.KVMR -> Icons.Filled.Radio
    SourceIds.NOTION_TECHEMPOWER -> Icons.Filled.Bolt
    SourceIds.NOTION_PAT -> Icons.Filled.Description
    SourceIds.HACKERNEWS -> Icons.Filled.Bolt
    SourceIds.ARXIV -> Icons.Filled.Science
    SourceIds.PLOS -> Icons.Filled.Science
    SourceIds.DISCORD -> Icons.Filled.Chat
    SourceIds.MATRIX -> Icons.Filled.Tag
    SourceIds.TELEGRAM -> Icons.Filled.Send
    SourceIds.SLACK -> Icons.Filled.Forum
    SourceIds.PALACE -> Icons.Filled.LocalLibrary
    SourceIds.READABILITY -> Icons.Filled.Link
    else -> Icons.Filled.Explore
}

/** Card width — wide enough for "Standard Ebooks" + an icon column at
 *  labelLarge, narrow enough that ~3 cards peek on a 360 dp phone so the
 *  user reads it as a horizontal carousel, not a column. */
private val CARD_WIDTH = 132.dp

/** Resting card height — fits icon + name + 2-line tagline at the chosen
 *  typography tokens with room to spare. */
private val CARD_HEIGHT_BASE = 92.dp

/** Selected card grows ~4 dp taller. Subtle enough not to break the row's
 *  baseline, obvious enough to read as "this one is active" without
 *  needing the border or color cues alone. */
private val CARD_HEIGHT_SELECTED = 96.dp

/** Carousel container height. Includes the selected lift + vertical
 *  padding so the LazyRow doesn't clip the bouncy spring overshoot. */
private val CAROUSEL_HEIGHT = 108.dp
