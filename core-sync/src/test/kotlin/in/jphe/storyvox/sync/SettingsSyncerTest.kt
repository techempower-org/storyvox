package `in`.jphe.storyvox.sync

import `in`.jphe.storyvox.data.repository.sync.SettingsSnapshotSource
import `in`.jphe.storyvox.sync.SyncIds
import `in`.jphe.storyvox.sync.client.FakeInstantBackend
import `in`.jphe.storyvox.sync.client.RowSnapshot
import `in`.jphe.storyvox.sync.client.SignedInUser
import `in`.jphe.storyvox.sync.coordinator.SyncOutcome
import `in`.jphe.storyvox.sync.domain.SettingsSyncer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per JP's brief — at-least-20 tests across the new syncers. This file
 * covers the **Tier 1** `SettingsSyncer`: local-write→remote-push,
 * remote-update→local-write, conflict resolution (LWW), migration on
 * a fresh device, tombstone (clear-on-local→propagate). Tier 2
 * coverage extension lives in [SecretsSyncerExtensionTest].
 *
 * `FakeInstantBackend` plays the cloud — device A pushes, device B
 * pulls, the syncer's reconcile-merge logic gets exercised
 * end-to-end without a real WebSocket.
 */
class SettingsSyncerTest {

    private val USER = SignedInUser(userId = "u-settings", email = "a@b.test", refreshToken = "rt")

    /** In-memory [SettingsSnapshotSource] — the tests treat this as a
     *  flat string→string map plus a write timestamp. The real impl
     *  in `:app` is built atop DataStore Preferences, but the
     *  contract from the syncer's POV is exactly this. */
    private class FakeSource(initial: Map<String, String> = emptyMap()) : SettingsSnapshotSource {
        val store: MutableMap<String, String> = initial.toMutableMap()
        var stamp: Long = 0L

        override suspend fun snapshot(): Map<String, String> = store.toMap()

        override suspend fun apply(snapshot: Map<String, String>) {
            for ((k, v) in snapshot) store[k] = v
        }

        override suspend fun lastLocalWriteAt(): Long = stamp

        override suspend fun stampLocalWrite(at: Long) { stamp = at }
    }

    @Test fun `local write gets pushed to remote on first sync`() = runTest {
        val backend = FakeInstantBackend()
        val source = FakeSource(
            initial = mapOf(
                "settings.pref_theme_override" to "DARK",
                "settings.pref_default_speed" to "1.25",
            ),
        ).also { it.stamp = 5_000L }
        val syncer = SettingsSyncer(source, backend)

        val outcome = syncer.push(USER)
        assertTrue("push must succeed: $outcome", outcome is SyncOutcome.Ok)

        val row = backend.fetch(USER, "blobs", SyncIds.rowUuid("settings", USER.userId)).getOrNull()
        assertNotNull("remote row must exist after push", row)
        // Payload contains both keys, JSON-encoded.
        assertTrue(row!!.payload.contains("\"settings.pref_theme_override\""))
        assertTrue(row.payload.contains("\"DARK\""))
        assertTrue(row.payload.contains("\"settings.pref_default_speed\""))
        assertTrue(row.payload.contains("\"1.25\""))
        assertEquals(5_000L, row.updatedAt)
    }

    @Test fun `remote state pulls down to a fresh device`() = runTest {
        val backend = FakeInstantBackend()
        // Device A pushes.
        val sourceA = FakeSource(
            initial = mapOf("settings.pref_theme_override" to "LIGHT"),
        ).also { it.stamp = 10_000L }
        SettingsSyncer(sourceA, backend).push(USER)

        // Device B — fresh install, no local settings yet.
        val sourceB = FakeSource()
        val syncerB = SettingsSyncer(sourceB, backend)
        val outcome = syncerB.pull(USER)
        assertTrue("pull must succeed: $outcome", outcome is SyncOutcome.Ok)
        assertEquals("LIGHT", sourceB.store["settings.pref_theme_override"])
        assertEquals(10_000L, sourceB.stamp)
    }

