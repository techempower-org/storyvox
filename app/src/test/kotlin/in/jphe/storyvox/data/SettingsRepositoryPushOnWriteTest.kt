package `in`.jphe.storyvox.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import `in`.jphe.storyvox.sync.domain.SettingsSyncer
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Issue #977 — push-on-every-write regression guard.
 *
 * Before this fix, synced preference setters ended at [stampSyncedWrite],
 * which only bumped a local timestamp; nothing in the app ever called
 * [SyncCoordinator.requestPush] for the "settings" domain except sign-in
 * and the manual "Sync now." So a preference change never reached
 * InstantDB until a cold start, and a cold-start pull could clobber the
 * un-pushed local change.
 *
 * These tests lock in that ANY synced write now schedules a debounced
 * push of the "settings" domain, and that a burst of writes coalesces
 * into ONE push (so dragging the source-order list doesn't spam the
 * backend).
 *
 * The push action is exercised through the [SettingsRepositoryUiImpl]
 * `pushSettings` seam (a recording lambda here) instead of a real
 * [SyncCoordinator], which is final and would drag in the whole
 * InstantDB client/session/prefs stack. The debounce runs on a
 * [StandardTestDispatcher] sharing the test scheduler, so the 1.5s
 * window resolves in virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryPushOnWriteTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStore<Preferences>

    /** Records each push the repo fires after the debounce. */
    private var pushCount = 0

    @Before
    fun setUp() {
        // Store IO runs eagerly on an Unconfined test dispatcher (mirrors
        // the other SettingsRepository tests); only the debounce uses the
        // runTest scheduler so virtual time controls the 1.5s window.
        scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val file = File(tempFolder.newFolder(), "storyvox_settings.preferences_pb")
        store = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { file },
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** Build the repo wired to a recording push action + a test-driven
     *  debounce dispatcher so virtual time controls the 1.5s window. */
    private fun makeRepo(dispatcher: TestDispatcher): SettingsRepositoryUiImpl {
        val pcmBundle = makeFakeCacheBundle(tempFolder.newFolder("pcm_bundle"), scope)
        return SettingsRepositoryUiImpl(
            store = store,
            auth = FakeAuth(),
            hydrator = FakeHydrator(),
            palaceConfig = makeFakePalaceConfig(tempFolder.newFolder("palace_ds"), scope),
            palaceApi = makeFakePalaceApi(),
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
            googleTokenSource =
                `in`.jphe.storyvox.llm.auth.GoogleOAuthTokenSource(okhttp3.OkHttpClient()),
            pcmCache = pcmBundle.pcmCache,
            pcmCacheConfig = pcmBundle.pcmCacheConfig,
            cacheStats = pcmBundle.cacheStats,
            pushSettings = { pushCount++ },
            pushDispatcher = dispatcher,
        )
    }

    @Test
    fun `synced write schedules a debounced settings push`() = runTest {
        val repo = makeRepo(StandardTestDispatcher(testScheduler))

        repo.setSourceDisplayOrder(listOf("ao3", "royalroad"))
        // The push is debounced — nothing fires synchronously with the write.
        assertEquals("push must NOT fire before the debounce window", 0, pushCount)

        // Let the 1.5s debounce elapse in virtual time.
        advanceUntilIdle()
        assertEquals("exactly one push after the debounce", 1, pushCount)
    }

    @Test
    fun `a burst of synced writes coalesces into a single push`() = runTest {
        val repo = makeRepo(StandardTestDispatcher(testScheduler))

        // Simulate dragging the source-order list: several rapid writes,
        // each well within the debounce window of the last.
        repo.setSourceDisplayOrder(listOf("ao3"))
        advanceTimeBy(200)
        repo.setSourceDisplayOrder(listOf("ao3", "royalroad"))
        advanceTimeBy(200)
        repo.setSourceFavorite("ao3", favorite = true)
        advanceTimeBy(200)
        repo.setSourceDisplayOrder(listOf("royalroad", "ao3"))

        // Still inside the window after the last write — no push yet.
        assertEquals("burst must not push mid-stream", 0, pushCount)

        advanceUntilIdle()
        assertEquals("burst collapses into ONE push", 1, pushCount)
    }

    @Test
    fun `push domain matches the SettingsSyncer domain`() {
        // Guards against the two strings drifting — requestPush(domain)
        // silently no-ops if no syncer registers under that name.
        assertEquals(
            SettingsSyncer.DOMAIN,
            SettingsRepositoryUiImpl.SETTINGS_PUSH_DOMAIN,
        )
    }
}
