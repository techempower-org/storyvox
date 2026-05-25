package `in`.jphe.storyvox.data.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import `in`.jphe.storyvox.data.db.dao.ChapterDao
import `in`.jphe.storyvox.data.db.dao.FictionDao
import `in`.jphe.storyvox.data.db.entity.ChapterDownloadState
import `in`.jphe.storyvox.data.repository.AuthRepository
import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.WebViewFetcher
import `in`.jphe.storyvox.data.source.model.FictionResult
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException

/**
 * Downloads a single chapter body. Run with `OneTimeWorkRequest`; orchestration
 * lives on [`in`.jphe.storyvox.data.repository.ChapterRepository.queueChapterDownload].
 *
 * Retry semantics:
 *  - [FictionResult.RateLimited]/[FictionResult.NetworkError] → `Result.retry()`
 *    with exponential backoff (configured by the request).
 *  - [FictionResult.NotFound]/[FictionResult.AuthRequired] → `Result.failure()`
 *    (terminal). On `AuthRequired`, the app module is responsible for posting
 *    a "Sign in to continue downloading" notification observing
 *    [AuthRepository.sessionState].
 *  - [FictionResult.Cloudflare] → escalate to [WebViewFetcher] (when bound),
 *    parse via the same source code path, then `Result.success()`. If the
 *    fetcher isn't available, `retry()`.
 *  - [FictionResult.Success] → write body, mark `DOWNLOADED`, `Result.success()`.
 *
 * # Stuck-state recovery (issue #705)
 *
 * Before setting this chapter to `DOWNLOADING` the worker calls
 * [ChapterDao.reapStuckDownloads] to flip any sibling rows that have been
 * stuck at `DOWNLOADING` for longer than [STUCK_CUTOFF_MS] back to
 * `QUEUED`. The whole `source.chapter()` / `setBody` path is then wrapped
 * in a `try` that catches any uncaught throwable (parser bugs, OOM,
 * Room constraint violations) and writes `FAILED` with a `crash` tag so
 * the row never sits at `DOWNLOADING` forever. [CancellationException]
 * is rethrown so coroutine cancellation propagates normally — the
 * reaper on the next worker run will pick the row up because the
 * `lastDownloadAttemptAt` timestamp from the `setDownloadState`
 * call already aged out.
 */
