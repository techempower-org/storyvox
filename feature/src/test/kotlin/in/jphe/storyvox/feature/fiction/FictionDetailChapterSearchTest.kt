package `in`.jphe.storyvox.feature.fiction

import `in`.jphe.storyvox.feature.api.UiChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #794 — chapter search + jump-to-chapter helpers. Pins the
 * pure-logic surface so the long-webnovel affordance can't silently
 * regress on a refactor of [FictionDetailScreen].
 */
class FictionDetailChapterSearchTest {

    private fun ch(id: String, number: Int, title: String = "Chapter $number") = UiChapter(
        id = id,
        number = number,
        title = title,
        publishedRelative = "1d",
        durationLabel = "12 min",
        isDownloaded = false,
        isFinished = false,
    )

    // --- filterChaptersByQuery ----------------------------------------------

    @Test
    fun `filter returns original list reference when query is blank`() {
        val chapters = listOf(ch("c1", 1), ch("c2", 2))
        assertSame(chapters, filterChaptersByQuery(chapters, ""))
        assertSame(chapters, filterChaptersByQuery(chapters, "   "))
    }

    @Test
    fun `filter matches title substring case insensitively`() {
        val chapters = listOf(
            ch("c1", 1, "The Beginning"),
            ch("c2", 2, "A Strange Awakening"),
            ch("c3", 3, "The End"),
        )
        val out = filterChaptersByQuery(chapters, "the")
        assertEquals(listOf("c1", "c3"), out.map { it.id })
    }

    @Test
    fun `filter trims surrounding whitespace`() {
        val chapters = listOf(ch("c1", 1, "Awakening"))
        val out = filterChaptersByQuery(chapters, "  awak  ")
        assertEquals(1, out.size)
    }

    @Test
    fun `filter returns empty when nothing matches`() {
        val chapters = listOf(ch("c1", 1, "Foo"), ch("c2", 2, "Bar"))
        val out = filterChaptersByQuery(chapters, "qux")
        assertTrue(out.isEmpty())
    }

    // --- parseChapterJumpInput ----------------------------------------------

    @Test
    fun `jump parser accepts bare integer`() {
        assertEquals(412, parseChapterJumpInput("412"))
    }

    @Test
    fun `jump parser strips ch prefix`() {
        assertEquals(412, parseChapterJumpInput("ch 412"))
        assertEquals(7, parseChapterJumpInput("ch.7"))
    }

    @Test
    fun `jump parser strips chapter prefix`() {
        assertEquals(12, parseChapterJumpInput("Chapter 12"))
        assertEquals(12, parseChapterJumpInput("chapter12"))
    }

    @Test
    fun `jump parser returns null on non-numeric input`() {
        assertNull(parseChapterJumpInput("epilogue"))
        assertNull(parseChapterJumpInput(""))
        assertNull(parseChapterJumpInput("   "))
    }

    @Test
    fun `jump parser rejects zero and negative ordinals`() {
        // Match is unsigned digits; "-5" → first integer-shaped token
        // is "5". That's fine — "-5" isn't a real chapter input. But
        // bare "0" must reject because chapter ordinals are 1+.
        assertNull(parseChapterJumpInput("0"))
        assertNull(parseChapterJumpInput("ch 0"))
    }

    // --- findChapterIndexForOrdinal -----------------------------------------

    @Test
    fun `find returns null on empty list`() {
        assertNull(findChapterIndexForOrdinal(emptyList(), 1))
    }

    @Test
    fun `find returns exact number match index`() {
        val chapters = listOf(
            ch("c1", 1, "Foo"),
            ch("c2", 7, "Bar"),
            ch("c3", 12, "Baz"),
        )
        assertEquals(1, findChapterIndexForOrdinal(chapters, 7))
        assertEquals(2, findChapterIndexForOrdinal(chapters, 12))
    }

    @Test
    fun `find falls back to title-prefix when ordinals are sparse`() {
        // Sources like RSS/Notion can have number=0 across the list
        // but still encode the ordinal in the title.
        val chapters = listOf(
            ch("c1", 0, "1: Awakening"),
            ch("c2", 0, "2: The Road"),
            ch("c3", 0, "12: The Hall"),
        )
        assertEquals(0, findChapterIndexForOrdinal(chapters, 1))
        assertEquals(2, findChapterIndexForOrdinal(chapters, 12))
    }

    @Test
    fun `find title prefix does not match longer numeric prefix`() {
        // Asking for "1" must not match a chapter titled "12: …".
        val chapters = listOf(
            ch("c1", 0, "12: The Hall"),
            ch("c2", 0, "1: Awakening"),
        )
        assertEquals(1, findChapterIndexForOrdinal(chapters, 1))
    }

    @Test
    fun `find returns null when no chapter matches`() {
        val chapters = listOf(ch("c1", 1), ch("c2", 2))
        assertNull(findChapterIndexForOrdinal(chapters, 99))
    }
}
