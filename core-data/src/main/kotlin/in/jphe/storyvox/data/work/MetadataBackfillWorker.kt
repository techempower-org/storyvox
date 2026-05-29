package `in`.jphe.storyvox.data.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import `in`.jphe.storyvox.data.db.dao.FictionDao
import `in`.jphe.storyvox.data.repository.FictionRepository
import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.FictionSourceIdResolver
import `in`.jphe.storyvox.data.source.model.FictionResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Issue #981 — back-fills metadata for synced placeholder fictions.
 *
 * When `LibrarySyncer`/`FollowsSyncer` pull a library/follows member that
 * has no local row (added on another device, or after a cache-clear /
 * fresh install) they insert a *placeholder* `Fiction` carrying only an
 * id + sourceId, `title = "Loading…"`, `metadataFetchedAt = 0`. The
 * comment in `LibrarySyncer.localAdd` promised metadata would "lazy-load
 * on first open" — but nothing back-filled it, so a synced library
 * rendered as a wall of "Loading…" cards that never resolved until each
 * was opened by hand.
 *
 * This worker closes that gap. It walks
 * [FictionDao.placeholdersToBackfill] (library/follows rows with
 * `metadataFetchedAt = 0`, minus recently-failed ones) and hydrates each
 * via [FictionRepository.refreshDetail], which resolves the source plugin
 * from the row's `sourceId` (so `gutenberg:84`, `ao3:…`, numeric Royal
 * Road, `notion:guides`, … all route correctly), fetches the detail page,
 * upserts the real metadata, and stamps `metadataFetchedAt = now` — which
 * drops the row out of the placeholder set.
 *
 * Concurrency: bounded to [PARALLELISM] in-flight fetches via a
 * [Semaphore] so we don't open 23 source connections at once on a cold
 * library. The work itself is one detail fetch per row.
 *
 * Failure handling (the issue's explicit ask — no eternal "Loading…"):
 *  - Per-item `runCatching` + a `FictionResult.Failure` branch: a source
 *    that fails (Royal Road without cookies → AuthRequired, removed
 *    upstream → NotFound, a network blip) is stamped
 *    [FictionDao.markBackfillFailed]. `metadataFetchedAt` stays 0 so the
 *    row remains eligible for a later run once the cool-down elapses, but
 *    the failure stamp lets the library UI show a distinct "Couldn't
 *    load" state instead of an infinite spinner.
 *  - One row's failure never fails the batch — we always return
 *    `Result.success()` with a count. WorkManager retry would re-run the
 *    *whole* sweep, which the cool-down already handles more cheaply, so
 *    a transient network failure simply leaves the rows un-hydrated for
 *    the next trigger (Library open / next sync).
 *  - [CancellationException] is rethrown so WorkManager `stop()` and
 *    structured concurrency propagate normally.
 */
@HiltWorker
class MetadataBackfillWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val fictionDao: FictionDao,
    private val fictionRepository: FictionRepository,
    /** The live bound-source registry. Used only to derive the SET of
     *  valid sourceId keys so the worker can repair a placeholder whose
     *  persisted sourceId can't be resolved (issue #981 — bare Royal Road
     *  ids + radio station ids were written with a bad sourceId). */
    private val sources: Map<String, @JvmSuppressWildcards FictionSource>,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val now = System.currentTimeMillis()
        val placeholders = fictionDao.placeholdersToBackfill(cutoff = now - FAILURE_COOLDOWN_MS)
        if (placeholders.isEmpty()) {
            return Result.success(output(hydrated = 0, failed = 0))
        }
        Log.i(TAG, "back-fill start: ${placeholders.size} placeholder(s)")

        val boundIds = sources.keys
        val gate = Semaphore(PARALLELISM)

        // Each fan-out task returns true when the row hydrated, false on a
        // (soft) failure that was stamped for later retry.
        val results: List<Boolean> = coroutineScope {
            placeholders.map { fiction ->
                async { gate.withPermit { hydrateOne(fiction, boundIds) } }
            }.awaitAll()
        }
        val hydrated = results.count { it }
        val failed = results.size - hydrated

        Log.i(TAG, "back-fill done: hydrated=$hydrated failed=$failed")
        return Result.success(output(hydrated = hydrated, failed = failed))
    }

    /**
     * Hydrate one placeholder. Returns true on success. A
     * `FictionResult.Failure` or any uncaught throwable (parser bug, OOM,
     * unmapped IOException) is treated as a soft failure: the row is
     * stamped via [FictionDao.markBackfillFailed] so the UI can show
     * "Couldn't load" and the cool-down applies, while `metadataFetchedAt`
     * stays 0 so a later run retries it. Never crashes the sweep on one
     * bad row. [CancellationException] is rethrown so WorkManager `stop()`
     * propagates.
     *
     * Self-heal (issue #981): before fetching, validate the row's stored
     * `sourceId` against the bound registry. Synced placeholders were
     * written with `id.substringBefore(':')`, which is wrong for bare
     * Royal Road numeric ids and radio station ids — those rows carry an
     * unbindable sourceId and `refreshDetail` would just throw "No
     * FictionSource for id=…". When the resolver finds the correct key we
     * persist it first ([FictionDao.setSourceId]) so `refreshDetail`
     * routes correctly AND so future tap-to-open / poll paths see the
     * repaired sourceId. A row whose id resolves to no bound source at all
     * is stamped failed (its source module isn't in this build).
     */
    private suspend fun hydrateOne(fiction: `in`.jphe.storyvox.data.db.entity.Fiction, boundIds: Set<String>): Boolean {
        val id = fiction.id
        val resolved = FictionSourceIdResolver.resolve(
            id = id,
            boundSourceIds = boundIds,
            storedSourceId = fiction.sourceId,
        )
        if (resolved == null) {
            // No bound source can hydrate this id (module not in this
            // build). Stamp failed so the UI shows "Couldn't load" rather
            // than an eternal spinner; cool-down applies.
            Log.d(TAG, "back-fill: no bound source for id=$id (stored sourceId=${fiction.sourceId})")
            fictionDao.markBackfillFailed(id, System.currentTimeMillis())
            return false
        }
        if (resolved != fiction.sourceId) {
            Log.i(TAG, "back-fill: repairing sourceId for $id: ${fiction.sourceId} → $resolved")
            fictionDao.setSourceId(id, resolved)
        }

        val result = try {
            // refreshDetail routes by the (now-repaired) row sourceId and,
            // on success, upserts real metadata + stamps metadataFetchedAt
            // + clears the failure flag.
            fictionRepository.refreshDetail(id)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            Log.w(TAG, "refreshDetail threw for $id", t)
            fictionDao.markBackfillFailed(id, System.currentTimeMillis())
            return false
        }
        return when (result) {
            is FictionResult.Success -> true
            is FictionResult.Failure -> {
                fictionDao.markBackfillFailed(id, System.currentTimeMillis())
                Log.d(TAG, "back-fill failed for $id: ${result.message}")
                false
            }
        }
    }

    private fun output(hydrated: Int, failed: Int): Data =
        Data.Builder()
            .putInt(KEY_HYDRATED, hydrated)
            .putInt(KEY_FAILED, failed)
            .build()

    companion object {
        const val TAG = "backfill:metadata"
        const val UNIQUE_NAME = "backfill:metadata"

        const val KEY_HYDRATED = "hydratedCount"
        const val KEY_FAILED = "failedCount"

        /**
         * Max concurrent source detail fetches. Small on purpose — a cold
         * synced library can be 20+ rows across as many sources, and we
         * don't want to open that many connections (or hit per-source
         * rate limits) simultaneously.
         */
        const val PARALLELISM: Int = 4

        /**
         * A row whose last back-fill attempt failed inside this window is
         * skipped by [FictionDao.placeholdersToBackfill]. Long enough that
         * a permanently-unreachable source (auth-gated, 404'd) doesn't get
         * re-fetched on every Library-screen open, short enough that a
         * transient failure recovers within an hour of normal use.
         */
        const val FAILURE_COOLDOWN_MS: Long = 60 * 60 * 1000L
    }
}
