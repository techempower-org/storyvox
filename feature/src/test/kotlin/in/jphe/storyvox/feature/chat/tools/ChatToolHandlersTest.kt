package `in`.jphe.storyvox.feature.chat.tools

import `in`.jphe.storyvox.data.db.entity.Shelf
import `in`.jphe.storyvox.data.repository.CachedBodyUsage
import `in`.jphe.storyvox.data.repository.ChapterRepository
import `in`.jphe.storyvox.data.repository.ShelfRepository
import `in`.jphe.storyvox.data.repository.playback.PlaybackChapter
import `in`.jphe.storyvox.data.db.entity.ChapterDownloadState
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.ChapterInfo
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.feature.api.DownloadMode
import `in`.jphe.storyvox.feature.api.FictionRepositoryUi
import `in`.jphe.storyvox.feature.api.PlaybackControllerUi
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import `in`.jphe.storyvox.feature.api.UiAddByUrlResult
import `in`.jphe.storyvox.feature.api.UiChapter
import `in`.jphe.storyvox.feature.api.UiFiction
import `in`.jphe.storyvox.feature.api.UiFollow
import `in`.jphe.storyvox.feature.api.UiPlaybackState
import `in`.jphe.storyvox.feature.api.UiRecapPlaybackState
import `in`.jphe.storyvox.feature.api.UiSettings
import `in`.jphe.storyvox.feature.api.UiSigil
import `in`.jphe.storyvox.feature.api.UiSleepTimerMode
import `in`.jphe.storyvox.feature.api.SetFollowedRemoteResult
import `in`.jphe.storyvox.feature.api.PalaceProbeResult
import `in`.jphe.storyvox.feature.api.AzureProbeResult
import `in`.jphe.storyvox.feature.api.ThemeOverride
import `in`.jphe.storyvox.feature.api.UiLlmProvider
import `in`.jphe.storyvox.llm.tools.ToolResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #216 — handler-level behaviour tests for the v1 tool catalog.
 * These exercise the [ChatToolHandlers] private functions through their
 * `internal` visibility (same module), threading typed JSON args in and
 * inspecting the [ToolResult] back.
 *
 * Repos are tiny in-memory fakes — full coverage of the repo
 * implementations lives in their own test classes. The intent here is
 * "does the handler glue between args/repos do the right thing?", not
 * "does the repo work?".
 */
class ChatToolHandlersTest {

    @Test
    fun `add_to_shelf round-trips Reading shelf`() = runTest {
        val shelf = FakeShelfRepo()
        val handlers = makeHandlers(shelfRepo = shelf)
        val args = buildJsonObject {
            put("fictionId", "f1")
            put("shelf", "Reading")
        }
        val result = handlers.addToShelf(args)
        assertTrue(
            "Expected Success, got $result",
            result is ToolResult.Success,
        )
        assertEquals(setOf("f1" to Shelf.Reading), shelf.added)
        val success = result as ToolResult.Success
        assertTrue(
            "Success message should reference the fiction title (was ${success.message})",
            success.message.contains("Sky Pride"),
        )
        assertTrue(success.message.contains("Reading"))
    }

    @Test
    fun `add_to_shelf rejects unknown shelf with error`() = runTest {
        val shelf = FakeShelfRepo()
        val handlers = makeHandlers(shelfRepo = shelf)
        val args = buildJsonObject {
            put("fictionId", "f1")
            put("shelf", "Favorites")  // not one of the three
        }
        val result = handlers.addToShelf(args)
        assertTrue(
            "Expected Error, got $result",
            result is ToolResult.Error,
        )
        // No mutation should have happened.
        assertEquals(emptySet<Pair<String, Shelf>>(), shelf.added)
    }

    @Test
    fun `set_speed clamps to the allowed range`() = runTest {
        val playback = FakePlayback()
        val handlers = makeHandlers(playback = playback)
        // 4x is well past the 2.5 cap.
        val tooFast = handlers.setSpeed(buildJsonObject { put("speed", 4.0f) })
        assertTrue(tooFast is ToolResult.Success)
        assertEquals(2.5f, playback.appliedSpeed, 0.001f)
        assertTrue(
            (tooFast as ToolResult.Success).message.contains("clamped"),
        )
        // 0.1x is below the 0.5 floor.
        val tooSlow = handlers.setSpeed(buildJsonObject { put("speed", 0.1f) })
        assertTrue(tooSlow is ToolResult.Success)
        assertEquals(0.5f, playback.appliedSpeed, 0.001f)
    }

