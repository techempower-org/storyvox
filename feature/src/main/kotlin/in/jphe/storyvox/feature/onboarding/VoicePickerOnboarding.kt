package `in`.jphe.storyvox.feature.onboarding

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.feature.engine.VoicePickerGateViewModel
import `in`.jphe.storyvox.playback.voice.UiVoiceInfo
import `in`.jphe.storyvox.playback.voice.VoiceManager
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.component.BrassProgressBar
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Issue #599 + #600 + #627 — second of three first-launch welcome
 * screens. Replaces the engineering-jargon voice picker (engine names,
 * tier strings, raw model IDs) with a 5-year-old-friendly version:
 *
 *   - Friendly first names only (Brian, Amy, Lessac, Cori) — NO engine
 *     identifiers, NO quality-tier labels, NO sample-rate fluff.
 *   - One-line plain-English description per voice ("Warm American
 *     narrator", "British, slow and gentle", etc.).
 *   - Download size + "Free" tag visible — honest about what they're
 *     committing to but framed as a positive, not a tax.
 *   - One brass button per row labeled "Pick this voice" (NOT
 *     "Activate", which #627 flagged as ambiguous).
 *   - "Skip — I'll choose later" link at the bottom. When the user
 *     skips, no voice is downloaded; the VoicePickerGate stays armed
 *     and reprompts when the user taps a chapter.
 *
 * Reuses the existing [VoicePickerGateViewModel] to drive the download
 * progress flow — this screen is the welcome-flow front end; the gate
 * remains the source of truth for "is a voice active". When the user
 * picks a voice here, the gate's `pick()` runs the download +
 * `setActive` and (because activeVoice flips non-null) the gate
 * suppresses itself for the post-welcome experience.
 */
@Composable
fun VoicePickerOnboarding(
    onContinue: () -> Unit,
    onSkip: () -> Unit,
    onMoreVoices: () -> Unit,
    viewModel: VoicePickerGateViewModel = hiltViewModel(),
) {
    val recommended by viewModel.recommended.collectAsStateWithLifecycle()
    val downloadingId by viewModel.downloadingVoiceId.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val activeVoice by viewModel.activeVoice.collectAsStateWithLifecycle()
    // Issue #681 — banner gate: true on devices like the Z Flip 3 (Google
    // TTS pre-installed, 22 voices enumerated) → banner hides. False on
    // TTS-less devices like stock Samsung Galaxy Tab A7 Lite (zero TTS
    // engines installed) → banner renders above the friendly Piper tiles
    // with an "Install Google TTS" CTA.
    val hasSystemTts by viewModel.hasSystemTtsEngines.collectAsStateWithLifecycle()

    // Latch on the initial activeVoice — if the user already had a
    // voice picked (post-reset replay), we don't want
    // [onPickComplete] to fire on the first composition. The latch
    // captures the entry value once via [remember]; subsequent
    // re-emissions trigger the auto-advance only when the value
    // changes from the initial null state.
    val initialActive = remember { activeVoice }
    // Issue #682 — if the user already has an active voice when this
    // step composes (the #676 System TTS seed populated it before
    // onboarding began), skip the picker entirely. The user can
    // change voices via Settings → Voice library. Pre-fix the
    // download-only Piper picker rendered anyway, re-blocking the
    // exact zero-download path #676 was supposed to enable.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (initialActive != null) {
            onContinue()
        }
    }
    androidx.compose.runtime.LaunchedEffect(activeVoice, downloadingId) {
        // Auto-advance when a download successfully completes:
        //   - activeVoice transitions from initial (typically null)
        //     to non-null AND
        //   - the download isn't mid-flight anymore.
        if (activeVoice != null && activeVoice != initialActive && downloadingId == null) {
            onContinue()
        }
    }

    VoicePickerOnboardingContent(
        recommended = recommended,
        downloadingVoiceId = downloadingId,
        progress = progress,
        showInstallSystemTtsBanner = !hasSystemTts,
        onPick = { voiceId ->
            viewModel.pick(voiceId)
        },
        onContinue = onContinue,
        onSkip = onSkip,
        onMoreVoices = onMoreVoices,
        onDismissProgress = viewModel::dismissProgress,
    )
}

