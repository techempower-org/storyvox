package `in`.jphe.storyvox.playback.tts

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #676 — pure JVM unit tests for [SystemTtsEngine]'s deterministic
 * surfaces.
 *
 * The full [SystemTtsEngine] lifecycle (TextToSpeech construction +
 * onInit + synthesizeToFile + UtteranceProgressListener) requires a
 * real Android runtime — those paths are covered by manual on-device
 * verification + the broader instrumented test pass when the picker
 * acceptance walk-through lands. What's left to JVM unit tests is the
 * WAV-header parser, which is the only piece of the engine where a
 * silent wrong-direction failure (cached wrong sample rate → AudioTrack
 * tuned to wrong rate → audibly chipmunked playback) would be
 * particularly hard to diagnose post-ship.
 */
class SystemTtsEngineTest {

    @Test fun `parseWavSampleRate decodes the 4-byte little-endian field at offset 24`() {
        // Canonical 44-byte RIFF/WAVE header with 24 kHz sample rate.
        // We only set the fields parseWavSampleRate inspects (RIFF +
        // WAVE magic, sample rate); the rest is left at 0 because the
        // parser doesn't read it.
        val header = ByteArray(44).apply {
            // "RIFF" magic
            this[0] = 'R'.code.toByte()
            this[1] = 'I'.code.toByte()
            this[2] = 'F'.code.toByte()
            this[3] = 'F'.code.toByte()
            // "WAVE" at offset 8
            this[8] = 'W'.code.toByte()
            this[9] = 'A'.code.toByte()
            this[10] = 'V'.code.toByte()
            this[11] = 'E'.code.toByte()
            // 24000 little-endian at offset 24 == 0x5DC0
            // 24000 = 0x00005DC0 → bytes [0xC0, 0x5D, 0x00, 0x00]
            this[24] = 0xC0.toByte()
            this[25] = 0x5D.toByte()
            this[26] = 0x00.toByte()
            this[27] = 0x00.toByte()
        }
        assertEquals(24_000, SystemTtsEngine.parseWavSampleRate(header))
    }

    @Test fun `parseWavSampleRate decodes 16kHz (eSpeak default) correctly`() {
        val header = ByteArray(44).apply {
            this[0] = 'R'.code.toByte(); this[1] = 'I'.code.toByte()
            this[2] = 'F'.code.toByte(); this[3] = 'F'.code.toByte()
            this[8] = 'W'.code.toByte(); this[9] = 'A'.code.toByte()
            this[10] = 'V'.code.toByte(); this[11] = 'E'.code.toByte()
            // 16000 = 0x00003E80 → bytes [0x80, 0x3E, 0x00, 0x00]
            this[24] = 0x80.toByte()
            this[25] = 0x3E.toByte()
        }
        assertEquals(16_000, SystemTtsEngine.parseWavSampleRate(header))
    }

    @Test fun `parseWavSampleRate decodes 48kHz (Samsung high-quality voices)`() {
        val header = ByteArray(44).apply {
            this[0] = 'R'.code.toByte(); this[1] = 'I'.code.toByte()
            this[2] = 'F'.code.toByte(); this[3] = 'F'.code.toByte()
            this[8] = 'W'.code.toByte(); this[9] = 'A'.code.toByte()
            this[10] = 'V'.code.toByte(); this[11] = 'E'.code.toByte()
            // 48000 = 0x0000BB80 → bytes [0x80, 0xBB, 0x00, 0x00]
            this[24] = 0x80.toByte()
            this[25] = 0xBB.toByte()
        }
        assertEquals(48_000, SystemTtsEngine.parseWavSampleRate(header))
    }

    @Test fun `parseWavSampleRate returns 0 on a too-short buffer`() {
        // 43-byte buffer (one short of the canonical header) should
        // fail the size guard — better to surface 0 (caller falls back
        // to DEFAULT_SAMPLE_RATE) than read garbage from out-of-bounds.
        assertEquals(0, SystemTtsEngine.parseWavSampleRate(ByteArray(43)))
    }

    @Test fun `parseWavSampleRate returns 0 on a non-RIFF buffer`() {
        // A buffer that's long enough but lacks the RIFF magic — a
        // synthesizeToFile bug or a malformed temp file. We'd rather
        // return 0 than misread random bytes (e.g. an HTTP-error body
        // somehow saved to the temp path) as a sample rate.
        val notRiff = ByteArray(44)
        // Leave all bytes 0 — definitely not "RIFF".
        assertEquals(0, SystemTtsEngine.parseWavSampleRate(notRiff))
    }

    @Test fun `parseWavSampleRate returns 0 on RIFF without WAVE`() {
        val riffButNotWave = ByteArray(44).apply {
            this[0] = 'R'.code.toByte()
            this[1] = 'I'.code.toByte()
            this[2] = 'F'.code.toByte()
            this[3] = 'F'.code.toByte()
            // intentionally leave offset 8..11 as zero — not "WAVE"
        }
        assertEquals(0, SystemTtsEngine.parseWavSampleRate(riffButNotWave))
    }

    @Test fun `DEFAULT_SAMPLE_RATE is 24kHz to match Google TTS native output`() {
        // Sanity check the constant doesn't silently drift. Google
        // TTS's default voice emits at 24 kHz, which matches Azure
        // + Kokoro — picking a different default here would cause the
        // first AudioTrack creation to use the wrong rate.
        assertEquals(24_000, SystemTtsEngine.DEFAULT_SAMPLE_RATE)
    }

    @Test fun `WAV_HEADER_BYTES is 44 — the canonical PCM-format header size`() {
        // synthesizeToFile is documented to emit a 16-bit mono LE WAV
        // whose header is exactly 44 bytes. A different value would
        // strip the wrong amount and either include header garbage as
        // audio (audible click) or chop off the first sample (less
        // audible but wrong).
        assertEquals(44, SystemTtsEngine.WAV_HEADER_BYTES)
    }
}
