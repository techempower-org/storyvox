package `in`.jphe.storyvox.data.repository.sync

/**
 * Snapshot/apply contract for the non-sensitive preferences that the
 * `:core-sync` layer round-trips through InstantDB.
 *
 * **Why an interface in `:core-data`** — the actual DataStore lives in
 * `:app`'s [SettingsRepositoryUiImpl] (file-private extension on
 * Context, by design — that file is the single owner of the
 * `"storyvox_settings"` preferences store). The sync layer needs to
 * read/write it, but pulling DataStore into `:core-sync` directly would
 * pull a circular dep on `:app`. So `:app` implements this thin
 * interface and `:core-sync`'s `SettingsSyncer` consumes the
 * abstraction. Same pattern as [`PronunciationDictRepository`].
 *
 * **Why a map<String,String>** — the synced preference set is
 * heterogeneous (booleans, ints, floats, strings, JSON-encoded
 * sub-maps), and DataStore Preferences has no built-in
 * type-discriminated wire format. Encoding everything as a
 * `Map<String,String>` (each value's `toString()`) and letting the
 * applier handle the type-coercion is simpler than a typed sum-type
 * surface, and matches the actual storage shape — DataStore
 * Preferences keys are typed but the underlying file is just a
 * key→bytes map. The applier in `:app` knows the per-key types
 * because it owns the [Keys] object.
 *
 * **Tier 1 only** — sensitive secrets (Notion / Discord / Outline /
 * API keys / cookies / OAuth tokens) go through
 * [SecretsSnapshotSource] instead, which is end-to-end encrypted
 * before it leaves the device. The two interfaces are split so the
 * non-sensitive sync round-trip can't accidentally widen to include
 * a secret key (a bug we never want).
 *
 * Allowlist of synced keys is owned by the implementation in `:app`
 * — sync is "snapshot everything we want synced; apply everything we
 * receive." The allowlist is named here in kdoc only:
 *
 *  - Theme override, default speed/pitch, voice tuning sliders.
 *  - All per-source enabled toggles + the `pref_source_plugins_enabled_v1`
 *    JSON map.
 *  - Per-backend non-secret config (Wikipedia lang, Notion DB id,
 *    Discord coalesce-minutes, Outline host, Memory Palace host).
 *  - Reading prefs / sleep timer defaults / AI feature toggles
 *    (carry-memory-across-fictions, function-calling, chat
 *    grounding flags, etc.).
 *  - Inbox per-source mute toggles.
 *
 * Issue #916 widened the allowlist to "every user-modifiable
 * preference" — the appearance / playback / Android-Auto sliders that
 * were previously tagged per-device (animation speed, skip distance,
 * particle / skeleton / brass styles, network patience, etc.), the
 * remaining per-source toggles, the voice-bundles map, and the
 * onboarding-completion gates all sync now.
 *
 * **Explicitly excluded** (would cause confusing UX if synced):
 *  - `SIGNED_IN`, `LAST_WAS_PLAYING` — device-local auth/playback
 *    state (the InstantDB session + per-fiction positions carry the
 *    real cross-device resume; see `PlaybackPositionSyncer`).
 *  - `V0500_MILESTONE_SEEN`, `V0500_CONFETTI_SHOWN` — one-time
 *    celebration easter-egg gates; a once-per-device moment is fine.
 *  - Cache quota — genuinely device-specific (storage capacity varies
 *    between a 32 GB phone and a 256 GB tablet).
 *  - `PRONUNCIATION_DICT` — handled by its own
 *    [PronunciationDictRepository] + [PronunciationDictSyncer].
 *
 * See `SettingsSyncer.kt` in `:core-sync` for the wire path.
 */
interface SettingsSnapshotSource {

    /**
     * Snapshot the current synced-preference set as a flat
     * `key → stringified value` map. Returns only the keys the impl
     * considers "user-facing preferences" (the allowlist); device-
     * local flags and secret keys are not included.
     *
     * Implementations should return a STABLE map (same input state →
     * same output bytes) so the LWW blob's `payload` is
     * byte-identical for byte-identical settings — otherwise we'd
     * thrash the sync server with no-op pushes.
     */
    suspend fun snapshot(): Map<String, String>

    /**
     * Apply a snapshot received from a remote device. Implementations
     * must:
     *  - Tolerate unknown keys (a future device might push keys this
     *    build doesn't know about) — drop them silently.
     *  - Tolerate ill-typed values (a v2-format float pushed to a v1
     *    int-typed key) — drop the offending entry, keep going.
     *  - Treat absent keys as "leave the local value alone" (not "set
     *    to default"). The remote omitting a key means "no opinion,"
     *    not "delete locally."
     */
    suspend fun apply(snapshot: Map<String, String>)

    /**
     * Epoch-ms timestamp of the last local write to a synced key.
     * Used as the `updatedAt` for the LWW blob — without it, the
     * sync coordinator would have to stamp every push with `now`,
     * which loses ordering across devices.
     *
     * Returns 0L when the user has never written a synced
     * preference on this device (fresh install).
     */
    suspend fun lastLocalWriteAt(): Long

    /**
     * Stamp a fresh local write at [at]. Called by the repository
     * impl every time it edits a synced key, so the next sync push
     * carries an `updatedAt` later than the previous one.
     */
    suspend fun stampLocalWrite(at: Long = System.currentTimeMillis())

    // ── Field-level sync (#978) ────────────────────────────────────
    /**
     * Per-key `updatedAt` (epoch-ms) for the keys returned by
     * [snapshot] — the field-level-merge fix for #978.
     *
     * The whole-blob model carried ONE [lastLocalWriteAt] for every
     * synced key, so a sync conflict resolved at blob granularity and
     * dropped concurrent cross-device edits to *different* keys. This
     * method surfaces a stamp per key so the syncer can merge
     * key-by-key (newest-per-key wins) instead of blob-by-blob.
     *
     * Contract:
     *  - The returned map's keys MUST be a subset of [snapshot]'s keys
     *    (a stamp for a key not in the snapshot is meaningless).
     *  - A key present in [snapshot] but ABSENT here means "this build
     *    has no per-key stamp for it" — the syncer falls back to
     *    [lastLocalWriteAt] for that key. This is what makes the
     *    first run after upgrade (no per-key stamps yet) behave
     *    exactly like the old blob-level model.
     *
     * Default: empty map → every key falls back to [lastLocalWriteAt],
     * reproducing the pre-#978 behavior. Only the `:app` impl overrides
     * this; in-memory test/fake sources inherit the default and keep
     * working unchanged.
     */
    suspend fun fieldStamps(): Map<String, Long> = emptyMap()

    /**
     * Apply a snapshot received from a remote device **with** the
     * per-key `updatedAt` that the merge selected for each key, so the
     * local per-key stamp store stays in lockstep with the values.
     *
     * Same tolerance rules as [apply] for the values. [stamps] keys
     * SHOULD line up with [snapshot] keys; any stamp for a key the
     * impl can't apply is simply ignored.
     *
     * Default: ignore [stamps] and delegate to [apply], so an impl
     * that doesn't track per-key stamps (every fake/test source) keeps
     * working. The `:app` impl overrides to persist the stamps.
     */
    suspend fun applyStamped(snapshot: Map<String, String>, stamps: Map<String, Long>) = apply(snapshot)
}
