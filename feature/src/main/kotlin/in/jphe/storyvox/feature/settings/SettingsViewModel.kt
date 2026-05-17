package `in`.jphe.storyvox.feature.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.feature.api.AzureProbeResult
import `in`.jphe.storyvox.feature.api.CoverStyle
import `in`.jphe.storyvox.feature.api.UiBrassPulseLevel
import `in`.jphe.storyvox.feature.api.UiNetworkPatience
import `in`.jphe.storyvox.feature.api.UiParticleIntensity
import `in`.jphe.storyvox.feature.api.UiSkeletonStyle
import `in`.jphe.storyvox.feature.api.PalaceProbeResult
import `in`.jphe.storyvox.feature.api.ReadingDirection
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import `in`.jphe.storyvox.feature.api.SpeakChapterMode
import `in`.jphe.storyvox.feature.api.ThemeOverride
import `in`.jphe.storyvox.feature.api.UiLlmProvider
import `in`.jphe.storyvox.feature.api.UiSettings
import `in`.jphe.storyvox.feature.api.UiVoice
import `in`.jphe.storyvox.feature.api.VoiceProviderUi
import `in`.jphe.storyvox.llm.LlmRepository
import `in`.jphe.storyvox.llm.ProbeResult
import `in`.jphe.storyvox.llm.ProviderId
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class SettingsUiState(
    val settings: UiSettings? = null,
    val voices: List<UiVoice> = emptyList(),
    /** Last [`SettingsRepositoryUi.testPalaceConnection`] result, or null
     *  before the user has tried. Drives the inline status message under
     *  the Memory Palace section. */
    val palaceProbe: PalaceProbeResult? = null,
    /** True while a probe is in flight (button shows spinner). */
    val palaceProbing: Boolean = false,
    /** #182 — last [`SettingsRepositoryUi.testAzureConnection`] result,
     *  or null before the user has tried. Drives the inline status
     *  under the Cloud Voices → Azure section. */
    val azureProbe: AzureProbeResult? = null,
    /** True while an Azure test-connection is in flight. */
    val azureProbing: Boolean = false,
    /** Most recent Test-connection probe outcome. Settings UI
     *  surfaces this as a transient toast/message under the Test
     *  button. */
    val probeOutcome: ProbeOutcome? = null,
)

/** Stable-typed probe message for the AI Settings UI. Distinct from
 *  [ProbeResult] so the feature module doesn't expose :core-llm
 *  types directly to the UI layer (this VM is the conversion seam). */
