package `in`.jphe.storyvox.feature.fiction

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.data.annotation.AnnotationExportFormatter
import `in`.jphe.storyvox.data.annotation.AnnotationExportResult
import `in`.jphe.storyvox.data.annotation.ExportAnnotationsUseCase
import `in`.jphe.storyvox.data.db.entity.Annotation
import `in`.jphe.storyvox.data.db.entity.FictionMemoryEntry
import `in`.jphe.storyvox.data.repository.AnnotationRepository
import `in`.jphe.storyvox.data.repository.FictionMemoryRepository
import `in`.jphe.storyvox.data.repository.pronunciation.PronunciationDictRepository
import `in`.jphe.storyvox.feature.api.DownloadMode
import `in`.jphe.storyvox.feature.api.FictionRepositoryUi
import `in`.jphe.storyvox.feature.api.PlaybackControllerUi
import `in`.jphe.storyvox.feature.api.SetFollowedRemoteResult
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import `in`.jphe.storyvox.feature.api.UiChapter
import `in`.jphe.storyvox.feature.api.UiFiction
import `in`.jphe.storyvox.feature.api.UiRecapPlaybackState
import `in`.jphe.storyvox.playback.audiobook.AudiobookExportScheduler
import `in`.jphe.storyvox.playback.audiobook.AudiobookExportStatus
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
import kotlinx.coroutines.flow.flatMapLatest
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
    /** Issue #999 — highlights + notes for this fiction, grouped by chapter
     *  in reading order for the "Highlights & notes" list. Empty list when
     *  the book has no annotations yet — the UI hides the section (like the
     *  Notebook) rather than rendering an empty card. */
    val annotationGroups: List<AnnotationGroup> = emptyList(),
    /** Issue #999 — true while [ExportAnnotationsUseCase] writes the
     *  Markdown/TXT file. Surfaces a building chip, like [isExportingEpub]. */
    val isExportingAnnotations: Boolean = false,
)

/**
 * Issue #999 — one chapter's worth of annotations for the FictionDetail list.
 * UI-facing projection: [chapterTitle] is resolved from the chapter row (so
 * the list shows real titles, not ids), [annotations] are the raw rows in the
 * DAO's start-offset order. Distinct from the export formatter's internal
 * `ChapterGroup` so the UI layer doesn't depend on the formatter's shape.
 */
