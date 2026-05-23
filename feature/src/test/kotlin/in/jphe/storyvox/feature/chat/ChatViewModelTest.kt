package `in`.jphe.storyvox.feature.chat

import androidx.lifecycle.SavedStateHandle
import `in`.jphe.storyvox.data.db.dao.LlmMessageDao
import `in`.jphe.storyvox.data.db.dao.LlmSessionDao
import `in`.jphe.storyvox.data.db.entity.FictionMemoryEntry
import `in`.jphe.storyvox.data.db.entity.LlmSession
import `in`.jphe.storyvox.data.db.entity.LlmStoredMessage
import `in`.jphe.storyvox.data.repository.FictionMemoryRepository
import `in`.jphe.storyvox.feature.api.DownloadMode
import `in`.jphe.storyvox.feature.api.FictionRepositoryUi
import `in`.jphe.storyvox.feature.api.PalaceProbeResult
import `in`.jphe.storyvox.feature.api.PlaybackControllerUi
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import `in`.jphe.storyvox.feature.api.ThemeOverride
import `in`.jphe.storyvox.feature.api.UiAddByUrlResult
import `in`.jphe.storyvox.feature.api.UiAiSettings
import `in`.jphe.storyvox.feature.api.UiChatGrounding
import `in`.jphe.storyvox.feature.api.UiLlmProvider
import `in`.jphe.storyvox.feature.api.UiSettings
import `in`.jphe.storyvox.feature.api.UiChapter
import `in`.jphe.storyvox.feature.api.UiFiction
import `in`.jphe.storyvox.feature.api.UiFollow
import `in`.jphe.storyvox.feature.api.UiPlaybackState
import `in`.jphe.storyvox.feature.api.UiRecapPlaybackState
import `in`.jphe.storyvox.feature.api.UiSleepTimerMode
import `in`.jphe.storyvox.llm.FeatureKind
import `in`.jphe.storyvox.llm.LlmConfig
import `in`.jphe.storyvox.llm.LlmError
import `in`.jphe.storyvox.llm.LlmMessage
import `in`.jphe.storyvox.llm.LlmRepository
import `in`.jphe.storyvox.llm.LlmSessionRepository
import `in`.jphe.storyvox.llm.ProviderId
import `in`.jphe.storyvox.llm.provider.ClaudeApiProvider
import `in`.jphe.storyvox.llm.provider.OllamaProvider
import `in`.jphe.storyvox.llm.provider.OpenAiApiProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() { Dispatchers.setMain(dispatcher) }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    // ── Helpers ────────────────────────────────────────────────────

    private fun makeViewModel(
        fictionId: String = "f1",
        fictionTitle: String? = "Sky Pride",
        playbackState: UiPlaybackState = playbackOf(),
        config: LlmConfig = LlmConfig(provider = ProviderId.Claude),
        session: FakeSessionRepo = FakeSessionRepo(),
        settings: FakeSettingsRepo = FakeSettingsRepo(),
        memory: FakeFictionMemoryRepo = FakeFictionMemoryRepo(),
        fictionRepo: FakeFictionRepo = FakeFictionRepo(fictionId, fictionTitle),
    ): Triple<ChatViewModel, FakeSessionRepo, FakeFictionMemoryRepo> {
        val vm = ChatViewModel(
            sessionRepo = session,
            fictionRepo = fictionRepo,
            playback = FakePlayback(playbackState),
            configFlow = flowOf(config),
            settingsRepo = settings,
            memoryRepo = memory,
            savedState = SavedStateHandle(mapOf("fictionId" to fictionId)),
        )
        return Triple(vm, session, memory)
    }

    /** Subscribe to [vm.uiState] in the test scope so the
     *  WhileSubscribed StateFlow actually starts emitting. Returns
     *  the collector job — caller cancels in the test cleanup. */
    private fun TestScope.collectUiState(vm: ChatViewModel): Job =
        backgroundScope.launch { vm.uiState.collect { /* drain */ } }

    @Test
    fun `empty state when no provider configured`() = runTest(dispatcher) {
        val (vm, _, _) = makeViewModel(config = LlmConfig(provider = null))
        collectUiState(vm)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.noProvider)
        assertTrue(vm.uiState.value.turns.isEmpty())
        assertNull(vm.uiState.value.streaming)
    }

    @Test
    fun `send streams tokens into uiState then finalises into history`() = runTest(dispatcher) {
        val session = FakeSessionRepo(tokens = listOf("Hello, ", "reader."))
        val (vm, _, _) = makeViewModel(session = session)
        collectUiState(vm)

        vm.send("What just happened?")
        advanceUntilIdle()

        // Stream finished — _streaming cleared, history contains both
        // the user turn and the assistant reply.
        assertNull(vm.uiState.value.streaming)
        val turns = vm.uiState.value.turns
        assertEquals(2, turns.size)
        assertEquals(ChatTurn.Role.User, turns[0].role)
        assertEquals("What just happened?", turns[0].text)
        assertEquals(ChatTurn.Role.Assistant, turns[1].role)
        assertEquals("Hello, reader.", turns[1].text)
    }

    @Test
    fun `send creates session on first call and reuses it on second`() = runTest(dispatcher) {
        val session = FakeSessionRepo(tokens = listOf("ok"))
        val (vm, _, _) = makeViewModel(session = session, fictionId = "f1")

        vm.send("first")
        advanceUntilIdle()
        vm.send("second")
        advanceUntilIdle()

        // createSession invoked exactly once, with the deterministic id.
        assertEquals(1, session.createSessionCount)
        assertEquals("chat:f1", session.createdSessionId)
        // chat() called twice on the same session id.
        assertEquals(2, session.chatCalls.size)
        assertTrue(session.chatCalls.all { it.first == "chat:f1" })
    }

    @Test
    fun `send surfaces LlmError as ChatError without crashing`() = runTest(dispatcher) {
        val session = FakeSessionRepo(throwOnChat = LlmError.AuthFailed(ProviderId.Claude, "bad key"))
        val (vm, _, _) = makeViewModel(session = session)
        collectUiState(vm)

        vm.send("hi")
        advanceUntilIdle()

        val err = vm.uiState.value.error
        assertNotNull(err)
        assertTrue(err is ChatError.AuthFailed)
        // streaming cleared even on error.
        assertNull(vm.uiState.value.streaming)
    }

    @Test
    fun `dismissError clears the banner`() = runTest(dispatcher) {
        val session = FakeSessionRepo(throwOnChat = LlmError.Transport(ProviderId.Claude, IllegalStateException("eof")))
        val (vm, _, _) = makeViewModel(session = session)
        collectUiState(vm)
        vm.send("hi")
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.error)

        vm.dismissError()
        advanceUntilIdle()
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `whitespace-only send is a no-op`() = runTest(dispatcher) {
        val session = FakeSessionRepo(tokens = listOf("ok"))
        val (vm, _, _) = makeViewModel(session = session)
        collectUiState(vm)

        vm.send("   ")
        advanceUntilIdle()

        assertEquals(0, session.chatCalls.size)
        assertTrue(vm.uiState.value.turns.isEmpty())
    }

    @Test
    fun `system prompt grounds in fiction title and current chapter`() = runTest(dispatcher) {
        val session = FakeSessionRepo(tokens = listOf("ok"))
        val (vm, _, _) = makeViewModel(
            session = session,
            fictionId = "f1",
            fictionTitle = "Sky Pride",
            playbackState = playbackOf(fictionId = "f1", chapterTitle = "Chapter 7"),
        )

        vm.send("ok")
        advanceUntilIdle()

        val sp = session.lastSystemPrompt
        assertNotNull(sp)
        assertTrue(sp!!.contains("Sky Pride"))
        assertTrue(sp.contains("Chapter 7"))
        assertTrue(sp.contains("Don't spoil"))
    }

    @Test
    fun `system prompt omits chapter clause when listening to a different fiction`() = runTest(dispatcher) {
        val session = FakeSessionRepo(tokens = listOf("ok"))
        val (vm, _, _) = makeViewModel(
            session = session,
            fictionId = "f1",
            fictionTitle = "Sky Pride",
            playbackState = playbackOf(fictionId = "other", chapterTitle = "Some other chapter"),
        )

        vm.send("ok")
        advanceUntilIdle()

        val sp = session.lastSystemPrompt!!
        assertTrue(sp.contains("Sky Pride"))
        assertFalse(sp.contains("Some other chapter"))
    }

    // ── Cross-fiction memory (issue #217) ──────────────────────────

    @Test
    fun `cross-fiction block appended when toggle on and another book has matching name`() =
        runTest(dispatcher) {
            // Seed: a prior book "f-prior" has an entry for "Kelsier".
            // Current chat is in "f-current". User asks about Kelsier.
            val memory = FakeFictionMemoryRepo().apply {
                seed("f-prior", "Kelsier", "Mistborn, leader of the crew")
            }
            val session = FakeSessionRepo(tokens = listOf("ok"))
            // The CrossFictionMemoryBlock uses fictionRepo.fictionById
            // to resolve titles for other books. The multi-title fake
            // resolves f-prior → "Mistborn" so the block renders cleanly.
            val fictionRepo = FakeFictionRepoMulti(
                mapOf(
                    "f-current" to "Sky Pride",
                    "f-prior" to "Mistborn",
                ),
            )
            val vm = ChatViewModel(
                sessionRepo = session,
                fictionRepo = fictionRepo,
                playback = FakePlayback(playbackOf()),
                configFlow = flowOf(LlmConfig(provider = ProviderId.Claude)),
                settingsRepo = FakeSettingsRepo(carryMemoryAcrossFictions = true),
                memoryRepo = memory,
                savedState = SavedStateHandle(mapOf("fictionId" to "f-current")),
            )
            backgroundScope.launch { vm.uiState.collect {} }

            vm.send("Who is Kelsier?")
            advanceUntilIdle()

            val sp = session.lastSystemPrompt!!
            assertTrue(
                "cross-fiction block must appear when memory has a match. sp=$sp",
                sp.contains("Cross-fiction context"),
            )
            assertTrue("entity name surfaces in block", sp.contains("Kelsier"))
            assertTrue("source-book title surfaces", sp.contains("Mistborn"))
        }

    @Test
    fun `cross-fiction block omitted when toggle off`() = runTest(dispatcher) {
        val memory = FakeFictionMemoryRepo().apply {
            seed("f-prior", "Kelsier", "Mistborn")
        }
        val session = FakeSessionRepo(tokens = listOf("ok"))
        val (vm, _, _) = makeViewModel(
            session = session,
            memory = memory,
            settings = FakeSettingsRepo(carryMemoryAcrossFictions = false),
        )

        vm.send("Who is Kelsier?")
        advanceUntilIdle()

        val sp = session.lastSystemPrompt!!
        assertFalse(
            "cross-fiction block must NOT appear when toggle is OFF. sp=$sp",
            sp.contains("Cross-fiction context"),
        )
    }

    @Test
    fun `assistant reply extracts entities into memory after send completes`() =
        runTest(dispatcher) {
            // The AI reply contains a defining sentence the regex
            // extractor recognises. Post-completion, the entity should
            // be upserted into the per-fiction memory.
            val session = FakeSessionRepo(
                tokens = listOf("Vin is a Mistborn who works with the crew."),
            )
            val memory = FakeFictionMemoryRepo()
            val (vm, _, _) = makeViewModel(
                fictionId = "f-current",
                session = session,
                memory = memory,
            )
            backgroundScope.launch { vm.uiState.collect {} }

            vm.send("Tell me about Vin")
            advanceUntilIdle()

            val recorded = memory.forFictionOnce("f-current")
            assertEquals(
                "expected exactly one entity from the reply, got: $recorded",
                1, recorded.size,
            )
            assertEquals("Vin", recorded.first().name)
            assertTrue(recorded.first().summary.contains("Mistborn"))
        }

    // Issue #215 — multi-modal image input composer state.

    @Test
    fun `attachImage sets pending image and clearPendingImage removes it`() =
        runTest(dispatcher) {
            val (vm, _, _) = makeViewModel()
            collectUiState(vm)
            advanceUntilIdle()
            assertNull(vm.uiState.value.pendingImage)

            val attachment = ImageAttachment(
                uri = "content://media/picture/123",
                base64 = "Zm9v",
                mimeType = "image/jpeg",
                widthPx = 800,
                heightPx = 600,
            )
            vm.attachImage(attachment)
            advanceUntilIdle()
            assertEquals(attachment, vm.uiState.value.pendingImage)

            vm.clearPendingImage()
            advanceUntilIdle()
            assertNull(vm.uiState.value.pendingImage)
        }

    @Test
    fun `send clears pending image and the next turn carries the URI`() =
        runTest(dispatcher) {
            val session = FakeSessionRepo(tokens = listOf("I see a cat."))
            val (vm, _, _) = makeViewModel(session = session)
            collectUiState(vm)
            advanceUntilIdle()

            val attachment = ImageAttachment(
                uri = "content://media/picture/456",
                base64 = "Zm9v",
                mimeType = "image/jpeg",
                widthPx = 1280,
                heightPx = 720,
            )
            vm.attachImage(attachment)
            vm.send("what's in this photo?")
            advanceUntilIdle()

            // The composer state cleared on send.
            assertNull(vm.uiState.value.pendingImage)

            // The user turn (most-recently appended) picks up the URI
            // via the in-memory keyed-by-text overlay.
            val userTurns = vm.uiState.value.turns.filter {
                it.role == ChatTurn.Role.User
            }
            assertEquals(1, userTurns.size)
            assertEquals("what's in this photo?", userTurns.first().text)
            assertEquals(
                "content://media/picture/456",
                userTurns.first().imageUri,
            )
        }
}

