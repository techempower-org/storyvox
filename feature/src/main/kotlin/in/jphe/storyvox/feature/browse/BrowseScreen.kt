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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.feature.components.OfflineBanner
import `in`.jphe.storyvox.data.source.plugin.SourcePluginDescriptor
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
import `in`.jphe.storyvox.ui.component.MagicTitleBar
import `in`.jphe.storyvox.ui.component.TestTags
import `in`.jphe.storyvox.ui.layout.isAtLeastExpanded
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
    content: @Composable () -> Unit,
) {
    if (embedded) {
        // No Scaffold / TopAppBar — Library owns those.
        content()
    } else {
        Scaffold(
            topBar = {
                // #830 — shared title bar across all primary-nav surfaces.
                MagicTitleBar(title = stringResource(R.string.browse_title))
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
    // #776 — pull-to-refresh state lives off the ViewModel so the
    // M3 spinner overlay's `isRefreshing` flag survives recomposition
    // and matches the paginator refresh lifecycle (cleared in the
    // finally block of refresh()).
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    // #786 — live network state. Drives the OfflineBanner above the grid
    // and the full-screen-error → banner downgrade when cached items exist.
    val isOffline by viewModel.isOffline.collectAsStateWithLifecycle()
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
    ) {
    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize().padding(top = spacing.md)) {
        // v0.5.72 — magical hero source carousel for the first-class
        // Browse tab. Replaces the v0.5.40 chip strip with brass-edged
        // source cards showing icon + name + tagline so the picker
        // reads as a discovery surface, not a search facet. Switching
        // cards swaps the multibinding lookup in FictionRepository
        // between sources; tabs and the filter sheet rebind to the new
        // source's shape.
        BrowseSourceCarousel(
            selectedId = state.sourceId,
            onSelect = viewModel::selectSource,
            visibleSources = state.visibleSources,
            // v0.5.76 — long-press → bottom sheet → favorite/hide.
            favoriteSourceIds = state.favoriteSourceIds,
            onToggleFavorite = viewModel::toggleFavorite,
            onHideSource = { viewModel.setSourceEnabled(it, enabled = false) },
            // Long-press + horizontal drag reorders source cards.
            onReorder = viewModel::reorderSources,
            // UI-test selector for the source picker row.
            modifier = Modifier.fillMaxWidth().testTag(TestTags.BrowseSourceList),
        )

        // Issue #695 — read the active source's `supportsSearch`
        // annotation off its descriptor so the Search tab disappears for
        // sources that declared `supportsSearch = false`
        // (Slack, Telegram). Sources missing from `visibleSources` (an
        // unenabled-id race) default to `true` so we don't accidentally
        // strip Search while the picker is still loading.
        val supportsSearch = state.visibleSources
            .firstOrNull { it.id == state.sourceId }
            ?.supportsSearch
            ?: true
        val supportedTabs = remember(
            state.sourceId,
            state.githubSignedIn,
            state.ao3SignedIn,
            supportsSearch,
        ) {
            BrowseSourceUi.supportedTabs(
                state.sourceId,
                githubSignedIn = state.githubSignedIn,
                ao3SignedIn = state.ao3SignedIn,
                supportsSearch = supportsSearch,
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
            val hasDynamicFilters = state.filterDimensions.isNotEmpty()
            val hasPalaceWing = state.sourceId == SourceIds.MEMPALACE
            if (hasDynamicFilters || hasPalaceWing) {
                val activeCount = if (hasPalaceWing) {
                    if (state.palaceFilter.wing != null) 1 else 0
                } else {
                    state.filterState.activeCount()
                }
                FilterButton(
                    activeCount = activeCount,
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
                label = { Text(stringResource(R.string.browse_search_hint, sourceLabel)) },
                // UI-test selector for the Browse search input.
                modifier = Modifier.fillMaxWidth().padding(spacing.md).testTag(TestTags.BrowseSearchField),
                singleLine = true,
            )
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

        // #786 — offline banner above the grid. Rendered whenever we're
        // offline AND have cached items to show (when there are no cached
        // items the full-screen error block below carries the offline copy
        // instead, so we don't stack two offline affordances). Retry reuses
        // the same loadMore() the paginator wires to scroll-near-end.
        if (isOffline && state.items.isNotEmpty()) {
            OfflineBanner(onRetry = { viewModel.loadMore() })
        }

        when {
            // Issue #241 — RR listings are gated on sign-in. CTA sits ahead
            // of the SkeletonGrid branch so the user never sees a loading
            // shimmer for a request the resolver wasn't going to fire.
            // Search stays open (the resolver in BrowseViewModel exempts
            // it), so the CTA is suppressed when the user is on Search.
            state.sourceId == SourceIds.ROYAL_ROAD &&
                !state.royalRoadSignedIn &&
                state.tab != BrowseTab.Search -> RoyalRoadSignedOutCta(
                    onOpenSignIn = onOpenRoyalRoadSignIn,
                    onSearchAnonymously = { viewModel.selectTab(BrowseTab.Search) },
                )
            state.isLoading && state.items.isEmpty() -> SkeletonGrid()
            state.tab == BrowseTab.Search && state.query.isBlank() && !state.filterState.isActive() ->
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
                LocalEmptyState(
                    title = "Listen to your own EPUBs",
                    body = "Pick a folder on your device — every .epub inside becomes " +
                        "a fiction storyvox can read to you. Zero network, " +
                        "your files stay on your device.",
                    onPick = viewModel::setEpubFolderUri,
                )
            // Issue #996 — same first-run empty state for the Local PDFs
            // chip. PdfSource lists nothing until the user picks a folder.
            state.sourceId == SourceIds.PDF &&
                state.tab != BrowseTab.Search &&
                state.items.isEmpty() &&
                !state.isLoading &&
                state.error == null ->
                LocalEmptyState(
                    title = "Listen to your own PDFs",
                    body = "Pick a folder on your device — every .pdf inside becomes " +
                        "a fiction storyvox can read to you. Syllabi, papers, " +
                        "manuals, forms. Zero network, your files stay on your device.",
                    onPick = viewModel::setPdfFolderUri,
                )
            // Issue #673 — Readability backend is the always-on last-resort
            // URL matcher; it has no listings of its own. Pre-fix, the chip's
            // body rendered as silent-empty: no spinner, no message, no CTA.
            // Surface an informative panel that explains the source's purpose
            // and points the user at the "Add fiction by URL" FAB.
            state.sourceId == SourceIds.READABILITY &&
                state.tab != BrowseTab.Search &&
                state.items.isEmpty() &&
                !state.isLoading &&
                state.error == null ->
                ReadabilityHintState()
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
                LaunchedEffect(state.sourceId, state.tab, state.query, state.filterState) {
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
                // #776 — wrap the grid in PullToRefreshBox so the user
                // can swipe-down to re-fetch the current paginator. The
                // M3 spinner overlays on top of the existing items
                // (rather than replacing them with a skeleton grid like
                // the first-page fetch path does at line ~372), so the
                // user keeps their visual context through the refresh.
                // viewModel.refresh() resets the paginator + immediately
                // calls loadNext(); isRefreshing flips false when that
                // returns (success OR failure).
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = viewModel::refresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = browseGridMinSizeDp(isAtLeastExpanded()).dp),
                    // UI-test selector for the loaded results grid (the
                    // skeleton/loading grid is intentionally untagged so a
                    // flow waiting on `browse-results` waits for real content).
                    modifier = Modifier.fillMaxSize().testTag(TestTags.BrowseResults),
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
                                // Use friendlyErrorMessage for consistency
                                // with the full-screen error block above.
                                // The pre-fix fallback "We couldn't reach
                                // Royal Road." was dead code (the if-guard
                                // ensures non-null) but also wrong for
                                // non-RR sources.
                                message = friendlyErrorMessage(state.error),
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
                            // Issue #782 — realm-vocabulary footer. Was a bare
                            // M3-default "End of list" Text; the rest of Browse
                            // already speaks the realm (MagicSpinner, brass
                            // error blocks), so the last line should too.
                            // Thin outline-variant divider anchors the footer
                            // as a deliberate landmark rather than a stray label.
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = spacing.lg),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                HorizontalDivider(
                                    modifier = Modifier
                                        .fillMaxWidth(0.4f)
                                        .padding(bottom = spacing.md),
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                )
                                Text(
                                    text = stringResource(R.string.browse_end_of_list),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
                }  // #776 PullToRefreshBox
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
        if (state.sourceId == SourceIds.MEMPALACE) {
            MemPalaceFilterSheet(
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
        } else if (state.filterDimensions.isNotEmpty()) {
            val descriptor = state.visibleSources.firstOrNull { it.id == state.sourceId }
            val sourceLabel = descriptor?.displayName ?: state.sourceId
            DynamicFilterSheet(
                sourceLabel = sourceLabel,
                dimensions = state.filterDimensions,
                state = state.filterState,
                onApply = { applied ->
                    viewModel.setFilterState(applied)
                    showFilterSheet = false
                },
                onReset = {
                    viewModel.resetFilterState()
                    showFilterSheet = false
                },
                onDismiss = { showFilterSheet = false },
            )
        } else {
            showFilterSheet = false
        }
    }
}

@Composable
private fun FilterButton(activeCount: Int, onClick: () -> Unit) {
    // UI-test selector for the filter entrypoint. Same tag on both
    // branches (badged vs. plain) so a flow addresses `browse-filter`
    // regardless of whether any filter is currently active.
    if (activeCount > 0) {
        BadgedBox(
            badge = {
                Badge(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) { Text(activeCount.toString()) }
            },
        ) {
            IconButton(onClick = onClick, modifier = Modifier.testTag(TestTags.BrowseFilter)) {
                Icon(Icons.Outlined.FilterAlt, contentDescription = "Filter")
            }
        }
    } else {
        IconButton(onClick = onClick, modifier = Modifier.testTag(TestTags.BrowseFilter)) {
            Icon(Icons.Outlined.FilterAlt, contentDescription = "Filter")
        }
    }
}

/**
 * Issue #783 — Browse cover-grid adaptive minimum. 140dp on tablet/phone
 * (matches the Library grid #452 tuning), bumped to 180dp on the Expanded
 * breakpoint (>=840dp) so covers grow on Tab S-class landscape and unfolded
 * foldables instead of over-packing the row. Both the live grid and
 * [SkeletonGrid] read it so the loading and loaded layouts agree on column
 * count. Pinned by [BrowseGridExpandedTest].
 */
internal fun browseGridMinSizeDp(expanded: Boolean): Int = if (expanded) 180 else 140

@Composable
private fun SkeletonGrid() {
    val spacing = LocalSpacing.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = browseGridMinSizeDp(isAtLeastExpanded()).dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(spacing.md),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        items(12) { FictionCardSkeleton(modifier = Modifier.fillMaxWidth()) }
    }
}

/**
 * #426 PR2 — compact AO3 sign-in banner. Surfaces above the AO3
 * listing when the chip is selected and no AO3 session is captured.
 * Same shape as [RoyalRoadSignedOutCta] (Card + headline + body + text
 * button) —
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
            ) { Text(stringResource(R.string.browse_ao3_signin_cta)) }
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
private fun LocalEmptyState(
    title: String,
    body: String,
    onPick: (String) -> Unit,
) {
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
            onPick(uri.toString())
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
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                body,
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

/**
 * Issue #673 — informative empty state for the Readability backend.
 * Readability is the always-on last-resort URL matcher: any HTTP(S)
 * URL that none of the 17 specialized backends claim falls through to
 * here, where Readability4J's Mozilla-Readability port extracts the
 * article body. It has no Browse listings of its own — popular()
 * intentionally returns emptyPage().
 */
@Composable
private fun ReadabilityHintState() {
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
                "Paste any web article",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                "The reader extracts the text from blog posts, news, " +
                    "and any web page that doesn't match a specialized " +
                    "backend. Tap the + button below to paste a URL.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
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
        // #796 — Wikipedia browse-only tab labels. Short forms to fit
        // the chip strip on narrow phones.
        BrowseTab.WikipediaOnThisDay -> "On This Day"
        BrowseTab.WikipediaInTheNews -> "In the News"
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
        label = { Text(stringResource(R.string.browse_mempalace_wing_chip, prettifyWing(wing))) },
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
private fun RoyalRoadSignedOutCta(
    onOpenSignIn: () -> Unit,
    onSearchAnonymously: () -> Unit,
) {
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
        Spacer(Modifier.height(spacing.xs))
        BrassButton(
            label = "Search without signing in",
            onClick = onSearchAnonymously,
            variant = BrassButtonVariant.Text,
        )
    }
}

