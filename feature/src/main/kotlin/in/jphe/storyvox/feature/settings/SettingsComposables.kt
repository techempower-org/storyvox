package `in`.jphe.storyvox.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.ui.a11y.LocalAccessibleTouchTargets
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.theme.LibraryNocturneTheme
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Settings UI building blocks (issue #104, Indigo's spec).
 *
 * Six row composables and one group container. Together they form the
 * vocabulary for the six-section grouped-card Settings layout. Each row
 * targets the brass aesthetic established by `BrassButton` and the
 * `LibraryScreen.ResumeCard` `surfaceContainerHigh` + `shapes.large` idiom.
 *
 * **Composition rule:** rows are designed to live inside a [SettingsGroupCard].
 * The card draws the warm-dark surface and the rounded outer shape; rows draw
 * just their inner padding. Inter-row separation is the 1dp peek of the card's
 * surface through the `Arrangement.spacedBy(1.dp)` gap — no explicit Divider.
 *
 * Rows render correctly outside a card too (they have their own min-height
 * and padding); they just lose the warm container.
 */

// region Group container

/**
 * Wraps a Settings section's rows. Card with `surfaceContainerHigh` and
 * `shapes.large`, mirroring `LibraryScreen.ResumeCard`. Internal column uses
 * `Arrangement.spacedBy(1.dp)` so the card's surface peeks through as a thin
 * brass-tinted ghost rule between rows (Indigo's spec, option B).
 */
@Composable
fun SettingsGroupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        // 1dp peek lets the card's surface read as a hairline rule between
        // rows. No explicit Divider — keeps callers simple, conditionally
        // hidden rows just disappear cleanly.
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            content()
        }
    }
}

// endregion

// region Section header

/**
 * Brass-colored section title rendered above each [SettingsGroupCard].
 * `labelLarge` in `colorScheme.primary` per Indigo's typography rhythm.
 * Header sits *outside* the card so it reads as a chapter heading, not a row.
 *
 * Optional [icon] is rendered as a small brass-tinted glyph immediately to the
 * left of the label — adds the visual rhythm a wall-of-cards layout needs to
 * stay scannable (Voice & Playback / Reading / Performance / AI / etc.).
 */
@Composable
fun SettingsSectionHeader(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val spacing = LocalSpacing.current
    Row(
        modifier = modifier.padding(start = spacing.xs, top = spacing.xs, bottom = spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

// endregion

// region SettingsRow — base

/**
 * Generic two-line row. Title (`bodyLarge`, `onSurface`) + optional subtitle
 * (`bodySmall`, `onSurfaceVariant`). Optional leading and trailing slots.
 * `onClick` makes the whole row tappable with a ripple. `64.dp` minHeight
 * matches the M3 list-item one-line touch target.
 */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val spacing = LocalSpacing.current
    // #690 Phase 2 — widen the 64dp min-height to 80dp under
    // [LocalAccessibleTouchTargets] (1.25×, matching the
    // [accessibleSize] 48→64dp ratio used elsewhere). The Settings
    // surface is the most common tap target in the app outside
    // playback, and Switch Access / motor-impaired users routinely
    // re-traverse it, so the row growth is load-bearing.
    val enlarged = LocalAccessibleTouchTargets.current
    val minRowHeight = if (enlarged) 80.dp else 64.dp
    val rowModifier = modifier
        .fillMaxWidth()
        .heightIn(min = minRowHeight)
        .let { m -> if (onClick != null) m.clickable(role = Role.Button, onClick = onClick) else m }
        .padding(horizontal = spacing.md, vertical = spacing.sm)

    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        if (leading != null) {
            leading()
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) {
            trailing()
        }
    }
}

// endregion

/**
 * Structural canary for issue #478 — [SettingsSwitchRow] must apply
 * `Modifier.toggleable(role = Role.Switch)` on the row (not on a
 * `Modifier.clickable(role = Role.Button)` inherited from [SettingsRow]),
 * so TalkBack announces the entire row as a single `Role.Switch` node
 * with the title merged in.
 *
 * Flipped to `false` only after a future refactor proves on a real
 * device with TalkBack that a different shape carries the same merged
 * announcement.
 *
 * Pinned by `SettingsSwitchRowToggleableTest`.
 */
internal const val settingsSwitchRowUsesToggleable: Boolean = true

// region SettingsSwitchRow

/**
 * Row whose trailing slot is a brass-tinted [Switch]. Tapping anywhere on
 * the row toggles the switch (full-row tap target). `checkedThumbColor` =
 * brass primary, `checkedTrackColor` = primaryContainer, giving the switch
 * the brass personality the M3 default lacks.
 *
 * **A11y (closes #478):** the entire row uses `Modifier.toggleable(role =
 * Role.Switch)` so TalkBack announces a single merged node — *"\<title\>,
 * switch, on/off"* — instead of two siblings (a row labelled "switch" and
 * an inner Switch with no label). The inner `Switch` passes
 * `onCheckedChange = null` to opt out of independent click handling; the
 * toggleable on the row owns the value change. This widens the tap target
 * from the ~52×47dp Switch hit box to the full row (≥64dp tall × screen
 * width) at the same time.
 *
 * The row deliberately does **not** delegate through [SettingsRow]
 * (which would apply `Role.Button` to the click) — `toggleable` needs to
 * be the outermost interaction modifier so its `Role.Switch` wins.
 */
@Composable
fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    val spacing = LocalSpacing.current
    val brassColors = SwitchDefaults.colors(
        checkedThumbColor = MaterialTheme.colorScheme.primary,
        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
        checkedBorderColor = MaterialTheme.colorScheme.primary,
        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
    )
    // #690 Phase 2 — same widened min-height as [SettingsRow]; switch
    // rows are the most numerous tap target in the accessibility
    // subscreen itself, so this matters even more here.
    val enlarged = LocalAccessibleTouchTargets.current
    val minRowHeight = if (enlarged) 80.dp else 64.dp
    val rowModifier = modifier
        .fillMaxWidth()
        .heightIn(min = minRowHeight)
        .toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        )
        .padding(horizontal = spacing.md, vertical = spacing.sm)

    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // `onCheckedChange = null` — the toggleable on the row owns the
        // value change; the Switch is now a visual indicator that
        // doesn't claim its own clickable semantics. TalkBack merges
        // this whole subtree under the row's Role.Switch.
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = brassColors,
        )
    }
}