// ── Fakes ──────────────────────────────────────────────────────────

/**
 * Stand-in for [LlmSessionRepository]. Overrides all three methods
 * the ViewModel touches — `observeMessages`, `createSession`, `chat`.
 * Backed by an in-memory list so observeMessages reflects what
 * createSession + chat append.
 */
private class FakeSessionRepo(
    var tokens: List<String> = emptyList(),
    var throwOnChat: Throwable? = null,
) : LlmSessionRepository(
    sessionDao = ThrowingSessionDao,
    messageDao = ThrowingMessageDao,
    llm = unreachableLlmRepository(),
) {
    var createSessionCount: Int = 0
    var createdSessionId: String? = null
    var lastSystemPrompt: String? = null
    val chatCalls: MutableList<Pair<String, String>> = mutableListOf()
    private val messages = MutableStateFlow<List<LlmMessage>>(emptyList())

    override fun observeMessages(sessionId: String): Flow<List<LlmMessage>> =
        messages.asStateFlow()

    override suspend fun createSession(
        name: String,
        provider: ProviderId,
        model: String,
        systemPrompt: String?,
        featureKind: FeatureKind?,
        anchorFictionId: String?,
        anchorChapterId: String?,
        explicitId: String?,
    ): String {
        createSessionCount++
        createdSessionId = explicitId
        lastSystemPrompt = systemPrompt
        return explicitId ?: "fake-session"
    }

    override suspend fun updateSystemPrompt(sessionId: String, systemPrompt: String?) {
        lastSystemPrompt = systemPrompt
    }

    override fun chat(
        sessionId: String,
        userMessage: String,
        userParts: List<`in`.jphe.storyvox.llm.LlmContentBlock>?,
    ): Flow<String> = flow {
        chatCalls += sessionId to userMessage
        // Persist the user turn synchronously, mirroring real repo
        // behaviour ("user msg saved before stream begins").
        messages.update { it + LlmMessage(LlmMessage.Role.user, userMessage) }
        throwOnChat?.let { throw it }
        val buf = StringBuilder()
        for (t in tokens) {
            buf.append(t)
            emit(t)
        }
        // On clean completion, the real repo persists the assistant
        // turn — replicate so observeMessages reflects history.
        messages.update { it + LlmMessage(LlmMessage.Role.assistant, buf.toString()) }
    }
}

