package `in`.jphe.storyvox.feature.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.feature.api.DebugEvent
import `in`.jphe.storyvox.feature.api.DebugEventKind
import `in`.jphe.storyvox.feature.api.DebugSnapshot
import `in`.jphe.storyvox.ui.theme.LibraryNocturneTheme
import `in`.jphe.storyvox.ui.theme.LocalSpacing
import kotlinx.coroutines.launch

/**
 * Dedicated `/debug` screen. Lives behind Settings → Developer →
 * "Open debug screen". Reads identical data to the overlay but renders
 * it as a full-page control panel with breathing room, drill-down
 * sections, and the export-to-clipboard button.
 *
 * Sections (in order of debugging utility):
 *  1. Status header — at-a-glance pipeline state pill
 *  2. Pipeline + Engine
 *  3. Audio buffer + routing
 *  4. Azure cloud-voice diagnostics (only when configured)
 *  5. Network state
 *  6. Storage (cached chapters + voice models)
 *  7. Build info — version, hash, sigil
 *  8. Event log (full 20-event ring)
 *  9. Export — single-tap "Copy snapshot" to clipboard
 *
 * Library Nocturne aesthetic: brass section headers, monospace numerics,
 * card substrate. Reuses the [Card] from M3 with the same
 * `surfaceContainerHigh` substrate the Settings groups use, so the
 * screen feels like a sibling of Settings rather than a console dump.
 *
 * **a11y note (#483):** the event-log row at line 492 uses
 * `fontSize = 11.sp` (monospace clock-time) rather than a typography
 * ramp token. This is intentional: the dev-only screen must surface
 * dense diagnostics in a compact tabular form. Text still rides system
 * font scaling (`.sp`, not `.dp`); the bypass is "smaller than any
 * production label" by design. Not reachable by end users.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    onBack: () -> Unit,
    viewModel: DebugViewModel = hiltViewModel(),
) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val overlayEnabled by viewModel.overlayEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var copyFlashAt by remember { mutableStateOf(0L) }

    DebugScreenContent(
        snapshot = snapshot,
        overlayEnabled = overlayEnabled,
        copyFlashAt = copyFlashAt,
        onBack = onBack,
        onToggleOverlay = viewModel::setOverlayEnabled,
        onCopy = {
            scope.launch {
                val snap = viewModel.captureForExport()
                copySnapshotToClipboard(context, snap)
                copyFlashAt = System.currentTimeMillis()
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DebugScreenContent(
    snapshot: DebugSnapshot,
    overlayEnabled: Boolean,
    copyFlashAt: Long,
    onBack: () -> Unit,
    onToggleOverlay: (Boolean) -> Unit,
    onCopy: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val flashVisible = copyFlashAt > 0L &&
        (System.currentTimeMillis() - copyFlashAt) < 2_000L
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.BugReport,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(stringResource(R.string.debug_title))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onCopy) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = "Copy snapshot to clipboard",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.md, vertical = spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            // Top — pulse pill summarizing pipeline state.
            StatusHeader(snapshot)

            // Overlay master switch — surfaced here too so power users
            // can flip it without bouncing to Settings → Developer.
            OverlayToggleCard(
                enabled = overlayEnabled,
                onToggle = onToggleOverlay,
            )

            DebugSection(title = "Pipeline") {
                MetricRow("running", snapshot.playback.pipelineRunning.toLabel())
                MetricRow("playing", snapshot.playback.isPlaying.toLabel())
                MetricRow("buffering", snapshot.playback.isBuffering.toLabel())
                MetricRow("warming up", snapshot.playback.isWarmingUp.toLabel())
                MetricRow("sentence index", "#${snapshot.playback.currentSentenceIndex}")
                MetricRow("char offset", "${snapshot.playback.charOffset}")
                MetricRow("chapter id", snapshot.playback.currentChapterId ?: "—")
                MetricRow("fiction id", snapshot.playback.currentFictionId ?: "—")
                snapshot.playback.lastErrorTag?.let {
                    MetricRow("error", it, isError = true)
                }
            }

            DebugSection(title = "Engine") {
                MetricRow("name", snapshot.engine.displayName)
                MetricRow("voice id", snapshot.engine.voiceId ?: "—")
                MetricRow("tier", snapshot.engine.tier)
                MetricRow("parallel instances", "${snapshot.engine.parallelInstances}")
                MetricRow(
                    "threads / instance",
                    if (snapshot.engine.threadsPerInstance == 0) "auto" else "${snapshot.engine.threadsPerInstance}",
                )
                MetricRow("speed", "%.2f×".format(snapshot.engine.speed))
                MetricRow("pitch", "%.2f×".format(snapshot.engine.pitch))
                MetricRow(
                    "punctuation pause",
                    "%.2f×".format(snapshot.engine.punctuationPauseMultiplier),
                )
            }

            DebugSection(title = "Audio") {
                MetricRow(
                    "producer queue",
                    "${snapshot.audio.producerQueueDepth} / ${snapshot.audio.producerQueueCapacity}",
                )
                MetricRow("audio buffered", DebugFormatters.duration(snapshot.audio.audioBufferMs))
                if (snapshot.engine.parallelInstances > 1) {
                    MetricRow("reorder buffer", "${snapshot.audio.reorderBufferOccupancy}")
                }
                MetricRow(
                    "sample rate",
                    if (snapshot.audio.sampleRate <= 0) "—" else "${snapshot.audio.sampleRate} Hz",
                )
                MetricRow(
                    "output device",
                    snapshot.audio.outputDevice.ifBlank { "—" },
                )
            }

            if (snapshot.azure.isConfigured) {
                DebugSection(title = "Azure") {
                    MetricRow("region", snapshot.azure.regionId)
                    MetricRow("pending requests", "${snapshot.azure.pendingRequests}")
                    MetricRow(
                        "last latency",
                        DebugFormatters.duration(snapshot.azure.lastLatencyMs),
                    )
                    MetricRow(
                        "voice cache age",
                        snapshot.azure.voiceCacheAgeSec?.let { "${it}s" } ?: "—",
                    )
                    snapshot.azure.lastErrorTag?.let {
                        MetricRow("last error", it, isError = true)
                    }
                }
            }

            DebugSection(title = "Network") {
                MetricRow("online", snapshot.network.online.toLabel(), isError = !snapshot.network.online)
                MetricRow(
                    "last chapter fetch",
                    DebugFormatters.duration(snapshot.network.lastChapterFetchAgeMs)
                        .let { if (it == "—") it else "$it ago" },
                )
                snapshot.network.lastFetchError?.let {
                    MetricRow("last fetch error", it, isError = true)
                }
            }

            DebugSection(title = "Storage") {
                MetricRow("cached chapters", "${snapshot.storage.cachedChapters}")
                MetricRow("chapter cache", DebugFormatters.bytes(snapshot.storage.cachedChapterBytes))
                MetricRow("voice model cache", DebugFormatters.bytes(snapshot.storage.voiceModelBytes))
            }

            DebugSection(title = "Build") {
                MetricRow("version", snapshot.build.versionName.ifBlank { "—" })
                MetricRow("hash", snapshot.build.hash.ifBlank { "—" })
                MetricRow("branch", snapshot.build.branch.ifBlank { "—" })
                if (snapshot.build.dirty) MetricRow("dirty", "yes", isError = true)
                MetricRow("built", snapshot.build.built.ifBlank { "—" })
                MetricRow("sigil", snapshot.build.sigilName.ifBlank { "—" })
            }

            DebugSection(title = "Recent events (20)") {
                if (snapshot.events.isEmpty()) {
                    Text(
                        "No events yet — start playback to see chapter / engine / error events here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    snapshot.events.forEach { ev ->
                        FullEventRow(ev)
                    }
                }
            }

            if (flashVisible) {
                Text(
                    text = "Snapshot copied to clipboard.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                )
            }

            Spacer(Modifier.height(spacing.md))
        }
    }
}

@Composable
private fun StatusHeader(snapshot: DebugSnapshot) {
    val tone = when {
        snapshot.playback.lastErrorTag != null || snapshot.azure.lastErrorTag != null ->
            MaterialTheme.colorScheme.error
        snapshot.playback.pipelineRunning ->
            MaterialTheme.colorScheme.primary
        else ->
            MaterialTheme.colorScheme.outline
    }
    val label = when {
        snapshot.playback.lastErrorTag != null -> "ERROR · ${snapshot.playback.lastErrorTag}"
        snapshot.azure.lastErrorTag != null -> "Azure · ${snapshot.azure.lastErrorTag}"
        snapshot.playback.isWarmingUp -> "warming up"
        snapshot.playback.isBuffering -> "buffering"
        snapshot.playback.isPlaying -> "playing · ${snapshot.engine.displayName}"
        snapshot.playback.pipelineRunning -> "pipeline running"
        else -> "idle"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(RoundedCornerShape(50))
                    .background(tone),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "sentence #${snapshot.playback.currentSentenceIndex} · queue ${snapshot.audio.producerQueueDepth}/${snapshot.audio.producerQueueCapacity}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun OverlayToggleCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val brassColors = SwitchDefaults.colors(
        checkedThumbColor = MaterialTheme.colorScheme.primary,
        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
        checkedBorderColor = MaterialTheme.colorScheme.primary,
        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        // a11y (#478): toggleable Row so TalkBack announces the
        // overlay toggle as a single Role.Switch node with the label.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = enabled,
                    role = Role.Switch,
                    onValueChange = onToggle,
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Show debug overlay",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "Floats a compact pipeline-state card above the reader / player.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = null,
                colors = brassColors,
            )
        }
    }
}

@Composable
private fun DebugSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Section heading — brass label, mirrors the Library Nocturne
        // rhythm Settings uses but tighter (the overlay screen is denser
        // than Settings).
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp),
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String, isError: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = if (isError) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = if (isError) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
        )
    }
}

@Composable
private fun FullEventRow(ev: DebugEvent) {
    val tone = when (ev.kind) {
        DebugEventKind.Error -> MaterialTheme.colorScheme.error
        DebugEventKind.Engine, DebugEventKind.Chapter -> MaterialTheme.colorScheme.primary
        DebugEventKind.Sentence -> MaterialTheme.colorScheme.outline
        DebugEventKind.Pipeline -> MaterialTheme.colorScheme.tertiary
        DebugEventKind.Info -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = DebugFormatters.clockTime(ev.timestampMs),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.outline,
            fontSize = 11.sp,
        )
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(50))
                .background(tone),
        )
        Text(
            text = ev.message,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = if (ev.kind == DebugEventKind.Error) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 2,
        )
    }
}

/**
 * Serializes the snapshot to a JSON-ish blob suitable for pasting into
 * a bug report. Intentionally hand-rolled (not kotlinx.serialization) so
 * we don't pull a serialization plugin onto every Immutable type just
 * for the export path. The output reads cleanly and never carries
 * secrets — see the [DebugSnapshot] kdoc invariants.
 */
