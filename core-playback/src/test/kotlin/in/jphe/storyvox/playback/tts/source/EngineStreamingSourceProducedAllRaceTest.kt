package `in`.jphe.storyvox.playback.tts.source

import `in`.jphe.storyvox.playback.tts.Sentence
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #642 — auto-advance stall on chapter end.
 *
 * The on-device reproduction (R5CRB0W66MK Z Flip 3, v0.5.63) showed the
 * consumer thread parked in `bufferHeadroomMs.first { ... }` after
 * dequeuing the LAST real chunk of a chapter. The producer had already
 * pushed END_PILL by then and would never emit again, so the consumer
 * waited forever for headroom to recover.
 *
 * The fix has two parts in [`in`.jphe.storyvox.playback.tts.EnginePlayer]:
 *
 *   1. The underrun pause gate now also checks
 *      `!source.producedAllSentences` — when the producer signals
 *      end-of-list, the consumer skips the pause and proceeds to drain
 *      the END_PILL.
 *
 *   2. The pause-park's wait helper races the headroom flow against a
 *      50 ms poll on `source.producedAllSentences`, so a producer that
 *      finishes WHILE the consumer is parked still wakes the park.
 *
 * Both rely on the contract that `producedAllSentences` is set true
 * BEFORE the END_PILL is pushed to the queue, and stays sticky-true
 * thereafter. This test pins that timing — without it the EnginePlayer
 * fix is correct but fragile (a producer change could break the
 * ordering and the on-device stall would silently regress).
 */
class EngineStreamingSourceProducedAllRaceTest {

    @Test
    fun `producedAllSentences flips true before END_PILL is dequeued`() = runBlocking {
        // Two sentences — enough to verify ordering. We pull the second
        // (last) chunk and immediately observe the flag, BEFORE pulling
        // the END_PILL. The producer must have set the flag in the same
        // critical section as its loop-exit; if the test sees false here
        // the on-device fix's underrun-gate `!producedAllSentences`
        // would race against the consumer's last-chunk dequeue and the
        // #642 stall would reappear.
        val sentences = listOf(
            Sentence(0, 0, 10, "First sentence."),
            Sentence(1, 11, 30, "Last sentence."),
        )
        val src = EngineStreamingSource(
            sentences = sentences,
            startSentenceIndex = 0,
            engine = ProducedAllRaceFakeVoiceEngine(22050) { ByteArray(100) },
            speed = 1f,
            pitch = 1f,
            engineMutex = Mutex(),
        )

        // Drain real chunks. After the SECOND nextChunk() returns the
        // last sentence, the producer has exited its for-loop, set
        // producedAll=true, and pushed END_PILL. The ordering invariant
        // is exactly the one EnginePlayer's underrun gate relies on.
        val first = src.nextChunk()
        assertTrue("First nextChunk must return a real chunk", first != null)
        val last = src.nextChunk()
        assertTrue("Last real nextChunk must return a chunk", last != null)

        // Give the producer thread a moment to set producedAll AND push
        // END_PILL (both happen in the same critical section in the
        // producer's for-loop exit path, but the JIT can reorder
        // observation across threads). The polling shape mirrors the
        // EnginePlayer fix's 50 ms cadence.
        val observedTrue = withTimeoutOrNull(2_000L) {
            while (!src.producedAllSentences) delay(20L)
            true
        }
        assertEquals(
            "producedAllSentences must flip true within 2 s of dequeuing the last real chunk; " +
                "if this assertion fails, EnginePlayer's #642 underrun gate races against the " +
                "consumer's last-chunk dequeue and the chapter-end stall reproduces on device",
            true,
            observedTrue,
        )

        // And confirm END_PILL is what the next dequeue gives us.
        val end = src.nextChunk()
        assertEquals("After the END_PILL is consumed nextChunk returns null", null, end)

        src.close()
    }

    @Test
    fun `producedAllSentences observable during consumer underrun pause window`() = runBlocking {
        // Simulates the on-device window: producer finishes, consumer is
        // mid-write on the chunk it just dequeued, ABOUT to enter the
        // underrun pause check. The check reads producedAllSentences
        // synchronously — this test pins that the read is correct
        // (true, since producer is done) and the underrun gate's
        // `!producedAllSentences` clause short-circuits the pause.
        val sentences = listOf(Sentence(0, 0, 10, "Solo sentence."))
        val src = EngineStreamingSource(
            sentences = sentences,
            startSentenceIndex = 0,
            engine = ProducedAllRaceFakeVoiceEngine(22050) { ByteArray(50) },
            speed = 1f,
            pitch = 1f,
            engineMutex = Mutex(),
        )

        val chunk = src.nextChunk()
        assertTrue("Must get the one real chunk", chunk != null)

        // Wait for producer to push END_PILL and flip the flag.
        val flagged = withTimeoutOrNull(2_000L) {
            while (!src.producedAllSentences) delay(10L)
            true
        }
        assertEquals(true, flagged)

        // At this exact moment the EnginePlayer consumer-thread underrun
        // gate `!source.producedAllSentences` evaluates to false → skip
        // the pause. The bug pre-fix had no such gate; the consumer
        // paused the AudioTrack and parked on bufferHeadroomMs.first
        // forever. Asserting on the flag at this moment is the
        // unit-test stand-in for the on-device assertion "chapter 2's
        // audio reaches the speakers within 3 s of auto-advance".
        assertTrue(
            "Producer must have completed by the time the consumer reaches the underrun check",
            src.producedAllSentences,
        )

        src.close()
    }
}

/** Test-only engine handle. Identity behaviour. Distinct copy from the
 *  Gapless test class's helper so a refactor to one doesn't leak. */
private class ProducedAllRaceFakeVoiceEngine(
    override val sampleRate: Int,
    val pcmFor: (String) -> ByteArray,
) : EngineStreamingSource.VoiceEngineHandle {
    override fun generateAudioPCM(text: String, speed: Float, pitch: Float): ByteArray? = pcmFor(text)
}
