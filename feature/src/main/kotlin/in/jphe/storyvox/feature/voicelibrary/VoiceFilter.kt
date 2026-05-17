package `in`.jphe.storyvox.feature.voicelibrary

import `in`.jphe.storyvox.playback.voice.EngineType
import `in`.jphe.storyvox.playback.voice.QualityLevel
import `in`.jphe.storyvox.playback.voice.UiVoiceInfo
import `in`.jphe.storyvox.playback.voice.VoiceGender

/**
 * Issue #264 — pure helpers for the Voice Library search + language
 * filter pipeline.
 *
 * Lifted out of [VoiceLibraryScreen] (where they were originally
 * declared as `private fun` for the inline-filtering call site) and
 * out of [VoiceLibraryViewModel] (where the pipeline now consumes them)
 * so JVM unit tests can exercise the contract without bringing up
 * Compose or Hilt.
 *
 * The criteria object carries every filter dimension the screen and
 * VM know about today. JP's spec (#264) calls out the two **primary**
 * dimensions explicitly — substring query + exact-match two-letter
 * language code — and these are the two the ViewModel exposes as
 * StateFlow knobs. The remaining three (gender / tier / multilingual)
 * stay on the screen as local chip state and apply on top of the VM
 * filter by re-running [matchesCriteria] with the same criteria type
 * extended.
 *
 * AND-semantics across dimensions: a voice passes iff it matches
 * every non-empty dimension. Within a multi-value dimension
 * (genders / tiers), the values OR together — an empty set means
 * "no filter".
 */
internal data class VoiceFilterCriteria(
    /** Case-insensitive substring search over [UiVoiceInfo.displayName],
     *  [UiVoiceInfo.language], and the engine label ("piper" / "kokoro"
     *  / "azure"). Blank string = no filter on this dimension. */
    val query: String = "",
    /** Two-letter primary language code (`en`, `es`, `fr`...) or null
     *  for no language filter. Matched against the **primary** code
     *  so en-US / en-GB / en-AU all match the "en" chip. JP's spec
     *  uses a single selected code (single-select chip strip); a Set
     *  could replace this later for multi-select. */
    val language: String? = null,
    /** Genders that pass the filter. Empty set = no filter. The screen's
     *  three chips (♀/♂/Neutral) map to [VoiceGender] members. */
    val genders: Set<VoiceGender> = emptySet(),
    /** Quality tiers that pass the filter. Empty set = no filter. */
    val tiers: Set<QualityLevel> = emptySet(),
    /** When true, restrict to voices whose [displayName] contains
     *  "multilingual" (Azure naming convention). Piper / Kokoro have
     *  no multilingual variant today; this naturally narrows to Azure. */
    val multilingualOnly: Boolean = false,
)

/** Primary (two-letter) language code for chip aggregation. en-US,
 *  en-GB, en-AU → "en". */
internal fun UiVoiceInfo.primaryLanguageCode(): String =
    language.take(2).lowercase()

/** Multilingual voices have "Multilingual" in their displayName (Azure
 *  convention: `en-US-AndrewMultilingualNeural`, etc). */
internal fun UiVoiceInfo.isMultilingual(): Boolean =
    displayName.contains("multilingual", ignoreCase = true)

/** Case-insensitive substring search across [displayName], [language],
 *  and the engine label. A blank [query] is a no-op pass-through. */
internal fun UiVoiceInfo.matchesQuery(query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    val needle = q.lowercase()
    if (displayName.contains(needle, ignoreCase = true)) return true
    if (language.contains(needle, ignoreCase = true)) return true
    val engineLabel = when (engineType) {
        is EngineType.Piper -> "piper"
        is EngineType.Kokoro -> "kokoro"
        // Issue #119 — Kitten search label.
        is EngineType.Kitten -> "kitten"
        is EngineType.Azure -> "azure"
        // #676 — System TTS search label. Users will hunt for "system",
        // "tts", or the engine vendor name (Google / Samsung) — match
        // a broad set so the picker filter works even when the user
        // can't remember the exact label.
        is EngineType.SystemTts -> "system tts"
    }
    return engineLabel.contains(needle, ignoreCase = true)
}

/** AND-semantics filter: a voice passes iff it matches every non-empty
 *  dimension of [criteria]. */
internal fun UiVoiceInfo.matchesCriteria(criteria: VoiceFilterCriteria): Boolean {
    if (!matchesQuery(criteria.query)) return false
    val lang = criteria.language
    if (lang != null && primaryLanguageCode() != lang) return false
    if (criteria.genders.isNotEmpty() && gender !in criteria.genders) return false
    if (criteria.tiers.isNotEmpty() && qualityLevel !in criteria.tiers) return false
    if (criteria.multilingualOnly && !isMultilingual()) return false
    return true
}

/** Filter a flat voice list by [criteria]. Pass-through when criteria
 *  is the empty default (avoids allocating an identical list). */
internal fun List<UiVoiceInfo>.filterBy(criteria: VoiceFilterCriteria): List<UiVoiceInfo> {
    if (criteria == VoiceFilterCriteria()) return this
    return filter { it.matchesCriteria(criteria) }
}

/** Filter the engine × tier nested map by [criteria], dropping empty
 *  tier and engine buckets so the screen doesn't render hollow
 *  section headers. Iteration order is preserved. */
internal fun Map<VoiceEngine, Map<QualityLevel, List<UiVoiceInfo>>>.filterBy(
    criteria: VoiceFilterCriteria,
): Map<VoiceEngine, Map<QualityLevel, List<UiVoiceInfo>>> {
    if (criteria == VoiceFilterCriteria()) return this
    return mapValues { (_, tiers) ->
        tiers.mapValues { (_, voices) -> voices.filter { it.matchesCriteria(criteria) } }
            .filterValues { it.isNotEmpty() }
    }.filterValues { it.isNotEmpty() }
}
