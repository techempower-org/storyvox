package `in`.jphe.storyvox

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import `in`.jphe.storyvox.BuildConfig
import `in`.jphe.storyvox.data.PrerenderModeWatcher
import `in`.jphe.storyvox.data.VersionUpgradeHandler
import `in`.jphe.storyvox.data.auth.SessionHydrator
import `in`.jphe.storyvox.data.db.StoryvoxDatabase
import `in`.jphe.storyvox.data.log.DebugLog
import `in`.jphe.storyvox.data.repository.AuthRepository
import `in`.jphe.storyvox.data.repository.FictionRepository
import `in`.jphe.storyvox.data.repository.PlaybackPositionRepository
import `in`.jphe.storyvox.data.repository.ShelfRepository
import `in`.jphe.storyvox.data.work.MetadataBackfillScheduler
import `in`.jphe.storyvox.data.work.NewChapterNotifier
import `in`.jphe.storyvox.data.work.WorkScheduler
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import `in`.jphe.storyvox.playback.VoiceEngineQualityBridge
import `in`.jphe.storyvox.sync.coordinator.SyncCoordinator
import `in`.jphe.storyvox.widget.WidgetStateObserver
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

@HiltAndroidApp
class StoryvoxApp : Application(), Configuration.Provider {

    /**
     * Issue #409 — cold launch on the Tab A7 Lite (Helio P22T) was 6.8s
     * vs 1.2s on the Z Flip3 (SD888). Logcat traced ~3.3s of that to the
     * synchronous `super.onCreate()` block below: `@Inject lateinit var`
     * fields are materialized eagerly when Hilt's
     * [HiltAndroidApp]-generated subclass calls `inject(this)` from
     * `attachBaseContext` / `onCreate`, and on a low-end SoC, building a
     * 6-field graph that transitively constructs ~25 [Singleton]s (esp.
     * the 20-arg [SettingsRepositoryUiImpl] and the 16-syncer
     * [SyncCoordinator] set-binding) before the first frame budget even
     * starts is a meaningful percentage of the cold-launch wall-clock.
     *
     * Switching to `Lazy<T>` defers actual construction to the moment of
     * `.get()`, which we punt to a background coroutine in [onCreate].
     * The Hilt graph is still fully wired (still type-safe, still
     * @Singleton-scoped); we just stop *materialising* it on the main
     * thread before the splash screen disappears.
     *
     * Only [workerFactory] stays eager — Android's `WorkManager`
     * initializer queries [Configuration.Provider.workManagerConfiguration]
     * the first time `WorkManager.getInstance(context)` is called, and
     * that getter reads [workerFactory]. We need it ready before any
     * worker is enqueued.
     */
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var workScheduler: Lazy<WorkScheduler>
    @Inject lateinit var authRepository: Lazy<AuthRepository>
    @Inject lateinit var sessionHydrator: Lazy<SessionHydrator>
    @Inject lateinit var syncCoordinator: Lazy<SyncCoordinator>
    @Inject lateinit var settingsRepo: Lazy<SettingsRepositoryUi>

    /**
     * Issue #409 — pre-warm targets for [warmDataLayer]. All five
     * are Hilt `@Singleton` instances LibraryViewModel (the start
     * destination's VM) injects on its constructor. Materialising
     * them on `Dispatchers.IO` from [Application.onCreate] means
     * by the time `hiltViewModel()` resolves the VM on the Compose
     * Main dispatcher, the underlying [StoryvoxDatabase] is already
     * built, the Room migration ladder has been validated, and the
     * repository singletons are cached — Hilt's DoubleCheck just
     * hands back the cached instances instead of doing first-time
     * construction (Room file open + schema validation ≈ 600-900 ms
     * on the Helio P22T tablet).
     *
     * These are still `Lazy<>` so any failure during the bg warm-up
     * doesn't take down the process: the foreground request just
     * runs the same construction it would have run pre-warm.
     */
    @Inject lateinit var database: Lazy<StoryvoxDatabase>
    @Inject lateinit var fictionRepository: Lazy<FictionRepository>
    @Inject lateinit var shelfRepository: Lazy<ShelfRepository>
    @Inject lateinit var playbackPositionRepository: Lazy<PlaybackPositionRepository>

