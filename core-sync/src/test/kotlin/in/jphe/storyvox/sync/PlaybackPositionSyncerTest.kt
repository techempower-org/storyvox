package `in`.jphe.storyvox.sync

import `in`.jphe.storyvox.data.db.dao.ChapterDao
import `in`.jphe.storyvox.data.db.dao.ChapterCacheUsageRow
import `in`.jphe.storyvox.data.db.dao.ChapterDownloadStateRow
import `in`.jphe.storyvox.data.db.dao.ChapterInfoRow
import `in`.jphe.storyvox.data.db.dao.ContinueListeningRow
import `in`.jphe.storyvox.data.db.dao.FictionDao
import `in`.jphe.storyvox.data.db.dao.LastPlayedRow
import `in`.jphe.storyvox.data.db.dao.PlaybackChapterRow
import `in`.jphe.storyvox.data.db.dao.PlaybackDao
import `in`.jphe.storyvox.data.db.dao.PlaybackPositionSnapshotRow
import `in`.jphe.storyvox.data.db.dao.RecentPlaybackRow
import `in`.jphe.storyvox.data.db.dao.UnreadChapterRow
import `in`.jphe.storyvox.data.db.entity.Chapter
import `in`.jphe.storyvox.data.db.entity.ChapterDownloadState
import `in`.jphe.storyvox.data.db.entity.Fiction
import `in`.jphe.storyvox.data.db.entity.DownloadMode
import `in`.jphe.storyvox.data.db.entity.PlaybackPosition
import `in`.jphe.storyvox.sync.client.FakeInstantBackend
import `in`.jphe.storyvox.sync.client.SignedInUser
import `in`.jphe.storyvox.sync.coordinator.SyncOutcome
import `in`.jphe.storyvox.sync.domain.PlaybackPositionSyncer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #965 — wire-format back-compat for the per-chapter position sync.
 *
 * The entry key moved from `fictionId` (one entry per fiction, the legacy
 * v1 blob) to the composite `(fictionId, chapterId)` (v2). The critical
 * invariant: a legacy v1 blob pushed by a device still on the old build
 * (phone / Android Auto / Wear not yet upgraded) must still be read
 * losslessly — every legacy entry already carries a valid `chapterId`, so
 * re-keying by the composite drops nothing. These tests pin that, plus the
 * v2 multi-chapter round-trip and the max-stamp merge per chapter.
 */
class PlaybackPositionSyncerTest {

    private val USER = SignedInUser(userId = "u-1", email = null, refreshToken = "rt-1")
    private val ENTITY = "positions"

    private fun syncer(
        backend: FakeInstantBackend,
        local: MutableList<PlaybackPosition>,
        knownFictions: Set<String> = setOf("f1", "f2"),
        knownChapters: Set<String> = setOf("f1:c0", "f1:c1", "f2:c0"),
    ) = PlaybackPositionSyncer(
        playbackDao = FakePlaybackDao(local),
        backend = backend,
        fictionDao = FakeFictionDao(knownFictions),
        chapterDao = FakeChapterDao(knownChapters),
    )

    @Test fun `legacy v1 blob (no version field, keyed by fiction) reads losslessly`() = runTest {
        val backend = FakeInstantBackend()
        // A v1 blob as written by an old build: no `version` field, one
        // entry per fiction. Each still has a valid chapterId.
        val legacy = """
            {"entries":[
              {"fictionId":"f1","chapterId":"f1:c0","charOffset":500,"paragraphIndex":2,"playbackSpeed":1.5,"durationEstimateMs":600000,"updatedAt":1000},
              {"fictionId":"f2","chapterId":"f2:c0","charOffset":10,"paragraphIndex":0,"playbackSpeed":1.0,"durationEstimateMs":120000,"updatedAt":2000}
            ]}
        """.trimIndent()
        backend.upsert(USER, ENTITY, SyncIds.rowUuid("positions", USER.userId), legacy, 2000L)

        val local = mutableListOf<PlaybackPosition>()
        val result = syncer(backend, local).pull(USER)

        assertTrue("pull succeeds: $result", result is SyncOutcome.Ok)
        // Both legacy entries landed in local — nothing dropped.
        val byChapter = local.associateBy { it.chapterId }
        assertEquals(2, byChapter.size)
        assertEquals(500, byChapter.getValue("f1:c0").charOffset)
        assertEquals(2, byChapter.getValue("f1:c0").paragraphIndex)
        assertEquals(1.5f, byChapter.getValue("f1:c0").playbackSpeed, 0.0001f)
        assertEquals(10, byChapter.getValue("f2:c0").charOffset)
    }

