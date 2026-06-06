package `in`.jphe.storyvox.ui.component

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Issue #629 — tablet-friendly NavigationRail variant of [BottomTabBar].
 *
 * On tablets the bottom-nav-bar shape (4 tabs across the bottom of a
 * 1280×800 screen) wastes the wider screen real estate and forces the
 * content area into a phone-shaped column. Material 3 prescribes a
 * **NavigationRail** at the 600dp+ breakpoint — a vertical strip of
 * tabs pinned to the start (left in LTR) of the screen with the
 * content area expanding to fill the remaining width.
 *
 * This implementation mirrors [BottomTabBar]'s visual vocabulary so a
 * device rotating across the 600dp threshold doesn't look like it's
 * switching apps:
 *  - same [HomeTab] enum drives both surfaces
 *  - same brass primaryContainer pill indicator slides between tabs
 *  - same icon + label per cell
 *  - same `Role.Tab` + `selected` semantics for TalkBack
 *  - same FastOutSlowInEasing 280 ms slide for the indicator
 *
 * The rail width is 80 dp (Material 3 NavigationRail minimum) which
 * matches the [BAR_HEIGHT] of the bottom bar — so the surface area
 * dedicated to navigation stays constant across the breakpoint, and
 * only the orientation flips.
 *
 * The pill indicator is drawn with `drawBehind` on the Column (same
 * single-child-of-BoxWithConstraints pattern that makes hit-testing
 * unambiguous in the bottom bar) — see the comment block on
 * [BottomTabBar] for the history of why we don't use an offset Box.
 */
@Composable
fun SideNavRail(
    selected: HomeTab,
    onSelect: (HomeTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = HomeTab.entries
    val selectedIndex = tabs.indexOf(selected).coerceAtLeast(0)
    val indicatorColor = MaterialTheme.colorScheme.primaryContainer

    Surface(
        modifier = modifier
            .fillMaxHeight()
            .width(RAIL_WIDTH),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                // Lift cells above the status bar / display cutout so
                // the topmost cell doesn't render under the camera
                // notch on landscape foldables. `systemBars` covers
                // both status and navigation bars on a side rail.
                .windowInsetsPadding(WindowInsets.systemBars)
                .width(RAIL_WIDTH),
        ) {
            val density = LocalDensity.current
            val cellWidthPx = with(density) { RAIL_WIDTH.toPx() }
            val cellHeightPx = with(density) { CELL_HEIGHT.toPx() }
            val pillWidthPx = with(density) { INDICATOR_WIDTH.toPx() }
            val pillHeightPx = with(density) { INDICATOR_HEIGHT.toPx() }
            // Center the indicator within its cell horizontally + at
            // the top portion vertically (icon sits ~14 dp above the
            // label, indicator hugs the icon).
            val pillLeftPx = (cellWidthPx - pillWidthPx) / 2f
            val pillTopOffsetPx = with(density) { INDICATOR_TOP_OFFSET.toPx() }
            // Each cell is CELL_HEIGHT dp tall; the pill's Y is the
            // cell origin plus the in-cell top offset.
            val targetY = cellHeightPx * selectedIndex + pillTopOffsetPx
            val pillY by animateFloatAsState(
                targetValue = targetY,
                animationSpec = tween(
                    durationMillis = SLIDE_DURATION_MS,
                    easing = FastOutSlowInEasing,
                ),
                label = "rail-indicator-slide",
            )

            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(RAIL_WIDTH)
                    .drawBehind {
                        drawRoundRect(
                            color = indicatorColor,
                            topLeft = Offset(pillLeftPx, pillY),
                            size = Size(pillWidthPx, pillHeightPx),
                            cornerRadius = CornerRadius(pillHeightPx / 2f),
                        )
                    },
            ) {
                tabs.forEach { tab ->
                    RailCell(
                        tab = tab,
                        isSelected = tab == selected,
                        onClick = { onSelect(tab) },
                        modifier = Modifier
                            .width(RAIL_WIDTH)
                            .height(CELL_HEIGHT),
                    )
                }
            }
        }
    }
}

@Composable
private fun RailCell(
    tab: HomeTab,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Same interaction-source pattern as BottomTabBar — the player
    // surface drives heavy recompositions through both surfaces and a
    // recreated MutableInteractionSource can lose press-up events.
    val interactionSource = remember { MutableInteractionSource() }
    val indication = LocalIndication.current
    Column(
        modifier = modifier
            // Same stable `nav-<tab>` selector as the bottom bar so a UI
            // test addresses the same destination regardless of which
            // surface (rail vs. bar) the breakpoint chose. Non-functional.
            .testTag(TestTags.navTab(tab))
            .semantics { selected = isSelected }
            .clickable(
                interactionSource = interactionSource,
                indication = indication,
                role = Role.Tab,
                onClickLabel = tab.label,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = INDICATOR_WIDTH, height = INDICATOR_HEIGHT),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isSelected) tab.filled else tab.outlined,
                contentDescription = tab.label,
                tint = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = tab.label,
            style = MaterialTheme.typography.labelMedium,
            color = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** Material 3 NavigationRail minimum width. Matches the bottom bar's
 *  height so the surface area dedicated to navigation is constant
 *  across the 600 dp breakpoint. */
private val RAIL_WIDTH = 80.dp

/** Height of each rail cell. 72 dp accommodates the 32 dp indicator
 *  pill + 4 dp spacer + the labelMedium text + comfortable padding. */
private val CELL_HEIGHT = 72.dp

/** Indicator pill geometry — kept identical to the bottom bar so the
 *  visual vocabulary stays consistent across the breakpoint flip. */
private val INDICATOR_WIDTH = 64.dp
private val INDICATOR_HEIGHT = 32.dp

/** Vertical offset of the indicator from the top of each cell. Tuned
 *  to center the pill over the icon's 32 dp hit-target box. */
private val INDICATOR_TOP_OFFSET = 12.dp

private const val SLIDE_DURATION_MS = 280