sealed class ProbeOutcome {
    object Ok : ProbeOutcome()
    data class Failure(val message: String) : ProbeOutcome()
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepositoryUi,
    private val voices: VoiceProviderUi,
    private val llm: LlmRepository,
    /**
     * Accessibility scaffold Phase 2 (#488) — live snapshot of which
     * assistive services Android reports as active. Used by the
     * Accessibility subscreen to decide whether to surface the
     * "TalkBack isn't running" install nudge.
     *
     * Default uses [AccessibilityStateBridge]'s base interface impl
     * (which emits an empty/all-false [AccessibilityState]); test
     * harnesses that don't care about a11y rely on this default so
     * they don't have to pass a fake bridge.
     */
    private val accessibilityStateBridge: AccessibilityStateBridge = object : AccessibilityStateBridge {},
) : ViewModel() {

    /**
     * #488 — derived state for the Accessibility subscreen: TalkBack
     * is reported as active right now. Used by the install-nudge
     * card alongside `UiSettings.a11yTalkBackNudgeDismissed`.
     */
    val isTalkBackActive: StateFlow<Boolean> = accessibilityStateBridge.state
        .map { it.isTalkBackActive }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val palaceProbe = MutableStateFlow<PalaceProbeResult?>(null)
    private val palaceProbing = MutableStateFlow(false)
    private val _probe = MutableStateFlow<ProbeOutcome?>(null)
    private val azureProbe = MutableStateFlow<AzureProbeResult?>(null)
    private val azureProbing = MutableStateFlow(false)

    /**
     * Two-step combine: kotlinx.coroutines's typed [combine] caps at 5
     * heterogeneous flow inputs, and we have 7 once Azure's probe pair
     * lands (#182). Pre-combining the palace trio and the Azure pair
     * each into a single tuple flow drops us back under the 5-flow
     * ceiling without losing type safety.
     */
    private val palaceTrio = combine(palaceProbe, palaceProbing, _probe) { p, isProbing, probe ->
        Triple(p, isProbing, probe)
    }
    private val azurePair = combine(azureProbe, azureProbing) { p, isProbing -> p to isProbing }

    val uiState: StateFlow<SettingsUiState> = combine(
        repo.settings,
        voices.installedVoices,
        palaceTrio,
        azurePair,
    ) { settings, installed, palace, azure ->
        SettingsUiState(
            settings = settings,
            voices = installed,
            palaceProbe = palace.first,
            palaceProbing = palace.second,
            probeOutcome = palace.third,
            azureProbe = azure.first,
            azureProbing = azure.second,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setTheme(t: ThemeOverride) = viewModelScope.launch { repo.setTheme(t) }
    fun setSpeed(s: Float) = viewModelScope.launch { repo.setDefaultSpeed(s) }
    fun setPitch(p: Float) = viewModelScope.launch { repo.setDefaultPitch(p) }
    fun setDefaultVoice(id: String?) = viewModelScope.launch { repo.setDefaultVoice(id) }
    fun setWifiOnly(enabled: Boolean) = viewModelScope.launch { repo.setDownloadOnWifiOnly(enabled) }
    fun setPollHours(h: Int) = viewModelScope.launch { repo.setPollIntervalHours(h) }
    /** Issue #109 — continuous inter-sentence pause multiplier (was a
     *  3-stop selector under #93). Repo coerces to the engine's [0..4]
     *  range; the slider in the screen passes a raw Float. */
    fun setPunctuationPauseMultiplier(multiplier: Float) =
        viewModelScope.launch { repo.setPunctuationPauseMultiplier(multiplier) }

    /** Issue #193 — high-quality Sonic pitch interpolation toggle. */
    fun setPitchInterpolationHighQuality(enabled: Boolean) =
        viewModelScope.launch { repo.setPitchInterpolationHighQuality(enabled) }
    fun setPlaybackBufferChunks(n: Int) = viewModelScope.launch { repo.setPlaybackBufferChunks(n) }
    /** Issue #98 — Mode A toggle. */
    fun setWarmupWait(enabled: Boolean) = viewModelScope.launch { repo.setWarmupWait(enabled) }
    /** Issue #98 — Mode B toggle. */
    fun setCatchupPause(enabled: Boolean) = viewModelScope.launch { repo.setCatchupPause(enabled) }
    /** PCM cache PR-F (#86) — Mode C (Full Pre-render) toggle. PR-G surfaces
     *  this in [PerformanceSettingsScreen]; flipping ON drives PR-F's
     *  PrerenderModeWatcher to fan out background renders across every
     *  library fiction. */
    fun setFullPrerender(enabled: Boolean) =
        viewModelScope.launch { repo.setFullPrerender(enabled) }
    /** PCM cache PR-G (#86) — picker for the four discrete tiers. The
     *  repository snaps to the nearest tier and runs evictToQuota
     *  immediately so a tightened cap is honored on the spot. */
    fun setCacheQuotaBytes(bytes: Long) =
        viewModelScope.launch { repo.setCacheQuotaBytes(bytes) }
    /** PCM cache PR-G (#86) — destructive "Clear cache" affordance. The
     *  confirm dialog in [PerformanceSettingsScreen] gates this; here
     *  we forward without inspecting the bytes-freed return value
     *  (the live "Currently used" indicator surfaces the new state on
     *  the next 5 s poll). */
    fun clearCache() = viewModelScope.launch { repo.clearCache() }
    /** Issue #599 (v1.0) — manual reset of the first-launch welcome
     *  flow flag. Flips `pref_onboarding_completed_v1` back to false
     *  so the OnboardingHost shows the three-screen welcome again on
     *  the next composition. Strictly a QA / dress-rehearsal hook;
     *  surfaced in Settings → Developer. */
    fun resetOnboarding() = viewModelScope.launch { repo.resetOnboardingV1() }
    /** Issue #85 — Voice-Determinism preset (Steady / Expressive). */
    fun setVoiceSteady(enabled: Boolean) = viewModelScope.launch { repo.setVoiceSteady(enabled) }
    fun signIn() = viewModelScope.launch { repo.signIn() }
    fun signOut() = viewModelScope.launch { repo.signOut() }
    /** GitHub OAuth (#91) — local sign-out. Remote revoke deep-links to github.com. */
    fun signOutGitHub() = viewModelScope.launch { repo.signOutGitHub() }
    /** Issue #203 — toggle "Enable private repos." Takes effect on the
     *  next sign-in; existing sessions keep their original scope until
     *  the user re-runs Device Flow. */
    fun setGitHubPrivateReposEnabled(enabled: Boolean) =
        viewModelScope.launch { repo.setGitHubPrivateReposEnabled(enabled) }

    /**
     * Plugin-seam Phase 3 (#384) — single per-source on/off entry
     * point. Toggles by stable plugin id (see `SourceIds`); the
     * BrowseViewModel picker reads back the map. As of v0.5.31 this
     * is the only source-toggle setter — the Phase 1/2 per-backend
     * `setSourceXxxEnabled` methods have all collapsed into this one.
     */
    fun setSourcePluginEnabled(id: String, enabled: Boolean) =
        viewModelScope.launch { repo.setSourcePluginEnabled(id, enabled) }

    /** Issue #236 — RSS feed-list management. */
    fun addRssFeed(url: String) = viewModelScope.launch { repo.addRssFeed(url) }
    fun removeRssFeed(fictionId: String) = viewModelScope.launch { repo.removeRssFeed(fictionId) }
    /** Settings UI surfaces URLs (not fictionIds) — convert via the
     *  repo's removal-by-URL helper so the UI doesn't import
     *  source-rss internals. */
    fun removeRssFeedByUrl(url: String) = viewModelScope.launch { repo.removeRssFeedByUrl(url) }

    /** Issue #235 — local EPUB folder picker. */
    fun setEpubFolderUri(uri: String) = viewModelScope.launch { repo.setEpubFolderUri(uri) }
    fun clearEpubFolder() = viewModelScope.launch { repo.clearEpubFolder() }
    val epubFolderUri: kotlinx.coroutines.flow.StateFlow<String?> =
        repo.epubFolderUri.stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000),
            null,
        )

    fun setWikipediaLanguageCode(code: String) =
        viewModelScope.launch { repo.setWikipediaLanguageCode(code) }
    fun setNotionDatabaseId(id: String) =
        viewModelScope.launch { repo.setNotionDatabaseId(id) }
    fun setNotionApiToken(token: String?) =
        viewModelScope.launch { repo.setNotionApiToken(token) }
    fun setDiscordApiToken(token: String?) =
        viewModelScope.launch { repo.setDiscordApiToken(token) }
    fun setDiscordServer(serverId: String, serverName: String) =
        viewModelScope.launch { repo.setDiscordServer(serverId, serverName) }
    fun setDiscordCoalesceMinutes(minutes: Int) =
        viewModelScope.launch { repo.setDiscordCoalesceMinutes(minutes) }

    /** Hot stream of guilds the configured bot has been invited to.
     *  Refreshed manually via [refreshDiscordGuilds]; the UI calls
     *  this when the Discord card opens and after a token paste so a
     *  fresh-installed bot's invite list lands without an app
     *  restart. Empty list = "no token / fetch failed / nothing
     *  joined yet". */
    private val _discordGuilds = kotlinx.coroutines.flow.MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val discordGuilds: kotlinx.coroutines.flow.StateFlow<List<Pair<String, String>>> = _discordGuilds.asStateFlow()

    fun refreshDiscordGuilds() {
        viewModelScope.launch {
            _discordGuilds.value = repo.fetchDiscordGuilds()
        }
    }

    // ─── Telegram (#462) ─────────────────────────────────────────

    fun setTelegramApiToken(token: String?) =
        viewModelScope.launch { repo.setTelegramApiToken(token) }

    /** Authenticated bot identity (@username, or null when unset /
     *  bad token / network out). Refreshed by [refreshTelegramProbe];
     *  the UI calls that when the Telegram card opens or after a
     *  token paste. Null reflects every failure path; the UI maps to
     *  the unified "Not signed in" empty state. */
    private val _telegramBotUsername =
        kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val telegramBotUsername: kotlinx.coroutines.flow.StateFlow<String?> =
        _telegramBotUsername.asStateFlow()

    /** Channels the bot has observed via getUpdates this session.
     *  Same shape as [discordGuilds] — (chatId, title) pairs the UI
     *  surfaces as the channel list. Empty when no token, no
     *  observed activity, or a probe failure. */
    private val _telegramChannels =
        kotlinx.coroutines.flow.MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val telegramChannels: kotlinx.coroutines.flow.StateFlow<List<Pair<String, String>>> =
        _telegramChannels.asStateFlow()

    /** Probe both getMe + getUpdates → getChat. Drives the Settings
     *  card's "Authenticated as @bot · sees N channels" line. */
    fun refreshTelegramProbe() {
        viewModelScope.launch {
            _telegramBotUsername.value = repo.probeTelegramBot()
            _telegramChannels.value = repo.fetchTelegramChannels()
        }
    }

    fun setOutlineHost(host: String) = viewModelScope.launch { repo.setOutlineHost(host) }
    fun setOutlineApiKey(apiKey: String) = viewModelScope.launch { repo.setOutlineApiKey(apiKey) }
    fun clearOutlineConfig() = viewModelScope.launch { repo.clearOutlineConfig() }
    val outlineHost: kotlinx.coroutines.flow.StateFlow<String> =
        repo.outlineHost.stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000),
            "",
        )

    /** Issue #246 — curated suggested RSS feeds, fetched from the
     *  jphein/storyvox-feeds GitHub repo at runtime. Falls back to
     *  the baked-in seed list while the fetch is in flight or if it
     *  fails. */
    val suggestedRssFeeds: kotlinx.coroutines.flow.StateFlow<List<`in`.jphe.storyvox.feature.api.SuggestedFeed>> =
        repo.suggestedRssFeeds.stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000),
            BAKED_IN_SUGGESTED_FEEDS,
        )
    val rssSubscriptions: kotlinx.coroutines.flow.StateFlow<List<String>> =
        repo.rssSubscriptions.stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000),
            emptyList(),
        )

    /** Issue #150 — sleep timer shake-to-extend on/off. */
    fun setSleepShakeToExtendEnabled(enabled: Boolean) =
        viewModelScope.launch { repo.setSleepShakeToExtendEnabled(enabled) }

    /** Vesper (v0.4.97) — debug overlay master switch. */
    fun setShowDebugOverlay(enabled: Boolean) =
        viewModelScope.launch { repo.setShowDebugOverlay(enabled) }

    fun previewVoice(voice: UiVoice) = voices.previewVoice(voice)

    // ─── Memory Palace (#79) ────────────────────────────────────────────
    fun setPalaceHost(host: String) = viewModelScope.launch {
        repo.setPalaceHost(host)
        // Clear any previous probe result — the user is changing the
        // address so the previous status is no longer authoritative.
        palaceProbe.value = null
    }

    fun setPalaceApiKey(apiKey: String) = viewModelScope.launch {
        repo.setPalaceApiKey(apiKey)
        palaceProbe.value = null
    }

    fun clearPalaceConfig() = viewModelScope.launch {
        repo.clearPalaceConfig()
        palaceProbe.value = null
    }

    fun testPalaceConnection() = viewModelScope.launch {
        if (palaceProbing.value) return@launch
        palaceProbing.value = true
        try {
            palaceProbe.value = repo.testPalaceConnection()
        } finally {
            palaceProbing.value = false
        }
    }

    // ─── Azure Speech Services BYOK (#182) ──────────────────────────
    fun setAzureKey(key: String?) = viewModelScope.launch {
        repo.setAzureKey(key)
        // Reset the probe — the user is editing the key, so the
        // previous test result is no longer authoritative.
        azureProbe.value = null
    }

    fun setAzureRegion(regionId: String) = viewModelScope.launch {
        repo.setAzureRegion(regionId)
        azureProbe.value = null
    }

    fun clearAzureCredentials() = viewModelScope.launch {
        repo.clearAzureCredentials()
        azureProbe.value = null
    }

    fun testAzureConnection() = viewModelScope.launch {
        if (azureProbing.value) return@launch
        azureProbing.value = true
        try {
            azureProbe.value = repo.testAzureConnection()
        } finally {
            azureProbing.value = false
        }
    }

    /** PR-6 (#185) — Azure offline-fallback toggle + voice picker. */
    fun setAzureFallbackEnabled(enabled: Boolean) =
        viewModelScope.launch { repo.setAzureFallbackEnabled(enabled) }
    fun setAzureFallbackVoiceId(voiceId: String?) =
        viewModelScope.launch { repo.setAzureFallbackVoiceId(voiceId) }

    /** Tier 3 (#88) — experimental parallel-synth instance count
     *  (range 1..8). 1 = serial (default), >=2 fans out across N
     *  engine instances. */
    fun setParallelSynthInstances(count: Int) =
        viewModelScope.launch { repo.setParallelSynthInstances(count) }

    /** Tier 3 companion — numThreads override per engine. 0 = Auto. */
    fun setSynthThreadsPerInstance(count: Int) =
        viewModelScope.launch { repo.setSynthThreadsPerInstance(count) }

    // ── AI settings (issue #81) ────────────────────────────────────

    fun setAiProvider(provider: UiLlmProvider?) = viewModelScope.launch {
        // Spec-only providers can be persisted (they're enum values)
        // but the runtime providers will throw NotConfigured if used.
        // The UI's "coming soon" rows already prevent that path.
        repo.setAiProvider(provider)
    }
    fun setClaudeApiKey(key: String?) = viewModelScope.launch { repo.setClaudeApiKey(key) }
    fun setClaudeModel(model: String) = viewModelScope.launch { repo.setClaudeModel(model) }
    fun setOpenAiApiKey(key: String?) = viewModelScope.launch { repo.setOpenAiApiKey(key) }
    fun setOpenAiModel(model: String) = viewModelScope.launch { repo.setOpenAiModel(model) }
    fun setOllamaBaseUrl(url: String) = viewModelScope.launch { repo.setOllamaBaseUrl(url) }
    fun setOllamaModel(model: String) = viewModelScope.launch { repo.setOllamaModel(model) }
    fun setVertexApiKey(key: String?) = viewModelScope.launch { repo.setVertexApiKey(key) }
    fun setVertexModel(model: String) = viewModelScope.launch { repo.setVertexModel(model) }

    /**
     * Issue #219 — install or clear a Vertex service-account JSON.
     * The repository call is allowed to throw [IllegalArgumentException]
     * on a malformed blob; we surface the message via [vertexSaError]
     * so the Settings UI can show a transient red inline message
     * under the file-picker row. Success clears the error.
     */
    fun setVertexServiceAccountJson(json: String?) = viewModelScope.launch {
        try {
            repo.setVertexServiceAccountJson(json)
            _vertexSaError.value = null
        } catch (e: IllegalArgumentException) {
            _vertexSaError.value = e.message ?: "Invalid service-account JSON"
        }
    }

    fun clearVertexSaError() { _vertexSaError.value = null }
    private val _vertexSaError = MutableStateFlow<String?>(null)
    /** Last SA-JSON parse/save failure, or null after a successful
     *  install or once the user dismisses it. Settings UI binds this
     *  to a transient inline error row. */
    val vertexSaError: StateFlow<String?> = _vertexSaError.asStateFlow()
    fun setFoundryApiKey(key: String?) = viewModelScope.launch { repo.setFoundryApiKey(key) }
    fun setFoundryEndpoint(url: String) = viewModelScope.launch { repo.setFoundryEndpoint(url) }
    fun setFoundryDeployment(deployment: String) =
        viewModelScope.launch { repo.setFoundryDeployment(deployment) }
    fun setFoundryServerless(serverless: Boolean) =
        viewModelScope.launch { repo.setFoundryServerless(serverless) }
    fun setBedrockAccessKey(key: String?) = viewModelScope.launch { repo.setBedrockAccessKey(key) }
    fun setBedrockSecretKey(key: String?) = viewModelScope.launch { repo.setBedrockSecretKey(key) }
    fun setBedrockRegion(region: String) = viewModelScope.launch { repo.setBedrockRegion(region) }
    fun setBedrockModel(model: String) = viewModelScope.launch { repo.setBedrockModel(model) }
    fun setSendChapterTextEnabled(enabled: Boolean) =
        viewModelScope.launch { repo.setSendChapterTextEnabled(enabled) }
    // Issue #212 — chat grounding-level toggles.
    fun setChatGroundChapterTitle(enabled: Boolean) =
        viewModelScope.launch { repo.setChatGroundChapterTitle(enabled) }
    fun setChatGroundCurrentSentence(enabled: Boolean) =
        viewModelScope.launch { repo.setChatGroundCurrentSentence(enabled) }
    fun setChatGroundEntireChapter(enabled: Boolean) =
        viewModelScope.launch { repo.setChatGroundEntireChapter(enabled) }
    fun setChatGroundEntireBookSoFar(enabled: Boolean) =
        viewModelScope.launch { repo.setChatGroundEntireBookSoFar(enabled) }
    /** Issue #217 — "Carry memory across fictions" toggle. */
    fun setCarryMemoryAcrossFictions(enabled: Boolean) =
        viewModelScope.launch { repo.setCarryMemoryAcrossFictions(enabled) }
    /** Issue #216 — "Allow the AI to take actions" toggle. The chat
     *  ViewModel reads [UiAiSettings.actionsEnabled] at send-time and
     *  switches between the tool-aware and plain-text chat paths. */
    fun setAiActionsEnabled(enabled: Boolean) =
        viewModelScope.launch { repo.setAiActionsEnabled(enabled) }
    fun acknowledgeAiPrivacy() = viewModelScope.launch { repo.acknowledgeAiPrivacy() }
    /** Anthropic Teams (OAuth) — local sign-out. Remote revoke deep-links
     *  to claude.ai/settings. (#181) */
    fun signOutTeams() = viewModelScope.launch { repo.signOutTeams() }
    fun resetAiSettings() = viewModelScope.launch {
        repo.resetAiSettings()
        _probe.value = null
    }

    /** Run the probe for the named UI provider. UI surfaces the
     *  outcome as a one-shot toast/message under the button. */
    fun testAiConnection(uiProvider: UiLlmProvider) = viewModelScope.launch {
        _probe.value = null
        val result = llm.probe(uiProvider.toCoreId())
        _probe.value = when (result) {
            ProbeResult.Ok -> ProbeOutcome.Ok
            is ProbeResult.AuthError -> ProbeOutcome.Failure(result.message)
            is ProbeResult.Misconfigured -> ProbeOutcome.Failure(result.message)
            is ProbeResult.NotReachable -> ProbeOutcome.Failure(result.message)
        }
    }

    fun clearProbeOutcome() { _probe.value = null }

    // ── Issue #383 — Inbox per-source mute toggles ─────────────────
    fun setInboxNotifyRoyalRoad(enabled: Boolean) =
        viewModelScope.launch { repo.setInboxNotifyRoyalRoad(enabled) }
    fun setInboxNotifyKvmr(enabled: Boolean) =
        viewModelScope.launch { repo.setInboxNotifyKvmr(enabled) }
    fun setInboxNotifyWikipedia(enabled: Boolean) =
        viewModelScope.launch { repo.setInboxNotifyWikipedia(enabled) }

    // ── Accessibility scaffold (Phase 1) ───────────────────────────
    // Phase 1 forwards the user's intent to the repo; no behavior is
    // wired yet. Phase 2 agents read [UiSettings.a11y*] + the
    // [AccessibilityStateBridge] flow to actually adapt the app.
    fun setA11yHighContrast(enabled: Boolean) =
        viewModelScope.launch { repo.setA11yHighContrast(enabled) }
    fun setA11yReducedMotion(enabled: Boolean) =
        viewModelScope.launch { repo.setA11yReducedMotion(enabled) }
    fun setA11yLargerTouchTargets(enabled: Boolean) =
        viewModelScope.launch { repo.setA11yLargerTouchTargets(enabled) }
    fun setA11yScreenReaderPauseMs(ms: Int) =
        viewModelScope.launch { repo.setA11yScreenReaderPauseMs(ms) }
    fun setA11ySpeakChapterMode(mode: SpeakChapterMode) =
        viewModelScope.launch { repo.setA11ySpeakChapterMode(mode) }
    fun setA11yFontScaleOverride(scale: Float) =
        viewModelScope.launch { repo.setA11yFontScaleOverride(scale) }
    fun setA11yReadingDirection(direction: ReadingDirection) =
        viewModelScope.launch { repo.setA11yReadingDirection(direction) }
    /**
     * Accessibility scaffold Phase 2 (#488) — one-shot TalkBack
     * install-nudge dismissal. The Accessibility subscreen surfaces a
     * dismissible card when TalkBack isn't running; tapping the
     * dismiss button flips this flag forever for this install.
     */
    fun setA11yTalkBackNudgeDismissed(dismissed: Boolean) =
        viewModelScope.launch { repo.setA11yTalkBackNudgeDismissed(dismissed) }

    /** v0.5.59 — book-cover fallback style. See [`CoverStyle`]. */
    fun setCoverStyle(style: CoverStyle) =
        viewModelScope.launch { repo.setCoverStyle(style) }

    /**
     * Issue #589 — global animation-speed master multiplier.
     * Snapped to the supported chip values on write.
     */
    fun setAnimationSpeedScale(scale: Float) =
        viewModelScope.launch { repo.setAnimationSpeedScale(scale) }

    /** Issue #593 — skip-forward / skip-back distance in seconds. */
    fun setSkipDistanceSec(seconds: Int) =
        viewModelScope.launch { repo.setSkipDistanceSec(seconds) }

    /** Issue #594 — SkipPrevious rewind-to-start threshold in seconds.
     *  0 = disabled (always jumps to previous chapter). */
    fun setRewindToStartThresholdSec(seconds: Int) =
        viewModelScope.launch { repo.setRewindToStartThresholdSec(seconds) }

    /** Issue #595 — sleep-timer shake-to-extend in minutes. */
    fun setSleepShakeExtendMinutes(minutes: Int) =
        viewModelScope.launch { repo.setSleepShakeExtendMinutes(minutes) }

    /** Issue #596 — PCM-cache pre-render window size in chapters. */
    fun setPrerenderChapterCount(count: Int) =
        viewModelScope.launch { repo.setPrerenderChapterCount(count) }

    /** Issue #590 — particle / confetti intensity. */
    fun setParticleIntensity(intensity: UiParticleIntensity) =
        viewModelScope.launch { repo.setParticleIntensity(intensity) }

    /** Issue #591 — skeleton shimmer style. */
    fun setSkeletonStyle(style: UiSkeletonStyle) =
        viewModelScope.launch { repo.setSkeletonStyle(style) }

    /** Issue #592 — brass alpha-pulse intensity. */
    fun setBrassPulseLevel(level: UiBrassPulseLevel) =
        viewModelScope.launch { repo.setBrassPulseLevel(level) }

    /** Issue #597 — network patience preset. */
    fun setNetworkPatience(patience: UiNetworkPatience) =
        viewModelScope.launch { repo.setNetworkPatience(patience) }

    /** Issue #598 — Android Auto bucket size. */
    fun setAutoItemsPerCategory(count: Int) =
        viewModelScope.launch { repo.setAutoItemsPerCategory(count) }
}

/** Map the feature-layer enum to the :core-llm enum. The two are
 *  kept in lockstep — when a new value is added in one, the other
 *  must follow. */
private fun UiLlmProvider.toCoreId(): ProviderId = when (this) {
    UiLlmProvider.Claude -> ProviderId.Claude
    UiLlmProvider.OpenAi -> ProviderId.OpenAi
    UiLlmProvider.Ollama -> ProviderId.Ollama
    UiLlmProvider.Bedrock -> ProviderId.Bedrock
    UiLlmProvider.Vertex -> ProviderId.Vertex
    UiLlmProvider.Foundry -> ProviderId.Foundry
    UiLlmProvider.Teams -> ProviderId.Teams
}
