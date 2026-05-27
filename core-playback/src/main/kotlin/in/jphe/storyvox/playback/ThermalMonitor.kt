package `in`.jphe.storyvox.playback

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.data.log.DebugLog
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Issue #803 — observes the device's thermal status via
 * [PowerManager.addThermalStatusListener] (API 29+) and exposes it as a
 * [StateFlow] that playback components can collect.
 *
 * On API < 29 the flow stays at [PowerManager.THERMAL_STATUS_NONE] (0)
 * permanently — callers treat 0 as "no thermal pressure, use defaults."
 *
 * ## Thermal status values (from PowerManager):
 *  - 0 = THERMAL_STATUS_NONE — no throttling
 *  - 1 = THERMAL_STATUS_LIGHT — light throttling, no user-visible impact
 *  - 2 = THERMAL_STATUS_MODERATE — moderate throttling, noticeable
 *  - 3 = THERMAL_STATUS_SEVERE — severe, device is hot
 *  - 4 = THERMAL_STATUS_CRITICAL — platform may shut down
 *  - 5 = THERMAL_STATUS_EMERGENCY — key components shutting down
 *  - 6 = THERMAL_STATUS_SHUTDOWN — shutdown imminent
 *
 * ## Consumer contract:
 *  - [EnginePlayer][in.jphe.storyvox.playback.tts.EnginePlayer] collects
 *    [thermalStatus] and caps parallel-synth concurrency when the value
 *    reaches MODERATE (2) or above. The user's saved setting is NOT
 *    modified — only the runtime effective value is overridden for the
 *    duration of the thermal event. When the status drops back below
 *    MODERATE, the user's configured concurrency is restored on the
 *    next pipeline rebuild.
 *
 * Lifecycle: `@Singleton` — the listener is registered once per process
 * and never removed. PowerManager holds a weak reference internally, so
 * no leak concern.
 */
@Singleton
class ThermalMonitor @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val _thermalStatus = MutableStateFlow(THERMAL_STATUS_NONE)

    /**
     * Current thermal status. 0 = no thermal pressure (or API < 29).
     * Mirrors the [PowerManager] THERMAL_STATUS_* constants.
     */
    val thermalStatus: StateFlow<Int> = _thermalStatus.asStateFlow()

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null) {
                try {
                    pm.addThermalStatusListener { status ->
                        val prev = _thermalStatus.value
                        _thermalStatus.value = status
                        Log.w(
                            TAG,
                            "#803 thermal status changed: $prev -> $status " +
                                "(${thermalLabel(status)})",
                        )
                        DebugLog.i(TAG) {
                            "#803 thermal status $prev -> $status (${thermalLabel(status)})"
                        }
                    }
                    DebugLog.i(TAG) { "#803 thermal listener registered (API ${Build.VERSION.SDK_INT})" }
                } catch (e: Exception) {
                    // Some OEM ROMs throw on addThermalStatusListener
                    // (seen on MIUI 14 / Xiaomi Redmi Note 12). Degrade
                    // gracefully — flow stays at 0, no thermal throttling.
                    Log.w(TAG, "#803 failed to register thermal listener", e)
                }
            }
        } else {
            DebugLog.i(TAG) { "#803 API ${Build.VERSION.SDK_INT} < 29, thermal monitoring disabled" }
        }
    }

    companion object {
        private const val TAG = "ThermalMonitor"

        /** Mirror of [PowerManager.THERMAL_STATUS_NONE] for use without
         *  a Q+ API-level guard. */
        const val THERMAL_STATUS_NONE = 0

        /** Moderate thermal pressure — parallel synth should drop to
         *  serial mode (1 instance). */
        const val THERMAL_STATUS_MODERATE = 2

        /** Severe thermal pressure — serial mode + reduced queue depth. */
        const val THERMAL_STATUS_SEVERE = 3

        private fun thermalLabel(status: Int): String = when (status) {
            0 -> "NONE"
            1 -> "LIGHT"
            2 -> "MODERATE"
            3 -> "SEVERE"
            4 -> "CRITICAL"
            5 -> "EMERGENCY"
            6 -> "SHUTDOWN"
            else -> "UNKNOWN($status)"
        }
    }
}
