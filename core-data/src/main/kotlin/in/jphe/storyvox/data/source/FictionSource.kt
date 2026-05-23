package `in`.jphe.storyvox.data.source

import `in`.jphe.storyvox.data.source.filter.FilterDimension
import `in`.jphe.storyvox.data.source.filter.FilterState
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.data.source.model.SearchQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Read-side abstraction over a fiction-hosting site (Royal Road today, others later).
 *
 * Implementations are stateless w.r.t. the caller — caching is the repository
 * layer's job. Auth-gated calls should return [FictionResult.AuthRequired]
 * gracefully rather than throwing when no session is available.
 *
 * All `suspend` calls are expected to be cancellable and to surface IO/parse
 * errors as [FictionResult.NetworkError] (with `cause` populated). A non-Success
 * return is the normal failure path; throwing is reserved for programmer errors.
 */
interface FictionSource {

    /** Stable identifier persisted with each cached row, e.g. `"royalroad"`. */
    val id: String

    /** Human-readable name for UI, e.g. `"Royal Road"`. */
    val displayName: String

    /**
     * Issue #382 — true when this source has a meaningful account-side
     * follow concept that storyvox can push to via [setFollowed]. False
     * (the default) means the FictionDetail Follow button stays hidden
     * for fictions from this source. Royal Road overrides to true today;
     * AO3 / GitHub / Wikipedia can override when their respective
     * sign-in flows land.
     *
     * The default-false posture means a backend that never overrides
     * gets the right (no-Follow-button) behavior automatically.
     */
    val supportsFollow: Boolean get() = false

    // ─── browse ───────────────────────────────────────────────────────────

    /** Front-page popular / "best" list, paginated. */
    suspend fun popular(page: Int = 1): FictionResult<ListPage<FictionSummary>>

    /** New releases / latest updates, paginated. */
    suspend fun latestUpdates(page: Int = 1): FictionResult<ListPage<FictionSummary>>

    /** Best-by-genre listing, paginated. */
    suspend fun byGenre(genre: String, page: Int = 1): FictionResult<ListPage<FictionSummary>>

    /** Free-form search. */
    suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>>

    // ─── detail ───────────────────────────────────────────────────────────

    /**
     * Fetch fiction-detail page (synopsis, chapter list, metadata).
     * Does NOT fetch chapter bodies.
     */
    suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail>

    /**
     * Fetch a single chapter's body. Implementations should sanitize/clean
     * as much as possible before returning.
     */
    suspend fun chapter(fictionId: String, chapterId: String): FictionResult<ChapterContent>

    // ─── auth-gated ───────────────────────────────────────────────────────

    /**
     * The user's source-side "Follows" list. Returns
     * [FictionResult.AuthRequired] if no session is available.
     */
    suspend fun followsList(page: Int = 1): FictionResult<ListPage<FictionSummary>>

    /**
     * Toggle follow on the source. Implementations may no-op when anonymous
     * and return [FictionResult.AuthRequired].
     */
    suspend fun setFollowed(fictionId: String, followed: Boolean): FictionResult<Unit>

    // ─── catalog ──────────────────────────────────────────────────────────

    /** All genres the source supports — for the genre picker UI. */
    suspend fun genres(): FictionResult<List<String>>

    // ─── polling ──────────────────────────────────────────────────────────

    /**
     * Cheap revision check for cheap-poll: returns a token (e.g. a
     * commit SHA, a feed-level ETag, a Last-Modified header) that the
     * poll worker can compare against the previously-stored token to
     * decide whether the upstream source has changed at all. If the
     * tokens match, the worker skips the heavier `fictionDetail` fetch.
     *
     * Default implementation returns `Success(null)` — sources that
     * don't have a cheap revision check (Royal Road today) opt out by
     * not overriding, and the worker falls back to the full path.
     *
     * Returning a non-null token implicitly tells the worker "if you
     * see this same token again, nothing has changed". Implementations
     * MUST therefore mint a fresh token whenever any chapter-affecting
     * content changes (typically: head commit on the default branch
     * for Git-backed sources).
     *
     * Errors should come back as a [FictionResult] failure variant
     * so the worker can choose to fall back to the full path; throwing
     * is reserved for programmer errors.
     */
    suspend fun latestRevisionToken(fictionId: String): FictionResult<String?> =
        FictionResult.Success(null)

    // ─── filter contract ────────────────────────────────────────────────

    fun filterDimensions(): List<FilterDimension> = emptyList()

    fun applyFilters(base: SearchQuery, state: FilterState): SearchQuery = base

    // ─── eventing ─────────────────────────────────────────────────────────

    /**
     * Optional hot stream the source can emit to when it observes content
     * change (e.g. a polling worker discovering a new chapter). Default emits
     * nothing — repositories drive change notification themselves in v1.
     */
    fun events(): Flow<FictionSourceEvent> = emptyFlow()
}

/** Events the source can push when it learns of upstream change. */
sealed interface FictionSourceEvent {
    data class NewChapter(val fictionId: String, val chapterId: String) : FictionSourceEvent
    data class FictionUpdated(val fictionId: String) : FictionSourceEvent
}

/**
 * Escape hatch for sources that hit Cloudflare or another bot-wall — implemented
 * by the source module (`:source-royalroad`) using a hidden Android WebView so
 * the JS challenge actually executes.
 *
 * Declared in `:core-data` so the download worker can consume it without taking
 * a hard dep on the source module.
 */
interface WebViewFetcher {
    /**
     * Fetch [url] through a one-shot WebView. Returns the rendered HTML on
     * success. If a [cookieHeader] is provided, the implementation should
     * inject it into the WebView cookie jar before navigation.
     */
    suspend fun fetch(url: String, cookieHeader: String? = null): FictionResult<String>
}
