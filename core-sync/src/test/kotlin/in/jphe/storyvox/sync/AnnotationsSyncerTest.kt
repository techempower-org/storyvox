package `in`.jphe.storyvox.sync

import android.content.SharedPreferences
import `in`.jphe.storyvox.data.db.dao.AnnotationDao
import `in`.jphe.storyvox.data.db.entity.Annotation
import `in`.jphe.storyvox.sync.client.FakeInstantBackend
import `in`.jphe.storyvox.sync.client.SignedInUser
import `in`.jphe.storyvox.sync.coordinator.SyncOutcome
import `in`.jphe.storyvox.sync.domain.AnnotationsSyncer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #999 — AnnotationsSyncer mirrors BookmarksSyncer, so its tests mirror
 * BookmarksSyncerTest: the #360 persisted-stamp cold-restart guard, the
 * #1029 local-only-survives-a-winning-remote contract, the explicit-null
 * delete signal, and stamp advancement on a no-op round. Plus the
 * annotation-specific FK skip-retain (parent fiction/chapter not local yet).
 */
class AnnotationsSyncerTest {

    private val USER = SignedInUser(userId = "u-1", email = null, refreshToken = "rt-1")
    private val json = Json { ignoreUnknownKeys = true }

    private fun ann(
        id: String,
        chapterId: String = "f1:0",
        fictionId: String = "f1",
        start: Int = 0,
        end: Int = 5,
        note: String? = null,
        at: Long = 1000L,
    ) = Annotation(
        id = id, fictionId = fictionId, chapterId = chapterId,
        startOffset = start, endOffset = end, color = "YELLOW",
        note = note, quotedText = "snip", createdAt = at, updatedAt = at,
    )

    private fun syncer(
        dao: FakeAnnotationDao,
        backend: FakeInstantBackend,
        prefs: SharedPreferences,
        parentsPresent: Boolean = true,
    ) = AnnotationsSyncer(
        annotationDao = dao,
        backend = backend,
        prefs = prefs,
        chapterExists = { parentsPresent },
        fictionExists = { parentsPresent },
    )

    @Test fun `cold restart between sync passes does not clobber the local annotation`() = runTest {
        val backend = FakeInstantBackend()
        val prefs = FakePrefs()

        val dao1 = FakeAnnotationDao(mutableMapOf("a1" to ann("a1")))
        val r1 = syncer(dao1, backend, prefs).push(USER)
        assertTrue("first push succeeds: $r1", r1 is SyncOutcome.Ok)
        val stamp1 = prefs.getLong("instantdb.annotations_synced_at", -1L)
        assertTrue("last-sync stamp must persist", stamp1 > 0L)

        // Cold restart: new syncer instance, SAME prefs bag, plus a local-only
        // add a2. Must not clobber.
        val dao2 = FakeAnnotationDao(mutableMapOf("a1" to ann("a1"), "a2" to ann("a2", start = 10, end = 15)))
        val r2 = syncer(dao2, backend, prefs).push(USER)
        assertTrue("post-restart push succeeds: $r2", r2 is SyncOutcome.Ok)
        assertTrue("a1 survives", dao2.rows.containsKey("a1"))
        assertTrue("local-only a2 survives", dao2.rows.containsKey("a2"))
        assertTrue(prefs.getLong("instantdb.annotations_synced_at", 0L) >= stamp1)
    }

    @Test fun `local-only annotation survives a winning remote that never had it`() = runTest {
        val backend = FakeInstantBackend()

        // Device B pushes {a1}.
        val daoB = FakeAnnotationDao(mutableMapOf("a1" to ann("a1")))
        syncer(daoB, backend, FakePrefs()).push(USER)

        // Device A: fresh stamp (0L), local-only a2 the remote never saw.
        val daoA = FakeAnnotationDao(mutableMapOf("a1" to ann("a1"), "a2" to ann("a2", start = 10, end = 15)))
        val rA = syncer(daoA, backend, FakePrefs()).pull(USER)
        assertTrue("device A sync succeeds: $rA", rA is SyncOutcome.Ok)

        assertTrue("a1 unchanged", daoA.rows.containsKey("a1"))
        assertTrue("local-only a2 must NOT be deleted on mere absence", daoA.rows.containsKey("a2"))

        // a2 rode out on the push payload.
        val snap = backend.fetch(USER, "blobs", SyncIds.rowUuid("annotations", USER.userId)).getOrNull()
        requireNotNull(snap) { "remote blob must exist after a push" }
        val pushed = json.decodeFromString(AnnotationsSyncer.Payload.serializer(), snap.payload).annotations
        assertTrue("a1 retained on the wire", pushed["a1"] != null)
        assertTrue("local-only a2 must be unioned into the push payload", pushed["a2"] != null)
    }

    @Test fun `explicit remote null still clears the local annotation`() = runTest {
        val backend = FakeInstantBackend()

        // Seed remote with an explicit-null tombstone for a2, newer than A's stamp.
        val now = System.currentTimeMillis()
        backend.upsert(
            user = USER,
            entity = "blobs",
            id = SyncIds.rowUuid("annotations", USER.userId),
            payload = """{"annotations":{"a1":{"fictionId":"f1","chapterId":"f1:0","startOffset":0,"endOffset":5,"color":"YELLOW","note":null,"quotedText":"snip","createdAt":1000,"updatedAt":1000},"a2":null},"updatedAt":$now}""",
            updatedAt = now,
        )

        val daoA = FakeAnnotationDao(mutableMapOf("a1" to ann("a1"), "a2" to ann("a2", start = 10, end = 15)))
        assertTrue(syncer(daoA, backend, FakePrefs()).pull(USER) is SyncOutcome.Ok)

        assertTrue("a1 unchanged", daoA.rows.containsKey("a1"))
        assertNull("a2 must be cleared by the explicit remote null", daoA.rows["a2"])
    }

