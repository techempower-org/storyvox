package `in`.jphe.storyvox.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.ui.a11y.LocalAccessibleTouchTargets

enum class BrassButtonVariant { Primary, Secondary, Text }

/**
 * Structural canary for issue #743. Pinned by
 * `BrassButtonSemanticsTest` — if a future refactor drops
 * `mergeDescendants = true` from BrassButton's semantics, the test fails
 * and forces a re-verification with uiautomator / TalkBack. See the
 * inline comment on `baseSem` for the failure mode this guards.
 */
internal const val brassButtonMergesDescendantsForLabel: Boolean = true

/**
 * The realm's brass button.
 *
 * @param loading when true, the button reads disabled and renders a small
 *   [MagicSpinner] over the label position. The label itself is rendered
 *   at 0 alpha so the button keeps its measured width — start/stop of an
 *   async action doesn't reflow the surrounding row. This replaces the
 *   older "swap to inline CircularProgressIndicator + Text" pattern that
 *   caused a width jump and broke the brass palette.
 */
@Composable
fun BrassButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: BrassButtonVariant = BrassButtonVariant.Primary,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    // #690 Phase 2 — under [LocalAccessibleTouchTargets], grow the
    // button's minimum height from the M3 default 40dp to 64dp so
    // motor-impaired / Switch Access users get the wider tap surface
    // promised by the "Larger touch targets" toggle. Padding stays the
    // same (visual size scales with label); the minimum just floors it.
    val enlarged = LocalAccessibleTouchTargets.current
    val padding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
    // #743 — mergeDescendants=true folds the inner Text(label) into this
    // node's accessibility tree. Without it, `Modifier.semantics { role =
    // Role.Button }` *replaces* the M3 button's default semantics and the
    // child label never bubbles up: uiautomator sees a clickable node with
    // text="" content-desc="" and flags NAF (the network-error retry
    // button under Browse → Hacker News was the report site, but every
    // BrassButton was affected).
    val baseSem = Modifier.semantics(mergeDescendants = true) { role = Role.Button }
    val sem = if (enlarged) {
        baseSem.then(Modifier.defaultMinSize(minHeight = 64.dp))
    } else {
        baseSem
    }
    // Disabled colors stay in the brass family rather than falling through to
    // M3's default `onSurface * 0.12 / 0.38`, which renders as cool grey and
    // breaks the brass aesthetic during reachable disabled flows (e.g.,
    // VoicePickerGate during voice download).
    //
    // When `loading` is set, we keep the brass at full opacity so the
    // button reads as "working", not "broken / unavailable".
    val brass = MaterialTheme.colorScheme.primary
    val onBrass = MaterialTheme.colorScheme.onPrimary
    val effectiveEnabled = enabled && !loading
    val disabledBrassAlpha = if (loading) 1.0f else 0.12f
    val disabledOnBrassAlpha = if (loading) 1.0f else 0.38f
    val disabledOutlineAlpha = if (loading) 1.0f else 0.38f
    when (variant) {
        BrassButtonVariant.Primary -> Button(
            onClick = onClick,
            enabled = effectiveEnabled,
            modifier = modifier.then(sem),
            colors = ButtonDefaults.buttonColors(
                containerColor = brass,
                contentColor = onBrass,
                disabledContainerColor = brass.copy(alpha = disabledBrassAlpha),
                disabledContentColor = onBrass.copy(alpha = disabledOnBrassAlpha),
            ),
            shape = MaterialTheme.shapes.medium,
            contentPadding = padding,
        ) { ButtonContent(label, loading) }

        BrassButtonVariant.Secondary -> OutlinedButton(
            onClick = onClick,
            enabled = effectiveEnabled,
            modifier = modifier.then(sem),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = brass,
                disabledContentColor = brass.copy(alpha = disabledOutlineAlpha),
            ),
            shape = MaterialTheme.shapes.medium,
            contentPadding = padding,
        ) { ButtonContent(label, loading) }

        BrassButtonVariant.Text -> TextButton(
            onClick = onClick,
            enabled = effectiveEnabled,
            modifier = modifier.then(sem),
            colors = ButtonDefaults.textButtonColors(
                contentColor = brass,
                disabledContentColor = brass.copy(alpha = disabledOutlineAlpha),
            ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) { ButtonContent(label, loading) }
    }
}

/**
 * Renders the label and, when [loading], hides it (alpha 0) while
 * overlaying a [MagicSpinner]. Using a Box keeps the button width stable
 * — the label's measured size always wins.
 *
 * The spinner inherits its color from `LocalContentColor`, which Material
 * sets from each variant's `contentColor`. So Primary gets onPrimary
 * brass, Secondary/Text get brass — all correct for the realm.
 */
@Composable
private fun ButtonContent(label: String, loading: Boolean) {
    Box(contentAlignment = Alignment.Center) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            modifier = if (loading) Modifier.alpha(0f) else Modifier,
        )
        if (loading) {
            // Use the variant's content color so the spinner stays visible
            // on Primary (onBrass on brass) and on Secondary/Text (brass on
            // surface). Hardcoding `primary` would make Primary spinners
            // brass-on-brass and invisible.
            MagicSpinner(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = LocalContentColor.current,
            )
        }
    }
}
