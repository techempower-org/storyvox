package `in`.jphe.storyvox.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.feature.api.ThemeOverride
import `in`.jphe.storyvox.ui.theme.LocalReaderTypography
import `in`.jphe.storyvox.ui.theme.LocalSpacing
import `in`.jphe.storyvox.ui.theme.ReaderColors
import `in`.jphe.storyvox.ui.theme.ReaderTheme
import `in`.jphe.storyvox.ui.theme.ReaderFontFamily
import `in`.jphe.storyvox.ui.theme.ReaderTypography
import `in`.jphe.storyvox.ui.theme.WcagContrast
import `in`.jphe.storyvox.ui.theme.WcagRating
import kotlin.math.roundToInt

/**
 * Settings → Reading subscreen (follow-up to #440 / #467).
 *
 * Visual reading knobs:
 *  - App theme override (System / Dark / Light, shipped in v0.5.32 #427).
 *  - Shake-to-extend sleep timer (#150).
 *  - Reading-color theme + custom overlay (#993) — tints the reading page
 *    only, leaving app chrome alone. Device-local pref.
 *  - Reading-text typography (#992): font family (incl. OpenDyslexic /
 *    Atkinson Hyperlegible), text size, line / letter / paragraph spacing.
 *    These target the chapter reading surface specifically — they do NOT
 *    scale app chrome (that is the separate Accessibility font-scale knob).
 *
 * The legacy long-scroll [SettingsScreen] still renders the theme + shake
 * rows under the "Reading" section heading; this subscreen is the curated
 * single-purpose surface reached from the hub.
 */
@Composable
fun ReadingSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current

    SettingsSubscreenScaffold(title = stringResource(R.string.settings_reading_title), onBack = onBack) { padding ->
        val s = state.settings ?: run {
            SettingsSkeleton(modifier = Modifier.fillMaxSize().padding(padding).padding(spacing.md))
            return@SettingsSubscreenScaffold
        }
        SettingsSubscreenBody(padding) {
            SettingsGroupCard {
                SettingsSegmentedBlock(
                    title = stringResource(R.string.settings_reading_theme_title),
                    subtitle = stringResource(R.string.settings_reading_theme_subtitle),
                    options = ThemeOverride.entries.map { it.name },
                    selectedIndex = ThemeOverride.entries.indexOf(s.themeOverride).coerceAtLeast(0),
                    onSelected = { idx -> viewModel.setTheme(ThemeOverride.entries[idx]) },
                )

                SettingsSwitchRow(
                    title = stringResource(R.string.settings_reading_shake_extend_title),
                    subtitle = stringResource(R.string.settings_reading_shake_extend_subtitle),
                    checked = s.sleepShakeToExtendEnabled,
                    onCheckedChange = viewModel::setSleepShakeToExtendEnabled,
                )
            }

            // #993 — reading-color theme (page overlay), separate from the
            // app theme above.
            SettingsSectionHeader(label = stringResource(R.string.settings_reading_overlay_group_title))
            SettingsGroupCard {
                ReadingThemeBlock(
                    selectedTheme = s.readerTheme,
                    customFgArgb = s.readerCustomFgArgb,
                    customBgArgb = s.readerCustomBgArgb,
                    onSelectTheme = viewModel::setReaderTheme,
                    onCustomColors = viewModel::setReaderCustomColors,
                )
            }

            // Reading-text typography (#992). Live preview at the top so the
            // user sees each knob's effect on real chapter-style text.
            SettingsGroupCard {
                ReadingTextGroup(
                    typo = s.readerTypography,
                    onFamily = viewModel::setReaderFontFamily,
                    onSize = viewModel::setReaderFontSize,
                    onLineHeight = viewModel::setReaderLineHeight,
                    onLetterSpacing = viewModel::setReaderLetterSpacing,
                    onParagraphSpacing = viewModel::setReaderParagraphSpacing,
                )
            }
        }
    }
}

/** Maps a [ReaderTheme] to its display-name string resource. */
@Composable
private fun ReaderTheme.label(): String = stringResource(
    when (this) {
        ReaderTheme.Default -> R.string.settings_reading_overlay_theme_default
        ReaderTheme.Light -> R.string.settings_reading_overlay_theme_light
        ReaderTheme.Dark -> R.string.settings_reading_overlay_theme_dark
        ReaderTheme.Sepia -> R.string.settings_reading_overlay_theme_sepia
        ReaderTheme.Cream -> R.string.settings_reading_overlay_theme_cream
        ReaderTheme.HighContrastBlackOnWhite -> R.string.settings_reading_overlay_theme_hc_bw
        ReaderTheme.HighContrastWhiteOnBlack -> R.string.settings_reading_overlay_theme_hc_wb
        ReaderTheme.Custom -> R.string.settings_reading_overlay_theme_custom
    },
)

/**
 * Reading-theme picker: a wrapping grid of preview chips (each rendered in its
 * own colours so the user *sees* sepia / cream / etc.) plus, when Custom is
 * selected, an RGB fg/bg picker with a live WCAG contrast readout.
 */
