package `in`.jphe.storyvox.feature.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.llm.tools.ToolCallEvent
import `in`.jphe.storyvox.llm.tools.ToolResult
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.theme.LocalReducedMotion
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Q&A chat surface attached to a fiction. The user types questions
 * about plot, characters, pacing, and writing craft; the AI streams
 * its replies in. One chat history per fiction (deterministic session
 * id `chat:<fictionId>`) so closing and re-opening picks back up.
 *
 * Library Nocturne aesthetic: brass-on-warm-dark bubbles, primary
 * surface for user turns, surfaceVariant for assistant. Streaming
 * tokens render with a brass blinking-cursor block character — same
 * visual vocabulary as the Recap modal so the in-flight signal is
 * consistent across AI surfaces.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    onOpenAiSettings: () -> Unit,
    /** Issue #216 — fired by the `open_voice_library` tool. The
     *  callback hops out to nav-land (NavHost wires
     *  StoryvoxRoutes.VOICE_LIBRARY). */
    onOpenVoiceLibrary: () -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Issue #216 — observe one-shot nav events from tool handlers and
    // dispatch them to the outer NavController. LaunchedEffect with
    // Unit key so the collector runs once per composition lifetime.
    LaunchedEffect(Unit) {
        viewModel.navEvents.collect { event ->
            when (event) {
                ChatNavEvent.OpenVoiceLibrary -> onOpenVoiceLibrary()
            }
        }
    }
    // Input prefill seeded by the reader's long-press character lookup
    // (#188). The VM emits this once via the `prefill` StateFlow; the
    // ChatInput observes it, copies into its local TextFieldValue, then
    // calls `consumePrefill()` so a subsequent recomposition can't
    // overwrite the user's edits.
    val prefill by viewModel.prefill.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current
    val listState = rememberLazyListState()

    // Auto-scroll to the latest content. We anchor on (turn count,
    // streaming length) so a slow stream still keeps the bottom in
    // view rather than only firing once when the assistant turn
    // finalises. User-driven scrolling isn't blocked — the next
    // delta will pull them back; if that becomes annoying we can
    // gate on `!listState.isScrollInProgress` later.
    LaunchedEffect(state.turns.size, state.streaming?.length) {
        val total = state.turns.size + (if (state.streaming != null) 1 else 0)
        if (total > 0) listState.animateScrollToItem(total - 1)
    }

    val barTitle = state.fictionTitle
        ?.let { stringResource(R.string.chat_title_with_fiction, it) }
        ?: stringResource(R.string.chat_title)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        barTitle,
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.chat_back_button_cd),
                        )
                    }
                },
            )
        },
        modifier = Modifier.imePadding(),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.noProvider -> EmptyStateNoProvider(
                    onOpenSettings = onOpenAiSettings,
                    modifier = Modifier.weight(1f),
                )
                state.turns.isEmpty() && state.streaming == null -> EmptyStatePrompt(
                    fictionTitle = state.fictionTitle,
                    modifier = Modifier.weight(1f),
                )
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = spacing.md,
                        vertical = spacing.sm,
                    ),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    items(state.turns, key = { "${it.role}:${it.text.hashCode()}" }) { turn ->
                        TurnBubble(
                            turn = turn,
                            isReadingThis = state.readingText == turn.text,
                            isAnythingReading = state.readingText != null,
                            onReadAloud = { viewModel.readAloud(turn.text) },
                            onStopReadAloud = viewModel::stopReadAloud,
                        )
                    }
                    // Issue #216 — tool-call cards render in-line after
                    // the assistant turn that triggered them. The
                    // ordering matches the model's emit order (started
                    // → completed cycles); the streaming bubble lives
                    // after all of them so the user sees "tool ran →
                    // reply continues" left-to-right.
                    items(state.toolCalls, key = { "tool:${it.id}" }) { call ->
                        ToolCallCard(event = call)
                    }
                    state.streaming?.let { partial ->
                        item(key = "streaming") { StreamingBubble(partial) }
                    }
                }
            }

            // Issue #215 — info banner shown when the just-sent message
            // had an image attached but the active provider couldn't
            // take it. Text-only request still went through; banner
            // dismisses on next send or on tap-x.
            if (state.imageDroppedWarning) {
                ImageDroppedBanner(
                    onDismiss = viewModel::dismissImageDroppedWarning,
                )
            }
            state.error?.let { err ->
                ErrorBanner(
                    error = err,
                    onDismiss = viewModel::dismissError,
                    onOpenSettings = onOpenAiSettings,
                )
            }

            if (!state.noProvider) {
                ChatInput(
                    enabled = state.streaming == null,
                    onSend = viewModel::send,
                    prefill = prefill,
                    onPrefillConsumed = viewModel::consumePrefill,
                    // Issue #215 — image-picker plumbing. The composer
                    // owns picker launch; the VM owns encoding +
                    // attach/detach state.
                    imagesSupported = state.imagesSupported,
                    pendingImage = state.pendingImage,
                    onAttachImage = viewModel::attachImage,
                    onClearImage = viewModel::clearPendingImage,
                )
            }
        }
    }
}

