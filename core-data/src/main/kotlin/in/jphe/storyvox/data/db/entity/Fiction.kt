package `in`.jphe.storyvox.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import `in`.jphe.storyvox.data.source.model.FictionStatus

/**
 * Row representing a fiction we've ever interacted with — library, follows, or
 * just visited. Library rows are persisted indefinitely; non-library rows are
 * effectively a metadata cache.
 */
@Entity(
    tableName = "fiction",
    indices = [
        Index(value = ["inLibrary"]),
        Index(value = ["followedRemotely"]),
        Index(value = ["sourceId", "lastUpdatedAt"]),
        Index(value = ["inLibrary", "addedToLibraryAt"]),
        Index(value = ["followedRemotely", "lastUpdatedAt"]),
    ],
)
data class Fiction(
    @PrimaryKey val id: String,
    val sourceId: String,
    val title: String,
    val author: String,
    val authorId: String? = null,
    val coverUrl: String? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val status: FictionStatus = FictionStatus.ONGOING,
    val chapterCount: Int = 0,
    val wordCount: Long? = null,
    val rating: Float? = null,
    val views: Long? = null,
    val followers: Int? = null,
    val lastUpdatedAt: Long? = null,
    val firstSeenAt: Long,
    val metadataFetchedAt: Long,
    val inLibrary: Boolean = false,
    val addedToLibraryAt: Long? = null,
    val followedRemotely: Boolean = false,
    val downloadMode: DownloadMode? = null,
    val pinnedVoiceId: String? = null,
    val pinnedVoiceLocale: String? = null,
    val notesEverSeen: Boolean = false,
    /**
     * Issue #989 — the original source URL needed to *rebuild* this
     * fiction on a device that never saw the original paste.
     *
     * Most sources don't need this: their `id` already encodes
     * everything required to re-fetch (`gutenberg:84`, `ao3:123`,
     * numeric Royal Road, `wikipedia:Foo`, …), so the
     * `MetadataBackfillWorker` can hydrate a placeholder from the id
     * alone. But three backends hash the source URL into an opaque,
     * non-reversible id:
     *  - **Readability** (`readability:<sha256-16>`) — the catch-all
     *    paste-anything article extractor;
     *  - **RSS** (`rss:<sha256-16>`) — feed URL hashed;
     *  - **EPUB direct download** (`epub:url:<sha256-16>`).
     * For those, the URL is the *only* thing that can rebuild the
     * fiction, and historically it lived purely in an in-memory cache
     * on the originating device (see `ReadabilitySource.persistedUrlFor`)
     * — so a synced placeholder on a second device had an id but no URL
     * and could never hydrate (issue #989; the #981 worker logged "has
     * no remembered URL").
     *
     * Persisting the URL here gives it a durable home that (a) survives
     * process death / cache-clear on the originating device and (b) is
     * readable by `LibrarySyncer` so it can ride across devices in the
     * synced library payload. Null for the many sources whose id is
     * self-describing.
     */
    val sourceUrl: String? = null,
    /**
     * Source-side revision token captured the last time we successfully
     * polled this fiction. For GitHub it's the head commit SHA on the
     * default branch; for sources that don't expose a cheap revision
     * check it stays null. Used by `NewChapterPollWorker` to short-
     * circuit the full detail fetch when nothing has changed upstream
     * — see step 9 in
     * `docs/superpowers/specs/2026-05-06-github-source-design.md`.
     */
    val lastSeenRevision: String? = null,
    /**
     * Issue #981 — wall-clock of the last *failed* metadata back-fill
     * attempt by `MetadataBackfillWorker`, or null if never attempted /
     * the last attempt succeeded.
     *
     * A placeholder row (`metadataFetchedAt = 0`) that a source can't
     * hydrate — auth-gated (Royal Road without cookies), removed
     * upstream (404), or a hard network failure — gets this stamp so the
     * library UI can render a distinct "Couldn't load" state instead of
     * an eternal "Loading…" spinner, and so the back-fill worker can
     * apply a cool-down before retrying (it doesn't re-hammer a row it
     * just failed on). `metadataFetchedAt` deliberately stays 0 so the
     * row remains in the back-fill set and a later run (new cookies,
     * network back) can still hydrate it. On a successful hydrate
     * `upsertDetail` stamps `metadataFetchedAt = now`, which drops the
     * row out of the placeholder query; this column is cleared back to
     * null at the same time via [FictionDao.clearBackfillFailure].
     */
    val metadataBackfillFailedAt: Long? = null,
)

/** Per-book download policy override. Null = inherit global setting. */
enum class DownloadMode { LAZY, EAGER, SUBSCRIBE }
