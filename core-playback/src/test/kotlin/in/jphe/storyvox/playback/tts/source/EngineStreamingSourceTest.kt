package `in`.jphe.storyvox.playback.tts.source

import `in`.jphe.storyvox.playback.tts.Sentence
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EngineStreamingSourceTest {

    @Test
    fun `nextChunk returns sentences in order then null at end`() = runBlocking {
        val sentences = listOf(
            Sentence(0, 0, 10, "One."),
            Sentence(1, 11, 20, "Two."),
            Sentence(2, 21, 30, "Three."),
        )
        val fakeEngine = FakeVoiceEngine(
            sampleRate = 22050,
            pcmFor = { text -> ByteArray(text.length * 2) { 0 } },
        )
        val source = EngineStreamingSource(
            sentences = sentences,
            startSentenceIndex = 0,
            engine = fakeEngine,
            speed = 1.0f,
            pitch = 1.0f,
            engineMutex = Mutex(),
        )

        val c0 = source.nextChunk()
        val c1 = source.nextChunk()
        val c2 = source.nextChunk()
        val end = source.nextChunk()

        assertEquals(0, c0?.sentenceIndex)
        assertEquals(1, c1?.sentenceIndex)
        assertEquals(2, c2?.sentenceIndex)
        assertNull(end)

        source.close()
    }

    @Test
    fun `close makes a subsequent nextChunk return null`() = runBlocking {
        // Engine that releases generation only when the test signals; this
        // guarantees the producer is parked while close() runs, no timing race.
        val gate = java.util.concurrent.CountDownLatch(1)
        val gatedEngine = object : EngineStreamingSource.VoiceEngineHandle {
            override val sampleRate: Int = 22050
            override fun generateAudioPCM(text: String, speed: Float, pitch: Float): ByteArray? {
                // Wait until the test releases us. If close() came before
                // the test releases, the next iteration's running.get()
                // check + the runInterruptible queue.put will both
                // short-circuit, so no chunk reaches the queue.
                gate.await()
                return ByteArray(100)
            }
        }
        val sentences = listOf(Sentence(0, 0, 10, "One."))
        val source = EngineStreamingSource(sentences, 0, gatedEngine, 1f, 1f, Mutex())

        // Producer is now parked at gate.await() inside engine.generateAudioPCM.
        // Give Dispatchers.IO a moment to schedule it; not strictly required
        // since close()'s contract holds even if the producer hasn't started
        // yet, but easier to reason about.
        delay(50)

        source.close()

        // Now release the producer. Its `running.get()` post-engine guard
        // should fire, the runInterruptible put should observe cancellation,
        // and no chunk reaches the queue. nextChunk should return null
        // (END_PILL placed by close).
        gate.countDown()

        val started = System.currentTimeMillis()
        val result = source.nextChunk()
        val elapsed = System.currentTimeMillis() - started

        assertNull(result)
        assert(elapsed < 500) { "nextChunk after close took $elapsed ms" }
    }

    @Test
    fun `punctuationPauseMultiplier scales trailing silence`() = runBlocking {
        // Issue #90 — verify the multiplier we plumb down from the
        // Settings selector actually changes the silence slot in each
        // PcmChunk. Two sentences ending in '.', so the base
        // trailingPauseMs is 350 ms each; speed=1f so no speed-scaling
        // contribution. We check three points:
        //   - multiplier=0f → silenceBytes == 0  (Off)
        //   - multiplier=1f → silenceBytes ≈ baseline (Normal — pre-#90)
        //   - multiplier=2f → silenceBytes ≈ 2× baseline (Long-ish)
        val sentences = listOf(
            Sentence(0, 0, 10, "First."),
            Sentence(1, 11, 20, "Second."),
        )
        val sampleRate = 22050
        val pcm = ByteArray(100) // small, doesn't matter for silence assertion
        val fakeEngine = FakeVoiceEngine(sampleRate) { pcm }

        suspend fun firstChunkSilenceBytes(multiplier: Float): Int {
            val src = EngineStreamingSource(
                sentences = sentences,
                startSentenceIndex = 0,
                engine = fakeEngine,
                speed = 1f,
                pitch = 1f,
                engineMutex = Mutex(),
                punctuationPauseMultiplier = multiplier,
            )
            val chunk = src.nextChunk()
            src.close()
            return chunk?.trailingSilenceBytes ?: -1
        }

        val off = firstChunkSilenceBytes(0f)
        val normal = firstChunkSilenceBytes(1f)
        val long = firstChunkSilenceBytes(2f)

        // Off: zero silence regardless of terminal punctuation.
        assertEquals(0, off)

        // Normal: matches the pre-#90 baseline of trailingPauseMs(".")=350ms
        // → silenceBytesFor(350, 22050) = (22050 * 350 / 1000).toInt() * 2
        // = 7717 * 2 = 15434.
        assertEquals(15_434, normal)

        // Long-ish (2×): roughly twice normal. Allow a ±2-byte tolerance
        // for the int floor in silenceBytesFor (700 ms × 22050 / 1000 =
        // 15435; × 2 = 30870 vs 2 × normal = 30868).
        val expected = silenceBytesFor(700, sampleRate)
        assertEquals(expected, long)
        assert(long >= normal * 2 - 4 && long <= normal * 2 + 4) {
            "Long ($long) should be ≈ 2× normal ($normal); diff = ${long - normal * 2}"
        }
    }

    @Test
    fun `queueCapacity bounds outstanding pre-synth chunks`() = runBlocking {
        // Issue #84 — verify the queueCapacity constructor parameter actually
        // controls back-pressure. With capacity=2 and a producer racing ahead
        // of any consumer, at most ~3 generate calls land before the
        // LinkedBlockingQueue.put blocks (2 in queue + 1 currently inside
        // generate). We give the producer plenty of wall time and confirm
        // it doesn't run away past that bound.
        val sentences = (0 until 50).map { i -> Sentence(i, i * 10, i * 10 + 5, "S$i.") }
        val generated = java.util.concurrent.atomic.AtomicInteger(0)
        val engine = object : EngineStreamingSource.VoiceEngineHandle {
            override val sampleRate: Int = 22050
            override fun generateAudioPCM(text: String, speed: Float, pitch: Float): ByteArray? {
                generated.incrementAndGet()
                return ByteArray(100)
            }
        }

        val source = EngineStreamingSource(
            sentences = sentences,
            startSentenceIndex = 0,
            engine = engine,
            speed = 1f,
            pitch = 1f,
            engineMutex = Mutex(),
            queueCapacity = 2,
        )

        // Give the producer a generous window to fill the bounded queue.
        // Without back-pressure it would race through all 50 sentences in
        // under 10 ms; with back-pressure it caps at ~3 (2 queued + 1 in
        // flight at the put().
        delay(200)

        val produced = generated.get()
        assert(produced in 2..4) {
            "expected producer bounded by queueCapacity=2 (≈ 2-4 generate calls), got $produced"
        }

        source.close()
    }

    // ── trailingPauseMs cadence table ──────────────────────────────────
    // Thalia's VoxSherpa P0 #3 (2026-05-08): bumped the ellipsis bucket
    // (both ASCII `...` and Unicode `…`) from 350 to 380 ms — narrators
    // audibly trail off on ellipsis vs a plain `.`. The other terminal
    // punctuation values are unchanged.

    @Test
    fun `trailingPauseMs gives ASCII ellipsis a longer pause than period`() {
        // Three-dot ASCII ellipsis (the shape that lands in HTML-decoded
        // chapter text) should hit the new 380 ms bucket, not the 350 ms
        // period bucket.
        assertEquals(380, trailingPauseMs("Wait..."))
        assertEquals(380, trailingPauseMs("She paused..."))
        // With trailing whitespace + closer — the strip loop should still
        // surface the ellipsis underneath.
        assertEquals(380, trailingPauseMs("\"Wait...\" "))
    }

    @Test
    fun `trailingPauseMs gives Unicode ellipsis the ellipsis pause`() {
        assertEquals(380, trailingPauseMs("Wait…"))
        assertEquals(380, trailingPauseMs("\"Wait…\""))
    }

    @Test
    fun `trailingPauseMs preserves period question and bang at 350ms`() {
        assertEquals(350, trailingPauseMs("Wait."))
        assertEquals(350, trailingPauseMs("Really?"))
        assertEquals(350, trailingPauseMs("Stop!"))
    }

    @Test
    fun `trailingPauseMs preserves semicolon and colon at 200ms`() {
        assertEquals(200, trailingPauseMs("First;"))
        assertEquals(200, trailingPauseMs("Then:"))
    }

    @Test
    fun `trailingPauseMs preserves comma and dashes at 120ms`() {
        assertEquals(120, trailingPauseMs("Wait,"))
        assertEquals(120, trailingPauseMs("Wait—"))
        assertEquals(120, trailingPauseMs("Wait–"))
        assertEquals(120, trailingPauseMs("Wait-"))
    }

    @Test
    fun `trailingPauseMs falls through to 60ms for parentheticals`() {
        // Documented behavior — the closer-stripping loop strips trailing
        // ')' so `(parenthetical)` is evaluated against its inner content's
        // terminal char. Here `l` falls through to the else branch.
        // If narrators want a longer parenthetical pause, that's a separate
        // enhancement (detect outermost closer before strip).
        assertEquals(60, trailingPauseMs("(parenthetical)"))
        assertEquals(60, trailingPauseMs("[bracketed]"))
    }

    @Test
    fun `trailingPauseMs strips closers before classifying`() {
        // Closers + whitespace are stripped before terminal-char lookup,
        // so a quote-wrapped sentence still gets the period bucket.
        assertEquals(350, trailingPauseMs("\"Wait.\""))
        assertEquals(350, trailingPauseMs("'Wait.'"))
        assertEquals(380, trailingPauseMs("\"Wait...\" "))
    }

    @Test
    fun `pronunciationDictApply rewrites engine input but not Sentence text`() = runBlocking {
        // Issue #135: confirm the dict substitution acts on the
        // generateAudioPCM input only — the consumer-visible chunk's
        // SentenceRange is still indexed against the original
        // sentence (so the highlight char-range keeps working).
        val sentences = listOf(
            Sentence(0, 0, 16, "Astaria fell."),
        )
        val seenByEngine = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val fakeEngine = object : EngineStreamingSource.VoiceEngineHandle {
            override val sampleRate: Int = 22050
            override fun generateAudioPCM(text: String, speed: Float, pitch: Float): ByteArray? {
                seenByEngine.set(text)
                return ByteArray(20)
            }
        }
        val source = EngineStreamingSource(
            sentences = sentences,
            startSentenceIndex = 0,
            engine = fakeEngine,
            speed = 1f,
            pitch = 1f,
            engineMutex = Mutex(),
            pronunciationDictApply = { it.replace("Astaria", "uh-STAY-ree-uh") },
        )

        val chunk = source.nextChunk()

        assertEquals("uh-STAY-ree-uh fell.", seenByEngine.get())
        // Range still anchored to the original sentence — the highlight
        // tracks the displayed text, not the spoken substitution.
        assertEquals(0, chunk?.range?.startCharInChapter)
        assertEquals(16, chunk?.range?.endCharInChapter)

        source.close()
    }

    @Test
    fun `bufferHeadroomMs reflects queued audio duration`() = runBlocking {
        val sentences = listOf(
            Sentence(0, 0, 10, "One."),
            Sentence(1, 11, 20, "Two."),
        )
        // 22050 Hz × 2 bytes per sample = 44100 bytes per second of audio.
        // 44100 bytes ⇒ 1000 ms.
        val fakeEngine = FakeVoiceEngine(22050) { _ -> ByteArray(44100) }
        val source = EngineStreamingSource(sentences, 0, fakeEngine, 1f, 1f, Mutex())

        // Wait for producer to fill the queue with both sentences.
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline &&
               source.bufferHeadroomMs.value < 2_000) {
            delay(10)
        }
        // 2 sentences × (1000 ms PCM + 350 ms cadence) = 2700 ms expected
        // (cadence is 350 ms because "One." / "Two." end in '.').
        val before = source.bufferHeadroomMs.value
        assert(before >= 2_000) { "expected ≥ 2000 ms headroom, got $before" }

        val first = source.nextChunk()
        assertEquals(0, first?.sentenceIndex)
        // Argus Fix B (#79): headroom no longer drops at nextChunk()
        // dequeue. The decrement now fires when the consumer calls
        // decrementHeadroomForChunk(chunk) AFTER AudioTrack has accepted
        // the bytes — the dequeue itself doesn't change "audio not yet
        // heard." Verify both the new (no-op-on-take) AND post-fix
        // (drop-on-decrement) shapes.
        val afterTake = source.bufferHeadroomMs.value
        assertEquals(
            "post-Fix-B: nextChunk does NOT decrement headroom (was $before)",
            before, afterTake,
        )
        source.decrementHeadroomForChunk(first!!)
        val afterDecrement = source.bufferHeadroomMs.value
        // Headroom should drop by ~1350 ms (1000 ms PCM + 350 ms cadence).
        assert(afterDecrement < before - 1_000) {
            "expected headroom to drop by > 1000 ms after decrementHeadroomForChunk; was $before → $afterDecrement"
        }

        source.close()
    }

    @Test
    fun `close settles headroom for a dequeued-but-undecremented chunk`() = runBlocking {
        // #906 — a consumer that dequeues a chunk and then aborts before
        // calling decrementHeadroomForChunk (pipeline torn down mid-write)
        // used to leave the producer's enqueue increment on the books
        // forever. With #540 fast-pause reusing the same source, that
        // phantom headroom accumulated across pause-resume cycles. close()
        // must now settle the in-flight chunk so headroom returns to 0.
        val sentences = listOf(
            Sentence(0, 0, 10, "One."),
            Sentence(1, 11, 20, "Two."),
        )
        val fakeEngine = FakeVoiceEngine(22050) { _ -> ByteArray(44100) }
        val source = EngineStreamingSource(sentences, 0, fakeEngine, 1f, 1f, Mutex())

        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline &&
            source.bufferHeadroomMs.value < 2_000) {
            delay(10)
        }

        // Dequeue one chunk and DON'T decrement it — this models the
        // consumer aborting between dequeue and its post-write decrement.
        val taken = source.nextChunk()!!
        assertEquals(0, taken.sentenceIndex)
        val beforeClose = source.bufferHeadroomMs.value
        assert(beforeClose > 0) {
            "precondition: headroom should be non-zero before close"
        }
        // The duration the dequeued (in-flight) chunk contributed — this is
        // exactly what close() should reclaim. Derive it the same way the
        // source does (pcmDurationMs floors per-segment) so the assertion
        // doesn't depend on a hand-computed value that ignores int rounding.
        val sr = source.sampleRate.toLong()
        fun durMs(bytes: Int) = bytes.toLong() * 1000L / (sr * 2L)
        val inFlightDurMs = durMs(taken.pcm.size) + durMs(taken.trailingSilenceBytes)

        source.close()

        // close() settles ONLY the in-flight (dequeued) chunk; chunks still
        // sitting in the queue are discarded with the pipeline and their
        // counter contribution goes away with the source. The observable
        // invariant: headroom dropped by exactly the in-flight chunk's
        // duration — the phantom increment #906 used to leak is gone.
        assertEquals(beforeClose - inFlightDurMs, source.bufferHeadroomMs.value)
    }

    @Test
    fun `decrementHeadroomForChunk is idempotent for the same chunk`() = runBlocking {
        // #906 — a double decrement (e.g. the consumer's post-write call
        // racing close()'s settle call) must not subtract the chunk's
        // duration twice. The identity guard against inFlightChunk makes the
        // second call a no-op.
        val sentences = listOf(
            Sentence(0, 0, 10, "One."),
            Sentence(1, 11, 20, "Two."),
        )
        val fakeEngine = FakeVoiceEngine(22050) { _ -> ByteArray(44100) }
        val source = EngineStreamingSource(sentences, 0, fakeEngine, 1f, 1f, Mutex())

        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline &&
            source.bufferHeadroomMs.value < 2_000) {
            delay(10)
        }
        val before = source.bufferHeadroomMs.value

        val chunk = source.nextChunk()!!
        source.decrementHeadroomForChunk(chunk)
        val afterOnce = source.bufferHeadroomMs.value
        // Second call for the same chunk must change nothing.
        source.decrementHeadroomForChunk(chunk)
        val afterTwice = source.bufferHeadroomMs.value

        assertEquals(
            "double decrement of the same chunk must be a no-op",
            afterOnce, afterTwice,
        )
        assert(afterOnce < before) {
            "first decrement should have lowered headroom ($before → $afterOnce)"
        }

        source.close()
    }
}

private class FakeVoiceEngine(
    override val sampleRate: Int,
    val pcmFor: (String) -> ByteArray,
) : EngineStreamingSource.VoiceEngineHandle {
    override fun generateAudioPCM(text: String, speed: Float, pitch: Float): ByteArray? = pcmFor(text)
}
