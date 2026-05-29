package `in`.jphe.storyvox.data.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import `in`.jphe.storyvox.data.db.StoryvoxDatabase
import `in`.jphe.storyvox.data.db.entity.DownloadMode
import `in`.jphe.storyvox.data.db.entity.Fiction
import `in`.jphe.storyvox.data.source.model.FictionStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #48 — Room+Robolectric DAO test for [FictionDao].
 *
 * Goal: exercise the SQL itself end-to-end, not the repository's coordination
 * logic. The repository tests use fakes that bypass Room; those would silently
 * tolerate things like a typoed column name in a `@Query` or a wrong index in
 * a composite WHERE clause. These tests catch:
 *
 *  - schema-affecting migrations that forget to update an `@Insert`/`@Query`
 *  - `setX(...)` queries that point at a renamed/removed column
 *  - `@Transaction` boundary mistakes inside [FictionDao.upsertAllPreservingUserState]
 *  - composite-predicate correctness for [FictionDao.deleteIfTransient]
 *  - ordering / projection contracts the UI relies on
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class FictionDaoTest {

    private lateinit var db: StoryvoxDatabase
    private lateinit var dao: FictionDao

    private fun fixture(
        id: String = "f1",
        sourceId: String = "royalroad",
        title: String = "Sky Pride",
        inLibrary: Boolean = false,
        followedRemotely: Boolean = false,
        addedToLibraryAt: Long? = null,
        lastUpdatedAt: Long? = null,
        metadataFetchedAt: Long = 0L,
        metadataBackfillFailedAt: Long? = null,
    ) = Fiction(
        id = id,
        sourceId = sourceId,
        title = title,
        author = "Anonymous",
        firstSeenAt = 0L,
        metadataFetchedAt = metadataFetchedAt,
        inLibrary = inLibrary,
        addedToLibraryAt = addedToLibraryAt,
        followedRemotely = followedRemotely,
        lastUpdatedAt = lastUpdatedAt,
        metadataBackfillFailedAt = metadataBackfillFailedAt,
    )

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, StoryvoxDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.fictionDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ----- core CRUD round-trip -------------------------------------------------

    @Test
    fun upsertThenGet_roundtripsAllColumns() = runTest {
        val f = fixture().copy(
            description = "A flight of fancy",
            tags = listOf("Adventure", "Magic"),
            status = FictionStatus.COMPLETED,
            chapterCount = 42,
            rating = 4.5f,
            inLibrary = true,
            addedToLibraryAt = 1_000L,
            downloadMode = DownloadMode.EAGER,
            pinnedVoiceId = "voice-x",
            pinnedVoiceLocale = "en-US",
            lastSeenRevision = "abc123",
        )
        dao.upsert(f)

        val out = dao.get("f1")
        assertEquals(f, out)
    }

    @Test
    fun get_unknownId_isNull() = runTest {
        assertNull(dao.get("nope"))
    }

    @Test
    fun getMany_returnsRowsForKnownIdsOnly() = runTest {
        dao.upsert(fixture(id = "a"))
        dao.upsert(fixture(id = "b"))

        val rows = dao.getMany(listOf("a", "missing", "b")).map { it.id }.toSet()
        assertEquals(setOf("a", "b"), rows)
    }

    // ----- observeLibrary / observeFollowsRemote --------------------------------

    @Test
    fun observeLibrary_filtersAndOrdersByAddedToLibraryAtDesc() = runTest {
        dao.upsert(fixture(id = "old", inLibrary = true, addedToLibraryAt = 100L))
        dao.upsert(fixture(id = "new", inLibrary = true, addedToLibraryAt = 999L))
        dao.upsert(fixture(id = "transient", inLibrary = false))

        val ids = dao.observeLibrary().first().map { it.id }
        assertEquals(listOf("new", "old"), ids)
    }

    @Test
    fun observeFollowsRemote_filtersAndOrdersByLastUpdatedAtDesc() = runTest {
        dao.upsert(fixture(id = "stale", followedRemotely = true, lastUpdatedAt = 5L))
        dao.upsert(fixture(id = "fresh", followedRemotely = true, lastUpdatedAt = 50L))
        dao.upsert(fixture(id = "unfollowed", followedRemotely = false, lastUpdatedAt = 999L))

        val ids = dao.observeFollowsRemote().first().map { it.id }
        assertEquals(listOf("fresh", "stale"), ids)
    }

    @Test
    fun pollableForNewChapters_includesFollowsAndSubscribeEagerButNotTransient() = runTest {
        // Issue #907 — a pure source-side follow (not in library) MUST be
        // polled so its new chapters fire a notification.
        dao.upsert(fixture(id = "follow", followedRemotely = true, lastUpdatedAt = 10L))
        // In-library SUBSCRIBE / EAGER — the original #383 set.
        dao.upsert(
            fixture(id = "sub", inLibrary = true, lastUpdatedAt = 30L)
                .copy(downloadMode = DownloadMode.SUBSCRIBE),
        )
        dao.upsert(
            fixture(id = "eager", inLibrary = true, lastUpdatedAt = 20L)
                .copy(downloadMode = DownloadMode.EAGER),
        )
        // In-library but LAZY and not followed — must be excluded (we don't
        // poll the user's whole library, only subscribed/followed rows).
        dao.upsert(
            fixture(id = "lazy", inLibrary = true, lastUpdatedAt = 99L)
                .copy(downloadMode = DownloadMode.LAZY),
        )
        // Transient row (neither in library nor followed) — excluded.
        dao.upsert(fixture(id = "transient", lastUpdatedAt = 99L))

        val ids = dao.pollableForNewChapters().map { it.id }
        // Ordered by lastUpdatedAt DESC: sub(30) > eager(20) > follow(10).
        assertEquals(listOf("sub", "eager", "follow"), ids)
    }

    @Test
    fun pollableForNewChapters_followedAndInLibrary_isNotDuplicated() = runTest {
        // A row that is BOTH followed and a library subscription matches both
        // OR-clauses; SELECT * over one table can't duplicate it, but assert
        // the contract so a future refactor to a UNION/JOIN can't regress it.
        dao.upsert(
            fixture(id = "both", inLibrary = true, followedRemotely = true, lastUpdatedAt = 1L)
                .copy(downloadMode = DownloadMode.SUBSCRIBE),
        )
        assertEquals(listOf("both"), dao.pollableForNewChapters().map { it.id })
    }

    // ----- setX queries: each must hit the right column -------------------------

    @Test
    fun setInLibrary_true_setsAddedToLibraryAt() = runTest {
        dao.upsert(fixture(id = "f1", inLibrary = false, addedToLibraryAt = null))

        dao.setInLibrary("f1", inLibrary = true, now = 42L)

        val row = dao.get("f1")!!
        assertTrue(row.inLibrary)
        assertEquals(42L, row.addedToLibraryAt)
    }

    @Test
    fun setInLibrary_false_preservesExistingAddedToLibraryAt() = runTest {
        dao.upsert(fixture(id = "f1", inLibrary = true, addedToLibraryAt = 100L))

        dao.setInLibrary("f1", inLibrary = false, now = 999L)

        val row = dao.get("f1")!!
        assertFalse(row.inLibrary)
        // CASE WHEN guards the column; un-libraried row keeps its historical timestamp.
        assertEquals(100L, row.addedToLibraryAt)
    }

    @Test
    fun setFollowedRemote_togglesFollowedFlag() = runTest {
        dao.upsert(fixture(id = "f1", followedRemotely = false))
        dao.setFollowedRemote("f1", true)
        assertTrue(dao.get("f1")!!.followedRemotely)
        dao.setFollowedRemote("f1", false)
        assertFalse(dao.get("f1")!!.followedRemotely)
    }

    @Test
    fun setDownloadMode_writesAndClears() = runTest {
        dao.upsert(fixture(id = "f1"))

        dao.setDownloadMode("f1", DownloadMode.SUBSCRIBE)
        assertEquals(DownloadMode.SUBSCRIBE, dao.get("f1")!!.downloadMode)

        dao.setDownloadMode("f1", null)
        assertNull(dao.get("f1")!!.downloadMode)
    }

    @Test
    fun setPinnedVoice_writesBothColumns() = runTest {
        dao.upsert(fixture(id = "f1"))

        dao.setPinnedVoice("f1", "voice-en-US-Tony", "en-US")

        val row = dao.get("f1")!!
        assertEquals("voice-en-US-Tony", row.pinnedVoiceId)
        assertEquals("en-US", row.pinnedVoiceLocale)
    }

    @Test
    fun touchMetadata_writesTimestamp() = runTest {
        dao.upsert(fixture(id = "f1").copy(metadataFetchedAt = 0L))
        dao.touchMetadata("f1", 12345L)
        assertEquals(12345L, dao.get("f1")!!.metadataFetchedAt)
    }

    @Test
    fun setLastSeenRevision_roundTrip() = runTest {
        dao.upsert(fixture(id = "f1").copy(lastSeenRevision = null))
        assertNull(dao.getLastSeenRevision("f1"))

        dao.setLastSeenRevision("f1", "sha-abc")
        assertEquals("sha-abc", dao.getLastSeenRevision("f1"))

        dao.setLastSeenRevision("f1", null)
        assertNull(dao.getLastSeenRevision("f1"))
    }

    // ----- #981 placeholder metadata back-fill -----------------------------------

    @Test
    fun placeholdersToBackfill_selectsUnhydratedLibraryAndFollowsRows() = runTest {
        // metadataFetchedAt == 0 + in library → eligible.
        dao.upsert(fixture(id = "lib", inLibrary = true, addedToLibraryAt = 10L, metadataFetchedAt = 0L))
        // metadataFetchedAt == 0 + followed → eligible.
        dao.upsert(fixture(id = "follow", followedRemotely = true, addedToLibraryAt = 20L, metadataFetchedAt = 0L))
        // Already hydrated (metadataFetchedAt != 0) → excluded.
        dao.upsert(fixture(id = "hydrated", inLibrary = true, addedToLibraryAt = 5L, metadataFetchedAt = 999L))
        // Transient (neither flag) but un-hydrated → excluded (not worth a fetch).
        dao.upsert(fixture(id = "transient", metadataFetchedAt = 0L))

        val ids = dao.placeholdersToBackfill(cutoff = Long.MAX_VALUE).map { it.id }
        // Ordered by addedToLibraryAt ASC: lib(10) before follow(20).
        assertEquals(listOf("lib", "follow"), ids)
    }

    @Test
    fun placeholdersToBackfill_skipsRecentlyFailedButIncludesNeverAttempted() = runTest {
        // Never attempted → always eligible.
        dao.upsert(fixture(id = "fresh", inLibrary = true, addedToLibraryAt = 1L, metadataFetchedAt = 0L))
        // Failed at 1000, cutoff 500 → 1000 >= 500, still in cool-down → excluded.
        dao.upsert(
            fixture(id = "cooling", inLibrary = true, addedToLibraryAt = 2L, metadataFetchedAt = 0L, metadataBackfillFailedAt = 1_000L),
        )
        // Failed at 100, cutoff 500 → 100 < 500, cool-down elapsed → eligible.
        dao.upsert(
            fixture(id = "retry", inLibrary = true, addedToLibraryAt = 3L, metadataFetchedAt = 0L, metadataBackfillFailedAt = 100L),
        )

        val ids = dao.placeholdersToBackfill(cutoff = 500L).map { it.id }
        assertEquals(listOf("fresh", "retry"), ids)
    }

    @Test
    fun placeholderCount_ignoresCooldownAndCountsAllUnhydratedUserRows() = runTest {
        dao.upsert(fixture(id = "a", inLibrary = true, metadataFetchedAt = 0L))
        dao.upsert(fixture(id = "b", followedRemotely = true, metadataFetchedAt = 0L, metadataBackfillFailedAt = 9L))
        dao.upsert(fixture(id = "hydrated", inLibrary = true, metadataFetchedAt = 1L))
        dao.upsert(fixture(id = "transient", metadataFetchedAt = 0L))

        // a + b counted (both un-hydrated user rows; b's failure stamp is
        // ignored by the count). hydrated + transient excluded.
        assertEquals(2, dao.placeholderCount())
    }

    @Test
    fun markBackfillFailed_thenClearBackfillFailure_roundTrips() = runTest {
        dao.upsert(fixture(id = "f1", inLibrary = true, metadataFetchedAt = 0L))
        assertNull(dao.get("f1")!!.metadataBackfillFailedAt)

        dao.markBackfillFailed("f1", 777L)
        assertEquals(777L, dao.get("f1")!!.metadataBackfillFailedAt)
        // metadataFetchedAt stays 0 so the row remains in the placeholder set.
        assertEquals(0L, dao.get("f1")!!.metadataFetchedAt)

        dao.clearBackfillFailure("f1")
        assertNull(dao.get("f1")!!.metadataBackfillFailedAt)
    }

    // ----- @Transaction upsertAllPreservingUserState ----------------------------

    @Test
    fun upsertAllPreservingUserState_preservesUserOwnedColumns() = runTest {
        // Existing library row with user-owned state.
        dao.upsert(
            fixture(id = "f1", inLibrary = true, followedRemotely = true, addedToLibraryAt = 100L)
                .copy(
                    downloadMode = DownloadMode.EAGER,
                    pinnedVoiceId = "tony",
                    pinnedVoiceLocale = "en-US",
                    notesEverSeen = true,
                    firstSeenAt = 50L,
                    lastSeenRevision = "rev-1",
                ),
        )

        // Listing-page upsert: only knows source-side fields, no library/follow.
        val listingPage = fixture(id = "f1", inLibrary = false, followedRemotely = false)
            .copy(title = "Sky Pride (Revised)", firstSeenAt = 999L)
        dao.upsertAllPreservingUserState(listOf(listingPage))

        val row = dao.get("f1")!!
        // Source-side metadata advanced —
        assertEquals("Sky Pride (Revised)", row.title)
        // — user-owned state preserved.
        assertTrue("inLibrary must survive listing-page upsert", row.inLibrary)
        assertTrue("followedRemotely must survive listing-page upsert", row.followedRemotely)
        assertEquals(100L, row.addedToLibraryAt)
        assertEquals(DownloadMode.EAGER, row.downloadMode)
        assertEquals("tony", row.pinnedVoiceId)
        assertEquals("en-US", row.pinnedVoiceLocale)
        assertTrue(row.notesEverSeen)
        // firstSeenAt is preserved (not overwritten with the listing's 999).
        assertEquals(50L, row.firstSeenAt)
        // lastSeenRevision is preserved (only the poll worker writes it).
        assertEquals("rev-1", row.lastSeenRevision)
    }

    @Test
    fun upsertAllPreservingUserState_insertsNewRowsUnchanged() = runTest {
        val incoming = fixture(id = "new", inLibrary = false, addedToLibraryAt = null)
        dao.upsertAllPreservingUserState(listOf(incoming))

        val row = dao.get("new")
        assertNotNull(row)
        assertEquals(incoming, row)
    }

    // ----- deleteIfTransient -----------------------------------------------------

    @Test
    fun deleteIfTransient_deletesOnlyWhenBothFlagsAreFalse() = runTest {
        dao.upsert(fixture(id = "transient", inLibrary = false, followedRemotely = false))
        dao.upsert(fixture(id = "library", inLibrary = true, followedRemotely = false))
        dao.upsert(fixture(id = "followed", inLibrary = false, followedRemotely = true))

        assertEquals(1, dao.deleteIfTransient("transient"))
        assertEquals(0, dao.deleteIfTransient("library"))
        assertEquals(0, dao.deleteIfTransient("followed"))

        assertNull(dao.get("transient"))
        assertNotNull(dao.get("library"))
        assertNotNull(dao.get("followed"))
    }
}
