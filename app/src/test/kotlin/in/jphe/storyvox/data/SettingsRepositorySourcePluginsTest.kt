package `in`.jphe.storyvox.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Plugin-seam Phase 3 (#384) — real-DataStore tests for the registry-
 * driven `setSourcePluginEnabled` + `sourcePluginsEnabled` round-trip
 * on [SettingsRepositoryUiImpl].
 *
 * Verifies:
 *  - Round-tripping toggles for every in-tree plugin id lands in the
 *    persisted JSON map.
 *  - The one-shot [SourcePluginsMapMigration] seeds the JSON map from
 *    the legacy `pref_source_*_enabled` boolean keys on first read.
 *  - Subsequent toggles do NOT dual-write into the legacy keys (Phase
 *    3 removed the dual-write).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositorySourcePluginsTest {

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
    fun `setSourcePluginEnabled round-trips for every in-tree plugin id`() = runTest {
        val allIds = listOf(
            SourceIds.ROYAL_ROAD, SourceIds.GITHUB, SourceIds.MEMPALACE,
            SourceIds.RSS, SourceIds.EPUB, SourceIds.OUTLINE,
            SourceIds.GUTENBERG, SourceIds.AO3, SourceIds.STANDARD_EBOOKS,
            SourceIds.WIKIPEDIA, SourceIds.WIKISOURCE,
            // Issue #417 — :source-kvmr generalized to :source-radio.
            // RADIO is the new canonical id; KVMR is kept as a one-cycle
            // migration alias and stays in the round-trip set so the
            // dual-key persistence shape is exercised.
            SourceIds.RADIO, SourceIds.KVMR,
            // Issue #770 — split NOTION into TECHEMPOWER + PAT.
            // Legacy NOTION alias is kept for one migration cycle.
            SourceIds.NOTION_TECHEMPOWER, SourceIds.NOTION_PAT, SourceIds.NOTION,
            SourceIds.HACKERNEWS, SourceIds.ARXIV,
            SourceIds.PLOS, SourceIds.DISCORD,
            // #462 — Telegram backend.
            SourceIds.TELEGRAM,
        )
        assertEquals(21, allIds.size)

        // Toggle each off then on, in order — verify both states
        // land in the persisted map.
        for (id in allIds) {
            repo.setSourcePluginEnabled(id, enabled = false)
            assertEquals(
                "Expected $id = false after setSourcePluginEnabled(false)",
                false,
                repo.settings.first().sourcePluginsEnabled[id],
            )
            repo.setSourcePluginEnabled(id, enabled = true)
            assertEquals(
                "Expected $id = true after setSourcePluginEnabled(true)",
                true,
                repo.settings.first().sourcePluginsEnabled[id],
            )
        }
    }

    @Test
    fun `legacy SOURCE_XXX_ENABLED keys seed the JSON map on first read`() = runTest {
        // Pre-seed legacy keys for three sources WITHOUT writing the
        // JSON map — exactly the upgrade-from-v0.5.30 state.
        val legacyRoyalRoad = booleanPreferencesKey("pref_source_royalroad_enabled")
        val legacyGitHub = booleanPreferencesKey("pref_source_github_enabled")
        val legacyKvmr = booleanPreferencesKey("pref_source_kvmr_enabled")
        store.edit { prefs ->
            prefs[legacyRoyalRoad] = false
            prefs[legacyGitHub] = true
            prefs[legacyKvmr] = false
        }

        // First read of `repo.settings` triggers the migration, which
        // seeds the JSON map from the legacy keys. Verify the
        // user's pre-migration toggle state survives.
        val first = repo.settings.first()
        assertEquals(false, first.sourcePluginsEnabled[SourceIds.ROYAL_ROAD])
        assertEquals(true, first.sourcePluginsEnabled[SourceIds.GITHUB])
        assertEquals(false, first.sourcePluginsEnabled[SourceIds.KVMR])
    }

    @Test
    fun `setSourcePluginEnabled does not dual-write into legacy boolean keys (Phase 3)`() = runTest {
        // Phase 1/2 dual-wrote each toggle into the matching legacy
        // SOURCE_XXX_ENABLED key. Phase 3 stopped doing that. This
        // test pins the new behaviour: after toggling via the
        // registry entry-point, the legacy key stays absent (or at
        // whatever value the migration seeded it to — null here for
        // a fresh install).
        val legacyHackerNews = booleanPreferencesKey("pref_source_hackernews_enabled")
        repo.setSourcePluginEnabled(SourceIds.HACKERNEWS, enabled = true)

        val prefs = store.data.first()
        assertNull(
            "Phase 3: legacy SOURCE_HACKERNEWS_ENABLED should not be written by setSourcePluginEnabled",
            prefs[legacyHackerNews],
        )
        // ...but the JSON map should reflect the toggle.
        assertEquals(
            true,
            repo.settings.first().sourcePluginsEnabled[SourceIds.HACKERNEWS],
        )
    }

    @Test
    fun `unknown plugin ids still land in the map for forward-compat`() = runTest {
        // An out-of-tree plugin pre-populating its toggle state before
        // its @SourcePlugin annotation lands should round-trip.
        repo.setSourcePluginEnabled("future-backend", enabled = true)
        assertEquals(true, repo.settings.first().sourcePluginsEnabled["future-backend"])
    }

    @Test
    fun `fresh install seeds every plugin id to ON for chip-strip discoverability`() = runTest {
        // #436 — fresh-install regression: v0.5.31..v0.5.36 shipped 12
        // of the 17 backends with `defaultEnabled = false`, so the
        // Browse chip strip only listed 5/17 backends. The product
        // invariant is the opposite: every registered backend gets a
        // chip on first launch — the chip strip is the *only* place a
        // new user learns these backends exist. Users still prune via
        // Settings → Plugins.
        //
        // First read on a clean store runs SourcePluginsMapMigration
        // with no legacy keys present; the migration seeds each id
        // from LegacySourceKeys.ALL[id].defaultValue. Asserting on
        // that snapshot pins the fresh-install behaviour without
        // depending on the registry (the registry lives in :core-data
        // and isn't wired through this test's repo construction).
        val expectedIds = listOf(
            SourceIds.ROYAL_ROAD, SourceIds.GITHUB, SourceIds.MEMPALACE,
            SourceIds.RSS, SourceIds.EPUB, SourceIds.OUTLINE,
            SourceIds.GUTENBERG, SourceIds.AO3, SourceIds.STANDARD_EBOOKS,
            SourceIds.WIKIPEDIA, SourceIds.WIKISOURCE,
            SourceIds.RADIO, SourceIds.KVMR,
            // Issue #770 — split NOTION into TECHEMPOWER + PAT.
            SourceIds.NOTION_TECHEMPOWER, SourceIds.NOTION,
            SourceIds.HACKERNEWS, SourceIds.ARXIV, SourceIds.PLOS,
            SourceIds.DISCORD,
            // #462 — Telegram backend defaults ON for fresh-install
            // chip-strip discoverability; backend stays inert until
            // the user enters a bot token in Settings.
            SourceIds.TELEGRAM,
        )

        val snapshot = repo.settings.first().sourcePluginsEnabled
        for (id in expectedIds) {
            assertEquals(
                "Fresh install should enable $id by default (see #436)",
                true,
                snapshot[id],
            )
        }
        // NOTION_PAT defaults OFF — private workspace requires token setup.
        assertEquals(
            "Fresh install should disable NOTION_PAT by default",
            false,
            snapshot[SourceIds.NOTION_PAT],
        )
    }

    @Test
    fun `legacy explicit OFF survives the fresh-install default flip`() = runTest {
        // #436 sibling check — an upgrading user who disabled a source
        // in v0.5.31..v0.5.36 must NOT see that source re-enabled by
        // the new default. The migration reads each legacy boolean key
        // (when present) and only falls back to the new `defaultValue`
        // when the key was never written. Pre-seed GITHUB=false to
        // simulate "user disabled GitHub before upgrading"; verify the
        // post-migration snapshot keeps GITHUB=false even though the
        // new default for fresh installs is now true.
        val legacyGitHub = booleanPreferencesKey("pref_source_github_enabled")
        store.edit { prefs ->
            prefs[legacyGitHub] = false
        }

        val snapshot = repo.settings.first().sourcePluginsEnabled
        assertEquals(
            "Upgrading user's explicit GitHub=false must not be clobbered by the #436 default flip",
            false,
            snapshot[SourceIds.GITHUB],
        )
        // And sources with no legacy key still take the new default.
        assertEquals(true, snapshot[SourceIds.WIKIPEDIA])
        assertEquals(true, snapshot[SourceIds.HACKERNEWS])
    }

    @Test
    fun `independent toggles do not affect each other`() = runTest {
        repo.setSourcePluginEnabled(SourceIds.NOTION_PAT, enabled = false)
        repo.setSourcePluginEnabled(SourceIds.WIKIPEDIA, enabled = true)
        val snapshot = repo.settings.first().sourcePluginsEnabled

        assertEquals(false, snapshot[SourceIds.NOTION_PAT])
        assertEquals(true, snapshot[SourceIds.WIKIPEDIA])
        // No cross-contamination — these are two independent keys.
        assertTrue(snapshot.containsKey(SourceIds.NOTION_PAT))
        assertTrue(snapshot.containsKey(SourceIds.WIKIPEDIA))
    }
}