// ── Bubbles ────────────────────────────────────────────────────────

@Composable
private fun TurnBubble(
    turn: ChatTurn,
    isReadingThis: Boolean = false,
    isAnythingReading: Boolean = false,
    onReadAloud: () -> Unit = {},
    onStopReadAloud: () -> Unit = {},
) {
    val isUser = turn.role == ChatTurn.Role.User
    val containerColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val alignment = if (isUser) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(containerColor, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Issue #215 — inline image preview on user turns
                // that were sent with an attachment. The 200dp max
                // width sits comfortably inside the 320dp bubble
                // even on the narrowest phone widths.
                turn.imageUri?.let { uri ->
                    coil3.compose.AsyncImage(
                        model = uri,
                        contentDescription = stringResource(R.string.chat_attached_image_cd),
                        modifier = Modifier
                            .widthIn(max = 200.dp)
                            .heightIn(max = 200.dp)
                            .background(
                                MaterialTheme.colorScheme.surface,
                                RoundedCornerShape(8.dp),
                            ),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    )
                }
                if (turn.text.isNotBlank()) {
                    Text(
                        text = turn.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor,
                    )
                }
            }
        }
        // Issue #214 — assistant turns get a Read-aloud / Stop
        // affordance below the bubble. Hidden on user turns (no point
        // reading what the user typed back to them) and on the
        // streaming partial (bubble has its own cursor, the read
        // would beat the response).
        if (!isUser && turn.text.isNotBlank()) {
            ReadAloudButton(
                isReadingThis = isReadingThis,
                isAnythingReading = isAnythingReading,
                onReadAloud = onReadAloud,
                onStopReadAloud = onStopReadAloud,
            )
        }
    }
}

@Composable
private fun ReadAloudButton(
    isReadingThis: Boolean,
    isAnythingReading: Boolean,
    onReadAloud: () -> Unit,
    onStopReadAloud: () -> Unit,
) {
    // Three-state button: this-is-reading (Stop), nothing-reading (Play),
    // another-bubble-reading (Play, dimmed). The dimmed state is
    // tappable — pressing Play on bubble B while bubble A reads will
    // stop A and start B, which matches user intent ("read THAT one").
    val (icon, label, onClick) = when {
        isReadingThis -> Triple(
            Icons.Outlined.Stop,
            stringResource(R.string.chat_read_aloud_stop),
            onStopReadAloud,
        )
        else -> Triple(
            Icons.Outlined.VolumeUp,
            stringResource(R.string.chat_read_aloud_play),
            onReadAloud,
        )
    }
    val tint = if (isReadingThis) {
        MaterialTheme.colorScheme.primary
    } else if (isAnythingReading) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    }
    androidx.compose.material3.TextButton(
        onClick = onClick,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 8.dp, vertical = 0.dp,
        ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.padding(end = 4.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
    }
}

/**
 * Mid-stream assistant bubble — same shape as the finalised assistant
 * bubble but with a blinking brass cursor appended. Honors
 * LocalReducedMotion: cursor stays static at full opacity for users
 * with reduce-motion, matching the rest of Library Nocturne.
 */
@Composable
private fun StreamingBubble(text: String) {
    val reducedMotion = LocalReducedMotion.current
    val cursorAlpha = if (reducedMotion) {
        1f
    } else {
        val infinite = rememberInfiniteTransition(label = "chat-cursor")
        val alpha by infinite.animateFloat(
            initialValue = 0.2f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 700),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "alpha",
        )
        alpha
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "▌",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.alpha(cursorAlpha),
                )
            }
        }
    }
}

// ── Tool-call card (#216) ──────────────────────────────────────────

/** Issue #216 — brass-edged card surfaced inline when the AI invokes
 *  one of the registered storyvox tools. Three visual states:
 *
 *  - in-flight: animated brass border, "Doing X…" copy.
 *  - success: solid brass border, check icon, completed copy.
 *  - error: solid error border, alert icon, error copy.
 *
 *  Visual vocabulary follows Library Nocturne: brass-on-warm-dark
 *  for the primary accent, errorContainer for the failure surface.
 *  The card stays in the timeline so the user can scroll back and
 *  see exactly what the AI did. */
