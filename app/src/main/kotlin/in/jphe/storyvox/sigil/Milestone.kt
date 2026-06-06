package `in`.jphe.storyvox.sigil

import `in`.jphe.storyvox.BuildConfig

/**
 * v0.5.00 milestone gate — the "graduation party" version.
 *
 * storyvox crossed from 0.4.x into 0.5.00 with a wave of UX work
 * (Voice Library, debug overlay, chapter auto-advance, settings
 * overhaul, ~13 Reeve fixes). The milestone has two paired surfaces:
 *  - a one-time "thank you" dialog on first launch of a qualifying
 *    build (gated by `KEY_V0500_MILESTONE_SEEN` in DataStore),
 *  - a one-time confetti easter-egg on the first natural chapter
 *    completion after install (gated by `KEY_V0500_CONFETTI_SHOWN`).
 *
 * Both surfaces are inert on builds *below* 0.5.00 — the version
 * comparison short-circuits and the flags never flip. A future
 * 0.6.00 / 1.0.0 milestone gets its own helper + its own DataStore
 * keys; we don't reuse this gate across versions because the copy
 * is hand-tuned per release.
 *
 * Version parsing is deliberately simple: split on `.`, parse the
 * first three integer components, default missing tails to 0. Any
 * parse failure (dev build sigil sneaks something weird in,
 * pre-release suffix appears) returns false — fail-closed so the
 * dialog never accidentally pops on a non-release artifact.
 */
object Milestone {

    /** First version that qualifies for the v0.5.00 celebration. */
    private val V0500 = Triple(0, 5, 0)

    /** Last version that qualifies for the v0.5.00 celebration. The
     *  dialog is a "we crossed 0.5.0 together" moment — users who
     *  install fresh on later builds didn't experience the crossing,
     *  so the celebration window closes after v0.5.5. (#439 reported
     *  the dialog firing on a fresh install of v0.5.36; the headline
     *  still said "storyvox 0.5.00", which read as a dead placeholder.
     *  Closing the window means fresh installers past v0.5.5 silently
     *  never see it; users who already dismissed it stay dismissed.) */
    private val V0500_WINDOW_END = Triple(0, 5, 5)

    /** True when the current build's [BuildConfig.VERSION_NAME] falls
     *  inside the v0.5.00 celebration window. Cached for the process
     *  lifetime — VERSION_NAME doesn't change at runtime. */
    val isV0500OrLater: Boolean by lazy {
        qualifies(BuildConfig.VERSION_NAME)
    }

    /** Visible for testing — parse + compare without touching
     *  BuildConfig. Returns false on any parse failure OR when the
     *  build is past the celebration window. */
    internal fun qualifies(versionName: String): Boolean {
        val parts = versionName.split('.', '-', '+')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return false
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
        val triple = Triple(major, minor, patch)
        return compareTriple(triple, V0500) >= 0 &&
            compareTriple(triple, V0500_WINDOW_END) <= 0
    }

    // ── v1.1.0 "read it your way" milestone (Luna) ─────────────────
    /**
     * Second milestone gate — the "read it your way / bring your own"
     * release. v1.1.0 landed a wave of reader work (paragraph nav,
     * per-word highlight, dyslexia typography, reading themes, focused
     * reading, in-text search) plus a fleet of new import sources.
     *
     * Per the [V0500] kdoc above, this is a *separate* helper with its
     * own DataStore keys (`pref_v110_milestone_seen` /
     * `pref_v110_confetti_shown`) and hand-tuned copy — we never reuse
     * the v0.5.00 gate across versions.
     *
     * Window is **[1.1.0, 1.2.0)** — closed-open. 1.2.0 itself is OUT:
     * a fresh install on 1.2.x never lived through the 1.1 crossing,
     * so the "Candela 1.1 — read it your way" dialog would read as a
     * dead placeholder (the same reasoning #439 applied to the v0.5.00
     * window). Same fail-closed parse: any unparseable input → false.
     */
    private val V110 = Triple(1, 1, 0)

    /** First version that no longer qualifies (exclusive upper bound). */
    private val V110_WINDOW_END_EXCLUSIVE = Triple(1, 2, 0)

    /** True when the current build's [BuildConfig.VERSION_NAME] falls
     *  inside the v1.1.0 celebration window. Process-lifetime constant. */
    val isV110OrLater: Boolean by lazy {
        qualifiesV110(BuildConfig.VERSION_NAME)
    }

    /** Visible for testing — parse + compare without touching
     *  BuildConfig. Returns false on any parse failure OR outside the
     *  half-open [1.1.0, 1.2.0) window. */
    internal fun qualifiesV110(versionName: String): Boolean {
        val parts = versionName.split('.', '-', '+')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return false
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
        val triple = Triple(major, minor, patch)
        return compareTriple(triple, V110) >= 0 &&
            compareTriple(triple, V110_WINDOW_END_EXCLUSIVE) < 0
    }

    private fun compareTriple(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>): Int {
        if (a.first != b.first) return a.first.compareTo(b.first)
        if (a.second != b.second) return a.second.compareTo(b.second)
        return a.third.compareTo(b.third)
    }
}
