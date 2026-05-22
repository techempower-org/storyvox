package `in`.jphe.storyvox.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Real-DataStore tests for the `SettingsSnapshotSource` impl on
 * [SettingsRepositoryUiImpl] — the bridge from `:core-sync`'s
 * `SettingsSyncer` to the live DataStore.
 *
 * These tests exercise:
 *  - `snapshot()` returns ONLY allowlisted keys.
 *  - `apply()` type-coerces correctly per [SettingsRepositoryUiImpl.SYNC_KEY_TYPES].
 *  - `apply()` ignores unknown keys (forward-compat).
 *  - `apply()` ignores ill-typed values (drops, doesn't crash).
 *  - `lastLocalWriteAt()` round-trips a stamp.
 *  - Per-backend keys round-trip via the right `*ConfigImpl`.
 *  - The allowlist <-> type-table invariant holds (every allowlist
 *    entry has a type entry; no type entry without a matching
 *    allowlist entry).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositorySyncSnapshotTest {

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

    @Test fun `snapshot returns only allowlisted keys`() = runTest {
        // Write some allowlisted keys + some explicitly-excluded ones.
        store.edit { prefs ->
            prefs[stringPreferencesKey("pref_theme_override")] = "DARK"
            prefs[floatPreferencesKey("pref_default_speed")] = 1.25f
            // Explicitly excluded — device-local flags.
            prefs[booleanPreferencesKey("pref_signed_in")] = true
            prefs[booleanPreferencesKey("pref_v0500_milestone_seen")] = true
            // Random non-allowlist key.
            prefs[stringPreferencesKey("pref_some_random_thing")] = "should not sync"
        }
        val snap = repo.snapshot()
        assertEquals("DARK", snap["settings.pref_theme_override"])
        assertEquals("1.25", snap["settings.pref_default_speed"])
        assertNull(
            "device-local pref_signed_in must not be in the snapshot",
            snap["settings.pref_signed_in"],
        )
        assertNull(
            "device-local pref_v0500_milestone_seen must not be in the snapshot",
            snap["settings.pref_v0500_milestone_seen"],
        )
        assertNull(
            "non-allowlist random key must not be in the snapshot",
            snap["settings.pref_some_random_thing"],
        )
    }

    @Test fun `apply writes a boolean key with correct type`() = runTest {
        repo.apply(mapOf("settings.pref_warmup_wait_v1" to "false"))
        val v = store.data.first()[booleanPreferencesKey("pref_warmup_wait_v1")]
        assertEquals(false, v)
    }

    @Test fun `apply writes a float key with correct type`() = runTest {
        repo.apply(mapOf("settings.pref_default_speed" to "1.45"))
        val v = store.data.first()[floatPreferencesKey("pref_default_speed")]
        assertEquals(1.45f, v)
    }

    @Test fun `apply writes an int key with correct type`() = runTest {
        repo.apply(mapOf("settings.pref_poll_interval_hours" to "12"))
        val v = store.data.first()[intPreferencesKey("pref_poll_interval_hours")]
        assertEquals(12, v)
    }

    @Test fun `apply writes a string key with correct type`() = runTest {
        repo.apply(mapOf("settings.pref_theme_override" to "LIGHT"))
        val v = store.data.first()[stringPreferencesKey("pref_theme_override")]
        assertEquals("LIGHT", v)
    }

    @Test fun `apply tolerates an unknown key (forward-compat)`() = runTest {
        // Don't crash on a key this build doesn't recognise.
        repo.apply(mapOf(
            "settings.pref_v999_future_unknown_key" to "future-value",
            "settings.pref_theme_override" to "DARK", // known key, alongside
        ))
        // Known key landed.
        assertEquals(
            "DARK",
            store.data.first()[stringPreferencesKey("pref_theme_override")],
        )
        // Unknown key did NOT land (not in the type table).
        assertNull(
            store.data.first()[stringPreferencesKey("pref_v999_future_unknown_key")],
        )
    }

    @Test fun `apply tolerates an ill-typed value`() = runTest {
        // A future build (or a corrupted blob) might push a string
        // value to an int-typed key. The applier should drop the
        // entry, not crash, and leave the existing local value alone.
        store.edit { it[intPreferencesKey("pref_poll_interval_hours")] = 6 }
        repo.apply(mapOf("settings.pref_poll_interval_hours" to "not-an-int"))
        val v = store.data.first()[intPreferencesKey("pref_poll_interval_hours")]
        assertEquals("ill-typed value must not corrupt local", 6, v)
    }

    @Test fun `lastLocalWriteAt round-trips a stamp`() = runTest {
        assertEquals(0L, repo.lastLocalWriteAt())
        repo.stampLocalWrite(at = 1_234_567L)
        assertEquals(1_234_567L, repo.lastLocalWriteAt())
    }

    @Test fun `apply writes wikipedia language via the wikipedia config impl`() = runTest {
        repo.apply(mapOf("wikipedia.pref_wikipedia_language_code" to "es"))
        // Read it back through the WikipediaConfig contract.
        val state = repo.snapshot()
        assertEquals("es", state["wikipedia.pref_wikipedia_language_code"])
    }

    @Test fun `apply writes outline host via the outline config impl`() = runTest {
        repo.apply(mapOf("outline.pref_outline_host" to "wiki.example.com"))
        val state = repo.snapshot()
        assertEquals("wiki.example.com", state["outline.pref_outline_host"])
    }

    @Test fun `apply writes notion database id via the notion config impl`() = runTest {
        repo.apply(mapOf("notion.pref_notion_database_id" to "abcd-1234"))
        val state = repo.snapshot()
        assertEquals("abcd-1234", state["notion.pref_notion_database_id"])
    }

    @Test fun `SYNC_ALLOWLIST and SYNC_KEY_TYPES are in lockstep`() {
        // Invariant: every entry in SYNC_ALLOWLIST must have a
        // corresponding entry in SYNC_KEY_TYPES, and vice versa. A
        // mismatch would mean a snapshot key gets pushed but
        // never written back on pull (or vice versa).
        val allowlistKeys = SettingsRepositoryUiImpl.SYNC_ALLOWLIST
        val typeTableKeys = SettingsRepositoryUiImpl.SYNC_KEY_TYPES.keys
        val onlyInAllowlist = allowlistKeys - typeTableKeys
        val onlyInTypeTable = typeTableKeys - allowlistKeys
        assertTrue(
            "keys in SYNC_ALLOWLIST but missing a SYNC_KEY_TYPES entry: $onlyInAllowlist",
            onlyInAllowlist.isEmpty(),
        )
        assertTrue(
            "keys in SYNC_KEY_TYPES but missing from SYNC_ALLOWLIST: $onlyInTypeTable",
            onlyInTypeTable.isEmpty(),
        )
    }

    @Test fun `snapshot is stable across re-reads for unchanged state`() = runTest {
        // Stable byte-output is load-bearing for "no-op pushes don't
        // thrash the sync server."
        store.edit { prefs ->
            prefs[stringPreferencesKey("pref_theme_override")] = "DARK"
            prefs[floatPreferencesKey("pref_default_speed")] = 1.0f
            prefs[booleanPreferencesKey("pref_warmup_wait_v1")] = false
        }
        val first = repo.snapshot()
        val second = repo.snapshot()
        assertEquals(first, second)
    }

    @Test fun `source favorites key IS in SYNC_ALLOWLIST (same family as plugins-enabled)`() {
        // v0.5.76 — Browse-carousel favorites. The user's set of
        // starred sources is cross-device intent ("AO3 is my main
        // source"), the same kind of preference that drives
        // pref_source_plugins_enabled_v1 into the allowlist. If a
        // future refactor accidentally drops it, the favorites star
        // becomes per-device — easy to miss on dogfood, painful to
        // notice on a sign-in to a new device.
        assertTrue(
            "pref_source_favorites_v1 must sync — cross-device intent",
            "pref_source_favorites_v1" in SettingsRepositoryUiImpl.SYNC_ALLOWLIST,
        )
        assertEquals(
            "pref_source_favorites_v1 is JSON-encoded, must use STRING codec",
            SettingsRepositoryUiImpl.SyncedType.STRING,
            SettingsRepositoryUiImpl.SYNC_KEY_TYPES["pref_source_favorites_v1"],
        )
    }

    @Test fun `pronunciation dict key is NOT in SYNC_ALLOWLIST (handled by its own syncer)`() {
        assertFalse(
            "pronunciation dict must NOT be in the settings allowlist — it has its own syncer",
            "pref_pronunciation_dict_v1" in SettingsRepositoryUiImpl.SYNC_ALLOWLIST,
        )
    }

    @Test fun `device-local flags are NOT in SYNC_ALLOWLIST`() {
        // These would all degrade UX if synced (see design-decisions.md).
        val deviceLocal = setOf(
            "pref_signed_in",
            "pref_last_was_playing",
            "pref_v0500_milestone_seen",
            "pref_v0500_confetti_shown",
        )
        for (key in deviceLocal) {
            assertFalse(
                "$key is a device-local flag and must NOT sync",
                key in SettingsRepositoryUiImpl.SYNC_ALLOWLIST,
            )
        }
    }
}
