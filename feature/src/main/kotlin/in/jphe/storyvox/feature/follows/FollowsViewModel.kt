package `in`.jphe.storyvox.feature.follows

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.feature.api.FictionRepositoryUi
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import `in`.jphe.storyvox.feature.api.UiFollow
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class FollowsUiState(
    val follows: List<UiFollow> = emptyList(),
    val isRefreshing: Boolean = true,
    val isSignedIn: Boolean = false,
)

sealed interface FollowsUiEvent {
    data class OpenFiction(val fictionId: String) : FollowsUiEvent
    data object CaughtUp : FollowsUiEvent
}

@HiltViewModel
class FollowsViewModel @Inject constructor(
    private val repo: FictionRepositoryUi,
    settings: SettingsRepositoryUi,
) : ViewModel() {

    private val _events = Channel<FollowsUiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** Tracks whether refreshFollows() is in flight so the screen can show
     *  the magical sigil grid instead of a blank tab while the RR fetch is
     *  pending. The Room follow flow emits emptyList immediately, so we
     *  can't infer "loading" from absence of data alone. */
    private val refreshing = MutableStateFlow(true)

    private val signedIn = settings.settings.map { it.isSignedIn }.distinctUntilChanged()

    init {
        // Lazy refresh on tab visit — pulls the user's RR follows list into
        // the local DB. Silent on failure (unauthed users keep an empty tab,
        // and the screen renders a sign-in CTA instead).
        viewModelScope.launch {
            try {
                repo.refreshFollows()
            } finally {
                refreshing.value = false
            }
        }
    }

    val uiState: StateFlow<FollowsUiState> =
        combine(repo.follows, refreshing, signedIn) { list, busy, authed ->
            FollowsUiState(follows = list, isRefreshing = busy, isSignedIn = authed)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FollowsUiState())

    fun open(fictionId: String) {
        viewModelScope.launch { _events.send(FollowsUiEvent.OpenFiction(fictionId)) }
    }

    fun markAllCaughtUp() {
        viewModelScope.launch {
            // Issue #982 — markAllCaughtUp now actually persists the read-status
            // flip and returns the number of chapters it transitioned. The
            // CaughtUp event still fires unconditionally: pressing the button
            // when you're already caught up (count == 0) is a no-op write but a
            // legitimately "caught up" state, so the confirmation is honest. The
            // bug it fixed was claiming success while writing *nothing* — that's
            // gone now that the repository write runs first.
            repo.markAllCaughtUp()
            _events.send(FollowsUiEvent.CaughtUp)
        }
    }
}
