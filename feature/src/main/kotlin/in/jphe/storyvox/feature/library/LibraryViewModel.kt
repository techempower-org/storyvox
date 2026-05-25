package `in`.jphe.storyvox.feature.library

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.data.db.entity.InboxEvent
import `in`.jphe.storyvox.data.db.entity.Shelf
import `in`.jphe.storyvox.data.repository.ContinueListeningEntry
import `in`.jphe.storyvox.data.repository.FictionRepository
import `in`.jphe.storyvox.data.repository.HistoryEntry
import `in`.jphe.storyvox.data.repository.HistoryRepository
import `in`.jphe.storyvox.data.repository.InboxRepository
import `in`.jphe.storyvox.data.repository.PlaybackPositionRepository
import `in`.jphe.storyvox.data.repository.ShelfRepository
import `in`.jphe.storyvox.data.repository.playback.PlaybackResumePolicyConfig
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.feature.api.DownloadMode
import `in`.jphe.storyvox.feature.api.FictionRepositoryUi
import `in`.jphe.storyvox.feature.api.PlaybackControllerUi
import `in`.jphe.storyvox.feature.api.UiAddByUrlResult
import `in`.jphe.storyvox.feature.api.UiRouteCandidate
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Issue #158 — top-level sub-tabs inside the Library screen. Order matters:
 * the enum's `ordinal` drives [SecondaryTabRow]'s `selectedTabIndex`.
 *
 *  - [All] mirrors the pre-#158 Library grid (Resume card + alphabetical
 *    library grid) — the user's full collection. Chip row from #116 is
 *    visible here to let the user drill into Read/Wishlist shelves
 *    without leaving the tab.
 *  - [Reading] uses the #116 [Shelf.Reading] filter under the hood; on
 *    select we coerce [ShelfFilter] to `OneShelf(Reading)` so the grid
 *    swaps without the chip row needing to show.
 *  - [History] is the chronological chapter-open feed.
 */
/**
 * Issue #158 — top-level Library sub-tabs.
 *
 * **#438 collapse** — v0.5.36 shipped four tabs (`All / Reading / Inbox /
 * History`) stacked directly above a four-chip shelf row (`All / Reading
 * / Read / Wishlist`), so the same strings (`All`, `Reading`) appeared in
 * two adjacent nested navigation surfaces — a Material 3 anti-pattern.
 * Collapsed to three tabs (Library / Inbox / History) at the time.
 *
 * **Restructure (v0.5.40)** — JP directive: "put follows and browse into
 * the library tab." The bottom nav drops to two destinations (Library +
 * Settings), and the Library tab becomes the umbrella for everything
 * book-related. Sub-tab order — left-to-right reading flow:
 *
 *  - [Library] — the user's own books (the existing shelf grid with the
 *    chip-row #116 filter). First and default so a fresh launch lands
 *    on what the user owns, not on a discovery surface.
 *  - [Browse]  — the existing standalone Browse surface, embedded.
 *    Source picker (RR / GitHub / Outline / etc.) + Popular / Search /
 *    New Releases / Best Rated sub-tabs render inside the Library body.
 *  - [Follows] — the existing standalone Follows surface, embedded.
 *  - [Inbox]   — chronological cross-source notification feed (#383).
 *  - [History] — chronological chapter-open feed (#158).
 *
 * Five tabs forces [SecondaryScrollableTabRow] (vs the fixed
 * SecondaryTabRow used pre-restructure) because the labels do not fit
 * the Flip3 portrait width when laid out evenly. Scrollable is the
 * pattern already used by BrowseScreen's own narrow-viewport tab row,
 * so this stays inside the existing visual vocabulary.
 */
enum class LibraryTab(val label: String) {
    Library("Library"),
    /**
     * Restructure (v0.5.40) — Follows folded under Library. The embedded
     * FollowsScreen body renders here sans its own TopAppBar (the Library
     * TopAppBar serves both).
     *
     * v0.5.72 note — Browse was previously a Library sub-tab too but was
     * promoted to a first-class bottom-nav destination in v0.5.72. Follows
     * stays embedded because it's tightly coupled to "your library" (per-
     * user, signed-in scope) while Browse is the cross-source discovery
     * surface that earned its own dock pill.
     */
    Follows("Follows"),
    /**
     * Issue #383 — chronological cross-source notification feed.
     * Carries a numeric badge driven by unread events.
     */
    Inbox("Inbox"),
    History("History"),
}

