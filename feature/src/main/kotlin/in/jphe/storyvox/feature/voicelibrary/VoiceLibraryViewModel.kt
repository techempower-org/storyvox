package `in`.jphe.storyvox.feature.voicelibrary

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.playback.cache.CacheStateInspector
import `in`.jphe.storyvox.playback.voice.EngineKey
import `in`.jphe.storyvox.playback.voice.EngineType
import `in`.jphe.storyvox.playback.voice.QualityLevel
import `in`.jphe.storyvox.playback.voice.UiVoiceInfo
import `in`.jphe.storyvox.playback.voice.VoiceCatalog
import `in`.jphe.storyvox.playback.voice.VoiceEngineId
import `in`.jphe.storyvox.playback.voice.VoiceFamilyRegistry
import `in`.jphe.storyvox.playback.voice.voiceFamilyId
import `in`.jphe.storyvox.playback.voice.VoiceFavorites
import `in`.jphe.storyvox.playback.voice.VoiceLibraryCollapse
import `in`.jphe.storyvox.playback.voice.VoiceLibrarySection
import `in`.jphe.storyvox.playback.voice.VoiceManager
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class VoiceLibraryUiState(
    /** User-starred voices, surfaced under their own header at the top.
     *  Empty when nothing pinned, in which case the whole section is
     *  hidden by the screen. The internal name "favorites" is preserved
     *  from #89 (so is the DataStore key `voice_favorites_v1`) — only
     *  the user-facing label changed to "Starred" in #106.
     *
     *  The legacy "Featured" header above this section was removed in
     *  #129 — the curated [VoiceCatalog.featuredIds] still feeds the
     *  first-launch [VoicePickerGate], but the Voice Library now lets
     *  those voices appear in their natural Engine → Tier home alongside
     *  every other voice rather than re-pinning them to a top section. */
    val favorites: List<UiVoiceInfo> = emptyList(),
    /** Installed voices grouped first by engine (Piper, then Kokoro)
     *  and then by tier within each engine. Within Piper the tier order
     *  is **ascending** (Low → Medium → High) — the engine choice is the
     *  coarse bucket and users typically want to scroll past lighter
     *  Piper voices on the way to the heavier ones. Within Kokoro the
     *  Studio tier leads (Kokoro-exclusive) followed by the rest. Both
     *  the outer and inner maps are iteration-ordered so the screen can
     *  render straight through. Empty engine and tier groups are
     *  dropped. See [groupByEngineThenTier]. */
    val installedByEngine: Map<VoiceEngine, Map<QualityLevel, List<UiVoiceInfo>>> = emptyMap(),
    /** Available (not-yet-downloaded) voices grouped by engine then
     *  tier. Same ordering contract as [installedByEngine]. */
    val availableByEngine: Map<VoiceEngine, Map<QualityLevel, List<UiVoiceInfo>>> = emptyMap(),
    val favoriteIds: Set<String> = emptySet(),
    val activeVoiceId: String? = null,
    val currentDownload: DownloadingVoice? = null,
    val pendingDelete: UiVoiceInfo? = null,
    val errorMessage: String? = null,
    /** Issue #541 / #548 — per-voice terminal-failure tracking. Keyed by
     *  voice id; presence means the most recent download attempt for
     *  that voice ended in [VoiceManager.DownloadProgress.Failed].
     *  Survives `currentDownload = null` (which is required by the
     *  pre-existing "row goes back to idle" UX) so the row's tile can
     *  render a "Tap to retry · reason" subtitle.
     *
     *  Entries are cleared on retry (the new download attempt clears
     *  the entry before starting), on successful download (Done), or
     *  on explicit user dismissal. No automatic timeout — a stale
     *  failure record next time the user lands on the screen is still
     *  the right UX (it's the most recent ground truth). */
    val failedDownloads: Map<String, FailedDownload> = emptyMap(),
    /** Engine sub-headers currently rendered as **collapsed** (#130).
     *  Derived from [VoiceLibraryCollapse]'s flipped set + per-section
     *  default policy in the ViewModel — the screen only needs to ask
     *  "is this (section, engine) collapsed?" without knowing the
     *  default rules. Empty on first launch for the Installed section
     *  (defaults to expanded); will already include every Available
     *  engine on first paint (Available defaults to collapsed). */
    val collapsedEngines: Set<EngineKey> = emptySet(),
    /** Issue #264 — set of two-letter language codes ("en", "es", "fr"...)
     *  for which at least one voice exists in the unfiltered catalog. The
     *  language-chip strip renders one chip per code. Sorted alphabetically
     *  with English pinned first when present (covers the dominant case for
     *  JP's catalog and matches how the rest of the app surfaces locale
     *  pickers — English-first, alphabetical otherwise). */
    val availableLanguageCodes: List<String> = emptyList(),
    /** Issue #197 — per-voice lexicon-path override map. Keys are
     *  voice IDs; values are absolute paths (or comma-joined paths)
     *  to user-provided `.lexicon` files. Surfaced via the active
     *  row's Advanced expander; empty for voices with no override. */
    val voiceLexiconOverrides: Map<String, String> = emptyMap(),
    /** Issue #198 — per-voice Kokoro phonemizer language override
     *  map. Keys are voice IDs; values are 2-letter language codes
     *  from [`in.jphe.storyvox.playback.KOKORO_PHONEMIZER_LANGS`].
     *  Surfaced only on Kokoro voice rows. */
    val voicePhonemizerLangOverrides: Map<String, String> = emptyMap(),
)

