package `in`.jphe.storyvox.feature.api

import `in`.jphe.storyvox.playback.cache.ChapterCacheState
import kotlinx.coroutines.flow.Flow

/**
 * Lightweight contracts the feature module depends on. The real impls live in
 * core-data (FictionRepository, FollowsRepository) and core-playback (PlaybackController).
 * Aurora ships these as interfaces so feature-module screens compile against a stable
 * surface; Selene + Hypnos provide bindings via Hilt in the app module.
 *
 * If any signature here doesn't match what core-data/core-playback expose by merge time,
 * Davis (orchestrator) reconciles in the spec-merge step.
 */

data class UiFiction(
    val id: String,
    val title: String,
    val author: String,
    val coverUrl: String?,
    val rating: Float,
    val chapterCount: Int,
    val isOngoing: Boolean,
    val synopsis: String,
    /** Issue #211 — the FictionSource this fiction came from. Drives
     *  the "Follow on Royal Road" affordance on FictionDetail; non-RR
     *  sources don't have a source-side follow concept. Defaulted to
     *  empty so existing construction sites stay compatible. */
    val sourceId: String = "",
    /** Issue #211 — true when storyvox has pushed a follow to the
     *  source's account (via [FictionRepositoryUi.setFollowedRemote])
     *  OR when the periodic /my/follows pull observed the user
     *  follows this fiction on RR. Drives the Follow-button label
     *  (Follow / Following). */
    val isFollowedRemote: Boolean = false,
    /** Issue #382 — generalized from #211's hardcoded RR check.
     *  True when the originating FictionSource exposes a follow
     *  action via `FictionSource.supportsFollow`. Drives the
     *  Follow-button *visibility* on FictionDetail; absent / false
     *  means the button is hidden regardless of sign-in state. */
    val sourceSupportsFollow: Boolean = false,
)

data class UiChapter(
    val id: String,
    val number: Int,
    val title: String,
    val publishedRelative: String,
    val durationLabel: String,
    val isDownloaded: Boolean,
    val isFinished: Boolean,
    /** PR-H (#86) — PCM cache state for this chapter under the user's
     *  currently-active voice at 1.0× speed / 1.0× pitch. Default
     *  `None` keeps every UiChapter construction site (today only
     *  `AppBindings.chaptersFor`) source-compatible; the
     *  FictionDetailViewModel composes the real value by combining
     *  this list with `CacheStateInspector.chapterStatesFor`. */
    val cacheState: ChapterCacheState = ChapterCacheState.None,
)

data class UiFollow(
    val fiction: UiFiction,
    val unreadCount: Int,
)

enum class DownloadMode { Lazy, Eager, Subscribe }

/**
 * Issue #246 — one curated RSS feed suggestion. Surfaces in
 * Settings → Library & Sync → RSS → Suggested feeds with a one-tap
 * Add button. Fetched from the jphein/storyvox-feeds GitHub repo at
 * runtime so categories + feeds can be added without an app rebuild.
 */
data class SuggestedFeed(
    val title: String,
    val description: String,
    val url: String,
    val category: String,
    val kind: SuggestedFeedKind,
)

enum class SuggestedFeedKind {
    /** Text-article feed — narrates well via TTS. */
    Text,

    /** Audio-podcast feed — show-notes narrate, audio enclosure
     *  doesn't (storyvox doesn't currently stream feed audio). */
    AudioPodcast,
}

/** Outcome of a source-side follow toggle (#211). */
sealed class SetFollowedRemoteResult {
    /** Push succeeded; storyvox's local copy already updated. */
    data object Success : SetFollowedRemoteResult()
    /** Source rejected without a session — caller should route the
     *  user to sign-in (Royal Road today). */
    data object AuthRequired : SetFollowedRemoteResult()
    /** Anything else — `message` is user-facing copy. */
    data class Error(val message: String) : SetFollowedRemoteResult()
}

/** Outcome of pasting a URL into the add-fiction sheet. */
sealed class UiAddByUrlResult {
    /** Resolved + persisted; UI navigates to the detail screen. */
    data class Success(val fictionId: String) : UiAddByUrlResult()
    /** No source matched the input. Post-#472 (Readability catch-all)
     *  this is only seen for genuinely unparseable inputs — empty
     *  strings, non-URL text. */
    data object UnrecognizedUrl : UiAddByUrlResult()
    /** Recognised but not yet supported. */
    data class UnsupportedSource(val sourceId: String) : UiAddByUrlResult()
    /** Source-layer failure (network, 404, auth, rate-limit, ...). [message] is user-facing. */
    data class Error(val message: String) : UiAddByUrlResult()
    /** Issue #472 — magic-link resolver matched two or more backends at
     *  chooser-eligible confidence (≥0.5). UI shows a picker, then
     *  re-invokes `addByUrl(url, preferredSourceId = picked)`. */
    data class MultipleMatches(val candidates: List<UiRouteCandidate>) : UiAddByUrlResult()
}

/** UI-facing copy of a single route candidate from the magic-link
 *  resolver (#472). Mirrors `RouteMatch` but doesn't depend on
 *  `:core-data` types so the feature module stays at the boundary it
 *  has today. */
data class UiRouteCandidate(
    val sourceId: String,
    val fictionId: String,
    val confidence: Float,
    val label: String,
)

interface FictionRepositoryUi {
    val library: Flow<List<UiFiction>>
    val follows: Flow<List<UiFollow>>
    fun fictionById(id: String): Flow<UiFiction?>
    /**
     * Mirrors [fictionById]'s underlying first-subscription refresh — emits
     * the error message of the most recent `refreshDetail(id)` attempt, or
     * null on success / not-yet-attempted. Lets screens distinguish
     * "still loading" (fiction == null, error == null) from "refresh failed
     * and we have no cache" (fiction == null, error != null). Cleared on
     * the next successful refresh.
     */
    fun fictionLoadError(id: String): Flow<String?>
    fun chaptersFor(fictionId: String): Flow<List<UiChapter>>
    /**
     * Targeted boolean stream: true when [fictionId] is in the user's
     * library. Scoped to a single row — unlike [library], this flow
     * does not re-emit when unrelated fictions change. Used by
     * FictionDetailViewModel to avoid the O(n) full-list scan.
     */
    fun observeIsInLibrary(fictionId: String): Flow<Boolean>
    /**
     * Issue #212 — fetch the plain-text body of a downloaded chapter
     * for AI grounding. Returns null if the chapter row doesn't exist
     * or its body hasn't been downloaded yet. Suspends because the
     * underlying DAO read is suspending; callers should treat this
     * as a one-shot, not a stream.
     */
    suspend fun chapterTextById(chapterId: String): String?
    suspend fun setDownloadMode(fictionId: String, mode: DownloadMode)
    suspend fun follow(fictionId: String, follow: Boolean)
    /**
     * Issue #211 — push a follow/unfollow to the *source* (Royal Road's
     * account, not storyvox's local library). The source layer
     * handles auth: returns silently on AuthRequired so callers can
     * pre-check sign-in and route to the appropriate screen instead
     * of swallowing the no-op. Distinct from [follow], which is local
     * library Add/Remove.
     */
    suspend fun setFollowedRemote(fictionId: String, followed: Boolean): SetFollowedRemoteResult
    /**
     * Issue #982 — Follows tab "Mark all caught up". Marks every unread
     * chapter of every followed fiction as read and persists it (the same
     * `userMarkedRead` column playback writes). Returns how many chapters
     * were transitioned so the screen only signals success when a write
     * actually happened — the previous v1 no-op claimed success while
     * writing nothing.
     */
    suspend fun markAllCaughtUp(): Int
    /**
     * Best-effort refresh of the user's source-side follows list. No-op if
     * the user isn't signed in. Caller doesn't await the result — the local
     * `follows` Flow will emit when the DB is upserted.
     */
    suspend fun refreshFollows()

    /**
     * Issue #806 — re-trigger the same refresh that [fictionById]'s
     * first-subscription kick-off runs, so screens can wire a Retry
     * affordance after a network/Cloudflare error. Updates the
     * [fictionLoadError] state in place; the value [fictionById] Flow
     * emits whatever cached row exists (if any). Best-effort: returns
     * without throwing on failure.
     */
    suspend fun retryDetail(id: String) {}

    /**
     * Resolve a pasted URL (or short form) to a fiction, persist it, and
     * fetch detail. Implementation routes through `UrlResolver` + the
     * multi-source map. Returns enough information for the sheet to
     * navigate on success or surface a useful message on failure.
     *
     * Issue #472 — when several backends claim the URL at chooser
     * confidence (≥0.5), returns [UiAddByUrlResult.MultipleMatches]
     * and the UI re-invokes with [preferredSourceId] = the user's
     * picked source.
     */
    suspend fun addByUrl(
        url: String,
        preferredSourceId: String? = null,
    ): UiAddByUrlResult

    /**
     * Issue #472 — debounced preview helper for the Magic-add sheet.
     * Returns ordered route candidates (highest-confidence first) for
     * the partially-typed [url]. Pure-CPU, safe to call on every
     * keystroke. Empty list = nothing parsed; the UI shows the empty
     * state and waits.
     */
    fun previewUrl(url: String): List<UiRouteCandidate>

    /**
     * Issues #644 + #647 (v1.0) — seed the local Fiction DB with the
     * first-page "popular" tiles for [sourceId]. Used by the welcome
     * flow's "Browse TechEmpower's free guides" CTA to ensure the
     * Notion fiction rows (notably `notion:guides`) exist in the
     * Fiction DAO before [fictionById] / [chaptersFor] attempt to
     * refresh detail — without this seeding pass, [refreshDetail]
     * falls back to the wrong source on first launch because the
     * row's `sourceId` column is unknown.
     *
     * The default no-op is a Kotlin interface defaulting trick used
     * elsewhere in the contract (see `markAllCaughtUp`'s comment in
     * the impl) so test stand-ins don't have to bother stubbing.
     *
     * Returns true iff at least one row was upserted; false on
     * network failure / source not bound / cancellation. Callers
     * treat false as "fall back to a path that doesn't depend on
     * the seeded rows".
     */
    suspend fun seedPopularForSource(sourceId: String): Boolean = false
}

interface BrowseRepositoryUi {
    /**
     * Build a paginator for the given browse [source] against the
     * named [sourceId]. Callers consume `items` / `isLoading` /
     * `isAppending` / `hasMore` as Flows and call `loadNext()` on
     * initial composition + on near-end scroll.
     *
     * [sourceId] defaults to `royalroad` for backward compatibility
     * with existing call sites; the BrowseScreen source-picker UI
     * passes `github` when the user has chosen the GitHub tab.
     */
    fun paginator(
        source: BrowseSource,
        sourceId: String = `in`.jphe.storyvox.data.source.SourceIds.ROYAL_ROAD,
    ): BrowsePaginator

    /**
     * Genres / tags / wings supported by [sourceId]. MemPalace returns
     * its top-level wing names; Royal Road returns the curated tag list;
     * GitHub returns an empty list (no genre concept). Used by
     * BrowseViewModel to populate the MemPalace wing filter chips.
     */
    suspend fun genres(sourceId: String): List<String>
}

/** What kind of listing the paginator should fetch. The repository
 *  adapter maps each variant onto a source call (Royal Road for the
 *  legacy variants, GitHub for [FilteredGitHub]). */
sealed interface BrowseSource {
    data object Popular : BrowseSource
    data object NewReleases : BrowseSource
    data object BestRated : BrowseSource
    data class Search(val query: String) : BrowseSource
    data class Filtered(
        val query: String,
        val state: `in`.jphe.storyvox.data.source.filter.FilterState,
    ) : BrowseSource

    data class ByGenre(val genre: String) : BrowseSource

    /**
     * Auth-gated `/user/repos` listing for the signed-in GitHub user
     * (#200). Only meaningful when `BrowseSourceKey.GitHub` is selected
     * AND `UiSettings.github` is signed-in; the BrowseViewModel gates
     * the tab visibility, the adapter routes to `GitHubAuthedSource`.
     */
    data object GitHubMyRepos : BrowseSource

    /**
     * Auth-gated `/user/starred?sort=updated` listing for the signed-in
     * GitHub user (#201). Filtered to fiction-shaped repos client-side.
     * Same gating as [GitHubMyRepos] — Browse tab visibility is driven
     * by `UiSettings.github`; the adapter routes to `GitHubAuthedSource`.
     */
    data object GitHubStarred : BrowseSource

    /**
     * Gists for the signed-in GitHub user (#202). Routes through
     * `GET /gists` (authenticated) so secret gists show up alongside
     * public ones; the `:source-github` source layer handles the
     * empty/anonymous case by surfacing an empty page rather than
     * erroring out.
     *
     * The repository adapter routes this onto
     * `GitHubAuthedSource.authenticatedUserGists(page)`. There's no
     * Royal Road or MemPalace analogue — the BrowseSource contract
     * has source-specific variants on purpose (`Filtered` vs
     * `FilteredGitHub`, `GitHubMyRepos` vs `GitHubStarred` vs
     * `GitHubGists`).
     */
    data object GitHubGists : BrowseSource

    /**
     * Auth-gated `/users/<username>/subscriptions` listing for the
     * signed-in AO3 user (#426 PR2). Browse → AO3 → My Subscriptions
     * tab. The repository adapter routes this onto
     * [`Ao3AuthedSource.subscriptions`][in.jphe.storyvox.source.ao3.Ao3AuthedSource.subscriptions];
     * the username is read from the captured `auth_cookie.userId`
     * column for the AO3 row.
     */
    data object Ao3MySubscriptions : BrowseSource

