package `in`.jphe.storyvox.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import `in`.jphe.storyvox.data.db.entity.DownloadMode
import `in`.jphe.storyvox.data.db.entity.Fiction
import kotlinx.coroutines.flow.Flow

@Dao
interface FictionDao {

    @Query("SELECT * FROM fiction WHERE id = :id")
    fun observe(id: String): Flow<Fiction?>

    @Query("SELECT * FROM fiction WHERE id = :id")
    suspend fun get(id: String): Fiction?

    @Query("SELECT * FROM fiction WHERE id IN (:ids)")
    suspend fun getMany(ids: List<String>): List<Fiction>

    @Query("SELECT * FROM fiction WHERE inLibrary = 1 ORDER BY addedToLibraryAt DESC")
    fun observeLibrary(): Flow<List<Fiction>>

    @Query("SELECT * FROM fiction WHERE inLibrary = 1 ORDER BY addedToLibraryAt DESC")
    suspend fun librarySnapshot(): List<Fiction>

    @Query("SELECT * FROM fiction WHERE followedRemotely = 1 ORDER BY lastUpdatedAt DESC")
    fun observeFollowsRemote(): Flow<List<Fiction>>

    @Query("SELECT * FROM fiction WHERE followedRemotely = 1 ORDER BY lastUpdatedAt DESC")
    suspend fun followsSnapshot(): List<Fiction>

    /**
     * Issue #907 — fictions the new-chapter poller should walk: anything
     * subscribed/eager in the library (the original #383 set) PLUS anything
     * the user follows on the source even if it isn't in the local library.
     * A pure source-side follow (followedRemotely = 1, inLibrary = 0) had no
     * poll path before #907, so new chapters never fired a notification.
     */
    @Query(
        """
        SELECT * FROM fiction
         WHERE followedRemotely = 1
            OR (inLibrary = 1 AND downloadMode IN ('SUBSCRIBE', 'EAGER'))
         ORDER BY lastUpdatedAt DESC
        """,
    )
    suspend fun pollableForNewChapters(): List<Fiction>

    /**
     * Insert a fresh row and ignore conflicts — used when listing pages produce
     * fictions we already know about. Real updates go through [upsert].
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNew(fiction: Fiction): Long

    @Upsert
    suspend fun upsert(fiction: Fiction)

    @Upsert
    suspend fun upsertMany(fictions: List<Fiction>)

    @Update
    suspend fun update(fiction: Fiction)

    @Query(
        """
        UPDATE fiction
           SET inLibrary = :inLibrary,
               addedToLibraryAt = CASE WHEN :inLibrary = 1 THEN :now ELSE addedToLibraryAt END
         WHERE id = :id
        """,
    )
    suspend fun setInLibrary(id: String, inLibrary: Boolean, now: Long)

    @Query("UPDATE fiction SET followedRemotely = :followed WHERE id = :id")
    suspend fun setFollowedRemote(id: String, followed: Boolean)

    @Query("UPDATE fiction SET downloadMode = :mode WHERE id = :id")
    suspend fun setDownloadMode(id: String, mode: DownloadMode?)

    @Query("UPDATE fiction SET pinnedVoiceId = :voiceId, pinnedVoiceLocale = :locale WHERE id = :id")
    suspend fun setPinnedVoice(id: String, voiceId: String?, locale: String?)

    @Query("UPDATE fiction SET metadataFetchedAt = :now WHERE id = :id")
    suspend fun touchMetadata(id: String, now: Long)

    /**
     * Issue #981 — correct a placeholder row's `sourceId`. Synced
     * placeholders were historically written with
     * `sourceId = id.substringBefore(':')`, which is wrong for bare
     * Royal Road numeric ids and radio station-prefixed ids (see
     * [in.jphe.storyvox.data.source.FictionSourceIdResolver]). The
     * back-fill worker re-derives the correct key and stamps it here
     * before fetching so the row routes to a real source — and so a
     * subsequent tap-to-open / poll also routes correctly.
     */
    @Query("UPDATE fiction SET sourceId = :sourceId WHERE id = :id")
    suspend fun setSourceId(id: String, sourceId: String)