/**
 * Issue #116 — which row of the chip-filter strip is selected. `All` is
 * the default (current behaviour pre-shelves); the three [Shelf] options
 * filter the grid to fictions that sit on that shelf. Surfaced as a
 * chip row under the [LibraryTab.Library] tab — #438 dropped the
 * pre-existing `LibraryTab.Reading` shortcut tab so this chip row is
 * the canonical "Reading shelf" affordance.
 */
@Immutable
sealed interface ShelfFilter {
    data object All : ShelfFilter
    data class OneShelf(val shelf: Shelf) : ShelfFilter
}

@Immutable
data class LibraryUiState(
    /** Topmost continue-listening entry — null until the user has played anything. */
    val resume: ContinueListeningEntry? = null,
    val fictions: List<FictionSummary> = emptyList(),
    /** Issue #158 — chronological history feed, most-recent-open first. */
    val history: List<HistoryEntry> = emptyList(),
    /** Issue #383 — cross-source Inbox feed, most-recent first. */
    val inbox: List<InboxEvent> = emptyList(),
    /** Issue #383 — unread-event count driving the Inbox tab badge. */
    val inboxUnreadCount: Int = 0,
    /** Selected sub-tab (#158). */
    val tab: LibraryTab = LibraryTab.Library,
    /** Active chip filter (#116) — only meaningful while tab == All. */
    val filter: ShelfFilter = ShelfFilter.All,
    val isLoading: Boolean = true,
)

/**
 * Long-press → manage-shelves bottom-sheet state. Tracks the fiction being
 * managed plus the live set of shelves it currently sits on (kept in sync
 * with the DB so toggling a switch updates immediately).
 */
@Immutable
sealed interface ManageShelvesSheetState {
    data object Hidden : ManageShelvesSheetState
    data class Open(
        val fictionId: String,
        val fictionTitle: String,
        val memberOf: Set<Shelf>,
    ) : ManageShelvesSheetState
}

sealed interface LibraryUiEvent {
    data class OpenFiction(val fictionId: String) : LibraryUiEvent
    data class OpenReader(val fictionId: String, val chapterId: String) : LibraryUiEvent
    /**
     * Issue #383 — Inbox row tap. Carries a fully-resolved deep-link URI
     * string (`storyvox://reader/<fid>/<cid>` or `storyvox://fiction/<fid>`).
     * The host activity decodes the URI and routes — same shape as the
     * existing Reader / Fiction events, just opaque so the Inbox can
     * point at surfaces that don't exist today (live audio player,
     * source-specific detail screens).
     */
    data class OpenInboxLink(val deepLinkUri: String) : LibraryUiEvent
}

/** State for the paste-URL bottom sheet. */
@Immutable
sealed interface AddByUrlSheetState {
    /** Sheet hidden. */
    data object Hidden : AddByUrlSheetState

    /** Sheet shown, accepting input. [error] non-null after a failed submission. */
    data class Open(val error: String? = null) : AddByUrlSheetState

    /** Submission in flight (network call to the source). */
    data object Submitting : AddByUrlSheetState

    /**
     * Issue #700 — magic-link resolver returned several
     * chooser-eligible candidates for the pasted [url]. Sheet shows a
     * picker so the user selects which backend to route to; tapping a
     * row re-submits via [LibraryViewModel.chooseSource] with the
     * picked sourceId. Cancel (back / dismiss) returns the sheet to
     * [Open] with the original input so the user can edit and retry.
     */
    data class ChooseSource(
        val url: String,
        val candidates: List<UiRouteCandidate>,
    ) : AddByUrlSheetState
}

