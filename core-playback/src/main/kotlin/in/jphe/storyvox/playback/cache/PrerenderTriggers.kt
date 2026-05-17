package `in`.jphe.storyvox.playback.cache

import android.util.Log
import `in`.jphe.storyvox.data.repository.ChapterRepository
import `in`.jphe.storyvox.data.repository.FictionLibraryListener
import `in`.jphe.storyvox.data.repository.PlaybackPositionRepository
import `in`.jphe.storyvox.data.repository.playback.PlaybackModeConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Trigger glue for the PCM cache pre-render scheduler. Sits between
 * trigger sources (FictionRepository, EnginePlayer, the `:app`-level
 * fullPrerender flow collector) and the scheduler so none of those
 * callers needs to know about WorkManager.
 *
 * Trigger semantics per spec (see
 * `docs/superpowers/specs/2026-05-07-pcm-cache-design.md` § "Trigger
 * points"):
 *
 *  - **Library add** ([onLibraryAdded]) — schedule renders for the
 *    first [DEFAULT_PRERENDER_CHAPTERS] chapters in reading order, OR
 *    all chapters if Mode C ([PlaybackModeConfig.fullPrerender]) is
 *    on.
 *  - **Chapter complete** ([onChapterCompleted]) — schedule a render
 *    of chapter N+2 in reading order, where N is the just-completed
 *    chapter. N+1 is usually already cached or in flight from the
 *    previous chapter-completed trigger or from PR-D's foreground
 *    tee while the user is actively listening.
 *  - **Fiction removed** ([onLibraryRemoved]) — cancel all scheduled
 *    renders for that fiction (via the `pcm-render-fiction-<id>` tag).
 *  - **Mode C flow flip** false → true ([onFullPrerenderEnabled]) —
 *    re-evaluate every library fiction and enqueue any not-yet-cached
 *    chapters. Called from `:app`'s flow collector (PrerenderModeWatcher)
 *    so this class doesn't need to take a FictionRepository dependency.
 *
 * Implements [FictionLibraryListener] (in :core-data) — :app's
 * CacheBindingsModule binds this class as the listener so
 * FictionRepository's addToLibrary / removeFromLibrary can dispatch
 * without depending on :core-playback directly.
 *
 * NOT in this PR: cancel a render when the user is now actively
 * playing the same (chapter, voice) key via the streaming source.
 * The streaming source's tee writes to the SAME cache key; PR-D's
 * appender's resume detection (meta exists, idx absent) means the
 * foreground tee will collide with a worker that finalized first.
 * Spec calls this "mutual exclusion contract"; PR-F's first cut
 * handles it via the engineMutex (worker can't generate while
 * foreground holds the mutex; serialization is at sentence
 * granularity, effectively the worker pauses between sentences while
 * foreground is active). A future cleanup adds explicit
 * [PcmRenderScheduler.cancelRender] calls from
 * EnginePlayer.startPlaybackPipeline for the matching key.
 *
 * PR F of the PCM cache series (#86).
 */
@Singleton
class PrerenderTriggers @Inject constructor(
    private val scheduler: PcmRenderScheduler,
    private val chapterRepo: ChapterRepository,
    private val modeConfig: PlaybackModeConfig,
    /**
     * Issue #557 (stuck-state-fixer batch) — used by [onLibraryAdded] to
     * anchor pre-render scheduling on the user's resume chapter instead
     * of always starting at chapter 0. When a fiction is re-added mid-
     * series (manual unfollow → re-follow, or the future "imported from
     * backup" flow), the user has likely already read several chapters
     * and the relevant warming window is N+1..N+3, not 1..3.
     *
     * Optional dependency by Singleton-graph: PlaybackPositionRepository
     * is always present in production (`:core-data` Hilt module). Tests
     * can pass a fake (see PrerenderTriggersTest).
     */
    private val positionRepo: PlaybackPositionRepository,
) : FictionLibraryListener {

    /**
     * Library-add: schedule the next [DEFAULT_PRERENDER_CHAPTERS] chapters
     * in reading order STARTING FROM the user's resume position (per
     * [PlaybackPositionRepository]), or chapter 0 when no resume row
     * exists yet (first-time add). Order matches reading order from
     * [ChapterRepository.observeChapters] — first emission is the
     * current snapshot, sorted by chapter index.
     *
     * Issue #557 — pre-fix this always took chapters[0..3], which meant
     * re-adding a mid-series fiction (e.g. user is at chapter 04 of a
     * 20-chapter Royal Road) burned the worker on rendering chapter 01
     * while the listener actually needs chapter 05 next. The user
     * complained: "Pre-render targets the WRONG chapter — when resuming
     * a fiction mid-series the cache pre-render starts on chapter 01
     * instead of chapter N+1."
     *
     * The fix anchors the slice on the resume chapter index:
     *  - No resume position → take chapters[0..N] (legacy behavior;
     *    fresh add of a never-played fiction).
     *  - Resume position present + resume chapter found in list →
     *    take chapters from (resumeIndex + 1) for N entries. The user
     *    is already AT the resume chapter; pre-rendering it would be
     *    wasted work (the foreground tee in EnginePlayer's streaming
     *    source populates the cache for the active chapter
     *    automatically). N+1, N+2, N+3 keep the window one chapter
     *    ahead of playback so the next-up transition is instant.
     *  - Resume chapter not in current chapter list (deleted /
     *    re-numbered upstream) → fall back to chapters[0..N] rather
     *    than skip silently.
     *  - Mode C (full pre-render) → render everything (no slice).
     */
    override suspend fun onLibraryAdded(fictionId: String) {
        val chapters = chapterRepo.observeChapters(fictionId).first()
            .sortedBy { it.index }
        if (chapters.isEmpty()) {
            Log.i(
                LOG_TAG,
                "pcm-cache TRIGGER-LIBRARY-ADD fictionId=$fictionId chapters=0 (skipping)",
            )
            return
        }
        if (modeConfig.currentFullPrerender()) {
            Log.i(
                LOG_TAG,
                "pcm-cache TRIGGER-LIBRARY-ADD fictionId=$fictionId " +
                    "scheduling=${chapters.size} fullPrerender=true",
            )
            for (chapter in chapters) {
                scheduler.scheduleRender(fictionId = fictionId, chapterId = chapter.id)
            }
            return
        }
        // Issue #557 — resolve the resume slice anchor. The
        // `runCatching` defends against a malformed PlaybackPositionRepo
        // (Room corruption, schema migration failure) — pre-render
        // scheduling is best-effort housekeeping, not a load-blocking
        // path, so a position read that throws falls through to the
        // legacy "start from 0" behavior.
        val resumeChapterId = runCatching { positionRepo.load(fictionId)?.chapterId }
            .getOrNull()
        val resumeIndex = resumeChapterId?.let { id ->
            chapters.indexOfFirst { it.id == id }.takeIf { it >= 0 }
        }
        val targets = if (resumeIndex != null) {
            // Start ONE PAST the resume chapter — that chapter is the
            // active one (or about to be) and gets cached by the
            // streaming source's foreground tee. The pre-render window
            // belongs to the chapters that come AFTER.
            chapters.drop(resumeIndex + 1).take(DEFAULT_PRERENDER_CHAPTERS)
        } else {
            chapters.take(DEFAULT_PRERENDER_CHAPTERS)
        }
        if (targets.isEmpty()) {
            // User is at end-of-fiction (resume = last chapter); nothing
            // left to pre-render. Log so the audit trail makes sense.
            Log.i(
                LOG_TAG,
                "pcm-cache TRIGGER-LIBRARY-ADD fictionId=$fictionId " +
                    "resumeChapterId=$resumeChapterId resumeIndex=$resumeIndex " +
                    "scheduling=0 (at end of fiction)",
            )
            return
        }
        Log.i(
            LOG_TAG,
            "pcm-cache TRIGGER-LIBRARY-ADD fictionId=$fictionId " +
                "resumeChapterId=$resumeChapterId resumeIndex=$resumeIndex " +
                "scheduling=${targets.size} fullPrerender=false",
        )
        for (chapter in targets) {
            scheduler.scheduleRender(fictionId = fictionId, chapterId = chapter.id)
        }
    }

    /**
     * Chapter open: pre-fetch the BODIES (NOT PCM) of the next
     * [BODY_PREFETCH_LOOKAHEAD] chapters in reading order. Called from
     * [`in`.jphe.storyvox.playback.tts.EnginePlayer.loadAndPlay] right
     * after the new chapter commits to PlaybackState, so by the time
     * the user finishes the current chapter the upcoming bodies are
     * already in Room and `advanceChapter`'s 60s body-wait latches
     * onto an already-present row instead of cold-fetching over the
     * network.
     *
     * Issue #558 — pre-fix, library-add scheduled PCM renders for the
     * first three chapters (which retry until bodies appear), but
     * nothing actively downloaded bodies for SUBSCRIBE-mode fictions
     * past chapter 1 until the user's `advanceChapter` call queued
     * each one individually. Chapters 4 → 5 on the TechEmpower Notion
     * Guides reliably tripped the 30s timeout because chapter 5's
     * body fetch only started AT advance-time. The fix decouples
     * "body in cache" from "PCM rendered" — bodies are small (<200 KB
     * each for typical Notion chapters), can be fetched on metered
     * networks, and don't require the battery/storage constraints
     * the PCM render worker imposes.
     *
     * Why a 3-chapter look-ahead window:
     *  - Catches the 2× speed case (user finishes chapter quickly,
     *    needs N+1 ready) without over-fetching.
     *  - Stays under the typical PCM render window (5 chapters, see
     *    [DEFAULT_PRERENDER_CHAPTERS]) so we never "outrun" the PCM
     *    cache and create a cliff where bodies exist but renders
     *    don't.
     *  - WorkManager dedupes by uniqueName so re-calling on every
     *    chapter open is cheap — already-queued/in-progress downloads
     *    are no-ops.
     *
     * `requireUnmetered = false`: the user is actively listening, so
     * "no Wi-Fi" should NOT block the next chapter's body. Matches
     * the policy on [`in`.jphe.storyvox.playback.tts.EnginePlayer]'s
     * own advance-time `queueChapterDownload`.
     *
     * Wrapped at the call site in `runCatching` so a chapter-graph
     * read failure doesn't block playback.
     */
    suspend fun onChapterOpened(fictionId: String, currentChapterId: String) {
        // Walk the reading-order graph forward up to N hops and queue
        // body downloads for each. The caller passes fictionId
        // explicitly because [ChapterRepository.getChapter] returns null
        // until the body is present — we can't reverse-resolve fictionId
        // from chapter rows that haven't been downloaded yet, and the
        // whole point of this trigger is to download exactly those.
        var cursor: String? = currentChapterId
        var fetched = 0
        while (fetched < BODY_PREFETCH_LOOKAHEAD) {
            val nextId = chapterRepo.getNextChapterId(cursor ?: break) ?: break
            Log.i(
                LOG_TAG,
                "pcm-cache TRIGGER-CHAPTER-OPENED prefetch body " +
                    "fictionId=$fictionId chapterId=$nextId",
            )
            chapterRepo.queueChapterDownload(
                fictionId = fictionId,
                chapterId = nextId,
                requireUnmetered = false,
            )
            cursor = nextId
            fetched++
        }
    }

    /**
     * Chapter natural-end: schedule N+2. N+1 should already be cached
     * (it was scheduled when N started OR is the next-up that the user
     * is about to play and PR-D's tee will populate). N+2 keeps the
     * pre-render window one chapter ahead of playback so the user
     * never hits a streaming cold-start.
     *
     * Resolves the next-next chapter's fictionId via the chapter row —
     * the trigger doesn't carry it and we don't want to assume.
     */
    suspend fun onChapterCompleted(currentChapterId: String) {
        val nextId = chapterRepo.getNextChapterId(currentChapterId)
        if (nextId == null) {
            Log.i(
                LOG_TAG,
                "pcm-cache TRIGGER-CHAPTER-DONE chapterId=$currentChapterId " +
                    "no-next (end of fiction)",
            )
            return
        }
        val nextNextId = chapterRepo.getNextChapterId(nextId) ?: return
        val nextNextChapter = chapterRepo.getChapter(nextNextId) ?: return
        Log.i(
            LOG_TAG,
            "pcm-cache TRIGGER-CHAPTER-DONE chapterId=$currentChapterId " +
                "scheduling next-next=$nextNextId",
        )
        scheduler.scheduleRender(
            fictionId = nextNextChapter.fictionId,
            chapterId = nextNextId,
        )
    }

    override fun onLibraryRemoved(fictionId: String) {
        Log.i(LOG_TAG, "pcm-cache TRIGGER-LIBRARY-REMOVE fictionId=$fictionId")
        scheduler.cancelAllForFiction(fictionId)
    }

    /**
     * Mode C flow flip false → true. Re-evaluates every library
     * fiction and enqueues any not-yet-cached chapters. Called by
     * `:app`'s PrerenderModeWatcher flow collector (which knows
     * about FictionRepository — this class deliberately doesn't, to
     * keep `:core-playback` from depending on the library repository).
     *
     * The KEEP existing-work policy on the scheduler means re-scheduling
     * an already-queued chapter is a no-op; we don't need to filter
     * here, the scheduler dedupes.
     */
    suspend fun onFullPrerenderEnabled(fictionIds: Iterable<String>) {
        for (fictionId in fictionIds) {
            val chapters = chapterRepo.observeChapters(fictionId).first()
                .sortedBy { it.index }
            Log.i(
                LOG_TAG,
                "pcm-cache TRIGGER-FULL-PRERENDER fictionId=$fictionId " +
                    "scheduling=${chapters.size}",
            )
            for (chapter in chapters) {
                scheduler.scheduleRender(fictionId, chapter.id)
            }
        }
    }

    companion object {
        private const val LOG_TAG = "PrerenderTriggers"

        /** Library-add pre-renders the first N chapters in reading order.
         *  Mode C expands to "all chapters".
         *
         *  Issue #558 — bumped 3 → 5. The original spec window of 3 was
         *  fine for Royal Road (chapter download is a single REST call,
         *  body lands in <1s on Wi-Fi), but Notion sources walk a
         *  multi-page hierarchy and can take 5-10s per body — so when the
         *  user reads through chapters 1..4 in sequence the PCM render
         *  window (only [DEFAULT_PRERENDER_CHAPTERS] from the START of
         *  the fiction) is exhausted by the time auto-advance hits N+1.
         *  Widening to 5 covers the most common early-read path through
         *  a typical Notion guide collection. */
        const val DEFAULT_PRERENDER_CHAPTERS = 5

        /** Issue #558 — number of upcoming chapter BODIES to pre-fetch
         *  on [onChapterOpened]. Strictly smaller than
         *  [DEFAULT_PRERENDER_CHAPTERS] so the body-prefetch window
         *  stays nested inside the PCM-render window — every body
         *  we prefetch will eventually have an accompanying PCM
         *  render scheduled. */
        const val BODY_PREFETCH_LOOKAHEAD = 3
    }
}
