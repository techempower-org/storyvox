package `in`.jphe.storyvox.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import `in`.jphe.storyvox.playback.KOKORO_PHONEMIZER_LANGS
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
 * Issues #197 + #198 — persistence-layer tests for the per-voice
 * lexicon path map and the per-voice Kokoro phonemizer language
 * map. Covers four contract surfaces required by the spec:
 *
 *  1. Per-voice key persistence — values land under unique map
 *     entries keyed by voiceId; cleared with null.
 *  2. Active-voice resolution — [UiSettings.effectiveLexicon] /
 *     [UiSettings.effectivePhonemizerLang] follow the active voice
 *     across switches, with a clean fallback to empty when the
 *     active voice has no override.
 *  3. setDefaultVoice clears the override surface when voice =
 *     null (the bridge re-apply path in the impl writes empty
 *     fields, which we observe via the effective getters).
 *  4. Kokoro language-code shape — every entry in
 *     [KOKORO_PHONEMIZER_LANGS] persists round-trip, and the
 *     UI's full set is wired through.
 *
 * The bridge write-through path (Voxsherpa static field updates)
 * is exercised by the companion test in
 * `:core-playback`'s `VoiceEngineQualityBridgeTest`. We deliberately
 * keep this app-side test off the VoxSherpa class surface — the
 * core-playback module ships VoxSherpa as `implementation` so the
 * Java classes aren't visible from `:app`'s test compile path. The
 * effective-getter pair below is the same observable contract the
 * production code uses (via [StoryvoxApp.applyPerVoiceEngineKnobsFromSettings]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryVoiceLexiconLangTest {

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
    fun `setVoiceLexicon persists per-voice override`() = runTest {
        repo.setVoiceLexicon("piper_amy_x_low", "/lex/amy.lexicon")
        repo.setVoiceLexicon("kokoro_af_bella", "/lex/bella.lexicon")

        val map = repo.settings.first().voiceLexiconOverrides
        assertEquals("/lex/amy.lexicon", map["piper_amy_x_low"])
        assertEquals("/lex/bella.lexicon", map["kokoro_af_bella"])
        // Other voices in the catalog must NOT pick up either path.
        assertNull(map["kokoro_af_sky"])
    }

    @Test
    fun `setVoiceLexicon with null clears the override`() = runTest {
        repo.setVoiceLexicon("piper_amy_x_low", "/lex/amy.lexicon")
        assertEquals(1, repo.settings.first().voiceLexiconOverrides.size)

        repo.setVoiceLexicon("piper_amy_x_low", null)
        assertTrue(repo.settings.first().voiceLexiconOverrides.isEmpty())
    }

    @Test
    fun `setVoiceLexicon with blank string clears the override`() = runTest {
        repo.setVoiceLexicon("piper_amy_x_low", "/lex/amy.lexicon")
        repo.setVoiceLexicon("piper_amy_x_low", "")
        assertTrue(repo.settings.first().voiceLexiconOverrides.isEmpty())

        repo.setVoiceLexicon("piper_amy_x_low", "/lex/amy.lexicon")
        repo.setVoiceLexicon("piper_amy_x_low", "   ")
        assertTrue(repo.settings.first().voiceLexiconOverrides.isEmpty())
    }

    @Test
    fun `setVoicePhonemizerLang accepts every documented Kokoro language`() = runTest {
        // Every code in the public KOKORO_PHONEMIZER_LANGS list must
        // round-trip cleanly through the DataStore. This is the
        // validation surface the spec asks for — the Settings UI
        // dropdown reads from the same list, so a regression that
        // drops a code makes both the chip and the persistence side
        // fall over together.
        for (code in KOKORO_PHONEMIZER_LANGS) {
            val voiceId = "kokoro_af_$code"  // synthetic id per-test
            repo.setVoicePhonemizerLang(voiceId, code)
            val persisted = repo.settings.first().voicePhonemizerLangOverrides[voiceId]
            assertEquals("code $code must persist verbatim", code, persisted)
        }
    }

    @Test
    fun `setVoicePhonemizerLang null clears the override`() = runTest {
        repo.setVoicePhonemizerLang("kokoro_af_bella", "es")
        assertEquals(1, repo.settings.first().voicePhonemizerLangOverrides.size)

        repo.setVoicePhonemizerLang("kokoro_af_bella", null)
        assertTrue(repo.settings.first().voicePhonemizerLangOverrides.isEmpty())
    }

    @Test
    fun `effectiveLexicon and effectivePhonemizerLang follow active voice`() = runTest {
        // Two voices, two distinct overrides. Switching the active
        // voice via setDefaultVoice must point the effective getters
        // at the *new* voice's stored values. This is the contract
        // the bridge re-apply path in setDefaultVoice depends on —
        // if effectiveLexicon doesn't track, the bridge writes the
        // wrong values on voice switch.
        repo.setVoiceLexicon("piper_amy_x_low", "/lex/amy.lexicon")
        repo.setVoicePhonemizerLang("kokoro_af_bella", "fr")

        repo.setDefaultVoice("piper_amy_x_low")
        var settings = repo.settings.first()
        assertEquals("/lex/amy.lexicon", settings.effectiveLexicon)
        // Piper voice has no lang override → effective is empty.
        assertEquals("", settings.effectivePhonemizerLang)

        repo.setDefaultVoice("kokoro_af_bella")
        settings = repo.settings.first()
        // Bella has no lexicon override → effective is empty.
        assertEquals("", settings.effectiveLexicon)
        assertEquals("fr", settings.effectivePhonemizerLang)
    }

    @Test
    fun `effectiveLexicon is empty when no active voice`() = runTest {
        repo.setVoiceLexicon("piper_amy_x_low", "/lex/amy.lexicon")
        // Default voice never set → defaultVoiceId is null →
        // effectiveLexicon falls back to "".
        val settings = repo.settings.first()
        assertNull(settings.defaultVoiceId)
        assertEquals("", settings.effectiveLexicon)
        assertEquals("", settings.effectivePhonemizerLang)
    }

    @Test
    fun `setDefaultVoice clears default and effective getters fall to empty`() = runTest {
        repo.setVoiceLexicon("piper_amy_x_low", "/lex/amy.lexicon")
        repo.setDefaultVoice("piper_amy_x_low")
        assertEquals("/lex/amy.lexicon", repo.settings.first().effectiveLexicon)

        repo.setDefaultVoice(null)
        val settings = repo.settings.first()
        assertNull(settings.defaultVoiceId)
        assertEquals("", settings.effectiveLexicon)
        assertEquals("", settings.effectivePhonemizerLang)
        // The per-voice map entry survives (clearing the active voice
        // doesn't wipe per-voice settings) — when the user re-activates
        // the same voice, the override is still there.
        assertEquals(
            "/lex/amy.lexicon",
            settings.voiceLexiconOverrides["piper_amy_x_low"],
        )
    }

    @Test
    fun `independent maps - setting lexicon does not touch lang map`() = runTest {
        repo.setVoiceLexicon("kokoro_af_bella", "/lex/bella.lexicon")
        val settings = repo.settings.first()
        // Per-voice phonemizer-lang map must remain empty when only
        // the lexicon side was touched.
        assertTrue(settings.voicePhonemizerLangOverrides.isEmpty())
        assertEquals("/lex/bella.lexicon", settings.voiceLexiconOverrides["kokoro_af_bella"])
    }

    @Test
    fun `multiple voices coexist in the same map`() = runTest {
        // Three Piper voices, three Kokoro voices — all share one
        // flat-string-encoded map without colliding. Catches a future
        // bug where the codec might shadow earlier entries on rewrite.
        repo.setVoiceLexicon("piper_amy_x_low", "/lex/a.lex")
        repo.setVoiceLexicon("piper_lessac_medium", "/lex/b.lex")
        repo.setVoiceLexicon("piper_hfc_female_medium", "/lex/c.lex")
        repo.setVoiceLexicon("kokoro_af_bella", "/lex/d.lex")
        repo.setVoiceLexicon("kokoro_am_adam", "/lex/e.lex")
        repo.setVoiceLexicon("kokoro_bf_emma", "/lex/f.lex")

        val map = repo.settings.first().voiceLexiconOverrides
        assertEquals(6, map.size)
        assertEquals("/lex/a.lex", map["piper_amy_x_low"])
        assertEquals("/lex/d.lex", map["kokoro_af_bella"])
        assertEquals("/lex/f.lex", map["kokoro_bf_emma"])
    }

    @Test
    fun `KOKORO_PHONEMIZER_LANGS surface is non-empty and unique`() {
        // Belt-and-suspenders guard: the UI dropdown renders one chip
        // per entry, and a duplicate would render two chips that
        // toggle in lockstep — confusing.
        assertFalse("must not be empty", KOKORO_PHONEMIZER_LANGS.isEmpty())
        assertEquals(
            "must contain no duplicates",
            KOKORO_PHONEMIZER_LANGS.size,
            KOKORO_PHONEMIZER_LANGS.toSet().size,
        )
        // Sanity check the documented baseline set.
        assertTrue("en must be present", "en" in KOKORO_PHONEMIZER_LANGS)
        assertTrue("ja must be present", "ja" in KOKORO_PHONEMIZER_LANGS)
    }
}