private object ThrowingSessionDao : LlmSessionDao {
    override suspend fun upsert(session: LlmSession) = error("not used in fake")
    override fun observeAll(): Flow<List<LlmSession>> = error("not used in fake")
    override suspend fun get(id: String): LlmSession? = error("not used in fake")
    override suspend fun touchLastUsed(id: String, ts: Long) = error("not used in fake")
    override suspend fun updateSystemPrompt(id: String, systemPrompt: String?) =
        error("not used in fake")
    override suspend fun delete(id: String) = error("not used in fake")
    override suspend fun deleteAll() = error("not used in fake")
}

private object ThrowingMessageDao : LlmMessageDao {
    override suspend fun insert(message: LlmStoredMessage): Long = error("not used in fake")
    override fun observeBySession(sessionId: String): Flow<List<LlmStoredMessage>> =
        error("not used in fake")
    override suspend fun getBySession(sessionId: String): List<LlmStoredMessage> =
        error("not used in fake")
    override suspend fun deleteBySession(sessionId: String) = error("not used in fake")
}

/** LlmRepository is non-open — but our [FakeSessionRepo] overrides
 *  every method that would otherwise touch it, so this instance is
 *  never reached. We pass a syntactically-valid one to satisfy the
 *  base ctor. */
private fun unreachableLlmRepository(): LlmRepository {
    val cfg = flowOf(LlmConfig())
    return LlmRepository(
        configFlow = cfg,
        claude = unreachableClaude(),
        openAi = unreachableOpenAi(),
        ollama = unreachableOllama(),
        vertex = unreachableVertex(),
        foundry = unreachableFoundry(),
        bedrock = unreachableBedrock(),
        teams = unreachableTeams(),
    )
}

