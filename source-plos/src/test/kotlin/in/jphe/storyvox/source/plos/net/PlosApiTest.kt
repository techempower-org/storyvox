package `in`.jphe.storyvox.source.plos.net

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * Issue #1058 — pins the Solr `fq=doc_type:full` filter on the
 * free-text Search path.
 *
 * PLOS's Solr index stores each article N+1 times: one `doc_type:full`
 * document plus one fragment doc per section (`/title`, `/abstract`,
 * `/body`, `/references`). [PlosApi.searchRecent] (the Popular tab)
 * drops the fragments with `&fq=doc_type:full`; before #1058
 * [PlosApi.searchQuery] (the Search tab) omitted it, so a free-text
 * query surfaced ~6 duplicate cards per real article.
 *
 * These tests assert the emitted request URL — not a parsed response —
 * because the bug is purely in URL construction. The MockWebServer
 * hands back an empty result set; we only care which query params the
 * client sent.
 */
class PlosApiTest {

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

    /** [PlosApi] pointed at the local MockWebServer. */
    private fun apiPointingAtServer(): PlosApi =
        object : PlosApi(http) {
            override val baseSearch: String = server.url("/search").toString()
        }

    /** Empty-but-valid Solr response so [PlosApi] returns Success. */
    private fun enqueueEmptyResponse() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"response":{"numFound":0,"start":0,"docs":[]}}"""),
        )
    }

    /** Decoded `fq` values from a recorded request's query string. */
    private fun fqValuesOf(rawQuery: String): List<String> =
        rawQuery.split('&')
            .filter { it.startsWith("fq=") }
            .map { URLDecoder.decode(it.removePrefix("fq="), "UTF-8") }

    @Test
    fun `searchQuery filters to doc_type full so section fragments are dropped`() = runTest {
        enqueueEmptyResponse()

        apiPointingAtServer().searchQuery(term = "climate", start = 0, rows = 50)

        val recorded = server.takeRequest()
        val query = recorded.requestUrl?.query.orEmpty()
        val fqs = fqValuesOf(query)
        assertTrue(
            "free-text search must carry fq=doc_type:full to drop the " +
                "per-section fragment docs (#1058); fq's were $fqs",
            fqs.contains("doc_type:full"),
        )
    }

    @Test
    fun `searchRecent still carries doc_type full filter (parity guard)`() = runTest {
        enqueueEmptyResponse()

        apiPointingAtServer().searchRecent(start = 0, rows = 50)

        val recorded = server.takeRequest()
        val query = recorded.requestUrl?.query.orEmpty()
        val fqs = fqValuesOf(query)
        assertTrue(
            "Popular path must keep fq=doc_type:full; fq's were $fqs",
            fqs.contains("doc_type:full"),
        )
    }
}
