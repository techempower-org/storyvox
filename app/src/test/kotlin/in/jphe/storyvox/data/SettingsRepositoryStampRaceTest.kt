package `in`.jphe.storyvox.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Issue #1024 — concurrency regression guard for the per-key stamp /
 * snapshot-baseline store in [SettingsRepositoryUiImpl].
 *
 * Two paths read-modify-write the same DataStore keys
 * (`SYNC_FIELD_STAMPS_KEY` / `SYNC_SNAPSHOT_BASELINE_KEY`):
 *  - **Path A** — a local UI `set*` → `stampSyncedWrite` → `reconcileFieldStamps`,
 *    on whatever coroutine the setter was invoked from.
 *  - **Path B** — a sync pull → `applyStamped`, inside the SyncCoordinator
 *    per-domain mutex (which does NOT cover Path A).
 *
 * Each path does read→read→(modify)→write. Before the fix there was no shared
 * lock, so the two compound sequences could interleave: one path reads, the
 * other runs its full RMW and commits, then the first path commits on top of
 * its now-stale read — clobbering the value/stamp the other just adopted (the
 * cross-device clobber #978 set out to kill).
 *
 * Forcing that interleave deterministically: [GatedStampWriteDataStore] parks
 * the FIRST write that touches the stamps key — by then the parking path has
 * finished its reads. The test drives a precise order:
 *
 *   1. Start Path A (local write) alone. It reads the stamp/baseline store,
 *      then parks at the stamp-write gate — its reads are now frozen-in-time.
 *   2. While A is parked, run Path B (`applyStamped`) to FULL completion: it
 *      reads, adopts the theme value with the old stamp, and commits.
 *   3. Release A. It commits the stamp/baseline it computed back in step 1 —
 *      a read taken BEFORE B existed.
 *
 * On the unguarded code step 3 clobbers B's just-committed baseline: A's
 * baseline has no record of theme="DARK", so the NEXT diff (or A's own write)
 * leaves theme stamped at a value inconsistent with the adopted one — the
 * assertion catches it. With the [stampMutex] guard, step 2's `applyStamped`
 * can't start its RMW at all (it blocks on the mutex A holds across its whole
 * read-modify-write), so the ordering in steps 1-3 is impossible; B only runs
 * after A commits a consistent baseline → assertion passes.
 *
 * Verified to FAIL on the pre-fix code and pass with the guard, by reverting
 * the fix while keeping the test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryStampRaceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var rawStore: DataStore<Preferences>

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val file = File(tempFolder.newFolder(), "storyvox_settings.preferences_pb")
        rawStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    /**
     * Parks the first stamp-touching write OUTSIDE the delegate's write actor
     * (so the other path's writes still commit during the park). It peeks the
     * current state, runs the caller's transform on a copy to see whether the
     * stamps key changes, and if so — and the gate is still armed — signals
     * [firstStampWriteReached] and awaits [release] before delegating.
     */
    private class GatedStampWriteDataStore(
        private val delegate: DataStore<Preferences>,
    ) : DataStore<Preferences> {
        private val stampsKey = stringPreferencesKey("instantdb.settings_field_stamps_v1")
        val firstStampWriteReached = CompletableDeferred<Unit>()
        private val releaseGate = CompletableDeferred<Unit>()
        private val armed = AtomicBoolean(true)

        fun release() {
            if (!releaseGate.isCompleted) releaseGate.complete(Unit)
        }

        override val data: Flow<Preferences> get() = delegate.data

        override suspend fun updateData(
            transform: suspend (Preferences) -> Preferences,
        ): Preferences {
            val before = delegate.data.first()
            val after = transform(before)
            val touchesStamps = after[stampsKey] != before[stampsKey]
            if (touchesStamps && armed.compareAndSet(true, false)) {
                firstStampWriteReached.complete(Unit)
                releaseGate.await()
            }
            // Re-run the caller's transform inside the real update so the
            // commit is atomic against the live state (the peek above was only
            // to classify the write; DataStore re-applies transform anyway).
            return delegate.updateData(transform)
        }
    }

    private fun makeRepo(store: DataStore<Preferences>): SettingsRepositoryUiImpl {
        val pcmBundle = makeFakeCacheBundle(tempFolder.newFolder("pcm_bundle"), scope)
        return SettingsRepositoryUiImpl(
            store = store,
            auth = FakeAuth(),
            hydrator = FakeHydrator(),
            palaceConfig = makeFakePalaceConfig(tempFolder.newFolder("palace_ds"), scope),
            palaceApi = makeFakePalaceApi(),
            llmCreds = `in`.jphe.storyvox.llm.LlmCredentialsStore.forTesting(),
            githubAuth = FakeGitHubAuth(),
            teamsAuth = fakeTeamsAuth(),
            rssConfig = makeFakeRssConfig(tempFolder.newFolder("rss_ds"), scope),
            epubConfig = makeFakeEpubConfig(tempFolder.newFolder("epub_ds"), scope),
            pdfConfig = makeFakePdfConfig(tempFolder.newFolder("pdf_ds"), scope),
            outlineConfig = makeFakeOutlineConfig(tempFolder.newFolder("outline_ds"), scope),
            wikipediaConfig = makeFakeWikipediaConfig(tempFolder.newFolder("wiki_ds"), scope),
            notionConfig = makeFakeNotionConfig(tempFolder.newFolder("notion_ds"), scope),
            discordConfig = makeFakeDiscordConfig(tempFolder.newFolder("discord_ds"), scope),
            discordGuildDirectory = makeFakeDiscordGuildDirectory(),
            telegramConfig = makeFakeTelegramConfig(tempFolder.newFolder("telegram_ds"), scope),
            telegramChannelDirectory = makeFakeTelegramChannelDirectory(),
            suggestedFeedsRegistry = SuggestedFeedsRegistry(),
            azureCreds = makeFakeAzureCredentials(),
            azureClient = makeFakeAzureClient(),
            azureRoster = makeFakeAzureRoster(),
            googleTokenSource =
                `in`.jphe.storyvox.llm.auth.GoogleOAuthTokenSource(okhttp3.OkHttpClient()),
            pcmCache = pcmBundle.pcmCache,
            pcmCacheConfig = pcmBundle.pcmCacheConfig,
            cacheStats = pcmBundle.cacheStats,
            pushSettings = { },
            pushDispatcher = StandardTestDispatcher(),
        )
    }

    @Test(timeout = 60_000)
    fun `concurrent local write and applyStamped do not corrupt the adopted stamp`() {
        val gated = GatedStampWriteDataStore(rawStore)
        val repo = makeRepo(gated)

        val pool = Executors.newFixedThreadPool(2)
        val raceDispatcher = pool.asCoroutineDispatcher()
        try {
            runBlocking {
                val themeKey = "settings.pref_theme_override"
                // The theme key is ONLY ever written by applyStamped, always
                // with this fixed OLD stamp. A consistent baseline keeps it
                // here; if a racing Path A diffs theme against a baseline that
                // doesn't record the adopted value, it re-stamps theme to a
                // fresh now (>> incomingStamp), which the assertion catches.
                val incomingStamp = 1_000L

                // Step 1 — Path A (local edit of a DIFFERENT key) starts first
                // and parks at its stamp-write gate with its reads frozen.
                val localWrite = async(raceDispatcher) {
                    repo.setSourceDisplayOrder(listOf("ao3", "royalroad"))
                }
                gated.firstStampWriteReached.await()

                // Step 2 — with A parked, kick off Path B (remote merge adopts
                // theme with the old stamp). DON'T await it here: under the fix
                // B blocks on the mutex A holds, so awaiting would deadlock.
                // Instead give it a generous window to run (it completes only
                // on the UNGUARDED code, where A doesn't hold a lock).
                val pull = async(raceDispatcher) {
                    repo.applyStamped(
                        snapshot = mapOf(themeKey to "DARK"),
                        stamps = mapOf(themeKey to incomingStamp),
                    )
                }
                delay(500L)

                // Step 3 — release A so it commits the stamp/baseline it read
                // back in step 1. On unguarded code that clobbers B's adopted
                // theme baseline; under the guard B never got to run, so A
                // commits clean and B runs afterwards.
                gated.release()

                // Both must finish (bounded, so a real deadlock fails loudly
                // rather than masquerading as a pass).
                val finished = withTimeoutOrNull(20_000L) {
                    awaitAll(localWrite, pull)
                    true
                }
                assertEquals("both paths must complete (no deadlock)", true, finished)

                assertEquals(
                    "the applyStamped-adopted theme key was re-stamped by a racing " +
                        "local write — the stamp/baseline read-modify-write interleaved " +
                        "(regression for #1024). Expected the incoming stamp, got a fresh now.",
                    incomingStamp,
                    repo.fieldStamps()[themeKey],
                )
                assertEquals(
                    "theme value must remain the remote-adopted one",
                    "DARK",
                    repo.snapshot()[themeKey],
                )
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
