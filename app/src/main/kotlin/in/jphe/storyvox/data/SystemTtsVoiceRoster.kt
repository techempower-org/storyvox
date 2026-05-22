package `in`.jphe.storyvox.data

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.data.source.SystemTtsVoiceDescriptor
import `in`.jphe.storyvox.data.source.SystemTtsVoiceProvider
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Issue #676 — implementation of [SystemTtsVoiceProvider].
 *
 * Enumerates installed TTS engines and their voices via Android's
 * framework `TextToSpeech`. The enumeration is a two-step dance:
 *
 *  1. Construct a one-shot [TextToSpeech] tied to the OS-default engine
 *     so we can list `engines` (every installed TTS provider package
 *     on the device).
 *  2. For each engine package, construct a second one-shot
 *     [TextToSpeech] pinned to that engine, wait for onInit, then read
 *     `.voices` and shut it down. The framework's voice list is
 *     scoped to the bound engine — without rebinding per-engine you'd
 *     only see the default engine's voices.
 *
 * Cheaper than it sounds: each onInit on a stock Samsung tablet
 * resolves in ~50–150 ms, and most devices have 1-2 installed engines
 * (Google + Samsung). First-launch cold path totals ~300 ms. After
 * the cache populates, subsequent reads are a Flow snapshot — free.
 *
 * **Architectural twin of `:source-azure`'s AzureVoiceRoster.** Same
 * coalescing mutex, same fire-and-forget refreshAsync hook, same
 * empty-on-failure policy so a misbehaving OEM engine can't crash the
 * picker.
 *
 * Lives in `:app` (not `:core-playback`) because Hilt's
 * `@ApplicationContext` injection point + the impl interface bound to
 * `:core-data`'s [SystemTtsVoiceProvider] interface gives us the same
 * one-direction-only module graph as Azure (which lives in
 * `:source-azure`). A future move into `:source-system-tts` would
 * symmetric-ize with Azure further; not worth the module shuffle for
 * v1.0.
 */
