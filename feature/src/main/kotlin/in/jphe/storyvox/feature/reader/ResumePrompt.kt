package `in`.jphe.storyvox.feature.reader

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.data.repository.ContinueListeningEntry
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.component.FictionCoverThumb
import `in`.jphe.storyvox.ui.component.MagicSkeletonTile
import `in`.jphe.storyvox.ui.component.MagicSpinner
import `in`.jphe.storyvox.ui.component.fictionMonogram
import `in`.jphe.storyvox.ui.theme.LocalMotion
import `in`.jphe.storyvox.ui.theme.LocalReducedMotion
import `in`.jphe.storyvox.ui.theme.LocalSpacing
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Playing tab's "pick up where you left off" hero. Rendered by
 * [HybridReaderScreen] when the player has no chapter loaded yet (cold
 * launch, app-killed-with-stale-state, hard-timeout error path) but the
 * playback-position table has a most-recent entry.
 *
 * Visual structure, top → bottom:
 *  - A cover tile (220 × 330 dp, 2:3) with a soft brass halo and a slow
 *    edge-alpha breath (0.85 ↔ 1.0 over 2 s). A [MagicSpinner] sigil
 *    ring rotates slowly behind the cover, alpha-breathing in sync with
 *    the halo.
 *  - Behind it, a sparse Canvas of drifting brass particles — cheap,
 *    sets the "magic is at work" mood without competing with the cover.
 *  - Fiction title (titleLarge, brass) + author (bodyMedium, dim).
 *  - Chapter line + progress + "X min ago" relative time.
 *  - A brass-filled "Start listening" Primary button (issue #601) with a one-shot
 *    diagonal shimmer sweep when the prompt first composes.
 *  - "From the start" text button below.
 *
 * Auto-play decision: tapping Resume calls back through to the
 * ReaderViewModel which routes through `PlaybackResumePolicyConfig`
 * (mirroring [LibraryViewModel.resume]). If the user explicitly paused
 * before, we load-but-don't-auto-play. App-killed-mid-playback (no
 * explicit pause) still auto-plays, which is the dominant "phone died,
 * keep going" case.
 *
 * Reduced-motion behaviour: when [LocalReducedMotion] is true, every
 * animation in this surface drops to a static frame — no rotation, no
 * breath, no particle drift, no shimmer. Library Nocturne treats
 * reduced-motion as ABSENT motion, not shorter motion.
 */
