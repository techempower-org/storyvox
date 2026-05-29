package `in`.jphe.storyvox.data.di

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.room.Room
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import `in`.jphe.storyvox.data.coroutines.ApplicationScope
import `in`.jphe.storyvox.data.db.StoryvoxDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import `in`.jphe.storyvox.data.db.dao.AuthDao
import `in`.jphe.storyvox.data.db.dao.ChapterDao
import `in`.jphe.storyvox.data.db.dao.ChapterHistoryDao
import `in`.jphe.storyvox.data.db.dao.FictionDao
import `in`.jphe.storyvox.data.db.dao.FictionMemoryDao
import `in`.jphe.storyvox.data.db.dao.FictionShelfDao
import `in`.jphe.storyvox.data.db.dao.InboxEventDao
import `in`.jphe.storyvox.data.db.dao.LlmMessageDao
import `in`.jphe.storyvox.data.db.dao.LlmSessionDao
import `in`.jphe.storyvox.data.db.dao.PlaybackDao
import `in`.jphe.storyvox.data.db.migration.ALL_MIGRATIONS
import `in`.jphe.storyvox.data.repository.AuthRepository
import `in`.jphe.storyvox.data.repository.AuthRepositoryImpl
import `in`.jphe.storyvox.data.repository.ChapterDownloadScheduler
import `in`.jphe.storyvox.data.repository.ChapterRepository
import `in`.jphe.storyvox.data.repository.ChapterRepositoryImpl
import `in`.jphe.storyvox.data.repository.FictionMemoryRepository
import `in`.jphe.storyvox.data.repository.FictionMemoryRepositoryImpl
import `in`.jphe.storyvox.data.repository.FictionRepository
import `in`.jphe.storyvox.data.repository.FictionRepositoryImpl
import `in`.jphe.storyvox.data.repository.FollowsRepository
import `in`.jphe.storyvox.data.repository.FollowsRepositoryImpl
import `in`.jphe.storyvox.data.repository.HistoryRepository
import `in`.jphe.storyvox.data.repository.HistoryRepositoryImpl
import `in`.jphe.storyvox.data.repository.InboxRepository
import `in`.jphe.storyvox.data.repository.InboxRepositoryImpl
import `in`.jphe.storyvox.data.repository.LibraryRepository
import `in`.jphe.storyvox.data.repository.LibraryRepositoryImpl
import `in`.jphe.storyvox.data.repository.PlaybackPositionRepository
import `in`.jphe.storyvox.data.repository.PlaybackPositionRepositoryImpl
import `in`.jphe.storyvox.data.repository.ShelfRepository
import `in`.jphe.storyvox.data.repository.ShelfRepositoryImpl
import `in`.jphe.storyvox.data.repository.WorkManagerChapterDownloadScheduler
import javax.inject.Singleton

