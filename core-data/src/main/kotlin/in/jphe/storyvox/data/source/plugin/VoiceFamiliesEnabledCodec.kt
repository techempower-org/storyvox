package `in`.jphe.storyvox.data.source.plugin

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Plugin-seam Phase 4 (#501) — JSON codec for the
 * `Map<voiceFamilyId, Boolean>` persisted under the
 * `pref_voice_families_enabled_v1` DataStore key.
 *
 * Twin of [encodeSourcePluginsEnabledJson] / [decodeSourcePluginsEnabledJson]
 * for the Voice bundles section of the Plugin Manager. Voice families
 * (Piper, Kokoro, KittenTTS, Azure HD, future VoxSherpa upstreams) carry
 * the same on/off semantic as fiction sources — when a family is OFF,
 * its voices are filtered out of the Voice Library.
 *
 * Lives in `:core-data` (not in `:app`) so any module that wants to
 * read or write the persisted shape doesn't have to depend on the
 * app module. The actual DataStore I/O still happens in
 * `SettingsRepositoryUiImpl` — this file is just the codec.
 *
 * `ignoreUnknownKeys = true` so a future schema bump doesn't lose data
 * on the way down. `encodeDefaults = true` so a `false` toggle
 * round-trips intact even when `false` is the default.
 */
private val VoiceFamiliesJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
}

private val MapSerializer = MapSerializer(String.serializer(), Boolean.serializer())

/** Serialize [map] to a JSON object for DataStore storage. */
fun encodeVoiceFamiliesEnabledJson(map: Map<String, Boolean>): String =
    VoiceFamiliesJson.encodeToString(MapSerializer, map)

/**
 * Parse a JSON blob produced by [encodeVoiceFamiliesEnabledJson].
 * Returns an empty map for null / blank / parse-failed input — a
 * corrupted blob shouldn't crash startup; consumers fall back to each
 * family's `defaultEnabled` when its id is absent from the map.
 */
fun decodeVoiceFamiliesEnabledJson(blob: String?): Map<String, Boolean> {
    if (blob.isNullOrBlank()) return emptyMap()
    return runCatching {
        VoiceFamiliesJson.decodeFromString(MapSerializer, blob)
    }.getOrDefault(emptyMap())
}
