package `in`.jphe.storyvox.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #527 — main-surface speed / sleep timer / voice quick chips.
 *
 * Pure-logic guards for [PlayerQuickChips]:
 *  - speed presets match the Spotify / Apple Music / Pocket Casts /
 *    Libby benchmark set (0.75 / 1.0 / 1.25 / 1.5 / 2.0×),
 *  - sleep timer preset minutes match the 2026-05-15 audit suggestion
 *    (5 / 15 / 30 / 60),
 *  - [isSpeedPresetSelected] only flips one chip selected at a time
 *    (no flashing two presets when speed sits between them),
 *  - [formatSpeedPreset] renders whole numbers as "1×" not "1.00×"
 *    so the chip layout doesn't bloat horizontally.
 */
class PlayerQuickChipsTest {

    @Test
    fun `speed presets match the audit benchmark set`() {
        assertEquals(
            listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f),
            SPEED_PRESETS,
        )
    }

    @Test
    fun `sleep timer presets match the audit suggestion`() {
        assertEquals(
            listOf(5, 15, 30, 60),
            SLEEP_TIMER_PRESETS_MIN,
        )
    }

    @Test
    fun `speed preset exactly matches selects`() {
        assertTrue(isSpeedPresetSelected(1.25f, 1.25f))
        assertTrue(isSpeedPresetSelected(1.0f, 1.0f))
        assertTrue(isSpeedPresetSelected(2.0f, 2.0f))
    }

    @Test
    fun `speed preset selection tolerates float rounding`() {
        // The continuous slider's floats can land at 1.2500001f from
        // ribbon-fine drags; the chip must still flash selected.
        assertTrue(isSpeedPresetSelected(1.2500001f, 1.25f))
        assertTrue(isSpeedPresetSelected(0.99995f, 1.0f))
    }

    @Test
    fun `speed preset selection rejects clearly different speeds`() {
        assertFalse(isSpeedPresetSelected(1.0f, 1.25f))
        assertFalse(isSpeedPresetSelected(1.4f, 1.5f))
        assertFalse(isSpeedPresetSelected(0.5f, 0.75f))
    }

    /**
     * Adjacent presets are spaced 0.25× apart (well above the
     * SPEED_PRESET_EPSILON of 0.01), so a speed between 1.0 and 1.25
     * shouldn't flash both selected. Pinning this so future preset
     * spacing changes don't silently break the chip UX.
     */
    @Test
    fun `speed preset selection never flashes adjacent presets together`() {
        // Speed at 1.125 — halfway between 1.0 and 1.25.
        val between = 1.125f
        val matches = SPEED_PRESETS.count { isSpeedPresetSelected(between, it) }
        assertEquals(
            "Speed at 1.125 should not be ε-close to ANY preset — " +
                "if this fails, either ε is too generous or two presets " +
                "are too close together.",
            0, matches,
        )
    }

    @Test
    fun `format speed preset strips trailing zeroes from whole numbers`() {
        assertEquals("1×", formatSpeedPreset(1.0f))
        assertEquals("2×", formatSpeedPreset(2.0f))
    }

    @Test
    fun `format speed preset keeps two decimals for 0_75 and 1_25`() {
        assertEquals("0.75×", formatSpeedPreset(0.75f))
        assertEquals("1.25×", formatSpeedPreset(1.25f))
        assertEquals("1.5×", formatSpeedPreset(1.5f))
    }

    /**
     * Issue #736 — pin the chapter chip label format. The chip lives
     * on row 4 of [PlayerQuickChips], opens the
     * [`in`.jphe.storyvox.feature.reader.ChapterListSheet`], and its
     * label is the only visible affordance other than the
     * [`Icons.AutoMirrored.Outlined.FormatListBulleted`] anchor icon —
     * if the format silently changes the chip becomes meaningless.
     */
    @Test
    fun `chapter chip label uses count delimiter format`() {
        assertEquals("Chapters · 12", formatChapterChipLabel(12))
        assertEquals("Chapters · 47", formatChapterChipLabel(47))
        assertEquals("Chapters · 156", formatChapterChipLabel(156))
    }

    @Test
    fun `chapter chip label handles single chapter`() {
        // Singular reads "Chapters · 1" — kept plural for the chip
        // label because the row's anchor icon already disambiguates
        // and "Chapter · 1" would read oddly when the typical case is
        // multi-chapter.
        assertEquals("Chapters · 1", formatChapterChipLabel(1))
    }

    @Test
    fun `chapter chip label handles zero chapters`() {
        // Zero shouldn't be rendered (the AudiobookView guards
        // chip visibility with `if (chapterCount > 0)`) but the
        // formatter must still produce a sane string — the chip
        // visibility guard is one edge of the contract, the
        // formatter being non-throwing is the other.
        assertEquals("Chapters · 0", formatChapterChipLabel(0))
    }
}
