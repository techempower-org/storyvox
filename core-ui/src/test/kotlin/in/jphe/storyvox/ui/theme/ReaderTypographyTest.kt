package `in`.jphe.storyvox.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Issue #992 — pure-JVM coverage for the reader typography value type.
 *
 * The two invariants that matter:
 *  1. The defaults reproduce the legacy reader style ([ReaderBodyStyle] —
 *     18sp / 28sp line height / 0.2sp letter spacing) so existing users see
 *     no change until they opt in.
 *  2. Every numeric field is clamped, so a corrupt local value or an
 *     out-of-range value arriving via cloud sync can never produce an
 *     unreadable surface.
 */
class ReaderTypographyTest {

    private val base = TextStyle(fontSize = 99.sp) // distinct so we can see overrides land

    @Test
    fun `default toTextStyle reproduces the legacy reader metrics`() {
        val style = ReaderTypography().toTextStyle(base)
        // 18sp font, 28sp line height (18 * 1.5556 ≈ 28.0), 0.2sp letter
        // spacing (0.0111em at 18sp ≈ 0.2sp). Assert in the units the data
        // class stores: sp for size + line height, em for letter spacing.
        assertEquals(18f, style.fontSize.value, 0.01f)
        assertEquals(28f, style.lineHeight.value, 0.05f)
        assertEquals(0.0111.em, style.letterSpacing)
    }

    @Test
    fun `default family resolves to the app book body family`() {
        assertSame(BookBodyFamily, ReaderFontFamily.Default.resolve())
        assertSame(OpenDyslexicFamily, ReaderFontFamily.OpenDyslexic.resolve())
        assertSame(AtkinsonHyperlegibleFamily, ReaderFontFamily.AtkinsonHyperlegible.resolve())
    }

    @Test
    fun `toTextStyle scales line height with font size`() {
        val style = ReaderTypography(fontSizeSp = 24f, lineHeightMultiplier = 2.0f).toTextStyle(base)
        assertEquals(24f, style.fontSize.value, 0.01f)
        assertEquals(48f, style.lineHeight.value, 0.01f)
    }

    @Test
    fun `toTextStyle preserves unrelated base attributes`() {
        val coloredBase = TextStyle(fontSize = 10.sp, background = androidx.compose.ui.graphics.Color.Red)
        val style = ReaderTypography().toTextStyle(coloredBase)
        assertEquals(androidx.compose.ui.graphics.Color.Red, style.background)
    }

    @Test
    fun `clamped pins font size into 12 to 48`() {
        assertEquals(12f, ReaderTypography.clamped(fontSizeSp = 4f).fontSizeSp, 0.001f)
        assertEquals(48f, ReaderTypography.clamped(fontSizeSp = 200f).fontSizeSp, 0.001f)
        assertEquals(20f, ReaderTypography.clamped(fontSizeSp = 20f).fontSizeSp, 0.001f)
    }

    @Test
    fun `clamped pins line height into 1_0 to 2_5`() {
        assertEquals(1.0f, ReaderTypography.clamped(lineHeightMultiplier = 0.1f).lineHeightMultiplier, 0.001f)
        assertEquals(2.5f, ReaderTypography.clamped(lineHeightMultiplier = 9f).lineHeightMultiplier, 0.001f)
    }

    @Test
    fun `clamped pins letter spacing into -0_05 to 0_25 em`() {
        assertEquals(-0.05f, ReaderTypography.clamped(letterSpacingEm = -1f).letterSpacingEm, 0.0001f)
        assertEquals(0.25f, ReaderTypography.clamped(letterSpacingEm = 5f).letterSpacingEm, 0.0001f)
    }

    @Test
    fun `clamped pins paragraph spacing into 0_5 to 3_0`() {
        assertEquals(0.5f, ReaderTypography.clamped(paragraphSpacingMultiplier = 0f).paragraphSpacingMultiplier, 0.001f)
        assertEquals(3.0f, ReaderTypography.clamped(paragraphSpacingMultiplier = 99f).paragraphSpacingMultiplier, 0.001f)
    }

    @Test
    fun `non-default family produces a different style than default`() {
        val default = ReaderTypography(family = ReaderFontFamily.Default).toTextStyle(base)
        val dyslexic = ReaderTypography(family = ReaderFontFamily.OpenDyslexic).toTextStyle(base)
        assertNotEquals(default.fontFamily, dyslexic.fontFamily)
    }
}
