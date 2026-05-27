package `in`.jphe.storyvox.playback

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

interface VolumeRamp {
    /** v in [0f, 1f]. Applied at next sentence boundary. */
    fun set(v: Float)
}

@Singleton
class SleepTimer @Inject constructor(
    private val volumeRamp: VolumeRamp,
    private val pauseAction: PauseAction,
) {
    private var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    @VisibleForTesting
    internal constructor(
        volumeRamp: VolumeRamp,
        pauseAction: PauseAction,
        scope: CoroutineScope,
    ) : this(volumeRamp, pauseAction) {
        this.scope = scope
    }

    private val _remainingMs = MutableStateFlow<Long?>(null)
    val remainingMs: StateFlow<Long?> = _remainingMs.asStateFlow()

    /**
     * Issue #34: chapter-end signal uses `replay = 1` so a
     * [signalChapterEnd] fired in the millisecond before
     * [start] subscribes is still observable. The previous
     * `replay = 0, extraBufferCapacity = 1` shape silently dropped that
     * race.
     *
     * The replay cache MUST be cleared in [cancel] and at the end of
     * [fadeAndPause] so a stale signal from a previously-played chapter
     * doesn't fire a freshly-armed EndOfChapter timer immediately. The
     * cache is logically tied to the active timer's lifetime, not the
     * SharedFlow's process lifetime.
     */
    private val chapterEndSignal = MutableSharedFlow<Unit>(replay = 1)
    val chapterEnd: SharedFlow<Unit> = chapterEndSignal.asSharedFlow()

    /** Called when the last sentence of a chapter finishes. */
    fun signalChapterEnd() {
        chapterEndSignal.tryEmit(Unit)
    }

    fun start(mode: SleepTimerMode) {
        // Tear down any prior job WITHOUT clearing the replay cache —
        // a chapter-end signal that arrived in the millisecond before
        // this start() call must survive into the new job's
        // chapterEndSignal.first() collector. Cache-clearing is
        // user-cancel-driven (see [cancel]) or fade-completion-driven
        // (see [fadeAndPause]).
        job?.cancel()
        job = null
        _remainingMs.value = null
        volumeRamp.set(1.0f)
        job = scope.launch {
            when (mode) {
                is SleepTimerMode.Duration -> runCountdown(mode.minutes * 60_000L)
                SleepTimerMode.EndOfChapter -> {
                    chapterEndSignal.first()
                    fadeAndPause()
                }
            }
        }
    }

    private suspend fun runCountdown(totalMs: Long) {
        var remaining = totalMs
        while (remaining > FADE_TAIL_MS) {
            _remainingMs.value = remaining
            delay(TICK_MS)
            remaining -= TICK_MS
        }
        fadeAndPause()
    }

    private suspend fun fadeAndPause() {
        val steps = (FADE_TAIL_MS / FADE_STEP_MS).toInt()
        for (step in 0..steps) {
            val v = (steps - step).toFloat() / steps
            volumeRamp.set(v)
            _remainingMs.value = ((steps - step) * FADE_STEP_MS).toLong()
            delay(FADE_STEP_MS)
        }
        pauseAction.invoke()
        volumeRamp.set(1.0f)
        _remainingMs.value = null
        // Drop the consumed signal so a future EndOfChapter timer
        // doesn't see a stale value (issue #34).
        chapterEndSignal.resetReplayCache()
    }

    fun cancel() {
        job?.cancel()
        job = null
        _remainingMs.value = null
        volumeRamp.set(1.0f)
        // Drop any cached chapter-end signal — the user explicitly
        // dismissed the timer, so a stale signal from a chapter that
        // ended while the timer was active should not arm the next
        // timer (issue #34).
        chapterEndSignal.resetReplayCache()
    }

    fun interface PauseAction {
        operator fun invoke()
    }

    companion object {
        private const val TICK_MS = 1_000L
        private const val FADE_TAIL_MS = 10_000L
        private const val FADE_STEP_MS = 100L
    }
}
