package `in`.jphe.storyvox.playback.voice

/**
 * UI-facing description of a voice the user can choose.
 *
 * One row per item in [VoiceCatalog]. The same shape covers both
 * Piper (per-voice .onnx + tokens.txt downloaded from huggingface) and
 * Kokoro (a single shared model bundled by the user that exposes 53
 * speaker IDs). The [engineType] discriminator tells the playback layer
 * which path to take when the user picks this voice.
 *
 * [sizeBytes] is the on-disk install size (catalog estimate for Piper;
 * 0 for Kokoro since the speaker selection is just an integer index into
 * the shared Kokoro model — no per-speaker download).
 *
 * [displayName] is the **clean** voice name, e.g. "Lessac" or "Aoede" —
 * no tier parentheticals, no flag prefix, no engine/quality clutter.
 * See #128: the Voice Library composes the on-screen title as
 * `<flag> <displayName>` and the subtitle as `<Engine> · <Tier> · <Gender>`,
 * so this field stays render-agnostic. (Curated picks keep their ⭐
 * marker prefixed inline — see [VoiceCatalog.featuredIds].)
 */
data class UiVoiceInfo(
    val id: String,
    val displayName: String,
    val language: String,
    val sizeBytes: Long,
    val isInstalled: Boolean,
    val qualityLevel: QualityLevel,
    val engineType: EngineType,
    val gender: VoiceGender = VoiceGender.Unknown,
    /** PR-H (#86) — bytes of PCM cache attributed to this voice, summed
     *  across every chapter / fiction the user has played with this
     *  voice active. 0 when no cache exists for the voice (a fresh
     *  install, or a voice the user has selected but never actually
     *  listened with). VoiceLibraryScreen renders this as an "X MB
     *  cached" subtitle so the user can see which voices they've
     *  meaningfully used vs which they've merely installed. Defaulted
     *  to 0 so every catalog-side construction stays source-compatible
     *  — only the VoiceLibraryViewModel layer threads the real value
     *  in via [CacheStateInspector.bytesUsedByEveryVoice]. */
    val cachedBytes: Long = 0L,
)

/**
 * Voice gender as surfaced in the Voice Library subtitle. Best-effort
 * — Piper's upstream filenames don't always encode it (e.g. "lessac",
 * "ryan", "amy" name speakers but only some carry an explicit gender
 * marker like `*_male` / `*_female`). [Unknown] renders as an empty
 * subtitle segment so the line collapses cleanly to `Engine · Tier`.
 *
 * Kokoro voices all carry an upstream gender grade — every Kokoro
 * entry resolves to [Female] or [Male].
 */
enum class VoiceGender { Female, Male, Unknown }

/**
 * Voice quality tiers shown in the picker. Order in this enum is
 * **ascending**, but the UI sorts **descending** (Studio first, Low last)
 * so users see the best voices at the top.
 *
 * - **Piper** voices encode tier in their model filename suffix
 *   (`*-low`, `*-medium`, `*-high`). [Studio] never applies to Piper —
 *   it's reserved for the highest-graded Kokoro speakers.
 * - **Kokoro** voices share one model with no per-voice quality
 *   variants; tier is therefore a curated property keyed on voice id
 *   (see [VoiceCatalog.kokoroEntries]). The [Studio] picks reflect
 *   upstream `hexgrad/Kokoro-82M` voice grades — the handful of
 *   speakers that reliably produce broadcast-grade audio without the
 *   small artifacts that creep into the lower-graded speakers.
 */
enum class QualityLevel { Low, Medium, High, Studio }

sealed interface EngineType {
    /** Piper voice — per-voice .onnx downloaded from rhasspy/piper-voices. */
    data object Piper : EngineType

    /** Kokoro speaker — shared Kokoro model, picks a speaker by index. */
    data class Kokoro(val speakerId: Int) : EngineType

    /**
     * KittenTTS speaker — shared Kitten model, picks a speaker by index.
     *
     * Issue #119 — third in-process voice family, slotted **below**
     * Piper-low as storyvox's smallest tier. Wraps VoxSherpa-TTS's
     * `KittenEngine` (v2.8.0+), which in turn wraps sherpa-onnx's
     * `OfflineTtsKittenModelConfig` path.
     *
     * Architecturally a twin of [Kokoro]: a single shared model (~25 MB
     * fp16 int8 ONNX + voices.bin + tokens.txt) supports 8 speakers
     * selected at synth time by [speakerId]. Picking any Kitten voice
     * triggers the shared-model download exactly once; subsequent picks
     * just flip the active speaker index with no additional payload.
     * The 25 MB footprint makes it the friendliest "try a neural voice"
     * first-launch onboarding path — a step beneath the 14 MB Piper-low
     * voices, but with multi-speaker variety.
     */
    data class Kitten(val speakerId: Int) : EngineType

    /**
     * Azure Speech Services HD voice. Cloud-rendered TTS over HTTPS;
     * no local model. [voiceName] is the Azure voice id (e.g.
     * `en-US-AvaDragonHDLatestNeural`, `en-US-AndrewMultilingualNeural`).
     * [region] is the Azure resource region; user-configurable in
     * Settings, defaults to `eastus`.
     *
     * The catalog entry seams in here ahead of the engine wiring (PR-4
     * in Solara's plan) so PR-3 can land Settings → Sources → Azure UI
     * against a stable type without circular module dependencies.
     */
    data class Azure(val voiceName: String, val region: String) : EngineType

    /**
     * Issue #676 — Android System TTS as a voice backend. Uses whatever
     * engine the OS has set as default (Google Speech Services, Samsung
     * TTS, Bixby, eSpeak, etc.) via Android's framework `TextToSpeech`.
     *
     * [engineName] is the package id of the chosen engine
     * (`com.google.android.tts`, `com.samsung.SMT`, etc.). [voiceName]
     * is the system TTS voice id within that engine
     * (`en-us-x-iol-network`, `en-US-language`, etc.).
     *
     * Why a [SystemTts] variant (not a generic flag on Piper):
     * the synthesis surface is fundamentally different — it goes
     * through Android's `TextToSpeech.synthesizeToFile()` which writes
     * a 44-byte-header WAV file we strip back to raw PCM. The voice
     * lifecycle is also different (async `onInit`, per-engine package
     * binding, framework-managed). Treating it as its own variant
     * keeps the dispatch in EnginePlayer mechanical instead of
     * branching inside each Piper/Kokoro/Kitten code path.
     *
     * Surfaced as #676's "zero-download first-launch voice" — fresh
     * installs default to a SystemTts voice so the first playback
     * works without ever showing a "downloading model..." spinner.
     * Sight-impaired users with TalkBack already have a configured
     * default TTS voice; this variant surfaces it.
     */
    data class SystemTts(val engineName: String, val voiceName: String) : EngineType
}
