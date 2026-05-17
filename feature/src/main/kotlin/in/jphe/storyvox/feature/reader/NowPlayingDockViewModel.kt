package `in`.jphe.storyvox.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import `in`.jphe.storyvox.feature.api.PlaybackControllerUi
import `in`.jphe.storyvox.feature.api.UiPlaybackState

/**
 * Issue #678 — VM backing [NowPlayingDock].
 *
 * Surfaces the bits of [UiPlaybackState] the dock actually renders
 * (fictionId / chapterId / titles / cover / isPlaying / position +
 * duration), and exposes two transport actions. Lives in the feature
 * module so it can sit on top of the existing [PlaybackControllerUi]
 * contract without leaking the core-playback module into the host's
 * navigation graph.
 *
 * Scoping: the VM is hoisted at the [`in`.jphe.storyvox.navigation.StoryvoxNavHost]
 * level via `hiltViewModel()` rather than per-screen, so the dock's
 * Hilt VM survives tab switches and the underlying StateFlow stays
 * subscribed (no re-collection cost when crossing Library → Browse →
 * Follows).
 */
@HiltViewModel
class NowPlayingDockViewModel @Inject constructor(
    private val playback: PlaybackControllerUi,
) : ViewModel() {

    /** Mirrors [PlaybackControllerUi.state] as a hot StateFlow so the
     *  dock can read a synchronous initial value at first composition
     *  rather than blanking for a frame while the cold Flow primes. */
    val state: StateFlow<UiPlaybackState> = playback.state
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = EMPTY_STATE,
        )

    /** Toggle transport without leaving the current surface. */
    fun togglePlayPause() {
        val snapshot = state.value
        if (snapshot.isPlaying) playback.pause() else playback.play()
    }

    /** Advance to the next chapter in place. Same semantics as the
     *  Reader's next-chapter button — the player's state flow pushes
     *  the new chapter title + cover into the dock automatically. */
    fun nextChapter() {
        viewModelScope.launch { playback.nextChapter() }
    }

    companion object {
        // Why: nullable fictionId/chapterId are the dock's visibility
        // gate — keep them null in the seed value so a tab-switch
        // before the controller's flow has emitted doesn't flash a
        // dock on top of a stale state.
        private val EMPTY_STATE = UiPlaybackState(
            fictionId = null,
            chapterId = null,
            chapterTitle = "",
            fictionTitle = "",
            coverUrl = null,
            isPlaying = false,
            positionMs = 0L,
            durationMs = 0L,
            sentenceStart = 0,
            sentenceEnd = 0,
            speed = 1.0f,
            pitch = 1.0f,
            voiceId = null,
            voiceLabel = "Default",
        )
    }
}
