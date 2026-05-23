package `in`.jphe.storyvox.feature.follows

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.feature.api.UiFollow
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.component.coverSourceFamilyFor
import `in`.jphe.storyvox.ui.component.FictionCoverThumb
import `in`.jphe.storyvox.ui.component.fictionMonogram
import `in`.jphe.storyvox.ui.component.MagicSkeletonTile
import `in`.jphe.storyvox.ui.component.SkeletonBlock
import `in`.jphe.storyvox.ui.layout.isAtLeastTablet
import `in`.jphe.storyvox.ui.theme.LocalSpacing
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.items as gridItems

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun FollowsScreen(
    onOpenFiction: (String) -> Unit,
    onOpenSignIn: () -> Unit,
    onOpenSettings: () -> Unit = {},
    /**
     * Restructure (v0.5.40) — when true, FollowsScreen renders without
     * its own TopAppBar. The Library tab's TopAppBar serves as the
     * parent header. The "Mark all caught up" action still surfaces,
     * just as an inline header row above the list instead of in a
     * TopAppBar slot. Standalone (`embedded = false`) preserves the
     * legacy full-screen rendering for the deep-linked FOLLOWS route.
     */
    embedded: Boolean = false,
    viewModel: FollowsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current
    val multiColumn = isAtLeastTablet()

    // #328 — single deduped list shared by both the grid (multiColumn)
    // and the linear LazyColumn branches below. Computed once per
    // state.follows change via remember instead of per-recomposition.
    // Logs when Royal Road sign-in cache hydration accidentally
    // double-emits so the underlying race can be traced.
    val dedupedFollows = androidx.compose.runtime.remember(state.follows) {
        val out = state.follows.distinctBy { it.fiction.id }
        val dropped = state.follows.size - out.size
        if (dropped > 0) {
            android.util.Log.w(
                "storyvox",
                "FollowsScreen: dropped $dropped duplicate follow id(s) (size ${state.follows.size} -> ${out.size}) — see #328",
            )
        }
        out
    }

    androidx.compose.runtime.LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            if (event is FollowsUiEvent.OpenFiction) onOpenFiction(event.fictionId)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Restructure (v0.5.40) — when embedded under Library, skip
            // the standalone TopAppBar entirely (Library's bar serves
            // as the parent header). The "Mark all caught up" action
            // surfaces inline below as a TextButton in an aligned-end
            // Row so it remains reachable.
            if (!embedded) {
                TopAppBar(
                    title = { Text(stringResource(R.string.follows_title), style = MaterialTheme.typography.titleLarge) },
                    actions = {
                        if (state.isSignedIn && state.follows.isNotEmpty()) {
                            BrassButton(
                                label = "Mark all caught up",
                                onClick = viewModel::markAllCaughtUp,
                                variant = BrassButtonVariant.Text,
                            )
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
            } else if (state.isSignedIn && state.follows.isNotEmpty()) {
                // Embedded fallback for the "Mark all caught up"
                // action — same affordance, just relocated below the
                // parent TopAppBar.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.md, vertical = spacing.xs),
                    horizontalArrangement = Arrangement.End,
                ) {
                    BrassButton(
                        label = "Mark all caught up",
                        onClick = viewModel::markAllCaughtUp,
                        variant = BrassButtonVariant.Text,
                    )
                }
            }
            when {
                // Brass-sigil skeletons while the network refresh is in flight
                // and we have no cached follows to show yet.
                state.isRefreshing && state.follows.isEmpty() -> FollowsSkeletons(multiColumn)
                // Unauthed: show the sign-in CTA. Cookies are device-local, so
                // a fresh install on a new device lands here even if the user
                // is signed in elsewhere.
                !state.isSignedIn -> SignedOutEmpty(onOpenSignIn)
                // Signed in but list is empty (user follows nothing on RR yet).
                state.follows.isEmpty() -> SignedInEmpty()
                multiColumn -> {
                    // Tablet/foldable: row-cards laid out in 2+ adaptive columns so a
                    // long follow list fills the screen instead of a thin centered strip.
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 320.dp),
                        contentPadding = PaddingValues(spacing.md),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        gridItems(dedupedFollows, key = { it.fiction.id }) { follow ->
                            FollowCard(follow = follow, onClick = { viewModel.open(follow.fiction.id) })
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(spacing.md),
                        verticalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        items(dedupedFollows, key = { it.fiction.id }) { follow ->
                            FollowCard(follow = follow, onClick = { viewModel.open(follow.fiction.id) })
                        }
                    }
                }
            }
        }
    }
}

