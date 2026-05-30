package `in`.jphe.storyvox.sync.domain

import `in`.jphe.storyvox.data.repository.sync.SettingsSnapshotSource
import `in`.jphe.storyvox.sync.SyncIds
import `in`.jphe.storyvox.sync.client.InstantBackend
import `in`.jphe.storyvox.sync.client.RowSnapshot
import `in`.jphe.storyvox.sync.client.SignedInUser
import `in`.jphe.storyvox.sync.coordinator.ConflictPolicies
import `in`.jphe.storyvox.sync.coordinator.Stamped
import `in`.jphe.storyvox.sync.coordinator.SyncOutcome
import `in`.jphe.storyvox.sync.coordinator.Syncer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Syncs the user's non-sensitive preferences — theme override, default
 * speed/pitch, voice tuning, source-plugin enabled map, per-backend
 * non-secret config (Wikipedia language, Notion database id, Discord
 * coalesce-minutes, Outline host, Memory Palace host), AI feature
 * toggles, sleep-timer defaults, inbox per-source mute toggles, etc.
 *
 * **Tier 1 only** — never touches secret tokens. Those go through
 * [SecretsSyncer], which encrypts client-side. See
 * [SettingsSnapshotSource]'s kdoc for the full allowlist and the
 * design justification for splitting Tier 1 from Tier 2.
 *
 * ## Field-level merge (#978)
 *
 * Settings used to ride [LwwBlobSyncer]: one JSON `Map<String,String>`
 * blob with a single `updatedAt`, resolved last-write-wins **at the
 * blob level**. That dropped concurrent cross-device edits — device A
 * reorders its sources, device B flips a theme, B's blob wins on
 * timestamp, A's reorder is gone. This syncer now reconciles
 * **per-key**: each setting carries its own `updatedAt` and the merge
 * is a union with newest-per-key-wins (see
 * [ConflictPolicies.mergeStampedMap]). Two devices editing two
 * different keys both survive.
 *
 * ### Wire format (v2) and back-compat
 *
 * The remote row is still `blobs` / id `settings:<userId>`, still a
 * single `payload` string + a row `updatedAt`. Only the payload's
 * shape changed, and it stays readable by a pre-#978 (v1) client:
 *
 *  - **Top level is the flat `{key: value}` map** — exactly the v1
 *    shape. An old client deserializing `Map<String,String>` reads
 *    every real preference unchanged.
 *  - Plus two reserved string entries an old client ignores as
 *    unknown keys: `"_v": "2"` (format version) and
 *    `"_field_stamps": "<json {key: updatedAt}>"` — the per-key
 *    stamps, **stringified** so every top-level value is a String and
 *    the old `Map<String,String>` decoder can't choke on an object
 *    value. (Same JSON-in-a-string trick the pronunciation dict uses.)
 *
 * Reading directions, neither loses data:
 *  - **v2 reads a v1 row** (no `_field_stamps`): synthesize every
 *    key's stamp from the row's `updatedAt` — that long *was* the only
 *    timestamp those keys ever had — then merge per-key. Reproduces v1
 *    semantics exactly.
 *  - **old (v1) client reads a v2 row**: reads the top-level flat
 *    values, ignores `_v` / `_field_stamps`. It keeps doing blob-LWW
 *    until it upgrades. The fleet degrades to v1 behavior, never
 *    corrupts.
 *
 * No migration job: the first push from a #978 device rewrites that
 * user's row to v2 in place (lazy, per-user).
 *
 * Concurrency: the [SyncCoordinator] serialises push/pull per domain
 * via a mutex, so the fetch→merge→upsert round here has no internal
 * race.
 */