@Immutable
data class DownloadingVoice(
    val voiceId: String,
    /** Null while the download is still resolving / no total known. */
    val progress: Float?,
)

/**
 * Issue #541 / #548 — per-voice terminal-failure record. Persists
 * after the download flow terminates so the row's tile can render
 * a "Tap to retry · $reason" subtitle. `lastProgress` is the last
 * determinate progress fraction seen on the failing attempt (0.0..1.0,
 * null for resolving / indeterminate) so the subtitle can show
 * "stopped at 37 %" for context.
 *
 * `reason` carries the upstream [VoiceManager.DownloadProgress.Failed.reason]
 * directly. For HTTP errors that's "HTTP 404 fetching $url"; for IO
 * timeouts it's the IOException message; for unknown-voiceId
 * misconfigurations it's "Unknown voiceId: $id". The screen surfaces
 * this verbatim so the user can hand it to support if they need to.
 */
@Immutable
data class FailedDownload(
    val voiceId: String,
    val reason: String,
    val lastProgress: Float?,
)

@OptIn(FlowPreview::class)
@HiltViewModel
class VoiceLibraryViewModel @Inject constructor(
    private val voiceManager: VoiceManager,
    private val voiceFavorites: VoiceFavorites,
    private val voiceLibraryCollapse: VoiceLibraryCollapse,
    /** PR-4 (#183) — Azure rows in the picker are tappable iff a key
     *  is configured. We observe the settings flow's `azure.isConfigured`
     *  bit and project Azure entries from `availableVoices` into the
     *  installed bucket when true, so the existing activate path Just
     *  Works. The actual key + region stay encrypted in
     *  [AzureCredentials]; this VM only needs the boolean. */
    private val settingsRepo: SettingsRepositoryUi,
    /** #501 — voice-family registry; the Voice Library filters voices
     *  whose family is disabled in `voiceFamiliesEnabled`. The registry
     *  carries each family's `defaultEnabled` so an absent key falls
     *  through to the right default. */
    private val voiceFamilyRegistry: VoiceFamilyRegistry,
    /** PR-H (#86) — per-voice cached-bytes lookup. Polled once per
     *  [installedVoices] emission (cheap: one directory walk + N meta
     *  JSON parses). The resulting Map<voiceId, Long> is folded into
     *  each [UiVoiceInfo] via [UiVoiceInfo.copy(cachedBytes=...)] so
     *  the screen's "X MB cached" subtitle reflects current cache
     *  state. No live re-poll — cache changes between screen visits
     *  show up on the next entry. */
    private val cacheInspector: CacheStateInspector,
) : ViewModel() {

    private val _currentDownload = MutableStateFlow<DownloadingVoice?>(null)
    private val _pendingDelete = MutableStateFlow<UiVoiceInfo?>(null)
    private val _error = MutableStateFlow<String?>(null)
    /** Issue #541 / #548 — per-voice last-failure store. Map keys are
     *  voice ids; values carry the upstream Failed reason plus the
     *  last-seen progress fraction so the row can render
     *  "Tap to retry · timeout at 37 %" subtitle copy. Cleared per-voice
     *  on retry / successful download / explicit dismiss. */
    private val _failedDownloads = MutableStateFlow<Map<String, FailedDownload>>(emptyMap())

    // Issue #264 — search + language filter state lives in the VM so it
    // survives rotation / process death and so the filter pipeline runs
    // off the main thread (the unfiltered installed list is 1188 rows).
    //
    // `_query` is the raw, instantly-updating value bound two-way to the
    // OutlinedTextField — the screen reads this for the field's `value`
    // so the user's keypresses paint immediately. The debounced
    // projection feeds the filter pipeline; debounced at 200 ms so
    // typing "english" doesn't recompute the filter five times in a row
    // on a slow scroll. JP's spec ask: 200 ms (#264).
    private val _query = MutableStateFlow("")
    /** Raw search query — bound two-way to the search field. Reflects
     *  every keypress instantly. The filter pipeline runs off the
     *  debounced projection below; this flow is only the field's bind
     *  target. */
    val voiceFilterQuery: StateFlow<String> = _query.asStateFlow()
    private val _selectedLanguage = MutableStateFlow<String?>(null)
    /** Selected two-letter language code (`en`, `es`, ...), or null when
     *  no language chip is selected. */
    val voiceFilterLanguage: StateFlow<String?> = _selectedLanguage.asStateFlow()

    private val azureKeyConfigured = settingsRepo.settings.map { it.azure.isConfigured }

    /** Issue #541 — pack azure-configured bit + failed-download map
     *  into one inner-combine slot so the existing 5-slot inner
     *  combine stays at 5. Adding failedDownloads as its own slot
     *  would push past the typed-combine ceiling. */
    private val azureAndFailures: kotlinx.coroutines.flow.Flow<AzureAndFailures> =
        combine(azureKeyConfigured, _failedDownloads.asStateFlow()) { azure, failed ->
            AzureAndFailures(azureConfigured = azure, failedDownloads = failed)
        }

    /** Issue #197 / #198 — observe just the two per-voice override maps
     *  so a flip surfaces in the Voice Library's Advanced expander
     *  without a screen relaunch. Pulled out as its own projection
     *  rather than collapsed into the unfiltered-state combine because
     *  the rest of the screen doesn't depend on these values; isolating
     *  the flow keeps Compose's recomposition scope tight on rows that
     *  AREN'T the active voice. */
    private val perVoiceOverrides = settingsRepo.settings.map { s ->
        PerVoiceOverrides(
            lexicon = s.voiceLexiconOverrides,
            phonemizerLang = s.voicePhonemizerLangOverrides,
            // #501 — voice-family on/off keyed by family id
            // (`voice_piper`, `voice_kokoro`, ...). When a family is
            // OFF its voices are filtered out of every bucket here so
            // the picker / library never surfaces them.
            voiceFamiliesEnabled = s.voiceFamiliesEnabled,
        )
    }

    /** PR-H (#86) — per-voice cached-bytes map. Side-band StateFlow so
     *  it folds into the existing 4-arg [uiState] combine without
     *  pushing the unfiltered-state inner combine past its 5-slot
     *  ceiling. Recomputed in an [init] block coroutine on every
     *  [installedVoices] emission — cheap (one directory walk + N
     *  meta JSON parses; single-digit ms even at 5 GB cache). Default
     *  empty map means a brand-new install with no cache yet renders
     *  every voice row without the "X MB cached" subtitle. */
    private val cachedBytesByVoice = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** Debounced query for the filter pipeline. 200 ms matches the
     *  spec from #264 — long enough to swallow burst-typing on Flip3
     *  (where finger latency on the inner display already adds ~50 ms),
     *  short enough that "fr" + pause feels instant. `onStart("")` is
     *  required so the very first emission of the pipeline doesn't wait
     *  200 ms for the user to type something — without it, the screen
     *  paints empty for a beat on cold launch. */
    private val debouncedQuery: kotlinx.coroutines.flow.Flow<String> = _query
        .debounce(200)
        .onStart { emit("") }
        .distinctUntilChanged()

    /** Unfiltered UI state — voices grouped by engine/tier, favourites
     *  resolved, collapsed-engine set computed. The filter pipeline runs
     *  on this and reuses the existing grouping; threading filter into
     *  the inner combine would push past the 5-arg combine ceiling. */
    private val unfilteredUiState: kotlinx.coroutines.flow.Flow<VoiceLibraryUiState> = combine(
        voiceManager.installedVoices,
        voiceManager.availableVoicesFlow,
        voiceManager.activeVoice,
        voiceFavorites.favoriteIds,
        // Pack the three local mutable sources + collapse-store flipped
        // set + azure-configured bit into one combined flow so the outer
        // combine fits in 5 slots. The Azure bit lives here rather than
        // as its own outer slot specifically because adding a 6th outer
        // slot would push us past kotlinx.coroutines's typed combine
        // ceiling and force a less-typed Iterable<Flow<*>> path.
        combine(
            _currentDownload.asStateFlow(),
            _pendingDelete.asStateFlow(),
            _error.asStateFlow(),
            voiceLibraryCollapse.flippedKeys,
            azureAndFailures,
        ) { d, p, e, flipped, azureAndFail ->
            CollapsedAndLocal(
                download = d,
                pendingDelete = p,
                error = e,
                flipped = flipped,
                azureConfigured = azureAndFail.azureConfigured,
                failedDownloads = azureAndFail.failedDownloads,
            )
        },
    ) { installedFromManager, available, active, favIds, locals ->
        // PR-4: when an Azure key is configured, project all Azure
        // catalog entries into the installed bucket so the existing
        // "tappable iff installed" picker logic activates them. Without
        // a key, Azure rows stay in availableByEngine and the user
        // sees them greyed out as "available but not yet downloaded"
        // — onRowTapped surfaces a friendly "configure key in Settings"
        // message instead of attempting download() (which would throw).
        val installed = if (locals.azureConfigured) {
            installedFromManager + available
                .filter { it.engineType is EngineType.Azure }
                .map { it.copy(isInstalled = true) }
        } else {
            installedFromManager
        }
        val installedIds = installed.mapTo(mutableSetOf()) { it.id }
        // Favourites pulls from installed first (preserving the
        // installed-flag) and falls back to the catalog for voices the
        // user pinned but hasn't downloaded yet. Listed favourites are
        // EXCLUDED from the per-tier sections below to avoid showing the
        // same row twice. (#129 removed the parallel exclusion for the
        // legacy Featured set — those voices now flow into their natural
        // Engine → Tier section like any other catalog entry.)
        val favorites = favIds.mapNotNull { id ->
            installed.firstOrNull { it.id == id }
                ?: available.firstOrNull { it.id == id }
        }
        val installedFiltered = installed.filterNot { it.id in favIds }
        val availableFiltered = available.filterNot {
            it.id in installedIds || it.id in favIds
        }
        val installedGrouped = installedFiltered.groupByEngineThenTier()
        val availableGrouped = availableFiltered.groupByEngineThenTier()
        // Issue #264 — dynamically derive the language-chip strip from
        // the union of every voice the user can see. The chip list shows
        // a chip per language code that has at least one voice, sorted
        // alphabetically with English pinned first (English is the
        // dominant default; first-place position keeps it under the
        // user's thumb on the Flip3 inner display without scrolling the
        // chip row).
        val allLanguages = (installed + available).asSequence()
            .map { it.primaryLanguageCode() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
            .toList()
        val orderedLanguages = if ("en" in allLanguages) {
            listOf("en") + allLanguages.filter { it != "en" }
        } else {
            allLanguages
        }
        VoiceLibraryUiState(
            favorites = favorites,
            installedByEngine = installedGrouped,
            availableByEngine = availableGrouped,
            favoriteIds = favIds,
            activeVoiceId = active?.id,
            currentDownload = locals.download,
            pendingDelete = locals.pendingDelete,
            errorMessage = locals.error,
            failedDownloads = locals.failedDownloads,
            collapsedEngines = computeCollapsedEngines(
                installedEngines = installedGrouped.keys,
                availableEngines = availableGrouped.keys,
                flipped = locals.flipped,
            ),
            availableLanguageCodes = orderedLanguages,
        )
    }

    /** Filtered, debounced UI state — what the screen actually renders.
     *  Combines the unfiltered state with the debounced query and the
     *  selected language code, applying the AND-semantics filter to the
     *  three voice buckets (favorites, installed, available). When both
     *  query and language are empty the unfiltered state passes through
     *  untouched — same reference, so Compose's `remember` keys stay
     *  stable and the screen avoids spurious re-renders. */
    val uiState: StateFlow<VoiceLibraryUiState> = combine(
        unfilteredUiState,
        debouncedQuery,
        _selectedLanguage.asStateFlow(),
        perVoiceOverrides,
        cachedBytesByVoice,
    ) { state, q, lang, overrides, cachedBytes ->
        // PR-H (#86) — fold cached-bytes onto each UiVoiceInfo FIRST so
        // the "X MB cached" subtitle survives the family filter and
        // text-search filters downstream. Skip the map when the bytes
        // map is empty (cold launch, fresh install) — every
        // UiVoiceInfo already carries `cachedBytes = 0L` per the data-
        // class default, so the no-op fast path is correct.
        val withCachedBytes = if (cachedBytes.isEmpty()) state else state.copy(
            favorites = state.favorites.withCachedBytes(cachedBytes),
            installedByEngine = state.installedByEngine.withCachedBytes(cachedBytes),
            availableByEngine = state.availableByEngine.withCachedBytes(cachedBytes),
        )
        // #501 — voice-family filter. Family ids absent from the
        // settings map fall back to each family's `defaultEnabled` in
        // the registry, so a fresh install (empty map) shows every
        // default-on family's voices. Only voices whose family
        // resolves to OFF are excluded.
        val familyEnabled: (UiVoiceInfo) -> Boolean = { v ->
            val familyId = v.engineType.voiceFamilyId()
            val explicit = overrides.voiceFamiliesEnabled[familyId]
            explicit ?: (voiceFamilyRegistry.byId(familyId)?.defaultEnabled ?: true)
        }
        val familyFiltered = withCachedBytes.copy(
            favorites = withCachedBytes.favorites.filter(familyEnabled),
            installedByEngine = withCachedBytes.installedByEngine.mapValues { (_, tierMap) ->
                tierMap.mapValues { (_, voices) -> voices.filter(familyEnabled) }
                    .filterValues { it.isNotEmpty() }
            }.filterValues { it.isNotEmpty() },
            availableByEngine = withCachedBytes.availableByEngine.mapValues { (_, tierMap) ->
                tierMap.mapValues { (_, voices) -> voices.filter(familyEnabled) }
                    .filterValues { it.isNotEmpty() }
            }.filterValues { it.isNotEmpty() },
        )
        val withOverrides = familyFiltered.copy(
            voiceLexiconOverrides = overrides.lexicon,
            voicePhonemizerLangOverrides = overrides.phonemizerLang,
        )
        if (q.isBlank() && lang == null) return@combine withOverrides
        val crit = VoiceFilterCriteria(query = q, language = lang)
        withOverrides.copy(
            favorites = withOverrides.favorites.filter { it.matchesCriteria(crit) },
            installedByEngine = withOverrides.installedByEngine.filterBy(crit),
            availableByEngine = withOverrides.availableByEngine.filterBy(crit),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VoiceLibraryUiState())

    private var downloadJob: Job? = null

    init {
        // PR-H (#86) — re-poll the inspector on every installedVoices
        // emission so cached-bytes labels track install/delete events.
        // collectLatest cancels an in-flight poll if a new emission
        // races in (e.g. delete + immediate refresh); the directory
        // walk is cheap but the cancellation contract keeps the flow
        // tidy. Failures fall back to the previous value rather than
        // wiping the labels — a transient filesystem hiccup shouldn't
        // erase the user's "X MB cached" line for every voice.
        viewModelScope.launch {
            voiceManager.installedVoices.collect { _ ->
                runCatching { cacheInspector.bytesUsedByEveryVoice() }
                    .onSuccess { cachedBytesByVoice.value = it }
            }
        }
    }

    fun toggleFavorite(voiceId: String) {
        viewModelScope.launch { voiceFavorites.toggle(voiceId) }
    }

    /** Issue #264 — search field two-way bind. The OutlinedTextField
     *  pushes every keypress here; the field re-reads [voiceFilterQuery]
     *  for its `value`. The debounced projection is what reaches the
     *  filter — see [debouncedQuery]. */
    fun setQuery(query: String) {
        _query.value = query
    }

    /** Issue #264 — language chip toggle. Pass the same code that is
     *  currently selected to deselect it (null = no language filter).
     *  AND-combined with the search query in [uiState]. */
    fun setLanguage(code: String?) {
        _selectedLanguage.value = code
    }

    /** Issue #264 — reset both filter dimensions in one call. Useful
     *  for an explicit "Clear filters" affordance and for tests. */
    fun clearFilters() {
        _query.value = ""
        _selectedLanguage.value = null
    }

    /** User tapped an engine sub-header — flip its collapsed state in
     *  the persisted store. The new state propagates to [uiState] via
     *  the [voiceLibraryCollapse.flippedKeys] flow combined into the
     *  main pipeline; the screen re-renders with rows hidden/shown. */
    fun toggleEngineCollapsed(section: VoiceLibrarySection, engine: VoiceEngine) {
        val key = EngineKey(section, engine.toCoreId())
        viewModelScope.launch { voiceLibraryCollapse.toggle(key) }
    }

    fun onRowTapped(voice: UiVoiceInfo) {
        // PR-4 (#183) — Azure voices activate when a key is configured;
        // otherwise the row is greyed out (not isInstalled) and tapping
        // surfaces a "configure key in Settings" pointer rather than
        // hitting download() (which throws by design — VoiceManager
        // rejects Azure download attempts so a future regression in
        // this branch can't quietly start hammering Azure for a
        // missing model).
        if (voice.engineType is EngineType.Azure) {
            if (voice.isInstalled) {
                activate(voice.id)
            } else {
                _error.value =
                    "Configure your Azure Speech key in " +
                    "Settings → Cloud voices to use this voice."
            }
            return
        }
        if (voice.isInstalled) activate(voice.id) else download(voice.id)
    }

    fun activate(voiceId: String) {
        viewModelScope.launch { voiceManager.setActive(voiceId) }
    }

    fun download(voiceId: String) {
        if (_currentDownload.value != null) return
        // Issue #541 — clear any stale failure record for this voice
        // before the new attempt. A successful download below leaves
        // the entry absent; a fresh Failed terminal writes a new
        // record. The row's "Tap to retry · $reason" subtitle
        // disappears the instant the user taps to retry — visual
        // feedback that the retry is in flight.
        _failedDownloads.update { it - voiceId }
        _currentDownload.value = DownloadingVoice(voiceId, progress = null)
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            // Issue #541 — track the last determinate progress fraction
            // so a Failed terminal can report "stopped at X %" in the
            // tile subtitle. Survives the collect lambda's per-emission
            // scope so the terminal branch can read it after the
            // Downloading emissions finish.
            var lastProgress: Float? = null
            try {
                voiceManager.download(voiceId).collect { p ->
                    when (p) {
                        is VoiceManager.DownloadProgress.Resolving -> {
                            _currentDownload.value = DownloadingVoice(voiceId, progress = null)
                        }
                        is VoiceManager.DownloadProgress.Downloading -> {
                            val frac = if (p.totalBytes > 0L) {
                                (p.bytesRead.toFloat() / p.totalBytes).coerceIn(0f, 1f)
                            } else null
                            if (frac != null) lastProgress = frac
                            _currentDownload.value = DownloadingVoice(voiceId, frac)
                        }
                        is VoiceManager.DownloadProgress.Done -> {
                            // Clear any failure entry on success — the
                            // most recent ground truth is "downloaded
                            // fine," and the tile should drop the
                            // retry affordance immediately.
                            _failedDownloads.update { it - voiceId }
                        }
                        is VoiceManager.DownloadProgress.Failed -> {
                            // Issue #541 / #548 — record the failure
                            // PER-VOICE so the row can render
                            // "Tap to retry · $reason" instead of the
                            // pre-fix terminal-but-blank tile. Also
                            // surfaces in the global snackbar via
                            // `_error` so the user gets a transient
                            // toast as well as the persistent row
                            // affordance — belt-and-suspenders.
                            _failedDownloads.update { current ->
                                current + (voiceId to FailedDownload(
                                    voiceId = voiceId,
                                    reason = p.reason,
                                    lastProgress = lastProgress,
                                ))
                            }
                            _error.value = p.reason
                        }
                    }
                }
            } catch (ce: CancellationException) {
                // User-driven cancel via cancelDownload(). Don't surface as
                // an error — the row already disappears via the finally
                // block. Re-throw so structured concurrency unwinds cleanly.
                throw ce
            } catch (t: Throwable) {
                // Issue #541 — exception path mirrors the Failed
                // emission's per-voice tracking. Without this branch a
                // SocketTimeoutException thrown FROM the flow would
                // silently clear _currentDownload in the finally block
                // and leave the user with no clue why the row stopped
                // animating.
                val reason = t.message ?: "Download failed"
                _failedDownloads.update { current ->
                    current + (voiceId to FailedDownload(
                        voiceId = voiceId,
                        reason = reason,
                        lastProgress = lastProgress,
                    ))
                }
                _error.value = reason
            } finally {
                _currentDownload.value = null
            }
        }
    }

    /** Issue #548 — retry a previously-failed voice download. Same
     *  effect as [download] but explicit so the screen's "Tap to retry"
     *  semantic is auditable from the public API. Clears the failure
     *  record before starting (the row's subtitle reverts to
     *  the in-flight progress indicator). */
    fun retryDownload(voiceId: String) {
        // download() already clears the failure record on entry; this
        // wrapper exists so the screen + tests can express "retry"
        // intent at the call site without re-stating the
        // recovery-from-failure path.
        download(voiceId)
    }

    /** Issue #541 — explicitly clear a per-voice failure record without
     *  re-triggering the download. Used by a row's "× Dismiss" action
     *  in case the user wants to drop the retry affordance (e.g. they
     *  hit Settings → cellular-only off and want to deal with the row
     *  later, on a different network). */
    fun dismissFailedDownload(voiceId: String) {
        _failedDownloads.update { it - voiceId }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _currentDownload.value = null
    }

    fun requestDelete(voice: UiVoiceInfo) {
        _pendingDelete.value = voice
    }

    fun cancelDelete() {
        _pendingDelete.value = null
    }

    fun confirmDelete() {
        val voice = _pendingDelete.value ?: return
        _pendingDelete.value = null
        viewModelScope.launch { voiceManager.delete(voice.id) }
    }

    fun dismissError() {
        _error.value = null
    }

    /** Issue #197 — persist a lexicon-file path for a specific voice.
     *  Pass null or empty to clear the override (engine falls back to
     *  its built-in lexicon). The settings impl writes through to the
     *  VoxSherpa bridge static field when [voiceId] matches the
     *  currently active voice. */
    fun setVoiceLexicon(voiceId: String, path: String?) {
        viewModelScope.launch { settingsRepo.setVoiceLexicon(voiceId, path) }
    }

    /** Issue #198 — persist a Kokoro phonemizer language override for
     *  a specific voice. Pass null or empty to clear. The screen
     *  guards visibility on Kokoro voice rows, but the underlying map
     *  accepts any voiceId for symmetry with the lexicon setter. */
    fun setVoicePhonemizerLang(voiceId: String, langCode: String?) {
        viewModelScope.launch { settingsRepo.setVoicePhonemizerLang(voiceId, langCode) }
    }
}

/** Tuple holding just the two per-voice override maps observed off
 *  the settings flow (#197 / #198). Isolated from CollapsedAndLocal
 *  so the unfiltered-uiState combine stays at 5 outer slots — adding
 *  these maps as a 6th would push past kotlinx.coroutines's typed
 *  combine ceiling. */
private data class PerVoiceOverrides(
    val lexicon: Map<String, String>,
    val phonemizerLang: Map<String, String>,
    /** #501 — voice-family on/off map. Empty = every family enabled
     *  by default; an explicit `false` removes the family's voices
     *  from the picker. */
    val voiceFamiliesEnabled: Map<String, Boolean> = emptyMap(),
)

/** Engine grouping discriminator used by the voice library UI. The
 *  underlying [EngineType] is sealed and Kokoro carries a speakerId we
 *  don't want to key on, so we collapse to a tag-only enum here. Order
 *  matters: this is the outer iteration order in [groupByEngineThenTier]
 *  — Piper section first, then Kokoro, then Azure. Azure goes last
 *  intentionally per Solara's spec — Local engines (no cost, no network)
 *  surface above the cloud section, even though Azure's quality tier is
 *  Studio. The visual cue (engine header) matters more than the tier
 *  sort here: users should reach for a free local voice before
 *  considering a paid cloud voice.
 *
 *  Mirrored as [VoiceEngineId] in core-playback for the collapse store
 *  (which lives in core so it can be Hilt-injected without dragging the
 *  feature module). The two enums are kept in lockstep — see
 *  [toCoreId]. */
enum class VoiceEngine { SystemTts, Piper, Kokoro, Kitten, Azure }

internal fun VoiceEngine.toCoreId(): VoiceEngineId = when (this) {
    VoiceEngine.Piper -> VoiceEngineId.Piper
    VoiceEngine.Kokoro -> VoiceEngineId.Kokoro
    // Issue #119 — Kitten section discriminator.
    VoiceEngine.Kitten -> VoiceEngineId.Kitten
    VoiceEngine.Azure -> VoiceEngineId.Azure
    // #676 — System TTS section discriminator.
    VoiceEngine.SystemTts -> VoiceEngineId.SystemTts
}

internal fun VoiceEngineId.toFeatureEngine(): VoiceEngine = when (this) {
    VoiceEngineId.Piper -> VoiceEngine.Piper
    VoiceEngineId.Kokoro -> VoiceEngine.Kokoro
    VoiceEngineId.Kitten -> VoiceEngine.Kitten
    VoiceEngineId.Azure -> VoiceEngine.Azure
    VoiceEngineId.SystemTts -> VoiceEngine.SystemTts
}

private fun UiVoiceInfo.voiceEngine(): VoiceEngine = when (engineType) {
    is EngineType.Piper -> VoiceEngine.Piper
    is EngineType.Kokoro -> VoiceEngine.Kokoro
    // Issue #119 — Kitten branch.
    is EngineType.Kitten -> VoiceEngine.Kitten
    is EngineType.Azure -> VoiceEngine.Azure
    // #676 — System TTS branch.
    is EngineType.SystemTts -> VoiceEngine.SystemTts
}

/** Tuple holding the "local + collapse" flow values that get packed
 *  into a single nested combine slot — the outer combine is capped at
 *  5 sources, so we group these here instead of using positional
 *  destructuring. Named for readability at the call site. */
private data class CollapsedAndLocal(
    val download: DownloadingVoice?,
    val pendingDelete: UiVoiceInfo?,
    val error: String?,
    val flipped: Set<String>,
    /** PR-4 (#183) — projected from `settings.azure.isConfigured`.
     *  Drives whether Azure rows in the picker are tappable. */
    val azureConfigured: Boolean,
    /** Issue #541 / #548 — per-voice last-failure map. Source-of-truth
     *  for the screen's "Tap to retry · $reason" row subtitles. */
    val failedDownloads: Map<String, FailedDownload>,
)

/** Issue #541 — packed inner-combine slot. The outer combine capacity
 *  is 5; packing `azureConfigured` + `failedDownloads` here keeps the
 *  outer at 5 slots without forcing an untyped Iterable<Flow<*>> path. */
private data class AzureAndFailures(
    val azureConfigured: Boolean,
    val failedDownloads: Map<String, FailedDownload>,
)

/** Compute the rendered collapsed-engines set from the persisted
 *  flipped keys + the engines actually present in each section. We
 *  only emit keys for engines the user can see — there's no point
 *  carrying "available:Piper" if the user has installed every Piper
 *  voice. The screen treats `key in collapsedEngines` as the source
 *  of truth for whether to skip emission of tier rows. */
internal fun computeCollapsedEngines(
    installedEngines: Set<VoiceEngine>,
    availableEngines: Set<VoiceEngine>,
    flipped: Set<String>,
): Set<EngineKey> {
    val out = mutableSetOf<EngineKey>()
    for (engine in installedEngines) {
        val key = EngineKey(VoiceLibrarySection.Installed, engine.toCoreId())
        if (VoiceLibraryCollapse.isCollapsed(key, flipped)) out += key
    }
    for (engine in availableEngines) {
        val key = EngineKey(VoiceLibrarySection.Available, engine.toCoreId())
        if (VoiceLibraryCollapse.isCollapsed(key, flipped)) out += key
    }
    return out
}

/** Tier order **within Piper** — ascending (Low → Medium → High). Piper
 *  has no Studio voice today, but if one ever lands it falls below High
 *  (the slot is left out of this list). The ascending sort matches JP's
 *  ask in #94: Piper users tend to start light and scale up, so showing
 *  "Low" first keeps the lighter-weight voices visible without scroll. */
private val PIPER_TIER_ORDER: List<QualityLevel> = listOf(
    QualityLevel.Low,
    QualityLevel.Medium,
    QualityLevel.High,
)

/** Tier order **within Kokoro** — Studio first (the curated peak,
 *  Kokoro-exclusive), then High, then Medium/Low if upstream ever
 *  introduces them. Kokoro voices all share one bundle so tier here is
 *  about quality grade rather than model size; Studio leading is the
 *  point of the section. */
private val KOKORO_TIER_ORDER: List<QualityLevel> = listOf(
    QualityLevel.Studio,
    QualityLevel.High,
    QualityLevel.Medium,
    QualityLevel.Low,
)

/** Tier order **within Azure** — every Azure HD voice we ship is
 *  Studio tier. Other levels are listed for completeness in case the
 *  curated list ever spans more than one tier (e.g. a "good enough"
 *  cheap Neural alongside Dragon HD). */
private val AZURE_TIER_ORDER: List<QualityLevel> = listOf(
    QualityLevel.Studio,
    QualityLevel.High,
    QualityLevel.Medium,
    QualityLevel.Low,
)

/** Issue #119 — Tier order **within Kitten**. All Kitten voices ship at
 *  [QualityLevel.Low] today (the fp16 nano model trades quality for
 *  size). Listing the higher tiers anyway keeps the sort future-proof
 *  against a possible Kitten-mini (80 MB, better quality) variant
 *  landing in a follow-up. */
private val KITTEN_TIER_ORDER: List<QualityLevel> = listOf(
    QualityLevel.High,
    QualityLevel.Medium,
    QualityLevel.Low,
)

/** Issue #676 — Tier order **within System TTS**. Every System TTS
 *  voice ships at [QualityLevel.Medium] today (the framework doesn't
 *  expose a quality grade so the catalog projection plants every entry
 *  in the Medium bucket — see [VoiceCatalog.systemTtsEntriesFromRoster]).
 *  Listing the other tiers anyway keeps the sort robust against a
 *  future "Google Wavenet HD" surfacing as High. */
private val SYSTEM_TTS_TIER_ORDER: List<QualityLevel> = listOf(
    QualityLevel.High,
    QualityLevel.Medium,
    QualityLevel.Low,
)

private fun tierOrderFor(engine: VoiceEngine): List<QualityLevel> = when (engine) {
    VoiceEngine.Piper -> PIPER_TIER_ORDER
    VoiceEngine.Kokoro -> KOKORO_TIER_ORDER
    // Issue #119 — Kitten tier order.
    VoiceEngine.Kitten -> KITTEN_TIER_ORDER
    VoiceEngine.Azure -> AZURE_TIER_ORDER
    // #676 — System TTS tier order.
    VoiceEngine.SystemTts -> SYSTEM_TTS_TIER_ORDER
}

/** Engine display order — Piper section first, Kokoro second, Kitten
 *  third (issue #119 — the smallest local tier sits BELOW Kokoro in the
 *  list so users see the higher-quality local options first; Kitten
 *  surfaces last among the local engines as a "lite alternative for slow
 *  devices" section), Azure last. Drives outer iteration order of
 *  [groupByEngineThenTier]. */
private val ENGINE_DISPLAY_ORDER: List<VoiceEngine> = listOf(
    // #676 — System TTS first: zero-download tier, the natural
    // first-launch + accessibility-default surface. Sight-impaired
    // users + 5-year-olds + casual newcomers see the OS's already-
    // configured voice at the top of the picker before the neural
    // download story even appears.
    VoiceEngine.SystemTts,
    VoiceEngine.Piper,
    VoiceEngine.Kokoro,
    VoiceEngine.Kitten,
    VoiceEngine.Azure,
)

/** Group a list of voices first by [VoiceEngine] then by
 *  [QualityLevel], producing iteration-ordered nested maps the screen
 *  can render straight through. Outer order: Piper → Kokoro. Inner
 *  order: Low→Medium→High for Piper, Studio→High→Medium→Low for
 *  Kokoro (see [PIPER_TIER_ORDER] / [KOKORO_TIER_ORDER] for the why).
 *
 *  Empty engine buckets and empty tier buckets are dropped so the
 *  screen doesn't render hollow headers — a user with only Piper
 *  voices installed never sees a "Kokoro" sub-header, and vice versa.
 *  Within a tier the source list's order is preserved (the catalog
 *  curates a sensible default; re-sorting here would lose that). */
internal fun List<UiVoiceInfo>.groupByEngineThenTier(): Map<VoiceEngine, Map<QualityLevel, List<UiVoiceInfo>>> {
    if (isEmpty()) return emptyMap()
    val byEngine = groupBy { it.voiceEngine() }
    val out = linkedMapOf<VoiceEngine, Map<QualityLevel, List<UiVoiceInfo>>>()
    for (engine in ENGINE_DISPLAY_ORDER) {
        val voicesInEngine = byEngine[engine]?.takeIf { it.isNotEmpty() } ?: continue
        val byTier = voicesInEngine.groupBy { it.qualityLevel }
        val tierMap = linkedMapOf<QualityLevel, List<UiVoiceInfo>>()
        for (tier in tierOrderFor(engine)) {
            byTier[tier]?.takeIf { it.isNotEmpty() }?.let { tierMap[tier] = it }
        }
        if (tierMap.isNotEmpty()) out[engine] = tierMap
    }
    return out
}

/** PR-H (#86) — fold a `Map<voiceId, Long>` of cached-bytes onto each
 *  voice. Returns a new list with `cachedBytes` set per row; voices
 *  not in the map keep their existing value (default 0L for fresh
 *  rows). Pulled out as an extension on `List<UiVoiceInfo>` so the
 *  three-bucket fold in [VoiceLibraryViewModel.uiState] stays
 *  one-liner-per-bucket. */
private fun List<UiVoiceInfo>.withCachedBytes(
    cachedBytes: Map<String, Long>,
): List<UiVoiceInfo> = map { v ->
    val bytes = cachedBytes[v.id] ?: return@map v
    if (v.cachedBytes == bytes) v else v.copy(cachedBytes = bytes)
}

/** PR-H (#86) — same fold for the engine/tier nested map. The inner
 *  list-of-voices is rebuilt only when at least one row carries fresh
 *  bytes; otherwise the original reference is returned so Compose's
 *  remember/key invariants stay stable for unchanged tiers. */
private fun Map<VoiceEngine, Map<QualityLevel, List<UiVoiceInfo>>>.withCachedBytes(
    cachedBytes: Map<String, Long>,
): Map<VoiceEngine, Map<QualityLevel, List<UiVoiceInfo>>> = mapValues { (_, tiers) ->
    tiers.mapValues { (_, voices) -> voices.withCachedBytes(cachedBytes) }
}
