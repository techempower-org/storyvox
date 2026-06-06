package `in`.jphe.storyvox.feature.engine

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import `in`.jphe.storyvox.data.source.SystemTtsVoiceProvider
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.feature.components.overlayBackground
import `in`.jphe.storyvox.feature.components.overlayForeground
import `in`.jphe.storyvox.playback.voice.UiVoiceInfo
import `in`.jphe.storyvox.playback.voice.VoiceCatalog
import `in`.jphe.storyvox.playback.voice.VoiceManager
import `in`.jphe.storyvox.playback.voice.VoiceManager.DownloadProgress
import `in`.jphe.storyvox.playback.voice.flagForLanguage
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.component.BrassProgressBar
import `in`.jphe.storyvox.ui.component.MagicSkeletonTile
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * First-launch gate that wraps the rest of the app. While no voice is
 * active in [VoiceManager] we render a sigil-themed picker over everything;
 * once an active voice exists (or the user opts into reader-only mode for
 * the session) the gate is invisible and [content] renders normally.
 *
 * The gate is reactive — it collects [VoiceManager.activeVoice] as a
 * Flow and dismisses itself the moment the DataStore-backed selection
 * flips to non-null. No manual refresh hook is required.
 */
@Composable
fun VoicePickerGate(
    onOpenVoiceLibrary: () -> Unit,
    content: @Composable () -> Unit,
) {
    val vm: VoicePickerGateViewModel = hiltViewModel()
    val activeVoice by vm.activeVoice.collectAsStateWithLifecycle()
    val downloadingId by vm.downloadingVoiceId.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val bypassed by vm.bypassed.collectAsStateWithLifecycle()
    val recommended by vm.recommended.collectAsStateWithLifecycle()

    // Render content unconditionally so the embedded NavHost (and its graph)
    // is registered even while the gate is still up. Tapping "More voices →"
    // dismisses the gate AND calls navController.navigate in the same frame —
    // if content weren't already in the composition, the destination route
    // wouldn't exist on the NavController yet and navigate() would crash.
    val gateShowing = !bypassed && activeVoice == null
    Box(modifier = Modifier.fillMaxSize()) {
        // #1026 — hide the live NavHost beneath from TalkBack while the gate
        // is up, so a screen-reader user can't swipe past the picker into the
        // (visually hidden) Library list / bottom nav. content() stays
        // composed (navigate() races the gate's dismiss); we isolate it
        // semantically rather than tearing it down.
        Box(modifier = Modifier.fillMaxSize().overlayBackground(gateShowing)) {
            content()
        }
        if (gateShowing) {
            // Swallow taps in the gate's empty regions so they don't fall
            // through to the LibraryScreen rendered underneath. We use a
            // ripple-less clickable rather than pointerInput because the
            // foundation API is shorter and we don't need finer gesture
            // control here.
            val swallow = remember { MutableInteractionSource() }
            // a11y (#481, #482): this clickable is a *tap-swallow* — a
            // transparent shield that prevents underlying content from
            // receiving taps while the voice picker overlays it. It
            // doesn't need (and shouldn't have) a Role. Marked explicitly
            // so a future Role-sweep doesn't add one.
            //
            // #1026 follow-up: the shield only blocks POINTER input — it
            // never bounded TalkBack traversal. The background NavHost is
            // now marked invisibleToUser via overlayBackground() above, and
            // this overlay is a traversal group (overlayForeground below),
            // so screen-reader focus is isolated to the picker explicitly
            // rather than relying on the opaque background occluding the
            // subtree (which only happens to work on some TalkBack versions).
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    // #1026 — scope the gate as a self-contained traversal
                    // group ahead of the background so TalkBack swipe-
                    // navigation stays within the picker's controls.
                    .overlayForeground()
                    .clickable(
                        interactionSource = swallow,
                        indication = null,
                        onClick = {},
                    ),
            ) {
                VoicePickerScreen(
                    recommended = recommended,
                    downloadingVoiceId = downloadingId,
                    progress = progress,
                    onPick = vm::pick,
                    onSkip = vm::bypass,
                    onOpenLibrary = {
                        vm.bypass()
                        onOpenVoiceLibrary()
                    },
                    onDismissProgress = vm::dismissProgress,
                )
            }
        }
    }
}