// endregion

// region SettingsSliderBlock

/**
 * Block for slider-shaped settings. Top row is a title + right-aligned brass-
 * colored value label. Optional one-line subtitle goes between the header and
 * the slider. The slider itself is caller-supplied so callers can pass
 * custom track colors (e.g., BufferSlider's amber/red past-tick state) or
 * stepped configurations. Optional caption renders below the slider for
 * explainer-heavy controls (BufferSlider's three-paragraph copy).
 */
@Composable
fun SettingsSliderBlock(
    title: String,
    valueLabel: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    caption: (@Composable ColumnScope.() -> Unit)? = null,
    slider: @Composable () -> Unit,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.End,
            )
        }
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        slider()
        if (caption != null) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
                caption()
            }
        }
    }
}

// endregion

// region SettingsSegmentedBlock

/**
 * Three-stop selector built on Material 3's `SingleChoiceSegmentedButtonRow`
 * with the same brass-tinted active treatment as [BrowseSourceCarousel].
 *
 * Earlier revisions used a row of [BrassButton]s where the selected stop
 * was `Primary` (filled brass) and the unselected were `Secondary`
 * (outlined brass). On the warm-dark substrate that read inverted: the
 * filled brass appeared muted/olive while the outlined brass strokes
 * popped, so the selected pill looked *less* prominent than the
 * unselected ones (#274). The segmented-row variant uses
 * `primaryContainer` / `onPrimaryContainer` for the active fill, which
 * sits brighter than the surface and surrounds the label with a clear
 * brass "well" — the selected stop reads as selected at a glance.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSegmentedBlock(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = index == selectedIndex,
                    onClick = { onSelected(index) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = options.size,
                    ),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                ) {
                    Text(label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

// endregion

// region SettingsLinkRow

/**
 * Navigation row. Same shape as [SettingsRow] with a chevron-right glyph in
 * the trailing slot. Used for links to subscreens (Voice library today;
 * Sources / OSS licenses in future).
 */
