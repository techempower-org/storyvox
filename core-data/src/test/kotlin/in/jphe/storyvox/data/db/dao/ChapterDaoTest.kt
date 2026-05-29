package `in`.jphe.storyvox.data.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import `in`.jphe.storyvox.data.db.StoryvoxDatabase
import `in`.jphe.storyvox.data.db.entity.Chapter
import `in`.jphe.storyvox.data.db.entity.ChapterDownloadState
import `in`.jphe.storyvox.data.db.entity.Fiction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #48 — Room+Robolectric DAO test for [ChapterDao].
 *
 * Focus on the non-trivial SQL the repository's fake-DAO tests can't reach:
 *
 *  - [ChapterDao.observeChapterInfosByFiction] — slim projection, must not
 *    drag the multi-MB plainBody/htmlBody columns off disk on every emission.
 *  - [ChapterDao.upsertChaptersForFiction] — two-phase parked-then-upsert
 *    transaction; the UNIQUE constraint on (fictionId, index) means a naive
 *    upsertAll mid-reorder would crash. Test the wrapping `@Transaction`.
 *  - [ChapterDao.parkChapterIndexesFor] — composite WHERE clause (`>= 0 AND
 *    < 100000`) that keeps previously-parked rows from drifting.
 *  - [ChapterDao.setBody] — writes a *bundle* of columns atomically.
 *  - [ChapterDao.trimDownloadedBodies] — keep-last predicate with a SELECT MAX
 *    subquery; correctness depends on the subquery scoping to fictionId.
 *  - [ChapterDao.cacheUsage] — aggregate with COALESCE for empty cases.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ChapterDaoTest {

    private lateinit var db: StoryvoxDatabase
    private lateinit var dao: ChapterDao
    private lateinit var fictionDao: FictionDao

    private val fiction = Fiction(
        id = "f1",
        sourceId = "royalroad",
        title = "Sky Pride",
        author = "Anonymous",
        coverUrl = "https://example.test/cover.jpg",
        firstSeenAt = 0L,
        metadataFetchedAt = 0L,
        inLibrary = true,
    )

    private fun chapter(
        id: String,
        index: Int,
        title: String = "Chapter $index",
        publishedAt: Long? = null,
        wordCount: Int? = null,
        plainBody: String? = null,
        htmlBody: String? = null,
        downloadState: ChapterDownloadState = ChapterDownloadState.NOT_DOWNLOADED,
        userMarkedRead: Boolean = false,
        fictionId: String = "f1",
    ) = Chapter(
        id = id,
        fictionId = fictionId,
        sourceChapterId = "src-$index",
        index = index,
        title = title,
        publishedAt = publishedAt,
        wordCount = wordCount,
        plainBody = plainBody,
        htmlBody = htmlBody,
        downloadState = downloadState,
        userMarkedRead = userMarkedRead,
    )

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, StoryvoxDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.chapterDao()
        fictionDao = db.fictionDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ----- slim projection contract (PR #40 -- must survive future edits) -------

    @Test
    fun observeChapterInfosByFiction_returnsSlimProjectionOrderedByIndex() = runTest {
        fictionDao.upsert(fiction)
        dao.upsertAll(
            listOf(
                chapter("c3", index = 3, title = "Third", publishedAt = 30L, wordCount = 1500),
                chapter("c1", index = 1, title = "First", publishedAt = 10L, wordCount = 1000),
                chapter("c2", index = 2, title = "Second", publishedAt = 20L, wordCount = 1200),
            ),
        )

        val rows = dao.observeChapterInfosByFiction("f1").first()
        assertEquals(listOf(1, 2, 3), rows.map { it.index })
        assertEquals(listOf("First", "Second", "Third"), rows.map { it.title })
        assertEquals(listOf("c1", "c2", "c3"), rows.map { it.id })
        assertEquals(listOf("src-1", "src-2", "src-3"), rows.map { it.sourceChapterId })
        assertEquals(listOf(10L, 20L, 30L), rows.map { it.publishedAt })
        assertEquals(listOf(1000, 1200, 1500), rows.map { it.wordCount })
    }

    @Test
    fun observeChapterInfosByFiction_filtersByFictionId() = runTest {
        val other = fiction.copy(id = "f2")
        fictionDao.upsert(fiction)
        fictionDao.upsert(other)
        dao.upsertAll(
            listOf(
                chapter("c1", index = 1, fictionId = "f1"),
                chapter("d1", index = 1, fictionId = "f2"),
            ),
        )

        val rows = dao.observeChapterInfosByFiction("f1").first()
        assertEquals(listOf("c1"), rows.map { it.id })
    }

    // ----- single-chapter helpers ------------------------------------------------

    @Test
    fun get_unknown_isNull() = runTest {
        assertNull(dao.get("nope"))
    }

    @Test
    fun maxIndex_emptyFiction_isNull() = runTest {
        fictionDao.upsert(fiction)
        assertNull(dao.maxIndex("f1"))
    }

    @Test
    fun maxIndex_returnsHighestIndex() = runTest {
        fictionDao.upsert(fiction)
        dao.upsertAll(
            listOf(
                chapter("c1", index = 1),
                chapter("c2", index = 2),
                chapter("c5", index = 5),
            ),
        )
        assertEquals(5, dao.maxIndex("f1"))
    }

    @Test
    fun nextAndPreviousChapterId_walkInIndexOrder() = runTest {
        fictionDao.upsert(fiction)
        dao.upsertAll(
            listOf(
                chapter("c1", index = 1),
                chapter("c2", index = 2),
                chapter("c3", index = 3),
            ),
        )
        assertEquals("c2", dao.nextChapterId("c1"))
        assertEquals("c3", dao.nextChapterId("c2"))
        assertNull(dao.nextChapterId("c3"))
        assertEquals("c2", dao.previousChapterId("c3"))
        assertNull(dao.previousChapterId("c1"))
    }

    // ----- @Transaction upsertChaptersForFiction (#349 RSS reorder) -------------

    @Test
    fun upsertChaptersForFiction_canReorderWithoutUniqueConstraintConflict() = runTest {
        fictionDao.upsert(fiction)
        // Existing live rows occupying indexes 0..2.
        dao.upsertAll(
            listOf(
                chapter("c1", index = 0, title = "Old A"),
                chapter("c2", index = 1, title = "Old B"),
                chapter("c3", index = 2, title = "Old C"),
            ),
        )

        // Same PKs, reshuffled indexes — would crash a naive upsertAll on
        // (fictionId, index) UNIQUE. Wrapped in @Transaction it must succeed.
        dao.upsertChaptersForFiction(
            "f1",
            listOf(
                chapter("c3", index = 0, title = "New A"),
                chapter("c1", index = 1, title = "New B"),
                chapter("c2", index = 2, title = "New C"),
            ),
        )

        val rows = dao.observeChapterInfosByFiction("f1").first()
        assertEquals(
            listOf("c3" to "New A", "c1" to "New B", "c2" to "New C"),
            rows.map { it.id to it.title },
        )
    }

    @Test
    fun upsertChaptersForFiction_droppedRowsAreDeleted() = runTest {
        // Issue #652 — semantics flipped from #349's "park dropped
        // rows above PARK_OFFSET" to "DELETE every chapter row for
        // the fiction, then INSERT the fresh batch." See the kdoc on
        // [ChapterDao.upsertChaptersForFiction] for the full history
        // — short version: orphan parked rows accumulated a UNIQUE
        // collision time-bomb that fired on tablet R83W80CAFZB when
        // Notion's "EBT spending" page was removed in v0.5.65.
        // Body preservation for chapters that survive across
        // refreshes now happens in the repository layer (per-PK
        // merge in [FictionRepositoryImpl.upsertDetail]) — the DAO
        // does a clean wipe-and-replace.
        fictionDao.upsert(fiction)
        dao.upsertAll(
            listOf(
                chapter("c1", index = 0, plainBody = "do not preserve me"),
                chapter("c2", index = 1),
            ),
        )

        // Only c2 is in the new feed; c1 drops off and MUST disappear.
        dao.upsertChaptersForFiction("f1", listOf(chapter("c2", index = 0, title = "Now first")))

        assertNull("dropped chapter must be deleted, not parked", dao.get("c1"))
        assertEquals("Now first", dao.get("c2")!!.title)
        assertEquals(0, dao.get("c2")!!.index)
    }

    @Test
    fun upsertChaptersForFiction_handlesRepeatedRefreshAfterChapterRemoval_issue652() = runTest {
        // Direct regression for issue #652 — the tablet repro.
        //
        // Setup: a fiction whose chapter list shrank. Before v0.5.65
        // the Notion Guides fiction had 8 chapters at indexes 0..7.
        // PR #648 dropped "EBT spending" (formerly index 4); v0.5.65+
        // refreshes write 7 chapters at indexes 0..6 with the same
        // stable PKs for the survivors. The crash was: open
        // FictionDetail twice in a row → UNIQUE constraint violation
        // on (fictionId, index) at index 4.
        //
        // The DELETE-then-INSERT path must succeed on N consecutive
        // calls regardless of how the index map shifts.
        fictionDao.upsert(fiction)
        // Pre-#648 state: 8 chapters, the 5th of which is "EBT spending"
        // (chapter pageId == "ebt"). Bodies populated so we can confirm
        // both that survivors' bodies are restored through the repo
        // merge path (out of scope here) AND that the orphan body is
        // really gone (in scope here).
        dao.upsertAll(
            (0..7).map { idx ->
                chapter(
                    id = "f1::p$idx",
                    index = idx,
                    title = "ch$idx",
                    plainBody = "body-$idx",
                )
            },
        )

        // New feed: 7 chapters; index 4 in the old DB was p4 ("EBT
        // spending"), index 4 in the new feed is p5 ("Findhelp"). The
        // PK shifts at the boundary.
        val newFeed = listOf(
            chapter("f1::p0", index = 0, title = "How to use"),
            chapter("f1::p1", index = 1, title = "Free internet"),
            chapter("f1::p2", index = 2, title = "EV incentives"),
            chapter("f1::p3", index = 3, title = "EBT balance"),
            chapter("f1::p5", index = 4, title = "Findhelp"),
            chapter("f1::p6", index = 5, title = "Password manager"),
            chapter("f1::p7", index = 6, title = "Free cell service"),
        )

        // First refresh after upgrade: must not throw.
        dao.upsertChaptersForFiction("f1", newFeed)
        // p4 (EBT spending) is gone.
        assertNull("dropped chapter must be deleted", dao.get("f1::p4"))
        // Survivors are at the new indexes.
        assertEquals(4, dao.get("f1::p5")!!.index)

        // Second refresh: this is the call that crashed on tablet.
        // With DELETE-then-INSERT it must remain a no-throw.
        dao.upsertChaptersForFiction("f1", newFeed)
        assertNull(dao.get("f1::p4"))
        assertEquals(7, dao.observeChapterInfosByFiction("f1").first().size)

        // Third refresh, same feed — still fine.
        dao.upsertChaptersForFiction("f1", newFeed)
        assertEquals(7, dao.observeChapterInfosByFiction("f1").first().size)
    }

    @Test
    fun parkChapterIndexesFor_doesNotReParkAlreadyParkedRows() = runTest {
        fictionDao.upsert(fiction)
        dao.upsertAll(listOf(chapter("c1", index = 0)))
        dao.parkChapterIndexesFor("f1") // c1 → 100000
        dao.parkChapterIndexesFor("f1") // c1 should stay at 100000, not jump to 200000

        assertEquals(100000, dao.get("c1")!!.index)
    }

    // ----- setBody and setDownloadState ----------------------------------------

    @Test
    fun setBody_writesAllBundledColumnsAtomically() = runTest {
        fictionDao.upsert(fiction)
        dao.upsert(chapter("c1", index = 0))

        dao.setBody(
            id = "c1",
            html = "<p>hi</p>",
            plain = "hi",
            checksum = "deadbeef",
            notesAuthor = "Author's note",
            notesAuthorPosition = null,
            now = 999L,
            audioUrl = "https://example.test/audio.mp3",
        )

        val row = dao.get("c1")!!
        assertEquals("<p>hi</p>", row.htmlBody)
        assertEquals("hi", row.plainBody)
        assertEquals("deadbeef", row.bodyChecksum)
        assertEquals(999L, row.bodyFetchedAt)
        assertEquals(999L, row.lastDownloadAttemptAt)
        assertEquals(ChapterDownloadState.DOWNLOADED, row.downloadState)
        assertNull(row.lastDownloadError)
        assertEquals("Author's note", row.notesAuthor)
        assertEquals("https://example.test/audio.mp3", row.audioUrl)
    }

    @Test
    fun setDownloadState_writesStateAndError() = runTest {
        fictionDao.upsert(fiction)
        dao.upsert(chapter("c1", index = 0))

        dao.setDownloadState("c1", ChapterDownloadState.FAILED, now = 1L, error = "boom")

        val row = dao.get("c1")!!
        assertEquals(ChapterDownloadState.FAILED, row.downloadState)
        assertEquals("boom", row.lastDownloadError)
        assertEquals(1L, row.lastDownloadAttemptAt)
    }

    // ----- reapStuckDownloads (issue #705) -------------------------------------

    @Test
    fun reapStuckDownloads_flipsAgedDownloadingRowsToQueued() = runTest {
        fictionDao.upsert(fiction)
        dao.upsertAll(
            listOf(
                chapter("c1", index = 0, downloadState = ChapterDownloadState.DOWNLOADING),
                chapter("c2", index = 1, downloadState = ChapterDownloadState.DOWNLOADING),
            ),
        )
        // c1 is old (started at t=100), c2 is fresh (started at t=900).
        dao.setDownloadState("c1", ChapterDownloadState.DOWNLOADING, now = 100L, error = null)
        dao.setDownloadState("c2", ChapterDownloadState.DOWNLOADING, now = 900L, error = null)

        // Cutoff = 500. c1 (lastAttempt=100 < 500) is stuck; c2 (=900) is fresh.
        val reaped = dao.reapStuckDownloads(now = 1000L, cutoff = 500L)

        assertEquals(1, reaped)
        val c1 = dao.get("c1")!!
        val c2 = dao.get("c2")!!
        assertEquals(ChapterDownloadState.QUEUED, c1.downloadState)
        assertEquals("stuck", c1.lastDownloadError)
        assertEquals(1000L, c1.lastDownloadAttemptAt)
        // c2 untouched.
        assertEquals(ChapterDownloadState.DOWNLOADING, c2.downloadState)
        assertNull(c2.lastDownloadError)
        assertEquals(900L, c2.lastDownloadAttemptAt)
    }

    @Test
    fun reapStuckDownloads_doesNotTouchNonDownloadingRows() = runTest {
        fictionDao.upsert(fiction)
        dao.upsertAll(
            listOf(
                chapter("c1", index = 0, downloadState = ChapterDownloadState.QUEUED),
                chapter("c2", index = 1, downloadState = ChapterDownloadState.DOWNLOADED),
                chapter("c3", index = 2, downloadState = ChapterDownloadState.FAILED),
                chapter("c4", index = 3, downloadState = ChapterDownloadState.NOT_DOWNLOADED),
            ),
        )
        // Backdate all four with the same ancient timestamp.
        dao.setDownloadState("c1", ChapterDownloadState.QUEUED, now = 1L, error = null)
        dao.setDownloadState("c2", ChapterDownloadState.DOWNLOADED, now = 1L, error = null)
        dao.setDownloadState("c3", ChapterDownloadState.FAILED, now = 1L, error = "old")
        // c4 left untouched so lastDownloadAttemptAt is NULL.

        val reaped = dao.reapStuckDownloads(now = 5000L, cutoff = 4000L)

        assertEquals(0, reaped)
        assertEquals(ChapterDownloadState.QUEUED, dao.get("c1")!!.downloadState)
        assertEquals(ChapterDownloadState.DOWNLOADED, dao.get("c2")!!.downloadState)
        assertEquals(ChapterDownloadState.FAILED, dao.get("c3")!!.downloadState)
        assertEquals(ChapterDownloadState.NOT_DOWNLOADED, dao.get("c4")!!.downloadState)
    }

    @Test
    fun reapStuckDownloads_reapsRowsWithNullLastAttempt() = runTest {
        // Belt-and-suspenders: if a chapter is somehow at DOWNLOADING with a
        // NULL lastDownloadAttemptAt (shouldn't happen with the worker as
        // written, but possible if the row was hand-edited or restored from
        // a stale backup), the reaper should still flip it back to QUEUED.
        fictionDao.upsert(fiction)
        dao.upsert(
            chapter("c1", index = 0, downloadState = ChapterDownloadState.DOWNLOADING),
        )
        // Don't call setDownloadState → lastDownloadAttemptAt stays NULL.
        assertNull(dao.get("c1")!!.lastDownloadAttemptAt)

        val reaped = dao.reapStuckDownloads(now = 1000L, cutoff = 500L)

        assertEquals(1, reaped)
        val row = dao.get("c1")!!
        assertEquals(ChapterDownloadState.QUEUED, row.downloadState)
        assertEquals("stuck", row.lastDownloadError)
        assertEquals(1000L, row.lastDownloadAttemptAt)
    }

    @Test
    fun setRead_setsFirstReadAtOnFirstReadOnly() = runTest {
        fictionDao.upsert(fiction)
        dao.upsert(chapter("c1", index = 0))

        dao.setRead("c1", read = true, now = 100L)
        assertEquals(100L, dao.get("c1")!!.firstReadAt)

        // Re-mark read at a later time — firstReadAt must NOT advance.
        dao.setRead("c1", read = true, now = 999L)
        assertEquals(100L, dao.get("c1")!!.firstReadAt)
    }

    // ----- markFollowedCaughtUp (#982) ------------------------------------------

    @Test
    fun markFollowedCaughtUp_marksOnlyFollowedUnreadChapters_andStampsFirstRead() = runTest {
        // f1 is followed; f2 is in-library-only (not followed). The action must
        // flip f1's unread chapters and leave f2 alone — that's the exact set
        // the Follows tab renders (followedRemotely = 1).
        val followed = fiction.copy(id = "f1", followedRemotely = true)
        val notFollowed = fiction.copy(id = "f2", followedRemotely = false)
        fictionDao.upsert(followed)
        fictionDao.upsert(notFollowed)
        dao.upsertAll(
            listOf(
                // f1: one unread, one already read with an existing firstReadAt.
                chapter("a1", index = 0, fictionId = "f1", userMarkedRead = false),
                chapter("a2", index = 1, fictionId = "f1", userMarkedRead = true),
                // f2 (not followed): unread, must stay unread.
                chapter("b1", index = 0, fictionId = "f2", userMarkedRead = false),
            ),
        )
        // Stamp f1's already-read chapter so we can prove it isn't re-stamped.
        dao.setRead("a2", read = true, now = 50L)

        val flipped = dao.markFollowedCaughtUp(now = 100L)

        // Only the single genuinely-unread followed chapter transitioned.
        assertEquals(1, flipped)
        assertTrue(dao.get("a1")!!.userMarkedRead)
        assertEquals(100L, dao.get("a1")!!.firstReadAt)
        // Already-read followed chapter keeps its original firstReadAt.
        assertTrue(dao.get("a2")!!.userMarkedRead)
        assertEquals(50L, dao.get("a2")!!.firstReadAt)
        // Non-followed fiction is untouched.
        assertEquals(false, dao.get("b1")!!.userMarkedRead)
        assertNull(dao.get("b1")!!.firstReadAt)
    }

    @Test
    fun markFollowedCaughtUp_noUnreadFollowed_returnsZero() = runTest {
        // Already-caught-up followed fiction: nothing to transition, so the
        // count is 0 and the caller can avoid claiming a fresh save.
        val followed = fiction.copy(id = "f1", followedRemotely = true)
        fictionDao.upsert(followed)
        dao.upsert(chapter("a1", index = 0, fictionId = "f1", userMarkedRead = true))

        assertEquals(0, dao.markFollowedCaughtUp(now = 100L))
    }

    // ----- trimDownloadedBodies -------------------------------------------------

    @Test
    fun trimDownloadedBodies_keepsLastN_clearsRest() = runTest {
        fictionDao.upsert(fiction)
        dao.upsertAll(
            (1..5).map { i ->
                chapter(
                    id = "c$i",
                    index = i,
                    plainBody = "body-$i",
                    htmlBody = "<p>$i</p>",
                    downloadState = ChapterDownloadState.DOWNLOADED,
                )
            },
        )

        // Keep last 2; rows with index < (max - 2) = 3 get cleared.
        // max = 5, so cleared = indexes < 3 (indexes 1, 2).
        dao.trimDownloadedBodies("f1", keepLast = 2)

        assertNull(dao.get("c1")!!.plainBody)
        assertNull(dao.get("c2")!!.plainBody)
        // c3 is at the boundary (< MAX - 2 = 3 is FALSE) so it is kept.
        assertEquals("body-3", dao.get("c3")!!.plainBody)
        assertEquals("body-4", dao.get("c4")!!.plainBody)
        assertEquals("body-5", dao.get("c5")!!.plainBody)
    }

    @Test
    fun trimDownloadedBodies_doesNotTouchOtherFiction() = runTest {
        val other = fiction.copy(id = "f2")
        fictionDao.upsert(fiction)
        fictionDao.upsert(other)
        dao.upsertAll(
            listOf(
                chapter("a1", index = 0, plainBody = "f1-body", fictionId = "f1"),
                chapter("b1", index = 0, plainBody = "f2-body", fictionId = "f2"),
            ),
        )

        dao.trimDownloadedBodies("f1", keepLast = 0)

        assertEquals("f2-body", dao.get("b1")!!.plainBody)
    }

    // ----- bookmarks (#121) -----------------------------------------------------

    @Test
    fun setBookmark_andGet_roundTrip() = runTest {
        fictionDao.upsert(fiction)
        dao.upsert(chapter("c1", index = 0))

        assertNull(dao.getBookmark("c1"))

        dao.setBookmark("c1", 1234)
        assertEquals(1234, dao.getBookmark("c1"))

        dao.setBookmark("c1", null)
        assertNull(dao.getBookmark("c1"))
    }

    @Test
    fun allBookmarks_returnsOnlyRowsWithNonNullOffset() = runTest {
        fictionDao.upsert(fiction)
        dao.upsertAll(
            listOf(
                chapter("c1", index = 0),
                chapter("c2", index = 1),
                chapter("c3", index = 2),
            ),
        )
        dao.setBookmark("c1", 100)
        dao.setBookmark("c3", 300)

        val rows = dao.allBookmarks().sortedBy { it.chapterId }
        assertEquals(2, rows.size)
        assertEquals("c1", rows[0].chapterId)
        assertEquals(100, rows[0].charOffset)
        assertEquals("c3", rows[1].chapterId)
        assertEquals(300, rows[1].charOffset)
    }

    // ----- cacheUsage (#293) ----------------------------------------------------

    @Test
    fun cacheUsage_emptyDb_returnsZeros() = runTest {
        val usage = dao.cacheUsage()
        assertEquals(0, usage.count)
        assertEquals(0L, usage.bytes)
    }

    @Test
    fun cacheUsage_countsOnlyRowsWithNonEmptyPlainBody() = runTest {
        fictionDao.upsert(fiction)
        dao.upsertAll(
            listOf(
                chapter("c1", index = 0, plainBody = "abcd"), // 4 chars
                chapter("c2", index = 1, plainBody = ""), // excluded (<> '')
                chapter("c3", index = 2, plainBody = null), // excluded (NULL)
                chapter("c4", index = 3, plainBody = "abcdef"), // 6 chars
            ),
        )

        val usage = dao.cacheUsage()
        assertEquals(2, usage.count)
        // The DAO multiplies CHAR length by 2 as a conservative byte estimate.
        assertEquals((4 + 6) * 2L, usage.bytes)
    }

    // ----- unread / played feeds ------------------------------------------------

    @Test
    fun unreadChapters_filtersToLibraryAndUnread_orderedByPublishedAtDesc() = runTest {
        val notInLibrary = fiction.copy(id = "f2", inLibrary = false)
        fictionDao.upsert(fiction)
        fictionDao.upsert(notInLibrary)
        dao.upsertAll(
            listOf(
                chapter("c1", index = 0, publishedAt = 10L, userMarkedRead = true, fictionId = "f1"),
                chapter("c2", index = 1, publishedAt = 30L, fictionId = "f1"),
                chapter("c3", index = 2, publishedAt = 20L, fictionId = "f1"),
                chapter("c4", index = 0, publishedAt = 999L, fictionId = "f2"), // excluded — not in library
            ),
        )

        val rows = dao.unreadChapters(limit = 10)
        assertEquals(listOf("c2", "c3"), rows.map { it.chapterId })
    }

    @Test
    fun observePlayedChapterIds_returnsOnlyMarkedRead() = runTest {
        fictionDao.upsert(fiction)
        dao.upsertAll(
            listOf(
                chapter("c1", index = 0, userMarkedRead = true),
                chapter("c2", index = 1, userMarkedRead = false),
                chapter("c3", index = 2, userMarkedRead = true),
            ),
        )

        val played = dao.observePlayedChapterIds("f1").first().toSet()
        assertEquals(setOf("c1", "c3"), played)
    }

    // ----- CASCADE delete -------------------------------------------------------

    @Test
    fun deletingFiction_cascadesChapters() = runTest {
        val transient = fiction.copy(id = "transient", inLibrary = false, followedRemotely = false)
        fictionDao.upsert(transient)
        dao.upsert(chapter("c1", index = 0, fictionId = "transient"))

        val deleted = fictionDao.deleteIfTransient("transient")
        assertEquals(1, deleted)

        assertNull(dao.get("c1"))
    }
}
