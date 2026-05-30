package `in`.jphe.storyvox.sync.domain

import `in`.jphe.storyvox.sync.client.SignedInUser
import `in`.jphe.storyvox.sync.coordinator.ConflictPolicies
import `in`.jphe.storyvox.sync.coordinator.SyncOutcome
import `in`.jphe.storyvox.sync.coordinator.Syncer
import `in`.jphe.storyvox.sync.coordinator.TombstonesAccess

/**
 * Reusable syncer for set-shaped domains — Library, Follows, Starred
 * voices, anything where the state is "this user has marked these IDs."
 *
 * Strategy:
 *  1. Snapshot the local set and the live tombstone set.
 *  2. Pull the remote set (via [remote]).
 *  3. Merge with [ConflictPolicies.unionWithTombstones].
 *  4. Compute the diff vs local — adds, removes.
 *  5. Apply locally (via [localAdd] / [localRemove]) and push the union
 *     plus tombstones back to remote (via [remote]).
 *  6. Forget tombstones that are now reflected in remote.
 *
 * One implementation, three callers — Library, Follows, plus future.
 */
class SetSyncer(
    override val name: String,
    private val tombstones: TombstonesAccess,
    private val localSnapshot: suspend () -> Set<String>,
    private val localAdd: suspend (String) -> Unit,
    private val localRemove: suspend (String) -> Unit,
    private val remote: SetRemote,
    /**
     * Issue #989 — per-member rebuild data (id → source URL) that some
     * sources need to reconstruct a fiction on a device that never saw
     * the original paste (Readability/RSS/EPUB-direct, whose id is an
     * opaque hash of the URL). Defaults make this a no-op for set
     * domains that have no per-member aux data (Follows, voices):
     *  - [localMemberData] returns the URLs this device knows for the
     *    given member ids (only the hash-id sources contribute);
     *  - [persistMemberData] is called for each merged member with a
     *    known URL so the pull side can store it durably before the
     *    back-fill worker tries to hydrate the placeholder.
     */
    private val localMemberData: suspend (Set<String>) -> Map<String, String> = { emptyMap() },
    private val persistMemberData: suspend (String, String) -> Unit = { _, _ -> },
) : Syncer {

    /** Abstraction over InstantDB calls so tests don't need a live
     *  network. Implementations transact updates to a single "set"
     *  entity per (user, domain).
     *
     *  Issue #360 finding 3: tombstones are now `Map<String, Long>`
     *  with the long being the epoch-ms stamp the tombstone was
     *  recorded — load-bearing for [ConflictPolicies.unionWithTombstoneStamps]
     *  to apply a freshness window so a re-add of a previously-
     *  tombstoned id can win.
     *
     *  Issue #989: [memberData] (id → source URL) rides alongside the
     *  member set so hash-id fictions can be rebuilt cross-device. The
     *  wire field is additive and defaulted empty, so old clients
     *  ignore it and a new client reading an old payload sees no URLs. */
    interface SetRemote {
        suspend fun fetch(user: SignedInUser): Result<RemoteSet>
        suspend fun push(
            user: SignedInUser,
            members: Set<String>,
            tombstones: Map<String, Long>,
            memberData: Map<String, String>,
        ): Result<Unit>
    }

    data class RemoteSet(
        val members: Set<String>,
        val tombstones: Map<String, Long>,
        val memberData: Map<String, String> = emptyMap(),
    )

    override suspend fun push(user: SignedInUser): SyncOutcome = pushImpl(user)

    override suspend fun pull(user: SignedInUser): SyncOutcome = pushImpl(user)

    /**
     * Push and pull are the same operation for set sync: read both
     * sides, merge, write the result. Calling it "push" or "pull"
     * is purely semantic — they bot end with both sides identical.
     */
    private suspend fun pushImpl(user: SignedInUser): SyncOutcome {
        val tag = "SetSyncer[$name]"
        val now = System.currentTimeMillis()
        val local = runCatching { localSnapshot() }
            .getOrElse { return SyncOutcome.Transient("local snapshot: ${it.message}") }
        val localTombs = runCatching { tombstones.snapshotWithStamps(name) }
            .getOrElse { return SyncOutcome.Transient("tombstones: ${it.message}") }
        android.util.Log.d(tag, "local: ${local.size} members, ${localTombs.size} tombstones")

        val remoteResult = remote.fetch(user)
        val remoteSet = remoteResult.getOrElse {
            return SyncOutcome.Transient("remote fetch: ${it.message}")
        }
        android.util.Log.d(tag, "remote: ${remoteSet.members.size} members, ${remoteSet.tombstones.size} tombstones")

        // Issue #360 finding 3: combine tombstone maps, taking the
        // newer stamp on conflict so a freshly-recorded tombstone on
        // one device isn't overwritten by a stale stamp from the
        // other. The conflict-policy then applies the freshness
        // window — tombstones older than DEFAULT_TOMBSTONE_TTL_MS
        // (24h) no longer block re-adds.
        val combinedTombs = mergeTombstones(localTombs, remoteSet.tombstones)
        val mergedMembers = ConflictPolicies.unionWithTombstoneStamps(
            local = local,
            remote = remoteSet.members,
            tombstones = combinedTombs,
            now = now,
        )
        android.util.Log.d(tag, "merged: ${mergedMembers.size} members (${combinedTombs.size} combined tombstones)")

        // Issue #989 — combine the per-member rebuild URLs from both
        // sides. Local wins on conflict: this device's persisted URL is
        // first-hand (it captured the paste) and we don't want a stale
        // remote value to shadow it. Remote-only URLs fill in members we
        // received from another device.
        val localData = runCatching { localMemberData(local) }
            .getOrElse { return SyncOutcome.Transient("local member data: ${it.message}") }
        val mergedData = (remoteSet.memberData + localData)
            .filterKeys { it in mergedMembers }
        android.util.Log.d(tag, "memberData: ${localData.size} local, ${remoteSet.memberData.size} remote, ${mergedData.size} merged")

        // Reconcile local — apply remote-only adds, drop remote tombs.
        val toAddLocally = mergedMembers - local
        val toRemoveLocally = (local - mergedMembers)
        android.util.Log.i(tag, "diff: +${toAddLocally.size} adds, -${toRemoveLocally.size} removes")
        for ((i, id) in toAddLocally.withIndex()) {
            runCatching { localAdd(id) }
                .getOrElse { return SyncOutcome.Transient("localAdd($id): ${it.message}") }
            // Persist the rebuild URL onto the row localAdd just created
            // so the back-fill worker (or a detail-open) can hydrate a
            // hash-id fiction this device has never seen. After localAdd
            // so the placeholder row exists for the UPDATE to land on.
            // No-op default for aux-less domains.
            mergedData[id]?.let { url ->
                runCatching { persistMemberData(id, url) }
                    .getOrElse { return SyncOutcome.Transient("persistMemberData($id): ${it.message}") }
            }
            if ((i + 1) % 10 == 0) android.util.Log.d(tag, "localAdd progress: ${i+1}/${toAddLocally.size}")
        }
        for ((i, id) in toRemoveLocally.withIndex()) {
            runCatching { localRemove(id) }
                .getOrElse { return SyncOutcome.Transient("localRemove($id): ${it.message}") }
            if ((i + 1) % 10 == 0) android.util.Log.d(tag, "localRemove progress: ${i+1}/${toRemoveLocally.size}")
        }

        // Push the canonical state back so the server matches. Note
        // we push the *combined* tombstone map (including expired
        // ones, until garbage-collected by [forget] on the next
        // round) so other devices see the same horizon we just
        // computed against.
        android.util.Log.d(tag, "pushing ${mergedMembers.size} members to remote")
        val push = remote.push(user, mergedMembers, combinedTombs, mergedData)
        if (push.isFailure) {
            return SyncOutcome.Transient("remote push: ${push.exceptionOrNull()?.message}")
        }

        // Best-effort tombstone hygiene — forget any that are now also
        // server-side, since the server keeps its own copy. We also
        // forget tombstones that are past the TTL (their info is no
        // longer load-bearing for the merge), keeping the local
        // tombstone log from growing without bound.
        for (id in remoteSet.tombstones.keys) {
            runCatching { tombstones.forget(name, id) }
        }
        val expired = combinedTombs.filterValues { stamp -> now - stamp >= ConflictPolicies.DEFAULT_TOMBSTONE_TTL_MS }.keys
        for (id in expired) {
            runCatching { tombstones.forget(name, id) }
        }
        android.util.Log.i(tag, "complete: ${toAddLocally.size + toRemoveLocally.size} records affected")
        return SyncOutcome.Ok(recordsAffected = toAddLocally.size + toRemoveLocally.size)
    }

    /** Merge two tombstone maps, taking the newer stamp on conflict.
     *  A "newer" tombstone is more authoritative than an older one
     *  for the same id (someone re-recorded the removal). */
    private fun mergeTombstones(a: Map<String, Long>, b: Map<String, Long>): Map<String, Long> {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        val out = a.toMutableMap()
        for ((id, stamp) in b) {
            val existing = out[id]
            if (existing == null || stamp > existing) out[id] = stamp
        }
        return out
    }
}
