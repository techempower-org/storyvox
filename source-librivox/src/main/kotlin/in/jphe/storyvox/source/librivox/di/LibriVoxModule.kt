package `in`.jphe.storyvox.source.librivox.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.source.librivox.LibriVoxSource
import `in`.jphe.storyvox.source.librivox.net.LibriVoxApi
import okhttp3.OkHttpClient
import javax.inject.Qualifier
import javax.inject.Singleton
import java.util.concurrent.TimeUnit

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LibriVoxHttp

/**
 * Issue #1015 — dedicated OkHttp client for the LibriVox catalog API.
 *
 * Modest connect timeout (the user might be on cellular), generous read
 * timeout (an `extended=1` single-book response for a 128-section novel
 * can be a few hundred KB), redirect-following ON because the
 * archive.org `listen_url`s 301 to a CDN host. We don't pool the client
 * with the other source backends — mixing clients across hostnames
 * defeats OkHttp's keep-alive within a single host, so each backend gets
 * its own pool (same rationale as `:source-radio`).
 */
@Module
@InstallIn(SingletonComponent::class)
internal object LibriVoxHttpModule {

    @Provides
    @Singleton
    @LibriVoxHttp
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
    fun provideLibriVoxApi(
        @LibriVoxHttp client: OkHttpClient,
    ): LibriVoxApi = LibriVoxApi(client)
}

/**
 * Issue #1015 — contributes [LibriVoxSource] into the multi-source
 * `Map<String, FictionSource>` under [SourceIds.LIBRIVOX].
 *
 * Dual-wire: the matching `@SourcePlugin` annotation on [LibriVoxSource]
 * adds the registry-driven descriptor binding (so the Settings →
 * Library & Sync auto-section and Browse chip surface it without manual
 * touchpoints), while this `@IntoMap` binding keeps the repository's
 * existing `Map<String, FictionSource>` resolving the source — the same
 * coexistence pattern `:source-radio` uses during the plugin-seam
 * migration (#384).
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class LibriVoxBindings {

    @Binds
    @Singleton
    @IntoMap
    @StringKey(SourceIds.LIBRIVOX)
    abstract fun bindFictionSource(impl: LibriVoxSource): FictionSource
}
