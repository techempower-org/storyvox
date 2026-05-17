package `in`.jphe.storyvox.playback.voice

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.data.source.AzureVoiceProvider
import `in`.jphe.storyvox.data.source.SystemTtsVoiceProvider
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink

private val Context.voicesSettingsStore: DataStore<Preferences> by preferencesDataStore(name = "voices_settings")

private object VoiceKeys {
    val INSTALLED_IDS = stringSetPreferencesKey("installed_voice_ids")
    val ACTIVE_ID = stringPreferencesKey("active_voice_id")

    /**
     * Issue #547 — sticky persistent "user has made an onboarding
     * decision" flag. Flipped to true when the user taps "Continue
     * without audio" on the [VoicePickerGate], or when [ACTIVE_ID]
     * is set for the first time (handled implicitly: a non-null
     * activeVoice already suppresses the gate). Once true, the gate
     * stays dismissed across cold launches — even if the active voice
     * is later deleted, the user explicitly chose reader-only at some
     * point and the gate should not re-pester them.
     *
     * To force the gate to re-appear (manual "Help me pick a voice
     * again" flow), the user clears Storage → app data, or a future
     * settings entry can call [markPickerForgotten].
     */
    val PICKER_DISMISSED = booleanPreferencesKey("voice_picker_dismissed")
}

/**
 * Owns the user-facing voice library: which voices are downloadable
 * (the [VoiceCatalog]), which are installed on disk, and which one is
 * active for playback.
 *
 * Storage layout under [Context.getFilesDir]:
 * ```
 * voices/
 *   {voiceId}/
 *     model.onnx       (Piper) — model weights
 *     tokens.json      (Piper) — tokens metadata (.onnx.json from huggingface)
 * ```
 * Kokoro entries live entirely in DataStore: a Kokoro selection means
 * "use the shared Kokoro model that the engine has loaded, with this
 * speaker index" — no per-voice file payload. We mark Kokoro voices as
 * installed the moment [setActive] is called for them (or [download]
 * is called — for parity it just emits Done immediately).
 *
 * Why DataStore for state instead of just listing the voices directory?
 * - Survives app restart cleanly without a directory walk.
 * - Stores active selection alongside installed set in one transaction.
 * - Plays nicely with the Flow surface other modules consume.
 */
