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
