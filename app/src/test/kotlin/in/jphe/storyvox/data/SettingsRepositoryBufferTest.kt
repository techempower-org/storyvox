package `in`.jphe.storyvox.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import `in`.jphe.storyvox.feature.api.BUFFER_DEFAULT_CHUNKS
import `in`.jphe.storyvox.feature.api.BUFFER_MAX_CHUNKS
import `in`.jphe.storyvox.feature.api.BUFFER_MIN_CHUNKS
import `in`.jphe.storyvox.feature.api.BUFFER_RECOMMENDED_MAX_CHUNKS
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Real-DataStore tests for the issue #84 buffer slider persistence.
 *
 * Spins up a temp-file `PreferenceDataStoreFactory` against a JUnit
 * [TemporaryFolder] so the persistence layer is exactly what production
 * runs against — same approach as `VoiceFavoritesTest`. Stubs Auth /
 * SessionHydrator since those aren't exercised by the buffer code path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryBufferTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStore<Preferences>
    private lateinit var repo: SettingsRepositoryUiImpl

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val file = File(tempFolder.newFolder(), "storyvox_settings.preferences_pb")
        store = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { file },
        )
        val pcmBundle = makeFakeCacheBundle(tempFolder.newFolder("pcm_bundle"), scope)
        repo = SettingsRepositoryUiImpl(
            store = store,
            auth = FakeAuth(),
            hydrator = FakeHydrator(),
            palaceConfig = makeFakePalaceConfig(tempFolder.newFolder("palace_ds"), scope),
            palaceApi = makeFakePalaceApi(),
            // Test-only LlmCredentialsStore — bypasses encrypted prefs.
            // The buffer-related tests don't touch AI fields, so a
            // no-op store is fine.
            llmCreds = `in`.jphe.storyvox.llm.LlmCredentialsStore.forTesting(),
            githubAuth = FakeGitHubAuth(),
            teamsAuth = fakeTeamsAuth(),
            rssConfig = makeFakeRssConfig(tempFolder.newFolder("rss_ds"), scope),
            epubConfig = makeFakeEpubConfig(tempFolder.newFolder("epub_ds"), scope),
            pdfConfig = makeFakePdfConfig(tempFolder.newFolder("pdf_ds"), scope),
            outlineConfig = makeFakeOutlineConfig(tempFolder.newFolder("outline_ds"), scope),
            wikipediaConfig = makeFakeWikipediaConfig(tempFolder.newFolder("wiki_ds"), scope),
            notionConfig = makeFakeNotionConfig(tempFolder.newFolder("notion_ds"), scope),
            discordConfig = makeFakeDiscordConfig(tempFolder.newFolder("discord_ds"), scope),
            discordGuildDirectory = makeFakeDiscordGuildDirectory(),
            telegramConfig = makeFakeTelegramConfig(tempFolder.newFolder("telegram_ds"), scope),
            telegramChannelDirectory = makeFakeTelegramChannelDirectory(),
            suggestedFeedsRegistry = SuggestedFeedsRegistry(),
            azureCreds = makeFakeAzureCredentials(),
            azureClient = makeFakeAzureClient(),
            azureRoster = makeFakeAzureRoster(),
            googleTokenSource = `in`.jphe.storyvox.llm.auth.GoogleOAuthTokenSource(okhttp3.OkHttpClient()),
            pcmCache = pcmBundle.pcmCache,
            pcmCacheConfig = pcmBundle.pcmCacheConfig,
            cacheStats = pcmBundle.cacheStats,
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `default buffer is the documented default`() = runTest {
        assertEquals(BUFFER_DEFAULT_CHUNKS, repo.currentBufferChunks())
        assertEquals(BUFFER_DEFAULT_CHUNKS, repo.settings.first().playbackBufferChunks)
    }

    @Test
    fun `setPlaybackBufferChunks persists and re-emits on the flow`() = runTest {
        repo.setPlaybackBufferChunks(64)
        assertEquals(64, repo.currentBufferChunks())
        assertEquals(64, repo.settings.first().playbackBufferChunks)
    }

    @Test
    fun `setPlaybackBufferChunks accepts the recommended-max tick value`() = runTest {
        repo.setPlaybackBufferChunks(BUFFER_RECOMMENDED_MAX_CHUNKS)
        assertEquals(BUFFER_RECOMMENDED_MAX_CHUNKS, repo.currentBufferChunks())
    }

    @Test
    fun `setPlaybackBufferChunks accepts values past the recommended max`() = runTest {
        // Issue #84 — the slider intentionally goes past the conservative tick.
        // Persistence must not clamp at the recommended max; only the absolute
        // mechanical bounds apply.
        val past = BUFFER_RECOMMENDED_MAX_CHUNKS * 8
        repo.setPlaybackBufferChunks(past)
        assertEquals(past, repo.currentBufferChunks())
        assertTrue(
            "expected stored value past recommended max, got ${repo.currentBufferChunks()}",
            repo.currentBufferChunks() > BUFFER_RECOMMENDED_MAX_CHUNKS,
        )
    }

    @Test
    fun `setPlaybackBufferChunks clamps below the minimum`() = runTest {
        repo.setPlaybackBufferChunks(0)
        assertEquals(BUFFER_MIN_CHUNKS, repo.currentBufferChunks())
    }

    @Test
    fun `setPlaybackBufferChunks clamps above the mechanical maximum`() = runTest {
        repo.setPlaybackBufferChunks(BUFFER_MAX_CHUNKS * 10)
        assertEquals(BUFFER_MAX_CHUNKS, repo.currentBufferChunks())
    }

    @Test
    fun `setPlaybackBufferChunks accepts the mechanical maximum exactly`() = runTest {
        repo.setPlaybackBufferChunks(BUFFER_MAX_CHUNKS)
        assertEquals(BUFFER_MAX_CHUNKS, repo.currentBufferChunks())
    }

    // FakeAuth / FakeHydrator / makeFakePalaceConfig / makeFakePalaceApi /
    // FakeSecrets all live in [SettingsRepositoryTestSupport] so the three
    // settings-repo tests share one source of truth as the repo's
    // constructor surface evolves.
}