    @Test fun `newer remote overrides older local on pull`() = runTest {
        val backend = FakeInstantBackend()
        // Pre-seed remote with newer state.
        SettingsSyncer(
            FakeSource(mapOf("settings.pref_theme_override" to "LIGHT")).also { it.stamp = 20_000L },
            backend,
        ).push(USER)

        // Device with older local state.
        val sourceB = FakeSource(
            initial = mapOf("settings.pref_theme_override" to "DARK"),
        ).also { it.stamp = 10_000L }
        SettingsSyncer(sourceB, backend).pull(USER)

        assertEquals(
            "newer remote (LIGHT) should overwrite older local (DARK)",
            "LIGHT",
            sourceB.store["settings.pref_theme_override"],
        )
    }

    @Test fun `newer local does not get clobbered by older remote on push`() = runTest {
        val backend = FakeInstantBackend()
        // Older remote.
        SettingsSyncer(
            FakeSource(mapOf("settings.pref_theme_override" to "LIGHT")).also { it.stamp = 1_000L },
            backend,
        ).push(USER)

        // Newer local on device B.
        val sourceB = FakeSource(
            initial = mapOf("settings.pref_theme_override" to "DARK"),
        ).also { it.stamp = 5_000L }
        SettingsSyncer(sourceB, backend).push(USER)

        // Local must still be DARK.
        assertEquals("DARK", sourceB.store["settings.pref_theme_override"])
        // And the remote must now reflect the newer local.
        val row = backend.fetch(USER, "blobs", SyncIds.rowUuid("settings", USER.userId)).getOrNull()!!
        assertTrue("remote should now hold DARK", row.payload.contains("\"DARK\""))
        assertEquals(5_000L, row.updatedAt)
    }

    @Test fun `empty local snapshot is a no-op push when remote is also empty`() = runTest {
        val backend = FakeInstantBackend()
        val source = FakeSource()
        val syncer = SettingsSyncer(source, backend)
        val outcome = syncer.push(USER)
        assertTrue("no-op push should be Ok: $outcome", outcome is SyncOutcome.Ok)
        // Nothing on the remote.
        assertNull(backend.fetch(USER, "blobs", SyncIds.rowUuid("settings", USER.userId)).getOrNull())
    }

    @Test fun `tombstone propagates — local clears a key, remote sees the clear`() = runTest {
        // Settings sync uses LWW on the whole blob; "clearing a key
        // locally" is encoded as "the next local snapshot omits the
        // key." Pushing the smaller snapshot replaces the remote blob
        // entirely (no per-key tombstones — that's the v1 model
        // documented in design-decisions.md).
        val backend = FakeInstantBackend()
        val source = FakeSource(
            initial = mapOf(
                "settings.pref_theme_override" to "DARK",
                "settings.pref_default_speed" to "1.5",
            ),
        ).also { it.stamp = 1_000L }
        SettingsSyncer(source, backend).push(USER)

        // User clears the speed override.
        source.store.remove("settings.pref_default_speed")
        source.stamp = 2_000L
        SettingsSyncer(source, backend).push(USER)

        val row = backend.fetch(USER, "blobs", SyncIds.rowUuid("settings", USER.userId)).getOrNull()!!
        assertTrue("remote should retain the theme", row.payload.contains("\"DARK\""))
        assertTrue(
            "remote should NOT carry the cleared default-speed",
            !row.payload.contains("\"settings.pref_default_speed\""),
        )
    }

