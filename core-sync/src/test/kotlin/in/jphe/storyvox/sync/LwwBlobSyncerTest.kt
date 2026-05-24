package `in`.jphe.storyvox.sync

import `in`.jphe.storyvox.sync.SyncIds
import `in`.jphe.storyvox.sync.client.FakeInstantBackend
import `in`.jphe.storyvox.sync.client.SignedInUser
import `in`.jphe.storyvox.sync.coordinator.Stamped
import `in`.jphe.storyvox.sync.coordinator.SyncOutcome
import `in`.jphe.storyvox.sync.domain.BackendBlobRemote
import `in`.jphe.storyvox.sync.domain.LwwBlobSyncer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LwwBlobSyncerTest {

    private val USER = SignedInUser(userId = "u-1", email = null, refreshToken = "rt-1")

    /** Mutable cell holding the local "state" of a blob domain. The
     *  per-syncer `localRead`/`localWrite` closures route through this
     *  so the test asserts what landed on disk after a sync round. */
    private class LocalCell {
        var value: Stamped<String>? = null
    }

    @Test fun `local-only state gets pushed to remote on first sync`() = runTest {
        val backend = FakeInstantBackend()
        val local = LocalCell().apply { value = Stamped("hello", 1000L) }
        val syncer = LwwBlobSyncer(
            name = "pronunciation",
            localRead = { local.value },
            localWrite = { local.value = it },
            remote = BackendBlobRemote("pronunciation", backend),
        )
        val outcome = syncer.push(USER)
        assertTrue(outcome is SyncOutcome.Ok)

        // Read-back through a fresh syncer on a new "device".
        val newDevice = LocalCell()
        val syncer2 = LwwBlobSyncer(
            name = "pronunciation",
            localRead = { newDevice.value },
            localWrite = { newDevice.value = it },
            remote = BackendBlobRemote("pronunciation", backend),
        )
        syncer2.pull(USER)
        assertNotNull(newDevice.value)
        assertEquals("hello", newDevice.value!!.value)
        assertEquals(1000L, newDevice.value!!.updatedAt)
    }

    @Test fun `newer remote overrides local on pull`() = runTest {
        val backend = FakeInstantBackend()
        // Pre-seed remote.
        backend.upsert(USER, "blobs", SyncIds.rowUuid("pronunciation", USER.userId), payload = "fresh", updatedAt = 2000L)

        val local = LocalCell().apply { value = Stamped("stale", 1000L) }
        val syncer = LwwBlobSyncer(
            name = "pronunciation",
            localRead = { local.value },
            localWrite = { local.value = it },
            remote = BackendBlobRemote("pronunciation", backend),
        )
        syncer.pull(USER)
        assertEquals("fresh", local.value!!.value)
        assertEquals(2000L, local.value!!.updatedAt)
    }

    @Test fun `older remote does not override newer local`() = runTest {
        val backend = FakeInstantBackend()
        backend.upsert(USER, "blobs", SyncIds.rowUuid("pronunciation", USER.userId), payload = "stale", updatedAt = 1000L)
        val local = LocalCell().apply { value = Stamped("fresh", 2000L) }
        val syncer = LwwBlobSyncer(
            name = "pronunciation",
            localRead = { local.value },
            localWrite = { local.value = it },
            remote = BackendBlobRemote("pronunciation", backend),
        )
        syncer.push(USER)
        // Local should still be "fresh".
        assertEquals("fresh", local.value!!.value)
        // And remote should be updated to match local.
        val onRemote = backend.fetch(USER, "blobs", SyncIds.rowUuid("pronunciation", USER.userId)).getOrNull()!!
        assertEquals("fresh", onRemote.payload)
    }
}
