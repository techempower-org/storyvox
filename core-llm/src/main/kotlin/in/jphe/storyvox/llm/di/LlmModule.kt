package `in`.jphe.storyvox.llm.di

import `in`.jphe.storyvox.llm.LlmConfig
import `in`.jphe.storyvox.llm.LlmConfigProvider
import `in`.jphe.storyvox.llm.auth.AnthropicTeamsAuthApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Qualifier for the OkHttpClient used by all LLM providers. Distinct
 * from `@RoyalRoadHttp` and `@GitHubHttp` qualifiers so the LLM
 * client can have its own timeout + interceptor configuration —
 * streaming responses can be long, and we want a body redaction
 * interceptor that strips `x-api-key` / `Authorization` from logs.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LlmHttp

/**
 * Hilt graph for `:core-llm`. Provides the OkHttpClient + Json that
 * the three LlmProvider implementations consume. Provider classes
 * themselves are `@Singleton @Inject constructor` — Hilt picks them
 * up automatically.
 *
 * The [LlmConfig] flow that providers read from is bound by
 * `:app`'s `SettingsRepositoryUiImpl` once the LLM-config persistence
 * lands there — until then the binding is a `Flow<LlmConfig>` of
 * the default config (effectively "AI disabled"), which is fine for
 * the unit tests below and surfaces NotConfigured at runtime if the
 * Settings layer isn't wiring its config.
 */
@Module
@InstallIn(SingletonComponent::class)
object LlmModule {

    @Provides
    @Singleton
    @LlmHttp
    fun provideHttp(): OkHttpClient = OkHttpClient.Builder()
        // Streaming responses can be long — Claude Sonnet on a
        // 1000-token reply takes ~10s, Ollama on a slow CPU can be
        // 60s+. Read timeout is generous; connect timeout stays
        // tight so a wrong Ollama URL fails fast.
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        coerceInputValues = true
    }

    /** Bridge the [LlmConfigProvider] (bound by `:app`) to the
     *  `Flow<LlmConfig>` that provider classes inject directly. */
    @Provides
    @Singleton
    fun provideConfigFlow(provider: LlmConfigProvider): Flow<LlmConfig> =
        provider.config

    /**
     * Anthropic Teams OAuth client (#181). Reuses the [LlmHttp] vanilla
     * OkHttpClient — that one has no Bearer interceptor, which is
     * exactly what the token endpoint needs (it takes the bearer in
     * the JSON body, not as an Authorization header).
     */
    @Provides
    @Singleton
    fun provideAnthropicTeamsAuthApi(@LlmHttp http: OkHttpClient): AnthropicTeamsAuthApi =
        AnthropicTeamsAuthApi(http)
}
