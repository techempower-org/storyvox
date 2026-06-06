package `in`.jphe.storyvox.data

import android.content.SharedPreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import `in`.jphe.storyvox.data.auth.SessionHydrator
import `in`.jphe.storyvox.data.auth.SessionState
import `in`.jphe.storyvox.data.repository.AuthRepository
import `in`.jphe.storyvox.llm.LlmCredentialsStore
import `in`.jphe.storyvox.llm.auth.AnthropicTeamsAuthRepository
import `in`.jphe.storyvox.source.github.auth.GitHubAuthRepository
import `in`.jphe.storyvox.source.github.auth.GitHubSession
import `in`.jphe.storyvox.source.mempalace.config.PalaceConfig
import `in`.jphe.storyvox.source.mempalace.config.PalaceConfigState
import `in`.jphe.storyvox.source.mempalace.net.PalaceDaemonApi
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import okhttp3.OkHttpClient

/**
 * Shared test fixtures for the three [SettingsRepositoryUiImpl] tests
 * (Buffer, Modes, PunctuationPause). Each test spins up its own temp-file
 * DataStore for the settings store; the palace surface is required by
 * the constructor since #79 but isn't exercised by these tests, so we
 * stub it with a real [PalaceConfigImpl] over a separate temp DataStore
 * + an in-memory [SharedPreferences] stub, and wire a [PalaceDaemonApi]
 * at a never-touched config flow.
 *
 * Extracting these here (instead of inlining identical helpers in three
 * places) keeps the surface stable as the [SettingsRepositoryUi] interface
 * grows — when a future contract member needs a new fake, one edit covers
 * every test fixture. Mirrors the rationale behind `Hilt`-shared test
 * doubles in this codebase.
 */
internal class FakeAuth : AuthRepository {
    private val state = MutableStateFlow<SessionState>(SessionState.Anonymous)
    override val sessionState: StateFlow<SessionState> = state.asStateFlow()
    override fun sessionState(sourceId: String): StateFlow<SessionState> = state.asStateFlow()
    override suspend fun captureSession(
        cookieHeader: String,
        userDisplayName: String?,
        userId: String?,
        expiresAt: Long?,
        sourceId: String,
    ) = Unit
    override suspend fun clearSession(sourceId: String) = Unit
    override suspend fun cookieHeader(sourceId: String): String? = null
    override suspend fun verifyOrExpire(sourceId: String): SessionState = SessionState.Anonymous
}

internal class FakeHydrator : SessionHydrator {
    override fun hydrate(cookies: Map<String, String>) = Unit
    override fun clear() = Unit
}

/**
 * In-memory [GitHubAuthRepository] for the settings tests. The buffer
 * / modes / pause tests don't exercise GitHub auth but the constructor
 * needs *some* binding since #91. Mirrors [FakeAuth]'s shape — start
 * Anonymous, allow capture/clear to flip the state flow synchronously.
 */
internal class FakeGitHubAuth : GitHubAuthRepository {
    private val state = MutableStateFlow<GitHubSession>(GitHubSession.Anonymous)
    override val sessionState: StateFlow<GitHubSession> = state.asStateFlow()
    override suspend fun captureSession(token: String, login: String?, scopes: String) {
        state.value = GitHubSession.Authenticated(
            token = token, login = login, scopes = scopes, grantedAt = 0L,
        )
    }
    override suspend fun clearSession() { state.value = GitHubSession.Anonymous }
    override fun markExpired() { state.value = GitHubSession.Expired }
}

/**
 * In-memory [AnthropicTeamsAuthRepository] for the settings tests (#181).
 * Wraps the existing [LlmCredentialsStore.forTesting] no-op store —
 * captureSession / clearSession just flip the in-memory state flow.
 */
internal fun fakeTeamsAuth(
    store: LlmCredentialsStore = LlmCredentialsStore.forTesting(),
): AnthropicTeamsAuthRepository = AnthropicTeamsAuthRepository(store)

