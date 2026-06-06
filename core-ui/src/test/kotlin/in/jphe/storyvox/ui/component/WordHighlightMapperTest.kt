package `in`.jphe.storyvox.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #994 — per-word "karaoke" highlight. The reader maps the audio
 * playhead's progress through the current sentence (0..1) to the word
 * being spoken, so a background highlight can follow the voice word by
 * word.
 *
 * The mapping is the *proportional* model the issue blesses (no true
 * phoneme timing): each word's spoken-time share is weighted by its
 * character length, since longer words take longer to say. These tests
 * pin the pure char-range math; the composable resolves the returned
 * range to pixels via its TextLayoutResult.
 *
 * All char offsets are absolute within the chapter text — the mapper is
 * handed the sentence's [sentenceStart, sentenceEnd) window and returns
 * a sub-range in the same coordinate space (or null when there's no
 * word to highlight).
 */
class WordHighlightMapperTest {

    // "The quick brown fox" — four words, lengths 3/5/5/3, total weight 16.
    // Cumulative fractional boundaries (start-of-word):
    //   The   [0/16 .. 3/16)   = 0.0000 .. 0.1875
    //   quick [3/16 .. 8/16)   = 0.1875 .. 0.5000
    //   brown [8/16 .. 13/16)  = 0.5000 .. 0.8125
    //   fox   [13/16 .. 16/16) = 0.8125 .. 1.0000
    private val text = "The quick brown fox"

    @Test
    fun `progress at sentence start highlights the first word`() {
        val range = currentWordRange(text, sentenceStart = 0, sentenceEnd = text.length, sentenceProgress = 0.0f)
        assertEquals(0, range!!.first)   // "The"
        assertEquals(3, range.last + 1)
    }

    @Test
    fun `progress in the second band highlights the second word`() {
        val range = currentWordRange(text, 0, text.length, sentenceProgress = 0.30f)
        assertEquals("quick", text.substring(range!!.first, range.last + 1))
    }

    @Test
    fun `progress in the third band highlights the third word`() {
        val range = currentWordRange(text, 0, text.length, sentenceProgress = 0.60f)
        assertEquals("brown", text.substring(range!!.first, range.last + 1))
    }

    @Test
    fun `progress at the end highlights the last word, never overruns`() {
        val range = currentWordRange(text, 0, text.length, sentenceProgress = 1.0f)
        assertEquals("fox", text.substring(range!!.first, range.last + 1))
    }

    @Test
    fun `progress is clamped — values past 1 stay on the last word`() {
        val range = currentWordRange(text, 0, text.length, sentenceProgress = 1.9f)
        assertEquals("fox", text.substring(range!!.first, range.last + 1))
    }

    @Test
    fun `progress is clamped — negative values stay on the first word`() {
        val range = currentWordRange(text, 0, text.length, sentenceProgress = -0.5f)
        assertEquals("The", text.substring(range!!.first, range.last + 1))
    }

    @Test
    fun `returns absolute offsets when the sentence starts mid-chapter`() {
        // Sentence "brown fox" begins at char 10 of the chapter text.
        val rangeFirst = currentWordRange(text, sentenceStart = 10, sentenceEnd = text.length, sentenceProgress = 0.0f)
        assertEquals("brown", text.substring(rangeFirst!!.first, rangeFirst.last + 1))
        assertEquals(10, rangeFirst.first) // absolute, not 0

        val rangeLast = currentWordRange(text, sentenceStart = 10, sentenceEnd = text.length, sentenceProgress = 1.0f)
        assertEquals("fox", text.substring(rangeLast!!.first, rangeLast.last + 1))
    }

    @Test
    fun `multiple spaces and punctuation do not produce empty words`() {
        // Leading/trailing whitespace + double space + trailing period.
        val s = "  Hi,  world. "
        val atStart = currentWordRange(s, 0, s.length, sentenceProgress = 0.0f)
        assertEquals("Hi,", s.substring(atStart!!.first, atStart.last + 1))
        val atEnd = currentWordRange(s, 0, s.length, sentenceProgress = 1.0f)
        assertEquals("world.", s.substring(atEnd!!.first, atEnd.last + 1))
    }

    @Test
    fun `empty or blank sentence yields no word`() {
        assertNull(currentWordRange("", 0, 0, 0.5f))
        assertNull(currentWordRange("     ", 0, 5, 0.5f))
    }

    @Test
    fun `single word sentence highlights that word for all progress`() {
        val s = "Hello"
        assertEquals("Hello", s.substring(currentWordRange(s, 0, s.length, 0.0f)!!.let { it.first..it.last }))
        assertEquals("Hello", s.substring(currentWordRange(s, 0, s.length, 0.5f)!!.let { it.first..it.last }))
        assertEquals("Hello", s.substring(currentWordRange(s, 0, s.length, 1.0f)!!.let { it.first..it.last }))
    }
}
