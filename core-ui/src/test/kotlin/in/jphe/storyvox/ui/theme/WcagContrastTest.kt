package `in`.jphe.storyvox.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the WCAG 2.x relative-luminance contrast ratio.
 *
 * Compose [Color] and the math are pure JVM — no Robolectric needed (same
 * approach as ReaderTypographyTest). Reference ratios are the canonical
 * WCAG values: black-on-white = 21:1 exactly, white-on-white = 1:1.
 */
class WcagContrastTest {

    private val white = Color(0xFFFFFFFF)
    private val black = Color(0xFF000000)

    @Test
    fun `black on white is the maximum 21 to 1`() {
        assertEquals(21.0, WcagContrast.ratio(black, white), 0.05)
    }

    @Test
    fun `white on white is the minimum 1 to 1`() {
        assertEquals(1.0, WcagContrast.ratio(white, white), 0.001)
    }

    @Test
    fun `ratio is symmetric — order of args does not matter`() {
        val a = Color(0xFF333333)
        val b = Color(0xFFCCCCCC)
        assertEquals(WcagContrast.ratio(a, b), WcagContrast.ratio(b, a), 0.0001)
    }

    @Test
    fun `mid grey on white — known reference around 5_3 to 1`() {
        // #767676 on white is the canonical "just passes AA" grey: ~4.54:1.
        val grey = Color(0xFF767676)
        assertEquals(4.54, WcagContrast.ratio(grey, white), 0.1)
    }

    @Test
    fun `sepia ink on cream paper clears AA for body text`() {
        // Sepia preset: dark-brown ink on warm cream.
        val ink = Color(0xFF4A3A28)
        val paper = Color(0xFFF4ECD8)
        assertTrue(WcagContrast.ratio(ink, paper) >= 4.5)
    }

    @Test
    fun `passesAaNormal threshold is 4_5 to 1`() {
        // A pair at exactly ~4.5 passes; a clearly-lower pair fails.
        assertTrue(WcagContrast.passesAaNormal(black, white))
        assertFalse(WcagContrast.passesAaNormal(Color(0xFFAAAAAA), white)) // ~2.0:1
    }

    @Test
    fun `passesAaLarge is the laxer 3 to 1 threshold`() {
        // A pair between 3 and 4.5 fails normal but passes large.
        val grey = Color(0xFF949494) // ~3.0:1 on white
        assertFalse(WcagContrast.passesAaNormal(grey, white))
        assertTrue(WcagContrast.passesAaLarge(grey, white))
    }

    @Test
    fun `rating classifies the canonical pairs`() {
        assertEquals(WcagRating.AAA, WcagContrast.rating(black, white))      // 21:1
        assertEquals(WcagRating.FAIL, WcagContrast.rating(white, white))     // 1:1
    }
}
