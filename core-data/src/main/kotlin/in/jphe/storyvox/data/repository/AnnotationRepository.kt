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
 * Writes (create/edit an annotation from the reader select-drag overlay) land
 * in #999's phase-2 fast-follow; this v1 surface is read + delete only, which
 * is all the FictionDetail list needs. The export path goes through
 * [in.jphe.storyvox.data.annotation.ExportAnnotationsUseCase], not this repo.
 */
interface AnnotationRepository {

    /** Live feed of every annotation for [fictionId], chapter-index then
     *  start-offset ordered (the DAO's JOIN-ed query) — the list surface. */
    fun observeForFiction(fictionId: String): Flow<List<Annotation>>

    /** Delete a single annotation by its UUID — the list's per-row delete. */
    suspend fun delete(id: String)
}

@Singleton
class AnnotationRepositoryImpl @Inject constructor(
    private val dao: AnnotationDao,
) : AnnotationRepository {

    override fun observeForFiction(fictionId: String): Flow<List<Annotation>> =
        dao.observeForFiction(fictionId)

    override suspend fun delete(id: String) {
        dao.deleteById(id)
    }
}
