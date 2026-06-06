package `in`.jphe.storyvox.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import `in`.jphe.storyvox.data.repository.pronunciation.MatchType
import `in`.jphe.storyvox.data.repository.pronunciation.PronunciationDict
import `in`.jphe.storyvox.data.repository.pronunciation.PronunciationEntry
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Real-DataStore tests for the issue #135 pronunciation dictionary
 * persistence. Mirrors [SettingsRepositoryPunctuationPauseTest]'s
 * temp-folder setup. Asserts:
 *  - Default state is [PronunciationDict.EMPTY].
 *  - `add`/`update`/`delete`/`replaceAll` round-trip through the
 *    DataStore-backed [`PronunciationDictRepository.dict`] flow.
 *  - The PCM-cache invalidation hash (`PronunciationDict.contentHash`)
 *    shifts whenever the persisted entries change, so a dictionary
 *    edit self-evicts the affected on-disk renders.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryPronunciationDictTest {

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
            // GitHubAuth fake (#91) — pronunciation-dict tests don't touch
            // GitHub state; mirrors BufferTest / ModesTest / PitchTest pattern.
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
    fun `default dict is empty`() = runTest {
        assertEquals(PronunciationDict.EMPTY, repo.dict.first())
    }

    @Test
    fun `add appends entry and round-trips through the flow`() = runTest {
        repo.add(PronunciationEntry(pattern = "Astaria", replacement = "uh-STAY-ree-uh"))
        val dict = repo.dict.first()
        assertEquals(1, dict.entries.size)
        assertEquals("Astaria", dict.entries[0].pattern)
        assertEquals("uh-STAY-ree-uh", dict.entries[0].replacement)
        assertEquals(MatchType.WORD, dict.entries[0].matchType)
    }

    @Test
    fun `update replaces entry at index`() = runTest {
        repo.add(PronunciationEntry(pattern = "A", replacement = "a"))
        repo.add(PronunciationEntry(pattern = "B", replacement = "b"))

        repo.update(0, PronunciationEntry(pattern = "AA", replacement = "aa"))

        val dict = repo.dict.first()
        assertEquals(2, dict.entries.size)
        assertEquals("AA", dict.entries[0].pattern)
        assertEquals("aa", dict.entries[0].replacement)
        assertEquals("B", dict.entries[1].pattern)
    }

    @Test
    fun `update with out-of-bounds index is a silent no-op`() = runTest {
        repo.add(PronunciationEntry(pattern = "A", replacement = "a"))
        repo.update(99, PronunciationEntry(pattern = "X", replacement = "x"))
        val dict = repo.dict.first()
        assertEquals(1, dict.entries.size)
        assertEquals("A", dict.entries[0].pattern)
    }

    @Test
    fun `delete removes entry at index`() = runTest {
        repo.add(PronunciationEntry(pattern = "A", replacement = "a"))
        repo.add(PronunciationEntry(pattern = "B", replacement = "b"))
        repo.add(PronunciationEntry(pattern = "C", replacement = "c"))

        repo.delete(1)

        val dict = repo.dict.first()
        assertEquals(2, dict.entries.size)
        assertEquals("A", dict.entries[0].pattern)
        assertEquals("C", dict.entries[1].pattern)
    }

    @Test
    fun `delete with out-of-bounds index is a silent no-op`() = runTest {
        repo.add(PronunciationEntry(pattern = "A", replacement = "a"))
        repo.delete(99)
        val dict = repo.dict.first()
        assertEquals(1, dict.entries.size)
    }

    @Test
    fun `replaceAll bulk-overwrites the dictionary`() = runTest {
        repo.add(PronunciationEntry(pattern = "old", replacement = "old"))
        repo.replaceAll(
            PronunciationDict(
                entries = listOf(
                    PronunciationEntry(pattern = "X", replacement = "x"),
                    PronunciationEntry(pattern = "Y", replacement = "y", matchType = MatchType.REGEX),
                ),
            )
        )
        val dict = repo.dict.first()
        assertEquals(2, dict.entries.size)
        assertEquals("X", dict.entries[0].pattern)
        assertEquals("Y", dict.entries[1].pattern)
        assertEquals(MatchType.REGEX, dict.entries[1].matchType)
    }

    @Test
    fun `current returns the latest persisted dict`() = runTest {
        repo.add(PronunciationEntry(pattern = "A", replacement = "a"))
        val current = repo.current()
        assertEquals(1, current.entries.size)
    }

    @Test
    fun `contentHash for cache invalidation shifts on every mutation`() = runTest {
        val empty = repo.dict.first().contentHash

        repo.add(PronunciationEntry(pattern = "Astaria", replacement = "ast"))
        val afterAdd = repo.dict.first().contentHash

        repo.update(0, PronunciationEntry(pattern = "Astaria", replacement = "ast2"))
        val afterUpdate = repo.dict.first().contentHash

        repo.delete(0)
        val afterDelete = repo.dict.first().contentHash

        // Each mutation produces a distinct hash. afterDelete returns to
        // empty (semantically — the user removed the only entry) and the
        // hash is the same as the initial empty state.
        assertEquals(empty, afterDelete)
        // The mid-states must differ from each other and from empty.
        assert(empty != afterAdd) { "add() must shift contentHash" }
        assert(afterAdd != afterUpdate) { "update() must shift contentHash" }
        assert(afterUpdate != afterDelete) { "delete() must shift contentHash" }
    }
}
