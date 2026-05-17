package `in`.jphe.storyvox.source.notion

import `in`.jphe.storyvox.source.notion.config.NotionDefaults
import `in`.jphe.storyvox.source.notion.config.NotionMode
import `in`.jphe.storyvox.source.notion.net.NotionBlockEnvelope
import `in`.jphe.storyvox.source.notion.net.NotionRecordMap
import `in`.jphe.storyvox.source.notion.net.NotionUnofficialApi
import `in`.jphe.storyvox.source.notion.net.contentIds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #393 — focused unit tests for the anonymous-mode parsing
 * surface: recordMap envelope unwrapping, decoration-array title
 * extraction, page-id hyphenation, content[] traversal, chapter
 * spec resolution, and HTML/plain rendering of a TechEmpower-shaped
 * page body.
 *
 * No HTTP mocking — the network layer is exercised at integration
 * level. These tests pin the pure mapping logic that took the most
 * reverse-engineering effort.
 */
class AnonymousNotionDelegateTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ─── page-id hyphenation ──────────────────────────────────────────

    @Test
    fun `hyphenatePageId converts 32-hex to 8-4-4-4-12`() {
        assertEquals(
            "0959e445-9998-4143-acab-c80187305001",
            NotionUnofficialApi.hyphenatePageId("0959e44599984143acabc80187305001"),
        )
    }

    @Test
    fun `hyphenatePageId leaves hyphenated id unchanged`() {
        assertEquals(
            "0959e445-9998-4143-acab-c80187305001",
            NotionUnofficialApi.hyphenatePageId("0959e445-9998-4143-acab-c80187305001"),
        )
    }

    @Test
    fun `hyphenatePageId returns malformed input as-is`() {
        // The API will reject malformed ids; we don't try to be clever.
        assertEquals("not-a-uuid", NotionUnofficialApi.hyphenatePageId("not-a-uuid"))
        assertEquals("", NotionUnofficialApi.hyphenatePageId(""))
    }

    // ─── recordMap envelope unwrapping ────────────────────────────────

    @Test
    fun `findBlock unwraps envelope and accepts compact id`() {
        val rm = recordMapWith(
            "0959e445-9998-4143-acab-c80187305001" to """
                {"value":{"value":{"id":"0959e445-9998-4143-acab-c80187305001","type":"page","properties":{"title":[["Welcome"]]},"content":["a","b"]}}}
            """.trimIndent(),
        )
        // Compact 32-hex form — findBlock must hyphenate before lookup.
        val block = rm.findBlock("0959e44599984143acabc80187305001")
        assertNotNull(block)
        assertEquals("page", block!!.blockType())
        assertEquals("Welcome", readTitle(block))
    }

    @Test
    fun `findBlock returns null for unknown id`() {
        val rm = NotionRecordMap()
        assertNull(rm.findBlock("ffffffff-ffff-ffff-ffff-ffffffffffff"))
    }

    // ─── title + content traversal ────────────────────────────────────

    @Test
    fun `readTitle joins decoration array`() {
        val block = parseBlock("""
            {"properties":{"title":[["Hello ", []],["world", []],["!"]]}}
        """.trimIndent())
        assertEquals("Hello world!", readTitle(block))
    }

    @Test
    fun `readTitle returns null when title array missing`() {
        assertNull(readTitle(parseBlock("""{"properties":{}}""")))
    }

    @Test
    fun `contentIds yields child ids in display order`() {
        val block = parseBlock("""
            {"content":["a","b","c"]}
        """.trimIndent())
        assertEquals(listOf("a", "b", "c"), block.contentIds())
    }

    @Test
    fun `contentIds returns empty list when content missing`() {
        assertTrue(parseBlock("""{"type":"text"}""").contentIds().isEmpty())
    }

    // ─── collection_view metadata ─────────────────────────────────────

    @Test
    fun `collection_view exposes collection_id and first view`() {
        val block = parseBlock("""
            {
              "type":"collection_view",
              "collection_id":"8cb5379d-fe78-4a15-9f3a-d539f5a60387",
              "view_ids":["329a4ee6-9520-8199-bc52-000caf9e1475","329a4ee6-9520-8109-8eea-000c7c8208dc"]
            }
        """.trimIndent())
        assertEquals("8cb5379d-fe78-4a15-9f3a-d539f5a60387", block.collectionId())
        assertEquals("329a4ee6-9520-8199-bc52-000caf9e1475", block.firstViewId())
    }

    @Test
    fun `firstViewId returns null when view_ids missing`() {
        val block = parseBlock("""{"type":"collection_view","collection_id":"x"}""")
        assertNull(block.firstViewId())
    }

    // ─── techempower fiction layout (v0.5.25 4-fiction model) ──────────

    @Test
    fun `techempowerFictions defines four top-level sections in nav order`() {
        val fictions = NotionDefaults.techempowerFictions
        assertEquals(4, fictions.size)
        assertEquals("guides", fictions[0].id)
        assertEquals("resources", fictions[1].id)
        assertEquals("about", fictions[2].id)
        assertEquals("donate", fictions[3].id)
    }

    @Test
    fun `Guides fiction lists the 7 curated guide pages`() {
        val guides = NotionDefaults.techempowerFictions
            .first { it.id == "guides" } as TechEmpowerFiction.PageList
        // Issue #558 — "EBT spending" dropped from the list in v0.5.65
        // because its hardcoded page id returns a Notion ValidationError
        // 400. List is 7 chapters; if the upstream page is restored,
        // add it back with the correct page id and bump this to 8.
        assertEquals(7, guides.chapters.size)
        // Order matches site.config.ts pageUrlOverrides — the website's
        // own navigation order is the contract.
        assertEquals("How to use TechEmpower.org", guides.chapters[0].first)
        assertEquals("6c979ba4e43f48d7a4836e0027ea4178", guides.chapters[0].second)
        assertEquals("Free internet", guides.chapters[1].first)
        assertEquals("Free cell service", guides.chapters[6].first)
        // Guarantee the broken EBT spending id never resurfaces unless
        // explicitly re-added — guards against an accidental revert.
        assertTrue(
            "EBT spending page id 16f7018a... must not be in the curated list",
            guides.chapters.none { it.second == "16f7018ad93542652b2b16c44464b1c3" },
        )
    }

    @Test
    fun `Resources fiction points at the TechEmpower database`() {
        val resources = NotionDefaults.techempowerFictions
            .first { it.id == "resources" } as TechEmpowerFiction.CollectionRows
        assertEquals(NotionDefaults.TECHEMPOWER_DATABASE_ID, resources.collectionBlockId)
    }

    @Test
    fun `About and Donate are SinglePage fictions`() {
        val about = NotionDefaults.techempowerFictions
            .first { it.id == "about" } as TechEmpowerFiction.SinglePage
        val donate = NotionDefaults.techempowerFictions
            .first { it.id == "donate" } as TechEmpowerFiction.SinglePage
        assertEquals("dbf0ddece2ce468fb2bf9049e6322e8a", about.pageId)
        assertEquals("59d8a4dab0cc484f8b044d33f240ce1d", donate.pageId)
    }

    @Test
    fun `decodeFictionId strips notion prefix and returns section id`() {
        assertEquals("guides", decodeFictionId("notion:guides"))
        assertEquals("resources", decodeFictionId("notion:resources"))
        assertNull(decodeFictionId("royalroad:12345"))
        assertNull(decodeFictionId("plain-string"))
    }

    @Test
    fun `chapterIdFor pageId overload binds page id under fiction id`() {
        val id = chapterIdFor("notion:guides", "6c979ba4e43f48d7a4836e0027ea4178")
        assertEquals("notion:guides::6c979ba4e43f48d7a4836e0027ea4178", id)
    }

    @Test
    fun `chapterIdFor strips hyphens from page id input`() {
        val id = chapterIdFor(
            "notion:guides",
            "6c979ba4-e43f-48d7-a483-6e0027ea4178",
        )
        assertEquals("notion:guides::6c979ba4e43f48d7a4836e0027ea4178", id)
    }

    // ─── cover URL extraction ─────────────────────────────────────────

    @Test
    fun `readCoverUrl pulls format-page_cover when present`() {
        val block = parseBlock("""
            {"type":"page","format":{"page_cover":"https://example.com/banner.jpg"}}
        """.trimIndent())
        assertEquals("https://example.com/banner.jpg", readCoverUrl(block))
    }

    @Test
    fun `readCoverUrl returns null when format missing or page_cover blank`() {
        assertNull(readCoverUrl(parseBlock("""{"type":"page"}""")))
        assertNull(readCoverUrl(parseBlock("""{"type":"page","format":{}}""")))
        assertNull(readCoverUrl(parseBlock("""{"type":"page","format":{"page_cover":""}}""")))
    }

    @Test
    fun `readImageBlockSource prefers format-display_source for signed URLs`() {
        // Notion-uploaded images: format.display_source is the
        // signed-S3 rewrite; we prefer it because that's the URL
        // Notion's own renderer uses.
        val block = parseBlock("""
            {
              "type":"image",
              "format":{"display_source":"https://aws.notion.so/signed.png"},
              "properties":{"source":[["https://example.com/raw.png"]]}
            }
        """.trimIndent())
        assertEquals("https://aws.notion.so/signed.png", readImageBlockSource(block))
    }

    @Test
    fun `readImageBlockSource falls back to properties-source for external URLs`() {
        // External-URL images carry the source only in
        // properties.source (decoration-array form).
        val block = parseBlock("""
            {
              "type":"image",
              "properties":{"source":[["https://example.com/external.png"]]}
            }
        """.trimIndent())
        assertEquals("https://example.com/external.png", readImageBlockSource(block))
    }

    @Test
    fun `readImageBlockSource returns null for non-image blocks`() {
        // Defensive: caller passes any block; we should only ever
        // return a URL when type == "image".
        val text = parseBlock("""
            {"type":"text","properties":{"source":[["https://example.com/x.png"]]}}
        """.trimIndent())
        assertNull(readImageBlockSource(text))
    }

    @Test
    fun `readBodyImageUrl returns first image source in display order`() {
        val rm = recordMapWith(
            "root" to envelope("""{"type":"page","content":["t1","img1","img2"]}"""),
            "t1" to envelope("""{"type":"text","properties":{"title":[["Lead."]]}}"""),
            "img1" to envelope("""{"type":"image","properties":{"source":[["https://example.com/first.png"]]}}"""),
            "img2" to envelope("""{"type":"image","properties":{"source":[["https://example.com/second.png"]]}}"""),
        )
        val root = rm.findBlock("root")!!
        assertEquals("https://example.com/first.png", readBodyImageUrl(rm, root))
    }

    @Test
    fun `readBodyImageUrl returns null when no image blocks exist`() {
        val rm = recordMapWith(
            "root" to envelope("""{"type":"page","content":["t1","t2"]}"""),
            "t1" to envelope("""{"type":"text","properties":{"title":[["Lead."]]}}"""),
            "t2" to envelope("""{"type":"header","properties":{"title":[["Heading"]]}}"""),
        )
        val root = rm.findBlock("root")!!
        assertNull(readBodyImageUrl(rm, root))
    }

    @Test
    fun `readBodyImageUrl skips tombstoned image blocks`() {
        // alive:false images shouldn't be picked up as the cover —
        // Notion has already marked them deleted.
        val rm = recordMapWith(
            "root" to envelope("""{"type":"page","content":["dead","live"]}"""),
            "dead" to envelope("""{"type":"image","alive":false,"properties":{"source":[["https://example.com/dead.png"]]}}"""),
            "live" to envelope("""{"type":"image","properties":{"source":[["https://example.com/live.png"]]}}"""),
        )
        val root = rm.findBlock("root")!!
        assertEquals("https://example.com/live.png", readBodyImageUrl(rm, root))
    }

    // ─── page body rendering ──────────────────────────────────────────

    @Test
    fun `renderPageBody concatenates blocks and skips tombstones`() {
        val rm = recordMapWith(
            "root" to envelope("""{"type":"page","content":["t1","dead","h1","t2"]}"""),
            "t1" to envelope("""{"type":"text","properties":{"title":[["Lead paragraph."]]}}"""),
            "dead" to envelope("""{"type":"text","alive":false,"properties":{"title":[["Tombstone."]]}}"""),
            "h1" to envelope("""{"type":"header","properties":{"title":[["Section heading"]]}}"""),
            "t2" to envelope("""{"type":"text","properties":{"title":[["Second paragraph."]]}}"""),
        )
        val root = rm.findBlock("root")!!
        val (html, plain) = renderPageBody(rm, root)
        assertTrue(html.contains("<p>Lead paragraph.</p>"))
        // header → h2 (v0.5.25 — see blockToHtml kdoc).
        assertTrue(html.contains("<h2>Section heading</h2>"))
        assertTrue(html.contains("<p>Second paragraph.</p>"))
        assertFalse(html.contains("Tombstone"))
        assertTrue(plain.contains("Lead paragraph."))
        assertTrue(plain.contains("Section heading"))
        assertFalse(plain.contains("Tombstone"))
    }

    @Test
    fun `renderPageBody embeds sub-page titles as bridge paragraphs`() {
        // Sub-pages stay reachable through their authored titles; the
        // chapter doesn't expand them inline (would balloon).
        val rm = recordMapWith(
            "root" to envelope("""{"type":"page","content":["intro","subp"]}"""),
            "intro" to envelope("""{"type":"text","properties":{"title":[["See our guides:"]]}}"""),
            "subp" to envelope("""{"type":"page","properties":{"title":[["How to use TechEmpower"]]}}"""),
        )
        val root = rm.findBlock("root")!!
        val (html, plain) = renderPageBody(rm, root)
        assertTrue(html.contains("<p><strong>How to use TechEmpower</strong></p>"))
        assertTrue(plain.contains("How to use TechEmpower"))
    }

    // ─── collection row extraction (v0.5.25 — each row = chapter) ─────

    @Test
    fun `collectRows pulls (rowId, title) pairs out of a recordMap`() {
        val rm = recordMapWith(
            "row1" to envelope("""{"type":"page","properties":{"title":[["Cursor for Students"]]}}"""),
            "row2" to envelope("""{"type":"page","properties":{"title":[["1yr Google AI Pro free"]]}}"""),
            "rowdead" to envelope("""{"type":"page","alive":false,"properties":{"title":[["Expired offer"]]}}"""),
            "rownotitle" to envelope("""{"type":"page","properties":{}}"""),
        )
        val rows = collectRows(rm)
        // Sorted alphabetically by title; tombstoned + titleless rows
        // are filtered out.
        assertEquals(2, rows.size)
        assertEquals("1yr Google AI Pro free", rows[0].second)
        assertEquals("Cursor for Students", rows[1].second)
        // The id is the recordMap key, with hyphens stripped.
        assertEquals("row2", rows[0].first)
    }

    @Test
    fun `collectRows compacts hyphenated ids`() {
        val rm = recordMapWith(
            "abc-def" to envelope("""{"type":"page","properties":{"title":[["Hyphenated"]]}}"""),
        )
        val rows = collectRows(rm)
        assertEquals(1, rows.size)
        assertEquals("abcdef", rows[0].first)
    }

    // ─── block-type → HTML projection ─────────────────────────────────

    @Test
    fun `blockToHtml maps unofficial block types correctly`() {
        val rm = NotionRecordMap()
        assertEquals(
            "<p>Hello.</p>",
            blockToHtml(rm, parseBlock("""{"type":"text","properties":{"title":[["Hello."]]}}""")),
        )
        // header demotes to h2 in v0.5.25 — chapter title is already
        // the h1, so internal heading_1s render as h2 to keep the
        // chapter's outline tidy.
        assertEquals(
            "<h2>Top heading</h2>",
            blockToHtml(rm, parseBlock("""{"type":"header","properties":{"title":[["Top heading"]]}}""")),
        )
        assertEquals(
            "<h3>Subhead</h3>",
            blockToHtml(rm, parseBlock("""{"type":"sub_header","properties":{"title":[["Subhead"]]}}""")),
        )
        assertEquals(
            "<h4>Sub-sub</h4>",
            blockToHtml(rm, parseBlock("""{"type":"sub_sub_header","properties":{"title":[["Sub-sub"]]}}""")),
        )
        assertEquals(
            "<li>Item</li>",
            blockToHtml(rm, parseBlock("""{"type":"bulleted_list","properties":{"title":[["Item"]]}}""")),
        )
        assertEquals(
            "<blockquote>Quoted</blockquote>",
            blockToHtml(rm, parseBlock("""{"type":"quote","properties":{"title":[["Quoted"]]}}""")),
        )
        assertEquals(
            "<aside>Note</aside>",
            blockToHtml(rm, parseBlock("""{"type":"callout","properties":{"title":[["Note"]]}}""")),
        )
        assertEquals(
            "<hr/>",
            blockToHtml(rm, parseBlock("""{"type":"divider"}""")),
        )
        // Unknown types render blank — the unofficial API has dozens
        // of block types and storyvox only narrates the textual subset.
        assertEquals(
            "",
            blockToHtml(rm, parseBlock("""{"type":"embed","properties":{"title":[["x"]]}}""")),
        )
    }

    @Test
    fun `htmlEscape escapes the four chars`() {
        // Sanity check — the existing official-mode helper is reused.
        assertEquals("&amp;&lt;&gt;&quot;", htmlEscape("&<>\""))
    }

    // ─── NotionConfigState mode posture ───────────────────────────────

    @Test
    fun `default NotionConfigState is anonymous`() {
        val s = `in`.jphe.storyvox.source.notion.config.NotionConfigState()
        assertEquals(NotionMode.ANONYMOUS_PUBLIC, s.mode)
        assertTrue(s.isConfigured) // root page id has a baked default
    }

    @Test
    fun `OFFICIAL_PAT mode requires both token and databaseId`() {
        val empty = `in`.jphe.storyvox.source.notion.config.NotionConfigState(
            mode = NotionMode.OFFICIAL_PAT,
            apiToken = "",
            databaseId = "",
        )
        assertFalse(empty.isConfigured)
        val tokenOnly = empty.copy(apiToken = "ntn_token")
        assertFalse(tokenOnly.isConfigured)
        val both = tokenOnly.copy(databaseId = "abc")
        assertTrue(both.isConfigured)
    }

    @Test
    fun `ANONYMOUS_PUBLIC mode requires only rootPageId`() {
        val withDefault = `in`.jphe.storyvox.source.notion.config.NotionConfigState(
            mode = NotionMode.ANONYMOUS_PUBLIC,
            apiToken = "",
        )
        // Default rootPageId is TechEmpower's; isConfigured is true.
        assertTrue(withDefault.isConfigured)
        val blank = withDefault.copy(rootPageId = "")
        assertFalse(blank.isConfigured)
    }

    // ─── test helpers ─────────────────────────────────────────────────

    private fun parseBlock(jsonText: String): JsonObject =
        json.parseToJsonElement(jsonText) as JsonObject

    private fun envelope(blockJson: String): String =
        """{"value":{"value":$blockJson}}"""

    private fun recordMapWith(vararg entries: Pair<String, String>): NotionRecordMap {
        val blocks = entries.associate { (id, envelopeJson) ->
            id to json.decodeFromString<NotionBlockEnvelope>(envelopeJson)
        }
        return NotionRecordMap(block = blocks)
    }
}
