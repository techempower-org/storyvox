package `in`.jphe.storyvox.feature.ocr

import `in`.jphe.storyvox.data.ocr.OcrBlock
import `in`.jphe.storyvox.data.ocr.OcrImage
import `in`.jphe.storyvox.data.ocr.OcrRecognition
import `in`.jphe.storyvox.data.ocr.OcrResult
import `in`.jphe.storyvox.data.ocr.OcrTextRecognizer
import `in`.jphe.storyvox.source.ocr.config.OcrDocumentStore
import `in`.jphe.storyvox.source.ocr.config.OcrPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Issue #995 — OcrCaptureViewModel logic over fakes (no ML Kit, no
 * DataStore, no Robolectric). Verifies the capture-accumulate-save flow
 * and the recoverable error / blank-page paths.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OcrCaptureViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeRecognizer(var next: OcrResult) : OcrTextRecognizer {
        var calls = 0
        val seen = mutableListOf<OcrImage>()
        override suspend fun recognize(image: OcrImage): OcrResult {
            calls++
            seen += image
            return next
        }
    }

    private class FakeStore : OcrDocumentStore {
        var savedTitle: String? = null
        var savedPages: List<OcrPage> = emptyList()
        override suspend fun save(title: String, pages: List<OcrPage>): String {
            savedTitle = title
            savedPages = pages
            return "ocr:saved"
        }
        override suspend fun delete(fictionId: String) = Unit // unused in these tests
    }

    private fun success(text: String, vararg blocks: String) = OcrResult.Success(
        OcrRecognition(text = text, blocks = blocks.map { OcrBlock(it) }),
    )

    @Test
    fun `captured image appends a page and seeds the title`() = runTest(dispatcher) {
        val rec = FakeRecognizer(success("The Manifesto\nWe hold these truths"))
        val vm = OcrCaptureViewModel(rec, FakeStore())

        vm.onImageCaptured(byteArrayOf(1, 2, 3), rotationDegrees = 90)
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.state.value
        assertEquals(1, state.pages.size)
        assertEquals("The Manifesto We hold these truths", state.pages[0].text)
        assertEquals("The Manifesto", state.title) // seeded from first line
        assertFalse(state.isRecognizing)
        assertEquals(90, rec.seen[0].rotationDegrees)
    }

    @Test
    fun `second capture appends a second page without overwriting the title`() = runTest(dispatcher) {
        val rec = FakeRecognizer(success("Page one"))
        val vm = OcrCaptureViewModel(rec, FakeStore())

        vm.onImageCaptured(byteArrayOf(1))
        dispatcher.scheduler.advanceUntilIdle()
        vm.onTitleChanged("My letter")
        rec.next = success("Page two")
        vm.onImageCaptured(byteArrayOf(2))
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.state.value
        assertEquals(2, state.pages.size)
        assertEquals("My letter", state.title) // user title not clobbered
        assertEquals(listOf(0, 1), state.pages.map { it.index })
    }

    @Test
    fun `blank recognition surfaces a recoverable error and adds no page`() = runTest(dispatcher) {
        val rec = FakeRecognizer(success("   "))
        val vm = OcrCaptureViewModel(rec, FakeStore())

        vm.onImageCaptured(byteArrayOf(1))
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.pages.isEmpty())
        assertTrue(state.error != null)
        assertFalse(state.isRecognizing)
    }

    @Test
    fun `recognizer failure surfaces its message`() = runTest(dispatcher) {
        val rec = FakeRecognizer(OcrResult.Failure("model unavailable"))
        val vm = OcrCaptureViewModel(rec, FakeStore())

        vm.onImageCaptured(byteArrayOf(1))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("model unavailable", vm.state.value.error)
        assertTrue(vm.state.value.pages.isEmpty())
    }

    @Test
    fun `removePage reindexes remaining pages`() = runTest(dispatcher) {
        val rec = FakeRecognizer(success("a"))
        val vm = OcrCaptureViewModel(rec, FakeStore())
        repeat(3) { i ->
            rec.next = success("page $i")
            vm.onImageCaptured(byteArrayOf(i.toByte()))
            dispatcher.scheduler.advanceUntilIdle()
        }

        vm.removePage(1)

        val pages = vm.state.value.pages
        assertEquals(2, pages.size)
        assertEquals(listOf(0, 1), pages.map { it.index })
        assertEquals(listOf("page 0", "page 2"), pages.map { it.text })
    }

    @Test
    fun `save persists pages and emits savedFictionId`() = runTest(dispatcher) {
        val rec = FakeRecognizer(success("Hello world"))
        val store = FakeStore()
        val vm = OcrCaptureViewModel(rec, store)

        vm.onImageCaptured(byteArrayOf(1))
        dispatcher.scheduler.advanceUntilIdle()
        vm.onTitleChanged("Greetings")
        vm.save()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Greetings", store.savedTitle)
        assertEquals(1, store.savedPages.size)
        assertEquals("Hello world", store.savedPages[0].text)
        assertEquals("ocr:saved", vm.state.value.savedFictionId)

        // One-shot navigation signal clears.
        vm.onNavigatedToSaved()
        assertNull(vm.state.value.savedFictionId)
    }

    @Test
    fun `save is a no-op with no pages`() = runTest(dispatcher) {
        val store = FakeStore()
        val vm = OcrCaptureViewModel(FakeRecognizer(success("x")), store)

        vm.save()
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(store.savedTitle)
        assertNull(vm.state.value.savedFictionId)
    }

    @Test
    fun `blank title falls back to default on save`() = runTest(dispatcher) {
        val rec = FakeRecognizer(
            // No usable first line for the title seed (whitespace then
            // text is fine, but here the recognized text has no leading
            // line — force the fallback by clearing the seeded title).
            success("body only"),
        )
        val store = FakeStore()
        val vm = OcrCaptureViewModel(rec, store)

        vm.onImageCaptured(byteArrayOf(1))
        dispatcher.scheduler.advanceUntilIdle()
        vm.onTitleChanged("   ") // user blanks it
        vm.save()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Scanned document", store.savedTitle)
    }
}
