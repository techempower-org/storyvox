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
        val user = session.current() ?: return
        scope.launch {
            // Validate the token first — if it's no longer good, treat
            // as signed-out and let the user re-auth before we attempt
            // any push/pull (which would 401 in a loop).
            val verify = client.verifyRefreshToken(user.refreshToken)
            if (verify is SyncAuthResult.Err) {
                session.clear()
                return@launch
            }
            // Best-effort pull — if it fails the next push will fix the
            // server up. Don't block the launch on a flaky network.
            pullAll(user)
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
        val user = session.current() ?: return
        scope.launch {
            pullAll(user)
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

    /** Called by [initialize] after token verification. */
    private suspend fun pullAll(user: SignedInUser) {
        syncers.forEach { syncer ->
            val mutex = locks[syncer.name] ?: return@forEach
            mutex.withLock { runWithStatus(syncer) { it.pull(user) } }
        }
    }

    /** Wrapper that publishes status transitions for the UI. */
    private suspend fun runWithStatus(syncer: Syncer, block: suspend (Syncer) -> SyncOutcome) {
        publish(syncer.name, SyncStatus.Running)
        val outcome = runCatching { block(syncer) }
            .getOrElse { SyncOutcome.Transient(it.message ?: it::class.simpleName ?: "error") }
        val status = when (outcome) {
            is SyncOutcome.Ok -> SyncStatus.OkAt(System.currentTimeMillis(), outcome.recordsAffected)
            is SyncOutcome.Transient -> SyncStatus.Transient(outcome.message)
            is SyncOutcome.Permanent -> SyncStatus.Permanent(outcome.message)
        }
        publish(syncer.name, status)
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
