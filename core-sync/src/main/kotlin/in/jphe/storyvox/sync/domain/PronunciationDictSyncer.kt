package `in`.jphe.storyvox.sync.domain

import `in`.jphe.storyvox.data.repository.pronunciation.PronunciationDictRepository
import `in`.jphe.storyvox.data.repository.pronunciation.decodePronunciationDictJson
import `in`.jphe.storyvox.data.repository.pronunciation.encodePronunciationDictJson
import `in`.jphe.storyvox.sync.client.InstantBackend
import `in`.jphe.storyvox.sync.client.SignedInUser
import `in`.jphe.storyvox.sync.coordinator.Stamped
import `in`.jphe.storyvox.sync.coordinator.SyncOutcome
import `in`.jphe.storyvox.sync.coordinator.Syncer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Syncs the user's pronunciation dictionary (issue #135).
 *
 * The dict is already a JSON-serializable [PronunciationDict] with stable
 * encode/decode helpers, so this syncer is a thin wrapper over
 * [LwwBlobSyncer] — push the JSON blob, last-writer-wins.
 *
 * Why this is one of the early syncers: the dict is the user's most
 * meaningful TTS customisation. Re-typing 30+ pronunciation overrides
 * after a reinstall is exactly the kind of pain that motivates the sync
 * project in the first place.
 *
 * Caveat: there's no per-entry merging — adding a new entry on device A
 * while editing one on device B will lose one of the edits on next
 * sync. Per-entry merge is a v2 improvement; we accept the
 * "last-writer-wins on the whole dict" footgun for v1 because the dict
 * is rarely edited from multiple devices simultaneously.
 */
@Singleton
class PronunciationDictSyncer @Inject constructor(
    private val repo: PronunciationDictRepository,
    private val backend: InstantBackend,
) : Syncer {

    private val delegate by lazy {
        LwwBlobSyncer(
            name = DOMAIN,
            localRead = ::readLocal,
            localWrite = ::writeLocal,
            remote = BackendBlobRemote(domain = DOMAIN, backend = backend),
        )
    }

    override val name: String get() = DOMAIN
    override suspend fun push(user: SignedInUser): SyncOutcome = delegate.push(user)
    override suspend fun pull(user: SignedInUser): SyncOutcome = delegate.pull(user)

    private suspend fun readLocal(): Stamped<String>? {
        val dict = repo.current()
        if (dict.entries.isEmpty()) return null
        // Issue #778 — the per-write stamp is owned by the repository
        // and persisted across cold starts. Falling back to "now" when
        // the user has never edited the dict on this device matches
        // [SettingsSyncer.readLocal]: a 0L stamp would lose blanket to
        // every non-zero remote, which is correct for true fresh
        // installs but wrong for upgrades from a build that didn't
        // record the stamp yet (the dict already exists locally, just
        // without a recorded write time). The fallback preserves the
        // pre-#778 behaviour for that one upgrade boundary.
        val persisted = repo.lastDictWriteAt()
        val stamp = if (persisted > 0L) persisted else System.currentTimeMillis()
        return Stamped(value = encodePronunciationDictJson(dict), updatedAt = stamp)
    }

    private suspend fun writeLocal(stamped: Stamped<String>) {
        val decoded = decodePronunciationDictJson(stamped.value)
        repo.replaceAll(decoded)
        // [replaceAll] above stamps "now" via the repository, which
        // would advance our local clock past the remote's `updatedAt`
        // and make us "win" the next push spuriously. Re-stamp with the
        // merged-blob's updatedAt so a subsequent pull-then-push cycle
        // is byte-stable — same shape as [SettingsSyncer.writeLocal].
        repo.stampDictWrite(stamped.updatedAt)
    }

    companion object {
        const val DOMAIN: String = "pronunciation"
    }
}
