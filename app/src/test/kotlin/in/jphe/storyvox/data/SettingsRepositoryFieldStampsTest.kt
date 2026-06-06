package `in`.jphe.storyvox.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Issue #978 — diff-based per-key stamping in [SettingsRepositoryUiImpl].
 *
 * The field-level merge in `:core-sync`'s `SettingsSyncer` relies on the
 * snapshot source surfacing a correct per-key `updatedAt` via
 * [SettingsRepositoryUiImpl.fieldStamps]. That stamp map is derived
 * WITHOUT touching any `set*` mutator — at the single
 * `stampSyncedWrite` chokepoint we diff the current snapshot against a
 * persisted baseline and bump only the keys that changed. These tests
 * prove that diff:
 *  - editing one key stamps ONLY that key (not the whole allowlist),
 *  - a later edit to a different key advances only its own stamp,
 *  - [SettingsRepositoryUiImpl.applyStamped] records the INCOMING merge
 *    stamps rather than re-stamping everything `now`.
 *
 * Wiring mirrors [SettingsRepositoryPushOnWriteTest] — a real
 * PreferenceDataStore over a temp file, a recording push lambda, and a
 * test dispatcher so the debounce resolves in virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryFieldStampsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStore<Preferences>

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val file = File(tempFolder.newFolder(), "storyvox_settings.preferences_pb")
        store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

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
            pushSettings = { },
            pushDispatcher = dispatcher,
        )
    }

    @Test
    fun `editing one key stamps only that key`() = runTest {
        val repo = makeRepo(StandardTestDispatcher(testScheduler))

        // Use a setter that funnels through stampSyncedWrite (the
        // source-order/favorites cluster — same as the #977 push test).
        repo.setSourceDisplayOrder(listOf("ao3", "royalroad"))
        advanceUntilIdle()

        val stamps = repo.fieldStamps()
        assertTrue(
            "display-order key must be stamped after editing it",
            stamps.containsKey("settings.pref_source_display_order_v1"),
        )
        // Diff-based: an UNTOUCHED key gets no per-key stamp (it falls
        // back to the global stamp in the syncer). The favorites key,
        // never written, must be absent.
        assertNull(
            "an unwritten key must NOT be stamped",
            stamps["settings.pref_source_favorites_v1"],
        )
    }

    @Test
    fun `a later edit advances only its own key's stamp`() = runTest {
        val repo = makeRepo(StandardTestDispatcher(testScheduler))

        repo.setSourceDisplayOrder(listOf("ao3"))
        advanceUntilIdle()
        val orderStamp = repo.fieldStamps()["settings.pref_source_display_order_v1"]!!

        // A distinct second edit to a DIFFERENT key.
        repo.setSourceFavorite("ao3", favorite = true)
        advanceUntilIdle()
        val after = repo.fieldStamps()

        assertEquals(
            "the display-order stamp must be unchanged by a later favorites edit",
            orderStamp,
            after["settings.pref_source_display_order_v1"],
        )
        assertTrue(
            "the favorites key must now be stamped",
            after.containsKey("settings.pref_source_favorites_v1"),
        )
    }

    @Test
    fun `applyStamped records the incoming merge stamps, not now`() = runTest {
        val repo = makeRepo(StandardTestDispatcher(testScheduler))

        // Simulate a remote merge landing two keys with fixed, OLD stamps.
        repo.applyStamped(
            snapshot = mapOf(
                "settings.pref_theme_override" to "LIGHT",
                "settings.pref_default_speed" to "0.9",
            ),
            stamps = mapOf(
                "settings.pref_theme_override" to 4_000L,
                "settings.pref_default_speed" to 5_000L,
            ),
        )
        advanceUntilIdle()

        val stamps = repo.fieldStamps()
        assertEquals(
            "applyStamped must persist the incoming theme stamp verbatim",
            4_000L,
            stamps["settings.pref_theme_override"],
        )
        assertEquals(
            "applyStamped must persist the incoming speed stamp verbatim",
            5_000L,
            stamps["settings.pref_default_speed"],
        )
        // The global lastLocalWriteAt advances to the newest applied field.
        assertEquals(5_000L, repo.lastLocalWriteAt())
    }
}
