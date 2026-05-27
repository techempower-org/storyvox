package `in`.jphe.storyvox.feature.debug

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.feature.api.DebugEventKind
import `in`.jphe.storyvox.feature.api.DebugSnapshot
import `in`.jphe.storyvox.ui.theme.LibraryNocturneTheme
import `in`.jphe.storyvox.ui.theme.LocalReducedMotion

/**
 * Library Nocturne brass accent for the overlay's hairline border.
 * Pulled into a named constant so the preview and the live composable
 * read the same value. Matches the brass-on-deep-purple aesthetic that
 * the v0.4.96 shimmer skeletons (Vesper's previous PR) established as
 * the loading-state language.
 */
private val BrassHairline = Color(0xFFC9A05F).copy(alpha = 0.40f)

/**
 * Deep-purple overlay substrate — the same warm dark used by surface
 * dark mode, alpha'd to 0.92 so the player below stays a hint visible
 * for "I haven't lost my place". Per the design brief in Vesper's
 * mission spec: "deep purple semi-transparent (alpha ~0.92) with
 * subtle brass border".
 */
private val OverlaySubstrate = Color(0xFF15131A).copy(alpha = 0.92f)

/**
 * Status pill colors. Brass for "good", deep red for "error", warm gray
 * for "neutral". The error red (`#A04535`) is darker than the Material
 * `error` token so it reads as "bug to investigate" rather than
 * "the chat is on fire".
 */
private val StatusBrass = Color(0xFFC9A05F)
private val StatusError = Color(0xFFA04535)
private val StatusNeutral = Color(0xFF8B7E6A)

/**
 * The on-Reader debug overlay.
 *
 * Lifecycle:
 *  - Mounted by Reader / Player shells when [DebugViewModel.overlayEnabled]
 *    is true.
 *  - Internally tracks an expanded/collapsed state, persisted across
 *    process death via [rememberSaveable].
 *  - The collapsed shape is a single-line strip pinned beneath the
 *    status bar; the expanded shape is a multi-section card.
 *  - Swipe down on the header dismisses (collapses) the card.
 *
 * Quality bar (from the mission brief):
 *  - The overlay must NOT obscure playback controls — by living at the
 *    top of the screen, transport controls at the bottom stay clean.
 *  - Numeric values update at 1Hz — the repo is throttled.
 *  - On narrow phones (Flip3, 360dp), the collapsed strip fits without
 *    truncation thanks to compact identifiers + ellipsis on titles.
 *
 * **a11y note (#483):** this overlay uses several hard-coded `fontSize`
 * values (9–11sp monospace) outside the typography ramp. These are
 * intentional: the overlay's whole job is to surface dense pipeline
 * diagnostics in the smallest readable form so the chapter text underneath
 * stays visible. The text *does* scale with system font scale (it's `.sp`,
 * not `.dp`), so accessibility-aware users still benefit; the ramp
 * tokens are bypassed because the design intent is "smaller than any
 * production label" — debug surfaces are explicitly opt-in via the
 * dev-only Settings → Debug subscreen, never reachable by end users.
 */
@Composable
fun DebugOverlay(
    modifier: Modifier = Modifier,
    viewModel: DebugViewModel = hiltViewModel(),
) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    DebugOverlayContent(
        snapshot = snapshot,
        modifier = modifier,
    )
}

@Composable
internal fun DebugOverlayContent(
    snapshot: DebugSnapshot,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    var dismissed by remember { mutableStateOf(false) }

    // #486 Phase 2 / #480 — debug overlay slide-in collapses to a snap
    // under reduced motion. Visibility flip still works; the slide
    // doesn't play.
    val reducedMotion = LocalReducedMotion.current
    AnimatedVisibility(
        visible = !dismissed,
        enter = if (reducedMotion) {
            androidx.compose.animation.EnterTransition.None
        } else {
            fadeIn(tween(220)) + slideInVertically(
                animationSpec = tween(260),
                initialOffsetY = { -it / 2 },
            )
        },
        exit = if (reducedMotion) {
            androidx.compose.animation.ExitTransition.None
        } else {
            fadeOut(tween(180)) + slideOutVertically(
                animationSpec = tween(220),
                targetOffsetY = { -it / 2 },
            )
        },
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(OverlaySubstrate)
                .border(
                    width = 1.dp,
                    color = BrassHairline,
                    shape = RoundedCornerShape(12.dp),
                ),
        ) {
            // Header — always visible. Tap to expand/collapse, swipe
            // down to dismiss the whole card.
            OverlayHeader(
                snapshot = snapshot,
                expanded = expanded,
                onToggle = { expanded = !expanded },
                onSwipeDown = { dismissed = true },
            )
            AnimatedVisibility(
                visible = expanded,
                enter = if (reducedMotion) androidx.compose.animation.EnterTransition.None else fadeIn(tween(260)),
                exit = if (reducedMotion) androidx.compose.animation.ExitTransition.None else fadeOut(tween(180)),
            ) {
                OverlayBody(snapshot = snapshot)
            }
        }
    }
}

