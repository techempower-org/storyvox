package `in`.jphe.storyvox.feature.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex

/**
 * Issue #1026 — accessibility isolation for full-screen first-launch
 * overlays ([OnboardingHost], [VoicePickerGate]).
 *
 * Both overlays render the real app content (the Library `NavHost`)
 * underneath themselves *unconditionally* — the embedded NavHost must be
 * composed so a CTA's `navController.navigate(...)` resolves in the same
 * frame the overlay dismisses. They guard against stray taps with a
 * transparent `clickable` "tap-swallow" shield, but that only intercepts
 * **pointer** input — it does nothing to TalkBack's semantic traversal.
 *
 * On the dev tablet (R83W80CAFZB, Samsung SM-T227U, TalkBack 17.0) the
 * opaque full-screen background already happens to occlude the Library
 * subtree out of the accessibility tree, so focus does not escape there.
 * But that is a *coincidental* side-effect of draw/occlusion order, not a
 * contract Compose guarantees across Android / TalkBack versions. These
 * helpers make the isolation **explicit** so a screen-reader user on first
 * launch can never swipe past the active overlay into the (visually
 * hidden) Library list / bottom nav rendered behind it.
 *
 * Apply [overlayBackground] to the underlying `content()` wrapper and
 * [overlayForeground] to the overlay's own root, gating both on whether an
 * overlay is currently showing.
 */

/**
 * Wraps the background `content()` so it is removed from the accessibility
 * traversal while [overlayShowing]. When an overlay is up the whole subtree
 * is marked [invisibleToUser] — TalkBack / Switch Access skip it entirely;
 * when no overlay is up this is a no-op and `content()` behaves normally.
 *
 * `invisibleToUser` (rather than `clearAndSetSemantics {}`) is deliberate:
 * it hides the subtree from accessibility services without disturbing the
 * pointer-input or layout semantics the live NavHost still needs while it
 * sits composed beneath the overlay.
 */
fun Modifier.overlayBackground(overlayShowing: Boolean): Modifier =
    if (overlayShowing) {
        this.semantics { invisibleToUser() }
    } else {
        this
    }

/**
 * Marks the overlay's own root as a self-contained traversal group placed
 * ahead of anything else on screen, so TalkBack's linear (swipe) navigation
 * stays within the overlay's controls. Pairs with [overlayBackground] — the
 * background is hidden, the foreground is grouped — so focus is bounded on
 * both ends regardless of draw order.
 */
fun Modifier.overlayForeground(): Modifier =
    this.semantics {
        isTraversalGroup = true
        // Negative index sorts the overlay group before the (now hidden)
        // background in the merged traversal order; belt-and-suspenders
        // with overlayBackground's invisibleToUser.
        traversalIndex = -1f
    }

/**
 * Structural canary for issue #1026 — both first-launch overlays MUST
 * isolate the background NavHost from TalkBack traversal while they are up.
 * The bug did not reproduce on the dev tablet (the opaque background
 * occluded the subtree *coincidentally*), so there is nothing a runtime
 * assertion can observe regress on this device — and the `feature` unit
 * source set has no Robolectric / ComposeTestRule to drive a live
 * traversal anyway. This boolean pins the contract the same way
 * [`in`.jphe.storyvox.feature.fiction.notebookAddNoteUsesExplicitFocus]
 * does for the Notebook focus plumbing: flip it to false only after
 * proving on a real device with TalkBack that the overlays still isolate
 * background focus without [overlayBackground] / [overlayForeground].
 *
 * Pinned by `OverlayA11yContractTest`.
 */
const val onboardingOverlaysIsolateBackgroundFromTalkBack: Boolean = true