/**
 * Real [PalaceConfigImpl] backed by a temp DataStore + an in-memory
 * SharedPreferences stub. The settings-tests don't exercise palace
 * state, but the repo's `settings` flow joins on it via combine(), so
 * we need a flow that emits at least once.
 */
internal fun makeFakePalaceConfig(
    dir: File,
    scope: CoroutineScope,
): PalaceConfigImpl {
    val palaceStore = PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { File(dir, "storyvox_palace.preferences_pb") },
    )
    return PalaceConfigImpl(palaceStore, FakeSecrets())
}

/** Real [RssConfigImpl] over a temp DataStore (#236). Same shape
 *  as [makeFakePalaceConfig] — the settings tests don't exercise RSS
 *  state but the repo signature requires the dependency. */
internal fun makeFakeRssConfig(
    dir: File,
    scope: CoroutineScope,
): RssConfigImpl {
    val rssStore = PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { File(dir, "storyvox_rss.preferences_pb") },
    )
    return RssConfigImpl(rssStore)
}

/** Real [EpubConfigImpl] over a temp DataStore (#235). Same pattern
 *  as [makeFakeRssConfig] — the settings tests don't exercise SAF or
 *  EPUB content, just need the dependency to satisfy the constructor.
 *  Robolectric-supplied application context is used as the SAF context;
 *  no SAF calls are exercised in these tests, so the empty
 *  ApplicationProvider context is sufficient. */
internal fun makeFakeEpubConfig(
    dir: File,
    scope: CoroutineScope,
): EpubConfigImpl {
    val epubStore = PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { File(dir, "storyvox_epub.preferences_pb") },
    )
    return EpubConfigImpl.forTesting(epubStore)
}

/** Real [PdfConfigImpl] over a temp DataStore (#996). Mirrors
 *  [makeFakeEpubConfig] — the settings tests don't exercise SAF or PDF
 *  enumeration, just need the dependency to satisfy the constructor.
 *  [PdfConfigImpl.forTesting] supplies a no-op enumerator, so no SAF
 *  context is touched. */
internal fun makeFakePdfConfig(
    dir: File,
    scope: CoroutineScope,
): PdfConfigImpl {
    val pdfStore = PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { File(dir, "storyvox_pdf.preferences_pb") },
    )
    return PdfConfigImpl.forTesting(pdfStore)
}

/** Real [OutlineConfigImpl] over a temp DataStore + fake secrets
 *  (#245). Settings tests don't exercise Outline state — the
 *  signature requires the dep, this satisfies it. */
internal fun makeFakeOutlineConfig(
    dir: File,
    scope: CoroutineScope,
): OutlineConfigImpl {
    val outlineStore = PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { File(dir, "storyvox_outline.preferences_pb") },
    )
    return OutlineConfigImpl(outlineStore, FakeSecrets())
}

/** Real [WikipediaConfigImpl] over a temp DataStore (#377). Settings
 *  tests don't exercise Wikipedia state — the signature requires
 *  the dep, this satisfies it. */
internal fun makeFakeWikipediaConfig(
    dir: File,
    scope: CoroutineScope,
): WikipediaConfigImpl {
    val wikipediaStore = PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { File(dir, "storyvox_wikipedia.preferences_pb") },
    )
    return WikipediaConfigImpl(wikipediaStore)
}

/** Real [NotionConfigImpl] over a temp DataStore + fake secrets (#233).
 *  Settings tests don't exercise Notion state — the signature requires
 *  the dep, this satisfies it. */
internal fun makeFakeNotionConfig(
    dir: File,
    scope: CoroutineScope,
): NotionConfigImpl {
    val notionStore = PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { File(dir, "storyvox_notion.preferences_pb") },
    )
    return NotionConfigImpl(notionStore, FakeSecrets())
}

/** Real [DiscordConfigImpl] over a temp DataStore + fake secrets (#403).
 *  Settings tests don't exercise Discord state — the signature requires
 *  the dep, this satisfies it. */
