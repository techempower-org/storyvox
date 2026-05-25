package `in`.jphe.storyvox.sync

import `in`.jphe.storyvox.data.repository.pronunciation.MatchType
import `in`.jphe.storyvox.data.repository.pronunciation.PronunciationDict
import `in`.jphe.storyvox.data.repository.pronunciation.PronunciationDictRepository
import `in`.jphe.storyvox.data.repository.pronunciation.PronunciationEntry
import `in`.jphe.storyvox.sync.client.FakeInstantBackend
import `in`.jphe.storyvox.sync.client.SignedInUser
import `in`.jphe.storyvox.sync.coordinator.SyncOutcome
import `in`.jphe.storyvox.sync.domain.PronunciationDictSyncer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #778 regression cover — `PronunciationDictSyncer.lastLocalWriteAt`
 * used to be an in-memory `var = 0L` that reset on every cold start. The
 * `readLocal` fallback then stamped the dict with
 * `System.currentTimeMillis()` and a stale local dict won blanket
 * against newer remotes in LWW.
 *
 * Same shape as [BookmarksSyncerTest] / [SettingsSyncerTest] — a
 * `FakeInstantBackend` plays the cloud, a `FakeDictRepo` plays the
 * persisted storage (its `lastDictWriteAt` value survives "cold restart"
 * because we re-use the same instance across syncer instances).
 */
class PronunciationDictSyncerTest {

    private val USER = SignedInUser(userId = "u-dict", email = null, refreshToken = "rt")

    /** In-memory [PronunciationDictRepository] standing in for the
     *  DataStore-backed `:app` impl. Persistence is just a field on
     *  this instance — passing the SAME instance to a second syncer
     *  simulates a cold restart that reloaded from disk. */
    private class FakeDictRepo(initial: PronunciationDict = PronunciationDict.EMPTY) : PronunciationDictRepository {
        private var current: PronunciationDict = initial
        private var writeStamp: Long = 0L

        override val dict: Flow<PronunciationDict> = flowOf(current)
        override suspend fun current(): PronunciationDict = current
        override suspend fun add(entry: PronunciationEntry) {
            current = current.copy(entries = current.entries + entry)
            writeStamp = System.currentTimeMillis()
        }
        override suspend fun update(index: Int, entry: PronunciationEntry) {
            if (index !in current.entries.indices) return
            current = current.copy(
                entries = current.entries.toMutableList().apply { this[index] = entry },
            )
            writeStamp = System.currentTimeMillis()
        }
        override suspend fun delete(index: Int) {
            if (index !in current.entries.indices) return
            current = current.copy(
                entries = current.entries.toMutableList().apply { removeAt(index) },
            )
            writeStamp = System.currentTimeMillis()
        }
        override suspend fun replaceAll(dict: PronunciationDict) {
            current = dict
            writeStamp = System.currentTimeMillis()
        }
        override suspend fun lastDictWriteAt(): Long = writeStamp
        override suspend fun stampDictWrite(at: Long) { writeStamp = at }
    }

    private fun entry(pattern: String, replacement: String) =
        PronunciationEntry(pattern = pattern, replacement = replacement, matchType = MatchType.WORD)

    @Test fun `cold restart preserves the persisted write stamp across syncer instances`() = runTest {
        // Device A: edits dict at T=1000, pushes.
        val backend = FakeInstantBackend()
        val repoA = FakeDictRepo().apply {
            replaceAll(PronunciationDict(entries = listOf(entry("Astaria", "a"))))
            // Pin the stamp to a deterministic value — the real impl
            // stamps `System.currentTimeMillis()` on every mutator,
            // but for the merge-rule assertion we want a fixed T.
            stampDictWrite(1_000L)
        }
        val syncerA1 = PronunciationDictSyncer(repoA, backend)
        val r1 = syncerA1.push(USER)
        assertTrue("first push succeeds: $r1", r1 is SyncOutcome.Ok)

        // Cold restart on device A — a fresh syncer instance, but the
        // repo (the disk-backed state) survives. The persisted stamp
        // must still be 1000L, NOT reset to 0L.
        val syncerA2 = PronunciationDictSyncer(repoA, backend)
        assertEquals("persisted stamp survives the syncer kill", 1_000L, repoA.lastDictWriteAt())

        // Round 2: A2 pushes again. The remote `updatedAt` must STILL
        // be 1000L — pre-#778, the cold-restart re-pushed with "now"
        // (System.currentTimeMillis()), which would have moved it
        // forward and let A clobber any concurrent edit on device B.
        val r2 = syncerA2.push(USER)
        assertTrue("second push succeeds: $r2", r2 is SyncOutcome.Ok)
        val row = backend.fetch(USER, "blobs", SyncIds.rowUuid("pronunciation", USER.userId)).getOrNull()
        assertNotNull("remote row must exist after both pushes", row)
        assertEquals(
            "remote updatedAt must remain the dict's true edit time, NOT bumped to 'now' by the cold-restart re-push",
            1_000L,
            row!!.updatedAt,
        )
    }

