package `in`.jphe.storyvox.sync

import `in`.jphe.storyvox.data.repository.sync.SettingsSnapshotSource
import `in`.jphe.storyvox.sync.SyncIds
import `in`.jphe.storyvox.sync.client.FakeInstantBackend
import `in`.jphe.storyvox.sync.client.RowSnapshot
import `in`.jphe.storyvox.sync.client.SignedInUser
import `in`.jphe.storyvox.sync.coordinator.SyncOutcome
import `in`.jphe.storyvox.sync.domain.SettingsSyncer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        /** Per-key stamps (#978). Empty by default → every key falls
         *  back to [stamp], reproducing the pre-#978 single-stamp
         *  behavior. Tests that exercise field-level merge populate
         *  this to give two keys independent timestamps. */
        val keyStamps: MutableMap<String, Long> = mutableMapOf()

        override suspend fun snapshot(): Map<String, String> = store.toMap()

        override suspend fun apply(snapshot: Map<String, String>) {
            for ((k, v) in snapshot) store[k] = v
        }

        override suspend fun lastLocalWriteAt(): Long = stamp

        override suspend fun stampLocalWrite(at: Long) { stamp = at }

        override suspend fun fieldStamps(): Map<String, Long> = keyStamps.toMap()

        override suspend fun applyStamped(snapshot: Map<String, String>, stamps: Map<String, Long>) {
            for ((k, v) in snapshot) store[k] = v
            for ((k, t) in stamps) keyStamps[k] = t
        }

        /** Convenience for tests: set a key + its per-key stamp. */
        fun put(key: String, value: String, at: Long) {
            store[key] = value
            keyStamps[key] = at
            if (at > stamp) stamp = at
        }
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

    @Test fun `field-level merge — omitting a key on push does NOT delete it remotely (#978 union)`() = runTest {
        // BEHAVIOR CHANGE (#978): settings sync moved from whole-blob
        // LWW to per-key UNION merge. Settings has no delete semantic
        // — an omitted key means "no opinion," not "tombstone" (see
        // SettingsSnapshotSource.apply kdoc). So pushing a smaller
        // snapshot no longer wipes the omitted key from the remote;
        // the union preserves it. This is intentional and is the flip
        // side of the cross-device-clobber fix: device A omitting a
        // key it doesn't know about must not erase device B's value
        // for it.
        val backend = FakeInstantBackend()
        val source = FakeSource(
            initial = mapOf(
                "settings.pref_theme_override" to "DARK",
                "settings.pref_default_speed" to "1.5",
            ),
        ).also { it.stamp = 1_000L }
        SettingsSyncer(source, backend).push(USER)

        // A later snapshot omits the speed key.
        source.store.remove("settings.pref_default_speed")
        source.stamp = 2_000L
        SettingsSyncer(source, backend).push(USER)

        val row = backend.fetch(USER, "blobs", SyncIds.rowUuid("settings", USER.userId)).getOrNull()!!
        assertTrue("remote should retain the theme", row.payload.contains("\"DARK\""))
        assertTrue(
            "remote should STILL carry the omitted default-speed (union, no delete semantic)",
            row.payload.contains("\"settings.pref_default_speed\""),
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

    // ── #978 field-level merge ─────────────────────────────────────

    @Test fun `field-level merge — A edits X, B edits Y concurrently, BOTH survive`() = runTest {
        // THE bug #978 fixes. Device A edits the theme; device B edits
        // the default speed; neither knows about the other's edit.
        // Under whole-blob LWW one would clobber the other. Under
        // per-key merge both land.
        val backend = FakeInstantBackend()

        // Device A: had theme=LIGHT + speed=1.0 at t=1000, then edits
        // theme→DARK at t=3000 (only the theme key bumps).
        val deviceA = FakeSource()
        deviceA.put("settings.pref_theme_override", "LIGHT", 1_000L)
        deviceA.put("settings.pref_default_speed", "1.0", 1_000L)
        deviceA.put("settings.pref_theme_override", "DARK", 3_000L) // A's edit
        SettingsSyncer(deviceA, backend).push(USER) // A uploads first

        // Device B: same starting state, edits speed→2.0 at t=2000
        // (only the speed key bumps), still thinks theme=LIGHT.
        val deviceB = FakeSource()
        deviceB.put("settings.pref_theme_override", "LIGHT", 1_000L)
        deviceB.put("settings.pref_default_speed", "1.0", 1_000L)
        deviceB.put("settings.pref_default_speed", "2.0", 2_000L) // B's edit
        SettingsSyncer(deviceB, backend).push(USER) // B syncs against A's row

        // After B's merge: theme=DARK (A's t=3000 beats B's t=1000) and
        // speed=2.0 (B's t=2000 beats A's t=1000). BOTH edits survived.
        assertEquals("A's theme edit survives on B", "DARK", deviceB.store["settings.pref_theme_override"])
        assertEquals("B's speed edit survives on B", "2.0", deviceB.store["settings.pref_default_speed"])

        // And the remote reflects both, so a third device pulls both.
        val deviceC = FakeSource()
        SettingsSyncer(deviceC, backend).pull(USER)
        assertEquals("DARK", deviceC.store["settings.pref_theme_override"])
        assertEquals("2.0", deviceC.store["settings.pref_default_speed"])
    }

    @Test fun `field-level merge — newest-per-key wins independently`() = runTest {
        val backend = FakeInstantBackend()
        // Remote: theme edited late (t=5000), speed edited early (t=1000).
        val a = FakeSource()
        a.put("settings.pref_theme_override", "DARK", 5_000L)
        a.put("settings.pref_default_speed", "1.0", 1_000L)
        SettingsSyncer(a, backend).push(USER)

        // Local: theme edited early (t=2000 < 5000 → loses), speed
        // edited late (t=9000 > 1000 → wins).
        val b = FakeSource()
        b.put("settings.pref_theme_override", "LIGHT", 2_000L)
        b.put("settings.pref_default_speed", "3.0", 9_000L)
        SettingsSyncer(b, backend).pull(USER)

        assertEquals("remote's newer theme wins", "DARK", b.store["settings.pref_theme_override"])
        assertEquals("local's newer speed wins", "3.0", b.store["settings.pref_default_speed"])
    }

    @Test fun `field-level merge — equal per-key timestamp ties to local`() = runTest {
        // Mirrors the whole-blob "tie → local" rule, applied per key.
        val backend = FakeInstantBackend()
        val a = FakeSource()
        a.put("settings.pref_theme_override", "REMOTE_VAL", 777L)
        SettingsSyncer(a, backend).push(USER)

        val b = FakeSource()
        b.put("settings.pref_theme_override", "LOCAL_VAL", 777L) // exact tie
        SettingsSyncer(b, backend).pull(USER)

        assertEquals("tie keeps local", "LOCAL_VAL", b.store["settings.pref_theme_override"])
    }

    @Test fun `v1 to v2 — a flat (v1) remote row synthesizes per-key stamps from the row updatedAt`() = runTest {
        // A pre-#978 device wrote a flat blob with a single row
        // updatedAt and NO _field_stamps sidecar. A #978 device must
        // read it, synthesize every key's stamp = row.updatedAt, and
        // merge correctly (no data loss, no crash).
        val backend = FakeInstantBackend()
        val rowId = SyncIds.rowUuid("settings", USER.userId)
        // Hand-craft a v1 flat payload (exactly what old code wrote).
        backend.upsert(
            USER,
            entity = "blobs",
            id = rowId,
            payload = """{"settings.pref_theme_override":"DARK","settings.pref_default_speed":"1.5"}""",
            updatedAt = 8_000L,
        )

        // Local device has a NEWER edit to one key (t=9000) and an
        // OLDER value for another (no per-key stamp → falls back to
        // global stamp 500, which loses to the v1 row's 8000).
        val local = FakeSource()
        local.put("settings.pref_theme_override", "LIGHT", 9_000L) // newer than 8000 → wins
        local.store["settings.pref_default_speed"] = "2.0"          // no per-key stamp
        local.stamp = 500L                                          // fallback < 8000 → loses
        SettingsSyncer(local, backend).pull(USER)

        assertEquals("local's newer theme wins over v1 row", "LIGHT", local.store["settings.pref_theme_override"])
        assertEquals("v1 row's speed (8000) beats local fallback (500)", "1.5", local.store["settings.pref_default_speed"])
    }

    @Test fun `HARD GATE — a v1 client can decode a v2 payload without throwing and reads every real key`() = runTest {
        // This is the back-compat gate Sandman flagged: JP's other
        // devices run v1.0.1 (pre-#978) and will read v2 payloads this
        // build writes. A v1 client deserializes the payload with
        // MapSerializer(String, String). If a v2 payload had an
        // OBJECT-typed value (e.g. a nested {value,updatedAt}) that
        // decode would throw and sync would BREAK for the old device.
        // The stringified _field_stamps sidecar keeps every top-level
        // value a String, so the old decoder reads every real
        // preference and silently ignores _v / _field_stamps.
        val backend = FakeInstantBackend()
        val rowId = SyncIds.rowUuid("settings", USER.userId)

        // Produce a REAL v2 payload by pushing through the new syncer.
        val producer = FakeSource()
        producer.put("settings.pref_theme_override", "DARK", 1_000L)
        producer.put("settings.pref_default_speed", "1.25", 2_000L)
        producer.put("notion.pref_notion_database_id", "db-9999", 3_000L)
        SettingsSyncer(producer, backend).push(USER)
        val v2payload = backend.fetch(USER, "blobs", rowId).getOrNull()!!.payload

        // Sanity: it really is v2 (carries the sidecar markers).
        assertTrue("payload must be v2", v2payload.contains("\"_v\""))
        assertTrue("payload must carry _field_stamps", v2payload.contains("\"_field_stamps\""))

        // Decode it EXACTLY as a v1 client would: flat Map<String,String>.
        val v1Json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        val flat: Map<String, String> = v1Json.decodeFromString(
            MapSerializer(String.serializer(), String.serializer()),
            v2payload,
        ) // <-- must not throw

        // Every real preference is present and correct for the old reader.
        assertEquals("DARK", flat["settings.pref_theme_override"])
        assertEquals("1.25", flat["settings.pref_default_speed"])
        assertEquals("db-9999", flat["notion.pref_notion_database_id"])
        // The reserved sidecar keys are visible to the old reader as
        // opaque strings, never as objects (which would have thrown).
        // They don't collide with any real key (all real keys are
        // namespaced, none start with `_`).
        assertEquals("2", flat["_v"])
        assertTrue("_field_stamps is an opaque string to the v1 reader", flat["_field_stamps"]!!.contains("updatedAt").not())
        assertFalse("no real preference key starts with _", flat.keys.filter { it.startsWith("_") }.any { it != "_v" && it != "_field_stamps" })
    }

    @Test fun `round-trip — v2 producer to v2 consumer preserves per-key stamps`() = runTest {
        // A v2 consumer reading a v2 row must recover the SAME per-key
        // stamps the producer wrote, so a subsequent merge resolves
        // correctly. We verify indirectly: after C pulls, C re-pushes
        // and the row is byte-identical (stamps round-tripped).
        val backend = FakeInstantBackend()
        val rowId = SyncIds.rowUuid("settings", USER.userId)
        val producer = FakeSource()
        producer.put("settings.pref_theme_override", "DARK", 1_111L)
        producer.put("settings.pref_default_speed", "1.25", 2_222L)
        SettingsSyncer(producer, backend).push(USER)
        val first = backend.fetch(USER, "blobs", rowId).getOrNull()!!.payload

        val consumer = FakeSource()
        SettingsSyncer(consumer, backend).pull(USER)
        // Consumer recorded the producer's per-key stamps via applyStamped.
        assertEquals(1_111L, consumer.keyStamps["settings.pref_theme_override"])
        assertEquals(2_222L, consumer.keyStamps["settings.pref_default_speed"])

        // Re-push from the consumer — identical state → identical bytes.
        SettingsSyncer(consumer, backend).push(USER)
        val second = backend.fetch(USER, "blobs", rowId).getOrNull()!!.payload
        assertEquals("v2 round-trip must be byte-stable", first, second)
    }
}
