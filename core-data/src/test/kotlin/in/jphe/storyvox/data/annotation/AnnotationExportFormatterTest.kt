package `in`.jphe.storyvox.data.annotation

import `in`.jphe.storyvox.data.annotation.AnnotationExportFormatter.ChapterGroup
import `in`.jphe.storyvox.data.annotation.AnnotationExportFormatter.Format
import `in`.jphe.storyvox.data.db.entity.Annotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #999 — pure-function coverage for the highlights/notes export formatter.
 * No Room, no Context: just shape-in → string-out.
 */
class AnnotationExportFormatterTest {

    private fun a(quoted: String, note: String? = null) = Annotation(
        id = "x", fictionId = "f", chapterId = "c",
        startOffset = 0, endOffset = quoted.length, color = "YELLOW",
        note = note, quotedText = quoted, createdAt = 0L, updatedAt = 0L,
    )

    @Test fun `markdown includes title, chapter headers, quotes and notes`() {
        val out = AnnotationExportFormatter.format(
            fictionTitle = "Mother of Learning",
            groups = listOf(
                ChapterGroup("Chapter 1", listOf(a("a passage", "my note"), a("another bit"))),
                ChapterGroup("Chapter 2", listOf(a("deep thought", "important"))),
            ),
            format = Format.MARKDOWN,
        )
        assertTrue(out.startsWith("# Mother of Learning"))
        assertTrue("chapter 1 header", out.contains("## Chapter 1"))
        assertTrue("chapter 2 header", out.contains("## Chapter 2"))
        assertTrue("quoted passage as blockquote", out.contains("> a passage"))
        assertTrue("note rendered", out.contains("my note"))
        assertTrue("note-less highlight still shows quote", out.contains("> another bit"))
        assertTrue("second chapter note", out.contains("important"))
    }

    @Test fun `plain text uses quotes and Note prefix`() {
        val out = AnnotationExportFormatter.format(
            fictionTitle = "MoL",
            groups = listOf(ChapterGroup("Ch1", listOf(a("hello world", "remember")))),
            format = Format.PLAIN,
        )
        assertTrue(out.startsWith("MoL"))
        assertTrue("plain has no markdown header", !out.contains("# MoL"))
        assertTrue("quoted with curly quotes", out.contains("“hello world”"))
        assertTrue("note prefixed", out.contains("Note: remember"))
    }

    @Test fun `empty groups yield a friendly no-highlights line, not a blank file`() {
        val md = AnnotationExportFormatter.format("Empty Book", emptyList(), Format.MARKDOWN)
        assertTrue(md.contains("No highlights yet."))
        val txt = AnnotationExportFormatter.format("Empty Book", emptyList(), Format.PLAIN)
        assertTrue(txt.contains("No highlights yet."))
    }

    @Test fun `chapters with zero annotations are skipped`() {
        val out = AnnotationExportFormatter.format(
            fictionTitle = "Book",
            groups = listOf(
                ChapterGroup("Has none", emptyList()),
                ChapterGroup("Has one", listOf(a("kept"))),
            ),
            format = Format.MARKDOWN,
        )
        assertFalse("empty chapter header must be omitted", out.contains("Has none"))
        assertTrue("non-empty chapter header present", out.contains("## Has one"))
    }

    @Test fun `multiline quoted text is collapsed to a single line`() {
        val out = AnnotationExportFormatter.format(
            fictionTitle = "Book",
            groups = listOf(ChapterGroup("Ch", listOf(a("line one\nline two")))),
            format = Format.MARKDOWN,
        )
        assertTrue("newline collapsed inside the quote", out.contains("> line one line two"))
        assertFalse("no stray mid-quote newline", out.contains("> line one\nline two"))
    }

    @Test fun `blank note is treated as no note`() {
        val out = AnnotationExportFormatter.format(
            fictionTitle = "Book",
            groups = listOf(ChapterGroup("Ch", listOf(a("quote", note = "   ")))),
            format = Format.PLAIN,
        )
        assertEquals("blank note must not render a Note line", false, out.contains("Note:"))
    }
}
