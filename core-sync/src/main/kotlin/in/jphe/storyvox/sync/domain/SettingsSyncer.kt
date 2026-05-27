package `in`.jphe.storyvox.sync.domain

import `in`.jphe.storyvox.data.repository.sync.SettingsSnapshotSource
import `in`.jphe.storyvox.sync.client.InstantBackend
import `in`.jphe.storyvox.sync.client.SignedInUser
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
 * Wire shape: a single JSON `Map<String, String>` blob, LWW on
 * `updatedAt`. Same shape and policy as `PronunciationDictSyncer` —
 * the trade-off is documented there: editing setting X on device A
 * and setting Y on device B simultaneously will lose one of the two
 * changes on next sync. The brief explicitly accepts that for
 * "settings are user intent, not collaborative data."
 *
 * Concurrency: relies on [LwwBlobSyncer]'s reconcile-then-write
 * shape — one fetch + one upsert per sync round, no per-key
 * fan-out. The [SyncCoordinator] serialises push/pull per domain
 * via a mutex, so there's no internal race.
 */
@Singleton
class SettingsSyncer @Inject constructor(
    private val source: SettingsSnapshotSource,
    private val backend: InstantBackend,
) : Syncer {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }

    private val delegate by lazy {
        LwwBlobSyncer(
            name = DOMAIN,
            localRead = ::readLocal,
            localWrite = ::writeLocal,
            remote = BackendBlobRemote(domain = DOMAIN, backend = backend),
        )
    }

    override val name: String get() = DOMAIN

    override suspend fun push(user: SignedInUser): SyncOutcome = delegate.push(user)
    override suspend fun pull(user: SignedInUser): SyncOutcome = delegate.pull(user)

    private suspend fun readLocal(): Stamped<String>? {
        val snap = source.snapshot()
        if (snap.isEmpty()) return null
        val stamp = source.lastLocalWriteAt().takeIf { it > 0L }
            ?: System.currentTimeMillis()
        val payload = json.encodeToString(
            MapSerializer(String.serializer(), String.serializer()),
            snap,
        )
        return Stamped(value = payload, updatedAt = stamp)
    }

    private suspend fun writeLocal(stamped: Stamped<String>) {
        val map = runCatching {
            json.decodeFromString(
                MapSerializer(String.serializer(), String.serializer()),
                stamped.value,
            )
        }.getOrNull() ?: return
        source.apply(map)
        source.stampLocalWrite(stamped.updatedAt)
    }

    companion object {
        /**
         * Stable domain identifier. Used as the row id prefix (`settings:<userId>`)
         * in the [InstantBackend] blob entity and as the [Syncer.name]
         * the [SyncCoordinator] dispatches against. NEVER rename this
         * — a rename would orphan existing rows.
         */
        const val DOMAIN: String = "settings"
    }
}
