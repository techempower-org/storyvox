package `in`.jphe.storyvox.sync

import `in`.jphe.storyvox.sync.SyncIds
import `in`.jphe.storyvox.sync.client.FakeInstantBackend
import `in`.jphe.storyvox.sync.client.SignedInUser
import `in`.jphe.storyvox.sync.coordinator.SyncOutcome
import `in`.jphe.storyvox.sync.coordinator.TombstonesAccess
import `in`.jphe.storyvox.sync.domain.BackendSetRemote
import `in`.jphe.storyvox.sync.domain.SetSyncer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #989 — the per-member rebuild data (id → source URL) that rides
 * alongside the library set so a hash-id fiction (Readability/RSS/EPUB
 * direct-download) can be reconstructed on a device that never saw the
 * original paste.
 *
 * These tests pin three things:
 *  1. round-trip: a URL pushed by device A reaches device B's
 *     `persistMemberData` callback (the seam `LibrarySyncer` uses to
 *     stamp `Fiction.sourceUrl`);
 *  2. local-wins merge: a device that captured the paste first-hand
 *     doesn't get its URL shadowed by a (possibly stale) remote value;
 *  3. wire back-compat both directions — a pre-#989 payload (no
 *     `memberData` key) decodes to no URLs, and the field only carries
 *     the handful of members that actually have a URL.
 */
class SetSyncerMemberDataTest {

    private val USER = SignedInUser(userId = "u-1", email = null, refreshToken = "rt-1")

    /** Same in-memory tombstone substitute as [SetSyncerTest]. */
    private class InMemoryTombstones : TombstonesAccess {
        private val byDomain = mutableMapOf<String, MutableMap<String, Long>>()
        override fun observe(domain: String): Flow<Set<String>> =
            flowOf(byDomain[domain]?.keys?.toSet() ?: emptySet())
        override suspend fun snapshot(domain: String): Set<String> =
            byDomain[domain]?.keys?.toSet() ?: emptySet()
        override suspend fun snapshotWithStamps(domain: String): Map<String, Long> =
            byDomain[domain]?.toMap() ?: emptyMap()
        override suspend fun add(domain: String, id: String) {
            byDomain.getOrPut(domain) { mutableMapOf() }[id] = System.currentTimeMillis()
        }
        override suspend fun addAll(domain: String, ids: Collection<String>) {
            val now = System.currentTimeMillis()
            val bucket = byDomain.getOrPut(domain) { mutableMapOf() }
            for (id in ids) bucket[id] = now
        }
        override suspend fun forget(domain: String, id: String) { byDomain[domain]?.remove(id) }
        override suspend fun clear(domain: String) { byDomain.remove(domain) }
    }

    private val readabilityId = "readability:1a2b3c4d5e6f7a8b"
    private val readabilityUrl = "https://example.com/some-long-article"

    @Test fun `member URL pushed by device A is delivered to device B on pull`() = runTest {
        val backend = FakeInstantBackend()

        // Device A: has a Readability fiction in its library and knows
        // the original URL (it captured the paste).
        val deviceA = mutableSetOf(readabilityId, "gutenberg:84")
        val deviceAUrls = mapOf(readabilityId to readabilityUrl)
        SetSyncer(
            name = "library",
            tombstones = InMemoryTombstones(),
            localSnapshot = { deviceA.toSet() },
            localAdd = { deviceA.add(it) },
            localRemove = { deviceA.remove(it) },
            remote = BackendSetRemote("library", backend),
            localMemberData = { ids -> deviceAUrls.filterKeys { it in ids } },
        ).push(USER)

        // Device B: empty library. It should pull both members AND
        // receive the Readability URL through persistMemberData.
        val deviceB = mutableSetOf<String>()
        val deviceBUrls = mutableMapOf<String, String>()
        val outcome = SetSyncer(
            name = "library",
            tombstones = InMemoryTombstones(),
            localSnapshot = { deviceB.toSet() },
            localAdd = { deviceB.add(it) },
            localRemove = { deviceB.remove(it) },
            remote = BackendSetRemote("library", backend),
            persistMemberData = { id, url -> deviceBUrls[id] = url },
        ).pull(USER)

        assertTrue(outcome is SyncOutcome.Ok)
        assertEquals(setOf(readabilityId, "gutenberg:84"), deviceB)
        // Only the hash-id member carries a URL; the self-describing
        // gutenberg id does not.
        assertEquals(mapOf(readabilityId to readabilityUrl), deviceBUrls)
    }