@Composable
private fun ReadingThemeBlock(
    selectedTheme: ReaderTheme,
    customFgArgb: Int,
    customBgArgb: Int,
    onSelectTheme: (ReaderTheme) -> Unit,
    onCustomColors: (fgArgb: Int, bgArgb: Int) -> Unit,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = stringResource(R.string.settings_reading_overlay_title),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(R.string.settings_reading_overlay_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Preset chips, two rows of four. Each chip previews its own theme.
        ReaderTheme.entries.toList().chunked(4).forEach { rowThemes ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                rowThemes.forEach { theme ->
                    ThemeChip(
                        theme = theme,
                        selected = theme == selectedTheme,
                        customFgArgb = customFgArgb,
                        customBgArgb = customBgArgb,
                        onClick = { onSelectTheme(theme) },
                        modifier = Modifier.width(76.dp),
                    )
                }
            }
        }

        if (selectedTheme == ReaderTheme.Custom) {
            CustomColorPicker(
                fgArgb = customFgArgb.takeIf { it != 0 } ?: Color(0xFF1A1614).toArgb(),
                bgArgb = customBgArgb.takeIf { it != 0 } ?: Color(0xFFF4ECD8).toArgb(),
                onChange = onCustomColors,
            )
        }
    }
}

/** A single selectable preview chip rendered in its theme's own colours. */
@Composable
private fun ThemeChip(
    theme: ReaderTheme,
    selected: Boolean,
    customFgArgb: Int,
    customBgArgb: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val resolved = ReaderColors.resolve(theme, customFgArgb, customBgArgb).resolved
    // Default (and unset-Custom) have no triple — preview with the app surface.
    val bg = resolved?.background ?: MaterialTheme.colorScheme.surface
    val fg = resolved?.foreground ?: MaterialTheme.colorScheme.onSurface
    val label = theme.label()
    val cd = stringResource(R.string.settings_reading_overlay_cd, label)
    val borderColor =
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val borderWidth = if (selected) 2.dp else 1.dp

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .border(borderWidth, borderColor, RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .semantics { contentDescription = cd }
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // "Aa" specimen in the theme's foreground, then the label.
        Text(
            text = "Aa",
            color = fg,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = label,
            color = fg,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * RGB fg/bg picker with a live preview line and a WCAG contrast readout.
 * Deliberately dependency-free (sliders, not a colour wheel) — delivers the
 * accessibility-validated custom pair the issue asks for without a new dep.
 * We warn on low contrast but never block: a deliberate choice is the user's.
 */
@Composable
private fun CustomColorPicker(
    fgArgb: Int,
    bgArgb: Int,
    onChange: (fgArgb: Int, bgArgb: Int) -> Unit,
) {
    val spacing = LocalSpacing.current
    val fg = Color(fgArgb)
    val bg = Color(bgArgb)

    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Text(
            text = stringResource(R.string.settings_reading_custom_title),
            style = MaterialTheme.typography.bodyLarge,
        )

        // Live preview of the current pair.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(bg)
                .padding(spacing.md),
        ) {
            Text(
                text = stringResource(R.string.settings_reading_overlay_preview),
                color = fg,
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        // WCAG readout + rating — warn-only.
        val ratio = WcagContrast.ratio(fg, bg)
        val ratingRes = when (WcagContrast.rating(fg, bg)) {
            WcagRating.AAA -> R.string.settings_reading_contrast_aaa
            WcagRating.AA -> R.string.settings_reading_contrast_aa
            WcagRating.AA_LARGE -> R.string.settings_reading_contrast_aa_large
            WcagRating.FAIL -> R.string.settings_reading_contrast_fail
        }
        val isLow = WcagContrast.rating(fg, bg) == WcagRating.FAIL
        Text(
            text = stringResource(
                R.string.settings_reading_contrast_readout,
                ratio,
                stringResource(ratingRes),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = if (isLow) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        RgbSliders(
            label = stringResource(R.string.settings_reading_custom_fg),
            color = fg,
            onColor = { onChange(it.toArgb(), bgArgb) },
        )
        RgbSliders(
            label = stringResource(R.string.settings_reading_custom_bg),
            color = bg,
            onColor = { onChange(fgArgb, it.toArgb()) },
        )
    }
}

/** Three R/G/B sliders for one opaque colour. */
@Composable
private fun RgbSliders(
    label: String,
    color: Color,
    onColor: (Color) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        ChannelSlider(stringResource(R.string.settings_reading_custom_red), color.red) {
            onColor(color.copy(red = it))
        }
        ChannelSlider(stringResource(R.string.settings_reading_custom_green), color.green) {
            onColor(color.copy(green = it))
        }
        ChannelSlider(stringResource(R.string.settings_reading_custom_blue), color.blue) {
            onColor(color.copy(blue = it))
        }
    }
}

@Composable
private fun ChannelSlider(name: String, value: Float, onValue: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = name.take(1),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .width(16.dp)
                .semantics { contentDescription = name },
        )
        Slider(
            value = value,
            onValueChange = onValue,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ReadingTextGroup(
    typo: ReaderTypography,
    onFamily: (ReaderFontFamily) -> Unit,
    onSize: (Float) -> Unit,
    onLineHeight: (Float) -> Unit,
    onLetterSpacing: (Float) -> Unit,
    onParagraphSpacing: (Float) -> Unit,
) {
    val spacing = LocalSpacing.current

    // Live preview — the same LocalReaderTypography seam the reader uses, so
    // what the user sees here is exactly what the chapter surface will render.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Text(
            text = stringResource(R.string.settings_reading_text_group_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.settings_reading_preview_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CompositionLocalProvider(LocalReaderTypography provides typo) {
            Text(
                text = stringResource(R.string.settings_reading_preview_text),
                style = LocalReaderTypography.current
                    .toTextStyle(MaterialTheme.typography.bodyLarge),
            )
        }
    }

    // Font family.
    val familyOptions = listOf(
        ReaderFontFamily.Default to stringResource(R.string.settings_reading_font_default),
        ReaderFontFamily.OpenDyslexic to stringResource(R.string.settings_reading_font_opendyslexic),
        ReaderFontFamily.AtkinsonHyperlegible to stringResource(R.string.settings_reading_font_atkinson),
    )
    SettingsSegmentedBlock(
        title = stringResource(R.string.settings_reading_font_title),
        subtitle = stringResource(R.string.settings_reading_font_subtitle),
        options = familyOptions.map { it.second },
        selectedIndex = familyOptions.indexOfFirst { it.first == typo.family }.coerceAtLeast(0),
        onSelected = { idx -> onFamily(familyOptions[idx].first) },
    )

    // Text size (sp) — stepped in 1sp increments.
    val sizeCd = stringResource(R.string.settings_reading_size_cd)
    SettingsSliderBlock(
        title = stringResource(R.string.settings_reading_size_title),
        valueLabel = stringResource(R.string.settings_reading_size_value, typo.fontSizeSp.roundToInt()),
        slider = {
            Slider(
                value = typo.fontSizeSp,
                onValueChange = { onSize(it.roundToInt().toFloat()) },
                valueRange = ReaderTypography.MIN_FONT_SIZE_SP..ReaderTypography.MAX_FONT_SIZE_SP,
                // 12..48 sp in whole-sp steps → 35 interior ticks.
                steps = (ReaderTypography.MAX_FONT_SIZE_SP - ReaderTypography.MIN_FONT_SIZE_SP).toInt() - 1,
                modifier = Modifier.semantics {
                    contentDescription = sizeCd
                    stateDescription = "${typo.fontSizeSp.roundToInt()} sp"
                },
            )
        },
    )

    // Line spacing (multiplier).
    val lineCd = stringResource(R.string.settings_reading_line_cd)
    SettingsSliderBlock(
        title = stringResource(R.string.settings_reading_line_title),
        valueLabel = stringResource(R.string.settings_reading_line_value, typo.lineHeightMultiplier),
        slider = {
            Slider(
                value = typo.lineHeightMultiplier,
                onValueChange = onLineHeight,
                valueRange = ReaderTypography.MIN_LINE_HEIGHT..ReaderTypography.MAX_LINE_HEIGHT,
                modifier = Modifier.semantics {
                    contentDescription = lineCd
                    stateDescription = "%.2f".format(typo.lineHeightMultiplier)
                },
            )
        },
    )

    // Letter spacing (em).
    val letterCd = stringResource(R.string.settings_reading_letter_cd)
    SettingsSliderBlock(
        title = stringResource(R.string.settings_reading_letter_title),
        valueLabel = stringResource(R.string.settings_reading_letter_value, typo.letterSpacingEm),
        slider = {
            Slider(
                value = typo.letterSpacingEm,
                onValueChange = onLetterSpacing,
                valueRange = ReaderTypography.MIN_LETTER_SPACING_EM..ReaderTypography.MAX_LETTER_SPACING_EM,
                modifier = Modifier.semantics {
                    contentDescription = letterCd
                    stateDescription = "%.3f".format(typo.letterSpacingEm)
                },
            )
        },
    )

    // Paragraph spacing (multiplier).
    val paragraphCd = stringResource(R.string.settings_reading_paragraph_cd)
    SettingsSliderBlock(
        title = stringResource(R.string.settings_reading_paragraph_title),
        valueLabel = stringResource(R.string.settings_reading_paragraph_value, typo.paragraphSpacingMultiplier),
        slider = {
            Slider(
                value = typo.paragraphSpacingMultiplier,
                onValueChange = onParagraphSpacing,
                valueRange = ReaderTypography.MIN_PARAGRAPH_SPACING..ReaderTypography.MAX_PARAGRAPH_SPACING,
                modifier = Modifier.semantics {
                    contentDescription = paragraphCd
                    stateDescription = "%.2f".format(typo.paragraphSpacingMultiplier)
                },
            )
        },
    )
}
