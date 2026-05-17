package `in`.jphe.storyvox.feature.browse

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.plugin.SourcePluginDescriptor
import `in`.jphe.storyvox.feature.api.BrowseFilter
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.component.cascadeReveal
import `in`.jphe.storyvox.ui.component.coverSourceFamilyFor
import `in`.jphe.storyvox.ui.component.ErrorBlock
import `in`.jphe.storyvox.ui.component.friendlyErrorMessage
import `in`.jphe.storyvox.ui.component.ErrorPlacement
import `in`.jphe.storyvox.ui.component.FictionCardSkeleton
import `in`.jphe.storyvox.ui.component.FictionCoverThumb
import `in`.jphe.storyvox.ui.component.fictionMonogram
import `in`.jphe.storyvox.ui.component.MagicSkeletonTile
import `in`.jphe.storyvox.ui.component.MagicSpinner
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Restructure (v0.5.40) — switch between the standalone Browse Scaffold
 * (with its own TopAppBar) and an embedded frame that just renders the
 * supplied body. Library is the only call-site that passes
 * `embedded = true`; the deep-linked BROWSE route keeps the original
 * full-screen rendering.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowseScaffoldOrFrame(
    embedded: Boolean,
    onOpenSettings: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (embedded) {
        // No Scaffold / TopAppBar — Library owns those.
        content()
    } else {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("Browse", style = MaterialTheme.typography.titleMedium) },
                    actions = {
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                        }
                    },
                )
            },
        ) { scaffoldPadding ->
            Box(modifier = Modifier.fillMaxSize().padding(scaffoldPadding)) {
                content()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    onOpenFiction: (String) -> Unit,
    /** Issue #241 — navigates to the Royal Road sign-in WebView. Surfaced
     *  on the listing tabs (Popular / NewReleases / BestRated) when the
     *  user is not signed in to RR; Search keeps working anonymously. */
    onOpenRoyalRoadSignIn: () -> Unit,
    /** #426 PR2 — navigates to the AO3 sign-in WebView. Surfaced on the
     *  AO3 chip's auth-only "My Subscriptions" / "Marked for Later" tabs
     *  when the user is not signed in to AO3. Defaults to a no-op so
     *  pre-PR2 BrowseScreen callers don't need to thread the param;
     *  the only call site that needs the real route is the
     *  StoryvoxNavHost Library branch. */
    onOpenAo3SignIn: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    /**
     * Restructure (v0.5.40) — when true, BrowseScreen renders without
     * its own Scaffold + TopAppBar. The Library tab's TopAppBar serves
     * as the parent header and this body fills the parent's content
     * area. Standalone (`embedded = false`) preserves the legacy
     * full-screen rendering for the deep-linked BROWSE route, which
     * still exists for the HybridReader empty-state CTA + back-stack
     * pushes that bypass the bottom nav.
     */
    embedded: Boolean = false,
    viewModel: BrowseViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current
    var showFilterSheet by remember { mutableStateOf(false) }
    /** Issue #247 — RSS feed management moved out of Settings into a
     *  FAB-launched sheet visible only when sourceKey=Rss. */
    var showRssManageSheet by remember { mutableStateOf(false) }

    // #328 — see LibraryScreen.kt; hoist distinctBy out of the grid
    // builder so allocations happen once per state.items change instead
    // of every recomposition, and log when duplicates appear so the
    // upstream RR / GitHub paginator that emitted them can be traced.
    val dedupedItems = remember(state.items) {
        val out = state.items.distinctBy { it.id }
        val dropped = state.items.size - out.size
        if (dropped > 0) {
            android.util.Log.w(
                "storyvox",
                "BrowseScreen: dropped $dropped duplicate fiction id(s) (size ${state.items.size} -> ${out.size}) — see #328",
            )
        }
        out
    }

    // Restructure (v0.5.40) — when embedded under Library, skip the
    // standalone Scaffold/TopAppBar: the parent screen already owns
    // those. The inner Box stays in both branches because the RSS FAB
    // uses `Modifier.align(Alignment.BottomEnd)` against it.
    BrowseScaffoldOrFrame(
        embedded = embedded,
        onOpenSettings = onOpenSettings,
    ) {
    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize().padding(top = spacing.md)) {
        // Top-level source picker. Switches the multibinding lookup in
        // FictionRepository between Royal Road and GitHub. Tabs and the
        // filter sheet rebind to whatever the chosen source supports.
        BrowseSourcePicker(
            selectedId = state.sourceId,
            onSelect = viewModel::selectSource,
            visibleSources = state.visibleSources,
            // Picker now handles its own horizontal padding via LazyRow's
            // contentPadding so off-screen chips can pan into view without
            // an outer padding clipping them at the edges.
            modifier = Modifier.fillMaxWidth(),
        )

        val supportedTabs = remember(state.sourceId, state.githubSignedIn, state.ao3SignedIn) {
            BrowseSourceUi.supportedTabs(
                state.sourceId,
                githubSignedIn = state.githubSignedIn,
                ao3SignedIn = state.ao3SignedIn,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Scrollable so 'Popular' / 'Best Rated' don't truncate when
            // sharing the row with the filter funnel on narrow phones.
            //
            // Issue #535 — edgePadding bumped from 0 to spacing.md so
            // the rightmost AO3 tab ("Marked") lands as an
            // obviously-partial chip on Flip3 narrow rather than
            // sitting 5dp from the screen edge (which reads as a
            // rendering glitch and hides the scroll affordance). Same
            // discoverability fix the Library tab row got for #532 and
            // the VoiceLibrary chip row got for #420.
            SecondaryScrollableTabRow(
                selectedTabIndex = supportedTabs.indexOf(state.tab).coerceAtLeast(0),
                modifier = Modifier.weight(1f),
                edgePadding = spacing.md,
            ) {
                supportedTabs.forEach { tab ->
                    Tab(
                        selected = tab == state.tab,
                        onClick = { viewModel.selectTab(tab) },
                        // Issues #258 + #270 — render Search as an icon-only
                        // tab so the row fits on Flip3 (1080px inner display
                        // and similar narrow phones). When "Search" was a
                        // text tab between Best Rated and the filter funnel,
                        // it got clipped to 'S' and dragged the row into
                        // horizontal-scroll territory, which then clipped
                        // 'Popular' to 'ar' on the other edge. Magnifying-
                        // glass is the universal affordance for search and
                        // the contentDescription keeps a11y intact.
                        icon = if (tab == BrowseTab.Search) {
                            {
                                Icon(
                                    imageVector = Icons.Filled.Search,
                                    contentDescription = "Search",
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        } else null,
                        text = if (tab == BrowseTab.Search) null else {
                            {
                                Text(
                                    text = tab.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        },
                    )
                }
            }
            // Filter sheet is per-source: RR has its `/fictions/search`
            // form, GitHub has the `/search/repositories` qualifier set,
            // MemPalace has a wing chooser. RSS / EPUB / Outline have
            // no filter sheet (configuration lives in Settings) — hide
            // the button entirely for those sources, since the click
            // handler is a documented no-op (`showFilterSheet = false`
            // at the bottom of the file) and presenting a tappable icon
            // that does nothing reads as a bug to users (phone audit
            // pass 2).
            // Plugin-seam Phase 3 (#384) — filter shape lookup goes
            // through the registry-keyed side-table in `BrowseSourceUi`
            // rather than an exhaustive when over the deleted
            // `BrowseSourceKey` enum. RR / GitHub / MemPalace are the
            // three filter-bearing surfaces today; everything else
            // hides the button.
            val shape = BrowseSourceUi.filterShape(state.sourceId)
            if (shape != FilterShape.None) {
                FilterButton(
                    activeCount = when (shape) {
                        FilterShape.RoyalRoad -> state.filter.activeCount()
                        FilterShape.GitHub -> state.githubFilter.activeCount()
                        // #191 — single dimension (wing) so badge counts
                        // at most 1.
                        FilterShape.MemPalace -> if (state.palaceFilter.wing != null) 1 else 0
                        FilterShape.None -> 0
                    },
                    onClick = { showFilterSheet = true },
                )
            }
        }

        // Active wing hint — surface the selected wing prominently
        // below the tab row so users always know the listing is scoped.
        // Tapping the chip clears the wing (one-tap reset path) without
        // having to reopen the sheet.
        if (state.sourceId == SourceIds.MEMPALACE && state.palaceFilter.wing != null) {
            ActiveWingChip(
                wing = state.palaceFilter.wing!!,
                onClear = { viewModel.resetPalaceFilter() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.md, vertical = spacing.xs),
            )
        }

        if (state.tab == BrowseTab.Search) {
            val sourceLabel = remember(state.sourceId, state.visibleSources) {
                state.visibleSources.firstOrNull { it.id == state.sourceId }?.displayName
                    ?: state.sourceId
            }
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                label = { Text("Search $sourceLabel") },
                modifier = Modifier.fillMaxWidth().padding(spacing.md),
                singleLine = true,
            )
        }

        // Issue #443 — when the Notion chip is active and the user
        // has no integration token configured, the source falls back
        // to TechEmpower's public Notion content via the anonymous
        // reader. Surface that as a labeled demo so users don't see
        // "Notion" → TechEmpower cards and conclude the source is
        // broken / mis-wired. JP design (issue comment): keep the
        // fallback, add a clear demo label + a Settings deep-link.
        if (state.sourceId == SourceIds.NOTION && state.notionAnonymousActive) {
            NotionDemoBanner(onOpenSettings = onOpenSettings)
        }

        // #426 PR2 — AO3 sign-in hint when the user has the AO3 chip
        // selected but no session is captured. Renders a compact
        // brass-tinted banner above the listing with a "Sign in to AO3"
        // affordance — the auth-only "My Subscriptions" / "Marked for
        // Later" tabs are hidden from the chip strip in that state, so
        // this is the only surface that points the user at the sign-in
        // route. Suppressed on Search (search keeps working anonymously
        // and the banner would be redundant noise on a search query).
        if (state.sourceId == SourceIds.AO3 &&
            !state.ao3SignedIn &&
            state.tab != BrowseTab.Search
        ) {
            Ao3SignInBanner(onOpenSignIn = onOpenAo3SignIn)
        }

        when {
            // Issue #241 — RR listings are gated on sign-in. CTA sits ahead
            // of the SkeletonGrid branch so the user never sees a loading
            // shimmer for a request the resolver wasn't going to fire.
            // Search stays open (the resolver in BrowseViewModel exempts
            // it), so the CTA is suppressed when the user is on Search.
            state.sourceId == SourceIds.ROYAL_ROAD &&
                !state.royalRoadSignedIn &&
                state.tab != BrowseTab.Search -> RoyalRoadSignedOutCta(onOpenRoyalRoadSignIn)
            state.isLoading && state.items.isEmpty() -> SkeletonGrid()
            state.tab == BrowseTab.Search && state.query.isBlank() && !state.isFilterActive ->
                SearchHint(state.sourceId, state.visibleSources)
            // Issue #458 — RSS chip on a fresh install shows the Popular
            // tab with no subscribed feeds, which used to render as a
            // blank screen with just a FAB at the corner. Users tapped
            // RSS, saw nothing, missed the FAB, and concluded the source
            // was broken. Surface an explicit empty state with copy + a
            // primary "Add a feed" CTA that opens the same manage sheet.
            state.sourceId == SourceIds.RSS &&
                state.tab != BrowseTab.Search &&
                state.items.isEmpty() &&
                !state.isLoading &&
                state.error == null ->
                RssEmptyState(onAdd = { showRssManageSheet = true })
            // Issue #669 — Local backend empty state. Without this branch
            // tapping the Local chip on a fresh install rendered a
            // completely blank content area: no spinner, no message,
            // no CTA. Indistinguishable from a backend hang. Surface
            // an explicit empty state with copy + a primary CTA that
            // launches the SAF folder picker directly so the user can
            // start importing without first navigating to Settings.
            state.sourceId == SourceIds.EPUB &&
                state.tab != BrowseTab.Search &&
                state.items.isEmpty() &&
                !state.isLoading &&
                state.error == null ->
                LocalEmptyState(viewModel = viewModel)
            // First-load failure with no cached items: full-screen error.
            // Retry triggers viewModel.loadMore() which the paginator
            // resolves to the same page that failed.
            state.error != null && state.items.isEmpty() -> ErrorBlock(
                title = "The realm is unreachable",
                // #171 — friendlyErrorMessage maps the raw exception
                // string (HTTP 0 timeouts, IOExceptions, "host not
                // configured") to user copy that doesn't leak the
                // OkHttp stack into the UI.
                message = friendlyErrorMessage(state.error),
                onRetry = { viewModel.loadMore() },
                placement = ErrorPlacement.FullScreen,
            )
            else -> {
                // Hoist the grid state so we can watch the last-visible
                // index and trigger viewModel.loadMore() near the end.
                val gridState = rememberLazyGridState()
                // Reset scroll to top whenever the source tuple changes
                // (tab switch, new search, filter applied). The paginator
                // hands us a fresh items list anyway; we just nudge the
                // viewport back to the start so the user doesn't land
                // mid-scroll into a different listing.
                LaunchedEffect(state.sourceId, state.tab, state.query, state.filter) {
                    if (gridState.firstVisibleItemIndex != 0) {
                        gridState.scrollToItem(0)
                    }
                }
                val nearEnd by remember(state.items.size, state.hasMore) {
                    derivedStateOf {
                        if (!state.hasMore) return@derivedStateOf false
                        val info = gridState.layoutInfo
                        val total = info.totalItemsCount
                        if (total == 0) return@derivedStateOf false
                        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                        // Trigger when within ~6 items of the end —
                        // roughly one row at 6-col, three rows at 2-col.
                        lastVisible >= total - 6
                    }
                }
                // rememberUpdatedState pins the latest state snapshot so
                // the long-lived collector below reads current values
                // without restarting on every state change.
                val currentState by rememberUpdatedState(state)
                // Edge-trigger on nearEnd: distinctUntilChanged means we
                // only fire on a transition false→true. Without this a
                // failed page (no items added, isAppending flips back
                // false while nearEnd stays true) would tight-loop the
                // network. User must scroll back+forward to retry — the
                // safe default.
                LaunchedEffect(gridState) {
                    snapshotFlow { nearEnd }
                        .distinctUntilChanged()
                        .filter { it }
                        .collect {
                            val s = currentState
                            if (!s.isAppending && !s.isLoading) viewModel.loadMore()
                        }
                }
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = 140.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    // Tail/refresh error while we still have cached items —
                    // surface as a banner so users keep seeing what they were
                    // browsing. Retry path is the same loadMore() the
                    // paginator already wires to scroll-near-end.
                    if (state.error != null) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            ErrorBlock(
                                title = "Couldn't refresh",
                                message = state.error ?: "We couldn't reach Royal Road.",
                                onRetry = { viewModel.loadMore() },
                                placement = ErrorPlacement.Banner,
                            )
                        }
                    }
                    itemsIndexed(dedupedItems, key = { _, item -> item.id }) { index, fiction ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem()
                                .cascadeReveal(index = index, key = fiction.id)
                                // a11y (#481): Role.Button + label for the fiction-card tap.
                                .clickable(
                                    role = Role.Button,
                                    onClickLabel = "Open ${fiction.title}",
                                ) { onOpenFiction(fiction.id) },
                            verticalArrangement = Arrangement.spacedBy(spacing.xs),
                        ) {
                            FictionCoverThumb(
                                coverUrl = fiction.coverUrl,
                                title = fiction.title,
                                monogram = fictionMonogram(fiction.author, fiction.title),
                                author = fiction.author,
                                sourceFamily = coverSourceFamilyFor(fiction.sourceId),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            // Issue #272 — titles longer than 2 lines were silently
                            // cut mid-token ("…[Vols", "…(OP", "the I"), reading as
                            // broken data rather than UI truncation. Set
                            // overflow = Ellipsis so the cut becomes "…" and the
                            // user knows the text continues. Author already has
                            // the same treatment below (maxLines = 1) but no
                            // overflow set; same fix.
                            Text(
                                fiction.title,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            if (fiction.author.isNotBlank()) {
                                Text(
                                    fiction.author,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    if (state.isAppending) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(spacing.lg),
                                contentAlignment = Alignment.Center,
                            ) {
                                // Brass MagicSpinner while the next page is being
                                // fetched — matches the rest of the realm's loading
                                // vocabulary instead of the cool-grey M3 default.
                                MagicSpinner(modifier = Modifier.size(32.dp))
                            }
                        }
                    } else if (!state.hasMore && state.items.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                "End of list",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(spacing.lg),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }

    // Issue #247 — FAB for RSS feed management. Only visible on the
    // RSS source; other backends manage subscriptions elsewhere
    // (Settings folder picker for EPUB, host config for Outline,
    // sign-in for RR/GitHub).
    if (state.sourceId == SourceIds.RSS) {
        FloatingActionButton(
            onClick = { showRssManageSheet = true },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(spacing.lg),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Add RSS feed")
        }
    }
    }  // Box
    }  // BrowseScaffoldOrFrame body

    if (showRssManageSheet) {
        BrowseRssManageSheet(
            viewModel = viewModel,
            onDismiss = { showRssManageSheet = false },
        )
    }

    if (showFilterSheet) {
        when (BrowseSourceUi.filterShape(state.sourceId)) {
            FilterShape.RoyalRoad -> BrowseFilterSheet(
                filter = state.filter,
                onApply = { applied ->
                    viewModel.setFilter(applied)
                    showFilterSheet = false
                },
                onReset = {
                    viewModel.resetFilter()
                    showFilterSheet = false
                },
                onDismiss = { showFilterSheet = false },
            )
            FilterShape.GitHub -> GitHubFilterSheet(
                filter = state.githubFilter,
                onApply = { applied ->
                    viewModel.setGitHubFilter(applied)
                    showFilterSheet = false
                },
                onReset = {
                    viewModel.resetGitHubFilter()
                    showFilterSheet = false
                },
                onDismiss = { showFilterSheet = false },
                showVisibilityChips = state.hasGitHubRepoScope,
            )
            FilterShape.MemPalace -> MemPalaceFilterSheet(
                filter = state.palaceFilter,
                wings = state.palaceWings,
                onApply = { applied ->
                    viewModel.setPalaceFilter(applied)
                    showFilterSheet = false
                },
                onReset = {
                    viewModel.resetPalaceFilter()
                    showFilterSheet = false
                },
                onDismiss = { showFilterSheet = false },
            )
            // Plugin-seam Phase 3 (#384) — sources without a filter
            // sheet (RSS / Epub / Outline / Gutenberg / AO3 / Standard
            // Ebooks / Wikipedia / Wikisource / KVMR / Notion / Hacker
            // News / arXiv / PLOS / Discord) collapse into one branch.
            // The toolbar filter button is hidden for these sources via
            // [BrowseSourceUi.filterShape], so reaching this branch is
            // a defensive path that just dismisses the sheet.
            FilterShape.None -> { showFilterSheet = false }
        }
    }
}

@Composable
private fun FilterButton(activeCount: Int, onClick: () -> Unit) {
    if (activeCount > 0) {
        BadgedBox(
            badge = {
                Badge(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) { Text(activeCount.toString()) }
            },
        ) {
            IconButton(onClick = onClick) {
                Icon(Icons.Outlined.FilterAlt, contentDescription = "Filter")
            }
        }
    } else {
        IconButton(onClick = onClick) {
            Icon(Icons.Outlined.FilterAlt, contentDescription = "Filter")
        }
    }
}

@Composable
private fun SkeletonGrid() {
    val spacing = LocalSpacing.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(spacing.md),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        items(12) { FictionCardSkeleton(modifier = Modifier.fillMaxWidth()) }
    }
}

/**
 * Issue #443 — demo-content banner for the Notion chip when no
 * integration token is configured. The Notion source falls back to
 * the TechEmpower public Notion content via the anonymous-public
 * reader (#393), which surfaces TechEmpower's Guides / Resources /
 * About / Donate as fictions.
 *
 * Issue #602 (v1.0). The banner used to lead with "Add a Notion
 * integration token to read your own database." — confusing to a
 * brand-new user who hadn't asked for a Notion database in the first
 * place. The anonymous content IS the user's content as far as
 * first-launch is concerned (TechEmpower's free guides), and the
 * "add your own" path is a power-user affordance, not a default
 * call-to-action. v1.0 reframes:
 *
 *   - Headline: "TechEmpower's free guides" (concrete, true)
 *   - Body: positive — what they ARE looking at, not what's missing
 *   - Power-user CTA: smaller, off-message "Have your own Notion
 *     workspace?" affordance that ONLY appears in the long-press /
 *     advanced flow. For v1.0 we keep the button in place but with
 *     softer copy so a brand-new user doesn't read it as a setup
 *     blocker.
 */
@Composable
private fun NotionDemoBanner(onOpenSettings: () -> Unit) {
    val spacing = LocalSpacing.current
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.md, vertical = spacing.xs),
    ) {
        Column(modifier = Modifier.padding(spacing.md)) {
            // Issue #621 — "Demo content" read as a debug placeholder
            // ("did the dev forget to wire something up?"). Replace
            // with a friendly TechEmpower phrasing that names the
            // collection explicitly so the user reads the cards as
            // intentional, not stub.
            Text(
                "TechEmpower's free guides",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Free, no account needed. Tap any card to listen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.xxs),
            )
            androidx.compose.material3.TextButton(
                onClick = onOpenSettings,
                modifier = Modifier.padding(top = spacing.xs),
            ) { Text("Have a Notion workspace? Connect it →") }
        }
    }
}

/**
 * #426 PR2 — compact AO3 sign-in banner. Surfaces above the AO3
 * listing when the chip is selected and no AO3 session is captured.
 * Mirrors [NotionDemoBanner]'s shape (Card + headline + body + text
 * button) rather than the full-screen [RoyalRoadSignedOutCta] —
 * AO3's Popular / NewReleases / Search tabs keep working anonymously,
 * so we're only nudging the user toward auth-only surfaces, not
 * blocking the listing entirely.
 */
@Composable
private fun Ao3SignInBanner(onOpenSignIn: () -> Unit) {
    val spacing = LocalSpacing.current
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.md, vertical = spacing.xs),
    ) {
        Column(modifier = Modifier.padding(spacing.md)) {
            Text(
                "AO3 sign-in",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Sign in to AO3 to see your subscribed works and Marked for Later.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.xxs),
            )
            androidx.compose.material3.TextButton(
                onClick = onOpenSignIn,
                modifier = Modifier.padding(top = spacing.xs),
            ) { Text("Sign in to AO3") }
        }
    }
}