private fun unreachableClaude(): ClaudeApiProvider =
    object : ClaudeApiProvider(
        http = okhttp3.OkHttpClient(),
        store = NullStore,
        configFlow = flowOf(LlmConfig()),
        json = kotlinx.serialization.json.Json,
    ) {
        override fun stream(messages: List<LlmMessage>, systemPrompt: String?, model: String?) =
            error("unreachable")
    }

private fun unreachableOpenAi(): OpenAiApiProvider =
    object : OpenAiApiProvider(
        http = okhttp3.OkHttpClient(),
        store = NullStore,
        configFlow = flowOf(LlmConfig()),
        json = kotlinx.serialization.json.Json,
    ) {
        override fun stream(messages: List<LlmMessage>, systemPrompt: String?, model: String?) =
            error("unreachable")
    }

private fun unreachableOllama(): OllamaProvider =
    object : OllamaProvider(
        http = okhttp3.OkHttpClient(),
        configFlow = flowOf(LlmConfig()),
        json = kotlinx.serialization.json.Json,
    ) {
        override fun stream(messages: List<LlmMessage>, systemPrompt: String?, model: String?) =
            error("unreachable")
    }

private fun unreachableVertex(): `in`.jphe.storyvox.llm.provider.VertexProvider =
    object : `in`.jphe.storyvox.llm.provider.VertexProvider(
        http = okhttp3.OkHttpClient(),
        store = NullStore,
        configFlow = flowOf(LlmConfig()),
        json = kotlinx.serialization.json.Json,
        tokenSource = `in`.jphe.storyvox.llm.auth.GoogleOAuthTokenSource(okhttp3.OkHttpClient()),
    ) {
        override fun stream(messages: List<LlmMessage>, systemPrompt: String?, model: String?) =
            error("unreachable")
    }

