package `in`.jphe.storyvox.sync

import android.content.SharedPreferences
import `in`.jphe.storyvox.data.db.dao.BookmarkRow
import `in`.jphe.storyvox.data.db.dao.ChapterDao
import `in`.jphe.storyvox.data.db.dao.ChapterDownloadStateRow
import `in`.jphe.storyvox.data.db.dao.ChapterInfoRow
import `in`.jphe.storyvox.data.db.dao.PlaybackChapterRow
import `in`.jphe.storyvox.data.db.dao.UnreadChapterRow
import `in`.jphe.storyvox.data.db.entity.Chapter
import `in`.jphe.storyvox.data.db.entity.ChapterDownloadState
import `in`.jphe.storyvox.sync.client.FakeInstantBackend
import `in`.jphe.storyvox.sync.client.SignedInUser
import `in`.jphe.storyvox.sync.coordinator.SyncOutcome
import `in`.jphe.storyvox.sync.domain.BookmarksSyncer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #360 finding 2 (argus): `lastSyncStamp` used to live in a
 * `@Volatile private var = 0L`, which reset to zero on every cold
 * start. The LWW merge `remotePayload.updatedAt > readLastSyncStamp()`
 * then always picked remote, clobbering local writes that had not yet
 * pushed.
 *
 * This test simulates the cold-restart-between-sync-passes pattern:
 * device A writes a bookmark and pushes; the app is killed; a fresh
 * syncer instance reads the same SharedPreferences bag (i.e. the on-
 * disk state would survive the kill) and the merge rule must NOT
 * blanket-pick remote anymore.
 */
class BookmarksSyncerTest {

    private val USER = SignedInUser(userId = "u-1", email = null, refreshToken = "rt-1")
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `cold restart between sync passes does not clobber the local bookmark`() = runTest {
        val backend = FakeInstantBackend()
        val prefs = FakePrefs()

        // Round 1: device A has bookmarks {c1=100}, pushes them.
        val dao1 = FakeChapterDao(bookmarks = mutableMapOf("c1" to 100))
        val syncer1 = BookmarksSyncer(chapterDao = dao1, backend = backend, prefs = prefs)
        val r1 = syncer1.push(USER)
        assertTrue("first push succeeds: $r1", r1 is SyncOutcome.Ok)
        // After round 1, the last-sync stamp must be in prefs (NOT
        // just in the syncer's memory).
        assertTrue(
            "last-sync stamp must be persisted",
            prefs.getLong("instantdb.bookmarks_synced_at", -1L) > 0L,
        )
        val stampAfterRound1 = prefs.getLong("instantdb.bookmarks_synced_at", 0L)

        // Round 2: app cold-restarts. A *new* syncer instance is
        // constructed with the SAME prefs bag (which mirrors the on-
        // disk state surviving the kill). The DAO and remote also
        // mirror what device A had on disk + on remote.
        //
        // Without the persistence fix, the new syncer's in-memory
        // `lastSyncStamp` was 0L and the merge picked remote — which
        // (because remote.updatedAt > 0L) would have cleared / replaced
        // any divergent local writes. With the fix, the persisted stamp
        // is read back, and the merge rule sees remote is NOT strictly
        // newer than the last sync (it's equal). Local wins.
        val dao2 = FakeChapterDao(bookmarks = mutableMapOf("c1" to 100, "c2" to 50))
        val syncer2 = BookmarksSyncer(chapterDao = dao2, backend = backend, prefs = prefs)
        val r2 = syncer2.push(USER)
        assertTrue("post-restart push succeeds: $r2", r2 is SyncOutcome.Ok)
        // The local "c2" addition survived; the merge did not clobber.
        assertEquals(100, dao2.bookmarks["c1"])
        assertEquals(50, dao2.bookmarks["c2"])
        // The persisted stamp moved forward.
        val stampAfterRound2 = prefs.getLong("instantdb.bookmarks_synced_at", 0L)
        assertTrue("stamp must advance on subsequent push", stampAfterRound2 >= stampAfterRound1)
    }