@Composable
private fun VoicePickerOnboardingContent(
    recommended: List<UiVoiceInfo>,
    downloadingVoiceId: String?,
    progress: VoiceManager.DownloadProgress?,
    showInstallSystemTtsBanner: Boolean,
    onPick: (String) -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit,
    onMoreVoices: () -> Unit,
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
            Spacer(Modifier.height(spacing.lg))
            Text(
                stringResource(R.string.onboarding_voice_headline),
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Medium,
                fontSize = 28.sp,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(spacing.sm))
            Text(
                stringResource(R.string.onboarding_voice_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 400.dp),
            )
            Spacer(Modifier.height(spacing.lg))

            // Issue #681 — TTS-less device fallback CTA. On stock Samsung
            // tablets (and other devices that ship without Google TTS
            // pre-installed) the System TTS roster is empty, so the
            // #676 zero-download first-listen path silently degrades to
            // the legacy Piper download. Surface a one-liner banner +
            // Play Store deep-link so the user knows there's a one-tap
            // path to the built-in-voices experience. On devices that
            // already have System TTS engines (Z Flip 3, most modern
            // phones) the banner is hidden and behavior matches v0.5.72.
            if (showInstallSystemTtsBanner) {
                InstallSystemTtsBanner()
                Spacer(Modifier.height(spacing.md))
            }

            // Show at most 3-4 friendly voices for the v1.0 onboarding.
            // The catalog's featuredIds today is 5 (Lessac × 3 tiers,
            // Cori × 2 tiers); we collapse same-named entries to their
            // medium tier so the user sees ONE row per *voice
            // personality*, not three rows of "Lessac" that look like
            // bugs. The full picker (More voices) keeps every tier
            // visible for power users.
            val friendlyVoices = remember(recommended) { friendlyVoiceSelection(recommended) }

            friendlyVoices.forEach { friendly ->
                FriendlyVoiceTile(
                    voice = friendly,
                    isDownloading = downloadingVoiceId == friendly.voice.id,
                    progress = progress.takeIf { downloadingVoiceId == friendly.voice.id },
                    enabled = downloadingVoiceId == null,
                    onPick = { onPick(friendly.voice.id) },
                    onDismissError = onDismissProgress,
                )
                Spacer(Modifier.height(spacing.sm))
            }

            Spacer(Modifier.height(spacing.md))
            BrassButton(
                label = stringResource(R.string.onboarding_voice_more_voices),
                onClick = onMoreVoices,
                variant = BrassButtonVariant.Text,
                enabled = downloadingVoiceId == null,
            )
            BrassButton(
                label = stringResource(R.string.onboarding_voice_skip),
                onClick = onSkip,
                variant = BrassButtonVariant.Text,
                enabled = downloadingVoiceId == null,
            )
            Spacer(Modifier.height(spacing.xl))
        }
    }
}

/**
 * Issue #681 — banner shown above the friendly voice tiles when the OS
 * exposes zero System TTS voices (no Google TTS, no Samsung SMT, no
 * eSpeak — typical of stock Samsung tablets and other devices that ship
 * without GMS TTS pre-installed).
 *
 * The copy is intentionally one-line and frames the Piper download as a
 * legitimate alternative, not a fallback: a user can either install
 * Google TTS (zero-byte storyvox download afterward) or pick a Piper
 * voice below (~14 MB one-time download). Both paths work.
 *
 * The "Install Google TTS" affordance fires `Intent.ACTION_VIEW` against
 * `market://details?id=com.google.android.tts` — opens the Play Store
 * app's listing for Google Speech Services. On devices without Play
 * Store installed (some non-GMS Android variants), `startActivity`
 * throws [ActivityNotFoundException]; we catch it and fall back to the
 * `https://play.google.com/...` web URL, which any browser can handle.
 *
 * Theme-safe by construction: surfaceVariant background + primary
 * accent + onSurface body text track [MaterialTheme.colorScheme] tokens
 * so the banner reads correctly under both light and dark themes.
 *
 * TalkBack: the banner copy is read as a single sentence (no per-word
 * fragmentation), and the BrassButton inherits the [Role.Button]
 * semantic from BrassButton itself, so TalkBack announces it as
 * "Install Google TTS, button".
 */