private fun unreachableFoundry(): `in`.jphe.storyvox.llm.provider.AzureFoundryProvider =
    object : `in`.jphe.storyvox.llm.provider.AzureFoundryProvider(
        http = okhttp3.OkHttpClient(),
        store = NullStore,
        configFlow = flowOf(LlmConfig()),
        json = kotlinx.serialization.json.Json,
    ) {
        override fun stream(messages: List<LlmMessage>, systemPrompt: String?, model: String?) =
            error("unreachable")
    }

private fun unreachableBedrock(): `in`.jphe.storyvox.llm.provider.BedrockProvider =
    object : `in`.jphe.storyvox.llm.provider.BedrockProvider(
        http = okhttp3.OkHttpClient(),
        store = NullStore,
        configFlow = flowOf(LlmConfig()),
        json = kotlinx.serialization.json.Json,
    ) {
        override fun stream(messages: List<LlmMessage>, systemPrompt: String?, model: String?) =
            error("unreachable")
    }

private fun unreachableTeams(): `in`.jphe.storyvox.llm.provider.AnthropicTeamsProvider =
    object : `in`.jphe.storyvox.llm.provider.AnthropicTeamsProvider(
        http = okhttp3.OkHttpClient(),
        store = NullStore,
        configFlow = flowOf(LlmConfig()),
        authApi = `in`.jphe.storyvox.llm.auth.AnthropicTeamsAuthApi(okhttp3.OkHttpClient()),
        authRepo = `in`.jphe.storyvox.llm.auth.AnthropicTeamsAuthRepository(NullStore),
        json = kotlinx.serialization.json.Json,
    ) {
        override fun stream(messages: List<LlmMessage>, systemPrompt: String?, model: String?) =
            error("unreachable")
    }

