package `in`.jphe.storyvox.feature.browse

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.data.auth.SessionState
import `in`.jphe.storyvox.data.network.ConnectivityObserver
import `in`.jphe.storyvox.data.repository.AuthRepository
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.plugin.SourcePluginDescriptor
import `in`.jphe.storyvox.data.source.plugin.SourcePluginRegistry
import `in`.jphe.storyvox.data.source.filter.FilterDimension
import `in`.jphe.storyvox.data.source.filter.FilterState
import `in`.jphe.storyvox.feature.api.BrowsePaginator
import `in`.jphe.storyvox.feature.api.BrowseRepositoryUi
import `in`.jphe.storyvox.feature.api.BrowseSource
import `in`.jphe.storyvox.feature.api.MemPalaceFilter
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import `in`.jphe.storyvox.feature.api.UiFiction
import `in`.jphe.storyvox.feature.api.UiGitHubAuthState
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class BrowseTab {
    Popular,
    NewReleases,
    BestRated,
    Search,
    // GitHub auth-only chips (#200/#201/#202).
    MyRepos,
    Starred,
    Gists,
    // AO3 auth-only chips (#426 PR2). Visible only when the user has
    // a captured AO3 session; resolves to the AO3 subscriptions /
    // Marked-for-Later endpoints via [BrowseSource.Ao3MySubscriptions] /
    // [BrowseSource.Ao3MarkedForLater].
    Ao3MySubscriptions,
    Ao3MarkedForLater,
    // Wikipedia browse-only chips (#796). Always visible on the
    // Wikipedia chip strip (no auth gate) — resolve to the On This Day /
    // In the News clusters via [BrowseSource.WikipediaOnThisDay] /
    // [BrowseSource.WikipediaInTheNews].
    WikipediaOnThisDay,
    WikipediaInTheNews,
}

@Immutable
data class BrowseUiState(
    /** Stable plugin id (see `SourceIds`) of the currently selected
     *  source. The Phase 1/2 `BrowseSourceKey` enum was deleted in
     *  Phase 3 (#384) in favour of registry-driven iteration; the chip
     *  picker, tab list, and filter sheet all key off this string now. */
    val sourceId: String = SourceIds.ROYAL_ROAD,
    val tab: BrowseTab = BrowseTab.Popular,
    val query: String = "",
    val items: List<UiFiction> = emptyList(),
    val isLoading: Boolean = true,
    val isAppending: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null,
    val filterState: FilterState = FilterState(),
    val filterDimensions: List<FilterDimension> = emptyList(),
    val palaceFilter: MemPalaceFilter = MemPalaceFilter(),
    val palaceWings: List<String> = emptyList(),
    val githubSignedIn: Boolean = false,
    val hasGitHubRepoScope: Boolean = false,
    val royalRoadSignedIn: Boolean = false,
    /** #426 PR2 — AO3 sign-in flag. Drives the AO3 chip's "My
     *  Subscriptions" / "Marked for Later" tab visibility and the
     *  signed-out CTA copy. */
    val ao3SignedIn: Boolean = false,
    /** Sources the user has enabled in Settings, as a set of stable
     *  plugin ids. */
    val enabledSourceIds: Set<String> = emptySet(),
    /** Ordered list of plugin descriptors visible to the picker (the
     *  enabled subset, in registry display order). Surfaced here so the
     *  chip strip can render without re-injecting the registry.
     *
     *  v0.5.76 — favorites are pinned to the front of this list in
     *  registry order, non-favorites follow in registry order. The
     *  carousel renders this list directly so the visual order matches
     *  the data-layer order without any extra sort in the UI. */
    val visibleSources: List<SourcePluginDescriptor> = emptyList(),
    /** v0.5.76 — ids the user has "starred" from the Browse carousel
     *  long-press sheet. Subset of [enabledSourceIds] in practice
     *  (favoriting a disabled source is allowed but the star only
     *  visibly applies when the source becomes visible again). The
     *  carousel renders a star overlay on cards whose id is in this
     *  set. */
    val favoriteSourceIds: Set<String> = emptySet(),
    /** Issue #443 — true when the Notion source is in anonymous-public
     *  mode (no integration token configured). The Notion listing then
     *  surfaces TechEmpower's public content as a zero-setup demo; the
     *  Browse screen renders a clarifying banner so users understand
     *  what they're seeing isn't "their Notion". */
    val notionAnonymousActive: Boolean = true,
)

