package `in`.jphe.storyvox.source.notion.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import dagger.Lazy
import `in`.jphe.storyvox.data.repository.net.NetworkPatience
import `in`.jphe.storyvox.data.repository.net.NetworkPatienceConfig
import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.source.notion.NotionSource
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import javax.inject.Qualifier
import javax.inject.Singleton
import java.util.concurrent.TimeUnit

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class NotionHttp

/**
 * Dedicated OkHttp client for the Notion REST API. Generous read
 * timeout because block-children fetches on long pages can compose
 * into 10-20 paginated round-trips and Notion's edge can be slow
 * during peak hours. Connect timeout stays tight — api.notion.com is
 * Cloudflare-fronted and reliably reachable.
 *
 * Notion uses HTTP/2 keep-alive; OkHttp pools connections so a
 * fictionDetail → chapter flow shares the same TLS handshake.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object NotionHttpModule {

    @Provides
    @Singleton
    @NotionHttp
    fun provideClient(patienceConfig: Lazy<NetworkPatienceConfig>): OkHttpClient {
        // Issue #597 — user-tunable patience preset. Notion's multi-
        // page block-children walks especially benefit from the
        // Patient (30s budget) preset. `Lazy<>` breaks the Dagger
        // cycle through SettingsRepositoryUiImpl → FictionSource map;
        // see [RoyalRoadHttpModule.provideClient] for the explanation.
        val defaultPatience = NetworkPatience.Default
        return OkHttpClient.Builder()
            .connectTimeout(defaultPatience.connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(defaultPatience.readTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(defaultPatience.writeTimeoutSeconds, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val patience = runBlocking { patienceConfig.get().currentPatience() }
                chain
                    .withConnectTimeout(patience.connectTimeoutSeconds.toInt(), TimeUnit.SECONDS)
                    .withReadTimeout(patience.readTimeoutSeconds.toInt(), TimeUnit.SECONDS)
                    .withWriteTimeout(patience.writeTimeoutSeconds.toInt(), TimeUnit.SECONDS)
                    .proceed(chain.request())
            }
            .build()
    }

    @Provides
    @Singleton
    fun provideNotionApi(
        @NotionHttp client: OkHttpClient,
        config: `in`.jphe.storyvox.source.notion.config.NotionConfig,
    ): `in`.jphe.storyvox.source.notion.net.NotionApi =
        `in`.jphe.storyvox.source.notion.net.NotionApi(client, config)

    /**
     * Issue #393 — anonymous-mode reader against the unofficial
     * `www.notion.so/api/v3` surface. Shares the [NotionHttp] OkHttp
     * client with the PAT-mode reader so both flows benefit from the
     * same connection pool + timeouts.
     */
    @Provides
    @Singleton
    fun provideNotionUnofficialApi(
        @NotionHttp client: OkHttpClient,
        config: `in`.jphe.storyvox.source.notion.config.NotionConfig,
    ): `in`.jphe.storyvox.source.notion.net.NotionUnofficialApi =
        `in`.jphe.storyvox.source.notion.net.NotionUnofficialApi(client, config)
}

/**
 * Issue #233 — contributes [NotionSource] into the multi-source
 * `Map<String, FictionSource>`. Adds a "Notion" entry to the
 * segmented source picker; persisted fictions with sourceId="notion"
 * route through this source.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class NotionBindings {

    @Binds
    @Singleton
    @IntoMap
    @StringKey(SourceIds.NOTION)
    abstract fun bindFictionSource(impl: NotionSource): FictionSource
}
