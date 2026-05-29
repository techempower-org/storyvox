package `in`.jphe.storyvox.data.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.data.db.dao.FictionDao
import `in`.jphe.storyvox.data.log.DebugLog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #981 — enqueues the [MetadataBackfillWorker] when there are
 * synced placeholder fictions to hydrate.
 *
 * Pulled behind an interface (mirroring [ChapterDownloadScheduler]) so the
 * trigger sites — `StoryvoxApp` after a cold-start sync pull and
 * `LibraryViewModel.init` so already-stuck placeholders resolve on screen
 * open — can depend on it without pulling `androidx.work` into the app /
 * feature modules, and so the enqueue gating is unit-testable with a
 * recording fake.
 *
 * Cheap to call repeatedly: it does a single indexed `COUNT(*)` and skips
 * the WorkManager round-trip entirely when nothing needs hydrating, and
 * enqueues with [ExistingWorkPolicy.KEEP] under a unique name so a burst
 * of triggers (sync completes while the Library is already open)
 * coalesces into one running job instead of piling up duplicates.
 */
interface MetadataBackfillScheduler {
    /**
     * Enqueue a back-fill pass if any library/follows placeholder rows
     * still need metadata. No-op when there's nothing to do or a pass is
     * already enqueued. Suspends only for the cheap count query.
     */
    suspend fun enqueueIfNeeded()
}

@Singleton
class WorkManagerMetadataBackfillScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fictionDao: FictionDao,
) : MetadataBackfillScheduler {

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    override suspend fun enqueueIfNeeded() {
        val pending = fictionDao.placeholderCount()
        if (pending == 0) {
            DebugLog.i(TAG) { "enqueueIfNeeded: no placeholders, skipping" }
            return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<MetadataBackfillWorker>()
            .setConstraints(constraints)
            .addTag(MetadataBackfillWorker.TAG)
            .build()

        DebugLog.i(TAG) { "enqueueIfNeeded: $pending placeholder(s), enqueue KEEP" }
        workManager.enqueueUniqueWork(
            MetadataBackfillWorker.UNIQUE_NAME,
            // KEEP: a pass already running/enqueued picks up whatever the
            // current placeholder set is when it runs, so a second trigger
            // doesn't need to replace it. New placeholders that land after
            // it starts are caught by the next trigger.
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private companion object {
        const val TAG = "MetadataBackfillScheduler"
    }
}