    @Test fun `migration — remote-only state on fresh device-B install lands locally`() = runTest {
        val backend = FakeInstantBackend()
        // Device A populates a rich preference set.
        val rich = mapOf(
            "settings.pref_theme_override" to "DARK",
            "settings.pref_default_speed" to "1.10",
            "settings.pref_voice_steady_v1" to "false",
            "settings.pref_source_notion_enabled" to "true",
            "notion.pref_notion_database_id" to "abcd-1234-database",
        )
        val sourceA = FakeSource(rich).also { it.stamp = 100_000L }
        SettingsSyncer(sourceA, backend).push(USER)

        // Fresh device B install — pulls the lot.
        val sourceB = FakeSource()
        SettingsSyncer(sourceB, backend).pull(USER)

        for ((k, v) in rich) {
            assertEquals("migration mismatch on $k", v, sourceB.store[k])
        }
        assertEquals(100_000L, sourceB.stamp)
    }

    @Test fun `first-write on a new device writes local-as-source-of-truth to remote`() = runTest {
        // Documented "migration" case from JP's brief: when device B
        // is freshly signed in, remote is empty, B's local has state
        // → push local to remote.
        val backend = FakeInstantBackend()
        val sourceB = FakeSource(
            initial = mapOf("settings.pref_theme_override" to "DARK"),
        ).also { it.stamp = 500L }
        SettingsSyncer(sourceB, backend).push(USER)

        val row = backend.fetch(USER, "blobs", SyncIds.rowUuid("settings", USER.userId)).getOrNull()
        assertNotNull(row)
        assertTrue(row!!.payload.contains("\"DARK\""))
    }

    @Test fun `conflict — A writes locally, B writes remotely simultaneously, LWW picks latest`() = runTest {
        val backend = FakeInstantBackend()
        // B's earlier write is on the remote.
        SettingsSyncer(
            FakeSource(mapOf("settings.pref_theme_override" to "LIGHT")).also { it.stamp = 100L },
            backend,
        ).push(USER)

        // A writes locally at a LATER timestamp. push() merges remote
        // + local and the higher updatedAt wins.
        val sourceA = FakeSource(
            initial = mapOf("settings.pref_theme_override" to "DARK"),
        ).also { it.stamp = 200L }
        SettingsSyncer(sourceA, backend).push(USER)
        assertEquals("A's later write wins", "DARK", sourceA.store["settings.pref_theme_override"])

        // Now C pulls — gets DARK (A's later write).
        val sourceC = FakeSource()
        SettingsSyncer(sourceC, backend).pull(USER)
        assertEquals("DARK", sourceC.store["settings.pref_theme_override"])
    }

    @Test fun `conflict — equal timestamps tie-break to local without rewrite`() = runTest {
        // ConflictPolicies.lastWriteWins: "Ties go to local."
        val backend = FakeInstantBackend()
        SettingsSyncer(
            FakeSource(mapOf("settings.pref_theme_override" to "REMOTE_VAL")).also { it.stamp = 999L },
            backend,
        ).push(USER)

        val sourceB = FakeSource(
            initial = mapOf("settings.pref_theme_override" to "LOCAL_VAL"),
        ).also { it.stamp = 999L }
        SettingsSyncer(sourceB, backend).push(USER)

        // Local must still hold LOCAL_VAL (tie went to local).
        assertEquals("LOCAL_VAL", sourceB.store["settings.pref_theme_override"])
        // Remote is now updated to local (same updatedAt — LwwBlobSyncer pushes back).
        val row = backend.fetch(USER, "blobs", SyncIds.rowUuid("settings", USER.userId)).getOrNull()!!
        assertTrue(row.payload.contains("\"LOCAL_VAL\""))
    }

    @Test fun `apply tolerates unknown keys from a future build`() = runTest {
        val backend = FakeInstantBackend()
        // Hand-craft a remote payload with a known + an unknown key.
        backend.upsert(
            USER,
            entity = "blobs",
            id = SyncIds.rowUuid("settings", USER.userId),
            payload = """{"settings.pref_theme_override":"DARK","settings.pref_future_v999_unknown":"hello"}""",
            updatedAt = 5_000L,
        )

        val sourceB = FakeSource()
        val outcome = SettingsSyncer(sourceB, backend).pull(USER)
        assertTrue("pull with unknown key should still succeed: $outcome", outcome is SyncOutcome.Ok)
        // Known key lands; unknown key is also stored by the
        // snapshot-source (the source just stores whatever the
        // syncer hands it — the per-key allowlist enforcement is
        // the impl's job in `:app`, tested separately).
        assertEquals("DARK", sourceB.store["settings.pref_theme_override"])
    }

