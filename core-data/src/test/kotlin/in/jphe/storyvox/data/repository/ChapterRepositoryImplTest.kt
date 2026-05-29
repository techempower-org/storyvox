package `in`.jphe.storyvox.data.repository

import `in`.jphe.storyvox.data.db.dao.ChapterDao
import `in`.jphe.storyvox.data.db.dao.ChapterDownloadStateRow
import `in`.jphe.storyvox.data.db.dao.ChapterInfoRow
import `in`.jphe.storyvox.data.db.dao.PlaybackChapterRow
import `in`.jphe.storyvox.data.db.dao.UnreadChapterRow
import `in`.jphe.storyvox.data.db.entity.Chapter
import `in`.jphe.storyvox.data.db.entity.ChapterDownloadState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChapterRepositoryImplTest {

    // -- Fakes ------------------------------------------------------------------

    private class FakeChapterDao : ChapterDao {
        val rows = mutableMapOf<String, Chapter>()
        val callLog = mutableListOf<String>()

        private val infoFeeds = mutableMapOf<String, MutableStateFlow<List<ChapterInfoRow>>>()
        private val rowFeeds = mutableMapOf<String, MutableStateFlow<Chapter?>>()
        private val stateFeeds = mutableMapOf<String, MutableStateFlow<List<ChapterDownloadStateRow>>>()

        var nextChapterIdResult: String? = null
        var previousChapterIdResult: String? = null
        var playbackChapterResult: PlaybackChapterRow? = null

        // -- helpers --
        private fun fictionRows(fictionId: String): List<ChapterInfoRow> =
            rows.values
                .filter { it.fictionId == fictionId }
                .sortedBy { it.index }
                .map {
                    ChapterInfoRow(
                        id = it.id, sourceChapterId = it.sourceChapterId,
                        index = it.index, title = it.title,
                        publishedAt = it.publishedAt, wordCount = it.wordCount,
                    )
                }

        private fun stateRows(fictionId: String): List<ChapterDownloadStateRow> =
            rows.values.filter { it.fictionId == fictionId }
                .map { ChapterDownloadStateRow(id = it.id, downloadState = it.downloadState) }

        private fun publishAll(fictionId: String) {
            infoFeeds[fictionId]?.value = fictionRows(fictionId)
            stateFeeds[fictionId]?.value = stateRows(fictionId)
        }

        private fun publishRow(id: String) {
            rowFeeds[id]?.value = rows[id]
            rows[id]?.fictionId?.let(::publishAll)
        }

        // -- DAO surface --
        override fun observeChapterInfosByFiction(fictionId: String): Flow<List<ChapterInfoRow>> =
            infoFeeds.getOrPut(fictionId) { MutableStateFlow(fictionRows(fictionId)) }

        override fun observe(id: String): Flow<Chapter?> =
            rowFeeds.getOrPut(id) { MutableStateFlow(rows[id]) }

        override suspend fun get(id: String): Chapter? {
            callLog += "get($id)"; return rows[id]
        }

        override suspend fun exists(id: String): Boolean = rows.containsKey(id)

        // Issue #117 — EPUB export reads every chapter row (with bodies) for
        // a fiction in one shot. The fake mirrors that by returning whatever
        // is currently in [rows] for the fictionId, sorted by index — the
        // production query does the same ordering at the SQL layer.
        override suspend fun allChapters(fictionId: String): List<Chapter> =
            rows.values.filter { it.fictionId == fictionId }.sortedBy { it.index }

        override fun observeDownloadStates(fictionId: String): Flow<List<ChapterDownloadStateRow>> =
            stateFeeds.getOrPut(fictionId) { MutableStateFlow(stateRows(fictionId)) }

        // Issue #282 — played-chapter set query for the chapter-list
        // `isFinished` indicator. The dao tests don't exercise played
        // tracking; emit empty so the new override compiles.
        override fun observePlayedChapterIds(fictionId: String): Flow<List<String>> =
            MutableStateFlow(emptyList())

        override suspend fun missingForFiction(fictionId: String): List<Chapter> {
            callLog += "missingForFiction($fictionId)"
            return rows.values
                .filter {
                    it.fictionId == fictionId &&
                        it.downloadState == ChapterDownloadState.NOT_DOWNLOADED
                }
                .sortedBy { it.index }
        }

        override suspend fun maxIndex(fictionId: String): Int? =
            rows.values.filter { it.fictionId == fictionId }.maxOfOrNull { it.index }

        override suspend fun nextChapterId(currentId: String): String? = nextChapterIdResult
        override suspend fun previousChapterId(currentId: String): String? = previousChapterIdResult
        override suspend fun playbackChapter(id: String): PlaybackChapterRow? = playbackChapterResult
        override suspend fun unreadChapters(limit: Int): List<UnreadChapterRow> = emptyList()

        override suspend fun upsert(chapter: Chapter) {
            callLog += "upsert(${chapter.id})"
            rows[chapter.id] = chapter
            publishRow(chapter.id)
        }

        override suspend fun upsertAll(chapters: List<Chapter>) {
            callLog += "upsertAll(${chapters.map { it.id }})"
            chapters.forEach { rows[it.id] = it; publishRow(it.id) }
        }

        // Issue #652 — DELETE-then-INSERT chapter sync. Model the wipe
        // so any test that drives [upsertChaptersForFiction] sees the
        // same row-removal semantics the real DAO ships.
        override suspend fun deleteByFictionId(fictionId: String) {
            callLog += "deleteByFictionId($fictionId)"
            val ids = rows.values.filter { it.fictionId == fictionId }.map { it.id }
            ids.forEach { id ->
                rows.remove(id)
                publishRow(id)
            }
        }

        override suspend fun chapterIdsForFiction(fictionId: String): List<String> =
            rows.values.filter { it.fictionId == fictionId }.map { it.id }

        override suspend fun deleteByIds(ids: List<String>) {
            callLog += "deleteByIds($ids)"
            ids.forEach { id -> rows.remove(id); publishRow(id) }
        }

        override suspend fun insertAll(chapters: List<Chapter>) {
            callLog += "insertAll(${chapters.map { it.id }})"
            chapters.forEach { rows[it.id] = it; publishRow(it.id) }
        }

        override suspend fun setDownloadState(
            id: String,
            state: ChapterDownloadState,
            now: Long,
            error: String?,
        ) {
            callLog += "setDownloadState($id, $state)"
            val r = rows[id] ?: return
            rows[id] = r.copy(
                downloadState = state,
                lastDownloadAttemptAt = now,
                lastDownloadError = error,
            )
            publishRow(id)
        }

        // Issue #705 — reaper for stuck DOWNLOADING rows. Repository never
        // calls this directly (the worker does), but the DAO interface
        // requires an impl. Match the live SQL: flip DOWNLOADING rows
        // whose lastDownloadAttemptAt is NULL or < cutoff back to QUEUED.
        override suspend fun reapStuckDownloads(now: Long, cutoff: Long): Int {
            callLog += "reapStuckDownloads(cutoff=$cutoff)"
            val stuck = rows.values.filter {
                it.downloadState == ChapterDownloadState.DOWNLOADING &&
                    (it.lastDownloadAttemptAt == null || it.lastDownloadAttemptAt < cutoff)
            }
            stuck.forEach { r ->
                rows[r.id] = r.copy(
                    downloadState = ChapterDownloadState.QUEUED,
                    lastDownloadAttemptAt = now,
                    lastDownloadError = "stuck",
                )
                publishRow(r.id)
            }
            return stuck.size
        }

        override suspend fun setBody(
            id: String,
            html: String,
            plain: String,
            checksum: String,
            notesAuthor: String?,
            notesAuthorPosition: String?,
            now: Long,
            audioUrl: String?,
        ) {
            // Repository never calls this directly — the worker does — but
            // the DAO interface still requires an impl.
            val r = rows[id] ?: return
            rows[id] = r.copy(
                htmlBody = html,
                plainBody = plain,
                bodyChecksum = checksum,
                bodyFetchedAt = now,
                downloadState = ChapterDownloadState.DOWNLOADED,
                audioUrl = audioUrl,
            )
            publishRow(id)
        }

        override suspend fun setRead(id: String, read: Boolean, now: Long) {
            callLog += "setRead($id, $read)"
            val r = rows[id] ?: return
            rows[id] = r.copy(
                userMarkedRead = read,
                firstReadAt = if (read && r.firstReadAt == null) now else r.firstReadAt,
            )
            publishRow(id)
        }

        override suspend fun markFollowedCaughtUp(now: Long): Int = 0

        override suspend fun cacheUsage(): `in`.jphe.storyvox.data.db.dao.ChapterCacheUsageRow {
            val cached = rows.values.filter { !it.plainBody.isNullOrEmpty() }
            return `in`.jphe.storyvox.data.db.dao.ChapterCacheUsageRow(
                count = cached.size,
                bytes = cached.sumOf { (it.plainBody?.length ?: 0).toLong() * 2 },
            )
        }

        // Issue #121 — bookmark read/write. Reflect the underlying rows
        // so getBookmark stays consistent after setBookmark; matches the
        // SQL update + select pair on the real DAO.
        override suspend fun setBookmark(id: String, charOffset: Int?) {
            callLog += "setBookmark($id, $charOffset)"
            val r = rows[id] ?: return
            rows[id] = r.copy(bookmarkCharOffset = charOffset)
            publishRow(id)
        }

        override suspend fun getBookmark(id: String): Int? =
            rows[id]?.bookmarkCharOffset

        override suspend fun allBookmarks(): List<`in`.jphe.storyvox.data.db.dao.BookmarkRow> =
            rows.values
                .filter { it.bookmarkCharOffset != null }
                .map {
                    `in`.jphe.storyvox.data.db.dao.BookmarkRow(
                        chapterId = it.id,
                        charOffset = it.bookmarkCharOffset!!,
                    )
                }

        // Issue #349 — fake the index parking so the FakeChapterDao
        // models the real two-phase upsert path. Live-range rows
        // (0..99_999) shift to +100_000; already-parked rows stay.
        override suspend fun parkChapterIndexesFor(fictionId: String) {
            callLog += "parkChapterIndexesFor($fictionId)"
            val parked = rows.values
                .filter { it.fictionId == fictionId && it.index in 0..99_999 }
                .map { it.copy(index = it.index + 100_000) }
            parked.forEach { c ->
                rows[c.id] = c
                publishRow(c.id)
            }
        }

        override suspend fun trimDownloadedBodies(fictionId: String, keepLast: Int) {
            callLog += "trimDownloadedBodies($fictionId, $keepLast)"
            val ids = rows.values
                .filter { it.fictionId == fictionId }
                .sortedByDescending { it.index }
                .drop(keepLast)
                .map { it.id }
            ids.forEach {
                val r = rows[it] ?: return@forEach
                rows[it] = r.copy(
                    htmlBody = null, plainBody = null,
                    bodyFetchedAt = null, bodyChecksum = null,
                    downloadState = ChapterDownloadState.NOT_DOWNLOADED,
                )
                publishRow(it)
            }
        }
    }

    private class RecordingScheduler : ChapterDownloadScheduler {
        data class ScheduleCall(
            val fictionId: String,
            val chapterId: String,
            val requireUnmetered: Boolean,
        )

        val calls = mutableListOf<ScheduleCall>()

        override fun schedule(fictionId: String, chapterId: String, requireUnmetered: Boolean) {
            calls += ScheduleCall(fictionId, chapterId, requireUnmetered)
        }
    }

    // -- Fixtures ---------------------------------------------------------------

    private fun chapter(
        id: String,
        fictionId: String = "f1",
        index: Int = 0,
        downloadState: ChapterDownloadState = ChapterDownloadState.NOT_DOWNLOADED,
        plainBody: String? = null,
        htmlBody: String? = null,
        userMarkedRead: Boolean = false,
    ) = Chapter(
        id = id,
        fictionId = fictionId,
        sourceChapterId = "src-$id",
        index = index,
        title = "Title $id",
        downloadState = downloadState,
        plainBody = plainBody,
        htmlBody = htmlBody,
        userMarkedRead = userMarkedRead,
    )

    private fun repo(): Triple<ChapterRepositoryImpl, FakeChapterDao, RecordingScheduler> {
        val dao = FakeChapterDao()
        val sched = RecordingScheduler()
        return Triple(ChapterRepositoryImpl(dao, sched), dao, sched)
    }

    // -- observeChapters --------------------------------------------------------

    @Test fun `observeChapters maps slim rows to ChapterInfo, ordered by index`() = runTest {
        val (r, dao, _) = repo()
        dao.upsert(chapter("c0", index = 0))
        dao.upsert(chapter("c1", index = 1))
        dao.upsert(chapter("c2", index = 2))

        val infos = r.observeChapters("f1").first()
        assertEquals(listOf("c0", "c1", "c2"), infos.map { it.id })
        assertEquals(listOf(0, 1, 2), infos.map { it.index })
    }

    // -- observeChapter ---------------------------------------------------------

    @Test fun `observeChapter returns null when row absent`() = runTest {
        val (r, _, _) = repo()
        assertNull(r.observeChapter("missing").first())
    }

    @Test fun `observeChapter returns null when bodies are unset`() = runTest {
        val (r, dao, _) = repo()
        dao.upsert(chapter("c0", plainBody = null, htmlBody = null))
        assertNull(r.observeChapter("c0").first())
    }

    @Test fun `observeChapter returns null when only one body half is present`() = runTest {
        val (r, dao, _) = repo()
        dao.upsert(chapter("c0", plainBody = "plain", htmlBody = null))
        assertNull(r.observeChapter("c0").first())

        dao.upsert(chapter("c1", plainBody = null, htmlBody = "<p>html</p>"))
        assertNull(r.observeChapter("c1").first())
    }

    @Test fun `observeChapter returns ChapterContent when both bodies present`() = runTest {
        val (r, dao, _) = repo()
        dao.upsert(chapter("c0", plainBody = "plain", htmlBody = "<p>html</p>"))

        val content = r.observeChapter("c0").first()
        assertNotNull(content)
        assertEquals("plain", content!!.plainBody)
        assertEquals("<p>html</p>", content.htmlBody)
        assertEquals("c0", content.info.id)
    }

    // -- observeDownloadState ---------------------------------------------------

    @Test fun `observeDownloadState returns id-to-state map`() = runTest {
        val (r, dao, _) = repo()
        dao.upsert(chapter("c0", index = 0, downloadState = ChapterDownloadState.DOWNLOADED))
        dao.upsert(chapter("c1", index = 1, downloadState = ChapterDownloadState.QUEUED))
        dao.upsert(chapter("c2", index = 2, downloadState = ChapterDownloadState.NOT_DOWNLOADED))

        val states = r.observeDownloadState("f1").first()
        assertEquals(ChapterDownloadState.DOWNLOADED, states["c0"])
        assertEquals(ChapterDownloadState.QUEUED, states["c1"])
        assertEquals(ChapterDownloadState.NOT_DOWNLOADED, states["c2"])
    }

    // -- queueChapterDownload ---------------------------------------------------

    @Test fun `queueChapterDownload sets QUEUED state BEFORE scheduling`() = runTest {
        val (r, dao, sched) = repo()
        dao.upsert(chapter("c0"))

        r.queueChapterDownload("f1", "c0", requireUnmetered = true)

        // The state mutation must precede the scheduler hand-off so observers
        // see the pending state immediately even if the scheduler fast-paths.
        val setIdx = dao.callLog.indexOfFirst {
            it.startsWith("setDownloadState(c0, QUEUED")
        }
        assertTrue("setDownloadState(QUEUED) must be logged", setIdx >= 0)
        assertEquals(1, sched.calls.size)
        assertEquals(ChapterDownloadState.QUEUED, dao.rows["c0"]?.downloadState)
    }

    @Test fun `queueChapterDownload forwards fictionId, chapterId, and requireUnmetered to scheduler`() = runTest {
        val (r, dao, sched) = repo()
        dao.upsert(chapter("c0"))

        r.queueChapterDownload("fiction-X", "c0", requireUnmetered = false)

        assertEquals(
            RecordingScheduler.ScheduleCall("fiction-X", "c0", requireUnmetered = false),
            sched.calls.single(),
        )
    }

    @Test fun `queueChapterDownload defaults to requireUnmetered=true`() = runTest {
        val (r, dao, sched) = repo()
        dao.upsert(chapter("c0"))

        r.queueChapterDownload("f1", "c0")

        assertEquals(true, sched.calls.single().requireUnmetered)
    }

    // -- queueAllMissing --------------------------------------------------------

    @Test fun `queueAllMissing schedules every NOT_DOWNLOADED chapter, in DAO order`() = runTest {
        val (r, dao, sched) = repo()
        dao.upsert(chapter("c0", index = 0, downloadState = ChapterDownloadState.DOWNLOADED))
        dao.upsert(chapter("c1", index = 1, downloadState = ChapterDownloadState.NOT_DOWNLOADED))
        dao.upsert(chapter("c2", index = 2, downloadState = ChapterDownloadState.QUEUED))
        dao.upsert(chapter("c3", index = 3, downloadState = ChapterDownloadState.NOT_DOWNLOADED))
        dao.upsert(chapter("c4", index = 4, downloadState = ChapterDownloadState.NOT_DOWNLOADED))

        r.queueAllMissing("f1", requireUnmetered = true)

        // Only the three NOT_DOWNLOADED rows scheduled, in index order.
        assertEquals(listOf("c1", "c3", "c4"), sched.calls.map { it.chapterId })
    }

    @Test fun `queueAllMissing on fully-downloaded fiction is a no-op`() = runTest {
        val (r, dao, sched) = repo()
        dao.upsert(chapter("c0", downloadState = ChapterDownloadState.DOWNLOADED))
        dao.upsert(chapter("c1", index = 1, downloadState = ChapterDownloadState.DOWNLOADED))

        r.queueAllMissing("f1", requireUnmetered = true)

        assertEquals(0, sched.calls.size)
    }

    @Test fun `queueAllMissing forwards requireUnmetered to every schedule call`() = runTest {
        val (r, dao, sched) = repo()
        dao.upsert(chapter("c0", index = 0, downloadState = ChapterDownloadState.NOT_DOWNLOADED))
        dao.upsert(chapter("c1", index = 1, downloadState = ChapterDownloadState.NOT_DOWNLOADED))

        r.queueAllMissing("f1", requireUnmetered = false)

        assertTrue(sched.calls.all { !it.requireUnmetered })
    }

    @Test fun `queueAllMissing sets each chapter to QUEUED before scheduling`() = runTest {
        val (r, dao, sched) = repo()
        dao.upsert(chapter("c0", index = 0, downloadState = ChapterDownloadState.NOT_DOWNLOADED))
        dao.upsert(chapter("c1", index = 1, downloadState = ChapterDownloadState.NOT_DOWNLOADED))

        r.queueAllMissing("f1", requireUnmetered = true)

        assertEquals(ChapterDownloadState.QUEUED, dao.rows["c0"]?.downloadState)
        assertEquals(ChapterDownloadState.QUEUED, dao.rows["c1"]?.downloadState)
        assertEquals(2, sched.calls.size)
    }

    // -- markRead / markChapterPlayed ------------------------------------------

    @Test fun `markRead delegates to setRead with read=true by default`() = runTest {
        val (r, dao, _) = repo()
        dao.upsert(chapter("c0"))

        r.markRead("c0")

        assertEquals(true, dao.rows["c0"]?.userMarkedRead)
    }

    @Test fun `markRead with read=false clears the flag`() = runTest {
        val (r, dao, _) = repo()
        dao.upsert(chapter("c0", userMarkedRead = true))

        r.markRead("c0", read = false)

        assertEquals(false, dao.rows["c0"]?.userMarkedRead)
    }

    @Test fun `markChapterPlayed is an alias for markRead(true)`() = runTest {
        val (r, dao, _) = repo()
        dao.upsert(chapter("c0"))

        r.markChapterPlayed("c0")

        assertEquals(true, dao.rows["c0"]?.userMarkedRead)
        // Confirm via the recorded call: the alias must end up in setRead.
        assertTrue(dao.callLog.any { it == "setRead(c0, true)" })
    }

    // -- trimDownloadedBodies ---------------------------------------------------

    @Test fun `trimDownloadedBodies delegates to DAO with same args`() = runTest {
        val (r, dao, _) = repo()

        r.trimDownloadedBodies("f1", keepLast = 5)

        assertTrue(dao.callLog.any { it == "trimDownloadedBodies(f1, 5)" })
    }

    // -- getChapter (playback projection) --------------------------------------

    @Test fun `getChapter returns null when DAO has no row`() = runTest {
        val (r, dao, _) = repo()
        dao.playbackChapterResult = null
        assertNull(r.getChapter("missing"))
    }

    @Test fun `getChapter treats empty text as not-yet-downloaded (returns null)`() = runTest {
        // The DAO's COALESCE(plainBody, '') means an undownloaded chapter
        // returns a row with text="". The repository must NOT return that to
        // the player — silence isn't a track.
        val (r, dao, _) = repo()
        dao.playbackChapterResult = PlaybackChapterRow(
            id = "c0", fictionId = "f1", text = "",
            title = "Ch 0", bookTitle = "Book", coverUrl = null,
            audioUrl = null,
        )
        assertNull(r.getChapter("c0"))
    }

    @Test fun `getChapter maps DAO row to PlaybackChapter when text is present`() = runTest {
        val (r, dao, _) = repo()
        dao.playbackChapterResult = PlaybackChapterRow(
            id = "c0", fictionId = "f1", text = "real chapter text",
            title = "Ch 0", bookTitle = "Book of Ages", coverUrl = "https://cover",
            audioUrl = null,
        )

        val pb = r.getChapter("c0")

        assertNotNull(pb)
        assertEquals("c0", pb!!.id)
        assertEquals("f1", pb.fictionId)
        assertEquals("real chapter text", pb.text)
        assertEquals("Ch 0", pb.title)
        assertEquals("Book of Ages", pb.bookTitle)
        assertEquals("https://cover", pb.coverUrl)
    }

    // -- getNextChapterId / getPreviousChapterId -------------------------------

    @Test fun `getNextChapterId passes through DAO result`() = runTest {
        val (r, dao, _) = repo()
        dao.nextChapterIdResult = "c5"
        assertEquals("c5", r.getNextChapterId("c4"))

        dao.nextChapterIdResult = null
        assertNull(r.getNextChapterId("c-last"))
    }

    @Test fun `getPreviousChapterId passes through DAO result`() = runTest {
        val (r, dao, _) = repo()
        dao.previousChapterIdResult = "c3"
        assertEquals("c3", r.getPreviousChapterId("c4"))

        dao.previousChapterIdResult = null
        assertNull(r.getPreviousChapterId("c-first"))
    }
}
