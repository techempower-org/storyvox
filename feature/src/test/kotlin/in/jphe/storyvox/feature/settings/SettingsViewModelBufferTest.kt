package `in`.jphe.storyvox.feature.settings

import `in`.jphe.storyvox.feature.api.BUFFER_DEFAULT_CHUNKS
import `in`.jphe.storyvox.feature.api.BUFFER_RECOMMENDED_MAX_CHUNKS
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
 * Verifies that [SettingsViewModel.setPlaybackBufferChunks] forwards the
 * value to the repository contract and that the ViewModel's exposed
 * [SettingsUiState.settings] reflects what the repository emits.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelBufferTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `setPlaybackBufferChunks forwards to the repository`() = runTest {
        val repo = FakeSettingsRepo(initial = baseSettings(buffer = BUFFER_DEFAULT_CHUNKS))
        val vm = SettingsViewModel(repo, FakeVoiceProvider(), fakeLlm())

        vm.setPlaybackBufferChunks(192)

        assertEquals(listOf(192), repo.bufferWrites)
    }

    @Test
    fun `viewmodel uiState surfaces repository's buffer value`() = runTest {
        val repo = FakeSettingsRepo(initial = baseSettings(buffer = 256))
        val vm = SettingsViewModel(repo, FakeVoiceProvider(), fakeLlm())

        // The shared flow's WhileSubscribed needs an active subscriber; first()
        // covers that for the duration of the read.
        val emitted = vm.uiState.first { it.settings != null }
        assertEquals(256, emitted.settings?.playbackBufferChunks)
    }

    @Test
    fun `viewmodel allows past-the-tick values`() = runTest {
        // Issue #84 — the ViewModel must not clamp at the recommended max;
        // that's the whole point of the experimental probe. Repo is the only
        // layer that applies the absolute mechanical bounds.
        val repo = FakeSettingsRepo(initial = baseSettings(buffer = BUFFER_DEFAULT_CHUNKS))
        val vm = SettingsViewModel(repo, FakeVoiceProvider(), fakeLlm())

        vm.setPlaybackBufferChunks(BUFFER_RECOMMENDED_MAX_CHUNKS * 8)

        assertEquals(listOf(BUFFER_RECOMMENDED_MAX_CHUNKS * 8), repo.bufferWrites)
    }

    private fun baseSettings(buffer: Int): UiSettings = UiSettings(
        ttsEngine = "VoxSherpa",
        defaultVoiceId = null,
        defaultSpeed = 1.0f,
        defaultPitch = 1.0f,
        themeOverride = ThemeOverride.System,
        downloadOnWifiOnly = true,
        pollIntervalHours = 6,
        isSignedIn = false,
        sigil = UiSigil.UNKNOWN,
        playbackBufferChunks = buffer,
    )

    private class FakeSettingsRepo(initial: UiSettings) : SettingsRepositoryUi {
        private val state = MutableStateFlow(initial)
        override val settings: Flow<UiSettings> = state
        val bufferWrites: MutableList<Int> = mutableListOf()
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
            state.value = state.value.copy(punctuationPauseMultiplier = multiplier)
        }
        override suspend fun setPitchInterpolationHighQuality(enabled: Boolean) = Unit
        override suspend fun setVoiceLexicon(voiceId: String, path: String?) = Unit
        override suspend fun setVoicePhonemizerLang(voiceId: String, langCode: String?) = Unit
        override suspend fun setPlaybackBufferChunks(chunks: Int) {
            bufferWrites += chunks
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
        // Memory Palace stubs (#79) — buffer-test fixture doesn't exercise
        // the palace surface; keep them no-op + return NotConfigured.
        override suspend fun setPalaceHost(host: String) = Unit
        override suspend fun setPalaceApiKey(apiKey: String) = Unit
        override suspend fun clearPalaceConfig() = Unit
        override suspend fun testPalaceConnection():
            `in`.jphe.storyvox.feature.api.PalaceProbeResult =
            `in`.jphe.storyvox.feature.api.PalaceProbeResult.NotConfigured

        // ── AI no-ops — buffer tests don't exercise these. ──
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

    /** Construct an LlmRepository with real-but-stubbed provider
     *  instances. The buffer tests don't call any LLM methods, so we
     *  just need an LlmRepository that doesn't blow up at
     *  construction. */
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

    private class FakeVoiceProvider : VoiceProviderUi {
        override val installedVoices: Flow<List<UiVoice>> = flowOf(emptyList())
        override fun previewVoice(voice: UiVoice) = Unit
    }
}