    /**
     * Auth-gated `/users/<username>/readings?show=marked` listing for
     * the signed-in AO3 user (#426 PR2). Browse → AO3 → Marked for
     * Later tab. Renders empty when the user has disabled "History"
     * in their AO3 preferences (AO3 doesn't populate the marked-
     * for-later list without history enabled).
     */
    data object Ao3MarkedForLater : BrowseSource

    /**
     * Wikipedia "On This Day" cluster from the `feed/featured` payload
     * (#796). Browse → Wikipedia → On This Day tab. The repository
     * adapter routes this onto
     * [`WikipediaBrowseSource.onThisDay`][in.jphe.storyvox.source.wikipedia.WikipediaBrowseSource.onThisDay].
     * Not auth-gated — the cluster ships in the same public payload the
     * Popular tab already reads.
     */
    data object WikipediaOnThisDay : BrowseSource

    /**
     * Wikipedia "In the news" cluster from the `feed/featured` payload
     * (#796). Browse → Wikipedia → In the News tab. The repository
     * adapter routes this onto
     * [`WikipediaBrowseSource.inTheNews`][in.jphe.storyvox.source.wikipedia.WikipediaBrowseSource.inTheNews].
     */
    data object WikipediaInTheNews : BrowseSource
}

/** A page-by-page accumulating cursor over a remote fiction listing.
 *  `loadNext()` is idempotent under concurrent calls — guarded by a
 *  mutex so the LazyGrid's near-end LaunchedEffect can fire freely. */
interface BrowsePaginator {
    val items: Flow<List<UiFiction>>
    /** True only on the very first page fetch (drives the skeleton grid). */
    val isLoading: Flow<Boolean>
    /** True while a subsequent page is being appended (drives the
     *  spinner footer below the existing grid). */
    val isAppending: Flow<Boolean>
    /** False once the source returned `hasNext = false`; the grid then
     *  shows an "end of list" footer. */
    val hasMore: Flow<Boolean>
    val error: Flow<String?>

    suspend fun loadNext()
    /** Reset to page 1 (caller should follow with [loadNext]). Wired up
     *  for future pull-to-refresh; not used in v1 of the feature. */
    suspend fun refresh()
}

/**
 * MemPalace-shaped filter for Browse → Palace (#191). Single dimension
 * today: which wing of the palace to scope the listing to. Null wing
 * means "all wings" — Browse falls back to Popular/NewReleases tabs as
 * before. Lives in `:feature/api` so the wing dropdown sheet and the
 * ViewModel can hold it without taking a dep on `:source-mempalace`.
 */
data class MemPalaceFilter(
    val wing: String? = null,
)

data class UiPlaybackState(
    val fictionId: String?,
    val chapterId: String?,
    val chapterTitle: String,
    val fictionTitle: String,
    val coverUrl: String?,
    val isPlaying: Boolean,
    /** True when the streaming TTS pipeline has paused AudioTrack waiting
     *  for the producer to refill the queue past the underrun threshold.
     *  UI surfaces a "Buffering..." spinner; differs from `!isPlaying`
     *  (user pause) and from initial voice warm-up (see [isWarmingUp]).
     *
     *  Issue #98 — when the user disables Mode B (Catch-up Pause), the
     *  EnginePlayer consumer thread no longer pauses on underrun, so this
     *  flag stays false through underrun events and the consumer drains
     *  through the silence instead. UI surfaces no buffering spinner in
     *  that mode by construction. */
    val isBuffering: Boolean = false,
    /** True when the user has hit play but the voice engine hasn't
     *  produced the first sentence's audio yet. Distinct from
     *  [isBuffering] (mid-stream underrun). UI surfaces the "warming up"
     *  spinner + freezes wall-time scrubber interpolation.
     *
     *  Issue #98 — Mode A (Warm-up Wait) gates this. With Mode A on
     *  (default), this is `isPlaying && sentenceEnd == 0`. With Mode A
     *  off, this is always false: the UI behaves as if playback started
     *  immediately and the listener accepts silence at chapter start. */
    val isWarmingUp: Boolean = false,
    val positionMs: Long,
    val durationMs: Long,
    /** Char index into the chapter text where the current sentence begins. */
    val sentenceStart: Int,
    /** Exclusive end index of the current sentence. */
    val sentenceEnd: Int,
    val speed: Float,
    val pitch: Float,
    val voiceId: String?,
    val voiceLabel: String,
    /** Null when no sleep timer is active; otherwise milliseconds until it fires. */
    val sleepTimerRemainingMs: Long? = null,
    /**
     * Issue #373 — true when the currently-loaded chapter routes
     * through Media3 / ExoPlayer (audio-stream backend: KVMR community
     * radio, future LibriVox MP3, etc.) rather than the TTS pipeline.
     * Gates the pitch slider in [AudiobookView] — Sonic pitch-shifting
     * applies to engine-rendered PCM and has no equivalent on a live
     * stream the player can't decode-re-encode in real time. UI hides
     * the slider when this flag is true.
     */
    val isLiveAudioChapter: Boolean = false,
)

/** Mirrors core-playback's SleepTimerMode without leaking the playback module to feature. */
sealed class UiSleepTimerMode {
    data class Duration(val minutes: Int) : UiSleepTimerMode()
    data object EndOfChapter : UiSleepTimerMode()
}

/** Issue #189 — feature-side mirror of core-playback's RecapPlaybackState.
 *  Idle = no recap utterance in flight. Speaking = recap audio is playing
 *  through the dedicated AudioTrack. The Reader UI's "Read aloud" button
 *  toggles between the two states. */
enum class UiRecapPlaybackState { Idle, Speaking }

interface PlaybackControllerUi {
    val state: Flow<UiPlaybackState>
    val chapterText: Flow<String>
    /** Issue #189 — recap-aloud TTS pipeline state. See [UiRecapPlaybackState]. */
    val recapPlayback: Flow<UiRecapPlaybackState>
    /**
     * Calliope (v0.5.00) — one-shot UI signals from the player.
     * Used today by the milestone celebration to detect the first
     * natural chapter completion ([`in`.jphe.storyvox.playback.PlaybackUiEvent.ChapterDone])
     * so the confetti overlay can fire once. Default emits nothing,
     * so test fakes that don't care about player events stay slim.
     *
     * This is the long-promised UI seam for the events SharedFlow on
     * the core-playback PlaybackController — pre-Calliope, the
     * `BookFinished` / `ChapterChanged` / `EngineMissing` /
     * `AzureFellBack` events still flowed inside core-playback but
     * never reached the feature layer because there was no UI-side
     * subscription path. New consumers should observe through this
     * flow; the existing debug-overlay path goes through
     * `PlaybackController.events` directly from `:app` and keeps
     * working.
     */
    val events: Flow<`in`.jphe.storyvox.playback.PlaybackUiEvent>
        get() = kotlinx.coroutines.flow.emptyFlow()

    /**
     * Issue #805 — typed engine state for error surfacing. The reader UI
     * subscribes to this to show distinct error banners for auth failures,
     * network errors, throttling, etc. — replacing the generic "something
     * went wrong" treatment with actionable recovery actions.
     *
     * Default emits [EngineState.Idle] forever so test fakes that don't
     * care about error surfaces stay slim.
     */
    val engineState: Flow<`in`.jphe.storyvox.playback.EngineState>
        get() = kotlinx.coroutines.flow.flowOf(`in`.jphe.storyvox.playback.EngineState.Idle)

    /**
     * "Why are we waiting?" diagnostic. Forwarded straight from
     * [in.jphe.storyvox.playback.PlaybackController.waitReason]. Null when
     * audio is genuinely flowing; non-null with a typed
     * [in.jphe.storyvox.playback.diagnostics.WaitReason] otherwise. The
     * reader UI subscribes through this flow to render the brass
     * `WhyAreWeWaitingPanel` above the cover.
     *
     * Default emits null forever so test fakes that don't care about
     * the diagnostic stay slim.
     */
    val waitReason: Flow<`in`.jphe.storyvox.playback.diagnostics.WaitReason?>
        get() = kotlinx.coroutines.flow.flowOf(null)
    fun play()
    fun pause()
    fun seekTo(ms: Long)
    /** Seek by char offset into chapter text (used by tap-on-sentence). */
    fun seekToChar(charOffset: Int)
    fun skipForward()
    fun skipBack()
    /** #120 — step to the next sentence boundary. No-op at chapter end. */
    fun nextSentence()
    /** #120 — step to the previous sentence boundary. No-op at sentence 0. */
    fun previousSentence()
    fun nextChapter()
    fun previousChapter()
    fun setSpeed(speed: Float)
    fun setPitch(pitch: Float)
    /**
     * Scale the inter-sentence punctuation pause (issue #90). 0f disables
     * trailing silence entirely; 1f is the audiobook-tuned default; >1f
     * lengthens. Applied to the next sentence the producer generates —
     * the live pipeline rebuilds so the change takes effect on the next
     * sentence boundary, mirroring [setSpeed]/[setPitch].
     */
    fun setPunctuationPauseMultiplier(multiplier: Float)
    /**
     * @param autoPlay when true (default), playback starts immediately
     *   after chapter+voice load completes. When false (#90 smart-resume
     *   path), the chapter loads + navigation fires but the engine
     *   remains paused — user has to tap play. Library's Resume CTA
     *   passes `lastWasPlaying` here so an explicit pre-pause is
     *   respected on next resume.
     */
    fun startListening(
        fictionId: String,
        chapterId: String,
        charOffset: Int = 0,
        autoPlay: Boolean = true,
    )
    fun startSleepTimer(mode: UiSleepTimerMode)
    fun cancelSleepTimer()

    /** Issue #189 — synthesize and play [text] as a one-shot utterance via
     *  the active voice. The caller (ReaderViewModel.toggleRecapAloud) is
     *  responsible for pausing fiction playback before calling — see the
     *  PlaybackController interface for the full contract. */
    suspend fun speakText(text: String)

    /** Issue #189 — cancel an in-flight recap-aloud utterance. Idempotent. */
    fun stopSpeaking()

    /**
     * Issue #121 — bookmark the current playback position. No-op when
     * no chapter is loaded; otherwise persists the current char offset
     * as that chapter's single bookmark, replacing any previous bookmark.
     */
    fun bookmarkHere()

    /** Issue #121 — clear the currently-loaded chapter's bookmark. No-op
     *  when nothing's loaded or no bookmark exists. */
    fun clearBookmark()

    /**
     * Issue #121 — seek to the currently-loaded chapter's bookmark
     * position, if any. Backed by a callback rather than a Flow because
     * "is there a bookmark?" is a one-shot question the menu asks when
     * it opens; observing every change would force the player options
     * sheet to re-collect on every chapter change.
     */
    fun jumpToBookmark()
}

data class UiVoice(
    val id: String,
    val label: String,
    val engine: String,
    val locale: String,
)

/**
 * Legacy voice surface. v0.4.0 introduced [VoiceManager] in core-playback as the
 * canonical source for voice install/select/download — new code should depend on
 * that directly. This contract is kept slim for the few remaining consumers
 * (SettingsViewModel, the legacy VoicePickerScreen, ReaderViewModel) that just
 * need an Android-TextToSpeech-backed voice list and one-shot preview.
 */
interface VoiceProviderUi {
    val installedVoices: Flow<List<UiVoice>>
    fun previewVoice(voice: UiVoice)
}

/** Mirror of [`in`.jphe.storyvox.llm.ProviderId] for the feature
 *  module — keeps `:feature` from depending on `:core-llm` directly.
 *  The Settings impl converts between the two. Stays in lockstep
 *  with the canonical enum (PR-1 ships with the same set + order). */
enum class UiLlmProvider {
    Claude, OpenAi, Ollama, Bedrock, Vertex, Foundry, Teams;

    /** Whether this provider has a real implementation.
     *  Matches `ProviderId.implemented`. */
    val implemented: Boolean
        get() = this == Claude || this == OpenAi || this == Ollama ||
            this == Vertex || this == Foundry || this == Bedrock

    val displayName: String
        get() = when (this) {
            Claude -> "Claude (Anthropic, BYOK)"
            OpenAi -> "OpenAI (BYOK)"
            Ollama -> "Ollama (local LAN)"
            Bedrock -> "AWS Bedrock"
            Vertex -> "Google Vertex AI"
            Foundry -> "Azure AI Foundry"
            Teams -> "Anthropic Teams (OAuth)"
        }
}

/** UI-facing AI config — a flattened bundle the Settings screen
 *  reads. Distinct from the wire-layer LlmConfig in :core-llm so
 *  `:feature` doesn't take a direct dependency on the LLM module. */