/**
 * Issue #458 — Browse → RSS first-tap empty state. Replaces the
 * blank-with-FAB-only screen that read as a broken source. Headline +
 * one-line explanation of how the source works + a primary "Add a
 * feed" CTA that opens the same `BrowseRssManageSheet` the FAB does.
 */
/**
 * Issue #669 — empty state for the Local (EPUB) backend.
 *
 * Pre-fix Browse → Local rendered a completely blank content area with
 * no spinner, no message, no CTA. Every other "not yet configured"
 * backend (Palace, Wiki, Discord) had a clear `realm is unreachable`
 * panel + setup path; Local did not.
 *
 * The CTA below launches the same SAF `OpenDocumentTree` picker
 * Settings → EPUB folder uses, then persists the URI via
 * `BrowseViewModel.setEpubFolderUri`. The repository's `epubFolderUri`
 * flow propagates the change so the EPUB source re-enumerates the
 * folder and the empty state flips into the normal grid on next
 * paginator tick.
 *
 * The folder-permission `takePersistableUriPermission` call mirrors the
 * Settings row's logic (SettingsScreen.EpubFolderPickerRow) so the
 * grant outlives the current process.
 */
@Composable
private fun LocalEmptyState(viewModel: BrowseViewModel) {
    val spacing = LocalSpacing.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, flags)
            }
            viewModel.setEpubFolderUri(uri.toString())
        }
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
            modifier = Modifier.padding(horizontal = spacing.xl),
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
            Text(
                "Listen to your own EPUBs",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                "Pick a folder on your device — every .epub inside becomes " +
                    "a fiction storyvox can read to you. Zero network, " +
                    "your files stay on your device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(spacing.md))
            BrassButton(
                label = "Choose folder",
                onClick = { launcher.launch(null) },
                variant = BrassButtonVariant.Primary,
            )
        }
    }
}

