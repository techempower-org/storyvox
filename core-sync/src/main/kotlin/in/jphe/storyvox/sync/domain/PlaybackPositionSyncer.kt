package `in`.jphe.storyvox.sync.domain

import `in`.jphe.storyvox.data.db.dao.PlaybackDao
import `in`.jphe.storyvox.data.db.entity.PlaybackPosition
import `in`.jphe.storyvox.sync.SyncIds
import `in`.jphe.storyvox.sync.client.InstantBackend
import `in`.jphe.storyvox.sync.client.SignedInUser
import `in`.jphe.storyvox.sync.coordinator.SyncOutcome
import `in`.jphe.storyvox.sync.coordinator.Syncer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Syncs the user's "where I was" position across devices, with the
 * "server keeps the maximum across devices" rule from JP's brief.
 *
 * Wire format is a single JSON blob per user: a map of fictionId →
 * position + chapter + speed + timestamps. One row, one transact —
 * keeps wire traffic low and the conflict rule trivial. The whole map
 * is rewritten on each sync; conflict resolution applies per-fiction
 * using [PlaybackPositionConflict.merge].
 *
 * Why not per-fiction-row: per-row would scale better at very large
 * libraries but introduces many more transacts per sync round and a
 * lot more bookkeeping. For storyvox's typical user (dozens, maybe
 * low hundreds, of fictions in library) the blob approach is fine and
 * keeps the data layer thin.
 */
@Singleton
class PlaybackPositionSyncer @Inject constructor(
    private val playbackDao: PlaybackDao,
    private val backend: InstantBackend,
) : Syncer {

    override val name: String get() = DOMAIN

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    data class Entry(
        val fictionId: String,
        val chapterId: String,
        val charOffset: Int,
        val paragraphIndex: Int,
        val playbackSpeed: Float,
        val durationEstimateMs: Long,
        val updatedAt: Long,
    )

    @Serializable
    data class Payload(val entries: List<Entry>)

    override suspend fun push(user: SignedInUser): SyncOutcome = reconcile(user)
    override suspend fun pull(user: SignedInUser): SyncOutcome = reconcile(user)

    private suspend fun reconcile(user: SignedInUser): SyncOutcome {
        // Read local. We don't have an "all positions" DAO method yet —
        // synthesize from the library. The library + follows are the only
        // contexts a position is meaningful in (a position outside the
        // library means the user once played a book they didn't add;
        // we still sync it because losing it on uninstall is what this
        // whole effort is fixing).
        // For v1 we use a workaround: walk the library's fictions and
        // ask the dao per id. This is O(library-size) round-trips on
        // pull. Acceptable for v1; revisit when we have a hundreds-of-
        // fictions library on a tablet.
        val localPositions = localSnapshot()

        val remote = backend.fetch(user, ENTITY, rowId(user)).getOrElse {
            return SyncOutcome.Transient("remote fetch: ${it.message}")
        }
        val remotePayload = remote?.payload?.let {
            runCatching { json.decodeFromString(Payload.serializer(), it) }.getOrNull()
        }
        val remoteByFiction = remotePayload?.entries?.associateBy { it.fictionId } ?: emptyMap()

        // Merge with the max-stamped policy.
        val mergedByFiction = (localPositions.keys + remoteByFiction.keys).associateWith { id ->
            val l = localPositions[id]
            val r = remoteByFiction[id]
            when {
                l == null -> r!!
                r == null -> l
                else -> if (r.updatedAt > l.updatedAt) r else l
            }
        }

        // Apply remote-only entries to local (those that didn't exist locally
        // and entries whose remote updatedAt won).
        var writes = 0
        for ((id, merged) in mergedByFiction) {
            val local = localPositions[id]
            if (local == null || merged.updatedAt > local.updatedAt) {
                playbackDao.upsert(merged.toEntity())
                writes++
            }
        }

        // Push the merged payload back.
        val pushPayload = Payload(entries = mergedByFiction.values.sortedBy { it.fictionId })
        val pushed = backend.upsert(
            user = user,
            entity = ENTITY,
            id = rowId(user),
            payload = json.encodeToString(Payload.serializer(), pushPayload),
            updatedAt = pushPayload.entries.maxOfOrNull { it.updatedAt } ?: System.currentTimeMillis(),
        )
        if (pushed.isFailure) {
            return SyncOutcome.Transient("remote push: ${pushed.exceptionOrNull()?.message}")
        }
        return SyncOutcome.Ok(writes)
    }

    /** Pull every fiction's position into a map. v1 uses no batch DAO
     *  call; relies on the per-id `playbackDao.get`. */
    private suspend fun localSnapshot(): Map<String, Entry> {
        // The DAO doesn't have an `all()`; use a SQL probe via the recent feed
        // capped to a high limit. v1 leaves the absence of a proper `all()` as
        // a documented limitation — see docs/sync.md.
        val rows = playbackDao.recent(limit = 10_000)
        if (rows.isEmpty()) return emptyMap()
        val byId = mutableMapOf<String, Entry>()
        for (r in rows) {
            val pos = playbackDao.get(r.fictionId) ?: continue
            byId[pos.fictionId] = Entry(
                fictionId = pos.fictionId,
                chapterId = pos.chapterId,
                charOffset = pos.charOffset,
                paragraphIndex = pos.paragraphIndex,
                playbackSpeed = pos.playbackSpeed,
                durationEstimateMs = pos.durationEstimateMs,
                updatedAt = pos.updatedAt,
            )
        }
        return byId
    }

    private fun Entry.toEntity(): PlaybackPosition = PlaybackPosition(
        fictionId = fictionId,
        chapterId = chapterId,
        charOffset = charOffset,
        paragraphIndex = paragraphIndex,
        playbackSpeed = playbackSpeed,
        durationEstimateMs = durationEstimateMs,
        updatedAt = updatedAt,
    )

    private fun rowId(user: SignedInUser) = SyncIds.rowUuid(DOMAIN, user.userId)

    companion object {
        const val DOMAIN: String = "positions"
        private const val ENTITY = "positions"
    }
}
