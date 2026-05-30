package `in`.jphe.storyvox.data.source

import `in`.jphe.storyvox.data.db.dao.FictionDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #989 — durable lookup of the original source URL for a fiction
 * whose `id` is an opaque hash of that URL (Readability/RSS/EPUB-direct).
 *
 * # Why this exists
 *
 * These sources can't reverse their id back to a URL, so rebuilding the
 * fiction needs the URL stored somewhere. Historically the Readability
 * source kept it in a process-lifetime in-memory map, which is lost on
 * process death and never existed on a second device — so a synced
 * placeholder could never hydrate (issue #989: "has no remembered URL").
 *
 * The URL now lives on `Fiction.sourceUrl` (persisted at paste time by
 * `FictionRepository.addByUrl`, and stamped onto a synced placeholder by
 * `LibrarySyncer`). This thin interface exposes that column to the leaf
 * source modules without making them depend on the full `FictionDao`
 * surface or the `:app`/repository layer — same decoupling rationale as
 * the other `:core-data` store interfaces (e.g. `FollowedTagsStore`).
 *
 * The interface lives in `:core-data` (which the source modules already
 * depend on) and the implementation is a trivial DAO wrapper, also in
 * `:core-data`, bound in `RepositoryBindings`.
 */
interface RememberedUrlStore {
    /**
     * The persisted source URL for [fictionId], or null if none was ever
     * remembered (the common case for self-describing-id sources, or a
     * pre-#989 row that predates the column).
     */
    suspend fun rememberedUrl(fictionId: String): String?
}

/** [RememberedUrlStore] backed by the `fiction.sourceUrl` column. */
@Singleton
class DaoRememberedUrlStore @Inject constructor(
    private val fictionDao: FictionDao,
) : RememberedUrlStore {
    override suspend fun rememberedUrl(fictionId: String): String? =
        fictionDao.getSourceUrl(fictionId)
}