@Composable
private fun InstallSystemTtsBanner() {
    val context = LocalContext.current
    val spacing = LocalSpacing.current
    val copy = stringResource(R.string.onboarding_voice_install_tts_banner)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 480.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(spacing.md)
            .semantics { contentDescription = copy },
    ) {
        Text(
            text = copy,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Start,
        )
        Spacer(Modifier.height(spacing.sm))
        BrassButton(
            label = stringResource(R.string.onboarding_voice_install_tts_cta),
            onClick = {
                val marketUri = Uri.parse("market://details?id=com.google.android.tts")
                val marketIntent = Intent(Intent.ACTION_VIEW, marketUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(marketIntent)
                } catch (_: ActivityNotFoundException) {
                    // Devices without Play Store (LineageOS, GrapheneOS,
                    // some Huawei builds) — fall through to the web
                    // listing, which any browser can render.
                    val webUri = Uri.parse(
                        "https://play.google.com/store/apps/details?id=com.google.android.tts",
                    )
                    val webIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        context.startActivity(webIntent)
                    } catch (_: ActivityNotFoundException) {
                        // No browser either — extraordinarily rare. We
                        // intentionally swallow rather than crash; the
                        // banner copy still informs the user, and the
                        // Piper tiles below remain functional.
                    }
                }
            },
            variant = BrassButtonVariant.Primary,
        )
    }
}

/**
 * One row in the friendly voice picker — a name, a one-line plain-
 * English description, a download size + Free tag, and a "Pick this
 * voice" button. While downloading, the button collapses into a
 * progress block (same shape as the legacy gate's downloader so the
 * visual progresses identically).
 *
 * Why a single big "Pick this voice" button instead of a row-wide tap
 * target: a 5-year-old (or a TalkBack user navigating with single-tap
 * gestures) shouldn't have to discover that the whole tile is
 * clickable. An explicit button is unambiguous — and per #627, the
 * button label is "Pick this voice", not the ambiguous "Activate".
 */
