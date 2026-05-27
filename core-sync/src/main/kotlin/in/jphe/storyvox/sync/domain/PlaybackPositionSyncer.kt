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
    private val fictionDao: `in`.jphe.storyvox.data.db.dao.FictionDao,
    private val chapterDao: `in`.jphe.storyvox.data.db.dao.ChapterDao,
) : Syncer {

    override val name: String get() = DOMAIN

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }

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
        // Guard: PlaybackPosition has FK constraints on both fiction and
        // chapter tables. After app data clear + re-sync, parent rows may
        // not exist yet — skip those entries rather than crashing on FK
        // violation. The position will be written on the next sync round
        // once library sync has created the parent rows.
        var writes = 0
        var skipped = 0
        for ((id, merged) in mergedByFiction) {
            val local = localPositions[id]
            if (local == null || merged.updatedAt > local.updatedAt) {
                val fictionExists = fictionDao.get(merged.fictionId) != null
                val chapterExists = chapterDao.exists(merged.chapterId)
                if (!fictionExists || !chapterExists) {
                    android.util.Log.d(TAG, "skipping position for ${merged.fictionId}: fiction=$fictionExists chapter=$chapterExists")
                    skipped++
                    continue
                }
                playbackDao.upsert(merged.toEntity())
                writes++
            }
        }
        if (skipped > 0) android.util.Log.i(TAG, "skipped $skipped positions (missing parent rows)")

        // Push the merged payload back.
        val pushPayload = Payload(entries = mergedByFiction.values.sortedBy { it.fictionId })
        // Nothing to upload (fresh install, no local or remote positions).
        // Pushing here would stamp the remote row with System.currentTimeMillis(),
        // an updatedAt that corresponds to no real entry and ratchets upward on
        // every empty sync round. Skip instead. See issue #887.
        val pushStamp = pushPayload.entries.maxOfOrNull { it.updatedAt }
            ?: return SyncOutcome.Ok(writes)
        val pushed = backend.upsert(
            user = user,
            entity = ENTITY,
            id = rowId(user),
            payload = json.encodeToString(Payload.serializer(), pushPayload),
            updatedAt = pushStamp,
        )
        if (pushed.isFailure) {
            return SyncOutcome.Transient("remote push: ${pushed.exceptionOrNull()?.message}")
        }
        return SyncOutcome.Ok(writes)
    }

    /**
     * Pull every fiction's position into a map. One Room round-trip via the
     * slim [PlaybackDao.allPositionsSnapshot] projection — no per-id follow-up
     * `get()` calls. See issue #777.
     */
    private suspend fun localSnapshot(): Map<String, Entry> {
        val rows = playbackDao.allPositionsSnapshot()
        if (rows.isEmpty()) return emptyMap()
        return rows.associate { r ->
            r.fictionId to Entry(
                fictionId = r.fictionId,
                chapterId = r.chapterId,
                charOffset = r.charOffset,
                paragraphIndex = r.paragraphIndex,
                playbackSpeed = r.playbackSpeed,
                durationEstimateMs = r.durationEstimateMs,
                updatedAt = r.updatedAt,
            )
        }
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
        private const val TAG = "PositionSyncer"
    }
}
