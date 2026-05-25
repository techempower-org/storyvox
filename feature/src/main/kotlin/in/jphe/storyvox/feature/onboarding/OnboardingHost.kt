package `in`.jphe.storyvox.feature.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi

/**
 * Issue #599 (v1.0 blocker) — the three-screen first-launch welcome
 * flow. Mounted at the navigation host root, *above* the existing
 * [VoicePickerGate], so a brand-new user sees the welcome before the
 * engineering-themed voice picker. Once the user finishes (or skips)
 * the welcome, [SettingsRepositoryUi.markOnboardingCompletedV1] flips
 * the persisted flag and the host disappears for the life of this
 * install (until Settings → Developer → Reset onboarding flips it
 * back for testing).
 *
 * State machine (three steps, forward-only — no Back, by design; if
 * the user needs to revisit a choice they completed earlier they can
 * use the corresponding affordance in the post-welcome app):
 *
 *   Welcome → VoicePicker → FirstFiction → done.
 *
 * Each transition is a fade — the screens share the same background
 * colour so a horizontal slide would feel like the user moved
 * "inside" the welcome, which is wrong; the welcome is a single
 * conceptual step composed of three pages.
 *
 * The host renders `content()` underneath itself unconditionally so
 * the embedded NavHost is alive when the user finally taps a CTA
 * that calls `navController.navigate(...)`. Same pattern as
 * VoicePickerGate; without this the route resolution races the
 * destination registration on cold launch.
 */
@Composable
fun OnboardingHost(
    onAddFromWebsite: (clipboardUrl: String?) -> Unit,
    onOpenVoiceLibrary: () -> Unit,
    viewModel: OnboardingHostViewModel = hiltViewModel(),
    content: @Composable () -> Unit,
) {
    val show by viewModel.shouldShow.collectAsStateWithLifecycle()
    Box(modifier = Modifier.fillMaxSize()) {
        content()
        if (show) {
            // Forward-only three-step state machine. rememberSaveable
            // so a configuration change (rotation) doesn't snap the
            // user back to step 1 mid-flow.
            var step by rememberSaveable { mutableStateOf(OnboardingStep.Welcome) }
            val swallow = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    // Tap-swallow shield (same pattern as
                    // VoicePickerGate) — prevents underlying Library
                    // taps from leaking through the welcome overlay.
                    .clickable(
                        interactionSource = swallow,
                        indication = null,
                        onClick = {},
                    ),
            ) {
                AnimatedContent(
                    targetState = step,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "onboarding-step",
                ) { current ->
                    when (current) {
                        OnboardingStep.Welcome -> WelcomeScreen(
                            onGetStarted = { step = OnboardingStep.VoicePick },
                            onSkip = {
                                viewModel.markCompleted()
                            },
                        )
                        OnboardingStep.VoicePick -> VoicePickerOnboarding(
                            onContinue = { step = OnboardingStep.FirstFiction },
                            onSkip = { step = OnboardingStep.FirstFiction },
                            onMoreVoices = {
                                viewModel.markCompleted()
                                onOpenVoiceLibrary()
                            },
                        )
                        OnboardingStep.FirstFiction -> FirstFictionPicker(
                            onAddFromWebsite = { clip ->
                                viewModel.markCompleted()
                                onAddFromWebsite(clip)
                            },
                            onSkip = {
                                viewModel.markCompleted()
                            },
                        )
                    }
                }
            }
        }
    }
}

/** The three pages of the welcome flow, in display order. */
internal enum class OnboardingStep { Welcome, VoicePick, FirstFiction }

@HiltViewModel
class OnboardingHostViewModel @Inject constructor(
    private val settings: SettingsRepositoryUi,
) : ViewModel() {

    /** True iff the welcome flow should be on screen right now. Maps
     *  the inverse of [SettingsRepositoryUi.onboardingCompletedV1] —
     *  show the flow until the user completes / skips it.
     *
     *  Eager start so the initial DataStore read overlaps with the
     *  cold-launch composition; otherwise a brand-new install would
     *  briefly see the Library underneath before the welcome
     *  composed in. */
    val shouldShow: StateFlow<Boolean> = settings.onboardingCompletedV1
        .map { !it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Called from any of the screens' completion / skip paths. The
     *  DataStore write is fire-and-forget; the [shouldShow] flow
     *  flips false on the next emission and the host self-removes
     *  from the composition. */
    fun markCompleted() {
        viewModelScope.launch { settings.markOnboardingCompletedV1() }
    }
}
