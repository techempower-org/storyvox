package `in`.jphe.storyvox.source.notion.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement

/**
 * Issue #233 — minimal wire types for the Notion REST API surfaces we
 * touch. Notion's published JSON shape is *enormous* (every block
 * variant, every property type, every rich-text annotation set); we
 * model only the fields storyvox renders and let the rest decode
 * permissively via `JsonElement` so unknown variants don't blow up
 * parsing.
 *
 * The three Notion endpoints we use:
 *
 *  - **POST `/v1/databases/{database_id}/query`** — paginated list of
 *    pages in a database. Returns a `NotionPageList` whose `results`
 *    array contains `NotionPage` rows (each with a `properties` map
 *    we project into FictionSummary fields).
 *  - **GET `/v1/pages/{page_id}`** — single page metadata. Same shape
 *    as the rows in `databases/{id}/query`; used by `fictionDetail`
 *    when the user opens a saved fiction directly without going
 *    through Browse.
 *  - **GET `/v1/blocks/{block_id}/children?page_size=100`** — paginated
 *    list of child blocks under a page or block. Each `NotionBlock`
 *    carries its type discriminator (heading_1, paragraph, code, ...)
 *    plus type-specific content under a key matching the type name.
 *
 * Notion returns 401 / 403 / 404 with structured error JSON
 * (`{ "code": "unauthorized", "message": "..." }`); we model that as
 * [NotionError] so the source layer can surface useful messages
 * without parsing prose.
 */

@Serializable
internal data class NotionPageList(
    @SerialName("object") val obj: String = "",
    val results: List<NotionPage> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
internal data class NotionPage(
    val id: String = "",
    @SerialName("created_time") val createdTime: String? = null,
    @SerialName("last_edited_time") val lastEditedTime: String? = null,
    val archived: Boolean = false,
    val url: String? = null,
    val cover: NotionFile? = null,
    val icon: NotionIcon? = null,
    /** Notion projects property values under a property-name → object
     *  map. The shapes vary by type (title, rich_text, select, date,
     *  multi_select, number, ...); we keep each value as a generic
     *  JsonElement and pull the relevant string at the call site. */
    val properties: Map<String, JsonElement> = emptyMap(),
)

@Serializable
internal data class NotionFile(
    val type: String? = null,
    val external: NotionExternalUrl? = null,
    val file: NotionFileUrl? = null,
)

@Serializable
internal data class NotionExternalUrl(val url: String = "")

@Serializable
internal data class NotionFileUrl(val url: String = "", @SerialName("expiry_time") val expiryTime: String? = null)

@Serializable
internal data class NotionIcon(
    val type: String? = null,
    val emoji: String? = null,
    val external: NotionExternalUrl? = null,
    val file: NotionFileUrl? = null,
)

@Serializable
internal data class NotionBlockList(
    @SerialName("object") val obj: String = "",
    val results: List<NotionBlock> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
)

/**
 * A Notion block. The `type` field is a discriminator; the actual
 * content lives under a property whose name matches the type
 * (`paragraph`, `heading_1`, `bulleted_list_item`, ...). We keep the
 * type-specific payload as JsonElement so unknown variants don't
 * crash parsing — the renderer projects the supported subset and
 * skips the rest.
 */
@Serializable
internal data class NotionBlock(
    val id: String = "",
    val type: String = "",
    @SerialName("has_children") val hasChildren: Boolean = false,
    val archived: Boolean = false,
    /** Type-specific payload. The Notion API encodes block content
     *  under a key matching `type` (e.g. paragraph blocks have a
     *  `paragraph` key); we capture the full block JSON and let the
     *  rendering layer dig into the matching key by name. */
    val paragraph: JsonElement? = null,
    @SerialName("heading_1") val heading1: JsonElement? = null,
    @SerialName("heading_2") val heading2: JsonElement? = null,
    @SerialName("heading_3") val heading3: JsonElement? = null,
    @SerialName("bulleted_list_item") val bulletedListItem: JsonElement? = null,
    @SerialName("numbered_list_item") val numberedListItem: JsonElement? = null,
    val quote: JsonElement? = null,
    val callout: JsonElement? = null,
    val code: JsonElement? = null,
    val divider: JsonElement? = null,
    val toggle: JsonElement? = null,
    @SerialName("to_do") val toDo: JsonElement? = null,
    /** Issue #1036 — nesting level in the flattened document order.
     *  Top-level blocks are depth 0; children spliced in by
     *  [flattenNested] carry their parent's depth + 1. Not part of the
     *  wire shape (Notion's API is a tree, not a flat depth-tagged list),
     *  so it's [Transient] — set during fetch-time flattening and read by
     *  the renderer to indent nested list items. */
    @Transient val depth: Int = 0,
)

/**
 * Structured Notion API error envelope. Returned with 4xx/5xx
 * responses; the `code` field is the stable machine-readable identifier
 * (`unauthorized`, `object_not_found`, `rate_limited`, ...) and
 * `message` is the human description we surface to the user.
 */
@Serializable
internal data class NotionError(
    @SerialName("object") val obj: String? = null,
    val status: Int? = null,
    val code: String? = null,
    val message: String? = null,
)