    @Test fun `persistMemberData fires only for newly-added members`() = runTest {
        val backend = FakeInstantBackend()

        // Seed remote with the member + URL.
        val deviceA = mutableSetOf(readabilityId)
        SetSyncer(
            "library", InMemoryTombstones(),
            { deviceA.toSet() }, { deviceA.add(it) }, { deviceA.remove(it) },
            BackendSetRemote("library", backend),
            localMemberData = { mapOf(readabilityId to readabilityUrl) },
        ).push(USER)

        // Device B ALREADY has the member locally (e.g. it captured the
        // paste itself). A pull must NOT re-persist the URL — the member
        // isn't in the toAddLocally diff.
        val deviceB = mutableSetOf(readabilityId)
        val persisted = mutableListOf<Pair<String, String>>()
        SetSyncer(
            "library", InMemoryTombstones(),
            { deviceB.toSet() }, { deviceB.add(it) }, { deviceB.remove(it) },
            BackendSetRemote("library", backend),
            persistMemberData = { id, url -> persisted.add(id to url) },
        ).pull(USER)

        assertTrue("no localAdd → no persistMemberData", persisted.isEmpty())
    }

    @Test fun `local URL wins over remote URL on conflict`() = runTest {
        val backend = FakeInstantBackend()

        // Remote already holds a STALE url for the member.
        SetSyncer(
            "library", InMemoryTombstones(),
            { setOf(readabilityId) }, { }, { },
            BackendSetRemote("library", backend),
            localMemberData = { mapOf(readabilityId to "https://example.com/STALE") },
        ).push(USER)

        // Device C has the member locally with the CORRECT url and also
        // has it in its set already (so no add), but pushes its own data.
        // localData wins the merge → the correct URL is written back.
        SetSyncer(
            "library", InMemoryTombstones(),
            { setOf(readabilityId) }, { }, { },
            BackendSetRemote("library", backend),
            localMemberData = { mapOf(readabilityId to readabilityUrl) },
        ).push(USER)

        // Read what's now on the wire — the local (correct) URL must have
        // won the merge and been written back.
        val finalDeviceUrls = mutableMapOf<String, String>()
        val freshDevice = mutableSetOf<String>()
        SetSyncer(
            "library", InMemoryTombstones(),
            { freshDevice.toSet() }, { freshDevice.add(it) }, { freshDevice.remove(it) },
            BackendSetRemote("library", backend),
            persistMemberData = { id, url -> finalDeviceUrls[id] = url },
        ).pull(USER)

        assertEquals(readabilityUrl, finalDeviceUrls[readabilityId])
    }

    @Test fun `reading a pre-989 payload without memberData yields no URLs`() = runTest {
        val backend = FakeInstantBackend()
        // Hand-seed a legacy payload: members + tombstoneStamps, NO
        // memberData key. ignoreUnknownKeys is irrelevant here — the
        // point is the field is ABSENT and must default to empty.
        val rowId = SyncIds.rowUuid("library", USER.userId)
        val legacyPayload = """
            {"members":["$readabilityId","gutenberg:84"],
             "tombstones":[],
             "tombstoneStamps":{},
             "updatedAt":1700000000000}
        """.trimIndent()
        backend.upsert(USER, entity = "sets", id = rowId, payload = legacyPayload, updatedAt = 1700000000000L)

        val device = mutableSetOf<String>()
        val urls = mutableMapOf<String, String>()
        val outcome = SetSyncer(
            "library", InMemoryTombstones(),
            { device.toSet() }, { device.add(it) }, { device.remove(it) },
            BackendSetRemote("library", backend),
            persistMemberData = { id, url -> urls[id] = url },
        ).pull(USER)

        assertTrue(outcome is SyncOutcome.Ok)
        // Members still pull through (back-compat), but no URLs exist.
        assertEquals(setOf(readabilityId, "gutenberg:84"), device)
        assertTrue("legacy payload has no member URLs", urls.isEmpty())
        assertNull(urls[readabilityId])
    }
}
