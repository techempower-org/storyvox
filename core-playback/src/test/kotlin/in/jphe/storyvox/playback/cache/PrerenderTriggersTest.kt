package `in`.jphe.storyvox.playback.cache

import `in`.jphe.storyvox.data.db.entity.ChapterDownloadState
import `in`.jphe.storyvox.data.db.entity.PlaybackPosition
import `in`.jphe.storyvox.data.repository.CachedBodyUsage
import `in`.jphe.storyvox.data.repository.ChapterRepository
import `in`.jphe.storyvox.data.repository.ContinueListeningEntry
import `in`.jphe.storyvox.data.repository.PlaybackPositionRepository
import `in`.jphe.storyvox.data.repository.playback.PlaybackChapter
import `in`.jphe.storyvox.data.repository.playback.PlaybackModeConfig
import `in`.jphe.storyvox.data.repository.playback.PrerenderChapterCountConfig
import `in`.jphe.storyvox.data.repository.playback.RecentItem
import `in`.jphe.storyvox.data.repository.playback.SavedPosition
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.ChapterInfo
import `in`.jphe.storyvox.playback.PowerSaveMonitor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Unit tests for [PrerenderTriggers] schedule + cancel semantics. Uses
 * fakes for [PcmRenderScheduler], [ChapterRepository], and
 * [PlaybackModeConfig] so the tests run without WorkManager or any
 * actual engine. RobolectricTestRunner is needed because the triggers
 * call `android.util.Log.i` on the schedule paths (unmocked by default
 * in pure-JVM JUnit tests) — Robolectric provides a real shadowed
 * implementation. The other core-playback unit tests follow the same
 * pattern (see VoiceManagerTest).
 *
 * PR F of the PCM cache series (#86).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PrerenderTriggersTest {

    /** Recording fake — captures every scheduleRender / cancel call. */
    private class RecordingScheduler : PcmRenderScheduler {
        val scheduled = mutableListOf<Pair<String, String>>()
        val cancelledChapters = mutableListOf<String>()
        val cancelledFiction = mutableListOf<String>()

        override fun scheduleRender(fictionId: String, chapterId: String) {
            scheduled += fictionId to chapterId
        }
        override fun cancelRender(chapterId: String) {
            cancelledChapters += chapterId
        }
        override fun cancelAllForFiction(fictionId: String) {
            cancelledFiction += fictionId
        }
    }

    private class FakeModeConfig(
        full: Boolean = false,
    ) : PlaybackModeConfig {
        override val warmupWait = flowOf(false)
        override val catchupPause = flowOf(true)
        private val fullState = MutableStateFlow(full)
        override val fullPrerender: Flow<Boolean> = fullState.asStateFlow()
        override suspend fun currentWarmupWait() = false
        override suspend fun currentCatchupPause() = true
        override suspend fun currentFullPrerender() = fullState.value
    }

    /**
     * Issue #596 — fake [PrerenderChapterCountConfig] for tests.
     * Default `count` mirrors [PrerenderTriggers.DEFAULT_PRERENDER_CHAPTERS]
     * so pre-#596 tests keep their assertions verbatim.
     */
    private class FakePrerenderCount(
        private val count: Int = PrerenderTriggers.DEFAULT_PRERENDER_CHAPTERS,
    ) : PrerenderChapterCountConfig {
        override val prerenderChapterCount: Flow<Int> = flowOf(count)
        override suspend fun currentPrerenderChapterCount(): Int = count
    }

    /** Minimal ChapterRepo fake — implements just enough surface for
     *  PrerenderTriggers to navigate the reading-order graph. */
    private open class FakeChapterRepo(
        private val byFiction: Map<String, List<ChapterInfo>>,
    ) : ChapterRepository {
        /** Issue #558 — recorded queueChapterDownload calls so tests
         *  can assert what onChapterOpened prefetched. Records the
         *  (fictionId, chapterId, requireUnmetered) triple. */
        val downloadsQueued = mutableListOf<Triple<String, String, Boolean>>()

        override fun observeChapters(fictionId: String): Flow<List<ChapterInfo>> =
            flowOf(byFiction[fictionId].orEmpty())
        override fun observeChapter(chapterId: String): Flow<ChapterContent?> =
            flowOf(null)
        override fun observeDownloadState(fictionId: String): Flow<Map<String, ChapterDownloadState>> =
            flowOf(emptyMap())
        override fun observePlayedChapterIds(fictionId: String): Flow<Set<String>> =
            flowOf(emptySet())
        override suspend fun queueChapterDownload(
            fictionId: String, chapterId: String, requireUnmetered: Boolean,
        ) {
            downloadsQueued += Triple(fictionId, chapterId, requireUnmetered)
        }
        override suspend fun queueAllMissing(fictionId: String, requireUnmetered: Boolean) = Unit
        override suspend fun markRead(chapterId: String, read: Boolean) = Unit
        override suspend fun markChapterPlayed(chapterId: String) = Unit
        override suspend fun trimDownloadedBodies(fictionId: String, keepLast: Int) = Unit
        /** Default returns a PlaybackChapter whose fictionId is the parent
         *  fiction key (resolved by reverse lookup from chapter id). Tests
         *  that need a null body can override. */
        override suspend fun getChapter(id: String): PlaybackChapter? {
            val owner = byFiction.entries.firstOrNull { (_, chapters) ->
                chapters.any { it.id == id }
            } ?: return null
            return PlaybackChapter(
                id = id,
                fictionId = owner.key,
                text = "body for $id",
                title = "Title $id",
                bookTitle = "Book ${owner.key}",
                coverUrl = null,
            )
        }
        override suspend fun getNextChapterId(currentChapterId: String): String? {
            // Walk the flat reading order across all fictions — sufficient
            // for the single-fiction test cases.
            val all = byFiction.values.flatten()
            val idx = all.indexOfFirst { it.id == currentChapterId }
            return all.getOrNull(idx + 1)?.id
        }
        override suspend fun getPreviousChapterId(currentChapterId: String): String? {
            val all = byFiction.values.flatten()
            val idx = all.indexOfFirst { it.id == currentChapterId }
            return all.getOrNull(idx - 1)?.id
        }
        override suspend fun cachedBodyUsage(): CachedBodyUsage =
            CachedBodyUsage(count = 0, bytesEstimate = 0L)
        override suspend fun setChapterBookmark(chapterId: String, charOffset: Int?) = Unit
        override suspend fun chapterBookmark(chapterId: String): Int? = null
    }

    /**
     * Issue #557 — FakePositionRepo for PrerenderTriggers's resume-aware
     * scheduling. Only the `load(fictionId)` path is exercised in
     * production from PrerenderTriggers, but the full interface is
     * stubbed to keep the fake type-safe across future refactors.
     */
    private class FakePositionRepo(
        private val positions: Map<String, SavedPosition> = emptyMap(),
    ) : PlaybackPositionRepository {
        override fun observePosition(fictionId: String): kotlinx.coroutines.flow.Flow<PlaybackPosition?> =
            flowOf(null)
        override fun observeMostRecentContinueListening(): kotlinx.coroutines.flow.Flow<ContinueListeningEntry?> =
            flowOf(null)
        override fun observeLastPlayedMap(): kotlinx.coroutines.flow.Flow<Map<String, Long>> =
            flowOf(emptyMap())
        override suspend fun savePosition(
            fictionId: String,
            chapterId: String,
            charOffset: Int,
            paragraphIndex: Int,
            playbackSpeed: Float,
        ) = Unit
        override suspend fun clearPosition(fictionId: String) = Unit
        override suspend fun save(
            fictionId: String,
            chapterId: String,
            charOffset: Int,
            durationEstimateMs: Long,
        ) = Unit
        override suspend fun load(fictionId: String): SavedPosition? = positions[fictionId]
        override suspend fun recent(limit: Int): List<RecentItem> = emptyList()
    }

    /** Issue #801 — power-save monitor routed through Robolectric's
     *  application context. Default state is OFF so existing tests
     *  preserve their pre-#801 behavior. */
    private val powerSaveMonitor: PowerSaveMonitor by lazy {
        PowerSaveMonitor(RuntimeEnvironment.getApplication())
    }

    private fun chapter(num: Int, fictionPrefix: String) = ChapterInfo(
        id = "$fictionPrefix-c$num",
        sourceChapterId = "src-$num",
        index = num,
        title = "Chapter $num",
    )

    @Test
    fun `onLibraryAdded enqueues first DEFAULT chapters when fullPrerender off`() = runBlocking {
        val scheduler = RecordingScheduler()
        // Issue #558 — DEFAULT_PRERENDER_CHAPTERS is 5 (up from 3); fiction
        // has 7 chapters so the slice is non-trivial.
        val repo = FakeChapterRepo(mapOf("f1" to (1..7).map { chapter(it, "f1") }))
        val triggers = PrerenderTriggers(scheduler, repo, FakeModeConfig(full = false), FakePositionRepo(), FakePrerenderCount(), powerSaveMonitor)

        triggers.onLibraryAdded("f1")

        assertEquals(PrerenderTriggers.DEFAULT_PRERENDER_CHAPTERS, scheduler.scheduled.size)
        assertEquals(
            listOf("f1-c1", "f1-c2", "f1-c3", "f1-c4", "f1-c5"),
            scheduler.scheduled.map { it.second },
        )
        assertTrue(
            "every scheduled render carries the source fictionId",
            scheduler.scheduled.all { it.first == "f1" },
        )
    }

    @Test
    fun `onLibraryAdded enqueues all chapters when fullPrerender on`() = runBlocking {
        val scheduler = RecordingScheduler()
        val repo = FakeChapterRepo(mapOf("f1" to (1..5).map { chapter(it, "f1") }))
        val triggers = PrerenderTriggers(scheduler, repo, FakeModeConfig(full = true), FakePositionRepo(), FakePrerenderCount(), powerSaveMonitor)

        triggers.onLibraryAdded("f1")

        assertEquals(5, scheduler.scheduled.size)
        assertEquals(
            listOf("f1-c1", "f1-c2", "f1-c3", "f1-c4", "f1-c5"),
            scheduler.scheduled.map { it.second },
        )
    }

    @Test
    fun `onLibraryAdded with fewer than DEFAULT chapters caps at chapters size`() = runBlocking {
        val scheduler = RecordingScheduler()
        // Fiction with only 2 chapters; default limit is 3 but we shouldn't
        // try to schedule beyond what exists.
        val repo = FakeChapterRepo(mapOf("f1" to (1..2).map { chapter(it, "f1") }))
        val triggers = PrerenderTriggers(scheduler, repo, FakeModeConfig(full = false), FakePositionRepo(), FakePrerenderCount(), powerSaveMonitor)

        triggers.onLibraryAdded("f1")

        assertEquals(2, scheduler.scheduled.size)
    }

    @Test
    fun `onLibraryAdded with empty chapter list is a no-op`() = runBlocking {
        val scheduler = RecordingScheduler()
        val repo = FakeChapterRepo(emptyMap())
        val triggers = PrerenderTriggers(scheduler, repo, FakeModeConfig(full = false), FakePositionRepo(), FakePrerenderCount(), powerSaveMonitor)

        triggers.onLibraryAdded("f1")

        assertTrue("nothing scheduled for empty fiction", scheduler.scheduled.isEmpty())
    }

    @Test
    fun `onLibraryRemoved cancels every render for that fiction`() {
        val scheduler = RecordingScheduler()
        val triggers = PrerenderTriggers(
            scheduler,
            FakeChapterRepo(emptyMap()),
            FakeModeConfig(),
            FakePositionRepo(),
            FakePrerenderCount(),
            powerSaveMonitor,
        )

        triggers.onLibraryRemoved("f1")

        assertEquals(listOf("f1"), scheduler.cancelledFiction)
    }

    @Test
    fun `onChapterCompleted schedules N+2`() = runBlocking {
        val scheduler = RecordingScheduler()
        // Stub getChapter so the next-next lookup returns a real
        // PlaybackChapter with the right fictionId.
        val repo = object : FakeChapterRepo(
            mapOf("f1" to (1..5).map { chapter(it, "f1") }),
        ) {
            override suspend fun getChapter(id: String): PlaybackChapter? =
                if (id == "f1-c3") PlaybackChapter(
                    id = "f1-c3",
                    fictionId = "f1",
                    text = "body",
                    title = "Chapter 3",
                    bookTitle = "F1",
                    coverUrl = null,
                ) else null
        }
        val triggers = PrerenderTriggers(scheduler, repo, FakeModeConfig(), FakePositionRepo(), FakePrerenderCount(), powerSaveMonitor)

        // Just-completed = c1 → next = c2 → next-next = c3 → schedule c3.
        triggers.onChapterCompleted("f1-c1")

        assertEquals(1, scheduler.scheduled.size)
        assertEquals("f1" to "f1-c3", scheduler.scheduled.first())
    }

    @Test
    fun `onChapterCompleted at penultimate chapter is a no-op`() = runBlocking {
        val scheduler = RecordingScheduler()
        // f1 has 3 chapters; completing c2 → next is c3 → next-next is null.
        val repo = FakeChapterRepo(mapOf("f1" to (1..3).map { chapter(it, "f1") }))
        val triggers = PrerenderTriggers(scheduler, repo, FakeModeConfig(), FakePositionRepo(), FakePrerenderCount(), powerSaveMonitor)

        triggers.onChapterCompleted("f1-c2")

        assertTrue("no N+2 to schedule at end of fiction", scheduler.scheduled.isEmpty())
    }

    @Test
    fun `onChapterCompleted at last chapter is a no-op`() = runBlocking {
        val scheduler = RecordingScheduler()
        val repo = FakeChapterRepo(mapOf("f1" to (1..3).map { chapter(it, "f1") }))
        val triggers = PrerenderTriggers(scheduler, repo, FakeModeConfig(), FakePositionRepo(), FakePrerenderCount(), powerSaveMonitor)

        triggers.onChapterCompleted("f1-c3")

        assertTrue("no next at end of fiction", scheduler.scheduled.isEmpty())
    }

    @Test
    fun `onFullPrerenderEnabled enqueues every chapter of every fiction`() = runBlocking {
        val scheduler = RecordingScheduler()
        val repo = FakeChapterRepo(
            mapOf(
                "f1" to (1..3).map { chapter(it, "f1") },
                "f2" to (1..4).map { chapter(it, "f2") },
            ),
        )
        val triggers = PrerenderTriggers(scheduler, repo, FakeModeConfig(), FakePositionRepo(), FakePrerenderCount(), powerSaveMonitor)

        triggers.onFullPrerenderEnabled(listOf("f1", "f2"))

        // 3 + 4 = 7 renders across both fictions.
        assertEquals(7, scheduler.scheduled.size)
        val fictions = scheduler.scheduled.map { it.first }.toSet()
        assertEquals(setOf("f1", "f2"), fictions)
    }

    // ─── Issue #557 — resume-anchored scheduling ─────────────────────

    @Test
    fun `onLibraryAdded with resume on chapter 4 schedules chapters 5 through 9 not 1-2-3`() = runBlocking {
        val scheduler = RecordingScheduler()
        val repo = FakeChapterRepo(mapOf("f1" to (1..10).map { chapter(it, "f1") }))
        // User is mid-series — last persisted position is chapter 4. The
        // pre-render window should start at chapter 5 (next chapter), not
        // chapter 1 (default). This is the audit's "Bug 3 — pre-render
        // targets the WRONG chapter on resume" scenario.
        //
        // Issue #558 — DEFAULT_PRERENDER_CHAPTERS = 5 so window is 5..9.
        val positions = FakePositionRepo(
            mapOf(
                "f1" to SavedPosition(
                    fictionId = "f1",
                    chapterId = "f1-c4",
                    charOffset = 1_234,
                    durationEstimateMs = 0L,
                ),
            ),
        )
        val triggers = PrerenderTriggers(scheduler, repo, FakeModeConfig(full = false), positions, FakePrerenderCount(), powerSaveMonitor)

        triggers.onLibraryAdded("f1")

        assertEquals(
            "pre-render window anchors on resume chapter + 1",
            listOf("f1-c5", "f1-c6", "f1-c7", "f1-c8", "f1-c9"),
            scheduler.scheduled.map { it.second },
        )
    }

    @Test
    fun `onLibraryAdded with no resume position falls back to first DEFAULT chapters`() = runBlocking {
        val scheduler = RecordingScheduler()
        val repo = FakeChapterRepo(mapOf("f1" to (1..10).map { chapter(it, "f1") }))
        // First-time add, no prior position. Should match the legacy
        // pre-#557 behavior — pre-render from the start.
        val triggers = PrerenderTriggers(scheduler, repo, FakeModeConfig(full = false), FakePositionRepo(), FakePrerenderCount(), powerSaveMonitor)

        triggers.onLibraryAdded("f1")

        assertEquals(
            listOf("f1-c1", "f1-c2", "f1-c3", "f1-c4", "f1-c5"),
            scheduler.scheduled.map { it.second },
        )
    }

    @Test
    fun `onLibraryAdded with resume position on last chapter is a no-op`() = runBlocking {
        val scheduler = RecordingScheduler()
        val repo = FakeChapterRepo(mapOf("f1" to (1..5).map { chapter(it, "f1") }))
        // Resume = chapter 5 (the last one). There's no N+1 to pre-render.
        val positions = FakePositionRepo(
            mapOf(
                "f1" to SavedPosition(
                    fictionId = "f1",
                    chapterId = "f1-c5",
                    charOffset = 999,
                    durationEstimateMs = 0L,
                ),
            ),
        )
        val triggers = PrerenderTriggers(scheduler, repo, FakeModeConfig(full = false), positions, FakePrerenderCount(), powerSaveMonitor)

        triggers.onLibraryAdded("f1")

        assertTrue(
            "no chapters left to pre-render at end of fiction",
            scheduler.scheduled.isEmpty(),
        )
    }

    @Test
    fun `onLibraryAdded with stale resume chapter id falls back to first DEFAULT chapters`() = runBlocking {
        val scheduler = RecordingScheduler()
        val repo = FakeChapterRepo(mapOf("f1" to (1..7).map { chapter(it, "f1") }))
        // Resume chapter id doesn't exist in the current chapter list —
        // upstream re-numbered, source restored, or row got corrupted.
        // The fallback must NOT crash and MUST schedule something useful.
        //
        // Issue #558 — DEFAULT_PRERENDER_CHAPTERS = 5 so fallback covers
        // chapters 1..5.
        val positions = FakePositionRepo(
            mapOf(
                "f1" to SavedPosition(
                    fictionId = "f1",
                    chapterId = "f1-c-deleted",
                    charOffset = 0,
                    durationEstimateMs = 0L,
                ),
            ),
        )
        val triggers = PrerenderTriggers(scheduler, repo, FakeModeConfig(full = false), positions, FakePrerenderCount(), powerSaveMonitor)

        triggers.onLibraryAdded("f1")

        assertEquals(
            listOf("f1-c1", "f1-c2", "f1-c3", "f1-c4", "f1-c5"),
            scheduler.scheduled.map { it.second },
        )
    }

    // ─── Issue #558 — onChapterOpened body prefetch ──────────────────

    @Test
    fun `onChapterOpened queues body downloads for next BODY_PREFETCH_LOOKAHEAD chapters`() = runBlocking {
        val scheduler = RecordingScheduler()
        val repo = FakeChapterRepo(mapOf("f1" to (1..10).map { chapter(it, "f1") }))
        val triggers = PrerenderTriggers(scheduler, repo, FakeModeConfig(full = false), FakePositionRepo(), FakePrerenderCount(), powerSaveMonitor)

        // User just opened chapter 4 — prefetch should pull chapters 5, 6, 7.
        triggers.onChapterOpened("f1", "f1-c4")

        assertEquals(
            PrerenderTriggers.BODY_PREFETCH_LOOKAHEAD,
            repo.downloadsQueued.size,
        )
        assertEquals(
            listOf("f1-c5", "f1-c6", "f1-c7"),
            repo.downloadsQueued.map { it.second },
        )
        assertTrue(
            "every prefetch carries the right fictionId",
            repo.downloadsQueued.all { it.first == "f1" },
        )
        assertTrue(
            "prefetch uses requireUnmetered=false so it never stalls on metered networks",
            repo.downloadsQueued.all { !it.third },
        )
    }

    @Test
    fun `onChapterOpened near end of fiction prefetches only what exists`() = runBlocking {
        val scheduler = RecordingScheduler()
        // 5-chapter fiction; user just opened chapter 3 → only c4, c5
        // exist downstream, so the prefetch should cap at 2.
        val repo = FakeChapterRepo(mapOf("f1" to (1..5).map { chapter(it, "f1") }))
        val triggers = PrerenderTriggers(scheduler, repo, FakeModeConfig(full = false), FakePositionRepo(), FakePrerenderCount(), powerSaveMonitor)

        triggers.onChapterOpened("f1", "f1-c3")

        assertEquals(
            listOf("f1-c4", "f1-c5"),
            repo.downloadsQueued.map { it.second },
        )
    }

    @Test
    fun `onChapterOpened at last chapter is a no-op`() = runBlocking {
        val scheduler = RecordingScheduler()
        val repo = FakeChapterRepo(mapOf("f1" to (1..3).map { chapter(it, "f1") }))
        val triggers = PrerenderTriggers(scheduler, repo, FakeModeConfig(full = false), FakePositionRepo(), FakePrerenderCount(), powerSaveMonitor)

        triggers.onChapterOpened("f1", "f1-c3")

        assertTrue(
            "no chapters left to prefetch at end of fiction",
            repo.downloadsQueued.isEmpty(),
        )
    }

    @Test
    fun `onChapterOpened queues prefetch even when chapter bodies are undownloaded`() = runBlocking {
        val scheduler = RecordingScheduler()
        // Critical regression case for #558 — at first chapter open the
        // upcoming chapters' bodies are NOT yet in the cache. The fix
        // must NOT depend on getChapter() returning non-null for the
        // prefetch target, because that's exactly the state where the
        // prefetch is most needed.
        val repo = object : FakeChapterRepo(mapOf("f1" to (1..6).map { chapter(it, "f1") })) {
            override suspend fun getChapter(id: String): PlaybackChapter? = null
        }
        val triggers = PrerenderTriggers(scheduler, repo, FakeModeConfig(full = false), FakePositionRepo(), FakePrerenderCount(), powerSaveMonitor)

        triggers.onChapterOpened("f1", "f1-c1")

        assertEquals(
            "must queue all three look-ahead bodies even when no chapter has a body yet",
            listOf("f1-c2", "f1-c3", "f1-c4"),
            repo.downloadsQueued.map { it.second },
        )
    }

    @Test
    fun `onLibraryAdded with resume + fullPrerender ignores resume and schedules everything`() =
        runBlocking {
            val scheduler = RecordingScheduler()
            val repo = FakeChapterRepo(mapOf("f1" to (1..5).map { chapter(it, "f1") }))
            // Mode C means "render every chapter"; the resume slice
            // semantics don't apply — user wants the whole fiction cached.
            val positions = FakePositionRepo(
                mapOf(
                    "f1" to SavedPosition(
                        fictionId = "f1",
                        chapterId = "f1-c3",
                        charOffset = 0,
                        durationEstimateMs = 0L,
                    ),
                ),
            )
            val triggers = PrerenderTriggers(scheduler, repo, FakeModeConfig(full = true), positions, FakePrerenderCount(), powerSaveMonitor)

            triggers.onLibraryAdded("f1")

            assertEquals(5, scheduler.scheduled.size)
            assertEquals(
                listOf("f1-c1", "f1-c2", "f1-c3", "f1-c4", "f1-c5"),
                scheduler.scheduled.map { it.second },
            )
        }

    // ─── Issue #801 — power-save mode suppression ───────────────────

    @Test
    fun `onLibraryAdded skips scheduling when power save mode is on`() = runBlocking {
        val scheduler = RecordingScheduler()
        val repo = FakeChapterRepo(mapOf("f1" to (1..7).map { chapter(it, "f1") }))
        // Set power-save mode ON via Robolectric's PowerManager shadow.
        val context = RuntimeEnvironment.getApplication()
        val pm = context.getSystemService(android.content.Context.POWER_SERVICE)
            as android.os.PowerManager
        org.robolectric.Shadows.shadowOf(pm).setIsPowerSaveMode(true)
        val psm = PowerSaveMonitor(context)
        val triggers = PrerenderTriggers(
            scheduler, repo, FakeModeConfig(full = false),
            FakePositionRepo(), FakePrerenderCount(), psm,
        )

        triggers.onLibraryAdded("f1")

        assertTrue(
            "no renders scheduled when power save mode is on",
            scheduler.scheduled.isEmpty(),
        )
    }

    @Test
    fun `onChapterCompleted skips scheduling when power save mode is on`() = runBlocking {
        val scheduler = RecordingScheduler()
        val repo = object : FakeChapterRepo(
            mapOf("f1" to (1..5).map { chapter(it, "f1") }),
        ) {
            override suspend fun getChapter(id: String): PlaybackChapter? =
                if (id == "f1-c3") PlaybackChapter(
                    id = "f1-c3", fictionId = "f1", text = "body",
                    title = "Chapter 3", bookTitle = "F1", coverUrl = null,
                ) else null
        }
        val context = RuntimeEnvironment.getApplication()
        val pm = context.getSystemService(android.content.Context.POWER_SERVICE)
            as android.os.PowerManager
        org.robolectric.Shadows.shadowOf(pm).setIsPowerSaveMode(true)
        val psm = PowerSaveMonitor(context)
        val triggers = PrerenderTriggers(
            scheduler, repo, FakeModeConfig(),
            FakePositionRepo(), FakePrerenderCount(), psm,
        )

        triggers.onChapterCompleted("f1-c1")

        assertTrue(
            "no renders scheduled when power save mode is on",
            scheduler.scheduled.isEmpty(),
        )
    }

    @Test
    fun `onFullPrerenderEnabled skips scheduling when power save mode is on`() = runBlocking {
        val scheduler = RecordingScheduler()
        val repo = FakeChapterRepo(
            mapOf("f1" to (1..3).map { chapter(it, "f1") }),
        )
        val context = RuntimeEnvironment.getApplication()
        val pm = context.getSystemService(android.content.Context.POWER_SERVICE)
            as android.os.PowerManager
        org.robolectric.Shadows.shadowOf(pm).setIsPowerSaveMode(true)
        val psm = PowerSaveMonitor(context)
        val triggers = PrerenderTriggers(
            scheduler, repo, FakeModeConfig(),
            FakePositionRepo(), FakePrerenderCount(), psm,
        )

        triggers.onFullPrerenderEnabled(listOf("f1"))

        assertTrue(
            "no renders scheduled when power save mode is on",
            scheduler.scheduled.isEmpty(),
        )
    }
}
