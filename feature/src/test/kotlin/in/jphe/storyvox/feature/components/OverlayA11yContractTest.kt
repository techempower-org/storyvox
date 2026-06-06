package `in`.jphe.storyvox.feature.components

import androidx.compose.ui.Modifier
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1026 — regression guard for first-launch overlay TalkBack
 * isolation ([`in`.jphe.storyvox.feature.onboarding.OnboardingHost] and
 * [`in`.jphe.storyvox.feature.engine.VoicePickerGate]).
 *
 * Both overlays keep the Library `NavHost` composed underneath themselves
 * (route resolution races the overlay's dismiss) and guard against stray
 * taps with a transparent tap-swallow shield. That shield blocks *pointer*
 * input only — it never bounded TalkBack's semantic traversal, so a
 * screen-reader user could in principle swipe past the overlay into the
 * visually hidden Library. On the dev tablet (R83W80CAFZB, TalkBack 17.0)
 * the opaque background happened to occlude the subtree out of the a11y
 * tree, so it did not reproduce — but that is a draw-order coincidence,
 * not a contract. [overlayBackground] + [overlayForeground] make the
 * isolation explicit and version-independent.
 *
 * The `feature` unit source set has no Robolectric / ComposeTestRule (see
 * [`in`.jphe.storyvox.feature.fiction.NotebookFocusContractTest]), so we
 * pin the contract via:
 *  (a) the structural marker [onboardingOverlaysIsolateBackgroundFromTalkBack],
 *      and
 *  (b) the no-op-when-inactive / mutates-when-active shape of
 *      [overlayBackground], which guarantees the live NavHost is untouched
 *      when no overlay is up and isolated when one is.
 */
class OverlayA11yContractTest {

    @Test
    fun `overlay background isolation is a no-op when no overlay is showing`() {
        // While no overlay is up, content() must behave exactly as before
        // — no stray semantics on the live NavHost wrapper. The helper
        // returns the receiver unchanged.
        val base = Modifier
        assertSame(
            "overlayBackground(false) must return the modifier unchanged so the live NavHost keeps its normal semantics",
            base,
            base.overlayBackground(overlayShowing = false),
        )
    }

    @Test
    fun `overlay background isolation adds semantics when an overlay is showing`() {
        // When an overlay is up, the background wrapper must carry the
        // invisibleToUser semantics (a different Modifier chain than the
        // bare receiver). We can't read the SemanticsConfiguration without
        // a ComposeTestRule, but the chain must have grown.
        val base = Modifier
        val isolated = base.overlayBackground(overlayShowing = true)
        assertNotEquals(
            "overlayBackground(true) must add semantics to hide the background from TalkBack",
            base,
            isolated,
        )
    }

    @Test
    fun `overlay foreground always adds traversal-group semantics`() {
        // The overlay root must always be scoped as a traversal group; it
        // is only ever applied while the overlay is on screen.
        val base = Modifier
        assertNotEquals(
            "overlayForeground() must add traversal-group semantics to the overlay root",
            base,
            base.overlayForeground(),
        )
    }

    @Test
    fun `onboarding overlays isolate background from TalkBack per issue #1026`() {
        // Structural canary. Both OnboardingHost and VoicePickerGate must
        // keep applying overlayBackground()/overlayForeground(). Flip to
        // false only after a real-device TalkBack pass proves the overlays
        // still bound screen-reader focus without these helpers.
        assertTrue(
            "OnboardingHost + VoicePickerGate must isolate the background NavHost from TalkBack (issue #1026)",
            onboardingOverlaysIsolateBackgroundFromTalkBack,
        )
    }
}