    @Test fun `v2 round-trips multiple chapters of one fiction`() = runTest {
        val backend = FakeInstantBackend()
        // Local has two chapters of f1 + one of f2 — only possible under v11.
        val local = mutableListOf(
            PlaybackPosition("f1", "f1:c0", charOffset = 100, updatedAt = 10L),
            PlaybackPosition("f1", "f1:c1", charOffset = 200, updatedAt = 20L),
            PlaybackPosition("f2", "f2:c0", charOffset = 300, updatedAt = 30L),
        )
        val pushResult = syncer(backend, local).push(USER)
        assertTrue("push succeeds: $pushResult", pushResult is SyncOutcome.Ok)

        // The pushed blob must carry all three per-chapter entries at v2.
        val pushed = backend.fetch(USER, ENTITY, SyncIds.rowUuid("positions", USER.userId))
            .getOrNull()
        assertNotNull(pushed)
        assertTrue("blob is v2", pushed!!.payload.contains("\"version\":2"))
        assertTrue(pushed.payload.contains("\"f1:c0\""))
        assertTrue(pushed.payload.contains("\"f1:c1\""))
        assertTrue(pushed.payload.contains("\"f2:c0\""))

        // A fresh device pulling that blob into an empty DB recovers all three.
        val fresh = mutableListOf<PlaybackPosition>()
        val pull = syncer(backend, fresh).pull(USER)
        assertTrue(pull is SyncOutcome.Ok)
        assertEquals(3, fresh.size)
        assertEquals(
            setOf("f1:c0", "f1:c1", "f2:c0"),
            fresh.map { it.chapterId }.toSet(),
        )
    }

    @Test fun `max-stamp merge is per chapter, not per fiction`() = runTest {
        val backend = FakeInstantBackend()
        // Remote already holds f1:c0 @ updatedAt=100 with offset 999.
        val remote = """
            {"version":2,"entries":[
              {"fictionId":"f1","chapterId":"f1:c0","charOffset":999,"paragraphIndex":0,"playbackSpeed":1.0,"durationEstimateMs":0,"updatedAt":100}
            ]}
        """.trimIndent()
        backend.upsert(USER, ENTITY, SyncIds.rowUuid("positions", USER.userId), remote, 100L)

        // Local has a NEWER f1:c0 (should win) and a separate f1:c1 the
        // remote never saw (should be added, not clobbered by the per-fiction
        // collapse the old code did).
        val local = mutableListOf(
            PlaybackPosition("f1", "f1:c0", charOffset = 555, updatedAt = 200L),
            PlaybackPosition("f1", "f1:c1", charOffset = 42, updatedAt = 150L),
        )
        val result = syncer(backend, local).pull(USER)
        assertTrue(result is SyncOutcome.Ok)

        val byChapter = local.associateBy { it.chapterId }
        // c0: local newer (200 > 100) → keeps local offset 555.
        assertEquals(555, byChapter.getValue("f1:c0").charOffset)
        // c1: remote never had it → survives.
        assertEquals(42, byChapter.getValue("f1:c1").charOffset)
    }

    // ─── minimal fakes ───────────────────────────────────────────────────

    /** In-memory PlaybackDao — only the sync-touched methods are live. */
    private class FakePlaybackDao(
        private val rows: MutableList<PlaybackPosition>,
    ) : PlaybackDao {
        override suspend fun allPositionsSnapshot(): List<PlaybackPositionSnapshotRow> =
            rows.map {
                PlaybackPositionSnapshotRow(
                    fictionId = it.fictionId,
                    chapterId = it.chapterId,
                    charOffset = it.charOffset,
                    paragraphIndex = it.paragraphIndex,
                    playbackSpeed = it.playbackSpeed,
                    durationEstimateMs = it.durationEstimateMs,
                    updatedAt = it.updatedAt,
                )
            }

        override suspend fun upsert(position: PlaybackPosition) {
            rows.removeAll {
                it.fictionId == position.fictionId && it.chapterId == position.chapterId
            }
            rows.add(position)
        }

        override fun observe(fictionId: String): Flow<PlaybackPosition?> = flowOf(null)
        override suspend fun get(fictionId: String): PlaybackPosition? = error("not used")
        override suspend fun get(fictionId: String, chapterId: String): PlaybackPosition? =
            error("not used")
        override suspend fun delete(fictionId: String) = error("not used")
        override suspend fun recent(limit: Int): List<RecentPlaybackRow> = error("not used")
        override fun observeLastPlayedTimes(): Flow<List<LastPlayedRow>> = flowOf(emptyList())
        override fun observeMostRecentContinueListening(): Flow<ContinueListeningRow?> = flowOf(null)
    }