private object NullStore : `in`.jphe.storyvox.llm.LlmCredentialsStore() {
    override fun claudeApiKey(): String? = null
    override fun openAiApiKey(): String? = null
}

// ── Feature-API fakes ──────────────────────────────────────────────

private fun playbackOf(
    fictionId: String? = null,
    chapterId: String? = null,
    chapterTitle: String = "",
    fictionTitle: String = "",
): UiPlaybackState = UiPlaybackState(
    fictionId = fictionId,
    chapterId = chapterId,
    chapterTitle = chapterTitle,
    fictionTitle = fictionTitle,
    coverUrl = null,
    isPlaying = false,
    positionMs = 0L,
    durationMs = 0L,
    sentenceStart = 0,
    sentenceEnd = 0,
    speed = 1f,
    pitch = 1f,
    voiceId = null,
    voiceLabel = "",
)

private class FakeFictionRepo(
    private val fictionId: String,
    private val title: String?,
) : FictionRepositoryUi {
    override val library: Flow<List<UiFiction>> = flowOf(emptyList())
    override val follows: Flow<List<UiFollow>> = flowOf(emptyList())
    override fun fictionById(id: String): Flow<UiFiction?> =
        flowOf(if (id == fictionId && title != null) uiFictionOf(id, title) else null)
    override fun fictionLoadError(id: String): Flow<String?> = flowOf(null)
    override fun chaptersFor(fictionId: String): Flow<List<UiChapter>> = flowOf(emptyList())
    override fun observeIsInLibrary(fictionId: String): Flow<Boolean> = flowOf(false)
    override suspend fun chapterTextById(chapterId: String): String? = null
    override suspend fun setDownloadMode(fictionId: String, mode: DownloadMode) = Unit
    override suspend fun follow(fictionId: String, follow: Boolean) = Unit
    override suspend fun setFollowedRemote(
        fictionId: String,
        followed: Boolean,
    ): `in`.jphe.storyvox.feature.api.SetFollowedRemoteResult =
        `in`.jphe.storyvox.feature.api.SetFollowedRemoteResult.Success
    override suspend fun markAllCaughtUp() = Unit
    override suspend fun refreshFollows() = Unit
    override suspend fun addByUrl(url: String, preferredSourceId: String?): UiAddByUrlResult = UiAddByUrlResult.UnrecognizedUrl
    override fun previewUrl(url: String) = emptyList<`in`.jphe.storyvox.feature.api.UiRouteCandidate>()
}

