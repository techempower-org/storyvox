package `in`.jphe.storyvox.di

import android.app.Application
import `in`.jphe.storyvox.data.db.entity.ChapterDownloadState
import `in`.jphe.storyvox.data.repository.ChapterRepository
import `in`.jphe.storyvox.data.repository.playback.PlaybackChapter
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.ChapterInfo
import `in`.jphe.storyvox.feature.api.PUNCTUATION_PAUSE_DEFAULT_MULTIPLIER
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import `in`.jphe.storyvox.feature.api.UiLlmProvider
import `in`.jphe.storyvox.feature.api.ThemeOverride
import `in`.jphe.storyvox.feature.api.UiSettings
import `in`.jphe.storyvox.playback.PlaybackController
import `in`.jphe.storyvox.playback.PlaybackState
import `in`.jphe.storyvox.playback.tts.RecapPlaybackState
import `in`.jphe.storyvox.playback.PlaybackUiEvent
import `in`.jphe.storyvox.playback.SleepTimerMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Issue #108 — verify the Settings → engine plumbing for speed + pitch.
 *
 * The bug: SettingsScreen sliders called SettingsViewModel.setSpeed which
 * persisted to DataStore but never reached EnginePlayer. The fix mirrors
 * the issue #90 punctuation-pause pattern — RealPlaybackControllerUi.init
 * observes settings.settings, distinct-on-value, forwards into
 * PlaybackController.setSpeed/setPitch.
 *
 * These tests assert that contract directly: emit a new defaultSpeed /
 * defaultPitch on the settings flow → controller receives setSpeed /
 * setPitch with the new value. Distinct guards too — duplicates from
 * DataStore hydration must not call the controller a second time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RealPlaybackControllerUiTest {

    @Test
    fun `speed change in settings flow propagates to controller`() = runTest {
        val controller = RecordingController()
        val settings = FakeSettings(initial = uiSettings(defaultSpeed = 1.0f))
        RealPlaybackControllerUi(
            context = RuntimeEnvironment.getApplication(),
            controller = controller,
            chapters = NoOpChapters(),
            settings = settings,
        )
        advanceUntilIdle()

        // Initial hydration replays the seed value once.
        assertEquals(listOf(1.0f), controller.speeds)

        settings.update { it.copy(defaultSpeed = 1.75f) }
        advanceUntilIdle()

        assertEquals(listOf(1.0f, 1.75f), controller.speeds)
    }

    @Test
    fun `pitch change in settings flow propagates to controller`() = runTest {
        val controller = RecordingController()
        val settings = FakeSettings(initial = uiSettings(defaultPitch = 1.0f))
        RealPlaybackControllerUi(
            context = RuntimeEnvironment.getApplication(),
            controller = controller,
            chapters = NoOpChapters(),
            settings = settings,
        )
        advanceUntilIdle()

        assertEquals(listOf(1.0f), controller.pitches)

        settings.update { it.copy(defaultPitch = 0.92f) }
        advanceUntilIdle()

        assertEquals(listOf(1.0f, 0.92f), controller.pitches)
    }

    @Test
    fun `duplicate settings emissions do not re-fire setSpeed`() = runTest {
        // distinctUntilChanged matters — DataStore hydration can replay the
        // same value, and setSpeed rebuilds the playback pipeline if
        // anything's playing. A duplicate fire would mid-cut the audio.
        val controller = RecordingController()
        val settings = FakeSettings(initial = uiSettings(defaultSpeed = 1.0f))
        RealPlaybackControllerUi(
            context = RuntimeEnvironment.getApplication(),
            controller = controller,
            chapters = NoOpChapters(),
            settings = settings,
        )
        advanceUntilIdle()

        // Re-emit the same speed via an unrelated field change.
        settings.update { it.copy(downloadOnWifiOnly = !it.downloadOnWifiOnly) }
        advanceUntilIdle()

        assertEquals(listOf(1.0f), controller.speeds)
    }

    @Test
    fun `speed and pitch propagate independently`() = runTest {
        val controller = RecordingController()
        val settings = FakeSettings(initial = uiSettings())
        RealPlaybackControllerUi(
            context = RuntimeEnvironment.getApplication(),
            controller = controller,
            chapters = NoOpChapters(),
            settings = settings,
        )
        advanceUntilIdle()

        settings.update { it.copy(defaultSpeed = 1.5f) }
        advanceUntilIdle()
        settings.update { it.copy(defaultPitch = 1.05f) }
        advanceUntilIdle()

        assertEquals(listOf(1.0f, 1.5f), controller.speeds)
        assertEquals(listOf(1.0f, 1.05f), controller.pitches)
    }

    // ---- helpers ----

    private fun uiSettings(
        defaultSpeed: Float = 1.0f,
        defaultPitch: Float = 1.0f,
    ): UiSettings = UiSettings(
        ttsEngine = "voxsherpa",
        defaultVoiceId = null,
        defaultSpeed = defaultSpeed,
        defaultPitch = defaultPitch,
        themeOverride = ThemeOverride.System,
        downloadOnWifiOnly = false,
        pollIntervalHours = 6,
        isSignedIn = false,
        punctuationPauseMultiplier = PUNCTUATION_PAUSE_DEFAULT_MULTIPLIER,
    )

    /**
     * Captures every setSpeed/setPitch call so the test can assert ordering
     * without needing a real EnginePlayer or coroutine plumbing.
     */
    private class RecordingController : PlaybackController {
        val speeds = mutableListOf<Float>()
        val pitches = mutableListOf<Float>()
        val multipliers = mutableListOf<Float>()

        override val state: StateFlow<PlaybackState> = MutableStateFlow(PlaybackState()).asStateFlow()
        override val events: SharedFlow<PlaybackUiEvent> =
            MutableSharedFlow<PlaybackUiEvent>().asSharedFlow()
        override val recapPlayback: StateFlow<RecapPlaybackState> =
            MutableStateFlow(RecapPlaybackState.Idle).asStateFlow()
        // audio-fidelity-fixer batch (#524 #530 #536 #539 #543) —
        // additional surfaces the controller now exposes. Test fakes
        // return idle/zero so existing assertions stay unaffected.
        override val engineState: StateFlow<`in`.jphe.storyvox.playback.EngineState> =
            MutableStateFlow<`in`.jphe.storyvox.playback.EngineState>(
                `in`.jphe.storyvox.playback.EngineState.Idle,
            ).asStateFlow()
        override val playbackPositionMs: StateFlow<Long> = MutableStateFlow(0L).asStateFlow()
        override val chapterDurationMs: StateFlow<Long> = MutableStateFlow(0L).asStateFlow()
        // "Why are we waiting?" — test fakes default to null (audio
        // flowing). Tests that exercise the diagnostic flow can swap
        // in a MutableStateFlow if needed.
        override val waitReason: StateFlow<`in`.jphe.storyvox.playback.diagnostics.WaitReason?> =
            MutableStateFlow<`in`.jphe.storyvox.playback.diagnostics.WaitReason?>(null).asStateFlow()

        override suspend fun play(fictionId: String, chapterId: String, charOffset: Int) = Unit
        override fun pause() = Unit
        override fun resume() = Unit
        override fun togglePlayPause() = Unit
        override fun seekTo(charOffset: Int) = Unit
        override fun seekToPositionMs(positionMs: Long) = Unit
        override fun skipForward30s() = Unit
        override fun skipBack30s() = Unit
        override fun prewarmEngine() = Unit
        // #120 — sentence stepping. Vesper drive-by fix: test wasn't
        // updated when PlaybackController grew these two; the missing
        // overrides broke `:app:compileDebugUnitTestKotlin` until now.
        override fun nextSentence() = Unit
        override fun previousSentence() = Unit
        override fun nextParagraph() = Unit
        override fun previousParagraph() = Unit
        override suspend fun nextChapter() = Unit
        override suspend fun previousChapter() = Unit
        override suspend fun jumpToChapter(chapterId: String) = Unit

        override fun setSpeed(speed: Float) { speeds += speed }
        override fun setPitch(pitch: Float) { pitches += pitch }
        override fun setPunctuationPauseMultiplier(multiplier: Float) {
            multipliers += multiplier
        }

        override fun startSleepTimer(mode: SleepTimerMode) = Unit
        override fun cancelSleepTimer() = Unit
        override fun toggleSleepTimer() = Unit
        override fun setShakeToExtendEnabled(enabled: Boolean) = Unit
        override suspend fun speakText(text: String) = Unit
        override fun stopSpeaking() = Unit
        override fun bufferTelemetry() = `in`.jphe.storyvox.playback.BufferTelemetry()
        override suspend fun bookmarkHere() = Unit
        override suspend fun clearBookmark() = Unit
        override suspend fun jumpToBookmark(): Boolean = false
    }

    /**
     * Hot StateFlow wrapper so the test can mutate the settings snapshot
     * after construction and observe the downstream side-effect.
     */
    private class FakeSettings(initial: UiSettings) : SettingsRepositoryUi {
        private val flow = MutableStateFlow(initial)
        override val settings: Flow<UiSettings> = flow

        fun update(transform: (UiSettings) -> UiSettings) {
            flow.value = transform(flow.value)
        }

        override suspend fun setTheme(override: ThemeOverride) = Unit
        override suspend fun setDefaultSpeed(speed: Float) {
            flow.value = flow.value.copy(defaultSpeed = speed)
        }
        override suspend fun setDefaultPitch(pitch: Float) {
            flow.value = flow.value.copy(defaultPitch = pitch)
        }
        override suspend fun setDefaultVoice(voiceId: String?) = Unit
        override suspend fun setDownloadOnWifiOnly(enabled: Boolean) = Unit
        override suspend fun setPollIntervalHours(hours: Int) = Unit
        override suspend fun setPunctuationPauseMultiplier(multiplier: Float) = Unit
        override suspend fun setPitchInterpolationHighQuality(enabled: Boolean) = Unit
        override suspend fun setVoiceLexicon(voiceId: String, path: String?) = Unit
        override suspend fun setVoicePhonemizerLang(voiceId: String, langCode: String?) = Unit
        override suspend fun setPlaybackBufferChunks(chunks: Int) = Unit
        override suspend fun setWarmupWait(enabled: Boolean) = Unit
        override suspend fun setCatchupPause(enabled: Boolean) = Unit
        override suspend fun setFullPrerender(enabled: Boolean) = Unit
        override suspend fun setCacheQuotaBytes(bytes: Long) = Unit
        override suspend fun clearCache(): Long = 0L
        override suspend fun setVoiceSteady(enabled: Boolean) = Unit
        override suspend fun signIn() = Unit
        override suspend fun signOut() = Unit
        // Memory Palace stubs (#79) — playback-controller-test fixture
        // doesn't exercise the palace surface; keep them no-op + return
        // NotConfigured.
        override suspend fun setPalaceHost(host: String) = Unit
        override suspend fun setPalaceApiKey(apiKey: String) = Unit
        override suspend fun clearPalaceConfig() = Unit
        override suspend fun testPalaceConnection():
            `in`.jphe.storyvox.feature.api.PalaceProbeResult =
            `in`.jphe.storyvox.feature.api.PalaceProbeResult.NotConfigured

        // ── AI no-ops (#81) — playback-controller-test fixture doesn't exercise these. ──
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
        // ── GitHub OAuth no-op (#91) — playback-controller test doesn't auth. ──
        override suspend fun signOutGitHub() = Unit
        override suspend fun setGitHubPrivateReposEnabled(enabled: Boolean) = Unit
        override suspend fun addRssFeed(url: String) = Unit
        override suspend fun removeRssFeed(fictionId: String) = Unit
        override suspend fun removeRssFeedByUrl(url: String) = Unit
        override val rssSubscriptions: kotlinx.coroutines.flow.Flow<List<String>> = kotlinx.coroutines.flow.flowOf(emptyList())
        override val epubFolderUri: kotlinx.coroutines.flow.Flow<String?> = kotlinx.coroutines.flow.flowOf(null)
        override suspend fun setEpubFolderUri(uri: String) = Unit
        override suspend fun clearEpubFolder() = Unit
        override val outlineHost: kotlinx.coroutines.flow.Flow<String> = kotlinx.coroutines.flow.flowOf("")
        override suspend fun setOutlineHost(host: String) = Unit
        override suspend fun setOutlineApiKey(apiKey: String) = Unit
        override suspend fun clearOutlineConfig() = Unit
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

    /** Chapter repo never invoked by the speed/pitch path under test. */
    private class NoOpChapters : ChapterRepository {
        override fun observeChapters(fictionId: String): Flow<List<ChapterInfo>> = flowOf(emptyList())
        override fun observeChapter(chapterId: String): Flow<ChapterContent?> = flowOf(null)
        override fun observeDownloadState(fictionId: String): Flow<Map<String, ChapterDownloadState>> =
            flowOf(emptyMap())
        // Issue #282 — played-chapter set query, added so the chapter-list
        // flow can compute `isFinished` after auto-advance. No-op here.
        override fun observePlayedChapterIds(fictionId: String): Flow<Set<String>> =
            flowOf(emptySet())
        override suspend fun queueChapterDownload(
            fictionId: String,
            chapterId: String,
            requireUnmetered: Boolean,
        ) = Unit
        override suspend fun queueAllMissing(fictionId: String, requireUnmetered: Boolean) = Unit
        override suspend fun markRead(chapterId: String, read: Boolean) = Unit
        override suspend fun markChapterPlayed(chapterId: String) = Unit
        override suspend fun trimDownloadedBodies(fictionId: String, keepLast: Int) = Unit
        override suspend fun getChapter(id: String): PlaybackChapter? = null
        override suspend fun getNextChapterId(currentChapterId: String): String? = null
        override suspend fun getPreviousChapterId(currentChapterId: String): String? = null
        override suspend fun cachedBodyUsage() =
            `in`.jphe.storyvox.data.repository.CachedBodyUsage(count = 0, bytesEstimate = 0L)
        override suspend fun setChapterBookmark(chapterId: String, charOffset: Int?) = Unit
        override suspend fun chapterBookmark(chapterId: String): Int? = null
    }
}
