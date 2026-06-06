package `in`.jphe.storyvox.ui.component

/**
 * Luna (v1.1.0) — book-completion streak celebration tiers.
 *
 * Finishing a book ticks a device-local DataStore counter. We light a
 * [LightMotes] celebration + an "N books lit" badge only at a sparse
 * set of tiers so the moment stays special — every-book confetti would
 * cheapen it. The tiers are deliberately front-loaded (1, 5) then
 * spaced out (10, 25): the first book is worth marking, and the rhythm
 * widens as the habit forms.
 *
 * The "did this increment cross a tier" decision is pure logic so it's
 * JVM-testable (the counter itself lives behind the repo seam). See
 * [BookStreakTest].
 */
val bookStreakTiers: List<Int> = listOf(1, 5, 10, 25)

/**
 * Returns the highest streak tier crossed by moving the completed-book
 * count from [previous] to [new], or null if no tier was crossed.
 *
 * The range is half-open `(previous, new]`: a tier counts as "crossed"
 * when the new count reaches or passes it AND the old count hadn't yet.
 * When a single delta vaults multiple tiers (e.g. a cross-device sync
 * backfill), we return only the *highest* tier reached — one
 * celebration for the top milestone, never a backlog of three. A
 * no-op or backward delta returns null.
 */
fun bookStreakMilestoneCrossed(previous: Int, new: Int): Int? {
    if (new <= previous) return null
    return bookStreakTiers.lastOrNull { tier -> tier in (previous + 1)..new }
}
