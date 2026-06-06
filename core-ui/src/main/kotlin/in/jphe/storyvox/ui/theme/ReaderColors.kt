package `in`.jphe.storyvox.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Reading-theme choice for the chapter-reading surface (#993).
 *
 * Separate from the *app* theme: a user may want sepia paper for reading while
 * keeping the brand dark chrome everywhere else. Stored as a stable enum (not a
 * [Color] triple) so it persists as a plain string. [Default] means "use the
 * app theme" — the reader renders exactly as it does today.
 */
enum class ReaderTheme {
    /** Inherit the app theme — no reader-specific tint. */
    Default,

    /** Warm light paper (the app's existing paper-cream light surface). */
    Light,

    /** Warm dark (the app's existing Library Nocturne dark surface). */
    Dark,

    /** Classic sepia — dark-brown ink on aged-paper cream. */
    Sepia,

    /** Soft cream — near-black ink on a gentle off-white, lower glare than pure white. */
    Cream,

    /** Maximal-legibility: pure black ink on pure white (AAA). */
    HighContrastBlackOnWhite,

    /** Maximal-legibility inverse: pure white ink on pure black (AAA), low light. */
    HighContrastWhiteOnBlack,

    /** User-chosen foreground/background pair (the Irlen-style custom overlay). */
    Custom,
}

/**
 * Resolved reading-surface colour triple.
 *
 * - [foreground] — chapter body text + chapter title.
 * - [background] — the reader surface behind the text.
 * - [accent] — the sentence-highlight underline (kept legible against [background]).
 */
@Immutable
data class ReaderColorTriple(
    val foreground: Color,
    val background: Color,
    val accent: Color,
)

/**
 * Reading-surface colours for the current reader, consumed via [LocalReaderColors]
 * and provided from the app layer (which owns the persisted setting) — the same
 * dependency-inversion seam used by [LocalReaderTypography] / [LocalSpacing].
 *
 * When [active] is false (the default), [resolved] is null and the reader renders
 * with `MaterialTheme` colours exactly as it does today. The renderer only applies
 * a tint when [active] — so existing readers see no change until they opt in (#993).
 */
@Immutable
data class ReaderColors(
    val active: Boolean = false,
    val resolved: ReaderColorTriple? = null,
) {
    companion object {
        // ── Preset triples ──────────────────────────────────────────────
        // Each preset's fg/bg clears at least AA-large (3:1); the two
        // high-contrast presets clear AAA (7:1). Accents are chosen to stay
        // ≥3:1 on their own background so the sentence underline never
        // vanishes. (Verified by ReaderColorsTest.)

        private val LIGHT = ReaderColorTriple(
            foreground = SurfaceTokens.OnSurfaceLight,   // #1A1614
            background = SurfaceTokens.SurfaceLight,      // #F4EDE2 paper-cream
            accent = BrassRamp.Brass600,                  // #5A431F — brass on cream
        )
        private val DARK = ReaderColorTriple(
            foreground = SurfaceTokens.OnSurfaceDark,    // #E8DFD1
            background = SurfaceTokens.SurfaceDark,        // #0E0C12
            accent = BrassRamp.Brass400,                  // #C9A774 — brass on dark
        )
        private val SEPIA = ReaderColorTriple(
            foreground = Color(0xFF4A3A28),               // dark-brown ink
            background = Color(0xFFF4ECD8),               // aged-paper cream
            accent = Color(0xFF7A4A1F),                   // deeper sepia-brown accent
        )
        private val CREAM = ReaderColorTriple(
            foreground = Color(0xFF2A2620),               // near-black warm ink
            background = Color(0xFFFBF6EC),               // soft off-white
            accent = BrassRamp.Brass600,                  // #5A431F
        )
        private val HC_BLACK_ON_WHITE = ReaderColorTriple(
            foreground = Color(0xFF000000),
            background = Color(0xFFFFFFFF),
            accent = Color(0xFF5A3E10),                   // dark brass — AAA on white
        )
        private val HC_WHITE_ON_BLACK = ReaderColorTriple(
            foreground = Color(0xFFFFFFFF),
            background = Color(0xFF000000),
            accent = HighContrastTokens.BrassHc500,       // #FFC14A — bright brass on black
        )

        /** Build [ReaderColors] for a preset. [ReaderTheme.Default] → inactive. */
        fun forTheme(theme: ReaderTheme): ReaderColors = when (theme) {
            ReaderTheme.Default -> ReaderColors()
            ReaderTheme.Light -> ReaderColors(active = true, resolved = LIGHT)
            ReaderTheme.Dark -> ReaderColors(active = true, resolved = DARK)
            ReaderTheme.Sepia -> ReaderColors(active = true, resolved = SEPIA)
            ReaderTheme.Cream -> ReaderColors(active = true, resolved = CREAM)
            ReaderTheme.HighContrastBlackOnWhite ->
                ReaderColors(active = true, resolved = HC_BLACK_ON_WHITE)
            ReaderTheme.HighContrastWhiteOnBlack ->
                ReaderColors(active = true, resolved = HC_WHITE_ON_BLACK)
            // Custom needs the user's colours — callers must use [custom] /
            // [resolve]; a bare Custom with no colours degrades to inactive.
            ReaderTheme.Custom -> ReaderColors()
        }

        /**
         * Build [ReaderColors] for a user-chosen foreground/background pair.
         * The accent is derived from the foreground so it always tracks the
         * user's ink colour (and therefore stays legible against their bg).
         */
        fun custom(foreground: Color, background: Color): ReaderColors =
            ReaderColors(
                active = true,
                resolved = ReaderColorTriple(
                    foreground = foreground,
                    background = background,
                    accent = foreground,
                ),
            )

        /**
         * Settings-layer entry point: turn the persisted `(theme, customFgArgb,
         * customBgArgb)` triple into [ReaderColors]. Custom colours persist as
         * ARGB ints (`Color.toArgb()`); `0` means "unset", in which case Custom
         * degrades to inactive rather than rendering a transparent surface.
         */
        fun resolve(theme: ReaderTheme, customFgArgb: Int, customBgArgb: Int): ReaderColors =
            if (theme == ReaderTheme.Custom) {
                if (customFgArgb == 0 || customBgArgb == 0) {
                    ReaderColors()
                } else {
                    custom(Color(customFgArgb), Color(customBgArgb))
                }
            } else {
                forTheme(theme)
            }
    }
}

/**
 * Reading-surface colours for the current reader. Defaults to *inactive* (the
 * reader uses `MaterialTheme` colours); the app layer overrides it via
 * `CompositionLocalProvider` around the reader when a theme is selected.
 */
val LocalReaderColors = staticCompositionLocalOf { ReaderColors() }
