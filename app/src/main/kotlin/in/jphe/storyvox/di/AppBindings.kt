package `in`.jphe.storyvox.di

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import `in`.jphe.storyvox.data.PalaceConfigImpl
import `in`.jphe.storyvox.data.SettingsRepositoryUiImpl
import `in`.jphe.storyvox.data.VoiceProviderUiImpl
import `in`.jphe.storyvox.source.mempalace.config.PalaceConfig
import `in`.jphe.storyvox.data.repository.ChapterRepository
import `in`.jphe.storyvox.data.repository.FictionRepository
import `in`.jphe.storyvox.data.repository.playback.PlaybackBufferConfig
import `in`.jphe.storyvox.data.repository.playback.PlaybackModeConfig
import `in`.jphe.storyvox.data.repository.playback.VoiceTuningConfig
import `in`.jphe.storyvox.data.repository.pronunciation.PronunciationDictRepository
import `in`.jphe.storyvox.data.source.WebViewFetcher
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.SearchOrder
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.data.source.model.SortDirection
import `in`.jphe.storyvox.feature.api.BrowsePaginator
import `in`.jphe.storyvox.feature.api.BrowseRepositoryUi
import `in`.jphe.storyvox.feature.api.BrowseSource
import `in`.jphe.storyvox.feature.api.DownloadMode
import `in`.jphe.storyvox.data.repository.AddByUrlResult
import `in`.jphe.storyvox.feature.api.FictionRepositoryUi
import `in`.jphe.storyvox.feature.api.UiAddByUrlResult
import `in`.jphe.storyvox.feature.api.PlaybackControllerUi
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import `in`.jphe.storyvox.feature.api.UiChapter
import `in`.jphe.storyvox.feature.api.UiFiction
import `in`.jphe.storyvox.feature.api.UiFollow
import `in`.jphe.storyvox.feature.api.UiPlaybackState
import `in`.jphe.storyvox.feature.api.UiRecapPlaybackState
import `in`.jphe.storyvox.feature.api.UiSleepTimerMode
import `in`.jphe.storyvox.feature.api.VoiceProviderUi
import `in`.jphe.storyvox.feature.browse.RealBrowsePaginator
import `in`.jphe.storyvox.feature.browse.toUiFiction
import `in`.jphe.storyvox.playback.PlaybackController
import `in`.jphe.storyvox.playback.PlaybackState
import `in`.jphe.storyvox.playback.PlaybackUiEvent
import `in`.jphe.storyvox.playback.tts.RecapPlaybackState
import `in`.jphe.storyvox.playback.SPEED_BASELINE_CHARS_PER_SECOND
import `in`.jphe.storyvox.playback.SleepTimerMode
import `in`.jphe.storyvox.playback.StoryvoxPlaybackService
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Hilt bindings that bridge `feature.api.*` UI contracts to the concrete
 * core-data repositories and core-playback controller.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppBindings {

    @Provides @Singleton
    fun provideFictionRepositoryUi(
        repo: FictionRepository,
        chapters: ChapterRepository,
    ): FictionRepositoryUi = RealFictionRepositoryUi(repo, chapters)

    @Provides @Singleton
    fun provideBrowseRepositoryUi(
        repo: FictionRepository,
        github: `in`.jphe.storyvox.source.github.GitHubAuthedSource,
        ao3: `in`.jphe.storyvox.source.ao3.Ao3AuthedSource,
        registry: `in`.jphe.storyvox.data.source.plugin.SourcePluginRegistry,
    ): BrowseRepositoryUi = RealBrowseRepositoryUi(repo, github, ao3, registry)

    @Provides @Singleton
    fun providePlaybackControllerUi(
        @ApplicationContext context: Context,
        controller: PlaybackController,
        chapters: ChapterRepository,
        settings: SettingsRepositoryUi,
    ): PlaybackControllerUi = RealPlaybackControllerUi(context, controller, chapters, settings)

    /**
     * Vesper (v0.4.97) — debug snapshot binding. Sits alongside
     * [providePlaybackControllerUi] because the debug surface shares the
     * same controller singleton + adds Azure for the cloud-voice
     * diagnostics row. Construction is cheap (no flow allocation until
     * subscribed); WhileSubscribed in [RealDebugRepositoryUi] keeps the
     * combine cold when nothing's looking.
     */
    @Provides @Singleton
    fun provideDebugRepositoryUi(
        @ApplicationContext context: Context,
        controller: PlaybackController,
        settings: SettingsRepositoryUi,
        azureCreds: `in`.jphe.storyvox.source.azure.AzureCredentials,
        azureEngine: `in`.jphe.storyvox.source.azure.AzureVoiceEngine,
        azureSpeechClient: `in`.jphe.storyvox.source.azure.AzureSpeechClient,
        azureVoiceRoster: `in`.jphe.storyvox.source.azure.AzureVoiceRoster,
        chapterRepo: `in`.jphe.storyvox.data.repository.ChapterRepository,
    ): `in`.jphe.storyvox.feature.api.DebugRepositoryUi =
        RealDebugRepositoryUi(
            context = context,
            controller = controller,
            settings = settings,
            azureCreds = azureCreds,
            azureEngine = azureEngine,
            azureSpeechClient = azureSpeechClient,
            azureVoiceRoster = azureVoiceRoster,
            chapterRepo = chapterRepo,
        )

    @Provides @Singleton
    fun provideVoiceProviderUi(impl: VoiceProviderUiImpl): VoiceProviderUi = impl

    /**
     * Issue #676 — bind [SystemTtsVoiceRoster] as the [SystemTtsVoiceProvider]
     * interface that VoiceManager consumes. The roster lives in `:app`
     * because Hilt's `@ApplicationContext` injection is needed for the
     * framework TextToSpeech construction; the interface lives in
     * `:core-data` so the rest of the graph (catalog projection,
     * VoiceManager, EnginePlayer) compiles without depending on `:app`.
     * Symmetric with how Azure's roster is bound.
     */
    @Provides @Singleton
    fun provideSystemTtsVoiceProvider(
        impl: `in`.jphe.storyvox.data.SystemTtsVoiceRoster,
    ): `in`.jphe.storyvox.data.source.SystemTtsVoiceProvider = impl

    @Provides @Singleton
    fun provideSettingsRepositoryUi(impl: SettingsRepositoryUiImpl): SettingsRepositoryUi = impl

    /** Issue #793 — DataStore-backed Library sort persistence. */
    @Provides @Singleton
    fun provideLibrarySortStore(
        impl: `in`.jphe.storyvox.data.LibrarySortStoreImpl,
    ): `in`.jphe.storyvox.feature.library.LibrarySortStore = impl

    /**
     * Bridges the source-mempalace [PalaceConfig] read interface to the
     * concrete app-side impl that owns DataStore + EncryptedSharedPreferences.
     * The impl is exposed concretely under its own provide so the Settings
     * UI can call its mutators without going through a write interface
     * (kept off the read-side seam by design).
     */
    @Provides @Singleton
    fun providePalaceConfig(impl: PalaceConfigImpl): PalaceConfig = impl

    /** Bridges the source-rss [RssConfig] read interface (#236) to the
     *  concrete app-side DataStore-backed impl. Same pattern as
     *  [providePalaceConfig]. The impl exposes mutators (addFeed /
     *  removeFeed) that the Settings UI calls directly. */
    @Provides @Singleton
    fun provideRssConfig(impl: `in`.jphe.storyvox.data.RssConfigImpl): `in`.jphe.storyvox.source.rss.config.RssConfig = impl

    /** Issue #417 — bridges the source-radio [RadioConfig] interface to
     *  the app-side DataStore-backed impl. Same shape as
     *  [provideRssConfig]: the impl is exposed concretely so the
     *  Browse → Radio → Search UI can call mutators (star / unstar)
     *  directly. */
    @Provides @Singleton
    fun provideRadioConfig(impl: `in`.jphe.storyvox.data.RadioConfigImpl): `in`.jphe.storyvox.source.radio.config.RadioConfig = impl

    /** Bridges the source-epub [EpubConfig] read interface (#235) to
     *  the concrete app-side SAF-backed impl. */
    @Provides @Singleton
    fun provideEpubConfig(impl: `in`.jphe.storyvox.data.EpubConfigImpl): `in`.jphe.storyvox.source.epub.config.EpubConfig = impl

    /** Bridges source-outline OutlineConfig (#245) to the app-side
     *  DataStore + EncryptedSharedPreferences impl. */
    @Provides @Singleton
    fun provideOutlineConfig(impl: `in`.jphe.storyvox.data.OutlineConfigImpl): `in`.jphe.storyvox.source.outline.config.OutlineConfig = impl

    /** Bridges source-wikipedia WikipediaConfig (#377) to the app-side
     *  DataStore impl. Wikipedia is read-only/public — no API key
     *  leg, just the language-code preference. */
    @Provides @Singleton
    fun provideWikipediaConfig(impl: `in`.jphe.storyvox.data.WikipediaConfigImpl): `in`.jphe.storyvox.source.wikipedia.config.WikipediaConfig = impl

    /** Bridges source-notion NotionConfig (#233) to the app-side
     *  DataStore + EncryptedSharedPreferences impl. Same shape as
     *  Outline — database id in plaintext, integration token encrypted. */
    @Provides @Singleton
    fun provideNotionConfig(impl: `in`.jphe.storyvox.data.NotionConfigImpl): `in`.jphe.storyvox.source.notion.config.NotionConfig = impl

    /** Bridges source-discord DiscordConfig (#403) to the app-side
     *  DataStore + EncryptedSharedPreferences impl. Same shape as
     *  Notion / Outline — server id + coalesce window in plaintext,
     *  bot token encrypted under `pref_source_discord_token` in the
     *  shared `storyvox.secrets` store. */
    @Provides @Singleton
    fun provideDiscordConfig(impl: `in`.jphe.storyvox.data.DiscordConfigImpl): `in`.jphe.storyvox.source.discord.config.DiscordConfig = impl

    /** Bridges source-telegram TelegramConfig (#462) to the app-side
     *  impl. Bot token encrypted under `pref_source_telegram_token`
     *  in the shared `storyvox.secrets` store. v1 has no plaintext-
     *  pref leg; the channel list is derived from observed
     *  `getUpdates` activity at runtime, not user-configured. */
    @Provides @Singleton
    fun provideTelegramConfig(impl: `in`.jphe.storyvox.data.TelegramConfigImpl): `in`.jphe.storyvox.source.telegram.config.TelegramConfig = impl

    /** Bridges source-slack SlackConfig (#454) to the app-side
     *  DataStore + EncryptedSharedPreferences impl. Bot token
     *  encrypted under `pref_source_slack_token` in the shared
     *  `storyvox.secrets` store (also listed in
     *  [`in`.jphe.storyvox.sync.domain.SecretsSyncer.SECRET_KEY_NAMES]
     *  so it syncs cross-device via InstantDB). Workspace name +
     *  URL cached in plaintext DataStore for empty-state copy
     *  without an extra `auth.test` round-trip. */
    @Provides @Singleton
    fun provideSlackConfig(impl: `in`.jphe.storyvox.data.SlackConfigImpl): `in`.jphe.storyvox.source.slack.config.SlackConfig = impl

    /** Bridges source-matrix MatrixConfig (#457) to the app-side
     *  DataStore + EncryptedSharedPreferences impl. Same shape as
     *  Discord / Telegram — homeserver URL + resolved user id +
     *  coalesce window in plaintext, access token encrypted under
     *  `pref_source_matrix_token` in the shared `storyvox.secrets`
     *  store. The Settings UI surface (token-entry sheet, homeserver
     *  field, room picker, coalesce slider) is a follow-up; the
     *  backend ships functional via KSP-driven plugin registration
     *  and the existing Plugin Manager toggle. */
    @Provides @Singleton
    fun provideMatrixConfig(impl: `in`.jphe.storyvox.data.MatrixConfigImpl): `in`.jphe.storyvox.source.matrix.config.MatrixConfig = impl

    /**
     * Same singleton instance as [provideSettingsRepositoryUi], exposed under
     * the [PlaybackBufferConfig] contract so `core-playback`'s [EnginePlayer]
     * can pull the user-tunable queue depth without depending on the feature
     * layer's [SettingsRepositoryUi].
     */
    @Provides @Singleton
    fun providePlaybackBufferConfig(impl: SettingsRepositoryUiImpl): PlaybackBufferConfig = impl

    /**
     * Issue #98 — Mode A / Mode B contract for `core-playback`'s EnginePlayer.
     * Same singleton instance as [provideSettingsRepositoryUi]; one DataStore,
     * three contracts.
     */
    @Provides @Singleton
    fun providePlaybackModeConfig(impl: SettingsRepositoryUiImpl): PlaybackModeConfig = impl

    /**
     * Issue #85 — Voice-Determinism preset for the VoxSherpa engine. Same
     * singleton instance as [provideSettingsRepositoryUi]; consumed by
     * `core-playback`'s EnginePlayer to dispatch
     * `VoiceEngine.setNoiseScale[W]()` calls on flips.
     */
    @Provides @Singleton
    fun provideVoiceTuningConfig(impl: SettingsRepositoryUiImpl): VoiceTuningConfig = impl

    /**
     * PR-6 (#185) — Azure offline-fallback contract. Same
     * SettingsRepositoryUiImpl singleton; one DataStore, four contracts
     * now. EnginePlayer reads this to decide whether to auto-swap to a
     * local voice on Azure synth failures.
     */
    @Provides @Singleton
    fun provideAzureFallbackConfig(impl: SettingsRepositoryUiImpl):
        `in`.jphe.storyvox.data.repository.playback.AzureFallbackConfig = impl

    /** Tier 3 (#88) — parallel-synth toggle binding. Same singleton. */
    @Provides @Singleton
    fun provideParallelSynthConfig(impl: SettingsRepositoryUiImpl):
        `in`.jphe.storyvox.data.repository.playback.ParallelSynthConfig = impl

    /** #90 — Smart-resume policy config. Same singleton. */
    @Provides @Singleton
    fun providePlaybackResumePolicyConfig(impl: SettingsRepositoryUiImpl):
        `in`.jphe.storyvox.data.repository.playback.PlaybackResumePolicyConfig = impl

    /** Issues #593 / #594 — user-tunable skip distance + rewind-to-start
     *  threshold. Same singleton; one DataStore, every contract.
     *  Consumed by `core-playback`'s DefaultPlaybackController. */
    @Provides @Singleton
    fun providePlaybackSkipConfig(impl: SettingsRepositoryUiImpl):
        `in`.jphe.storyvox.data.repository.playback.PlaybackSkipConfig = impl

    /** Issue #595 — user-tunable sleep-timer shake-to-extend duration.
     *  Same singleton; consumed by `:core-playback`'s
     *  `StoryvoxPlaybackService` via the AppBindings flow collector
     *  (mirrors the shake-enabled toggle in #150). */
    @Provides @Singleton
    fun provideSleepTimerExtendConfig(impl: SettingsRepositoryUiImpl):
        `in`.jphe.storyvox.data.repository.playback.SleepTimerExtendConfig = impl

    /** Issue #596 — user-tunable PCM-cache pre-render window size.
     *  Same singleton; consumed by `:core-playback`'s
     *  `PrerenderTriggers`. */
    @Provides @Singleton
    fun providePrerenderChapterCountConfig(impl: SettingsRepositoryUiImpl):
        `in`.jphe.storyvox.data.repository.playback.PrerenderChapterCountConfig = impl

    /** Issue #598 — user-tunable Android Auto bucket size. Same
     *  singleton; consumed by `:core-playback`'s
     *  `StoryvoxAutoBrowserService`. */
    @Provides @Singleton
    fun provideAutoBrowserConfig(impl: SettingsRepositoryUiImpl):
        `in`.jphe.storyvox.data.repository.playback.AutoBrowserConfig = impl

    /** Issue #597 — user-tunable HTTP timeout preset. Same singleton;
     *  consumed by source-* modules' OkHttp factories via an
     *  Interceptor / timeout override. v1 wires this into the most-
     *  touched source modules (royalroad, notion, rss); follow-up
     *  issues track per-module adoption for the long tail. */
    @Provides @Singleton
    fun provideNetworkPatienceConfig(impl: SettingsRepositoryUiImpl):
        `in`.jphe.storyvox.data.repository.net.NetworkPatienceConfig = impl

    /**
     * Issue #135 — pronunciation dictionary contract for `core-playback`'s
     * EnginePlayer + the Settings UI. Same singleton instance as the rest;
     * one DataStore, every contract.
     */
    @Provides @Singleton
    fun providePronunciationDictRepository(
        impl: SettingsRepositoryUiImpl,
    ): PronunciationDictRepository = impl

    /**
     * Tier 1 settings-sync snapshot/apply seam (this PR — extends #360).
     * Bridges `:core-sync`'s `SettingsSyncer` to the live DataStore via
     * the same singleton — one store, every contract, including the
     * sync-side snapshot/apply pair.
     */
    @Provides @Singleton
    fun provideSettingsSnapshotSource(
        impl: SettingsRepositoryUiImpl,
    ): `in`.jphe.storyvox.data.repository.sync.SettingsSnapshotSource = impl

    /**
     * Issue #178 — followed-tags store binding. The impl lives in
     * `:app` (`FollowedTagsStoreImpl`) because it owns a DataStore
     * file, and the interface is in `:core-data` so
     * `:source-royalroad`'s tag-sync coordinator can consume it
     * without depending on `:app`.
     */
    @Provides @Singleton
    fun provideFollowedTagsStore(
        impl: `in`.jphe.storyvox.data.FollowedTagsStoreImpl,
    ): `in`.jphe.storyvox.data.repository.sync.FollowedTagsStore = impl

    /**
     * Issue #178 — feature-api facade for the Settings tag-sync
     * row. The implementation glues `:core-data`'s store +
     * `:source-royalroad`'s coordinator together; the row in
     * `:feature` only sees this UI-shaped interface.
     */
    @Provides @Singleton
    fun provideRoyalRoadTagSyncUi(
        impl: RealRoyalRoadTagSyncUi,
    ): `in`.jphe.storyvox.feature.api.RoyalRoadTagSyncUi = impl

    // Tier 2 secrets sync (this PR — extends #360): all three tokens
    // (Notion / Discord / Outline) actually live in
    // EncryptedSharedPreferences already (Notion: `notion.api_token`,
    // Outline: `outline.api_key`, Discord: `pref_source_discord_token`).
    // No additional binding needed — `SecretsSyncer` picks them up via
    // the extended `SECRET_KEY_PREFIXES` (`notion.`) and
    // `SECRET_KEY_NAMES` (Discord's flat-named token).

    /**
     * Real passphrase provider — replaces the no-op default that
     * was in `:core-sync`'s SyncModule. [PassphraseStore] reads from
     * EncryptedSharedPreferences under `sync.passphrase`.
     */
    @Provides
    @Singleton
    @Named(`in`.jphe.storyvox.sync.domain.SecretsSyncer.PASSPHRASE_PROVIDER)
    fun providePassphraseProvider(
        impl: `in`.jphe.storyvox.data.PassphraseStore,
    ): `in`.jphe.storyvox.sync.domain.PassphraseProvider = impl

    @Provides @Singleton
    fun providePassphraseManager(
        impl: `in`.jphe.storyvox.data.PassphraseStore,
    ): `in`.jphe.storyvox.sync.domain.PassphraseManager = impl

    /**
     * Issue #203 — narrow [GitHubScopePreferences] surface for the
     * `GitHubSignInViewModel`. Same singleton as the rest; the
     * `:source-github` module reads only the toggle flag, never the
     * full settings repo.
     */
    @Provides @Singleton
    fun provideGitHubScopePreferences(
        impl: SettingsRepositoryUiImpl,
    ): `in`.jphe.storyvox.source.github.auth.GitHubScopePreferences = impl

    /**
     * Same singleton instance as [provideSettingsRepositoryUi], exposed
     * under the [`in`.jphe.storyvox.llm.LlmConfigProvider] contract so
     * `core-llm`'s provider classes can read the user's chosen
     * provider/model/URL without depending on the feature/api layer.
     */
    @Provides @Singleton
    fun provideLlmConfigProvider(
        impl: SettingsRepositoryUiImpl,
    ): `in`.jphe.storyvox.llm.LlmConfigProvider = impl

    /**
     * Issue #383 — per-source mute gate for the cross-source Inbox feed.
     * Reads the matching INBOX_NOTIFY_* boolean from DataStore via
     * [SettingsRepositoryUiImpl] so the production wiring lives in
     * one place. Recognised sourceIds fall back to true when no
     * preference is stored; unrecognised sources (future backends not
     * yet given their own toggle) also default to true so events are
     * never silently dropped.
     */
    @Provides @Singleton
    fun provideInboxNotificationGate(
        settings: SettingsRepositoryUiImpl,
    ): `in`.jphe.storyvox.data.repository.InboxNotificationGate =
        object : `in`.jphe.storyvox.data.repository.InboxNotificationGate {
            override suspend fun isEnabled(sourceId: String): Boolean {
                val snap = settings.settings.first()
                return when (sourceId) {
                    "royalroad" -> snap.inboxNotifyRoyalRoad
                    // Issue #417 — :source-kvmr generalized to
                    // :source-radio. Both sourceIds map to the same
                    // inboxNotifyKvmr toggle (the UI label can update
                    // in a follow-up; the persisted key stays stable
                    // so the toggle history survives).
                    "radio", "kvmr" -> snap.inboxNotifyKvmr
                    "wikipedia" -> snap.inboxNotifyWikipedia
                    else -> true
                }
            }
        }

    /**
     * Stub WebViewFetcher — Selene's `:core-data` declares the interface; the
     * real impl in `:source-royalroad` is part of the deferred integration.
     * Returns a NetworkError with a clear message so any caller fails loudly.
     */
    @Provides @Singleton
    fun provideWebViewFetcher(): WebViewFetcher = object : WebViewFetcher {
        override suspend fun fetch(url: String, cookieHeader: String?): FictionResult<String> =
            FictionResult.NetworkError(
                message = "WebViewFetcher integration pending — see source-royalroad/_unintegrated/",
                cause = NotImplementedError("WebViewFetcher v1 stub"),
            )
    }
}

