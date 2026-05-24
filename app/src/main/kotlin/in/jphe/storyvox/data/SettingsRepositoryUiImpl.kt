package `in`.jphe.storyvox.data

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.data.auth.SessionHydrator
import `in`.jphe.storyvox.data.repository.AuthRepository
import `in`.jphe.storyvox.data.repository.playback.AzureFallbackConfig
import `in`.jphe.storyvox.data.repository.playback.AzureFallbackState
import `in`.jphe.storyvox.data.repository.playback.ParallelSynthConfig
import `in`.jphe.storyvox.data.repository.playback.ParallelSynthState
import `in`.jphe.storyvox.data.repository.playback.PlaybackBufferConfig
import `in`.jphe.storyvox.data.repository.playback.PlaybackModeConfig
import `in`.jphe.storyvox.data.repository.playback.PlaybackResumePolicyConfig
import `in`.jphe.storyvox.data.repository.playback.VoiceTuningConfig
import `in`.jphe.storyvox.data.repository.pronunciation.PronunciationDict
import `in`.jphe.storyvox.data.repository.pronunciation.PronunciationDictRepository
import `in`.jphe.storyvox.data.repository.pronunciation.PronunciationEntry
import `in`.jphe.storyvox.data.repository.pronunciation.decodePronunciationDictJson
import `in`.jphe.storyvox.data.repository.pronunciation.encodePronunciationDictJson
import `in`.jphe.storyvox.feature.api.BUFFER_DEFAULT_CHUNKS
import `in`.jphe.storyvox.feature.api.BUFFER_MAX_CHUNKS
import `in`.jphe.storyvox.feature.api.BUFFER_MIN_CHUNKS
import `in`.jphe.storyvox.feature.api.PUNCTUATION_PAUSE_DEFAULT_MULTIPLIER
import `in`.jphe.storyvox.feature.api.PUNCTUATION_PAUSE_LONG_MULTIPLIER
import `in`.jphe.storyvox.feature.api.PUNCTUATION_PAUSE_MAX_MULTIPLIER
import `in`.jphe.storyvox.feature.api.PUNCTUATION_PAUSE_MIN_MULTIPLIER
import `in`.jphe.storyvox.feature.api.PUNCTUATION_PAUSE_NORMAL_MULTIPLIER
import `in`.jphe.storyvox.feature.api.PUNCTUATION_PAUSE_OFF_MULTIPLIER
import `in`.jphe.storyvox.feature.api.AzureProbeResult
import `in`.jphe.storyvox.feature.api.CoverStyle
import `in`.jphe.storyvox.feature.api.UiBrassPulseLevel
import `in`.jphe.storyvox.feature.api.UiNetworkPatience
import `in`.jphe.storyvox.feature.api.UiParticleIntensity
import `in`.jphe.storyvox.feature.api.UiSkeletonStyle
import `in`.jphe.storyvox.data.repository.net.NetworkPatience
import `in`.jphe.storyvox.data.repository.net.NetworkPatienceConfig
import `in`.jphe.storyvox.data.repository.playback.AutoBrowserConfig
import `in`.jphe.storyvox.data.repository.playback.PrerenderChapterCountConfig
import `in`.jphe.storyvox.data.repository.playback.SleepTimerExtendConfig
import `in`.jphe.storyvox.feature.api.PalaceProbeResult
import `in`.jphe.storyvox.feature.api.ReadingDirection
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import `in`.jphe.storyvox.feature.api.SpeakChapterMode
import `in`.jphe.storyvox.feature.api.ThemeOverride
import `in`.jphe.storyvox.feature.api.UiAiSettings
import `in`.jphe.storyvox.feature.api.UiAzureConfig
import `in`.jphe.storyvox.feature.api.UiChatGrounding
import `in`.jphe.storyvox.feature.api.UiGitHubAuthState
import `in`.jphe.storyvox.feature.api.UiLlmProvider
import `in`.jphe.storyvox.feature.api.UiPalaceConfig
import `in`.jphe.storyvox.source.azure.AzureCredentials
import `in`.jphe.storyvox.source.azure.AzureError
import `in`.jphe.storyvox.source.azure.AzureRegion
import `in`.jphe.storyvox.source.azure.AzureSpeechClient
import `in`.jphe.storyvox.source.azure.AzureVoiceRoster
import `in`.jphe.storyvox.feature.api.CacheQuotaOptions
import `in`.jphe.storyvox.feature.api.UiSettings
import `in`.jphe.storyvox.feature.api.UiSigil
import `in`.jphe.storyvox.playback.cache.CacheStatsRepository
import `in`.jphe.storyvox.playback.cache.PcmCache
import `in`.jphe.storyvox.playback.cache.PcmCacheConfig
import `in`.jphe.storyvox.source.github.auth.GitHubAuthRepository
import `in`.jphe.storyvox.source.github.auth.GitHubScopePreferences
import `in`.jphe.storyvox.source.github.auth.GitHubSession
import `in`.jphe.storyvox.llm.LlmConfig
import `in`.jphe.storyvox.llm.LlmConfigProvider
import `in`.jphe.storyvox.llm.auth.AnthropicTeamsAuthRepository
import `in`.jphe.storyvox.llm.auth.GoogleOAuthTokenSource
import `in`.jphe.storyvox.llm.auth.GoogleServiceAccount
import `in`.jphe.storyvox.llm.auth.TeamsSession
import `in`.jphe.storyvox.llm.LlmCredentialsStore
import `in`.jphe.storyvox.llm.ProviderId
import `in`.jphe.storyvox.sigil.Sigil
import `in`.jphe.storyvox.source.mempalace.net.PalaceDaemonApi
import `in`.jphe.storyvox.source.mempalace.net.PalaceDaemonResult
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

internal val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "storyvox_settings",
    produceMigrations = { _ ->
        listOf(
            PunctuationPauseEnumToMultiplierMigration,
            FirstTimeDefaultVoiceMigration,
            // Issue #417 — seed `pref_source_radio_enabled` from the
            // legacy `pref_source_kvmr_enabled` value on the first run
            // of v0.5.32. Runs BEFORE SourcePluginsMapMigration so the
            // JSON map seed sees the renamed key. Idempotent.
            KvmrEnabledToRadioEnabledMigration,
            // Plugin-seam Phase 1 (#384) — seed the per-plugin JSON map
            // from the legacy per-source keys on first read. Idempotent
            // once the JSON key exists.
            SourcePluginsMapMigration,
        )
    },
)

/**
 * Issue #109 — one-shot migration from the pre-#109 enum-string key
 * `pref_punctuation_pause` ("Off"/"Normal"/"Long") to the continuous
 * Float key `pref_punctuation_pause_multiplier_v2`. Maps:
 *   Off    → 0×
 *   Normal → 1×
 *   Long   → 1.75×
 *
 * Runs once per process at first DataStore read. Idempotent — `shouldMigrate`
 * returns false once the new key is present, so a partial-migration crash
 * mid-run still completes safely on next launch. The old key is removed
 * after the new one is written.
 */
internal val PunctuationPauseEnumToMultiplierMigration: DataMigration<Preferences> =
    object : DataMigration<Preferences> {
        private val oldKey = stringPreferencesKey("pref_punctuation_pause")
        private val newKey = floatPreferencesKey("pref_punctuation_pause_multiplier_v2")

        override suspend fun shouldMigrate(currentData: Preferences): Boolean {
            // If new key is set, we're done. If old key isn't present either, also done
            // (fresh install — defaults take over). Otherwise we have an enum value to map.
            if (currentData[newKey] != null) return false
            return currentData[oldKey] != null
        }

        override suspend fun migrate(currentData: Preferences): Preferences {
            val mutable = currentData.toMutablePreferences()
            val mapped = when (currentData[oldKey]) {
                "Off" -> PUNCTUATION_PAUSE_OFF_MULTIPLIER
                "Long" -> PUNCTUATION_PAUSE_LONG_MULTIPLIER
                // "Normal" or any unrecognized legacy value falls through to the default.
                else -> PUNCTUATION_PAUSE_NORMAL_MULTIPLIER
            }
            mutable[newKey] = mapped
            mutable.remove(oldKey)
            return mutable.toPreferences()
        }

        override suspend fun cleanUp() = Unit
    }

/**
 * Issue #294 — seed `pref_default_voice_id` on a fresh install so the
 * "no voice activated yet" state doesn't leave defaultVoiceId null
 * until the user explicitly picks one (that pushes first-run friction
 * onto a picker tap).
 *
 * Updated 2026-05-13: the seed follows
 * `VoiceCatalog.featuredIds[0]` — currently `piper_lessac_en_US_low`,
 * the smallest of the three Lessac quality tiers the VoicePickerGate
 * presents. Picking the low tier as the implicit default minimizes
 * first-launch download size; users who want richer audio can pick
 * Medium or High in the picker before the gate dismisses.
 *
 * Runs once per process at first DataStore read. Idempotent —
 * `shouldMigrate` returns false the moment the key is present, so a
 * user who picks a different voice on first launch (or who upgraded
 * from a build that already has a stored voice) gets their choice
 * preserved verbatim.
 */
internal val FirstTimeDefaultVoiceMigration: DataMigration<Preferences> =
    object : DataMigration<Preferences> {
        private val voiceKey = stringPreferencesKey("pref_default_voice_id")

        override suspend fun shouldMigrate(currentData: Preferences): Boolean =
            currentData[voiceKey] == null

        override suspend fun migrate(currentData: Preferences): Preferences {
            val mutable = currentData.toMutablePreferences()
            mutable[voiceKey] = "piper_lessac_en_US_low"
            return mutable.toPreferences()
        }

        override suspend fun cleanUp() = Unit
    }

/**
 * Issue #417 — one-shot migration from `pref_source_kvmr_enabled` to
 * `pref_source_radio_enabled` on the first run of v0.5.32 (when
 * `:source-kvmr` was generalized to `:source-radio`).
 *
 * Existing users with `sourceKvmrEnabled = true` (the v0.5.20+ default
 * for KVMR) keep their radio backend visible — the toggle they flipped
 * (or didn't flip) for KVMR continues to govern the renamed source
 * without an explicit re-opt-in. A user who explicitly turned KVMR off
 * sees the new Radio backend OFF too; their preference travels.
 *
 * Order matters: this runs BEFORE [SourcePluginsMapMigration] so the
 * JSON-map seeder finds the renamed key and lands the right state for
 * the new `SourceIds.RADIO` entry there too. The legacy
 * `pref_source_kvmr_enabled` key is kept (not deleted) so the same
 * value can also seed `SourceIds.KVMR` in the JSON map — the alias
 * id in [`SourceIds.KVMR`] is still a routable backend during the
 * one-cycle transition.
 *
 * Idempotent: once `pref_source_radio_enabled` is present (or the
 * legacy key is absent on a fresh install), the migration is a no-op.
 */
internal val KvmrEnabledToRadioEnabledMigration: DataMigration<Preferences> =
    object : DataMigration<Preferences> {
        private val legacyKvmr = booleanPreferencesKey("pref_source_kvmr_enabled")
        private val newRadio = booleanPreferencesKey("pref_source_radio_enabled")

        override suspend fun shouldMigrate(currentData: Preferences): Boolean {
            // Nothing to seed if the user already has a value (or had
            // one seeded on a prior run).
            if (currentData[newRadio] != null) return false
            // Fresh install (no legacy key either) → the JSON-map
            // seeder will pick up the SourceIds.RADIO default directly;
            // no migration needed.
            return currentData[legacyKvmr] != null
        }

        override suspend fun migrate(currentData: Preferences): Preferences {
            val mutable = currentData.toMutablePreferences()
            val legacyValue = currentData[legacyKvmr] ?: true
            mutable[newRadio] = legacyValue
            // Keep legacy key intact for one cycle so the JSON-map
            // seeder + the SourceIds.KVMR alias keep their preference
            // history. Phase-4 of the plugin seam will delete it.
            return mutable.toPreferences()
        }

        override suspend fun cleanUp() = Unit
    }

/**
 * Plugin-seam Phase 1 (#384) — one-shot migration from the per-source
 * `pref_source_xxx_enabled` boolean keys to the JSON map under
 * `pref_source_plugins_enabled_v1`.
 *
 * Runs once when the JSON key is absent. Reads every existing
 * `SOURCE_*_ENABLED` boolean and emits the equivalent
 * `{"royalroad": true, "kvmr": true, …}` blob. The per-source
 * booleans are *kept* (not deleted) for Phase 1 — the existing
 * Settings screen + BrowseViewModel still read them, so deleting
 * would regress those surfaces. Phase 2 deletes them once the
 * registry-driven UI lands and the per-source `setSourceXxxEnabled`
 * overrides also write into the map (the impl below handles that
 * dual-write).
 *
 * Idempotent: once the JSON key exists, `shouldMigrate` returns false
 * even if the per-source values change later. Subsequent
 * `setSourceXxxEnabled` calls update both the legacy key and the map
 * via the dual-write in [SettingsRepositoryUiImpl].
 *
 * Defaults for a plugin that has no persisted per-source key (e.g.
 * a brand-new install that goes straight through here) come from the
 * `SOURCE_DEFAULTS` table below — matches the fall-through defaults
 * used in `UiSettings` assembly. Keeping the defaults in one place
 * means a fresh install and a Phase-1 migration land on the same
 * starting state.
 */
internal val SourcePluginsMapMigration: DataMigration<Preferences> =
    object : DataMigration<Preferences> {
        private val jsonKey = stringPreferencesKey("pref_source_plugins_enabled_v1")

        override suspend fun shouldMigrate(currentData: Preferences): Boolean =
            currentData[jsonKey] == null

        override suspend fun migrate(currentData: Preferences): Preferences {
            val mutable = currentData.toMutablePreferences()
            val map = LinkedHashMap<String, Boolean>()
            for ((id, legacy) in LegacySourceKeys.ALL) {
                map[id] = currentData[legacy.key] ?: legacy.defaultValue
            }
            mutable[jsonKey] = `in`.jphe.storyvox.data.source.plugin.encodeSourcePluginsEnabledJson(map)
            return mutable.toPreferences()
        }

        override suspend fun cleanUp() = Unit
    }

/**
 * Plugin-seam Phase 1 (#384) — single-source-of-truth table mapping
 * each registered plugin id to (legacy `SOURCE_*_ENABLED` key, default
 * value). Used by the [SourcePluginsMapMigration] above and by the
 * dual-write in [SettingsRepositoryUiImpl.setSourcePluginEnabled].
 *
 * When a backend migrates to a registry-only world (Phase 2), its
 * entry comes off this table and its row in `UiSettings` collapses.
 * Until then, every legacy `sourceXxxEnabled` field corresponds to
 * exactly one row here.
 */
internal object LegacySourceKeys {
    data class Spec(
        val key: androidx.datastore.preferences.core.Preferences.Key<Boolean>,
        val defaultValue: Boolean,
    )

    // Keep in sync with the per-source defaults inlined in
    // SettingsRepositoryUiImpl.settings (the `prefs[...] ?: <bool>`
    // expressions). Drift here is a fresh-install behavior bug.
    val ALL: Map<String, Spec> = linkedMapOf(
        `in`.jphe.storyvox.data.source.SourceIds.ROYAL_ROAD to
            Spec(booleanPreferencesKey("pref_source_royalroad_enabled"), defaultValue = true),
        // #436 — fresh-install discoverability: all 17 backends default
        // ON in the JSON map so the Browse chip strip lists every
        // backend. Per-source migration tables (`KvmrEnabledToRadioEnabled`,
        // legacy boolean keys carried forward from old installs) still
        // honor the user's previous explicit choices on upgrade; this
        // table is only consulted when the legacy boolean key was never
        // written (i.e. fresh install). Keep these defaults in lockstep
        // with each backend's `@SourcePlugin(defaultEnabled = …)` —
        // `SourcePluginContractTest` asserts the parity.
        `in`.jphe.storyvox.data.source.SourceIds.GITHUB to
            Spec(booleanPreferencesKey("pref_source_github_enabled"), defaultValue = true),
        `in`.jphe.storyvox.data.source.SourceIds.MEMPALACE to
            Spec(booleanPreferencesKey("pref_source_mempalace_enabled"), defaultValue = true),
        `in`.jphe.storyvox.data.source.SourceIds.RSS to
            Spec(booleanPreferencesKey("pref_source_rss_enabled"), defaultValue = true),
        `in`.jphe.storyvox.data.source.SourceIds.EPUB to
            Spec(booleanPreferencesKey("pref_source_epub_enabled"), defaultValue = true),
        `in`.jphe.storyvox.data.source.SourceIds.OUTLINE to
            Spec(booleanPreferencesKey("pref_source_outline_enabled"), defaultValue = true),
        `in`.jphe.storyvox.data.source.SourceIds.GUTENBERG to
            Spec(booleanPreferencesKey("pref_source_gutenberg_enabled"), defaultValue = true),
        `in`.jphe.storyvox.data.source.SourceIds.AO3 to
            Spec(booleanPreferencesKey("pref_source_ao3_enabled"), defaultValue = true),
        `in`.jphe.storyvox.data.source.SourceIds.STANDARD_EBOOKS to
            Spec(booleanPreferencesKey("pref_source_standard_ebooks_enabled"), defaultValue = true),
        `in`.jphe.storyvox.data.source.SourceIds.WIKIPEDIA to
            Spec(booleanPreferencesKey("pref_source_wikipedia_enabled"), defaultValue = true),
        `in`.jphe.storyvox.data.source.SourceIds.WIKISOURCE to
            Spec(booleanPreferencesKey("pref_source_wikisource_enabled"), defaultValue = true),
        // Issue #417 — :source-kvmr generalized to :source-radio. The
        // canonical entry is RADIO with its own pref_source_radio_enabled
        // key (seeded from the legacy pref_source_kvmr_enabled value
        // via KvmrEnabledToRadioEnabledMigration). The KVMR row is kept
        // as a one-cycle alias so the JSON map continues to surface a
        // value under the legacy id for any consumer still routing
        // through SourceIds.KVMR; the actual FictionSource binding for
        // both ids points at the same RadioSource instance.
        `in`.jphe.storyvox.data.source.SourceIds.RADIO to
            Spec(booleanPreferencesKey("pref_source_radio_enabled"), defaultValue = true),
        `in`.jphe.storyvox.data.source.SourceIds.KVMR to
            Spec(booleanPreferencesKey("pref_source_kvmr_enabled"), defaultValue = true),
        `in`.jphe.storyvox.data.source.SourceIds.NOTION_TECHEMPOWER to
            Spec(booleanPreferencesKey("pref_source_notion_techempower_enabled"), defaultValue = true),
        `in`.jphe.storyvox.data.source.SourceIds.NOTION_PAT to
            Spec(booleanPreferencesKey("pref_source_notion_pat_enabled"), defaultValue = false),
        // Legacy alias — one migration cycle so persisted enabled-state
        // for the old "notion" source survives.
        `in`.jphe.storyvox.data.source.SourceIds.NOTION to
            Spec(booleanPreferencesKey("pref_source_notion_enabled"), defaultValue = true),
        `in`.jphe.storyvox.data.source.SourceIds.HACKERNEWS to
            Spec(booleanPreferencesKey("pref_source_hackernews_enabled"), defaultValue = true),
        // #378 — arXiv. Fresh-install discoverability (#436) flipped
        // this from off → on; the user can disable via Settings → Plugins.
        `in`.jphe.storyvox.data.source.SourceIds.ARXIV to
            Spec(booleanPreferencesKey("pref_source_arxiv_enabled"), defaultValue = true),
        // #380 — PLOS open-access peer-reviewed science. Fresh-install
        // discoverability (#436) flipped this from off → on.
        `in`.jphe.storyvox.data.source.SourceIds.PLOS to
            Spec(booleanPreferencesKey("pref_source_plos_enabled"), defaultValue = true),
        // #403 — Discord backend. Fresh-install discoverability (#436)
        // flipped this on; chip is visible but the backend stays inert
        // until the user enters a bot token in Settings.
        `in`.jphe.storyvox.data.source.SourceIds.DISCORD to
            Spec(booleanPreferencesKey("pref_source_discord_enabled"), defaultValue = true),
        // #462 — Telegram backend. Same posture as Discord: chip is
        // visible on fresh install for discoverability, but the
        // backend stays inert until the user enters a bot token via
        // @BotFather in Settings.
        `in`.jphe.storyvox.data.source.SourceIds.TELEGRAM to
            Spec(booleanPreferencesKey("pref_source_telegram_enabled"), defaultValue = true),
    )
}

