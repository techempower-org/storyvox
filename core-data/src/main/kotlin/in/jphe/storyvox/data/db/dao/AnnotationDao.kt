package `in`.jphe.storyvox.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import `in`.jphe.storyvox.data.db.entity.Annotation
import kotlinx.coroutines.flow.Flow

/**
 * Read/write surface for the `annotation` table (issue #999) — text
 * highlights + optional notes, beyond the single per-chapter bookmark.
 *
 * Patterned on [FictionShelfDao]: idempotent upsert via
 * [OnConflictStrategy.REPLACE] (re-saving the same annotation id is a no-op
 * refresh, not a crash), plus targeted deletes. The reader observes one
 * chapter's spans ([observeForChapter]); the FictionDetail list observes the
 * whole fiction ([observeForFiction]); the syncer reads everything
 * ([allAnnotations]) and probes existence ([exists]) for its FK-guard.
 */
@Dao
interface AnnotationDao {

    // ── Observers ────────────────────────────────────────────────────────

    /**
     * Every annotation for a fiction, ordered for the grouped list:
     * chapter-by-chapter (by the chapter's reading order via the join),
     * then by start offset, then newest-edit as a tiebreak.
     */
    @Query(
        """
        SELECT a.* FROM annotation a
        INNER JOIN chapter c ON c.id = a.chapterId
        WHERE a.fictionId = :fictionId
        ORDER BY c.`index` ASC, a.startOffset ASC, a.updatedAt DESC
        """,
    )
    fun observeForFiction(fictionId: String): Flow<List<Annotation>>

    /** This chapter's annotations, start-offset order — the reader overlay. */
    @Query(
        "SELECT * FROM annotation WHERE chapterId = :chapterId ORDER BY startOffset ASC",
    )
    fun observeForChapter(chapterId: String): Flow<List<Annotation>>

    /** Whole-table snapshot for the syncer's reconcile pass. */
    @Query("SELECT * FROM annotation")
    suspend fun allAnnotations(): List<Annotation>

    /** One-shot per-fiction read for non-flow paths (export builder). */
    @Query(
        """
        SELECT a.* FROM annotation a
        INNER JOIN chapter c ON c.id = a.chapterId
        WHERE a.fictionId = :fictionId
        ORDER BY c.`index` ASC, a.startOffset ASC, a.updatedAt DESC
        """,
    )
    suspend fun annotationsForFiction(fictionId: String): List<Annotation>

    /** Sync FK-guard: is this annotation row already present locally? */
    @Query("SELECT EXISTS(SELECT 1 FROM annotation WHERE id = :id)")
    suspend fun exists(id: String): Boolean

    // ── Mutators ─────────────────────────────────────────────────────────

    /**
     * Insert is REPLACE so re-saving an annotation by id (an edit to its
     * note, or a sync apply of a newer copy) overwrites the row rather than
     * throwing. UI / sync are both idempotent on id.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(annotation: Annotation)

    @Query("DELETE FROM annotation WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Drop every annotation for a fiction — e.g. on library removal. */
    @Query("DELETE FROM annotation WHERE fictionId = :fictionId")
    suspend fun clearForFiction(fictionId: String)
}