@Composable
fun ResumePrompt(
    entry: ContinueListeningEntry,
    onResume: () -> Unit,
    onFromStart: () -> Unit,
    nowMs: Long = System.currentTimeMillis(),
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val motion = LocalMotion.current
    val reducedMotion = LocalReducedMotion.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Subtle background brass-particle drift. Behind everything else,
        // alpha capped low so it reads as atmosphere rather than UI.
        if (!reducedMotion) {
            BrassParticleField(
                modifier = Modifier.fillMaxSize().alpha(0.55f),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.lg, vertical = spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.height(spacing.lg))

            // Cover + sigil ring stack. The MagicSpinner is sized larger
            // than the cover so the ring orbits visually around it; the
            // cover sits centered on top.
            CoverWithSigil(
                entry = entry,
                reducedMotion = reducedMotion,
            )

            Spacer(Modifier.height(spacing.lg))

            Text(
                text = entry.fiction.title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
            if (entry.fiction.author.isNotBlank()) {
                Spacer(Modifier.height(spacing.xxs))
                Text(
                    text = "by ${entry.fiction.author}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(spacing.md))

            // Chapter line — matches the LibraryScreen ResumeCard format:
            // "Ch. 12 · The Doors Open" (no separator when title blank).
            // Issue #453 — 1-indexed display (index + 1) so the user
            // never sees the storyvox internal 0-indexed counter; if
            // the title already starts with "Chapter N" (Gutenberg's
            // spine entries do), drop the "Chapter M ·" prefix instead
            // of clashing with the title's own numbering.
            val chapterTitle = entry.chapter.title
            val titleAlreadyIndexed = chapterTitle.matches(
                Regex("(?i)^\\s*(ch(apter|\\.)?|episode|part)\\s*\\d+.*"),
            )
            Text(
                text = when {
                    chapterTitle.isBlank() -> "Chapter ${entry.chapter.index + 1}"
                    titleAlreadyIndexed -> chapterTitle
                    else -> "Chapter ${entry.chapter.index + 1} · $chapterTitle"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(spacing.xxs))
            // Progress + relative time as a single dim subline.
            val fraction = entry.progressFraction()
            val relative = relativeTimeString(entry.updatedAt, nowMs)
            val subline = buildString {
                if (fraction != null) append("${(fraction * 100).toInt()}%")
                if (fraction != null && relative != null) append(" · ")
                if (relative != null) append(relative)
            }
            if (subline.isNotEmpty()) {
                Text(
                    text = subline,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(spacing.xl))

            // The hero CTA. One-shot brass shimmer sweep on enter is layered
            // as a translucent gradient overlay; the BrassButton itself
            // handles the click semantics + brass palette so we don't fork
            // the component contract.
            ShimmerOverlay(
                enabled = !reducedMotion,
                durationMs = motion.swipeDurationMs * 3, // a deliberate 1080ms reveal
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Issue #601 — "Playing" tab named for playback, so
                // the CTA verb has to match. Pre-fix this read "Resume
                // reading" — which sounded like the tab opened a
                // text-reader, not an audio player. JP's audit:
                // "Playing" → tap → see card with no audio + a
                // "reading" button → confusion. The verb is now
                // unambiguous: tap → audio starts. The brass-shimmer
                // sweep is preserved (it's the moment of magic
                // storyvox is named for).
                BrassButton(
                    label = stringResource(R.string.reader_start_listening),
                    onClick = onResume,
                    variant = BrassButtonVariant.Primary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(spacing.sm))

            BrassButton(
                label = stringResource(R.string.reader_from_the_start),
                onClick = onFromStart,
                variant = BrassButtonVariant.Text,
            )

            Spacer(Modifier.height(spacing.xl))
        }
    }
}

/**
 * Cover thumb (220 × 330) with a slow-rotating brass sigil ring orbiting
 * behind it and a soft 2 s alpha-breath. Reduced motion locks all three
 * to a static frame.
 */
@Composable
private fun CoverWithSigil(
    entry: ContinueListeningEntry,
    reducedMotion: Boolean,
) {
    // Soft alpha breath on the cover edge — only when motion is on.
    val breath = if (reducedMotion) 1f else {
        val transition = rememberInfiniteTransition(label = "resume-breath")
        val v by transition.animateFloat(
            initialValue = 0.85f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "breath",
        )
        v
    }

    Box(contentAlignment = Alignment.Center) {
        // Brass halo — radial gradient behind everything so the cover
        // sits on a softly-lit substrate. Sized larger than the spinner
        // so its falloff stays outside the ring.
        Box(
            modifier = Modifier
                .size(width = 320.dp, height = 430.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f * breath),
                            MaterialTheme.colorScheme.primary.copy(alpha = 0f),
                        ),
                    ),
                ),
        )

        // Sigil ring — slow-rotating dashed brass circle.
        if (reducedMotion) {
            // Static placeholder: a single faint MagicSpinner frame would
            // still spin (it's an infinite animation), so we omit it
            // entirely. The brass halo + static cover sigil placeholder
            // are enough to set "this is a magical surface" without motion.
        } else {
            MagicSpinner(
                modifier = Modifier
                    .size(width = 280.dp, height = 390.dp)
                    .alpha(breath),
                durationMs = 16_000, // very slow — atmospheric, not anxious
                dashLengthFraction = 0.22f,
            )
        }

        // Cover thumb on top. fictionMonogram already prefers
        // author → title → brass star, so coverless fictions still get a
        // Library Nocturne sigil placeholder rather than `?` per #322.
        FictionCoverThumb(
            coverUrl = entry.fiction.coverUrl,
            title = entry.fiction.title,
            monogram = fictionMonogram(entry.fiction.author, entry.fiction.title),
            modifier = Modifier
                .size(width = 220.dp, height = 330.dp)
                .alpha(breath),
        )
    }
}

/**
 * A handful of brass dots drifting on a `Canvas`. Deliberately sparse and
 * slow — sets atmosphere, doesn't compete with the cover. The dots' positions
 * are pseudo-random but stable across recompositions (seeded by index) so
 * the field looks intentional, not flickery.
 */
@Composable
private fun BrassParticleField(modifier: Modifier = Modifier) {
    val brass = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "resume-particles")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 14_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "drift",
    )

    // Pre-compute particle seeds so each composition holds them stable —
    // remember on Unit so they regenerate exactly once per call site
    // lifetime, not per recomposition.
    val particles = remember {
        List(18) { i ->
            val seed = i * 9176543L + 31
            Particle(
                xFraction = ((seed and 0xFFFF).toFloat() / 0xFFFF),
                yFraction = (((seed shr 16) and 0xFFFF).toFloat() / 0xFFFF),
                phase = (((seed shr 32) and 0xFFFF).toFloat() / 0xFFFF),
                radiusDp = 1.5f + (((seed shr 48) and 0xFF).toFloat() / 0xFF) * 2.5f,
            )
        }
    }

    Canvas(modifier = modifier) {
        particles.forEach { p ->
            // Each particle bobs vertically on its own phase; the global
            // `drift` value drives the period. Sin keeps the motion smooth.
            val rad = (drift + p.phase) * 2.0 * PI
            val bob = sin(rad).toFloat() * 18f
            val x = size.width * p.xFraction
            val y = size.height * p.yFraction + bob
            // Brass with a soft alpha — sparkles, not headlights.
            // DrawScope exposes `density` as a member of Density; we
            // multiply the dp-valued radius by it to land in px.
            drawCircle(
                color = brass.copy(alpha = 0.20f + 0.18f * (1f - cos(rad).toFloat()) / 2f),
                radius = p.radiusDp * this.density,
                center = Offset(x, y),
            )
        }
    }
}

