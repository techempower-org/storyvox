package `in`.jphe.storyvox.source.epub.writer

import `in`.jphe.storyvox.data.db.dao.ChapterCacheUsageRow
import `in`.jphe.storyvox.data.db.dao.ChapterDao
import `in`.jphe.storyvox.data.db.dao.ChapterDownloadStateRow
import `in`.jphe.storyvox.data.db.dao.ChapterInfoRow
import `in`.jphe.storyvox.data.db.dao.FictionDao
import `in`.jphe.storyvox.data.db.dao.PlaybackChapterRow
import `in`.jphe.storyvox.data.db.dao.UnreadChapterRow
import `in`.jphe.storyvox.data.db.entity.Chapter
import `in`.jphe.storyvox.data.db.entity.ChapterDownloadState
import `in`.jphe.storyvox.data.db.entity.DownloadMode
import `in`.jphe.storyvox.data.db.entity.Fiction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Issue #117 — no-op DAOs used by [ExportFictionBuildBookTest] to instantiate
 * [ExportFictionToEpubUseCase] without a real database. The test only
 * exercises [ExportFictionToEpubUseCase.buildBook], which is a pure function
 * over its [Fiction] / [Chapter] inputs — no DAO calls.
 *
 * If you find yourself reaching for these to test the full [export] path,
 * stop and add Robolectric (or move to an instrumentation test). The export
 * path needs an Android Context for cacheDir + FileProvider, which neither
 * mock framework here handles. Stick to [buildBook] for unit-level coverage.
 */
internal class NoopFictionDao : FictionDao {
    override fun observe(id: String): Flow<Fiction?> = flowOf(null)
    override suspend fun get(id: String): Fiction? = null
    override suspend fun getMany(ids: List<String>): List<Fiction> = emptyList()
    override fun observeLibrary(): Flow<List<Fiction>> = flowOf(emptyList())
    override suspend fun librarySnapshot(): List<Fiction> = emptyList()
    override fun observeFollowsRemote(): Flow<List<Fiction>> = flowOf(emptyList())
    override suspend fun followsSnapshot(): List<Fiction> = emptyList()
    override suspend fun pollableForNewChapters(): List<Fiction> = emptyList()
    override suspend fun insertIfNew(fiction: Fiction): Long = 0L
    override suspend fun upsert(fiction: Fiction) = Unit
    override suspend fun upsertMany(fictions: List<Fiction>) = Unit
    override suspend fun update(fiction: Fiction) = Unit
    override suspend fun setInLibrary(id: String, inLibrary: Boolean, now: Long) = Unit
    override suspend fun setFollowedRemote(id: String, followed: Boolean) = Unit
    override suspend fun setDownloadMode(id: String, mode: DownloadMode?) = Unit
    override suspend fun setPinnedVoice(id: String, voiceId: String?, locale: String?) = Unit
    override suspend fun touchMetadata(id: String, now: Long) = Unit
    override suspend fun setSourceId(id: String, sourceId: String) = Unit
    override suspend fun placeholdersToBackfill(cutoff: Long): List<Fiction> = emptyList()
    override suspend fun placeholderCount(): Int = 0
    override suspend fun markBackfillFailed(id: String, now: Long) = Unit
    override suspend fun clearBackfillFailure(id: String) = Unit
    override suspend fun getLastSeenRevision(id: String): String? = null
    override suspend fun setLastSeenRevision(id: String, revision: String?) = Unit
    override suspend fun deleteIfTransient(id: String): Int = 0
}

internal class NoopChapterDao : ChapterDao {
    override fun observeChapterInfosByFiction(fictionId: String): Flow<List<ChapterInfoRow>> = flowOf(emptyList())
    override fun observe(id: String): Flow<Chapter?> = flowOf(null)
    override suspend fun get(id: String): Chapter? = null
    override suspend fun exists(id: String): Boolean = false
    override suspend fun allChapters(fictionId: String): List<Chapter> = emptyList()
    override fun observeDownloadStates(fictionId: String): Flow<List<ChapterDownloadStateRow>> = flowOf(emptyList())
    override fun observePlayedChapterIds(fictionId: String): Flow<List<String>> = flowOf(emptyList())
    override suspend fun missingForFiction(fictionId: String): List<Chapter> = emptyList()
    override suspend fun maxIndex(fictionId: String): Int? = null
    override suspend fun nextChapterId(currentId: String): String? = null
    override suspend fun previousChapterId(currentId: String): String? = null
    override suspend fun playbackChapter(id: String): PlaybackChapterRow? = null
    override suspend fun unreadChapters(limit: Int): List<UnreadChapterRow> = emptyList()
    override suspend fun upsert(chapter: Chapter) = Unit
    override suspend fun upsertAll(chapters: List<Chapter>) = Unit
    override suspend fun parkChapterIndexesFor(fictionId: String) = Unit
    override suspend fun deleteByFictionId(fictionId: String) = Unit
    override suspend fun chapterIdsForFiction(fictionId: String): List<String> = emptyList()
    override suspend fun deleteByIds(ids: List<String>) = Unit
    override suspend fun insertAll(chapters: List<Chapter>) = Unit
    override suspend fun setDownloadState(id: String, state: ChapterDownloadState, now: Long, error: String?) = Unit
    override suspend fun reapStuckDownloads(now: Long, cutoff: Long): Int = 0
    override suspend fun setBody(
        id: String,
        html: String,
        plain: String,
        checksum: String,
        notesAuthor: String?,
        notesAuthorPosition: String?,
        now: Long,
        audioUrl: String?,
    ) = Unit
    override suspend fun setRead(id: String, read: Boolean, now: Long) = Unit
    override suspend fun trimDownloadedBodies(fictionId: String, keepLast: Int) = Unit
    override suspend fun setBookmark(id: String, charOffset: Int?) = Unit
    override suspend fun getBookmark(id: String): Int? = null
    override suspend fun allBookmarks(): List<`in`.jphe.storyvox.data.db.dao.BookmarkRow> = emptyList()
    override suspend fun cacheUsage(): ChapterCacheUsageRow = ChapterCacheUsageRow(count = 0, bytes = 0L)
}
