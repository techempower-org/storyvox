package `in`.jphe.storyvox.data.source.model

/**
 * Lightweight representation of a fiction used in lists (browse, search, follows).
 *
 * Sources may leave optional fields null when the listing page doesn't expose them;
 * the gap is filled later by [FictionDetail] from a detail-page fetch.
 */
data class FictionSummary(
    val id: String,
    val sourceId: String,
    val title: String,
    val author: String,
    val coverUrl: String? = null,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val status: FictionStatus = FictionStatus.ONGOING,
    val chapterCount: Int? = null,
    val rating: Float? = null,
    /** Issue #211 — true when storyvox has pushed a follow to the
     *  source's account (e.g. Royal Road's `/fictions/setbookmark`)
     *  or the periodic pull observed the user follows this fiction.
     *  Defaults to false; only meaningful for sources with an
     *  account-side follow concept (RR today). */
    val followedRemotely: Boolean = false,
    /** Issue #382 — populated from `FictionSource.supportsFollow` at
     *  the repository mapper layer. Drives the FictionDetail Follow
     *  button's visibility on the UI side without each consumer
     *  needing to know which sources happen to support follow. */
    val supportsFollow: Boolean = false,
    /** Issue #793 — wall-clock time the row joined the user's library
     *  (`fiction.addedToLibraryAt`). Null on rows the user hasn't
     *  added (browse / search listings). Drives the Library "recently
     *  added" / "longest unread" sort modes. */
    val addedAt: Long? = null,
    /** Issue #793 — wall-clock time the most-recent playback-position
     *  upsert for this fiction. Null when the user has never started
     *  the book. Populated only at the Library-sort join site; null
     *  on browse / search summaries. Drives "recently played" +
     *  "longest unread" sort modes. */
    val lastPlayedAt: Long? = null,
    /**
     * Issue #981 — true when this is an un-hydrated synced placeholder
     * (`fiction.metadataFetchedAt == 0`): a library/follows row added on
     * another device that carries only id + sourceId + the sentinel
     * `title = "Loading…"`. The `MetadataBackfillWorker` hydrates these in
     * the background; the Library card uses this to show a neutral
     * "Loading…" caption rather than the raw stored title. Always false
     * for browse / search / fully-fetched rows.
     */
    val isPlaceholder: Boolean = false,
    /**
     * Issue #981 — true when the most recent back-fill attempt for a
     * placeholder row FAILED (`fiction.metadataBackfillFailedAt != null`):
     * the source couldn't be reached (auth-gated, removed upstream,
     * network). The Library card shows a distinct "Couldn't load" state
     * instead of an eternal "Loading…"; the worker still retries after a
     * cool-down. Only meaningful when [isPlaceholder] is also true.
     */
    val backfillFailed: Boolean = false,
)