@Composable
private fun ToolCallCard(event: ToolCallEvent) {
    val result = event.result
    val (borderColor, statusIcon, statusTint) = when (result) {
        is ToolResult.Success -> Triple(
            MaterialTheme.colorScheme.primary,
            Icons.Outlined.CheckCircle,
            MaterialTheme.colorScheme.primary,
        )
        is ToolResult.Error -> Triple(
            MaterialTheme.colorScheme.error,
            Icons.Outlined.ErrorOutline,
            MaterialTheme.colorScheme.error,
        )
        null -> Triple(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
            Icons.Outlined.AutoFixHigh,
            MaterialTheme.colorScheme.primary,
        )
    }
    val statusText = when (result) {
        null -> describeInFlight(event.name)
        is ToolResult.Success -> result.message
        is ToolResult.Error -> result.message
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(8.dp))
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = statusIcon,
            contentDescription = null,
            tint = statusTint,
            modifier = Modifier.padding(end = 2.dp),
        )
        Text(
            text = statusText,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Short, present-progressive copy used while a tool handler is
 *  in flight. Matches the [StoryvoxToolSpecs] name set; unknown names
 *  fall back to the generic verb. */
@Composable
private fun describeInFlight(name: String): String = when (name) {
    "add_to_shelf" -> stringResource(R.string.chat_tool_in_flight_add_to_shelf)
    "queue_chapter" -> stringResource(R.string.chat_tool_in_flight_queue_chapter)
    "mark_chapter_read" -> stringResource(R.string.chat_tool_in_flight_mark_chapter_read)
    "set_speed" -> stringResource(R.string.chat_tool_in_flight_set_speed)
    "open_voice_library" -> stringResource(R.string.chat_tool_in_flight_open_voice_library)
    else -> stringResource(R.string.chat_tool_in_flight_generic, name)
}

// ── Empty states ───────────────────────────────────────────────────

@Composable
private fun EmptyStateNoProvider(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier.fillMaxWidth().padding(spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.md, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Outlined.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.chat_empty_no_provider_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        BrassButton(
            label = stringResource(R.string.chat_empty_open_settings),
            onClick = onOpenSettings,
            variant = BrassButtonVariant.Primary,
        )
    }
}

@Composable
private fun EmptyStatePrompt(
    fictionTitle: String?,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier.fillMaxWidth().padding(spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.md, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Outlined.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = if (fictionTitle != null) {
                stringResource(R.string.chat_empty_prompt_with_fiction, fictionTitle)
            } else {
                stringResource(R.string.chat_empty_prompt_generic)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// ── Input + error banner ───────────────────────────────────────────

/** Pre-fill chips for the most common chat asks (#213). Tap inserts
 *  the prompt into the input field but does NOT auto-send — the user
 *  can edit / append before sending. Visible only when input is empty
 *  so they don't clutter the chat once a conversation is rolling.
 *
 *  Order is touch-frequency: 'What did I miss?' is the dominant ask
 *  for resumed listening; 'Who is X?' is character lookup; the rest
 *  catch the long tail. */
@Composable
private fun rememberQuickActionPrompts(): List<String> = listOf(
    stringResource(R.string.chat_quick_what_did_i_miss),
    stringResource(R.string.chat_quick_who_is_this_character),
    stringResource(R.string.chat_quick_explain_that),
    stringResource(R.string.chat_quick_where_are_we),
)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChatInput(
    enabled: Boolean,
    onSend: (String) -> Unit,
    /** One-shot starter text from the long-press lookup deep-link
     *  (#188). When non-null+non-blank, the input field is seeded with
     *  this value (replacing whatever the user had typed — but this
     *  only fires on first emission per nav, so a typing user never
     *  sees their text wiped mid-flow). After applying we call
     *  [onPrefillConsumed] so the VM clears the latch. */
    prefill: String? = null,
    onPrefillConsumed: () -> Unit = {},
    /** Issue #215 — true when the active provider accepts image
     *  content. Gates the attach button's visibility. */
    imagesSupported: Boolean = false,
    /** Issue #215 — image queued for the next send, or null. When
     *  non-null, the thumbnail preview row is shown. */
    pendingImage: ImageAttachment? = null,
    /** Issue #215 — called with a freshly-resized + encoded image
     *  after the user picks a file through the SAF picker. */
    onAttachImage: (ImageAttachment) -> Unit = {},
    /** Issue #215 — called when the user taps the x on the
     *  thumbnail preview to detach without sending. */
    onClearImage: () -> Unit = {},
) {
    val spacing = LocalSpacing.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val quickActionPrompts = rememberQuickActionPrompts()
    var text by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }

    LaunchedEffect(prefill) {
        if (!prefill.isNullOrBlank()) {
            text = prefill
            onPrefillConsumed()
        }
    }

    // Issue #215 — SAF picker for image attachment. OpenDocument
    // returns a content:// URI; we open the InputStream off-main,
    // hand the bytes to ImageResizer for downscale + JPEG-encode,
    // and stuff the result onto the composer state. The picking
    // boolean disables the send/attach affordances while the bytes
    // are being processed so the user can't fire two encodes at once.
    val imagePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            picking = false
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            try {
                val encoded = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes()
                    } ?: throw IllegalArgumentException("Could not read image")
                    `in`.jphe.storyvox.llm.imaging.ImageResizer.encodeForUpload(bytes)
                }
                onAttachImage(
                    ImageAttachment(
                        uri = uri.toString(),
                        base64 = encoded.base64,
                        mimeType = encoded.mimeType,
                        widthPx = encoded.widthPx,
                        heightPx = encoded.heightPx,
                    ),
                )
            } catch (_: Throwable) {
                // Encoding failed — best effort, no error banner. The
                // user can simply pick again. A future PR could
                // surface this via the existing error banner.
            } finally {
                picking = false
            }
        }
    }

    androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxWidth()) {
        if (enabled && text.isBlank() && pendingImage == null) {
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.md, vertical = spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                quickActionPrompts.forEach { prompt ->
                    androidx.compose.material3.SuggestionChip(
                        onClick = { text = prompt },
                        label = { Text(prompt, style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }
        }
        // Issue #215 — thumbnail preview row above the text input.
        // Compact (64dp) so it leaves room for the composer; tap-the-x
        // detaches without sending.
        pendingImage?.let { img ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.md, vertical = spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    coil3.compose.AsyncImage(
                        model = img.uri,
                        contentDescription = stringResource(R.string.chat_attached_image_preview_cd),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                    // x affordance bottom-right of the thumbnail.
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(2.dp)
                            .size(20.dp)
                            .background(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                CircleShape,
                            )
                            // a11y (#481): Role.Button for the clear-image x.
                            .clickable(
                                role = Role.Button,
                                onClickLabel = stringResource(R.string.chat_remove_image_cd),
                            ) { onClearImage() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.chat_remove_image_cd),
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.chat_image_attached_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "${img.widthPx} × ${img.heightPx}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.md, vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            // Issue #215 — attach button. Only rendered when the
            // active provider can actually use the image; otherwise
            // the chat composer behaves identically to pre-#215.
            if (imagesSupported) {
                IconButton(
                    onClick = {
                        picking = true
                        imagePicker.launch(arrayOf("image/*"))
                    },
                    enabled = enabled && !picking && pendingImage == null,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AddPhotoAlternate,
                        contentDescription = stringResource(R.string.chat_attach_image_cd),
                        tint = if (enabled && !picking && pendingImage == null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        },
                    )
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                enabled = enabled,
                placeholder = { Text(stringResource(R.string.chat_ask_question_placeholder)) },
                modifier = Modifier.weight(1f),
                singleLine = false,
                maxLines = 4,
            )
            IconButton(
                onClick = {
                    val toSend = text.trim()
                    // Issue #215 — allow a send with an image even if
                    // the text is empty ("describe this image" UX).
                    if (toSend.isNotEmpty() || pendingImage != null) {
                        onSend(toSend)
                        text = ""
                    }
                },
                enabled = enabled && (text.isNotBlank() || pendingImage != null),
            ) {
                val sendEnabled = enabled && (text.isNotBlank() || pendingImage != null)
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.chat_send_button_cd),
                    tint = if (sendEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    },
                )
            }
        }
    }
}

/** Issue #215 — info banner explaining that the image was dropped
 *  because the active provider doesn't accept images. Uses the
 *  surfaceVariant colour rather than errorContainer because the
 *  message did still send — this is a "fyi" not a "fail". */
@Composable
private fun ImageDroppedBanner(onDismiss: () -> Unit) {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Icon(
            Icons.Outlined.Image,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.chat_image_dropped_banner),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(R.string.chat_dismiss_cd),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ErrorBanner(
    error: ChatError,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val showSettings = error is ChatError.NotConfigured ||
        error is ChatError.AuthFailed
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = error.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f),
        )
        if (showSettings) {
            BrassButton(
                label = stringResource(R.string.chat_error_open_settings),
                onClick = onOpenSettings,
                variant = BrassButtonVariant.Text,
            )
        }
        IconButton(onClick = onDismiss) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(R.string.chat_dismiss_cd),
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}
