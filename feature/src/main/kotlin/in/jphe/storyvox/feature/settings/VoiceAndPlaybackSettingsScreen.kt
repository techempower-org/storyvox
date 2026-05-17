package `in`.jphe.storyvox.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Settings → Voice & Playback subscreen (follow-up to #440 / #467).
 *
 * Houses the auditory knobs a listener touches *for this story, this
 * session* — voice, speed, pitch, cadence, sentence-by-sentence
 * pacing. The legacy long-scroll [SettingsScreen] still renders the
 * same rows (so power users searching from the "All settings" escape
 * hatch keep their muscle memory); this subscreen is the curated
 * single-purpose surface reached from the [SettingsHubScreen] gear-
 * icon hub.
 *
 * Row order matches the legacy screen — most-touched first:
 *  1. Voice library link
 *  2. Speed slider (with the 1× tick anchor — #273)
 *  3. Pitch slider (with the 1× tick anchor)
 *  4. Punctuation cadence slider (#109)
 *  5. High-quality pitch interpolation switch (#193)
 *  6. Pronunciation dictionary link (#135)
 */
@Composable
fun VoiceAndPlaybackSettingsScreen(
    onBack: () -> Unit,
    onOpenVoiceLibrary: () -> Unit,
    onOpenPronunciationDict: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current

    SettingsSubscreenScaffold(title = "Voice & Playback", onBack = onBack) { padding ->
        val s = state.settings ?: run {
            SettingsSkeleton(modifier = Modifier.fillMaxSize().padding(padding).padding(spacing.md))
            return@SettingsSubscreenScaffold
        }
        SettingsSubscreenBody(padding) {
            SettingsGroupCard {
                SettingsLinkRow(
                    title = "Voice library",
                    subtitle = "Pick a voice and hear samples.",
                    onClick = onOpenVoiceLibrary,
                )

                // Speed and Pitch sliders preserve the v0.4.x tick-label
                // pattern: a single "▲ 1×" mark anchors the natural value,
                // tap snaps the slider back to neutral. See the matching
                // block in [SettingsScreen] for the rationale (#273).
                val speedMin = 0.5f
                val speedMax = 4.0f
                val naturalValue = 1.0f
                val speedNaturalFraction = (naturalValue - speedMin) / (speedMax - speedMin)
                SettingsSliderBlock(
                    title = "Speed",
                    valueLabel = "${"%.2f".format(s.effectiveSpeed)}×",
                    slider = {
                        Column {
                            Slider(
                                value = s.effectiveSpeed,
                                onValueChange = viewModel::setSpeed,
                                valueRange = speedMin..speedMax,
                                modifier = Modifier.semantics {
                                    contentDescription = "Default speech speed"
                                    stateDescription = "%.2f times".format(s.effectiveSpeed)
                                },
                            )
                            SliderTickLabels(
                                ticks = listOf("▲ 1×" to speedNaturalFraction),
                                onTickTap = { viewModel.setSpeed(naturalValue) },
                            )
                        }
                    },
                )

                val pitchMin = 0.6f
                val pitchMax = 1.4f
                val pitchNaturalFraction = (naturalValue - pitchMin) / (pitchMax - pitchMin)
                SettingsSliderBlock(
                    title = "Pitch",
                    valueLabel = "${"%.2f".format(s.effectivePitch)}×",
                    slider = {
                        Column {
                            Slider(
                                value = s.effectivePitch,
                                onValueChange = viewModel::setPitch,
                                valueRange = pitchMin..pitchMax,
                                modifier = Modifier.semantics {
                                    contentDescription = "Default pitch"
                                    stateDescription = "%.2f, neutral at one".format(s.effectivePitch)
                                },
                            )
                            SliderTickLabels(
                                ticks = listOf("▲ 1×" to pitchNaturalFraction),
                                onTickTap = { viewModel.setPitch(naturalValue) },
                            )
                        }
                    },
                )

                PunctuationPauseSlider(
                    multiplier = s.punctuationPauseMultiplier,
                    onMultiplierChange = viewModel::setPunctuationPauseMultiplier,
                )

                SettingsSwitchRow(
                    title = "High-quality pitch interpolation",
                    subtitle = if (s.pitchInterpolationHighQuality) {
                        "Smoother pitch-shifted audio. ~20% extra CPU per chapter render."
                    } else {
                        "Faster pitch shifting. Some grittiness at non-neutral pitch."
                    },
                    checked = s.pitchInterpolationHighQuality,
                    onCheckedChange = viewModel::setPitchInterpolationHighQuality,
                )

                SettingsLinkRow(
                    title = "Pronunciation",
                    subtitle = "Teach the voice how to say specific names and words.",
                    onClick = onOpenPronunciationDict,
                )
            }

            // Issues #593 / #594 — playback transport tunables. The
            // skip distance chip (#593) drives the +N s / -N s
            // transport buttons; the rewind-to-start chip (#594)
            // controls how SkipPrevious behaves mid-chapter. Bundled
            // because the two prefs pair conceptually — users
            // calibrate them together for their content style.
            SettingsGroupCard {
                // #593 — skip distance. Matches Spotify / Apple Music
                // / Pocket Casts default of 30s; users on dense
                // chapters often want 10/15, podcast users 45/60.
                val skipOptions = listOf(10, 15, 30, 45, 60)
                val skipSelectedIndex = skipOptions
                    .indexOfFirst { it == s.skipDistanceSec }
                    .let { if (it < 0) skipOptions.indexOf(30) else it }
                SettingsSegmentedBlock(
                    title = "Skip distance",
                    subtitle = "Seconds the +N / -N buttons jump per tap.",
                    options = skipOptions.map { "${it}s" },
                    selectedIndex = skipSelectedIndex,
                    onSelected = { idx -> viewModel.setSkipDistanceSec(skipOptions[idx]) },
                )

                // #594 — rewind-to-start window. When you tap
                // SkipPrevious *past* this many seconds into a
                // chapter, it rewinds to the chapter start. Within
                // this window, it jumps to the previous chapter.
                // 0 = always go to previous chapter (radio / podcast
                // users on short content who want fast prev-track
                // navigation).
                val rewindOptions = listOf(0, 1, 3, 5, 10)
                val rewindSelectedIndex = rewindOptions
                    .indexOfFirst { it == s.rewindToStartThresholdSec }
                    .let { if (it < 0) rewindOptions.indexOf(3) else it }
                SettingsSegmentedBlock(
                    title = "Skip-back: jump to start when within",
                    subtitle = "Past this point in a chapter, the prev-track button " +
                        "rewinds to the start. Within it, jumps to the previous chapter. " +
                        "Off (0s) always goes to the previous chapter.",
                    options = rewindOptions.map { if (it == 0) "Off" else "${it}s" },
                    selectedIndex = rewindSelectedIndex,
                    onSelected = { idx -> viewModel.setRewindToStartThresholdSec(rewindOptions[idx]) },
                )

                // Issue #595 — sleep-timer shake-to-extend duration.
                // When the timer's 10-second fade tail starts and the
                // user shakes the device, the timer extends by this
                // many minutes. Pre-fix this was hardcoded at 15
                // (StoryvoxPlaybackService.LEGACY_SHAKE_EXTEND_MINUTES);
                // listeners who fall asleep quickly often want 5, and
                // those on a slower wind-down often want 30.
                val shakeOptions = listOf(5, 10, 15, 30)
                val shakeIndex = shakeOptions
                    .indexOfFirst { it == s.sleepShakeExtendMinutes }
                    .let { if (it < 0) shakeOptions.indexOf(15) else it }
                SettingsSegmentedBlock(
                    title = "Sleep timer: shake-to-extend",
                    subtitle = "Minutes the timer extends when you shake during the " +
                        "10-second fade tail.",
                    options = shakeOptions.map { "${it} min" },
                    selectedIndex = shakeIndex,
                    onSelected = { idx ->
                        viewModel.setSleepShakeExtendMinutes(shakeOptions[idx])
                    },
                )
            }
        }
    }
}