    @Test
    fun `set_speed in-range applies exactly`() = runTest {
        val playback = FakePlayback()
        val handlers = makeHandlers(playback = playback)
        val result = handlers.setSpeed(buildJsonObject { put("speed", 1.2f) })
        assertTrue(result is ToolResult.Success)
        assertEquals(1.2f, playback.appliedSpeed, 0.001f)
        assertTrue(
            !(result as ToolResult.Success).message.contains("clamped"),
        )
    }

    @Test
    fun `open_voice_library fires the navigation callback`() = runTest {
        var fired = 0
        val handlers = makeHandlers(onOpenVoiceLibrary = { fired++ })
        val result = handlers.openVoiceLibrary()
        assertTrue(result is ToolResult.Success)
        assertEquals(1, fired)
    }

    @Test
    fun `registry exposes every catalog tool by name`() {
        val handlers = makeHandlers()
        val registry = handlers.registry()
        assertEquals(5, registry.catalog.size)
        registry.catalog.forEach { spec ->
            assertNotNull(
                "Registry missing handler for ${spec.name}",
                registry.handler(spec.name),
            )
        }
    }

    // ── Helpers ────────────────────────────────────────────────────

    private fun makeHandlers(
        shelfRepo: FakeShelfRepo = FakeShelfRepo(),
        chapterRepo: FakeChapterRepo = FakeChapterRepo(),
        fictionRepo: FakeFictionRepoT = FakeFictionRepoT(),
        playback: FakePlayback = FakePlayback(),
        settings: FakeSettings = FakeSettings(),
        onOpenVoiceLibrary: () -> Unit = {},
    ): ChatToolHandlers = ChatToolHandlers(
        activeFictionId = "f1",
        shelfRepo = shelfRepo,
        chapterRepo = chapterRepo,
        fictionRepo = fictionRepo,
        playback = playback,
        settingsRepo = settings,
        onOpenVoiceLibrary = onOpenVoiceLibrary,
    )
}

// ── Fakes ───────────────────────────────────────────────────────────

private class FakeShelfRepo : ShelfRepository {
    val added: MutableSet<Pair<String, Shelf>> = mutableSetOf()
    override fun observeByShelf(shelf: Shelf): Flow<List<FictionSummary>> = flowOf(emptyList())
    override fun observeShelvesForFiction(fictionId: String): Flow<Set<Shelf>> = flowOf(emptySet())
    override suspend fun shelvesForFiction(fictionId: String): Set<Shelf> = emptySet()
    override suspend fun add(fictionId: String, shelf: Shelf) {
        added += fictionId to shelf
    }
    override suspend fun remove(fictionId: String, shelf: Shelf) {
        added -= fictionId to shelf
    }
    override suspend fun clearForFiction(fictionId: String) {
        added.removeAll { it.first == fictionId }
    }
}

private class FakeChapterRepo : ChapterRepository {
    val readMarks: MutableList<Pair<String, Boolean>> = mutableListOf()
    override fun observeChapters(fictionId: String): Flow<List<ChapterInfo>> = flowOf(emptyList())
    override fun observeChapter(chapterId: String): Flow<ChapterContent?> = flowOf(null)
    override fun observeDownloadState(fictionId: String): Flow<Map<String, ChapterDownloadState>> = flowOf(emptyMap())
    override fun observePlayedChapterIds(fictionId: String): Flow<Set<String>> = flowOf(emptySet())
    override suspend fun queueChapterDownload(fictionId: String, chapterId: String, requireUnmetered: Boolean) = Unit
    override suspend fun queueAllMissing(fictionId: String, requireUnmetered: Boolean) = Unit
    override suspend fun markRead(chapterId: String, read: Boolean) {
        readMarks += chapterId to read
    }
    override suspend fun markChapterPlayed(chapterId: String) {
        readMarks += chapterId to true
    }
    override suspend fun trimDownloadedBodies(fictionId: String, keepLast: Int) = Unit
    override suspend fun getChapter(id: String): PlaybackChapter? = null
    override suspend fun getNextChapterId(currentChapterId: String): String? = null
    override suspend fun getPreviousChapterId(currentChapterId: String): String? = null
    override suspend fun cachedBodyUsage() = CachedBodyUsage(0, 0L)
    override suspend fun setChapterBookmark(chapterId: String, charOffset: Int?) = Unit
    override suspend fun chapterBookmark(chapterId: String): Int? = null
}