private object Keys {
    val DEFAULT_SPEED = floatPreferencesKey("pref_default_speed")
    val DEFAULT_PITCH = floatPreferencesKey("pref_default_pitch")
    val DEFAULT_VOICE_ID = stringPreferencesKey("pref_default_voice_id")

    // Issue #195 — per-voice speed/pitch override maps. Stored as a
    // simple `voiceId=value;voiceId=value` string to avoid pulling in
    // kotlinx-serialization for one tiny map (and to keep DataStore's
    // type-safe key API). Empty when no overrides are present, which
    // is the migration default for pre-#195 installs.
    val VOICE_SPEED_OVERRIDES = stringPreferencesKey("pref_voice_speed_overrides")
    val VOICE_PITCH_OVERRIDES = stringPreferencesKey("pref_voice_pitch_overrides")
    /** Issue #197 — per-voice lexicon override map. Same flat
     *  `voiceId=path;voiceId=path` codec as the speed/pitch maps.
     *  Empty for fresh installs; engine falls back to its built-in
     *  lexicon. */
    val VOICE_LEXICON_OVERRIDES = stringPreferencesKey("pref_voice_lexicon_overrides")
    /** Issue #198 — per-voice Kokoro phonemizer language override map.
     *  `voiceId=lang;voiceId=lang` (e.g. `kokoro_af_bella=es`). Only
     *  honored by KokoroEngine; Piper entries are inert at the engine
     *  layer but persisted so the UI surface stays consistent. */
    val VOICE_PHONEMIZER_LANG_OVERRIDES = stringPreferencesKey("pref_voice_phonemizer_lang_overrides")
    val THEME_OVERRIDE = stringPreferencesKey("pref_theme_override")
    val DOWNLOAD_WIFI_ONLY = booleanPreferencesKey("pref_download_wifi_only")
    val POLL_INTERVAL_HOURS = intPreferencesKey("pref_poll_interval_hours")
    val SIGNED_IN = booleanPreferencesKey("pref_signed_in")
    /** Issue #109 — continuous inter-sentence pause multiplier (Float).
     *  Replaces the pre-#109 enum-string key `pref_punctuation_pause`; the
     *  one-shot [PunctuationPauseEnumToMultiplierMigration] in this file
     *  maps existing installs forward. Default = 1× preserves the
     *  audiobook-tuned baseline on fresh installs. */
    val PUNCTUATION_PAUSE_MULTIPLIER = floatPreferencesKey("pref_punctuation_pause_multiplier_v2")
    /** Issue #193 — Sonic pitch-interpolation quality toggle. true = quality=1,
     *  false = quality=0 (Sonic's upstream default). Defaults to true on
     *  fresh installs. */
    val PITCH_INTERP_HIGH_QUALITY = booleanPreferencesKey("pref_pitch_interp_high_quality")
    /** Pre-synth queue depth (sentence-chunks). Issue #84 — the slider is an
     *  exploratory probe for where Android's LMK kills the app on slow
     *  devices, so the persisted value is intentionally NOT clamped at a
     *  conservative ceiling; only the absolute mechanical bounds apply. */
    val PLAYBACK_BUFFER_CHUNKS = intPreferencesKey("pref_playback_buffer_chunks_v1")
    /** Issue #98 — Mode A. Default true preserves the v0.4.30 spinner-on-warmup
     *  behavior; OFF skips the warmup-wait UI and accepts silence at chapter
     *  start. _v1 suffix matches PLAYBACK_BUFFER_CHUNKS' versioning so we can
     *  rev defaults later without colliding with persisted v1 values. */
    val WARMUP_WAIT = booleanPreferencesKey("pref_warmup_wait_v1")
    /** Issue #98 — Mode B. Default true preserves PR #77's pause-buffer-resume
     *  behavior on mid-stream underrun; OFF lets the consumer drain through
     *  underruns without raising the "Buffering…" UI. */
    val CATCHUP_PAUSE = booleanPreferencesKey("pref_catchup_pause_v1")
    /** Issue #98 / PR-F (#86) — Mode C. Default false. When true, the
     *  PCM cache pre-render scheduler expands library-add scheduling
     *  from "chapters 1-3" to "all chapters in the fiction", and a flow
     *  collector in `:app` ([PrerenderModeWatcher]) catches the false→true
     *  flip and enqueues remaining chapters across every library fiction.
     *  PR-F adds the persistence; PR-G surfaces the Settings toggle. */
    val FULL_PRERENDER = booleanPreferencesKey("pref_full_prerender_v1")
    /** Issue #85 — Voice-Determinism preset. Default true = Steady, which
     *  preserves the pre-#85 calmed VITS defaults (0.35 / 0.667). false =
     *  Expressive (sherpa-onnx upstream Piper defaults 0.667 / 0.8 — more
     *  variable prosody but less reproducible). _v1 suffix matches the
     *  versioning convention used by PLAYBACK_BUFFER_CHUNKS / WARMUP_WAIT
     *  so we can rev defaults later without colliding with persisted v1
     *  values. */
    val VOICE_STEADY = booleanPreferencesKey("pref_voice_steady_v1")

    /** Issue #589 — global animation-speed master multiplier (Float).
     *  Per-device, NOT synced. Default 1.0× preserves existing behavior
     *  on fresh installs. */
    val ANIMATION_SPEED_SCALE = floatPreferencesKey("pref_animation_speed_scale_v1")

    /** Issue #593 — skip distance in seconds (Int). Per-device, NOT
     *  synced. Default 30s matches Spotify / Apple Music / Pocket Casts. */
    val SKIP_DISTANCE_SEC = intPreferencesKey("pref_skip_distance_sec_v1")

    /** Issue #594 — rewind-to-start threshold for SkipPrevious, in
     *  seconds (Int). 0 = disable rewind-to-start (always jump to
     *  previous chapter). Per-device, NOT synced. Default 3s matches
     *  every major player. */
    val REWIND_TO_START_THRESHOLD_SEC = intPreferencesKey("pref_rewind_to_start_threshold_sec_v1")

    /** Issue #595 — sleep-timer shake-to-extend duration in minutes
     *  (Int). Per-device, NOT synced. Default 15 matches the legacy
     *  `SHAKE_EXTEND_MINUTES` constant in StoryvoxPlaybackService. */
    val SLEEP_SHAKE_EXTEND_MINUTES =
        intPreferencesKey("pref_sleep_shake_extend_min_v1")

    /** Issue #596 — PCM-cache pre-render window size in chapters
     *  (Int). Per-device, NOT synced. Default 5 matches the legacy
     *  `DEFAULT_PRERENDER_CHAPTERS` constant in PrerenderTriggers
     *  (bumped 3 → 5 in #558). */
    val PRERENDER_CHAPTER_COUNT =
        intPreferencesKey("pref_prerender_chapter_count_v1")

    /** Issue #590 — particle / confetti intensity preset, stored as
     *  the UiParticleIntensity enum's `name` ("None"/"Subtle"/"Lush").
     *  Per-device, NOT synced. Default Subtle preserves the current
     *  6-ember overlay on a fresh install. */
    val PARTICLE_INTENSITY = stringPreferencesKey("pref_particle_intensity_v1")

    /** Issue #591 — skeleton-shimmer style preset, stored as the
     *  UiSkeletonStyle enum's `name` ("Off"/"Pulse"/"Sigil"). Per-
     *  device, NOT synced. Default Sigil preserves the v0.5.66
     *  MagicSkeletonTile look on a fresh install. */
    val SKELETON_STYLE = stringPreferencesKey("pref_skeleton_style_v1")

    /** Issue #592 — brass alpha-pulse intensity preset, stored as
     *  the UiBrassPulseLevel enum's `name` ("Subtle"/"Standard"/
     *  "Bold"). Per-device, NOT synced. Default Standard preserves
     *  the current 0.55..1.0 pulse band. */
    val BRASS_PULSE_LEVEL = stringPreferencesKey("pref_brass_pulse_v1")

    /** Issue #597 — network-patience preset, stored as the
     *  UiNetworkPatience enum's `name` ("Aggressive"/"Default"/
     *  "Patient"). Per-device, NOT synced. Default `Default`
     *  preserves the 10 s baseline timeout budget. */
    val NETWORK_PATIENCE = stringPreferencesKey("pref_network_patience_v1")

    /** Issue #598 — Android Auto bucket size in items (Int). Per-
     *  device, NOT synced. Default 6 matches the HMI guideline + the
     *  legacy `MAX_PER_CATEGORY` constant in
     *  StoryvoxAutoBrowserService. */
    val AUTO_ITEMS_PER_CATEGORY =
        intPreferencesKey("pref_auto_items_per_category_v1")

    // ── AI / LLM (issue #81) ────────────────────────────────────────
    /** Active provider — stored as the [ProviderId] enum's name.
     *  Empty/missing = AI disabled. */
    val AI_PROVIDER = stringPreferencesKey("pref_ai_provider")
    val AI_CLAUDE_MODEL = stringPreferencesKey("pref_ai_claude_model")
    val AI_OPENAI_MODEL = stringPreferencesKey("pref_ai_openai_model")
    val AI_OLLAMA_BASE_URL = stringPreferencesKey("pref_ai_ollama_base_url")
    val AI_OLLAMA_MODEL = stringPreferencesKey("pref_ai_ollama_model")
    val AI_VERTEX_MODEL = stringPreferencesKey("pref_ai_vertex_model")
    /** Azure Foundry — endpoint URL the user pasted (e.g.
     *  `https://my-resource.openai.azure.com`). Empty/missing = not
     *  configured. */
    val AI_FOUNDRY_ENDPOINT = stringPreferencesKey("pref_ai_foundry_endpoint")
    /** Azure Foundry — deployment name (deployed mode) or catalog
     *  model id (serverless mode). */
    val AI_FOUNDRY_DEPLOYMENT = stringPreferencesKey("pref_ai_foundry_deployment")
    /** Azure Foundry — true selects the serverless URL shape;
     *  default (false) selects per-model deployed URLs. */
    val AI_FOUNDRY_SERVERLESS = booleanPreferencesKey("pref_ai_foundry_serverless")
    val AI_PRIVACY_ACK = booleanPreferencesKey("pref_ai_privacy_ack")
    val AI_SEND_CHAPTER_TEXT = booleanPreferencesKey("pref_ai_send_chapter_text")
    val AI_BEDROCK_REGION = stringPreferencesKey("pref_ai_bedrock_region")
    val AI_BEDROCK_MODEL = stringPreferencesKey("pref_ai_bedrock_model")

    /** Issue #203 — "Enable private repos" toggle. Default false keeps
     *  the least-privilege public-only baseline; ON makes the next
     *  Device Flow request the `repo` scope. _v1 suffix matches the
     *  versioning convention used by other v1-tagged keys here. */
    val GITHUB_PRIVATE_REPOS_ENABLED = booleanPreferencesKey("pref_github_private_repos_enabled_v1")

    // ── Per-source on/off (issue #221) ─────────────────────────────
    val SOURCE_ROYALROAD_ENABLED = booleanPreferencesKey("pref_source_royalroad_enabled")
    val SOURCE_GITHUB_ENABLED = booleanPreferencesKey("pref_source_github_enabled")
    val SOURCE_MEMPALACE_ENABLED = booleanPreferencesKey("pref_source_mempalace_enabled")
    /** Issue #236 — RSS backend on/off. */
    val SOURCE_RSS_ENABLED = booleanPreferencesKey("pref_source_rss_enabled")
    /** Issue #235 — local EPUB backend on/off. */
    val SOURCE_EPUB_ENABLED = booleanPreferencesKey("pref_source_epub_enabled")
    /** Issue #245 — Outline backend on/off. */
    val SOURCE_OUTLINE_ENABLED = booleanPreferencesKey("pref_source_outline_enabled")
    /** Issue #237 — Project Gutenberg backend on/off. Default true
     *  for fresh installs; an explicit prefs entry overrides. */
    val SOURCE_GUTENBERG_ENABLED = booleanPreferencesKey("pref_source_gutenberg_enabled")
    val SOURCE_AO3_ENABLED = booleanPreferencesKey("pref_source_ao3_enabled")
    /** Issue #375 — Standard Ebooks backend on/off. Default false for
     *  fresh installs; opt-in surface like Outline/Epub. */
    val SOURCE_STANDARD_EBOOKS_ENABLED = booleanPreferencesKey("pref_source_standard_ebooks_enabled")
    /** Issue #377 — Wikipedia backend on/off. Default false for fresh
     *  installs; first non-fiction-shaped source is opt-in. */
    val SOURCE_WIKIPEDIA_ENABLED = booleanPreferencesKey("pref_source_wikipedia_enabled")
    /** Issue #376 — Wikisource backend on/off. Default false for fresh
     *  installs; opt-in surface like other text backends. Wikisource is
     *  the Wikimedia transcribed-public-domain-texts project (CC0/PD
     *  posture, no third-party-ToS surface). */
    val SOURCE_WIKISOURCE_ENABLED = booleanPreferencesKey("pref_source_wikisource_enabled")
    /** Issue #417 — generalized :source-kvmr → :source-radio. Default
     *  TRUE on fresh installs (the audio-stream pipeline + the curated
     *  KVMR/KQED/KCSB/KXPR/SomaFM seed list should be discoverable
     *  without an opt-in step). Seeded from the legacy
     *  pref_source_kvmr_enabled value for upgrading users via
     *  KvmrEnabledToRadioEnabledMigration. */
    val SOURCE_RADIO_ENABLED = booleanPreferencesKey("pref_source_radio_enabled")

    /** Issue #374 — legacy KVMR backend on/off, kept as a one-cycle
     *  alias of [SOURCE_RADIO_ENABLED] (#417). Default TRUE on fresh
     *  installs. A follow-up release will drop this key once one full
     *  release cycle has elapsed with the renamed entry live. */
    val SOURCE_KVMR_ENABLED = booleanPreferencesKey("pref_source_kvmr_enabled")
    /** Issue #233 — Notion backend on/off. Default TRUE on fresh
     *  installs per #390 — the bundled techempower.org database id
     *  needs the toggle ON to be visible in Browse. Source returns
     *  AuthRequired on every call until the user pastes an integration
     *  token via Settings → Library & Sync → Notion. */
    val SOURCE_NOTION_ENABLED = booleanPreferencesKey("pref_source_notion_enabled")
    /** Issue #379 — Hacker News backend on/off. Default false: this
     *  is a tech-news / discussion backend, not a fiction source in
     *  the classic sense, so it should be an explicit opt-in. */
    val SOURCE_HACKERNEWS_ENABLED = booleanPreferencesKey("pref_source_hackernews_enabled")
    /** Issue #378 — arXiv backend on/off. Default false for fresh
     *  installs; second non-fiction-shaped source after Wikipedia
     *  follows the same opt-in posture. */
    val SOURCE_ARXIV_ENABLED = booleanPreferencesKey("pref_source_arxiv_enabled")
    /** Issue #380 — PLOS open-access peer-reviewed science backend
     *  on/off. Default false for fresh installs; opt-in surface like
     *  Wikipedia. */
    val SOURCE_PLOS_ENABLED = booleanPreferencesKey("pref_source_plos_enabled")
    /** Issue #462 — Telegram backend on/off. Default true for
     *  fresh-install discoverability (#436); the backend stays
     *  inert until the user enters a bot token via @BotFather
     *  in Settings. */
    val SOURCE_TELEGRAM_ENABLED = booleanPreferencesKey("pref_source_telegram_enabled")

    /** Issue #403 — Discord backend on/off. Default false for fresh
     *  installs — bot-token onboarding is high-friction and Discord
     *  is a private workspace, not a public catalog. */
    val SOURCE_DISCORD_ENABLED = booleanPreferencesKey("pref_source_discord_enabled")

    // ── Plugin-seam Phase 1 (#384) ────────────────────────────────
    /**
     * JSON-serialized `Map<String, Boolean>` keyed by `@SourcePlugin`
     * id. Replaces the per-source `SOURCE_*_ENABLED` keys above as
     * backends migrate to the registry-driven shape. The two views
     * coexist during the phased rollout: setting the legacy key also
     * writes the corresponding map entry, and vice-versa, so existing
     * UI observers keep working.
     *
     * The _v1 suffix lets us rev the schema later (e.g. richer
     * per-plugin state than a bare boolean) without a destructive
     * migration; an unparseable v1 blob falls back to a
     * defaults-from-registry map and the per-plugin migration shim
     * (SourcePluginsMapMigration) re-seeds from the legacy keys.
     */
    val SOURCE_PLUGINS_ENABLED_JSON = stringPreferencesKey("pref_source_plugins_enabled_v1")

    /**
     * Plugin-seam Phase 4 (#501) — JSON-encoded
     * `Map<voiceFamilyId, Boolean>` for the Plugin Manager's Voice
     * bundles section. Twin of [SOURCE_PLUGINS_ENABLED_JSON]; missing
     * ids fall back to each family's `defaultEnabled` in
     * `VoiceFamilyRegistry`. The `_v1` suffix lets us rev the schema
     * later (e.g. per-family richer state than a bare boolean) without
     * a destructive migration.
     */
    val VOICE_FAMILIES_ENABLED_JSON = stringPreferencesKey("pref_voice_families_enabled_v1")

    /**
     * v0.5.76 — JSON-encoded `Set<String>` of source plugin ids that
     * the user has "starred" from the Browse carousel long-press
     * sheet. Twin of [SOURCE_PLUGINS_ENABLED_JSON], but stores a Set
     * (favorites have no per-source default — empty set is correct
     * for every fresh install). Codec lives in
     * [`in.jphe.storyvox.data.source.plugin.encodeSourceFavoritesJson`].
     * `_v1` suffix lets us rev to a richer shape later (e.g. ordered
     * list, per-source position) without a destructive migration.
     */
    val SOURCE_FAVORITES_JSON = stringPreferencesKey("pref_source_favorites_v1")

    // ── Sleep timer shake-to-extend (issue #150) ───────────────────
    val SLEEP_SHAKE_TO_EXTEND_ENABLED = booleanPreferencesKey("pref_sleep_shake_to_extend_enabled")

    // ── Debug overlay (Vesper, v0.4.97) ────────────────────────────
    /** Master switch for the on-Reader debug overlay. Default false;
     *  power users opt in from Settings → Developer. The dedicated
     *  /debug screen is reachable regardless of this toggle. */
    val SHOW_DEBUG_OVERLAY = booleanPreferencesKey("pref_show_debug_overlay")

