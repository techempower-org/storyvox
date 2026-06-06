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
 * Real-DataStore tests for the issue #85 Voice-Determinism (Steady /
 * Expressive) preset persistence + flow surface.
 *
 * Same shape as [SettingsRepositoryModesTest]: spins up a temp-file
 * DataStore so persistence is identical to production. Verifies the
 * default preserves the calmed VITS shipping behavior (Steady = true on
 * fresh install) and that the setter round-trips through the
 * `UiSettings` flow + the [VoiceTuningConfig] snapshot/flow that
 * core-playback's `EnginePlayer` collects from.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryVoiceSteadyTest {

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
            // Test-only LlmCredentialsStore — voice-tuning tests don't touch
            // AI fields; a no-op store is fine.
            llmCreds = `in`.jphe.storyvox.llm.LlmCredentialsStore.forTesting(),
            // GitHubAuth fake (#91) — voice-tuning tests don't touch GitHub
            // state; mirrors the pattern in BufferTest / ModesTest / etc.
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
    fun `default voice-steady is on (Steady)`() = runTest {
        // The default-on bias preserves VoxSherpa's calmed VITS defaults
        // (noise_scale 0.35 / 0.667). Listeners replaying chapters get
        // identical-sounding takes.
        assertEquals(true, repo.currentVoiceSteady())
        assertEquals(true, repo.settings.first().voiceSteady)
        assertEquals(true, repo.voiceSteady.first())
    }

    @Test
    fun `setVoiceSteady false persists and re-emits on settings flow`() = runTest {
        repo.setVoiceSteady(false)
        assertEquals(false, repo.currentVoiceSteady())
        assertEquals(false, repo.settings.first().voiceSteady)
        assertEquals(false, repo.voiceSteady.first())
    }

    @Test
    fun `setVoiceSteady round-trips both directions`() = runTest {
        repo.setVoiceSteady(false)
        assertEquals(false, repo.currentVoiceSteady())
        repo.setVoiceSteady(true)
        assertEquals(true, repo.currentVoiceSteady())
    }

    @Test
    fun `voice-steady persists independently of warmup wait and catchup pause`() = runTest {
        // Catches a regression where two booleans share a Preferences key
        // (e.g. typo in Keys) — the three v1 booleans are all
        // pref_*_v1 keys and a copy-paste mistake there would silently
        // alias them.
        repo.setWarmupWait(false)
        repo.setCatchupPause(false)
        repo.setVoiceSteady(false)

        assertEquals(false, repo.currentWarmupWait())
        assertEquals(false, repo.currentCatchupPause())
        assertEquals(false, repo.currentVoiceSteady())

        repo.setVoiceSteady(true)
        assertEquals(false, repo.currentWarmupWait())
        assertEquals(false, repo.currentCatchupPause())
        assertEquals(true, repo.currentVoiceSteady())

        // Sanity: the same triple surfaces on the joint settings flow.
        val finalSettings = repo.settings.first()
        assertEquals(false, finalSettings.warmupWait)
        assertEquals(false, finalSettings.catchupPause)
        assertEquals(true, finalSettings.voiceSteady)
    }

    // FakeAuth / FakeHydrator / palace fakes live in [SettingsRepositoryTestSupport].
}
