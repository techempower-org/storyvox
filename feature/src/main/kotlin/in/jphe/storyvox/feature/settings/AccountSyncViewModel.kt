package `in`.jphe.storyvox.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.sync.client.InstantSession
import `in`.jphe.storyvox.sync.client.SignedInUser
import `in`.jphe.storyvox.sync.coordinator.SyncCoordinator
import `in`.jphe.storyvox.sync.coordinator.SyncStatus
import `in`.jphe.storyvox.sync.domain.PassphraseManager
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class AccountSyncState(
    val signedInUser: SignedInUser? = null,
    val domainStatuses: Map<String, SyncStatus> = emptyMap(),
    val passphraseSet: Boolean = false,
    val anySyncing: Boolean = false,
)

@HiltViewModel
class AccountSyncViewModel @Inject constructor(
    private val session: InstantSession,
    private val coordinator: SyncCoordinator,
    private val passphraseManager: PassphraseManager,
) : ViewModel() {

    private val passphraseTick = MutableStateFlow(0)

    val state: StateFlow<AccountSyncState> = combine(
        session.signedIn,
        coordinator.status,
        passphraseTick,
    ) { user, statuses, _ ->
        AccountSyncState(
            signedInUser = user,
            domainStatuses = statuses,
            passphraseSet = passphraseManager.isSet(),
            anySyncing = statuses.values.any { it is SyncStatus.Running },
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AccountSyncState(
            signedInUser = session.current(),
            passphraseSet = passphraseManager.isSet(),
        ),
    )

    fun setPassphrase(value: CharArray) {
        passphraseManager.set(value)
        value.fill(' ')
        passphraseTick.update { it + 1 }
    }

    fun clearPassphrase() {
        passphraseManager.clear()
        passphraseTick.update { it + 1 }
    }

    fun syncNow() {
        coordinator.syncNow()
    }
}
