package `in`.jphe.storyvox.ui.component

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Top-level bottom-nav destinations.
 *
 * **Restructure (v0.5.40)** — JP directive: "put settings in the main nav bar,
 * and put follows and browse into the library tab." The bar collapses to two
 * destinations: [Library] (umbrella for the user's books + Browse + Follows +
 * Inbox + History as sub-tabs) and [Settings] (landing on the v0.5.38
 * SettingsHubScreen).
 *
 *  - Playing / Follows / Browse / Voices were previously primary destinations.
 *    Their routes still exist (so deep-links resolve and the in-Library
 *    sub-tabs render their content), but they no longer carry bottom-bar
 *    icons. Voices is reached via Settings → Voices; Browse / Follows live
 *    under Library; Playing returns when a player surface (#267) is needed.
 */
/**
 * Bottom-nav destinations.
 *
 * **v0.5.40 restructure** collapsed to `{Library, Settings}` with Browse +
 * Follows + Inbox + History folded into Library sub-tabs.
 *
 * **v0.5.48 partial revert** (JP feedback 2026-05-15) — Playing + Voices
 * restored as primary nav destinations alongside Library + Settings.
 *
 * **v0.5.72 — Browse promoted to first-class** (issues #712/#713 follow-up,
 * 2026-05-22). Browse was a Library sub-tab in the v0.5.40 restructure but
 * the discovery surface (multi-backend source switching + content grid)
 * carried enough first-class behavior — its own ViewModel, sub-tabs of its
 * own, and the standalone BROWSE deep-link route — that hiding it behind a
 * Library sub-tab cost a tap on the most common "what should I read next"
 * flow. The compass icon reads as "go look around" without overloading the
 * other dock metaphors (Playing = transport, Library = your shelves,
 * Voices = TTS, Settings = gear).
 *
 * Five tabs now in the dock: `{Playing, Library, Browse, Voices, Settings}`.
 */
// Order matters — entries' ordinal positions the sliding indicator
// pill left-to-right in the bar. v0.5.72 final order: Playing leads
// (most-touched during a listening session); Library second (the
// cold-launch landing — NavHost startDestination is independent of
// dock order but adjacency matters for the "I just opened the app"
// glance); Browse third (discovery sits between "your stuff" and
// "everything else about playback"); Voices fourth; Settings always
// last. Library + Browse adjacent is intentional — a user finishing
// a book in Library can swipe one tab to discover the next.
@Immutable
enum class HomeTab(val label: String, val filled: ImageVector, val outlined: ImageVector) {
    Playing("Playing", Icons.Filled.PlayArrow, Icons.Outlined.PlayArrow),
    Library("Library", Icons.Filled.AutoStories, Icons.Outlined.AutoStories),
    Browse("Browse", Icons.Filled.Explore, Icons.Outlined.Explore),
    Voices("Voices", Icons.Filled.RecordVoiceOver, Icons.Outlined.RecordVoiceOver),
    Settings("Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
}

/**
 * Issue #280 — custom bottom navigation with a single sliding indicator
 * pill that animates between tabs rather than fading per-item.
 *
 * Material 3's `NavigationBar` + `NavigationBarItem` defaults render an
 * indicator pill **per item**, with the selected item's pill fading in
 * and the unselected items' pills fading out. The visual effect reads
 * as a "pop"; this bar paints a single pill that slides between tabs.
 *
 * Issue #XXX (2026-05-12) — the first cut put the indicator pill in its
 * own `Box(.offset(x).size(...).background(...))` sibling to the tab
 * Row inside `BoxWithConstraints`. Two layout children + a mid-animation
 * `.offset` made hit-testing flaky under playback's high recomposition
 * rate: taps on tabs would land on the press-down but lose the press-up,
 * particularly while audio was playing. The pill is now a `drawBehind`
 * on the Row itself, so `BoxWithConstraints` has a single layout child
 * and hit-testing is unambiguous. Each TabCell also pins an explicit
 * `MutableInteractionSource` so the clickable's state survives the
 * playback-driven recompositions that previously could interrupt it.
 *
 * `BoxWithConstraints` is required because per-cell width is derived
 * from the parent's measured pixel width — we can't hard-code it
 * (foldable hinge, tablet portrait/landscape, large fonts all change
 * the available width). The constraints subcompose costs one extra
 * measurement pass but is bounded by the bar's 80dp height.
 */
@Composable
fun BottomTabBar(
    selected: HomeTab,
    onSelect: (HomeTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = HomeTab.entries
    val selectedIndex = tabs.indexOf(selected).coerceAtLeast(0)
    val indicatorColor = MaterialTheme.colorScheme.primaryContainer

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                // Pad above the visible nav bar (3-button nav) or
                // gesture pill (~20px). Earlier revisions also called
                // `Modifier.systemGestureExclusion()` on each tab cell
                // (below) to claim the deeper ~64px mandatorySystemGestures
                // zone for taps, but that turned out to block the OS
                // swipe-up-home and long-press-up-recents gestures
                // entirely — they hit our exclusion rect and never
                // reached the system. JP reported it on v0.5.41 tablet.
                // The exclusion is removed; `windowInsetsPadding`
                // already lifts cells above the visible gesture pill,
                // and if specific cells fall into the gesture-pool we
                // narrow the exclusion to just the top half of each
                // cell (so the home gesture lives in the bottom 24dp).
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(BAR_HEIGHT),
        ) {
            val density = LocalDensity.current
            val cellWidthPx = constraints.maxWidth.toFloat() / tabs.size
            val pillWidthPx = with(density) { INDICATOR_WIDTH.toPx() }
            val pillHeightPx = with(density) { INDICATOR_HEIGHT.toPx() }
            val pillTopPx = with(density) { INDICATOR_TOP_OFFSET.toPx() }
            // Center the indicator pill horizontally within the cell —
            // the pill is narrower than a cell so the math is
            // `cellLeft + (cellWidth - pillWidth) / 2`.
            val targetX = cellWidthPx * selectedIndex + (cellWidthPx - pillWidthPx) / 2f
            val pillX by animateFloatAsState(
                targetValue = targetX,
                animationSpec = tween(
                    durationMillis = SLIDE_DURATION_MS,
                    easing = FastOutSlowInEasing,
                ),
                label = "nav-indicator-slide",
            )

            // Indicator drawn behind the Row. Pill is paint, not a
            // layout node — so the BoxWithConstraints has exactly one
            // child (the Row), and hit-testing is never ambiguous.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .drawBehind {
                        drawRoundRect(
                            color = indicatorColor,
                            topLeft = Offset(pillX, pillTopPx),
                            size = Size(pillWidthPx, pillHeightPx),
                            cornerRadius = CornerRadius(pillHeightPx / 2f),
                        )
                    },
            ) {
                tabs.forEach { tab ->
                    TabCell(
                        tab = tab,
                        isSelected = tab == selected,
                        onClick = { onSelect(tab) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                            // No `.systemGestureExclusion()` here — see
                            // the comment on the BoxWithConstraints
                            // padding above. Earlier revisions claimed
                            // each cell from the OS gesture pool to
                            // make taps reliable, but that blocked the
                            // swipe-up-home and long-press-up-recents
                            // gestures entirely (they hit our exclusion
                            // rect and never reached the system). If
                            // tap reliability degrades on gesture nav,
                            // re-add a narrowed exclusion bound to the
                            // top half of each cell (so the bottom 24dp
                            // stays available for OS gestures).
                    )
                }
            }
        }
    }
}

