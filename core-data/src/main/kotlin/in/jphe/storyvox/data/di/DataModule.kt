package `in`.jphe.storyvox.data.di

import android.content.Context
import android.content.SharedPreferences
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

    @Provides
    @Singleton
    fun provideEncryptedPrefs(@ApplicationContext ctx: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            ctx,
            "storyvox.secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
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