@Composable
private fun FriendlyVoiceTile(
    voice: FriendlyVoice,
    isDownloading: Boolean,
    progress: VoiceManager.DownloadProgress?,
    enabled: Boolean,
    onPick: () -> Unit,
    onDismissError: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val sizeMb = (voice.voice.sizeBytes / 1_000_000L).coerceAtLeast(1L)
    val failed = progress as? VoiceManager.DownloadProgress.Failed

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 480.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Text(
                    voice.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (voice.descriptionFallbackArg != null) {
                        stringResource(voice.descriptionRes, voice.descriptionFallbackArg)
                    } else {
                        stringResource(voice.descriptionRes)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.onboarding_voice_free_size, sizeMb.toInt()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        if (isDownloading && progress != null) {
            Spacer(Modifier.height(spacing.sm))
            FriendlyDownloadProgress(
                voiceName = voice.displayName,
                progress = progress,
                onDismissError = onDismissError,
            )
        } else if (failed != null) {
            Spacer(Modifier.height(spacing.sm))
            FriendlyDownloadProgress(
                voiceName = voice.displayName,
                progress = failed,
                onDismissError = onDismissError,
            )
        } else {
            Spacer(Modifier.height(spacing.sm))
            BrassButton(
                label = stringResource(R.string.onboarding_voice_pick_cta),
                onClick = onPick,
                variant = BrassButtonVariant.Primary,
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun FriendlyDownloadProgress(
    voiceName: String,
    progress: VoiceManager.DownloadProgress,
    onDismissError: () -> Unit,
) {
    val spacing = LocalSpacing.current
    when (progress) {
        VoiceManager.DownloadProgress.Resolving -> {
            Text(
                stringResource(R.string.onboarding_voice_download_resolving, voiceName),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(spacing.xs))
            BrassProgressBar(progress = null, modifier = Modifier.fillMaxWidth())
        }
        is VoiceManager.DownloadProgress.Downloading -> {
            val pct = if (progress.totalBytes > 0L) {
                (progress.bytesRead.toFloat() / progress.totalBytes).coerceIn(0f, 1f)
            } else 0f
            val mb = progress.bytesRead / 1_000_000
            val totalMb = progress.totalBytes / 1_000_000
            Text(
                stringResource(R.string.onboarding_voice_downloading, voiceName, mb.toInt(), totalMb.toInt()),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(spacing.xs))
            BrassProgressBar(
                progress = if (progress.totalBytes > 0L) pct else null,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        VoiceManager.DownloadProgress.Done -> {
            Text(
                stringResource(R.string.onboarding_voice_download_done, voiceName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        is VoiceManager.DownloadProgress.Failed -> {
            Text(
                stringResource(R.string.onboarding_voice_download_failed, voiceName, progress.reason),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(spacing.xs))
            BrassButton(
                label = stringResource(R.string.onboarding_voice_try_again),
                onClick = onDismissError,
                variant = BrassButtonVariant.Text,
            )
        }
    }
}

/**
 * Friendly-name overlay for a single catalog voice — pairs the
 * engineering-named [UiVoiceInfo] with a plain-English [displayName]
 * and a description resource that a 5-year-old reader (or a TalkBack
 * listener) can parse on first read.
 *
 * [descriptionRes] resolves at render time via [stringResource]. When
 * [descriptionFallbackArg] is non-null, the resource is formatted with
 * it (currently used by the [R.string.voice_desc_fallback] template for
 * voices not in the curated description table).
 */
internal data class FriendlyVoice(
    val voice: UiVoiceInfo,
    val displayName: String,
    @androidx.annotation.StringRes val descriptionRes: Int,
    val descriptionFallbackArg: String? = null,
)

/**
 * Curate the recommended-voice list down to 3-4 friendly entries by
 * collapsing same-personality tiers (Lessac low/medium/high becomes a
 * single "Lessac" entry pointing at the medium tier) and layering a
 * plain-English description over each. The catalog's existing display
 * names ("Lessac", "Cori") are kept verbatim — they're already first
 * names — but each is paired with a one-line description so a brand-
 * new user knows what "Lessac" *sounds like* without having to tap
 * "Hear a sample".
 *
 * Why we don't dynamically detect gender/accent from the catalog and
 * synthesise the description: the catalog's per-voice metadata is
 * inconsistent (some en_GB Piper voices are unlabeled, Kokoro's
 * speaker-index voices have no per-row gender at all). A hand-curated
 * lookup table is one PR-to-update vs. a metadata audit across the
 * whole catalog.
 */
internal fun friendlyVoiceSelection(recommended: List<UiVoiceInfo>): List<FriendlyVoice> {
    if (recommended.isEmpty()) return emptyList()
    val descriptions = mapOf(
        "lessac" to R.string.voice_desc_lessac,
        "cori" to R.string.voice_desc_cori,
        "amy" to R.string.voice_desc_amy,
        "ryan" to R.string.voice_desc_ryan,
        "alan" to R.string.voice_desc_alan,
        "alba" to R.string.voice_desc_alba,
    )
    // Collapse same-named entries to the FIRST one we see (catalog
    // orders featuredIds by tier: low → medium → high). Picking the
    // first means we surface the smallest download by default — a
    // user with patchy wifi gets to listen sooner. Power users hit
    // "More voices →" for the high-tier variants.
    val seen = LinkedHashSet<String>()
    val collapsed = recommended.filter { v ->
        val key = v.displayName.lowercase()
        if (key in seen) false else { seen.add(key); true }
    }
    return collapsed.take(4).map { v ->
        val key = v.displayName.lowercase()
        val curatedRes = descriptions[key]
        if (curatedRes != null) {
            FriendlyVoice(
                voice = v,
                displayName = v.displayName,
                descriptionRes = curatedRes,
            )
        } else {
            FriendlyVoice(
                voice = v,
                displayName = v.displayName,
                descriptionRes = R.string.voice_desc_fallback,
                descriptionFallbackArg = v.displayName,
            )
        }
    }
}