    /** PR-F (#86) — Mode C (fullPrerender) flow collector. `Lazy<>` so
     *  the singleton isn't materialised on the cold-launch main thread;
     *  initScope's coroutine pulls it on IO and calls [start()]. */
    @Inject lateinit var prerenderModeWatcher: Lazy<PrerenderModeWatcher>

    /** Issue #159 — push-driven RemoteViews refresher for the home-
     *  screen now-playing widget. Subscribes to PlaybackController.state
     *  and broadcasts a refresh to NowPlayingWidgetProvider on every
     *  user-visible change. Started off the cold-launch main thread
     *  via the [Lazy] wrapper. */
    @Inject lateinit var widgetStateObserver: Lazy<WidgetStateObserver>

    /**
     * Issue #178 — Royal Road tag-sync periodic worker + sign-in
     * observer. The scheduler enqueues the 24h periodic worker
     * (idempotent KEEP policy). The observer subscribes to
     * AuthRepository's RR session state and kicks the coordinator
     * once on the Anonymous → Authenticated edge, so first-sync
     * happens immediately after sign-in rather than waiting ~24h.
     */
    @Inject lateinit var tagSyncScheduler: Lazy<`in`.jphe.storyvox.source.royalroad.tagsync.RoyalRoadTagSyncScheduler>
    @Inject lateinit var tagSyncCoordinator: Lazy<`in`.jphe.storyvox.source.royalroad.tagsync.RoyalRoadTagSyncCoordinator>

    /**
     * Issue #860 — compares the persisted versionCode against
     * [BuildConfig.VERSION_CODE] on cold start and wipes the PCM
     * cache on any mismatch. Lazy so the Hilt graph + DataStore +
     * PcmCache construction stays off the cold-launch main thread
     * (Issue #409 — same posture as the other init handlers above).
     */
    @Inject lateinit var versionUpgradeHandler: Lazy<VersionUpgradeHandler>

    /**
     * Issue #907 — registers the "New Chapters" notification channel on
     * cold start so it's visible in system settings before the first
     * poll fires one, and so a user can pre-mute it. Lazy + IO like the
     * rest of the init handlers (Issue #409 cold-launch posture); the
     * notifier itself also self-heals the channel on each notify().
     */
    @Inject lateinit var newChapterNotifier: Lazy<NewChapterNotifier>

