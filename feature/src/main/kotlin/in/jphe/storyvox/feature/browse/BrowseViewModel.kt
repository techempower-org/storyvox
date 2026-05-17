package `in`.jphe.storyvox.feature.browse

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.data.auth.SessionState
import `in`.jphe.storyvox.data.repository.AuthRepository
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.plugin.SourcePluginDescriptor
import `in`.jphe.storyvox.data.source.plugin.SourcePluginRegistry
import `in`.jphe.storyvox.feature.api.BrowseFilter
import `in`.jphe.storyvox.feature.api.BrowsePaginator
import `in`.jphe.storyvox.feature.api.BrowseRepositoryUi
import `in`.jphe.storyvox.feature.api.BrowseSource
import `in`.jphe.storyvox.feature.api.GitHubSearchFilter
import `in`.jphe.storyvox.feature.api.MemPalaceFilter
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import `in`.jphe.storyvox.feature.api.UiFiction
import `in`.jphe.storyvox.feature.api.UiGitHubAuthState
import `in`.jphe.storyvox.feature.api.UiSearchOrder
import `in`.jphe.storyvox.feature.api.UiSortDirection
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
    val filter: BrowseFilter = BrowseFilter(),
    val isFilterActive: Boolean = false,
    val githubFilter: GitHubSearchFilter = GitHubSearchFilter(),
    val isGitHubFilterActive: Boolean = false,
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
     *  chip strip can render without re-injecting the registry. */
    val visibleSources: List<SourcePluginDescriptor> = emptyList(),
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
    val filter: BrowseFilter,
    val githubFilter: GitHubSearchFilter,
    val palaceFilter: MemPalaceFilter,
    val githubSignedIn: Boolean,
    val hasGitHubRepoScope: Boolean,
    val royalRoadSignedIn: Boolean,
    val ao3SignedIn: Boolean,
    val enabledSourceIds: Set<String>,
    val visibleSources: List<SourcePluginDescriptor>,
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
) : ViewModel() {

    /** Default selected source — first defaultEnabled plugin, or
     *  [SourceIds.ROYAL_ROAD] as a sentinel when nothing is enabled. */
    private val defaultSourceId: String =
        registry.descriptors.firstOrNull { it.defaultEnabled }?.id ?: SourceIds.ROYAL_ROAD

    private val _sourceId = MutableStateFlow(defaultSourceId)
    private val _tab = MutableStateFlow(BrowseTab.Popular)
    private val _query = MutableStateFlow("")
    private val _filter = MutableStateFlow(BrowseFilter())
    private val _githubFilter = MutableStateFlow(GitHubSearchFilter())
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

    private val paginator: StateFlow<BrowsePaginator?> = run {
        val baseTuple = combine(
            _sourceId,
            _tab,
            _query.debounce(300),
            _filter,
            _githubFilter,
        ) { sourceId, tab, q, filter, ghFilter ->
            ResolveTuple(sourceId, tab, q, filter, ghFilter)
        }
        // #426 PR2 — ao3SignedIn joins the gating tuple. The 4-arg
        // `combine` overload caps at the (baseTuple, palaceFilter,
        // ghSignedIn, rrSignedIn) shape; fold ao3SignedIn into the
        // RR/GH auth snapshot so the call stays inside the 4-arg
        // ceiling without dropping back to the vararg overload that
        // costs an extra array allocation per emission.
        val authForResolve = combine(githubSignedIn, royalRoadSignedIn, ao3SignedIn) { gh, rr, ao3 ->
            Triple(gh, rr, ao3)
        }
        combine(baseTuple, _palaceFilter, authForResolve) { tup, palaceFilter, auth ->
            resolveSource(
                sourceId = tup.sourceId,
                tab = tup.tab,
                q = tup.q,
                filter = tup.filter,
                githubFilter = tup.ghFilter,
                palaceFilter = palaceFilter,
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
            _sourceId, _tab, _query, _filter, _githubFilter,
        ) { sourceId, tab, q, filter, ghFilter ->
            ResolveTuple(sourceId, tab, q, filter, ghFilter)
        }
        combine(
            baseTuple,
            _palaceFilter,
            authSnapshot,
            enabledSourceIds,
        ) { tup, palaceFilter, auth, enabled ->
            ControlsView(
                sourceId = tup.sourceId,
                tab = tup.tab,
                query = tup.q,
                filter = tup.filter,
                githubFilter = tup.ghFilter,
                palaceFilter = palaceFilter,
                githubSignedIn = auth.githubSignedIn,
                hasGitHubRepoScope = auth.hasGitHubRepoScope,
                royalRoadSignedIn = auth.royalRoadSignedIn,
                ao3SignedIn = auth.ao3SignedIn,
                enabledSourceIds = enabled,
                visibleSources = registry.descriptors.filter { it.id in enabled },
            )
        }
    }

    val uiState: StateFlow<BrowseUiState> = paginator.flatMapLatest { p ->
        if (p == null) {
            combine(controls, _palaceWings, notionAnonymousActive) { c, wings, notionAnon ->
                BrowseUiState(
                    sourceId = c.sourceId,
                    tab = c.tab,
                    query = c.query,
                    items = emptyList(),
                    isLoading = false,
                    isAppending = false,
                    hasMore = false,
                    error = null,
                    filter = c.filter,
                    isFilterActive = c.filter.isActive(),
                    githubFilter = c.githubFilter,
                    isGitHubFilterActive = c.githubFilter.isActive(),
                    palaceFilter = c.palaceFilter,
                    palaceWings = wings,
                    githubSignedIn = c.githubSignedIn,
                    hasGitHubRepoScope = c.hasGitHubRepoScope,
                    royalRoadSignedIn = c.royalRoadSignedIn,
                    ao3SignedIn = c.ao3SignedIn,
                    enabledSourceIds = c.enabledSourceIds,
                    visibleSources = c.visibleSources,
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
                BrowseUiState(
                    sourceId = c.sourceId,
                    tab = c.tab,
                    query = c.query,
                    items = view.items,
                    isLoading = view.isLoading,
                    isAppending = view.isAppending,
                    hasMore = view.hasMore,
                    error = view.error,
                    filter = c.filter,
                    isFilterActive = c.filter.isActive(),
                    githubFilter = c.githubFilter,
                    isGitHubFilterActive = c.githubFilter.isActive(),
                    palaceFilter = c.palaceFilter,
                    palaceWings = wings,
                    githubSignedIn = c.githubSignedIn,
                    hasGitHubRepoScope = c.hasGitHubRepoScope,
                    royalRoadSignedIn = c.royalRoadSignedIn,
                    ao3SignedIn = c.ao3SignedIn,
                    enabledSourceIds = c.enabledSourceIds,
                    visibleSources = c.visibleSources,
                    notionAnonymousActive = notionAnon,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowseUiState(sourceId = defaultSourceId))

    /** Select a source by stable plugin id (see `SourceIds`). */
    fun selectSource(id: String) {
        if (_sourceId.value == id) return
        _sourceId.value = id
        val supported = BrowseSourceUi.supportedTabs(
            id,
            githubSignedIn = githubSignedIn.value,
            ao3SignedIn = ao3SignedIn.value,
        )
        if (_tab.value !in supported) {
            _tab.value = BrowseTab.Popular
        }
        _query.value = ""
        when (BrowseSourceUi.filterShape(id)) {
            FilterShape.RoyalRoad -> {
                _githubFilter.value = GitHubSearchFilter()
                _palaceFilter.value = MemPalaceFilter()
            }
            FilterShape.GitHub -> {
                _filter.value = BrowseFilter()
                _palaceFilter.value = MemPalaceFilter()
            }
            FilterShape.MemPalace -> {
                _filter.value = BrowseFilter()
                _githubFilter.value = GitHubSearchFilter()
                ensurePalaceWingsLoaded()
            }
            FilterShape.None -> {
                _filter.value = BrowseFilter()
                _githubFilter.value = GitHubSearchFilter()
                _palaceFilter.value = MemPalaceFilter()
            }
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

    fun selectTab(tab: BrowseTab) { _tab.value = tab }
    fun setQuery(q: String) { _query.value = q }
    fun setFilter(filter: BrowseFilter) { _filter.value = filter }
    fun resetFilter() { _filter.value = BrowseFilter() }
    fun setGitHubFilter(filter: GitHubSearchFilter) { _githubFilter.value = filter }
    fun resetGitHubFilter() { _githubFilter.value = GitHubSearchFilter() }
    fun setPalaceFilter(filter: MemPalaceFilter) { _palaceFilter.value = filter }
    fun resetPalaceFilter() { _palaceFilter.value = MemPalaceFilter() }

    fun loadMore() {
        viewModelScope.launch { paginator.value?.loadNext() }
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
    val filter: BrowseFilter,
    val ghFilter: GitHubSearchFilter,
)

/**
 * Phase 3 (#384) — id-keyed source resolver. The Phase 1/2 version
 * was an exhaustive `when (BrowseSourceKey)` on 17 enum branches;
 * Phase 3 keys off the stable plugin id, with a "registry default"
 * fall-through for unknown ids (Popular / NewReleases / Search →
 * the default `BrowseSource` shape). Source-specific routing (RR
 * sign-in gate, GitHub auth-only tabs, MemPalace wing routing)
 * stays as targeted branches keyed off the id constants.
 */
private fun resolveSource(
    sourceId: String,
    tab: BrowseTab,
    q: String,
    filter: BrowseFilter,
    githubFilter: GitHubSearchFilter,
    palaceFilter: MemPalaceFilter,
    githubSignedIn: Boolean,
    royalRoadSignedIn: Boolean,
    ao3SignedIn: Boolean,
): BrowseSource? = when (sourceId) {
    SourceIds.GITHUB -> when {
        tab == BrowseTab.MyRepos -> if (githubSignedIn) BrowseSource.GitHubMyRepos else null
        tab == BrowseTab.Starred -> if (githubSignedIn) BrowseSource.GitHubStarred else null
        tab == BrowseTab.Gists -> if (githubSignedIn) BrowseSource.GitHubGists else null
        githubFilter.isActive() -> BrowseSource.FilteredGitHub(
            query = if (tab == BrowseTab.Search) q else "",
            filter = githubFilter,
        )
        tab == BrowseTab.Popular -> BrowseSource.Popular
        tab == BrowseTab.NewReleases -> BrowseSource.NewReleases
        tab == BrowseTab.Search -> if (q.isBlank()) null else BrowseSource.Search(q)
        else -> null
    }
    SourceIds.ROYAL_ROAD -> when {
        tab == BrowseTab.Search -> if (q.isBlank()) null else BrowseSource.Search(q)
        !royalRoadSignedIn -> null
        filter.isActive() -> BrowseSource.Filtered(
            if (tab == BrowseTab.Search && q.isNotBlank()) filter.copy(term = q) else filter,
        )
        tab == BrowseTab.Popular -> BrowseSource.Popular
        tab == BrowseTab.NewReleases -> BrowseSource.NewReleases
        tab == BrowseTab.BestRated -> BrowseSource.BestRated
        else -> null
    }
    SourceIds.MEMPALACE -> when {
        palaceFilter.wing != null -> BrowseSource.ByGenre(palaceFilter.wing)
        tab == BrowseTab.Popular -> BrowseSource.Popular
        tab == BrowseTab.NewReleases -> BrowseSource.NewReleases
        else -> null
    }
    SourceIds.RSS -> when {
        tab == BrowseTab.Search -> if (q.isBlank()) BrowseSource.NewReleases else BrowseSource.Search(q)
        tab == BrowseTab.Popular -> BrowseSource.Popular
        tab == BrowseTab.NewReleases -> BrowseSource.NewReleases
        else -> null
    }
    SourceIds.EPUB, SourceIds.OUTLINE -> when {
        tab == BrowseTab.Search -> if (q.isBlank()) BrowseSource.Popular else BrowseSource.Search(q)
        tab == BrowseTab.Popular -> BrowseSource.Popular
        else -> null
    }
    SourceIds.AO3 -> when (tab) {
        BrowseTab.Popular -> BrowseSource.Popular
        BrowseTab.NewReleases -> BrowseSource.NewReleases
        BrowseTab.Search -> if (q.isBlank()) null else BrowseSource.Search(q)
        // #426 PR2 — AO3 auth-gated tabs. Resolver returns null when
        // not signed in (BrowseSourceUi.supportedTabs already gates
        // chip visibility behind ao3SignedIn; the redundant gate here
        // is defensive against a stale persisted tab selection).
        BrowseTab.Ao3MySubscriptions ->
            if (ao3SignedIn) BrowseSource.Ao3MySubscriptions else null
        BrowseTab.Ao3MarkedForLater ->
            if (ao3SignedIn) BrowseSource.Ao3MarkedForLater else null
        else -> null
    }
    // Issue #417 — Radio source. Popular surfaces the curated + starred
    // list; Search hits Radio Browser. KVMR alias stays Popular-only
    // for v0.5.20+ persisted shortcuts that still route through it.
    SourceIds.RADIO -> when (tab) {
        BrowseTab.Popular -> BrowseSource.Popular
        BrowseTab.Search -> if (q.isBlank()) null else BrowseSource.Search(q)
        else -> null
    }
    SourceIds.KVMR -> when (tab) {
        BrowseTab.Popular -> BrowseSource.Popular
        else -> null
    }
    // Default Popular/NewReleases/Search shape — Gutenberg, Standard
    // Ebooks, Wikipedia, Wikisource, Notion, Hacker News, arXiv, PLOS,
    // Discord all use this resolver.
    else -> when (tab) {
        BrowseTab.Popular -> BrowseSource.Popular
        BrowseTab.NewReleases -> BrowseSource.NewReleases
        BrowseTab.Search -> if (q.isBlank()) null else BrowseSource.Search(q)
        else -> null
    }
}

private fun BrowseFilter.isActive(): Boolean =
    tagsInclude.isNotEmpty() ||
        tagsExclude.isNotEmpty() ||
        statuses.isNotEmpty() ||
        warningsRequire.isNotEmpty() ||
        warningsExclude.isNotEmpty() ||
        type != `in`.jphe.storyvox.feature.api.UiFictionType.All ||
        minPages != null || maxPages != null ||
        minRating != null || maxRating != null ||
        orderBy != UiSearchOrder.Popularity ||
        direction != UiSortDirection.Desc
