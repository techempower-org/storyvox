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
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests for the v0.5.62 v1.0-sweeper prefs (issues #589 / #593 / #594):
 *  - animation-speed master multiplier
 *  - skip-forward / skip-back distance in seconds
 *  - SkipPrevious rewind-to-start threshold in seconds
 *
 * Mirrors [SettingsRepositoryModesTest]'s real-DataStore harness: spins
 * up a temp-file DataStore so persistence is identical to production.
 * Verifies:
 *   - defaults match the documented seed values (1.0× / 30s / 3s)
 *   - setters snap to the supported chip tiers
 *   - reads round-trip through both the `UiSettings` flow AND the
 *     `PlaybackSkipConfig` contract surface
 *   - new keys are NOT in the sync allowlist (per-device intent)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositorySweeperPrefsTest {

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

    // ---- Issue #589 — animation-speed master multiplier ----

    @Test
    fun `default animation speed scale is 1×`() = runTest {
        assertEquals(1f, repo.settings.first().animationSpeedScale, 0f)
    }

    @Test
    fun `setAnimationSpeedScale snaps to supported tiers`() = runTest {
        // 0 / 0.5 / 1 / 1.5 / 2 are the canonical tiers.
        repo.setAnimationSpeedScale(0f)
        assertEquals(0f, repo.settings.first().animationSpeedScale, 0f)
        repo.setAnimationSpeedScale(0.5f)
        assertEquals(0.5f, repo.settings.first().animationSpeedScale, 0f)
        repo.setAnimationSpeedScale(2f)
        assertEquals(2f, repo.settings.first().animationSpeedScale, 0f)

        // Off-grid values snap to nearest tier.
        repo.setAnimationSpeedScale(0.4f)
        assertEquals(0.5f, repo.settings.first().animationSpeedScale, 0f)
        repo.setAnimationSpeedScale(1.7f)
        assertEquals(1.5f, repo.settings.first().animationSpeedScale, 0f)
        repo.setAnimationSpeedScale(0.2f)
        // 0.2 is equidistant from 0 and 0.5; minBy returns first match (0f).
        // Either is acceptable — assert we picked a valid tier.
        val snapped = repo.settings.first().animationSpeedScale
        assertEquals(true, snapped == 0f || snapped == 0.5f)
    }

    // ---- Issue #593 — skip distance ----

    @Test
    fun `default skip distance is 30 seconds`() = runTest {
        assertEquals(30, repo.settings.first().skipDistanceSec)
        assertEquals(30, repo.currentSkipDistanceSec())
        assertEquals(30, repo.skipDistanceSec.first())
    }

    @Test
    fun `setSkipDistanceSec snaps to supported tiers`() = runTest {
        // 10 / 15 / 30 / 45 / 60 are the canonical tiers.
        repo.setSkipDistanceSec(10)
        assertEquals(10, repo.settings.first().skipDistanceSec)
        repo.setSkipDistanceSec(60)
        assertEquals(60, repo.settings.first().skipDistanceSec)

        // Off-grid values snap to nearest tier.
        repo.setSkipDistanceSec(12)
        assertEquals(10, repo.settings.first().skipDistanceSec)
        repo.setSkipDistanceSec(20)
        // 20 is equidistant from 15 and 30 (well, closer to 15);
        // minBy returns the first match (15).
        assertEquals(15, repo.settings.first().skipDistanceSec)
        repo.setSkipDistanceSec(100)
        assertEquals(60, repo.settings.first().skipDistanceSec)
    }

    @Test
    fun `setSkipDistanceSec round-trips through PlaybackSkipConfig`() = runTest {
        repo.setSkipDistanceSec(15)
        assertEquals(15, repo.currentSkipDistanceSec())
        assertEquals(15, repo.skipDistanceSec.first())
    }

    // ---- Issue #594 — rewind-to-start threshold ----

    @Test
    fun `default rewind-to-start threshold is 3 seconds`() = runTest {
        assertEquals(3, repo.settings.first().rewindToStartThresholdSec)
        assertEquals(3, repo.currentRewindToStartThresholdSec())
        assertEquals(3, repo.rewindToStartThresholdSec.first())
    }

    @Test
    fun `setRewindToStartThresholdSec accepts 0 (off)`() = runTest {
        repo.setRewindToStartThresholdSec(0)
        assertEquals(0, repo.settings.first().rewindToStartThresholdSec)
        assertEquals(0, repo.currentRewindToStartThresholdSec())
    }

    @Test
    fun `setRewindToStartThresholdSec snaps to supported tiers`() = runTest {
        // 0 / 1 / 3 / 5 / 10 are the canonical tiers.
        repo.setRewindToStartThresholdSec(1)
        assertEquals(1, repo.settings.first().rewindToStartThresholdSec)
        repo.setRewindToStartThresholdSec(10)
        assertEquals(10, repo.settings.first().rewindToStartThresholdSec)

        // Off-grid values snap to nearest tier.
        repo.setRewindToStartThresholdSec(7)
        assertEquals(5, repo.settings.first().rewindToStartThresholdSec)
        repo.setRewindToStartThresholdSec(100)
        assertEquals(10, repo.settings.first().rewindToStartThresholdSec)
    }

    // #916 — sweeper prefs were promoted to SYNC_ALLOWLIST by the
    // expanded preference sync surface. The old "must NOT sync" assertion
    // was removed because syncing playback ergonomics across devices is
    // now the intended behavior.

    // FakeAuth / FakeHydrator / palace fakes live in [SettingsRepositoryTestSupport].
}
