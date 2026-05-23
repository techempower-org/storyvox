package `in`.jphe.storyvox.feature.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.feature.api.UiPlaybackState
import `in`.jphe.storyvox.ui.component.humanizeVoiceLabel
import `in`.jphe.storyvox.ui.theme.LocalReducedMotion
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Issue #418 — the magical-voice-icon bottom sheet. Five live tuning
 * rows + an "Advanced" expander for the v0.5.30 #197 #198 features.
 *
 * Ordered by frequency-of-use rather than logical taxonomy:
 *  1. Speed — the most-tweaked knob, listeners ride this constantly.
 *  2. Pitch — second-most-tweaked; gated off for live-audio chapters
 *     where Sonic shifting is a no-op (#373).
 *  3. Voice picker chip — current voice name + tap-to-open-full-picker.
 *  4. Pause / sentence silence (#109) — third-most-tweaked, especially
 *     for accessibility (slower cadence) and speedrun-listening (faster).
 *  5. Sonic high-quality toggle (#193) — set-once-and-forget for most.
 *
 * The Advanced expander surfaces "Per-voice lexicon + Kokoro phonemizer
 * lang" as a single link-row that deep-links into Voice Library (where
 * the rich per-voice picker UI already lives — see VoiceLibraryScreen
 * lines 364/367/451/454). Replicating that picker inline would force
 * a SAF launcher + Kokoro-detection logic into the player layer; the
 * link-row keeps the quick sheet "quick" while still discoverable.
 *
 * Settings apply LIVE — no need to dismiss the sheet to hear the effect.
 * The slider's `onValueChange` fires per drag-pixel; the engine's
 * `setSpeed`/`setPitch`/`setPunctuationPauseMultiplier` are designed
 * for continuous live-tune (cheap per-call), mirroring the Player
 * Options sheet's pattern documented in AudiobookView.kt.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun VoiceQuickSheetContent(
    state: UiPlaybackState,
    punctuationPauseMultiplier: Float,
    pitchInterpolationHighQuality: Boolean,
    onSetSpeed: (Float) -> Unit,
    onPersistSpeed: (Float) -> Unit,
    onSetPitch: (Float) -> Unit,
    onPersistPitch: (Float) -> Unit,
    onSetPunctuationPause: (Float) -> Unit,
    onSetPitchHighQuality: (Boolean) -> Unit,
    onPickVoice: () -> Unit,
    onOpenAdvancedVoice: () -> Unit,
) {
    val spacing = LocalSpacing.current
    var advancedOpen by remember { mutableStateOf(false) }
    // #527 follow-up — chip-first quick sheet. Each tuning row is a
    // chip preset row (one tap, persisted immediately) with a
    // "Custom…" toggle that reveals the continuous slider for users
    // who want fine-grained control. Default-closed: the chips cover
    // the audit benchmark set (Spotify / Apple Music / Pocket Casts /
    // Libby) so most listeners never need the slider. The slider
    // stays available behind the toggle so we don't strip the
    // continuous-knob path from anyone who currently relies on it.
    var speedSliderOpen by remember { mutableStateOf(false) }
    var pitchSliderOpen by remember { mutableStateOf(false) }
    var pausesSliderOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .padding(horizontal = spacing.lg, vertical = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        // ── 1. Speed (most-used) ───────────────────────────────────
        // Chip presets first; "Custom…" expands the continuous slider
        // below. The chip presets are the *fast path* — one tap and
        // the value is committed immediately. The slider stays
        // available for fine-tuning between presets.
        QuickSheetHeader(stringResource(R.string.reader_voice_speed_header), "${"%.2f".format(state.speed)}×")
        QuickSheetPresetChipRow(
            presets = SPEED_PRESETS,
            isSelected = { preset -> isSpeedPresetSelected(state.speed, preset) },
            label = ::formatSpeedPreset,
            contentDescriptionFor = { preset ->
                "Playback speed ${formatSpeedPreset(preset)}"
            },
            onPick = { preset ->
                // Discrete preset → commit immediately (the user
                // expressed intent exactly). Mirrors the
                // [PlayerQuickChips] persistence pattern.
                onSetSpeed(preset)
                onPersistSpeed(preset)
            },
            customExpanded = speedSliderOpen,
            onToggleCustom = { speedSliderOpen = !speedSliderOpen },
        )
        AnimatedVisibility(visible = speedSliderOpen) {
            Slider(
                value = state.speed,
                onValueChange = onSetSpeed,
                onValueChangeFinished = { onPersistSpeed(state.speed) },
                valueRange = 0.5f..3.0f,
                modifier = Modifier.semantics {
                    contentDescription = "Speech speed"
                    stateDescription = "%.2f times".format(state.speed)
                },
            )
        }

        // ── 2. Pitch ───────────────────────────────────────────────
        // Hidden on Media3-routed live audio (#373) — Sonic pitch-shifting
        // has no equivalent on a live stream the player can't re-encode.
        // Hiding rather than disabling keeps the sheet visually clean.
        if (!state.isLiveAudioChapter) {
            QuickSheetHeader(stringResource(R.string.reader_voice_pitch_header), "${"%.2f".format(state.pitch)}×")
            QuickSheetPresetChipRow(
                presets = PITCH_PRESETS,
                isSelected = { preset -> isPitchPresetSelected(state.pitch, preset) },
                label = ::formatPitchPreset,
                contentDescriptionFor = { preset ->
                    "Pitch ${formatPitchPreset(preset)}"
                },
                onPick = { preset ->
                    onSetPitch(preset)
                    onPersistPitch(preset)
                },
                customExpanded = pitchSliderOpen,
                onToggleCustom = { pitchSliderOpen = !pitchSliderOpen },
            )
            AnimatedVisibility(visible = pitchSliderOpen) {
                Slider(
                    value = state.pitch,
                    onValueChange = onSetPitch,
                    onValueChangeFinished = { onPersistPitch(state.pitch) },
                    valueRange = 0.6f..1.4f,
                    modifier = Modifier.semantics {
                        contentDescription = "Pitch"
                        stateDescription = "%.2f, neutral at one".format(state.pitch)
                    },
                )
            }
        }

        // ── 3. Voice picker chip ───────────────────────────────────
        // Tap-to-open-full-picker. The whole row is the touch target
        // (matches the established Player Options sheet pattern; the
        // previous IconButton-only target was undersized at 36 dp).
        QuickSheetHeader(stringResource(R.string.reader_voice_header), null)
        // a11y (#481): Role.Button + label for the voice-picker row.
        val pickVoiceLabel = stringResource(R.string.reader_pick_voice)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = pickVoiceLabel, onClick = onPickVoice)
                .padding(vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Icon(
                Icons.Outlined.RecordVoiceOver,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                // Issue #619 — humanize the raw voice id to a chip-
                // friendly form like "Brian · US English" instead of
                // the raw `azure:en-US-BrianNeural` token. Falls back
                // to "Pick a voice" via `ifBlank` when no voice is set.
                Text(
                    humanizeVoiceLabel(state.voiceLabel).ifBlank { stringResource(R.string.reader_pick_a_voice) },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.reader_voice_tap_to_change),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ── 4. Pause / sentence silence (#109) ─────────────────────
        // Engine clamps to [0..4]; chip presets cover the practical
        // listening range (0×..3×). The 4× extreme is reachable via
        // "Custom…" for users who want extra-slow narrator cadence.
        // 0× = rapid listening; 1× = audiobook-tuned default;
        // 2-3× = slow, a11y / language-learning cadence.
        QuickSheetHeader(stringResource(R.string.reader_voice_sentence_silence_header), "${"%.2f".format(punctuationPauseMultiplier)}×")
        QuickSheetPresetChipRow(
            presets = PUNCTUATION_PAUSE_PRESETS,
            isSelected = { preset ->
                isPunctuationPausePresetSelected(punctuationPauseMultiplier, preset)
            },
            label = ::formatPunctuationPausePreset,
            contentDescriptionFor = { preset ->
                "Sentence silence ${formatPunctuationPausePreset(preset)}"
            },
            onPick = { preset ->
                // The slider's onValueChange was both live AND persist
                // (Settings VM persists on the same callback the engine
                // listens on). Chip taps keep that single-callback
                // semantic — the engine + the pref key both update.
                onSetPunctuationPause(preset)
            },
            customExpanded = pausesSliderOpen,
            onToggleCustom = { pausesSliderOpen = !pausesSliderOpen },
        )
        AnimatedVisibility(visible = pausesSliderOpen) {
            Slider(
                value = punctuationPauseMultiplier,
                onValueChange = onSetPunctuationPause,
                valueRange = 0f..4f,
                modifier = Modifier.semantics {
                    contentDescription = "Inter-sentence pause"
                    stateDescription = "%.2f times".format(punctuationPauseMultiplier)
                },
            )
        }

        // ── 5. Sonic high-quality toggle (#193) ────────────────────
        // Persisted-only; engine reads at the start of the next chapter
        // render, so the subtitle calls out the deferred-apply behaviour
        // so the listener isn't surprised the current chapter doesn't
        // change tone mid-stream.
        QuickSheetSwitchRow(
            title = stringResource(R.string.reader_voice_high_quality_title),
            subtitle = if (pitchInterpolationHighQuality) {
                stringResource(R.string.reader_voice_high_quality_on)
            } else {
                stringResource(R.string.reader_voice_high_quality_off)
            },
            checked = pitchInterpolationHighQuality,
            onCheckedChange = onSetPitchHighQuality,
        )

        // ── Advanced expander: per-voice lexicon + Kokoro lang ─────
        // Deep-links into Voice Library where the per-voice SAF picker
        // + Kokoro phonemizer-lang dropdown already live. Putting that
        // rich UI in the quick sheet would bloat the surface; the
        // link-row keeps the v0.5.30 features discoverable from the
        // player without duplicating the picker UI.
        // a11y (#481): Role.Button + label for the Advanced expander.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    role = Role.Button,
                    onClickLabel = if (advancedOpen) stringResource(R.string.reader_voice_collapse_advanced) else stringResource(R.string.reader_voice_expand_advanced),
                ) { advancedOpen = !advancedOpen }
                .padding(vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Icon(
                Icons.Outlined.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(R.string.reader_voice_advanced),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (advancedOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (advancedOpen) stringResource(R.string.reader_voice_collapse_advanced) else stringResource(R.string.reader_voice_expand_advanced),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // #486 Phase 2 / #480 — expand/collapse snaps under reduced
        // motion (structural state change preserved; the easing curve
        // doesn't play).
        val reducedMotion = LocalReducedMotion.current
        AnimatedVisibility(
            visible = advancedOpen,
            enter = if (reducedMotion) androidx.compose.animation.EnterTransition.None else fadeIn() + expandVertically(),
            exit = if (reducedMotion) androidx.compose.animation.ExitTransition.None else fadeOut() + shrinkVertically(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            role = Role.Button,
                            onClickLabel = stringResource(R.string.reader_voice_advanced_open_label),
                            onClick = onOpenAdvancedVoice,
                        )
                        .padding(vertical = spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.reader_voice_per_voice_lexicon),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(R.string.reader_voice_per_voice_lexicon_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(spacing.md))
    }
}

@Composable
private fun QuickSheetHeader(title: String, valueLabel: String?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (valueLabel != null) {
            Text(
                valueLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * #527 follow-up — chip-preset row used by every continuous-tuning
 * control in the quick sheet (Speed, Pitch, Sentence silence). Renders
 * `presets.size` [FilterChip]s plus a trailing "Custom…" chip that
 * toggles the slider's visibility (see callers — each row's slider
 * is wrapped in an [AnimatedVisibility] gated on `customExpanded`).
 *
 * The chip row uses [FlowRow] so a 5-chip preset list + a Custom chip
 * wraps gracefully on narrow phones (R5CRB0W66MK reports a Compose
 * width of ~360 dp; the chip row needs at least 2 rows of wrapping
 * when all 6 chips have label "1.25×"-shape text).
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun QuickSheetPresetChipRow(
    presets: List<Float>,
    isSelected: (Float) -> Boolean,
    label: (Float) -> String,
    contentDescriptionFor: (Float) -> String,
    onPick: (Float) -> Unit,
    customExpanded: Boolean,
    onToggleCustom: () -> Unit,
) {
    val spacing = LocalSpacing.current
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        presets.forEach { preset ->
            FilterChip(
                selected = isSelected(preset),
                onClick = { onPick(preset) },
                label = { Text(label(preset)) },
                colors = brassFilterChipColors(),
                modifier = Modifier.semantics {
                    contentDescription = contentDescriptionFor(preset)
                },
            )
        }
        // "Custom…" chip — secondary affordance for users who want the
        // continuous-knob path. Selected-state reflects expanded-ness
        // so the chip's brass fill reads as "the slider is open right
        // now". Doesn't itself change the value — just opens / closes
        // the slider below.
        FilterChip(
            selected = customExpanded,
            onClick = onToggleCustom,
            label = { Text(if (customExpanded) stringResource(R.string.reader_voice_custom) else stringResource(R.string.reader_voice_custom_more)) },
            colors = brassFilterChipColors(),
            modifier = Modifier.semantics {
                contentDescription = if (customExpanded) {
                    "Hide custom slider"
                } else {
                    "Show custom slider"
                }
            },
        )
    }
}

@Composable
private fun QuickSheetSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val spacing = LocalSpacing.current
    // a11y (#478): toggleable Row so TalkBack announces the row as a
    // single Role.Switch with the merged title as its label.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * Issue #418 — pure-logic spec for the quick-sheet's content rows.
 * Each entry corresponds to one of the 5 control rows; the Advanced
 * expander is a 6th entry whose presence isn't gated on playback state.
 *
 * Surfaced as a public, testable list so the bottom-sheet-content unit
 * test can assert all rows are present without booting a Compose
 * runtime. The composable above renders rows in the same order; if
 * one is added/removed there it must also land here.
 */
internal enum class VoiceQuickSheetRow {
    Speed,
    Pitch,
    Voice,
    SentenceSilence,
    SonicQuality,
    Advanced,
}

/**
 * Returns the rows visible in the quick sheet for the given playback
 * state. Pitch is hidden on Media3-routed live audio (#373); everything
 * else is always visible. The Advanced row is always visible (it's a
 * link, not a setting; rendering cost is negligible).
 */
internal fun voiceQuickSheetRowsFor(state: UiPlaybackState): List<VoiceQuickSheetRow> =
    buildList {
        add(VoiceQuickSheetRow.Speed)
        if (!state.isLiveAudioChapter) add(VoiceQuickSheetRow.Pitch)
        add(VoiceQuickSheetRow.Voice)
        add(VoiceQuickSheetRow.SentenceSilence)
        add(VoiceQuickSheetRow.SonicQuality)
        add(VoiceQuickSheetRow.Advanced)
    }
