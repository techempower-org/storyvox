package `in`.jphe.storyvox.data.annotation

import `in`.jphe.storyvox.data.annotation.AnnotationExportFormatter.Format
import `in`.jphe.storyvox.data.db.entity.Annotation
import `in`.jphe.storyvox.data.db.entity.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #999 — pure coverage for the grouping seam + Format helpers behind
 * [ExportAnnotationsUseCase]. The Context-bound `export()` path (FileProvider,
 * cacheDir write) is exercised on-device, not here; this tests the testable
 * core: flat DAO rows → chapter-ordered [AnnotationExportFormatter.ChapterGroup]s.
 */
class ExportAnnotationsUseCaseTest {

    private fun chapter(id: String, index: Int, title: String = "Chapter $index") = Chapter(
        id = id, fictionId = "f", sourceChapterId = id, index = index, title = title,
    )

    private fun annotation(id: String, chapterId: String, quoted: String, note: String? = null) =
        Annotation(
            id = id, fictionId = "f", chapterId = chapterId,
            startOffset = 0, endOffset = quoted.length, color = "YELLOW",
            note = note, quotedText = quoted, createdAt = 0L, updatedAt = 0L,
        )

    @Test fun `groups by chapter and orders by chapter index`() {
        // Chapters out of index order in the input list; annotations reference
        // both. Grouping must emit the chapter-1 group before chapter-2.
        val chapters = listOf(chapter("c2", 1), chapter("c1", 0))
        val annotations = listOf(
            annotation("a1", "c1", "first chapter quote"),
            annotation("a2", "c2", "second chapter quote"),
        )
        val groups = groupAnnotationsByChapter(annotations, chapters)
        assertEquals(2, groups.size)
        assertEquals("Chapter 0", groups[0].chapterTitle)
        assertEquals("Chapter 1", groups[1].chapterTitle)
        assertEquals("first chapter quote", groups[0].annotations.single().quotedText)
    }

    @Test fun `multiple annotations in one chapter stay in one group`() {
        val chapters = listOf(chapter("c1", 0))
        val annotations = listOf(
            annotation("a1", "c1", "alpha"),
            annotation("a2", "c1", "beta"),
        )
        val groups = groupAnnotationsByChapter(annotations, chapters)
        assertEquals(1, groups.size)
        assertEquals(listOf("alpha", "beta"), groups[0].annotations.map { it.quotedText })
    }

    @Test fun `empty annotation list yields no groups`() {
        val groups = groupAnnotationsByChapter(emptyList(), listOf(chapter("c1", 0)))
        assertTrue(groups.isEmpty())
    }

    @Test fun `blank chapter title falls back to Chapter N+1`() {
        val chapters = listOf(chapter("c1", 4, title = ""))
        val annotations = listOf(annotation("a1", "c1", "quote"))
        val groups = groupAnnotationsByChapter(annotations, chapters)
        // index 4 → human-facing "Chapter 5"
        assertEquals("Chapter 5", groups.single().chapterTitle)
    }

    @Test fun `annotation for an unknown chapter still exports, sorted last`() {
        // A chapter row may be missing (e.g. purged) while the annotation
        // survives until the next sync reconcile. Don't drop it — sort it
        // last (Int.MAX_VALUE index) so the highlight isn't silently lost.
        val chapters = listOf(chapter("c1", 0))
        val annotations = listOf(
            annotation("a1", "c1", "known"),
            annotation("a2", "ghost", "orphaned"),
        )
        val groups = groupAnnotationsByChapter(annotations, chapters)
        assertEquals(2, groups.size)
        assertEquals("known", groups[0].annotations.single().quotedText)
        assertEquals("orphaned", groups[1].annotations.single().quotedText)
        assertEquals("Chapter", groups[1].chapterTitle)
    }

    @Test fun `format extension and mime track the chosen format`() {
        assertEquals("md", Format.MARKDOWN.extension())
        assertEquals("txt", Format.PLAIN.extension())
        assertEquals("text/markdown", Format.MARKDOWN.mimeType())
        assertEquals("text/plain", Format.PLAIN.mimeType())
    }
}
