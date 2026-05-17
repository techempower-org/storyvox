package `in`.jphe.storyvox.source.royalroad.di

import `in`.jphe.storyvox.data.auth.AuthSource
import `in`.jphe.storyvox.data.auth.SessionHydrator
import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.source.royalroad.RoyalRoadSource
import `in`.jphe.storyvox.source.royalroad.auth.RoyalRoadAuthSource
import `in`.jphe.storyvox.source.royalroad.auth.RoyalRoadSessionHydrator
import `in`.jphe.storyvox.source.royalroad.model.RoyalRoadIds
import `in`.jphe.storyvox.source.royalroad.net.RateLimitedClient
import `in`.jphe.storyvox.source.royalroad.net.RoyalRoadCookieJar
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
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class RoyalRoadHttp

@Module
@InstallIn(SingletonComponent::class)
internal object RoyalRoadHttpModule {

    @Provides @Singleton @RoyalRoadHttp
    fun provideClient(
        jar: RoyalRoadCookieJar,
        // Issue #597 — `Lazy<>` breaks the Dagger cycle:
        // NetworkPatienceConfig is bound to SettingsRepositoryUiImpl,
        // which transitively depends on FictionSource (because
        // AuthRepository walks the source map), which depends on
        // RoyalRoadSource, which depends on this OkHttpClient.
        // Wrapping the dependency in `Lazy<>` defers the read until
        // the Interceptor actually fires — by which time the rest of
        // the graph is constructed and the cycle is broken.
        patienceConfig: Lazy<NetworkPatienceConfig>,
    ): OkHttpClient {
        // Read the user's preset once at first request. The default
        // patience covers the singleton client's initial timeouts;
        // an Interceptor below overrides the per-call timeouts so a
        // Settings flip applies on the next call (no process
        // restart needed).
        val defaultPatience = NetworkPatience.Default
        return OkHttpClient.Builder()
            .cookieJar(jar)
            .followRedirects(true)
            .followSslRedirects(true)
            // Tab A7 Lite is the constraint device. Without explicit
            // timeouts OkHttp falls back to 10 s/10 s/10 s; combined
            // with retryOnConnectionFailure(true) below the worst-case
            // stall on Wi-Fi-off / flaky-cellular reaches ~30 s — long
            // enough that Browse / FictionDetail sit at "blank cream"
            // before the upstream Failure surfaces and ErrorBlock
            // renders. The user-tunable [NetworkPatience] preset
            // controls all four timeouts:
            //   connectTimeout:  TCP handshake (incl. TLS pre-negotiation
            //                    on a flaky link).
            //   readTimeout:     between socket reads while a response
            //                    streams.
            //   callTimeout:     hard cap on the whole call (DNS +
            //                    connect + TLS + read + retries) — the
            //                    safety net that bounds the worst case
            //                    even when retryOnConnectionFailure
            //                    elects to retry.
            .connectTimeout(defaultPatience.connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(defaultPatience.readTimeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(defaultPatience.callTimeoutSeconds, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                // Per-call patience: re-read the pref on each chain
                // proceed so a Settings flip propagates immediately
                // without rebuilding the singleton client. The lazy
                // read is safe inside the Interceptor — by the time
                // the first request fires, the rest of the Dagger
                // graph is constructed.
                val patience = runBlocking { patienceConfig.get().currentPatience() }
                val req = chain.request().newBuilder()
                    .header("User-Agent", RoyalRoadIds.USER_AGENT)
                    .build()
                chain
                    .withConnectTimeout(patience.connectTimeoutSeconds.toInt(), TimeUnit.SECONDS)
                    .withReadTimeout(patience.readTimeoutSeconds.toInt(), TimeUnit.SECONDS)
                    .withWriteTimeout(patience.writeTimeoutSeconds.toInt(), TimeUnit.SECONDS)
                    .proceed(req)
            }
            .build()
    }

    @Provides @Singleton
    fun provideRateLimitedClient(
        @RoyalRoadHttp http: OkHttpClient,
        robots: `in`.jphe.storyvox.source.royalroad.net.RobotsCache,
    ): RateLimitedClient =
        RateLimitedClient(http, robots)

    @Provides @Singleton
    fun provideRobotsCache(@RoyalRoadHttp http: OkHttpClient): `in`.jphe.storyvox.source.royalroad.net.RobotsCache =
        `in`.jphe.storyvox.source.royalroad.net.RobotsCache(http)
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class RoyalRoadBindings {

    @Binds
    @Singleton
    @IntoMap
    @StringKey(SourceIds.ROYAL_ROAD)
    abstract fun bindFictionSource(impl: RoyalRoadSource): FictionSource

    /**
     * Royal Road's [SessionHydrator] — top-level singleton binding.
     *
     * Pre-#426 callers (StoryvoxApp's eager hydration, the Settings
     * sign-out path, the session-refresh worker, AuthViewModel itself)
     * inject `SessionHydrator` directly and expect to get the RR
     * implementation. Keep the binding as-is so those call sites are
     * untouched — PR2 of #426 adds a *separate* `@IntoMap` map
     * binding below for sourceId-keyed dispatch from the AO3 sign-in
     * path, without disturbing the legacy contract.
     */
    @Binds
    @Singleton
    abstract fun bindSessionHydrator(impl: RoyalRoadSessionHydrator): SessionHydrator

    /**
     * RR's contribution to the per-source hydrator map (#426 PR2).
     *
     * The cross-source [AuthSource] map (added in PR1) routes
     * WebView config by sourceId. PR2 adds the matching hydrator
     * map: `AuthViewModel.captureCookies(sourceId, ...)` looks up
     * `hydrators[sourceId]` and pushes the captured cookies into
     * the right OkHttp jar. RR's hydrator is the same singleton as
     * the top-level binding above — Hilt treats both as the same
     * scoped instance because they bind from the same provider.
     */
    @Binds
    @IntoMap
    @StringKey(SourceIds.ROYAL_ROAD)
    abstract fun bindSessionHydratorIntoMap(impl: RoyalRoadSessionHydrator): SessionHydrator

    /**
     * Contributes [RoyalRoadAuthSource] into the cross-source
     * `Map<String, AuthSource>` consumed by
     * [`in`.jphe.storyvox.data.repository.AuthRepository] (#426).
     *
     * PR2 will add an analogous binding in `:source-ao3` keyed by
     * [SourceIds.AO3]. The legacy single-source binding in
     * [`in`.jphe.storyvox.data.repository.AuthRepositoryImpl] is
     * replaced by the map; this binding is what keeps the RR sign-in
     * flow finding its WebView configuration after the refactor.
     */
    @Binds
    @Singleton
    @IntoMap
    @StringKey(SourceIds.ROYAL_ROAD)
    abstract fun bindAuthSource(impl: RoyalRoadAuthSource): AuthSource
}
