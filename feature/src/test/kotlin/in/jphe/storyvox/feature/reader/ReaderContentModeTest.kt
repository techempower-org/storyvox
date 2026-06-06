package `in`.jphe.storyvox.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TechEmpower wrong-book regression (v1.1.1) — when the user opens the
 * Resources fiction's detail page and taps Play, the reader briefly (or
 * permanently, on a 30s body-fetch timeout) shows a DIFFERENT,
 * previously-played book.
 *
 * Root cause: [HybridReaderScreen] renders the GLOBAL PlaybackController
 * state, not its own nav args. The #638 loading gate
 * ([readerContentMode] → [ReaderContentMode.ExplicitLoading]) only held
 * while the controller's ids were BLANK (cold-start warm-up window). If
 * the app process survived from a prior session, the controller still
 * holds the prior book's (non-blank) ids, so the gate fell through to
 * [ReaderContentMode.Content] and the reader painted the prior book —
 * even though the user navigated to /reader/{resources}/{ch} with
 * explicit args. The cold body-fetch (first-ever Resources open, 30s
 * Notion fetch) makes the window long enough to be the reported bug; a
 * warm fetch resolves before the user notices.
 *
 * These tests pin [readerContentMode] (the pure decision the screen
 * calls) so the gate can't regress. The load-bearing case is
 * [explicit args for a different fiction than the controller currently
 * holds must show the loading card, not the prior book].
 */
class ReaderContentModeTest {

    @Test
    fun `explicit args while controller holds a DIFFERENT fiction shows loading, never the prior book`() {
        // The exact regression: user navigated to /reader/{resources}/{ch}
        // (hasExplicitChapterArgs = true, argFictionId = resources) but the
        // controller still holds a prior book B (non-blank ids) because the
        // process survived. The reader must show the explicit-args loading
        // card for Resources, NOT fall through to Content (which paints B).
        val mode = readerContentMode(
            hasExplicitChapterArgs = true,
            argFictionId = "notion:resources",
            hasPlayback = true,
            playbackFictionId = "lucidx:bookB",
            playbackChapterId = "lucidx:bookB::ch1",
            hasResumeEntry = true,
            timedOut = false,
        )
        assertEquals(ReaderContentMode.ExplicitLoading, mode)
    }

    @Test
    fun `cold Play on fiction A whose chapters are unresolved never surfaces resume for prior book B`() {
        // The lead's exact COLD-timing case: the user taps Play on Resources
        // (A) whose chapter list/body has NOT resolved yet — the controller
        // either hasn't emitted, or still carries the prior most-recent book
        // B. A most-recent continue-listening entry for B EXISTS
        // (hasResumeEntry = true), so the *only* thing standing between the
        // user and ResumePrompt(B) is that the explicit-args gate must stay
        // CLOSED (return ExplicitLoading) for A. We assert both unresolved
        // shapes resolve to A's loading card, never B's resume prompt.

        // Shape 1: controller hasn't emitted yet (truly cold, app-killed).
        val coldNoEmit = readerContentMode(
            hasExplicitChapterArgs = true,
            argFictionId = "notion:resources",
            hasPlayback = false,
            playbackFictionId = null,
            playbackChapterId = null,
            hasResumeEntry = true, // B is the most-recent — must NOT surface
            timedOut = false,
        )
        assertEquals(ReaderContentMode.ExplicitLoading, coldNoEmit)

        // Shape 2: controller survived holding prior book B; A still resolving.
        val coldHoldsPriorB = readerContentMode(
            hasExplicitChapterArgs = true,
            argFictionId = "notion:resources",
            hasPlayback = true,
            playbackFictionId = "lucidx:bookB",
            playbackChapterId = "lucidx:bookB::ch1",
            hasResumeEntry = true, // B is the most-recent — must NOT surface
            timedOut = false,
        )
        assertEquals(ReaderContentMode.ExplicitLoading, coldHoldsPriorB)
    }

