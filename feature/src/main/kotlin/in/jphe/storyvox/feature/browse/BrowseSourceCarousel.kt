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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.plugin.SourcePluginDescriptor
import `in`.jphe.storyvox.ui.theme.LocalSpacing

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
 */
@Composable
internal fun BrowseSourceCarousel(
    selectedId: String,
    onSelect: (String) -> Unit,
    visibleSources: List<SourcePluginDescriptor>,
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
                onClick = { onSelect(descriptor.id) },
            )
        }
    }
}

@Composable
private fun BrowseSourceCard(
    descriptor: SourcePluginDescriptor,
    isSelected: Boolean,
    onClick: () -> Unit,
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
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
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
        }
    }
}

/**
 * Short, plain-English tagline rendered under the source name in the
 * carousel. Kept to ~24 characters so it fits two lines comfortably at
 * labelSmall on a narrow phone. New backends can fall through to the
 * descriptor's category text or stay blank — the carousel doesn't crash
 * if a source has no row here, it just hides the tagline line.
 */
private fun sourceTagline(id: String): String = when (id) {
    SourceIds.ROYAL_ROAD -> "Web serials"
    SourceIds.AO3 -> "Fanfiction archive"
    SourceIds.GUTENBERG -> "Classic books"
    SourceIds.STANDARD_EBOOKS -> "Polished classics"
    SourceIds.WIKIPEDIA -> "Encyclopedia"
    SourceIds.WIKISOURCE -> "Historic texts"
    SourceIds.GITHUB -> "Repo READMEs"
    SourceIds.RSS -> "Your feeds"
    SourceIds.EPUB -> "Your EPUBs"
    SourceIds.OUTLINE -> "Your wiki"
    SourceIds.MEMPALACE -> "Your palace"
    SourceIds.RADIO, SourceIds.KVMR -> "Live radio"
    SourceIds.NOTION -> "Your Notion"
    SourceIds.HACKERNEWS -> "Tech news"
    SourceIds.ARXIV -> "Research papers"
    SourceIds.PLOS -> "Open science"
    SourceIds.DISCORD -> "Server channels"
    SourceIds.MATRIX -> "Matrix rooms"
    SourceIds.TELEGRAM -> "Channel posts"
    SourceIds.SLACK -> "Workspace threads"
    SourceIds.PALACE -> "Library borrows"
    SourceIds.READABILITY -> "Paste any link"
    else -> ""
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
    SourceIds.NOTION -> Icons.Filled.Description
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
