package `in`.jphe.storyvox.sync.di

import android.content.Context
import android.content.SharedPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet
import dagger.multibindings.IntoSet
import `in`.jphe.storyvox.sync.BuildConfig
import `in`.jphe.storyvox.sync.client.DisabledBackend
import `in`.jphe.storyvox.sync.client.HttpInstantBackend
import `in`.jphe.storyvox.sync.client.InstantBackend
import `in`.jphe.storyvox.sync.client.InstantClient
import `in`.jphe.storyvox.sync.client.InstantHttpTransport
import `in`.jphe.storyvox.sync.client.InstantSession
import `in`.jphe.storyvox.sync.client.OkHttpInstantTransport
import `in`.jphe.storyvox.data.db.dao.AnnotationDao
import `in`.jphe.storyvox.data.db.dao.ChapterDao
import `in`.jphe.storyvox.data.db.dao.FictionDao
import `in`.jphe.storyvox.sync.coordinator.Syncer
import `in`.jphe.storyvox.sync.coordinator.TombstoneStore
import `in`.jphe.storyvox.sync.domain.AnnotationsSyncer
import `in`.jphe.storyvox.sync.domain.BookmarksSyncer
import `in`.jphe.storyvox.sync.domain.FollowsSyncer
import `in`.jphe.storyvox.sync.domain.LibrarySyncer

import `in`.jphe.storyvox.sync.domain.PlaybackPositionSyncer
import `in`.jphe.storyvox.sync.domain.PronunciationDictSyncer
import `in`.jphe.storyvox.sync.domain.SecretsSyncer
import `in`.jphe.storyvox.sync.domain.SettingsSyncer

import javax.inject.Singleton

/**
 * Hilt wiring for the `:core-sync` module.
 *
 * The DI graph:
 *  - [InstantBackend]: the data-plane. Resolves to [HttpInstantBackend]
 *    when [BuildConfig.INSTANTDB_APP_ID] is a real value; to
 *    [DisabledBackend] when it's the sentinel placeholder. The sentinel
 *    keeps the app building on CI runners that don't have the secret.
 *  - [InstantClient]: the auth-plane HTTP client.
 *  - [InstantSession]: token storage.
 *  - [Set<Syncer>]: multibound, populated by each per-domain syncer
 *    contributing itself via `@IntoSet`. The [SyncCoordinator] injects
 *    the full set and dispatches per-domain.
 *
 * Why multibinds and not a hand-rolled "register every syncer in
 * onCreate": multibinds is the idiomatic Hilt seam for "plug in N
 * implementations of an interface and have the framework hand the
 * consumer the full set." Adding a new syncer is then a single `@Binds`
 * + `@IntoSet` line — no risk of forgetting to register, and the
 * coordinator's signature documents the contract.
 *
 * SecretsSyncer is wired here alongside the other syncers — the earlier
 * "circular dep" justification (defer to `:app`) didn't survive a second
 * look. SecretsSyncer's deps are [SharedPreferences] (from `:core-data`),
 * [InstantBackend] (this module), and a [PassphraseProvider] (defaulted
 * to a no-op null binding here; `:app` or `:feature` overrides once the
 * Settings → Account passphrase entry lands). No cycle, and this module
 * now controls the full multibound set.
 */
@Module
@InstallIn(SingletonComponent::class)
object SyncModule {

    @Provides
    @Singleton
    fun provideHttpTransport(): InstantHttpTransport = OkHttpInstantTransport()

    @Provides
    @Singleton
    fun provideInstantClient(transport: InstantHttpTransport): InstantClient =
        InstantClient(appId = BuildConfig.INSTANTDB_APP_ID, transport = transport)

    @Provides
    @Singleton
    fun provideInstantSession(prefs: SharedPreferences): InstantSession =
        InstantSession(prefs)