    // ── Azure offline-fallback (issue #185, PR-6) ──────────────────
    val AZURE_FALLBACK_ENABLED = booleanPreferencesKey("pref_azure_fallback_enabled")
    val AZURE_FALLBACK_VOICE_ID = stringPreferencesKey("pref_azure_fallback_voice_id")

    // ── Tier 3 parallel synth (issue #88) ──────────────────────────
    /** Pre-slider Boolean key (kept for read-side migration). When the
     *  Int key below is unset and this is true, treat as count = 2. */
    val EXPERIMENTAL_PARALLEL_SYNTH = booleanPreferencesKey("pref_experimental_parallel_synth")
    /** Slider-era Int key — 1..8 instance count. */
    val PARALLEL_SYNTH_INSTANCES = intPreferencesKey("pref_parallel_synth_instances")
    /** Slider-era Int key — 0 = Auto, 1..8 = numThreads override per engine. */
    val SYNTH_THREADS_PER_INSTANCE = intPreferencesKey("pref_synth_threads_per_instance")

    // ── Resume policy (issue #90) ──────────────────────────────────
    /** Tracks the user's last play/pause intent. true = was playing,
     *  false = explicitly paused. Library's Resume CTA reads this. */
    val LAST_WAS_PLAYING = booleanPreferencesKey("pref_last_was_playing")

    // ── Chat grounding (issue #212) ────────────────────────────────
    /** Defaults match pre-#212 ChatViewModel behaviour: chapter title
     *  on, every more-expensive level off. */
    val AI_CHAT_GROUND_CHAPTER_TITLE = booleanPreferencesKey("pref_ai_chat_ground_chapter_title")
    val AI_CHAT_GROUND_CURRENT_SENTENCE = booleanPreferencesKey("pref_ai_chat_ground_current_sentence")
    val AI_CHAT_GROUND_ENTIRE_CHAPTER = booleanPreferencesKey("pref_ai_chat_ground_entire_chapter")
    val AI_CHAT_GROUND_ENTIRE_BOOK = booleanPreferencesKey("pref_ai_chat_ground_entire_book")
    /** Issue #217 — cross-fiction memory toggle. Default ON; the
     *  Settings → AI screen lets the user opt out. Absent key reads
     *  as true so fresh installs (and pre-#217 installs upgrading)
     *  both get the more-useful default. */
    val AI_CARRY_MEMORY_ACROSS_FICTIONS = booleanPreferencesKey("pref_ai_carry_memory_across_fictions")
    /** Issue #216 — "Allow the AI to take actions" toggle. Default
     *  ON across fresh + upgrading installs; the Settings → AI
     *  screen lets the user opt out. Same write-once-default-on
     *  pattern as the cross-fiction memory key. */
    val AI_ACTIONS_ENABLED = booleanPreferencesKey("pref_ai_actions_enabled")

    /** Issue #135 — JSON-serialized [PronunciationDict] (list of
     *  pattern/replacement/matchType entries). _v1 suffix lets us
     *  rev the schema later without a destructive migration; an
     *  unparseable v1 blob falls back to [PronunciationDict.EMPTY]
     *  and the user can re-enter their entries.
     *
     *  Stored as a flat JSON string rather than DataStore-Proto
     *  because the rest of the file uses preferencesDataStore and
     *  we want one store, one migration surface. The Json instance
     *  in this file matches Converters.kt's settings
     *  (`ignoreUnknownKeys = true; encodeDefaults = true`). */
    val PRONUNCIATION_DICT = stringPreferencesKey("pref_pronunciation_dict_v1")

    // ── Calliope (v0.5.00) milestone celebration ──────────────────
    /** One-time gate for the brass "thank-you" dialog. Flips to true
     *  after the user taps Continue (or taps outside the card). Pre-
     *  flip, the dialog renders on every fresh launch of a v0.5.00+
     *  build. Post-flip, the dialog never reappears for the life of
     *  this install. Cleared by uninstall / app-data-clear like every
     *  other pref. */
    val V0500_MILESTONE_SEEN = booleanPreferencesKey("pref_v0500_milestone_seen")
    /** One-time gate for the chapter-complete confetti easter-egg.
     *  Flips to true after the overlay fades. Independent from
     *  [V0500_MILESTONE_SEEN] — the user might dismiss the dialog
     *  before listening, or listen first and only later open the
     *  dialog. */
    val V0500_CONFETTI_SHOWN = booleanPreferencesKey("pref_v0500_confetti_shown")

    /** Issue #517 — gate for the TechEmpower Home onboarding state.
     *  Flips to true the first time the user opens the dedicated
     *  TechEmpower Home screen (via the brass-edged Library hero
     *  card). Today it's set-only — no UI reads it back yet — but
     *  surfaces a future one-time onboarding nudge (e.g., "tap the
     *  brass hero to explore TechEmpower's resources") without
     *  needing to add a fresh DataStore key in that follow-up.
     *
     *  Unlike [V0500_MILESTONE_SEEN] / [V0500_CONFETTI_SHOWN], this
     *  flag IS synced across devices so a user who visits TechEmpower
     *  Home on their phone doesn't get re-nudged on their tablet — a
     *  cross-device "have you discovered this section?" flag carries
     *  the right semantics for sync. See the per-key entry in
     *  [SYNC_ALLOWLIST] + [SYNC_KEY_TYPES]. */
    val TECHEMPOWER_HOME_SEEN = booleanPreferencesKey("pref_techempower_home_seen")

    // ── Inbox per-source mute toggles (issue #383) ─────────────────
    // Added at the END of Keys to minimize merge conflicts with the
    // other agents touching this file (Vertex SA, plugin seam).
    // Defaults are ON across the board — the Inbox is opt-out
    // per-source; flipping a toggle OFF stops the backend's update
    // emitter from writing to the inbox_event table.
    val INBOX_NOTIFY_ROYALROAD = booleanPreferencesKey("pref_inbox_notify_royalroad")
    val INBOX_NOTIFY_KVMR = booleanPreferencesKey("pref_inbox_notify_kvmr")
    val INBOX_NOTIFY_WIKIPEDIA = booleanPreferencesKey("pref_inbox_notify_wikipedia")

    // ── InstantDB magical sign-in onboarding (issue #500) ──────────
    /** Has the user seen and dismissed (or completed) the first-launch
     *  InstantDB sync onboarding card mounted after the
     *  VoicePickerGate? One-way flag — once flipped to true it never
     *  resets for the life of this install, so the card never re-
     *  prompts. The issue explicitly requires "Skip is fully respected
     *  — never re-prompt this flow." */
    val SYNC_ONBOARDING_DISMISSED = booleanPreferencesKey("pref_sync_onboarding_dismissed")

    /**
     * Issue #599 (v1.0 blocker) — has the user completed (or skipped)
     * the three-screen first-launch welcome flow? Once true, the flow
     * never re-prompts on cold launch. Settings → Advanced → "Reset
     * onboarding" flips this back to false; ordinary use sets it true
     * either via the Welcome screen's "I've used storyvox before" skip
     * or via the Pick-what-to-listen-to screen's final CTA.
     */
    val ONBOARDING_COMPLETED_V1 = booleanPreferencesKey("pref_onboarding_completed_v1")

    // ── Accessibility scaffold (Phase 1, v0.5.42) ──────────────────
    // Persists the user's explicit intent for the assistive-service
    // tunables surfaced by the new Settings → Accessibility subscreen.
    // Phase 1 only stores the values; Phase 2 agents (high-contrast
    // theme, TalkBack adapter, reduced-motion enforcer) read them.
    // All defaults are the no-op state — turning a toggle off keeps
    // storyvox's current behavior bit-identical.
    val A11Y_HIGH_CONTRAST = booleanPreferencesKey("pref_a11y_high_contrast")
    val A11Y_REDUCED_MOTION = booleanPreferencesKey("pref_a11y_reduced_motion")
    val A11Y_LARGER_TOUCH_TARGETS = booleanPreferencesKey("pref_a11y_larger_touch_targets")
    val A11Y_SCREEN_READER_PAUSE_MS = intPreferencesKey("pref_a11y_screen_reader_pause_ms")
    /** Stored as the [SpeakChapterMode] enum's name. Unknown values
     *  fall back to [SpeakChapterMode.Both] on read. */
    val A11Y_SPEAK_CHAPTER_MODE = stringPreferencesKey("pref_a11y_speak_chapter_mode")
    val A11Y_FONT_SCALE_OVERRIDE = floatPreferencesKey("pref_a11y_font_scale_override")
    /** Stored as the [ReadingDirection] enum's name. Unknown values
     *  fall back to [ReadingDirection.FollowSystem] on read. */
    val A11Y_READING_DIRECTION = stringPreferencesKey("pref_a11y_reading_direction")

    /**
     * Accessibility scaffold Phase 2 (#488, v0.5.43) — one-shot
     * dismissal flag for the TalkBack-install nudge surfaced in the
     * Settings → Accessibility subscreen when the user touches a
     * screen-reader-related row but Android isn't reporting an
     * active TalkBack service.
     *
     * Default `false` — every install sees the card once until the
     * user dismisses it. Phase 2 doesn't surface the card outside
     * the Accessibility subscreen.
     */
    val A11Y_TALKBACK_NUDGE_DISMISSED = booleanPreferencesKey("pref_a11y_talkback_nudge_dismissed")

    // ── Appearance (v0.5.59) ───────────────────────────────────────
    /**
     * Book-cover fallback style — see [`CoverStyle`] in `:feature/api`
     * and the [`LocalCoverStyle`] CompositionLocal in `:core-ui` that
     * routes to the right tile. Persisted as the enum's `name` so a
     * future-added value falls back cleanly to [CoverStyle.Monogram]
     * on read.
     *
     * Default on read is [CoverStyle.Monogram] — the JP-preferred
     * classic minimalist tile, and the visual revert from the v0.5.51
     * BrandedCoverTile experiment (#514). Existing users on
     * v0.5.51..v0.5.58 don't have this key in their DataStore, so the
     * absent-key path resolves to Monogram on first launch of v0.5.59;
     * they see the old covers come back automatically.
     */
    val COVER_STYLE = stringPreferencesKey("pref_cover_style")
}

/** Issue #195 — flat string codec for `Map<voiceId, Float>` overrides.
 *  Format: `voiceId=value;voiceId=value`. Voice IDs from the catalog
 *  are alphanumeric + underscores (`piper_amy_x_low`, `kokoro_af_bella`)
 *  so neither `=` nor `;` collide with valid IDs. Empty / null input
 *  parses to an empty map. Bad entries are dropped silently — the
 *  override map is non-critical state. */
/**
 * Issue #589 — supported animation-speed scale tiers. Mirrors the chip
 * row in Settings → Appearance: Off / Slow / Normal / Brisk / Fast.
 * Off is 0 (durations become 0 = instant); other tiers multiply
 * existing tween durations.
 *
 * Exposed as `internal` so the Settings chip-row UI in `:feature` and
 * the `:core-ui` tweenScaled helper can read the same canonical list
 * without forking the values.
 */
internal val ANIMATION_SPEED_SCALE_TIERS: List<Float> =
    listOf(0f, 0.5f, 1f, 1.5f, 2f)

internal fun snapAnimationSpeedScale(scale: Float): Float =
    ANIMATION_SPEED_SCALE_TIERS.minBy { kotlin.math.abs(it - scale) }

/**
 * Issue #593 — supported skip-distance tiers in seconds. Matches
 * Spotify (15/30), Apple Music (15/30), Pocket Casts (10/15/30/45/60).
 */
internal val SKIP_DISTANCE_TIERS_SEC: List<Int> = listOf(10, 15, 30, 45, 60)

internal fun snapSkipDistanceSec(seconds: Int): Int =
    SKIP_DISTANCE_TIERS_SEC.minBy { kotlin.math.abs(it - seconds) }

/**
 * Issue #594 — supported rewind-to-start threshold tiers in seconds.
 * 0 = disabled. Standard player default is 3s.
 */
internal val REWIND_TO_START_TIERS_SEC: List<Int> = listOf(0, 1, 3, 5, 10)

internal fun snapRewindToStartThresholdSec(seconds: Int): Int =
    REWIND_TO_START_TIERS_SEC.minBy { kotlin.math.abs(it - seconds) }

/**
 * Issue #595 — supported sleep-timer shake-to-extend tiers in
 * minutes. Chip-row: 5 / 10 / 15 / 30. Default 15 matches the
 * legacy hardcoded constant.
 */
internal val SLEEP_SHAKE_EXTEND_TIERS_MIN: List<Int> = listOf(5, 10, 15, 30)

internal fun snapSleepShakeExtendMinutes(minutes: Int): Int =
    SLEEP_SHAKE_EXTEND_TIERS_MIN.minBy { kotlin.math.abs(it - minutes) }

/**
 * Issue #596 — supported pre-render chapter-count tiers. Chip-row
 * spec is "N+1 / N+2 / N+3 / N+5"; we store the raw integer count
 * directly (1/2/3/5). Default 5 matches the legacy
 * `DEFAULT_PRERENDER_CHAPTERS` constant.
 */
internal val PRERENDER_CHAPTER_COUNT_TIERS: List<Int> = listOf(1, 2, 3, 5)

internal fun snapPrerenderChapterCount(count: Int): Int =
    PRERENDER_CHAPTER_COUNT_TIERS.minBy { kotlin.math.abs(it - count) }

/**
 * Issue #598 — supported Android Auto bucket-size tiers. Chip-row:
 * 4 / 6 / 8 / 12. Default 6 matches the HMI guideline.
 */
internal val AUTO_ITEMS_PER_CATEGORY_TIERS: List<Int> = listOf(4, 6, 8, 12)

internal fun snapAutoItemsPerCategory(count: Int): Int =
    AUTO_ITEMS_PER_CATEGORY_TIERS.minBy { kotlin.math.abs(it - count) }

private fun encodeVoiceFloatMap(map: Map<String, Float>): String =
    map.entries.joinToString(";") { (k, v) -> "$k=$v" }

private fun decodeVoiceFloatMap(raw: String?): Map<String, Float> {
    if (raw.isNullOrBlank()) return emptyMap()
    return raw.split(';').mapNotNull { entry ->
        val eq = entry.indexOf('=')
        if (eq <= 0) return@mapNotNull null
        val k = entry.substring(0, eq)
        val v = entry.substring(eq + 1).toFloatOrNull() ?: return@mapNotNull null
        k to v
    }.toMap()
}

/** Issue #197 / #198 — same flat-string codec as
 *  [encodeVoiceFloatMap] but for string values (lexicon paths,
 *  language codes). Voice IDs from the catalog are alphanumeric +
 *  underscores so neither `=` nor `;` collide. Values:
 *   - lexicon paths: Android FS paths use `/` + alphanumerics; SAF
 *     content:// URIs use `://` and `%2F` but not raw `;` (RFC 3986
 *     reserves it, and SAF percent-encodes). Documented expectation
 *     is alphanumerics + `/`, `.`, `_`, `-`, `:`, `%` — no codec
 *     collisions in practice.
 *   - language codes: 2-letter ISO codes from
 *     [KOKORO_PHONEMIZER_LANGS], no special chars.
 *  Bad / corrupt entries are dropped silently — these maps are
 *  non-critical state and the engine falls back cleanly. */
private fun encodeVoiceStringMap(map: Map<String, String>): String =
    map.entries.joinToString(";") { (k, v) -> "$k=$v" }

private fun decodeVoiceStringMap(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    return raw.split(';').mapNotNull { entry ->
        val eq = entry.indexOf('=')
        if (eq <= 0) return@mapNotNull null
        val k = entry.substring(0, eq)
        val v = entry.substring(eq + 1)
        if (v.isEmpty()) return@mapNotNull null
        k to v
    }.toMap()
}


