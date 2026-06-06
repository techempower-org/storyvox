package `in`.jphe.storyvox.source.ocr

import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.source.ocr.config.OcrConfig
import `in`.jphe.storyvox.source.ocr.config.OcrDocument
import `in`.jphe.storyvox.source.ocr.config.OcrPage
import `in`.jphe.storyvox.source.ocr.config.fictionIdForOcrCapture
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #995 — OcrSource read-path contract over a fake [OcrConfig].
 *
 * No Robolectric, no ML Kit, no DataStore — the fake stands in for the
 * `:app`-side store so the source's mapping (document→fiction,
 * page→chapter, search) is verified in pure JVM. Same fake-source
 * posture the project uses in PluginManagerLogicTest.
 */
class OcrSourceTest {

    private fun doc(
        id: String,
        title: String,
        vararg pageTexts: String,
    ) = OcrDocument(
        fictionId = id,
        title = title,
        createdAt = 1_000L,
        pages = pageTexts.mapIndexed { i, t -> OcrPage(index = i, title = "Page ${i + 1}", text = t) },
    )

    private class FakeConfig(private val docs: List<OcrDocument>) : OcrConfig {
        override val documents: Flow<List<OcrDocument>> = flowOf(docs)
        override suspend fun documents(): List<OcrDocument> = docs
        override suspend fun document(fictionId: String): OcrDocument? =
            docs.firstOrNull { it.fictionId == fictionId }
    }

    @Test
    fun `popular lists every scanned document as a fiction`() = runTest {
        val letterTitle = "Letter"
        val recipeTitle = "Recipe"
        val source = OcrSource(FakeConfig(listOf(
            doc("ocr:1", letterTitle, "Dear sir"),
            doc("ocr:2", recipeTitle, "Mix flour"),
        )))

        val res = source.popular()

        assertTrue(res is FictionResult.Success)
        val items = (res as FictionResult.Success).value.items
        assertEquals(2, items.size)
        assertEquals(SourceIds.OCR, items[0].sourceId)
        assertEquals(setOf(letterTitle, recipeTitle), items.map { it.title }.toSet())
    }

    @Test
    fun `fictionDetail maps each page to a chapter`() = runTest {
        val source = OcrSource(FakeConfig(listOf(
            doc("ocr:1", "Doc", "page one text", "page two text", "page three"),
        )))

        val res = source.fictionDetail("ocr:1")

        assertTrue(res is FictionResult.Success)
        val detail = (res as FictionResult.Success).value
        assertEquals(3, detail.chapters.size)
        assertEquals(listOf(0, 1, 2), detail.chapters.map { it.index })
        assertEquals(3, detail.summary.chapterCount)
    }

    @Test
    fun `fictionDetail returns NotFound for unknown id`() = runTest {
        val source = OcrSource(FakeConfig(emptyList()))

        val res = source.fictionDetail("ocr:nope")

        assertTrue(res is FictionResult.NotFound)
    }

    @Test
    fun `chapter returns the stored page text as plain body`() = runTest {
        val source = OcrSource(FakeConfig(listOf(
            doc("ocr:1", "Doc", "first page body", "second page body"),
        )))

        val detail = (source.fictionDetail("ocr:1") as FictionResult.Success).value
        val secondChapterId = detail.chapters[1].id

        val res = source.chapter("ocr:1", secondChapterId)

        assertTrue(res is FictionResult.Success)
        val content = (res as FictionResult.Success).value
        assertEquals("second page body", content.plainBody)
        assertTrue(content.htmlBody.contains("<p>second page body</p>"))
    }

    @Test
    fun `chapter returns NotFound for unknown chapter`() = runTest {
        val source = OcrSource(FakeConfig(listOf(doc("ocr:1", "Doc", "only page"))))

        val res = source.chapter("ocr:1", "ocr:1::p99")

        assertTrue(res is FictionResult.NotFound)
    }

    @Test
    fun `search matches on title and on page text`() = runTest {
        val source = OcrSource(FakeConfig(listOf(
            doc("ocr:1", "Grocery list", "milk eggs bread"),
            doc("ocr:2", "Poem", "the road less travelled"),
        )))

        val byTitle = (source.search(SearchQuery(term = "grocery")) as FictionResult.Success).value.items
        assertEquals(listOf("ocr:1"), byTitle.map { it.id })

        val byBody = (source.search(SearchQuery(term = "travelled")) as FictionResult.Success).value.items
        assertEquals(listOf("ocr:2"), byBody.map { it.id })
    }

    @Test
    fun `empty search term lists all documents`() = runTest {
        val source = OcrSource(FakeConfig(listOf(doc("ocr:1", "A", "x"), doc("ocr:2", "B", "y"))))

        val res = (source.search(SearchQuery(term = "  ")) as FictionResult.Success).value.items

        assertEquals(2, res.size)
    }

    @Test
    fun `fictionIdForOcrCapture is stable for the same inputs and prefixed`() {
        val a = fictionIdForOcrCapture(1234L, "n1")
        val b = fictionIdForOcrCapture(1234L, "n1")
        val c = fictionIdForOcrCapture(1234L, "n2")

        assertEquals(a, b)
        assertTrue(a != c)
        assertTrue(a.startsWith("${SourceIds.OCR}:"))
    }
}
