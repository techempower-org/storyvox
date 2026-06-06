package `in`.jphe.storyvox.source.notion

import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.model.FictionStatus
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.source.notion.net.NotionBlock
import `in`.jphe.storyvox.source.notion.net.NotionPage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// ─── helpers ──────────────────────────────────────────────────────────

/** Issue #472 — Notion page URL. The pageId is a 32-char hex blob,
 *  optionally hyphenated as `8-4-4-4-12`. We accept both forms. The
 *  pattern matches both bare workspace.notion.so/{id} and the
 *  slug+id form workspace.notion.so/{slug-with-id-at-end}. */
internal val NOTION_URL_PATTERN: Regex = Regex(
    """^https?://(?:[\w-]+\.)?notion\.(?:so|site)/(?:[\w-]+/)?(?:[\w-]*-)?([0-9a-f]{32}|(?:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}))(?:[?#].*)?$""",
    RegexOption.IGNORE_CASE,
)

/** Compose a stable Notion fiction id from a page id. */
internal fun notionFictionId(pageId: String): String =
    "notion:" + pageId.replace("-", "")

internal fun chapterIdFor(fictionId: String, sectionIndex: Int): String =
    "${fictionId}::section-$sectionIndex"

/** Compose a chapter id by binding a Notion page id to a parent fiction
 *  id. Used by anonymous-mode delegate where each chapter corresponds
 *  to one underlying Notion page (a curated guide, a database row, an
 *  About/Donate page) rather than a section-index offset. */
internal fun chapterIdFor(fictionId: String, pageId: String): String =
    "${fictionId}::${pageId.replace("-", "")}"

/** Strip the `notion:` prefix and return the canonical page id (with
 *  hyphens removed). Returns null on ids that don't carry the prefix. */
internal fun String.toPageId(): String? =
    if (startsWith("notion:")) removePrefix("notion:").substringBefore("::").replace("-", "")
    else null

/** Strip the `notion:` prefix and return the raw fiction id portion —
 *  for anonymous-mode fictions this is a section name ("guides",
 *  "resources", "about", "donate") rather than a Notion page id.
 *  Returns null on ids that don't carry the prefix. */
internal fun decodeFictionId(fictionId: String): String? =
    if (fictionId.startsWith("notion:")) fictionId.removePrefix("notion:")
    else null

/**
 * Internal section type — one chapter's title + ordered blocks.
 */
internal data class NotionSection(
    val title: String,
    val blocks: List<NotionBlock>,
)

/**
 * Split a flat block list into chapter sections on every `heading_1`
 * boundary. Section 0 (the "lead" before the first heading_1) is
 * titled "Introduction" unless empty. Empty leads are dropped.
 *
 * Same shape as :source-wikipedia's `splitTopLevelSections`, adapted
 * for Notion's flat block list (no nested `<section>` markup).
 */
internal fun splitOnHeading1(blocks: List<NotionBlock>): List<NotionSection> {
    if (blocks.isEmpty()) {
        // Empty page still gets one "Introduction" chapter so the
        // FictionDetail screen has something to anchor playback to —
        // the reader shows an empty body, the engine narrates nothing.
        return listOf(NotionSection("Introduction", emptyList()))
    }
    val sections = mutableListOf<NotionSection>()
    var currentTitle: String = "Introduction"
    var currentBlocks = mutableListOf<NotionBlock>()
    for (block in blocks) {
        if (block.type == "heading_1") {
            // Close out the prior section if it had content, then start a
            // new one titled with this heading's text.
            if (currentBlocks.isNotEmpty()) {
                sections.add(NotionSection(currentTitle, currentBlocks.toList()))
            }
            currentTitle = extractRichText(block.heading1) ?: "Section ${sections.size + 1}"
            currentBlocks = mutableListOf()
            // Don't include the heading_1 itself in the chapter body;
            // the chapter title duplicates it.
            continue
        }
        currentBlocks.add(block)
    }
    if (currentBlocks.isNotEmpty()) {
        sections.add(NotionSection(currentTitle, currentBlocks.toList()))
    }
    // Fully-empty page = one "Introduction" section with no blocks, so
    // FictionDetail still has a chapter to anchor playback (the reader
    // shows an empty body, the engine narrates nothing — same as a
    // blank Wikipedia article).
    if (sections.isEmpty()) {
        sections.add(NotionSection("Introduction", emptyList()))
    }
    return sections
}

/**
 * Project a NotionPage into a [FictionSummary]. Returns null if no
 * usable title can be extracted (a property of type "title" must
 * exist and contain at least one rich-text segment).
 *
 * Notion databases have one mandatory title property, but its *name*
 * varies per database (often "Name", sometimes "Title", sometimes
 * something domain-specific). We find it by type rather than by name:
 * scan the `properties` map for the first entry whose JSON shape
 * declares `"type": "title"`, then extract the rich-text array under
 * that property's `title` key.
 *
 * @param sourceId The [SourceIds] constant to stamp on the returned
 *  summary — callers pass their own id so the split TechEmpower /
 *  PAT sources each claim their own fictions.
 */