/**
 * Adapter from [FictionRepositoryUi] (Aurora's UI contract) to
 * [FictionRepository] (Selene's data layer).
 *
 * Library and Follows lists come straight from `observeLibrary()` /
 * `observeFollowsRemote()` (Flow).
 *
 * `fictionById(id)` triggers a one-shot `refreshDetail(id)` on first
 * subscription and then returns `observeFiction(id)` mapped to UiFiction.
 * The first emission may be null (no row cached yet); subsequent emissions
 * carry the real data once `refreshDetail` upserts.
 */
private class RealFictionRepositoryUi(
    private val repo: FictionRepository,
    private val chapters: ChapterRepository,
) : FictionRepositoryUi {

    override val library: Flow<List<UiFiction>> =
        repo.observeLibrary().map { list -> list.map(::toUiFiction) }

    override val follows: Flow<List<UiFollow>> =
        repo.observeFollowsRemote().map { list ->
            list.map { UiFollow(fiction = toUiFiction(it), unreadCount = 0) }
        }

    /** Per-id error tracker shared between [fictionById] and
     *  [fictionLoadError] so the same first-subscription refresh result
     *  surfaces in both flows. Singleton-scoped (this adapter is
     *  @Singleton via Hilt), so the map and its entries live for the app
     *  lifetime — fine for the small number of fictions a user opens. */
    private val loadErrors = java.util.concurrent.ConcurrentHashMap<String, MutableStateFlow<String?>>()

    private fun errorState(id: String): MutableStateFlow<String?> =
        loadErrors.getOrPut(id) { MutableStateFlow(null) }

    override fun fictionById(id: String): Flow<UiFiction?> = flow {
        // Kick off a refresh on first subscription. Failures are silently
        // tolerated for the value flow (the cached row may exist) but
        // captured in [errorState] so [fictionLoadError] can surface them.
        when (val result = repo.refreshDetail(id)) {
            is FictionResult.Success -> errorState(id).value = null
            is FictionResult.Failure -> errorState(id).value = result.message
        }
        emitAll(repo.observeFiction(id).map { detail -> detail?.summary?.let(::toUiFiction) })
    }

    override fun fictionLoadError(id: String): Flow<String?> = errorState(id).asStateFlow()

    override suspend fun chapterTextById(chapterId: String): String? =
        chapters.getChapter(chapterId)?.text

    override fun observeIsInLibrary(fictionId: String): Flow<Boolean> =
        repo.observeIsInLibrary(fictionId)

    override fun chaptersFor(fictionId: String): Flow<List<UiChapter>> =
        // Issue #282 — the previous mapping hard-coded `isFinished = false`,
        // so the played-indicator on the Fiction detail chapter list never
        // updated after auto-advance (or any markChapterPlayed call). Now
        // combine the chapter list with the observable set of "user marked
        // played" chapter ids so the circle fills as soon as the DB row
        // flips. Both flows are Room-backed and re-emit on any relevant
        // write; downstream combine fires once with whichever lands last.
        kotlinx.coroutines.flow.combine(
            repo.observeFiction(fictionId),
            chapters.observePlayedChapterIds(fictionId),
        ) { detail, playedIds ->
            detail?.chapters.orEmpty().map { ch ->
                UiChapter(
                    id = ch.id,
                    number = ch.index + 1,
                    title = ch.title,
                    publishedRelative = relativeTime(ch.publishedAt),
                    durationLabel = ch.wordCount?.let { "${(it / 250).coerceAtLeast(1)} min" } ?: "",
                    isDownloaded = false,
                    isFinished = ch.id in playedIds,
                )
            }
        }

    override suspend fun setDownloadMode(fictionId: String, mode: DownloadMode) {
        repo.setDownloadMode(fictionId, mode.toData())
    }

    override suspend fun follow(fictionId: String, follow: Boolean) {
        if (follow) repo.addToLibrary(fictionId, mode = null) else repo.removeFromLibrary(fictionId)
    }

    override suspend fun setFollowedRemote(
        fictionId: String,
        followed: Boolean,
    ): `in`.jphe.storyvox.feature.api.SetFollowedRemoteResult =
        when (val r = repo.setFollowedRemote(fictionId, followed)) {
            is FictionResult.Success ->
                `in`.jphe.storyvox.feature.api.SetFollowedRemoteResult.Success
            is FictionResult.AuthRequired ->
                `in`.jphe.storyvox.feature.api.SetFollowedRemoteResult.AuthRequired
            is FictionResult.Failure ->
                `in`.jphe.storyvox.feature.api.SetFollowedRemoteResult.Error(r.message)
        }

    override suspend fun markAllCaughtUp() {
        // No-op for v1 — chapter-level "read" tracking lands when the reader is wired.
    }

    override suspend fun refreshFollows() {
        // Best-effort — failures are silent. AuthRequired returns when unauthed,
        // which the UI treats as "stay with whatever's in the local DB".
        runCatching { repo.refreshRemoteFollows() }
    }

    override suspend fun retryDetail(id: String) {
        // Issue #806 — Retry button on the FictionDetail error states.
        // Mirror the first-subscription refresh that [fictionById] runs:
        // call refreshDetail and update the per-id error state. The
        // value flow is already a Room observer on the same row, so a
        // successful refresh will surface automatically.
        when (val result = repo.refreshDetail(id)) {
            is FictionResult.Success -> errorState(id).value = null
            is FictionResult.Failure -> errorState(id).value = result.message
        }
    }

    override suspend fun addByUrl(
        url: String,
        preferredSourceId: String?,
    ): UiAddByUrlResult =
        when (val r = repo.addByUrl(url, preferredSourceId)) {
            is AddByUrlResult.Success -> UiAddByUrlResult.Success(r.fictionId)
            AddByUrlResult.UnrecognizedUrl -> UiAddByUrlResult.UnrecognizedUrl
            is AddByUrlResult.UnsupportedSource -> UiAddByUrlResult.UnsupportedSource(r.sourceId)
            is AddByUrlResult.SourceFailure -> UiAddByUrlResult.Error(r.failure.message)
            is AddByUrlResult.MultipleMatches -> UiAddByUrlResult.MultipleMatches(
                r.candidates.map { it.toUi() },
            )
        }

    override fun previewUrl(url: String): List<`in`.jphe.storyvox.feature.api.UiRouteCandidate> =
        repo.previewUrl(url).map { it.toUi() }

    /**
     * Issues #644 + #647 (v1.0) — passthrough to [FictionRepository.browsePopular]
     * for the onboarding's "Browse TechEmpower's free guides" CTA. Calling
     * `browsePopular(page = 1, sourceId)` runs the source's `popular()`
     * round-trip and pipes the result through `cacheListing`, which is
     * what upserts the per-fiction rows into the `fictionDao` so that a
     * subsequent `refreshDetail(notion:guides)` routes to the right source
     * instead of the legacy `ROYAL_ROAD` fallback. Returns true iff the
     * source returned a Success with at least one item.
     */
    override suspend fun seedPopularForSource(sourceId: String): Boolean =
        runCatching {
            when (val result = repo.browsePopular(page = 1, sourceId = sourceId)) {
                is FictionResult.Success -> result.value.items.isNotEmpty()
                is FictionResult.Failure -> false
            }
        }.getOrDefault(false)

    private fun `in`.jphe.storyvox.data.source.RouteMatch.toUi():
        `in`.jphe.storyvox.feature.api.UiRouteCandidate =
        `in`.jphe.storyvox.feature.api.UiRouteCandidate(
            sourceId = sourceId,
            fictionId = fictionId,
            confidence = confidence,
            label = label,
        )
}

