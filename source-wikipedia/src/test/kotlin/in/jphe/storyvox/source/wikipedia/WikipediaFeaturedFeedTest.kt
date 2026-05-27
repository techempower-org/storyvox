package `in`.jphe.storyvox.source.wikipedia

import `in`.jphe.storyvox.source.wikipedia.net.WikipediaFeatured
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #796 — parsing + surfacing tests for the `onthisday` and `news`
 * clusters of the `feed/featured` response. The fixture mirrors the
 * real Wikimedia REST shape (trimmed to the fields the source reads),
 * and the [Json] config matches [WikipediaApi]'s
 * (`ignoreUnknownKeys`, `coerceInputValues`).
 */
class WikipediaFeaturedFeedTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private fun parse(): WikipediaFeatured = json.decodeFromString(FEATURED_FIXTURE)

    @Test
    fun `onthisday cluster deserializes`() {
        val feed = parse()
        assertEquals(2, feed.onthisday.size)
        assertEquals(2016, feed.onthisday[0].year)
        assertTrue(feed.onthisday[0].text.startsWith("Syrian civil war"))
        assertEquals("Syrian_civil_war", feed.onthisday[0].pages[0].titles?.canonical)
    }

    @Test
    fun `news cluster deserializes`() {
        val feed = parse()
        assertEquals(2, feed.news.size)
        assertEquals("Sonny_Rollins", feed.news[0].links[0].titles?.canonical)
        assertTrue(feed.news[0].story.contains("Sonny Rollins"))
    }

    @Test
    fun `onthisday event surfaces most prominent page with year-prefixed description`() {
        val summary = parse().onthisday[0].toSummary()
        assertNotNull(summary)
        assertEquals("wikipedia:Syrian_civil_war", summary!!.id)
        assertEquals("Syrian civil war", summary.title)
        // Event year + text lead the description.
        assertTrue(summary.description!!.startsWith("2016: Syrian civil war"))
        // The article abstract (description, preferred over extract)
        // follows the event line.
        assertTrue(summary.description!!.contains("2011–2024 conflict in Syria"))
    }

    @Test
    fun `news story surfaces lead link with story text stripped of html`() {
        val summary = parse().news[0].toSummary()
        assertNotNull(summary)
        assertEquals("wikipedia:Sonny_Rollins", summary!!.id)
        assertEquals("Sonny Rollins", summary.title)
        // Story HTML (tags + comment marker) is stripped to plain text.
        assertTrue(summary.description!!.contains("Sonny Rollins"))
        assertFalse("description should not leak HTML tags", summary.description!!.contains("<"))
        assertFalse("description should not leak the date comment", summary.description!!.contains("May 25"))
    }

    @Test
    fun `event with no usable page is dropped`() {
        // Second onthisday event has a page with no canonical title.
        assertNull(parse().onthisday[1].toSummary())
    }

    @Test
    fun `news story with no usable link is dropped`() {
        // Second news item's lead link has no canonical title.
        assertNull(parse().news[1].toSummary())
    }

    @Test
    fun `feed without news or onthisday keys yields empty lists`() {
        val feed: WikipediaFeatured = json.decodeFromString("""{"tfa":{"titles":{"canonical":"X"}}}""")
        assertTrue(feed.onthisday.isEmpty())
        assertTrue(feed.news.isEmpty())
    }
}

private val FEATURED_FIXTURE = """
{
  "tfa": { "titles": { "canonical": "Featured_Article" }, "extract": "lead" },
  "mostread": { "articles": [] },
  "image": { "title": "Picture of the day" },
  "onthisday": [
    {
      "text": "Syrian civil war: The Syrian Democratic Forces launched an offensive.",
      "year": 2016,
      "pages": [
        {
          "titles": {
            "canonical": "Syrian_civil_war",
            "normalized": "Syrian civil war",
            "display": "Syrian civil war"
          },
          "extract": "The Syrian civil war was a multi-sided civil war in Syria.",
          "description": "2011–2024 conflict in Syria",
          "thumbnail": { "source": "https://example.org/syria.jpg" }
        }
      ]
    },
    {
      "text": "An event whose pages carry no canonical title.",
      "year": 1900,
      "pages": [ { "extract": "no canonical" } ]
    }
  ],
  "news": [
    {
      "story": "<!--May 25-->American jazz saxophonist <b><a href=\"./Sonny_Rollins\">Sonny Rollins</a></b> dies aged 95.",
      "links": [
        {
          "titles": {
            "canonical": "Sonny_Rollins",
            "normalized": "Sonny Rollins",
            "display": "Sonny Rollins"
          },
          "extract": "Walter Theodore \"Sonny\" Rollins was an American jazz tenor saxophonist.",
          "description": "American jazz saxophonist (1930–2026)",
          "thumbnail": { "source": "https://example.org/rollins.jpg" }
        }
      ]
    },
    {
      "story": "A story whose links carry no canonical title.",
      "links": [ { "extract": "no canonical" } ]
    }
  ]
}
""".trimIndent()
