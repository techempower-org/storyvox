package `in`.jphe.storyvox.sync.domain

import `in`.jphe.storyvox.sync.SyncIds
import `in`.jphe.storyvox.sync.client.InstantBackend
import `in`.jphe.storyvox.sync.client.SignedInUser
import `in`.jphe.storyvox.sync.coordinator.Stamped
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Set-shaped remote adaptor — serializes the (members, tombstones) tuple
 * as a single JSON blob and round-trips it through [InstantBackend].
 *
 * Storage layout: entity name `sets`, id = `<domain>:<userId>` so a
 * single InstantDB app can host every set domain without rowcount
 * blow-up. The blob is the authoritative remote copy of the merged
 * members + tombstones (post-conflict-resolution).
 */
class BackendSetRemote(
    private val domain: String,
    private val backend: InstantBackend,
    private val json: Json = Defaults.json,
) : SetSyncer.SetRemote {

    /**
     * Wire payload for set-shaped domains.
     *
     * Issue #360 finding 3 (argus): tombstones evolved from
     * `List<String>` to `Map<String, Long>` so [ConflictPolicies.unionWithTombstoneStamps]
     * can apply a freshness window. We keep the legacy `tombstones`
     * field as `List<String>` defaulted to empty for read-side
     * backward compatibility with rows written by the pre-fix code
     * (only relevant for the very brief window where any internal
     * device had pushed v1 payloads — the PR isn't merged yet, so
     * this is belt-and-braces, not load-bearing). The authoritative
     * field is `tombstoneStamps`; legacy tombstones get a stamp of
     * 0L on read (== "infinitely old, expired immediately").
     */
    @Serializable
    private data class Payload(
        val members: List<String> = emptyList(),
        val tombstones: List<String> = emptyList(),
        val tombstoneStamps: Map<String, Long> = emptyMap(),
        val updatedAt: Long = 0L,
    )

    override suspend fun fetch(user: SignedInUser): Result<SetSyncer.RemoteSet> {
        val res = backend.fetch(user, entity = ENTITY, id = rowId(user)).getOrElse {
            return Result.failure(it)
        } ?: return Result.success(SetSyncer.RemoteSet(emptySet(), emptyMap()))
        return runCatching {
            val payload = json.decodeFromString(Payload.serializer(), res.payload)
            // Prefer the new timestamped map; fall back to the legacy
            // list with stamp 0L so already-expired tombstones don't
            // block re-adds.
            val tombs = when {
                payload.tombstoneStamps.isNotEmpty() -> payload.tombstoneStamps
                payload.tombstones.isNotEmpty() -> payload.tombstones.associateWith { 0L }
                else -> emptyMap()
            }
            SetSyncer.RemoteSet(
                members = payload.members.toSet(),
                tombstones = tombs,
            )
        }
    }

    override suspend fun push(user: SignedInUser, members: Set<String>, tombstones: Map<String, Long>): Result<Unit> {
        val now = System.currentTimeMillis()
        val payload = Payload(
            members = members.toList().sorted(),
            // Keep the legacy list field in sync for any old reader
            // that hasn't been updated yet.
            tombstones = tombstones.keys.sorted(),
            tombstoneStamps = tombstones,
            updatedAt = now,
        )
        val body = json.encodeToString(Payload.serializer(), payload)
        return backend.upsert(
            user = user,
            entity = ENTITY,
            id = rowId(user),
            payload = body,
            updatedAt = payload.updatedAt,
        )
    }

    private fun rowId(user: SignedInUser) = SyncIds.rowUuid(domain, user.userId)

    private companion object { const val ENTITY = "sets" }
}

/**
 * Blob-shaped remote adaptor — single JSON string carried verbatim,
 * plus an updatedAt. Used by LwwBlobSyncer for the pronunciation
 * dict, encrypted secrets envelope, and any other "one big blob"
 * domain.
 */
class BackendBlobRemote(
    private val domain: String,
    private val backend: InstantBackend,
) : LwwBlobSyncer.BlobRemote {

    override suspend fun fetch(user: SignedInUser): Result<Stamped<String>?> =
        backend.fetch(user, entity = ENTITY, id = rowId(user))
            .map { snap -> snap?.let { Stamped(it.payload, it.updatedAt) } }

    override suspend fun push(user: SignedInUser, payload: Stamped<String>): Result<Unit> =
        backend.upsert(
            user = user,
            entity = ENTITY,
            id = rowId(user),
            payload = payload.value,
            updatedAt = payload.updatedAt,
        )

    private fun rowId(user: SignedInUser) = SyncIds.rowUuid(domain, user.userId)

    private companion object { const val ENTITY = "blobs" }
}

internal object Defaults {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}