    /**
     * Issue #981 — placeholder rows the [MetadataBackfillWorker] should
     * hydrate. A placeholder is a library / source-follow row that was
     * inserted by `LibrarySyncer`/`FollowsSyncer.localAdd` with
     * `metadataFetchedAt = 0` and a sentinel `title = "Loading…"` — it
     * carries only an id + sourceId, no real metadata.
     *
     * Inclusion criteria:
     *  - `metadataFetchedAt = 0` — never successfully hydrated. A real
     *    metadata fetch stamps this to `now` (see
     *    `FictionRepository.upsertDetail`), which drops the row out of
     *    this set permanently.
     *  - `inLibrary = 1 OR followedRemotely = 1` — only user-meaningful
     *    rows. A transient browse-cache stub (neither flag) isn't worth a
     *    background fetch; it'll hydrate on tap and get GC'd otherwise.
     *  - cool-down: skip rows whose last back-fill attempt FAILED within
     *    [cutoff] (caller passes `now - COOLDOWN_MS`). A row that has
     *    never been attempted (`metadataBackfillFailedAt IS NULL`) is
     *    always eligible. This stops a permanently-unreachable source
     *    (auth-gated, 404'd upstream) from being re-fetched on every
     *    single Library-screen open while still letting it retry later.
     *
     * Ordered oldest-added first so the longest-stuck items resolve
     * first.
     */
    @Query(
        """
        SELECT * FROM fiction
         WHERE metadataFetchedAt = 0
           AND (inLibrary = 1 OR followedRemotely = 1)
           AND (metadataBackfillFailedAt IS NULL OR metadataBackfillFailedAt < :cutoff)
         ORDER BY addedToLibraryAt ASC
        """,
    )
    suspend fun placeholdersToBackfill(cutoff: Long): List<Fiction>

    /**
     * Issue #981 — count of un-hydrated placeholder rows still in the
     * library/follows set, ignoring the cool-down. Drives the decision to
     * enqueue the back-fill worker at all (skip the WorkManager round-trip
     * when there's nothing to do) and is a cheap COUNT vs materialising
     * full rows.
     */
    @Query(
        """
        SELECT COUNT(*) FROM fiction
         WHERE metadataFetchedAt = 0
           AND (inLibrary = 1 OR followedRemotely = 1)
        """,
    )
    suspend fun placeholderCount(): Int

    /**
     * Issue #981 — stamp a failed back-fill attempt. Leaves
     * `metadataFetchedAt = 0` so the row stays in the placeholder set and
     * a later run can retry once the cool-down elapses; the UI reads this
     * column to show "Couldn't load" instead of "Loading…".
     */
    @Query("UPDATE fiction SET metadataBackfillFailedAt = :now WHERE id = :id")
    suspend fun markBackfillFailed(id: String, now: Long)

    /**
     * Issue #981 — clear the failure stamp after a successful hydrate.
     * Called alongside the metadata upsert so a row that recovered (new
     * cookies, network back) doesn't keep a stale "Couldn't load" flag.
     */
    @Query("UPDATE fiction SET metadataBackfillFailedAt = NULL WHERE id = :id")
    suspend fun clearBackfillFailure(id: String)

    /**
     * Source-side revision token captured the last time we successfully
     * polled the fiction. See [Fiction.lastSeenRevision].
     */
    @Query("SELECT lastSeenRevision FROM fiction WHERE id = :id")
    suspend fun getLastSeenRevision(id: String): String?

    @Query("UPDATE fiction SET lastSeenRevision = :revision WHERE id = :id")
    suspend fun setLastSeenRevision(id: String, revision: String?)

    @Transaction
    suspend fun upsertAllPreservingUserState(fictions: List<Fiction>) {
        // Listing pages don't know about library/follow state — preserve the
        // user-owned columns so a browse-page upsert never silently un-libraries.
        //
        // Batch-fetch existing rows instead of O(N) individual get() calls.
        // SQLite has a 999-variable limit for IN clauses, so chunk if needed.
        val existingMap: Map<String, Fiction> = fictions
            .map { it.id }
            .chunked(900)
            .flatMap { chunk -> getMany(chunk) }
            .associateBy { it.id }

        val merged = fictions.map { incoming ->
            val existing = existingMap[incoming.id]
            if (existing == null) incoming else incoming.copy(
                inLibrary = existing.inLibrary,
                addedToLibraryAt = existing.addedToLibraryAt,
                followedRemotely = existing.followedRemotely,
                downloadMode = existing.downloadMode,
                pinnedVoiceId = existing.pinnedVoiceId,
                pinnedVoiceLocale = existing.pinnedVoiceLocale,
                notesEverSeen = existing.notesEverSeen,
                firstSeenAt = existing.firstSeenAt,
                // Listing pages don't carry revision tokens — only the
                // poll worker writes this column. Preserve so a browse
                // upsert doesn't reset the cheap-poll state.
                lastSeenRevision = existing.lastSeenRevision,
            )
        }
        upsertMany(merged)
    }

    @Query("DELETE FROM fiction WHERE id = :id AND inLibrary = 0 AND followedRemotely = 0")
    suspend fun deleteIfTransient(id: String): Int
}