private fun DownloadMode.toData(): `in`.jphe.storyvox.data.db.entity.DownloadMode = when (this) {
    DownloadMode.Lazy -> `in`.jphe.storyvox.data.db.entity.DownloadMode.LAZY
    DownloadMode.Eager -> `in`.jphe.storyvox.data.db.entity.DownloadMode.EAGER
    DownloadMode.Subscribe -> `in`.jphe.storyvox.data.db.entity.DownloadMode.SUBSCRIBE
}

private fun relativeTime(epochMs: Long?): String {
    if (epochMs == null) return ""
    val deltaSec = (System.currentTimeMillis() - epochMs) / 1000L
    return when {
        deltaSec < 60 -> "just now"
        deltaSec < 3600 -> "${deltaSec / 60}m ago"
        deltaSec < 86_400 -> "${deltaSec / 3600}h ago"
        deltaSec < 86_400 * 7 -> "${deltaSec / 86_400}d ago"
        deltaSec < 86_400 * 30 -> "${deltaSec / (86_400 * 7)}w ago"
        else -> "${deltaSec / (86_400 * 30)}mo ago"
    }
}

/**
 * Adapter from [BrowseRepositoryUi] (Aurora's UI contract) to
 * [FictionRepository] (Selene's data layer). Hands out a fresh
 * [RealBrowsePaginator] per (tab, debounced query, filter) tuple
 * the ViewModel asks for; each paginator accumulates pages on
 * `loadNext()` calls until the upstream returns `hasNext = false`.
 */