@Composable
private fun TabCell(
    tab: HomeTab,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Pin the interaction source per cell. Without an explicit
    // `remember`, `Modifier.clickable(onClick = lambda)` constructs an
    // anonymous source each composition; under the playback flow's
    // recomposition pressure the source can lose the press-up half of a
    // tap. Holding the source across recompositions keeps press state
    // continuous so the click always fires.
    val interactionSource = remember { MutableInteractionSource() }
    val indication = LocalIndication.current
    Column(
        // a11y (#485, #645): expose this cell to TalkBack with a
        // `selected` flag for state announcement, but use `Role.Button`
        // (NOT `Role.Tab`) on the clickable. The combination of
        // `Role.Tab` + `selected=true` triggers a TalkBack behavior
        // where the cell is read as "already on" and loses its
        // "double-tap to activate" hint — observed on Reader / Voices /
        // Settings where Library is marked `selected=true` (the route-
        // collapse mapping in StoryvoxNavHost lights Library for any
        // route under its umbrella). Switching to `Role.Button` keeps
        // the selected-state announcement ("Library, selected, button")
        // AND restores the activation hint so a TalkBack user can
        // always tap to return to the Library home, even from within a
        // deep-link drill-down. The indicator pill remains a purely
        // visual cue.
        //
        // `.semantics { selected = isSelected }` must come BEFORE the
        // clickable so the role on clickable doesn't override it.
        modifier = modifier
            .semantics { selected = isSelected }
            .clickable(
                interactionSource = interactionSource,
                indication = indication,
                role = Role.Button,
                onClickLabel = tab.label,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Inner column to wrap icon + label tightly; the outer Column
        // centers this group inside the cell.
        Box(
            modifier = Modifier
                .size(width = ICON_TARGET_WIDTH, height = INDICATOR_HEIGHT),
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

private val BAR_HEIGHT = 80.dp

/** M3-ish indicator pill: 64dp wide × 32dp tall, rounded ends. Wide
 *  enough to hug the 24dp Material icon with ~20dp of slack on each
 *  side; matches the visual weight of the existing per-item indicator. */
private val INDICATOR_WIDTH = 64.dp
private val INDICATOR_HEIGHT = 32.dp

/** Vertical offset of the indicator from the top of the bar. Leaves
 *  ~12dp above the icon and ~36dp below for label + bottom padding. */
private val INDICATOR_TOP_OFFSET = 12.dp

/** Same 24dp Material icon size; the surrounding Box gives the icon
 *  a hit target matching the indicator pill so taps near the icon
 *  edges still trigger the click. */
private val ICON_TARGET_WIDTH = 64.dp

/** Slide duration. 280ms with FastOutSlowInEasing matches Material's
 *  motion-medium-1 token — slow enough to read as "I'm navigating",
 *  fast enough to feel responsive. */
private const val SLIDE_DURATION_MS = 280

/**
 * Structural canary for issues #485 + #645 — TabCell must expose a
 * `selected` semantics property so TalkBack announces the currently-
 * active tab. Issue #645 (v1.0) changed the clickable's role from
 * `Role.Tab` to `Role.Button` because `Role.Tab` + `selected=true`
 * caused TalkBack to suppress the "double-tap to activate" hint on
 * the selected cell — fatal for the Reader / Voices / Settings
 * surfaces where Library lights as selected (via the route-collapse
 * mapping in StoryvoxNavHost) and the user needs to tap it to return
 * home. With `Role.Button` + `selected`, TalkBack announces "Library,
 * selected, button" AND still offers the activation hint.
 *
 * Pinned by `BottomTabBarSemanticsTest`.
 */
internal const val bottomTabBarUsesRoleButtonPlusSelected: Boolean = true