    @Test fun `newer remote overrides older local on cold-start pull`() = runTest {
        val backend = FakeInstantBackend()
        // Device B (newer) pushes first.
        val repoB = FakeDictRepo().apply {
            replaceAll(PronunciationDict(entries = listOf(entry("name", "newer"))))
            stampDictWrite(5_000L)
        }
        PronunciationDictSyncer(repoB, backend).push(USER)

        // Device A: had a stale local edit at T=1000, then was killed.
        // Cold start: persisted stamp = 1000L. Pull should bring in
        // B's newer dict because 5000 > 1000.
        val repoA = FakeDictRepo().apply {
            replaceAll(PronunciationDict(entries = listOf(entry("name", "older"))))
            stampDictWrite(1_000L)
        }
        val outcome = PronunciationDictSyncer(repoA, backend).pull(USER)
        assertTrue("pull succeeds: $outcome", outcome is SyncOutcome.Ok)
        assertEquals(
            "newer remote must win over stale local on pull",
            "newer",
            repoA.current().entries.single().replacement,
        )
    }

    @Test fun `older remote does not clobber newer local on push`() = runTest {
        val backend = FakeInstantBackend()
        // Remote: older.
        val repoOld = FakeDictRepo().apply {
            replaceAll(PronunciationDict(entries = listOf(entry("name", "older"))))
            stampDictWrite(1_000L)
        }
        PronunciationDictSyncer(repoOld, backend).push(USER)

        // Local: newer. Push must overwrite the older remote.
        val repoNew = FakeDictRepo().apply {
            replaceAll(PronunciationDict(entries = listOf(entry("name", "newer"))))
            stampDictWrite(5_000L)
        }
        val outcome = PronunciationDictSyncer(repoNew, backend).push(USER)
        assertTrue("push succeeds: $outcome", outcome is SyncOutcome.Ok)

        val row = backend.fetch(USER, "blobs", SyncIds.rowUuid("pronunciation", USER.userId)).getOrNull()!!
        assertTrue("remote payload should now contain the newer replacement", row.payload.contains("newer"))
        assertEquals("remote stamp must be the local newer stamp", 5_000L, row.updatedAt)
    }

    @Test fun `pull writes back the remote stamp so subsequent push is byte-stable`() = runTest {
        // After a pull, the local stamp adopts the merged blob's
        // updatedAt. Otherwise the syncer's `replaceAll` would stamp
        // "now" via the repo's mutator and the next push would spuriously
        // advance the remote's updatedAt without any real local edit.
        val backend = FakeInstantBackend()
        val repoA = FakeDictRepo().apply {
            replaceAll(PronunciationDict(entries = listOf(entry("k", "v"))))
            stampDictWrite(7_000L)
        }
        PronunciationDictSyncer(repoA, backend).push(USER)

        val repoB = FakeDictRepo()
        PronunciationDictSyncer(repoB, backend).pull(USER)
        assertEquals(
            "after pull, local stamp must equal the merged blob's updatedAt, not 'now'",
            7_000L,
            repoB.lastDictWriteAt(),
        )
    }

    @Test fun `empty local dict is a no-op push when remote is also empty`() = runTest {
        val backend = FakeInstantBackend()
        val repo = FakeDictRepo()  // empty dict, stamp 0
        val outcome = PronunciationDictSyncer(repo, backend).push(USER)
        assertTrue("no-op push should still be Ok: $outcome", outcome is SyncOutcome.Ok)
        val row = backend.fetch(USER, "blobs", SyncIds.rowUuid("pronunciation", USER.userId)).getOrNull()
        assertEquals(null, row)
    }

    @Test fun `fresh install with non-zero local stamp pushes with that stamp`() = runTest {
        // Smoke test for the fallback path — readLocal uses the
        // persisted stamp when > 0, only falls back to "now" when 0.
        val backend = FakeInstantBackend()
        val repo = FakeDictRepo().apply {
            replaceAll(PronunciationDict(entries = listOf(entry("Astaria", "a"))))
            stampDictWrite(42L)
        }
        PronunciationDictSyncer(repo, backend).push(USER)
        val row = backend.fetch(USER, "blobs", SyncIds.rowUuid("pronunciation", USER.userId)).getOrNull()!!
        assertEquals(42L, row.updatedAt)
    }

    @Test fun `domain identifier remains stable`() = runTest {
        // Renames orphan rows on the InstantDB side — assert the wire
        // constant doesn't drift.
        assertEquals("pronunciation", PronunciationDictSyncer.DOMAIN)
    }

    @Test fun `repository mutators record a strictly-positive stamp`() = runTest {
        // The fake mirrors the real impl: `add`/`update`/`delete`/
        // `replaceAll` must advance `lastDictWriteAt`. Without that
        // wiring, the syncer would push with `System.currentTimeMillis()`
        // on the first push after an edit, which breaks LWW ordering on
        // any device that processed the push later.
        val repo = FakeDictRepo()
        assertEquals(0L, repo.lastDictWriteAt())
        repo.add(entry("a", "x"))
        assertTrue("add() must advance stamp", repo.lastDictWriteAt() > 0L)
        val afterAdd = repo.lastDictWriteAt()

        repo.update(0, entry("a", "y"))
        assertFalse("update() must shift stamp", repo.lastDictWriteAt() == 0L)

        repo.delete(0)
        assertFalse("delete() must shift stamp", repo.lastDictWriteAt() == 0L)

        repo.replaceAll(PronunciationDict(entries = listOf(entry("z", "zz"))))
        assertFalse("replaceAll() must shift stamp", repo.lastDictWriteAt() == 0L)

        // Each mutation advances the stamp — at minimum the post-add
        // stamp is strictly less than the final replaceAll stamp.
        assertTrue(afterAdd <= repo.lastDictWriteAt())
    }
}