    @Test
    fun `explicit args while controller holds the SAME fiction renders content`() {
        // Once the controller catches up to the requested fiction, the
        // loading gate releases and the reader renders the player normally.
        val mode = readerContentMode(
            hasExplicitChapterArgs = true,
            argFictionId = "notion:resources",
            hasPlayback = true,
            playbackFictionId = "notion:resources",
            playbackChapterId = "notion:resources::abc",
            hasResumeEntry = true,
            timedOut = false,
        )
        assertEquals(ReaderContentMode.Content, mode)
    }

    @Test
    fun `explicit args with null playback shows loading (cold start, pre-emission)`() {
        // #638 original case: playback flow hasn't emitted yet.
        val mode = readerContentMode(
            hasExplicitChapterArgs = true,
            argFictionId = "notion:resources",
            hasPlayback = false,
            playbackFictionId = null,
            playbackChapterId = null,
            hasResumeEntry = true,
            timedOut = false,
        )
        assertEquals(ReaderContentMode.ExplicitLoading, mode)
    }

    @Test
    fun `explicit args with blank controller ids shows loading (#638 post-emission window)`() {
        // #638 follow-up: controller emitted its default state with both
        // ids null but hasn't reached the engine yet.
        val mode = readerContentMode(
            hasExplicitChapterArgs = true,
            argFictionId = "notion:resources",
            hasPlayback = true,
            playbackFictionId = null,
            playbackChapterId = null,
            hasResumeEntry = true,
            timedOut = false,
        )
        assertEquals(ReaderContentMode.ExplicitLoading, mode)
    }

    @Test
    fun `Playing tab (no explicit args) with empty controller shows the resume prompt`() {
        // The Playing tab has no nav args → hasExplicitChapterArgs = false.
        // With a most-recent entry it shows the magical Resume prompt.
        val mode = readerContentMode(
            hasExplicitChapterArgs = false,
            argFictionId = null,
            hasPlayback = false,
            playbackFictionId = null,
            playbackChapterId = null,
            hasResumeEntry = true,
            timedOut = false,
        )
        assertEquals(ReaderContentMode.ResumePrompt, mode)
    }

    @Test
    fun `Playing tab with empty controller and no resume entry shows the empty prompt`() {
        val mode = readerContentMode(
            hasExplicitChapterArgs = false,
            argFictionId = null,
            hasPlayback = false,
            playbackFictionId = null,
            playbackChapterId = null,
            hasResumeEntry = false,
            timedOut = false,
        )
        assertEquals(ReaderContentMode.ResumeEmpty, mode)
    }

    @Test
    fun `Playing tab with a loaded chapter renders content`() {
        val mode = readerContentMode(
            hasExplicitChapterArgs = false,
            argFictionId = null,
            hasPlayback = true,
            playbackFictionId = "notion:resources",
            playbackChapterId = "notion:resources::abc",
            hasResumeEntry = true,
            timedOut = false,
        )
        assertEquals(ReaderContentMode.Content, mode)
    }

    @Test
    fun `timed-out with a resume entry falls back to the resume prompt`() {
        // Lyra's TimedOut escape hatch: a stalled load with a resume entry
        // surfaces the Resume prompt instead of a dead-end error block.
        val mode = readerContentMode(
            hasExplicitChapterArgs = false,
            argFictionId = null,
            hasPlayback = true,
            playbackFictionId = "notion:resources",
            playbackChapterId = "notion:resources::abc",
            hasResumeEntry = true,
            timedOut = true,
        )
        assertEquals(ReaderContentMode.ResumePrompt, mode)
    }

    @Test
    fun `explicit-args loading wins over a timed-out resume entry`() {
        // On the explicit drill-down route a stall surfaces the typed
        // ExplicitArgsLoadingPrompt (with its own Retry/Back), NOT the
        // global resume prompt — so we never bounce the user into a
        // different book on a slow Resources fetch.
        val mode = readerContentMode(
            hasExplicitChapterArgs = true,
            argFictionId = "notion:resources",
            hasPlayback = true,
            playbackFictionId = "lucidx:bookB",
            playbackChapterId = "lucidx:bookB::ch1",
            hasResumeEntry = true,
            timedOut = true,
        )
        assertEquals(ReaderContentMode.ExplicitLoading, mode)
    }
}
