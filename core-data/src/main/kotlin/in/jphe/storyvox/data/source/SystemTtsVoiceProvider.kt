package `in`.jphe.storyvox.data.source

import kotlinx.coroutines.flow.Flow

/**
 * Issue #676 — Android System TTS voice roster surface.
 *
 * Live roster of voices exposed by the OS's framework
 * `android.speech.tts.TextToSpeech` — across all installed TTS engines
 * (Google Speech Services, Samsung TTS, Bixby, eSpeak, etc.). The
 * interface lives here in `:core-data` so `:core-playback`'s
 * `VoiceCatalog` can project the descriptors into `CatalogEntry` rows
 * without taking a build dep on `:app` (where the Android-context-bound
 * impl has to live).
 *
 * **Empty until the first TextToSpeech.onInit callback fires.** On
 * stock Samsung tablets Google TTS resolves in ~150 ms; on devices
 * with no installed TTS engines at all the flow stays empty. Callers
 * that need to gate on "is System TTS available" should observe the
 * flow and check `isNotEmpty()` rather than presuming a synchronous
 * snapshot.
 *
 * **Architectural twin of [AzureVoiceProvider].** Same shape, same
 * cycle-avoidance reason — the Android `Context`-bound impl can't
 * live in `:core-playback` (well, it could, but pulling the impl into
 * `:app` keeps the wiring symmetric with how `:source-azure` binds
 * `AzureVoiceRoster`).
 */
interface SystemTtsVoiceProvider {
    /**
     * Live roster — empty until the framework finishes enumerating.
     * Updates whenever an engine is installed or removed at the OS
     * level (best-effort: we don't subscribe to PackageManager
     * broadcasts in v1.0; a manual [refresh] or an app cold-start
     * picks up changes).
     */
    val voices: Flow<List<SystemTtsVoiceDescriptor>>

    /**
     * Re-enumerate the OS's installed TTS engines and voices. Idempotent;
     * concurrent calls coalesce. The first observation of [voices] kicks
     * an implicit refresh, so this is only needed when the caller has
     * reason to believe the OS state changed (Settings → Open TTS
     * settings round-trip, for instance).
     */
    suspend fun refresh()
}

/**
 * One System TTS voice as surfaced by the framework.
 *
 * - [engineName] is the package id of the chosen engine
 *   (`com.google.android.tts`, `com.samsung.SMT`, `com.reecedunn.espeak`,
 *   etc.). Used both as the user-facing label seed and as the second
 *   arg to `TextToSpeech(context, listener, engineName)` so we pin
 *   which installed provider services the synthesis request.
 * - [engineLabel] is the human-readable engine label from
 *   `TextToSpeech.EngineInfo.label` ("Google", "Samsung text-to-speech
 *   engine"). Surfaces in the picker subtitle.
 * - [voiceName] is the framework `Voice.name` — opaque-but-stable; we
 *   round-trip it to select the voice via
 *   `TextToSpeech.voices.firstOrNull { it.name == voiceName }`.
 * - [displayName] is what the picker renders for the voice. Derived
 *   from `voiceName`'s last component humanized (the framework doesn't
 *   expose a friendly name) plus the locale.
 * - [locale] is the BCP-47 locale (`en-US`, `en-GB`, etc.) for the
 *   sort key. The catalog uses underscored form (`en_US`); convert at
 *   the boundary.
 * - [isNetworkConnectionRequired] mirrors the framework's
 *   `Voice.isNetworkConnectionRequired`. Drives the picker's sort —
 *   offline voices land above network-tier voices.
 * - [requiresInternetConnection] is the framework's "needs internet for
 *   this synthesis" flag. v1.0 doesn't gate by it (Google's network
 *   voices still work over Wi-Fi); a v1.x follow-up could filter or
 *   warn before using one.
 */
data class SystemTtsVoiceDescriptor(
    val engineName: String,
    val engineLabel: String,
    val voiceName: String,
    val displayName: String,
    val locale: String,
    val isNetworkConnectionRequired: Boolean,
    val requiresInternetConnection: Boolean = isNetworkConnectionRequired,
)