private class FakeFictionRepoT : FictionRepositoryUi {
    override val library: Flow<List<UiFiction>> = flowOf(emptyList())
    override val follows: Flow<List<UiFollow>> = flowOf(emptyList())
    override fun fictionById(id: String): Flow<UiFiction?> = flowOf(
        UiFiction(
            id = id,
            title = "Sky Pride",
            author = "Anon",
            coverUrl = null,
            rating = 0f,
            chapterCount = 0,
            isOngoing = true,
            synopsis = "",
        ),
    )
    override fun fictionLoadError(id: String): Flow<String?> = flowOf(null)
    override fun chaptersFor(fictionId: String): Flow<List<UiChapter>> = flowOf(emptyList())
    override fun observeIsInLibrary(fictionId: String): Flow<Boolean> = flowOf(false)
    override suspend fun chapterTextById(chapterId: String): String? = null
    override suspend fun setDownloadMode(fictionId: String, mode: DownloadMode) = Unit
    override suspend fun follow(fictionId: String, follow: Boolean) = Unit
    override suspend fun setFollowedRemote(fictionId: String, followed: Boolean): SetFollowedRemoteResult =
        SetFollowedRemoteResult.Success
    override suspend fun markAllCaughtUp() = 0
    override suspend fun refreshFollows() = Unit
    override suspend fun addByUrl(url: String, preferredSourceId: String?): UiAddByUrlResult = UiAddByUrlResult.UnrecognizedUrl
    override fun previewUrl(url: String) = emptyList<`in`.jphe.storyvox.feature.api.UiRouteCandidate>()
}

private class FakePlayback : PlaybackControllerUi {
    var appliedSpeed: Float = Float.NaN
    var started: Triple<String, String, Int>? = null
    override val state: Flow<UiPlaybackState> = MutableStateFlow(
        UiPlaybackState(
            fictionId = null, chapterId = null, chapterTitle = "", fictionTitle = "",
            coverUrl = null, isPlaying = false, positionMs = 0, durationMs = 0,
            sentenceStart = 0, sentenceEnd = 0, speed = 1f, pitch = 1f,
            voiceId = null, voiceLabel = "",
        ),
    )
    override val chapterText: Flow<String> = flowOf("")
    override val recapPlayback: Flow<UiRecapPlaybackState> = flowOf(UiRecapPlaybackState.Idle)
    override fun play() = Unit
    override fun pause() = Unit
    override fun seekTo(ms: Long) = Unit
    override fun seekToChar(charOffset: Int) = Unit
    override fun skipForward() = Unit
    override fun skipBack() = Unit
    override fun nextSentence() = Unit
    override fun previousSentence() = Unit
    override fun nextParagraph() = Unit
    override fun previousParagraph() = Unit
    override fun nextChapter() = Unit
    override fun previousChapter() = Unit
    override fun setSpeed(speed: Float) { appliedSpeed = speed }
    override fun setPitch(pitch: Float) = Unit
    override fun setPunctuationPauseMultiplier(multiplier: Float) = Unit
    override fun startListening(
        fictionId: String, chapterId: String, charOffset: Int, autoPlay: Boolean,
    ) { started = Triple(fictionId, chapterId, charOffset) }
    override fun startSleepTimer(mode: UiSleepTimerMode) = Unit
    override fun cancelSleepTimer() = Unit
    override suspend fun speakText(text: String) = Unit
    override fun stopSpeaking() = Unit
    override fun bookmarkHere() = Unit
    override fun clearBookmark() = Unit
    override fun jumpToBookmark() = Unit
}