data class UiAiSettings(
    /** null = AI disabled. */
    val provider: UiLlmProvider? = null,
    val claudeModel: String = "claude-haiku-4.5",
    val claudeKeyConfigured: Boolean = false,
    val openAiModel: String = "gpt-4o-mini",
    val openAiKeyConfigured: Boolean = false,
    val ollamaBaseUrl: String = "http://10.0.0.1:11434",
    val ollamaModel: String = "llama3.3",
    val vertexModel: String = "gemini-2.5-flash",
    val vertexKeyConfigured: Boolean = false,
    /**
     * Issue #219 — alternative Vertex auth via uploaded service-account
     * JSON. Mutually exclusive with [vertexKeyConfigured] at the
     * repository level (setting either side clears the other). The UI
     * surfaces a single "Vertex auth" section with both rows visible
     * so the user can switch between modes without digging.
     *
     * Whether the SA was rejected by Google IAM (revoked, missing
     * roles, etc.) is surfaced separately via the probe path —
     * "configured" here only means a JSON blob is on disk.
     */
    val vertexServiceAccountConfigured: Boolean = false,
    /** SA's `client_email` if one is uploaded; shown read-only as a
     *  reminder of *which* SA is wired. Never persists outside the
     *  encrypted-prefs blob — this field is derived on each read. */
    val vertexServiceAccountEmail: String? = null,
    /** Azure Foundry endpoint URL the user pasted (e.g.
     *  `https://my-resource.openai.azure.com`). Empty = not set. */
    val foundryEndpoint: String = "",
    /** Deployment name (deployed mode) or catalog model id
     *  (serverless mode). The same field is reused across both modes
     *  because the URL builder reads it differently per mode. */
    val foundryDeployment: String = "",
    /** True selects Azure's serverless `/models/...` URL shape;
     *  false selects the per-model `/openai/deployments/{name}/...`
     *  shape (default — matches "Azure OpenAI Service" deployments). */
    val foundryServerless: Boolean = false,
    val foundryKeyConfigured: Boolean = false,
    /** AWS Bedrock — both keys are encrypted; UI only sees the
     *  "configured" booleans. Region + model are non-secret and round-trip
     *  through DataStore. */
    val bedrockAccessKeyConfigured: Boolean = false,
    val bedrockSecretKeyConfigured: Boolean = false,
    val bedrockRegion: String = "us-east-1",
    val bedrockModel: String = "claude-haiku-4.5",
    /**
     * Anthropic Teams (OAuth) session state (#181). The bearer token
     * itself never crosses into the UI — Settings only needs to know
     * whether the user has signed in and (optionally) which scopes
     * the workspace granted. Token refresh is handled inside the
     * provider; the UI flips to "not signed in" only when the refresh
     * token also gets revoked (mid-session expiry rotates the bearer
     * silently). */
    val teamsSignedIn: Boolean = false,
    val teamsScopes: String = "",
    val privacyAcknowledged: Boolean = false,
    val sendChapterTextEnabled: Boolean = true,
    /**
     * Issue #212 — what the chat ViewModel injects into its system
     * prompt to ground replies in what the user is currently reading.
     * The fiction title is always sent (no toggle); the four fields
     * here are the per-grounding-level opt-ins.
     *
     * Token cost ramps fast on the bottom two: "entire chapter" is
     * a few thousand tokens per turn; "entire book so far" can hit
     * 50k+ tokens on long fictions, which is fine on Claude / OpenAI
     * but blows past Ollama's default 8k context. The Settings UI
     * surfaces a per-toggle estimate so users understand the cost.
     */
    val chatGrounding: UiChatGrounding = UiChatGrounding(),
    /**
     * Issue #217 — "Carry memory across fictions" toggle. When ON,
     * each AI chat turn pulls cross-fiction memory entries matching
     * names in the user's message and appends them to the system
     * prompt under a "Cross-fiction context" section. Token budget
     * is capped at ~500 tokens (oldest entries dropped first), so
     * the worst-case cost is bounded.
     *
     * Default ON for fresh installs — JP's call in #217's decisions
     * list: more useful by default, especially for users with deep
     * libraries who'd hit duplicate-name disambiguation immediately.
     * Existing installs upgrading from pre-#217 also get ON because
     * the DataStore default kicks in for missing keys.
     */
    val carryMemoryAcrossFictions: Boolean = true,
    /**
     * Issue #216 — "Allow the AI to take actions" toggle. When ON
     * (default) the chat surface advertises the v1 tool catalog
     * (add_to_shelf, queue_chapter, mark_chapter_read, set_speed,
     * open_voice_library) to the model and executes calls locally.
     * When OFF the chat behaves as a plain Q&A bot.
     *
     * Only meaningful when the active provider supports function
     * calling — v1 is Anthropic (Claude direct + Teams) + OpenAI.
     * On other providers the chat surfaces an "actions not
     * supported on this provider" empty state regardless of this
     * toggle.
     */
    val actionsEnabled: Boolean = true,
)

/**
 * Per-toggle grounding settings for the Q&A chat ViewModel
 * ([in.jphe.storyvox.feature.chat.ChatViewModel.buildSystemPrompt]).
 * Issue #212.
 *
 * Defaults match the pre-#212 behaviour: only the chapter title is
 * included (when the user is actively listening to the same fiction);
 * everything more expensive is opt-in.
 */
data class UiChatGrounding(
    /** Pre-#212 default behaviour. Cheap (a few words). */
    val includeChapterTitle: Boolean = true,
    /** ~50 tokens. The exact sentence the listener is on right now. */
    val includeCurrentSentence: Boolean = false,
    /** ~2-5k tokens depending on chapter length. */
    val includeEntireChapter: Boolean = false,
    /** Chapter 1 → current sentence. 50k+ tokens on long fictions. */
    val includeEntireBookSoFar: Boolean = false,
)