internal fun NotionPage.toSummary(sourceId: String): FictionSummary? {
    val title = extractTitleProperty(properties) ?: return null
    if (title.isBlank()) return null
    val description = extractDescriptionProperty(properties)
    val coverUrl = cover?.let { it.external?.url?.ifBlank { null } ?: it.file?.url?.ifBlank { null } }
    return FictionSummary(
        id = notionFictionId(id),
        sourceId = sourceId,
        title = title,
        author = extractAuthorProperty(properties) ?: "Notion",
        description = description,
        coverUrl = coverUrl,
        tags = extractTagsProperty(properties),
        status = FictionStatus.ONGOING,
    )
}

/** Pull the first property of type `title` from a Notion page's
 *  property map and return the concatenated rich-text content. */
internal fun extractTitleProperty(properties: Map<String, JsonElement>): String? {
    for ((_, v) in properties) {
        val obj = v as? JsonObject ?: continue
        val type = obj["type"]?.jsonPrimitive?.contentOrNull
        if (type == "title") {
            val arr = obj["title"]?.jsonArray ?: continue
            return joinRichText(arr).ifBlank { null }
        }
    }
    return null
}

/** Pull a free-form "description" / "summary" / "abstract" property,
 *  if present. Notion databases vary wildly here; we check a couple of
 *  common names then any rich_text property in declaration order. */
internal fun extractDescriptionProperty(properties: Map<String, JsonElement>): String? {
    val preferredNames = listOf("Description", "description", "Summary", "summary", "Abstract", "abstract")
    for (name in preferredNames) {
        val v = properties[name] as? JsonObject ?: continue
        val type = v["type"]?.jsonPrimitive?.contentOrNull ?: continue
        if (type == "rich_text") {
            val arr = v["rich_text"]?.jsonArray ?: continue
            val s = joinRichText(arr)
            if (s.isNotBlank()) return s
        }
    }
    // Fallback — first non-empty rich_text property in declaration order.
    for ((name, v) in properties) {
        if (name in preferredNames) continue
        val obj = v as? JsonObject ?: continue
        val type = obj["type"]?.jsonPrimitive?.contentOrNull
        if (type == "rich_text") {
            val arr = obj["rich_text"]?.jsonArray ?: continue
            val s = joinRichText(arr)
            if (s.isNotBlank()) return s
        }
    }
    return null
}

