package `in`.jphe.storyvox.data.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import `in`.jphe.storyvox.data.db.StoryvoxDatabase
import `in`.jphe.storyvox.data.db.entity.Chapter
import `in`.jphe.storyvox.data.db.entity.Fiction
import `in`.jphe.storyvox.data.db.entity.PlaybackPosition
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #48 — Room+Robolectric DAO test for [PlaybackDao].
 *
 * The hot path is [PlaybackDao.observeMostRecentContinueListening] — the
 * `LIMIT 1` + slim projection introduced for the Library Resume tile. A
 * regression in either dimension (wrong row, wrong columns) ships a
 * user-visible bug, so this is the single most valuable DAO test surface.
 *
 *  - LIMIT 1 means only the topmost row by `updatedAt DESC` may emit.
 *  - The projection is hand-rolled `f.col AS f_col` aliasing — typos in
 *    either side compile fine and silently produce wrong values.
 *  - `f.inLibrary = 1` filters out un-libraried fictions even if a
 *    `playback_position` row still exists for them.
 *
 * Also covers recent() + the cascade delete from chapter / fiction.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PlaybackDaoTest {

    private lateinit var db: StoryvoxDatabase
    private lateinit var dao: PlaybackDao
    private lateinit var fictionDao: FictionDao
    private lateinit var chapterDao: ChapterDao

    private fun fiction(
        id: String = "f1",
        title: String = "Sky Pride",
        inLibrary: Boolean = true,
        coverUrl: String? = "https://example.test/$id.jpg",
    ) = Fiction(
        id = id,
        sourceId = "royalroad",
        title = title,
        author = "Anonymous",
        coverUrl = coverUrl,
        description = "desc-$id",
        tags = listOf("Adventure"),
        firstSeenAt = 0L,
        metadataFetchedAt = 0L,
        inLibrary = inLibrary,
    )

    private fun chapter(
        id: String,
        fictionId: String,
        index: Int = 0,
        title: String = "Ch-$id",
    ) = Chapter(
        id = id,
        fictionId = fictionId,
        sourceChapterId = "src-$id",
        index = index,
        title = title,
        publishedAt = 0L,
        wordCount = 100,
    )

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, StoryvoxDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.playbackDao()
        fictionDao = db.fictionDao()
        chapterDao = db.chapterDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun observeMostRecentContinueListening_returnsTopRowByUpdatedAtDesc() = runTest {
        fictionDao.upsert(fiction("f1", title = "Old"))
        fictionDao.upsert(fiction("f2", title = "New"))
        chapterDao.upsert(chapter("c1", "f1"))
        chapterDao.upsert(chapter("c2", "f2"))
        dao.upsert(PlaybackPosition(fictionId = "f1", chapterId = "c1", updatedAt = 100L))
        dao.upsert(PlaybackPosition(fictionId = "f2", chapterId = "c2", updatedAt = 200L))

        val row = dao.observeMostRecentContinueListening().first()
        assertNotNull(row)
        assertEquals("f2", row!!.f_id)
        assertEquals("New", row.f_title)
        assertEquals("c2", row.c_id)
    }

    @Test
    fun observeMostRecentContinueListening_excludesUnLibrariedFiction() = runTest {
        fictionDao.upsert(fiction("f1", title = "Library", inLibrary = true))
        fictionDao.upsert(fiction("f2", title = "Transient", inLibrary = false))
        chapterDao.upsert(chapter("c1", "f1"))
        chapterDao.upsert(chapter("c2", "f2"))

        // The un-libraried fiction has the more recent updatedAt, but
        // observeMostRecentContinueListening must skip it.
        dao.upsert(PlaybackPosition("f1", "c1", updatedAt = 100L))
        dao.upsert(PlaybackPosition("f2", "c2", updatedAt = 999L))

        val row = dao.observeMostRecentContinueListening().first()
        assertNotNull(row)
        assertEquals("f1", row!!.f_id)
    }

    @Test
    fun observeMostRecentContinueListening_emptyDb_emitsNull() = runTest {
        val row = dao.observeMostRecentContinueListening().first()
        assertNull(row)
    }

    @Test
    fun observeMostRecentContinueListening_projectionAliasesAreCorrect() = runTest {
        // Defensive: build a row where every aliased column has a distinct
        // value, then verify the SELECT...AS...AS mapping doesn't transpose
        // anything. Catches typos like `f.title AS c_title` that would compile.
        fictionDao.upsert(
            fiction("f1", title = "Fiction title", coverUrl = "https://cover.test/x.jpg"),
        )
        chapterDao.upsert(chapter("c1", "f1", index = 7, title = "Chapter title"))
        dao.upsert(
            PlaybackPosition(
                fictionId = "f1",
                chapterId = "c1",
                charOffset = 1234,
                playbackSpeed = 1.5f,
                updatedAt = 500L,
            ),
        )

        val row = dao.observeMostRecentContinueListening().first()!!
        assertEquals("f1", row.f_id)
        assertEquals("Fiction title", row.f_title)
        assertEquals("https://cover.test/x.jpg", row.f_coverUrl)
        assertEquals("c1", row.c_id)
        assertEquals(7, row.c_index)
        assertEquals("Chapter title", row.c_title)
        assertEquals(1234, row.charOffset)
        assertEquals(1.5f, row.playbackSpeed, 0.0001f)
        assertEquals(500L, row.updatedAt)
    }

    @Test
    fun recent_joinsAndOrdersByUpdatedAtDesc_respectsLimit() = runTest {
        fictionDao.upsert(fiction("f1", title = "A"))
        fictionDao.upsert(fiction("f2", title = "B"))
        fictionDao.upsert(fiction("f3", title = "C"))
        chapterDao.upsert(chapter("c1", "f1"))
        chapterDao.upsert(chapter("c2", "f2"))
        chapterDao.upsert(chapter("c3", "f3"))
        dao.upsert(PlaybackPosition("f1", "c1", updatedAt = 10L))
        dao.upsert(PlaybackPosition("f2", "c2", updatedAt = 30L))
        dao.upsert(PlaybackPosition("f3", "c3", updatedAt = 20L))

        val rows = dao.recent(limit = 2)
        assertEquals(listOf("f2", "f3"), rows.map { it.fictionId })
        assertEquals("B", rows[0].bookTitle)
    }

    @Test
    fun deleteFiction_cascadesPlaybackPosition() = runTest {
        // A transient fiction so we can use deleteIfTransient as the trigger.
        val transient = fiction("transient", inLibrary = false)
        fictionDao.upsert(transient)
        chapterDao.upsert(chapter("ct", "transient"))
        dao.upsert(PlaybackPosition("transient", "ct", updatedAt = 1L))

        fictionDao.deleteIfTransient("transient")

        assertNull(dao.get("transient"))
    }

    @Test
    fun allPositionsSnapshot_returnsEveryRow_withSyncFields() = runTest {
        fictionDao.upsert(fiction("f1"))
        fictionDao.upsert(fiction("f2"))
        chapterDao.upsert(chapter("c1", "f1"))
        chapterDao.upsert(chapter("c2", "f2"))
        dao.upsert(
            PlaybackPosition(
                fictionId = "f1",
                chapterId = "c1",
                charOffset = 42,
                paragraphIndex = 3,
                playbackSpeed = 1.25f,
                durationEstimateMs = 9_999L,
                updatedAt = 100L,
            ),
        )
        dao.upsert(PlaybackPosition("f2", "c2", updatedAt = 200L))

        val rows = dao.allPositionsSnapshot().associateBy { it.fictionId }
        assertEquals(2, rows.size)
        val r1 = rows.getValue("f1")
        assertEquals("c1", r1.chapterId)
        assertEquals(42, r1.charOffset)
        assertEquals(3, r1.paragraphIndex)
        assertEquals(1.25f, r1.playbackSpeed, 0.0001f)
        assertEquals(9_999L, r1.durationEstimateMs)
        assertEquals(100L, r1.updatedAt)
        assertEquals("f2", rows.getValue("f2").fictionId)
    }

    @Test
    fun allPositionsSnapshot_emptyDb_returnsEmptyList() = runTest {
        assertEquals(emptyList<PlaybackPositionSnapshotRow>(), dao.allPositionsSnapshot())
    }

    @Test
    fun upsertThenDelete_isIdempotent() = runTest {
        fictionDao.upsert(fiction("f1"))
        chapterDao.upsert(chapter("c1", "f1"))
        dao.upsert(PlaybackPosition("f1", "c1", updatedAt = 1L))
        dao.delete("f1")

        assertNull(dao.get("f1"))

        // Second delete is a no-op, must not throw.
        dao.delete("f1")
    }

    // ─── Issue #965: per-chapter (fictionId, chapterId) PK semantics ──────

    @Test
    fun multipleChaptersOfOneFiction_coexistAsIndependentRows() = runTest {
        // Under the old fictionId-only PK the second upsert would REPLACE the
        // first. With the composite PK both rows must survive.
        fictionDao.upsert(fiction("f1"))
        chapterDao.upsert(chapter("c1", "f1", index = 0))
        chapterDao.upsert(chapter("c2", "f1", index = 1))
        dao.upsert(PlaybackPosition("f1", "c1", charOffset = 100, updatedAt = 10L))
        dao.upsert(PlaybackPosition("f1", "c2", charOffset = 200, updatedAt = 20L))

        val rows = dao.allPositionsSnapshot()
            .filter { it.fictionId == "f1" }
            .associateBy { it.chapterId }
        assertEquals(2, rows.size)
        assertEquals(100, rows.getValue("c1").charOffset)
        assertEquals(200, rows.getValue("c2").charOffset)
    }

    @Test
    fun getByFiction_returnsMostRecentlyPlayedChapter() = runTest {
        // load(fictionId) / observe(fictionId) must surface the freshest
        // chapter, never a stale earlier one — the core #965 fix.
        fictionDao.upsert(fiction("f1"))
        chapterDao.upsert(chapter("c1", "f1", index = 0))
        chapterDao.upsert(chapter("c2", "f1", index = 1))
        dao.upsert(PlaybackPosition("f1", "c1", charOffset = 100, updatedAt = 10L))
        dao.upsert(PlaybackPosition("f1", "c2", charOffset = 200, updatedAt = 20L))

        val latest = dao.get("f1")
        assertNotNull(latest)
        assertEquals("c2", latest!!.chapterId)
        assertEquals(200, latest.charOffset)

        // Observing the fiction yields the same freshest row.
        val observed = dao.observe("f1").first()
        assertEquals("c2", observed!!.chapterId)

        // Backing up to an earlier chapter (newer timestamp on c1) flips the
        // resume target back to c1 — the "skips forward" guard.
        dao.upsert(PlaybackPosition("f1", "c1", charOffset = 50, updatedAt = 30L))
        assertEquals("c1", dao.get("f1")!!.chapterId)
        assertEquals(50, dao.get("f1")!!.charOffset)
    }

    @Test
    fun getByFictionAndChapter_returnsExactChapterRow() = runTest {
        fictionDao.upsert(fiction("f1"))
        chapterDao.upsert(chapter("c1", "f1", index = 0))
        chapterDao.upsert(chapter("c2", "f1", index = 1))
        dao.upsert(
            PlaybackPosition(
                "f1", "c1", charOffset = 11, paragraphIndex = 3,
                playbackSpeed = 1.25f, updatedAt = 99L,
            ),
        )
        dao.upsert(PlaybackPosition("f1", "c2", charOffset = 22, updatedAt = 100L))

        // Exact-row lookup ignores recency — returns the asked-for chapter,
        // so the repository's save() preserves the SAME chapter's fields.
        val c1 = dao.get("f1", "c1")
        assertNotNull(c1)
        assertEquals(11, c1!!.charOffset)
        assertEquals(3, c1.paragraphIndex)
        assertEquals(1.25f, c1.playbackSpeed, 0.0001f)

        assertNull("non-existent chapter row is null", dao.get("f1", "nope"))
    }

    @Test
    fun observeLastPlayedTimes_collapsesToMaxPerFiction() = runTest {
        // With multiple chapter rows per fiction the Library sort must still
        // see exactly one (fictionId, lastPlayedAt) — the MAX(updatedAt).
        fictionDao.upsert(fiction("f1"))
        fictionDao.upsert(fiction("f2"))
        chapterDao.upsert(chapter("c1", "f1", index = 0))
        chapterDao.upsert(chapter("c2", "f1", index = 1))
        chapterDao.upsert(chapter("c3", "f2", index = 0))
        dao.upsert(PlaybackPosition("f1", "c1", updatedAt = 10L))
        dao.upsert(PlaybackPosition("f1", "c2", updatedAt = 40L))
        dao.upsert(PlaybackPosition("f2", "c3", updatedAt = 25L))

        val map = dao.observeLastPlayedTimes().first().associate { it.fictionId to it.updatedAt }
        assertEquals(2, map.size)
        assertEquals("f1 collapses to its newest chapter timestamp", 40L, map.getValue("f1"))
        assertEquals(25L, map.getValue("f2"))
    }

    @Test
    fun recent_collapsesToOneRowPerFiction_mostRecentChapter() = runTest {
        // The Auto rail lists books, not chapters: each fiction appears once,
        // at its most-recently-played chapter.
        fictionDao.upsert(fiction("f1", title = "A"))
        fictionDao.upsert(fiction("f2", title = "B"))
        chapterDao.upsert(chapter("c1", "f1", index = 0, title = "A-ch0"))
        chapterDao.upsert(chapter("c2", "f1", index = 1, title = "A-ch1"))
        chapterDao.upsert(chapter("c3", "f2", index = 0, title = "B-ch0"))
        dao.upsert(PlaybackPosition("f1", "c1", updatedAt = 10L))
        dao.upsert(PlaybackPosition("f1", "c2", updatedAt = 50L))
        dao.upsert(PlaybackPosition("f2", "c3", updatedAt = 30L))

        val rows = dao.recent(limit = 10)
        assertEquals("one row per fiction", listOf("f1", "f2"), rows.map { it.fictionId })
        // f1's row is its newest chapter (c2 @50), not c1 @10.
        assertEquals("c2", rows.first { it.fictionId == "f1" }.chapterId)
        assertEquals("A-ch1", rows.first { it.fictionId == "f1" }.chapterTitle)
    }

    @Test
    fun observeMostRecentContinueListening_picksFreshestChapterAcrossAllFictions() = runTest {
        // The single Continue tile resumes the freshest chapter overall, even
        // when one fiction has several chapter rows.
        fictionDao.upsert(fiction("f1", title = "A"))
        fictionDao.upsert(fiction("f2", title = "B"))
        chapterDao.upsert(chapter("c1", "f1", index = 0))
        chapterDao.upsert(chapter("c2", "f1", index = 1))
        chapterDao.upsert(chapter("c3", "f2", index = 0))
        dao.upsert(PlaybackPosition("f1", "c1", updatedAt = 100L))
        dao.upsert(PlaybackPosition("f1", "c2", updatedAt = 300L))
        dao.upsert(PlaybackPosition("f2", "c3", updatedAt = 200L))

        val row = dao.observeMostRecentContinueListening().first()
        assertNotNull(row)
        assertEquals("f1", row!!.f_id)
        assertEquals("c2", row.c_id)
    }

    @Test
    fun deleteByFiction_clearsEveryChapterRow() = runTest {
        fictionDao.upsert(fiction("f1"))
        chapterDao.upsert(chapter("c1", "f1", index = 0))
        chapterDao.upsert(chapter("c2", "f1", index = 1))
        dao.upsert(PlaybackPosition("f1", "c1", updatedAt = 10L))
        dao.upsert(PlaybackPosition("f1", "c2", updatedAt = 20L))

        dao.delete("f1")

        assertNull(dao.get("f1"))
        assertEquals(emptyList<PlaybackPositionSnapshotRow>(), dao.allPositionsSnapshot())
    }
}