internal fun copySnapshotToClipboard(context: Context, snapshot: DebugSnapshot) {
    val json = renderSnapshotAsText(snapshot)
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("storyvox debug snapshot", json))
}

internal fun renderSnapshotAsText(s: DebugSnapshot): String {
    return buildString {
        appendLine("storyvox debug snapshot @ ${DebugFormatters.clockTime(s.snapshotAtMs)}")
        appendLine("=" + "=".repeat(48))
        appendLine("[build]")
        appendLine("  version=${s.build.versionName}  branch=${s.build.branch}  hash=${s.build.hash}  dirty=${s.build.dirty}")
        appendLine("  sigil=${s.build.sigilName}  built=${s.build.built}")
        appendLine("[playback]")
        appendLine("  running=${s.playback.pipelineRunning}  playing=${s.playback.isPlaying}")
        appendLine("  buffering=${s.playback.isBuffering}  warmingUp=${s.playback.isWarmingUp}")
        appendLine("  sentence=#${s.playback.currentSentenceIndex}  charOffset=${s.playback.charOffset}")
        appendLine("  chapter=${s.playback.currentChapterId}  fiction=${s.playback.currentFictionId}")
        s.playback.lastErrorTag?.let { appendLine("  error=$it") }
        appendLine("[engine]")
        appendLine("  ${s.engine.displayName}  voice=${s.engine.voiceId}")
        appendLine("  tier=${s.engine.tier}  instances=${s.engine.parallelInstances}  threads=${s.engine.threadsPerInstance}")
        appendLine("  speed=%.2f  pitch=%.2f  pauseMul=%.2f".format(
            s.engine.speed, s.engine.pitch, s.engine.punctuationPauseMultiplier
        ))
        appendLine("[audio]")
        appendLine("  queue=${s.audio.producerQueueDepth}/${s.audio.producerQueueCapacity}")
        appendLine("  audioBufferedMs=${s.audio.audioBufferMs}  reorderBuf=${s.audio.reorderBufferOccupancy}")
        appendLine("  outputDevice=${s.audio.outputDevice.ifBlank { "(none)" }}")
        if (s.azure.isConfigured) {
            appendLine("[azure]")
            appendLine("  region=${s.azure.regionId}  pending=${s.azure.pendingRequests}")
            appendLine("  lastLatencyMs=${s.azure.lastLatencyMs}  lastError=${s.azure.lastErrorTag}")
        }
        appendLine("[network]")
        appendLine("  online=${s.network.online}  lastFetchAgeMs=${s.network.lastChapterFetchAgeMs}")
        s.network.lastFetchError?.let { appendLine("  lastFetchError=$it") }
        appendLine("[events]  (newest first, ${s.events.size} entries)")
        s.events.forEach { ev ->
            appendLine("  ${DebugFormatters.clockTime(ev.timestampMs)}  [${ev.kind}]  ${ev.message}")
        }
    }
}