@HiltViewModel
class VoicePickerGateViewModel @Inject constructor(
    private val voices: VoiceManager,
    private val systemTtsVoiceProvider: SystemTtsVoiceProvider,
) : ViewModel() {

    /** Issue #681 — true once the OS exposes at least one System TTS voice
     *  via any installed engine (Google TTS, Samsung SMT, eSpeak, etc.).
     *  Starts false and flips to true (~150 ms post-init on devices with
     *  Google TTS pre-installed) once `SystemTtsVoiceProvider.voices`
     *  emits a non-empty list. Stays false on stock Samsung tablets and
     *  other TTS-less devices — the [VoicePickerOnboarding] composable
     *  observes this and surfaces a "Install Google TTS" CTA above the
     *  Piper voice tiles so the user knows the zero-download path is
     *  available behind one Play Store install. */
    val hasSystemTtsEngines: StateFlow<Boolean> = systemTtsVoiceProvider.voices
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        // #676 — best-effort first-launch seed: if the user has never
        // chosen a voice and the OS exposes at least one System TTS
        // voice, pick it as the default. Sight-impaired users with
        // TalkBack-configured engines hear audio immediately (their
        // configured System TTS voice) without traversing the picker
        // gate; casual users see the gate but already have a working
        // voice if they ignore it. seedSystemTtsDefaultIfUnset is a
        // no-op when an active voice is already set, so re-arming
        // this init block on every gate visit is cheap + safe.
        viewModelScope.launch {
            runCatching { voices.seedSystemTtsDefaultIfUnset() }
                .onFailure { t ->
                    android.util.Log.w(
                        "VoicePickerGate",
                        "#676 seedSystemTtsDefaultIfUnset failed",
                        t,
                    )
                }
        }
    }

    /** Hand-picked best-of-catalog starter voices ([VoiceCatalog.featuredIds]).
     *  Same set the Voice Library highlights under "Featured", so a user sees
     *  the same three names whether they pick now or browse the library.
     *  Prefers an installed [UiVoiceInfo] when available so the gate row
     *  reflects reality (and so [pick] can skip the download on tap). */
    val recommended: StateFlow<List<UiVoiceInfo>> = voices.installedVoices
        .map { installed ->
            val installedById = installed.associateBy { it.id }
            VoiceCatalog.featuredIds.mapNotNull { id ->
                installedById[id] ?: voices.availableVoices.firstOrNull { it.id == id }
            }
        }
        .stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            VoiceCatalog.featuredIds.mapNotNull { id ->
                voices.availableVoices.firstOrNull { it.id == id }
            },
        )

    val activeVoice: StateFlow<UiVoiceInfo?> = voices.activeVoice
        .let { flow ->
            val s = MutableStateFlow<UiVoiceInfo?>(null)
            viewModelScope.launch { flow.collect { s.value = it } }
            s.asStateFlow()
        }

    private val _downloadingVoiceId = MutableStateFlow<String?>(null)
    val downloadingVoiceId: StateFlow<String?> = _downloadingVoiceId.asStateFlow()

    private val _progress = MutableStateFlow<DownloadProgress?>(null)
    val progress: StateFlow<DownloadProgress?> = _progress.asStateFlow()

    /** Issue #547 — sticky persistent dismissed flag, sourced from
     *  [VoiceManager.pickerDismissed]. Once the user taps "Continue
     *  without audio" (or picks any voice via [setActive]), the flag
     *  flips true in DataStore and the gate stops re-prompting on
     *  cold launch. Replaces the previous session-only flag that
     *  re-armed every app start — JP filed #547 on that exact behavior.
     *
     *  Two-stage `stateIn`: the underlying Flow is cold (reads DataStore
     *  on first collect); we expose a hot StateFlow so the gate's
     *  composition can short-circuit synchronously. Eager start so the
     *  initial DataStore read overlaps with cold-launch composition. */
    val bypassed: StateFlow<Boolean> = voices.pickerDismissed
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun pick(voiceId: String) {
        if (_downloadingVoiceId.value != null) return
        // Already-installed featured voices (e.g. the gate re-appearing
        // after the previously-active voice was deleted but other voices
        // are still on disk) should activate immediately — no need to
        // re-download a model that already lives in filesDir.
        val installed = recommended.value.firstOrNull { it.id == voiceId }?.isInstalled == true
        if (installed) {
            viewModelScope.launch { voices.setActive(voiceId) }
            return
        }
        _downloadingVoiceId.value = voiceId
        _progress.value = DownloadProgress.Resolving
        viewModelScope.launch {
            voices.download(voiceId).collect { p ->
                _progress.value = p
                if (p is DownloadProgress.Done) {
                    voices.setActive(voiceId)
                    _downloadingVoiceId.value = null
                    _progress.value = null
                }
            }
        }
    }

    /** Issue #547 — fires when the user taps "Continue without audio"
     *  (or "More voices →", since both routes mean "stop showing me
     *  this onboarding screen"). Writes through to VoiceManager so the
     *  next cold launch's gate sees the dismissed flag and stays down.
     *  Fire-and-forget — the DataStore edit is single-key and the UI
     *  doesn't need to wait for the write to commit before dismissing. */
    fun bypass() {
        viewModelScope.launch { voices.markPickerDismissed() }
    }

    fun dismissProgress() {
        _downloadingVoiceId.value = null
        _progress.value = null
    }
}

