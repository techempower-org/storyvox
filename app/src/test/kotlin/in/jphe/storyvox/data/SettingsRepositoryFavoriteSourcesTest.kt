package `in`.jphe.storyvox.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import `in`.jphe.storyvox.data.source.SourceIds
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v0.5.76 — real-DataStore tests for [SettingsRepositoryUiImpl.setSourceFavorite].
 *
 * Verifies the round-trip from `setSourceFavorite(id, true|false)` to the
 * persisted `UiSettings.favoriteSourceIds` Set. Mirrors the
 * [SettingsRepositorySourcePluginsTest] setup so the two stay in
 * lockstep — favorites use the same DataStore + JSON codec posture as
 * the enabled map, just with a Set shape.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryFavoriteSourcesTest {

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
    fun `fresh install starts with empty favoriteSourceIds`() = runTest {
        val snapshot = repo.settings.first()
        assertTrue(
            "Expected empty favorites on fresh install, got ${snapshot.favoriteSourceIds}",
            snapshot.favoriteSourceIds.isEmpty(),
        )
    }

    @Test
    fun `setSourceFavorite adds the id to the set`() = runTest {
        repo.setSourceFavorite(SourceIds.ROYAL_ROAD, favorite = true)

        val snapshot = repo.settings.first()
        assertTrue(SourceIds.ROYAL_ROAD in snapshot.favoriteSourceIds)
    }

    @Test
    fun `setSourceFavorite false removes the id from the set`() = runTest {
        repo.setSourceFavorite(SourceIds.ROYAL_ROAD, favorite = true)
        repo.setSourceFavorite(SourceIds.AO3, favorite = true)
        repo.setSourceFavorite(SourceIds.ROYAL_ROAD, favorite = false)

        val snapshot = repo.settings.first()
        assertFalse(SourceIds.ROYAL_ROAD in snapshot.favoriteSourceIds)
        assertTrue(
            "AO3 should remain favorited after RR was un-favorited",
            SourceIds.AO3 in snapshot.favoriteSourceIds,
        )
    }

    @Test
    fun `setSourceFavorite is idempotent for repeated true calls`() = runTest {
        repo.setSourceFavorite(SourceIds.GUTENBERG, favorite = true)
        repo.setSourceFavorite(SourceIds.GUTENBERG, favorite = true)
        repo.setSourceFavorite(SourceIds.GUTENBERG, favorite = true)

        val snapshot = repo.settings.first()
        assertEquals(setOf(SourceIds.GUTENBERG), snapshot.favoriteSourceIds)
    }

    @Test
    fun `setSourceFavorite accepts unknown ids (forward-compat)`() = runTest {
        // Same posture as setSourcePluginEnabled — an out-of-tree id
        // round-trips into the Set without validation. Plugins that
        // pre-populate their favorited state before their annotation
        // lands shouldn't lose the bit on a startup race.
        repo.setSourceFavorite("future-backend", favorite = true)

        val snapshot = repo.settings.first()
        assertTrue("future-backend" in snapshot.favoriteSourceIds)
    }

    @Test
    fun `multiple favorites coexist`() = runTest {
        repo.setSourceFavorite(SourceIds.ROYAL_ROAD, favorite = true)
        repo.setSourceFavorite(SourceIds.AO3, favorite = true)
        repo.setSourceFavorite(SourceIds.GUTENBERG, favorite = true)

        val snapshot = repo.settings.first()
        assertEquals(
            setOf(SourceIds.ROYAL_ROAD, SourceIds.AO3, SourceIds.GUTENBERG),
            snapshot.favoriteSourceIds,
        )
    }

    @Test
    fun `un-favoriting an id that was never favorited is a no-op`() = runTest {
        repo.setSourceFavorite(SourceIds.WIKIPEDIA, favorite = false)

        val snapshot = repo.settings.first()
        assertTrue(snapshot.favoriteSourceIds.isEmpty())
    }
}