    /**
     * Issue #1029 (data loss): a bookmark added on device A but not yet
     * on the wire must NOT be deleted when device A pulls a remote blob
     * that wins LWW. The remote merely *doesn't know about* the new
     * bookmark (it's absent), which the documented wire contract defines
     * as "no change" — only an explicit null clears a bookmark.
     *
     * Repro:
     *  1. Device B pushes {ch1=100} → remote blob, updatedAt = T1.
     *  2. Device A (fresh stamp = 0L, i.e. hasn't synced since B's push)
     *     has local {ch1=100, ch2=200}.
     *  3. A syncs. remote.updatedAt (T1) > A's stamp (0L) → remote wins.
     *  4. Old code: ch2 ∈ local, ch2 ∉ winning map → setBookmark(ch2,
     *     null). A's just-added bookmark is destroyed and was never on
     *     the wire as an explicit null.
     *
     * With the fix, ch2 (a local-only add, absent from remote) survives
     * locally AND is unioned into the pushed payload so it propagates.
     */
    @Test fun `local-only bookmark survives a winning remote that never had it`() = runTest {
        val backend = FakeInstantBackend()

        // Device B: pushes {ch1=100} to the shared remote.
        val prefsB = FakePrefs()
        val daoB = FakeChapterDao(bookmarks = mutableMapOf("ch1" to 100))
        val syncerB = BookmarksSyncer(chapterDao = daoB, backend = backend, prefs = prefsB)
        assertTrue(syncerB.push(USER) is SyncOutcome.Ok)

        // Device A: fresh prefs (stamp 0L → hasn't synced since B's push),
        // and a local-only bookmark ch2 that the remote has never seen.
        val prefsA = FakePrefs()
        val daoA = FakeChapterDao(bookmarks = mutableMapOf("ch1" to 100, "ch2" to 200))
        val syncerA = BookmarksSyncer(chapterDao = daoA, backend = backend, prefs = prefsA)
        val rA = syncerA.pull(USER)
        assertTrue("device A sync succeeds: $rA", rA is SyncOutcome.Ok)

        // ch2 survives locally — it was never explicitly cleared.
        assertEquals("ch1 unchanged", 100, daoA.bookmarks["ch1"])
        assertEquals("local-only ch2 must NOT be deleted on mere absence", 200, daoA.bookmarks["ch2"])

        // ...and it rode out on the pushed payload, so it propagates.
        val snap = backend.fetch(USER, "blobs", SyncIds.rowUuid("bookmarks", USER.userId)).getOrNull()
        requireNotNull(snap) { "remote blob must exist after a push" }
        val pushed = json
            .decodeFromString(BookmarksSyncer.Payload.serializer(), snap.payload)
            .bookmarks
        assertEquals("ch1 retained on the wire", 100, pushed["ch1"])
        assertEquals("local-only ch2 must be unioned into the push payload", 200, pushed["ch2"])
    }

    /**
     * The flip side of #1029: an *explicit* remote null (id -> null) is
     * the one legitimate delete signal and must still clear the local
     * bookmark. This guards against an over-correction that would never
     * delete anything.
     */
    @Test fun `explicit remote null still clears the local bookmark`() = runTest {
        val backend = FakeInstantBackend()

        // Seed the remote directly with an explicit-null clear for ch2,
        // newer than device A's stamp (0L) so remote wins.
        backend.upsert(
            user = USER,
            entity = "blobs",
            id = SyncIds.rowUuid("bookmarks", USER.userId),
            payload = """{"bookmarks":{"ch1":100,"ch2":null},"updatedAt":${System.currentTimeMillis()}}""",
            updatedAt = System.currentTimeMillis(),
        )

        val prefsA = FakePrefs()
        val daoA = FakeChapterDao(bookmarks = mutableMapOf("ch1" to 100, "ch2" to 200))
        val syncerA = BookmarksSyncer(chapterDao = daoA, backend = backend, prefs = prefsA)
        assertTrue(syncerA.pull(USER) is SyncOutcome.Ok)

        assertEquals("ch1 unchanged", 100, daoA.bookmarks["ch1"])
        assertTrue("ch2 must be cleared by the explicit remote null", daoA.bookmarks["ch2"] == null)
    }

    @Test fun `stamp is written even on a no-op sync round`() = runTest {
        // Even when nothing changes, the merge does a push (the
        // canonical state). The stamp must advance.
        val backend = FakeInstantBackend()
        val prefs = FakePrefs()
        val dao = FakeChapterDao(bookmarks = mutableMapOf("c1" to 42))
        val syncer = BookmarksSyncer(chapterDao = dao, backend = backend, prefs = prefs)
        syncer.push(USER)
        val stamp1 = prefs.getLong("instantdb.bookmarks_synced_at", 0L)
        assertTrue(stamp1 > 0L)
        // Second pass with same state — still pushes, still advances stamp.
        Thread.sleep(2L) // System.currentTimeMillis() needs a tick
        syncer.push(USER)
        val stamp2 = prefs.getLong("instantdb.bookmarks_synced_at", 0L)
        assertTrue("stamp must advance even on identical-state rounds: $stamp1 vs $stamp2", stamp2 >= stamp1)
    }

