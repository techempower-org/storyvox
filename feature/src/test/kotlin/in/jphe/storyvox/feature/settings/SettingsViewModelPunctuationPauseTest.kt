package `in`.jphe.storyvox.feature.settings

import `in`.jphe.storyvox.feature.api.BUFFER_DEFAULT_CHUNKS
import `in`.jphe.storyvox.feature.api.PUNCTUATION_PAUSE_DEFAULT_MULTIPLIER
import `in`.jphe.storyvox.feature.api.PUNCTUATION_PAUSE_LONG_MULTIPLIER
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import `in`.jphe.storyvox.feature.api.ThemeOverride
import `in`.jphe.storyvox.feature.api.UiLlmProvider
import `in`.jphe.storyvox.feature.api.UiSettings
import `in`.jphe.storyvox.feature.api.UiSigil
import `in`.jphe.storyvox.feature.api.UiVoice
import `in`.jphe.storyvox.feature.api.VoiceProviderUi
import `in`.jphe.storyvox.llm.LlmConfig
import `in`.jphe.storyvox.llm.LlmRepository
import `in`.jphe.storyvox.llm.provider.AzureFoundryProvider
import `in`.jphe.storyvox.llm.auth.AnthropicTeamsAuthApi
import `in`.jphe.storyvox.llm.auth.AnthropicTeamsAuthRepository
import `in`.jphe.storyvox.llm.provider.AnthropicTeamsProvider
import `in`.jphe.storyvox.llm.provider.BedrockProvider
import `in`.jphe.storyvox.llm.provider.ClaudeApiProvider
import `in`.jphe.storyvox.llm.provider.OllamaProvider
import `in`.jphe.storyvox.llm.provider.OpenAiApiProvider
import `in`.jphe.storyvox.llm.provider.VertexProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Verifies the issue #109 punctuation-pause continuous slider on
 * [SettingsViewModel] forwards the float to the repository contract and
 * surfaces the repository's emission via [SettingsUiState.settings].
 *
 * Mirrors [SettingsViewModelBufferTest]'s shape — same Fake repo
 * scaffolding, same flow-collection pattern.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelPunctuationPauseTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `setPunctuationPauseMultiplier forwards to the repository`() = runTest {
        val repo = FakeSettingsRepo(
            initial = baseSettings(multiplier = PUNCTUATION_PAUSE_DEFAULT_MULTIPLIER),
        )
        val vm = SettingsViewModel(repo, FakeVoiceProvider(), fakeLlm())

        vm.setPunctuationPauseMultiplier(2.5f)

        assertEquals(listOf(2.5f), repo.punctuationPauseMultiplierWrites)
    }

    @Test
    fun `viewmodel uiState surfaces repository's punctuation pause multiplier`() = runTest {
        val repo = FakeSettingsRepo(
            initial = baseSettings(multiplier = PUNCTUATION_PAUSE_LONG_MULTIPLIER),
        )
        val vm = SettingsViewModel(repo, FakeVoiceProvider(), fakeLlm())

        val emitted = vm.uiState.first { it.settings != null }
        assertEquals(
            PUNCTUATION_PAUSE_LONG_MULTIPLIER,
            emitted.settings?.punctuationPauseMultiplier ?: Float.NaN,
            0.0001f,
        )
    }

    @Test
    fun `viewmodel forwards values past the legacy Long stop`() = runTest {
        // The whole point of #109 is the slider goes wider than the legacy
        // 1.75× ceiling. The ViewModel must not clamp; that's the repo's job
        // (it applies the engine's [0..4] range).
        val repo = FakeSettingsRepo(
            initial = baseSettings(multiplier = PUNCTUATION_PAUSE_DEFAULT_MULTIPLIER),
        )
        val vm = SettingsViewModel(repo, FakeVoiceProvider(), fakeLlm())

        vm.setPunctuationPauseMultiplier(3.5f)

        assertEquals(listOf(3.5f), repo.punctuationPauseMultiplierWrites)
    }

    private fun baseSettings(multiplier: Float): UiSettings = UiSettings(
        ttsEngine = "VoxSherpa",
        defaultVoiceId = null,
        defaultSpeed = 1.0f,
        defaultPitch = 1.0f,
        themeOverride = ThemeOverride.System,
        downloadOnWifiOnly = true,
        pollIntervalHours = 6,
        isSignedIn = false,
        sigil = UiSigil.UNKNOWN,
        playbackBufferChunks = BUFFER_DEFAULT_CHUNKS,
        punctuationPauseMultiplier = multiplier,
    )

    private class FakeSettingsRepo(initial: UiSettings) : SettingsRepositoryUi {
        private val state = MutableStateFlow(initial)
        override val settings: Flow<UiSettings> = state
        val punctuationPauseMultiplierWrites: MutableList<Float> = mutableListOf()
        override suspend fun setTheme(override: ThemeOverride) {
            state.value = state.value.copy(themeOverride = override)
        }
        override suspend fun setDefaultSpeed(speed: Float) {
            state.value = state.value.copy(defaultSpeed = speed)
        }
        override suspend fun setDefaultPitch(pitch: Float) {
            state.value = state.value.copy(defaultPitch = pitch)
        }
        override suspend fun setDefaultVoice(voiceId: String?) {
            state.value = state.value.copy(defaultVoiceId = voiceId)
        }
        override suspend fun setDownloadOnWifiOnly(enabled: Boolean) {
            state.value = state.value.copy(downloadOnWifiOnly = enabled)
        }
        override suspend fun setPollIntervalHours(hours: Int) {
            state.value = state.value.copy(pollIntervalHours = hours)
        }
        override suspend fun setPunctuationPauseMultiplier(multiplier: Float) {
            punctuationPauseMultiplierWrites += multiplier
            state.value = state.value.copy(punctuationPauseMultiplier = multiplier)
        }
        override suspend fun setPitchInterpolationHighQuality(enabled: Boolean) = Unit
        override suspend fun setVoiceLexicon(voiceId: String, path: String?) = Unit
        override suspend fun setVoicePhonemizerLang(voiceId: String, langCode: String?) = Unit
        override suspend fun setPlaybackBufferChunks(chunks: Int) {
            state.value = state.value.copy(playbackBufferChunks = chunks)
        }
        override suspend fun setWarmupWait(enabled: Boolean) {
            state.value = state.value.copy(warmupWait = enabled)
        }
        override suspend fun setCatchupPause(enabled: Boolean) {
            state.value = state.value.copy(catchupPause = enabled)
        }
        override suspend fun setFullPrerender(enabled: Boolean) {
            state.value = state.value.copy(fullPrerender = enabled)
        }
        override suspend fun setCacheQuotaBytes(bytes: Long) {
            state.value = state.value.copy(cacheQuotaBytes = bytes)
        }
        override suspend fun clearCache(): Long {
            val before = state.value.cacheUsedBytes
            state.value = state.value.copy(cacheUsedBytes = 0L)
            return before
        }
        override suspend fun setVoiceSteady(enabled: Boolean) {
            state.value = state.value.copy(voiceSteady = enabled)
        }
        override suspend fun signIn() = Unit
        override suspend fun signOut() = Unit
        // Memory Palace stubs (#79) — punctuation-pause-test fixture doesn't
        // exercise the palace surface; keep them no-op + return NotConfigured.
        override suspend fun setPalaceHost(host: String) = Unit
        override suspend fun setPalaceApiKey(apiKey: String) = Unit
        override suspend fun clearPalaceConfig() = Unit
        override suspend fun testPalaceConnection():
            `in`.jphe.storyvox.feature.api.PalaceProbeResult =
            `in`.jphe.storyvox.feature.api.PalaceProbeResult.NotConfigured

        // ── AI no-ops (#81) — punctuation-pause-test fixture doesn't exercise these. ──
        override suspend fun setAiProvider(provider: UiLlmProvider?) = Unit
        override suspend fun setClaudeApiKey(key: String?) = Unit
        override suspend fun setClaudeModel(model: String) = Unit
        override suspend fun setOpenAiApiKey(key: String?) = Unit
        override suspend fun setOpenAiModel(model: String) = Unit
        override suspend fun setOllamaBaseUrl(url: String) = Unit
        override suspend fun setOllamaModel(model: String) = Unit
        override suspend fun setVertexApiKey(key: String?) = Unit
        override suspend fun setVertexModel(model: String) = Unit
        override suspend fun setVertexServiceAccountJson(json: String?) = Unit
        override suspend fun setFoundryApiKey(key: String?) = Unit
        override suspend fun setFoundryEndpoint(url: String) = Unit
        override suspend fun setFoundryDeployment(deployment: String) = Unit
        override suspend fun setFoundryServerless(serverless: Boolean) = Unit
        override suspend fun setBedrockAccessKey(key: String?) = Unit
        override suspend fun setBedrockSecretKey(key: String?) = Unit
        override suspend fun setBedrockRegion(region: String) = Unit
        override suspend fun setBedrockModel(model: String) = Unit
        override suspend fun setSendChapterTextEnabled(enabled: Boolean) = Unit
        override suspend fun setChatGroundChapterTitle(enabled: Boolean) = Unit
        override suspend fun setChatGroundCurrentSentence(enabled: Boolean) = Unit
        override suspend fun setChatGroundEntireChapter(enabled: Boolean) = Unit
        override suspend fun setChatGroundEntireBookSoFar(enabled: Boolean) = Unit
        override suspend fun setCarryMemoryAcrossFictions(enabled: Boolean) = Unit
        override suspend fun setAiActionsEnabled(enabled: Boolean) = Unit
        override suspend fun acknowledgeAiPrivacy() = Unit
        override suspend fun resetAiSettings() = Unit
        override suspend fun signOutGitHub() = Unit
        override suspend fun setGitHubPrivateReposEnabled(enabled: Boolean) = Unit
        override suspend fun addRssFeed(url: String) = Unit
        override suspend fun removeRssFeed(fictionId: String) = Unit
        override suspend fun removeRssFeedByUrl(url: String) = Unit
        override val rssSubscriptions: kotlinx.coroutines.flow.Flow<List<String>> = kotlinx.coroutines.flow.flowOf(emptyList())
        override val epubFolderUri: kotlinx.coroutines.flow.Flow<String?> = kotlinx.coroutines.flow.flowOf(null)
        override suspend fun setEpubFolderUri(uri: String) = Unit
        override suspend fun clearEpubFolder() = Unit
        override suspend fun setWikipediaLanguageCode(code: String) = Unit
        override suspend fun setDiscordApiToken(token: String?) = Unit
        override suspend fun setDiscordServer(serverId: String, serverName: String) = Unit
        override suspend fun setDiscordCoalesceMinutes(minutes: Int) = Unit
        override suspend fun fetchDiscordGuilds(): List<Pair<String, String>> = emptyList()
        override suspend fun setTelegramApiToken(token: String?) = Unit
        override suspend fun probeTelegramBot(): String? = null
        override suspend fun fetchTelegramChannels(): List<Pair<String, String>> = emptyList()
        override suspend fun setSourcePluginEnabled(id: String, enabled: Boolean) = Unit
        override suspend fun setSourceFavorite(id: String, favorite: Boolean) = Unit
        override suspend fun setSourceDisplayOrder(order: List<String>) = Unit
        override suspend fun setVoiceFamilyEnabled(id: String, enabled: Boolean) = Unit
        override suspend fun setNotionDatabaseId(id: String) = Unit
        override suspend fun setNotionApiToken(token: String?) = Unit
        override val outlineHost: kotlinx.coroutines.flow.Flow<String> = kotlinx.coroutines.flow.flowOf("")
        override suspend fun setOutlineHost(host: String) = Unit
        override suspend fun setOutlineApiKey(apiKey: String) = Unit
        override suspend fun clearOutlineConfig() = Unit
        override val suggestedRssFeeds: kotlinx.coroutines.flow.Flow<List<`in`.jphe.storyvox.feature.api.SuggestedFeed>> = kotlinx.coroutines.flow.flowOf(emptyList())
        override suspend fun setSleepShakeToExtendEnabled(enabled: Boolean) = Unit
        override suspend fun setAzureKey(key: String?) = Unit
        override suspend fun setAzureRegion(regionId: String) = Unit
        override suspend fun clearAzureCredentials() = Unit
        override suspend fun setParallelSynthInstances(count: Int) = Unit
        override suspend fun setSynthThreadsPerInstance(count: Int) = Unit
        override suspend fun setAzureFallbackEnabled(enabled: Boolean) = Unit
        override suspend fun setAzureFallbackVoiceId(voiceId: String?) = Unit
        override suspend fun testAzureConnection(): `in`.jphe.storyvox.feature.api.AzureProbeResult =
            `in`.jphe.storyvox.feature.api.AzureProbeResult.NotConfigured
        override suspend fun signOutTeams() = Unit
    }

    private class FakeVoiceProvider : VoiceProviderUi {
        override val installedVoices: Flow<List<UiVoice>> = flowOf(emptyList())
        override fun previewVoice(voice: UiVoice) = Unit
    }

    /** Construct an LlmRepository with three real-but-stubbed
     *  provider instances. The punctuation tests don't call any LLM
     *  methods, so we just need an LlmRepository that doesn't blow up
     *  at construction. Mirrors [SettingsViewModelBufferTest.fakeLlm]. */
    private fun fakeLlm(): LlmRepository {
        val cfg = flowOf(LlmConfig())
        val store = `in`.jphe.storyvox.llm.LlmCredentialsStore.forTesting()
        val http = OkHttpClient()
        val json = Json
        return LlmRepository(
            configFlow = cfg,
            claude = ClaudeApiProvider(http, store, cfg, json),
            openAi = OpenAiApiProvider(http, store, cfg, json),
            ollama = OllamaProvider(http, cfg, json),
            vertex = VertexProvider(
                http, store, cfg, json,
                `in`.jphe.storyvox.llm.auth.GoogleOAuthTokenSource(http),
            ),
            foundry = AzureFoundryProvider(http, store, cfg, json),
            bedrock = BedrockProvider(http, store, cfg, json),
            teams = AnthropicTeamsProvider(
                http, store, cfg,
                AnthropicTeamsAuthApi(http),
                AnthropicTeamsAuthRepository(store),
                json,
            ),
        )
    }
}
