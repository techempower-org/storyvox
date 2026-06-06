package `in`.jphe.storyvox.playback.diagnostics

import android.content.Context
import android.media.AudioManager
import `in`.jphe.storyvox.playback.AudioFocusController
import `in`.jphe.storyvox.playback.EngineState
import `in`.jphe.storyvox.playback.PlaybackController
import `in`.jphe.storyvox.playback.PlaybackState
import javax.inject.Provider
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * "Why are we waiting?" diagnostic — pin the core diagnose() decision
 * tree. The full lifecycle (tick loop, audio-route callbacks) is hard
 * to exercise without an instrumented test; the diagnose() function is
 * pure-ish (reads volatile flags, AudioManager state) and covers the
 * decision matrix the architecture brief specs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AudioOutputMonitorTest {

    private fun monitor(focus: AudioFocusController? = null, audible: Boolean = true): AudioOutputMonitor {
        val ctx = RuntimeEnvironment.getApplication()
        if (audible) {
            // Robolectric's STREAM_MUSIC defaults to volume 0, which trips
            // the DeviceMuted branch ahead of the case-under-test. Raise it.
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, max / 2, 0)
        }
        val af = focus ?: AudioFocusController(ctx)
        val controller = StubController()
        return AudioOutputMonitor(
            context = ctx,
            controllerProvider = Provider { controller },
            audioFocus = af,
        )
    }

    @Test
    fun `diagnose returns null for engine Idle`() {
        val m = monitor()
        val r = m.diagnose(
            state = PlaybackState(),
            engine = EngineState.Idle,
            positionMs = 0L,
        )
        assertNull("Idle is handled by other UI surfaces", r)
    }

    @Test
    fun `diagnose returns null for engine Paused`() {
        val m = monitor()
        val r = m.diagnose(
            state = PlaybackState(currentChapterId = "c1"),
            engine = EngineState.Paused,
            positionMs = 1000L,
        )
        assertNull(r)
    }

    @Test
    fun `diagnose returns WarmingVoice when engine is Warming`() {
        val af = AudioFocusController(RuntimeEnvironment.getApplication())
        af.acquire()
        val m = monitor(af)
        val r = m.diagnose(
            state = PlaybackState(voiceId = "kokoro-en-US-brian", currentChapterId = "c1"),
            engine = EngineState.Warming("Warming Brian"),
            positionMs = 0L,
        )
        assertTrue("Warming should surface WarmingVoice — got $r", r is WaitReason.WarmingVoice)
    }

    @Test
    fun `diagnose returns BufferingNextSentence on mid-chapter buffer`() {
        val af = AudioFocusController(RuntimeEnvironment.getApplication())
        af.acquire()
        val m = monitor(af)
        val r = m.diagnose(
            state = PlaybackState(
                currentChapterId = "c1",
                chapterTitle = "Chapter 1",
                voiceId = "v1",
            ),
            engine = EngineState.Buffering,
            positionMs = 5000L,
        )
        assertTrue(
            "Mid-chapter buffering should be BufferingNextSentence — got $r",
            r is WaitReason.BufferingNextSentence,
        )
    }

    @Test
    fun `diagnose returns LoadingChapter when buffering with no chapter title`() {
        val af = AudioFocusController(RuntimeEnvironment.getApplication())
        af.acquire()
        val m = monitor(af)
        val r = m.diagnose(
            state = PlaybackState(currentChapterId = null, chapterTitle = null),
            engine = EngineState.Buffering,
            positionMs = 0L,
        )
        assertTrue(
            "Pre-chapter buffering should be LoadingChapter — got $r",
            r is WaitReason.LoadingChapter,
        )
    }

    @Test
    fun `diagnose returns DeviceMuted when STREAM_MUSIC volume is zero`() {
        val af = AudioFocusController(RuntimeEnvironment.getApplication())
        af.acquire()
        val m = monitor(af, audible = false) // leave volume at 0
        val r = m.diagnose(
            state = PlaybackState(currentChapterId = "c1", chapterTitle = "x"),
            engine = EngineState.Playing,
            positionMs = 0L,
        )
        assertEquals(WaitReason.DeviceMuted, r)
    }

    @Test
    fun `guessFocusCause defaults to another app when audio mode is normal`() {
        val m = monitor()
        val cause = m.guessFocusCause()
        assertEquals("another app", cause)
    }

    @Test
    fun `WaitReason AudioOutputStuck escalates user-actionable past threshold`() {
        val short = WaitReason.AudioOutputStuck(secondsSilent = 1)
        val long = WaitReason.AudioOutputStuck(secondsSilent = 6)
        assertTrue("under 4s should not be actionable", !short.isRecoverable)
        assertTrue("over 4s should be actionable", long.isRecoverable)
    }

    /**
     * Minimal PlaybackController stub for the test — every flow is a
     * trivial constant. The diagnose() test path doesn't touch any
     * mutator so we don't need to record calls.
     */
    private class StubController : PlaybackController {
        override val state: StateFlow<PlaybackState> = MutableStateFlow(PlaybackState()).asStateFlow()
        override val events: SharedFlow<`in`.jphe.storyvox.playback.PlaybackUiEvent> =
            MutableSharedFlow<`in`.jphe.storyvox.playback.PlaybackUiEvent>().asSharedFlow()
        override val engineState: StateFlow<EngineState> =
            MutableStateFlow<EngineState>(EngineState.Idle).asStateFlow()
        override val playbackPositionMs: StateFlow<Long> = MutableStateFlow(0L).asStateFlow()
        override val chapterDurationMs: StateFlow<Long> = MutableStateFlow(0L).asStateFlow()
        override val recapPlayback: StateFlow<`in`.jphe.storyvox.playback.tts.RecapPlaybackState> =
            MutableStateFlow(`in`.jphe.storyvox.playback.tts.RecapPlaybackState.Idle).asStateFlow()
        override val waitReason: StateFlow<WaitReason?> =
            MutableStateFlow<WaitReason?>(null).asStateFlow()

        override suspend fun play(fictionId: String, chapterId: String, charOffset: Int) = Unit
        override fun pause() = Unit
        override fun resume() = Unit
        override fun togglePlayPause() = Unit
        override fun seekTo(charOffset: Int) = Unit
        override fun seekToPositionMs(positionMs: Long) = Unit
        override fun skipForward30s() = Unit
        override fun skipBack30s() = Unit
        override fun prewarmEngine() = Unit
        override fun nextSentence() = Unit
        override fun previousSentence() = Unit
        override fun nextParagraph() = Unit
        override fun previousParagraph() = Unit
        override suspend fun nextChapter() = Unit
        override suspend fun previousChapter() = Unit
        override suspend fun jumpToChapter(chapterId: String) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun setPitch(pitch: Float) = Unit
        override fun setPunctuationPauseMultiplier(multiplier: Float) = Unit
        override fun startSleepTimer(mode: `in`.jphe.storyvox.playback.SleepTimerMode) = Unit
        override fun cancelSleepTimer() = Unit
        override fun toggleSleepTimer() = Unit
        override fun setShakeToExtendEnabled(enabled: Boolean) = Unit
        override suspend fun speakText(text: String) = Unit
        override fun stopSpeaking() = Unit
        override fun bufferTelemetry() = `in`.jphe.storyvox.playback.BufferTelemetry()
        override suspend fun bookmarkHere() = Unit
        override suspend fun clearBookmark() = Unit
        override suspend fun jumpToBookmark() = false
    }
}
