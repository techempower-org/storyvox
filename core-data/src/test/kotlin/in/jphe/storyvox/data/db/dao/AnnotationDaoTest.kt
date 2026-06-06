package `in`.jphe.storyvox.data.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import `in`.jphe.storyvox.data.db.StoryvoxDatabase
import `in`.jphe.storyvox.data.db.entity.Annotation
import `in`.jphe.storyvox.data.db.entity.Chapter
import `in`.jphe.storyvox.data.db.entity.Fiction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Roundtrip exercise of [AnnotationDao] (issue #999) against an in-memory
 * Room database. Covers the operations the reader overlay, the per-fiction
 * list, the export builder, and [AnnotationsSyncer] rely on:
 *
 *  - upsert → observeForChapter / observeForFiction return it
 *  - upsert same id → REPLACE (edit a note in place, not a duplicate row)
 *  - observeForFiction ordering: chapter index, then start offset
 *  - exists() probe (the syncer FK-guard)
 *  - deleteById / clearForFiction
 *  - CASCADE delete of the fiction drops its annotations
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AnnotationDaoTest {

    private lateinit var db: StoryvoxDatabase
    private lateinit var dao: AnnotationDao
    private lateinit var fictionDao: FictionDao
    private lateinit var chapterDao: ChapterDao

    private val fiction = Fiction(
        id = "f1",
        sourceId = "royalroad",
        title = "Sky Pride",
        author = "Anonymous",
        firstSeenAt = 0L,
        metadataFetchedAt = 0L,
        inLibrary = true,
    )

    // Two chapters so the per-fiction ordering (chapter index asc) is exercised.
    private val ch0 = Chapter(
        id = "f1:0", fictionId = "f1", sourceChapterId = "0", index = 0, title = "One",
    )
    private val ch1 = Chapter(
        id = "f1:1", fictionId = "f1", sourceChapterId = "1", index = 1, title = "Two",
    )

    private fun ann(
        id: String,
        chapterId: String,
        start: Int,
        end: Int,
        color: String = "YELLOW",
        note: String? = null,
        quoted: String = "snippet",
        at: Long = 1000L,
    ) = Annotation(
        id = id, fictionId = "f1", chapterId = chapterId,
        startOffset = start, endOffset = end, color = color,
        note = note, quotedText = quoted, createdAt = at, updatedAt = at,
    )

    @Before
    fun setUp() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, StoryvoxDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.annotationDao()
        fictionDao = db.fictionDao()
        chapterDao = db.chapterDao()
        fictionDao.upsert(fiction)
        chapterDao.upsert(ch0)
        chapterDao.upsert(ch1)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun upsert_thenObserveForChapter_returnsIt() = runTest {
        dao.upsert(ann("a1", "f1:0", 10, 20, note = "hi"))

        val rows = dao.observeForChapter("f1:0").first()
        assertEquals(1, rows.size)
        assertEquals("a1", rows[0].id)
        assertEquals("hi", rows[0].note)
    }

    @Test
    fun upsertSameId_replacesInPlace() = runTest {
        dao.upsert(ann("a1", "f1:0", 10, 20, note = null, at = 1000L))
        // Edit: add a note, bump updatedAt — REPLACE, not a second row.
        dao.upsert(ann("a1", "f1:0", 10, 20, note = "added later", at = 2000L))

        val rows = dao.observeForChapter("f1:0").first()
        assertEquals("exactly one row after re-upsert", 1, rows.size)
        assertEquals("added later", rows[0].note)
        assertEquals(2000L, rows[0].updatedAt)
    }

    @Test
    fun observeForFiction_ordersByChapterIndexThenStartOffset() = runTest {
        // Insert out of order across both chapters.
        dao.upsert(ann("b", "f1:1", 5, 9))      // chapter index 1
        dao.upsert(ann("a2", "f1:0", 40, 50))   // chapter index 0, later offset
        dao.upsert(ann("a1", "f1:0", 10, 20))   // chapter index 0, earlier offset

        val ids = dao.observeForFiction("f1").first().map { it.id }
        assertEquals(listOf("a1", "a2", "b"), ids)
    }

    @Test
    fun exists_reflectsPresence() = runTest {
        assertFalse(dao.exists("nope"))
        dao.upsert(ann("a1", "f1:0", 10, 20))
        assertTrue(dao.exists("a1"))
    }

    @Test
    fun deleteById_removesOnlyThatRow() = runTest {
        dao.upsert(ann("a1", "f1:0", 10, 20))
        dao.upsert(ann("a2", "f1:0", 40, 50))

        dao.deleteById("a1")

        val ids = dao.observeForFiction("f1").first().map { it.id }
        assertEquals(listOf("a2"), ids)
    }

    @Test
    fun clearForFiction_dropsAll() = runTest {
        dao.upsert(ann("a1", "f1:0", 10, 20))
        dao.upsert(ann("b", "f1:1", 5, 9))

        dao.clearForFiction("f1")
        assertTrue(dao.annotationsForFiction("f1").isEmpty())
    }

    @Test
    fun deletingFiction_cascadesAnnotations() = runTest {
        dao.upsert(ann("a1", "f1:0", 10, 20))
        dao.upsert(ann("b", "f1:1", 5, 9))

        // Transient fiction is eligible for deleteIfTransient; CASCADE drops
        // its annotations (and chapters). Flip library/follow off first.
        fictionDao.setInLibrary("f1", false, now = 0L)
        val deleted = fictionDao.deleteIfTransient("f1")
        assertEquals(1, deleted)
        assertTrue(dao.annotationsForFiction("f1").isEmpty())
    }

    @Test
    fun nullNote_roundTrips() = runTest {
        dao.upsert(ann("a1", "f1:0", 10, 20, note = null))
        assertNull(dao.observeForChapter("f1:0").first().single().note)
    }
}
