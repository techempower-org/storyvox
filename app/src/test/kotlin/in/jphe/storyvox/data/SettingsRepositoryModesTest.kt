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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Real-DataStore tests for the issue #98 Mode A / Mode B toggles.
 *
 * Same shape as [SettingsRepositoryBufferTest]: spins up a temp-file
 * DataStore so persistence is identical to production. Verifies the
 * defaults preserve v0.4.30 behavior (warmup wait + catch-up pause both
 * on by default), and that both setters round-trip through the
 * `UiSettings` flow + the `PlaybackModeConfig` snapshot/flow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryModesTest {

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
            // Test-only LlmCredentialsStore (#81) — bypasses encrypted prefs.
            // Modes tests don't touch AI fields, so a no-op store is fine.
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

    // ---- Mode A — Warm-up Wait ----

    @Test
    fun `default warmup wait is off`() = runTest {
        // 2026-05-09: defaults flipped per JP — all Performance &
        // Buffering toggles default off on fresh installs. Existing
        // installs keep their persisted preference.
        assertEquals(false, repo.currentWarmupWait())
        assertEquals(false, repo.settings.first().warmupWait)
        assertEquals(false, repo.warmupWait.first())
    }

    @Test
    fun `setWarmupWait persists and re-emits on settings flow`() = runTest {
        repo.setWarmupWait(false)
        assertEquals(false, repo.currentWarmupWait())
        assertEquals(false, repo.settings.first().warmupWait)
        assertEquals(false, repo.warmupWait.first())
    }

    @Test
    fun `setWarmupWait round-trips both directions`() = runTest {
        repo.setWarmupWait(false)
        assertEquals(false, repo.currentWarmupWait())
        repo.setWarmupWait(true)
        assertEquals(true, repo.currentWarmupWait())
    }

    // ---- Mode B — Catch-up Pause ----

    @Test
    fun `default catchup pause is on`() = runTest {
        // 2026-05-09: warmupWait flipped off but catchupPause stayed
        // on — JP reported audible gaps with Mode B disabled. See
        // UiSettings.catchupPause kdoc.
        assertEquals(true, repo.currentCatchupPause())
        assertEquals(true, repo.settings.first().catchupPause)
        assertEquals(true, repo.catchupPause.first())
    }

    @Test
    fun `setCatchupPause persists and re-emits on settings flow`() = runTest {
        repo.setCatchupPause(false)
        assertEquals(false, repo.currentCatchupPause())
        assertEquals(false, repo.settings.first().catchupPause)
        assertEquals(false, repo.catchupPause.first())
    }

    @Test
    fun `setCatchupPause round-trips both directions`() = runTest {
        repo.setCatchupPause(false)
        assertEquals(false, repo.currentCatchupPause())
        repo.setCatchupPause(true)
        assertEquals(true, repo.currentCatchupPause())
    }

    @Test
    fun `Mode A and Mode B persist independently`() = runTest {
        // Flipping one must not affect the other. Catches a regression
        // where both keys end up sharing a Preferences key (e.g. typo).
        // Walks through (true,true) → (false,true) → (false,false) →
        // (true,false) so each persisted state is exercised — works
        // regardless of which way the defaults are set.
        repo.setWarmupWait(true)
        repo.setCatchupPause(true)
        assertEquals(true, repo.currentWarmupWait())
        assertEquals(true, repo.currentCatchupPause())

        repo.setWarmupWait(false)
        assertEquals(false, repo.currentWarmupWait())
        assertEquals(true, repo.currentCatchupPause())

        repo.setCatchupPause(false)
        assertEquals(false, repo.currentWarmupWait())
        assertEquals(false, repo.currentCatchupPause())

        repo.setWarmupWait(true)
        assertEquals(true, repo.currentWarmupWait())
        assertEquals(false, repo.currentCatchupPause())

        // Sanity: settings flow surfaces the same final state.
        val finalSettings = repo.settings.first()
        assertTrue(
            "expected warmupWait=true catchupPause=false, got $finalSettings",
            finalSettings.warmupWait && !finalSettings.catchupPause,
        )
    }

    // FakeAuth / FakeHydrator / palace fakes live in [SettingsRepositoryTestSupport].
}