@Composable
private fun RssEmptyState(onAdd: () -> Unit) {
    val spacing = LocalSpacing.current
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
            modifier = Modifier.padding(horizontal = spacing.xl),
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
            Text(
                "Add your first RSS feed",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                "Paste any feed URL — blog posts, podcasts, news. Each entry " +
                    "becomes a chapter storyvox can read to you.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(spacing.md))
            BrassButton(
                label = "Add a feed",
                onClick = onAdd,
                variant = BrassButtonVariant.Primary,
            )
        }
    }
}

@Composable
private fun SearchHint(sourceId: String, visibleSources: List<SourcePluginDescriptor>) {
    val spacing = LocalSpacing.current
    val displayName = remember(sourceId, visibleSources) {
        visibleSources.firstOrNull { it.id == sourceId }?.displayName ?: sourceId
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
            Text(
                "Search by title",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // Issue #271 — per-source empty-state subtitle. Phase 3
            // (#384) — the per-source phrase lookup goes through the
            // `BrowseSourceUi` side-table keyed by stable plugin id.
            Text(
                BrowseSourceUi.searchHint(sourceId, displayName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(spacing.xxl))
        }
    }
}

private val BrowseTab.label: String
    get() = when (this) {
        BrowseTab.Popular -> "Popular"
        BrowseTab.NewReleases -> "New"
        BrowseTab.BestRated -> "Best Rated"
        BrowseTab.Search -> "Search"
        BrowseTab.MyRepos -> "My Repos"
        BrowseTab.Starred -> "Starred"
        BrowseTab.Gists -> "Gists"
        // #426 PR2 — AO3 auth-only tab labels. Short forms so the
        // chip strip fits on Flip3's 1080-px inner display without
        // truncation (subscriptions → "Subscribed").
        BrowseTab.Ao3MySubscriptions -> "Subscribed"
        BrowseTab.Ao3MarkedForLater -> "Marked"
    }

/**
 * Horizontally scrollable backend-picker strip pinned above the tab row.
 *
 * Each enabled backend gets its own [FilterChip] at its natural label
 * width inside a [LazyRow]. The previous implementation used Material 3
 * `SingleChoiceSegmentedButtonRow`, which divides the parent's width
 * evenly between every segment — fine for 2-3 backends, but with 11+
 * enabled it forces the label to wrap mid-word inside ~50dp segments
 * and clips the off-screen backends entirely (no horizontal scroll on
 * segmented rows). See #407.
 *
 * Filter chips:
 *  - keep each label on one line (`softWrap = false`, `maxLines = 1`,
 *    overflow = ellipsis for paranoia on very narrow tablets),
 *  - allow the strip to grow as long as needed and pan freely on a
 *    one-finger horizontal swipe (LazyRow handles fling + clipping),
 *  - keep the brass-primary selection treatment so the strip still
 *    reads as part of the realm aesthetic.
 *
 * Plugin-seam Phase 3 (#384) — the picker iterates the
 * `SourcePluginRegistry`-derived `visibleSources` list rather than
 * the (deleted) `BrowseSourceKey.entries`. Adding a new backend is
 * one `@SourcePlugin`-annotated class — no enum entry needed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowseSourcePicker(
    selectedId: String,
    onSelect: (String) -> Unit,
    visibleSources: List<SourcePluginDescriptor>,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    if (visibleSources.isEmpty()) return
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        contentPadding = PaddingValues(horizontal = spacing.md),
    ) {
        items(visibleSources, key = { it.id }) { descriptor ->
            val label = BrowseSourceUi.chipLabel(descriptor.id, descriptor.displayName)
            val isSelected = descriptor.id == selectedId
            // Issue #622 — consistency sweep across the 21 backend chips.
            // Pre-fix the chips relied on `FilterChipDefaults` for the
            // unselected state, which on dark surfaces renders an almost-
            // invisible outline. The result was a strip where the selected
            // chip popped but the rest faded into the background, making
            // it hard to count or scan the available sources.
            //
            // Fix: every chip carries the SAME shape, SAME border, SAME
            // label style. Selected = brass primaryContainer fill.
            // Unselected = transparent fill + brass outline at 35 % alpha
            // (subtle, matches the WhyAreWeWaitingPanel border tone) +
            // onSurfaceVariant label. The brass primary border on the
            // active chip is suppressed (the fill already announces
            // selection) so the strip reads as a uniform family.
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(descriptor.id) },
                label = {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        softWrap = false,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = isSelected,
                    borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                    selectedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                    borderWidth = 1.dp,
                    selectedBorderWidth = 0.dp,
                ),
            )
        }
    }
}

/**
 * Hint chip surfaced under the tab row when MemPalace browse is scoped
 * to a wing (#191). The leading "Wing:" label keeps the source of the
 * filter unambiguous (vs a bare wing name); the trailing close icon
 * gives one-tap reset without re-opening the filter sheet.
 */
@Composable
private fun ActiveWingChip(
    wing: String,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AssistChip(
        onClick = onClear,
        label = { Text("Wing: ${prettifyWing(wing)}") },
        trailingIcon = {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Clear wing filter",
                modifier = Modifier.size(AssistChipDefaults.IconSize),
            )
        },
        modifier = modifier,
    )
}