/**
 * Same shape as ChatViewModelTest.FakeSettingsRepo — covers every
 * abstract method on [SettingsRepositoryUi] with a no-op. The
 * interface has a number of default-impl members we don't need to
 * override (markMilestoneDialogSeen, setInboxNotifyRoyalRoad, etc.).
 */
private class FakeSettings : SettingsRepositoryUi {
    override val settings: Flow<UiSettings> = flowOf(
        UiSettings(
            ttsEngine = "VoxSherpa",
            defaultVoiceId = null,
            defaultSpeed = 1f,
            defaultPitch = 1f,
            themeOverride = ThemeOverride.System,
            downloadOnWifiOnly = false,
            pollIntervalHours = 0,
            isSignedIn = false,
        ),
    )
    override val outlineHost: Flow<String> = flowOf("")
    override val rssSubscriptions: Flow<List<String>> = flowOf(emptyList())
    override val epubFolderUri: Flow<String?> = flowOf(null)
    override val suggestedRssFeeds: Flow<List<`in`.jphe.storyvox.feature.api.SuggestedFeed>> =
        flowOf(emptyList())
    override suspend fun setTheme(override: ThemeOverride) = Unit
    override suspend fun setDefaultSpeed(speed: Float) = Unit
    override suspend fun setDefaultPitch(pitch: Float) = Unit
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
    override suspend fun setPalaceHost(host: String) = Unit
    override suspend fun setPalaceApiKey(apiKey: String) = Unit
    override suspend fun clearPalaceConfig() = Unit
    override suspend fun testPalaceConnection(): PalaceProbeResult = PalaceProbeResult.NotConfigured
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
    override suspend fun signOutTeams() = Unit
    override suspend fun resetAiSettings() = Unit
    override suspend fun signOutGitHub() = Unit
    override suspend fun setGitHubPrivateReposEnabled(enabled: Boolean) = Unit
    override suspend fun setSourcePluginEnabled(id: String, enabled: Boolean) = Unit
    override suspend fun setSourceFavorite(id: String, favorite: Boolean) = Unit
    override suspend fun setSourceDisplayOrder(order: List<String>) = Unit
    override suspend fun setVoiceFamilyEnabled(id: String, enabled: Boolean) = Unit
    override suspend fun setOutlineHost(host: String) = Unit
    override suspend fun setOutlineApiKey(apiKey: String) = Unit
    override suspend fun clearOutlineConfig() = Unit
    override suspend fun setWikipediaLanguageCode(code: String) = Unit
    override suspend fun setNotionDatabaseId(id: String) = Unit
    override suspend fun setNotionApiToken(token: String?) = Unit
    override suspend fun setDiscordApiToken(token: String?) = Unit
    override suspend fun setDiscordServer(serverId: String, serverName: String) = Unit
    override suspend fun setDiscordCoalesceMinutes(minutes: Int) = Unit
    override suspend fun fetchDiscordGuilds(): List<Pair<String, String>> = emptyList()
    override suspend fun setTelegramApiToken(token: String?) = Unit
    override suspend fun probeTelegramBot(): String? = null
    override suspend fun fetchTelegramChannels(): List<Pair<String, String>> = emptyList()
    override suspend fun addRssFeed(url: String) = Unit
    override suspend fun removeRssFeed(fictionId: String) = Unit
    override suspend fun removeRssFeedByUrl(url: String) = Unit
    override suspend fun setEpubFolderUri(uri: String) = Unit
    override suspend fun clearEpubFolder() = Unit
    override suspend fun setSleepShakeToExtendEnabled(enabled: Boolean) = Unit
    override suspend fun setAzureKey(key: String?) = Unit
    override suspend fun setAzureRegion(regionId: String) = Unit
    override suspend fun clearAzureCredentials() = Unit
    override suspend fun setParallelSynthInstances(count: Int) = Unit
    override suspend fun setSynthThreadsPerInstance(count: Int) = Unit
    override suspend fun setAzureFallbackEnabled(enabled: Boolean) = Unit
    override suspend fun setAzureFallbackVoiceId(voiceId: String?) = Unit
    override suspend fun testAzureConnection(): AzureProbeResult = AzureProbeResult.NotConfigured
}
