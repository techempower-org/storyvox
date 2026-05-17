package `in`.jphe.storyvox.playback.voice

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plugin-seam Phase 4 (#501) — known voice families, surfaced as cards
 * in the Plugin Manager's Voice bundles section.
 *
 * A "voice family" is a TTS engine + its bundle of voices. storyvox
 * ships four installed families (Piper, Kokoro, KittenTTS, Azure HD)
 * and reserves the "VoxSherpa upstreams" id as a placeholder for
 * future engine-lib additions.
 *
 * The family ids here are the **registry keys** — they're persisted in
 * `UiSettings.voiceFamiliesEnabled` and surfaced by [VoiceFamilyRegistry].
 * They are **not** voice ids — those are per-voice (`piper_lessac_*`,
 * `kokoro_alloy_*`, etc.) and live in [VoiceCatalog].
 *
 * @property id Stable key for `voiceFamiliesEnabled` and routing.
 * @property displayName Card title (e.g. "Piper", "Azure HD voices").
 * @property description One-line subtitle for the card.
 * @property sourceUrl Canonical upstream URL for the details modal.
 * @property license Human-readable license string for the details modal.
 * @property sizeHint Human-readable size info (e.g. "~14–30 MB each",
 *  "~330 MB single download"). Empty when not meaningful.
 * @property requiresConfiguration True for BYOK families (Azure) where
 *  the family is "installed" but unusable until the user provides
 *  credentials. The card surfaces a "Configure → Settings" CTA in
 *  place of the "Manage voices →" link.
 * @property isPlaceholder True for the "VoxSherpa upstreams" entry that
 *  represents future engine-lib voice families. Renders with a muted
 *  outline and no toggle / Manage voices link.
 * @property defaultEnabled Whether a fresh install seeds this family's
 *  toggle as ON.
 * @property engineFamily Categorisation chip shown on the card —
 *  "Local" (on-device synth) or "Cloud" (network-backed).
 */
data class VoiceFamilyDescriptor(
    val id: String,
    val displayName: String,
    val description: String,
    val sourceUrl: String,
    val license: String,
    val sizeHint: String,
    val requiresConfiguration: Boolean = false,
    val isPlaceholder: Boolean = false,
    val defaultEnabled: Boolean = true,
    val engineFamily: VoiceEngineFamily = VoiceEngineFamily.Local,
)

/** Coarse engine classification for the family card's capability chip. */
enum class VoiceEngineFamily { Local, Cloud }

/**
 * Canonical voice-family ids — also the keys in
 * `UiSettings.voiceFamiliesEnabled`. Kept as constants so consumers
 * (Voice Library filter, Plugin Manager card row, settings codec)
 * don't drift on string literals.
 */
object VoiceFamilyIds {
    const val PIPER = "voice_piper"
    const val KOKORO = "voice_kokoro"
    const val KITTEN = "voice_kitten"
    const val AZURE = "voice_azure"
    /** Issue #676 — Android System TTS family. Zero-download
     *  first-launch tier; surfaces whatever TTS engines the OS already
     *  has installed (Google, Samsung, eSpeak, etc.). */
    const val SYSTEM_TTS = "voice_system_tts"
    /** Placeholder for future engine-lib voice families. Has no
     *  toggle in the manager card — exists so users can see the
     *  shape of "the next thing that lands here". */
    const val VOXSHERPA_UPSTREAMS = "voice_voxsherpa_upstreams"
}

/**
 * Plugin-seam Phase 4 (#501) — runtime registry of every voice family
 * the Plugin Manager surfaces as a brass-edged card.
 *
 * The list is **static** today: the four installed engines plus the
 * VoxSherpa-upstreams placeholder. Each [VoiceFamilyDescriptor] is
 * declarative metadata — the engine code itself (`KokoroEngine`,
 * `KittenEngine`, `AzureVoiceEngine`) is wired through the playback
 * pipeline independently of this registry.
 *
 * Adding a new family is a two-line change here. When a `:source-foo`
 * module lands and wants to surface a new family card without touching
 * this file, the natural next step is to migrate the list to a Hilt
 * multibinding (`Set<VoiceFamilyDescriptor>`), mirroring
 * `SourcePluginRegistry`. The static list is the cheaper Phase-4 form
 * for the four in-tree families.
 *
 * The `voiceFamily` extension on [EngineType] (in this file's
 * companion code) maps a per-voice [EngineType] to the matching family
 * id, so the Voice Library can filter voices by enabled family in O(N)
 * without touching the registry itself.
 */
@Singleton
class VoiceFamilyRegistry @Inject constructor() {

    /** All known voice families, in display order. System TTS comes
     *  first as the zero-download first-launch tier (#676); then the
     *  in-process neural families (Piper / Kokoro / Kitten); then
     *  cloud (Azure); then placeholders. */
    val descriptors: List<VoiceFamilyDescriptor> = listOf(
        VoiceFamilyDescriptor(
            id = VoiceFamilyIds.SYSTEM_TTS,
            displayName = "System TTS",
            description = "Uses your device's built-in voice — no download needed",
            sourceUrl = "https://developer.android.com/reference/android/speech/tts/TextToSpeech",
            license = "Bundled with Android — varies by engine (Google / Samsung / eSpeak / etc.)",
            sizeHint = "0 MB — synthesis happens via Android's TextToSpeech framework",
            defaultEnabled = true,
            // The OS engine runs locally (no network for Google's
            // offline voices, no network for Samsung TTS), so we
            // classify Local. A handful of Google network-tier voices
            // do require connectivity; future work could split tier
            // chips per-voice — keep this Local for now so the family
            // card reads honestly about the common case.
            engineFamily = VoiceEngineFamily.Local,
        ),
        VoiceFamilyDescriptor(
            id = VoiceFamilyIds.PIPER,
            displayName = "Piper",
            description = "Local neural voices · per-voice ONNX download",
            sourceUrl = "https://github.com/rhasspy/piper-voices",
            license = "MIT (sherpa-onnx) · CC-BY / CC0 voice datasets",
            sizeHint = "~14–30 MB per voice (low / medium tier), ~120 MB high tier",
            defaultEnabled = true,
            engineFamily = VoiceEngineFamily.Local,
        ),
        VoiceFamilyDescriptor(
            id = VoiceFamilyIds.KOKORO,
            displayName = "Kokoro",
            description = "Local multi-speaker · one shared ~330 MB model",
            sourceUrl = "https://huggingface.co/hexgrad/Kokoro-82M",
            license = "Apache 2.0",
            sizeHint = "~330 MB single download, 53 speakers share the model",
            defaultEnabled = true,
            engineFamily = VoiceEngineFamily.Local,
        ),
        VoiceFamilyDescriptor(
            id = VoiceFamilyIds.KITTEN,
            displayName = "KittenTTS",
            description = "Local lightweight · ~24 MB shared, 8 en_US speakers",
            sourceUrl = "https://github.com/KittenML/KittenTTS",
            license = "Apache 2.0",
            sizeHint = "~24 MB shared model, 8 en_US speakers (F1–F4 / M1–M4)",
            defaultEnabled = true,
            engineFamily = VoiceEngineFamily.Local,
        ),
        VoiceFamilyDescriptor(
            id = VoiceFamilyIds.AZURE,
            displayName = "Azure HD voices",
            description = "Cloud · BYOK · Dragon HD + Multilingual + Neural tiers",
            sourceUrl = "https://learn.microsoft.com/en-us/azure/ai-services/speech-service/language-support",
            license = "Proprietary · billed by Azure ($30 / 1M chars)",
            sizeHint = "0 MB local — synthesis happens server-side",
            requiresConfiguration = true,
            // Default OFF so a fresh install doesn't pretend to have Azure
            // voices ready; the user opts in by configuring credentials.
            defaultEnabled = false,
            engineFamily = VoiceEngineFamily.Cloud,
        ),
        VoiceFamilyDescriptor(
            id = VoiceFamilyIds.VOXSHERPA_UPSTREAMS,
            displayName = "VoxSherpa upstreams",
            description = "Placeholder for future engine-lib voice families",
            sourceUrl = "https://github.com/techempower-org/VoxSherpa-TTS",
            license = "—",
            sizeHint = "—",
            isPlaceholder = true,
            defaultEnabled = false,
            engineFamily = VoiceEngineFamily.Local,
        ),
    )

    /** Lookup by stable family id. */
    fun byId(id: String): VoiceFamilyDescriptor? = descriptors.firstOrNull { it.id == id }

    /** All non-placeholder family ids — the set that participates in
     *  Voice Library filtering. The placeholder has no voices and is
     *  never toggled. */
    val toggleableIds: List<String> = descriptors.filterNot { it.isPlaceholder }.map { it.id }
}

/**
 * Map a per-voice [EngineType] to the [VoiceFamilyDescriptor.id] that
 * owns it. Used by the Voice Library filter to hide voices belonging
 * to a disabled family.
 *
 * Unknown engine types fall back to [VoiceFamilyIds.VOXSHERPA_UPSTREAMS]
 * — they're never enabled, so unrecognised engines surface as filtered
 * out by default. This keeps a future `EngineType.Foo` from leaking
 * into the Voice Library before its family card lands.
 */
fun EngineType.voiceFamilyId(): String = when (this) {
    is EngineType.Piper -> VoiceFamilyIds.PIPER
    is EngineType.Kokoro -> VoiceFamilyIds.KOKORO
    is EngineType.Kitten -> VoiceFamilyIds.KITTEN
    is EngineType.Azure -> VoiceFamilyIds.AZURE
    is EngineType.SystemTts -> VoiceFamilyIds.SYSTEM_TTS
}