/**
 * Issue #241 — empty state shown on the Royal Road listing tabs
 * (Popular / NewReleases / BestRated / filter-active) when the user
 * is not signed in. Mirrors FollowsScreen's `SignedOutEmpty` rhythm
 * (sigil tile + titleMedium primary headline + bodyMedium body +
 * brass primary CTA) so the two surfaces read as part of the same
 * family — the same brass voice asking the user to authorize before
 * we hit RR's listing endpoints on their behalf.
 *
 * The Search tab is intentionally suppressed in BrowseScreen's
 * `when` branch — search and Add-by-URL keep working anonymously per
 * the #241 spec, since they target specific URLs the user already
 * knows (not anonymous discovery of the catalog).
 */
@Composable
private fun RoyalRoadSignedOutCta(onOpenSignIn: () -> Unit) {
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
            "Sign in to browse Royal Road",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(spacing.xs))
        Text(
            "Storyvox uses your Royal Road session to fetch listings on your behalf — a logged-in reader, not an anonymous one. Search and paste-URL still work without signing in.",
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

/** Number of independent filter knobs the user has actively set. */
private fun BrowseFilter.activeCount(): Int {
    var n = 0
    if (tagsInclude.isNotEmpty()) n++
    if (tagsExclude.isNotEmpty()) n++
    if (statuses.isNotEmpty()) n++
    if (warningsRequire.isNotEmpty()) n++
    if (warningsExclude.isNotEmpty()) n++
    if (type != `in`.jphe.storyvox.feature.api.UiFictionType.All) n++
    if (minPages != null || maxPages != null) n++
    if (minRating != null || maxRating != null) n++
    return n
}
