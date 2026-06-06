package `in`.jphe.storyvox.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import `in`.jphe.storyvox.ui.R

/**
 * Font family for the chapter-reading surface.
 *
 * [Default] keeps the app's curated EB Garamond ([BookBodyFamily]); the other two
 * are bundled offline a11y fonts (#992). Stored as a stable enum (not a [FontFamily])
 * so it can be persisted in settings and synced as a plain string.
 */
enum class ReaderFontFamily {
    /** EB Garamond — the app's default long-form reading face. */
    Default,

    /** OpenDyslexic — weighted bottoms / varied shapes to reduce letter-swapping. */
    OpenDyslexic,

    /** Atkinson Hyperlegible — Braille-Institute face tuned for maximal legibility. */
    AtkinsonHyperlegible,
    ;

    /** Resolve to the concrete Compose [FontFamily] for rendering. */
    fun resolve(): FontFamily = when (this) {
        Default -> BookBodyFamily
        OpenDyslexic -> OpenDyslexicFamily
        AtkinsonHyperlegible -> AtkinsonHyperlegibleFamily
    }
}

/** OpenDyslexic, loaded from bundled `res/font` (offline). */
val OpenDyslexicFamily = FontFamily(
    Font(R.font.opendyslexic_regular, FontWeight.Normal),
    Font(R.font.opendyslexic_bold, FontWeight.Bold),
)

/** Atkinson Hyperlegible, loaded from bundled `res/font` (offline). */
val AtkinsonHyperlegibleFamily = FontFamily(
    Font(R.font.atkinson_hyperlegible_regular, FontWeight.Normal),
    Font(R.font.atkinson_hyperlegible_bold, FontWeight.Bold),
)

/**
 * Reader-surface typography knobs, applied **only** to the chapter text (not app chrome).
 *
 * Values are stored as device-independent *relative* settings so they scale correctly
 * when font size changes: [lineHeightMultiplier] is a multiple of font size and
 * [letterSpacingEm] is in `em`. The defaults reproduce the legacy [ReaderBodyStyle]
 * (18sp / 28sp line height / 0.2sp letter spacing) pixel-for-pixel:
 *   - line height: 28 / 18 = 1.5556
 *   - letter spacing: 0.2sp / 18sp = 0.0111em
 *
 * Consumed via [LocalReaderTypography]; provided from the app layer (which owns the
 * persisted settings) so `core-ui` never depends on DataStore — the same seam used by
 * [LocalSpacing] and [LocalMotion].
 */
@Immutable
data class ReaderTypography(
    val family: ReaderFontFamily = ReaderFontFamily.Default,
    val fontSizeSp: Float = 18f,
    val lineHeightMultiplier: Float = 1.5556f,
    val letterSpacingEm: Float = 0.0111f,
    /** Multiplier on inter-paragraph spacing in the reader; 1.0 = today's spacing. */
    val paragraphSpacingMultiplier: Float = 1f,
) {
    /**
     * Overlay these reader settings onto a [base] text style (typically
     * `MaterialTheme.typography.bodyLarge`). Keeps the base color and other
     * attributes; replaces family, size, line height, and letter spacing.
     */
    fun toTextStyle(base: TextStyle): TextStyle = base.copy(
        fontFamily = family.resolve(),
        fontSize = fontSizeSp.sp,
        lineHeight = (fontSizeSp * lineHeightMultiplier).sp,
        letterSpacing = letterSpacingEm.em,
    )

    companion object {
        const val MIN_FONT_SIZE_SP = 12f
        const val MAX_FONT_SIZE_SP = 48f
        const val MIN_LINE_HEIGHT = 1.0f
        const val MAX_LINE_HEIGHT = 2.5f
        const val MIN_LETTER_SPACING_EM = -0.05f
        const val MAX_LETTER_SPACING_EM = 0.25f
        const val MIN_PARAGRAPH_SPACING = 0.5f
        const val MAX_PARAGRAPH_SPACING = 3.0f

        /**
         * Build a [ReaderTypography], clamping every numeric field into its safe
         * range. Persisted/synced values flow through here so a corrupt or
         * out-of-range stored value can never produce an unreadable surface.
         */
        fun clamped(
            family: ReaderFontFamily = ReaderFontFamily.Default,
            fontSizeSp: Float = 18f,
            lineHeightMultiplier: Float = 1.5556f,
            letterSpacingEm: Float = 0.0111f,
            paragraphSpacingMultiplier: Float = 1f,
        ): ReaderTypography = ReaderTypography(
            family = family,
            fontSizeSp = fontSizeSp.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP),
            lineHeightMultiplier = lineHeightMultiplier.coerceIn(MIN_LINE_HEIGHT, MAX_LINE_HEIGHT),
            letterSpacingEm = letterSpacingEm.coerceIn(MIN_LETTER_SPACING_EM, MAX_LETTER_SPACING_EM),
            paragraphSpacingMultiplier = paragraphSpacingMultiplier.coerceIn(
                MIN_PARAGRAPH_SPACING,
                MAX_PARAGRAPH_SPACING,
            ),
        )
    }
}

/**
 * Reader typography for the current reading surface. Defaults to the legacy reader
 * style; the app layer overrides it via `CompositionLocalProvider` around the reader.
 */
val LocalReaderTypography = staticCompositionLocalOf { ReaderTypography() }
