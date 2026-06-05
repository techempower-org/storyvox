package `in`.jphe.storyvox.source.librivox

import `in`.jphe.storyvox.source.librivox.net.LibriVoxBook
import `in`.jphe.storyvox.source.librivox.net.LibriVoxFeed
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1015 — JSON-parse tests for the LibriVox catalog client.
 *
 * The HTTP round-trip is exercised at integration time (manual
 * verification on the tablet); these tests pin the JSON-shape contract
 * against captured fixtures, which is the bit that breaks loudly if
 * LibriVox revs their feed or the decoder drifts.
 *
 * The [MONTE_CRISTO_FIXTURE] is a real `extended=1` response captured
 * via `curl https://librivox.org/api/feed/audiobooks/?id=47&extended=1&format=json`
 * on 2026-06-05, trimmed to two sections for compactness. Only the
 * fields storyvox decodes are guaranteed to round-trip; unknown fields
 * (genres, translators, url_*) are tolerated via
 * `Json { ignoreUnknownKeys = true }` in the production client.
 */
class LibriVoxApiTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Test fun `parses a real extended audiobooks response into a LibriVoxBook`() {
        val feed = json.decodeFromString(LibriVoxFeed.serializer(), MONTE_CRISTO_FIXTURE)
        assertEquals(1, feed.books.size)
        val book = feed.books.first()
        assertEquals("47", book.id)
        assertEquals("Count of Monte Cristo", book.title)
        assertEquals("English", book.language)
        assertEquals("1844", book.copyrightYear)
        assertEquals("128", book.numSections)
        assertEquals(178995L, book.totalTimeSecs)
    }

    @Test fun `decodes sections with archive_org listen URLs`() {
        val book = json.decodeFromString(LibriVoxFeed.serializer(), MONTE_CRISTO_FIXTURE)
            .books.first()
        assertEquals(2, book.sections.size)
        val first = book.sections.first()
        assertEquals("1", first.sectionNumber)
        assertTrue("section title round-trips", first.title.startsWith("Marseilles"))
        assertTrue(
            "listen_url must be an HTTPS archive.org MP3",
            first.listenUrl.startsWith("https://") && first.listenUrl.endsWith(".mp3"),
        )
        assertEquals("Kristin LeMoine", first.readers.first().displayName)
    }

    @Test fun `authorLabel joins first and last name`() {
        val book = json.decodeFromString(LibriVoxFeed.serializer(), MONTE_CRISTO_FIXTURE)
            .books.first()
        assertEquals("Alexandre Dumas", book.authorLabel())
    }

    @Test fun `authorLabel falls back when no author present`() {
        val book = LibriVoxBook(id = "1", title = "Anon", authors = emptyList())
        assertEquals("Unknown author", book.authorLabel())
    }

    @Test fun `tolerates null section file_name via coerceInputValues`() {
        // The live feed returns `"file_name":null`; the production
        // decoder coerces null → the field default. We don't even decode
        // file_name, but the parse must not throw on the null.
        val book = json.decodeFromString(LibriVoxFeed.serializer(), MONTE_CRISTO_FIXTURE)
            .books.first()
        assertFalse("sections parsed despite null file_name", book.sections.isEmpty())
    }

    @Test fun `tolerates unknown fields in the wire shape`() {
        // Forward-compat — LibriVox carries genres / translators / a
        // dozen url_* fields the model doesn't decode. The decoder must
        // NOT throw on them.
        val raw = """
            {"books":[{
                "id":"99","title":"Future Book","language":"English",
                "num_sections":"3","totaltimesecs":120,
                "authors":[{"first_name":"A","last_name":"B"}],
                "sections":[],
                "future_field":"new metadata","another":42
            }]}
        """.trimIndent()
        val book = json.decodeFromString(LibriVoxFeed.serializer(), raw).books.first()
        assertEquals("Future Book", book.title)
        assertEquals("A B", book.authorLabel())
    }

    @Test fun `no-results sentinel is an error object without a books array`() {
        // LibriVox returns `{"error":"Audiobooks could not be found"}`
        // (HTTP 200) for an empty result set rather than `{"books":[]}`.
        // LibriVoxApi.getBooks special-cases this shape into an empty
        // success — pin the discriminator the client keys off (no `books`
        // key, an `error` key present).
        val raw = """{"error":"Audiobooks could not be found"}"""
        val obj = json.parseToJsonElement(raw) as JsonObject
        assertTrue("error key present", obj.containsKey("error"))
        assertFalse("books key absent", obj.containsKey("books"))
    }

    private companion object {
        /** Captured 2026-06-05 from
         *  `curl 'https://librivox.org/api/feed/audiobooks/?id=47&extended=1&format=json'`,
         *  trimmed to two sections. */
        const val MONTE_CRISTO_FIXTURE: String = """{"books":[{
            "id":"47","title":"Count of Monte Cristo",
            "description":"<i>The Count of Monte Cristo</i> is an adventure novel.",
            "url_text_source":"https://www.gutenberg.org/etext/1184",
            "language":"English","copyright_year":"1844","num_sections":"128",
            "url_rss":"https://librivox.org/rss/47",
            "url_librivox":"https://librivox.org/the-count-of-monte-cristo-by-alexandre-dumas/",
            "url_other":"","totaltime":"49:43:15","totaltimesecs":178995,
            "authors":[{"id":"431","first_name":"Alexandre","last_name":"Dumas","dob":"1802","dod":"1870"}],
            "url_iarchive":"https://www.archive.org/details/count_monte_cristo_0711_librivox",
            "sections":[
                {"id":"121010","section_number":"1","title":"Marseilles-The Arrival ",
                 "listen_url":"https://www.archive.org/download/count_monte_cristo_0711_librivox/count_of_monte_cristo_001_dumas_64kb.mp3",
                 "language":"English","playtime":"1179","file_name":null,
                 "readers":[{"reader_id":"14","display_name":"Kristin LeMoine"}]},
                {"id":"121011","section_number":"2","title":"Father and Son",
                 "listen_url":"https://www.archive.org/download/count_monte_cristo_0711_librivox/count_of_monte_cristo_002_dumas_64kb.mp3",
                 "language":"English","playtime":"1157","file_name":null,
                 "readers":[{"reader_id":"17","display_name":"Gord Mackenzie"}]}
            ],
            "genres":[{"id":"20","name":"Literary Fiction"}],
            "translators":[]
        }]}"""
    }
}
