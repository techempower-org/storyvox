package `in`.jphe.storyvox.ui.component

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #743 — regression guard for `BrassButton` TalkBack / uiautomator
 * semantics.
 *
 * The Browse source error-state retry button rendered as
 * `NAF="true" text="" content-desc=""` in uiautomator because the
 * `Modifier.semantics { role = Role.Button }` modifier defaults to
 * `mergeDescendants = false`, which *replaces* the M3 Button's default
 * semantics — the inner `Text(label)` never bubbled up to the parent
 * accessibility node. Screen readers hit a dead-end on every transient
 * network failure.
 *
 * The fix is `Modifier.semantics(mergeDescendants = true) { role =
 * Role.Button }`. We can't run a Compose UI test from the unit-test
 * source set, so this test pins the structural canary
 * `brassButtonMergesDescendantsForLabel`, which must stay `true`. Drop
 * it only after proving on-device that a different shape carries the
 * same uiautomator text + TalkBack announcement.
 */
class BrassButtonSemanticsTest {

    @Test
    fun `BrassButton merges descendants so its label is in the a11y tree per issue #743`() {
        assertTrue(
            "BrassButton must use Modifier.semantics(mergeDescendants = true) " +
                "so the inner Text(label) reaches uiautomator / TalkBack (issue #743)",
            brassButtonMergesDescendantsForLabel,
        )
    }
}
