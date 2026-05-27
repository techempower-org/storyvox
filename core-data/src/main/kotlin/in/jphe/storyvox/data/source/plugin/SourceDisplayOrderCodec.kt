package `in`.jphe.storyvox.data.source.plugin

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * JSON codec for the `List<pluginId>` of user-arranged source display
 * order persisted under the `pref_source_display_order_v1` DataStore key.
 *
 * Twin of [SourceFavoritesCodec] but stores a List (preserving order).
 * An empty list means "use the default order" (favorites-first, then
 * registry order). A corrupted blob falls back to empty — same posture
 * as the favorites codec, where a parse failure means "lose the custom
 * order" rather than "crash startup".
 */
private val DisplayOrderJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

private val ListStringSerializer = ListSerializer(String.serializer())

/** Serialize [order] to a JSON array for DataStore storage. */
fun encodeSourceDisplayOrderJson(order: List<String>): String =
    DisplayOrderJson.encodeToString(ListStringSerializer, order)

/**
 * Parse a JSON blob produced by [encodeSourceDisplayOrderJson]. Returns
 * an empty list for null / blank / parse-failed input — corruption
 * shouldn't crash startup. A user with a corrupted order blob sees the
 * default favorites-first ordering and can re-arrange.
 */
fun decodeSourceDisplayOrderJson(blob: String?): List<String> {
    if (blob.isNullOrBlank()) return emptyList()
    return runCatching {
        DisplayOrderJson.decodeFromString(ListStringSerializer, blob)
    }.getOrDefault(emptyList())
}