internal fun makeFakeDiscordConfig(
    dir: File,
    scope: CoroutineScope,
): DiscordConfigImpl {
    val discordStore = PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { File(dir, "storyvox_discord.preferences_pb") },
    )
    return DiscordConfigImpl(discordStore, FakeSecrets())
}

/**
 * Stub [DiscordGuildDirectory] for settings tests. Tests don't
 * exercise the bot-token guild lookup; the repo signature requires
 * the dep so the stub returns an empty list (matches "no token" /
 * "fetch failed" mode in production).
 */
internal fun makeFakeDiscordGuildDirectory(): `in`.jphe.storyvox.source.discord.DiscordGuildDirectory =
    `in`.jphe.storyvox.source.discord.DiscordGuildDirectory.Empty

/** Real [TelegramConfigImpl] over a temp DataStore + fake secrets (#462).
 *  Settings tests don't exercise Telegram state — the signature requires
 *  the dep, this satisfies it. */
internal fun makeFakeTelegramConfig(
    dir: File,
    scope: CoroutineScope,
): TelegramConfigImpl {
    val telegramStore = PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { File(dir, "storyvox_telegram.preferences_pb") },
    )
    return TelegramConfigImpl(telegramStore, FakeSecrets())
}

/**
 * Stub [TelegramChannelDirectory] for settings tests. Tests don't
 * exercise the Bot API probe; the repo signature requires the dep
 * so the stub returns nulls/empties (matches "no token" / "fetch
 * failed" mode in production).
 */
internal fun makeFakeTelegramChannelDirectory(): `in`.jphe.storyvox.source.telegram.TelegramChannelDirectory =
    `in`.jphe.storyvox.source.telegram.TelegramChannelDirectory.Empty

/**
 * Real [PalaceDaemonApi] over an OkHttpClient + a fake config that emits
 * an empty [PalaceConfigState]. No HTTP is exercised by the settings
 * tests; the dep is there because the repo signature requires it.
 */
internal fun makeFakePalaceApi(): PalaceDaemonApi =
    PalaceDaemonApi(
        httpClient = OkHttpClient(),
        config = object : PalaceConfig {
            override val state: Flow<PalaceConfigState> = flowOf(PalaceConfigState("", ""))
            override suspend fun current() = PalaceConfigState("", "")
        },
    )

/**
 * In-memory [AzureCredentials] for the settings tests (#182). Settings
 * tests don't exercise BYOK plumbing but the repo signature requires
 * the binding since the Azure PR-3 ship; this returns a no-op stub.
 * `forTesting()` already returns an instance backed by `NullSharedPreferences`,
 * so we just wrap it.
 */
internal fun makeFakeAzureCredentials(): `in`.jphe.storyvox.source.azure.AzureCredentials =
    `in`.jphe.storyvox.source.azure.AzureCredentials.forTesting()

/**
 * Stub [AzureSpeechClient] for the settings tests. Real synthesis is
 * never called; the test-connection probe path isn't exercised here
 * either. We hand back a client whose `voicesList()` throws — any test
 * that needs the probe will need its own subclass override.
 */
internal fun makeFakeAzureClient(): `in`.jphe.storyvox.source.azure.AzureSpeechClient {
    val creds = makeFakeAzureCredentials()
    return object : `in`.jphe.storyvox.source.azure.AzureSpeechClient(
        http = OkHttpClient(),
        credentials = creds,
    ) {
        override fun synthesize(ssml: String): ByteArray =
            throw `in`.jphe.storyvox.source.azure.AzureError.AuthFailed(
                "stub — settings test should not exercise synthesis",
            )
        override fun voicesList(): Int =
            throw `in`.jphe.storyvox.source.azure.AzureError.AuthFailed(
                "stub — settings test should not exercise voices/list",
            )
    }
}

