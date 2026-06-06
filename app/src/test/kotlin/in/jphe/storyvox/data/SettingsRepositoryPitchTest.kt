package `in`.jphe.storyvox.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Persistence-layer tests for the [SettingsRepositoryUiImpl.setDefaultPitch]
 * coercion band, tightened from `0.5..2.0` to `0.6..1.4` in Thalia's
 * VoxSherpa P0 #1 (2026-05-08).
 *
 * Why a coercion change is worth a test:
 *  - The UI sliders (`SettingsScreen` + `AudiobookView`) live in the
 *    `feature` module and can drift independently of this clamp.
 *  - A future code path (e.g. AI-suggested defaults, voice-import) might
 *    bypass the slider and call [setDefaultPitch] directly. The clamp is
 *    the last line of defense before the value reaches Sonic, and below
 *    ~0.7 Sonic introduces audible artifacts on Piper-medium voices.
 *  - The previous wider band (0.5..2.0) lived through the 0.85..1.15
 *    UI era — anyone with a stale persisted pitch outside the new
 *    0.6..1.4 will be re-coerced on next write, so this test also pins
 *    the migration semantics.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryPitchTest {

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
            llmCreds = `in`.jphe.storyvox.llm.LlmCredentialsStore.forTesting(),
            // GitHubAuth fake (#91) — pitch tests don't touch GitHub state;
            // mirrors the pattern in BufferTest / ModesTest / PunctuationPauseTest.
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
    fun `default pitch is 1_0 when nothing has been persisted`() = runTest {
        assertEquals(1.0f, repo.settings.first().defaultPitch, 0.0001f)
    }

    @Test
    fun `setDefaultPitch persists a value within the band`() = runTest {
        repo.setDefaultPitch(0.85f)
        assertEquals(0.85f, repo.settings.first().defaultPitch, 0.0001f)

        repo.setDefaultPitch(1.25f)
        assertEquals(1.25f, repo.settings.first().defaultPitch, 0.0001f)
    }

    @Test
    fun `setDefaultPitch coerces below 0_6 up to the floor`() = runTest {
        // Thalia's hard floor — below ~0.7 Sonic on Piper-medium starts
        // introducing audible artifacts. 0.6 is the conservative limit.
        repo.setDefaultPitch(0.5f)
        assertEquals(0.6f, repo.settings.first().defaultPitch, 0.0001f)

        repo.setDefaultPitch(0.0f)
        assertEquals(0.6f, repo.settings.first().defaultPitch, 0.0001f)

        repo.setDefaultPitch(-1.0f)
        assertEquals(0.6f, repo.settings.first().defaultPitch, 0.0001f)
    }

    @Test
    fun `setDefaultPitch coerces above 1_4 down to the ceiling`() = runTest {
        repo.setDefaultPitch(1.5f)
        assertEquals(1.4f, repo.settings.first().defaultPitch, 0.0001f)

        repo.setDefaultPitch(2.0f)
        assertEquals(1.4f, repo.settings.first().defaultPitch, 0.0001f)

        repo.setDefaultPitch(99.0f)
        assertEquals(1.4f, repo.settings.first().defaultPitch, 0.0001f)
    }

    @Test
    fun `setDefaultPitch accepts the band edges exactly`() = runTest {
        repo.setDefaultPitch(0.6f)
        assertEquals(0.6f, repo.settings.first().defaultPitch, 0.0001f)

        repo.setDefaultPitch(1.4f)
        assertEquals(1.4f, repo.settings.first().defaultPitch, 0.0001f)
    }
}
