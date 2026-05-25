package `in`.jphe.storyvox.data.log

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Issue #823 — process-wide verbose-logging toggle.
 *
 * Default-off lightweight tracer for the playback + chapter-download
 * pipeline. Errors and critical events keep going through `Log.e` /
 * `Log.w` unconditionally; this helper only gates the Info-level
 * "what step just happened" breadcrumbs that the issue lists as the
 * subsystems worth tracing when something goes wrong on device:
 *
 *  - **ChapterDownloadScheduler** — schedule / complete / fail with
 *    chapter ids
 *  - **ChapterDownloadWorker** — doWork entry, result, retry reason
 *  - **EnginePlayer** — loadAndPlay, advanceChapter, voice load,
 *    pipeline start/stop
 *  - **PlaybackController** — play / pause / seek with position info
 *
 * Why an [AtomicBoolean] rather than reading the flow on every call:
 * playback log call sites can fire many times per second
 * (EnginePlayer's sentence loop, the audio queue, etc.) — going
 * through a coroutine-collected `StateFlow` per-call would add latency
 * to the hot path even when logging is off. `AtomicBoolean.get()` is a
 * single volatile read.
 *
 * Wiring: [StoryvoxApp][in.jphe.storyvox.StoryvoxApp] collects the
 * `debugLogging` flag from
 * [SettingsRepositoryUi][in.jphe.storyvox.feature.api.SettingsRepositoryUi]
 * and calls [setEnabled] on every emission. The Settings → Developer
 * "Verbose logging" toggle is the user-facing surface.
 *
 * When disabled, call sites short-circuit before any string
 * formatting — pass eagerly-built messages only at sites where the
 * string is already cheap, and use the lambda overloads ([i], [d])
 * everywhere else.
 */
object DebugLog {

    private val enabled = AtomicBoolean(false)

    /** Read the current toggle state. Cheap (single volatile read). */
    fun isEnabled(): Boolean = enabled.get()

    /**
     * Settings collector calls this on every emission of
     * `UiSettings.debugLogging`. Idempotent — re-setting the same
     * value is a no-op at the [AtomicBoolean] level.
     */
    fun setEnabled(value: Boolean) {
        enabled.set(value)
    }

    /**
     * Info-level breadcrumb. The [message] lambda is only invoked
     * when verbose logging is on, so callers pay nothing for string
     * construction on the disabled path.
     */
    inline fun i(tag: String, message: () -> String) {
        if (isEnabled()) Log.i(tag, message())
    }

    /**
     * Debug-level breadcrumb. Same gating contract as [i]; use for
     * the noisiest trace points (per-sentence, per-buffer) so they
     * stay off `logcat` even when a developer flips the toggle for a
     * narrower trace via [i] only.
     */
    inline fun d(tag: String, message: () -> String) {
        if (isEnabled()) Log.d(tag, message())
    }
}