/**
 * Issue #217 — variant that resolves multiple fiction ids to titles.
 * Needed by the cross-fiction-memory tests, where the prompt-builder
 * resolves OTHER books' titles for the rendered block.
 */
private class FakeFictionRepoMulti(
    private val titlesById: Map<String, String>,
) : FictionRepositoryUi {
    override val library: Flow<List<UiFiction>> = flowOf(emptyList())
    override val follows: Flow<List<UiFollow>> = flowOf(emptyList())
    override fun fictionById(id: String): Flow<UiFiction?> =
        flowOf(titlesById[id]?.let { uiFictionOf(id, it) })
    override fun fictionLoadError(id: String): Flow<String?> = flowOf(null)
    override fun chaptersFor(fictionId: String): Flow<List<UiChapter>> = flowOf(emptyList())
    override fun observeIsInLibrary(fictionId: String): Flow<Boolean> = flowOf(false)
    override suspend fun chapterTextById(chapterId: String): String? = null
    override suspend fun setDownloadMode(fictionId: String, mode: DownloadMode) = Unit
    override suspend fun follow(fictionId: String, follow: Boolean) = Unit
    override suspend fun setFollowedRemote(
        fictionId: String,
        followed: Boolean,
    ): `in`.jphe.storyvox.feature.api.SetFollowedRemoteResult =
        `in`.jphe.storyvox.feature.api.SetFollowedRemoteResult.Success
    override suspend fun markAllCaughtUp() = Unit
    override suspend fun refreshFollows() = Unit
    override suspend fun addByUrl(url: String, preferredSourceId: String?): UiAddByUrlResult = UiAddByUrlResult.UnrecognizedUrl
    override fun previewUrl(url: String) = emptyList<`in`.jphe.storyvox.feature.api.UiRouteCandidate>()
}

private fun uiFictionOf(id: String, title: String) = UiFiction(
    id = id,
    title = title,
    author = "",
    coverUrl = null,
    rating = 0f,
    chapterCount = 0,
    isOngoing = true,
    synopsis = "",
)

private class FakePlayback(initial: UiPlaybackState) : PlaybackControllerUi {
    override val state: StateFlow<UiPlaybackState> = MutableStateFlow(initial).asStateFlow()
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
    override fun nextChapter() = Unit
    override fun previousChapter() = Unit
    override fun setSpeed(speed: Float) = Unit
    override fun setPitch(pitch: Float) = Unit
    override fun setPunctuationPauseMultiplier(multiplier: Float) = Unit
    override fun startListening(
        fictionId: String,
        chapterId: String,
        charOffset: Int,
        autoPlay: Boolean,
    ) = Unit
    override fun startSleepTimer(mode: UiSleepTimerMode) = Unit
    override fun cancelSleepTimer() = Unit
    override suspend fun speakText(text: String) = Unit
    override fun stopSpeaking() = Unit
    override fun bookmarkHere() = Unit
    override fun clearBookmark() = Unit
    override fun jumpToBookmark() = Unit
}

/** Minimal settings stub — only the [settings] flow is read by
 *  ChatViewModel, but [SettingsRepositoryUi] is a wide interface so
 *  every method must be implemented; setters are no-ops. */