    @Test fun `incoming annotation with missing parent rows is skipped but retained on wire`() = runTest {
        val backend = FakeInstantBackend()

        // Remote has a1; device A's parents are NOT yet hydrated locally.
        val now = System.currentTimeMillis()
        backend.upsert(
            user = USER,
            entity = "blobs",
            id = SyncIds.rowUuid("annotations", USER.userId),
            payload = """{"annotations":{"a1":{"fictionId":"f1","chapterId":"f1:0","startOffset":0,"endOffset":5,"color":"YELLOW","note":null,"quotedText":"snip","createdAt":1000,"updatedAt":1000}},"updatedAt":$now}""",
            updatedAt = now,
        )

        val daoA = FakeAnnotationDao(mutableMapOf())
        val rA = syncer(daoA, backend, FakePrefs(), parentsPresent = false).pull(USER)
        assertTrue(rA is SyncOutcome.Ok)

        // Not written locally (FK guard), but retained on the wire for a later round.
        assertTrue("a1 must NOT be written when parents are missing", daoA.rows.isEmpty())
        val snap = backend.fetch(USER, "blobs", SyncIds.rowUuid("annotations", USER.userId)).getOrNull()!!
        val pushed = json.decodeFromString(AnnotationsSyncer.Payload.serializer(), snap.payload).annotations
        assertTrue("a1 retained on the wire for a later round", pushed["a1"] != null)
    }

    @Test fun `edited note applies on a winning remote`() = runTest {
        val backend = FakeInstantBackend()

        // Device B pushes a1 with a note, newer stamp.
        val daoB = FakeAnnotationDao(mutableMapOf("a1" to ann("a1", note = "edited", at = 5000L)))
        syncer(daoB, backend, FakePrefs()).push(USER)

        // Device A has the old (note-less) a1, fresh stamp → remote wins, note applies.
        val daoA = FakeAnnotationDao(mutableMapOf("a1" to ann("a1", note = null, at = 1000L)))
        assertTrue(syncer(daoA, backend, FakePrefs()).pull(USER) is SyncOutcome.Ok)
        assertEquals("edited", daoA.rows["a1"]?.note)
    }

    @Test fun `stamp advances even on a no-op round`() = runTest {
        val backend = FakeInstantBackend()
        val prefs = FakePrefs()
        val dao = FakeAnnotationDao(mutableMapOf("a1" to ann("a1")))
        val s = syncer(dao, backend, prefs)
        s.push(USER)
        val stamp1 = prefs.getLong("instantdb.annotations_synced_at", 0L)
        assertTrue(stamp1 > 0L)
        Thread.sleep(2L)
        s.push(USER)
        assertTrue(prefs.getLong("instantdb.annotations_synced_at", 0L) >= stamp1)
    }

    /** Pure-JVM SharedPreferences substitute — identical to the one in
     *  BookmarksSyncerTest; duplicated for the same reason (same test source
     *  set, no shared test-only module). */
    private class FakePrefs : SharedPreferences {
        private val store = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = store.toMutableMap()
        override fun getString(key: String, defValue: String?): String? = (store[key] as? String) ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (store[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String, defValue: Int): Int = (store[key] as? Int) ?: defValue
        override fun getLong(key: String, defValue: Long): Long = (store[key] as? Long) ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = (store[key] as? Float) ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = (store[key] as? Boolean) ?: defValue
        override fun contains(key: String): Boolean = store.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor()
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        private inner class FakeEditor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var clearAll = false
            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { pending[key] = REMOVE_SENTINEL }
            override fun clear() = apply { clearAll = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearAll) store.clear()
                for ((k, v) in pending) if (v === REMOVE_SENTINEL) store.remove(k) else store[k] = v
            }
        }
        private companion object { val REMOVE_SENTINEL = Any() }
    }

    /** Minimal in-memory AnnotationDao — only the methods AnnotationsSyncer
     *  touches are implemented; everything else throws if accidentally hit. */
    private class FakeAnnotationDao(
        val rows: MutableMap<String, Annotation>,
    ) : AnnotationDao {
        override suspend fun allAnnotations(): List<Annotation> = rows.values.toList()
        override suspend fun upsert(annotation: Annotation) { rows[annotation.id] = annotation }
        override suspend fun deleteById(id: String) { rows.remove(id) }
        override suspend fun exists(id: String): Boolean = rows.containsKey(id)

        // ---- not used by AnnotationsSyncer ----
        override fun observeForFiction(fictionId: String): Flow<List<Annotation>> = flowOf(emptyList())
        override fun observeForChapter(chapterId: String): Flow<List<Annotation>> = flowOf(emptyList())
        override suspend fun annotationsForFiction(fictionId: String): List<Annotation> = error("not used")
        override suspend fun clearForFiction(fictionId: String) = error("not used")
    }
}