/**
 * Stub [AzureVoiceRoster] for settings tests. The settings setters
 * call `refreshAsync()` to invalidate the live roster on key/region
 * changes; tests don't observe the roster state, so the stub is a
 * no-op around the same fake client+credentials the other Azure
 * helpers use.
 */
internal fun makeFakeAzureRoster(): `in`.jphe.storyvox.source.azure.AzureVoiceRoster =
    `in`.jphe.storyvox.source.azure.AzureVoiceRoster(
        client = makeFakeAzureClient(),
        credentials = makeFakeAzureCredentials(),
    )

/**
 * Bundle the three PR-G cache deps the settings tests need to satisfy
 * the [SettingsRepositoryUiImpl] constructor. One temp folder per
 * bundle keeps tests isolated; tests that don't exercise the cache
 * surface (the majority) can ignore the contents and let the bundle
 * carry the wiring.
 *
 * The helpers use the real production classes via PR-G's secondary
 * constructors (PcmCache(File, ...) / PcmCacheConfig(DataStore)) so
 * the cache calls in [SettingsRepositoryUiImpl.setCacheQuotaBytes] and
 * [SettingsRepositoryUiImpl.clearCache] go through the real code path
 * — just against a temp folder instead of `Context.cacheDir`.
 */
internal data class FakeCacheBundle(
    val pcmCacheConfig: `in`.jphe.storyvox.playback.cache.PcmCacheConfig,
    val pcmCache: `in`.jphe.storyvox.playback.cache.PcmCache,
    val cacheStats: `in`.jphe.storyvox.playback.cache.CacheStatsRepository,
)

internal fun makeFakeCacheBundle(
    storeDir: File,
    scope: CoroutineScope,
): FakeCacheBundle {
    val cfgFile = File(storeDir, "pcm_cache_config.preferences_pb")
    val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { cfgFile })
    val config = `in`.jphe.storyvox.playback.cache.PcmCacheConfig(store)
    val rootDir = File(storeDir, "pcm-cache").apply { mkdirs() }
    val cache = `in`.jphe.storyvox.playback.cache.PcmCache(rootDir, config)
    val stats = `in`.jphe.storyvox.playback.cache.CacheStatsRepository(cache, config)
    return FakeCacheBundle(config, cache, stats)
}

/**
 * Minimal SharedPreferences stub — only `getString` / `edit` are reached
 * by the palace code paths the test fixtures touch.
 */
internal class FakeSecrets : SharedPreferences {
    private val map = mutableMapOf<String, Any?>()
    override fun getAll(): MutableMap<String, *> = map
    override fun getString(key: String, defValue: String?): String? =
        map[key] as? String ?: defValue
    override fun getStringSet(
        key: String,
        defValues: MutableSet<String>?,
    ): MutableSet<String>? = defValues
    override fun getInt(key: String, defValue: Int): Int = (map[key] as? Int) ?: defValue
    override fun getLong(key: String, defValue: Long): Long = (map[key] as? Long) ?: defValue
    override fun getFloat(key: String, defValue: Float): Float =
        (map[key] as? Float) ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        (map[key] as? Boolean) ?: defValue
    override fun contains(key: String): Boolean = key in map
    override fun edit(): SharedPreferences.Editor =
        object : SharedPreferences.Editor {
            override fun putString(key: String, value: String?) = apply { map[key] = value }
            override fun putStringSet(
                key: String,
                values: MutableSet<String>?,
            ) = apply { map[key] = values }
            override fun putInt(key: String, value: Int) = apply { map[key] = value }
            override fun putLong(key: String, value: Long) = apply { map[key] = value }
            override fun putFloat(key: String, value: Float) = apply { map[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { map[key] = value }
            override fun remove(key: String) = apply { map.remove(key) }
            override fun clear() = apply { map.clear() }
            override fun commit(): Boolean = true
            override fun apply() = Unit
        }
    override fun registerOnSharedPreferenceChangeListener(
        l: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(
        l: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit
}