private class FakeSettingsRepo(
    chatGrounding: UiChatGrounding = UiChatGrounding(),
    carryMemoryAcrossFictions: Boolean = true,
) : SettingsRepositoryUi {
    private val ai = UiAiSettings(
        chatGrounding = chatGrounding,
        carryMemoryAcrossFictions = carryMemoryAcrossFictions,
    )
    override val settings: Flow<UiSettings> = flowOf(
        UiSettings(
            ttsEngine = "VoxSherpa",
            defaultVoiceId = null,
            defaultSpeed = 1f,
            defaultPitch = 1f,
            themeOverride = ThemeOverride.System,
            downloadOnWifiOnly = true,
            pollIntervalHours = 6,
            isSignedIn = false,
            ai = ai,
        ),
    )
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

/**
 * Issue #217 — in-memory fake for [FictionMemoryRepository]. Mirrors
 * the production upsert + cross-fiction lookup semantics so the
 * ChatViewModel's prompt-builder and extractor paths can be exercised
 * on plain JVM. Composite key (fictionId, name) — last write wins for
 * non-user-edited entries; user-edited entries are protected from
 * AI-overwrite (mirrors the impl behaviour).
 */
private class FakeFictionMemoryRepo : FictionMemoryRepository {
    val entries: MutableMap<Pair<String, String>, FictionMemoryEntry> = mutableMapOf()
    val flows: MutableMap<String, MutableStateFlow<List<FictionMemoryEntry>>> = mutableMapOf()

    /** Synchronous seeding helper for tests — avoids needing a
     *  CoroutineScope to pre-populate the fake before the VM runs. */
    fun seed(
        fictionId: String,
        name: String,
        summary: String,
        entityType: FictionMemoryEntry.Kind = FictionMemoryEntry.Kind.CHARACTER,
    ) {
        entries[fictionId to name] = FictionMemoryEntry(
            fictionId = fictionId,
            entityType = entityType.name,
            name = name,
            summary = summary,
            firstSeenChapterIndex = null,
            lastUpdated = System.currentTimeMillis(),
            userEdited = false,
        )
    }

    override suspend fun recordEntity(
        fictionId: String,
        entityType: FictionMemoryEntry.Kind,
        name: String,
        summary: String,
        firstSeenChapterIndex: Int?,
        userEdited: Boolean,
    ) {
        val key = fictionId to name.trim()
        if (!userEdited) {
            val existing = entries[key]
            if (existing?.userEdited == true) return
        }
        entries[key] = FictionMemoryEntry(
            fictionId = fictionId,
            entityType = entityType.name,
            name = name.trim(),
            summary = summary.trim().take(FictionMemoryRepository.SUMMARY_MAX_CHARS),
            firstSeenChapterIndex = firstSeenChapterIndex,
            lastUpdated = System.currentTimeMillis(),
            userEdited = userEdited,
        )
        flows[fictionId]?.update { entries.values.filter { it.fictionId == fictionId } }
    }

    override suspend fun findEntityAcrossFictions(
        name: String,
        excludeFictionId: String?,
    ): List<FictionMemoryEntry> = entries.values
        .filter { it.name == name.trim() && it.fictionId != excludeFictionId }
        .sortedByDescending { it.lastUpdated }

    override fun entitiesForFiction(fictionId: String) =
        flows.getOrPut(fictionId) {
            MutableStateFlow(entries.values.filter { it.fictionId == fictionId })
        }.asStateFlow()

    override suspend fun forFictionOnce(fictionId: String): List<FictionMemoryEntry> =
        entries.values.filter { it.fictionId == fictionId }

    override suspend fun deleteEntry(fictionId: String, name: String) {
        entries.remove(fictionId to name.trim())
        flows[fictionId]?.update { entries.values.filter { it.fictionId == fictionId } }
    }
}
