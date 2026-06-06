package `in`.jphe.storyvox.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import `in`.jphe.storyvox.feature.api.PUNCTUATION_PAUSE_DEFAULT_MULTIPLIER
import `in`.jphe.storyvox.feature.api.PUNCTUATION_PAUSE_LONG_MULTIPLIER
import `in`.jphe.storyvox.feature.api.PUNCTUATION_PAUSE_MAX_MULTIPLIER
import `in`.jphe.storyvox.feature.api.PUNCTUATION_PAUSE_MIN_MULTIPLIER
import `in`.jphe.storyvox.feature.api.PUNCTUATION_PAUSE_NORMAL_MULTIPLIER
import `in`.jphe.storyvox.feature.api.PUNCTUATION_PAUSE_OFF_MULTIPLIER
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Real-DataStore tests for the issue #109 punctuation-pause continuous
 * slider — slider value persistence (round-trip), the legacy enum →
 * multiplier migration, and the absolute clamp at the engine's [0..4]
 * range. Mirrors [SettingsRepositoryBufferTest]'s temp-folder setup.
 *
 * The migration test seeds an old `pref_punctuation_pause` enum value
 * directly into a fresh DataStore, then constructs a new DataStore over
 * the same file with the production migration registered, and asserts
 * the new key reflects the mapped multiplier and the old key is gone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryPunctuationPauseTest {

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
            // Punctuation-pause tests don't touch AI fields, so a no-op store is fine.
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
    fun `default punctuation pause multiplier is 1x`() = runTest {
        assertEquals(
            PUNCTUATION_PAUSE_DEFAULT_MULTIPLIER,
            repo.settings.first().punctuationPauseMultiplier,
            0.0001f,
        )
    }

    @Test
    fun `setPunctuationPauseMultiplier round-trips through the flow`() = runTest {
        repo.setPunctuationPauseMultiplier(1.5f)
        assertEquals(1.5f, repo.settings.first().punctuationPauseMultiplier, 0.0001f)
    }

    @Test
    fun `setPunctuationPauseMultiplier persists the legacy Long stop precisely`() = runTest {
        // Users coming from the 3-stop selector will dial in 1.75× to recreate the
        // pre-#109 "Long" cadence. Verify it persists exactly (no rounding drift).
        repo.setPunctuationPauseMultiplier(PUNCTUATION_PAUSE_LONG_MULTIPLIER)
        assertEquals(
            PUNCTUATION_PAUSE_LONG_MULTIPLIER,
            repo.settings.first().punctuationPauseMultiplier,
            0.0001f,
        )
    }

    @Test
    fun `setPunctuationPauseMultiplier clamps below zero`() = runTest {
        repo.setPunctuationPauseMultiplier(-1f)
        assertEquals(
            PUNCTUATION_PAUSE_MIN_MULTIPLIER,
            repo.settings.first().punctuationPauseMultiplier,
            0.0001f,
        )
    }

    @Test
    fun `setPunctuationPauseMultiplier clamps above the engine ceiling`() = runTest {
        repo.setPunctuationPauseMultiplier(99f)
        assertEquals(
            PUNCTUATION_PAUSE_MAX_MULTIPLIER,
            repo.settings.first().punctuationPauseMultiplier,
            0.0001f,
        )
    }

    @Test
    fun `setPunctuationPauseMultiplier accepts the mechanical max exactly`() = runTest {
        repo.setPunctuationPauseMultiplier(PUNCTUATION_PAUSE_MAX_MULTIPLIER)
        assertEquals(
            PUNCTUATION_PAUSE_MAX_MULTIPLIER,
            repo.settings.first().punctuationPauseMultiplier,
            0.0001f,
        )
    }

    @Test
    fun `migration maps legacy Off enum to 0x and removes the old key`() = runTest {
        assertMigratesLegacyEnum(legacy = "Off", expected = PUNCTUATION_PAUSE_OFF_MULTIPLIER)
    }

    @Test
    fun `migration maps legacy Normal enum to 1x and removes the old key`() = runTest {
        assertMigratesLegacyEnum(legacy = "Normal", expected = PUNCTUATION_PAUSE_NORMAL_MULTIPLIER)
    }

    @Test
    fun `migration maps legacy Long enum to 175x and removes the old key`() = runTest {
        assertMigratesLegacyEnum(legacy = "Long", expected = PUNCTUATION_PAUSE_LONG_MULTIPLIER)
    }

    @Test
    fun `migration falls back to default for unrecognized legacy values`() = runTest {
        // Defensive path — if a future-self ever shipped a 4th enum stop briefly
        // before this slider PR, downgrading should produce the safe default
        // rather than crash. "Normal"-equivalent is the documented behavior.
        assertMigratesLegacyEnum(legacy = "Verylong", expected = PUNCTUATION_PAUSE_NORMAL_MULTIPLIER)
    }

    /**
     * Seed the legacy enum key into a closed DataStore, then re-open with the
     * production migration registered and assert it converted on first read.
     *
     * The shape mirrors what production sees on app upgrade: a pre-#109 install
     * has only `pref_punctuation_pause` persisted; the new DataStore opens with
     * [PunctuationPauseEnumToMultiplierMigration] in `produceMigrations`, the
     * migration runs once, and the new Float key replaces the old String.
     */
    private suspend fun assertMigratesLegacyEnum(legacy: String, expected: Float) {
        // Use a fresh folder + scope for this isolated migration scenario so we
        // don't conflict with the @Before-built `store` (DataStore enforces a
        // single instance per file).
        val migrationScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val file = File(tempFolder.newFolder(), "storyvox_settings.preferences_pb")
        try {
            // Seed: write the legacy enum key into the file via a no-migration store.
            val seedStore = PreferenceDataStoreFactory.create(
                scope = migrationScope,
                produceFile = { file },
            )
            val oldKey = stringPreferencesKey("pref_punctuation_pause")
            seedStore.edit { it[oldKey] = legacy }
            // Verify the seed wrote.
            assertEquals(legacy, seedStore.data.first()[oldKey])

            // Cancel the seed scope so DataStore releases the file lock before we
            // open a second instance over it.
            migrationScope.cancel()

            // Re-open with the production migration registered.
            val migratedScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
            try {
                val migratedStore = PreferenceDataStoreFactory.create(
                    scope = migratedScope,
                    migrations = listOf(PunctuationPauseEnumToMultiplierMigration),
                    produceFile = { file },
                )
                val prefs = migratedStore.data.first()
                val newKey = floatPreferencesKey("pref_punctuation_pause_multiplier_v2")
                assertEquals(expected, prefs[newKey] ?: Float.NaN, 0.0001f)
                assertNull(
                    "legacy enum key should be removed after migration",
                    prefs[oldKey],
                )
            } finally {
                migratedScope.cancel()
            }
        } finally {
            // Defensive — if seed-scope was already cancelled this is a no-op.
            try {
                migrationScope.cancel()
            } catch (_: IllegalStateException) {
                // Already cancelled.
            }
        }
    }

    // FakeAuth / FakeHydrator / palace fakes live in [SettingsRepositoryTestSupport].
}