/** Typed view of a paginator's five state flows. */
private data class PaginatorView(
    val items: List<UiFiction>,
    val isLoading: Boolean,
    val isAppending: Boolean,
    val hasMore: Boolean,
    val error: String?,
)

/** Bundled view of the user-controllable knobs. */
private data class ControlsView(
    val sourceId: String,
    val tab: BrowseTab,
    val query: String,
    val filterState: FilterState,
    val palaceFilter: MemPalaceFilter,
    val githubSignedIn: Boolean,
    val hasGitHubRepoScope: Boolean,
    val royalRoadSignedIn: Boolean,
    val ao3SignedIn: Boolean,
    val enabledSourceIds: Set<String>,
    val visibleSources: List<SourcePluginDescriptor>,
    val favoriteSourceIds: Set<String>,
)

/**
 * Browse screen ViewModel.
 *
 * Plugin-seam Phase 3 (#384) — the source picker is now driven by
 * `SourcePluginRegistry.descriptors` rather than the (deleted)
 * `BrowseSourceKey` enum. `selectSource` takes a plugin id; the
 * resolver routes by id string.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val repo: BrowseRepositoryUi,
    private val settings: SettingsRepositoryUi,
    private val registry: SourcePluginRegistry,
    // #426 PR2 — observe AO3 session state directly off AuthRepository
    // (rather than threading it through UiSettings) since AO3 sign-in
    // is a strictly per-source concern and the UiSettings sign-in flag
    // is RR-specific by design.
    private val authRepo: AuthRepository,
    // #786 — observe live network state so Browse can surface an offline
    // banner and downgrade the full-screen error to a banner when cached
    // items exist, instead of waiting out the OkHttp socket timeout.
    connectivity: ConnectivityObserver,
) : ViewModel() {

    /** Default selected source — first defaultEnabled plugin, or
     *  [SourceIds.ROYAL_ROAD] as a sentinel when nothing is enabled. */
    private val defaultSourceId: String =
        registry.descriptors.firstOrNull { it.defaultEnabled }?.id ?: SourceIds.ROYAL_ROAD

    private val _sourceId = MutableStateFlow(defaultSourceId)
    private val _tab = MutableStateFlow(BrowseTab.Popular)
    private val _query = MutableStateFlow("")
    private val _filterState = MutableStateFlow(FilterState())
    private val _palaceFilter = MutableStateFlow(MemPalaceFilter())
    private val _palaceWings = MutableStateFlow<List<String>>(emptyList())
    private var palaceWingsLoaded = false
    val query: StateFlow<String> = _query.asStateFlow()

    private val githubSignedIn: StateFlow<Boolean> = settings.settings
        .map { it.github is UiGitHubAuthState.SignedIn }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Issue #656 — v1.0 blocker. RR sign-in projection now reads from
     * the cross-source [AuthRepository.sessionState] flow as the
     * **primary** signal, OR-combined with the legacy DataStore
     * `isSignedIn` flag for back-compat with older installs.
     *
     * Why the union (not just the new path):
     *   - The legacy `pref_signed_in` DataStore key was the *only*
     *     RR-signedness signal pre-#426; existing installs that signed
     *     in before #509 / #521 have the flag set but never wrote a
     *     row into the per-source state map until they re-launched.
     *   - Conversely, [AuthRepository.init] hydrates the
     *     `cookie:royalroad` entry into `sessionState(ROYAL_ROAD)` on
     *     every cold start, so any install that has a captured cookie
     *     surfaces here as `Authenticated` even if the DataStore flag
     *     somehow drifted (one historical drift path: DataStore got
     *     reset by an aborted migration while EncryptedSharedPreferences
     *     kept the cookie).
     *
     * Pre-#656 this projection read **only** the DataStore flag. That
     * meant a perfectly valid signed-in session — cookie on disk,
     * OkHttp jar hydrated, `sessionState(ROYAL_ROAD)` = Authenticated —
     * could still render the "Sign in to Royal Road" CTA on Browse if
     * the DataStore key was missing. Cross-checking both stores closes
     * the gap and matches the [ao3SignedIn] pattern below (which reads
     * exclusively off [AuthRepository], since AO3 never touched
     * `pref_signed_in`).
     *
     * Resolver order: signed-in if **either** signal is true. The
     * resolver still consults this single boolean — only the input
     * shape changed. CTA copy / Settings sign-out flow are unchanged.
     */
    private val royalRoadSignedIn: StateFlow<Boolean> = combine(
        settings.settings.map { it.isSignedIn },
        authRepo.sessionState(SourceIds.ROYAL_ROAD).map { it is SessionState.Authenticated },
    ) { dsFlag, repoFlag -> dsFlag || repoFlag }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** #426 PR2 — AO3 sign-in flag from the cross-source session-state
     *  map. Drives the AO3 chip's "My Subscriptions" / "Marked for Later"
     *  tab visibility. */
    private val ao3SignedIn: StateFlow<Boolean> = authRepo
        .sessionState(SourceIds.AO3)
        .map { it is SessionState.Authenticated }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Issue #443 — anonymous-public Notion mode is active when no
     *  integration token is configured (the source falls back to the
     *  TechEmpower public Notion content). Surfaces a demo banner on
     *  the Browse → Notion chip so users understand what they're
     *  seeing. */
    private val notionAnonymousActive: StateFlow<Boolean> = settings.settings
        .map { !it.notionTokenConfigured }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private val hasGitHubRepoScope: kotlinx.coroutines.flow.Flow<Boolean> =
        settings.settings
            .map { s ->
                val auth = s.github
                auth is UiGitHubAuthState.SignedIn &&
                    auth.scopes.split(' ').any { it == "repo" }
            }
            .distinctUntilChanged()

    private data class AuthSnapshot(
        val githubSignedIn: Boolean,
        val hasGitHubRepoScope: Boolean,
        val royalRoadSignedIn: Boolean,
        // #426 PR2 — AO3 session flag carried alongside RR / GitHub so
        // the same auth tuple feeds both the resolver (gating
        // [Ao3MySubscriptions] tab routing) and the controls flow
        // (gating chip visibility).
        val ao3SignedIn: Boolean,
    )

    private val authSnapshot: kotlinx.coroutines.flow.Flow<AuthSnapshot> =
        combine(githubSignedIn, hasGitHubRepoScope, royalRoadSignedIn, ao3SignedIn) { gh, repo, rr, ao3 ->
            AuthSnapshot(gh, repo, rr, ao3)
        }.distinctUntilChanged()

    /**
     * Plugin-seam Phase 3 (#384) — enabled-set projection straight off
     * the registry-driven `sourcePluginsEnabled` map. The Phase 1/2
     * version collected 17 hand-rolled per-source boolean projections;
     * Phase 3 reads them by id from one map, with `defaultEnabled`
     * fallback for ids that haven't been written yet.
     */
    private val enabledSourceIds: StateFlow<Set<String>> =
        settings.settings
            .map { s ->
                buildSet {
                    for (descriptor in registry.descriptors) {
                        val explicit = s.sourcePluginsEnabled[descriptor.id]
                        val effective = explicit ?: descriptor.defaultEnabled
                        if (effective) add(descriptor.id)
                    }
                }
            }
            .distinctUntilChanged()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                registry.descriptors.filter { it.defaultEnabled }.map { it.id }.toSet(),
            )

    /** v0.5.76 — the user's "starred" sources, surfaced to the carousel
     *  as the membership signal for the corner star overlay AND as the
     *  re-ordering input for `visibleSources` below. */
    private val favoriteSourceIds: StateFlow<Set<String>> =
        settings.settings
            .map { it.favoriteSourceIds }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** User's custom display order for the Browse carousel. Empty list
     *  means "use the default order" (favourites-first, then registry
     *  order). When non-empty, sources are displayed in this order. */
    private val sourceDisplayOrder: StateFlow<List<String>> =
        settings.settings
            .map { it.sourceDisplayOrder }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val paginator: StateFlow<BrowsePaginator?> = run {
        val baseTuple = combine(
            _sourceId,
            _tab,
            _query.debounce(300),
            _filterState,
        ) { sourceId, tab, q, fs ->
            ResolveTuple(sourceId, tab, q, fs)
        }
        val authForResolve = combine(githubSignedIn, royalRoadSignedIn, ao3SignedIn) { gh, rr, ao3 ->
            Triple(gh, rr, ao3)
        }
        combine(baseTuple, _palaceFilter, authForResolve) { tup, pf, auth ->
            resolveSource(
                sourceId = tup.sourceId,
                tab = tup.tab,
                q = tup.q,
                filterState = tup.filterState,
                palaceFilter = pf,
                githubSignedIn = auth.first,
                royalRoadSignedIn = auth.second,
                ao3SignedIn = auth.third,
            )?.let { source -> source to tup.sourceId }
        }
            .distinctUntilChanged()
            .map { pair -> pair?.let { (source, sourceId) -> repo.paginator(source, sourceId) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    }

    init {
        viewModelScope.launch {
            paginator.collectLatest { p -> p?.loadNext() }
        }
        viewModelScope.launch {
            githubSignedIn.collectLatest { signedIn ->
                if (!signedIn && _tab.value in AUTH_ONLY_GH_TABS) {
                    _tab.value = BrowseTab.Popular
                }
            }
        }
        // #426 PR2 — AO3 sign-out tear-down. Mirror of the GitHub
        // branch above: if the user signs out of AO3 while the
        // Subscriptions / Marked-for-Later chip is active, fall
        // back to Popular so the chip strip doesn't render a tab
        // that resolveSource will null out (which would leave the
        // user staring at an empty Browse).
        viewModelScope.launch {
            ao3SignedIn.collectLatest { signedIn ->
                if (!signedIn && _tab.value in AUTH_ONLY_AO3_TABS) {
                    _tab.value = BrowseTab.Popular
                }
            }
        }
        viewModelScope.launch {
            enabledSourceIds.collectLatest { enabled ->
                if (_sourceId.value !in enabled) {
                    _sourceId.value = enabled.firstOrNull() ?: defaultSourceId
                }
            }
        }
    }

    private val controls: kotlinx.coroutines.flow.Flow<ControlsView> = run {
        val baseTuple = combine(
            _sourceId, _tab, _query, _filterState,
        ) { sourceId, tab, q, fs ->
            ResolveTuple(sourceId, tab, q, fs)
        }
        val enabledFavoritesOrder = combine(
            enabledSourceIds,
            favoriteSourceIds,
            sourceDisplayOrder,
        ) { e, f, o -> Triple(e, f, o) }
        combine(
            baseTuple,
            _palaceFilter,
            authSnapshot,
            enabledFavoritesOrder,
        ) { tup, pf, auth, efo ->
            val (enabled, favorites, customOrder) = efo
            val visible = run {
                val enabledDescriptors = registry.descriptors.filter { it.id in enabled }
                if (customOrder.isNotEmpty()) {
                    // User has a custom drag-and-drop order — honour it.
                    // Sources in the custom order come first (in that order),
                    // followed by any enabled sources not yet in the list
                    // (newly enabled since the last reorder).
                    val byId = enabledDescriptors.associateBy { it.id }
                    // #938 — dedupe customOrder before lookup: sync conflicts
                    // or hand-edited JSON can land duplicate ids in the list,
                    // which would otherwise render the same source card twice.
                    val ordered = customOrder.distinct().mapNotNull { byId[it] }
                    val remaining = enabledDescriptors.filter { it.id !in customOrder }
                    ordered + remaining
                } else {
                    // Default: favourites-first, then the rest in registry order.
                    val faves = mutableListOf<SourcePluginDescriptor>()
                    val rest = mutableListOf<SourcePluginDescriptor>()
                    for (d in enabledDescriptors) {
                        if (d.id in favorites) faves.add(d) else rest.add(d)
                    }
                    faves + rest
                }
            }
            ControlsView(
                sourceId = tup.sourceId,
                tab = tup.tab,
                query = tup.q,
                filterState = tup.filterState,
                palaceFilter = pf,
                githubSignedIn = auth.githubSignedIn,
                hasGitHubRepoScope = auth.hasGitHubRepoScope,
                royalRoadSignedIn = auth.royalRoadSignedIn,
                ao3SignedIn = auth.ao3SignedIn,
                enabledSourceIds = enabled,
                visibleSources = visible,
                favoriteSourceIds = favorites,
            )
        }
    }

    val uiState: StateFlow<BrowseUiState> = paginator.flatMapLatest { p ->
        if (p == null) {
            combine(controls, _palaceWings, notionAnonymousActive) { c, wings, notionAnon ->
                val dims = registry.byId(c.sourceId)?.source?.filterDimensions().orEmpty()
                BrowseUiState(
                    sourceId = c.sourceId,
                    tab = c.tab,
                    query = c.query,
                    items = emptyList(),
                    isLoading = false,
                    isAppending = false,
                    hasMore = false,
                    error = null,
                    filterState = c.filterState,
                    filterDimensions = dims,
                    palaceFilter = c.palaceFilter,
                    palaceWings = wings,
                    githubSignedIn = c.githubSignedIn,
                    hasGitHubRepoScope = c.hasGitHubRepoScope,
                    royalRoadSignedIn = c.royalRoadSignedIn,
                    ao3SignedIn = c.ao3SignedIn,
                    enabledSourceIds = c.enabledSourceIds,
                    visibleSources = c.visibleSources,
                    favoriteSourceIds = c.favoriteSourceIds,
                    notionAnonymousActive = notionAnon,
                )
            }
        } else {
            val paginatorView = combine(
                p.items,
                p.isLoading,
                p.isAppending,
                p.hasMore,
                p.error,
            ) { items, loading, appending, more, error ->
                PaginatorView(items, loading, appending, more, error)
            }
            combine(paginatorView, controls, _palaceWings, notionAnonymousActive) { view, c, wings, notionAnon ->
                val dims = registry.byId(c.sourceId)?.source?.filterDimensions().orEmpty()
                BrowseUiState(
                    sourceId = c.sourceId,
                    tab = c.tab,
                    query = c.query,
                    items = view.items,
                    isLoading = view.isLoading,
                    isAppending = view.isAppending,
                    hasMore = view.hasMore,
                    error = view.error,
                    filterState = c.filterState,
                    filterDimensions = dims,
                    palaceFilter = c.palaceFilter,
                    palaceWings = wings,
                    githubSignedIn = c.githubSignedIn,
                    hasGitHubRepoScope = c.hasGitHubRepoScope,
                    royalRoadSignedIn = c.royalRoadSignedIn,
                    ao3SignedIn = c.ao3SignedIn,
                    enabledSourceIds = c.enabledSourceIds,
                    visibleSources = c.visibleSources,
                    favoriteSourceIds = c.favoriteSourceIds,
                    notionAnonymousActive = notionAnon,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowseUiState(sourceId = defaultSourceId))

    /** Select a source by stable plugin id (see `SourceIds`). */
    fun selectSource(id: String) {
        if (_sourceId.value == id) return
        _sourceId.value = id
        // Issue #695 — descriptor-driven supportsSearch so Slack /
        // Telegram (which declare `supportsSearch = false`) don't end up
        // with an active Search tab after the user picks them. Missing
        // descriptor (out-of-tree id) defaults to true to preserve the
        // old fallthrough behavior.
        val supportsSearch = registry.descriptors.firstOrNull { it.id == id }
            ?.supportsSearch ?: true
        val supported = BrowseSourceUi.supportedTabs(
            id,
            githubSignedIn = githubSignedIn.value,
            ao3SignedIn = ao3SignedIn.value,
            supportsSearch = supportsSearch,
        )
        if (_tab.value !in supported) {
            _tab.value = BrowseTab.Popular
        }
        _query.value = ""
        _filterState.value = FilterState()
        _palaceFilter.value = MemPalaceFilter()
        if (id == SourceIds.MEMPALACE) {
            ensurePalaceWingsLoaded()
        }
    }

    private fun ensurePalaceWingsLoaded() {
        if (palaceWingsLoaded) return
        palaceWingsLoaded = true
        viewModelScope.launch {
            val wings = runCatching { repo.genres(SourceIds.MEMPALACE) }
                .getOrDefault(emptyList())
            if (wings.isEmpty()) palaceWingsLoaded = false
            _palaceWings.value = wings
        }
    }

    /**
     * v0.5.76 — toggle a source plugin's favorite star from the Browse
     * carousel long-press sheet. The persisted set is updated through
     * the settings repo; the next [BrowseUiState] emission re-sorts
     * [BrowseUiState.visibleSources] so the just-starred card slides to
     * the front of the row.
     *
     * Note: favoriting a *disabled* source is allowed and persists, but
     * the card stays hidden in the carousel until the source is
     * re-enabled. This is intentional — the long-press sheet can star
     * a card before disabling it, and re-enabling later restores both
     * the visibility and the star without a second tap.
     */
    fun toggleFavorite(id: String) {
        val currentFavorite = id in (uiState.value.favoriteSourceIds)
        viewModelScope.launch {
            settings.setSourceFavorite(id, favorite = !currentFavorite)
        }
    }

    /**
     * Persist a new display order for the Browse carousel. Called after
     * the user completes a drag-and-drop reorder gesture. The list
     * contains the stable plugin ids in the user's chosen order. An
     * empty list reverts to the default favourites-first ordering.
     */
    fun reorderSources(newOrder: List<String>) {
        viewModelScope.launch {
            settings.setSourceDisplayOrder(newOrder)
        }
    }

    /**
     * v0.5.76 — enable/disable a source plugin from the Browse carousel
     * long-press sheet. Twin entry point to the Plugin Manager
     * Settings row — same persistence layer, just reachable from the
     * gesture that surfaced the question.
     */
    fun setSourceEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            settings.setSourcePluginEnabled(id, enabled)
        }
    }

    fun selectTab(tab: BrowseTab) { _tab.value = tab }
    fun setQuery(q: String) { _query.value = q }
    fun setFilterState(state: FilterState) { _filterState.value = state }
    fun resetFilterState() { _filterState.value = FilterState() }
    fun setPalaceFilter(filter: MemPalaceFilter) { _palaceFilter.value = filter }
    fun resetPalaceFilter() { _palaceFilter.value = MemPalaceFilter() }

    fun loadMore() {
        viewModelScope.launch { paginator.value?.loadNext() }
    }

    // #776 — pull-to-refresh state. Distinct from the paginator's own
    // [BrowseUiState.isLoading] flag (which drives the skeleton grid on
    // first page fetch) — `isRefreshing` overlays the M3 spinner on top
    // of the existing items so the user keeps seeing what they had while
    // a fresh page-1 fetch lands underneath. Cleared when `loadNext`
    // returns regardless of success/failure, so a network blip during
    // refresh still drops the indicator and surfaces the cached items
    // banner via [BrowseUiState.error] like any other refresh failure.
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // #786 — sibling flow to [uiState] (kept separate so the existing
    // paginator combine stays untouched). The screen renders OfflineBanner
    // above the grid when true and downgrades the first-load error block to
    // the banner if cached items are present.
    val isOffline: StateFlow<Boolean> = connectivity.state
        .map { !it.isOnline }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** #776 — pull-to-refresh entry point. Resets the current paginator
     *  to page 1 then immediately re-fetches; the existing
     *  source-tuple-change `LaunchedEffect` would only fire on tab/query
     *  changes, so refresh has to push `loadNext()` itself. No-op when
     *  no paginator is active (e.g. Search tab with empty query). */
    fun refresh() {
        val p = paginator.value ?: return
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                p.refresh()
                p.loadNext()
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    // ─── Local EPUB folder picker (#669) ─────────────────────────────────
    //
    // Browse → Local chip empty-state CTA writes the picked SAF tree URI
    // straight through here so the user can start importing local books
    // without navigating to Settings → EPUB folder. The corresponding
    // Settings row continues to exist for "I want to change my folder
    // later" — this entry point is for first-run discoverability.
    val epubFolderUri: StateFlow<String?> =
        settings.epubFolderUri
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setEpubFolderUri(uri: String) = viewModelScope.launch {
        settings.setEpubFolderUri(uri)
    }

    // ─── RSS feed management (#247) ─────────────────────────────────────

    val rssSubscriptions: StateFlow<List<String>> =
        settings.rssSubscriptions
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val suggestedRssFeeds: StateFlow<List<`in`.jphe.storyvox.feature.api.SuggestedFeed>> =
        settings.suggestedRssFeeds
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addRssFeed(url: String) {
        viewModelScope.launch { settings.addRssFeed(url) }
    }

    fun removeRssFeedByUrl(url: String) {
        viewModelScope.launch { settings.removeRssFeedByUrl(url) }
    }
}

private val AUTH_ONLY_GH_TABS: Set<BrowseTab> = setOf(BrowseTab.MyRepos, BrowseTab.Starred, BrowseTab.Gists)

/** #426 PR2 — AO3 auth-only tabs (mirror of [AUTH_ONLY_GH_TABS]). */
private val AUTH_ONLY_AO3_TABS: Set<BrowseTab> = setOf(
    BrowseTab.Ao3MySubscriptions,
    BrowseTab.Ao3MarkedForLater,
)

private data class ResolveTuple(
    val sourceId: String,
    val tab: BrowseTab,
    val q: String,
    val filterState: FilterState,
)

/**
 * Unified resolver. Auth-gated tabs (GitHub, AO3) keep their targeted
 * branches; MemPalace wing selection routes through [BrowseSource.ByGenre].
 * Everything else: when [filterState] is active, route through
 * [BrowseSource.Filtered]; otherwise fall through to the tab default.
 */
private fun resolveSource(
    sourceId: String,
    tab: BrowseTab,
    q: String,
    filterState: FilterState,
    palaceFilter: MemPalaceFilter,
    githubSignedIn: Boolean,
    royalRoadSignedIn: Boolean,
    ao3SignedIn: Boolean,
): BrowseSource? = when (sourceId) {
    SourceIds.GITHUB -> when {
        tab == BrowseTab.MyRepos -> if (githubSignedIn) BrowseSource.GitHubMyRepos else null
        tab == BrowseTab.Starred -> if (githubSignedIn) BrowseSource.GitHubStarred else null
        tab == BrowseTab.Gists -> if (githubSignedIn) BrowseSource.GitHubGists else null
        else -> resolveDefault(tab, q, filterState)
    }
    SourceIds.ROYAL_ROAD -> when {
        tab == BrowseTab.Search -> if (q.isBlank()) null else resolveDefault(tab, q, filterState)
        !royalRoadSignedIn -> null
        else -> resolveDefault(tab, q, filterState)
    }
    SourceIds.MEMPALACE -> when {
        palaceFilter.wing != null -> BrowseSource.ByGenre(palaceFilter.wing)
        tab == BrowseTab.Popular -> BrowseSource.Popular
        tab == BrowseTab.NewReleases -> BrowseSource.NewReleases
        else -> null
    }
    SourceIds.AO3 -> when (tab) {
        BrowseTab.Ao3MySubscriptions ->
            if (ao3SignedIn) BrowseSource.Ao3MySubscriptions else null
        BrowseTab.Ao3MarkedForLater ->
            if (ao3SignedIn) BrowseSource.Ao3MarkedForLater else null
        else -> resolveDefault(tab, q, filterState)
    }
    SourceIds.KVMR -> when (tab) {
        BrowseTab.Popular -> BrowseSource.Popular
        else -> null
    }
    SourceIds.WIKIPEDIA -> when (tab) {
        // #796 — the On This Day / In the News clusters route to their
        // dedicated WikipediaBrowseSource surfaces rather than through
        // the Filtered/search path. The configured Wikipedia language
        // is read by the source from WikipediaConfig (Settings), not the
        // Browse filter, so no filter threading is needed here.
        BrowseTab.WikipediaOnThisDay -> BrowseSource.WikipediaOnThisDay
        BrowseTab.WikipediaInTheNews -> BrowseSource.WikipediaInTheNews
        else -> resolveDefault(tab, q, filterState)
    }
    else -> resolveDefault(tab, q, filterState)
}

private fun resolveDefault(
    tab: BrowseTab,
    q: String,
    filterState: FilterState,
): BrowseSource? = when {
    filterState.isActive() -> BrowseSource.Filtered(
        query = if (tab == BrowseTab.Search) q else "",
        state = filterState,
    )
    tab == BrowseTab.Popular -> BrowseSource.Popular
    tab == BrowseTab.NewReleases -> BrowseSource.NewReleases
    tab == BrowseTab.BestRated -> BrowseSource.BestRated
    tab == BrowseTab.Search -> if (q.isBlank()) null else BrowseSource.Search(q)
    else -> null
}