data class UiSettings(
    val ttsEngine: String,
    val defaultVoiceId: String?,
    val defaultSpeed: Float,
    val defaultPitch: Float,
    val themeOverride: ThemeOverride,
    val downloadOnWifiOnly: Boolean,
    val pollIntervalHours: Int,
    val isSignedIn: Boolean,
    /**
     * Issue #109 — inter-sentence pause as a continuous multiplier (was a
     * 3-stop selector under #93). 0× disables trailing silence; 1× is the
     * audiobook-tuned default; the engine already coerces to [0..4]
     * ([in.jphe.storyvox.playback.tts.EnginePlayer.setPunctuationPauseMultiplier]).
     * Migrated from `pref_punctuation_pause` (enum) on first read; see
     * `PunctuationPauseEnumToMultiplierMigration` in
     * `:app`'s `SettingsRepositoryUiImpl` for the mapping.
     */
    val punctuationPauseMultiplier: Float = PUNCTUATION_PAUSE_DEFAULT_MULTIPLIER,
    /**
     * Issue #193 — VoxSherpa-TTS v2.7.13 — Sonic pitch-interpolation
     * quality. Default true (quality=1) for smoother pitch-shifted
     * output. Costs ~20% extra CPU per pitch-shifted chunk, which is
     * fine for storyvox because chapter PCM is pre-rendered and
     * cached (post-#97). Users on slow hardware can flip this off to
     * fall back to Sonic's upstream default (quality=0, faster but
     * audibly grittier at non-neutral pitch).
     */
    val pitchInterpolationHighQuality: Boolean = true,
    val sigil: UiSigil = UiSigil.UNKNOWN,
    val ai: UiAiSettings = UiAiSettings(),
    /**
     * Audio pre-synth queue depth, in sentence-chunks. Maps directly to
     * [EngineStreamingSource.queueCapacity]. The minimum 2 keeps a real
     * back-pressure buffer (1 in flight + 1 queued); the mechanical max is
     * intentionally well past where we think Android's LMK starts killing the
     * app — see [BUFFER_RECOMMENDED_MAX_CHUNKS] for the conservative tick.
     *
     * Issue #84 tracks the empirical work to find the real LMK threshold on
     * Tab A7 Lite (Helio P22T, 3 GB). Treat values past the recommended max
     * as exploratory; we want telemetry from users running there.
     */
    val playbackBufferChunks: Int = BUFFER_DEFAULT_CHUNKS,
    /**
     * Issue #98 — Mode A. When true (default), the UI shows a "warming up"
     * spinner + freezes wall-time scrubber interpolation while the voice
     * engine is loading + producing the first sentence's audio. When false,
     * the UI behaves as if playback started immediately; the listener hears
     * silence until the engine catches up but the scrubber and play button
     * look "playing" the whole time. Useful for users who'd rather see motion
     * than a spinner on slower devices.
     */
    /** Default flipped to false on 2026-05-09 — JP's directive
     *  "all performance & buffering toggles default off." Existing
     *  users keep their persisted preference; only fresh installs
     *  see the new default. */
    val warmupWait: Boolean = false,
    /**
     * Issue #98 — Mode B. When true (default), the streaming pipeline pauses
     * AudioTrack on mid-stream underrun (PR #77's pause-buffer-resume) and
     * surfaces a "Buffering..." spinner while the producer catches up. When
     * false, the consumer thread drains through underruns; the listener
     * hears moments of dead air instead of paused-then-resumed playback,
     * but never sees the buffering spinner.
     */
    /** Default flipped to false on 2026-05-09 then back to true the
     *  same evening — JP reported audible inter-chunk gaps with
     *  buffer=3000 immediately after the off-default landed. Mode B
     *  is what handles "buffer underrun → pause AudioTrack → wait
     *  for refill → resume" smoothing; turning it off means every
     *  generation-vs-playback miss surfaces as a gap. Keeping it
     *  default-on now; Mode A (warmupWait) stays default-off as a
     *  separate UX call. */
    val catchupPause: Boolean = true,
    /**
     * Issue #98 / PCM cache PR-F (#86). Mode C — Full Pre-render. When
     * true, the background pre-render scheduler treats every chapter
     * of a library fiction as a candidate; when false (default), it
     * only renders chapters 1-3 on library-add and chapter N+2 on
     * natural-end. Off by default because a 40-chapter fiction at
     * Piper-high (~70 MB/chapter) blows past the 2 GB quota and
     * thrashes the LRU.
     *
     * PR-F adds the persistence + plumbing; PR-G adds the Settings
     * toggle. Field is invisible to users until PR-G ships.
     */
    val fullPrerender: Boolean = false,
    /**
     * PCM cache PR-G (#86). Total bytes used by the on-disk PCM cache
     * (sum of `<sha>.pcm` file sizes under
     * `${context.cacheDir}/pcm-cache/`). Polled by `:app`'s
     * SettingsRepositoryUiImpl via [CacheStatsRepository] on a 5 s
     * cadence while the Settings screen is in the foreground.
     * Surfaced in Settings → Performance & buffering as
     * "Currently used: 1.4 GB / 2 GB".
     */
    val cacheUsedBytes: Long = 0L,
    /**
     * PCM cache PR-G (#86). User-configured PCM cache quota, in bytes.
     * Default 2 GB. The Settings UI offers four discrete tiers (see
     * [CacheQuotaOptions]: 500 MB / 2 GB / 5 GB / Unlimited);
     * Unlimited is represented as [Long.MAX_VALUE]. Backed by
     * `PcmCacheConfig` (its own DataStore, not the main settings
     * store) so it's intentionally NOT round-tripped through InstantDB
     * sync — different devices may have different storage realities.
     */
    val cacheQuotaBytes: Long = CacheQuotaOptions.DEFAULT_2GB,
    /**
     * Issue #85 — Voice-Determinism preset for the VoxSherpa engine. When
     * true (default), the engine runs with VoxSherpa's calmed VITS defaults
     * (`noise_scale = 0.35`, `noise_scale_w = 0.667`); identical text
     * re-renders sound nearly identical, best for audiobook listeners
     * replaying chapters. When false, the engine runs with sherpa-onnx
     * upstream's Piper defaults (`0.667` / `0.8`); slightly more variable
     * prosody and fuller delivery, closer to vanilla Piper.
     *
     * Toggling forces a model reload — ~1-3s on Piper, ~30s on Kokoro
     * (though Kokoro ignores noise_scale and the setter is a cheap no-op
     * there). The reload is handled in `EnginePlayer` via VoxSherpa's
     * `VoiceEngine.setNoiseScale()` / `setNoiseScaleW()` setters
     * (introduced in `VoxSherpa-TTS` v2.7.4).
     */
    val voiceSteady: Boolean = true,
    /** Memory Palace daemon config (#79). Empty host = source disabled. */
    val palace: UiPalaceConfig = UiPalaceConfig(),
    /**
     * GitHub OAuth session surface (#91). Drives the Sources → GitHub
     * row in Settings. The token itself is never exposed to the UI —
     * only the login + state. See [UiGitHubAuthState].
     */
    val github: UiGitHubAuthState = UiGitHubAuthState.Anonymous,
    /**
     * Issue #203 — when true, the next GitHub Device Flow run requests
     * the `repo` scope (full repo, read/write, includes private). When
     * false (default), Device Flow uses [`GitHubAuthConfig.DEFAULT_SCOPES`]
     * (`read:user public_repo`). Toggling this on a signed-in account
     * doesn't auto-upgrade the live token — the user has to re-run
     * sign-in for the new scope to take effect; the existing session
     * keeps its original scopes until then.
     */
    val githubPrivateReposEnabled: Boolean = false,
    /** Wikipedia language code (#377) — `en`, `de`, `ja`, `simple`, etc.
     *  Selects which Wikipedia host the source talks to:
     *  `<lang>.wikipedia.org`. Default `en`. Empty falls back to the
     *  default so a malformed prefs value doesn't brick the source. */
    val wikipediaLanguageCode: String = "en",
    /** True when a Discord bot token has been stored. The token itself
     *  is never surfaced to the UI — only this boolean. */
    val discordTokenConfigured: Boolean = false,
    /** Selected Discord guild (server) id. Empty until the user picks
     *  one from the populated server picker (which only appears after
     *  the bot token is configured and the `users/@me/guilds` lookup
     *  succeeds). */
    val discordServerId: String = "",
    /** Human-readable Discord server name, captured at picker time so
     *  empty-state copy can name the server without an extra
     *  `users/@me/guilds` round-trip. */
    val discordServerName: String = "",
    /** Issue #403 — same-author coalesce window in minutes. Within
     *  this window, consecutive messages from the same author collapse
     *  into one chapter. Default 5 min; slider range 1-30. */
    val discordCoalesceMinutes: Int = 5,
    /** Issue #462 — true when a Telegram bot token has been stored.
     *  The token itself is never surfaced to the UI; only this
     *  boolean drives the Settings card's "Token configured / paste a
     *  token" branch. v1 has no separate server/channel picker — the
     *  channel list is derived from observed `getUpdates` activity. */
    val telegramTokenConfigured: Boolean = false,
    /**
     * Plugin-seam Phase 3 (#384) — per-plugin on/off keyed by stable
     * plugin id ("kvmr", "royalroad", "notion-techempower", ...). Single source of
     * truth as of v0.5.31: the deleted Phase 1/2 hand-rolled
     * `sourceXxxEnabled` fields have all collapsed into this map.
     *
     * Reads: a fresh install (no migration run yet) sees an empty map;
     * the settings impl's [SourcePluginsMapMigration] seeds the map
     * from the legacy `pref_source_xxx_enabled` boolean keys on first
     * launch. Subsequent toggles route through
     * `SettingsRepositoryUi.setSourcePluginEnabled(id, enabled)`.
     *
     * Missing ids fall through to the plugin's `defaultEnabled` in the
     * `BrowseViewModel` projection — so a fresh install before the
     * migration completes still surfaces the right default-on chips.
     */
    val sourcePluginsEnabled: Map<String, Boolean> = emptyMap(),
    /**
     * v0.5.76 — set of stable plugin ids the user has "starred" from
     * the Browse carousel's long-press sheet. Favorited sources are
     * pinned to the front of the carousel in registry order, ahead of
     * non-favorites. The set is purely a UI ordering hint; it does not
     * affect [sourcePluginsEnabled] (a source can be favorited but
     * disabled, in which case it's still hidden until re-enabled).
     *
     * Storage: JSON-encoded `Set<String>` under
     * `pref_source_favorites_v1` (see
     * [`in.jphe.storyvox.data.source.plugin.encodeSourceFavoritesJson`]).
     * No migration is needed — the natural default is empty.
     */
    val favoriteSourceIds: Set<String> = emptySet(),
    /**
     * User-arranged display order for the Browse carousel. Persisted as
     * a JSON array of plugin ids under `pref_source_display_order_v1`.
     * An empty list means "use the default order" (favourites-first,
     * then registry order). When non-empty, sources are displayed in
     * this order, with any sources not in the list appended at the end.
     */
    val sourceDisplayOrder: List<String> = emptyList(),
    /**
     * Plugin-seam Phase 4 (#501) — per-voice-family on/off keyed by
     * stable family id (`voice_piper`, `voice_kokoro`, `voice_kitten`,
     * `voice_azure`). Twin of [sourcePluginsEnabled] for the Plugin
     * Manager's Voice bundles section.
     *
     * When a family id maps to `false`, voices belonging to that
     * family are filtered out of the Voice Library and the picker.
     * Missing ids fall through to each family's `defaultEnabled` in
     * [`in.jphe.storyvox.playback.voice.VoiceFamilyRegistry`].
     *
     * Reads: a fresh install sees an empty map and every family
     * resolves to its declared default; subsequent toggles route
     * through `SettingsRepositoryUi.setVoiceFamilyEnabled(id, enabled)`.
     */
    val voiceFamiliesEnabled: Map<String, Boolean> = emptyMap(),
    /** Notion database id (#233 + #390). Defaults to the baked-in
     *  techempower.org placeholder from `NotionDefaults`; existing
     *  users with a different stored value keep it. Notion accepts
     *  both hyphenated UUID and compact 32-hex forms. Used only in
     *  [notionMode] = `OFFICIAL_PAT`. */
    val notionDatabaseId: String = "",
    /** True when a Notion Internal Integration Token has been stored.
     *  The token itself is never surfaced to the UI — only this
     *  boolean. Empty token = anonymous-mode reader (#393). */
    val notionTokenConfigured: Boolean = false,
    /** Issue #393 — read-path selector. Anonymous-mode (default for
     *  fresh installs and the token-less case) reads the public
     *  techempower.org Notion tree via `www.notion.so/api/v3` without
     *  setup; PAT-mode (set automatically when the user pastes a
     *  token) reads a private workspace database via the official
     *  Notion REST API. Carried as a string here to keep
     *  `:feature-settings` independent of `:source-notion`. Values:
     *  `"ANONYMOUS_PUBLIC"` or `"OFFICIAL_PAT"`. */
    val notionMode: String = "ANONYMOUS_PUBLIC",
    /** Issue #393 — anonymous-mode root page id. The public Notion
     *  page storyvox walks for fictions. Defaults to TechEmpower's
     *  root; users can override to any public Notion page. */
    val notionRootPageId: String = "",
    /** Issue #150 — when ON, a shake during the sleep timer's fade
     *  tail re-arms the timer. Default ON; users with bumpy commutes
     *  can disable to avoid accidental extensions. */
    val sleepShakeToExtendEnabled: Boolean = true,
    /**
     * Issue #195 — per-voice speed/pitch tweaks. Different voices have
     * different "natural pitch centers" and pacing; storing one global
     * value applies the previous voice's offset when the user switches.
     * The maps key on `voiceId` (matches [defaultVoiceId]) and override
     * the global [defaultSpeed] / [defaultPitch] when present. Voices
     * not in the map fall back to the global defaults — the migration
     * path preserves pre-#195 behavior.
     */
    val voiceSpeedOverrides: Map<String, Float> = emptyMap(),
    val voicePitchOverrides: Map<String, Float> = emptyMap(),
    /**
     * Issue #197 — per-voice lexicon override map. Keys are voice IDs
     * (same shape as [voiceSpeedOverrides]); values are absolute file
     * paths to user-provided `.lexicon` files (sherpa-onnx-format IPA /
     * X-SAMPA phoneme dictionaries). Empty / missing voiceId = the
     * engine falls back to its built-in lexicon.
     *
     * Multiple lexicons per voice are supported by comma-joining the
     * paths in a single map value (sherpa-onnx
     * `OfflineTts*ModelConfig.setLexicon()` accepts comma-separated
     * paths and merges them in order). The Settings UI today wires
     * just one picker per voice; the map shape is forward-compatible
     * for a multi-file UI later.
     *
     * Storage: encoded as `voiceId=path;voiceId=path` in DataStore,
     * same flat-string codec as [voiceSpeedOverrides]. The `=` and
     * `;` delimiters require the paths NOT to contain those bytes;
     * SAF-resolved paths from `getExternalFilesDir()` and the per-voice
     * `${filesDir}/lexicons/<voiceId>/` directory both satisfy this
     * (Android FS paths use `/` and alphanumerics).
     *
     * The engine reads at construction time via the static
     * [`in.jphe.storyvox.playback.VoiceEngineQualityBridge.applyLexicon`]
     * field write, so a flip requires the next voice load — Settings
     * forces this by re-applying on active-voice change.
     */
    val voiceLexiconOverrides: Map<String, String> = emptyMap(),
    /**
     * Issue #198 — per-voice Kokoro phonemizer language override map.
     * Keys are voice IDs; values are language codes from
     * [KOKORO_PHONEMIZER_LANGS] (`en`, `es`, `fr`, ...). Empty /
     * missing voiceId = the engine uses the voice's native language.
     *
     * Only Kokoro voices honor this override (Piper voices are
     * per-language, no phonemizer-language indirection). Settings UI
     * only surfaces the picker on Kokoro voice rows; the map can
     * contain entries for Piper voiceIds without effect.
     *
     * Storage: encoded as `voiceId=langCode;voiceId=langCode` in
     * DataStore, same codec as [voiceLexiconOverrides].
     */
    val voicePhonemizerLangOverrides: Map<String, String> = emptyMap(),
    /**
     * Azure Speech Services BYOK config (#182). Empty key = source not
     * yet configured; the picker shows Azure rows greyed-out with a
     * "Configure Azure key →" CTA until [UiAzureConfig.isConfigured]
     * flips to true.
     */
    val azure: UiAzureConfig = UiAzureConfig(),
    /**
     * PR-6 (#185) — Azure offline-fallback toggle. When ON and an
     * Azure synthesis fails with a non-auth error (network out,
     * Azure 5xx after retries, throttled-after-retries), storyvox
     * auto-swaps to the user-chosen [azureFallbackVoiceId] for the
     * remainder of the chapter rather than halting playback. The
     * playback sheet emits a one-shot toast on swap. Default OFF;
     * users with a key opt in if they want offline resilience.
     */
    val azureFallbackEnabled: Boolean = false,
    /**
     * PR-6 (#185) — voice id used when [azureFallbackEnabled] fires.
     * Null until the user picks one in Settings → Cloud voices →
     * "Fall back to local voice." If null while the toggle is on,
     * fallback is a no-op (we have no voice to swap to).
     */
    val azureFallbackVoiceId: String? = null,
    /**
     * Tier 3 (#88) — experimental parallel-synth instance count.
     * Range 1..[PARALLEL_SYNTH_MAX_INSTANCES]. Default 1 = serial
     * (no extra memory, original behavior). Higher values spin up
     * additional VoiceEngine / KokoroEngine instances; the producer
     * dispatches sentences round-robin across all instances.
     *
     * Throughput scales roughly linearly with instance count up to
     * the device's CPU core count, after which OS scheduling
     * overhead dominates.
     *
     * Memory cost scales linearly:
     * - Piper-high: ~150 MB per instance
     * - Kokoro Studio: ~325 MB per instance (multi-speaker model)
     *
     * Conservative defaults (range capped at 8) keep even the
     * heaviest configuration (8× Kokoro = ~2.6 GB) within budget on
     * 6 GB+ devices. On 3 GB devices users should stay at 2 or below.
     *
     * Azure ignores this setting — cloud synth is HTTP, no local
     * engine instances to multiply.
     *
     * Migration from pre-#88-slider boolean toggle: old `true` →
     * count 2, old `false` → count 1.
     */
    val parallelSynthInstances: Int = 1,
    /**
     * Tier 3 (#88) companion slider — sherpa-onnx numThreads passed
     * to each engine instance at loadModel time. 0 = "Auto" (use
     * VoxSherpa's getOptimalThreadCount heuristic, which today
     * returns the available core count). 1..8 = explicit override.
     *
     * Why surface this: Snapdragon 888 (Galaxy Z Flip3) throttles
     * after several minutes of sustained inference; pegging all 8
     * cores at numThreads=8 causes thermal degradation that manifests
     * as a producer slowdown after ~5 min of playback. Lowering to
     * numThreads=5 or 6 keeps the chip under the throttle line and
     * sustains realtime synthesis.
     *
     * Total compute = parallelSynthInstances × synthThreadsPerInstance.
     * Both sliders cap at 8; the practical ceiling is the device's
     * core count + thermal envelope.
     */
    val synthThreadsPerInstance: Int = 0,
    /**
     * Vesper (v0.4.97) — debug overlay master switch. When true, the
     * Reader and home shells draw [DebugOverlay] on top of their
     * normal content as a swipe-down-to-collapse card. Wires through
     * to the same DataStore key as every other Boolean toggle. Default
     * `false` — power users opt in from Settings → Developer.
     *
     * The dedicated `/debug` screen is reachable from Settings →
     * Developer regardless of this toggle; the switch only controls
     * the *overlay* surface.
     */
    val showDebugOverlay: Boolean = false,
    /**
     * Issue #823 — verbose-logging master switch. Default `false`.
     * When `true`, the playback + chapter-download subsystems
     * (PlaybackController, EnginePlayer, ChapterDownloadScheduler,
     * ChapterDownloadWorker) emit Info-level breadcrumbs through
     * [DebugLog][in.jphe.storyvox.data.log.DebugLog] for on-device
     * diagnostics via `adb logcat`. Errors and critical events log
     * regardless of this flag.
     *
     * Per-device pref (NOT synced) — only relevant to whichever
     * device the user is currently diagnosing.
     */
    val debugLogging: Boolean = false,
    /**
     * Issue #383 — per-source Inbox notification toggles. When false,
     * the corresponding backend's update emitter skips writing events
     * to the cross-source Inbox feed (and skips the optional system
     * notification). Default ON across the board — the Inbox tab is
     * opt-out per-source, not opt-in. The toggle only gates the
     * Inbox/notification surface; the source itself stays visible in
     * Browse, and library updates still happen.
     *
     * Only backends that emit events today are surfaced as fields
     * here. Wikipedia / Standard Ebooks / Outline / etc. don't poll
     * for diffs yet (v1 scope) — they're filed as follow-ups; the
     * matching toggle pre-existing on the Inbox section just becomes
     * meaningful when the source starts emitting.
     */
    val inboxNotifyRoyalRoad: Boolean = true,
    val inboxNotifyKvmr: Boolean = true,
    val inboxNotifyWikipedia: Boolean = true,
    /**
     * Accessibility scaffold (Phase 1) — opt-in switches that adapt
     * storyvox for users on TalkBack, Switch Access, or other
     * assistive services. The toggles persist user intent here in
     * [UiSettings]; the actual behavior wiring (theme swap, animation
     * suppression, touch-target bump, TTS pacing) lands in Phase 2
     * agents that consume these fields.
     *
     * Each default is the no-op state — turning the toggle off keeps
     * storyvox's current behavior bit-identical, so a Phase 1 install
     * is indistinguishable from an upgrade without the new fields
     * persisted yet. Phase 2 introduces a separate "auto-on when
     * assistive service detected" overlay that reads
     * [`AccessibilityState`] without flipping these stored prefs —
     * the prefs remain the user's explicit intent, the live state
     * remains the device's current reality, and Phase 2's adapter
     * uses `prefs OR detected` as the effective flag.
     */
    val a11yHighContrast: Boolean = false,
    val a11yReducedMotion: Boolean = false,
    val a11yLargerTouchTargets: Boolean = false,
    /** Extra inter-sentence pause when TalkBack is active. 0..1500 ms,
     *  default 500ms. Phase 2 consumes this when a TalkBack session is
     *  detected; outside TalkBack the slider is inert. */
    val a11yScreenReaderPauseMs: Int = 500,
    /** Controls how chapter headers read out under TalkBack. */
    val a11ySpeakChapterMode: SpeakChapterMode = SpeakChapterMode.Both,
    /** Font scale override applied on top of Android's system font scale.
     *  0.85..1.5; 1.0 = no extra scaling. */
    val a11yFontScaleOverride: Float = 1.0f,
    val a11yReadingDirection: ReadingDirection = ReadingDirection.FollowSystem,
    /**
     * Accessibility scaffold Phase 2 (#488, v0.5.43) — one-shot
     * "TalkBack isn't running" nudge dismissed-flag. The
     * Accessibility subscreen surfaces a brass-edged card prompting
     * the user to enable TalkBack in Android Settings the first time
     * the user interacts with a screen-reader-related row without
     * TalkBack active; dismissal flips this true forever.
     */
    val a11yTalkBackNudgeDismissed: Boolean = false,
    /**
     * v0.5.59 — book-cover fallback style. Controls what
     * [`FictionCoverThumb`] renders when the remote cover URL is
     * missing, expired, or fails to load. Real covers are always shown
     * when they load.
     *
     * Defaults to [CoverStyle.Monogram] — the visual revert from the
     * v0.5.51 BrandedCoverTile (#514). Existing users on v0.5.51..0.5.58
     * who don't have the pref persisted yet land on Monogram on first
     * launch of v0.5.59; users who explicitly prefer the BrandedCoverTile
     * can opt back in via Settings → Appearance → Book cover style.
     */
    val coverStyle: CoverStyle = CoverStyle.Monogram,
    /**
     * Issue #589 — global animation-speed master multiplier. Multiplies
     * into every Compose `tween(N)` site via the
     * `tweenScaled(N)` helper in `:core-ui`. Values:
     *  - `0f` = Off (durations become 0 → instant transitions)
     *  - `0.5f` = Slow (half speed, 2× duration)
     *  - `1f` = Normal (default, no change)
     *  - `1.5f` = Brisk (~33% faster)
     *  - `2f` = Fast (half-duration)
     *
     * Per-device pref (NOT synced) — a 5-year-old's tablet wants slow,
     * a tablet auditor wants fast; capturing one user's preference
     * across devices would push the same hand on every device.
     *
     * Default 1.0 keeps existing storyvox behavior bit-identical until
     * the user opts in.
     *
     * The chip-row picker lives in Settings → Appearance.
     */
    val animationSpeedScale: Float = 1.0f,
    /**
     * Issue #593 — skip-forward / skip-back distance in seconds.
     * Default 30 matches Spotify / Apple Music / Pocket Casts'
     * default 30-second skip. Users on dense audiobook chapters often
     * want shorter (10/15), while users on podcasts often want longer
     * (45/60). Five discrete chip options: 10/15/30/45/60.
     *
     * Per-device pref (NOT synced). Wired through [PlaybackController]
     * via [PlaybackSkipDistanceConfig] so the seek math reads it
     * without taking a feature-module dep.
     */
    val skipDistanceSec: Int = 30,
    /**
     * Issue #594 — SkipPrevious rewind-to-start threshold in seconds.
     * Tapping SkipPrevious *past* this threshold rewinds to the start
     * of the current chapter; tapping it *within* this threshold
     * (right after a chapter start) jumps to the previous chapter.
     * Standard player UX (Apple Music, Spotify, Pocket Casts default
     * to ~3s); storyvox surfaces 1/3/5/10/Off chip options.
     *
     * `Off` (0) means SkipPrevious ALWAYS jumps to the previous
     * chapter — never rewinds to chapter start. Useful for radio /
     * podcast users where every chapter is short and they want fast
     * prev-track navigation.
     *
     * Per-device pref (NOT synced).
     */
    val rewindToStartThresholdSec: Int = 3,
    /**
     * Issue #595 — sleep-timer shake-to-extend duration in minutes.
     * When the sleep timer's fade tail starts and the user shakes the
     * device, the timer extends by this many minutes. Pre-fix this was
     * hardcoded at 15 (PlaybackService.SHAKE_EXTEND_MINUTES). Chip
     * options: 5 / 10 / 15 / 30.
     *
     * Per-device pref (NOT synced).
     */
    val sleepShakeExtendMinutes: Int = 15,
    /**
     * Issue #596 — PCM-cache pre-render window size, in chapters.
     * The pre-render scheduler caches the next N chapters ahead of the
     * current position. Pre-fix this was hardcoded at 5
     * (PrerenderTriggers.DEFAULT_PRERENDER_CHAPTERS, bumped from 3 in
     * #558). Chip options: 1 / 2 / 3 / 5.
     *
     * Per-device pref (NOT synced) — disk + bandwidth vary per
     * device.
     */
    val prerenderChapterCount: Int = 5,
    /**
     * Issue #590 — particle/confetti intensity preset. Controls the
     * brass-ember overlay density + chapter-completion confetti
     * flare. Stored as the enum's `name` under `pref_particle_intensity_v1`.
     *
     * Per-device pref (NOT synced).
     */
    val particleIntensity: UiParticleIntensity = UiParticleIntensity.Subtle,
    /**
     * Issue #591 — skeleton-shimmer style preset. `Off` renders a plain
     * Box, `Pulse` renders an alpha-shimmer rectangle, `Sigil` (the
     * default) renders the 3-layered rotating brass sigil.
     *
     * Per-device pref (NOT synced).
     */
    val skeletonStyle: UiSkeletonStyle = UiSkeletonStyle.Sigil,
    /**
     * Issue #592 — brass alpha-pulse intensity preset. Controls how
     * deep the brass pulse breathes (e.g. on loading dots, the
     * WhyAreWeWaitingPanel sigil, the MagicSkeletonTile center dot).
     * `Subtle` = 0.7..1.0, `Standard` = 0.55..1.0 (current), `Bold` =
     * 0.4..1.0.
     *
     * Per-device pref (NOT synced).
     */
    val brassPulseLevel: UiBrassPulseLevel = UiBrassPulseLevel.Standard,
    /**
     * Issue #597 — network-patience preset. Controls HTTP timeout
     * budgets in source modules' OkHttp clients. `Aggressive` = 5s,
     * `Default` = 10s, `Patient` = 30s.
     *
     * Per-device pref (NOT synced) — network conditions vary per
     * device + carrier.
     */
    val networkPatience: UiNetworkPatience = UiNetworkPatience.Default,
    /**
     * Issue #598 — Android Auto bucket size. Each Auto category
     * (Library / Follows / Recent / New) caps its children at this
     * many items. Default 6 matches the HMI guideline; chips offer
     * 4 / 6 / 8 / 12.
     *
     * Per-device pref (NOT synced) — Auto pairing is device-bound.
     */
    val autoItemsPerCategory: Int = 6,
) {
    /** Speed value the engine should run at right now — the active
     *  voice's override if set, otherwise the global default (#195). */
    val effectiveSpeed: Float
        get() = defaultVoiceId?.let { voiceSpeedOverrides[it] } ?: defaultSpeed

    /** Pitch value the engine should run at right now — the active
     *  voice's override if set, otherwise the global default (#195). */
    val effectivePitch: Float
        get() = defaultVoiceId?.let { voicePitchOverrides[it] } ?: defaultPitch

    /**
     * Lexicon override the engine should use right now (#197). Empty
     * string = no override (engine uses its built-in lexicon). The
     * VoxSherpa bridge reads this on the *next* engine construction,
     * so a flip requires a voice reload to take effect — Settings
     * forces this by re-applying on active-voice change.
     */
    val effectiveLexicon: String
        get() = defaultVoiceId?.let { voiceLexiconOverrides[it] }.orEmpty()

    /**
     * Kokoro phonemizer language override active right now (#198).
     * Empty string = no override (Kokoro uses the voice's native
     * language). Piper voices ignore this entirely.
     */
    val effectivePhonemizerLang: String
        get() = defaultVoiceId?.let { voicePhonemizerLangOverrides[it] }.orEmpty()
}