    @Provides
    @Singleton
    fun provideInstantBackend(transport: InstantHttpTransport): InstantBackend {
        val appId = BuildConfig.INSTANTDB_APP_ID
        return if (appId.isBlank() || appId == HttpInstantBackend.PLACEHOLDER_APP_ID) {
            DisabledBackend()
        } else {
            // Issue #691 — the previous wiring used a WebSocket backend
            // (`WsInstantBackend`) against `/runtime/session`. That endpoint
            // returns EAV triples wrapped in a `datalog-result` envelope, not
            // the materialized rows the syncers expect; every domain failed
            // with `JsonArray is not a JsonObject`. The admin HTTP API
            // (`/admin/query`, `/admin/transact`) accepts as-token
            // impersonation (no admin token shipped) and returns rows keyed
            // by entity name — exactly the shape our syncers consume.
            HttpInstantBackend(appId = appId, transport = transport)
        }
    }

    /** Bookmark / pronunciation / playback-position / library / follows
     *  syncers — every concrete [Syncer] gets contributed to the
     *  multibound set the coordinator consumes. New domains land here
     *  with a single `@IntoSet` line. */
    @Provides @IntoSet
    fun provideLibrarySyncer(impl: LibrarySyncer): Syncer = impl

    @Provides @IntoSet
    fun provideFollowsSyncer(impl: FollowsSyncer): Syncer = impl

    @Provides @IntoSet
    fun providePlaybackPositionSyncer(impl: PlaybackPositionSyncer): Syncer = impl

    @Provides @IntoSet
    fun providePronunciationDictSyncer(impl: PronunciationDictSyncer): Syncer = impl

    @Provides @IntoSet
    fun provideBookmarksSyncer(impl: BookmarksSyncer): Syncer = impl

    /** Issue #999 — text highlights + notes. One blob/user, LWW, the same
     *  #1029 absence-is-not-deletion + local-only-union contract as
     *  BookmarksSyncer. The FK-parent existence probes are supplied as
     *  lambdas (fiction via [FictionDao.get], chapter via [ChapterDao.exists])
     *  so the syncer can FK-guard an incoming annotation whose chapter/fiction
     *  row hasn't synced locally yet, without depending on the whole DAOs. */
    @Provides @IntoSet
    fun provideAnnotationsSyncer(
        annotationDao: AnnotationDao,
        backend: InstantBackend,
        prefs: SharedPreferences,
        chapterDao: ChapterDao,
        fictionDao: FictionDao,
    ): Syncer = AnnotationsSyncer(
        annotationDao = annotationDao,
        backend = backend,
        prefs = prefs,
        chapterExists = { id -> chapterDao.exists(id) },
        fictionExists = { id -> fictionDao.get(id) != null },
    )

    /** Issue #360 finding 1 (argus) — SecretsSyncer is now in the
     *  multibound set so its push/pull actually fires from the
     *  coordinator. Without this binding, secrets sync silently no-ops
     *  on every device and a reinstall loses the user's LLM keys / RR
     *  cookies / GitHub PAT / Outline tokens. */
    @Provides @IntoSet
    fun provideSecretsSyncer(impl: SecretsSyncer): Syncer = impl

    /** Tier 1 (this PR) — non-sensitive preferences as a single LWW
     *  JSON blob per user. Settings push happens on every sync round;
     *  the `:app` impl of [SettingsSnapshotSource] owns which keys
     *  are in scope. */
    @Provides @IntoSet
    fun provideSettingsSyncer(impl: SettingsSyncer): Syncer = impl

}

// [SettingsSnapshotSource] and [SecretsSnapshotSource] bindings live
// in `:app`'s SettingsRepositoryUiImpl + its Hilt @Binds module — the
// snapshot interfaces are owned by `:core-data`, but the
// implementations live in `:app` (file-private DataStore). `:core-sync`
// provides no defaults here on purpose: forcing the wiring to live in
// `:app` makes "the sync layer can read settings" an explicit
// integration step, not an accidental no-op.
