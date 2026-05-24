package `in`.jphe.storyvox.feature.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.sync.coordinator.SyncStatus
import `in`.jphe.storyvox.ui.theme.LocalSpacing

@Composable
internal fun DomainStatusRow(domain: String, status: SyncStatus) {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.xs / 2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        DomainStatusIcon(status = status)
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = domain.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = describeSyncStatus(status),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun DomainStatusIcon(status: SyncStatus) {
    val tint: Color
    val icon = when (status) {
        SyncStatus.Idle -> {
            tint = MaterialTheme.colorScheme.onSurfaceVariant
            Icons.Filled.HelpOutline
        }
        SyncStatus.Running -> {
            tint = MaterialTheme.colorScheme.primary
            Icons.Filled.Sync
        }
        is SyncStatus.OkAt -> {
            tint = MaterialTheme.colorScheme.primary
            Icons.Filled.CheckCircle
        }
        is SyncStatus.Transient -> {
            tint = MaterialTheme.colorScheme.tertiary
            Icons.Filled.Error
        }
        is SyncStatus.Permanent -> {
            tint = MaterialTheme.colorScheme.error
            Icons.Filled.Error
        }
    }
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(18.dp),
    )
}

internal fun describeSyncStatus(status: SyncStatus): String = when (status) {
    SyncStatus.Idle -> "Idle"
    SyncStatus.Running -> "Syncing…"
    is SyncStatus.OkAt -> "Synced (${status.records} record${if (status.records == 1) "" else "s"})"
    is SyncStatus.Transient -> "Retrying: ${status.message}"
    is SyncStatus.Permanent -> "Stopped: ${status.message}"
}
