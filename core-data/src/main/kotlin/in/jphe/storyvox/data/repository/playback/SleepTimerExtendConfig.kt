package `in`.jphe.storyvox.data.repository.playback

import kotlinx.coroutines.flow.Flow

/**
 * Issue #595 — user-tunable sleep-timer shake-to-extend duration.
 *
 * The shake-to-extend gesture (#150) currently hardcodes the extension
 * at 15 minutes. v1 surfaces this as a chip row in
 * Settings → Voice & Playback so listeners who fall asleep faster /
 * slower than that default can pick a window that matches.
 *
 * Surfaced as its own contract (mirroring [PlaybackSkipConfig] /
 * [PlaybackBufferConfig]) so `:core-playback`'s
 * `StoryvoxPlaybackService` can read the user's pref without taking
 * a feature-layer dep.
 *
 * Per-device (NOT synced) — a bedroom tablet and a commuting phone
 * have different ergonomic targets.
 *
 * Implementations read from the same DataStore that hosts the rest of
 * [UiSettings]. The `:app` module's `SettingsRepositoryUiImpl`
 * implements this alongside the existing contracts; one DataStore,
 * many contracts.
 */
interface SleepTimerExtendConfig {

    /** Live flow of extend duration in minutes. */
    val shakeExtendMinutes: Flow<Int>

    /**
     * Snapshot read for callers that need a single value without
     * subscribing. Implementations should return the most-recent
     * value, falling back to 15 if the underlying store hasn't
     * emitted yet.
     */
    suspend fun currentShakeExtendMinutes(): Int
}
