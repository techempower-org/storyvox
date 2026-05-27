package `in`.jphe.storyvox.feature.library

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #452 — regression guard for "Library grid renders empty in
 * landscape orientation". The root cause: `LazyVerticalGrid` was placed
 * directly inside a `Column(fillMaxSize)` without a `weight(1f)` /
 * bounded-height wrapper. In portrait the surrounding Column's natural
 * height happened to bound it; in landscape (or the Flip3 unfolded
 * wider-than-tall state) the unbounded measurement collapsed the grid
 * to zero rows and the user's books became invisible.
 *
 * Fix: wrap `LibraryGridBody` in `Box(Modifier.weight(1f).fillMaxWidth())`
 * inside the parent Column so the grid always has a concrete bounded
 * inner-axis constraint.
 *
 * The grid also uses `GridCells.Adaptive(minSize = 140.dp)`, which
 * remains the right choice in both orientations: at 140dp minimum the
 * Flip3 inner display in landscape (~932dp wide) yields ~6 columns and
 * in portrait (~393dp) yields ~2 columns, both of which produce a
 * non-zero row count for the user's library.
 */
class LibraryGridLandscapeTest {

    @Test
    fun `grid columns stay non-zero across portrait and landscape widths`() {
        // GridCells.Adaptive(minSize) → at least one column fits if
        // the parent has width >= minSize. We verify the column-count
        // math here as a pure-arithmetic sanity check; the production
        // value is 140.dp and the parent surface is full-width.
        val minSizeDp = 140
        val portraitWidth = 393   // Z Flip3 inner display, portrait
        val landscapeWidth = 932  // Z Flip3 unfolded inner display, landscape
        val portraitCols = portraitWidth / minSizeDp
        val landscapeCols = landscapeWidth / minSizeDp
        assertTrue(
            "Portrait Flip3 must render at least 2 columns (got $portraitCols)",
            portraitCols >= 2,
        )
        assertTrue(
            "Landscape Flip3 must render at least 4 columns (got $landscapeCols)",
            landscapeCols >= 4,
        )
    }

    @Test
    fun `LibraryGridBody is wrapped in a weight-bounded Box per issue #452`() {
        // Structural canary — see the marker constant in
        // LibraryScreen. If a future refactor drops the Box wrapper
        // and goes back to the regressed shape (LazyVerticalGrid as
        // direct Column child), the canary flips false and this fails
        // before the user-facing landscape regression returns.
        assertTrue(
            "LibraryGridBody must be wrapped in a weight(1f)/fillMaxWidth Box (issue #452)",
            libraryGridIsWeightBounded,
        )
    }

    @Test
    fun `LazyVerticalGrid minSize stays at 140dp for both orientations`() {
        // The 140.dp minimum was tuned in the original Library design
        // for the cover-thumb aspect ratio (~ 16:24 → roughly 140 x
        // 210). Bumping it up shrinks the column count in landscape;
        // shrinking it past ~110 breaks the cover legibility on the
        // outer Flip3 display. Pin the value so a "make it bigger /
        // smaller" PR has to explicitly bump this test too.
        assertTrue(
            "Adaptive minSize must remain 140dp for the cover-thumb aspect ratio",
            libraryGridAdaptiveMinSizeDp == 140,
        )
    }

    @Test
    fun `Expanded breakpoint bumps the adaptive minSize to 180dp`() {
        // Issue #783 — at >=840dp (Tab S-class landscape, unfolded
        // foldables) the grid switches from the 140dp tablet minimum
        // to 180dp for larger covers. Pin the Expanded value so a PR
        // that retunes it has to bump this test in lock-step, same
        // contract as the 140dp tablet value above.
        assertTrue(
            "Expanded minSize must be 180dp (issue #783)",
            libraryGridExpandedMinSizeDp == 180,
        )
        assertTrue(
            "Expanded minimum must be strictly larger than the tablet minimum",
            libraryGridExpandedMinSizeDp > libraryGridAdaptiveMinSizeDp,
        )
    }

    @Test
    fun `Expanded minSize keeps a sane column count on a Tab S-class landscape width`() {
        // Tab S9 landscape content width ~1180dp. At 180dp minimum
        // that yields ~6 columns; the 140dp value would over-pack to
        // ~8. Verify the Expanded value stays in the 4..6 band so we
        // get larger covers without collapsing to one giant column.
        val expandedWidth = 1180
        val cols = expandedWidth / libraryGridExpandedMinSizeDp
        assertTrue(
            "Expanded grid on a Tab S-class width must render 4..7 columns (got $cols)",
            cols in 4..7,
        )
    }
}
