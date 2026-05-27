package `in`.jphe.storyvox.data.source.plugin

import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * v0.5.76 — JSON codec for the `Set<pluginId>` of "favorited" source
 * plugins persisted under the `pref_source_favorites_v1` DataStore key.
 *
 * Twin of [SourcePluginsEnabledCodec] but stores a Set (not a Map), since
 * favorites have no per-source default — empty set is the only sensible
 * fresh-install state. Promoting a source to a favorite is a strictly
 * user-driven act from the Browse carousel long-press sheet.
 *
 * `ignoreUnknownKeys` doesn't apply (Set has no keys), and we don't need
 * `encodeDefaults` because the Set serializer always emits its members.
 * A corrupted blob falls back to empty — same posture as the enabled
 * codec, where a parse failure means "lose the user's favorites" rather
 * than "crash startup".
 */
private val SourceFavoritesJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

private val SetSerializer = SetSerializer(String.serializer())

/** Serialize [favorites] to a JSON array for DataStore storage. */
fun encodeSourceFavoritesJson(favorites: Set<String>): String =
    SourceFavoritesJson.encodeToString(SetSerializer, favorites)

/**
 * Parse a JSON blob produced by [encodeSourceFavoritesJson]. Returns
 * an empty set for null / blank / parse-failed input — corruption
 * shouldn't crash startup. A user with a corrupted favorites blob
 * sees an unstarred carousel and can re-star.
 */
fun decodeSourceFavoritesJson(blob: String?): Set<String> {
    if (blob.isNullOrBlank()) return emptySet()
    return runCatching {
        SourceFavoritesJson.decodeFromString(SetSerializer, blob)
    }.getOrDefault(emptySet())
}