/**
 * Empty state shown when the user has no Royal Road session on this device.
 * The brass sigil keeps the realm aesthetic; the CTA jumps to the WebView
 * sign-in flow which captures cookies into the encrypted store.
 */
@Composable
private fun SignedOutEmpty(onOpenSignIn: () -> Unit) {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier.fillMaxSize().padding(spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MagicSkeletonTile(
            modifier = Modifier.size(width = 160.dp, height = 220.dp),
            shape = MaterialTheme.shapes.medium,
            glyphSize = 80.dp,
        )
        Spacer(Modifier.height(spacing.lg))
        Text(
            "Sign in to see your follows",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(spacing.xs))
        Text(
            "Your Royal Road session lives on this device. Sign in here to pull in the fictions you follow.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(spacing.lg))
        BrassButton(
            label = "Sign in to Royal Road",
            onClick = onOpenSignIn,
            variant = BrassButtonVariant.Primary,
        )
    }
}

/**
 * Empty state shown when the user IS signed in but has zero follows on RR.
 * No CTA — directing them to Browse is the obvious next step but the bottom
 * nav already does that.
 */
@Composable
private fun SignedInEmpty() {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier.fillMaxSize().padding(spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "No follows yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(spacing.xs))
        Text(
            "Find a fiction in Browse and tap the follow icon to add it here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Magical loading state for the Follows tab — five rows of brass-sigil
 * placeholders that match the FollowCard layout (small cover thumb +
 * title bar + author bar). The sigil rotates on each cover slot.
 */
@Composable
private fun FollowsSkeletons(multiColumn: Boolean) {
    val spacing = LocalSpacing.current
    if (multiColumn) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 320.dp),
            contentPadding = PaddingValues(spacing.md),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            gridItems(List(6) { it }) { FollowCardSkeleton() }
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            items(List(6) { it }) { FollowCardSkeleton() }
        }
    }
}

@Composable
private fun FollowCardSkeleton() {
    val spacing = LocalSpacing.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            MagicSkeletonTile(
                modifier = Modifier.size(width = 56.dp, height = 84.dp),
                shape = MaterialTheme.shapes.medium,
                glyphSize = 36.dp,
            )
            Column(modifier = Modifier.weight(1f)) {
                SkeletonBlock(modifier = Modifier.fillMaxWidth(0.85f).height(16.dp))
                SkeletonBlock(modifier = Modifier.fillMaxWidth(0.6f).height(12.dp).padding(top = spacing.xxs))
            }
        }
    }
}

@Composable
private fun FollowCard(follow: UiFollow, onClick: () -> Unit) {
    val spacing = LocalSpacing.current
    Card(
        // a11y (#481): Role.Button for the card-level tap target.
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            FictionCoverThumb(
                coverUrl = follow.fiction.coverUrl,
                title = follow.fiction.title,
                monogram = fictionMonogram(follow.fiction.author, follow.fiction.title),
                author = follow.fiction.author,
                sourceFamily = coverSourceFamilyFor(follow.fiction.sourceId),
                modifier = Modifier.size(width = 56.dp, height = 84.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(follow.fiction.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                Text(follow.fiction.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (follow.unreadCount > 0) {
                Badge(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                    Text(follow.unreadCount.toString())
                }
            }
        }
    }
}