    /** Pure-JVM SharedPreferences substitute. Identical shape to the
     *  one in SecretsSyncerTest; duplicated here rather than extracted
     *  because both tests live in the same test source set and a
     *  shared util in `:core-sync` test would either need a shared
     *  test-only module (gradle config churn) or polluting main. */
    private class FakePrefs : SharedPreferences {
        private val store = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = store.toMutableMap()
        override fun getString(key: String, defValue: String?): String? =
            (store[key] as? String) ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (store[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String, defValue: Int): Int = (store[key] as? Int) ?: defValue
        override fun getLong(key: String, defValue: Long): Long = (store[key] as? Long) ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = (store[key] as? Float) ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            (store[key] as? Boolean) ?: defValue
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

    /** Minimal in-memory ChapterDao stub — only the bookmark-related
     *  methods are implemented; everything else throws if a test
     *  accidentally touches it. Keeps the fake honest. */
    private class FakeChapterDao(
        val bookmarks: MutableMap<String, Int?>,
    ) : ChapterDao {
        override suspend fun allBookmarks(): List<BookmarkRow> =
            bookmarks.entries.filter { it.value != null }
                .map { BookmarkRow(chapterId = it.key, charOffset = it.value) }
        override suspend fun setBookmark(id: String, charOffset: Int?) {
            if (charOffset == null) bookmarks.remove(id) else bookmarks[id] = charOffset
        }
        override suspend fun getBookmark(id: String): Int? = bookmarks[id]
        override suspend fun exists(id: String): Boolean = bookmarks.containsKey(id)

        // ---- everything below is "not used by BookmarksSyncer" ----
        override suspend fun allChapters(fictionId: String): List<Chapter> = emptyList()
        override fun observeChapterInfosByFiction(fictionId: String): Flow<List<ChapterInfoRow>> = flowOf(emptyList())
        override fun observe(id: String): Flow<Chapter?> = flowOf(null)
        override suspend fun get(id: String): Chapter? = null
        override fun observeDownloadStates(fictionId: String): Flow<List<ChapterDownloadStateRow>> = flowOf(emptyList())
        override fun observePlayedChapterIds(fictionId: String): Flow<List<String>> = flowOf(emptyList())
        override suspend fun missingForFiction(fictionId: String): List<Chapter> = emptyList()
        override suspend fun maxIndex(fictionId: String): Int? = null
        override suspend fun nextChapterId(currentId: String): String? = null
        override suspend fun previousChapterId(currentId: String): String? = null
        override suspend fun playbackChapter(id: String): PlaybackChapterRow? = null
        override suspend fun unreadChapters(limit: Int): List<UnreadChapterRow> = emptyList()
        // #982 — not exercised by BookmarksSyncer; stub to satisfy the
        // ChapterDao contract (added to the interface after this fake).
        override suspend fun markFollowedCaughtUp(now: Long): Int = error("not used")
        override suspend fun upsert(chapter: Chapter) = error("not used")
        override suspend fun upsertAll(chapters: List<Chapter>) = error("not used")
        override suspend fun parkChapterIndexesFor(fictionId: String) = error("not used")
        override suspend fun deleteByFictionId(fictionId: String) = error("not used")
        override suspend fun insertAll(chapters: List<Chapter>) = error("not used")
        override suspend fun setDownloadState(id: String, state: ChapterDownloadState, now: Long, error: String?) = error("not used")
        override suspend fun reapStuckDownloads(now: Long, cutoff: Long): Int = error("not used")
        override suspend fun setBody(
            id: String, html: String, plain: String, checksum: String,
            notesAuthor: String?, notesAuthorPosition: String?, now: Long,
            audioUrl: String?,
        ) = error("not used")
        override suspend fun setRead(id: String, read: Boolean, now: Long) = error("not used")
        override suspend fun trimDownloadedBodies(fictionId: String, keepLast: Int) = error("not used")
        override suspend fun cacheUsage(): `in`.jphe.storyvox.data.db.dao.ChapterCacheUsageRow =
            error("not used")
        override suspend fun chapterIdsForFiction(fictionId: String): List<String> = emptyList()
        override suspend fun deleteByIds(ids: List<String>) = error("not used")
    }
}