private class RealBrowseRepositoryUi(
    private val repo: FictionRepository,
    private val github: `in`.jphe.storyvox.source.github.GitHubAuthedSource,
    private val ao3: `in`.jphe.storyvox.source.ao3.Ao3AuthedSource,
    private val registry: `in`.jphe.storyvox.data.source.plugin.SourcePluginRegistry,
) : BrowseRepositoryUi {
    override fun paginator(source: BrowseSource, sourceId: String): BrowsePaginator =
        RealBrowsePaginator { page ->
            when (source) {
                BrowseSource.Popular -> repo.browsePopular(page = page, sourceId = sourceId)
                BrowseSource.NewReleases -> repo.browseLatest(page = page, sourceId = sourceId)
                BrowseSource.BestRated -> repo.search(
                    SearchQuery(
                        orderBy = SearchOrder.RATING,
                        direction = SortDirection.DESC,
                        page = page,
                    ),
                    sourceId = sourceId,
                )
                is BrowseSource.Search -> repo.search(
                    SearchQuery(
                        term = source.query,
                        orderBy = SearchOrder.RELEVANCE,
                        page = page,
                    ),
                    sourceId = sourceId,
                )
                is BrowseSource.Filtered -> {
                    val base = SearchQuery(
                        term = source.query,
                        page = page,
                    )
                    val resolved = registry.byId(sourceId)?.source
                        ?.applyFilters(base, source.state)
                        ?: base
                    repo.search(resolved, sourceId = sourceId)
                }
                is BrowseSource.ByGenre -> repo.browseByGenre(
                    genre = source.genre,
                    page = page,
                    sourceId = sourceId,
                )
                // #200/#201 — auth-gated `/user/repos` and `/user/starred`
                // listings. Bypass the FictionRepository.search path
                // because the endpoint shape doesn't fit `SearchQuery`
                // (no qualifier syntax — affiliation/sort knobs only).
                // Routes directly to the source surface, then funnels
                // the result through `cacheBrowseListing` so each row
                // lands in the DB with `sourceId="github"` — without
                // that, tapping a card hits `refreshDetail`'s "no row
                // → fall back to RR source" branch and 404s on the
                // github fictionId.
                BrowseSource.GitHubMyRepos -> repo.cacheBrowseListing(github.myRepos(page = page))
                BrowseSource.GitHubStarred -> repo.cacheBrowseListing(github.starred(page = page))
                // #202 — auth-gated `/gists` listing. Same caching
                // pattern as MyRepos / Starred: rows land in the DB
                // with their `github:gist:<id>` ids so tapping a gist
                // card resolves through `fictionDetail` and the gist
                // codepath in GitHubSource — not the RR fallback.
                BrowseSource.GitHubGists ->
                    repo.cacheBrowseListing(github.authenticatedUserGists(page = page))
                // #426 PR2 — AO3 authed surfaces. Same caching pattern
                // as the GitHub auth-only variants: rows land in the
                // DB with `sourceId="ao3"` so tapping a card resolves
                // through `fictionDetail` on the AO3 codepath.
                BrowseSource.Ao3MySubscriptions ->
                    repo.cacheBrowseListing(ao3.subscriptions(page = page))
                BrowseSource.Ao3MarkedForLater ->
                    repo.cacheBrowseListing(ao3.markedForLater(page = page))
            }
        }

    override suspend fun genres(sourceId: String): List<String> =
        when (val r = repo.genres(sourceId)) {
            is FictionResult.Success -> r.value
            else -> emptyList()
        }
}

