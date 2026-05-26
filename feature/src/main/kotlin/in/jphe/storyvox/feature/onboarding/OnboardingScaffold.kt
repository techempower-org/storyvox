package `in`.jphe.storyvox.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Issue #787 — shared scaffold wrapping each of the three onboarding
 * screens with a step indicator that tells the user where they are in
 * the forward-only flow (Welcome → VoicePick → FirstFiction).
 *
 * Visual: a row of three dots at the top of the page (filled brass =
 * visited or current, outlined = upcoming) plus a small "Step X of 3"
 * text label below the dots. The dots alone are not screen-reader
 * accessible — TalkBack needs the explicit count — so we surface both.
 *
 * The indicator is informational only: dots are NOT clickable. Per
 * `OnboardingHost.kt`'s state-machine doc, the welcome flow is
 * forward-only by design; tappable dots would invite a non-existent
 * Back affordance.
 *
 * The whole indicator row is wrapped in a [contentDescription] +
 * [liveRegion] so TalkBack announces the step change ("Step 2 of 3")
 * once when the AnimatedContent transition lands on a new screen,
 * rather than re-reading the dot count on every focus pass.
 */
@Composable
internal fun OnboardingScaffold(
    stepIndex: Int,
    totalSteps: Int = 3,
    content: @Composable () -> Unit,
) {
    val spacing = LocalSpacing.current
    val stepLabel = stringResource(
        R.string.onboarding_step_n_of_m,
        stepIndex + 1,
        totalSteps,
    )
    Box(modifier = Modifier.fillMaxSize()) {
        content()
        // Indicator overlay sits above the screen's own padded content
        // so each screen keeps its existing vertical rhythm (the page
        // headlines already center themselves in the viewport). The
        // overlay uses its own top padding rather than competing for
        // a slot inside each screen's Column.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = spacing.md)
                .semantics(mergeDescendants = true) {
                    contentDescription = stepLabel
                    liveRegion = LiveRegionMode.Polite
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(totalSteps) { i ->
                    StepDot(filled = i <= stepIndex)
                }
            }
            Spacer(Modifier.height(spacing.xxs))
            Text(
                text = stepLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun StepDot(filled: Boolean) {
    val brass = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(if (filled) brass else brass.copy(alpha = 0.25f)),
    )
}
