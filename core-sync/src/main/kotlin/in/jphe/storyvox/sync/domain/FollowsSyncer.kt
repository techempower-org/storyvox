package `in`.jphe.storyvox.sync.domain

import `in`.jphe.storyvox.data.db.dao.FictionDao
import `in`.jphe.storyvox.sync.client.InstantBackend
import `in`.jphe.storyvox.sync.client.SignedInUser
import `in`.jphe.storyvox.sync.coordinator.SyncOutcome
import `in`.jphe.storyvox.sync.coordinator.Syncer
import `in`.jphe.storyvox.sync.coordinator.TombstoneStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Same shape as [LibrarySyncer], but for `followedRemotely`. The Follows
 * tab in storyvox is mirrored from Royal Road's follow list at sign-in
 * time — this syncer makes sure that mirroring also rides between
 * devices, so adding a follow on tablet shows up on phone.
 *
 * Note this does NOT push follows back to Royal Road. The directionality
 * is RR → storyvox → cloud → other devices. RR's own follow API is
 * the source of truth for the user-facing "follow on RR" semantic; we
 * just cache the resulting set across devices so a fresh install
 * doesn't lose it.
 */
@Singleton
class FollowsSyncer @Inject constructor(
    private val fictionDao: FictionDao,
    private val backend: InstantBackend,
    private val tombstones: TombstoneStore,
) : Syncer {

    private val delegate by lazy { buildDelegate() }

    override val name: String get() = DOMAIN

    override suspend fun push(user: SignedInUser): SyncOutcome = delegate.push(user)
    override suspend fun pull(user: SignedInUser): SyncOutcome = delegate.pull(user)

    suspend fun recordUnfollow(fictionId: String) {
        tombstones.add(DOMAIN, fictionId)
    }

    private fun buildDelegate(): SetSyncer = SetSyncer(
        name = DOMAIN,
        tombstones = tombstones,
        localSnapshot = {
            fictionDao.followsSnapshot().map { it.id }.toSet()
        },
        localAdd = { id ->
            val existing = fictionDao.get(id)
            if (existing == null) {
                val now = System.currentTimeMillis()
                fictionDao.upsert(
                    `in`.jphe.storyvox.data.db.entity.Fiction(
                        id = id,
                        // Issue #981 — shape-aware sourceId; see the
                        // matching note in LibrarySyncer.localAdd. The
                        // back-fill worker repairs anything resolveByShape
                        // can't (radio station ids) on its first pass.
                        sourceId = `in`.jphe.storyvox.data.source.FictionSourceIdResolver.resolveByShape(id),
                        title = "Loading…",
                        author = "",
                        firstSeenAt = now,
                        metadataFetchedAt = 0L,
                        followedRemotely = true,
                    ),
                )
            } else {
                fictionDao.setFollowedRemote(id, followed = true)
            }
        },
        localRemove = { id ->
            fictionDao.setFollowedRemote(id, followed = false)
        },
        remote = BackendSetRemote(domain = DOMAIN, backend = backend),
    )

    companion object {
        const val DOMAIN: String = "follows"
    }
}