@Composable
fun SettingsLinkRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    SettingsRow(
        title = title,
        subtitle = subtitle,
        modifier = modifier,
        onClick = onClick,
        trailing = {
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

// endregion

// region AdvancedExpander

/**
 * Collapsible "Advanced" row. Closed state shows `▸ Advanced (N)` + an
 * ellipsized preview of hidden row titles (Android Settings guidance:
 * "subtext reveals hidden setting titles in a single line with ellipsis
 * truncation"). Open state flips the chevron and reveals [content].
 *
 * Per Android guidance, "Advanced" is only used when there are at least 3
 * items to hide. This composable enforces that — if [titlesPreview] has
 * fewer than 3 entries, it renders nothing (callers are expected to inline
 * the row instead).
 *
 * Reserved for future use (BufferSlider experimental zone, decoder choice,
 * PCM cache eviction). Not wired into v1 of the redesign per spec; kept
 * here so contributors landing those features can drop straight in.
 */
@Composable
fun AdvancedExpander(
    titlesPreview: List<String>,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Advanced",
    content: @Composable ColumnScope.() -> Unit,
) {
    if (titlesPreview.size < 3) return

    val previewLine = titlesPreview.joinToString(separator = " · ")
    Column(modifier = modifier.fillMaxWidth()) {
        SettingsRow(
            title = "$title (${titlesPreview.size})",
            subtitle = if (expanded) null else previewLine,
            onClick = onToggle,
            trailing = {
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                content()
            }
        }
    }
}

// endregion

// region Previews
//
// One pair of (dark, light) for each composable. Visual verification only —
// no test harness, per spec ("preview composables cover visual verification
// interactively via Android Studio").

@Preview(name = "GroupCard — three rows (dark)", widthDp = 360)
@Composable
private fun PreviewSettingsGroupCardDark() = LibraryNocturneTheme(darkTheme = true) {
    Box(modifier = Modifier.padding(16.dp)) {
        SettingsGroupCard {
            SettingsRow(title = "First row", subtitle = "With a subtitle")
            SettingsRow(title = "Second row", subtitle = "With a subtitle")
            SettingsRow(title = "Third row")
        }
    }
}

@Preview(name = "GroupCard — three rows (light)", widthDp = 360)
@Composable
private fun PreviewSettingsGroupCardLight() = LibraryNocturneTheme(darkTheme = false) {
    Box(modifier = Modifier.padding(16.dp)) {
        SettingsGroupCard {
            SettingsRow(title = "First row", subtitle = "With a subtitle")
            SettingsRow(title = "Second row", subtitle = "With a subtitle")
            SettingsRow(title = "Third row")
        }
    }
}

@Preview(name = "SwitchRow — checked + unchecked (dark)", widthDp = 360)
@Composable
private fun PreviewSettingsSwitchRowDark() = LibraryNocturneTheme(darkTheme = true) {
    Box(modifier = Modifier.padding(16.dp)) {
        SettingsGroupCard {
            SettingsSwitchRow(
                title = "Wi-Fi only",
                subtitle = "Don't poll on cellular.",
                checked = true,
                onCheckedChange = {},
            )
            SettingsSwitchRow(
                title = "Catch-up Pause",
                subtitle = "Drain through underruns; no buffering spinner.",
                checked = false,
                onCheckedChange = {},
            )
        }
    }
}

@Preview(name = "SliderBlock — speed + caption (dark)", widthDp = 360)
@Composable
private fun PreviewSettingsSliderBlockDark() = LibraryNocturneTheme(darkTheme = true) {
    Box(modifier = Modifier.padding(16.dp)) {
        SettingsGroupCard {
            var v by remember { mutableStateOf(1.20f) }
            SettingsSliderBlock(
                title = "Speed",
                valueLabel = "${"%.2f".format(v)}×",
                slider = {
                    Slider(value = v, onValueChange = { v = it }, valueRange = 0.5f..3.0f)
                },
            )
            var b by remember { mutableStateOf(8f) }
            SettingsSliderBlock(
                title = "Buffer Headroom",
                valueLabel = "${b.toInt()} chunks",
                slider = {
                    Slider(value = b, onValueChange = { b = it }, valueRange = 2f..1500f)
                },
                caption = {
                    Text(
                        text = "Pre-synthesizes audio ahead. Useful on slow voices.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
    }
}

@Preview(name = "SegmentedBlock — three-stop (dark)", widthDp = 360)
@Composable
private fun PreviewSettingsSegmentedBlockDark() = LibraryNocturneTheme(darkTheme = true) {
    Box(modifier = Modifier.padding(16.dp)) {
        SettingsGroupCard {
            var idx by remember { mutableStateOf(1) }
            SettingsSegmentedBlock(
                title = "Theme",
                subtitle = "Match the device's day/night.",
                options = listOf("System", "Dark", "Light"),
                selectedIndex = idx,
                onSelected = { idx = it },
            )
        }
    }
}

@Preview(name = "LinkRow — chevron (dark)", widthDp = 360)
@Composable
private fun PreviewSettingsLinkRowDark() = LibraryNocturneTheme(darkTheme = true) {
    Box(modifier = Modifier.padding(16.dp)) {
        SettingsGroupCard {
            SettingsLinkRow(
                title = "Voice library",
                subtitle = "Andrew · en-US · 12 installed",
                onClick = {},
            )
        }
    }
}

@Preview(name = "SectionHeader + GroupCard composed (dark)", widthDp = 360)
@Composable
private fun PreviewSettingsSectionDark() = LibraryNocturneTheme(darkTheme = true) {
    Column(modifier = Modifier.padding(16.dp)) {
        SettingsSectionHeader("Voice & Playback")
        Spacer(Modifier.width(8.dp))
        SettingsGroupCard {
            SettingsLinkRow(
                title = "Voice library",
                subtitle = "Andrew · en-US",
                onClick = {},
            )
            var v by remember { mutableStateOf(1.0f) }
            SettingsSliderBlock(
                title = "Speed",
                valueLabel = "${"%.2f".format(v)}×",
                slider = { Slider(value = v, onValueChange = { v = it }, valueRange = 0.5f..3.0f) },
            )
        }
    }
}

@Preview(name = "AdvancedExpander — collapsed + expanded (dark)", widthDp = 360)
@Composable
private fun PreviewAdvancedExpanderDark() = LibraryNocturneTheme(darkTheme = true) {
    Box(modifier = Modifier.padding(16.dp)) {
        SettingsGroupCard {
            var open by remember { mutableStateOf(false) }
            AdvancedExpander(
                titlesPreview = listOf("Buffer probe", "Decoder", "Cache eviction"),
                expanded = open,
                onToggle = { open = !open },
            ) {
                SettingsRow(title = "Buffer probe", subtitle = "Experimental")
                SettingsRow(title = "Decoder", subtitle = "Experimental")
                SettingsRow(title = "Cache eviction", subtitle = "Experimental")
            }
        }
    }
}

// endregion