@Composable
private fun OverlayHeader(
    snapshot: DebugSnapshot,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSwipeDown: () -> Unit,
) {
    val playback = snapshot.playback
    val tone = when {
        snapshot.playback.lastErrorTag != null || snapshot.azure.lastErrorTag != null -> StatusError
        playback.pipelineRunning -> StatusBrass
        else -> StatusNeutral
    }
    // a11y (#481): debug overlay collapse/expand header — Role.Button.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                role = Role.Button,
                onClickLabel = if (expanded) "Collapse debug overlay" else "Expand debug overlay",
                onClick = onToggle,
            )
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, dragAmount ->
                    if (dragAmount > 18f) onSwipeDown()
                }
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Status dot — pulses subtly. Brass when playing, red on
        // error, warm gray when idle.
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(50))
                .background(tone),
        )
        Icon(
            imageVector = Icons.Outlined.BugReport,
            contentDescription = null,
            tint = StatusBrass,
            modifier = Modifier.size(16.dp),
        )
        // One-line status — "engine · sentence · state".
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = headerSummary(snapshot),
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFFE8DCC8),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 1,
            )
            Text(
                text = headerSubtitle(snapshot),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFB8AE9F),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                maxLines = 1,
            )
        }
        Icon(
            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = if (expanded) "Collapse debug overlay" else "Expand debug overlay",
            tint = Color(0xFFC9A774),
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun OverlayBody(snapshot: DebugSnapshot) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DebugDivider()
        OverlaySection(title = "Pipeline") {
            DebugRow("state", pipelineStateText(snapshot))
            DebugRow("sentence", "#${snapshot.playback.currentSentenceIndex} @ ${snapshot.playback.charOffset}")
            DebugRow("chapter", DebugFormatters.id(snapshot.playback.currentChapterId, width = 22))
        }
        OverlaySection(title = "Engine") {
            DebugRow("name", snapshot.engine.displayName)
            DebugRow("voice", snapshot.engine.voiceLabel.ifBlank { "—" })
            DebugRow("tier", snapshot.engine.tier)
            DebugRow(
                "speed × pitch",
                "%.2f× · %.2f×".format(snapshot.engine.speed, snapshot.engine.pitch),
            )
        }
        OverlaySection(title = "Buffer / Audio") {
            DebugRow(
                "producer queue",
                "${snapshot.audio.producerQueueDepth} / ${snapshot.audio.producerQueueCapacity}",
            )
            DebugRow("audio buffered", DebugFormatters.duration(snapshot.audio.audioBufferMs))
            if (snapshot.engine.parallelInstances > 1) {
                DebugRow("reorder buf", "${snapshot.audio.reorderBufferOccupancy}")
            }
        }
        if (snapshot.azure.isConfigured) {
            OverlaySection(title = "Azure") {
                DebugRow("region", snapshot.azure.regionId)
                DebugRow("pending", "${snapshot.azure.pendingRequests}")
                DebugRow("last latency", DebugFormatters.duration(snapshot.azure.lastLatencyMs))
                val err = snapshot.azure.lastErrorTag
                if (err != null) DebugRow("error", err, isError = true)
            }
        }
        OverlaySection(title = "Network") {
            DebugRow("online", if (snapshot.network.online) "yes" else "no",
                isError = !snapshot.network.online)
            DebugRow("last fetch", DebugFormatters.duration(snapshot.network.lastChapterFetchAgeMs) + " ago")
        }
        // Recent events — top 5 only; the full 20 live on the screen.
        if (snapshot.events.isNotEmpty()) {
            OverlaySection(title = "Recent") {
                snapshot.events.take(5).forEach { ev ->
                    EventRow(
                        time = DebugFormatters.clockTime(ev.timestampMs),
                        kind = ev.kind,
                        message = ev.message,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Swipe down to dismiss · tap to collapse",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF8B7E6A),
            fontSize = 9.sp,
            modifier = Modifier.padding(bottom = 2.dp),
        )
    }
}

@Composable
private fun OverlaySection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            // Serif for section headers per the design brief; matches the
            // Library Nocturne header rhythm where chapter-headings use
            // BookBodyFamily (Garamond).
            style = MaterialTheme.typography.titleSmall,
            color = StatusBrass,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
        content()
    }
}

