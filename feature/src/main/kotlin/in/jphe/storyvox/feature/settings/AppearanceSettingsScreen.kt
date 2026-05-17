package `in`.jphe.storyvox.feature.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.feature.api.CoverStyle
import `in`.jphe.storyvox.feature.api.UiBrassPulseLevel
import `in`.jphe.storyvox.feature.api.UiParticleIntensity
import `in`.jphe.storyvox.feature.api.UiSkeletonStyle
import `in`.jphe.storyvox.ui.component.CoverSourceFamily
import `in`.jphe.storyvox.ui.component.CoverStyleLocal
import `in`.jphe.storyvox.ui.component.FictionCoverThumb
import `in`.jphe.storyvox.ui.component.LocalCoverStyle
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Settings → Appearance subscreen (v0.5.59 — #cover-style-toggle).
 *
 * Surface for visual-style preferences that don't fit cleanly under
 * Reading (theme + sleep timer) or Accessibility (TalkBack +
 * reduced-motion). v0.5.59 ships one row — book-cover fallback style —
 * but the section is named broadly so future visual-style knobs
 * (Library hero density, chip-strip style, etc.) can land alongside.
 *
 * ## Book cover style
 *
 * v0.5.51 (#514) introduced [BrandedCoverTile] as the second-tier
 * fallback when a remote cover URL is missing or fails. JP's audit
 * found the warm gradient + EB-Garamond title made every fallback feel
 * like the cover itself — blurring the distinction between "we have a
 * real cover" and "we synthesized one". The new default reverts to the
 * classic [MonogramSigilTile] (sigil + author initial on dark), which
 * reads more clearly as a placeholder.
 *
 * Three options:
 *  - **Monogram** (default) — classic minimalist sigil tile + author
 *    initial on dark. The visual revert from v0.5.51..v0.5.58.
 *  - **Branded** — the v0.5.51 BrandedCoverTile (warm gradient, EB-
 *    Garamond title, brass border). Bold, magical, claims more visual
 *    space.
 *  - **Cover only** — render the real cover when one loads; otherwise
 *    a dim brass-ring outline placeholder ([DimCoverPlaceholderTile]).
 *    For users who want a strict "real cover or nothing salient" view.
 *
 * A 64-dp live preview chip-row sits under the picker so the user can
 * see all three styles before committing. Each chip is keyboard-
 * focusable and announces its selection state to TalkBack
 * (Role.RadioButton + semantics.selected = true on the active chip).
 */
@Composable
fun AppearanceSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current

    SettingsSubscreenScaffold(title = "Appearance", onBack = onBack) { padding ->
        val s = state.settings ?: run {
            SettingsSkeleton(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(spacing.md),
            )
            return@SettingsSubscreenScaffold
        }
        SettingsSubscreenBody(padding) {
            SettingsGroupCard {
                // Book cover style — three radio-style options + live
                // preview chip-row. The picker writes through the
                // ViewModel; the preview reflects the persisted value
                // (state.settings) so the AnimatedContent crossfade
                // matches what FictionCoverThumb will actually render
                // in the rest of the app.
                val styleOptions = listOf(
                    CoverStyleOption(
                        value = CoverStyle.Monogram,
                        label = "Monogram",
                        subtitle = "Classic minimalist · author initial on dark",
                    ),
                    CoverStyleOption(
                        value = CoverStyle.Branded,
                        label = "Branded",
                        subtitle = "Bold magical · sun-disk + EB Garamond title",
                    ),
                    CoverStyleOption(
                        value = CoverStyle.CoverOnly,
                        label = "Cover only",
                        subtitle = "Real cover when available, dim sigil outline otherwise",
                    ),
                )

                CoverStylePicker(
                    options = styleOptions,
                    selected = s.coverStyle,
                    onSelected = { viewModel.setCoverStyle(it) },
                )

                CoverStylePreviewStrip(
                    selected = s.coverStyle,
                    onSelected = { viewModel.setCoverStyle(it) },
                )
            }

            // Subtle help row — explains the cascade and the upgrade
            // story so users who arrive here after a v0.5.59 upgrade
            // understand why their covers changed.
            SettingsGroupCard {
                SettingsRow(
                    title = "About these covers",
                    subtitle = "Real cover images are always used when available. " +
                        "This setting controls what we render when a fiction has no cover, " +
                        "the cover URL has expired, or the load fails.",
                )
            }

            // Issue #589 — global animation-speed master multiplier.
            // Drives `LocalAnimationSpeedScale` at the NavHost root;
            // every storyvox transition that uses `tweenScaled(N)`
            // honors the chip. Five tiers cover the design space:
            //   Off (0×) — instant; visually identical to ReducedMotion
            //   Slow (0.5×) — half speed, for users who want to savor
            //     the brass-shimmer rhythm; the 5-year-old default.
            //   Normal (1×) — Library Nocturne's hand-tuned cadence.
            //   Brisk (1.5×) — fastpath without flicker.
            //   Fast (2×) — power-user / tablet-audit mode.
            //
            // Per-device pref (not synced) — different ergonomic
            // targets per device.
            SettingsGroupCard {
                val speedOptions = listOf(
                    0f to "Off",
                    0.5f to "Slow",
                    1f to "Normal",
                    1.5f to "Brisk",
                    2f to "Fast",
                )
                val activeScale = s.animationSpeedScale
                val selectedIndex = speedOptions
                    .indexOfFirst { it.first == activeScale }
                    .let { if (it < 0) speedOptions.indexOfFirst { p -> p.first == 1f } else it }
                SettingsSegmentedBlock(
                    title = "Animation speed",
                    subtitle = "Master multiplier on every transition. " +
                        "Off makes animations instant — useful with assistive tech " +
                        "or on slower devices.",
                    options = speedOptions.map { it.second },
                    selectedIndex = selectedIndex,
                    onSelected = { idx ->
                        viewModel.setAnimationSpeedScale(speedOptions[idx].first)
                    },
                )

                // Issue #590 — particle/confetti intensity. Controls
                // the brass-ember overlay density (the calm sparks
                // that drift up from the cover during loading) and
                // the chapter-completion confetti flare. None silences
                // the decoration entirely; Lush doubles the count for
                // a more vivid Library Nocturne ambiance.
                val particleOptions = listOf(
                    UiParticleIntensity.None to "None",
                    UiParticleIntensity.Subtle to "Subtle",
                    UiParticleIntensity.Lush to "Lush",
                )
                val particleIndex = particleOptions
                    .indexOfFirst { it.first == s.particleIntensity }
                    .let { if (it < 0) 1 else it }
                SettingsSegmentedBlock(
                    title = "Particle intensity",
                    subtitle = "Brass-ember sparks + chapter-completion flare. " +
                        "None silences decorative particles; Lush doubles the count.",
                    options = particleOptions.map { it.second },
                    selectedIndex = particleIndex,
                    onSelected = { idx ->
                        viewModel.setParticleIntensity(particleOptions[idx].first)
                    },
                )

                // Issue #591 — skeleton-shimmer style. Off renders a
                // plain rectangle placeholder; Pulse falls back to the
                // pre-#650 alpha-shimmer; Sigil (the default) renders
                // the 3-layered rotating brass sigil from
                // [MagicSkeletonTile].
                val skeletonOptions = listOf(
                    UiSkeletonStyle.Off to "Off",
                    UiSkeletonStyle.Pulse to "Pulse",
                    UiSkeletonStyle.Sigil to "Sigil",
                )
                val skeletonIndex = skeletonOptions
                    .indexOfFirst { it.first == s.skeletonStyle }
                    .let { if (it < 0) 2 else it }
                SettingsSegmentedBlock(
                    title = "Skeleton style",
                    subtitle = "How loading placeholders look. " +
                        "Off = plain rectangle, Pulse = breathing rectangle, " +
                        "Sigil = rotating brass sigil.",
                    options = skeletonOptions.map { it.second },
                    selectedIndex = skeletonIndex,
                    onSelected = { idx ->
                        viewModel.setSkeletonStyle(skeletonOptions[idx].first)
                    },
                )

                // Issue #592 — brass alpha-pulse band. Subtle = 0.7..1.0
                // (narrow breath), Standard = 0.55..1.0 (current),
                // Bold = 0.4..1.0 (wide vivid pulse).
                val pulseOptions = listOf(
                    UiBrassPulseLevel.Subtle to "Subtle",
                    UiBrassPulseLevel.Standard to "Standard",
                    UiBrassPulseLevel.Bold to "Bold",
                )
                val pulseIndex = pulseOptions
                    .indexOfFirst { it.first == s.brassPulseLevel }
                    .let { if (it < 0) 1 else it }
                SettingsSegmentedBlock(
                    title = "Brass pulse",
                    subtitle = "How deep the brass breath cycles on loading dots, " +
                        "the player sigil, and skeleton tiles.",
                    options = pulseOptions.map { it.second },
                    selectedIndex = pulseIndex,
                    onSelected = { idx ->
                        viewModel.setBrassPulseLevel(pulseOptions[idx].first)
                    },
                )
            }
        }
    }
}

internal data class CoverStyleOption(
    val value: CoverStyle,
    val label: String,
    val subtitle: String,
)

/**
 * Three-row radio-style picker. Built on [SettingsRow] so the touch
 * surface (and the larger-touch-targets accessibility expansion)
 * stays consistent with the rest of Settings; the trailing dot makes
 * the selection state legible without TalkBack having to parse a
 * segmented row.
 */
@Composable
private fun CoverStylePicker(
    options: List<CoverStyleOption>,
    selected: CoverStyle,
    onSelected: (CoverStyle) -> Unit,
) {
    val spacing = LocalSpacing.current
    Column {
        Text(
            text = "Book cover style",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(
                start = spacing.md,
                end = spacing.md,
                top = spacing.sm,
            ),
        )
        Text(
            text = "What to show when a fiction has no cover image.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = spacing.md),
        )
        options.forEach { option ->
            val isSelected = option.value == selected
            SettingsRow(
                title = option.label,
                subtitle = option.subtitle,
                onClick = { onSelected(option.value) },
                modifier = Modifier.semantics {
                    role = Role.RadioButton
                    this.selected = isSelected
                    contentDescription = "${option.label}. ${option.subtitle}"
                },
                trailing = {
                    CoverStylePickerDot(selected = isSelected)
                },
            )
        }
    }
}

@Composable
private fun CoverStylePickerDot(selected: Boolean) {
    val color = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    }
    val border = if (selected) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(2.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f))
    }
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(RoundedCornerShape(50))
            .border(border, shape = RoundedCornerShape(50)),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(color),
            )
        }
    }
}

