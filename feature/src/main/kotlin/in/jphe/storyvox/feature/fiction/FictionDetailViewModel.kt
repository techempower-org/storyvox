package `in`.jphe.storyvox.feature.fiction

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.data.db.entity.FictionMemoryEntry
import `in`.jphe.storyvox.data.repository.FictionMemoryRepository
import `in`.jphe.storyvox.data.repository.pronunciation.PronunciationDictRepository
import `in`.jphe.storyvox.feature.api.DownloadMode
import `in`.jphe.storyvox.feature.api.FictionRepositoryUi
import `in`.jphe.storyvox.feature.api.PlaybackControllerUi
import `in`.jphe.storyvox.feature.api.SetFollowedRemoteResult
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import `in`.jphe.storyvox.feature.api.UiChapter
import `in`.jphe.storyvox.feature.api.UiFiction
import `in`.jphe.storyvox.playback.cache.CacheStateInspector
import `in`.jphe.storyvox.playback.cache.ChapterCacheState
import `in`.jphe.storyvox.playback.cache.PcmCache
import `in`.jphe.storyvox.playback.tts.CHUNKER_VERSION
import `in`.jphe.storyvox.playback.voice.VoiceManager
import `in`.jphe.storyvox.source.epub.writer.EpubExportResult
import `in`.jphe.storyvox.source.epub.writer.ExportFictionToEpubUseCase
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class FictionDetailUiState(
    val fiction: UiFiction? = null,
    val chapters: List<UiChapter> = emptyList(),
    val isInLibrary: Boolean = false,
    val downloadMode: DownloadMode = DownloadMode.Lazy,
    val isLoading: Boolean = true,
    /** Set when the first-subscription `refreshDetail` failed and we have
     *  no cached row to show. Cleared on the next successful refresh.
     *  When [fiction] is non-null this is a tail/refresh error — the
     *  screen should keep showing the cached data and surface the error
     *  as a snackbar / banner rather than blocking the page. */
    val error: String? = null,
    /** Issue #117 — true while [ExportFictionToEpubUseCase] is building the
     *  .epub. UI surfaces a "Building .epub…" chip so the user knows their
     *  tap took effect even on a 5000-chapter export. */
    val isExportingEpub: Boolean = false,
    /** Issue #217 — Notebook sub-section. The per-fiction memory entries
     *  the cross-fiction extractor recorded (or the user typed manually
     *  via the Notebook UI's add-note affordance). Empty list when this
     *  book has no recorded entities yet — the UI hides the section
     *  rather than rendering an empty card. */
    val notebookEntries: List<FictionMemoryEntry> = emptyList(),
)

sealed interface FictionDetailUiEvent {
    data class OpenReader(val fictionId: String, val chapterId: String) : FictionDetailUiEvent
    /**
     * Issue #117 — fired when an EPUB export finishes. The screen
     * collects this and surfaces the Share-vs-Save bottom sheet to the
     * user. We pipe the result through an event channel rather than a
     * StateFlow because the share action is one-shot per tap and we
     * don't want it re-firing on configuration change.
     */
    data class EpubExported(val result: EpubExportResult) : FictionDetailUiEvent
    /** Issue #117 — surfaces non-fatal export failures (no network for
     *  the cover, disk write error, etc) so the screen can toast. */
    data class EpubExportFailed(val message: String) : FictionDetailUiEvent
    /** Issue #211 — RR follow tapped while anonymous. Screen routes
     *  the user to the Royal Road sign-in WebView (same destination
     *  Settings → Royal Road and the #241 Browse CTA use). */
    data object OpenRoyalRoadSignIn : FictionDetailUiEvent
    /** Issue #211 — RR returned a non-auth error on the follow POST
     *  (network, CSRF token miss, 500). Surfaced as a toast so the
     *  user knows the tap didn't take. */
    data class FollowFailed(val message: String) : FictionDetailUiEvent
}