@Singleton
class SettingsRepositoryUiImpl(
    private val store: DataStore<Preferences>,
    private val auth: AuthRepository,
    private val hydrator: SessionHydrator,
    private val palaceConfig: PalaceConfigImpl,
    private val palaceApi: PalaceDaemonApi,
    private val llmCreds: LlmCredentialsStore,
    private val githubAuth: GitHubAuthRepository,
    private val teamsAuth: AnthropicTeamsAuthRepository,
    private val rssConfig: RssConfigImpl,
    private val epubConfig: EpubConfigImpl,
    private val outlineConfig: OutlineConfigImpl,
    private val wikipediaConfig: WikipediaConfigImpl,
    private val notionConfig: NotionConfigImpl,
    private val discordConfig: DiscordConfigImpl,
    private val discordGuildDirectory: `in`.jphe.storyvox.source.discord.DiscordGuildDirectory,
    private val telegramConfig: TelegramConfigImpl,
    private val telegramChannelDirectory: `in`.jphe.storyvox.source.telegram.TelegramChannelDirectory,
    private val suggestedFeedsRegistry: SuggestedFeedsRegistry,
    private val azureCreds: AzureCredentials,
    private val azureClient: AzureSpeechClient,
    private val azureRoster: AzureVoiceRoster,
    /** Issue #219 — injected so clearing/replacing the SA JSON also
     *  evicts the in-process access-token cache. Without this, a user
     *  who swaps their SA in Settings would keep using the old token
     *  until process restart. */
    private val googleTokenSource: GoogleOAuthTokenSource,
    /** PCM cache PR-G (#86) — Settings UI's "Clear cache" button calls
     *  through to [PcmCache.clearAll], and quota tightening calls
     *  [PcmCache.evictToQuota] to honor the new cap immediately. */
    private val pcmCache: PcmCache,
    /** PCM cache PR-G (#86) — [SettingsRepositoryUi.setCacheQuotaBytes]
     *  snaps to a discrete tier and persists via this config object's
     *  own DataStore (`pcm_cache_config`), separate from the main
     *  settings store. */
    private val pcmCacheConfig: PcmCacheConfig,
    /** PCM cache PR-G (#86) — 5-second polling Flow of cache size +
     *  quota, combined into the [settings] flow so the Settings UI's
     *  "Currently used: 1.4 GB / 2 GB" row stays live. */
    private val cacheStats: CacheStatsRepository,
) : SettingsRepositoryUi,
    PlaybackBufferConfig,
    PlaybackModeConfig,
    VoiceTuningConfig,
    AzureFallbackConfig,
    ParallelSynthConfig,
    PlaybackResumePolicyConfig,
    `in`.jphe.storyvox.data.repository.playback.PlaybackSkipConfig,
    SleepTimerExtendConfig,
    PrerenderChapterCountConfig,
    AutoBrowserConfig,
    NetworkPatienceConfig,
    PronunciationDictRepository,
    LlmConfigProvider,
    GitHubScopePreferences,
    `in`.jphe.storyvox.data.repository.sync.SettingsSnapshotSource {

    /** Hilt entry point — pulls the production DataStore from the app context.
     *  The primary constructor takes the store directly so tests can swap in
     *  a `PreferenceDataStoreFactory.create(file)`-backed instance against a
     *  `TemporaryFolder`. Mirrors the seam used in [VoiceFavorites.forTesting]. */
    @Inject constructor(
        @ApplicationContext context: Context,
        auth: AuthRepository,
        hydrator: SessionHydrator,
        palaceConfig: PalaceConfigImpl,
        palaceApi: PalaceDaemonApi,
        llmCreds: LlmCredentialsStore,
        githubAuth: GitHubAuthRepository,
        teamsAuth: AnthropicTeamsAuthRepository,
        rssConfig: RssConfigImpl,
        epubConfig: EpubConfigImpl,
        outlineConfig: OutlineConfigImpl,
        wikipediaConfig: WikipediaConfigImpl,
        notionConfig: NotionConfigImpl,
        discordConfig: DiscordConfigImpl,
        discordGuildDirectory: `in`.jphe.storyvox.source.discord.DiscordGuildDirectory,
        telegramConfig: TelegramConfigImpl,
        telegramChannelDirectory: `in`.jphe.storyvox.source.telegram.TelegramChannelDirectory,
        suggestedFeedsRegistry: SuggestedFeedsRegistry,
        azureCreds: AzureCredentials,
        azureClient: AzureSpeechClient,
        azureRoster: AzureVoiceRoster,
        googleTokenSource: GoogleOAuthTokenSource,
        pcmCache: PcmCache,
        pcmCacheConfig: PcmCacheConfig,
        cacheStats: CacheStatsRepository,
    ) : this(
        context.settingsDataStore, auth, hydrator,
        palaceConfig, palaceApi, llmCreds, githubAuth, teamsAuth, rssConfig, epubConfig,
        outlineConfig, wikipediaConfig, notionConfig, discordConfig, discordGuildDirectory,
        telegramConfig, telegramChannelDirectory,
        suggestedFeedsRegistry,
        azureCreds, azureClient, azureRoster,
        googleTokenSource,
        pcmCache, pcmCacheConfig, cacheStats,
    )

    /**
     * Tick that bumps on every Azure setter so the [settings] flow
     * re-emits with the fresh [AzureCredentials] snapshot. The
     * encrypted-prefs store doesn't expose a Flow of its own (it's a
     * bare [SharedPreferences] for cross-language compatibility with
     * [LlmCredentialsStore]), so this internal tick fills the gap —
     * same shape as `MutableStateFlow<Long>(0)` used elsewhere in the
     * app for non-flow-aware deps.
     */
    private val azureTick = kotlinx.coroutines.flow.MutableStateFlow(0L)

    /** Issue #377 + #233 + #403 + #462 — non-prefs source configs
     *  bundled into a single combine so the outer combine stays
     *  inside the 5-arg overload. Palace + Wikipedia + Notion +
     *  Discord + Telegram ride together; each re-emits independently
     *  when its respective store changes.
     *
     *  PCM cache PR-G (#86) added [cacheStats] to this bundle for the
     *  same reason — the Settings UI's "Currently used: 1.4 GB / 2 GB"
     *  indicator subscribes through the main [settings] flow, and the
     *  bundle lets us add a sixth observable without bumping the outer
     *  combine to a vararg form. */
    private data class NonPrefsConfigs(
        val palace: `in`.jphe.storyvox.source.mempalace.config.PalaceConfigState,
        val wikipedia: `in`.jphe.storyvox.source.wikipedia.config.WikipediaConfigState,
        val notion: `in`.jphe.storyvox.source.notion.config.NotionConfigState,
        val discord: `in`.jphe.storyvox.source.discord.config.DiscordConfigState,
        val telegram: `in`.jphe.storyvox.source.telegram.config.TelegramConfigState,
        val cacheStats: CacheStatsRepository.CacheStats,
    )

    private val nonPrefsConfigs: Flow<NonPrefsConfigs> =
        combine(
            combine(
                palaceConfig.state,
                wikipediaConfig.state,
                notionConfig.state,
                discordConfig.state,
                telegramConfig.state,
            ) { palace, wiki, notion, discord, telegram ->
                arrayOf(palace, wiki, notion, discord, telegram)
            },
            cacheStats.observe(),
        ) { sourceStates, stats ->
            NonPrefsConfigs(
                palace = sourceStates[0] as `in`.jphe.storyvox.source.mempalace.config.PalaceConfigState,
                wikipedia = sourceStates[1] as `in`.jphe.storyvox.source.wikipedia.config.WikipediaConfigState,
                notion = sourceStates[2] as `in`.jphe.storyvox.source.notion.config.NotionConfigState,
                discord = sourceStates[3] as `in`.jphe.storyvox.source.discord.config.DiscordConfigState,
                telegram = sourceStates[4] as `in`.jphe.storyvox.source.telegram.config.TelegramConfigState,
                cacheStats = stats,
            )
        }

    override val settings: Flow<UiSettings> = combine(
        store.data,
        nonPrefsConfigs,
        githubAuth.sessionState,
        teamsAuth.sessionState,
        azureTick,
    ) { prefs, configs, githubSession, teamsSession, _ ->
        val palace = configs.palace
        val wikipedia = configs.wikipedia
        val notion = configs.notion
        val discord = configs.discord
        val telegram = configs.telegram
        UiSettings(
            ttsEngine = "VoxSherpa",
            defaultVoiceId = prefs[Keys.DEFAULT_VOICE_ID],
            // Issue #294 — web-fiction listeners consistently prefer 1.10×;
            // 1.00× is audiobook tempo, too slow for serial fiction.
            // Existing users keep their stored value via the prefs lookup;
            // only the fallback for a fresh install changed.
            defaultSpeed = prefs[Keys.DEFAULT_SPEED] ?: 1.1f,
            defaultPitch = prefs[Keys.DEFAULT_PITCH] ?: 1.0f,
            voiceSpeedOverrides = decodeVoiceFloatMap(prefs[Keys.VOICE_SPEED_OVERRIDES]),
            voicePitchOverrides = decodeVoiceFloatMap(prefs[Keys.VOICE_PITCH_OVERRIDES]),
            voiceLexiconOverrides = decodeVoiceStringMap(prefs[Keys.VOICE_LEXICON_OVERRIDES]),
            voicePhonemizerLangOverrides =
                decodeVoiceStringMap(prefs[Keys.VOICE_PHONEMIZER_LANG_OVERRIDES]),
            themeOverride = prefs[Keys.THEME_OVERRIDE]?.let { runCatching { ThemeOverride.valueOf(it) }.getOrNull() }
                ?: ThemeOverride.System,
            downloadOnWifiOnly = prefs[Keys.DOWNLOAD_WIFI_ONLY] ?: true,
            pollIntervalHours = prefs[Keys.POLL_INTERVAL_HOURS] ?: 6,
            isSignedIn = prefs[Keys.SIGNED_IN] ?: false,
            pitchInterpolationHighQuality = prefs[Keys.PITCH_INTERP_HIGH_QUALITY] ?: true,
            punctuationPauseMultiplier = (prefs[Keys.PUNCTUATION_PAUSE_MULTIPLIER]
                ?: PUNCTUATION_PAUSE_DEFAULT_MULTIPLIER)
                .coerceIn(PUNCTUATION_PAUSE_MIN_MULTIPLIER, PUNCTUATION_PAUSE_MAX_MULTIPLIER),
            playbackBufferChunks = (prefs[Keys.PLAYBACK_BUFFER_CHUNKS] ?: BUFFER_DEFAULT_CHUNKS)
                .coerceIn(BUFFER_MIN_CHUNKS, BUFFER_MAX_CHUNKS),
            // 2026-05-09: warmupWait default off (UX), catchupPause
            // default back on (perf — see UiSettings.catchupPause kdoc).
            warmupWait = prefs[Keys.WARMUP_WAIT] ?: false,
            catchupPause = prefs[Keys.CATCHUP_PAUSE] ?: true,
            fullPrerender = prefs[Keys.FULL_PRERENDER] ?: false,
            // PCM cache PR-G (#86) — live cache stats pulled from
            // CacheStatsRepository's 5 s polling flow (combined into
            // NonPrefsConfigs above). The "Currently used" indicator
            // in Settings reads these; we re-poll on demand after
            // Clear cache so the wipe lands faster than a 5 s tick.
            cacheUsedBytes = configs.cacheStats.usedBytes,
            cacheQuotaBytes = configs.cacheStats.quotaBytes,
            voiceSteady = prefs[Keys.VOICE_STEADY] ?: true,
            palace = UiPalaceConfig(host = palace.host, apiKey = palace.apiKey),
            github = githubSession.toUi(),
            githubPrivateReposEnabled = prefs[Keys.GITHUB_PRIVATE_REPOS_ENABLED] ?: false,
            // Issue #294 — Royal Road is the de-facto primary source
            // storyvox was built around; defaulting OFF meant a new user
            // opened Browse and saw the empty-picker state. Flip to ON
            // for fresh installs alongside RSS. Existing users keep their
            // stored toggle via the prefs lookup; only the fallback
            // changed.
            //
            // The playstore build flavor (#240, not yet landed) is
            // expected to override this to FALSE at the BuildConfig level
            // to satisfy the Play Store's anti-scraping posture. Until
            // that lands, this default is ON for all builds.
            wikipediaLanguageCode = wikipedia.languageCode,
            discordTokenConfigured = discord.apiToken.isNotBlank(),
            discordServerId = discord.serverId,
            discordServerName = discord.serverName,
            discordCoalesceMinutes = discord.coalesceMinutes,
            telegramTokenConfigured = telegram.apiToken.isNotBlank(),
            // Plugin-seam Phase 1 (#384) — derive the per-plugin map
            // from the JSON blob seeded by SourcePluginsMapMigration.
            // Empty map (parse error / missing key in a race) falls
            // through to a defaults-from-LegacySourceKeys snapshot so
            // observers always see a coherent state.
            sourcePluginsEnabled = run {
                val parsed = `in`.jphe.storyvox.data.source.plugin.decodeSourcePluginsEnabledJson(
                    prefs[Keys.SOURCE_PLUGINS_ENABLED_JSON],
                )
                if (parsed.isNotEmpty()) {
                    parsed
                } else {
                    LegacySourceKeys.ALL.mapValues { (_, spec) ->
                        prefs[spec.key] ?: spec.defaultValue
                    }
                }
            },
            // Plugin-seam Phase 4 (#501) — Voice bundles in Plugin
            // Manager. The map is empty on a fresh install; the
            // ViewModel falls back to each family's `defaultEnabled`
            // when its id is absent, so no migration shim is needed.
            voiceFamiliesEnabled = `in`.jphe.storyvox.data.source.plugin.decodeVoiceFamiliesEnabledJson(
                prefs[Keys.VOICE_FAMILIES_ENABLED_JSON],
            ),
            // v0.5.76 — Browse carousel "starred" sources. Empty on
            // fresh install; no migration since there's no legacy
            // shape to seed from.
            favoriteSourceIds = `in`.jphe.storyvox.data.source.plugin.decodeSourceFavoritesJson(
                prefs[Keys.SOURCE_FAVORITES_JSON],
            ),
            notionDatabaseId = notion.databaseId,
            notionTokenConfigured = notion.apiToken.isNotBlank(),
            // Issue #393 — surface the anonymous-mode posture so the
            // Settings UI can render a "Reading TechEmpower content
            // anonymously" affordance and the user understands the PAT
            // field is opt-in for private workspaces.
            notionMode = notion.mode.name,
            notionRootPageId = notion.rootPageId,
            sleepShakeToExtendEnabled = prefs[Keys.SLEEP_SHAKE_TO_EXTEND_ENABLED] ?: true,
            showDebugOverlay = prefs[Keys.SHOW_DEBUG_OVERLAY] ?: false,
            azure = run {
                // #182 — read the encrypted snapshot imperatively each
                // emission. The azureTick flow above guarantees a fresh
                // re-emission after every setter; combining the bare
                // SharedPreferences-backed creds in directly would need
                // a Flow we don't currently expose from :source-azure.
                val regionId = azureCreds.regionId()
                UiAzureConfig(
                    key = azureCreds.key().orEmpty(),
                    regionId = regionId,
                    regionDisplayName = AzureRegion.byId(regionId)?.displayName ?: regionId,
                )
            },
            // PR-6 (#185) — Azure offline-fallback toggle + voice id.
            // Read from the unencrypted prefs store (no secret here —
            // just user UX preference + a public voice id). The
            // AzureVoiceEngine's lastError observer in EnginePlayer
            // reads these at the moment of failure; no need for a
            // dedicated tick flow.
            azureFallbackEnabled = prefs[Keys.AZURE_FALLBACK_ENABLED] ?: false,
            azureFallbackVoiceId = prefs[Keys.AZURE_FALLBACK_VOICE_ID],
            parallelSynthInstances = readParallelSynthInstances(prefs),
            synthThreadsPerInstance = (prefs[Keys.SYNTH_THREADS_PER_INSTANCE] ?: 0)
                .coerceIn(0, 8),
            ai = UiAiSettings(
                provider = prefs[Keys.AI_PROVIDER]
                    ?.takeIf { it.isNotBlank() }
                    ?.let { runCatching { UiLlmProvider.valueOf(it) }.getOrNull() },
                claudeModel = prefs[Keys.AI_CLAUDE_MODEL] ?: "claude-haiku-4.5",
                claudeKeyConfigured = llmCreds.hasClaudeKey,
                openAiModel = prefs[Keys.AI_OPENAI_MODEL] ?: "gpt-4o-mini",
                openAiKeyConfigured = llmCreds.hasOpenAiKey,
                // Issue #294 — JP's LAN address shouldn't be baked into
                // every fresh install. Default empty; the UI surfaces a
                // placeholder.
                ollamaBaseUrl = prefs[Keys.AI_OLLAMA_BASE_URL] ?: "",
                // Issue #294 — llama3.2:3b actually fits on phone-class
                // hardware; 3.3 (70B) doesn't run locally for most users
                // even when they have ollama on a LAN host.
                ollamaModel = prefs[Keys.AI_OLLAMA_MODEL] ?: "llama3.2:3b",
                vertexModel = prefs[Keys.AI_VERTEX_MODEL] ?: "gemini-2.5-flash",
                vertexKeyConfigured = llmCreds.hasVertexKey,
                vertexServiceAccountConfigured = llmCreds.hasVertexServiceAccount,
                vertexServiceAccountEmail = llmCreds.vertexServiceAccountJson()
                    ?.let { runCatching { GoogleServiceAccount.parse(it).clientEmail }.getOrNull() },
                foundryEndpoint = prefs[Keys.AI_FOUNDRY_ENDPOINT] ?: "",
                foundryDeployment = prefs[Keys.AI_FOUNDRY_DEPLOYMENT] ?: "",
                foundryServerless = prefs[Keys.AI_FOUNDRY_SERVERLESS] ?: false,
                foundryKeyConfigured = llmCreds.hasFoundryKey,
                bedrockAccessKeyConfigured = !llmCreds.bedrockAccessKey().isNullOrBlank(),
                bedrockSecretKeyConfigured = !llmCreds.bedrockSecretKey().isNullOrBlank(),
                bedrockRegion = prefs[Keys.AI_BEDROCK_REGION] ?: "us-east-1",
                bedrockModel = prefs[Keys.AI_BEDROCK_MODEL] ?: "claude-haiku-4.5",
                teamsSignedIn = teamsSession is TeamsSession.SignedIn,
                teamsScopes = (teamsSession as? TeamsSession.SignedIn)?.scopes
                    ?: llmCreds.teamsScopes().orEmpty(),
                privacyAcknowledged = prefs[Keys.AI_PRIVACY_ACK] ?: false,
                // Issue #294 — privacy-first: chapter text is the user's
                // content, and AI grounding is opt-in by default. Recap
                // and chat fall back to title + current-sentence
                // grounding (still useful) until the user opts in. This
                // is a behavioural change worth flagging in release
                // notes.
                sendChapterTextEnabled = prefs[Keys.AI_SEND_CHAPTER_TEXT] ?: false,
                chatGrounding = UiChatGrounding(
                    includeChapterTitle = prefs[Keys.AI_CHAT_GROUND_CHAPTER_TITLE] ?: true,
                    // Issue #294 — current-sentence grounding is ~50
                    // tokens; tiny cost for outsized context gain. Ship
                    // ON by default so first-launch chats are useful.
                    includeCurrentSentence = prefs[Keys.AI_CHAT_GROUND_CURRENT_SENTENCE] ?: true,
                    includeEntireChapter = prefs[Keys.AI_CHAT_GROUND_ENTIRE_CHAPTER] ?: false,
                    includeEntireBookSoFar = prefs[Keys.AI_CHAT_GROUND_ENTIRE_BOOK] ?: false,
                ),
                // Issue #217 — default ON for fresh installs; pre-#217
                // upgrades also pick up the default because the key
                // doesn't exist yet (DataStore returns null → ?: true).
                carryMemoryAcrossFictions = prefs[Keys.AI_CARRY_MEMORY_ACROSS_FICTIONS] ?: true,
                // Issue #216 — default ON for fresh installs (matches
                // [LlmConfig.aiActionsEnabled]). The chat surface only
                // exposes actions when the active provider supports
                // tool use; this toggle is the user's "I don't want
                // the AI doing things" kill switch.
                actionsEnabled = prefs[Keys.AI_ACTIONS_ENABLED] ?: true,
            ),
            sigil = Sigil.current.let {
                UiSigil(
                    name = it.name,
                    realm = it.realm,
                    hash = it.hash,
                    branch = it.branch,
                    dirty = it.dirty,
                    built = it.built,
                    repo = it.repo,
                    versionName = it.versionName,
                )
            },
            // Issue #383 — Inbox per-source notification toggles.
            // Default ON across the board on fresh installs; user opts
            // out from Settings → Library & Sync → Inbox notifications.
            inboxNotifyRoyalRoad = prefs[Keys.INBOX_NOTIFY_ROYALROAD] ?: true,
            inboxNotifyKvmr = prefs[Keys.INBOX_NOTIFY_KVMR] ?: true,
            inboxNotifyWikipedia = prefs[Keys.INBOX_NOTIFY_WIKIPEDIA] ?: true,
            // Accessibility scaffold (Phase 1) — all defaults are the
            // no-op state so an upgrading user sees identical behavior
            // until they explicitly opt in. Enum keys fall back to
            // their first-declared value on a parse failure (a forward-
            // compat remote pushing a v2 enum we don't know).
            a11yHighContrast = prefs[Keys.A11Y_HIGH_CONTRAST] ?: false,
            a11yReducedMotion = prefs[Keys.A11Y_REDUCED_MOTION] ?: false,
            a11yLargerTouchTargets = prefs[Keys.A11Y_LARGER_TOUCH_TARGETS] ?: false,
            a11yScreenReaderPauseMs = (prefs[Keys.A11Y_SCREEN_READER_PAUSE_MS] ?: 500)
                .coerceIn(0, 1500),
            a11ySpeakChapterMode = prefs[Keys.A11Y_SPEAK_CHAPTER_MODE]
                ?.let { runCatching { SpeakChapterMode.valueOf(it) }.getOrNull() }
                ?: SpeakChapterMode.Both,
            a11yFontScaleOverride = (prefs[Keys.A11Y_FONT_SCALE_OVERRIDE] ?: 1.0f)
                .coerceIn(0.85f, 1.5f),
            a11yReadingDirection = prefs[Keys.A11Y_READING_DIRECTION]
                ?.let { runCatching { ReadingDirection.valueOf(it) }.getOrNull() }
                ?: ReadingDirection.FollowSystem,
            a11yTalkBackNudgeDismissed = prefs[Keys.A11Y_TALKBACK_NUDGE_DISMISSED] ?: false,
            // v0.5.59 — book-cover fallback style. Absent key →
            // CoverStyle.Monogram (the JP-preferred default + the
            // visual revert from the v0.5.51 BrandedCoverTile). Unknown
            // string values (a future-rolled-back enum value coming in
            // via sync) also fall back to Monogram rather than crashing.
            coverStyle = prefs[Keys.COVER_STYLE]
                ?.let { runCatching { CoverStyle.valueOf(it) }.getOrNull() }
                ?: CoverStyle.Monogram,
            // Issue #589 — animation-speed master multiplier. Snap to
            // the supported chip values on read so a corrupt or
            // legacy value still renders predictably in the picker;
            // anything off-grid maps to the nearest tier.
            animationSpeedScale = snapAnimationSpeedScale(
                prefs[Keys.ANIMATION_SPEED_SCALE] ?: 1.0f,
            ),
            // Issue #593 — skip distance in seconds. Coerce to a
            // supported tier on read.
            skipDistanceSec = snapSkipDistanceSec(
                prefs[Keys.SKIP_DISTANCE_SEC] ?: 30,
            ),
            // Issue #594 — rewind-to-start threshold. 0 = disable
            // (always jump to prev chapter). Snap to supported tier.
            rewindToStartThresholdSec = snapRewindToStartThresholdSec(
                prefs[Keys.REWIND_TO_START_THRESHOLD_SEC] ?: 3,
            ),
            // Issue #595 — sleep-timer shake-to-extend duration in
            // minutes. Snap to a supported chip tier on read.
            sleepShakeExtendMinutes = snapSleepShakeExtendMinutes(
                prefs[Keys.SLEEP_SHAKE_EXTEND_MINUTES] ?: 15,
            ),
            // Issue #596 — pre-render window in chapters.
            prerenderChapterCount = snapPrerenderChapterCount(
                prefs[Keys.PRERENDER_CHAPTER_COUNT] ?: 5,
            ),
            // Issue #590 — particle / confetti intensity. Unknown or
            // legacy values fall back to Subtle.
            particleIntensity = prefs[Keys.PARTICLE_INTENSITY]
                ?.let { runCatching { UiParticleIntensity.valueOf(it) }.getOrNull() }
                ?: UiParticleIntensity.Subtle,
            // Issue #591 — skeleton shimmer style. Fallback Sigil.
            skeletonStyle = prefs[Keys.SKELETON_STYLE]
                ?.let { runCatching { UiSkeletonStyle.valueOf(it) }.getOrNull() }
                ?: UiSkeletonStyle.Sigil,
            // Issue #592 — brass pulse intensity. Fallback Standard.
            brassPulseLevel = prefs[Keys.BRASS_PULSE_LEVEL]
                ?.let { runCatching { UiBrassPulseLevel.valueOf(it) }.getOrNull() }
                ?: UiBrassPulseLevel.Standard,
            // Issue #597 — network patience preset. Fallback Default.
            networkPatience = prefs[Keys.NETWORK_PATIENCE]
                ?.let { runCatching { UiNetworkPatience.valueOf(it) }.getOrNull() }
                ?: UiNetworkPatience.Default,
            // Issue #598 — Android Auto bucket size. Snap to chip
            // tier on read.
            autoItemsPerCategory = snapAutoItemsPerCategory(
                prefs[Keys.AUTO_ITEMS_PER_CATEGORY] ?: 6,
            ),
        )
    }

    override suspend fun setTheme(override: ThemeOverride) {
        store.edit { it[Keys.THEME_OVERRIDE] = override.name }
    }

    override suspend fun setDefaultSpeed(speed: Float) {
        // #195 — per-voice override when a voice is active; otherwise
        // fall back to the global default for fresh installs that
        // haven't picked a voice yet. Pre-#195 callers never wrote to
        // the global key when a voice was selected, so behavior of
        // existing voices migrates cleanly: their previous global
        // value is the implicit fallback until the user tweaks the
        // slider with that voice active.
        val coerced = speed.coerceIn(0.5f, 3.0f)
        store.edit { prefs ->
            val voiceId = prefs[Keys.DEFAULT_VOICE_ID]
            if (voiceId != null) {
                val map = decodeVoiceFloatMap(prefs[Keys.VOICE_SPEED_OVERRIDES]).toMutableMap()
                map[voiceId] = coerced
                prefs[Keys.VOICE_SPEED_OVERRIDES] = encodeVoiceFloatMap(map)
            } else {
                prefs[Keys.DEFAULT_SPEED] = coerced
            }
        }
    }

    override suspend fun setDefaultPitch(pitch: Float) {
        // #195 — same dual-write story as setDefaultSpeed, with the
        // tightened pitch band from Thalia's VoxSherpa P0 #1
        // (2026-05-08): below ~0.7 Sonic introduces audible artifacts
        // on Piper-medium voices, and above 1.4 is unlistenable.
        val coerced = pitch.coerceIn(0.6f, 1.4f)
        store.edit { prefs ->
            val voiceId = prefs[Keys.DEFAULT_VOICE_ID]
            if (voiceId != null) {
                val map = decodeVoiceFloatMap(prefs[Keys.VOICE_PITCH_OVERRIDES]).toMutableMap()
                map[voiceId] = coerced
                prefs[Keys.VOICE_PITCH_OVERRIDES] = encodeVoiceFloatMap(map)
            } else {
                prefs[Keys.DEFAULT_PITCH] = coerced
            }
        }
    }

    override suspend fun setDefaultVoice(voiceId: String?) {
        // #197 + #198 — switching voices means the per-voice lexicon
        // and (Kokoro) phonemizer-lang overrides change too. Read the
        // new voice's stored values inside the same edit() block and
        // push them to the bridge BEFORE returning so VoiceManager's
        // reload picks up the right knobs. If voiceId is null (user
        // cleared the default) we wipe the bridge fields back to
        // empty so any later engine instantiation uses defaults.
        var lexicon = ""
        var lang = ""
        store.edit { prefs ->
            if (voiceId == null) {
                prefs.remove(Keys.DEFAULT_VOICE_ID)
            } else {
                prefs[Keys.DEFAULT_VOICE_ID] = voiceId
                lexicon = decodeVoiceStringMap(prefs[Keys.VOICE_LEXICON_OVERRIDES])[voiceId]
                    .orEmpty()
                lang = decodeVoiceStringMap(prefs[Keys.VOICE_PHONEMIZER_LANG_OVERRIDES])[voiceId]
                    .orEmpty()
            }
        }
        `in`.jphe.storyvox.playback.VoiceEngineQualityBridge.applyLexicon(lexicon)
        `in`.jphe.storyvox.playback.VoiceEngineQualityBridge.applyPhonemizerLang(lang)
    }

    override suspend fun setVoiceLexicon(voiceId: String, path: String?) {
        // #197 — write the per-voice path map. null / blank clears
        // the entry entirely so the engine falls back to its
        // built-in lexicon on next load. We push to the bridge
        // immediately when voiceId matches the active voice so the
        // *next* chapter pre-render reads the new path; the
        // in-flight engine instance keeps its old lexicon until the
        // next loadModel().
        var isActive = false
        store.edit { prefs ->
            val map = decodeVoiceStringMap(prefs[Keys.VOICE_LEXICON_OVERRIDES]).toMutableMap()
            if (path.isNullOrBlank()) map.remove(voiceId)
            else map[voiceId] = path
            prefs[Keys.VOICE_LEXICON_OVERRIDES] = encodeVoiceStringMap(map)
            isActive = prefs[Keys.DEFAULT_VOICE_ID] == voiceId
        }
        if (isActive) {
            `in`.jphe.storyvox.playback.VoiceEngineQualityBridge
                .applyLexicon(path.orEmpty())
        }
    }

    override suspend fun setVoicePhonemizerLang(voiceId: String, langCode: String?) {
        // #198 — write the per-voice Kokoro phonemizer-lang map.
        // null / blank clears the entry. We don't validate against
        // KOKORO_PHONEMIZER_LANGS here — the UI dropdown only offers
        // recognized codes, and a stray unrecognized value falls back
        // cleanly at the engine layer (Kokoro uses the voice's
        // native language for unknown codes).
        var isActive = false
        store.edit { prefs ->
            val map = decodeVoiceStringMap(
                prefs[Keys.VOICE_PHONEMIZER_LANG_OVERRIDES]
            ).toMutableMap()
            if (langCode.isNullOrBlank()) map.remove(voiceId)
            else map[voiceId] = langCode
            prefs[Keys.VOICE_PHONEMIZER_LANG_OVERRIDES] = encodeVoiceStringMap(map)
            isActive = prefs[Keys.DEFAULT_VOICE_ID] == voiceId
        }
        if (isActive) {
            `in`.jphe.storyvox.playback.VoiceEngineQualityBridge
                .applyPhonemizerLang(langCode.orEmpty())
        }
    }

    override suspend fun setDownloadOnWifiOnly(enabled: Boolean) {
        store.edit { it[Keys.DOWNLOAD_WIFI_ONLY] = enabled }
    }

    override suspend fun setPollIntervalHours(hours: Int) {
        store.edit { it[Keys.POLL_INTERVAL_HOURS] = hours.coerceIn(1, 24) }
    }

    override suspend fun setPunctuationPauseMultiplier(multiplier: Float) {
        store.edit {
            it[Keys.PUNCTUATION_PAUSE_MULTIPLIER] = multiplier
                .coerceIn(PUNCTUATION_PAUSE_MIN_MULTIPLIER, PUNCTUATION_PAUSE_MAX_MULTIPLIER)
        }
    }

    override suspend fun setPitchInterpolationHighQuality(enabled: Boolean) {
        store.edit { it[Keys.PITCH_INTERP_HIGH_QUALITY] = enabled }
        // Push to both engine fields immediately so the next
        // chapter pre-render uses the new setting without waiting
        // for a process restart. Sonic instances are created fresh
        // inside each generateAudioPCM call, so the volatile read
        // there picks up the new value.
        `in`.jphe.storyvox.playback.VoiceEngineQualityBridge.applyPitchQuality(enabled)
    }

    override suspend fun setPlaybackBufferChunks(chunks: Int) {
        store.edit {
            it[Keys.PLAYBACK_BUFFER_CHUNKS] = chunks.coerceIn(BUFFER_MIN_CHUNKS, BUFFER_MAX_CHUNKS)
        }
    }

    override suspend fun setWarmupWait(enabled: Boolean) {
        store.edit { it[Keys.WARMUP_WAIT] = enabled }
    }

    override suspend fun setCatchupPause(enabled: Boolean) {
        store.edit { it[Keys.CATCHUP_PAUSE] = enabled }
    }

    /** PR-F (#86) — Mode C toggle. See [UiSettings.fullPrerender]. */
    override suspend fun setFullPrerender(enabled: Boolean) {
        store.edit { it[Keys.FULL_PRERENDER] = enabled }
    }

    /**
     * PCM cache PR-G (#86) — Settings UI's cache-quota picker calls
     * this. Snaps to the discrete [CacheQuotaOptions] tier, persists
     * via PcmCacheConfig (its own DataStore), then runs evictToQuota
     * so a tightened cap is honored immediately. evictToQuota is
     * idempotent on a no-change quota and a no-op when the new quota
     * is larger than current size, so the unconditional call is safe.
     *
     * Pinned set is empty: the Settings screen is in the foreground
     * which means no chapter is actively rendering on the streaming
     * tee path; the only thing that could collide is PR-F's background
     * worker (which pulls a fresh quota on next finalize anyway).
     */
    override suspend fun setCacheQuotaBytes(bytes: Long) {
        val snapped = CacheQuotaOptions.snap(bytes)
        pcmCacheConfig.setQuotaBytes(snapped)
        runCatching { pcmCache.evictToQuota() }
    }

    /**
     * PCM cache PR-G (#86) — Settings UI's "Clear cache" button calls
     * this after the destructive-action confirmation. Returns
     * bytes-freed for the optional toast and any logging; the UI also
     * re-polls CacheStatsRepository to confirm the wipe landed.
     */
    override suspend fun clearCache(): Long {
        val before = pcmCache.totalSizeBytes()
        pcmCache.clearAll()
        return before
    }

    override suspend fun setVoiceSteady(enabled: Boolean) {
        store.edit { it[Keys.VOICE_STEADY] = enabled }
    }

    // --- PlaybackBufferConfig (consumed by core-playback's EnginePlayer) ---

    override val playbackBufferChunks: Flow<Int> = store.data.map { prefs ->
        (prefs[Keys.PLAYBACK_BUFFER_CHUNKS] ?: BUFFER_DEFAULT_CHUNKS)
            .coerceIn(BUFFER_MIN_CHUNKS, BUFFER_MAX_CHUNKS)
    }

    override suspend fun currentBufferChunks(): Int = playbackBufferChunks.first()

    // --- PlaybackSkipConfig (issues #593 / #594, consumed by core-playback's PlaybackController) ---

    override val skipDistanceSec: Flow<Int> = store.data.map { prefs ->
        snapSkipDistanceSec(prefs[Keys.SKIP_DISTANCE_SEC] ?: 30)
    }

    override suspend fun currentSkipDistanceSec(): Int = skipDistanceSec.first()

    override val rewindToStartThresholdSec: Flow<Int> = store.data.map { prefs ->
        snapRewindToStartThresholdSec(prefs[Keys.REWIND_TO_START_THRESHOLD_SEC] ?: 3)
    }

    override suspend fun currentRewindToStartThresholdSec(): Int =
        rewindToStartThresholdSec.first()

    // --- SleepTimerExtendConfig (issue #595, consumed by core-playback's
    //     StoryvoxPlaybackService) ---

    override val shakeExtendMinutes: Flow<Int> = store.data.map { prefs ->
        snapSleepShakeExtendMinutes(prefs[Keys.SLEEP_SHAKE_EXTEND_MINUTES] ?: 15)
    }

    override suspend fun currentShakeExtendMinutes(): Int = shakeExtendMinutes.first()

    // --- PrerenderChapterCountConfig (issue #596, consumed by
    //     core-playback's PrerenderTriggers) ---

    override val prerenderChapterCount: Flow<Int> = store.data.map { prefs ->
        snapPrerenderChapterCount(prefs[Keys.PRERENDER_CHAPTER_COUNT] ?: 5)
    }

    override suspend fun currentPrerenderChapterCount(): Int =
        prerenderChapterCount.first()

    // --- AutoBrowserConfig (issue #598, consumed by core-playback's
    //     StoryvoxAutoBrowserService) ---

    override val itemsPerCategory: Flow<Int> = store.data.map { prefs ->
        snapAutoItemsPerCategory(prefs[Keys.AUTO_ITEMS_PER_CATEGORY] ?: 6)
    }

    override suspend fun currentItemsPerCategory(): Int = itemsPerCategory.first()

    // --- NetworkPatienceConfig (issue #597, consumed by source-* OkHttp
    //     module builders) ---

    override val patience: Flow<NetworkPatience> = store.data.map { prefs ->
        when (prefs[Keys.NETWORK_PATIENCE]) {
            "Aggressive" -> NetworkPatience.Aggressive
            "Patient" -> NetworkPatience.Patient
            else -> NetworkPatience.Default
        }
    }

    override suspend fun currentPatience(): NetworkPatience = patience.first()

    // --- PlaybackModeConfig (issue #98) ---

    override val warmupWait: Flow<Boolean> = store.data.map { it[Keys.WARMUP_WAIT] ?: false }
    override val catchupPause: Flow<Boolean> = store.data.map { it[Keys.CATCHUP_PAUSE] ?: true }
    /** PR-F (#86) — see [PlaybackModeConfig.fullPrerender] kdoc for semantics.
     *  Default false to keep the new-install footprint small. */
    override val fullPrerender: Flow<Boolean> =
        store.data.map { it[Keys.FULL_PRERENDER] ?: false }
    override suspend fun currentWarmupWait(): Boolean = warmupWait.first()
    override suspend fun currentCatchupPause(): Boolean = catchupPause.first()
    override suspend fun currentFullPrerender(): Boolean = fullPrerender.first()

    // --- VoiceTuningConfig (issue #85, consumed by core-playback's EnginePlayer) ---

    override val voiceSteady: Flow<Boolean> = store.data.map { it[Keys.VOICE_STEADY] ?: true }
    override suspend fun currentVoiceSteady(): Boolean = voiceSteady.first()

    // --- AzureFallbackConfig (#185, PR-6) ---
    // Surfaced through the same DataStore-backed flow as the other
    // playback config interfaces; EnginePlayer collects it in
    // observeAzureFallbackConfig() and snapshots into a volatile pair
    // so the synth-failure path can read without suspending.
    override val state: Flow<AzureFallbackState> = store.data.map { prefs ->
        AzureFallbackState(
            enabled = prefs[Keys.AZURE_FALLBACK_ENABLED] ?: false,
            fallbackVoiceId = prefs[Keys.AZURE_FALLBACK_VOICE_ID],
        )
    }

    override suspend fun currentAzureFallback(): AzureFallbackState = state.first()

    // --- ParallelSynthConfig (#88, Tier 3) ---
    /**
     * Read the parallel-synth instance count, migrating the pre-slider
     * Boolean key to the new Int key on the fly. The Int key wins when
     * present; the Boolean key is the legacy fallback (true→2, false→1).
     * Coerced to the supported [1..8] range so a corrupt persist or a
     * future-format value doesn't blow up the engine wiring.
     */
    private fun readParallelSynthInstances(prefs: Preferences): Int {
        val explicit = prefs[Keys.PARALLEL_SYNTH_INSTANCES]
        if (explicit != null) return explicit.coerceIn(1, 8)
        // Pre-slider migration path. Once the user touches the slider
        // we'll persist the Int key; the boolean key stays around as
        // a no-op (untouched on disk) until the next clear-data event.
        val legacy = prefs[Keys.EXPERIMENTAL_PARALLEL_SYNTH] ?: false
        return if (legacy) 2 else 1
    }

    override val parallelSynthState: Flow<ParallelSynthState> = store.data.map { prefs ->
        ParallelSynthState(
            instances = readParallelSynthInstances(prefs),
            threadsPerInstance = (prefs[Keys.SYNTH_THREADS_PER_INSTANCE] ?: 0)
                .coerceIn(0, 8),
        )
    }

    override suspend fun currentParallelSynthState(): ParallelSynthState =
        parallelSynthState.first()

    // --- PlaybackResumePolicyConfig (#90) ---
    override val lastWasPlaying: Flow<Boolean> = store.data.map { prefs ->
        prefs[Keys.LAST_WAS_PLAYING] ?: true
    }

    override suspend fun currentLastWasPlaying(): Boolean = lastWasPlaying.first()

    override suspend fun setLastWasPlaying(playing: Boolean) {
        store.edit { it[Keys.LAST_WAS_PLAYING] = playing }
    }

    override suspend fun signIn() {
        // Just flips the persisted UI flag; the cookie capture is owned by
        // AuthViewModel (it writes to AuthRepository + SessionHydrator and
        // calls signIn() here last). Keeping signIn idempotent so callers
        // can flip it without side effects on the auth store.
        store.edit { it[Keys.SIGNED_IN] = true }
    }

    override suspend fun signOut() {
        // Tear down all three stores: the encrypted cookie header in
        // AuthRepository, the live OkHttp jar via SessionHydrator, and the
        // DataStore flag that drives the Settings UI.
        auth.clearSession()
        hydrator.clear()
        store.edit { it[Keys.SIGNED_IN] = false }
    }

    // --- Memory Palace (#79) ---

    override suspend fun setPalaceHost(host: String) {
        palaceConfig.setHost(host)
    }

    override suspend fun setPalaceApiKey(apiKey: String) {
        palaceConfig.setApiKey(apiKey)
    }

    override suspend fun clearPalaceConfig() {
        palaceConfig.clear()
    }

    override suspend fun testPalaceConnection(): PalaceProbeResult {
        if (!palaceConfig.current().isConfigured) return PalaceProbeResult.NotConfigured
        return when (val r = palaceApi.health()) {
            is PalaceDaemonResult.Success -> PalaceProbeResult.Reachable(
                daemonVersion = r.value.version ?: "unknown",
            )
            is PalaceDaemonResult.HostRejected -> PalaceProbeResult.Unreachable(
                "Host '${r.host}' is not on the home network.",
            )
            is PalaceDaemonResult.Unauthorized -> PalaceProbeResult.Unreachable(
                "API key rejected by daemon.",
            )
            is PalaceDaemonResult.NotReachable -> PalaceProbeResult.Unreachable(
                r.cause.message ?: "Could not reach daemon.",
            )
            is PalaceDaemonResult.Degraded -> PalaceProbeResult.Unreachable(
                "Palace is rebuilding — try again shortly.",
            )
            is PalaceDaemonResult.HttpError -> PalaceProbeResult.Unreachable(
                "Daemon returned ${r.code}: ${r.message}",
            )
            is PalaceDaemonResult.NotFound -> PalaceProbeResult.Unreachable(
                "Daemon /health endpoint not found — is this palace-daemon?",
            )
            is PalaceDaemonResult.ParseError -> PalaceProbeResult.Unreachable(
                "Malformed health response — is this palace-daemon?",
            )
        }
    }

    // ── AI settings (issue #81) ────────────────────────────────────

    override suspend fun setAiProvider(provider: UiLlmProvider?) {
        store.edit {
            if (provider == null) it.remove(Keys.AI_PROVIDER)
            else it[Keys.AI_PROVIDER] = provider.name
        }
    }

    override suspend fun setClaudeApiKey(key: String?) {
        if (key == null) llmCreds.clearClaudeApiKey()
        else llmCreds.setClaudeApiKey(key)
    }

    override suspend fun setClaudeModel(model: String) {
        store.edit { it[Keys.AI_CLAUDE_MODEL] = model }
    }

    override suspend fun setOpenAiApiKey(key: String?) {
        if (key == null) llmCreds.clearOpenAiApiKey()
        else llmCreds.setOpenAiApiKey(key)
    }

    override suspend fun setOpenAiModel(model: String) {
        store.edit { it[Keys.AI_OPENAI_MODEL] = model }
    }

    override suspend fun setOllamaBaseUrl(url: String) {
        store.edit { it[Keys.AI_OLLAMA_BASE_URL] = url }
    }

    override suspend fun setOllamaModel(model: String) {
        store.edit { it[Keys.AI_OLLAMA_MODEL] = model }
    }

    override suspend fun setVertexApiKey(key: String?) {
        if (key == null) {
            llmCreds.clearVertexApiKey()
        } else {
            // Issue #219 — the API-key and SA-JSON modes are mutually
            // exclusive at the storage layer; setting one drops the
            // other so VertexProvider's dispatch never sees ambiguous
            // state. The SA-side invalidation also flushes the OAuth
            // token cache so an old token can't keep being used.
            llmCreds.setVertexApiKey(key)
            if (llmCreds.hasVertexServiceAccount) {
                llmCreds.clearVertexServiceAccountJson()
                googleTokenSource.invalidate()
            }
        }
    }

    override suspend fun setVertexModel(model: String) {
        store.edit { it[Keys.AI_VERTEX_MODEL] = model }
    }

    override suspend fun setVertexServiceAccountJson(json: String?) {
        // Validate-then-persist. Parsing throws IllegalArgumentException
        // with a human-readable cause on bad input; we let it propagate
        // so the SAF-picker callback can toast the message rather than
        // silently writing garbage to the encrypted prefs.
        if (json == null) {
            llmCreds.clearVertexServiceAccountJson()
            googleTokenSource.invalidate()
        } else {
            GoogleServiceAccount.parse(json)
            llmCreds.setVertexServiceAccountJson(json)
            // Mutual-exclusion: if an API key was set, drop it. Same
            // rationale as setVertexApiKey above.
            if (llmCreds.hasVertexKey) {
                llmCreds.clearVertexApiKey()
            }
            // New SA installed → old cached token is stale (matches a
            // different SA identity). Belt-and-braces; the cache also
            // self-invalidates on identity mismatch.
            googleTokenSource.invalidate()
        }
    }

    override suspend fun setFoundryApiKey(key: String?) {
        if (key == null) llmCreds.clearFoundryApiKey()
        else llmCreds.setFoundryApiKey(key)
    }

    override suspend fun setFoundryEndpoint(url: String) {
        store.edit { it[Keys.AI_FOUNDRY_ENDPOINT] = url }
    }

    override suspend fun setFoundryDeployment(deployment: String) {
        store.edit { it[Keys.AI_FOUNDRY_DEPLOYMENT] = deployment }
    }

    override suspend fun setFoundryServerless(serverless: Boolean) {
        store.edit { it[Keys.AI_FOUNDRY_SERVERLESS] = serverless }
    }

    override suspend fun setBedrockAccessKey(key: String?) {
        if (key.isNullOrBlank()) {
            // Clear access only — leave secret in place. The "Forget all"
            // path goes through resetAiSettings → llmCreds.clearAll().
            llmCreds.setBedrockKeys(
                access = "",
                secret = llmCreds.bedrockSecretKey() ?: "",
            )
            // Empty access = treated as not-configured by the UI, but we
            // need a single "remove just access" path; fall back to
            // clearing both if secret is also empty.
            if (llmCreds.bedrockSecretKey().isNullOrBlank()) {
                llmCreds.clearBedrockKeys()
            }
        } else {
            llmCreds.setBedrockKeys(
                access = key,
                secret = llmCreds.bedrockSecretKey() ?: "",
            )
        }
    }

    override suspend fun setBedrockSecretKey(key: String?) {
        if (key.isNullOrBlank()) {
            llmCreds.setBedrockKeys(
                access = llmCreds.bedrockAccessKey() ?: "",
                secret = "",
            )
            if (llmCreds.bedrockAccessKey().isNullOrBlank()) {
                llmCreds.clearBedrockKeys()
            }
        } else {
            llmCreds.setBedrockKeys(
                access = llmCreds.bedrockAccessKey() ?: "",
                secret = key,
            )
        }
    }

    override suspend fun setBedrockRegion(region: String) {
        store.edit { it[Keys.AI_BEDROCK_REGION] = region }
    }

    override suspend fun setBedrockModel(model: String) {
        store.edit { it[Keys.AI_BEDROCK_MODEL] = model }
    }

    override suspend fun setSendChapterTextEnabled(enabled: Boolean) {
        store.edit { it[Keys.AI_SEND_CHAPTER_TEXT] = enabled }
    }

    // ── Chat grounding (#212) ──────────────────────────────────────

    override suspend fun setChatGroundChapterTitle(enabled: Boolean) {
        store.edit { it[Keys.AI_CHAT_GROUND_CHAPTER_TITLE] = enabled }
    }

    override suspend fun setChatGroundCurrentSentence(enabled: Boolean) {
        store.edit { it[Keys.AI_CHAT_GROUND_CURRENT_SENTENCE] = enabled }
    }

    override suspend fun setChatGroundEntireChapter(enabled: Boolean) {
        store.edit { it[Keys.AI_CHAT_GROUND_ENTIRE_CHAPTER] = enabled }
    }

    override suspend fun setChatGroundEntireBookSoFar(enabled: Boolean) {
        store.edit { it[Keys.AI_CHAT_GROUND_ENTIRE_BOOK] = enabled }
    }

    override suspend fun setCarryMemoryAcrossFictions(enabled: Boolean) {
        store.edit { it[Keys.AI_CARRY_MEMORY_ACROSS_FICTIONS] = enabled }
    }

    override suspend fun setAiActionsEnabled(enabled: Boolean) {
        store.edit { it[Keys.AI_ACTIONS_ENABLED] = enabled }
    }

    override suspend fun acknowledgeAiPrivacy() {
        store.edit { it[Keys.AI_PRIVACY_ACK] = true }
    }

    override suspend fun signOutTeams() {
        // Local sign-out — wipes the bearer + refresh + scope cache.
        // Remote revoke at console.anthropic.com requires the
        // client_secret we don't have (public-client posture). The
        // Settings UI surfaces a deep-link to claude.ai if the user
        // wants to fully revoke the session there. Going through the
        // repo (not llmCreds directly) keeps the in-memory StateFlow
        // in sync, which is what the Settings UI subscribes to.
        teamsAuth.clearSession()
    }

    override suspend fun resetAiSettings() {
        store.edit {
            it.remove(Keys.AI_PROVIDER)
            it.remove(Keys.AI_CLAUDE_MODEL)
            it.remove(Keys.AI_OPENAI_MODEL)
            it.remove(Keys.AI_OLLAMA_BASE_URL)
            it.remove(Keys.AI_OLLAMA_MODEL)
            it.remove(Keys.AI_VERTEX_MODEL)
            it.remove(Keys.AI_FOUNDRY_ENDPOINT)
            it.remove(Keys.AI_FOUNDRY_DEPLOYMENT)
            it.remove(Keys.AI_FOUNDRY_SERVERLESS)
            it.remove(Keys.AI_BEDROCK_REGION)
            it.remove(Keys.AI_BEDROCK_MODEL)
            it.remove(Keys.AI_PRIVACY_ACK)
            it.remove(Keys.AI_SEND_CHAPTER_TEXT)
            it.remove(Keys.AI_CHAT_GROUND_CHAPTER_TITLE)
            it.remove(Keys.AI_CHAT_GROUND_CURRENT_SENTENCE)
            it.remove(Keys.AI_CHAT_GROUND_ENTIRE_CHAPTER)
            it.remove(Keys.AI_CHAT_GROUND_ENTIRE_BOOK)
        }
        llmCreds.clearAll()
        // llmCreds.clearAll() wipes the Teams encrypted-prefs entries;
        // refresh the in-memory session flow so the UI flips to
        // SignedOut without waiting for a separate emission.
        teamsAuth.refreshFromStore()
        // Same logic for the Google OAuth access-token cache (#219) —
        // the SA JSON it was minted from no longer exists on disk.
        googleTokenSource.invalidate()
    }

    // ── GitHub OAuth (#91) ─────────────────────────────────────────

    override suspend fun signOutGitHub() {
        // Remote revoke at github.com requires the client_secret we don't
        // have (public client model — see GitHubAuthConfig kdoc). Local
        // sign-out always works and clears the encrypted token + login +
        // scopes from prefs. The Settings UI surfaces the deep-link to
        // github.com/settings/applications for users who want full revoke.
        githubAuth.clearSession()
    }

    override suspend fun setGitHubPrivateReposEnabled(enabled: Boolean) {
        store.edit { it[Keys.GITHUB_PRIVATE_REPOS_ENABLED] = enabled }
    }

    override suspend fun addRssFeed(url: String) = rssConfig.addFeed(url)
    override suspend fun removeRssFeed(fictionId: String) = rssConfig.removeFeed(fictionId)
    override suspend fun removeRssFeedByUrl(url: String) {
        // Look up the fictionId for the URL via the canonical hash and
        // delete by id. Keeps the UI free of source-rss internals.
        rssConfig.removeFeed(
            `in`.jphe.storyvox.source.rss.config.fictionIdForFeedUrl(url),
        )
    }
    override val rssSubscriptions: kotlinx.coroutines.flow.Flow<List<String>> =
        rssConfig.subscriptions.map { subs -> subs.map { it.url } }

    override val epubFolderUri: kotlinx.coroutines.flow.Flow<String?> = epubConfig.folderUriString
    override suspend fun setEpubFolderUri(uri: String) = epubConfig.setFolder(uri)
    override suspend fun clearEpubFolder() = epubConfig.clearFolder()

    override val outlineHost: kotlinx.coroutines.flow.Flow<String> =
        outlineConfig.state.map { it.host }
    override suspend fun setOutlineHost(host: String) = outlineConfig.setHost(host)
    override suspend fun setOutlineApiKey(apiKey: String) = outlineConfig.setApiKey(apiKey)
    override suspend fun clearOutlineConfig() = outlineConfig.clear()

    override suspend fun setWikipediaLanguageCode(code: String) =
        wikipediaConfig.setLanguageCode(code)

    override suspend fun setDiscordApiToken(token: String?) {
        discordConfig.setApiToken(token)
    }

    override suspend fun setDiscordServer(serverId: String, serverName: String) {
        discordConfig.setServer(serverId, serverName)
    }

    override suspend fun setDiscordCoalesceMinutes(minutes: Int) {
        discordConfig.setCoalesceMinutes(minutes)
    }

    override suspend fun fetchDiscordGuilds(): List<Pair<String, String>> =
        // Delegates to the DiscordGuildDirectory in :source-discord;
        // that thin wrapper exposes the internal DiscordApi's
        // listGuilds() through a public surface so :app doesn't reach
        // into the source's internal wire types directly. On any
        // failure (no token, network, 401/403) the directory returns
        // an empty list — the UI handles "empty picker" as the
        // unified empty-state across the three failure modes.
        discordGuildDirectory.listGuilds()

    override suspend fun setTelegramApiToken(token: String?) {
        telegramConfig.setApiToken(token)
    }

    override suspend fun probeTelegramBot(): String? =
        // Delegates to TelegramChannelDirectory in :source-telegram.
        // Returns the bot's @username (or first_name) on success,
        // null on any failure (no token, bad token, network out) —
        // the UI handles "null" as the unified failure state across
        // those modes.
        telegramChannelDirectory.authenticate()

    override suspend fun fetchTelegramChannels(): List<Pair<String, String>> =
        // Delegates to TelegramChannelDirectory's `getUpdates` +
        // per-channel `getChat` probe. Returns
        // (chatId-as-string, displayTitle) pairs — empty on any
        // failure or when no channels have been observed since the
        // bot joined.
        telegramChannelDirectory.listChannels()

    /**
     * Plugin-seam Phase 3 (#384) — registry-driven entry point and
     * single source of truth as of v0.5.31. Writes the per-plugin map;
     * the legacy `pref_source_xxx_enabled` boolean keys are kept on
     * disk for the one-shot [SourcePluginsMapMigration] seed but no
     * longer dual-written here. The Phase 1/2 dual-write that mirrored
     * each toggle into the legacy boolean is gone — there's only one
     * shape now.
     */
    override suspend fun setSourcePluginEnabled(id: String, enabled: Boolean) {
        store.edit { prefs ->
            val current = `in`.jphe.storyvox.data.source.plugin.decodeSourcePluginsEnabledJson(
                prefs[Keys.SOURCE_PLUGINS_ENABLED_JSON],
            ).toMutableMap()
            current[id] = enabled
            prefs[Keys.SOURCE_PLUGINS_ENABLED_JSON] =
                `in`.jphe.storyvox.data.source.plugin.encodeSourcePluginsEnabledJson(current)
        }
    }

    /**
     * v0.5.76 — toggle a source plugin's "favorite" star. Read-modify-
     * write the persisted Set so concurrent toggles for *different*
     * ids in flight at the same DataStore tick don't clobber each
     * other (DataStore serializes the edit block, so reading inside
     * is safe). Unknown ids round-trip into the set without
     * validation — same posture as [setSourcePluginEnabled].
     *
     * Idempotent: a no-op favorite (re-add of an already-favorited id,
     * or remove of one that's not present) skips the JSON write.
     * [stampSyncedWrite] still fires unconditionally so a deliberate
     * "touch" by the user after a sync conflict still pushes their
     * intent forward. The key is in [SYNC_ALLOWLIST] + [SYNC_KEY_TYPES]
     * so favorites ride the next InstantDB sync round to the user's
     * other devices — cross-device intent ("AO3 is my main source") is
     * the kind of preference users want mirrored everywhere.
     */
    override suspend fun setSourceFavorite(id: String, favorite: Boolean) {
        store.edit { prefs ->
            val current = `in`.jphe.storyvox.data.source.plugin.decodeSourceFavoritesJson(
                prefs[Keys.SOURCE_FAVORITES_JSON],
            ).toMutableSet()
            val changed = if (favorite) current.add(id) else current.remove(id)
            if (changed) {
                prefs[Keys.SOURCE_FAVORITES_JSON] =
                    `in`.jphe.storyvox.data.source.plugin.encodeSourceFavoritesJson(current)
            }
        }
        stampSyncedWrite()
    }

    /**
     * Plugin-seam Phase 4 (#501) — twin of [setSourcePluginEnabled]
     * for the voice family map. Single setter for the Plugin Manager's
     * Voice bundles section; absent ids fall back to each family's
     * `defaultEnabled` in `VoiceFamilyRegistry`.
     */
    override suspend fun setVoiceFamilyEnabled(id: String, enabled: Boolean) {
        store.edit { prefs ->
            val current = `in`.jphe.storyvox.data.source.plugin.decodeVoiceFamiliesEnabledJson(
                prefs[Keys.VOICE_FAMILIES_ENABLED_JSON],
            ).toMutableMap()
            current[id] = enabled
            prefs[Keys.VOICE_FAMILIES_ENABLED_JSON] =
                `in`.jphe.storyvox.data.source.plugin.encodeVoiceFamiliesEnabledJson(current)
        }
    }
    override suspend fun setNotionDatabaseId(id: String) {
        notionConfig.setDatabaseId(id)
    }
    override suspend fun setNotionApiToken(token: String?) {
        notionConfig.setApiToken(token)
    }

    /** #246 — bridge to SuggestedFeedsRegistry. The fallback list
     *  passed in is the baked-in seed; the registry emits it
     *  immediately, then re-emits with the remote list once the
     *  fetch resolves. */
    override val suggestedRssFeeds: kotlinx.coroutines.flow.Flow<List<`in`.jphe.storyvox.feature.api.SuggestedFeed>> =
        suggestedFeedsRegistry.observe(
            // Seed list lives in feature/settings module; the impl
            // reaches across to read it. If the feature dependency
            // direction ever inverts, the seed moves to :app instead.
            fallback = `in`.jphe.storyvox.feature.settings.BAKED_IN_SUGGESTED_FEEDS,
        )

    override suspend fun setSleepShakeToExtendEnabled(enabled: Boolean) {
        store.edit { it[Keys.SLEEP_SHAKE_TO_EXTEND_ENABLED] = enabled }
    }

    override suspend fun setShowDebugOverlay(enabled: Boolean) {
        store.edit { it[Keys.SHOW_DEBUG_OVERLAY] = enabled }
    }

    // ── Accessibility scaffold (Phase 1, v0.5.42) ──────────────────
    // Every setter also stamps a synced-write so the next InstantDB
    // sync round carries the new value to the user's other devices —
    // accessibility intent is the kind of preference users want
    // mirrored everywhere they've signed in.
    override suspend fun setA11yHighContrast(enabled: Boolean) {
        store.edit { it[Keys.A11Y_HIGH_CONTRAST] = enabled }
        stampSyncedWrite()
    }

    override suspend fun setA11yReducedMotion(enabled: Boolean) {
        store.edit { it[Keys.A11Y_REDUCED_MOTION] = enabled }
        stampSyncedWrite()
    }

    override suspend fun setA11yLargerTouchTargets(enabled: Boolean) {
        store.edit { it[Keys.A11Y_LARGER_TOUCH_TARGETS] = enabled }
        stampSyncedWrite()
    }

    override suspend fun setA11yScreenReaderPauseMs(ms: Int) {
        store.edit { it[Keys.A11Y_SCREEN_READER_PAUSE_MS] = ms.coerceIn(0, 1500) }
        stampSyncedWrite()
    }

    override suspend fun setA11ySpeakChapterMode(mode: SpeakChapterMode) {
        store.edit { it[Keys.A11Y_SPEAK_CHAPTER_MODE] = mode.name }
        stampSyncedWrite()
    }

    override suspend fun setA11yFontScaleOverride(scale: Float) {
        store.edit { it[Keys.A11Y_FONT_SCALE_OVERRIDE] = scale.coerceIn(0.85f, 1.5f) }
        stampSyncedWrite()
    }

    override suspend fun setA11yReadingDirection(direction: ReadingDirection) {
        store.edit { it[Keys.A11Y_READING_DIRECTION] = direction.name }
        stampSyncedWrite()
    }

    override suspend fun setA11yTalkBackNudgeDismissed(dismissed: Boolean) {
        // #488 — one-way flip. The TalkBack nudge is a one-shot
        // affordance, so the setter is named "dismissed" rather than
        // "show" to make the call sites self-documenting at the use
        // site (`setA11yTalkBackNudgeDismissed(true)` reads as "user
        // dismissed the card").
        store.edit { it[Keys.A11Y_TALKBACK_NUDGE_DISMISSED] = dismissed }
        // Deliberately NOT stamped via [stampSyncedWrite]: nudge
        // dismissal is per-install UX state and doesn't need to
        // sync across the user's devices.
    }

    // ── Appearance (v0.5.59) ───────────────────────────────────────

    /**
     * Persist the user's book-cover fallback preference. Stamped via
     * [stampSyncedWrite] so the choice rides the next InstantDB sync
     * round to the user's other devices — cover preference is the
     * kind of visual-style intent users want mirrored everywhere
     * they've signed in (same logic as `pref_theme_override`).
     */
    override suspend fun setCoverStyle(style: CoverStyle) {
        store.edit { it[Keys.COVER_STYLE] = style.name }
        stampSyncedWrite()
    }

    /**
     * Issue #589 — animation-speed master multiplier. Per-device, NOT
     * synced (different ergonomic targets per device). Snap to the
     * supported chip values on write so the persisted state always
     * matches a UI tier.
     */
    override suspend fun setAnimationSpeedScale(scale: Float) {
        store.edit { it[Keys.ANIMATION_SPEED_SCALE] = snapAnimationSpeedScale(scale) }
    }

    /**
     * Issue #593 — skip distance in seconds. Per-device, NOT synced.
     */
    override suspend fun setSkipDistanceSec(seconds: Int) {
        store.edit { it[Keys.SKIP_DISTANCE_SEC] = snapSkipDistanceSec(seconds) }
    }

    /**
     * Issue #594 — rewind-to-start threshold for SkipPrevious, in
     * seconds. 0 = disable. Per-device, NOT synced.
     */
    override suspend fun setRewindToStartThresholdSec(seconds: Int) {
        store.edit { it[Keys.REWIND_TO_START_THRESHOLD_SEC] = snapRewindToStartThresholdSec(seconds) }
    }

    /** Issue #595 — sleep-timer shake-to-extend in minutes. Per-device. */
    override suspend fun setSleepShakeExtendMinutes(minutes: Int) {
        store.edit {
            it[Keys.SLEEP_SHAKE_EXTEND_MINUTES] = snapSleepShakeExtendMinutes(minutes)
        }
    }

    /** Issue #596 — PCM-cache pre-render window in chapters. Per-device. */
    override suspend fun setPrerenderChapterCount(count: Int) {
        store.edit {
            it[Keys.PRERENDER_CHAPTER_COUNT] = snapPrerenderChapterCount(count)
        }
    }

    /** Issue #590 — particle / confetti intensity. Per-device. */
    override suspend fun setParticleIntensity(intensity: UiParticleIntensity) {
        store.edit { it[Keys.PARTICLE_INTENSITY] = intensity.name }
    }

    /** Issue #591 — skeleton shimmer style. Per-device. */
    override suspend fun setSkeletonStyle(style: UiSkeletonStyle) {
        store.edit { it[Keys.SKELETON_STYLE] = style.name }
    }

    /** Issue #592 — brass alpha-pulse intensity. Per-device. */
    override suspend fun setBrassPulseLevel(level: UiBrassPulseLevel) {
        store.edit { it[Keys.BRASS_PULSE_LEVEL] = level.name }
    }

    /** Issue #597 — network patience preset. Per-device. */
    override suspend fun setNetworkPatience(patience: UiNetworkPatience) {
        store.edit { it[Keys.NETWORK_PATIENCE] = patience.name }
    }

    /** Issue #598 — Android Auto bucket size. Per-device. */
    override suspend fun setAutoItemsPerCategory(count: Int) {
        store.edit {
            it[Keys.AUTO_ITEMS_PER_CATEGORY] = snapAutoItemsPerCategory(count)
        }
    }

    // ── Azure Speech Services BYOK (#182, PR-3) ────────────────────

    override suspend fun setAzureKey(key: String?) {
        // Don't also wipe the region on a key-only clear — the user may
        // want to re-paste the same region's key. clearAzureCredentials()
        // is the explicit wipe-both surface. The bug fixed in v0.4.84:
        // pre-fix this called azureCreds.clear() (which wiped both),
        // contradicting the comment and silently bouncing region back
        // to the default mid-paste, so a CTRL+A+DELETE during a UI
        // re-key flow lost the user's region selection.
        if (key.isNullOrBlank()) azureCreds.clearKey() else azureCreds.setKey(key.trim())
        azureTick.value = azureTick.value + 1
        // Live roster needs a refresh: a new key may unlock a different
        // set of voices than the previous one (different Azure resource,
        // different SKU, different regional rollout). Async so the
        // settings setter returns immediately for snappy UI feedback.
        azureRoster.refreshAsync()
    }

    override suspend fun setAzureRegion(regionId: String) {
        azureCreds.setRegion(regionId)
        azureTick.value = azureTick.value + 1
        // Region change → new roster. eastus carries Dragon HD, westus
        // doesn't, etc. Async refresh keeps the picker live.
        azureRoster.refreshAsync()
    }

    override suspend fun clearAzureCredentials() {
        azureCreds.clear()
        azureTick.value = azureTick.value + 1
        // Force the roster to re-evaluate — refresh() will see no key
        // and clear the cached voice list, collapsing the picker's
        // Azure section back to "Configure key" empty state.
        azureRoster.refreshAsync()
    }

    override suspend fun setAzureFallbackEnabled(enabled: Boolean) {
        store.edit { it[Keys.AZURE_FALLBACK_ENABLED] = enabled }
    }

    override suspend fun setAzureFallbackVoiceId(voiceId: String?) {
        store.edit {
            if (voiceId == null) it.remove(Keys.AZURE_FALLBACK_VOICE_ID)
            else it[Keys.AZURE_FALLBACK_VOICE_ID] = voiceId
        }
    }

    override suspend fun setParallelSynthInstances(count: Int) {
        val coerced = count.coerceIn(1, 8)
        store.edit {
            it[Keys.PARALLEL_SYNTH_INSTANCES] = coerced
            // Legacy boolean key — keep it in sync so a user who
            // downgrades to a pre-slider build sees a sensible value.
            // (true if count >= 2, false otherwise.)
            it[Keys.EXPERIMENTAL_PARALLEL_SYNTH] = coerced >= 2
        }
    }

    override suspend fun setSynthThreadsPerInstance(count: Int) {
        store.edit { it[Keys.SYNTH_THREADS_PER_INSTANCE] = count.coerceIn(0, 8) }
    }

    override suspend fun testAzureConnection(): AzureProbeResult {
        if (!azureCreds.isConfigured) return AzureProbeResult.NotConfigured
        // voicesList() is blocking OkHttp work — push to IO so the
        // suspend-fun signature isn't a lie. Settings VM already
        // launches this on viewModelScope, but a blocking JVM thread
        // off the main dispatcher is the wrong shape for a "suspend"
        // contract — withContext(IO) makes it cancellable + correct.
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val count = azureClient.voicesList()
                AzureProbeResult.Reachable(voiceCount = count)
            } catch (e: AzureError.AuthFailed) {
                AzureProbeResult.AuthFailed(
                    e.message ?: "Azure rejected the subscription key.",
                )
            } catch (e: AzureError.NetworkError) {
                AzureProbeResult.Unreachable(
                    e.message ?: "Network error reaching Azure.",
                )
            } catch (e: AzureError) {
                AzureProbeResult.Unreachable(
                    e.message ?: "Azure returned an unexpected error.",
                )
            }
        }
    }

    /**
     * Issue #203 — [GitHubScopePreferences] surface for the
     * `GitHubSignInViewModel`. Read at the moment Device Flow starts so
     * a freshly-flipped toggle takes effect on the next sign-in
     * attempt. Defaults false to match the least-privilege baseline.
     */
    override suspend fun privateReposEnabled(): Boolean =
        store.data.map { it[Keys.GITHUB_PRIVATE_REPOS_ENABLED] ?: false }.first()

    // ── PronunciationDictRepository (issue #135) ───────────────────

    /**
     * Live flow of the user's pronunciation dictionary. Decodes the
     * stored JSON on every emission; an empty / missing / unparseable
     * value falls back to [PronunciationDict.EMPTY] so the engine
     * pipeline always has a usable substitution lambda.
     *
     * Decode failures are silent on purpose — a corrupted blob (e.g.
     * a future-format payload an older binary can't read) shouldn't
     * crash the player; the user sees their dictionary "reset" and
     * can re-enter it. The corrupt blob stays on disk until the next
     * write (we don't actively wipe it on read), which preserves the
     * forward-roll case where the user downgrades, sees the dict
     * empty, then upgrades and gets it back.
     */
    override val dict: Flow<PronunciationDict> = store.data.map { prefs ->
        decodePronunciationDictJson(prefs[Keys.PRONUNCIATION_DICT])
    }

    override suspend fun current(): PronunciationDict = dict.first()

    override suspend fun add(entry: PronunciationEntry) {
        store.edit { prefs ->
            val current = decodePronunciationDictJson(prefs[Keys.PRONUNCIATION_DICT])
            val next = current.copy(entries = current.entries + entry)
            prefs[Keys.PRONUNCIATION_DICT] = encodePronunciationDictJson(next)
        }
    }

    override suspend fun update(index: Int, entry: PronunciationEntry) {
        store.edit { prefs ->
            val current = decodePronunciationDictJson(prefs[Keys.PRONUNCIATION_DICT])
            if (index !in current.entries.indices) return@edit
            val newList = current.entries.toMutableList().apply { this[index] = entry }
            prefs[Keys.PRONUNCIATION_DICT] = encodePronunciationDictJson(current.copy(entries = newList))
        }
    }

    override suspend fun delete(index: Int) {
        store.edit { prefs ->
            val current = decodePronunciationDictJson(prefs[Keys.PRONUNCIATION_DICT])
            if (index !in current.entries.indices) return@edit
            val newList = current.entries.toMutableList().apply { removeAt(index) }
            prefs[Keys.PRONUNCIATION_DICT] = encodePronunciationDictJson(current.copy(entries = newList))
        }
    }

    override suspend fun replaceAll(dict: PronunciationDict) {
        store.edit { it[Keys.PRONUNCIATION_DICT] = encodePronunciationDictJson(dict) }
    }

    // ── LlmConfigProvider — bridge to :core-llm ────────────────────

    override val config: Flow<LlmConfig> = store.data.map { prefs ->
        LlmConfig(
            provider = prefs[Keys.AI_PROVIDER]
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { ProviderId.valueOf(it) }.getOrNull() },
            claudeModel = prefs[Keys.AI_CLAUDE_MODEL] ?: "claude-haiku-4.5",
            openAiModel = prefs[Keys.AI_OPENAI_MODEL] ?: "gpt-4o-mini",
            // Issue #294 — mirror the UiSettings fallbacks at the
            // LlmConfig export site so both surfaces report the same
            // first-time defaults.
            ollamaBaseUrl = prefs[Keys.AI_OLLAMA_BASE_URL] ?: "",
            ollamaModel = prefs[Keys.AI_OLLAMA_MODEL] ?: "llama3.2:3b",
            vertexModel = prefs[Keys.AI_VERTEX_MODEL] ?: "gemini-2.5-flash",
            foundryEndpoint = prefs[Keys.AI_FOUNDRY_ENDPOINT] ?: "",
            foundryDeployment = prefs[Keys.AI_FOUNDRY_DEPLOYMENT] ?: "",
            foundryServerless = prefs[Keys.AI_FOUNDRY_SERVERLESS] ?: false,
            bedrockRegion = prefs[Keys.AI_BEDROCK_REGION] ?: "us-east-1",
            bedrockModel = prefs[Keys.AI_BEDROCK_MODEL] ?: "claude-haiku-4.5",
            privacyAcknowledged = prefs[Keys.AI_PRIVACY_ACK] ?: false,
            sendChapterTextEnabled = prefs[Keys.AI_SEND_CHAPTER_TEXT] ?: false,
            // Issue #216 — default ON, matches the UiAiSettings
            // mirror above.
            aiActionsEnabled = prefs[Keys.AI_ACTIONS_ENABLED] ?: true,
        )
    }

    override suspend fun setConfig(config: LlmConfig) {
        // Bulk write — used by tests / one-shot reset paths. The
        // narrow setters above are the day-to-day path.
        val providerName: String? = config.provider?.name
        store.edit { p ->
            if (providerName == null) p.remove(Keys.AI_PROVIDER)
            else p[Keys.AI_PROVIDER] = providerName
            p[Keys.AI_CLAUDE_MODEL] = config.claudeModel
            p[Keys.AI_OPENAI_MODEL] = config.openAiModel
            p[Keys.AI_OLLAMA_BASE_URL] = config.ollamaBaseUrl
            p[Keys.AI_OLLAMA_MODEL] = config.ollamaModel
            p[Keys.AI_VERTEX_MODEL] = config.vertexModel
            p[Keys.AI_FOUNDRY_ENDPOINT] = config.foundryEndpoint
            p[Keys.AI_FOUNDRY_DEPLOYMENT] = config.foundryDeployment
            p[Keys.AI_FOUNDRY_SERVERLESS] = config.foundryServerless
            p[Keys.AI_BEDROCK_REGION] = config.bedrockRegion
            p[Keys.AI_BEDROCK_MODEL] = config.bedrockModel
            p[Keys.AI_PRIVACY_ACK] = config.privacyAcknowledged
            p[Keys.AI_SEND_CHAPTER_TEXT] = config.sendChapterTextEnabled
            p[Keys.AI_ACTIONS_ENABLED] = config.aiActionsEnabled
        }
    }

    // ── Calliope (v0.5.00) milestone celebration ──────────────────
    /** [Milestone.isV0500OrLater] is a process-lifetime constant —
     *  it's pinned to the build's VERSION_NAME, which doesn't change
     *  while the app is running. We still emit through a Flow so the
     *  UI's collect cadence matches the persisted-flag stream and the
     *  dialog's gating is a single combine source. */
    override val milestoneState: Flow<`in`.jphe.storyvox.feature.api.MilestoneState> =
        store.data.map { prefs ->
            `in`.jphe.storyvox.feature.api.MilestoneState(
                qualifies = `in`.jphe.storyvox.sigil.Milestone.isV0500OrLater,
                dialogSeen = prefs[Keys.V0500_MILESTONE_SEEN] ?: false,
                confettiShown = prefs[Keys.V0500_CONFETTI_SHOWN] ?: false,
            )
        }

    override suspend fun markMilestoneDialogSeen() {
        store.edit { it[Keys.V0500_MILESTONE_SEEN] = true }
    }

    override suspend fun markMilestoneConfettiShown() {
        store.edit { it[Keys.V0500_CONFETTI_SHOWN] = true }
    }

    // ── Issue #383 — Inbox per-source mute toggles ─────────────────
    // Added at the END of the class body so the diff stays away from
    // other agents touching this file (Vertex SA, plugin seam).
    override suspend fun setInboxNotifyRoyalRoad(enabled: Boolean) {
        store.edit { it[Keys.INBOX_NOTIFY_ROYALROAD] = enabled }
        stampSyncedWrite()
    }
    override suspend fun setInboxNotifyKvmr(enabled: Boolean) {
        store.edit { it[Keys.INBOX_NOTIFY_KVMR] = enabled }
        stampSyncedWrite()
    }
    override suspend fun setInboxNotifyWikipedia(enabled: Boolean) {
        store.edit { it[Keys.INBOX_NOTIFY_WIKIPEDIA] = enabled }
        stampSyncedWrite()
    }

    // ── Issue #500 — magical InstantDB sign-in onboarding ──────────
    /** Read the dismissed flag. False until the user explicitly
     *  dismisses or completes the first-launch sync onboarding card,
     *  then true forever (for the life of this install — uninstall /
     *  data-clear resets along with everything else). */
    override val syncOnboardingDismissed: Flow<Boolean> =
        store.data.map { it[Keys.SYNC_ONBOARDING_DISMISSED] ?: false }

    /** Flip the flag. Intentionally not synced via [stampSyncedWrite] —
     *  the onboarding gate is a per-device first-launch experience, not
     *  user-level state. A user signing in on a new device should see
     *  the onboarding offer (their existing data will pull down) rather
     *  than have the "you already dismissed this" flag inherited and
     *  miss the welcome moment. */
    override suspend fun markSyncOnboardingDismissed() {
        store.edit { it[Keys.SYNC_ONBOARDING_DISMISSED] = true }
    }

    // ── Issue #599 — v1.0 first-launch onboarding flow ─────────────
    /** Default false until the user finishes the welcome flow or taps
     *  "I've used storyvox before". Once true, stays true for the life
     *  of this install (unless reset for testing). */
    override val onboardingCompletedV1: Flow<Boolean> =
        store.data.map { it[Keys.ONBOARDING_COMPLETED_V1] ?: false }

    /** Flip to true. Idempotent; called from the third onboarding
     *  screen's "Skip — show me everything" tap, from the "Browse
     *  TechEmpower's free guides" tap, and from the "I've used storyvox
     *  before" skip on Welcome. The picker may still appear afterward
     *  (the VoicePickerGate has its own dismissed flag) — but the
     *  three-screen welcome is one-shot. */
    override suspend fun markOnboardingCompletedV1() {
        store.edit { it[Keys.ONBOARDING_COMPLETED_V1] = true }
    }

    /** Flip back to false — exposed via Settings → Advanced → "Reset
     *  onboarding" so JP / QA can re-experience the welcome without
     *  clearing app data. Also clears the VoicePickerGate's dismissed
     *  flag so the gate doesn't immediately suppress its own prompt
     *  after the welcome runs again — both surfaces need to be armed
     *  for a true "first-launch dress rehearsal". */
    override suspend fun resetOnboardingV1() {
        store.edit { it[Keys.ONBOARDING_COMPLETED_V1] = false }
    }

    // ── InstantDB settings sync (this PR) ──────────────────────────
    /**
     * Snapshot/apply seam consumed by `:core-sync`'s `SettingsSyncer`.
     * Round-trips every key in [SYNC_ALLOWLIST] through a flat
     * `Map<String, String>` JSON blob plus the per-backend
     * non-secret config keys (Wikipedia / Notion / Discord /
     * Outline / Memory Palace hosts and ids). See the kdoc on
     * [SettingsSnapshotSource] and the PR description for the
     * design.
     *
     * The wire format keys are namespaced so a Wikipedia language
     * code never collides with a main-store preference key:
     *  - `settings.<key>` — main `storyvox_settings` DataStore
     *  - `notion.<key>`   — `storyvox_notion` DataStore (id +
     *                       root-page id; NOT the integration
     *                       token, that's in the encrypted bundle)
     *  - `discord.<key>`  — `storyvox_discord` DataStore (server id
     *                       + name + coalesce minutes; NOT the bot
     *                       token)
     *  - `outline.<key>`  — `storyvox_outline` DataStore (host;
     *                       NOT the API key)
     *  - `wikipedia.<key>` — `storyvox_wikipedia` DataStore
     *                        (language code)
     *  - `palace.<key>`    — `storyvox_palace` DataStore (host;
     *                        NOT the API key)
     */
    override suspend fun snapshot(): Map<String, String> {
        val out = mutableMapOf<String, String>()
        // 1. Main settings DataStore — only allowlisted keys.
        val prefs = store.data.first()
        for ((key, value) in prefs.asMap()) {
            val keyName = key.name
            if (keyName !in SYNC_ALLOWLIST) continue
            // `asMap()` values are non-null by contract — DataStore
            // doesn't store null values — so a direct toString() is
            // safe.
            out["settings.$keyName"] = value.toString()
        }
        // 2. Per-backend non-secret config. Read via each impl's
        //    `current()` — pulls the latest committed state without
        //    needing to know each impl's internal DataStore keys.
        val notion = notionConfig.current()
        if (notion.databaseId.isNotEmpty()) out["notion.pref_notion_database_id"] = notion.databaseId
        if (notion.rootPageId.isNotEmpty()) out["notion.pref_notion_root_page_id"] = notion.rootPageId

        val discord = discordConfig.current()
        if (discord.serverId.isNotEmpty()) out["discord.pref_discord_server_id"] = discord.serverId
        if (discord.serverName.isNotEmpty()) out["discord.pref_discord_server_name"] = discord.serverName
        out["discord.pref_discord_coalesce_minutes"] = discord.coalesceMinutes.toString()

        val outline = outlineConfig.current()
        if (outline.host.isNotEmpty()) out["outline.pref_outline_host"] = outline.host

        val wiki = wikipediaConfig.current()
        if (wiki.languageCode.isNotEmpty()) out["wikipedia.pref_wikipedia_language_code"] = wiki.languageCode

        val palace = palaceConfig.current()
        if (palace.host.isNotEmpty()) out["palace.pref_palace_host"] = palace.host

        return out
    }

    override suspend fun apply(snapshot: Map<String, String>) {
        // 1. Main settings DataStore.
        val mainSlice = snapshot.filterKeys { it.startsWith("settings.") }
            .mapKeys { (k, _) -> k.removePrefix("settings.") }
        if (mainSlice.isNotEmpty()) {
            applyToMainStore(mainSlice)
        }
        // 2. Per-backend stores. Each block tolerates absent keys —
        //    a remote that only set one field doesn't blow away the
        //    others on this device.
        snapshot["notion.pref_notion_database_id"]?.let { notionConfig.setDatabaseId(it) }
        snapshot["notion.pref_notion_root_page_id"]?.let { notionConfig.setRootPageId(it) }
        // Discord's setServer is paired (id + name); call only when both present.
        val dServerId = snapshot["discord.pref_discord_server_id"]
        val dServerName = snapshot["discord.pref_discord_server_name"]
        if (dServerId != null && dServerName != null) {
            discordConfig.setServer(dServerId, dServerName)
        }
        snapshot["discord.pref_discord_coalesce_minutes"]?.toIntOrNull()?.let {
            discordConfig.setCoalesceMinutes(it)
        }
        snapshot["outline.pref_outline_host"]?.let { outlineConfig.setHost(it) }
        snapshot["wikipedia.pref_wikipedia_language_code"]?.let { wikipediaConfig.setLanguageCode(it) }
        snapshot["palace.pref_palace_host"]?.let { palaceConfig.setHost(it) }
        // After applying, mark this as a fresh local write so the
        // next sync round push carries the merged-blob timestamp.
        stampSyncedWrite()
    }

    /**
     * Apply the main-store slice. Each key's preferred type is
     * inferred from [Keys] via [parseAndPut]; ill-typed values are
     * dropped (tolerate forward-compat).
     */
    private suspend fun applyToMainStore(slice: Map<String, String>) {
        store.edit { editor ->
            for ((keyName, value) in slice) {
                parseAndPut(editor, keyName, value)
            }
        }
    }

    /**
     * Type-aware put: looks up [keyName] in the static
     * [SYNC_KEY_TYPES] table and applies the corresponding
     * `*PreferencesKey` write. Unknown keys are silently dropped
     * (forward-compat); type-coercion failures (e.g. "not a float")
     * are dropped (don't crash on a malformed remote).
     */
    private fun parseAndPut(editor: androidx.datastore.preferences.core.MutablePreferences, keyName: String, value: String) {
        val type = SYNC_KEY_TYPES[keyName] ?: return
        runCatching {
            when (type) {
                SyncedType.BOOLEAN -> editor[booleanPreferencesKey(keyName)] = value.toBooleanStrict()
                SyncedType.INT -> editor[intPreferencesKey(keyName)] = value.toInt()
                SyncedType.FLOAT -> editor[floatPreferencesKey(keyName)] = value.toFloat()
                SyncedType.STRING -> editor[stringPreferencesKey(keyName)] = value
                SyncedType.LONG -> editor[longPreferencesKey(keyName)] = value.toLong()
            }
        }
    }

    override suspend fun lastLocalWriteAt(): Long =
        store.data.first()[SYNC_LAST_WRITE_KEY] ?: 0L

    override suspend fun stampLocalWrite(at: Long) {
        store.edit { it[SYNC_LAST_WRITE_KEY] = at }
    }

    /** Convenience used by every setter on this class so writes to a
     *  synced key automatically advance the sync timestamp. Settings UI
     *  that calls one of the existing `set*` mutators will get its
     *  change uploaded on the next sync round without any additional
     *  wiring. */
    private suspend fun stampSyncedWrite() {
        stampLocalWrite(System.currentTimeMillis())
    }

    companion object {
        /** Synced-keys timestamp — internal to this file, not in
         *  [SYNC_ALLOWLIST] (we never sync the sync timestamp itself
         *  — that'd be a loop). */
        private val SYNC_LAST_WRITE_KEY = longPreferencesKey("instantdb.settings_synced_at")

        /**
         * Names of every DataStore key in the main `storyvox_settings`
         * store that round-trips through InstantDB settings sync.
         *
         * Explicit allowlist — adding a key here AND a type entry in
         * [SYNC_KEY_TYPES] is what gates a preference for sync. The
         * two tables must stay in lockstep; see
         * `SettingsRepositoryUiImplSnapshotTest` in `:app` for the
         * test that enforces it.
         *
         * Excluded by design (see PR description / design-decisions.md):
         *  - `pref_signed_in`, `pref_last_was_playing` — device-local
         *    auth/playback state
         *  - `pref_v0500_milestone_seen`, `pref_v0500_confetti_shown`
         *    — one-time device-local dialog gates
         *  - `pref_pronunciation_dict_v1` — handled by
         *    `PronunciationDictSyncer`
         */
        internal val SYNC_ALLOWLIST: Set<String> = setOf(
            // Theme / playback knobs.
            "pref_theme_override",
            "pref_default_speed",
            "pref_default_pitch",
            "pref_default_voice_id",
            "pref_voice_speed_overrides",
            "pref_voice_pitch_overrides",
            "pref_voice_lexicon_overrides",
            "pref_voice_phonemizer_lang_overrides",
            "pref_punctuation_pause_multiplier_v2",
            "pref_pitch_interp_high_quality",
            "pref_voice_steady_v1",
            "pref_warmup_wait_v1",
            "pref_catchup_pause_v1",
            // PR-F (#86) — Mode C (full pre-render) flips between
            // "render N+1/N+2 only" and "render every chapter". User
            // intent, not device-specific, so sync it. Cache quota is
            // intentionally NOT synced (device-local — storage capacity
            // varies between a 32 GB phone and a 256 GB tablet).
            "pref_full_prerender_v1",
            "pref_playback_buffer_chunks_v1",
            // Parallel synth.
            "pref_experimental_parallel_synth",
            "pref_parallel_synth_instances",
            "pref_synth_threads_per_instance",
            // Download / poll.
            "pref_download_wifi_only",
            "pref_poll_interval_hours",
            // AI / LLM (config — secrets live in the encrypted bundle).
            "pref_ai_provider",
            "pref_ai_claude_model",
            "pref_ai_openai_model",
            "pref_ai_ollama_base_url",
            "pref_ai_ollama_model",
            "pref_ai_vertex_model",
            "pref_ai_foundry_endpoint",
            "pref_ai_foundry_deployment",
            "pref_ai_foundry_serverless",
            "pref_ai_privacy_ack",
            "pref_ai_send_chapter_text",
            "pref_ai_bedrock_region",
            "pref_ai_bedrock_model",
            "pref_ai_chat_ground_chapter_title",
            "pref_ai_chat_ground_current_sentence",
            "pref_ai_chat_ground_entire_chapter",
            "pref_ai_chat_ground_entire_book",
            "pref_ai_carry_memory_across_fictions",
            "pref_ai_actions_enabled",
            // GitHub scope.
            "pref_github_private_repos_enabled_v1",
            // Per-source enabled toggles (legacy boolean keys + the
            // collapsed JSON map). Both shapes are synced because the
            // plugin-seam Phase 3 rollout keeps them coexisting.
            "pref_source_royalroad_enabled",
            "pref_source_github_enabled",
            "pref_source_mempalace_enabled",
            "pref_source_rss_enabled",
            "pref_source_epub_enabled",
            "pref_source_outline_enabled",
            "pref_source_gutenberg_enabled",
            "pref_source_ao3_enabled",
            "pref_source_standard_ebooks_enabled",
            "pref_source_wikipedia_enabled",
            "pref_source_wikisource_enabled",
            "pref_source_radio_enabled",
            "pref_source_kvmr_enabled",
            "pref_source_notion_enabled",
            "pref_source_hackernews_enabled",
            "pref_source_arxiv_enabled",
            "pref_source_plos_enabled",
            "pref_source_discord_enabled",
            "pref_source_plugins_enabled_v1",
            // v0.5.76 — Browse-carousel favorites. Same family as the
            // enabled-plugins map: cross-device intent ("AO3 is my main
            // source") rides the sync.
            "pref_source_favorites_v1",
            // Sleep timer.
            "pref_sleep_shake_to_extend_enabled",
            // Azure fallback.
            "pref_azure_fallback_enabled",
            "pref_azure_fallback_voice_id",
            // Debug overlay.
            "pref_show_debug_overlay",
            // Inbox mute toggles.
            "pref_inbox_notify_royalroad",
            "pref_inbox_notify_kvmr",
            "pref_inbox_notify_wikipedia",
            // Accessibility scaffold (Phase 1, v0.5.42) — user intent
            // for assistive-service tunables. All synced so a user who
            // turns on high-contrast on their phone sees it on their
            // tablet too.
            "pref_a11y_high_contrast",
            "pref_a11y_reduced_motion",
            "pref_a11y_larger_touch_targets",
            "pref_a11y_screen_reader_pause_ms",
            "pref_a11y_speak_chapter_mode",
            "pref_a11y_font_scale_override",
            "pref_a11y_reading_direction",
            // Issue #517 — TechEmpower Home onboarding gate.
            "pref_techempower_home_seen",
            // Issue #178 — Royal Road tag-sync metadata. The actual
            // followed-tags set (`pref_rr_tag_sync_followed_tags_v1`)
            // is intentionally NOT in this allowlist: it's mirrored
            // through RR's own server-side preference store (the
            // "saved tags" UI on royalroad.com) rather than through
            // InstantDB. Round-tripping it through both would risk
            // double-merge collisions (InstantDB-LWW vs RR-LWW with
            // different freshness windows). The two metadata keys
            // ARE synced so a user who flips "sync with RR off" on
            // their phone sees the toggle reflected on their tablet.
            "pref_rr_tag_sync_enabled",
            "pref_rr_tag_sync_last_synced_at",
            // v0.5.59 — Appearance: book-cover fallback style. Visual
            // preference that users want mirrored across devices, same
            // rationale as `pref_theme_override`.
            "pref_cover_style",
        )

        /**
         * Per-key type table for [parseAndPut]. Must contain every
         * entry in [SYNC_ALLOWLIST]; the
         * `SettingsRepositoryUiImplSnapshotTest` in `:app` asserts
         * this invariant so a future contributor can't add a key to
         * the allowlist without also declaring its type.
         */
        internal val SYNC_KEY_TYPES: Map<String, SyncedType> = mapOf(
            // String keys.
            "pref_theme_override" to SyncedType.STRING,
            "pref_default_voice_id" to SyncedType.STRING,
            "pref_voice_speed_overrides" to SyncedType.STRING,
            "pref_voice_pitch_overrides" to SyncedType.STRING,
            "pref_voice_lexicon_overrides" to SyncedType.STRING,
            "pref_voice_phonemizer_lang_overrides" to SyncedType.STRING,
            "pref_ai_provider" to SyncedType.STRING,
            "pref_ai_claude_model" to SyncedType.STRING,
            "pref_ai_openai_model" to SyncedType.STRING,
            "pref_ai_ollama_base_url" to SyncedType.STRING,
            "pref_ai_ollama_model" to SyncedType.STRING,
            "pref_ai_vertex_model" to SyncedType.STRING,
            "pref_ai_foundry_endpoint" to SyncedType.STRING,
            "pref_ai_foundry_deployment" to SyncedType.STRING,
            "pref_ai_bedrock_region" to SyncedType.STRING,
            "pref_ai_bedrock_model" to SyncedType.STRING,
            "pref_source_plugins_enabled_v1" to SyncedType.STRING,
            "pref_source_favorites_v1" to SyncedType.STRING,
            "pref_azure_fallback_voice_id" to SyncedType.STRING,
            // Float keys.
            "pref_default_speed" to SyncedType.FLOAT,
            "pref_default_pitch" to SyncedType.FLOAT,
            "pref_punctuation_pause_multiplier_v2" to SyncedType.FLOAT,
            // Int keys.
            "pref_playback_buffer_chunks_v1" to SyncedType.INT,
            "pref_parallel_synth_instances" to SyncedType.INT,
            "pref_synth_threads_per_instance" to SyncedType.INT,
            "pref_poll_interval_hours" to SyncedType.INT,
            // Boolean keys — everything else.
            "pref_pitch_interp_high_quality" to SyncedType.BOOLEAN,
            "pref_voice_steady_v1" to SyncedType.BOOLEAN,
            "pref_warmup_wait_v1" to SyncedType.BOOLEAN,
            "pref_catchup_pause_v1" to SyncedType.BOOLEAN,
            "pref_full_prerender_v1" to SyncedType.BOOLEAN,
            "pref_experimental_parallel_synth" to SyncedType.BOOLEAN,
            "pref_download_wifi_only" to SyncedType.BOOLEAN,
            "pref_ai_foundry_serverless" to SyncedType.BOOLEAN,
            "pref_ai_privacy_ack" to SyncedType.BOOLEAN,
            "pref_ai_send_chapter_text" to SyncedType.BOOLEAN,
            "pref_ai_chat_ground_chapter_title" to SyncedType.BOOLEAN,
            "pref_ai_chat_ground_current_sentence" to SyncedType.BOOLEAN,
            "pref_ai_chat_ground_entire_chapter" to SyncedType.BOOLEAN,
            "pref_ai_chat_ground_entire_book" to SyncedType.BOOLEAN,
            "pref_ai_carry_memory_across_fictions" to SyncedType.BOOLEAN,
            "pref_ai_actions_enabled" to SyncedType.BOOLEAN,
            "pref_github_private_repos_enabled_v1" to SyncedType.BOOLEAN,
            "pref_source_royalroad_enabled" to SyncedType.BOOLEAN,
            "pref_source_github_enabled" to SyncedType.BOOLEAN,
            "pref_source_mempalace_enabled" to SyncedType.BOOLEAN,
            "pref_source_rss_enabled" to SyncedType.BOOLEAN,
            "pref_source_epub_enabled" to SyncedType.BOOLEAN,
            "pref_source_outline_enabled" to SyncedType.BOOLEAN,
            "pref_source_gutenberg_enabled" to SyncedType.BOOLEAN,
            "pref_source_ao3_enabled" to SyncedType.BOOLEAN,
            "pref_source_standard_ebooks_enabled" to SyncedType.BOOLEAN,
            "pref_source_wikipedia_enabled" to SyncedType.BOOLEAN,
            "pref_source_wikisource_enabled" to SyncedType.BOOLEAN,
            "pref_source_radio_enabled" to SyncedType.BOOLEAN,
            "pref_source_kvmr_enabled" to SyncedType.BOOLEAN,
            "pref_source_notion_enabled" to SyncedType.BOOLEAN,
            "pref_source_hackernews_enabled" to SyncedType.BOOLEAN,
            "pref_source_arxiv_enabled" to SyncedType.BOOLEAN,
            "pref_source_plos_enabled" to SyncedType.BOOLEAN,
            "pref_source_discord_enabled" to SyncedType.BOOLEAN,
            "pref_sleep_shake_to_extend_enabled" to SyncedType.BOOLEAN,
            "pref_azure_fallback_enabled" to SyncedType.BOOLEAN,
            "pref_show_debug_overlay" to SyncedType.BOOLEAN,
            "pref_inbox_notify_royalroad" to SyncedType.BOOLEAN,
            "pref_inbox_notify_kvmr" to SyncedType.BOOLEAN,
            "pref_inbox_notify_wikipedia" to SyncedType.BOOLEAN,
            // Accessibility scaffold (Phase 1, v0.5.42).
            "pref_a11y_high_contrast" to SyncedType.BOOLEAN,
            "pref_a11y_reduced_motion" to SyncedType.BOOLEAN,
            "pref_a11y_larger_touch_targets" to SyncedType.BOOLEAN,
            "pref_a11y_screen_reader_pause_ms" to SyncedType.INT,
            "pref_a11y_speak_chapter_mode" to SyncedType.STRING,
            "pref_a11y_font_scale_override" to SyncedType.FLOAT,
            "pref_a11y_reading_direction" to SyncedType.STRING,
            // Issue #517 — TechEmpower Home onboarding gate.
            "pref_techempower_home_seen" to SyncedType.BOOLEAN,
            // Issue #178 — Royal Road tag-sync metadata.
            "pref_rr_tag_sync_enabled" to SyncedType.BOOLEAN,
            "pref_rr_tag_sync_last_synced_at" to SyncedType.LONG,
            // v0.5.59 — Appearance: book-cover fallback style stored as
            // CoverStyle enum's `name` string ("Monogram"/"Branded"/"CoverOnly").
            "pref_cover_style" to SyncedType.STRING,
        )
    }

    /** Synced-preference value type tag for [parseAndPut]. */
    internal enum class SyncedType { BOOLEAN, INT, FLOAT, STRING, LONG }
}

/**
 * Source-internal [GitHubSession] → public [UiGitHubAuthState] projection.
 * Strips the token before crossing into the feature/UI layer — Settings
 * never needs the secret, only "are you signed in, who as." Issue #91.
 */
private fun GitHubSession.toUi(): UiGitHubAuthState = when (this) {
    is GitHubSession.Anonymous -> UiGitHubAuthState.Anonymous
    is GitHubSession.Authenticated -> UiGitHubAuthState.SignedIn(
        login = login,
        scopes = scopes,
    )
    is GitHubSession.Expired -> UiGitHubAuthState.Expired
}
