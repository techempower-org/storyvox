package `in`.jphe.storyvox.feature.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.feature.api.DebugRepositoryUi
import `in`.jphe.storyvox.feature.api.DebugSnapshot
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Surface for the debug screen + overlay. Owns:
 *  - the live snapshot (1 Hz heartbeat from [DebugRepositoryUi])
 *  - the overlay master switch (persisted in [SettingsRepositoryUi])
 *  - the one-shot "export snapshot to clipboard" action
 *
 * One ViewModel serves both surfaces because both consume the same
 * snapshot stream. Hilt gives us one instance per screen — when the
 * overlay is mounted above the reader it gets its own instance, but
 * the underlying [DebugRepositoryUi] is a singleton so the flow seam
 * still shares the combiner.
 */
@HiltViewModel
class DebugViewModel @Inject constructor(
    private val debug: DebugRepositoryUi,
    private val settings: SettingsRepositoryUi,
) : ViewModel() {

    /** Always-on; the screen and overlay both observe this. */
    val snapshot: StateFlow<DebugSnapshot> = debug.snapshot.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        DebugSnapshot.EMPTY,
    )

    /**
     * Master switch state. Reads from the settings flow so any toggle
     * from the Settings → Developer section propagates to whatever
     * shell currently mounts the overlay.
     */
    val overlayEnabled: StateFlow<Boolean> = settings.settings
        .map { it.showDebugOverlay }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Writes through to DataStore. */
    fun setOverlayEnabled(enabled: Boolean) = viewModelScope.launch {
        settings.setShowDebugOverlay(enabled)
    }

    /**
     * Issue #823 — verbose-logging master switch. Reads from the
     * settings flow; [StoryvoxApp][in.jphe.storyvox.StoryvoxApp]
     * propagates the same value into
     * [DebugLog][in.jphe.storyvox.data.log.DebugLog] on every change.
     */
    val debugLoggingEnabled: StateFlow<Boolean> = settings.settings
        .map { it.debugLogging }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Writes through to DataStore. */
    fun setDebugLoggingEnabled(enabled: Boolean) = viewModelScope.launch {
        settings.setDebugLogging(enabled)
    }

    /** Suspending one-shot capture for clipboard export — invoked by the
     *  Debug screen's "Copy snapshot" button. The screen wraps this in
     *  a coroutine so the suspend is invisible. */
    suspend fun captureForExport(): DebugSnapshot = debug.captureSnapshot()
}
