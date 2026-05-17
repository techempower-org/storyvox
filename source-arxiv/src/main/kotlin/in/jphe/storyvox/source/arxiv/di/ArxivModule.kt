package `in`.jphe.storyvox.source.arxiv.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.source.arxiv.ArxivSource
import okhttp3.OkHttpClient
import javax.inject.Qualifier
import javax.inject.Singleton
import java.util.concurrent.TimeUnit

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ArxivHttp

/**
 * Issue #378 — dedicated OkHttp client for the arXiv API. Generous read
 * timeout because the Atom-feed response can carry up to 50 entries (each
 * with a paragraph-length abstract); connect timeout stays tight because
 * `export.arxiv.org` is CDN-fronted and reliably reachable.
 *
 * Follow-redirects stays on as a belt-and-suspenders guard, but the
 * base URL is now HTTPS directly — Android's network-security-config
 * blocks cleartext before OkHttp can follow arXiv's HTTP→HTTPS 301.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object ArxivHttpModule {

    @Provides
    @Singleton
    @ArxivHttp
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
    fun provideArxivApi(
        @ArxivHttp client: OkHttpClient,
    ): `in`.jphe.storyvox.source.arxiv.net.ArxivApi =
        `in`.jphe.storyvox.source.arxiv.net.ArxivApi(client)
}

/**
 * Issue #378 — contributes [ArxivSource] into the multi-source
 * `Map<String, FictionSource>`. Adds an "arXiv" entry to the segmented
 * source picker; persisted fictions with sourceId="arxiv" route through
 * this source.
 *
 * Plugin-seam Phase 2 (#384) dual-wire: the `@SourcePlugin` annotation
 * on [ArxivSource] also emits a Hilt `@IntoSet SourcePluginDescriptor`
 * binding via the `:core-plugin-ksp` processor. The two views coexist
 * until Phase 3 retires the legacy map binding.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class ArxivBindings {

    @Binds
    @Singleton
    @IntoMap
    @StringKey(SourceIds.ARXIV)
    abstract fun bindFictionSource(impl: ArxivSource): FictionSource
}
