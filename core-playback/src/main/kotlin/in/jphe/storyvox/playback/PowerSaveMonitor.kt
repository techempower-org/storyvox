package `in`.jphe.storyvox.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Issue #801 — monitors Android's battery-saver / power-save mode and
 * exposes the current state as a [StateFlow]. Consumers in the playback
 * layer use this to:
 *
 *  - **Pause pre-rendering** ([PrerenderTriggers]) — background chapter
 *    renders are CPU-heavy (~30%+ on Helio P22T per chapter) and should
 *    not run when the OS is explicitly trying to conserve battery.
 *  - **Lower producer thread priority** ([EngineStreamingSource]) — the
 *    producer drives TTS synthesis and normally runs at URGENT_AUDIO so
 *    the consumer's AudioTrack never underruns. In power-save mode the
 *    producer drops to THREAD_PRIORITY_BACKGROUND; the consumer KEEPS
 *    URGENT_AUDIO because audio glitches are worse than battery drain.
 *
 * Lifecycle: `@Singleton` scope — constructed once by Hilt, lives for
 * the process. The [BroadcastReceiver] is registered in the constructor
 * with the application context, so it survives Activity recreation.
 *
 * Initial state is seeded from [PowerManager.isPowerSaveMode] so the
 * flow has a correct value before the first broadcast arrives.
 */
@Singleton
class PowerSaveMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _isPowerSaveMode = MutableStateFlow(queryPowerSaveMode())

    /**
     * `true` when the device is in battery-saver / power-save mode.
     * Updates within milliseconds of the OS broadcast.
     */
    val isPowerSaveMode: StateFlow<Boolean> = _isPowerSaveMode.asStateFlow()

    init {
        val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        context.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val newState = queryPowerSaveMode()
                    _isPowerSaveMode.value = newState
                    Log.i(
                        LOG_TAG,
                        "power-save mode changed: $newState",
                    )
                }
            },
            filter,
        )
        Log.i(LOG_TAG, "initialized — isPowerSaveMode=${_isPowerSaveMode.value}")
    }

    private fun queryPowerSaveMode(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return pm?.isPowerSaveMode ?: false
    }

    companion object {
        private const val LOG_TAG = "PowerSaveMonitor"
    }
}
