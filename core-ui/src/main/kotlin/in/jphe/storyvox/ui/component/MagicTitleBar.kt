package `in`.jphe.storyvox.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.ui.theme.LibraryNocturneTheme
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Issue #830 — the shared title bar for every primary-nav surface
 * (Library / Browse / Follows / Voice Library / Settings Hub).
 *
 * Before this composable existed each tab built its own bar inline,
 * which drifted: three used [CenterAlignedTopAppBar] with `titleMedium`,
 * two used a left-aligned [androidx.compose.material3.TopAppBar] with
 * `titleLarge`, and Follows overrode the container color to `surface`.
 * The visible result was five subtly different headers across one app.
 *
 * [MagicTitleBar] picks the most polished pattern (center-aligned,
 * `titleMedium`, default container) and adds a small brass-tinted
 * sparkle glyph next to the title. The glyph is the same
 * [Icons.Outlined.AutoAwesome] used by the Settings hub heading, so it
 * reads as one design family. Slots for [navigationIcon] and [actions]
 * mirror the underlying [CenterAlignedTopAppBar] API.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MagicTitleBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable () -> Unit = {},
) {
    val spacing = LocalSpacing.current
    CenterAlignedTopAppBar(
        modifier = modifier,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        },
        navigationIcon = navigationIcon,
        actions = { actions() },
    )
}

@Preview(name = "MagicTitleBar — dark", widthDp = 360)
@Composable
private fun PreviewMagicTitleBarDark() = LibraryNocturneTheme(darkTheme = true) {
    MagicTitleBar(title = "Library")
}

@Preview(name = "MagicTitleBar — light", widthDp = 360)
@Composable
private fun PreviewMagicTitleBarLight() = LibraryNocturneTheme(darkTheme = false) {
    MagicTitleBar(title = "Voice library")
}
