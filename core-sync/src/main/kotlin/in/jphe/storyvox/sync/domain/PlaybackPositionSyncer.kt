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
 * Wire format is a single JSON blob per user: a list of position entries
 * (position + chapter + speed + timestamps). One row, one transact — keeps
 * wire traffic low and the conflict rule trivial. The whole list is
 * rewritten on each sync; conflict resolution applies per entry, max-stamp
 * wins.
 *
 * ## Wire-format versioning (issue #965)
 *
 * The entry key changed from `fictionId` (one entry per fiction) to the
 * composite `(fictionId, chapterId)` (one entry per chapter), matching the
 * new per-chapter PK. The on-wire [Entry] shape is UNCHANGED — it always
 * carried both `fictionId` and `chapterId`; only how we *key* the merge map
 * changed. [Payload.version] is bumped to [WIRE_VERSION] = 2 for diagnostics.
 *
 * BACK-COMPAT (the critical bit): a legacy v1 blob pushed by a device still
 * on the old build (phone / Android Auto / Wear not yet upgraded) is a list
 * of entries each keyed-by-fiction — i.e. at most one entry per fiction, but
 * every one still has a valid `chapterId`. Re-keying that list by
 * `(fictionId, chapterId)` is lossless: each legacy entry lands on its own
 * composite key and is merged normally. Nothing is dropped. Conversely a v1
 * reader receiving our v2 blob (multiple chapters per fiction) keeps only
 * the last entry it sees per fiction via its `associateBy { fictionId }` —
 * it loses the extra chapters but keeps a valid, recent position, so an
 * un-upgraded device degrades gracefully rather than corrupting. The
 * back-compat window stays open until all of a user's devices are on v11+.
 *
 * Why not per-entry-row: per-row would scale better at very large libraries
 * but introduces many more transacts per sync round and a lot more
 * bookkeeping. For storyvox's typical user (dozens, maybe low hundreds, of
 * fictions) the blob approach is fine and keeps the data layer thin.
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

    /**
     * [version] defaults to 1 so a legacy blob (which omits the field
     * entirely) decodes as v1 without `coerceInputValues` having to guess.
     * We always *write* [WIRE_VERSION]. The version is advisory — the reader
     * re-keys entries by `(fictionId, chapterId)` regardless, so a v1 blob is
     * read losslessly (see class kdoc). Kept for diagnostics + any future
     * format change that genuinely can't be back-read.
     */
    @Serializable
    data class Payload(
        val entries: List<Entry>,
        val version: Int = 1,
    )

    /** Composite merge key — mirrors the v11 `(fictionId, chapterId)` PK. */
    private data class PosKey(val fictionId: String, val chapterId: String)

    private val Entry.key: PosKey get() = PosKey(fictionId, chapterId)

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
        // Re-key remote entries by the composite (fictionId, chapterId).
        // Lossless for both formats: a legacy v1 blob has at most one entry
        // per fiction (each with a valid chapterId), so each lands on its
        // own composite key; a v2 blob already has per-chapter entries.
        // associateBy keeps the last entry on a key collision, which can't
        // happen within a well-formed blob (composite keys are unique).
        val remoteByKey = remotePayload?.entries?.associateBy { it.key } ?: emptyMap()

        // Merge with the max-stamped policy, per (fiction, chapter).
        val mergedByKey = (localPositions.keys + remoteByKey.keys).associateWith { key ->
            val l = localPositions[key]
            val r = remoteByKey[key]
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
        for ((key, merged) in mergedByKey) {
            val local = localPositions[key]
            if (local == null || merged.updatedAt > local.updatedAt) {
                val fictionExists = fictionDao.get(merged.fictionId) != null
                val chapterExists = chapterDao.exists(merged.chapterId)
                if (!fictionExists || !chapterExists) {
                    android.util.Log.d(TAG, "skipping position for ${merged.fictionId}/${merged.chapterId}: fiction=$fictionExists chapter=$chapterExists")
                    skipped++
                    continue
                }
                playbackDao.upsert(merged.toEntity())
                writes++
            }
        }
        if (skipped > 0) android.util.Log.i(TAG, "skipped $skipped positions (missing parent rows)")

        // Push the merged payload back at the current wire version. Sort by
        // (fictionId, chapterId) so the blob is deterministic across devices.
        val pushPayload = Payload(
            entries = mergedByKey.values.sortedWith(
                compareBy({ it.fictionId }, { it.chapterId }),
            ),
            version = WIRE_VERSION,
        )
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
     * Pull every saved position into a map keyed by `(fictionId, chapterId)`.
     * One Room round-trip via the slim [PlaybackDao.allPositionsSnapshot]
     * projection — no per-id follow-up `get()` calls. See issue #777. Under
     * the v11 PK there are now multiple rows per fiction; each is its own
     * map entry (issue #965).
     */
    private suspend fun localSnapshot(): Map<PosKey, Entry> {
        val rows = playbackDao.allPositionsSnapshot()
        if (rows.isEmpty()) return emptyMap()
        return rows.associate { r ->
            PosKey(r.fictionId, r.chapterId) to Entry(
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

        /**
         * Wire-format version written into [Payload.version]. v1 keyed
         * entries by fictionId (one per fiction); v2 (#965) keys by
         * `(fictionId, chapterId)`. The reader re-keys regardless, so v1
         * blobs are still read losslessly — see the class kdoc back-compat
         * note. Bump only when a future change can't be back-read.
         */
        private const val WIRE_VERSION = 2
    }
}
