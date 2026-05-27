package `in`.jphe.storyvox.source.wikipedia.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.source.wikipedia.WikipediaSource
import okhttp3.OkHttpClient
import javax.inject.Qualifier
import javax.inject.Singleton
import java.util.concurrent.TimeUnit

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class WikipediaHttp

/**
 * Dedicated OkHttp client for the Wikimedia REST API. Generous read
 * timeout because Parsoid HTML payloads can be 100kB-1MB for long
 * articles; connect timeout stays tight because en.wikipedia.org is
 * reliably reachable on any reasonable connection.
 *
 * Wikimedia REST traffic is heavily edge-cached and uses HTTP/2
 * keep-alive; the default OkHttp client pools connections so a
 * Search → tap-article → fetch-sections flow shares a single TLS
 * handshake.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object WikipediaHttpModule {

    @Provides
    @Singleton
    @WikipediaHttp
    fun provideClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()

    @Provides
    @Singleton
    fun provideWikipediaApi(
        @WikipediaHttp client: OkHttpClient,
        config: `in`.jphe.storyvox.source.wikipedia.config.WikipediaConfig,
    ): `in`.jphe.storyvox.source.wikipedia.net.WikipediaApi =
        `in`.jphe.storyvox.source.wikipedia.net.WikipediaApi(client, config)
}

/**
 * Issue #377 — contributes [WikipediaSource] into the multi-source
 * `Map<String, FictionSource>`. Adds a "Wikipedia" entry to the
 * segmented source picker; persisted fictions with sourceId="wikipedia"
 * route through this source.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class WikipediaBindings {

    @Binds
    @Singleton
    @IntoMap
    @StringKey(SourceIds.WIKIPEDIA)
    abstract fun bindFictionSource(impl: WikipediaSource): FictionSource

    /**
     * Issue #796 — expose the same [WikipediaSource] singleton through
     * the [`in`.jphe.storyvox.source.wikipedia.WikipediaBrowseSource]
     * seam so the app-module Browse adapter can route the On This Day /
     * In the News tabs without depending on the internal source type.
     * Mirrors the AO3 / GitHub authed-source bindings.
     */
    @Binds
    @Singleton
    abstract fun bindBrowseSource(
        impl: WikipediaSource,
    ): `in`.jphe.storyvox.source.wikipedia.WikipediaBrowseSource
}