@HiltWorker
class ChapterDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val sources: Map<String, @JvmSuppressWildcards FictionSource>,
    private val chapterDao: ChapterDao,
    private val fictionDao: FictionDao,
    private val auth: AuthRepository,
    private val webView: WebViewFetcher? = null,
) : CoroutineWorker(appContext, params) {

    private fun sourceFor(sourceId: String?): FictionSource =
        sourceId?.let { sources[it] }
            ?: sources.values.singleOrNull()
            ?: error("No FictionSource for id=$sourceId; bound: ${sources.keys}")

    override suspend fun doWork(): Result {
        val fictionId = inputData.getString(KEY_FICTION_ID) ?: return Result.failure(
            Data.Builder().putString(KEY_RESULT, RESULT_BAD_INPUT).build(),
        )
        val chapterId = inputData.getString(KEY_CHAPTER_ID) ?: return Result.failure(
            Data.Builder().putString(KEY_RESULT, RESULT_BAD_INPUT).build(),
        )
        // Issue #808 — cap retries so zombie URLs (chapter pulled, source
        // permanently rate-limiting us, etc.) eventually terminate instead
        // of looping forever on exponential backoff. The reaper handles
        // the DOWNLOADING-state side; this handles the worker side.
        if (runAttemptCount > MAX_RUN_ATTEMPTS) {
            chapterDao.setDownloadState(
                chapterId,
                ChapterDownloadState.FAILED,
                System.currentTimeMillis(),
                "retry-exhausted",
            )
            return Result.failure(output(RESULT_RETRY_EXHAUSTED))
        }
        val now = System.currentTimeMillis()
        val source = sourceFor(fictionDao.get(fictionId)?.sourceId)

        // Issue #705 — Reap sibling rows stuck in DOWNLOADING from a
        // crashed/killed previous worker run before we claim a new one.
        // Cheap (indexed on downloadState) and runs once per scheduled
        // download, which is the natural cadence — if downloads are
        // happening at all, the reaper is making progress.
        chapterDao.reapStuckDownloads(now = now, cutoff = now - STUCK_CUTOFF_MS)

        chapterDao.setDownloadState(chapterId, ChapterDownloadState.DOWNLOADING, now, null)

        return try {
            when (val result = source.chapter(fictionId, chapterId)) {
                is FictionResult.Success -> {
                    val content = result.value
                    val checksum = sha256(content.htmlBody)
                    chapterDao.setBody(
                        id = chapterId,
                        html = content.htmlBody,
                        plain = content.plainBody,
                        checksum = checksum,
                        notesAuthor = content.notesAuthor,
                        notesAuthorPosition = content.notesAuthorPosition?.name,
                        now = System.currentTimeMillis(),
                        audioUrl = content.audioUrl,
                    )
                    Result.success(output(RESULT_OK))
                }

                is FictionResult.NotFound -> {
                    chapterDao.setDownloadState(chapterId, ChapterDownloadState.FAILED, now, "404")
                    Result.failure(output(RESULT_NOT_FOUND))
                }

                is FictionResult.AuthRequired -> {
                    chapterDao.setDownloadState(chapterId, ChapterDownloadState.FAILED, now, "auth")
                    Result.failure(output(RESULT_AUTH))
                }

                is FictionResult.Cloudflare -> {
                    val fetcher = webView
                    if (fetcher == null) {
                        chapterDao.setDownloadState(chapterId, ChapterDownloadState.QUEUED, now, "cf-no-fetcher")
                        return Result.retry()
                    }
                    // The source's `chapter()` already failed at the HTTP layer.
                    // We escalate by fetching the URL via WebView and then asking
                    // the source to re-parse it. To avoid leaking a parse-from-html
                    // method into the FictionSource interface we instead just
                    // retry the original suspend call — Oneiros's RR impl is
                    // expected to consult AuthRepository's cookie + WebViewFetcher
                    // on retry. If still Cloudflare, we give up for this run.
                    when (val retry = source.chapter(fictionId, chapterId)) {
                        is FictionResult.Success -> {
                            val checksum = sha256(retry.value.htmlBody)
                            chapterDao.setBody(
                                id = chapterId,
                                html = retry.value.htmlBody,
                                plain = retry.value.plainBody,
                                checksum = checksum,
                                notesAuthor = retry.value.notesAuthor,
                                notesAuthorPosition = retry.value.notesAuthorPosition?.name,
                                now = System.currentTimeMillis(),
                                audioUrl = retry.value.audioUrl,
                            )
                            Result.success(output(RESULT_OK))
                        }
                        is FictionResult.Failure -> {
                            chapterDao.setDownloadState(chapterId, ChapterDownloadState.FAILED, now, "cf")
                            Result.retry()
                        }
                    }
                }

                is FictionResult.RateLimited -> {
                    chapterDao.setDownloadState(chapterId, ChapterDownloadState.QUEUED, now, "rate")
                    Result.retry()
                }

                is FictionResult.NetworkError -> {
                    chapterDao.setDownloadState(chapterId, ChapterDownloadState.QUEUED, now, "net")
                    Result.retry()
                }
            }
        } catch (cancel: CancellationException) {
            // Don't swallow cancellation — the coroutines machinery uses
            // this to propagate WorkManager's `stop()` (system kills the
            // worker mid-flight) and structured concurrency. The reaper
            // at the top of the NEXT doWork() pass will flip this row
            // back to QUEUED after STUCK_CUTOFF_MS — that's the recovery
            // path for the cancel-mid-flight case.
            throw cancel
        } catch (t: Throwable) {
            // Issue #705 — any other uncaught throwable (parser bug, OOM
            // on a giant body, Room constraint violation, IOException
            // that didn't get mapped to FictionResult.NetworkError) used
            // to leave the row stuck at DOWNLOADING forever. Flip it to
            // FAILED with a `crash` tag so the user (and the reaper) can
            // see something happened, and return retry() so WorkManager's
            // exponential backoff gets a shot at it.
            chapterDao.setDownloadState(
                chapterId,
                ChapterDownloadState.FAILED,
                System.currentTimeMillis(),
                "crash: ${t.javaClass.simpleName}",
            )
            Result.retry()
        }
    }

    private fun output(tag: String): Data =
        Data.Builder().putString(KEY_RESULT, tag).build()

    private fun sha256(s: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val TAG = "download:chapter"

        const val KEY_FICTION_ID = "fictionId"
        const val KEY_CHAPTER_ID = "chapterId"
        const val KEY_REQUIRE_UNMETERED = "requireUnmetered"

        const val KEY_RESULT = "result"

        const val RESULT_OK = "ok"
        const val RESULT_NOT_FOUND = "404"
        const val RESULT_AUTH = "auth"
        const val RESULT_BAD_INPUT = "bad-input"
        const val RESULT_RETRY_EXHAUSTED = "retry-exhausted"

        /** Issue #808 — terminate after this many WorkManager retry attempts. */
        const val MAX_RUN_ATTEMPTS: Int = 5

        /**
         * Issue #705 — DOWNLOADING rows older than this are flipped back
         * to QUEUED by [ChapterDao.reapStuckDownloads] on the next
         * worker run. 5 minutes is well above the slowest observed
         * `source.chapter()` call (RR cold-cache page-walk parsers
         * land in <30s) so we don't race a still-running download.
         */
        const val STUCK_CUTOFF_MS: Long = 5 * 60 * 1000L

        fun uniqueName(chapterId: String): String = "download:$chapterId"
    }
}