private fun Boolean.toLabel(): String = if (this) "yes" else "no"

@Preview(name = "Debug screen — idle", widthDp = 360, heightDp = 720)
@Composable
private fun PreviewDebugScreenIdle() = LibraryNocturneTheme(darkTheme = true) {
    DebugScreenContent(
        snapshot = DebugSnapshot.EMPTY.copy(
            build = DebugSnapshot.EMPTY.build.copy(
                versionName = "0.4.97",
                hash = "abcd123",
                branch = "feat/debug-overlay",
                built = "2026-05-10T01:23:45Z",
                sigilName = "Spectral Lantern · abcd123",
            ),
            snapshotAtMs = System.currentTimeMillis(),
        ),
        overlayEnabled = false,
        copyFlashAt = 0L,
        onBack = {},
        onToggleOverlay = {},
        onCopy = {},
    )
}

@Preview(name = "Debug screen — playing", widthDp = 360, heightDp = 720)
@Composable
private fun PreviewDebugScreenPlaying() = LibraryNocturneTheme(darkTheme = true) {
    DebugScreenContent(
        snapshot = DebugSnapshot.EMPTY.copy(
            playback = DebugSnapshot.EMPTY.playback.copy(
                pipelineRunning = true,
                isPlaying = true,
                currentSentenceIndex = 47,
                charOffset = 12_344,
                currentChapterId = "rr:fiction/123/ch/47",
                chapterTitle = "The Bonewright",
            ),
            engine = DebugSnapshot.EMPTY.engine.copy(
                displayName = "VoxSherpa · Piper",
                voiceId = "piper:en_US-amy-medium",
                voiceLabel = "en_US-amy-medium",
                tier = "Tier 1 / 2 (serial / streaming)",
                speed = 1.4f, pitch = 1.0f,
            ),
            audio = DebugSnapshot.EMPTY.audio.copy(
                producerQueueDepth = 6, producerQueueCapacity = 8,
                audioBufferMs = 2_400L,
            ),
            events = listOf(
                DebugEvent(System.currentTimeMillis(), DebugEventKind.Sentence, "Sentence 47 emitted"),
                DebugEvent(System.currentTimeMillis() - 4_000, DebugEventKind.Chapter, "Loaded: The Bonewright"),
                DebugEvent(System.currentTimeMillis() - 12_000, DebugEventKind.Engine, "Engine → VoxSherpa · Piper"),
            ),
            build = DebugSnapshot.EMPTY.build.copy(
                versionName = "0.4.97",
                hash = "abcd123",
                branch = "feat/debug-overlay",
                sigilName = "Spectral Lantern · abcd123",
            ),
            snapshotAtMs = System.currentTimeMillis(),
        ),
        overlayEnabled = true,
        copyFlashAt = 0L,
        onBack = {},
        onToggleOverlay = {},
        onCopy = {},
    )
}