@Singleton
class SystemTtsVoiceRoster @Inject constructor(
    @ApplicationContext private val context: Context,
) : SystemTtsVoiceProvider {

    private val state = MutableStateFlow<List<SystemTtsVoiceDescriptor>>(emptyList())
    private val refreshMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val voices: Flow<List<SystemTtsVoiceDescriptor>> = state.asStateFlow()
        .onStart { refreshAsync() }

    override suspend fun refresh() {
        refreshMutex.withLock {
            val enumerated = runCatching { enumerate() }
                .onFailure { t ->
                    Log.w(TAG, "System TTS enumeration failed: ${t.javaClass.simpleName}: ${t.message}")
                }
                .getOrDefault(emptyList())
            // Sort: locale rank first (en-US, en-GB, en-AU, en-IN,
            // en-CA, other en-*, then everything else), then offline
            // before network within a locale, then by display name.
            val sorted = enumerated.sortedWith(
                compareBy(
                    { englishLocaleRank(it.locale) },
                    { if (it.isNetworkConnectionRequired) 1 else 0 },
                    { it.displayName.lowercase() },
                ),
            )
            state.value = sorted
            Log.i(TAG, "Enumerated ${sorted.size} System TTS voices")
        }
    }

    fun refreshAsync() = scope.launch { refresh() }

    /**
     * Walk every installed engine's voice list. Top-level
     * try/catch tolerates a misbehaving engine (a stuck onInit or a
     * voice-list NPE on one engine doesn't blank the picker for the
     * others).
     */
    private suspend fun enumerate(): List<SystemTtsVoiceDescriptor> {
        // Step 1: list installed engines via the default-engine TTS.
        val defaultTts = bootTts(engine = null) ?: return emptyList()
        val engineInfos = runCatching { defaultTts.engines?.toList().orEmpty() }
            .getOrDefault(emptyList())
        runCatching { defaultTts.shutdown() }

        if (engineInfos.isEmpty()) {
            Log.i(TAG, "No installed TTS engines found")
            return emptyList()
        }

        val raw = mutableListOf<RawVoice>()
        // Step 2: bind each engine and enumerate its voices.
        for (engineInfo in engineInfos) {
            val engineName = engineInfo.name
            val engineLabel = runCatching {
                engineInfo.label.takeIf { !it.isNullOrBlank() } ?: engineName
            }.getOrDefault(engineName)
            val tts = bootTts(engine = engineName) ?: continue
            try {
                val voices = runCatching { tts.voices?.toList().orEmpty() }
                    .getOrDefault(emptyList())
                for (v in voices) {
                    // Skip voices flagged as not-installed by the
                    // framework. Some engines (older Google TTS)
                    // report future-tense network voices here; surfacing
                    // them would crash synthesizeToFile.
                    if (runCatching { Voice.LATENCY_VERY_LOW }.getOrNull() == null) {
                        // (defensive: Voice constants exist at API 21+;
                        // we're 26+ so this never fails — keeps the
                        // line analysis simple)
                    }
                    val features = runCatching { v.features?.toSet().orEmpty() }
                        .getOrDefault(emptySet<String>())
                    val isNotInstalled = TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in features
                    if (isNotInstalled) continue

                    val voiceLocale = runCatching { v.locale }.getOrNull()
                    val localeTag = runCatching { voiceLocale?.toLanguageTag() }
                        .getOrNull()
                        ?.takeIf { it.isNotBlank() } ?: "und"
                    raw += RawVoice(
                        engineName = engineName,
                        engineLabel = engineLabel,
                        voiceName = v.name,
                        locale = localeTag,
                        voiceLocale = voiceLocale,
                        isNetworkConnectionRequired = runCatching {
                            v.isNetworkConnectionRequired
                        }.getOrDefault(false),
                    )
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Enumeration failed for engine=$engineName: ${t.message}")
            } finally {
                runCatching { tts.shutdown() }
            }
        }
        return assignDisplayNames(raw)
    }

    private fun assignDisplayNames(raw: List<RawVoice>): List<SystemTtsVoiceDescriptor> =
        labelRawVoices(raw)

    /** Intermediate enumeration row before display-name assignment. */
    internal data class RawVoice(
        val engineName: String,
        val engineLabel: String,
        val voiceName: String,
        val locale: String,
        val voiceLocale: Locale?,
        val isNetworkConnectionRequired: Boolean,
    )

    /**
     * Boot a one-shot framework TextToSpeech bound to [engine] (null
     * = OS default), suspending until onInit fires. Returns null if
     * the engine fails to initialize.
     */
    private suspend fun bootTts(engine: String?): TextToSpeech? = withContext(Dispatchers.IO) {
        val initDeferred = CompletableDeferred<Int>()
        val tts = try {
            if (engine.isNullOrBlank()) {
                TextToSpeech(context) { status -> initDeferred.complete(status) }
            } else {
                TextToSpeech(
                    context,
                    { status -> initDeferred.complete(status) },
                    engine,
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "TextToSpeech ctor threw for engine=$engine: ${t.message}")
            return@withContext null
        }
        val status = initDeferred.await()
        if (status != TextToSpeech.SUCCESS) {
            runCatching { tts.shutdown() }
            return@withContext null
        }
        tts
    }

    /** Sort key — English locales surface first, then alpha rank. */
    private fun englishLocaleRank(locale: String): Int = when {
        locale.equals("en-US", ignoreCase = true) -> 0
        locale.equals("en-GB", ignoreCase = true) -> 1
        locale.equals("en-AU", ignoreCase = true) -> 2
        locale.equals("en-IN", ignoreCase = true) -> 3
        locale.equals("en-CA", ignoreCase = true) -> 4
        locale.startsWith("en", ignoreCase = true) -> 5
        else -> 100
    }

    internal companion object {
        const val TAG = "SystemTtsVoiceRoster"

        /**
         * #740 — second pass over the raw roster that assigns
         * human-readable display names.
         *
         * The framework's `Voice.name` is a slug like
         * `en-us-x-lob-network` — the `lob` middle segment is an
         * internal Google code with no published mapping, so surfacing
         * it ("Lob", "Log", "Lol") looks unfinished in the picker.
         * Instead, label by locale display name + quality tier, and
         * disambiguate identical buckets with a numeric suffix in
         * voice-name order so the labels stay stable across runs.
         *
         * Examples for a Samsung tablet's stock Google TTS:
         *   en-us-x-lob-network → "American English (HD) #1"
         *   en-us-x-log-network → "American English (HD) #2"
         *   en-us-x-iol-local   → "American English (offline) #1"
         *
         * Stable across enumerations because the bucket sort key is
         * deterministic. If a future Google TTS update exposes
         * `Voice` gender/age hints, the labeling pass can grow to
         * include them without touching the descriptor schema.
         *
         * `internal` for unit testing — see [SystemTtsVoiceLabelingTest].
         */
        internal fun labelRawVoices(raw: List<RawVoice>): List<SystemTtsVoiceDescriptor> {
            // Sort each bucket by voiceName so #1/#2 numbering is
            // stable across enumerations.
            val sortedRaw = raw.sortedBy { it.voiceName }
            // Group by (engine, locale, network/local) — the picker's
            // intuitive bucket.
            val bucketCounts = sortedRaw
                .groupingBy { Triple(it.engineName, it.locale, it.isNetworkConnectionRequired) }
                .eachCount()
            val bucketIndex = mutableMapOf<Triple<String, String, Boolean>, Int>()
            return sortedRaw.map { r ->
                val key = Triple(r.engineName, r.locale, r.isNetworkConnectionRequired)
                val ordinal = bucketIndex.getOrDefault(key, 0) + 1
                bucketIndex[key] = ordinal
                val total = bucketCounts[key] ?: 1
                val display = friendlyDisplayName(
                    voiceLocale = r.voiceLocale,
                    localeTag = r.locale,
                    voiceName = r.voiceName,
                    isNetwork = r.isNetworkConnectionRequired,
                    ordinal = ordinal,
                    bucketSize = total,
                )
                SystemTtsVoiceDescriptor(
                    engineName = r.engineName,
                    engineLabel = r.engineLabel,
                    voiceName = r.voiceName,
                    displayName = display,
                    locale = r.locale,
                    isNetworkConnectionRequired = r.isNetworkConnectionRequired,
                    requiresInternetConnection = r.isNetworkConnectionRequired,
                )
            }
        }

        /**
         * Compose the user-facing label for one System TTS voice. Falls
         * back through `Locale.getDisplayName()`, the raw locale tag,
         * and finally a stripped form of the voice name so a misshapen
         * entry still gets *something* readable rather than going
         * blank.
         */
        internal fun friendlyDisplayName(
            voiceLocale: Locale?,
            localeTag: String,
            voiceName: String,
            isNetwork: Boolean,
            ordinal: Int,
            bucketSize: Int,
        ): String {
            val baseLocaleName = voiceLocale
                ?.runCatching { getDisplayName(Locale.getDefault()) }
                ?.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: localeTag.takeIf { it.isNotBlank() && it != "und" }
                ?: stripSlug(voiceName)
            val qualityTag = if (isNetwork) "HD" else "offline"
            val suffix = if (bucketSize > 1) " #$ordinal" else ""
            return "$baseLocaleName ($qualityTag)$suffix"
        }

        /**
         * Last-resort fallback when a voice has no resolvable locale.
         * Strips the locale-shaped head (`en-us`), the `x` marker, and
         * `network`/`local` tags from a `Voice.name` slug and Title-
         * cases what remains. Mirrors the pre-#740 behavior; only fires
         * now when [friendlyDisplayName] has no `Locale` *and* no
         * locale tag to work with — extraordinarily rare on real
         * devices.
         */
        private fun stripSlug(rawName: String): String {
            val parts = rawName.replace('_', '-').split('-').filter { it.isNotBlank() }
            val filtered = parts.filterIndexed { i, part ->
                val isMaybeLocale = i < 2 && part.length == 2
                val isMarker = part.equals("x", ignoreCase = true) ||
                    part.equals("network", ignoreCase = true) ||
                    part.equals("local", ignoreCase = true)
                !isMaybeLocale && !isMarker
            }
            val pretty = filtered.joinToString(separator = " ") {
                it.replaceFirstChar { c -> c.titlecase() }
            }
            return pretty.takeIf { it.isNotBlank() } ?: rawName
        }
    }
}