    /**
     * Issue #981 — enqueues the metadata back-fill worker after the
     * cold-start sync pull. `LibrarySyncer`/`FollowsSyncer` insert
     * placeholder Fiction rows (`title = "Loading…"`,
     * `metadataFetchedAt = 0`) for members added on another device; this
     * scheduler kicks the worker that hydrates them so a synced library
     * doesn't render as a wall of "Loading…" cards. Lazy + IO like the
     * rest of init (Issue #409 cold-launch posture).
     */
    @Inject lateinit var metadataBackfillScheduler: Lazy<MetadataBackfillScheduler>

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.INFO)
            .build()

    /**
     * Long-lived scope for the deferred init coroutines. Survives the
     * Application lifetime; uses a [SupervisorJob] so a failure in one
     * init step doesn't poison the others.
     */
    private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Issue #409 (round 2) — voice-engine seed calls are gated through
     * this latch so they don't fire from [Application.onCreate]. Even
     * though the seed coroutines run on [Dispatchers.IO], the FIRST
     * static-field touch on `VoiceEngine`/`KokoroEngine` triggers
     * class-loader resolution of the VoxSherpa AAR, which dlopens
     * `libsherpa-onnx-jni.so` (~600 KB). On the Helio P22T tablet,
     * that dlopen contends with the Compose first-composition pass
     * for the single dexopt/linker mutex and steals roughly a second
     * of wall-clock from "splash → first frame" — the .so load is
     * unavoidable, but it doesn't need to happen on the cold-launch
     * critical path. MainActivity calls [seedVoiceEngineFromSettings]
     * from a `Choreographer.postFrameCallback` after the first real
     * frame is drawn; the seed coroutines then run before any chapter
     * can possibly start synthesizing (the user has to tap a chapter
     * first).
     */
    private val voiceEngineSeeded = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        // Issue #409 — every previous-eager step is now scheduled on
        // [Dispatchers.IO] via the [Lazy] accessors. The main thread
        // returns from onCreate as fast as the Hilt component-injection
        // boilerplate allows, so the splash screen → first-frame budget
        // is dominated by Compose work, not Application init.
        // Issue #409 — pre-warm the data layer (Room db open + key
        // repository singletons) on IO so LibraryViewModel's @Inject
        // constructor (called on Main during the first composition)
        // pulls cached instances instead of doing a first-time
        // Room.databaseBuilder().build() on the main thread.
        warmDataLayer()
        // Issue #860 — wipe the PCM cache on any versionCode change.
        // Runs on IO before anything that could touch the cache; the
        // first cache read is downstream of user interaction (tap a
        // chapter) so a short DataStore read + File.delete sweep here
        // is always done well before the cache is needed.
        //
        // Issue #931 — capture this Job so [prerenderModeWatcher] can
        // join() before it starts. If an upgrade is detected,
        // [runIfUpgraded] wipes the PCM cache root, which races with
        // any writer that has already opened a file under it. The
        // watcher is the only init-time writer that touches the cache,
        // so serializing watcher-start → upgrade-handler-finish is
        // sufficient; everything else on initScope stays concurrent.
        val upgradeHandlerJob = initScope.launch {
            versionUpgradeHandler.get().runIfUpgraded()
        }
        initScope.launch {
            // ensurePeriodicWorkScheduled hits SQLite via WorkManager's
            // internal database; on the Helio P22T that's ~150-300ms of
            // disk I/O. Off the main thread → invisible to cold launch.
            workScheduler.get().ensurePeriodicWorkScheduled()
        }
        // Issue #907 — register the "New Chapters" channel up front so it
        // shows in system notification settings before the poll fires.
        initScope.launch {
            newChapterNotifier.get().ensureChannel()
        }
        rehydrateRoyalRoadCookies()
        // Issue #823 — propagate the persisted "verbose logging" toggle
        // into DebugLog so the playback / download pipeline starts
        // emitting Info-level breadcrumbs on next event. distinctUntilChanged
        // keeps the AtomicBoolean writes quiet when the flow re-emits
        // the same value (DataStore replay-on-subscribe behaviour).
        initScope.launch {
            settingsRepo.get().settings
                .map { it.debugLogging }
                .distinctUntilChanged()
                .collect { DebugLog.setEnabled(it) }
        }
        // Voice-engine seed calls intentionally NOT invoked here — see
        // [seedVoiceEngineFromSettings] kdoc. MainActivity drives the
        // post-first-frame hand-off so the .so dlopen lands well after
        // the splash screen is gone.
        // InstantDB sync — if a refresh token is stored, validate it and
        // pull every per-domain syncer. No-op when no one is signed in.
        // Fire-and-forget: the coordinator launches its own coroutines
        // once [Lazy.get] materialises it on IO.
        initScope.launch {
            syncCoordinator.get().initialize()
        }
        // Issue #981 — once the library/follows pull settles, hydrate any
        // placeholder rows it created. `initialize()` is fire-and-forget
        // (launches its own pull coroutines), so we can't enqueue right
        // after it returns — the placeholders don't exist yet. Instead we
        // watch the per-domain sync status and enqueue the back-fill when
        // either FK-root domain reaches a terminal state (OkAt for a
        // successful pull; Transient/Permanent so a partial pull that
        // still wrote some placeholders gets them hydrated too). The
        // scheduler gates on a cheap placeholder COUNT and enqueues
        // unique-KEEP, so re-firing across both domains coalesces to one
        // job and a no-placeholder state is a no-op. The Library-screen
        // trigger (LibraryViewModel.init) is the redundant safety net for
        // placeholders that predate this process start.
        initScope.launch {
            val seen = mutableSetOf<String>()
            syncCoordinator.get().status.collect { statuses ->
                val settled = listOf("library", "follows").any { domain ->
                    val s = statuses[domain]
                    val terminal = s is `in`.jphe.storyvox.sync.coordinator.SyncStatus.OkAt ||
                        s is `in`.jphe.storyvox.sync.coordinator.SyncStatus.Transient ||
                        s is `in`.jphe.storyvox.sync.coordinator.SyncStatus.Permanent
                    terminal && seen.add(domain)
                }
                if (settled) {
                    runCatching { metadataBackfillScheduler.get().enqueueIfNeeded() }
                }
            }
        }
        // PR-F (#86) — Mode C flow collector. Started on IO so the
        // PrerenderTriggers + FictionRepository + DataStore graph is
        // materialised off the cold-launch critical path; start()
        // launches its own long-running collector inside the watcher's
        // singleton scope.
        //
        // Issue #931 — join the upgrade-handler Job first. The watcher
        // writes pre-rendered PCM into the same cache root that
        // [VersionUpgradeHandler.runIfUpgraded] wipes on a versionCode
        // change; without the join the two race and the watcher's
        // freshly-written files can be deleted (or its writes can hit
        // an I/O exception mid-sweep). Joining serializes only that
        // one ordering — the watcher still runs on IO and the rest of
        // init stays concurrent.
        initScope.launch {
            upgradeHandlerJob.join()
            prerenderModeWatcher.get().start()
        }
        // Issue #159 — start the widget state observer once the Hilt
        // graph is warm. The observer is a thin coroutine that
        // re-broadcasts when PlaybackController.state changes; doing
        // it on IO means no cold-launch frame budget impact even on
        // P22T-class hardware. Idempotent — re-starting is a no-op.
        initScope.launch {
            widgetStateObserver.get().start()
        }
        // Issue #178 — Royal Road tag-sync. Two coroutines:
        //   1. Schedule the 24h periodic worker (idempotent, KEEP).
        //   2. Watch the RR session state and trigger an immediate
        //      sync on the Anonymous → Authenticated edge so the
        //      first round-trip happens within seconds of sign-in
        //      rather than at the next periodic tick. Subsequent
        //      sign-outs cancel the worker so we don't sit on an
        //      empty schedule.
        initScope.launch {
            tagSyncScheduler.get().schedule()
        }
        initScope.launch {
            var wasAuthed = false
            authRepository.get().sessionState.collect { state ->
                val nowAuthed = state is `in`.jphe.storyvox.data.auth.SessionState.Authenticated
                if (nowAuthed && !wasAuthed) {
                    // Sign-in edge — first sync immediately. Fire-
                    // and-forget; failures are silent (#178 spec).
                    runCatching { tagSyncCoordinator.get().syncNow() }
                }
                if (!nowAuthed && wasAuthed) {
                    // Sign-out — cancel the periodic so we stop
                    // hitting RR with credentialed-but-stale calls.
                    tagSyncScheduler.get().cancel()
                }
                if (nowAuthed && !wasAuthed) {
                    // Re-enqueue on each new sign-in (KEEP no-ops
                    // if one is already scheduled).
                    tagSyncScheduler.get().schedule()
                }
                wasAuthed = nowAuthed
            }
        }
    }

    /**
     * Issue #409 — pre-warm the Hilt data-layer singletons LibraryViewModel
     * depends on, BEFORE the start-destination composition tries to
     * resolve them on the Compose Main dispatcher. The biggest
     * contributor is [StoryvoxDatabase] — Room's `databaseBuilder().build()`
     * opens the SQLite file and validates the schema (running any
     * pending migration) synchronously, and on the Helio P22T tablet
     * that's ~600-900 ms of disk I/O + CPU. Doing it off main pulls
     * that entire cost off the critical path.
     *
     * Once the database is up, we fan out the four key repository
     * singletons (Fiction, Shelf, History/Playback) — Hilt's
     * `DoubleCheck` provider caches each `@Singleton` after first
     * construction, so the main-thread `hiltViewModel<LibraryViewModel>()`
     * resolution just sees a cached `instance != UNINITIALIZED` and
     * skips reconstruction.
     *
     * Fire-and-forget: if the warm-up coroutine fails (no observed
     * failure mode today, but defensive) the foreground request just
     * does the work itself — same code path, same outcome, just on a
     * slower thread.
     */
    private fun warmDataLayer() {
        initScope.launch {
            // Open the database first; everything else depends on it.
            database.get()
            // Then the repos LibraryViewModel @Inject's. Each `.get()`
            // is a no-op after the first, so listing them in any order
            // is fine.
            fictionRepository.get()
            shelfRepository.get()
            playbackPositionRepository.get()
        }
    }

    /**
     * Issue #409 — post-first-frame hook for the VoxSherpa engine-
     * bridge seeds. Idempotent (the [voiceEngineSeeded] latch guards
     * against re-entry from a config-change reattach of MainActivity).
     * Called from [MainActivity] inside a `Choreographer.postFrameCallback`
     * so the .so dlopen happens after the splash has handed off to the
     * Compose surface.
     *
     * The semantics match the pre-#409-round-2 behavior — same two
     * coroutines, same dispatcher, same fields written — just shifted
     * later on the timeline. The user-visible contract ("seed the
     * static fields before the first chapter renders") still holds:
     * tapping a chapter is at minimum a few hundred ms of user-input
     * latency after the first frame, and the seed coroutines complete
     * in well under that window even on the P22T.
     */
    fun seedVoiceEngineFromSettings() {
        if (!voiceEngineSeeded.compareAndSet(false, true)) return
        applyPitchQualityFromSettings()
        applyPerVoiceEngineKnobsFromSettings()
    }

    /**
     * Issue #193 — seed the VoxSherpa engine static `sonicQuality`
     * fields from the user's persisted Settings toggle. The field
     * default in VoxSherpa-TTS v2.7.13 is 1 (high quality), but a
     * user who flipped to OFF in a prior session needs that pref
     * re-applied on cold start, before the first chapter renders.
     */
    private fun applyPitchQualityFromSettings() {
        initScope.launch {
            val highQuality = settingsRepo.get().settings.first().pitchInterpolationHighQuality
            VoiceEngineQualityBridge.applyPitchQuality(highQuality)
        }
    }

    /**
     * Issues #197 + #198 — seed the VoxSherpa engine static fields
     * `voiceLexicon` and `phonemizerLang` from the *active voice's*
     * persisted overrides on cold start. Without this, the first
     * chapter render after process restart uses the engine's built-in
     * lexicon and the voice's native language even if the user had
     * set per-voice overrides in a prior session.
     *
     * Mirrors [applyPitchQualityFromSettings] — fire-and-forget, on
     * IO, no need to await before onCreate() returns because the
     * VoiceManager's first loadModel() always comes after the user
     * interacts with the UI, well after this short flow.first()
     * completes.
     */
    private fun applyPerVoiceEngineKnobsFromSettings() {
        initScope.launch {
            val settings = settingsRepo.get().settings.first()
            VoiceEngineQualityBridge.applyLexicon(settings.effectiveLexicon)
            VoiceEngineQualityBridge.applyPhonemizerLang(settings.effectivePhonemizerLang)
        }
    }

    /**
     * The OkHttp cookie jar is in-memory; persisted cookies live in
     * [AuthRepository] (EncryptedSharedPreferences). On every cold start we
     * pull the saved Cookie header back into the live jar so the next browse
     * / chapter / follows fetch is authed without making the user re-sign-in.
     */
    private fun rehydrateRoyalRoadCookies() {
        initScope.launch {
            val header = authRepository.get().cookieHeader() ?: return@launch
            // Cookie header is "name1=value1; name2=value2; ..." — parse back
            // into the Map<String,String> shape SessionHydrator expects.
            val cookies = header.split("; ")
                .mapNotNull { pair ->
                    val eq = pair.indexOf('=')
                    if (eq <= 0) null else pair.substring(0, eq) to pair.substring(eq + 1)
                }
                .toMap()
            if (cookies.isNotEmpty()) sessionHydrator.get().hydrate(cookies)
        }
    }
}