/**
 * UI projection of the GitHub OAuth session (#91). The Settings row
 * needs to know "are you signed in, who as, do you need to re-auth"
 * — never the token string itself, which stays inside :source-github's
 * `GitHubAuthRepository`.
 */
sealed class UiGitHubAuthState {
    object Anonymous : UiGitHubAuthState()
    data class SignedIn(val login: String?, val scopes: String) : UiGitHubAuthState()
    /**
     * Token at github.com is gone (revoked, rotated). Disk copy intact;
     * settings row shows "Session expired — sign in again" + the same
     * sign-in CTA as [Anonymous].
     */
    object Expired : UiGitHubAuthState()
}

/**
 * UI projection of the MemPalace daemon connection config. The
 * Settings screen shows two fields (host, optional API key) and the
 * "test connection" button feeds [`SettingsRepositoryUi.testPalace`].
 *
 * The api key is shown masked in the field; reading it back hands the
 * unmasked value to the UI so it can pre-populate the password field
 * on a re-edit. This keeps the UX honest — the user typed the secret,
 * they can read it back.
 */
data class UiPalaceConfig(
    val host: String = "",
    val apiKey: String = "",
) {
    val isConfigured: Boolean get() = host.isNotBlank()
}

/**
 * UI projection of the Azure Speech Services BYOK config (#182).
 * Settings → Cloud Voices → Azure shows three fields: masked API key,
 * region dropdown, and a "Test connection" button. Mirrors
 * [UiPalaceConfig]'s read-back-the-secret shape — the user typed the
 * key, they can read it back to verify it's right.
 *
 * The plaintext copy here lives only in the UI projection; the
 * persisted copy is in `EncryptedSharedPreferences` via
 * `AzureCredentials`. Same trust shape as the GitHub PAT and Memory
 * Palace key.
 */
data class UiAzureConfig(
    val key: String = "",
    /** Persisted region id (e.g. `eastus`). Used verbatim in the
     *  endpoint URL, so it stays as a String — supports the
     *  user-pasted "Other" region beyond the curated dropdown. */
    val regionId: String = "eastus",
    /** Display label for the dropdown, derived from [regionId]. Falls
     *  back to the raw id when no curated entry matches (the "Other"
     *  affordance). */
    val regionDisplayName: String = "US East",
) {
    val isConfigured: Boolean get() = key.isNotBlank()
}

/** Default queue depth.
 *
 *  Issue #294 — bumped from 8 → 12. The original 8 was the bare minimum
 *  from #84's exploratory probe era; ~12 hides voice startup spikes on
 *  Flip3-class hardware without entering the amber zone (recommended
 *  max is 64). Per Lyra's first-time-defaults audit. */
const val BUFFER_DEFAULT_CHUNKS: Int = 12

/** Lower bound — 1 in flight + 1 queued is the minimum that gives any back-pressure benefit. */
const val BUFFER_MIN_CHUNKS: Int = 2

/**
 * Conservative tick where the slider color flips amber. Below this we believe
 * the queue is safe on a 3 GB device. Past this, copy intensifies + slider
 * track turns amber → red as the user enters experimental territory. Picked
 * to give Piper-high ≈ 160 s of headroom (≈ 64 chunks × 2.5 s/sentence ≈ 7 MB
 * of PCM); refine as the LMK probe data arrives.
 */
const val BUFFER_RECOMMENDED_MAX_CHUNKS: Int = 64

/**
 * Mechanical upper bound. The LinkedBlockingQueue can hold this many; whether
 * the heap survives is JP's experimental question. 3000 chunks ≈ 330 MB of
 * PCM at 22050 Hz mono — way past the worst-case LMK guess for a 3 GB Helio
 * P22T. JP wants this exposed so listeners can probe the kill threshold; the
 * danger-zone color shifts (amber → red past the recommended max) plus the
 * intensified copy past the recommended tick are the user's brake, not the
 * slider's mechanical max.
 */
const val BUFFER_MAX_CHUNKS: Int = 3000

/** Slider color shifts to red (intensified warning) past this multiple of the recommended max. */
const val BUFFER_DANGER_MULTIPLIER: Int = 4

/**
 * Inter-sentence pause multiplier bounds (issue #109).
 *
 * The base pause table lives in `EngineStreamingSource.trailingPauseMs(...)`
 * — `.`/`?`/`!` get 350 ms, `;`/`:` get 200 ms, `,`/dashes get 120 ms,
 * fallback 60 ms. Storyvox scales every output by the multiplier the user
 * sets in Settings → Performance & buffering.
 *
 * Issue #109 widened the original 3-stop selector (Off=0×, Normal=1×,
 * Long=1.75× under #93) into a continuous slider. The ceiling is now 4×
 * to match the engine's existing internal coerceIn(0f, 4f) clamp — past
 * that the engine truncates anyway. Tick marks at 0×/1×/1.75×/4× anchor
 * the historical stops + the new max.
 *
 * The legacy enum stops are exposed as constants so the slider can render
 * "Off" / "Normal" / "Long" tick labels and the migration code in
 * [SettingsRepositoryUi]'s impl can map old enum names → multiplier.
 */
