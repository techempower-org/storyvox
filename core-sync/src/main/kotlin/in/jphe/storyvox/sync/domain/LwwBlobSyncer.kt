package `in`.jphe.storyvox.sync.domain

import `in`.jphe.storyvox.sync.client.SignedInUser
import `in`.jphe.storyvox.sync.coordinator.ConflictPolicies
import `in`.jphe.storyvox.sync.coordinator.Stamped
import `in`.jphe.storyvox.sync.coordinator.SyncOutcome
import `in`.jphe.storyvox.sync.coordinator.Syncer

/**
 * Reusable last-write-wins syncer for blob-shaped domains — the
 * pronunciation dict, the per-domain settings JSON, encrypted secret
 * bundles. Stored remotely as a single entity per domain with `payload`
 * and `updatedAt` fields.
 *
 * Concrete domains supply:
 *  - [localRead] / [localWrite] — the local persistence path
 *  - [remote] — the InstantDB call surface
 *
 * Conflict resolution: pick the higher updatedAt. Tie goes to local
 * (we'd rather not churn writes). See [ConflictPolicies.lastWriteWins].
 */
class LwwBlobSyncer(
    override val name: String,
    private val localRead: suspend () -> Stamped<String>?,
    private val localWrite: suspend (Stamped<String>) -> Unit,
    private val remote: BlobRemote,
) : Syncer {

    interface BlobRemote {
        suspend fun fetch(user: SignedInUser): Result<Stamped<String>?>
        suspend fun push(user: SignedInUser, payload: Stamped<String>): Result<Unit>
    }

    override suspend fun push(user: SignedInUser): SyncOutcome = reconcile(user)
    override suspend fun pull(user: SignedInUser): SyncOutcome = reconcile(user)

    private suspend fun reconcile(user: SignedInUser): SyncOutcome {
        val tag = "LwwSyncer[$name]"
        val local = runCatching { localRead() }
            .getOrElse { return SyncOutcome.Transient("local read: ${it.message}") }
        val remoteVal = remote.fetch(user).getOrElse {
            return SyncOutcome.Transient("remote fetch: ${it.message}")
        }
        android.util.Log.d(tag, "local=${if (local != null) "${local.value.length}chars@${local.updatedAt}" else "null"} remote=${if (remoteVal != null) "${remoteVal.value.length}chars@${remoteVal.updatedAt}" else "null"}")

        val merged = when {
            local == null && remoteVal == null -> {
                android.util.Log.d(tag, "both null, nothing to sync")
                return SyncOutcome.Ok(0)
            }
            local == null -> {
                android.util.Log.i(tag, "local empty, applying remote (${remoteVal!!.value.length} chars)")
                runCatching { localWrite(remoteVal) }
                    .onFailure { return SyncOutcome.Transient("localWrite: ${it.message}") }
                return SyncOutcome.Ok(1)
            }
            remoteVal == null -> {
                android.util.Log.i(tag, "remote empty, pushing local (${local.value.length} chars)")
                val push = remote.push(user, local)
                if (push.isFailure) return SyncOutcome.Transient("remote push: ${push.exceptionOrNull()?.message}")
                return SyncOutcome.Ok(1)
            }
            else -> ConflictPolicies.lastWriteWins(local, remoteVal)
        }
        android.util.Log.d(tag, "merged: winner=${if (merged.updatedAt == local.updatedAt) "local" else "remote"}")

        if (merged.updatedAt != local.updatedAt || merged.value != local.value) {
            android.util.Log.d(tag, "writing merged to local")
            runCatching { localWrite(merged) }
                .onFailure { return SyncOutcome.Transient("localWrite: ${it.message}") }
        }
        if (merged.updatedAt != remoteVal!!.updatedAt || merged.value != remoteVal.value) {
            android.util.Log.d(tag, "pushing merged to remote")
            val push = remote.push(user, merged)
            if (push.isFailure) return SyncOutcome.Transient("remote push: ${push.exceptionOrNull()?.message}")
        }
        return SyncOutcome.Ok(1)
    }
}
