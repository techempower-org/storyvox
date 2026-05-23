package `in`.jphe.storyvox.feature.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.ui.theme.LocalSpacing
import java.text.DateFormat
import java.util.Date

/**
 * Issue #218 — Settings → AI → Sessions. List past LLM sessions
 * with provider/model, message count, last-used time, and Open /
 * Delete actions. Free-form chat sessions navigate back into the
 * chat surface for the same fictionId; chapter-recap sessions only
 * support delete (the recap surface is the reader, not a standalone
 * route).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    onBack: () -> Unit,
    onOpenChat: (fictionId: String) -> Unit,
    viewModel: SessionsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current

    var pendingDelete by remember { mutableStateOf<SessionRow?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.sessions_title), style = MaterialTheme.typography.titleMedium)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (state.rows.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No AI sessions yet. Open a chat or generate a chapter recap to start one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = spacing.xl),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                items(state.rows, key = { it.session.id }) { row ->
                    SessionCard(
                        row = row,
                        onOpen = if (row.isFreeFormChat) {
                            { row.session.anchorFictionId?.let(onOpenChat) }
                        } else null,
                        onDelete = { pendingDelete = row },
                    )
                }
            }
        }
    }

    pendingDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.sessions_delete_dialog_title)) },
            text = {
                Text(
                    "Removes the chat history and all messages. Cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSession(row.session.id)
                    pendingDelete = null
                }) { Text(stringResource(R.string.sessions_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.sessions_cancel)) }
            },
        )
    }
}

@Composable
private fun SessionCard(
    row: SessionRow,
    onOpen: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    val spacing = LocalSpacing.current
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(spacing.md)) {
            // Header row — kind icon + fiction title (or session name fallback).
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (icon, label) = when {
                    row.isChapterRecap -> Icons.Outlined.Notes to "Chapter Recap"
                    else -> Icons.Outlined.Chat to "Chat"
                }
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = spacing.sm),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.fictionTitle ?: row.session.name,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Provider + model + last-used metadata row.
            Text(
                text = "${row.session.provider.name} · ${row.session.model}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.xs),
            )
            Text(
                text = "Last used " + formatLastUsed(row.session.lastUsedAt),
                style = MaterialTheme.typography.labelSmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Action row — Open + Delete with the dimmed-state for
            // recap sessions where Open isn't wired (recap modal
            // lives in the reader, not a standalone route).
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = spacing.sm),
                horizontalArrangement = Arrangement.End,
            ) {
                if (onOpen != null) {
                    TextButton(onClick = onOpen) { Text(stringResource(R.string.sessions_open)) }
                }
                TextButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    Text(stringResource(R.string.sessions_delete))
                }
            }
        }
    }
}

/** Render an epoch-millis timestamp as a relative phrase ("2 days
 *  ago", "today"). Java's DateFormat gives us a localized fallback
 *  for older entries without pulling in a relative-time library. */
private fun formatLastUsed(epochMs: Long): String {
    val now = System.currentTimeMillis()
    val diffMs = (now - epochMs).coerceAtLeast(0L)
    val mins = diffMs / 60_000L
    val hours = mins / 60L
    val days = hours / 24L
    return when {
        mins < 1L -> "just now"
        mins < 60L -> "${mins}m ago"
        hours < 24L -> "${hours}h ago"
        days < 7L -> "${days}d ago"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMs))
    }
}
