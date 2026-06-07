package `in`.jphe.storyvox.data.repository

import `in`.jphe.storyvox.data.db.dao.AnnotationDao
import `in`.jphe.storyvox.data.db.entity.Annotation
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * Issue #999 — read/delete surface for a fiction's highlights + notes,
 * backing the FictionDetail "Highlights & notes" list. Mirrors
 * [FictionMemoryRepository]: a thin domain seam over [AnnotationDao] so the
 * feature module observes/deletes annotations without importing Room DAOs
 * directly (the convention every feature ViewModel here follows).
 *
 * Phase 2 (#999 fast-follow, this PR) adds the *write* surface the in-reader
 * select-text highlight overlay needs — [observeForChapter] for the per-chapter
 * render layer and [upsert] for create/edit. The FictionDetail list still uses
 * [observeForFiction] + [delete] unchanged. The export path goes through
 * [in.jphe.storyvox.data.annotation.ExportAnnotationsUseCase], not this repo.
 */
interface AnnotationRepository {

    /** Live feed of every annotation for [fictionId], chapter-index then
     *  start-offset ordered (the DAO's JOIN-ed query) — the list surface. */
    fun observeForFiction(fictionId: String): Flow<List<Annotation>>

    /**
     * Issue #999 phase 2 — this chapter's annotations, start-offset ordered,
     * for the reader's highlight-render overlay. The reader observes only the
     * loaded chapter (not the whole fiction) so the span list stays small and
     * the observer churns only on a chapter switch.
     */
    fun observeForChapter(chapterId: String): Flow<List<Annotation>>

    /**
     * Issue #999 phase 2 — create or edit an annotation. Insert is REPLACE on
     * the [Annotation.id] primary key, so re-saving the same id (an edit to a
     * note or colour) overwrites the row in place rather than duplicating it —
     * the idempotent-on-id contract the DAO and [AnnotationsSyncer] share.
     */
    suspend fun upsert(annotation: Annotation)

    /** Delete a single annotation by its UUID — the list's per-row delete. */
    suspend fun delete(id: String)
}

@Singleton
class AnnotationRepositoryImpl @Inject constructor(
    private val dao: AnnotationDao,
) : AnnotationRepository {

    override fun observeForFiction(fictionId: String): Flow<List<Annotation>> =
        dao.observeForFiction(fictionId)

    override fun observeForChapter(chapterId: String): Flow<List<Annotation>> =
        dao.observeForChapter(chapterId)

    override suspend fun upsert(annotation: Annotation) {
        dao.upsert(annotation)
    }

    override suspend fun delete(id: String) {
        dao.deleteById(id)
    }
}
