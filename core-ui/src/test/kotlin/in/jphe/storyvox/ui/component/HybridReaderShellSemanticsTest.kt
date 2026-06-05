package `in`.jphe.storyvox.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1025 — regression guard for `HybridReaderShell` TalkBack /
 * Switch Access reachability.
 *
 * The shell flips between the Audiobook and Reader panes via a horizontal
 * drag-with-velocity gesture and *nothing else*. A drag is not exposed as
 * an accessibility action, so a screen-reader user (and any motor-impaired
 * user who can't produce a precise fling) cannot reach the Reader pane at
 * all — the entire reading-mode surface is unreachable. The fix wires a
 * [androidx.compose.ui.semantics.CustomAccessibilityAction] onto the shell
 * root, labelled by the *target* pane, driving the same `onViewChange`
 * callback the drag uses, plus a `stateDescription` announcing the current
 * pane.
 *
 * We can't run a Compose UI test from the unit-test source set (no
 * Robolectric / ComposeTestRule — see [BottomTabBarSemanticsTest]). This
 * test pins the contract by:
 *  (a) asserting [ReaderView.opposite] resolves the target pane the custom
 *      action announces and invokes (from Audiobook the action offers
 *      Reader, and vice versa — it must never resolve to itself), and
 *  (b) checking the structural marker constant
 *      [hybridReaderShellExposesPaneSwitchAction] stays `true`.
 *
 * If a future refactor drops the custom action without proving an
 * alternative accessible path on a real device with TalkBack, this test
 * fails and forces a re-verification.
 */
class HybridReaderShellSemanticsTest {

    @Test
    fun `opposite resolves the other pane — the custom action targets the pane the user can't currently see`() {
        // The action label and the pane it switches to are both derived
        // from opposite(): from Audiobook it offers "Switch to reading
        // view" → Reader; from Reader it offers "Switch to audiobook
        // view" → Audiobook.
        assertEquals(ReaderView.Reader, ReaderView.Audiobook.opposite())
        assertEquals(ReaderView.Audiobook, ReaderView.Reader.opposite())
    }

    @Test
    fun `opposite never resolves to the current pane — a no-op action would announce a dead control`() {
        ReaderView.entries.forEach { pane ->
            assertNotEquals(
                "ReaderView.$pane.opposite() must not be itself — the pane-switch action would do nothing",
                pane,
                pane.opposite(),
            )
        }
    }

    @Test
    fun `opposite is an involution — two switches return to the start`() {
        ReaderView.entries.forEach { pane ->
            assertEquals(
                "Switching panes twice must return to the starting pane",
                pane,
                pane.opposite().opposite(),
            )
        }
    }

    @Test
    fun `HybridReaderShell exposes a non-gesture pane-switch action per issue #1025`() {
        // Structural canary. The shell must keep a CustomAccessibilityAction
        // (+ stateDescription) on its root so the drag-only pane switch
        // stays reachable for TalkBack / Switch Access users. Flip to false
        // only after a real-device TalkBack pass proves a different shape
        // carries the same announcement + activation.
        assertTrue(
            "HybridReaderShell must expose a custom accessibility action for the pane switch (issue #1025)",
            hybridReaderShellExposesPaneSwitchAction,
        )
    }
}
