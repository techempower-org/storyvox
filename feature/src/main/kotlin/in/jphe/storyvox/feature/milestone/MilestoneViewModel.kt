package `in`.jphe.storyvox.feature.milestone

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import `in`.jphe.storyvox.feature.api.MilestoneState
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi

/**
 * Calliope (v0.5.00) — the small graduation-celebration ViewModel.
 *
 * Drives the one-time milestone dialog mounted at the navigation
 * host level. The dialog renders when:
 *  - the build is v0.5.00 or later (qualifies),
 *  - the user hasn't yet seen the dialog this install (!dialogSeen).
 *
 * Both gates flip false-forever after first dismissal via DataStore,
 * so reopening the app on the same install doesn't replay the
 * dialog. The confetti easter-egg is handled in a sibling flow
 * inside [`in`.jphe.storyvox.feature.reader.ReaderViewModel] —
 * keeping them in separate VMs lets the dialog mount at the app
 * root while confetti scopes to the reader.
 */
@HiltViewModel
class MilestoneViewModel @Inject constructor(
    private val settings: SettingsRepositoryUi,
) : ViewModel() {

    /** True iff the milestone dialog should be on screen right now.
     *  Collapses MilestoneState's two relevant fields into a single
     *  boolean so the host composable can `if (showDialog) { ... }`
     *  without re-deriving the gate on every recomposition. */
    val showDialog: StateFlow<Boolean> = settings.milestoneState
        .map { it.qualifies && !it.dialogSeen }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Called by the dialog's Continue button (and outside-tap path).
     *  Persists the seen-flag; the [showDialog] flow flips to false
     *  on the next emission, which removes the dialog from the
     *  composition. */
    fun dismiss() {
        viewModelScope.launch { settings.markMilestoneDialogSeen() }
    }

    // ── v1.1.0 "read it your way" milestone (Luna) ─────────────────
    /** True iff the v1.1 "ignite" dialog should be on screen: the
     *  build is inside the v1.1 window and the user hasn't dismissed it
     *  on this install. Independent gate from [showDialog] — the two
     *  milestones never co-fire (disjoint version windows) but carry
     *  separate seen-flags so a user who dismissed the 0.5.00 card
     *  still meets the 1.1 one. */
    val showV110Dialog: StateFlow<Boolean> = settings.milestoneState
        .map { it.v110Qualifies && !it.v110DialogSeen }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Dismiss the v1.1 ignite dialog (Continue or outside-tap).
     *  Persists the seen-flag; [showV110Dialog] flips false next
     *  emission and the dialog leaves the composition. */
    fun dismissV110() {
        viewModelScope.launch { settings.markV110MilestoneDialogSeen() }
    }
}
