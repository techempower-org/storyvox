package `in`.jphe.storyvox.sync.domain

import android.content.SharedPreferences
import androidx.core.content.edit
import `in`.jphe.storyvox.data.db.dao.AnnotationDao
import `in`.jphe.storyvox.data.db.entity.Annotation
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
 * Syncs text highlights + notes (#999). One blob per user with a map of
 * annotation id → [Dto] (or null), exactly mirroring [BookmarksSyncer].
 *
 * Wire format: `{ annotations: { <id>: Dto | null }, updatedAt: Long }`.
 * LWW on the whole map, keyed by the persisted last-sync stamp (NOT a
 * process-local var — see the #360 lesson in [BookmarksSyncer]). Per-row
 * merge is not done in v1: like bookmarks, two devices rarely edit the
 * *same* highlight simultaneously, and the UUID id keeps independent adds
 * on two devices from colliding.
 *
 * Deletion contract (the #1029 lesson, applied verbatim): a map entry of
 * `id -> null` is an explicit delete tombstone and clears the local row.
 * *Absence* of an id from a winning remote map means "no change", NOT
 * "delete" — a local-only add (made on this device, not yet pushed) is
 * absent from a winning remote, so it is preserved locally and unioned
 * into the push payload rather than destroyed.
 *
 * FK guard (the [BookmarksSyncer] / PlaybackPositionSyncer pattern): an
 * incoming annotation can arrive before its chapter/fiction row is
 * hydrated locally. `annotationDao.upsert` would violate the FK; we keep
 * such an entry on the wire (so a later round writes it once the parent
 * rows arrive) but skip the local write this round. Because the FK is on
 * fiction+chapter, the guard checks those parents — not the annotation's
 * own id (which is exactly what we're trying to insert).
 */
@Singleton
class AnnotationsSyncer @Inject constructor(
    private val annotationDao: AnnotationDao,
    private val backend: InstantBackend,
    private val prefs: SharedPreferences,
    // FK-parent existence probes. Kept as small lambdas so the syncer
    // doesn't take a hard dependency on the whole Fiction/Chapter DAOs;
    // wired in SyncModule. Default to "present" so a test can omit them.
    private val chapterExists: suspend (String) -> Boolean = { true },
    private val fictionExists: suspend (String) -> Boolean = { true },
) : Syncer {

    override val name: String get() = DOMAIN

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }

    /** Wire representation of one annotation. Mirrors [Annotation] minus
     *  the redundant id (it's the map key) and fictionId/chapterId which
     *  ARE carried so a second device can write the row + FK-guard it. */
    @Serializable
    data class Dto(
        val fictionId: String,
        val chapterId: String,
        val startOffset: Int,
        val endOffset: Int,
        val color: String,
        val note: String? = null,
        val quotedText: String,
        val createdAt: Long,
        val updatedAt: Long,
    )

    @Serializable
    data class Payload(
        val annotations: Map<String, Dto?>,
        val updatedAt: Long,
    )

    override suspend fun push(user: SignedInUser): SyncOutcome = reconcile(user)
    override suspend fun pull(user: SignedInUser): SyncOutcome = reconcile(user)

    private suspend fun reconcile(user: SignedInUser): SyncOutcome {
        val localRows = annotationDao.allAnnotations()
        val localMap: Map<String, Dto> = localRows.associate { it.id to it.toDto() }

        val remoteSnap = backend.fetch(user, ENTITY, rowId(user)).getOrElse {
            return SyncOutcome.Transient("remote fetch: ${it.message}")
        }
        val remotePayload = remoteSnap?.payload?.let {
            runCatching { json.decodeFromString(Payload.serializer(), it) }.getOrNull()
        }
        val remoteMap: Map<String, Dto?> = remotePayload?.annotations.orEmpty()

        // LWW winner for the shared keys. Same rule as BookmarksSyncer: if
        // remote is a perfect mirror of local (and as-fresh as the row), no
        // one wins; otherwise remote wins iff it's strictly newer than our
        // last sync stamp.
        val remoteWins = when {
            remotePayload == null -> false
            remotePayload.updatedAt > (remoteSnap.updatedAt - 1L) &&
                remoteMap == localMap -> false
            else -> remotePayload.updatedAt > readLastSyncStamp()
        }

        // Build [merged] honoring the #1029 contract:
        //  - start from the LWW winner for keys both sides know,
        //  - union in every local-only row (present locally, no remote entry
        //    at all) so an unsynced add survives + propagates,
        //  - a remote *explicit* null is the only delete signal and stays
        //    null in [merged] so the apply loop clears it and the push
        //    doesn't resurrect it.
        val merged = LinkedHashMap<String, Dto?>()
        merged.putAll(if (remoteWins) remoteMap else localMap)
        if (remoteWins) {
            for ((id, dto) in localMap) {
                if (!remoteMap.containsKey(id)) merged[id] = dto
            }
        }

        // Apply [merged] to local. An explicit null deletes; a present Dto
        // upserts iff it diverges from local. FK guard: skip-but-retain when
        // the parent fiction/chapter row isn't local yet.
        var writes = 0
        var skipped = 0
        for ((id, dto) in merged) {
            if (dto == null) {
                if (localMap.containsKey(id)) {
                    annotationDao.deleteById(id)
                    writes++
                }
                continue
            }
            if (localMap[id] == dto) continue // already in desired state
            if (!fictionExists(dto.fictionId) || !chapterExists(dto.chapterId)) {
                skipped++
                continue
            }
            annotationDao.upsert(dto.toEntity(id))
            writes++
        }
        if (skipped > 0) {
            android.util.Log.i(TAG, "skipped $skipped annotations (missing parent rows); retained on wire")
        }

        val now = System.currentTimeMillis()
        val push = backend.upsert(
            user = user,
            entity = ENTITY,
            id = rowId(user),
            payload = json.encodeToString(
                Payload.serializer(),
                Payload(annotations = merged, updatedAt = now),
            ),
            updatedAt = now,
        )
        writeLastSyncStamp(now)
        return if (push.isSuccess) SyncOutcome.Ok(writes)
        else SyncOutcome.Transient("remote push: ${push.exceptionOrNull()?.message}")
    }

    private fun Annotation.toDto() = Dto(
        fictionId = fictionId, chapterId = chapterId,
        startOffset = startOffset, endOffset = endOffset,
        color = color, note = note, quotedText = quotedText,
        createdAt = createdAt, updatedAt = updatedAt,
    )

    private fun Dto.toEntity(id: String) = Annotation(
        id = id, fictionId = fictionId, chapterId = chapterId,
        startOffset = startOffset, endOffset = endOffset,
        color = color, note = note, quotedText = quotedText,
        createdAt = createdAt, updatedAt = updatedAt,
    )

    // Persisted last-sync stamp — see the #360 lesson in BookmarksSyncer:
    // a process-local @Volatile var resets to 0L on cold start and makes
    // any non-empty remote blanket-win, clobbering unpushed local adds.
    private fun readLastSyncStamp(): Long = prefs.getLong(LAST_SYNC_KEY, 0L)
    private fun writeLastSyncStamp(at: Long) { prefs.edit { putLong(LAST_SYNC_KEY, at) } }

    private fun rowId(user: SignedInUser) = SyncIds.rowUuid(DOMAIN, user.userId)

    companion object {
        const val DOMAIN: String = "annotations"
        private const val ENTITY = "blobs"
        private const val LAST_SYNC_KEY = "instantdb.annotations_synced_at"
        private const val TAG = "AnnotationsSyncer"
    }
}