/**
 * Hilt graph for `:core-data`. Note that `FictionSource` itself is NOT bound
 * here — it lives in `:source-royalroad` (Oneiros) under its own
 * `@InstallIn(SingletonComponent::class)` module. We just inject it into our
 * repository impls.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDb(@ApplicationContext ctx: Context): StoryvoxDatabase =
        Room.databaseBuilder(ctx, StoryvoxDatabase::class.java, StoryvoxDatabase.NAME)
            .addMigrations(*ALL_MIGRATIONS)
            // Issue #721 — survive APK downgrade (newer install → older
            // install) by wiping the DB instead of crashing. ALL_MIGRATIONS
            // covers v1→v9 forward only; Room doesn't auto-derive
            // downgrade paths and would throw `IllegalStateException`
            // ("A migration from N to M was required but not found")
            // at the first call to provideDb on downgrade — silent
            // hard-crash at app startup with no recovery short of
            // uninstall (which wipes the user's library entirely).
            //
            // The trade-off — wipe the local Room DB on strict
            // downgrade — is the right policy here: library content
            // rehydrates from the source backends (Royal Road, Gutenberg,
            // etc.) on next sync, and EncryptedSharedPreferences (cookies,
            // sync state) is untouched. Same-version opens and forward
            // migrations are unaffected.
            //
            // Real-world downgrade scenarios this protects:
            //   1. Sideload testing on R83W80CAFZB / R5CRB0W66MK
            //      (`adb install -d v0.5.70.apk` over a v0.5.71 install).
            //   2. Play Store user-initiated rollback.
            //   3. Internal-track / beta channel rollback.
            .fallbackToDestructiveMigrationOnDowngrade()
            // Issue #570 — F2FS_IOC_SET_PIN_FILE SELinux denial silencer.
            // Samsung's One UI 4+ (F2FS filesystem) audits SQLite's WAL
            // file-pinning ioctl as `untrusted_app` cannot perform
            // ioctl on app_data_file (ioctlcmd=0xf522 in dmesg). The
            // denials are cosmetic (the WAL pin call falls through
            // benignly when denied) but flood the audit log on Z
            // Flip3, slowing every `adb shell dumpsys` and burying
            // real failures. Switching the journal mode to TRUNCATE
            // bypasses WAL entirely so SQLite never asks F2FS to pin
            // the log file. Trade-off: slightly higher per-write
            // latency on bulk transactions (storyvox's DB is small —
            // chapter rows, history rows, playback positions — so
            // the cost is negligible). Read concurrency is
            // unaffected; readers + writer still serialize through
            // a single connection regardless of mode.
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
            .build()

    @Provides fun fictionDao(db: StoryvoxDatabase): FictionDao = db.fictionDao()
    @Provides fun chapterDao(db: StoryvoxDatabase): ChapterDao = db.chapterDao()
    @Provides fun chapterHistoryDao(db: StoryvoxDatabase): ChapterHistoryDao = db.chapterHistoryDao()
    @Provides fun playbackDao(db: StoryvoxDatabase): PlaybackDao = db.playbackDao()
    @Provides fun authDao(db: StoryvoxDatabase): AuthDao = db.authDao()
    @Provides fun llmSessionDao(db: StoryvoxDatabase): LlmSessionDao = db.llmSessionDao()
    @Provides fun llmMessageDao(db: StoryvoxDatabase): LlmMessageDao = db.llmMessageDao()
    @Provides fun fictionShelfDao(db: StoryvoxDatabase): FictionShelfDao = db.fictionShelfDao()
    @Provides fun inboxEventDao(db: StoryvoxDatabase): InboxEventDao = db.inboxEventDao()
    @Provides fun fictionMemoryDao(db: StoryvoxDatabase): FictionMemoryDao = db.fictionMemoryDao()

    /**
     * Long-lived [CoroutineScope] for singleton repositories that need to fire
     * "init" coroutines outside the lifecycle of a viewmodel / Hilt component.
     *
     * - [SupervisorJob] means a child failure doesn't poison sibling children.
     * - [Dispatchers.Default] is the safe default; consumers can override with
     *   `withContext(Dispatchers.IO)` for blocking I/O. This avoids pinning a
     *   whole-process scope to the IO pool.
     * - Qualified with [ApplicationScope] so test doubles can swap in a
     *   `TestScope` without colliding with other `CoroutineScope` bindings.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The single backing store for every encrypted preference in the app
     * (auth cookies, source tokens, palace credentials, sync magic-code,
     * etc.). The file name is referenced from XML backup rules
     * (`app/src/main/res/xml/backup_rules.xml`,
     * `data_extraction_rules.xml`) — if you rename it, you MUST update
     * both XML files in lockstep or auto-backup will silently restore
     * encrypted blobs whose MAC can no longer be verified by the new
     * install's keystore (see issue #951).
     */
    private const val SECRETS_PREFS_FILE = "storyvox.secrets"

    /**
     * Provides the app-wide [EncryptedSharedPreferences] handle, with a
     * one-shot self-heal on keystore/MAC verification failures.
     *
     * ### Why the defensive layer (issue #951)
     *
     * After uninstall + reinstall on a device with Google auto-backup
     * configured, `BackupManager` restores `/data/data/<pkg>/shared_prefs/`
     * BEFORE the first cold start. The restored EncryptedSharedPreferences
     * blob was MAC'd with the previous install's Android Keystore master
     * key — which is destroyed at uninstall. The new install's keystore
     * has a fresh master key that cannot verify the restored MAC, and
     * `EncryptedSharedPreferences.create` (or the first read after init)
     * throws:
     *
     *   - `android.security.KeyStoreException` ("Signature/MAC verification failed")
     *   - `javax.crypto.AEADBadTagException`
     *   - `java.security.InvalidKeyException`
     *   - `java.security.GeneralSecurityException` (Tink wrappers)
     *
     * With `allowBackup=true` and no recovery path, the app hard-crashes
     * on cold start.
     *
     * The XML backup rules (`backup_rules.xml`, `data_extraction_rules.xml`)
     * EXCLUDE this file from auto-backup, which is the primary defense.
     * This try/catch is the secondary defense in case the exclusion rules
     * are misconfigured or a future Android version changes the semantics:
     * we delete the corrupt blob, delete the keystore entry, and recreate
     * an empty store. The user loses whatever was stored (source tokens,
     * sync magic-code) and has to re-authenticate — far better than a
     * launch-loop crash.
     *
     * Trade-off: a real user-initiated restore (e.g. via Google's
     * "continue setup from previous device" flow) would also wipe the
     * restored credentials. Acceptable: those credentials are device-bound
     * (Android Keystore is non-exportable by construction), so they could
     * never have been restored intact regardless of `allowBackup`. The
     * user has to sign in again on the new device either way.
     *
     * The catch is intentionally broad (`Exception`) because the real
     * failure mode in #951 surfaces as
     * `android.security.KeyStoreException` — a hidden/system class that
     * we cannot reference by name from app code, and which is NOT a
     * subclass of `GeneralSecurityException`. Tink also wraps various
     * I/O and crypto failures in its own runtime exceptions. Recovering
     * by wiping the corrupt file is always safe (the user just has to
     * re-authenticate); failing to recover and crashing is not. We log
     * the exception so a real bug in our crypto path is still
     * diagnosable in logcat, and we do NOT catch `Error` — OOMs, etc.,
     * still propagate.
     */
    @Provides
    @Singleton
    fun provideEncryptedPrefs(@ApplicationContext ctx: Context): SharedPreferences {
        return try {
            openEncryptedPrefs(ctx)
        } catch (e: Exception) {
            Log.w(TAG_PREFS, "EncryptedSharedPreferences open failed; wiping + retrying", e)
            recoverFromCorruptSecrets(ctx)
            openEncryptedPrefs(ctx)
        }
    }

    private fun openEncryptedPrefs(ctx: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val prefs = EncryptedSharedPreferences.create(
            ctx,
            SECRETS_PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        // Force decryption of the index — `create()` may succeed lazily
        // while the underlying Tink keyset is unreadable. `all` (which
        // calls `getAll()`) is the cheapest way to surface a bad-MAC
        // failure NOW, while we still hold a try/catch frame around it.
        prefs.all
        return prefs
    }

    private fun recoverFromCorruptSecrets(ctx: Context) {
        // Delete the prefs blob (both the shared_prefs XML file and any
        // companion file Tink may have created).
        runCatching { ctx.deleteSharedPreferences(SECRETS_PREFS_FILE) }
        // Delete the master key entry from the Android Keystore so the
        // next `MasterKey.Builder` call regenerates it cleanly. If we
        // skip this step, Tink may still fail because it has a fresh
        // keyset but the master key alias is in an inconsistent state.
        // `MasterKey.DEFAULT_MASTER_KEY_ALIAS` is deprecated in the
        // public API but the *literal alias string* is still what
        // androidx.security uses by default — we must clear that exact
        // entry. Inlining the constant rather than depending on the
        // deprecated symbol.
        runCatching {
            val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keyStore.containsAlias(MASTER_KEY_ALIAS)) {
                keyStore.deleteEntry(MASTER_KEY_ALIAS)
            }
        }
    }

    /**
     * The default master-key alias used by androidx.security's
     * [MasterKey.Builder] when no explicit alias is supplied. Inlined
     * because the public `MasterKey.DEFAULT_MASTER_KEY_ALIAS` is
     * deprecated, but the underlying alias string has not changed.
     */
    private const val MASTER_KEY_ALIAS = "_androidx_security_master_key_"

    private const val TAG_PREFS = "DataModule.SecretsPrefs"
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryBindings {

    @Binds @Singleton
    abstract fun bindFictionRepository(impl: FictionRepositoryImpl): FictionRepository

    @Binds @Singleton
    abstract fun bindChapterRepository(impl: ChapterRepositoryImpl): ChapterRepository

    @Binds @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds @Singleton
    abstract fun bindPlaybackPositionRepository(
        impl: PlaybackPositionRepositoryImpl,
    ): PlaybackPositionRepository

    @Binds @Singleton
    abstract fun bindLibraryRepository(impl: LibraryRepositoryImpl): LibraryRepository

    @Binds @Singleton
    abstract fun bindFollowsRepository(impl: FollowsRepositoryImpl): FollowsRepository

    @Binds @Singleton
    abstract fun bindShelfRepository(impl: ShelfRepositoryImpl): ShelfRepository

    @Binds @Singleton
    abstract fun bindHistoryRepository(impl: HistoryRepositoryImpl): HistoryRepository

    @Binds @Singleton
    abstract fun bindInboxRepository(impl: InboxRepositoryImpl): InboxRepository

    @Binds @Singleton
    abstract fun bindFictionMemoryRepository(
        impl: FictionMemoryRepositoryImpl,
    ): FictionMemoryRepository

    @Binds @Singleton
    abstract fun bindChapterDownloadScheduler(
        impl: WorkManagerChapterDownloadScheduler,
    ): ChapterDownloadScheduler
}
