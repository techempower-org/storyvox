package `in`.jphe.storyvox.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import `in`.jphe.storyvox.data.db.entity.PlaybackPosition
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackDao {

    @Query("SELECT * FROM playback_position WHERE fictionId = :fictionId")
    fun observe(fictionId: String): Flow<PlaybackPosition?>

    /** One-shot lookup used by the playback layer's `load(fictionId)`. */
    @Query("SELECT * FROM playback_position WHERE fictionId = :fictionId")
    suspend fun get(fictionId: String): PlaybackPosition?

    @Upsert
    suspend fun upsert(position: PlaybackPosition)

    @Query("DELETE FROM playback_position WHERE fictionId = :fictionId")
    suspend fun delete(fictionId: String)

    /**
     * Slim "all positions" projection used by [PlaybackPositionSyncer] to build
     * its sync payload in one Room round-trip. Returns only the fields the wire
     * `Entry` needs; skips bigger columns that aren't part of the sync record.
     */
    @Query(
        """
        SELECT fictionId, chapterId, charOffset, paragraphIndex,
               playbackSpeed, durationEstimateMs, updatedAt
          FROM playback_position
        """,
    )
    suspend fun allPositionsSnapshot(): List<PlaybackPositionSnapshotRow>

    /**
     * Denormalized "recently played" feed for the Auto/Wear menu — flattens
     * fiction title + cover + chapter title into one row so the playback
     * layer doesn't need follow-up queries while building Auto's browse tree.
     */
    @Query(
        """
        SELECT p.fictionId   AS fictionId,
               p.chapterId   AS chapterId,
               f.title       AS bookTitle,
               c.title       AS chapterTitle,
               f.coverUrl    AS coverUrl
          FROM playback_position p
          JOIN fiction f ON f.id = p.fictionId
          JOIN chapter c ON c.id = p.chapterId
         ORDER BY p.updatedAt DESC
         LIMIT :limit
        """,
    )
    suspend fun recent(limit: Int): List<RecentPlaybackRow>

    /**
     * Most-recent "Continue listening" projection — Aurora flows this directly
     * into the Library tile.
     *
     * Only the topmost row is needed and only the FictionSummary + ChapterInfo
     * fields ever leave the repository ([Fiction.toSummary] / [Chapter.toInfo]),
     * so we both:
     *  - push `LIMIT 1` into SQL so the join collapses from O(library size)
     *    per emission to O(1), and a position-save tick no longer rebuilds
     *    and re-emits an entire library's worth of joined rows;
     *  - select a slim projection ([ContinueListeningRow]) covering only the
     *    columns the Resume tile actually renders. The full `chapter` row
     *    contains `htmlBody` / `plainBody` text blobs that are multi-MB on
     *    long chapters — those columns were being read off disk on every
     *    emission and discarded by the mapper. Skipping them removes that
     *    per-tick I/O.
     */
    @Query(
        """
        SELECT
            f.id                  AS f_id,
            f.sourceId            AS f_sourceId,
            f.title               AS f_title,
            f.author              AS f_author,
            f.coverUrl            AS f_coverUrl,
            f.description         AS f_description,
            f.tags                AS f_tags,
            f.status              AS f_status,
            f.chapterCount        AS f_chapterCount,
            f.rating              AS f_rating,
            c.id                  AS c_id,
            c.sourceChapterId     AS c_sourceChapterId,
            c.`index`             AS c_index,
            c.title               AS c_title,
            c.publishedAt         AS c_publishedAt,
            c.wordCount           AS c_wordCount,
            p.charOffset          AS charOffset,
            p.playbackSpeed       AS playbackSpeed,
            p.updatedAt           AS updatedAt
          FROM playback_position p
          JOIN fiction f ON f.id = p.fictionId
          JOIN chapter c ON c.id = p.chapterId
         WHERE f.inLibrary = 1
         ORDER BY p.updatedAt DESC
         LIMIT 1
        """,
    )
    fun observeMostRecentContinueListening(): Flow<ContinueListeningRow?>
}

/**
 * Slim "Continue listening" join row — every column listed here is rendered
 * by the Resume tile or is needed to reconstruct a [FictionSummary] /
 * [ChapterInfo] for the public [ContinueListeningEntry]. Mirrors only the
 * non-blob fields of [Fiction] / [Chapter]; full bodies/genres/etc. stay in
 * the database.
 */
data class ContinueListeningRow(
    val f_id: String,
    val f_sourceId: String,
    val f_title: String,
    val f_author: String,
    val f_coverUrl: String?,
    val f_description: String?,
    val f_tags: List<String>,
    val f_status: `in`.jphe.storyvox.data.source.model.FictionStatus,
    val f_chapterCount: Int,
    val f_rating: Float?,
    val c_id: String,
    val c_sourceChapterId: String,
    val c_index: Int,
    val c_title: String,
    val c_publishedAt: Long?,
    val c_wordCount: Int?,
    val charOffset: Int,
    val playbackSpeed: Float,
    val updatedAt: Long,
)

/** Denormalized row backing the playback layer's "recent" Auto rail. */
data class RecentPlaybackRow(
    val fictionId: String,
    val chapterId: String,
    val bookTitle: String,
    val chapterTitle: String,
    val coverUrl: String?,
)

/** Slim projection of [PlaybackPosition] for sync snapshots. */
data class PlaybackPositionSnapshotRow(
    val fictionId: String,
    val chapterId: String,
    val charOffset: Int,
    val paragraphIndex: Int,
    val playbackSpeed: Float,
    val durationEstimateMs: Long,
    val updatedAt: Long,
)
