package `in`.jphe.storyvox.playback.tts

import `in`.jphe.storyvox.playback.PlaybackError
import `in`.jphe.storyvox.playback.PlaybackState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #728 — pure-function unit test for [shouldAutoPlayAfterAdvance],
 * the decision rule that prevents a sleep-timer pause (or any other
 * mid-wait pause) from being silently stomped when the next chapter's
 * body lands and `EnginePlayer.advanceChapter` calls `loadAndPlay`.
 *
 * Pre-fix: `loadAndPlay` unconditionally wrote `isPlaying = true` and
 * called `startPlaybackPipeline()`, regardless of whether the user's
 * intent had changed during the chapter-body wait. The user-visible
 * symptom was waking up to the next chapter playing at full volume.
 *
 * Post-fix: `advanceChapter` snapshots `_observableState.value` after
 * the wait completes and feeds the result of this helper into
 * `loadAndPlay(..., autoPlay = ...)`. The helper's contract: return
 * `state.isPlaying` — if anything paused us during the wait the value
 * is already false, and the chapter loads paused.
 *
 * The test deliberately stays at the pure-function layer; the full
 * EnginePlayer construction needs a Hilt graph + sherpa-onnx AARs (see
 * the kdoc on PlaybackControllerSkipSeekTest for the same constraint).
 */
class EnginePlayerAutoPlayDecisionTest {

    @Test
    fun `auto-play when state shows isPlaying=true after the body wait`() {
        // Natural-end auto-advance: handleChapterDone fires, advanceChapter
        // sets isBuffering=true (NOT touching isPlaying), waits for body,
        // nothing pauses us during the wait. The post-wait snapshot keeps
        // isPlaying=true and the next chapter resumes playing.
        val state = PlaybackState(
            currentFictionId = "f",
            currentChapterId = "c1",
            isPlaying = true,
            isBuffering = true,
        )
        assertTrue(
            "advanceChapter should auto-play when no pause landed during the body wait",
            shouldAutoPlayAfterAdvance(state),
        )
    }

    @Test
    fun `do NOT auto-play when sleep timer flipped isPlaying=false during the body wait`() {
        // The #728 race: chapter ended, advanceChapter parked in
        // observeChapter().first(). SleepTimer.fadeAndPause invoked
        // PauseAction → controller.pause() → pauseTts → _observableState
        // got `isPlaying = false`. Now the body finally lands; the
        // post-wait snapshot is the state below. Pre-fix, loadAndPlay
        // stomped it back to true. The decision rule returns false so
        // the new chapter loads paused — user stays asleep.
        val state = PlaybackState(
            currentFictionId = "f",
            currentChapterId = "c1",
            isPlaying = false,
            isBuffering = true,
        )
        assertFalse(
            "advanceChapter must NOT auto-play when a pause landed during the body wait (#728)",
            shouldAutoPlayAfterAdvance(state),
        )
    }

    @Test
    fun `do NOT auto-play when audio focus loss flipped isPlaying=false during the body wait`() {
        // #560 cousin of the #728 race. AudioFocusController's
        // onFocusLost callback routes through the same pause() path
        // (PlaybackController.pause → pauseTts → isPlaying=false). A
        // phone call ringing during chapter-advance must not be
        // overridden by the chapter-load completion. Same shape, same
        // decision rule, no special case needed — that's the value of
        // funnelling through one isPlaying flag.
        val state = PlaybackState(
            currentFictionId = "f",
            currentChapterId = "c1",
            isPlaying = false,
            isBuffering = true,
            error = null,
        )
        assertFalse(
            "advanceChapter must respect a pause caused by audio-focus loss too (#560 / #728)",
            shouldAutoPlayAfterAdvance(state),
        )
    }

    @Test
    fun `do NOT auto-play when a manual user pause landed during the body wait`() {
        // User taps Pause on the play screen the moment the chapter
        // finishes naturally. The tap routes to controller.pause →
        // pauseTts → isPlaying=false. By the time the body lands, the
        // user has intentionally requested silence. Honor it.
        val state = PlaybackState(
            currentFictionId = "f",
            currentChapterId = "c1",
            isPlaying = false,
            isBuffering = true,
        )
        assertFalse(
            "advanceChapter must respect an explicit user pause that landed during the body wait",
            shouldAutoPlayAfterAdvance(state),
        )
    }

    @Test
    fun `decision is a pure read of isPlaying — other fields are not consulted`() {
        // Defensive: if a future refactor adds new state fields, this
        // test pins the decision to a single field. The helper deliberately
        // does NOT inspect isBuffering / error / sleepTimerRemainingMs /
        // sentence range — the truthful "did anyone pause us during the
        // wait" signal lives entirely in isPlaying, written by pauseTts.
        val playingHasError = PlaybackState(
            isPlaying = true,
            isBuffering = true,
            error = PlaybackError.ChapterFetchFailed("stale error from prior chapter"),
            sleepTimerRemainingMs = 30_000L,
        )
        assertTrue(shouldAutoPlayAfterAdvance(playingHasError))

        val pausedNoError = PlaybackState(
            isPlaying = false,
            isBuffering = false,
            error = null,
            sleepTimerRemainingMs = null,
        )
        assertFalse(shouldAutoPlayAfterAdvance(pausedNoError))
    }
}