/** Pull a "By" / "Author" / "Created by" property if present. */
internal fun extractAuthorProperty(properties: Map<String, JsonElement>): String? {
    val preferredNames = listOf("Author", "author", "By", "by", "Authors", "authors")
    for (name in preferredNames) {
        val v = properties[name] as? JsonObject ?: continue
        val type = v["type"]?.jsonPrimitive?.contentOrNull ?: continue
        when (type) {
            "rich_text" -> {
                val arr = v["rich_text"]?.jsonArray ?: continue
                val s = joinRichText(arr)
                if (s.isNotBlank()) return s
            }
            "people" -> {
                val arr = v["people"]?.jsonArray ?: continue
                val names = arr.mapNotNull { p ->
                    (p as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
                }
                if (names.isNotEmpty()) return names.joinToString(", ")
            }
            "select" -> {
                val sel = v["select"] as? JsonObject ?: continue
                val s = sel["name"]?.jsonPrimitive?.contentOrNull
                if (!s.isNullOrBlank()) return s
            }
        }
    }
    return null
}

/** Pull a "Tags" / "Categories" multi-select property, if present. */
internal fun extractTagsProperty(properties: Map<String, JsonElement>): List<String> {
    val preferredNames = listOf("Tags", "tags", "Categories", "categories", "Topics", "topics")
    for (name in preferredNames) {
        val v = properties[name] as? JsonObject ?: continue
        val type = v["type"]?.jsonPrimitive?.contentOrNull ?: continue
        if (type == "multi_select") {
            val arr = v["multi_select"]?.jsonArray ?: continue
            val names = arr.mapNotNull { entry ->
                (entry as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
            }
            if (names.isNotEmpty()) return names
        }
    }
    return emptyList()
}

/**
 * Pull a heading's title text from its `heading_1` / `heading_2` /
 * `heading_3` payload. Notion stores the heading text as a
 * `rich_text` array under the type-named key; we concatenate.
 */
internal fun extractRichText(payload: JsonElement?): String? {
    val obj = payload as? JsonObject ?: return null
    val arr = obj["rich_text"]?.jsonArray ?: return null
    val text = joinRichText(arr)
    return text.ifBlank { null }
}

/**
 * Concatenate a Notion `rich_text` array into a plain-text string.
 * Each element is `{ type, plain_text, annotations, ... }`; the
 * `plain_text` field carries the user-visible text without HTML
 * markup. We use `plain_text` rather than reconstructing from
 * type-specific payloads because Notion guarantees plain_text is
 * always populated, even for `mention` / `equation` elements.
 */
internal fun joinRichText(array: JsonArray): String {
    val sb = StringBuilder()
    for (e in array) {
        val obj = e as? JsonObject ?: continue
        val plain = obj["plain_text"]?.jsonPrimitive?.contentOrNull ?: continue
        sb.append(plain)
    }
    return sb.toString()
}

/**
 * Render a Notion block to a fragment of HTML for the reader view.
 * Unsupported block types render as empty strings so the reader
 * doesn't show raw JSON. The TTS pipeline ignores tags; only the
 * concatenated text matters for narration.
 */
internal fun NotionBlock.toHtml(): String = when (type) {
    "paragraph" -> {
        val text = htmlEscape(extractRichText(paragraph) ?: "")
        if (text.isBlank()) "" else "<p>$text</p>"
    }
    "heading_2" -> {
        val text = htmlEscape(extractRichText(heading2) ?: "")
        if (text.isBlank()) "" else "<h2>$text</h2>"
    }
    "heading_3" -> {
        val text = htmlEscape(extractRichText(heading3) ?: "")
        if (text.isBlank()) "" else "<h3>$text</h3>"
    }
    "bulleted_list_item" -> {
        val text = htmlEscape(extractRichText(bulletedListItem) ?: "")
        if (text.isBlank()) "" else nestListItem(text)
    }
    "numbered_list_item" -> {
        val text = htmlEscape(extractRichText(numberedListItem) ?: "")
        if (text.isBlank()) "" else nestListItem(text)
    }
    "quote" -> {
        val text = htmlEscape(extractRichText(quote) ?: "")
        if (text.isBlank()) "" else "<blockquote>$text</blockquote>"
    }
    "callout" -> {
        val text = htmlEscape(extractRichText(callout) ?: "")
        if (text.isBlank()) "" else "<aside>$text</aside>"
    }
    "code" -> {
        val text = htmlEscape(extractRichText(code) ?: "")
        if (text.isBlank()) "" else "<pre><code>$text</code></pre>"
    }
    "divider" -> "<hr/>"
    "toggle" -> {
        val text = htmlEscape(extractRichText(toggle) ?: "")
        if (text.isBlank()) "" else "<p>$text</p>"
    }
    "to_do" -> {
        val text = htmlEscape(extractRichText(toDo) ?: "")
        if (text.isBlank()) "" else "<p>$text</p>"
    }
    // Issue #1036 — layout containers carry no text of their own; their
    // children were spliced into the flat list by flattenNested and
    // render on their own. Emitting nothing for the container keeps the
    // reader free of empty wrappers.
    "column_list", "column", "synced_block" -> ""
    else -> ""
}

/**
 * Issue #1036 — wrap a nested list item so the reader shows its nesting.
 * Top-level items (depth 0) render as a bare `<li>` exactly as before;
 * each level of nesting wraps in one more `<ul>` so a sub-bullet reads
 * as indented under its parent. The TTS path ignores tags, so this is
 * purely visual — narration completeness comes from the block being in
 * the flat list at all, which it now is.
 */
private fun NotionBlock.nestListItem(escapedText: String): String {
    val li = "<li>$escapedText</li>"
    return if (depth <= 0) li else "<ul>".repeat(depth) + li + "</ul>".repeat(depth)
}

/** Plain-text projection of a block for TTS. Strips all markup; only
 *  the text content matters. */
internal fun NotionBlock.toPlainText(): String = when (type) {
    "paragraph" -> extractRichText(paragraph) ?: ""
    "heading_2" -> extractRichText(heading2) ?: ""
    "heading_3" -> extractRichText(heading3) ?: ""
    "bulleted_list_item" -> extractRichText(bulletedListItem) ?: ""
    "numbered_list_item" -> extractRichText(numberedListItem) ?: ""
    "quote" -> extractRichText(quote) ?: ""
    "callout" -> extractRichText(callout) ?: ""
    "code" -> extractRichText(code) ?: ""
    "divider" -> ""
    "toggle" -> extractRichText(toggle) ?: ""
    "to_do" -> extractRichText(toDo) ?: ""
    // Issue #1036 — transparent containers narrate nothing; their child
    // blocks carry the text and narrate on their own.
    "column_list", "column", "synced_block" -> ""
    else -> ""
}

/** Minimal HTML escape — angle brackets, ampersand, double-quote. */
internal fun htmlEscape(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
