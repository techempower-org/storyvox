package `in`.jphe.storyvox.source.ocr

import `in`.jphe.storyvox.data.ocr.OcrBlock
import `in`.jphe.storyvox.data.ocr.OcrRecognition
import `in`.jphe.storyvox.source.ocr.parse.OcrTextParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #995 — pins the OCR text-shaping contract.
 *
 * ML Kit OCRs line-by-line, so a printed paragraph that wraps across
 * several physical lines comes back as several `\n`-separated lines.
 * Narrating that verbatim pauses after every line. [OcrTextParser]
 * reflows wrapped lines back into paragraphs, de-hyphenates split
 * words, and emits clean HTML + plaintext bodies. These tests fix that
 * behavior so a refactor can't silently regress the cadence — which is
 * the whole point of the accessibility feature.
 */
class OcrTextParserTest {

    @Test
    fun `wrapped lines within a paragraph are joined with spaces`() {
        // A single printed sentence wrapped across three OCR lines.
        val rec = OcrRecognition(
            text = "The quick brown fox\njumps over the\nlazy dog.",
        )

        val body = OcrTextParser.renderPage(rec)

        assertEquals("The quick brown fox jumps over the lazy dog.", body.plainBody)
        assertFalse("no hard breaks inside a paragraph", body.plainBody.contains("\n"))
    }

    @Test
    fun `blank line is a hard paragraph break`() {
        val rec = OcrRecognition(
            text = "First paragraph wraps\nacross two lines.\n\nSecond paragraph here.",
        )

        val paras = OcrTextParser.paragraphsFrom(rec)

        assertEquals(2, paras.size)
        assertEquals("First paragraph wraps across two lines.", paras[0])
        assertEquals("Second paragraph here.", paras[1])
    }

    @Test
    fun `hyphenated word split across a line break is rejoined`() {
        // The single most common OCR-of-print artifact.
        val rec = OcrRecognition(text = "the encyclo-\npedia of birds")

        val body = OcrTextParser.renderPage(rec)

        assertTrue(
            "expected de-hyphenated 'encyclopedia', got: ${body.plainBody}",
            body.plainBody.contains("encyclopedia of birds"),
        )
        assertFalse(body.plainBody.contains("encyclo-"))
    }

    @Test
    fun `a real trailing dash on a non-letter is not eaten`() {
        // "1939-" then "1945" is a date range, not a word split — the
        // char before the hyphen is a digit, so we keep the space-join
        // path rather than gluing.
        val rec = OcrRecognition(text = "the war 1939-\n1945 ended")

        val body = OcrTextParser.renderPage(rec)

        // We must not silently glue "1939-1945" into the previous word's
        // de-hyphenation rule (that rule only fires on letters). Either
        // outcome keeps both numbers; assert both survive.
        assertTrue(body.plainBody.contains("1939"))
        assertTrue(body.plainBody.contains("1945"))
    }

    @Test
    fun `ML Kit blocks become separate paragraphs`() {
        val rec = OcrRecognition(
            text = "ignored when blocks present",
            blocks = listOf(
                OcrBlock("Dear Sir,\nI am writing to you"),
                OcrBlock("Yours faithfully,\nA. Person"),
            ),
        )

        val paras = OcrTextParser.paragraphsFrom(rec)

        assertEquals(2, paras.size)
        assertEquals("Dear Sir, I am writing to you", paras[0])
        assertEquals("Yours faithfully, A. Person", paras[1])
    }

    @Test
    fun `html body wraps each paragraph in p tags and escapes`() {
        val rec = OcrRecognition(
            text = "Tom & Jerry\n\n5 < 10 is true",
        )

        val body = OcrTextParser.renderPage(rec)

        assertEquals(
            "<p>Tom &amp; Jerry</p>\n<p>5 &lt; 10 is true</p>",
            body.htmlBody,
        )
    }

    @Test
    fun `blank scan yields empty bodies`() {
        val rec = OcrRecognition(text = "   \n  \n")

        val body = OcrTextParser.renderPage(rec)

        assertEquals("", body.plainBody)
        assertEquals("", body.htmlBody)
        assertTrue(rec.isEmpty)
    }

    @Test
    fun `suggestTitle returns first non-blank line trimmed`() {
        val rec = OcrRecognition(text = "\n   The Great Gatsby   \nby F. Scott Fitzgerald")

        assertEquals("The Great Gatsby", OcrTextParser.suggestTitle(rec, "Scan"))
    }

    @Test
    fun `suggestTitle falls back when no text`() {
        assertEquals(
            "Scanned page",
            OcrTextParser.suggestTitle(OcrRecognition.EMPTY, "Scanned page"),
        )
    }

    @Test
    fun `suggestTitle uses block text when flat text is blank`() {
        val rec = OcrRecognition(
            text = "",
            blocks = listOf(OcrBlock("Chapter One\nIt was a dark night")),
        )

        assertEquals("Chapter One", OcrTextParser.suggestTitle(rec, "Scan"))
    }

    @Test
    fun `wordCount counts whitespace-separated tokens`() {
        assertEquals(0, OcrTextParser.wordCount("   "))
        assertEquals(1, OcrTextParser.wordCount("hello"))
        assertEquals(4, OcrTextParser.wordCount("the lazy brown fox"))
    }

    @Test
    fun `multiple internal spaces collapse after reflow`() {
        val rec = OcrRecognition(text = "word1    word2\nword3")

        val body = OcrTextParser.renderPage(rec)

        assertEquals("word1 word2 word3", body.plainBody)
        assertFalse(body.plainBody.contains("  "))
    }
}
