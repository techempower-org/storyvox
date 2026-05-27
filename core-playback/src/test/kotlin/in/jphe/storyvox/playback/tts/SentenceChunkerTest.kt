package `in`.jphe.storyvox.playback.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SentenceChunkerTest {

    private val chunker = SentenceChunker()
    private val locale = Locale.US

    @Test fun `empty text yields empty list`() {
        assertEquals(emptyList<Sentence>(), chunker.chunk("", locale))
    }

    @Test fun `whitespace-only text yields empty list`() {
        assertEquals(emptyList<Sentence>(), chunker.chunk("   \n\t  ", locale))
    }

    @Test fun `single sentence with no terminator is one chunk`() {
        val out = chunker.chunk("Hello world", locale)
        assertEquals(1, out.size)
        assertEquals(0, out[0].index)
        assertEquals("Hello world", out[0].text)
        assertEquals(0, out[0].startChar)
        assertEquals("Hello world".length, out[0].endChar)
    }

    @Test fun `three sentences split on terminators`() {
        val text = "First. Second! Third?"
        val out = chunker.chunk(text, locale)
        assertEquals(3, out.size)
        assertEquals(listOf("First.", "Second!", "Third?"), out.map { it.text })
        assertEquals(listOf(0, 1, 2), out.map { it.index })
    }

    @Test fun `sentence indices are zero-based and contiguous`() {
        val out = chunker.chunk("A. B. C. D. E.", locale)
        assertEquals(5, out.size)
        out.forEachIndexed { i, s -> assertEquals(i, s.index) }
    }

    @Test fun `endChar spans to next sentence startChar (contiguous partitioning)`() {
        val text = "First sentence.   Second sentence after extra spaces. "
        val out = chunker.chunk(text, locale)
        for (i in 0 until out.size - 1) {
            assertEquals(
                "endChar of sentence $i must equal startChar of sentence ${i + 1}",
                out[i + 1].startChar,
                out[i].endChar,
            )
        }
        assertEquals("last endChar must be text.length", text.length, out.last().endChar)
    }

    @Test fun `startChar points at first non-whitespace of trimmed sentence`() {
        val text = "First.   Second."
        val out = chunker.chunk(text, locale)
        assertEquals(2, out.size)
        // "First." starts at offset 0, "Second." starts at offset 9 (after "First.   ")
        assertEquals(0, out[0].startChar)
        assertEquals(text.indexOf("Second."), out[1].startChar)
    }

    @Test fun `text slice at startChar starts with sentence text`() {
        val text = "Alpha is first. Bravo is second. Charlie is third."
        val out = chunker.chunk(text, locale)
        out.forEach { s ->
            val slice = text.substring(s.startChar, s.endChar)
            assertTrue(
                "slice '$slice' should start with sentence text '${s.text}'",
                slice.startsWith(s.text),
            )
        }
    }

    @Test fun `leading whitespace before first sentence is excluded`() {
        val text = "    Hello there."
        val out = chunker.chunk(text, locale)
        assertEquals(1, out.size)
        assertEquals("Hello there.", out[0].text)
        assertEquals(text.indexOf("Hello"), out[0].startChar)
    }

    @Test fun `multiple consecutive whitespace runs do not produce empty sentences`() {
        val text = "One.\n\n\n   Two.\t\t\tThree."
        val out = chunker.chunk(text, locale)
        assertEquals(3, out.size)
        assertTrue(out.all { it.text.isNotEmpty() })
    }

    @Test fun `utteranceId encodes sentence and sub-index`() {
        assertEquals("s0_p0", chunker.utteranceId(0))
        assertEquals("s0_p0", chunker.utteranceId(0, 0))
        assertEquals("s5_p2", chunker.utteranceId(5, 2))
        assertEquals("s42_p7", chunker.utteranceId(42, 7))
    }

    @Test fun `parseSentenceIndex round-trips utteranceId`() {
        listOf(0 to 0, 1 to 0, 5 to 2, 42 to 7, 999 to 0).forEach { (s, p) ->
            val id = chunker.utteranceId(s, p)
            assertEquals("round-trip on $id", s, chunker.parseSentenceIndex(id))
        }
    }

    @Test fun `parseSentenceIndex returns null for malformed ids`() {
        assertNull(chunker.parseSentenceIndex("garbage"))
        assertNull(chunker.parseSentenceIndex("sX_p0"))
        assertNull(chunker.parseSentenceIndex(""))
    }

    @Test fun `parseSentenceIndex handles missing sub-index suffix`() {
        // No "_p" — substringBefore("_") returns the whole string after "s",
        // which should still parse if numeric.
        assertEquals(3, chunker.parseSentenceIndex("s3"))
    }

    // ── detectLocale tests ──────────────────────────────────────────────

    @Test fun `detectLocale returns fallback for empty text`() {
        assertEquals(Locale.FRENCH, detectLocale("", Locale.FRENCH))
    }

    @Test fun `detectLocale returns fallback for whitespace-only text`() {
        assertEquals(Locale.GERMAN, detectLocale("   \n\t  ", Locale.GERMAN))
    }

    @Test fun `detectLocale returns fallback for Latin text`() {
        val fallback = Locale.US
        assertEquals(fallback, detectLocale("Hello world. This is English.", fallback))
    }

    @Test fun `detectLocale returns Japanese for hiragana-heavy text`() {
        assertEquals(Locale.JAPANESE, detectLocale("これは日本語のテストです。次の文も日本語です。"))
    }

    @Test fun `detectLocale returns Japanese for katakana-heavy text`() {
        assertEquals(Locale.JAPANESE, detectLocale("カタカナのテキストです。テストケースです。"))
    }

    @Test fun `detectLocale returns Chinese for Han-only text`() {
        assertEquals(Locale.CHINESE, detectLocale("这是中文测试。下一句也是中文。"))
    }

    @Test fun `detectLocale returns Korean for Hangul text`() {
        assertEquals(Locale.KOREAN, detectLocale("이것은 한국어 테스트입니다. 다음 문장도 한국어입니다."))
    }

    @Test fun `detectLocale returns Thai for Thai text`() {
        assertEquals(Locale("th"), detectLocale("นี่คือการทดสอบภาษาไทย ประโยคถัดไปก็เป็นภาษาไทย"))
    }

    @Test fun `detectLocale returns Arabic for Arabic text`() {
        assertEquals(Locale("ar"), detectLocale("هذا اختبار باللغة العربية. الجملة التالية أيضاً بالعربية."))
    }

    @Test fun `detectLocale returns Hebrew for Hebrew text`() {
        assertEquals(Locale("he"), detectLocale("זהו מבחן בעברית. המשפט הבא גם בעברית."))
    }

    @Test fun `detectLocale ignores punctuation and digits in classification`() {
        // Digits and punctuation are COMMON script — only actual CJK carries weight.
        assertEquals(Locale.JAPANESE, detectLocale("123 これはテスト!!! 456 もう一つ。"))
    }

    @Test fun `chunk uses detected locale for Japanese text`() {
        // Japanese sentence endings use '。' — BreakIterator with Japanese
        // locale should split on them correctly.
        val text = "最初の文。二番目の文。三番目の文。"
        val out = chunker.chunk(text, detectLocale(text))
        assertEquals(3, out.size)
        assertEquals("最初の文。", out[0].text)
        assertEquals("二番目の文。", out[1].text)
        assertEquals("三番目の文。", out[2].text)
    }
}
