package `in`.jphe.storyvox.data.repository.pronunciation

import kotlinx.coroutines.flow.Flow

/**
 * Read/write contract for the user's pronunciation dictionary (issue
 * #135). Phase 1 owns only the global dict; per-fiction overrides are
 * deferred to phase 2.
 *
 * Implementation lives in `:app`'s `SettingsRepositoryUiImpl`,
 * matching how [`PlaybackBufferConfig`] and [`PlaybackModeConfig`]
 * are handled — one DataStore, multiple contracts. `core-playback`
 * pulls this contract from DI so the engine pipeline can apply
 * substitutions without depending on the feature/UI layer.
 */
interface PronunciationDictRepository {

    /** Live flow of the global dictionary. Re-emits on every save.
     *  Default before first emission is [PronunciationDict.EMPTY]. */
    val dict: Flow<PronunciationDict>

    /** Snapshot read for callers that need a single value without
     *  subscribing — e.g. the engine pipeline pulling a fresh dict
     *  on chapter start. Returns [PronunciationDict.EMPTY] if the
     *  store hasn't emitted yet. */
    suspend fun current(): PronunciationDict

    /** Append [entry] to the global dictionary. No de-duplication —
     *  the user is allowed to have two entries with the same pattern
     *  for whatever reason; whichever is later wins on application
     *  (later passes see earlier output). */
    suspend fun add(entry: PronunciationEntry)

    /** Replace the entry at [index] with [entry]. Index out of
     *  bounds is silently ignored — the editor is the only writer
     *  and may race with a concurrent delete from the same screen,
     *  which we'd rather lose than crash on. */
    suspend fun update(index: Int, entry: PronunciationEntry)

    /** Remove the entry at [index]. Out-of-bounds is silently
     *  ignored, same reasoning as [update]. */
    suspend fun delete(index: Int)

    /** Bulk replace — used by tests, future import/export, and the
     *  reset path. */
    suspend fun replaceAll(dict: PronunciationDict)

    /**
     * Epoch-ms timestamp of the last local edit to the dictionary.
     * Used as the `updatedAt` for [PronunciationDictSyncer]'s LWW blob
     * push — without persisting this across cold starts, a stale local
     * dict gets re-stamped "now" on every process restart and wins
     * blanket against newer remotes, silently overwriting cross-device
     * edits (issue #778).
     *
     * Returns 0L when the user has never edited the dictionary on this
     * device (fresh install). The syncer's `readLocal` treats 0L as
     * "fall back to `System.currentTimeMillis()`" — the same shape used
     * by [`SettingsSnapshotSource.lastLocalWriteAt`].
     *
     * Named with a `dict` suffix to avoid colliding with the identically-
     * shaped methods on [`SettingsSnapshotSource`] that the same `:app`
     * impl also implements — Kotlin can't disambiguate two interface
     * methods with the same JVM signature on one class.
     */
    suspend fun lastDictWriteAt(): Long

    /**
     * Stamp a fresh local write at [at]. Mutators in this contract
     * ([add], [update], [delete], [replaceAll]) must call this on every
     * successful edit so the next sync push carries a strictly-increasing
     * `updatedAt`. Also called by [PronunciationDictSyncer.writeLocal]
     * on a remote-pull so the local stamp adopts the merged blob's
     * `updatedAt` (otherwise a pull → push round-trip would advance the
     * stamp past the remote and break LWW ordering).
     */
    suspend fun stampDictWrite(at: Long = System.currentTimeMillis())
}