const val PUNCTUATION_PAUSE_MIN_MULTIPLIER: Float = 0f
const val PUNCTUATION_PAUSE_MAX_MULTIPLIER: Float = 4f
/** Issue #294 — bumped from 1.0× → 0.85×. Web fiction has more dialogue
 *  tags and short paragraphs than audiobooks; 1.00× felt stilted on a
 *  Royal Road / RSS reading session. 0.85× still respects terminators
 *  but lets the cadence flow with the source. Per Lyra's first-time-
 *  defaults audit. */
const val PUNCTUATION_PAUSE_DEFAULT_MULTIPLIER: Float = 0.85f

/** Legacy enum stop multipliers — used by the migration shim and tick labels. */
const val PUNCTUATION_PAUSE_OFF_MULTIPLIER: Float = 0f
const val PUNCTUATION_PAUSE_NORMAL_MULTIPLIER: Float = 1f
const val PUNCTUATION_PAUSE_LONG_MULTIPLIER: Float = 1.75f

/**
 * PCM cache PR-G (#86). The four discrete quota tiers surfaced by
 * Settings → Performance & buffering → "Audio cache size". Stored
 * via `PcmCacheConfig.setQuotaBytes` (its own DataStore); the Settings
 * UI snaps any off-grid stored value to the nearest tier with [snap].
 *
 * Why four discrete options instead of a continuous slider: storage
 * has fewer "in-between" use cases than punctuation pause — a user
 * either wants a small cache (commute device, 32 GB phone) or a
 * roomy one (binge-listening tablet, 128 GB+). Per the PCM-cache
 * design spec.
 */
object CacheQuotaOptions {
    const val LIGHT_500MB: Long = 500L * 1024 * 1024
    const val DEFAULT_2GB: Long = 2L * 1024 * 1024 * 1024
    const val ROOMY_5GB: Long = 5L * 1024 * 1024 * 1024

    /** Sentinel for the "Unlimited" tier. PcmCacheConfig stores this
     *  verbatim (its 100-MB floor's `coerceAtLeast` is a no-op against
     *  [Long.MAX_VALUE]), and PcmCache.evictTo treats any quota greater
     *  than the on-disk total as a no-op. */
    const val UNLIMITED: Long = Long.MAX_VALUE

    /** Ordered list — matches the left-to-right order in the Settings
     *  selector row. */
    val all: List<Long> = listOf(LIGHT_500MB, DEFAULT_2GB, ROOMY_5GB, UNLIMITED)

    /** Short user-facing label for a tier value. Non-tier values fall
     *  through to [formatBytes] so the "Currently used / X" indicator
     *  can render a custom-stored quota cleanly. */
    fun label(bytes: Long): String = when (bytes) {
        LIGHT_500MB -> "500 MB"
        DEFAULT_2GB -> "2 GB"
        ROOMY_5GB -> "5 GB"
        UNLIMITED -> "Unlimited"
        else -> formatBytes(bytes)
    }

    /**
     * Snap an arbitrary value to the nearest discrete tier. Used by
     * the repository setter so a hypothetical future "custom slider"
     * follow-up can store off-grid values without breaking the radio
     * UI today. Anything past half of [UNLIMITED] snaps to Unlimited
     * (the only sane interpretation of a Long-near-max-value).
     */
    fun snap(bytes: Long): Long {
        if (bytes >= UNLIMITED / 2) return UNLIMITED
        val discrete = listOf(LIGHT_500MB, DEFAULT_2GB, ROOMY_5GB)
        return discrete.minBy { kotlin.math.abs(it - bytes) }
    }
}

/**
 * Pretty-print a byte count for the cache "Currently used" indicator
 * and the [CacheQuotaOptions.label] fallback. Rounds GB to one
 * decimal, MB and KB to zero. Matches the spec example
 * "Currently used: 1.4 GB / 2 GB".
 */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024L * 1024 -> "%.0f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/**
 * Realm-sigil version metadata captured at build time. Surfaced in the
 * Settings → About row. A "fantasy"-realm sigil reads as e.g.
 * "Blazing Crown · ef6a4cf3".
 */
data class UiSigil(
    val name: String,
    val realm: String,
    val hash: String,
    val branch: String,
    val dirty: Boolean,
    val built: String,
    val repo: String,
    val versionName: String,
) {
    val commitUrl: String
        get() = if (hash != "dev" && repo.isNotBlank()) "$repo/commit/$hash" else ""

    companion object {
        val UNKNOWN = UiSigil(
            name = "Unsigned · dev",
            realm = "fantasy",
            hash = "dev",
            branch = "unknown",
            dirty = false,
            built = "unknown",
            repo = "",
            versionName = "0.0.0",
        )
    }
}

enum class ThemeOverride { System, Dark, Light }

/**
 * Accessibility scaffold (Phase 1) — chapter-header read-out preference
 * for TalkBack. The values render verbatim as the user-facing radio
 * labels in [`AccessibilitySettingsScreen`]; renaming any of these is a
 * UI-visible change.
 *
 * Phase 2 (the TalkBack adapter agent) consumes the selected value when
 * a chapter header is about to be announced; outside TalkBack the
 * setting is inert. See [UiSettings.a11ySpeakChapterMode].
 */
enum class SpeakChapterMode { Both, NumbersOnly, TitlesOnly }

/**
 * Accessibility scaffold (Phase 1) — system reading-direction escape
 * hatch. `FollowSystem` defers to the device locale (the v0.5.41
 * behavior); `ForceLtr` / `ForceRtl` flip the layout direction
 * unconditionally so a developer can audit RTL rendering without
 * switching the system locale, and so a user on a mismatched-locale
 * device (LTR content on an RTL-defaulted handset, or the reverse)
 * can override.
 *
 * Phase 2 wires this into `LayoutDirection` at the NavHost root; today
 * the radio group only persists the user's intent.
 */
enum class ReadingDirection { FollowSystem, ForceLtr, ForceRtl }

/**
 * Book-cover fallback style — what [`FictionCoverThumb`] renders when
 * the remote cover URL is missing, expired, or fails to load. Real
 * covers are always shown when they load successfully; this enum only
 * controls the fallback tile.
 *
 * Introduced in v0.5.59 as a user-facing opt-out from the v0.5.51
 * BrandedCoverTile experiment (#514). JP's audit found the warm
 * gradient + EB-Garamond title made every fallback feel like the
 * cover, blurring the visual distinction between "we have a cover"
 * and "we're standing in for one". The old [`MonogramSigilTile`] —
 * minimalist sigil + author initial on dark — reads more clearly as a
 * placeholder, so it's the new default.
 *
 *  - [Monogram] — classic minimalist sigil tile + author initial.
 *    Default for new installs and the visual revert for users on
 *    v0.5.51..v0.5.58.
 *  - [Branded] — the v0.5.51 BrandedCoverTile: warm gradient,
 *    sun-disk watermark, EB-Garamond title, brass border.
 *  - [CoverOnly] — show the remote cover when one loads; otherwise
 *    a dim brass-ring outline placeholder (no title, no monogram).
 *    For users who want a strict "real cover or nothing visible".
 *
 * Persisted as the enum's `name` under `pref_cover_style`; unknown
 * values fall back to [Monogram] on read.
 */
enum class CoverStyle { Monogram, Branded, CoverOnly }

/**
 * Issue #590 — particle/confetti intensity. Feature-layer projection
 * of the `:core-ui` `ParticleIntensity` enum; kept separate from the
 * core-ui flavour so the Settings UI doesn't need to import `:core-ui`'s
 * theme package for the chip-row picker.
 *
 * - [None] — no decorative particles. Brass-ember overlay short-circuits
 *   to empty; chapter-completion confetti is suppressed.
 * - [Subtle] — current 6-ember overlay; calm and atmospheric.
 * - [Lush] — 12-ember overlay + extra chapter-completion flare.
 *
 * Stored as the enum's `name` under `pref_particle_intensity_v1`;
 * unknown / future values fall back to [Subtle] on read.
 */
enum class UiParticleIntensity { None, Subtle, Lush }

/**
 * Issue #591 — skeleton-shimmer style. Feature-layer projection of
 * `:core-ui`'s `SkeletonStyle`.
 *
 * - [Off] — plain Box placeholder, no animation.
 * - [Pulse] — alpha-pulse rectangle.
 * - [Sigil] — current 3-layered rotating brass sigil (the v0.5.66
 *   default).
 *
 * Stored as the enum's `name` under `pref_skeleton_style_v1`.
 */
enum class UiSkeletonStyle { Off, Pulse, Sigil }

/**
 * Issue #592 — brass alpha-pulse intensity. Feature-layer projection
 * of `:core-ui`'s `BrassPulseLevel`.
 *
 * - [Subtle] — narrow band, 0.7..1.0. Calmer pulse.
 * - [Standard] — current band, 0.55..1.0.
 * - [Bold] — wide band, 0.4..1.0. More vivid breath.
 *
 * Stored as the enum's `name` under `pref_brass_pulse_v1`.
 */
enum class UiBrassPulseLevel { Subtle, Standard, Bold }

/**
 * Issue #597 — network patience preset. Feature-layer projection of
 * `:core-data`'s `NetworkPatience`.
 *
 * - [Aggressive] — 5 s timeout budget.
 * - [Default] — 10 s (current).
 * - [Patient] — 30 s.
 *
 * Stored as the enum's `name` under `pref_network_patience_v1`.
 */
enum class UiNetworkPatience { Aggressive, Default, Patient }

interface SettingsRepositoryUi {
    val settings: Flow<UiSettings>
    suspend fun setTheme(override: ThemeOverride)
    /**
     * Issue #589 — set the global animation-speed master multiplier.
     * Snapped to the supported chip values [0f, 0.5f, 1f, 1.5f, 2f]
     * on write. Default impl is a no-op so existing test fakes
     * compile without overrides.
     */
    suspend fun setAnimationSpeedScale(scale: Float) = Unit
    /**
     * Issue #593 — set the skip-forward / skip-back distance in
     * seconds. Coerced to [10, 60]. Default impl is a no-op so
     * existing test fakes compile without overrides.
     */
    suspend fun setSkipDistanceSec(seconds: Int) = Unit
    /**
     * Issue #594 — set the SkipPrevious rewind-to-start threshold in
     * seconds. 0 disables the rewind-to-start behavior (SkipPrevious
     * always jumps to previous chapter); other supported values are
     * [1, 3, 5, 10]. Default impl is a no-op so existing test fakes
     * compile without overrides.
     */
    suspend fun setRewindToStartThresholdSec(seconds: Int) = Unit
    /**
     * Issue #595 — set the sleep-timer shake-to-extend duration in
     * minutes. Snapped to [5, 10, 15, 30] on write. Default impl is
     * a no-op so existing test fakes compile without overrides.
     */
    suspend fun setSleepShakeExtendMinutes(minutes: Int) = Unit
    /**
     * Issue #596 — set the PCM-cache pre-render window size, in
     * chapters. Snapped to [1, 2, 3, 5] on write. Default impl is a
     * no-op.
     */
    suspend fun setPrerenderChapterCount(count: Int) = Unit
    /**
     * Issue #590 — set the particle/confetti intensity. Default impl
     * is a no-op.
     */
    suspend fun setParticleIntensity(intensity: UiParticleIntensity) = Unit
    /**
     * Issue #591 — set the skeleton-shimmer style. Default impl is a
     * no-op.
     */
    suspend fun setSkeletonStyle(style: UiSkeletonStyle) = Unit
    /**
     * Issue #592 — set the brass-pulse intensity. Default impl is a
     * no-op.
     */
    suspend fun setBrassPulseLevel(level: UiBrassPulseLevel) = Unit
    /**
     * Issue #597 — set the network-patience preset. Default impl is
     * a no-op.
     */
    suspend fun setNetworkPatience(patience: UiNetworkPatience) = Unit
    /**
     * Issue #598 — set the Android Auto bucket size. Snapped to
     * [4, 6, 8, 12] on write. Default impl is a no-op.
     */
    suspend fun setAutoItemsPerCategory(count: Int) = Unit
    suspend fun setDefaultSpeed(speed: Float)
    suspend fun setDefaultPitch(pitch: Float)
    suspend fun setDefaultVoice(voiceId: String?)
    suspend fun setDownloadOnWifiOnly(enabled: Boolean)
    suspend fun setPollIntervalHours(hours: Int)
    /**
     * Issue #109 — set the inter-sentence pause multiplier (continuous,
     * coerced to [PUNCTUATION_PAUSE_MIN_MULTIPLIER]..[PUNCTUATION_PAUSE_MAX_MULTIPLIER]).
     * Replaces the pre-#109 `setPunctuationPause(mode: PunctuationPause)`.
     */
    suspend fun setPunctuationPauseMultiplier(multiplier: Float)
    /** Issue #193 — toggle high-quality Sonic pitch interpolation
     *  (quality=1 vs upstream default 0). See
     *  [UiSettings.pitchInterpolationHighQuality]. */
    suspend fun setPitchInterpolationHighQuality(enabled: Boolean)

    /**
     * Issue #197 — set or clear the lexicon override for a specific
     * voice. [path] is an absolute file path (or comma-separated paths)
     * to a `.lexicon` file; null or empty clears the override and the
     * engine falls back to its built-in lexicon.
     *
     * The override takes effect on the next voice load. If [voiceId]
     * matches the currently active voice the impl re-applies the
     * static bridge field immediately so the next chapter render uses
     * the new path; the active engine instance keeps its old lexicon
     * until the next loadModel().
     */
    suspend fun setVoiceLexicon(voiceId: String, path: String?)