@Composable
private fun VoicePickerScreen(
    recommended: List<UiVoiceInfo>,
    downloadingVoiceId: String?,
    progress: DownloadProgress?,
    onPick: (String) -> Unit,
    onSkip: () -> Unit,
    onOpenLibrary: () -> Unit,
    onDismissProgress: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(spacing.lg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(spacing.xl))
            MagicSkeletonTile(
                modifier = Modifier.size(width = 180.dp, height = 240.dp),
                shape = MaterialTheme.shapes.medium,
                glyphSize = 96.dp,
            )
            Spacer(Modifier.height(spacing.lg))
            Text(
                "Pick a voice",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(spacing.sm))
            Text(
                "Storyvox uses an offline neural TTS engine. Pick a starter " +
                    "voice — you can add more in Settings → Voice library later.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(spacing.xl))

            recommended.forEach { voice ->
                VoiceTile(
                    voice = voice,
                    isDownloading = downloadingVoiceId == voice.id,
                    progress = progress.takeIf { downloadingVoiceId == voice.id },
                    enabled = downloadingVoiceId == null,
                    onPick = { onPick(voice.id) },
                    onDismissError = onDismissProgress,
                )
                Spacer(Modifier.height(spacing.sm))
            }

            Spacer(Modifier.height(spacing.md))
            BrassButton(
                label = "More voices →",
                onClick = onOpenLibrary,
                variant = BrassButtonVariant.Text,
                enabled = downloadingVoiceId == null,
            )
            Spacer(Modifier.height(spacing.xs))
            BrassButton(
                label = "Continue without audio",
                onClick = onSkip,
                variant = BrassButtonVariant.Text,
                enabled = downloadingVoiceId == null,
            )
            Spacer(Modifier.height(spacing.xs))
            Text(
                "Reader-only mode skips audio playback for this session.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(spacing.xl))
        }
    }
}

@Composable
private fun VoiceTile(
    voice: UiVoiceInfo,
    isDownloading: Boolean,
    progress: DownloadProgress?,
    enabled: Boolean,
    onPick: () -> Unit,
    onDismissError: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val sizeMb = (voice.sizeBytes / 1_000_000L).coerceAtLeast(1L)
    val failed = progress as? DownloadProgress.Failed

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            // a11y (#481): Role.Button for the voice-pick tap.
            .clickable(role = Role.Button, enabled = enabled && !isDownloading) { onPick() }
            .padding(spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                // Title gains the language flag prefix per #128 — the
                // catalog [displayName] is now flag-free, so we
                // re-prefix it at render time. Subtitle keeps tier +
                // size since this picker drives a download decision;
                // language is already encoded in the flag.
                Text(
                    "${flagForLanguage(voice.language)} ${voice.displayName}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${voice.qualityLevel.name.lowercase()} · ${sizeMb} MB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (isDownloading && progress != null) {
            Spacer(Modifier.height(spacing.sm))
            DownloadProgressBlock(
                progress = progress,
                onDismissError = onDismissError,
            )
        } else if (failed != null) {
            Spacer(Modifier.height(spacing.sm))
            DownloadProgressBlock(progress = failed, onDismissError = onDismissError)
        }
    }
}

@Composable
private fun DownloadProgressBlock(
    progress: DownloadProgress,
    onDismissError: () -> Unit,
) {
    val spacing = LocalSpacing.current
    when (progress) {
        DownloadProgress.Resolving -> {
            Text(stringResource(R.string.engine_voice_resolving), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(spacing.xs))
            // Indeterminate brass comet — the manifest fetch is brief but
            // network-flaky enough that callers sit on this state for 1-3s.
            BrassProgressBar(progress = null, modifier = Modifier.fillMaxWidth())
        }
        is DownloadProgress.Downloading -> {
            val pct = if (progress.totalBytes > 0L) {
                (progress.bytesRead.toFloat() / progress.totalBytes).coerceIn(0f, 1f)
            } else 0f
            val mb = progress.bytesRead / 1_000_000
            val totalMb = progress.totalBytes / 1_000_000
            Text(
                "Downloading… $mb MB / $totalMb MB",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(spacing.xs))
            // Determinate when we know totalBytes (sherpa-onnx serves
            // Content-Length on the canonical CDN). Indeterminate fallback
            // for rarer CDNs that omit it.
            BrassProgressBar(
                progress = if (progress.totalBytes > 0L) pct else null,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        DownloadProgress.Done -> {
            Text(
                "Voice ready.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        is DownloadProgress.Failed -> {
            Text(
                progress.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(spacing.xs))
            BrassButton(
                label = "Try again",
                onClick = onDismissError,
                variant = BrassButtonVariant.Text,
            )
        }
    }
}