@HiltViewModel
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryViewModel @Inject constructor(
    fictionRepo: FictionRepository,
    positionRepo: PlaybackPositionRepository,
    private val shelfRepo: ShelfRepository,
    /** Issue #158 — reading history backing the new "History" sub-tab. */
    historyRepo: HistoryRepository,
    /** Issue #383 — cross-source Inbox feed + unread count. */
    private val inboxRepo: InboxRepository,
    private val uiRepo: FictionRepositoryUi,
    private val playback: PlaybackControllerUi,
    /** #90 — read the user's last play/pause intent to decide whether
     *  the Resume CTA should auto-start playback. */
    private val resumePolicy: PlaybackResumePolicyConfig,
) : ViewModel() {

    private val _events = Channel<LibraryUiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val _addByUrlState = MutableStateFlow<AddByUrlSheetState>(AddByUrlSheetState.Hidden)
    val addByUrlState: StateFlow<AddByUrlSheetState> = _addByUrlState.asStateFlow()

    private val _filter = MutableStateFlow<ShelfFilter>(ShelfFilter.All)

    /**
     * Selected sub-tab. Hoisted out of the combined `uiState` flow so a
     * tab-switch doesn't have to wait for the library/resume/history
     * flows to all emit — pressing the tab feels instant.
     */
    private val _tab = MutableStateFlow(LibraryTab.Library)

    private val _manageShelves = MutableStateFlow<ManageShelvesSheetState>(ManageShelvesSheetState.Hidden)
    val manageShelvesState: StateFlow<ManageShelvesSheetState> = _manageShelves.asStateFlow()

    /**
     * The fiction list flow swaps between the full library and a
     * shelf-scoped flow depending on the active filter. flatMapLatest
     * cancels the previous subscription so flipping chips doesn't pile
     * up Room flows.
     */
    private val fictionsFlow = _filter.flatMapLatest { f ->
        when (f) {
            ShelfFilter.All -> fictionRepo.observeLibrary()
            is ShelfFilter.OneShelf -> shelfRepo.observeByShelf(f.shelf)
        }
    }

    /**
     * Issue #383 — Inbox feed + unread count + filter merged into a
     * single tab-related snapshot so the outer combine stays inside
     * the 5-arg overload. Mirrors the `NonPrefsConfigs` pattern in
     * [SettingsRepositoryUiImpl] — roll multiple deps into one
     * product type, combine once outside.
     */
    private data class TabSnapshot(
        val inboxEvents: List<InboxEvent>,
        val inboxUnreadCount: Int,
        val tab: LibraryTab,
        val filter: ShelfFilter,
    )

    private val tabSnapshot: kotlinx.coroutines.flow.Flow<TabSnapshot> =
        combine(
            inboxRepo.observeAll(),
            inboxRepo.observeUnreadCount(),
            _tab,
            _filter,
        ) { events, count, tab, filter ->
            TabSnapshot(events, count, tab, filter)
        }

    val uiState: StateFlow<LibraryUiState> = combine(
        fictionsFlow,
        positionRepo.observeMostRecentContinueListening(),
        historyRepo.observeAll(),
        tabSnapshot,
    ) { library, resume, history, snap ->
        LibraryUiState(
            resume = resume,
            fictions = library,
            history = history,
            inbox = snap.inboxEvents,
            inboxUnreadCount = snap.inboxUnreadCount,
            tab = snap.tab,
            filter = snap.filter,
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    // ─── sub-tabs (#158) ──────────────────────────────────────────────────

    /**
     * Tab switch. #438 collapsed the four-tab strip to three; the shelf
     * filter is the chip row under [LibraryTab.Library] now. We don't
     * touch [_filter] on tab switch — the user's last chip choice stays
     * remembered when they bounce out to Inbox/History and back.
     */
    fun selectTab(tab: LibraryTab) {
        _tab.value = tab
        // Follows renders its own embedded surface (own ViewModel via
        // hiltViewModel), so the selectTab handler has nothing source-
        // specific to coerce. Each branch is a documented no-op for
        // grep symmetry.
        when (tab) {
            LibraryTab.Library -> { /* chip-row filter persists across tab switches */ }
            LibraryTab.Follows -> { /* FollowsScreen owns its own state */ }
            LibraryTab.History -> { /* history feed renders from state.history */ }
            LibraryTab.Inbox -> { /* inbox feed renders from state.inbox */ }
        }
    }

    /**
     * Issue #383 — Inbox row tap. Marks the event read (so the badge
     * count decrements immediately) and emits a deep-link nav event
     * for the host activity to decode. If the event has no
     * deepLinkUri (rare — source-wide events with no good landing
     * page) we still mark it read; the tap was the acknowledgement.
     */
    fun openInboxEvent(event: InboxEvent) {
        viewModelScope.launch {
            inboxRepo.markRead(event.id)
            val link = event.deepLinkUri ?: return@launch
            _events.send(LibraryUiEvent.OpenInboxLink(link))
        }
    }

    /** Issue #383 — "Mark all read" action in the Inbox top affordance. */
    fun markAllInboxRead() {
        viewModelScope.launch { inboxRepo.markAllRead() }
    }

    /**
     * Issue #158 — History row tap. Navigates to the reader at that
     * (fictionId, chapterId). We deliberately *don't* call
     * `playback.startListening` here: the user might be browsing history
     * to find context, not to start playing right now. Reader opens the
     * chapter view; if they want audio they tap play from there.
     */
    fun openHistoryEntry(entry: HistoryEntry) {
        viewModelScope.launch {
            _events.send(LibraryUiEvent.OpenReader(entry.fictionId, entry.chapterId))
        }
    }

    // ─── chips (#116) ─────────────────────────────────────────────────────

    /** Top-of-screen chip row taps (only visible on Tab.All). */
    fun selectFilter(filter: ShelfFilter) {
        _filter.value = filter
    }

    // ─── manage-shelves bottom sheet (#116) ───────────────────────────────

    /** Long-press on a library card → open the manage-shelves sheet. */
    fun openManageShelves(fiction: FictionSummary) {
        viewModelScope.launch {
            val current = shelfRepo.shelvesForFiction(fiction.id)
            _manageShelves.value = ManageShelvesSheetState.Open(
                fictionId = fiction.id,
                fictionTitle = fiction.title,
                memberOf = current,
            )
        }
    }

    fun dismissManageShelves() {
        _manageShelves.value = ManageShelvesSheetState.Hidden
    }

    /** Flip a single shelf for the currently-managed fiction. */
    fun toggleShelf(fictionId: String, shelf: Shelf) {
        viewModelScope.launch {
            shelfRepo.toggle(fictionId, shelf)
            val sheet = _manageShelves.value
            if (sheet is ManageShelvesSheetState.Open && sheet.fictionId == fictionId) {
                _manageShelves.value = sheet.copy(
                    memberOf = shelfRepo.shelvesForFiction(fictionId),
                )
            }
        }
    }

    /**
     * Issue #828 — long-press → "Remove from library" affordance on the
     * manage-shelves sheet. Routes through [FictionRepositoryUi.follow]
     * with `follow=false`, the same plumbing the #169 FictionDetail
     * destructive flow uses, so the underlying tear-down (library row,
     * download cancellation, FictionLibraryListener fan-out) stays
     * single-sourced. Caller is responsible for confirming first; this
     * is the un-gated terminal action.
     *
     * Dismisses the sheet on completion so the user lands back on the
     * library grid with the now-absent row.
     */
    fun removeFromLibrary(fictionId: String) {
        viewModelScope.launch {
            uiRepo.follow(fictionId, follow = false)
            _manageShelves.value = ManageShelvesSheetState.Hidden
        }
    }

    // ─── existing surface (unchanged behaviour) ───────────────────────────

    fun openFiction(id: String) {
        viewModelScope.launch { _events.send(LibraryUiEvent.OpenFiction(id)) }
    }

    fun resume() {
        val entry = uiState.value.resume ?: return
        viewModelScope.launch {
            // #90 smart-resume — read the user's last play/pause
            // intent. If they explicitly paused before, we still load
            // the chapter (so the player tab is ready) but don't
            // auto-start audio. App-killed-mid-playback (no explicit
            // pause) leaves the flag at its prior `true` so the
            // common "phone died, want to keep listening" case
            // auto-resumes as before.
            val autoPlay = resumePolicy.currentLastWasPlaying()
            playback.startListening(
                entry.fiction.id,
                entry.chapter.id,
                entry.charOffset,
                autoPlay = autoPlay,
            )
            _events.send(LibraryUiEvent.OpenReader(entry.fiction.id, entry.chapter.id))
        }
    }

    fun setDownloadMode(fictionId: String, mode: DownloadMode) {
        viewModelScope.launch { uiRepo.setDownloadMode(fictionId, mode) }
    }

    fun showAddByUrl() {
        _addByUrlState.value = AddByUrlSheetState.Open()
    }

    fun dismissAddByUrl() {
        _addByUrlState.value = AddByUrlSheetState.Hidden
    }

    /**
     * Issue #700 — user picked a backend from the multi-match chooser.
     * Re-submits the original URL with [sourceId] as the preferred
     * source so [FictionRepository.addByUrl] bypasses the resolver and
     * routes straight to that backend. A no-op if the sheet isn't in
     * [AddByUrlSheetState.ChooseSource] (defensive — the picker can
     * only fire while that state is live, but recompositions during
     * dismissal can race).
     */
    fun chooseSource(sourceId: String) {
        val current = _addByUrlState.value as? AddByUrlSheetState.ChooseSource ?: return
        submitAddByUrl(current.url, preferredSourceId = sourceId)
    }

    /**
     * Issue #700 — back-out of the chooser without picking. Returns the
     * sheet to the editable [AddByUrlSheetState.Open] state so the user
     * can amend the URL and resubmit; a no-op when the sheet isn't
     * showing the chooser.
     */
    fun cancelChooseSource() {
        if (_addByUrlState.value is AddByUrlSheetState.ChooseSource) {
            _addByUrlState.value = AddByUrlSheetState.Open()
        }
    }

    fun submitAddByUrl(url: String, preferredSourceId: String? = null) {
        if (_addByUrlState.value === AddByUrlSheetState.Submitting) return
        // Issue #584 — reject non-http(s) schemes before hitting the
        // resolver. The pre-fix path queued anything (including
        // `file:///etc/passwd`, `javascript:alert(1)`, `content://`,
        // `data:`, `intent://`) with no client-side feedback; the
        // user saw a partial sheet state and silent acceptance. The
        // allowlist also strips leading/trailing whitespace before
        // the check (a trailing space made `'file:///etc/passwd '`
        // slip through the helper text's "supported" hint).
        val trimmed = url.trim()
        if (!isLikelyAddByUrl(trimmed)) {
            _addByUrlState.value = AddByUrlSheetState.Open(
                error = "Only http:// and https:// URLs are supported.",
            )
            return
        }
        _addByUrlState.value = AddByUrlSheetState.Submitting
        viewModelScope.launch {
            when (val result = uiRepo.addByUrl(trimmed, preferredSourceId)) {
                is UiAddByUrlResult.Success -> {
                    _addByUrlState.value = AddByUrlSheetState.Hidden
                    _events.send(LibraryUiEvent.OpenFiction(result.fictionId))
                }
                UiAddByUrlResult.UnrecognizedUrl -> {
                    _addByUrlState.value = AddByUrlSheetState.Open(
                        error = "That doesn't look like a URL. Paste an http(s):// link.",
                    )
                }
                is UiAddByUrlResult.UnsupportedSource -> {
                    _addByUrlState.value = AddByUrlSheetState.Open(
                        error = "${result.sourceId.replaceFirstChar { it.uppercase() }} support is coming soon.",
                    )
                }
                is UiAddByUrlResult.Error -> {
                    _addByUrlState.value = AddByUrlSheetState.Open(
                        error = result.message.ifBlank { "Could not load that fiction. Try again." },
                    )
                }
                is UiAddByUrlResult.MultipleMatches -> {
                    // Issue #700 — surface the chooser UI rather than
                    // silently auto-picking. The resolver already
                    // dominance-collapses obvious wins
                    // (FictionRepositoryImpl.maybeMultipleMatchesOr);
                    // anything that reaches here is genuinely
                    // ambiguous and the user gets to decide. An empty
                    // candidate list shouldn't normally arrive — the
                    // repository only emits MultipleMatches when there
                    // are ≥2 — but we still degrade gracefully.
                    if (result.candidates.isEmpty()) {
                        _addByUrlState.value = AddByUrlSheetState.Open(
                            error = "Could not pick a backend for that URL.",
                        )
                    } else {
                        _addByUrlState.value = AddByUrlSheetState.ChooseSource(
                            url = trimmed,
                            candidates = result.candidates,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Issue #584 — scheme allowlist for the Add-by-URL flow. Only
 * `http://` and `https://` reach the resolver. Everything else
 * (`file://`, `javascript:`, `content://`, `data:`, `intent://`,
 * bare strings without a scheme) gets a friendly error before any
 * network hit or filesystem touch.
 *
 * Why a single scheme allowlist rather than a regex: the magic-link
 * resolver downstream has its own per-source pattern matching, so we
 * don't want to second-guess shape here. The only invariant the
 * resolver currently relies on is "this is an HTTP(S) URL we can
 * actually fetch", and that's exactly what we pin.
 *
 * Exposed `internal` so a unit test can pin the spec — same pattern
 * as [isLikelyEmail] in the sync auth module.
 */
internal fun isLikelyAddByUrl(raw: String): Boolean {
    val url = raw.trim()
    if (url.isEmpty()) return false
    // Case-insensitive scheme check. RFC 3986 §3.1 says schemes are
    // case-insensitive; users paste both `HTTP://` (browser address
    // bar copy-paste on some platforms) and `http://` interchangeably.
    val lower = url.lowercase()
    if (lower.startsWith("http://") && url.length > "http://".length) return true
    if (lower.startsWith("https://") && url.length > "https://".length) return true
    return false
}
