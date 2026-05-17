package `in`.jphe.storyvox.data.repository.playback

import kotlinx.coroutines.flow.Flow

/**
 * Issue #598 — user-tunable Android Auto bucket size. Each category
 * tile in Auto's browse tree (Library / Follows / Recent / New) caps
 * its children at this many items. Pre-fix this was hardcoded at 6
 * — Google's HMI guideline default.
 *
 * v1 surfaces a chip row (4 / 6 / 8 / 12) in Settings → Advanced so
 * users with large libraries on Android Auto can widen the bucket
 * (subject to Auto's UX restrictions on visible tile count) and
 * users on small screens can keep it tight.
 *
 * Per-device (NOT synced) — the Auto experience is bound to a
 * specific car/head-unit pairing, not a user-wide setting.
 */
interface AutoBrowserConfig {

    /** Live flow of bucket size. */
    val itemsPerCategory: Flow<Int>

    /**
     * Snapshot read. Falls back to 6 (the HMI default) if the
     * underlying store hasn't emitted yet.
     */
    suspend fun currentItemsPerCategory(): Int
}