@Singleton
class SettingsSyncer @Inject constructor(
    private val source: SettingsSnapshotSource,
    private val backend: InstantBackend,
) : Syncer {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }
    private val mapSerializer = MapSerializer(String.serializer(), String.serializer())
    private val stampSerializer = MapSerializer(String.serializer(), Long.serializer())

    override val name: String get() = DOMAIN

    override suspend fun push(user: SignedInUser): SyncOutcome = reconcile(user)
    override suspend fun pull(user: SignedInUser): SyncOutcome = reconcile(user)

    /**
     * Fetch the remote row, merge per-key against the local snapshot,
     * write the merged result back to whichever side is stale. Push
     * and pull are the same operation — both end with the two sides
     * field-level-reconciled. (Same shape as [LwwBlobSyncer.reconcile],
     * but the conflict step is [ConflictPolicies.mergeStampedMap]
     * instead of whole-blob LWW.)
     */
    private suspend fun reconcile(user: SignedInUser): SyncOutcome {
        val tag = "SettingsSyncer"
        val local = runCatching { readLocalStamped() }
            .getOrElse { return SyncOutcome.Transient("local read: ${it.message}") }
        val remoteRow = backend.fetch(user, ENTITY, rowId(user)).getOrElse {
            return SyncOutcome.Transient("remote fetch: ${it.message}")
        }
        val remote = remoteRow?.let { decodeStamped(it) }
        android.util.Log.d(
            tag,
            "local=${local?.size ?: "null"}keys remote=${remote?.size ?: "null"}keys",
        )

        when {
            local.isNullOrEmpty() && remote.isNullOrEmpty() -> {
                android.util.Log.d(tag, "both empty, nothing to sync")
                return SyncOutcome.Ok(0)
            }
            local.isNullOrEmpty() -> {
                // Fresh device — adopt the remote wholesale.
                android.util.Log.i(tag, "local empty, applying remote (${remote!!.size} keys)")
                runCatching { writeLocalStamped(remote) }
                    .onFailure { return SyncOutcome.Transient("localWrite: ${it.message}") }
                return SyncOutcome.Ok(remote.size)
            }
            remote.isNullOrEmpty() -> {
                // Remote empty — upload local as source of truth.
                android.util.Log.i(tag, "remote empty, pushing local (${local.size} keys)")
                val push = backend.upsert(user, ENTITY, rowId(user), encodeStamped(local), rowStamp(local))
                if (push.isFailure) return SyncOutcome.Transient("remote push: ${push.exceptionOrNull()?.message}")
                return SyncOutcome.Ok(local.size)
            }
        }

        val merged = ConflictPolicies.mergeStampedMap(local, remote)
        android.util.Log.d(tag, "merged=${merged.size}keys")

        if (merged != local) {
            android.util.Log.d(tag, "writing merged to local")
            runCatching { writeLocalStamped(merged) }
                .onFailure { return SyncOutcome.Transient("localWrite: ${it.message}") }
        }
        if (merged != remote) {
            android.util.Log.d(tag, "pushing merged to remote")
            val push = backend.upsert(user, ENTITY, rowId(user), encodeStamped(merged), rowStamp(merged))
            if (push.isFailure) return SyncOutcome.Transient("remote push: ${push.exceptionOrNull()?.message}")
        }
        return SyncOutcome.Ok(merged.size)
    }

    // ── Local IO ──────────────────────────────────────────────────

    /**
     * Read the local snapshot as a per-key stamped map. Each value's
     * stamp comes from [SettingsSnapshotSource.fieldStamps]; a key the
     * impl has no per-key stamp for (e.g. first run after upgrade,
     * before any per-key write) falls back to the global
     * [SettingsSnapshotSource.lastLocalWriteAt] — reproducing the
     * pre-#978 blob-level behavior for keys that haven't been touched
     * since the upgrade. Returns null when there are no synced keys.
     */
    private suspend fun readLocalStamped(): Map<String, Stamped<String>>? {
        val snap = source.snapshot()
        if (snap.isEmpty()) return null
        val perKey = runCatching { source.fieldStamps() }.getOrDefault(emptyMap())
        val fallback = source.lastLocalWriteAt().takeIf { it > 0L } ?: System.currentTimeMillis()
        return snap.mapValues { (key, value) ->
            Stamped(value, perKey[key] ?: fallback)
        }
    }

    private suspend fun writeLocalStamped(merged: Map<String, Stamped<String>>) {
        val values = merged.mapValues { it.value.value }
        val stamps = merged.mapValues { it.value.updatedAt }
        source.applyStamped(values, stamps)
        // Keep the legacy global stamp advanced to the newest field —
        // it's still the v1 row updatedAt and the fallback for any key
        // the impl can't stamp per-key.
        rowStampOrNull(merged)?.let { source.stampLocalWrite(it) }
    }

    // ── Wire encode / decode (v2 payload, v1-compatible) ──────────

    /**
     * Encode the merged map as the v2 payload: flat `{key: value}` at
     * the top level (v1-readable) plus `_v` and a stringified
     * `_field_stamps` sidecar. Deterministic key order (sorted) so a
     * byte-identical input yields a byte-identical payload — no no-op
     * push thrash.
     */
    private fun encodeStamped(merged: Map<String, Stamped<String>>): String {
        val flat = sortedMapOf<String, String>()
        val stamps = sortedMapOf<String, Long>()
        for ((key, sv) in merged) {
            flat[key] = sv.value
            stamps[key] = sv.updatedAt
        }
        val out = LinkedHashMap<String, String>(flat.size + 2)
        out.putAll(flat)
        out[KEY_VERSION] = WIRE_VERSION.toString()
        out[KEY_FIELD_STAMPS] = json.encodeToString(stampSerializer, stamps)
        return json.encodeToString(mapSerializer, out)
    }

    /**
     * Decode a remote row into a per-key stamped map. Handles both
     * shapes:
     *  - **v2**: strip the reserved `_v` / `_field_stamps` entries, use
     *    the parsed sidecar for per-key stamps (a key missing from the
     *    sidecar falls back to the row's `updatedAt`).
     *  - **v1** (no `_field_stamps`): every key inherits the row's
     *    `updatedAt` — the only timestamp a v1 row ever had.
     *
     * Returns null on an unparseable payload (treated as "no remote"
     * by reconcile — we never let a corrupt row clobber local).
     */
    private fun decodeStamped(row: RowSnapshot): Map<String, Stamped<String>>? {
        val raw = runCatching { json.decodeFromString(mapSerializer, row.payload) }.getOrNull()
            ?: return null
        val stampsJson = raw[KEY_FIELD_STAMPS]
        val perKey: Map<String, Long> = if (stampsJson != null) {
            runCatching { json.decodeFromString(stampSerializer, stampsJson) }.getOrDefault(emptyMap())
        } else {
            emptyMap()
        }
        val out = LinkedHashMap<String, Stamped<String>>(raw.size)
        for ((key, value) in raw) {
            if (key == KEY_VERSION || key == KEY_FIELD_STAMPS) continue
            out[key] = Stamped(value, perKey[key] ?: row.updatedAt)
        }
        return out
    }

    /** The row-level `updatedAt` to stamp an upsert with — the newest
     *  per-key stamp (so the v1 fallback an old reader sees is the most
     *  recent edit). Defaults to `now` for an empty map (never happens
     *  on the push paths, which guard non-empty first). */
    private fun rowStamp(merged: Map<String, Stamped<String>>): Long =
        rowStampOrNull(merged) ?: System.currentTimeMillis()

    private fun rowStampOrNull(merged: Map<String, Stamped<String>>): Long? =
        merged.values.maxOfOrNull { it.updatedAt }

    private fun rowId(user: SignedInUser) = SyncIds.rowUuid(DOMAIN, user.userId)

    companion object {
        /**
         * Stable domain identifier. Used as the row id prefix (`settings:<userId>`)
         * in the [InstantBackend] blob entity and as the [Syncer.name]
         * the [SyncCoordinator] dispatches against. NEVER rename this
         * — a rename would orphan existing rows.
         */
        const val DOMAIN: String = "settings"

        /** Remote entity. Shared with the other blob-shaped domains. */
        private const val ENTITY: String = "blobs"

        /** v2 = field-level (#978). v1 rows have no version marker and
         *  decode as flat blob-LWW. */
        private const val WIRE_VERSION: Int = 2

        /** Reserved payload keys. Underscore-prefixed so they can't
         *  collide with a real synced key (every synced key is
         *  namespaced `settings.` / `notion.` / `discord.` / etc., none
         *  start with `_`). An old v1 reader sees these as unknown
         *  String entries and ignores them. */
        private const val KEY_VERSION: String = "_v"
        private const val KEY_FIELD_STAMPS: String = "_field_stamps"
    }
}
