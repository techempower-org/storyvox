package `in`.jphe.storyvox.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/** WCAG conformance rating for a foreground/background pair (normal-size body text). */
enum class WcagRating {
    /** < 3:1 — fails even large-text AA. */
    FAIL,

    /** ≥ 3:1 but < 4.5:1 — passes AA for large text only. */
    AA_LARGE,

    /** ≥ 4.5:1 but < 7:1 — passes AA for normal body text. */
    AA,

    /** ≥ 7:1 — passes AAA for normal body text. */
    AAA,
}

/**
 * WCAG 2.x relative-luminance contrast ratio.
 *
 * Used by the #993 custom reading-theme picker to validate (and warn on) a
 * user-chosen foreground/background pair before they apply it. We *warn* but
 * never hard-block: a deliberate low-contrast choice is the user's call (an
 * a11y tool that overrides the user isn't accessible), and the presets are
 * always one tap away. The math is pure JVM so it unit-tests without
 * Robolectric.
 *
 * Formula (WCAG 2.1, "relative luminance" + "contrast ratio"):
 *   - linearize each sRGB channel c in [0,1]: c ≤ 0.03928 ? c/12.92 : ((c+0.055)/1.055)^2.4
 *   - L = 0.2126·R + 0.7152·G + 0.0722·B
 *   - ratio = (Llighter + 0.05) / (Ldarker + 0.05), in [1, 21]
 */
object WcagContrast {

    private fun linearize(channel: Float): Double {
        val c = channel.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    /** WCAG relative luminance of [color], ignoring alpha (text is composited opaque). */
    fun relativeLuminance(color: Color): Double =
        0.2126 * linearize(color.red) +
            0.7152 * linearize(color.green) +
            0.0722 * linearize(color.blue)

    /** Contrast ratio between two colors, in `[1.0, 21.0]`. Symmetric in its arguments. */
    fun ratio(a: Color, b: Color): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /** AA for normal body text: ≥ 4.5:1. */
    fun passesAaNormal(fg: Color, bg: Color): Boolean = ratio(fg, bg) >= 4.5

    /** AA for large text (≥18pt, or ≥14pt bold): ≥ 3:1. */
    fun passesAaLarge(fg: Color, bg: Color): Boolean = ratio(fg, bg) >= 3.0

    /** Classify a foreground/background pair against the WCAG body-text thresholds. */
    fun rating(fg: Color, bg: Color): WcagRating {
        val r = ratio(fg, bg)
        return when {
            r >= 7.0 -> WcagRating.AAA
            r >= 4.5 -> WcagRating.AA
            r >= 3.0 -> WcagRating.AA_LARGE
            else -> WcagRating.FAIL
        }
    }
}