@Singleton
class VoiceManager @Inject constructor(
    @ApplicationContext private val context: Context,
    /**
     * Live Azure roster — surfaces voices that actually exist in the
     * user's configured region. Replaces the v0.4.x hardcoded Azure
     * catalog (which carried wrong Dragon HD names and ignored
     * region-scoped availability). Empty until the user configures a
     * key + the first fetch completes.
     */
    private val azureVoiceProvider: AzureVoiceProvider,
    /**
     * Issue #676 — live System TTS roster. Surfaces voices installed
     * on-device via Android's framework `TextToSpeech` (Google,
     * Samsung, eSpeak, etc.). Empty until the first enumeration
     * completes (~150–500 ms on a stock Samsung tablet).
     */
    private val systemTtsVoiceProvider: SystemTtsVoiceProvider,
) {

    private val store: DataStore<Preferences> = context.voicesSettingsStore
    @VisibleForTesting internal var http: OkHttpClient = OkHttpClient.Builder().build()
    private val migrationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // One-shot migration for users who installed v0.4.5–v0.4.13 with
        // `piper_*_int8`-suffixed IDs. The suffix was kept across the
        // INT8→fp32 catalog repoint to preserve persisted state, but it's
        // misleading (the files have been fp32 since v0.4.12). Strip it
        // from DataStore + rename matching voice directories.
        migrationScope.launch { migrateInt8VoiceIds() }
    }

    private suspend fun migrateInt8VoiceIds() {
        // 1. Filesystem: voices/{id}_int8/ → voices/{id}/. Track which IDs
        //    we successfully migrated so we don't rewrite DataStore for an
        //    ID whose directory we couldn't actually move (e.g. target
        //    already exists from a previous interrupted migration, or
        //    File.renameTo returns false on this filesystem). The
        //    [normalizeId] reads tolerate either form, so a partial
        //    migration is still functional — but we want DataStore to
        //    eventually agree with the on-disk truth.
        val migratedIds = mutableSetOf<String>()
        val voicesRoot = File(context.filesDir, "voices")
        if (voicesRoot.exists()) {
            voicesRoot.listFiles()?.forEach { dir ->
                if (dir.isDirectory && dir.name.endsWith("_int8")) {
                    val newName = dir.name.removeSuffix("_int8")
                    val newDir = File(voicesRoot, newName)
                    when {
                        newDir.exists() -> {
                            // New-form dir already there; treat the rename
                            // as logically done. Any duplicate files on the
                            // old path are dead weight but harmless.
                            migratedIds += newName
                        }
                        dir.renameTo(newDir) -> migratedIds += newName
                        // else: rename failed — leave both DataStore and
                        // disk in legacy state. Reads still resolve.
                    }
                }
            }
        }
        // 2. DataStore: only rewrite the legacy IDs whose directory we
        //    actually moved (or that have no per-voice directory at all,
        //    i.e. Kokoro entries). Anything else stays as `_int8` so
        //    voiceDirFor(id) still resolves.
        store.edit { prefs ->
            prefs[VoiceKeys.ACTIVE_ID]?.let { active ->
                val stripped = active.removeSuffix("_int8")
                val isLegacy = active.endsWith("_int8")
                val isKokoro = stripped.startsWith("kokoro_")
                if (isLegacy && (isKokoro || stripped in migratedIds)) {
                    prefs[VoiceKeys.ACTIVE_ID] = stripped
                }
            }
            prefs[VoiceKeys.INSTALLED_IDS]?.let { ids ->
                val rewritten = ids.map {
                    val stripped = it.removeSuffix("_int8")
                    val isLegacy = it.endsWith("_int8")
                    val isKokoro = stripped.startsWith("kokoro_")
                    if (isLegacy && (isKokoro || stripped in migratedIds)) stripped else it
                }.toSet()
                if (rewritten != ids) prefs[VoiceKeys.INSTALLED_IDS] = rewritten
            }
        }
    }

    /** Catalog projected as [UiVoiceInfo]. The Piper / Kokoro half is
     *  static — the Azure half is empty until the live roster fetch
     *  populates it. Callers that want the live state should use
     *  [availableVoicesFlow] instead. */
    val availableVoices: List<UiVoiceInfo>
        get() = VoiceCatalog.voices.map { it.toUiVoiceInfo(installed = false) }

    /** Hot Flow of [availableVoices] — combines the static catalog
     *  with the live Azure + System TTS rosters (#676). Use this when
     *  you want the picker to react to roster updates (region change,
     *  key change, OS engine install/uninstall, refresh). */
    val availableVoicesFlow: Flow<List<UiVoiceInfo>> =
        azureVoiceProvider.voices.combine(systemTtsVoiceProvider.voices) { azure, system ->
            VoiceCatalog.voicesWithAzureAndSystemTts(azure, system)
                .map { it.toUiVoiceInfo(installed = false) }
        }

    /** Hot Flow of installed voices, derived from the DataStore-backed installed-id set.
     *  All 53 Kokoro speakers report installed once the shared model has been
     *  downloaded — they all share one set of files, the speaker id is just
     *  metadata baked into a generate() call.
     *
     *  Azure rows are reported installed whenever they're in the live
     *  roster — there's no per-voice download (the model lives on
     *  Azure's side), so "available in the roster" IS "installed" for
     *  picker purposes.
     *
     *  Reads `_int8`-suffixed legacy IDs through [normalizeId] so users
     *  upgrading from v0.4.5–v0.4.13 don't briefly see "no voices installed"
     *  if the async [migrateInt8VoiceIds] hasn't completed by the time the
     *  first collector observes this Flow. */
    val installedVoices: Flow<List<UiVoiceInfo>> = combine(
        store.data,
        azureVoiceProvider.voices,
        systemTtsVoiceProvider.voices,
    ) { prefs, azureRoster, systemTtsRoster ->
        val installedIds = prefs[VoiceKeys.INSTALLED_IDS].orEmpty().map(::normalizeId).toSet()
        val kokoroReady = isKokoroSharedModelInstalled()
        // Issue #119 — Kitten parallels Kokoro: shared-model presence
        // is the single source of truth for "every Kitten voice is
        // playable." Each speaker is just an index into voices.bin.
        val kittenReady = isKittenSharedModelInstalled()
        VoiceCatalog.voicesWithAzureAndSystemTts(azureRoster, systemTtsRoster)
            .filter {
                it.id in installedIds ||
                    (it.engineType is EngineType.Kokoro && kokoroReady) ||
                    (it.engineType is EngineType.Kitten && kittenReady) ||
                    it.engineType is EngineType.Azure ||
                    // #676 — System TTS voices are "installed" whenever
                    // they appear in the OS roster. The user didn't
                    // download anything per-voice; the framework
                    // exposes whatever the device's TTS engines list.
                    it.engineType is EngineType.SystemTts
            }
            .map { it.toUiVoiceInfo(installed = true) }
    }

    /** Hot Flow of the active voice (or null if nothing chosen yet).
     *  Same legacy-ID normalization as [installedVoices]. */
    val activeVoice: Flow<UiVoiceInfo?> = combine(
        store.data,
        azureVoiceProvider.voices,
        systemTtsVoiceProvider.voices,
    ) { prefs, azureRoster, systemTtsRoster ->
        val activeId = prefs[VoiceKeys.ACTIVE_ID]?.let(::normalizeId) ?: return@combine null
        val installed = prefs[VoiceKeys.INSTALLED_IDS].orEmpty().map(::normalizeId).toSet()
        val entry = VoiceCatalog.byIdWithAzureAndSystemTts(
            activeId, azureRoster, systemTtsRoster,
        ) ?: return@combine null
        val isInstalled = activeId in installed ||
            (entry.engineType is EngineType.Kokoro && isKokoroSharedModelInstalled()) ||
            (entry.engineType is EngineType.Kitten && isKittenSharedModelInstalled()) ||
            entry.engineType is EngineType.Azure ||
            // #676 — SystemTts presence in the roster IS installation.
            entry.engineType is EngineType.SystemTts
        entry.toUiVoiceInfo(installed = isInstalled)
    }

    /**
     * Issue #547 — sticky onboarding-dismissed flag, DataStore-backed.
     * `true` means the user has made a choice about the voice picker
     * (either picked a voice or tapped "Continue without audio"); the
     * gate stays down on subsequent cold launches even if [activeVoice]
     * is null. `false` (the default) means the gate may still appear.
     *
     * Distinct from [activeVoice] being non-null because the user may
     * delete every installed voice and we still don't want to bombard
     * them with the gate on next launch — they already declined to
     * commit once.
     */
    val pickerDismissed: Flow<Boolean> = store.data
        .map { prefs -> prefs[VoiceKeys.PICKER_DISMISSED] == true }

    /**
     * Issue #547 — flip the sticky dismissed flag to true. Idempotent;
     * safe to call from "Continue without audio" tap, from the picker's
     * first successful [setActive] (defensive — the active-voice check
     * would already suppress the gate, but this keeps the two paths
     * symmetric), and from any future "I'm fine without a voice" tap.
     */
    suspend fun markPickerDismissed() {
        store.edit { prefs ->
            prefs[VoiceKeys.PICKER_DISMISSED] = true
        }
    }

    /** Strip the historical `_int8` suffix so legacy stored IDs resolve
     *  against the current catalog. Used on every read; the async migration
     *  rewrites the persisted store eventually but reads must succeed before
     *  it lands. */
    private fun normalizeId(id: String): String =
        if (id.endsWith("_int8")) id.removeSuffix("_int8") else id

    private fun isKokoroSharedModelInstalled(): Boolean {
        val dir = kokoroSharedDir()
        return File(dir, "model.onnx").exists() &&
            File(dir, "voices.bin").exists() &&
            File(dir, "tokens.txt").exists()
    }

    /** Issue #119 — Kitten parallels Kokoro: one ~25 MB shared model
     *  underpins all 8 speakers, so "installed" for any Kitten voice
     *  means "the shared dir is populated." Mirrors [isKokoroSharedModelInstalled]. */
    private fun isKittenSharedModelInstalled(): Boolean {
        val dir = kittenSharedDir()
        return File(dir, "model.onnx").exists() &&
            File(dir, "voices.bin").exists() &&
            File(dir, "tokens.txt").exists()
    }

    sealed interface DownloadProgress {
        data object Resolving : DownloadProgress
        data class Downloading(val bytesRead: Long, val totalBytes: Long) : DownloadProgress
        data object Done : DownloadProgress
        data class Failed(val reason: String) : DownloadProgress
    }

    /**
     * Stream a download for [voiceId]. Emits [DownloadProgress.Resolving]
     * before any network activity, then a sequence of [Downloading]
     * frames as bytes land, then a single terminal [Done] or [Failed].
     *
     * Does NOT call [setActive] on completion — callers (the gate, the
     * library UI) decide when activation happens.
     */
    fun download(voiceId: String): Flow<DownloadProgress> = flow {
        emit(DownloadProgress.Resolving)
        val entry = VoiceCatalog.byId(voiceId)
        if (entry == null) {
            // #676 — System TTS voices live in the live roster, not
            // in the static catalog, so byId returns null. Treat as
            // a no-op success: the OS already has the engine
            // installed; we don't need to fetch anything. Anything
            // else with a non-matching id is a real error.
            if (voiceId.startsWith("system_tts_")) {
                emit(DownloadProgress.Done)
            } else {
                emit(DownloadProgress.Failed("Unknown voiceId: $voiceId"))
            }
            return@flow
        }

        when (entry.engineType) {
            is EngineType.Azure -> {
                // Cloud voice — nothing to download. The "install" step
                // for an Azure voice is BYOK key entry in Settings, which
                // PR-3 wires up. PR-4 lights the activation path.
                //
                // Currently this branch is a guarded no-op: VoiceManager
                // surfaces Azure entries in `availableVoices` (so they
                // appear greyed out in the picker per Solara's spec) but
                // never reports them as installed and refuses to "download"
                // them. Calling code in this PR shouldn't reach here yet
                // — PR-4 will replace this guard with the real activation
                // flow.
                error("Azure voices have no downloadable assets — " +
                    "credential-keyed activation arrives in PR-4. (#85)")
            }
            is EngineType.SystemTts -> {
                // Issue #676 — System TTS voices have nothing to
                // download; they live in the OS's already-installed
                // TTS engines (Google, Samsung, etc.). UI flows that
                // arrive here treat the request as a no-op success
                // — the user has already "installed" the voice by
                // having Android's TTS framework set up. We do NOT
                // call markInstalled because the
                // [installedVoices] flow tracks SystemTts presence
                // directly from the live roster (no per-voice
                // DataStore bookkeeping needed).
                emit(DownloadProgress.Done)
            }
            is EngineType.Kitten -> {
                // Issue #119 — Kitten parallels Kokoro: one shared model
                // (~25 MB total — fp16 ONNX + voices.bin + tokens.txt)
                // underpins all 8 speakers, first install lands the model,
                // subsequent voice picks just flip the active speaker index
                // with no additional payload.
                val sharedDir = kittenSharedDir()
                val onnxFile = File(sharedDir, "model.onnx")
                val tokensFile = File(sharedDir, "tokens.txt")
                val voicesFile = File(sharedDir, "voices.bin")
                if (!onnxFile.exists() || !voicesFile.exists() || !tokensFile.exists()) {
                    sharedDir.mkdirs()
                    try {
                        // kitten-nano-en-v0_1-fp16 sizes (sherpa-onnx
                        // tts-models release, repackaged onto the
                        // jphein/VoxSherpa-TTS voices-v2 release for
                        // flat per-file URLs the OkHttp loop can
                        // stream — the upstream tar.bz2 would require
                        // in-app extraction we don't want to add).
                        // Total ~23 MB on first install, dominated by
                        // the ONNX. voices.bin is tiny (8 KB — just
                        // the per-speaker embedding table).
                        val modelBytes = 23_848_586L
                        val voicesBytes = 8_192L
                        val totalBytes = modelBytes + voicesBytes  // tokens is ~1 KB, negligible
                        downloadFile(
                            url = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/kitten-nano-en-v0_1-model.onnx",
                            target = onnxFile,
                            knownTotalBytes = modelBytes,
                        ) { read, _ -> emit(DownloadProgress.Downloading(read, totalBytes)) }
                        downloadFile(
                            url = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/kitten-nano-en-v0_1-voices.bin",
                            target = voicesFile,
                            knownTotalBytes = voicesBytes,
                        ) { read, _ ->
                            // Continue progress from where the model
                            // left off so the bar keeps moving.
                            emit(DownloadProgress.Downloading(modelBytes + read, totalBytes))
                        }
                        downloadFile(
                            url = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/kitten-nano-en-v0_1-tokens.txt",
                            target = tokensFile,
                            knownTotalBytes = 0L,
                        ) { _, _ -> }
                    } catch (ce: CancellationException) {
                        // User-driven cancel — re-throw to honour
                        // structured concurrency. Mirrors the Kokoro
                        // branch's shape: partial files survive (no
                        // wipe on cancel) so a sibling that finished
                        // before the cancel doesn't get re-fetched.
                        throw ce
                    } catch (t: Throwable) {
                        // Real failure: wipe shared dir to keep retries
                        // deterministic. Trade-off matches Kokoro's
                        // branch; re-fetch cost on Kitten is tiny
                        // (~24 MB) so the wipe is essentially free.
                        sharedDir.deleteRecursively()
                        emit(DownloadProgress.Failed(t.message ?: t::class.java.simpleName))
                        return@flow
                    }
                }
                markInstalled(voiceId)
                emit(DownloadProgress.Done)
            }
            is EngineType.Kokoro -> {
                // Kokoro speakers all share one ~168MB multi-speaker model
                // (model.onnx + tokens.txt + voices.bin). The first
                // Kokoro pick downloads it; every subsequent one just flips
                // the active speaker id with no additional payload.
                val sharedDir = kokoroSharedDir()
                val onnxFile = File(sharedDir, "model.onnx")
                val tokensFile = File(sharedDir, "tokens.txt")
                val voicesFile = File(sharedDir, "voices.bin")
                if (!onnxFile.exists() || !voicesFile.exists() || !tokensFile.exists()) {
                    sharedDir.mkdirs()
                    try {
                        // Kokoro v1_1 fp32 sizes (vs voices-v1's int8: 114M / 53M).
                        // Total ~379MB on first install — bigger than int8 but
                        // produces clean speech (no quantization fuzz).
                        downloadFile(
                            url = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/kokoro-model.onnx",
                            target = onnxFile,
                            knownTotalBytes = 325_631_784L,
                        ) { read, _ -> emit(DownloadProgress.Downloading(read, 379_423_615L)) }
                        downloadFile(
                            url = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/kokoro-voices.bin",
                            target = voicesFile,
                            knownTotalBytes = 53_790_720L,
                        ) { read, _ ->
                            // Continue progress where the model left off so the bar keeps moving.
                            emit(DownloadProgress.Downloading(325_631_784L + read, 379_423_615L))
                        }
                        downloadFile(
                            url = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/kokoro-tokens.txt",
                            target = tokensFile,
                            knownTotalBytes = 0L,
                        ) { _, _ -> }
                    } catch (ce: CancellationException) {
                        // Caller cancelled (collector dropped, ViewModel
                        // cancelled the job). Re-throw so structured
                        // concurrency sees the cancel — emitting Failed
                        // here would race with the cancel and surface a
                        // phantom error in the UI. Don't wipe the shared
                        // dir: any sibling file that finished before the
                        // cancel (e.g. the 325 MB model.onnx when the user
                        // cancelled mid voices.bin) survives the outer
                        // `if (!exists())` checks above on retry, so only
                        // the still-incomplete target is re-fetched. The
                        // mid-flight file itself is rewritten from byte 0
                        // — `downloadFile()` doesn't do HTTP Range — but
                        // the completed sibling is the big saving.
                        throw ce
                    } catch (t: Throwable) {
                        // Per #28: wipe shared dir on real failure to keep retries deterministic.
                        // Trade-off: re-download cost (≤325 MB) vs. avoiding a corrupted partial onnx.
                        // Cancel path keeps partials (#20/#26) — user expectation.
                        sharedDir.deleteRecursively()
                        emit(DownloadProgress.Failed(t.message ?: t::class.java.simpleName))
                        return@flow
                    }
                }
                markInstalled(voiceId)
                emit(DownloadProgress.Done)
            }
            EngineType.Piper -> {
                val piper = entry.piper
                if (piper == null) {
                    emit(DownloadProgress.Failed("Piper entry $voiceId has no URLs"))
                    return@flow
                }
                val voiceDir = voiceDirFor(voiceId).also { it.mkdirs() }
                try {
                    downloadFile(
                        url = piper.onnxUrl,
                        target = File(voiceDir, "model.onnx"),
                        knownTotalBytes = entry.sizeBytes,
                    ) { bytesRead, total -> emit(DownloadProgress.Downloading(bytesRead, total)) }
                    downloadFile(
                        url = piper.tokensUrl,
                        target = File(voiceDir, "tokens.txt"),
                        knownTotalBytes = 0L,
                    ) { _, _ -> /* tokens file is small (~1KB) — no per-byte tick */ }
                } catch (ce: CancellationException) {
                    // User-driven cancel. Re-throw to honour structured
                    // concurrency — emitting Failed would surface a
                    // phantom error toast for what was a deliberate
                    // cancel. Skipping the deleteRecursively here is
                    // a no-op net of bytes (downloadFile rewrites the
                    // target from byte 0 on retry — no HTTP Range), but
                    // it keeps the cancel path side-effect-free, which
                    // matches the Kokoro branch's shape.
                    throw ce
                } catch (t: Throwable) {
                    voiceDir.deleteRecursively()
                    emit(DownloadProgress.Failed(t.message ?: t::class.java.simpleName))
                    return@flow
                }
                markInstalled(voiceId)
                emit(DownloadProgress.Done)
            }
        }
    }.flowOn(Dispatchers.IO)

    /** Remove a voice's files (Piper) and unmark it installed. If the
     *  active voice is being deleted, the active selection is cleared. */
    suspend fun delete(voiceId: String) {
        withContext(Dispatchers.IO) {
            voiceDirFor(voiceId).deleteRecursively()
        }
        store.edit { prefs ->
            val ids = prefs[VoiceKeys.INSTALLED_IDS].orEmpty().toMutableSet()
            ids.remove(voiceId)
            prefs[VoiceKeys.INSTALLED_IDS] = ids
            if (prefs[VoiceKeys.ACTIVE_ID] == voiceId) prefs.remove(VoiceKeys.ACTIVE_ID)
        }
    }

    /** Persist [voiceId] as the user's active voice. Does not validate
     *  installation — the picker UI is responsible for downloading first.
     *
     *  Issue #547 — also flips [VoiceKeys.PICKER_DISMISSED] true. The
     *  user has explicitly chosen a voice, so the onboarding gate
     *  should not re-prompt on cold launch even if the voice is later
     *  deleted. Done in the same `edit` block so both writes commit
     *  atomically. */
    suspend fun setActive(voiceId: String) {
        store.edit { prefs ->
            prefs[VoiceKeys.ACTIVE_ID] = voiceId
            prefs[VoiceKeys.PICKER_DISMISSED] = true
        }
    }

    /**
     * Issue #676 — best-effort first-launch seed for a System TTS
     * voice. If the user has never picked a voice AND the OS exposes
     * at least one System TTS voice via [SystemTtsVoiceProvider], pick
     * the first roster entry as the active voice.
     *
     * "First entry" lands on the highest-priority English locale
     * (en-US, en-GB, en-AU, etc.) per the roster's pre-sort, with
     * offline voices preferred over network ones — exactly what a
     * casual or sight-impaired user expects on first launch: an
     * English voice that works without Wi-Fi.
     *
     * Idempotent: re-running this after the user has chosen any other
     * voice is a no-op (we don't override user intent).
     *
     * NOT auto-invoked here — the caller (the app's first-launch
     * onboarding path) decides when to run this. Typically that's
     * during the Voice Picker Gate's "show me a voice already" path,
     * or as part of the early-init bootstrap when no active voice is
     * set.
     */
    suspend fun seedSystemTtsDefaultIfUnset() {
        val prefs = store.data.first()
        val existingActive = prefs[VoiceKeys.ACTIVE_ID]
        if (!existingActive.isNullOrBlank()) return
        // Snapshot the roster — onStart triggers an enumeration when
        // it's empty, so by the time first() returns we either have
        // voices or the OS has none to surface (in which case we
        // can't seed anyway).
        val roster = systemTtsVoiceProvider.voices.first()
        if (roster.isEmpty()) return
        val firstEntry = VoiceCatalog.systemTtsEntriesFromRoster(roster).firstOrNull() ?: return
        store.edit { p ->
            // Defensive: another setActive call may have raced this
            // suspend chain. Re-check inside the edit block — if a
            // value is now present, bail without overwriting.
            if (!p[VoiceKeys.ACTIVE_ID].isNullOrBlank()) return@edit
            p[VoiceKeys.ACTIVE_ID] = firstEntry.id
            // Don't auto-dismiss the picker — even though we picked a
            // voice for the user, we still want to show the Voice
            // Picker Gate on first launch so they can pick something
            // else if they prefer. The gate's dismiss is gated by the
            // user's explicit choice, not by our seed.
        }
    }

    /**
     * Filesystem path for a voice's installed payload. Public so the
     * playback layer can hand the .onnx + tokens.json to VoiceEngine
     * when loading a Piper model. For Kokoro entries the directory
     * isn't used (returns the path anyway — never created).
     */
    fun voiceDirFor(voiceId: String): File = File(File(context.filesDir, "voices"), voiceId)

    /** Shared Kokoro multi-speaker model dir (one install per device, used by all 53 speakers). */
    fun kokoroSharedDir(): File = File(context.filesDir, "voices/_kokoro_shared")

    /** Issue #119 — shared Kitten multi-speaker model dir (one install
     *  per device, used by all 8 Kitten speakers). Public for the same
     *  reason as [kokoroSharedDir] — playback paths need the absolute
     *  paths to hand to `KittenEngine.loadModel`. */
    fun kittenSharedDir(): File = File(context.filesDir, "voices/_kitten_shared")

    // ----- internals -----

    private suspend fun markInstalled(voiceId: String) {
        store.edit { prefs ->
            val ids = prefs[VoiceKeys.INSTALLED_IDS].orEmpty().toMutableSet()
            ids.add(voiceId)
            prefs[VoiceKeys.INSTALLED_IDS] = ids
        }
    }

    private suspend inline fun downloadFile(
        url: String,
        target: File,
        knownTotalBytes: Long,
        crossinline onProgress: suspend (bytesRead: Long, totalBytes: Long) -> Unit,
    ) {
        val request = Request.Builder().url(url).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} fetching $url")
            }
            val body = response.body ?: throw IOException("Empty body for $url")
            val totalBytes = body.contentLength().takeIf { it > 0 } ?: knownTotalBytes
            target.sink().buffer().use { sink ->
                body.source().use { source ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = source.read(buf)
                        if (n == -1) break
                        sink.write(buf, 0, n)
                        read += n
                        onProgress(read, totalBytes)
                    }
                    sink.flush()
                }
            }
        }
    }

    private fun CatalogEntry.toUiVoiceInfo(installed: Boolean): UiVoiceInfo = UiVoiceInfo(
        id = id,
        displayName = displayName,
        language = language,
        sizeBytes = sizeBytes,
        isInstalled = installed,
        qualityLevel = qualityLevel,
        engineType = engineType,
        gender = gender,
    )
}
