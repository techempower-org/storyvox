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
}
