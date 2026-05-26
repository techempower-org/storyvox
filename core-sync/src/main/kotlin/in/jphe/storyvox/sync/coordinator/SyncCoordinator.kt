package `in`.jphe.storyvox.sync.coordinator

import `in`.jphe.storyvox.sync.client.InstantClient
import `in`.jphe.storyvox.sync.client.InstantSession
import `in`.jphe.storyvox.sync.client.SignedInUser
import `in`.jphe.storyvox.sync.client.SyncAuthResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Orchestrates per-domain syncers.
 *
 * Lifecycle:
 *  - On app process start, if a refresh token is in [InstantSession], we
 *    [verifyAndResume] — validate the token, then run a pull pass so
 *    in-memory state catches up before any UI subscribes.
 *  - On sign-in (handled in the [`signInWithMagicCode`] UI flow), we run
 *    a push pass first (the "migration" — every local row that exists
 *    today gets uploaded so a fresh install on another device can pull
 *    it back).
 *  - On sign-out, we don't auto-wipe local state — the user signs out
 *    to disable sync, not to nuke their on-device library. The
 *    refresh token is cleared and the syncers go quiet.
 *
 * Concurrency model: a single [SupervisorJob] scope, with one mutex per
 * domain (so a push and pull for the same domain don't race) but
 * different domains run in parallel. Failures in one syncer don't
 * cascade to others — that's the whole point of SupervisorJob.
 *
 * v1 push/pull is request-driven, not real-time. The websocket-driven
 * push-on-mutation path is the v2 work item — for v1 the app calls
 * [requestPush] from its existing "I just wrote" seams (the
 * repositories), and pull happens on sign-in + app cold start.
 */
@Singleton
class SyncCoordinator @Inject constructor(
    private val client: InstantClient,
    private val session: InstantSession,
    private val syncers: Set<@JvmSuppressWildcards Syncer>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val locks = syncers.associate { it.name to Mutex() }

    private val _status = MutableStateFlow<Map<String, SyncStatus>>(
        syncers.associate { it.name to SyncStatus.Idle },
    )
    val status: StateFlow<Map<String, SyncStatus>> = _status.asStateFlow()

    /** Called at app start once Hilt has wired everything. */
    fun initialize() {
        val user = session.current() ?: run {
            android.util.Log.d(TAG, "initialize: no signed-in user, skipping")
            return
        }
        android.util.Log.i(TAG, "initialize: verifying token for user=${user.userId.take(8)}…")
        scope.launch {
            // Validate the token first — if it's no longer good, treat
            // as signed-out and let the user re-auth before we attempt
            // any push/pull (which would 401 in a loop).
            val verify = client.verifyRefreshToken(user.refreshToken)
            if (verify is SyncAuthResult.Err) {
                android.util.Log.w(TAG, "initialize: token invalid, clearing session")
                session.clear()
                return@launch
            }
            android.util.Log.i(TAG, "initialize: token valid, starting pullAll (${syncers.size} syncers)")
            // Best-effort pull — if it fails the next push will fix the
            // server up. Don't block the launch on a flaky network.
            pullAll(user)
            android.util.Log.i(TAG, "initialize: pullAll complete")
        }
    }

    /**
     * Request a push for one domain. Called by repositories on write
     * (or by the coordinator's own pull-then-rewrite path).
     *
     * Returns immediately; the actual push runs on the coordinator's
     * scope. Status flow surfaces progress.
     */
    fun requestPush(domainName: String) {
        val user = session.current() ?: return
        val syncer = syncers.firstOrNull { it.name == domainName } ?: return
        val mutex = locks[domainName] ?: return
        scope.launch {
            mutex.withLock { runWithStatus(syncer) { it.push(user) } }
        }
    }

    /** Manual sync trigger from Settings → Account. Pull then push. */
    fun syncNow() {
        val user = session.current() ?: run {
            android.util.Log.w(TAG, "syncNow: no signed-in user")
            return
        }
        android.util.Log.i(TAG, "syncNow: starting pull+push for user=${user.userId.take(8)}…")
        scope.launch {
            pullAll(user)
            android.util.Log.i(TAG, "syncNow: pullAll done, starting pushAll")
            requestPushAll()
        }
    }

    /** Request a push for every domain. Used by the post-sign-in
     *  migration step. */
    fun requestPushAll() {
        val user = session.current() ?: return
        syncers.forEach { requestPush(it.name) }
        // Note: each domain runs under its own mutex, so push-all is
        // concurrent across domains and serialised within a domain.
        @Suppress("UNUSED_EXPRESSION") user // tame static analyser
    }

    /** Called by [initialize] after token verification.
     *
     *  Ordering matters: library + follows create Fiction placeholder rows
     *  that other syncers (positions, bookmarks) depend on via FK. We run
     *  those first, then everything else. */
    private suspend fun pullAll(user: SignedInUser) {
        val ordered = syncers.sortedBy { if (it.name in FK_ROOT_DOMAINS) 0 else 1 }
        ordered.forEachIndexed { i, syncer ->
            android.util.Log.d(TAG, "pullAll: [${i+1}/${ordered.size}] ${syncer.name}")
            val mutex = locks[syncer.name] ?: return@forEachIndexed
            mutex.withLock { runWithStatus(syncer) { it.pull(user) } }
        }
    }

    /**
     * Wrapper that publishes status transitions for the UI.
     *
     * Issue #779 — `SyncOutcome.Transient` documents that the coordinator
     * retries on transient failures (see [SyncOutcome.Transient] kdoc and
     * [Syncer.push] kdoc that promises idempotency under coordinator
     * retry). Before this change the block was called exactly once and a
     * Transient outcome was published verbatim, so a cold-start `pullAll`
     * on a flaky network left every domain stuck until the user manually
     * hit "Sync now."
     *
     * Retry policy: bounded in-coroutine retry with exponential backoff
     * — up to [RETRY_BACKOFFS_MS]`.size + 1` attempts (1 initial + N
     * retries), with the per-attempt sleep coming from
     * [RETRY_BACKOFFS_MS]. We only retry on `Transient` (or thrown
     * exception, which we map to `Transient`); `Permanent` and `Ok`
     * short-circuit immediately. The whole loop runs inside the
     * per-domain mutex (same as before), so per-domain serialisation is
     * preserved — concurrent local writes on the same domain queue
     * behind the retry, which is exactly the idempotency contract the
     * syncer kdoc already requires.
     *
     * This handles the "device woke up on a flaky connection" case; the
     * "device was offline for an hour" case is left for a future
     * WorkManager-backed scheduler (option 2 in #779).
     */
    private suspend fun runWithStatus(syncer: Syncer, block: suspend (Syncer) -> SyncOutcome) {
        publish(syncer.name, SyncStatus.Running)
        val t0 = System.currentTimeMillis()
        val result = runWithRetry(syncer.name, RETRY_BACKOFFS_MS) { block(syncer) }
        val elapsed = System.currentTimeMillis() - t0
        val status = when (val outcome = result.outcome) {
            is SyncOutcome.Ok -> {
                android.util.Log.i(TAG, "${syncer.name}: OK (${outcome.recordsAffected} records, ${elapsed}ms, attempts=${result.attempts})")
                SyncStatus.OkAt(System.currentTimeMillis(), outcome.recordsAffected)
            }
            is SyncOutcome.Transient -> {
                android.util.Log.w(TAG, "${syncer.name}: TRANSIENT — ${outcome.message} (${elapsed}ms, attempts=${result.attempts})")
                SyncStatus.Transient(outcome.message)
            }
            is SyncOutcome.Permanent -> {
                android.util.Log.e(TAG, "${syncer.name}: PERMANENT — ${outcome.message} (${elapsed}ms, attempts=${result.attempts})")
                SyncStatus.Permanent(outcome.message)
            }
        }
        publish(syncer.name, status)
    }

    private companion object {
        private const val TAG = "SyncCoordinator"
        private val FK_ROOT_DOMAINS = setOf("library", "follows")

        /**
         * Retry backoff schedule (ms) for [SyncOutcome.Transient] failures.
         * Length = max retries. Total worst-case wall time = sum of entries
         * (~13s) plus per-attempt work. Picked to ride out a typical DNS
         * blip / TLS handshake retry without making cold-start feel hung.
         */
        private val RETRY_BACKOFFS_MS = longArrayOf(1_000L, 3_000L, 9_000L)
    }

    private fun publish(domain: String, status: SyncStatus) {
        // Atomic CAS-based read-modify-write so concurrent syncers
        // running on different domains can't lose each other's updates.
        // See #704 for the lost-update repro.
        _status.update { it + (domain to status) }
    }
}

/** UI-facing status for a single syncer. */
sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Running : SyncStatus
    data class OkAt(val at: Long, val records: Int) : SyncStatus
    data class Transient(val message: String) : SyncStatus
    data class Permanent(val message: String) : SyncStatus
}