/**
 * Live preview chip-row — three ~64-dp tile previews so the user can
 * see what each style actually looks like before committing. Each
 * chip is tappable as an alternative to the radio picker above; the
 * two stay in sync on the same `s.coverStyle` value.
 *
 * Each preview wraps its tile in a [CompositionLocalProvider] that
 * forces [LocalCoverStyle] to that option's value, so the
 * [FictionCoverThumb] render walks the cascade exactly as it would
 * in the rest of the app — no special preview-only code path that
 * could silently drift from the real cascade.
 *
 * Smoothly cross-fades the active outline across selections via
 * [AnimatedContent] (skipped when the user has reduced-motion on —
 * Compose's `LocalReducedMotion` is already consulted by storyvox's
 * animation primitives; here we use a short fade that's well under
 * the typical reduced-motion budget).
 */
@Composable
private fun CoverStylePreviewStrip(
    selected: CoverStyle,
    onSelected: (CoverStyle) -> Unit,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Text(
            text = "Preview",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            CoverStylePreviewChip(
                label = "Monogram",
                style = CoverStyle.Monogram,
                isSelected = selected == CoverStyle.Monogram,
                onClick = { onSelected(CoverStyle.Monogram) },
                modifier = Modifier.weight(1f),
            )
            CoverStylePreviewChip(
                label = "Branded",
                style = CoverStyle.Branded,
                isSelected = selected == CoverStyle.Branded,
                onClick = { onSelected(CoverStyle.Branded) },
                modifier = Modifier.weight(1f),
            )
            CoverStylePreviewChip(
                label = "Cover only",
                style = CoverStyle.CoverOnly,
                isSelected = selected == CoverStyle.CoverOnly,
                onClick = { onSelected(CoverStyle.CoverOnly) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun CoverStylePreviewChip(
    label: String,
    style: CoverStyle,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val localStyle = when (style) {
        CoverStyle.Monogram -> CoverStyleLocal.Monogram
        CoverStyle.Branded -> CoverStyleLocal.Branded
        CoverStyle.CoverOnly -> CoverStyleLocal.CoverOnly
    }
    val borderColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    }
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .semantics {
                role = Role.RadioButton
                this.selected = isSelected
                contentDescription = "$label preview — tap to select"
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        AnimatedContent(
            targetState = localStyle,
            transitionSpec = {
                fadeIn(animationSpec = androidx.compose.animation.core.tween(180)) togetherWith
                    fadeOut(animationSpec = androidx.compose.animation.core.tween(180))
            },
            label = "cover-style-preview-$label",
        ) { effectiveStyle ->
            CompositionLocalProvider(LocalCoverStyle provides effectiveStyle) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(width = if (isSelected) 2.dp else 1.dp, color = borderColor),
                    modifier = Modifier
                        .width(64.dp)
                        .aspectRatio(2f / 3f),
                ) {
                    // Use a representative title + author so the
                    // Branded tile has content to render. The Monogram
                    // tile reads "SV"; the CoverOnly preview falls
                    // through to DimCoverPlaceholderTile because we
                    // pass coverUrl=null and the style is CoverOnly.
                    FictionCoverThumb(
                        coverUrl = null,
                        title = "Storyvox",
                        monogram = "SV",
                        author = "JP",
                        sourceFamily = CoverSourceFamily.Generic,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
