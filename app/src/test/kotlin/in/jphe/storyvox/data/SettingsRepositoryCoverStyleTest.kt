package `in`.jphe.storyvox.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import `in`.jphe.storyvox.feature.api.CoverStyle
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
 * Real-DataStore tests for the v0.5.59 `pref_cover_style` book-cover
 * fallback style toggle (#cover-style-toggle).
 *
 * Pins three load-bearing properties:
 *
 *  1. Fresh-install default is [CoverStyle.Monogram] — JP's preference
 *     and the visual revert from the v0.5.51 BrandedCoverTile
 *     experiment. Existing users on v0.5.51..v0.5.58 don't have the
 *     pref persisted, so the absent-key path matters: it must read
 *     Monogram, not Branded.
 *
 *  2. The setter round-trips through the [UiSettings] flow. Same
 *     shape as the a11y enum setters.
 *
 *  3. Unknown / future-rolled-back values on disk fall back to
 *     Monogram on read rather than crashing — protects against a
 *     forward-compat sync push of a CoverStyle value not yet known
 *     locally.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryCoverStyleTest {

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
    fun `default cover style on a fresh install is Monogram`() = runTest {
        // The whole point of v0.5.59 — without an explicit pref, users
        // see the classic Monogram tile, not the v0.5.51 Branded tile.
        // If this test ever flips to Branded, an upgrading user loses
        // their visual revert.
        assertEquals(CoverStyle.Monogram, repo.settings.first().coverStyle)
    }

    @Test
    fun `setCoverStyle round-trips through the settings flow`() = runTest {
        repo.setCoverStyle(CoverStyle.Branded)
        assertEquals(CoverStyle.Branded, repo.settings.first().coverStyle)

        repo.setCoverStyle(CoverStyle.CoverOnly)
        assertEquals(CoverStyle.CoverOnly, repo.settings.first().coverStyle)

        repo.setCoverStyle(CoverStyle.Monogram)
        assertEquals(CoverStyle.Monogram, repo.settings.first().coverStyle)
    }

    @Test
    fun `unknown stored value falls back to Monogram on read`() = runTest {
        // A forward-compat sync push (or a future-rolled-back enum
        // value sitting on disk) must not crash the settings flow.
        // The read path uses runCatching + valueOf() and falls to
        // Monogram on parse failure.
        val coverStyleKey = stringPreferencesKey("pref_cover_style")
        store.edit { it[coverStyleKey] = "SomeFutureValueWeDontKnow" }

        assertEquals(CoverStyle.Monogram, repo.settings.first().coverStyle)
    }
}