@HiltViewModel
class FictionDetailViewModel @Inject constructor(
    private val repo: FictionRepositoryUi,
    private val playback: PlaybackControllerUi,
    private val exportEpub: ExportFictionToEpubUseCase,
    settings: SettingsRepositoryUi,
    /** Issue #217 — cross-fiction memory repo. Backs the Notebook
     *  sub-section showing which characters/places/concepts the AI
     *  has recorded for this book, plus the user's manual notes. */
    private val memoryRepo: FictionMemoryRepository,
    /** PR-H (#86) — sources for the per-chapter cache-state badge:
     *  - [cacheInspector] resolves None/Partial/Complete for each chapter
     *  - [voiceManager] supplies the active voice (cache state is per-voice)
     *  - [pronunciationDict] supplies the dict content-hash that feeds
     *    `PcmCacheKey` (issue #135) — without this the lookup would key
     *    on hash `0` and miss any cache entry rendered after the user
     *    edited their dictionary
     *  - [pcmCache] is the writer surface for "Clear fiction cache"
     *    (per-chapter `deleteAllForChapter` sweep)
     */
    private val cacheInspector: CacheStateInspector,
    private val voiceManager: VoiceManager,
    private val pronunciationDict: PronunciationDictRepository,
    private val pcmCache: PcmCache,
    savedState: SavedStateHandle,
) : ViewModel() {

    /** Issue #211 — Royal Road sign-in projection. Read at button-tap
     *  time to decide whether to call the source push or route the
     *  user to AUTH_WEBVIEW. StateFlow (not cold Flow) so the
     *  imperative .value read in [toggleFollowOnSource] is correct. */
    private val royalRoadSignedIn: StateFlow<Boolean> = settings.settings
        .map { it.isSignedIn }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val fictionId: String = checkNotNull(savedState["fictionId"]) {
        "FictionDetailScreen requires a `fictionId` nav arg"
    }

    private val _events = Channel<FictionDetailUiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** Issue #117 — export-in-flight flag. Exposed via [FictionDetailUiState.isExportingEpub]
     *  through the combine below. Held outside the combine so the suspend
     *  call site can flip it cleanly around the use-case invocation. */
    private val isExporting = MutableStateFlow(false)

    /** PR-H (#86) — cache-state nudge channel. The view-model recomputes
     *  per-chapter cache states whenever (chapters, active voice,
     *  dict-hash) change — but "Clear fiction cache" also needs to
     *  trigger a recompute even though none of those inputs flipped
     *  (the filesystem changed under the inspector's nose). Bumping
     *  this counter via `value++` re-fires the cache-state flow on
     *  demand without rebuilding the upstream chapter list. */
    private val cacheStateNudge = MutableStateFlow(0)

    val uiState: StateFlow<FictionDetailUiState> = run {
        // Issue #217 — Notebook entries surface in the detail page's
        // mid-section. The combine ceiling is 5 args, so we nest:
        // outer combine merges the core detail flow with the notebook
        // feed AND the per-chapter cache-state map (PR-H #86).
        //
        // PR-H (#86) — cache-state flow is built from (chapter list,
        // active voice, dict hash, nudge counter). The inspector call
        // is a suspend `withContext(Dispatchers.IO)` doing a few
        // File.exists per chapter — cheap enough to re-run on every
        // upstream change. flow's first emission carries the
        // resolved Map; downstream UI maps it onto each UiChapter.
        val base = combine(
            repo.fictionById(fictionId),
            repo.chaptersFor(fictionId),
            repo.observeIsInLibrary(fictionId),
            repo.fictionLoadError(fictionId),
            isExporting,
        ) { fiction, chapters, inLibrary, error, exporting ->
            FictionDetailUiState(
                fiction = fiction,
                chapters = chapters,
                isInLibrary = inLibrary,
                // Stop showing the spinner once we either have a cached row
                // OR the refresh has failed — otherwise a Cloudflare/network
                // error leaves the user on a permanent spinner with no signal.
                isLoading = fiction == null && error == null,
                error = error,
                isExportingEpub = exporting,
            )
        }
        val cacheStates: kotlinx.coroutines.flow.Flow<Map<String, ChapterCacheState>> = combine(
            repo.chaptersFor(fictionId),
            voiceManager.activeVoice,
            pronunciationDict.dict,
            cacheStateNudge,
        ) { chapters, activeVoice, dict, _ ->
            if (chapters.isEmpty() || activeVoice == null) {
                emptyMap()
            } else {
                cacheInspector.chapterStatesFor(
                    chapterIds = chapters.map { it.id },
                    voiceId = activeVoice.id,
                    chunkerVersion = CHUNKER_VERSION,
                    pronunciationDictHash = dict.contentHash,
                )
            }
        }
        combine(
            base,
            memoryRepo.entitiesForFiction(fictionId),
            cacheStates,
        ) { state, notebook, cacheStateMap ->
            // PR-H — fold the cache-state map onto each UiChapter. If
            // the inspector hasn't produced a value for a chapter
            // (empty map on missing voice, or new chapter not yet in
            // the batch) default to None so the row renders without a
            // badge instead of crashing.
            val chaptersWithCache = if (cacheStateMap.isEmpty()) {
                state.chapters
            } else {
                state.chapters.map { ch ->
                    val cs = cacheStateMap[ch.id] ?: ChapterCacheState.None
                    if (ch.cacheState == cs) ch else ch.copy(cacheState = cs)
                }
            }
            state.copy(chapters = chaptersWithCache, notebookEntries = notebook)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FictionDetailUiState())
    }

    /**
     * Issue #217 — record a manual Notebook entry. The Notebook UI's
     * "Add note" affordance routes here. [userEdited]=true so the
     * extractor's next pass on this (fictionId, name) won't overwrite
     * the user's curation.
     */
    fun addNotebookEntry(name: String, summary: String, kind: FictionMemoryEntry.Kind) {
        viewModelScope.launch {
            memoryRepo.recordEntity(
                fictionId = fictionId,
                entityType = kind,
                name = name,
                summary = summary,
                userEdited = true,
            )
        }
    }

    /** Issue #217 — delete a Notebook entry. The UI's per-row "X"
     *  button routes here. Used to remove AI-extracted entries that
     *  the user judges wrong, or to retract a manual note. */
    fun deleteNotebookEntry(name: String) {
        viewModelScope.launch { memoryRepo.deleteEntry(fictionId, name) }
    }

    fun toggleFollow(follow: Boolean) {
        viewModelScope.launch { repo.follow(fictionId, follow) }
    }

    /**
     * Issue #211 — push a follow/unfollow to Royal Road's account
     * (distinct from [toggleFollow], which is local library Add/Remove).
     *
     * Pre-checks sign-in: if the user isn't signed in we route them to
     * the Royal Road sign-in WebView via [FictionDetailUiEvent.OpenRoyalRoadSignIn]
     * rather than calling the source and silently no-op-ing. After a
     * successful sign-in the user lands back here and taps again.
     *
     * Errors that aren't AuthRequired surface as toast events so the
     * tap isn't a black hole; the button's optimistic UI flips when
     * the source confirms via the underlying Fiction.followedRemotely
     * flow.
     */
    fun toggleFollowOnSource() {
        val current = uiState.value.fiction ?: return
        if (!royalRoadSignedIn.value) {
            viewModelScope.launch { _events.send(FictionDetailUiEvent.OpenRoyalRoadSignIn) }
            return
        }
        val newValue = !current.isFollowedRemote
        viewModelScope.launch {
            when (val r = repo.setFollowedRemote(fictionId, newValue)) {
                SetFollowedRemoteResult.Success -> { /* UI flips via the flow */ }
                SetFollowedRemoteResult.AuthRequired -> {
                    // Belt-and-braces: pre-check said signed in but the
                    // session may have expired between then and the
                    // POST. Route to sign-in to refresh.
                    _events.send(FictionDetailUiEvent.OpenRoyalRoadSignIn)
                }
                is SetFollowedRemoteResult.Error -> {
                    _events.send(FictionDetailUiEvent.FollowFailed(r.message))
                }
            }
        }
    }

    fun setMode(mode: DownloadMode) {
        viewModelScope.launch { repo.setDownloadMode(fictionId, mode) }
    }

    /**
     * Issue #117 — build a `.epub` of the current fiction in the cache dir
     * and emit [FictionDetailUiEvent.EpubExported] so the screen can fire
     * the share-sheet / SAF flow. Takes [context] because the use case
     * needs `cacheDir` + `FileProvider` — both Context-bound surfaces.
     *
     * Idempotent: if the user double-taps while a previous export is in
     * flight, we short-circuit the second call. The completed export
     * lands in the cache directory, so re-exporting only matters when
     * the user wants a fresh timestamp (or has read more chapters since
     * the last export).
     */
    fun exportToEpub(context: Context) {
        if (isExporting.value) return
        viewModelScope.launch {
            isExporting.value = true
            try {
                val result = exportEpub.export(context, fictionId)
                _events.send(FictionDetailUiEvent.EpubExported(result))
            } catch (t: Throwable) {
                // The use case throws IllegalStateException for missing
                // rows (impossible from this code path — the detail screen
                // only renders for known fictions) and otherwise lets file
                // / IO exceptions bubble. Surface a friendly message; the
                // VM stays alive for the user to try again.
                _events.send(
                    FictionDetailUiEvent.EpubExportFailed(
                        "Couldn't build .epub: ${t.message ?: t.javaClass.simpleName}",
                    ),
                )
            } finally {
                isExporting.value = false
            }
        }
    }

    /**
     * PR-H (#86) — destructive action surfaced through the
     * FictionDetailScreen overflow menu. Wipes every cache entry whose
     * `meta.json` references one of this fiction's chapter IDs,
     * including in-flight (Partial) entries from PR-D's tee writer or
     * PR-F's worker. The sweep is per-chapter so it cuts across every
     * voice variant + every (speed, pitch, dict-hash) tuple the user
     * has ever played for this fiction — clearing the cache for
     * "Mother of Learning" wipes "Cori at 1.0×", "Cori at 1.25×",
     * "Amy at 1.0×", and the user's-current-dict variant of each.
     *
     * Behaviour when a chapter is actively playing: PR-D's appender
     * resumes writing into a re-created `.pcm` file on the next
     * `appendSentence`. Clean fall-through; no crash, no playback
     * interruption — the cache just rebuilds while the user listens.
     *
     * Bumps [cacheStateNudge] after the sweep so the badge row flips
     * from Complete/Partial back to None on the next frame. Without
     * this nudge the cache-states flow wouldn't re-fire (none of its
     * upstream values changed) and the badges would lie about state
     * until the next voice swap or screen-enter.
     */
    fun clearFictionCache() {
        viewModelScope.launch {
            val chapterIds = uiState.value.chapters.map { it.id }
            for (id in chapterIds) {
                runCatching { pcmCache.deleteAllForChapter(id) }
            }
            cacheStateNudge.value = cacheStateNudge.value + 1
        }
    }

    fun listen(chapterId: String) {
        // Issue #288 — tapping Listen is a stronger intent than tapping
        // 'Add to library'. The user is committing to listen RIGHT NOW.
        // Silently follow the fiction if it isn't already in their
        // library so it survives app restart and the Resume card can
        // surface it on Library. Mirror the gesture-only-add pattern
        // (no confirm dialog) — remove-from-library still requires the
        // explicit AlertDialog from issue #169.
        if (!uiState.value.isInLibrary) {
            viewModelScope.launch { repo.follow(fictionId, true) }
        }
        playback.startListening(fictionId, chapterId)
        viewModelScope.launch { _events.send(FictionDetailUiEvent.OpenReader(fictionId, chapterId)) }
    }
}
