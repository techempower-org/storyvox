package `in`.jphe.storyvox.source.standardebooks

import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.source.standardebooks.net.StandardEbooksApi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Issue #735 — pins the EPUB-download wire behavior for [StandardEbooksApi].
 *
 * Standard Ebooks serves an HTML interstitial ("Your Download Has
 * Started!") at the bare `<author>_<book>.epub` URL and relies on a
 * `<meta http-equiv="refresh">` to deliver the actual binary EPUB at
 * the same path with `?source=download` appended. v0.5.71 and earlier
 * hit the bare URL and persisted the 8KB interstitial as if it were
 * the EPUB, which downstream failed with "META-INF/container.xml
 * missing or has no rootfile".
 *
 * These tests assert:
 *  1. The download request carries `?source=download`.
 *  2. A valid EPUB binary (PK\x03\x04 signature) is returned as Success.
 *  3. SE's HTML interstitial response is rejected as NetworkError —
 *     not silently propagated to the EPUB parser.
 */
class StandardEbooksApiTest {

    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        http = OkHttpClient.Builder()
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** [StandardEbooksApi] pointed at the local MockWebServer. */
    private fun apiPointingAtServer(): StandardEbooksApi =
        object : StandardEbooksApi(http) {
            override val baseUrl: String = server.url("/").toString().trimEnd('/')
        }

    @Test
    fun `downloadEpub appends source-download query to bypass interstitial`() = runTest {
        // Even though the response body is fake, we only care about
        // the request URL the client emits.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/epub+zip")
                .setBody(Buffer().write(pkZipPrefix())),
        )

        apiPointingAtServer().downloadEpub("jane-austen", "pride-and-prejudice")

        val recorded = server.takeRequest()
        val expectedPath = "/ebooks/jane-austen/pride-and-prejudice/downloads/" +
            "jane-austen_pride-and-prejudice.epub?source=download"
        assertEquals(expectedPath, recorded.path)
    }

    @Test
    fun `downloadEpub returns Success when body starts with PK zip signature`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/epub+zip")
                .setBody(Buffer().write(pkZipPrefix())),
        )

        val result = apiPointingAtServer().downloadEpub("a", "b")

        assertTrue(
            "expected Success, got $result",
            result is FictionResult.Success,
        )
        val bytes = (result as FictionResult.Success).value
        assertEquals(0x50.toByte(), bytes[0]) // 'P'
        assertEquals(0x4B.toByte(), bytes[1]) // 'K'
    }

    @Test
    fun `downloadEpub rejects HTML interstitial as NetworkError`() = runTest {
        // Trimmed but representative of the "Your Download Has Started!"
        // XHTML page SE returns at the bare URL. No PK header.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/xhtml+xml; charset=utf-8")
                .setBody(
                    """<?xml version="1.0" encoding="utf-8"?>
                    <!DOCTYPE html>
                    <html xmlns="http://www.w3.org/1999/xhtml" lang="en-US">
                    <head><title>Your Download Has Started!</title>
                    <meta http-equiv="refresh"
                      content="0; url=/ebooks/a/b/downloads/a_b.epub?source=download" />
                    </head><body></body></html>
                    """.trimIndent(),
                ),
        )

        val result = apiPointingAtServer().downloadEpub("a", "b")

        assertTrue(
            "expected NetworkError, got $result",
            result is FictionResult.NetworkError,
        )
        val message = (result as FictionResult.NetworkError).message
        assertNotNull(message)
        assertTrue(
            "error message should hint at the non-EPUB content: $message",
            message!!.contains("non-EPUB", ignoreCase = true) ||
                message.contains("PK", ignoreCase = true),
        )
    }

    /** Local-file-header signature for any zip (and therefore EPUB). */
    private fun pkZipPrefix(): ByteArray =
        byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x00, 0x00, 0x00)
}