/** Return value of [runWithRetry]: the final [SyncOutcome] plus how many
 *  total attempts (1 initial + N retries) were actually executed. */
internal data class RetryResult(val outcome: SyncOutcome, val attempts: Int)

/**
 * Bounded-retry wrapper for a single syncer call. Issue #779.
 *
 * - One initial attempt, then up to `backoffsMs.size` retries.
 * - Retries fire only on [SyncOutcome.Transient] or thrown exceptions
 *   (mapped to Transient with the exception message).
 * - [SyncOutcome.Ok] and [SyncOutcome.Permanent] short-circuit the loop.
 * - Between attempts, `delay(backoffsMs[i])` is called — under
 *   `kotlinx.coroutines.test.runTest` virtual time this auto-advances,
 *   so tests don't wait real seconds.
 *
 * Top-level + `internal` rather than a method on [SyncCoordinator] so the
 * loop is unit-testable without needing to stand up the coordinator's
 * Hilt-injected deps (InstantClient, InstantSession, SharedPreferences).
 * The [name] parameter is just for log breadcrumbs.
 */
internal suspend fun runWithRetry(
    name: String,
    backoffsMs: LongArray,
    block: suspend () -> SyncOutcome,
): RetryResult {
    var outcome: SyncOutcome = runAttempt(name, block)
    var attempts = 1
    while (outcome is SyncOutcome.Transient && attempts <= backoffsMs.size) {
        val backoff = backoffsMs[attempts - 1]
        android.util.Log.w(
            "SyncCoordinator",
            "$name: transient (\"${outcome.message}\"), retry $attempts/${backoffsMs.size} in ${backoff}ms",
        )
        delay(backoff)
        outcome = runAttempt(name, block)
        attempts++
    }
    return RetryResult(outcome, attempts)
}

private suspend fun runAttempt(name: String, block: suspend () -> SyncOutcome): SyncOutcome =
    runCatching { block() }
        .getOrElse { e ->
            android.util.Log.e("SyncCoordinator", "runAttempt: $name threw", e)
            SyncOutcome.Transient(e.message ?: e::class.simpleName ?: "error")
        }