    /** FictionDao stub — only `get` (existence check) is used by the syncer. */
    private class FakeFictionDao(private val known: Set<String>) : FictionDao {
        override suspend fun get(id: String): Fiction? =
            if (id in known) STUB.copy(id = id) else null

        override fun observe(id: String): Flow<Fiction?> = flowOf(null)
        override suspend fun getMany(ids: List<String>): List<Fiction> = error("not used")
        override fun observeLibrary(): Flow<List<Fiction>> = flowOf(emptyList())
        override suspend fun librarySnapshot(): List<Fiction> = error("not used")
        override fun observeFollowsRemote(): Flow<List<Fiction>> = flowOf(emptyList())
        override suspend fun followsSnapshot(): List<Fiction> = error("not used")
        override suspend fun pollableForNewChapters(): List<Fiction> = error("not used")
        override suspend fun insertIfNew(fiction: Fiction): Long = error("not used")
        override suspend fun upsert(fiction: Fiction) = error("not used")
        override suspend fun upsertMany(fictions: List<Fiction>) = error("not used")
        override suspend fun update(fiction: Fiction) = error("not used")
        override suspend fun setInLibrary(id: String, inLibrary: Boolean, now: Long) = error("not used")
        override suspend fun setFollowedRemote(id: String, followed: Boolean) = error("not used")
        override suspend fun setDownloadMode(id: String, mode: DownloadMode?) = error("not used")
        override suspend fun setPinnedVoice(id: String, voiceId: String?, locale: String?) = error("not used")
        override suspend fun touchMetadata(id: String, now: Long) = error("not used")
        override suspend fun setSourceId(id: String, sourceId: String) = error("not used")
        override suspend fun getSourceUrl(id: String): String? = error("not used")
        override suspend fun setSourceUrlIfAbsent(id: String, url: String) = error("not used")
        override suspend fun placeholdersToBackfill(cutoff: Long): List<Fiction> = error("not used")
        override suspend fun placeholderCount(): Int = error("not used")
        override suspend fun markBackfillFailed(id: String, now: Long) = error("not used")
        override suspend fun clearBackfillFailure(id: String) = error("not used")
        override suspend fun getLastSeenRevision(id: String): String? = error("not used")
        override suspend fun setLastSeenRevision(id: String, revision: String?) = error("not used")
        override suspend fun deleteIfTransient(id: String): Int = error("not used")

        private companion object {
            val STUB = Fiction(
                id = "stub",
                sourceId = "royalroad",
                title = "Stub",
                author = "nobody",
                coverUrl = null,
                description = null,
                tags = emptyList(),
                firstSeenAt = 0L,
                metadataFetchedAt = 0L,
                inLibrary = true,
            )
        }
    }

    /** ChapterDao stub — only `exists` is used by the syncer. */
    private class FakeChapterDao(private val known: Set<String>) : ChapterDao {
        override suspend fun exists(id: String): Boolean = id in known

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
        // #982 — not exercised by PlaybackPositionSyncer; stub to
        // satisfy the ChapterDao contract (added after this fake).
        override suspend fun markFollowedCaughtUp(now: Long): Int = error("not used")
        override suspend fun allBookmarks() = emptyList<`in`.jphe.storyvox.data.db.dao.BookmarkRow>()
        override suspend fun setBookmark(id: String, charOffset: Int?) = error("not used")
        override suspend fun getBookmark(id: String): Int? = null
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
        override suspend fun cacheUsage(): ChapterCacheUsageRow = error("not used")
        override suspend fun chapterIdsForFiction(fictionId: String): List<String> = emptyList()
        override suspend fun deleteByIds(ids: List<String>) = error("not used")
    }
}
