package `in`.jphe.storyvox.sync.coordinator

/**
 * Conflict-resolution primitives shared by the per-domain syncers.
 *
 * The design constraint from JP's brief: "last-write-wins for scalars; for
 * sets/lists (library/follows) use union semantics with tombstones for
 * deletes. Reading position: server keeps the maximum across devices (you
 * generally only listen forward)."
 *
 * Each of these is a pure function so they're trivially unit-testable
 * without needing Room or the network. Concrete syncers compose them with
 * their domain-specific IO.
 */
object ConflictPolicies {

    /**
     * Pick the side with the higher [Stamped.updatedAt]. Ties go to
     * [local] (we'd rather not write back to the local store when nothing
     * meaningfully changed).
     */
    fun <T> lastWriteWins(local: Stamped<T>, remote: Stamped<T>): Stamped<T> =
        if (remote.updatedAt > local.updatedAt) remote else local

    /**
     * Union two sets, suppressing entries that appear in [tombstones].
     * Tombstones win over presence — if A is in local and in
     * tombstones, the merged set won't contain A (the user deleted it
     * on some device, so it should be gone everywhere).
     *
     * **Deprecated v1 shape** — `Set<T>` tombstones make removals
     * permanent: once an id is tombstoned, a future re-add of the same
     * id is filtered forever, because the merge has no signal to tell
     * "the user re-added this" from "tombstone is stale." See [unionWithTombstoneStamps]
     * for the timestamped variant.
     *
     * Kept here only for callers / tests that still use the old shape;
     * new code should always go through [unionWithTombstoneStamps].
     */
    fun <T> unionWithTombstones(
        local: Set<T>,
        remote: Set<T>,
        tombstones: Set<T>,
    ): Set<T> = (local union remote) - tombstones

    /**
     * Same shape as [unionWithTombstones] but tombstones carry a
     * timestamp (epoch ms) — the moment the removal was first
     * recorded. An id stays excluded from the merge **only if its
     * tombstone is still within the freshness window [now] −
     * [tombstoneTtlMs]**; tombstones older than the TTL are
     * considered "forgiven" and a re-add of the id propagates
     * normally.
     *
     * **Why a TTL and not per-member add timestamps?** A proper
     * "tombstones[id] > addedAt[id]" rule requires every member to
     * carry its own add-timestamp — a schema change to the v1
     * `members: List<String>` shape that breaks wire-format
     * compatibility for every domain that uses set-sync (library,
     * follows). The TTL ships the correctness fix in PR #360 without
     * forcing a v2 schema. Per-member timestamps are a documented v2
     * follow-up.
     *
     * The TTL value is a knob; 24h is the recommended default (matches
     * the "I removed a fiction by accident, then re-added it the same
     * day, sync clobbered it" repro from argus's review). After 24h
     * the user can recover by re-adding manually and waiting one sync.
     *
     * Issue #360 finding 3 (argus). Closes the "tombstones are immortal"
     * bug — a re-add of a previously-tombstoned id now propagates on
     * the next sync (within the TTL window if a fresh add races a
     * fresh tombstone; immediately after the window otherwise).
     */
    fun <T> unionWithTombstoneStamps(
        local: Set<T>,
        remote: Set<T>,
        tombstones: Map<T, Long>,
        now: Long,
        tombstoneTtlMs: Long = DEFAULT_TOMBSTONE_TTL_MS,
    ): Set<T> {
        val fresh = tombstones
            .filterValues { stamp -> now - stamp < tombstoneTtlMs }
            .keys
        return (local union remote) - fresh
    }

    /** Default tombstone freshness window — 24 hours. After this much
     *  wall-clock time has passed since a tombstone was recorded, the
     *  tombstone no longer blocks a re-add of the same id. */
    const val DEFAULT_TOMBSTONE_TTL_MS: Long = 24L * 60L * 60L * 1000L

    /**
     * Max-of-comparable. Used for reading position — the listener
     * generally only moves forward, so the highest position seen on any
     * device is the right one to land on after a sync.
     *
     * Caveat: if a user actually rewinds a chapter intentionally, max
     * will undo their rewind on next sync. Mitigation: per-fiction
     * positions, not per-chapter — rewinding within a chapter is fine,
     * but jumping back across chapters is rare and explicitly happens
     * via the chapter picker, not the playhead. The chapter picker
     * action updates the per-fiction "current chapter" with a fresh
     * timestamp, which is LWW (not max), so an intentional rewind wins.
     */
    fun <T : Comparable<T>> maxScalar(local: T, remote: T): T =
        if (remote > local) remote else local

    /**
     * Merge two stamped scalars with the [TimestampedMaxComparator]:
     * pick the one with the later updatedAt; ties go to the higher
     * value (so a clock-skewed simultaneous update on two devices
     * doesn't lose the further-forward listener).
     */
    fun <T : Comparable<T>> maxScalarStamped(
        local: Stamped<T>,
        remote: Stamped<T>,
    ): Stamped<T> {
        val tieBreak = local.value.compareTo(remote.value)
        return when {
            remote.updatedAt > local.updatedAt -> remote
            local.updatedAt > remote.updatedAt -> local
            tieBreak >= 0 -> local
            else -> remote
        }
    }

    /**
     * Field-level LWW for a map of independently-stamped values — the
     * settings-domain fix for #978.
     *
     * The whole-blob [lastWriteWins] above picks ONE side's entire blob
     * by a single `updatedAt`, discarding every key on the loser. That
     * silently drops cross-device concurrent edits: device A reorders
     * its sources, device B flips a theme, B's blob wins on timestamp,
     * A's reorder is gone. This merge instead resolves **each key
     * independently**:
     *
     *  - **Union of keys** — a key present on only one side survives
     *    (the other side simply has "no opinion" about it; settings has
     *    no delete semantic — a cleared pref is an *omitted* key, not a
     *    tombstone, per `SettingsSnapshotSource.apply`'s kdoc). This
     *    union is the actual fix: A's local-only edit and B's local-only
     *    edit both land.
     *  - **Per-key newest-wins** — for a key on both sides, the higher
     *    `updatedAt` wins.
     *  - **Tie → local** — mirrors [lastWriteWins]'s "ties go to local"
     *    rule, applied per key instead of per blob (same anti-churn
     *    intent: don't rewrite a value that didn't meaningfully change).
     *
     * Order note: `merge(a, b)` and `merge(b, a)` agree on every key
     * except exact-`updatedAt`-tie keys, where each call keeps its own
     * `local`. That's the intended "don't churn the local store"
     * behavior, not a correctness bug — tied values are equal-priority
     * by definition.
     */
    fun <T> mergeStampedMap(
        local: Map<String, Stamped<T>>,
        remote: Map<String, Stamped<T>>,
    ): Map<String, Stamped<T>> {
        if (remote.isEmpty()) return local
        if (local.isEmpty()) return remote
        val out = LinkedHashMap<String, Stamped<T>>(local.size + remote.size)
        out.putAll(local)
        for ((key, r) in remote) {
            val l = out[key]
            // Remote-only key, or remote strictly newer → take remote.
            // Local-only key, or tie → keep local (already in `out`).
            if (l == null || r.updatedAt > l.updatedAt) out[key] = r
        }
        return out
    }
}

/**
 * A value with an updatedAt epoch-millis stamp. Two stamped values are
 * comparable for LWW purposes.
 */
data class Stamped<T>(val value: T, val updatedAt: Long)
