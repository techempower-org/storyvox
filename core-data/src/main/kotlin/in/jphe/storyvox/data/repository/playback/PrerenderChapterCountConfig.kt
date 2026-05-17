package `in`.jphe.storyvox.data.repository.playback

import kotlinx.coroutines.flow.Flow

/**
 * Issue #596 — user-tunable PCM-cache pre-render window size. The
 * pre-render scheduler (#86) caches the next N chapters ahead of the
 * current position; pre-fix this was hardcoded at 5 (bumped from 3 in
 * #558).
 *
 * v1 surfaces this as a chip row in Settings → Performance so users
 * on tight-disk devices can shrink the window and users on Wi-Fi-only
 * tablets can expand it.
 *
 * Chip tiers: 1, 2, 3, 5 (matches PR-F's "N+1 / N+2 / N+3 / N+5"
 * spec). Default 5 mirrors the legacy `DEFAULT_PRERENDER_CHAPTERS`
 * value so behavior is bit-identical on a fresh install.
 *
 * Per-device (NOT synced) — disk and bandwidth differ between
 * devices, so the same user wants different windows on different
 * hardware.
 */
interface PrerenderChapterCountConfig {

    /** Live flow of pre-render window size in chapters. */
    val prerenderChapterCount: Flow<Int>

    /**
     * Snapshot read. Falls back to 5 if the underlying store hasn't
     * emitted yet (matches the legacy `DEFAULT_PRERENDER_CHAPTERS`
     * constant).
     */
    suspend fun currentPrerenderChapterCount(): Int
}
