package `in`.jphe.storyvox.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the #993 reading-theme seam. Compose [Color] is pure
 * JVM, so presets, the custom builder, and the "inactive default" no-op can
 * all be asserted without Robolectric (same approach as ReaderTypographyTest).
 */
class ReaderColorsTest {

    @Test
    fun `default reader colors are inactive — renderer falls back to MaterialTheme`() {
        // The CompositionLocal default MUST be inactive so existing readers
        // render exactly as today until they opt into a theme (#993).
        assertFalse(ReaderColors().active)
        assertNull(ReaderColors().resolved)
    }

    @Test
    fun `every non-Default preset resolves to a concrete colour triple`() {
        ReaderTheme.entries
            .filter { it != ReaderTheme.Default && it != ReaderTheme.Custom }
            .forEach { theme ->
                val colors = ReaderColors.forTheme(theme)
                assertTrue("$theme should be active", colors.active)
                assertNotNull("$theme should resolve", colors.resolved)
            }
    }

    @Test
    fun `Default theme is inactive`() {
        assertFalse(ReaderColors.forTheme(ReaderTheme.Default).active)
    }

    @Test
    fun `every preset clears AA-large for body text (3 to 1 floor)`() {
        // Presets are curated; none may ship below the large-text AA floor.
        ReaderTheme.entries
            .filter { it != ReaderTheme.Default && it != ReaderTheme.Custom }
            .forEach { theme ->
                val c = ReaderColors.forTheme(theme).resolved!!
                val ratio = WcagContrast.ratio(c.foreground, c.background)
                assertTrue(
                    "$theme contrast ${"%.2f".format(ratio)} below 3:1",
                    ratio >= 3.0,
                )
            }
    }

    @Test
    fun `high-contrast presets clear AAA (7 to 1)`() {
        listOf(
            ReaderTheme.HighContrastBlackOnWhite,
            ReaderTheme.HighContrastWhiteOnBlack,
        ).forEach { theme ->
            val c = ReaderColors.forTheme(theme).resolved!!
            assertEquals(WcagRating.AAA, WcagContrast.rating(c.foreground, c.background))
        }
    }

    @Test
    fun `custom builder uses the supplied fg and bg`() {
        val fg = Color(0xFF102030)
        val bg = Color(0xFFFAFAFA)
        val c = ReaderColors.custom(fg, bg).resolved!!
        assertEquals(fg, c.foreground)
        assertEquals(bg, c.background)
    }

    @Test
    fun `custom theme is active`() {
        assertTrue(ReaderColors.custom(Color(0xFF000000), Color(0xFFFFFFFF)).active)
    }

    @Test
    fun `accent is legible against its own background (at least AA-large)`() {
        // The sentence underline + chapter title use the accent; it must not
        // vanish into the themed background.
        ReaderTheme.entries
            .filter { it != ReaderTheme.Default && it != ReaderTheme.Custom }
            .forEach { theme ->
                val c = ReaderColors.forTheme(theme).resolved!!
                assertTrue(
                    "$theme accent invisible on its background",
                    WcagContrast.ratio(c.accent, c.background) >= 3.0,
                )
            }
    }

    @Test
    fun `resolve picks preset triple for a preset and custom triple for Custom`() {
        // The settings-layer entry point: (theme, customFgArgb, customBgArgb) → ReaderColors.
        // Custom colours persist as ARGB ints (Color.toArgb); 0 = unset.
        val sepia = ReaderColors.resolve(ReaderTheme.Sepia, customFgArgb = 0, customBgArgb = 0)
        assertEquals(ReaderColors.forTheme(ReaderTheme.Sepia).resolved, sepia.resolved)

        val customFg = 0xFF112233.toInt()
        val customBg = 0xFFEEDDCC.toInt()
        val custom = ReaderColors.resolve(ReaderTheme.Custom, customFg, customBg).resolved!!
        assertEquals(Color(0xFF112233), custom.foreground)
        assertEquals(Color(0xFFEEDDCC), custom.background)
    }

    @Test
    fun `Custom with unset colours falls back to inactive`() {
        // A user who selected Custom but never picked colours shouldn't get a
        // transparent/black surface — degrade to the app theme (inactive).
        assertFalse(ReaderColors.resolve(ReaderTheme.Custom, customFgArgb = 0, customBgArgb = 0).active)
    }
}