private data class Particle(
    val xFraction: Float,
    val yFraction: Float,
    val phase: Float,
    val radiusDp: Float,
)

/**
 * One-shot diagonal brass shimmer sweep that overlays its child the
 * moment it composes. Used to draw the eye to the Resume CTA on enter.
 * After the sweep finishes, the overlay holds at full transparency —
 * no idle pulse (we already have plenty of motion: the particle field,
 * the cover breath, the sigil ring).
 */
@Composable
private fun ShimmerOverlay(
    enabled: Boolean,
    durationMs: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier) {
        content()
        if (enabled) {
            val transition = rememberInfiniteTransition(label = "resume-shimmer-cycle")
            // Idle long pause then a brief sweep, repeating. Keeps the
            // surface attractive without becoming distracting — the
            // shimmer is rare enough to read as deliberate.
            val phase by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = durationMs + 3200,
                        easing = FastOutSlowInEasing,
                    ),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "shimmer-phase",
            )
            ShimmerSweep(phase = phase, sweepWindow = durationMs.toFloat() / (durationMs + 3200))
        }
    }
}

/**
 * Renders the actual gradient sweep as an overlay Canvas. Only paints
 * when [phase] is inside the sweep window — outside the window the
 * overlay is fully transparent.
 */
@Composable
private fun ShimmerSweep(phase: Float, sweepWindow: Float) {
    val brass = MaterialTheme.colorScheme.onPrimary
    if (phase > sweepWindow) return // idle gap — paint nothing

    val sweepProgress = (phase / sweepWindow).coerceIn(0f, 1f)
    Canvas(modifier = Modifier.fillMaxSize()) {
        val bandWidth = size.width * 0.30f
        // Travel from off-left to off-right so the band fully crosses.
        val center = -bandWidth + sweepProgress * (size.width + bandWidth * 2)
        val highlight = brass.copy(alpha = 0.28f)
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.Transparent,
                    highlight,
                    Color.Transparent,
                ),
                start = Offset(center - bandWidth / 2f, 0f),
                end = Offset(center + bandWidth / 2f, size.height),
            ),
        )
    }
}

/**
 * Convert the playback-position `updatedAt` epoch ms to a "25 min ago" /
 * "2 h ago" / "3 d ago" string, mirroring the rhythm of the existing
 * `publishedRelative` field on `UiChapter`. Returns null when the delta
 * is negative or unreasonably large (clock skew, corrupted row).
 */
internal fun relativeTimeString(updatedAt: Long, nowMs: Long): String? {
    val delta = nowMs - updatedAt
    if (delta < 0L) return null
    if (delta > 365L * 24 * 60 * 60 * 1000L * 10) return null // 10 years guard
    return when {
        delta < 60_000L -> "just now"
        delta < 60L * 60_000L -> "${delta / 60_000L} min ago"
        delta < 24L * 60 * 60_000L -> "${delta / (60L * 60_000L)} h ago"
        delta < 7L * 24 * 60 * 60_000L -> "${delta / (24L * 60 * 60_000L)} d ago"
        else -> "${delta / (7L * 24 * 60 * 60_000L)} w ago"
    }
}

/**
 * Estimate progress through the chapter from `charOffset` and `wordCount`.
 * Mirrors the helper in [LibraryScreen]; duplicated rather than hoisted to
 * avoid forcing a core-data dep on the (downstream) UI module just for one
 * pure helper. Both call sites stay in lock-step via tests.
 */
internal fun ContinueListeningEntry.progressFraction(): Float? {
    val words = chapter.wordCount ?: return null
    if (words <= 0) return null
    val estimatedChars = words * 5f
    return (charOffset / estimatedChars).coerceIn(0f, 1f)
}

/**
 * Empty-empty state: no continue-listening entry at all. Used on a
 * first-launch / wiped-data device where the Playing tab is otherwise
 * dead air. Same brass-sigil hero rhythm as [EmptyLibrary] in
 * `LibraryScreen`, plus a "Browse the realms" CTA wired by the
 * NavHost to the Browse tab.
 */
@Composable
fun ResumeEmptyPrompt(
    onBrowse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier.fillMaxSize().padding(spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MagicSkeletonTile(
            modifier = Modifier.size(width = 180.dp, height = 240.dp),
            glyphSize = 96.dp,
        )
        Spacer(Modifier.height(spacing.lg))
        Text(
            text = stringResource(R.string.reader_empty_library_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(spacing.xs))
        Text(
            text = stringResource(R.string.reader_empty_library_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(spacing.lg))
        BrassButton(
            label = stringResource(R.string.reader_browse_realms_arrow),
            onClick = onBrowse,
            variant = BrassButtonVariant.Primary,
        )
    }
}

