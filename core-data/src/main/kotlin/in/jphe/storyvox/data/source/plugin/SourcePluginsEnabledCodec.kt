package `in`.jphe.storyvox.data.source.plugin

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Plugin-seam Phase 1 (#384) — JSON codec for the
 * `Map<pluginId, Boolean>` persisted under the
 * `pref_source_plugins_enabled_v1` DataStore key.
 *
 * Lives in `:core-data` (not in `:app`) so any module that wants to
 * read or write the persisted shape doesn't have to depend on the
 * app module. The actual DataStore I/O still happens in
 * `SettingsRepositoryUiImpl` — this file is just the codec.
 *
 * `ignoreUnknownKeys = true` so a future schema bump (extra fields in
 * a richer Map<String, PluginPrefs> shape, say) doesn't lose data on
 * the way down. `encodeDefaults = true` so a `false` toggle round-trips
 * intact even when `false` is the default.
 */
private val SourcePluginsJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
}

private val MapSerializer = MapSerializer(String.serializer(), Boolean.serializer())

/** Serialize [map] to a JSON object for DataStore storage. */
fun encodeSourcePluginsEnabledJson(map: Map<String, Boolean>): String =
    SourcePluginsJson.encodeToString(MapSerializer, map)

/**
 * Parse a JSON blob produced by [encodeSourcePluginsEnabledJson].
 * Returns an empty map for null / blank / parse-failed input — a
 * corrupted blob shouldn't crash startup; the caller's migration
 * shim re-seeds the map from the legacy per-source preferences on
 * the next read.
 */
fun decodeSourcePluginsEnabledJson(blob: String?): Map<String, Boolean> {
    if (blob.isNullOrBlank()) return emptyMap()
    return runCatching {
        SourcePluginsJson.decodeFromString(MapSerializer, blob)
    }.getOrDefault(emptyMap())
}