    @Test fun `wire payload is byte-stable for byte-identical input`() = runTest {
        // Stable payload bytes are load-bearing — otherwise we'd
        // thrash the sync server with no-op pushes on every sync
        // round even when nothing changed locally.
        val backend = FakeInstantBackend()
        val source = FakeSource(
            initial = mapOf(
                "settings.pref_theme_override" to "DARK",
                "settings.pref_default_speed" to "1.10",
                "settings.pref_warmup_wait_v1" to "false",
            ),
        ).also { it.stamp = 1_000L }
        SettingsSyncer(source, backend).push(USER)
        val first = backend.fetch(USER, "blobs", SyncIds.rowUuid("settings", USER.userId)).getOrNull()!!.payload

        // Re-push with the same source — payload should be byte-identical.
        SettingsSyncer(source, backend).push(USER)
        val second = backend.fetch(USER, "blobs", SyncIds.rowUuid("settings", USER.userId)).getOrNull()!!.payload

        assertEquals("repeated push of identical source must produce byte-identical payload", first, second)
    }

    @Test fun `domain name and row id are stable identifiers`() = runTest {
        // Renames would orphan existing InstantDB rows — assert the
        // wire constants don't drift.
        assertEquals("settings", SettingsSyncer.DOMAIN)
        val backend = FakeInstantBackend()
        SettingsSyncer(
            FakeSource(mapOf("settings.pref_theme_override" to "LIGHT")).also { it.stamp = 1L },
            backend,
        ).push(USER)
        // The row id is a UUID v3 derived from `domain:userId` per
        // SyncIds.rowUuid — direct-fetch by that id should return the row.
        assertNotNull(backend.fetch(USER, "blobs", SyncIds.rowUuid("settings", USER.userId)).getOrNull())
    }

    @Test fun `per-backend keys round-trip alongside main settings`() = runTest {
        // The blob carries both `settings.*` keys (main DataStore) and
        // `notion.*`, `discord.*`, etc. (per-backend DataStores). The
        // syncer should treat them all as opaque map entries —
        // namespacing is the impl's responsibility.
        val backend = FakeInstantBackend()
        val payload = mapOf(
            "settings.pref_theme_override" to "DARK",
            "notion.pref_notion_database_id" to "db-id-1234",
            "discord.pref_discord_server_id" to "guild-9876",
            "outline.pref_outline_host" to "wiki.example.com",
            "wikipedia.pref_wikipedia_language_code" to "es",
            "palace.pref_palace_host" to "palace.example.com",
        )
        val sourceA = FakeSource(payload).also { it.stamp = 7_000L }
        SettingsSyncer(sourceA, backend).push(USER)

        val sourceB = FakeSource()
        SettingsSyncer(sourceB, backend).pull(USER)
        for ((k, v) in payload) assertEquals("$k must round-trip", v, sourceB.store[k])
    }

    @Test fun `subsequent push uses lastLocalWriteAt as the updatedAt stamp`() = runTest {
        // SettingsSyncer reads source.lastLocalWriteAt() and uses
        // that as the LWW blob's updatedAt. If the impl forgets to
        // surface that stamp, every push gets stamped "now" and LWW
        // becomes meaningless across devices.
        val backend = FakeInstantBackend()
        val source = FakeSource(mapOf("settings.pref_theme_override" to "DARK"))
        source.stamp = 42L
        SettingsSyncer(source, backend).push(USER)
        val row = backend.fetch(USER, "blobs", SyncIds.rowUuid("settings", USER.userId)).getOrNull()!!
        assertEquals(42L, row.updatedAt)
    }
}
