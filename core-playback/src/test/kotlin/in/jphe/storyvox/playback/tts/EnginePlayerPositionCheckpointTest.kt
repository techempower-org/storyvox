package `in`.jphe.storyvox.playback.tts

import `in`.jphe.storyvox.playback.PlaybackState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #980 — pure-function unit test for [shouldCheckpointPosition], the
 * save-guard that every position-persist path funnels through
 * (periodic checkpoint loop, post-seek save, pause, chapter boundary, and the
 * NonCancellable teardown saves in releaseEngine / handleStop).
 *
 * The bug fixed in #980 was about WHEN persistPosition fires, not whether the
 * write commits — but a regression in the guard would silently disable every
 * one of those saves. This pins the contract: a checkpoint is meaningful iff
 * BOTH the fiction id and the chapter id are present, because the row's
 * composite identity needs them and the Library "Continue listening" join has
 * nothing to point at otherwise.
 *
 * Stays at the pure-function layer — full EnginePlayer construction needs a
 * Hilt graph + sherpa-onnx AARs (same constraint documented on
 * EnginePlayerAutoPlayDecisionTest / PlaybackControllerSkipSeekTest).
 */
class EnginePlayerPositionCheckpointTest {

    @Test
    fun `checkpoint saves when both fiction and chapter ids are present`() {
        // Steady-state continuous listen: the periodic loop ticks mid-chapter
        // with a live fiction + chapter and a non-zero offset. This is the
        // headline #980 case — an OS-kill after this save resumes here instead
        // of rolling back to the chapter start.
        val state = PlaybackState(
            currentFictionId = "f",
            currentChapterId = "c1",
            charOffset = 5_000,
        )
        assertTrue(
            "a fully-identified, mid-chapter state must be persisted",
            shouldCheckpointPosition(state),
        )
    }

    @Test
    fun `checkpoint saves even at charOffset zero when ids are present`() {
        // A fresh chapter load (offset 0) is still a real position — the
        // Resume card should point at chapter start, not the previous chapter.
        // The guard keys on identity, not progress.
        val state = PlaybackState(
            currentFictionId = "f",
            currentChapterId = "c1",
            charOffset = 0,
        )
        assertTrue(
            "offset 0 with a live chapter id is a valid, save-worthy position",
            shouldCheckpointPosition(state),
        )
    }

    @Test
    fun `no checkpoint when chapter id is null`() {
        // This is the handleStop ordering trap (#980 GAP-4): handleStop zeroes
        // charOffset and nulls currentChapterId. If a save ran AFTER that
        // clear, the guard short-circuits here and the listener's place is
        // lost. The fix persists BEFORE clearing; this test pins the guard
        // that makes a post-clear save a no-op rather than a 0-offset stomp.
        val cleared = PlaybackState(
            currentFictionId = "f",
            currentChapterId = null,
            charOffset = 0,
        )
        assertFalse(
            "a null chapter id (post-stop / cold-boot) has nothing to checkpoint",
            shouldCheckpointPosition(cleared),
        )
    }

    @Test
    fun `no checkpoint when fiction id is null`() {
        // Idle / cold-boot Idle state. Defensive: a stray checkpoint tick on
        // an unloaded engine must not write a half-identified row.
        val idle = PlaybackState(
            currentFictionId = null,
            currentChapterId = "c1",
        )
        assertFalse(
            "a null fiction id is not a persistable position",
            shouldCheckpointPosition(idle),
        )
    }

    @Test
    fun `no checkpoint on the default Idle state`() {
        // The all-default PlaybackState (engine constructed, nothing loaded).
        // The periodic loop is never armed in this state, but the teardown
        // saves can still call persistPosition on a never-played engine; the
        // guard makes that a clean no-op.
        assertFalse(
            "the default Idle state has no fiction/chapter to save",
            shouldCheckpointPosition(PlaybackState()),
        )
    }
}
