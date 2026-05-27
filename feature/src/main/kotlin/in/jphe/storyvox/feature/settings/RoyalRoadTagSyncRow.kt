package `in`.jphe.storyvox.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.feature.api.RoyalRoadTagSyncUi
import `in`.jphe.storyvox.feature.api.UiTagSyncOutcome
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.theme.LocalSpacing
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Settings → Account row for "Sync saved tags with Royal Road"
 * (issue #178).
 *
 * Surfaces three affordances:
 *  - A status text: "Last synced with RR: <relative time>" (or
 *    "never" if no sync has happened, or "off" if disabled).
 *  - A toggle: turn the periodic sync on or off
 *    (`pref_rr_tag_sync_enabled`).
 *  - A "Sync now" brass button that fires
 *    [RoyalRoadTagSyncCoordinator.syncNow] immediately. Useful
 *    for power users who want to test the round-trip after
 *    flipping a tag.
 *
 * Lives in its own file/ViewModel so the existing massive
 * [SettingsViewModel] doesn't need a new constructor parameter.
 * The composable expects to be called from inside an existing
 * `SettingsGroupCard` (it doesn't paint its own card chrome).
 */
@HiltViewModel
class RoyalRoadTagSyncViewModel @Inject internal constructor(
    private val tagSync: RoyalRoadTagSyncUi,
) : ViewModel() {

    data class State(
        val enabled: Boolean = true,
        val lastSyncedAt: Long = 0L,
        val syncInFlight: Boolean = false,
        val lastOutcome: String? = null,
    )

    private val inFlight = MutableStateFlow(false)
    private val lastOutcome = MutableStateFlow<String?>(null)

    val state: StateFlow<State> = combine(
        tagSync.syncEnabled,
        tagSync.lastSyncedAt,
        inFlight,
        lastOutcome,
    ) { enabled, lastSyncedAt, busy, outcome ->
        State(
            enabled = enabled,
            lastSyncedAt = lastSyncedAt,
            syncInFlight = busy,
            lastOutcome = outcome,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State())

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { tagSync.setSyncEnabled(enabled) }
    }

    fun syncNow() {
        viewModelScope.launch {
            if (inFlight.value) return@launch
            inFlight.value = true
            try {
                val outcome = tagSync.syncNow()
                lastOutcome.value = describeOutcome(outcome)
            } finally {
                inFlight.value = false
            }
        }
    }

    private fun describeOutcome(outcome: UiTagSyncOutcome): String =
        when (outcome) {
            is UiTagSyncOutcome.Ok ->
                "Synced — pulled ${outcome.tagsPulledIn} tag(s), pushed ${outcome.tagsPushedOut}"
            UiTagSyncOutcome.NotAuthenticated ->
                "Sign in to Royal Road to sync"
            UiTagSyncOutcome.Disabled ->
                "Sync is turned off"
            is UiTagSyncOutcome.Failed ->
                "Sync failed — will retry"
        }
}

/**
 * Compose-side widget. Renders inside an existing
 * [SettingsGroupCard]; expects an outer signed-in gate so it
 * doesn't show on signed-out users (the affordance is
 * meaningless without a session).
 */
@Composable
fun RoyalRoadTagSyncRow(
    viewModel: RoyalRoadTagSyncViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsState()
    val spacing = LocalSpacing.current
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.md, vertical = spacing.sm)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Sync saved tags with Royal Road",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = lastSyncedLine(s),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = s.enabled,
                onCheckedChange = { viewModel.setEnabled(it) },
            )
        }

        Spacer(Modifier.height(spacing.xs))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Surfaces the last-sync outcome (if any) — useful for
            // power users diagnosing why their tag set didn't move.
            // Most users will never glance at this row.
            s.lastOutcome?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            } ?: Spacer(Modifier.weight(1f))

            BrassButton(
                label = if (s.syncInFlight) "Syncing…" else "Sync now",
                onClick = { viewModel.syncNow() },
                variant = BrassButtonVariant.Text,
                enabled = s.enabled && !s.syncInFlight,
            )
        }
    }
}

/** Format a "Last synced" status line for the row. */
private fun lastSyncedLine(s: RoyalRoadTagSyncViewModel.State): String {
    if (!s.enabled) return "Off"
    if (s.lastSyncedAt == 0L) return "Last synced with RR: never"
    val rel = relativeTimeSince(s.lastSyncedAt, System.currentTimeMillis())
    return "Last synced with RR: $rel"
}

/** Coarse-grained relative-time formatter. Picks the largest unit
 *  that fits — "3 days ago", "5 hours ago", "just now". Kept here
 *  rather than pulled in from `DateUtils` to keep the row pure-
 *  Kotlin and unit-testable. */
private fun relativeTimeSince(then: Long, now: Long): String {
    val diff = (now - then).coerceAtLeast(0L)
    val sec = diff / 1_000L
    val min = sec / 60L
    val hr = min / 60L
    val day = hr / 24L
    return when {
        sec < 30L -> "just now"
        sec < 60L -> "${sec}s ago"
        min < 60L -> "${min}m ago"
        hr < 24L -> "${hr}h ago"
        day < 30L -> "${day}d ago"
        else -> "long ago"
    }
}