    /**
     * Issue #198 — set or clear the Kokoro phonemizer language
     * override for a specific voice. [langCode] should be one of
     * [`KOKORO_PHONEMIZER_LANGS`]; null or empty clears the override
     * and the engine uses the voice's native language.
     *
     * No-op on Piper voices at the engine layer (the static field
     * exists only on KokoroEngine), but the map persists per-voice
     * regardless so the UI can surface and clear values uniformly.
     * Caller is responsible for only writing for Kokoro voices —
     * the Settings UI hides the picker on non-Kokoro rows.
     */
    suspend fun setVoicePhonemizerLang(voiceId: String, langCode: String?)
    suspend fun setPlaybackBufferChunks(chunks: Int)
    /** Issue #98 — Mode A toggle. See [UiSettings.warmupWait]. */
    suspend fun setWarmupWait(enabled: Boolean)
    /** Issue #98 — Mode B toggle. See [UiSettings.catchupPause]. */
    suspend fun setCatchupPause(enabled: Boolean)
    /** Issue #98 / PR-F (#86) — Mode C toggle. See [UiSettings.fullPrerender]. */
    suspend fun setFullPrerender(enabled: Boolean)
    /**
     * PCM cache PR-G (#86). Set the PCM cache quota in bytes. The
     * impl snaps to the nearest [CacheQuotaOptions] entry, then
     * persists via `PcmCacheConfig.setQuotaBytes` and runs
     * `PcmCache.evictToQuota` immediately so a tightened cap is
     * honored on the spot.
     *
     * Setting [CacheQuotaOptions.UNLIMITED] disables eviction entirely
     * (PcmCacheConfig stores [Long.MAX_VALUE]; PcmCache.evictTo treats
     * any quota greater than on-disk total as a no-op).
     */
    suspend fun setCacheQuotaBytes(bytes: Long)
    /**
     * PCM cache PR-G (#86). Wipe the entire PCM cache
     * (`PcmCache.clearAll`). Triggered by the destructive-action
     * confirmation in Settings → Performance & buffering. Returns
     * the number of bytes freed (informational; the Settings UI also
     * re-polls [CacheStatsRepository] to confirm the wipe).
     */
    suspend fun clearCache(): Long
    /** Issue #85 — Voice-Determinism preset. See [UiSettings.voiceSteady]. */
    suspend fun setVoiceSteady(enabled: Boolean)
    suspend fun signIn()
    suspend fun signOut()
    /** Memory Palace daemon mutators (#79). */
    suspend fun setPalaceHost(host: String)
    suspend fun setPalaceApiKey(apiKey: String)
    suspend fun clearPalaceConfig()
    /**
     * One-shot reachability probe against the configured daemon.
     * Returns the daemon version on success, an error message on failure.
     */
    suspend fun testPalaceConnection(): PalaceProbeResult

    // ── AI settings (issue #81) ────────────────────────────────────
    /** null disables AI. Picking a spec-only provider has no effect
     *  at runtime (the providers throw NotConfigured) but the
     *  Settings UI surfaces them as "coming soon" rows that are
     *  visible but not actually selectable. */
    suspend fun setAiProvider(provider: UiLlmProvider?)
    suspend fun setClaudeApiKey(key: String?)   // null = clear
    suspend fun setClaudeModel(model: String)
    suspend fun setOpenAiApiKey(key: String?)
    suspend fun setOpenAiModel(model: String)
    suspend fun setOllamaBaseUrl(url: String)
    suspend fun setOllamaModel(model: String)
    suspend fun setVertexApiKey(key: String?)   // null = clear
    suspend fun setVertexModel(model: String)
    /**
     * Issue #219 — install a Google service-account JSON for Vertex
     * auth. The argument is the raw JSON text; the repo validates +
     * encrypts-at-rest. Pass `null` to clear. Setting a non-null JSON
     * also clears any existing API key (the two modes are mutually
     * exclusive). Throws [IllegalArgumentException] from the parse
     * path if the JSON is malformed or not a service-account key —
     * the UI catches and toasts the message.
     */
    suspend fun setVertexServiceAccountJson(json: String?)
    /** Azure Foundry mutators. [setFoundryApiKey] with `null` clears
     *  the encrypted key. [setFoundryServerless] flips the URL template
     *  + body shape — see `AzureFoundryProvider.buildUrl`. */
    suspend fun setFoundryApiKey(key: String?)
    suspend fun setFoundryEndpoint(url: String)
    suspend fun setFoundryDeployment(deployment: String)
    suspend fun setFoundryServerless(serverless: Boolean)
    /** AWS Bedrock BYOK creds. Pass null to clear individual keys.
     *  Both must be set for the provider to be usable; we let the
     *  user save them one at a time so the UI can paste / show / save
     *  flow stays per-field. */
    suspend fun setBedrockAccessKey(key: String?)
    suspend fun setBedrockSecretKey(key: String?)
    suspend fun setBedrockRegion(region: String)
    suspend fun setBedrockModel(model: String)
    suspend fun setSendChapterTextEnabled(enabled: Boolean)
    /** Issue #212 — chat grounding-level toggles. See [UiChatGrounding]. */
    suspend fun setChatGroundChapterTitle(enabled: Boolean)
    suspend fun setChatGroundCurrentSentence(enabled: Boolean)
    suspend fun setChatGroundEntireChapter(enabled: Boolean)
    suspend fun setChatGroundEntireBookSoFar(enabled: Boolean)
    /** Issue #217 — "Carry memory across fictions" toggle. See
     *  [UiAiSettings.carryMemoryAcrossFictions]. */
    suspend fun setCarryMemoryAcrossFictions(enabled: Boolean)
    /** Issue #216 — "Allow the AI to take actions" toggle. See
     *  [UiAiSettings.actionsEnabled]. */
    suspend fun setAiActionsEnabled(enabled: Boolean)
    suspend fun acknowledgeAiPrivacy()
    /**
     * Anthropic Teams (OAuth) — local sign-out. Wipes the bearer +
     * refresh + scope cache. Remote revoke at console.anthropic.com
     * requires a client_secret we don't have (public-client posture);
     * Settings UI deep-links the user to manage sessions there. (#181)
     */
    suspend fun signOutTeams()
    /** Wipe all AI configuration — provider/keys/URLs. */
    suspend fun resetAiSettings()

    // ── GitHub OAuth (#91) ─────────────────────────────────────────
    /**
     * Local sign-out from GitHub. Clears the encrypted token + identity
     * metadata. Remote revoke at github.com requires the client_secret
     * we don't have — Settings UI deep-links the user to
     * `github.com/settings/applications` if they want to revoke fully.
     */
    suspend fun signOutGitHub()

    /**
     * Issue #203 — toggle "Enable private repos" preference. ON makes the
     * next Device Flow request the `repo` scope; OFF goes back to
     * `public_repo`. The currently-signed-in token is unaffected —
     * existing sessions keep the scopes they were granted with until the
     * user re-signs-in.
     */
    suspend fun setGitHubPrivateReposEnabled(enabled: Boolean)

    /**
     * Plugin-seam Phase 3 (#384) — toggle a `@SourcePlugin`-registered
     * backend by its stable id. Updates the JSON-serialised
     * `sourcePluginsEnabled` map; the BrowseViewModel projection reads
     * the map back. Unknown ids (no plugin registered) write to the map
     * regardless, so an out-of-tree backend that pre-populates its
     * toggle state before its annotation lands still round-trips.
     *
     * As of v0.5.31 this is the single setter for per-source on/off;
     * the Phase 1/2 hand-rolled `setSourceXxxEnabled` methods have all
     * collapsed into this one entry point.
     */
    suspend fun setSourcePluginEnabled(id: String, enabled: Boolean)

    /**
     * v0.5.76 — toggle a source plugin's "favorite" star by stable id.
     * Favorited ids are added to [UiSettings.favoriteSourceIds];
     * unfavorited ids are removed. The Browse carousel projection
     * re-sorts so favorites land at the front of the row in registry
     * order. Favoriting a disabled source is allowed (the star will
     * apply once the source is re-enabled).
     */
    suspend fun setSourceFavorite(id: String, favorite: Boolean)

    /**
     * Persist the user's custom display order for the Browse carousel.
     * An empty list clears the custom order, reverting to the default
     * favourites-first layout. The order syncs across devices.
     */
    suspend fun setSourceDisplayOrder(order: List<String>)

    /**
     * Plugin-seam Phase 4 (#501) — toggle a voice family by its stable
     * id (`voice_piper`, `voice_kokoro`, `voice_kitten`, `voice_azure`).
     * Updates [UiSettings.voiceFamiliesEnabled]; the Voice Library
     * projection filters voices whose engine type maps to a disabled
     * family. Unknown ids are still written so a future family that
     * pre-toggles its state before its registry entry lands round-trips.
     */
    suspend fun setVoiceFamilyEnabled(id: String, enabled: Boolean)

    /** Issue #245 — Outline self-hosted-wiki backend config. */
    val outlineHost: Flow<String>
    suspend fun setOutlineHost(host: String)
    suspend fun setOutlineApiKey(apiKey: String)
    suspend fun clearOutlineConfig()

    /** Issue #377 — set the Wikipedia language code (`en`, `de`,
     *  `ja`, `simple`, ...). Trimmed + lowercased before persistence;
     *  empty falls back to the default. */
    suspend fun setWikipediaLanguageCode(code: String)

    /** Issue #233 — set the Notion database id the source queries.
     *  Both hyphenated UUID and compact 32-hex forms accepted; the
     *  impl normalizes whitespace. Empty falls back to the baked-in
     *  techempower.org default (#390). */
    suspend fun setNotionDatabaseId(id: String)
    /** Issue #233 — persist or clear the Notion integration token.
     *  Pass null or empty to clear. Stored encrypted alongside the
     *  Outline / palace tokens in `storyvox.secrets`. */
    suspend fun setNotionApiToken(token: String?)

    /** Issue #403 — persist or clear the Discord bot token. Pass
     *  null or empty to clear. Stored encrypted under
     *  `pref_source_discord_token` in `storyvox.secrets`. */
    suspend fun setDiscordApiToken(token: String?)
    /** Issue #403 — persist the selected Discord server id +
     *  human-readable name. Both captured at the moment the user
     *  picks the server from the populated picker. Pass empty
     *  strings to clear the selection. */
    suspend fun setDiscordServer(serverId: String, serverName: String)
    /** Issue #403 — same-author message coalesce window (minutes).
     *  Slider range 1-30; impl clamps. */
    suspend fun setDiscordCoalesceMinutes(minutes: Int)
    /**
     * Issue #403 — fetch the list of guilds (servers) the configured
     * bot has been invited to. Drives the Settings server picker
     * dropdown. Returns an empty list when no token is configured or
     * the call fails — the UI handles both as "nothing to pick from"
     * (with a hint that the token is missing for the first case).
     */
    suspend fun fetchDiscordGuilds(): List<Pair<String, String>>

    /** Issue #462 — persist or clear the Telegram bot token. Pass
     *  null/blank to clear. Stored encrypted under
     *  `pref_source_telegram_token` in `storyvox.secrets`. */
    suspend fun setTelegramApiToken(token: String?)

    /**
     * Issue #462 — verify the bot token by hitting Telegram's
     * `getMe` endpoint. Returns the bot's @username on success,
     * null on any failure (no token, bad token, network out).
     * Drives the Settings card's "Authenticated as @bot_name"
     * confirmation row.
     */
    suspend fun probeTelegramBot(): String?

    /**
     * Issue #462 — probe `getUpdates` once and return
     * (chatId, displayTitle) pairs for the public channels the
     * bot has been invited to. Empty when no token, no observed
     * channels yet, or the call fails — the UI handles all three
     * as "your bot has not been added to any channels yet" with a
     * clarifying line about the bot-after-invite history
     * limitation.
     */
    suspend fun fetchTelegramChannels(): List<Pair<String, String>>

    /** Issue #236 — manage subscribed feed URLs. */
    suspend fun addRssFeed(url: String)
    suspend fun removeRssFeed(fictionId: String)
    suspend fun removeRssFeedByUrl(url: String)
    val rssSubscriptions: Flow<List<String>>

    /** Issue #235 — currently-picked SAF folder URI (or null). */
    val epubFolderUri: Flow<String?>
    suspend fun setEpubFolderUri(uri: String)
    suspend fun clearEpubFolder()

    /** Issue #246 — curated suggested feeds, fetched from the
     *  jphein/storyvox-feeds GitHub repo on first observation,
     *  cached for the app session, falling back to a baked-in list
     *  on parse failure / first-launch-offline. */
    val suggestedRssFeeds: Flow<List<SuggestedFeed>>

    /** Issue #150 — sleep timer shake-to-extend on/off. */
    suspend fun setSleepShakeToExtendEnabled(enabled: Boolean)

    // ── Azure Speech Services BYOK (#182) ──────────────────────────
    /** Persist the user's Azure subscription key. `null` clears it. */
    suspend fun setAzureKey(key: String?)
    /** Persist the Azure resource region id (e.g. `eastus`). The id is
     *  used verbatim in the endpoint URL; pass a raw region id to
     *  support the "Other" affordance. */
    suspend fun setAzureRegion(regionId: String)
    /** Wipe both key and region — Settings "Forget key" button. */
    suspend fun clearAzureCredentials()
    /**
     * One-shot reachability probe against the configured Azure region
     * + key. Hits the `voices/list` endpoint, which is a cheap GET
     * that exercises auth + DNS + TLS but doesn't bill any synthesis
     * characters. Returns the voice count on success, an error
     * message on failure.
     */
    suspend fun testAzureConnection(): AzureProbeResult