/**
 * Adapter from [PlaybackControllerUi] (Aurora's UI contract) to
 * [PlaybackController] (Hypnos's playback layer) plus [ChapterRepository]
 * for chapter text streaming.
 *
 * `startListening` is the cold-start path. The chapter body almost certainly
 * isn't downloaded on the first tap, so we (1) start the foreground media
 * service so the [EnginePlayer] binds to the controller, (2) queue a download with
 * `requireUnmetered = false` so it actually runs on cell, (3) await the first
 * non-null body emission from `chapters.observeChapter(chapterId)`, then
 * (4) call `controller.play(...)` which kicks the TTS engine.
 *
 * Subsequent transport calls (play/pause/seek) just delegate to the
 * controller — once a chapter is loaded, seeking and skipping never need
 * new bytes from the network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class RealPlaybackControllerUi(
    private val context: Context,
    private val controller: PlaybackController,
    private val chapters: ChapterRepository,
    private val settings: SettingsRepositoryUi,
) : PlaybackControllerUi {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Issue #98 — Mode A live cache. Read by [toUi] when computing
     * `isWarmingUp` + the wall-time freeze gate. Volatile because writers
     * are coroutine collectors and readers are state-flow combine emissions
     * on potentially different threads.
     */
    @Volatile
    private var cachedWarmupWait: Boolean = true

    init {
        // Issue #90: keep the live engine's punctuation-pause multiplier in
        // sync with the persisted user preference. We do the observation here
        // (rather than in core-playback's StoryvoxPlaybackService) because
        // SettingsRepositoryUi lives in feature/api — adding it as a dep on
        // core-playback would invert the layering. This adapter is the
        // first place both modules already meet, so it's the right seam.
        //
        // Distinct on the multiplier value so a no-op DataStore re-emission
        // (e.g. on hydration) doesn't trigger a needless pipeline rebuild via
        // setPunctuationPauseMultiplier (which calls startPlaybackPipeline if
        // currently playing). #109 widened this from a 3-stop enum to a
        // continuous slider; the seam below didn't change shape — the engine
        // has always taken a Float and clamps to [0..4] internally.
        scope.launch {
            settings.settings
                .map { it.punctuationPauseMultiplier }
                .distinctUntilChanged()
                .collect { controller.setPunctuationPauseMultiplier(it) }
        }
        // Issue #108: keep the live engine's speed + pitch in sync with the
        // persisted user preference. Same seam + same reasoning as
        // punctuationPause above — Settings slider writes to DataStore, this
        // observer forwards the new value into PlaybackController so the
        // EnginePlayer actually applies it. Without this, the slider only
        // changes what the *next* fresh process boot would hydrate to, which
        // is invisible to the user mid-session.
        //
        // distinctUntilChanged is load-bearing: setSpeed/setPitch each rebuild
        // the playback pipeline if anything's playing, so a duplicate emission
        // from DataStore hydration would interrupt audio for no reason.
        scope.launch {
            // #195 — observe the *effective* speed/pitch (per-voice
            // override, with global fallback) so a voice swap that
            // brings new override values triggers setSpeed/setPitch
            // automatically. distinctUntilChanged is still load-bearing
            // — same value re-emitting (e.g. settings hydration) would
            // otherwise rebuild the playback pipeline mid-sentence.
            settings.settings
                .map { it.effectiveSpeed }
                .distinctUntilChanged()
                .collect { controller.setSpeed(it) }
        }
        scope.launch {
            settings.settings
                .map { it.effectivePitch }
                .distinctUntilChanged()
                .collect { controller.setPitch(it) }
        }
        // Issue #98 Mode A — mirror warmupWait into the volatile cache so the
        // synchronous toUi mapper can read it without suspending. Same shape
        // as EnginePlayer's cachedBufferChunks.
        scope.launch {
            settings.settings
                .map { it.warmupWait }
                .distinctUntilChanged()
                .collect { cachedWarmupWait = it }
        }
        // Issue #150 — surface the shake-to-extend toggle into
        // PlaybackState so StoryvoxPlaybackService can gate the
        // accelerometer registration on it. Same seam shape as
        // speed/pitch/punctuation above.
        scope.launch {
            settings.settings
                .map { it.sleepShakeToExtendEnabled }
                .distinctUntilChanged()
                .collect { controller.setShakeToExtendEnabled(it) }
        }
    }

    /**
     * Position interpolation: the underlying [PlaybackState.charOffset] only
     * advances on sentence boundaries (or per-word if VoxSherpa fires
     * `onRangeStart`). To keep the slider moving smoothly we anchor on each
     * `charOffset` change and interpolate forward at the current speed using
     * the monotonic [SystemClock.elapsedRealtime] clock, ticking every 250ms
     * while playing. Pause freezes the displayed position; sentence
     * boundaries snap it back to the engine's truth.
     *
     * Shared across consumers so the `combine` + `toUi` allocation path runs
     * once per emission regardless of subscriber count, and so a config
     * change / screen recreation reattaches to the same upstream collector
     * within the 5 s `WhileSubscribed` grace window instead of restarting
     * the tick flow + re-running the anchor logic from scratch. `replay = 1`
     * gives a new subscriber the most recent `UiPlaybackState` immediately —
     * crucial for the reader UI, which would otherwise paint one frame of
     * stale state during a recomposition before the first new emission.
     */
    override val state: Flow<UiPlaybackState> = combine(
        controller.state,
        tickWhilePlaying(controller.state),
    ) { s, nowMs ->
        s.toUi(nowMs)
    }.shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    /**
     * Chapter body stream. Shared so a screen recreation (config change,
     * pane swap) doesn't restart the chapter-DB query — the upstream
     * `observeChapter(id)` Room flow stays alive for the 5 s
     * `WhileSubscribed` grace window. `replay = 1` lets the reader UI
     * render the body immediately on resubscribe instead of flickering
     * empty for the duration of one DB roundtrip.
     */
    override val chapterText: Flow<String> = controller.state
        .map { it.currentChapterId }
        .distinctUntilChanged()
        .flatMapLatest { id ->
            if (id == null) flowOf("") else chapters.observeChapter(id).map { it?.plainBody.orEmpty() }
        }
        .shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    override fun play() = controller.resume()
    override fun pause() = controller.pause()
    override fun seekTo(ms: Long) {
        // Issue #555 follow-up — the scrubber rail and duration both live on
        // the speed-invariant media-time axis (see EnginePlayer.estimateDurationMs
        // and DefaultPlaybackController.seekToPositionMs). The conversion must
        // use the baseline rate WITHOUT multiplying by speed, otherwise a seek
        // at 2x speed jumps twice as far as intended. Pre-fix this method
        // multiplied by `s.speed`, producing a seek overshoot proportional to
        // the playback speed — e.g. tapping the 50% mark at 2x landed at the
        // character offset that corresponds to 100% of the chapter.
        controller.seekToPositionMs(ms)
    }
    override fun seekToChar(charOffset: Int) {
        controller.seekTo(charOffset.coerceAtLeast(0))
    }
    override fun skipForward() = controller.skipForward30s()
    override fun skipBack() = controller.skipBack30s()
    override fun nextSentence() = controller.nextSentence()
    override fun previousSentence() = controller.previousSentence()
    override fun nextChapter() {
        scope.launch { controller.nextChapter() }
    }
    override fun previousChapter() {
        scope.launch { controller.previousChapter() }
    }
    override fun setSpeed(speed: Float) = controller.setSpeed(speed)
    override fun setPitch(pitch: Float) = controller.setPitch(pitch)
    override fun setPunctuationPauseMultiplier(multiplier: Float) =
        controller.setPunctuationPauseMultiplier(multiplier)

    override fun startSleepTimer(mode: UiSleepTimerMode) {
        val internal = when (mode) {
            is UiSleepTimerMode.Duration -> SleepTimerMode.Duration(mode.minutes)
            UiSleepTimerMode.EndOfChapter -> SleepTimerMode.EndOfChapter
        }
        controller.startSleepTimer(internal)
    }

    override fun cancelSleepTimer() = controller.cancelSleepTimer()

    /** Issue #189 — recap-aloud TTS pipeline state, mapped from
     *  core-playback's RecapPlaybackState onto the feature-side
     *  [UiRecapPlaybackState]. Distinct flow from [state] so the chapter
     *  playback observers don't recompose on every recap toggle. */
    override val recapPlayback: Flow<UiRecapPlaybackState> = controller.recapPlayback
        .map {
            when (it) {
                RecapPlaybackState.Idle -> UiRecapPlaybackState.Idle
                RecapPlaybackState.Speaking -> UiRecapPlaybackState.Speaking
            }
        }

    /** Calliope (v0.5.00) — forward the PlaybackController's events
     *  SharedFlow into the feature layer. Used by HybridReaderScreen
     *  to drive the milestone confetti easter-egg on the first
     *  natural [PlaybackUiEvent.ChapterDone] after a v0.5.00+ install.
     *  No mapping — the event types live in `:core-playback` which the
     *  feature module already depends on, and re-wrapping would just
     *  add a stale duplication seam. */
    override val events: Flow<PlaybackUiEvent> = controller.events

    /** Issue #805 — forward the typed engine state to the feature layer
     *  so the reader can show distinct error banners (auth / network /
     *  throttle / generic) rather than collapsing everything to a string. */
    override val engineState: Flow<`in`.jphe.storyvox.playback.EngineState> =
        controller.engineState

    /** "Why are we waiting?" — forward the AudioOutputMonitor's
     *  diagnostic flow through the UI contract. The monitor lives in
     *  core-playback as a Singleton; this adapter doesn't transform the
     *  value, just opens the seam for the feature layer to subscribe. */
    override val waitReason: Flow<`in`.jphe.storyvox.playback.diagnostics.WaitReason?> =
        controller.waitReason

    override suspend fun speakText(text: String) {
        controller.speakText(text)
    }

    override fun stopSpeaking() {
        controller.stopSpeaking()
    }

    // Issue #121 — bookmark fan-out. controller methods are suspend
    // because they touch ChapterRepository, but the UI contract is
    // fire-and-forget per the rest of this interface; we launch on the
    // shared scope and let the controller handle ordering.
    override fun bookmarkHere() {
        scope.launch { controller.bookmarkHere() }
    }

    override fun clearBookmark() {
        scope.launch { controller.clearBookmark() }
    }

    override fun jumpToBookmark() {
        scope.launch { controller.jumpToBookmark() }
    }

    override fun startListening(
        fictionId: String,
        chapterId: String,
        charOffset: Int,
        autoPlay: Boolean,
    ) {
        // Start the service synchronously while we still have the click's foreground
        // attribution. Calling startForegroundService from inside scope.launch (even on
        // Dispatchers.Main.immediate) lost the FG attribution on Android 12+ and threw
        // ForegroundServiceStartNotAllowedException — see f723e72.
        ContextCompat.startForegroundService(
            context,
            Intent(context, StoryvoxPlaybackService::class.java),
        )
        scope.launch {
            // Kick off the download (idempotent — WorkManager dedupes by uniqueName).
            // requireUnmetered=false: user just tapped Listen; honour their intent.
            android.util.Log.i("PlaybackBindings", "startListening: fiction=$fictionId chapter=$chapterId offset=$charOffset autoPlay=$autoPlay")
            chapters.queueChapterDownload(fictionId, chapterId, requireUnmetered = false)
            val ready = kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                chapters.observeChapter(chapterId).filterNotNull().first()
            }
            if (ready == null) {
                android.util.Log.e("PlaybackBindings", "startListening: chapter $chapterId body not ready within 30s — aborting")
                return@launch
            }
            controller.play(fictionId, chapterId, charOffset = charOffset)
            // #90 smart-resume — when the user explicitly paused the
            // last session, Library's Resume CTA loads the chapter
            // (above) but immediately re-pauses so playback waits for
            // an explicit play tap. Engine cold-load + 10s buffer
            // threshold means the audio output window for any leak
            // is essentially zero on slow voices like Piper-high.
            if (!autoPlay) controller.pause()
        }
    }

    /**
     * Anchor state for position interpolation between sentence-boundary
     * updates. `anchorElapsedMs` is in the [SystemClock.elapsedRealtime]
     * time base — see [tickWhilePlaying].
     */
    private var anchorChapterId: String? = null
    private var anchorCharOffset: Int = 0
    private var anchorElapsedMs: Long = 0L

    /**
     * Monotonic-clock source driving position interpolation between sentence
     * boundaries. Two regimes, gated by `isPlaying`:
     *
     *  - **Playing**: tick at 250 ms (the original cadence). Emits an
     *    immediate tick on play-resume so the slider snaps forward without
     *    waiting on the first delay window.
     *  - **Paused**: tick is suppressed *but* every upstream state emission
     *    re-emits a fresh [SystemClock.elapsedRealtime]. Required because
     *    `PlaybackState.toUi` re-anchors `anchorElapsedMs = nowMs` whenever
     *    the engine reports a new `charOffset` (seek, chapter switch). If
     *    the anchor were stuck at the pause-time tick, a seek-while-paused
     *    followed by play would interpret all the paused wall-time as
     *    elapsed playback time and lurch the scrubber forward (Copilot's
     *    PR #21 review). Emitting on each upstream change keeps the anchor
     *    fresh while still skipping the 4 Hz allocation storm of an
     *    unconditional ticker.
     *
     * Time base: [SystemClock.elapsedRealtime] is monotonic and counts
     * milliseconds since boot, including deep sleep. Unlike
     * [System.currentTimeMillis], it cannot jump backwards or forwards from
     * NTP corrections / user time changes, so a corrected wall clock can
     * never make the scrubber jitter, leap, or freeze. Both the tick source
     * and [anchorElapsedMs] use this time base so the subtraction in
     * [PlaybackState.toUi] stays consistent.
     *
     * Net effect for an open-but-idle reader (paused, no seeks): one tick on
     * the play→pause transition, then quiet until the user does something.
     */
    private fun tickWhilePlaying(stateFlow: Flow<PlaybackState>): Flow<Long> =
        stateFlow
            .map { it.isPlaying }
            .distinctUntilChanged()
            .flatMapLatest { playing ->
                if (playing) flow {
                    emit(SystemClock.elapsedRealtime())
                    while (true) {
                        kotlinx.coroutines.delay(250L)
                        emit(SystemClock.elapsedRealtime())
                    }
                } else stateFlow.map { SystemClock.elapsedRealtime() }
            }

    private fun PlaybackState.toUi(nowMs: Long): UiPlaybackState {
        // Issue #555 — duration + position both live on the speed-1
        // (media-time) axis now. [in.jphe.storyvox.playback.tts.EnginePlayer.estimateDurationMs]
        // returns the media-time duration; `baseMs` here converts the
        // engine's reported char-offset to media-time the same way (no
        // `* speed`). Wall-clock interpolation between sentence anchors
        // multiplies elapsed wall-clock-ms BY speed so the interpolated
        // media-time advances at the same rate as the engine's actual
        // playback through the chapter text — at speed=1.5, 1 s of
        // wall-clock = 1.5 s of media-time.
        //
        // Result: when the user taps a different speed chip mid-chapter,
        // the displayed position and total duration both stay rock-stable
        // (rail length doesn't shrink/grow, thumb pixel position holds).
        // The audit reported a ~19 s backward jump on a 1× → 1.5× tap;
        // this formula makes the symptom impossible at the UI seam.
        val baselineCharsPerSec = SPEED_BASELINE_CHARS_PER_SECOND
        // Re-anchor when the engine reports a new charOffset (sentence boundary,
        // seek, or chapter switch). Otherwise interpolate forward from the anchor
        // using wall time.
        if (currentChapterId != anchorChapterId || charOffset != anchorCharOffset) {
            anchorChapterId = currentChapterId
            anchorCharOffset = charOffset
            anchorElapsedMs = nowMs
        }
        val baseMs = if (baselineCharsPerSec > 0f) {
            ((charOffset / baselineCharsPerSec) * 1000f).toLong()
        } else 0L
        val sentence = currentSentenceRange
        // Freeze wall-time interpolation while the voice is warming up — i.e.
        // user hit play but no sentence audio has started yet. Otherwise the
        // scrubber slides forward during a 5-15s engine load even though no
        // audio has actually played.
        //
        // Issue #98 Mode A — when Warm-up Wait is OFF, treat the warmup
        // window as if playback had already started: the scrubber ticks from
        // t=0 even though no audio plays, and `isWarmingUp` reports false
        // so the UI doesn't show the spinner. The user trades the spinner
        // for visible "playing" feedback + silence.
        val rawWarmingUp = isPlaying && sentence == null
        val warmingUp = rawWarmingUp && cachedWarmupWait
        // Issue #555 — elapsed wall-clock-ms × speed = elapsed media-time-ms.
        // Without this scaling, interpolation would advance at wall-clock
        // rate while the engine plays at speed × wall-clock through the
        // chapter — the scrubber would underrun the audio at higher
        // speeds and overrun at lower speeds. Multiplying by speed keeps
        // the interpolated thumb in sync with what's actually being heard.
        val wallElapsedMs = if (isPlaying && !warmingUp) (nowMs - anchorElapsedMs).coerceAtLeast(0L) else 0L
        val elapsedMediaMs = (wallElapsedMs * speed).toLong()
        val positionMs = (baseMs + elapsedMediaMs).coerceAtMost(durationEstimateMs.coerceAtLeast(baseMs))
        return UiPlaybackState(
            fictionId = currentFictionId,
            chapterId = currentChapterId,
            chapterTitle = chapterTitle.orEmpty(),
            fictionTitle = bookTitle.orEmpty(),
            coverUrl = coverUri,
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            isWarmingUp = warmingUp,
            positionMs = positionMs,
            durationMs = durationEstimateMs,
            sentenceStart = sentence?.startCharInChapter ?: 0,
            sentenceEnd = sentence?.endCharInChapter ?: 0,
            speed = speed,
            pitch = pitch,
            voiceId = voiceId,
            voiceLabel = voiceId ?: "Default",
            sleepTimerRemainingMs = sleepTimerRemainingMs,
            // #373 — propagate the live-audio flag so AudiobookView /
            // Settings pitch slider can hide on KVMR + future audio
            // backends. Sourced from PlaybackState.isLiveAudioChapter
            // which EnginePlayer sets in loadAndPlayAudioStream.
            isLiveAudioChapter = isLiveAudioChapter,
        )
    }
}