@Composable
private fun DebugRow(label: String, value: String, isError: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = Color(0xFFB8AE9F),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = value,
            color = if (isError) StatusError else Color(0xFFE8DCC8),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (isError) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

@Composable
private fun EventRow(time: String, kind: DebugEventKind, message: String) {
    val tone = when (kind) {
        DebugEventKind.Error -> StatusError
        DebugEventKind.Engine, DebugEventKind.Chapter -> StatusBrass
        else -> Color(0xFFB8AE9F)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = time,
            color = Color(0xFF8B7E6A),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
        )
        Box(
            modifier = Modifier
                .size(4.dp)
                .clip(RoundedCornerShape(50))
                .background(tone),
        )
        Text(
            text = message,
            color = if (kind == DebugEventKind.Error) StatusError else Color(0xFFE8DCC8),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DebugDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(BrassHairline.copy(alpha = 0.25f)),
    )
}

private fun headerSummary(s: DebugSnapshot): String {
    val state = pipelineStateText(s)
    val engine = s.engine.displayName
    return "$engine · $state"
}

private fun headerSubtitle(s: DebugSnapshot): String {
    val sent = s.playback.currentSentenceIndex.coerceAtLeast(0)
    val q = "${s.audio.producerQueueDepth}/${s.audio.producerQueueCapacity}"
    val errBadge = s.playback.lastErrorTag?.let { " · ⚠ $it" } ?: ""
    return "sent #$sent · queue $q$errBadge"
}

/**
 * Issue #537 — human-readable engine-state label for the overlay strip.
 *
 * Pre-#537 this returned "idle" whenever the user paused a chapter
 * mid-listen — the pipeline was still loaded (`pipelineRunning = true`)
 * but `isPlaying = false`, which fell through to the "idle" branch. That
 * read as "the engine is doing nothing / has no chapter", causing
 * audit confusion: a paused chapter looked indistinguishable from a
 * fresh app launch.
 *
 * The fix:
 *  - When a chapter is loaded but not playing AND no other state owns
 *    the moment (not warming, not buffering, no error), the engine is
 *    PAUSED, not idle.
 *  - Real idle ("no chapter loaded, no pipeline running") still maps
 *    to an empty label rather than the word "idle" — there's nothing
 *    useful to surface, and a blank slot reads as "nothing to report"
 *    while "idle" reads as a stuck state.
 *
 * Exposed `internal` so [DebugOverlayReleaseTest] can pin the mapping
 * without rendering the composable.
 */
internal fun pipelineStateText(s: DebugSnapshot): String {
    val p = s.playback
    return when {
        p.lastErrorTag != null -> "ERROR"
        p.isWarmingUp -> "warming up"
        p.isBuffering -> "buffering"
        p.isPlaying -> "playing"
        // #537 — pipeline loaded but not playing → paused, not idle.
        // `pipelineRunning` is the loaded-chapter signal; without
        // playing/warming/buffering taking precedence, this is the
        // user-paused branch and must surface as such.
        p.pipelineRunning -> "paused"
        // No chapter loaded — surface nothing rather than "idle". A
        // blank label avoids the false-positive read of "the engine is
        // stuck". An empty string keeps the column position stable for
        // the monospace header layout.
        else -> ""
    }
}

// region Previews

@Preview(name = "Overlay — playing", widthDp = 360, heightDp = 200)
@Composable
private fun PreviewOverlayPlaying() = LibraryNocturneTheme(darkTheme = true) {
    val sample = DebugSnapshot.EMPTY.copy(
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
            voiceLabel = "en_US-amy-medium",
            tier = "Tier 1 / 2 (serial / streaming)",
            speed = 1.4f,
            pitch = 1.0f,
        ),
        audio = DebugSnapshot.EMPTY.audio.copy(
            producerQueueDepth = 6,
            producerQueueCapacity = 8,
            audioBufferMs = 2_400L,
        ),
        snapshotAtMs = System.currentTimeMillis(),
    )
    DebugOverlayContent(snapshot = sample)
}

@Preview(name = "Overlay — error (Azure)", widthDp = 360, heightDp = 240)
@Composable
private fun PreviewOverlayError() = LibraryNocturneTheme(darkTheme = true) {
    val sample = DebugSnapshot.EMPTY.copy(
        playback = DebugSnapshot.EMPTY.playback.copy(
            pipelineRunning = false,
            isPlaying = false,
            lastErrorTag = "AzureThrottled",
            currentSentenceIndex = 12,
            currentChapterId = "azure:test",
        ),
        engine = DebugSnapshot.EMPTY.engine.copy(
            displayName = "Azure",
            voiceLabel = "en-US-AvaMultilingualNeural",
            tier = "Streaming (Azure)",
        ),
        azure = DebugSnapshot.EMPTY.azure.copy(
            isConfigured = true,
            regionId = "eastus",
            lastErrorTag = "Throttled",
        ),
        network = DebugSnapshot.EMPTY.network.copy(online = true),
        snapshotAtMs = System.currentTimeMillis(),
    )
    DebugOverlayContent(snapshot = sample)
}

// endregion