@Immutable
data class AnnotationGroup(
    val chapterTitle: String,
    val annotations: List<Annotation>,
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

    /**
     * Issue #999 — fired when a highlights/notes export finishes. The screen
     * collects this and surfaces the Share / Save-As sheet, mirroring #117's
     * [EpubExported]. One-shot via the event channel so the share action
     * doesn't re-fire on configuration change.
     */
    data class AnnotationsExported(val result: AnnotationExportResult) : FictionDetailUiEvent

    /** Issue #999 — non-fatal export failure (disk write error). Toasted. */
    data class AnnotationsExportFailed(val message: String) : FictionDetailUiEvent
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
    /** Issue #1003 — enqueues + observes the "export this fiction as an
     *  audiobook" render. Mirrors the EPUB export's home on this screen. */
    private val audiobookExportScheduler: AudiobookExportScheduler,
    /** Issue #999 — read/delete surface for this fiction's highlights +
     *  notes (the "Highlights & notes" list). */
    private val annotationRepo: AnnotationRepository,
    /** Issue #999 — builds the Markdown/TXT export file + FileProvider URI
     *  for the share-sheet. Injected like [exportEpub] (#117). */
    private val exportAnnotations: ExportAnnotationsUseCase,
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

    /** Issue #999 — highlights-export-in-flight flag. Exposed via
     *  [FictionDetailUiState.isExportingAnnotations]; held outside the combine
     *  so [exportAnnotations] can flip it around the use-case invocation. */
    private val isExportingAnnotationsFlow = MutableStateFlow(false)

    /** Issue #1003 — unique work name of an in-flight audiobook export for
     *  this fiction (null until the user taps "Export as audiobook"). */
    private val audiobookWorkName = MutableStateFlow<String?>(null)

    /** Issue #1003 — live status of the audiobook export. Idle until the user
     *  starts one; the screen observes this to show a progress chip and then
     *  the Share / Save-As sheet. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val audiobookExportStatus: StateFlow<AudiobookExportStatus> = audiobookWorkName
        .flatMapLatest { name ->
            if (name == null) flowOf(AudiobookExportStatus.Idle)
            else audiobookExportScheduler.statusFor(name)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AudiobookExportStatus.Idle)

    /**
     * Issue #1003 — enqueue an audiobook (`.m4b`) export of this fiction using
     * the active voice. Idempotent at the WorkManager layer (one export per
     * fiction in flight). The screen surfaces progress + the finished file via
     * [audiobookExportStatus].
     */
    fun exportToAudiobook() {
        val name = audiobookExportScheduler.enqueue(
            fictionId = fictionId,
            title = uiState.value.fiction?.title.orEmpty(),
            voiceId = null,
        )
        audiobookWorkName.value = name
    }

    /** Issue #1003 — clear the audiobook export status after the user
     *  dismisses the result sheet. */
    fun clearAudiobookExport() {
        audiobookWorkName.value = null
    }

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
        // Issue #999 — highlights/notes feed, grouped by chapter for the list.
        // Combined with the chapter list (for titles + reading order) and the
        // export-in-flight flag. Re-grouping on every annotation change is
        // cheap (a groupBy over a handful of rows). Emits a Pair so the outer
        // combine stays within the 5-arg ceiling.
        val annotationsFlow: kotlinx.coroutines.flow.Flow<Pair<List<AnnotationGroup>, Boolean>> =
            combine(
                annotationRepo.observeForFiction(fictionId),
                repo.chaptersFor(fictionId),
                isExportingAnnotationsFlow,
            ) { annotations, chapters, exporting ->
                groupAnnotations(annotations, chapters) to exporting
            }
        combine(
            base,
            memoryRepo.entitiesForFiction(fictionId),
            cacheStates,
            annotationsFlow,
        ) { state, notebook, cacheStateMap, (annotationGroups, exportingAnnotations) ->
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
            state.copy(
                chapters = chaptersWithCache,
                notebookEntries = notebook,
                annotationGroups = annotationGroups,
                isExportingAnnotations = exportingAnnotations,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FictionDetailUiState())
    }

    /**
     * Issue #999 — group a flat annotation list (chapter-index ordered by the
     * DAO) into per-chapter [AnnotationGroup]s with resolved titles. Mirrors
     * the export use case's `groupAnnotationsByChapter`, but keyed off the
     * already-loaded [UiChapter] list (the VM has it via [repo.chaptersFor])
     * rather than re-reading the DB. A blank/unknown chapter falls back to a
     * "Chapter N" label so a highlight never renders title-less or vanishes.
     */
    private fun groupAnnotations(
        annotations: List<Annotation>,
        chapters: List<UiChapter>,
    ): List<AnnotationGroup> {
        if (annotations.isEmpty()) return emptyList()
        val chapterById = chapters.associateBy { it.id }
        return annotations.groupBy { it.chapterId }.entries
            .sortedBy { (chapterId, _) -> chapterById[chapterId]?.number ?: Int.MAX_VALUE }
            .map { (chapterId, anns) ->
                val ch = chapterById[chapterId]
                val title = ch?.title?.ifBlank { null }
                    ?: ch?.let { "Chapter ${it.number}" }
                    ?: "Chapter"
                AnnotationGroup(chapterTitle = title, annotations = anns)
            }
    }

    /** Issue #760 — synopsis-aloud TTS state. Reuses the recap-aloud
     *  pipeline from [PlaybackControllerUi.speakText]. Exposed as a
     *  StateFlow so the Synopsis composable's listen icon can toggle
     *  between play and stop states. */
    val synopsisSpeaking: StateFlow<UiRecapPlaybackState> = playback.recapPlayback
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiRecapPlaybackState.Idle)

    /** Issue #760 — toggle listening to the full synopsis text via TTS.
     *  When already speaking, stops. Otherwise pauses any active fiction
     *  playback (so the voices don't overlap) and speaks the full
     *  synopsis text. The recap pipeline handles state transitions;
     *  [synopsisSpeaking] updates automatically via the shared
     *  [PlaybackControllerUi.recapPlayback] flow. */
    fun toggleSynopsisAloud() {
        if (synopsisSpeaking.value == UiRecapPlaybackState.Speaking) {
            playback.stopSpeaking()
            return
        }
        val text = uiState.value.fiction?.synopsis ?: return
        if (text.isBlank()) return
        viewModelScope.launch {
            playback.speakText(text)
        }
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
     * Issue #806 — re-fire the same first-subscription refresh that
     * loads this fiction's detail. Wired to the Retry button on the
     * full-screen "Couldn't load this fiction" error and the inline
     * "Couldn't refresh" banner. Failures update the load-error flow
     * the screen is already observing; success clears it.
     */
    fun retryRefresh() {
        viewModelScope.launch { repo.retryDetail(fictionId) }
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
     * Issue #999 — build a Markdown / plain-text file of this fiction's
     * highlights + notes in the cache dir and emit
     * [FictionDetailUiEvent.AnnotationsExported] so the screen fires the
     * share-sheet / SAF flow. Mirrors [exportToEpub] (#117): takes [context]
     * for `cacheDir` + `FileProvider`, idempotent against a double-tap while a
     * previous export is in flight.
     *
     * Exports even when there are no annotations (the file carries a friendly
     * "No highlights yet." line); the screen can choose to gate the menu item
     * on a non-empty list, but the use case itself never refuses.
     */
    fun exportAnnotations(context: Context, format: AnnotationExportFormatter.Format) {
        if (isExportingAnnotationsFlow.value) return
        viewModelScope.launch {
            isExportingAnnotationsFlow.value = true
            try {
                val result = exportAnnotations.export(context, fictionId, format)
                _events.send(FictionDetailUiEvent.AnnotationsExported(result))
            } catch (t: Throwable) {
                _events.send(
                    FictionDetailUiEvent.AnnotationsExportFailed(
                        "Couldn't export highlights: ${t.message ?: t.javaClass.simpleName}",
                    ),
                )
            } finally {
                isExportingAnnotationsFlow.value = false
            }
        }
    }

    /** Issue #999 — delete one annotation by its UUID. The "Highlights &
     *  notes" list's per-row delete routes here; the list updates via the
     *  observed flow. */
    fun deleteAnnotation(id: String) {
        viewModelScope.launch { annotationRepo.delete(id) }
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