    /** PR-6 (#185) — Azure offline-fallback toggle. */
    suspend fun setAzureFallbackEnabled(enabled: Boolean)
    /** PR-6 (#185) — voice id used when fallback fires. Pass null to
     *  clear so the toggle becomes a no-op until a voice is picked. */
    suspend fun setAzureFallbackVoiceId(voiceId: String?)

    /** Tier 3 (#88) — experimental parallel-synth instance count
     *  (range 1..[PARALLEL_SYNTH_MAX_INSTANCES]). 1 = serial, higher
     *  values fan out across N engines. */
    suspend fun setParallelSynthInstances(count: Int)

    /** Tier 3 companion (#88) — numThreads override per engine.
     *  0 = Auto (VoxSherpa's getOptimalThreadCount heuristic).
     *  1..[PARALLEL_SYNTH_MAX_INSTANCES] = explicit value passed to
     *  sherpa-onnx. */
    suspend fun setSynthThreadsPerInstance(count: Int)

    /**
     * Vesper (v0.4.97) — toggle the debug overlay master switch
     * ([UiSettings.showDebugOverlay]). Default implementation no-ops so
     * existing fakes in the feature test suite don't need to be touched
     * — only the real DataStore impl persists the value. Test fakes
     * that *do* care about the overlay's persistence can override.
     */
    suspend fun setShowDebugOverlay(enabled: Boolean) {
        // default no-op for test fakes; SettingsRepositoryUiImpl overrides.
    }

    /**
     * Issue #823 — toggle the verbose-logging master switch
     * ([UiSettings.debugLogging]). Default impl is a no-op so existing
     * test fakes don't need to be touched; the real DataStore impl
     * persists the value and [StoryvoxApp][in.jphe.storyvox.StoryvoxApp]
     * propagates emissions to
     * [DebugLog][in.jphe.storyvox.data.log.DebugLog].
     */
    suspend fun setDebugLogging(enabled: Boolean) {
        // default no-op for test fakes; SettingsRepositoryUiImpl overrides.
    }

    // ── v0.5.00 milestone celebration (Calliope) ───────────────────
    /**
     * Calliope (v0.5.00) — one-time celebration state for the
     * graduation milestone. Drives the brass "thank-you" dialog and
     * the chapter-complete confetti easter-egg. See [MilestoneState]
     * for the field semantics. Default emits an inert state so test
     * fakes neither flicker the dialog nor the confetti — the only
     * caller that needs real data is the production app's Settings
     * repo, which reads from DataStore + BuildConfig.
     */
    val milestoneState: Flow<MilestoneState>
        get() = kotlinx.coroutines.flow.flowOf(MilestoneState())

    /** Flip the "saw the milestone dialog" flag to true so it never
     *  shows again on this install. Default no-op for fakes; the
     *  DataStore impl persists. */
    suspend fun markMilestoneDialogSeen() {}

    /** Flip the "saw the confetti easter-egg" flag to true so it
     *  never fires again on this install. Default no-op for fakes;
     *  the DataStore impl persists. */
    suspend fun markMilestoneConfettiShown() {}

    // ── Issue #383 — Inbox per-source mute toggles ─────────────────
    /**
     * Per-source Inbox notification toggles. When OFF, the backend's
     * update emitter (poller, watcher) does NOT write to the
     * `inbox_event` table for that source. Default ON across the
     * board; default impls here let test fakes that don't care about
     * the Inbox surface ignore the calls.
     */
    suspend fun setInboxNotifyRoyalRoad(enabled: Boolean) {}
    suspend fun setInboxNotifyKvmr(enabled: Boolean) {}
    suspend fun setInboxNotifyWikipedia(enabled: Boolean) {}

    // ── Accessibility scaffold (Phase 1) ───────────────────────────
    /**
     * Setters for the new Accessibility subscreen toggles. Phase 1
     * only persists the user's intent — no behavior is wired yet.
     * Phase 2 agents (high-contrast theme, TalkBack adapter,
     * reduced-motion enforcer) consume [UiSettings.a11y*] and the
     * Hilt-provided `Flow<AccessibilityState>` to actually adapt the
     * app.
     *
     * Defaults are no-ops so existing test fakes don't have to
     * implement them; the real DataStore impl in `:app` overrides.
     */
    suspend fun setA11yHighContrast(enabled: Boolean) {}
    suspend fun setA11yReducedMotion(enabled: Boolean) {}
    suspend fun setA11yLargerTouchTargets(enabled: Boolean) {}
    suspend fun setA11yScreenReaderPauseMs(ms: Int) {}
    suspend fun setA11ySpeakChapterMode(mode: SpeakChapterMode) {}
    suspend fun setA11yFontScaleOverride(scale: Float) {}
    suspend fun setA11yReadingDirection(direction: ReadingDirection) {}
    /**
     * Accessibility scaffold Phase 2 (#488, v0.5.43) — flip the
     * TalkBack-install nudge dismissed flag. One-way: once true, the
     * card never reappears for this install (a fresh DataStore wipe
     * resets, but a Settings → Accessibility tap won't).
     */
    suspend fun setA11yTalkBackNudgeDismissed(dismissed: Boolean) {}

    // ── Appearance (v0.5.59) ───────────────────────────────────────
    /**
     * Book-cover fallback style — see [UiSettings.coverStyle] and
     * [`CoverStyle`]. Default no-op for test fakes; the DataStore
     * impl in `:app` persists under `pref_cover_style`, mirrors
     * through the `:core-sync` allowlist, and triggers a recomposition
     * via the [`LocalCoverStyle`] CompositionLocal so the next
     * `FictionCoverThumb` render picks the new tile.
     */
    suspend fun setCoverStyle(style: CoverStyle) {}

    // ── InstantDB magical sign-in onboarding (#500) ────────────────
    /**
     * Issue #500 — has the user dismissed (or accepted) the first-
     * launch InstantDB sync onboarding card? Default false; the
     * onboarding card mounted at the app root checks this flow to
     * decide whether to render. Flips to true once via
     * [markSyncOnboardingDismissed] and stays there for the life of
     * this install — per the issue's "Skip is fully respected — never
     * re-prompt this flow" requirement.
     *
     * Default emits true so test fakes never accidentally show the
     * card; the real DataStore impl in `:app` overrides with the
     * persisted value (defaulting to false on first launch).
     */
    val syncOnboardingDismissed: Flow<Boolean>
        get() = kotlinx.coroutines.flow.flowOf(true)

    /** Flip the sync-onboarding-dismissed flag to true. Idempotent.
     *  Default no-op for fakes; the DataStore impl persists. */
    suspend fun markSyncOnboardingDismissed() {}

    // ── v1.0 first-launch onboarding (#599) ───────────────────────────
    /**
     * Issue #599 (v1.0 blocker) — has the user completed (or skipped)
     * the three-screen first-launch welcome flow? Default false; the
     * flow renders before the [VoicePickerGate] when this is false, and
     * persists `true` on either "Get started → Pick a voice → Pick what
     * to listen to" completion OR an explicit "I've used storyvox
     * before" skip. Once true the flow never re-prompts; Settings →
     * Advanced → "Reset onboarding" flips it back to false for testing.
     *
     * Defaults to `true` here so test fakes never accidentally show the
     * flow during unit tests for unrelated surfaces; the real DataStore
     * impl in `:app` overrides with the persisted value (defaulting to
     * `false` on first launch).
     */
    val onboardingCompletedV1: Flow<Boolean>
        get() = kotlinx.coroutines.flow.flowOf(true)

    /** Flip the onboarding-completed flag to true. Idempotent; called
     *  from the final onboarding screen's CTA and from the "skip"
     *  affordance on the Welcome screen. */
    suspend fun markOnboardingCompletedV1() {}

    /** Flip the onboarding-completed flag back to false — Settings →
     *  Advanced → "Reset onboarding" exposes this so JP (and any QA
     *  flow) can re-experience the welcome screens without clearing
     *  app data. */
    suspend fun resetOnboardingV1() {}

    // ── Reader auto-scroll toggle (#946) ───────────────────────────
    /**
     * Issue #946 — magical toggle for the reader's
     * "scroll-to-current-sentence" auto-follow behavior. When true
     * (default), the reading view smoothly scrolls each highlighted
     * sentence into the 40% viewport band as the engine advances.
     * When false, the chapter body stays put — the user scrolls
     * manually at their own pace while audio continues.
     *
     * Default emits `true` here so test fakes get the production
     * behavior without opting in; the real DataStore impl overrides
     * with the persisted value (also defaulting `true` on first
     * launch — feature is on by default to preserve the read-along
     * UX that's been there since v0.1).
     */
    val readerAutoScrollEnabled: Flow<Boolean>
        get() = kotlinx.coroutines.flow.flowOf(true)

    /** Persist the reader auto-scroll toggle (#946). Wired to the
     *  brass IconButton in the reader controls overlay. Default no-op
     *  for fakes; the DataStore impl persists. */
    suspend fun setReaderAutoScrollEnabled(enabled: Boolean) {}
}

/**
 * v0.5.00 milestone state. All three fields are independent gates:
 *
 *  - [qualifies] is true when the build's version is v0.5.00 or
 *    later. On lower builds nothing in this struct matters — both
 *    surfaces stay dark. Computed from [BuildConfig.VERSION_NAME]
 *    in the production repo; always false in tests.
 *  - [dialogSeen] flips to true after the user dismisses the
 *    one-time dialog. The dialog renders only when `qualifies &&
 *    !dialogSeen`.
 *  - [confettiShown] flips to true after the first natural
 *    chapter-completion drops the celebration overlay. Confetti
 *    renders only when `qualifies && !confettiShown` AND a
 *    PlaybackUiEvent.ChapterDone arrives. The two are deliberately
 *    independent — the user might dismiss the dialog before
 *    listening, or never open the dialog and just finish a chapter.
 *
 * Default instance is the "inert" state safe for previews + test
 * fakes — nothing fires.
 */
data class MilestoneState(
    val qualifies: Boolean = false,
    val dialogSeen: Boolean = false,
    val confettiShown: Boolean = false,
)

/** Tier 3 (#88) slider bounds. Min 1 (serial), max 8 (the Snapdragon
 *  888 / Helio P22T core count ceiling — beyond 8 the OS scheduler
 *  dominates and instance memory cost becomes pathological). */
const val PARALLEL_SYNTH_MIN_INSTANCES: Int = 1
const val PARALLEL_SYNTH_MAX_INSTANCES: Int = 8

/** Outcome of [`SettingsRepositoryUi.testPalaceConnection`]. */
sealed class PalaceProbeResult {
    data class Reachable(val daemonVersion: String) : PalaceProbeResult()
    data class Unreachable(val message: String) : PalaceProbeResult()
    object NotConfigured : PalaceProbeResult()
}

/**
 * Outcome of [`SettingsRepositoryUi.testAzureConnection`] (#182).
 * Distinct cases for `AuthFailed` and `Unreachable` so the Settings
 * UI can render different copy ("re-paste your key" vs. "check your
 * connection") without string-matching on error messages.
 */
sealed class AzureProbeResult {
    /** voices/list returned 200 — key + region are good. */
    data class Reachable(val voiceCount: Int) : AzureProbeResult()
    /** 401 / 403 — key rejected. UX: prompt to re-paste. */
    data class AuthFailed(val message: String) : AzureProbeResult()
    /** Network failure, 5xx, or any other transport-level problem. */
    data class Unreachable(val message: String) : AzureProbeResult()
    /** No key configured — Test button still fires this for clarity. */
    object NotConfigured : AzureProbeResult()
}

/**
 * Issue #178 — UI-side facade for Royal Road's saved-tags ↔
 * storyvox-followed-tags two-way mirror. Lives in `:feature/api`
 * (this file) so the Settings → Account composable can consume
 * the surface without `:feature` depending on `:source-royalroad`.
 *
 * Implementation lives in `:app`'s
 * [`in.jphe.storyvox.di.RealRoyalRoadTagSyncUi`], which bridges
 * to the `:source-royalroad`'s `RoyalRoadTagSyncCoordinator` and
 * `FollowedTagsStore`. Same shape as the existing
 * [SettingsRepositoryUi] / [FictionRepositoryUi] split.
 */
interface RoyalRoadTagSyncUi {
    /** Hot stream of the `pref_rr_tag_sync_enabled` toggle. */
    val syncEnabled: Flow<Boolean>

    /** Hot stream of the `pref_rr_tag_sync_last_synced_at` Long
     *  (epoch ms), or `0L` if no sync has happened yet. */
    val lastSyncedAt: Flow<Long>

    /** Toggle the periodic + per-action sync on or off. Default
     *  true once the user is signed in to Royal Road. */
    suspend fun setSyncEnabled(enabled: Boolean)

    /** Fire a single sync round-trip immediately. Used by the
     *  "Sync now" brass button. Returns a UI-visible outcome
     *  so the row can render a one-shot status line. */
    suspend fun syncNow(): UiTagSyncOutcome
}

/** UI-side projection of `RoyalRoadTagSyncCoordinator.Outcome`. */
sealed interface UiTagSyncOutcome {
    data class Ok(
        val tagsPulledIn: Int,
        val tagsPushedOut: Int,
        val tagsRemovedLocally: Int,
        val tagsRemovedRemotely: Int,
        val syncedAt: Long,
    ) : UiTagSyncOutcome

    /** User isn't signed in to RR — skipped silently. */
    data object NotAuthenticated : UiTagSyncOutcome

    /** User disabled sync. */
    data object Disabled : UiTagSyncOutcome

    /** Network or parser failure. Spec #178: don't surface these
     *  as toasts; the Settings row shows a dim status line and
     *  the next 24h periodic retries silently. */
    data class Failed(val message: String) : UiTagSyncOutcome
}
