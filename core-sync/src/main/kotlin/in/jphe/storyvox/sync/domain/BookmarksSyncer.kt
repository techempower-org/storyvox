package `in`.jphe.storyvox.sync.domain

import android.content.SharedPreferences
import androidx.core.content.edit
import `in`.jphe.storyvox.data.db.dao.ChapterDao
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
 * Syncs per-chapter bookmarks (#121). One bookmark per chapter, stored
 * as a `bookmarkCharOffset` on the chapter row.
 *
 * Wire format: a single blob per user with a map of chapterId →
 * charOffset. LWW on the whole map. Per-bookmark merge is not done in
 * v1 — the same caveat as PronunciationDictSyncer applies, and the same
 * argument: bookmarks are rarely edited simultaneously across devices.
 *
 * Deletion: a null offset means "no bookmark." We use null in the wire
 * format too so a sync round can clear a bookmark; absence means "no
 * change," null means "explicitly cleared." This avoids needing a
 * tombstone log.
 */
@Singleton
class BookmarksSyncer @Inject constructor(
    private val chapterDao: ChapterDao,
    private val backend: InstantBackend,
    private val prefs: SharedPreferences,
) : Syncer {

    override val name: String get() = DOMAIN

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    data class Payload(
        val bookmarks: Map<String, Int?>,
        val updatedAt: Long,
    )

    override suspend fun push(user: SignedInUser): SyncOutcome = reconcile(user)
    override suspend fun pull(user: SignedInUser): SyncOutcome = reconcile(user)

    private suspend fun reconcile(user: SignedInUser): SyncOutcome {
        val localRows = chapterDao.allBookmarks()
        val localMap = localRows.associate { it.chapterId to it.charOffset }

        val remoteSnap = backend.fetch(user, ENTITY, rowId(user)).getOrElse {
            return SyncOutcome.Transient("remote fetch: ${it.message}")
        }
        val remotePayload = remoteSnap?.payload?.let {
            runCatching { json.decodeFromString(Payload.serializer(), it) }.getOrNull()
        }
        val remoteMap = remotePayload?.bookmarks.orEmpty()

        val merged = when {
            remotePayload == null -> localMap
            remotePayload.updatedAt > (remoteSnap.updatedAt - 1L) && remoteMap == localMap -> localMap
            // LWW: pick whichever side is newer. We don't have a local
            // updatedAt stamp per the chapter row (bookmark write
            // doesn't bump a timestamp), so the local "stamp" is the
            // last push attempt for this domain — if remote is strictly
            // newer than that, take remote.
            remotePayload.updatedAt > readLastSyncStamp() -> remoteMap
            else -> localMap
        }

        // Apply merged to local: write every chapterId in [merged] that
        // diverges from local, and clear chapters present in remote
        // with null offset.
        //
        // Guard (#885): `setBookmark` is an UPDATE … WHERE id = :id, a
        // silent no-op when the chapter row doesn't exist locally yet
        // (library/chapter sync runs separately and chapter rows are
        // repopulated lazily on detail-page open). Skip the local write
        // for missing chapters but KEEP the entry in [merged] so the
        // push payload below retains it — a later sync round writes it
        // locally once the chapter row arrives. Mirrors the FK guard in
        // PlaybackPositionSyncer.
        var writes = 0
        var skipped = 0
        for ((id, offset) in merged) {
            if (localMap[id] != offset) {
                if (!chapterDao.exists(id)) {
                    skipped++
                    continue
                }
                chapterDao.setBookmark(id, offset)
                writes++
            }
        }
        // Clear local bookmarks that the remote explicitly removed.
        for ((id, _) in localMap) {
            if (!merged.containsKey(id)) {
                chapterDao.setBookmark(id, null)
                writes++
            }
        }
        if (skipped > 0) {
            android.util.Log.i(TAG, "skipped $skipped bookmarks (missing chapter rows); retained on wire")
        }

        val now = System.currentTimeMillis()
        val push = backend.upsert(
            user = user,
            entity = ENTITY,
            id = rowId(user),
            payload = json.encodeToString(
                Payload.serializer(),
                Payload(bookmarks = merged, updatedAt = now),
            ),
            updatedAt = now,
        )
        writeLastSyncStamp(now)
        return if (push.isSuccess) SyncOutcome.Ok(writes)
        else SyncOutcome.Transient("remote push: ${push.exceptionOrNull()?.message}")
    }

    // Issue #360 finding 2 (argus): `lastSyncStamp` used to be a
    // `@Volatile private var = 0L` — process-local, reset on every
    // cold start. The merge rule at the top of `reconcile` reads
    // `remotePayload.updatedAt > readLastSyncStamp()`, so after a
    // restart any non-empty remote was strictly newer than 0L and won
    // blanket — clobbering local bookmarks the user had added between
    // the previous push and the kill.
    //
    // Now persisted to the `instantdb.*` SharedPreferences namespace
    // (same bag InstantSession + SecretsSyncer use), so the merge rule
    // is correct across process restarts and across the
    // post-sign-in `pull → push` sequence on the same cold start.
    private fun readLastSyncStamp(): Long = prefs.getLong(LAST_SYNC_KEY, 0L)
    private fun writeLastSyncStamp(at: Long) { prefs.edit { putLong(LAST_SYNC_KEY, at) } }

    private fun rowId(user: SignedInUser) = SyncIds.rowUuid(DOMAIN, user.userId)

    companion object {
        const val DOMAIN: String = "bookmarks"
        private const val ENTITY = "blobs"
        private const val LAST_SYNC_KEY = "instantdb.bookmarks_synced_at"
        private const val TAG = "BookmarksSyncer"
    }
}
