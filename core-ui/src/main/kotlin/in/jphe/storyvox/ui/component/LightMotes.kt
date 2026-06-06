package `in`.jphe.storyvox.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.ui.theme.BrassRamp
import `in`.jphe.storyvox.ui.theme.LibraryNocturneTheme
import `in`.jphe.storyvox.ui.theme.LocalAnimationSpeedScale
import `in`.jphe.storyvox.ui.theme.LocalParticleIntensity
import `in`.jphe.storyvox.ui.theme.LocalReducedMotion
import `in`.jphe.storyvox.ui.theme.ParticleIntensity
import `in`.jphe.storyvox.ui.theme.scaleDurationMs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Candela (v1.1.0) — the celebratory **one-shot** sibling of
 * [BrassEmberOverlay].
 *
 * Where [BrassEmberOverlay] is an *infinite* ambient candle-ember
 * drift (always running, layered over the warming cover), [LightMotes]
 * is a *finite* burst: a wash of warm amber→gold motes that rise out
 * of the bottom, bloom with a soft radial glow, sway gently, and fade
 * to nothing over [LIGHT_MOTES_LIFETIME_MS] — then call [onFinished] exactly once.
 * It is the shared "luminous language" for every celebration in the
 * app: the v1.1 milestone ignite, the hidden candle egg, the book-
 * streak tiers, and the chapter-complete moment (replacing the old
 * falling [MilestoneConfetti]). One mote engine, used everywhere, so
 * the whole app celebrates in the same voice.
 *
 * Shares [BrassEmberOverlay]'s three preference seams so a celebration
 * never contradicts the user's own settings:
 *  - [LocalReducedMotion] — motion off → a single warm static bloom +
 *    no rising particles (still warm, not blank), and [onFinished]
 *    fires on the next frame so any one-time gate still closes.
 *  - [LocalAnimationSpeedScale] (#589) — scales the burst duration;
 *    scale 0 freezes the same as reduced motion.
 *  - [LocalParticleIntensity] (#590) — None still runs the burst (a
 *    celebration with zero motes would just be a flash), but at a
 *    floor count; Subtle is the default density; Lush doubles it.
 *
 * **onFinished reliability** — [onFinished] is captured via
 * [rememberUpdatedState] and fired from a [LaunchedEffect] whose key is
 * [Unit], so it runs once per mount. If the host removes [LightMotes]
 * early (composition leaves before the burst completes), the
 * LaunchedEffect is cancelled and [onFinished] does NOT fire — so the
 * **caller** must treat the side-effect (e.g. markV110ConfettiShown,
 * streak-flag) as the gate, and the caller serializes that through the
 * repo seam exactly like [MilestoneConfetti]'s host does. This keeps a
 * dropped celebration from permanently consuming its one-time flag.
 *
 * Pure Compose [Canvas] — no third-party particle library.
 */
@Composable
fun LightMotes(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = LocalReducedMotion.current
    val speedScale = LocalAnimationSpeedScale.current
    val intensity = LocalParticleIntensity.current
    val frozen = reducedMotion || speedScale <= 0f

    // Capture the latest onFinished so a recomposition that swaps the
    // lambda doesn't fire a stale one. Single-fire via the Unit-keyed
    // effect below.
    val finished by rememberUpdatedState(onFinished)

    if (frozen) {
        // Warm static fallback — a single soft amber bloom at the
        // bottom-center, so reduced-motion users still get a "light was
        // lit" beat rather than nothing. Fire onFinished next frame so
        // the one-time gate closes immediately.
        LaunchedEffect(Unit) { finished() }
        Box(modifier = modifier.fillMaxSize()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawMoteBloom(
                    center = Offset(size.width * 0.5f, size.height * 0.72f),
                    radius = size.minDimension * 0.22f,
                    alpha = 0.5f,
                )
            }
        }
        return
    }

    // Mote count from intensity: floor of 10 even at None (a
    // celebration needs *some* light), default 18 at Subtle, 36 at
    // Lush. Distinct from the ambient overlay's calmer 6/12 — a burst
    // wants more density than a background drift.
    val moteCount = when (intensity) {
        ParticleIntensity.None -> 10
        ParticleIntensity.Subtle -> 18
        ParticleIntensity.Lush -> 36
    }

    // Stable seed pinned at first composition so motes don't reshuffle
    // when an ancestor recomposes mid-burst.
    val seed = remember { Random.nextLong() }
    val motes = remember(seed, moteCount) {
        val r = Random(seed)
        List(moteCount) { i ->
            Mote(
                x0 = r.nextFloat(),
                swayAmp = 0.03f + r.nextFloat() * 0.05f,
                swayFreq = 0.5f + r.nextFloat() * 0.9f,
                radiusDp = 2.0f + r.nextFloat() * 2.5f,
                phase = r.nextFloat(),
                rise = 0.85f + r.nextFloat() * 0.25f,
                // Amber→gold gradient across the trio: deep brass, warm
                // brass, near-cream gold — the "rising spark" palette.
                tint = when (i % 3) {
                    0 -> BrassRamp.Brass400
                    1 -> BrassRamp.Brass300
                    else -> BrassRamp.Brass100
                },
            )
        }
    }

    // One transition 0→1 over the scaled lifetime drives the whole
    // burst; updateTransition cancels cleanly if the parent removes us.
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        started = true
        kotlinx.coroutines.delay(scaleDurationMs(LIGHT_MOTES_LIFETIME_MS, speedScale).toLong().coerceAtLeast(1L))
        finished()
    }
    val transition = updateTransition(targetState = started, label = "lightMotesT")
    val progress by transition.animateFloat(
        label = "lightMotesProgress",
        transitionSpec = {
            tween(
                durationMillis = scaleDurationMs(LIGHT_MOTES_LIFETIME_MS, speedScale).coerceAtLeast(1),
                easing = LinearEasing,
            )
        },
    ) { if (it) 1f else 0f }

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val onePx = 1.dp.toPx()

            // A soft warm bloom at the bottom that swells in the first
            // third then fades — the "glow" beat under the rising motes.
            val bloomAlpha = moteBloomAlpha(progress)
            if (bloomAlpha > 0.01f) {
                drawMoteBloom(
                    center = Offset(w * 0.5f, h * 0.78f),
                    radius = h * 0.34f,
                    alpha = bloomAlpha,
                )
            }

            for (m in motes) {
                // Each mote is offset in phase so they don't all rise in
                // lockstep; its own progress is clamped 0..1.
                val mp = ((progress + m.phase) % 1f)
                val alpha = moteAlpha(mp)
                if (alpha <= 0.01f) continue
                val swayRad = (mp * 2.0 * Math.PI * m.swayFreq).toFloat()
                val x = (m.x0 + m.swayAmp * sin(swayRad.toDouble()).toFloat())
                    .coerceIn(0.04f, 0.96f) * w
                // Rise from just below the bottom edge (1.02) up past the
                // top (-0.04), so motes drift INTO and OUT of frame
                // rather than popping at the edges.
                val y = (1.02f - mp * m.rise * 1.06f) * h
                val r = m.radiusDp * onePx
                // Soft mote: a small filled core with a faint halo so it
                // reads as light, not a hard dot.
                drawCircle(
                    color = m.tint.copy(alpha = m.tint.alpha * alpha * 0.30f),
                    radius = r * 2.2f,
                    center = Offset(x, y),
                )
                drawCircle(
                    color = m.tint.copy(alpha = m.tint.alpha * alpha),
                    radius = r,
                    center = Offset(x, y),
                )
            }
        }
    }
}

/** Per-mote model. Fractions are of the canvas (resolution-independent). */
private data class Mote(
    val x0: Float,
    val swayAmp: Float,
    val swayFreq: Float,
    val radiusDp: Float,
    val phase: Float,
    val rise: Float,
    val tint: Color,
)

/** Total burst lifetime before [LightMotes] calls onFinished. */
internal const val LIGHT_MOTES_LIFETIME_MS: Int = 2800

/**
 * Structural canary (matches the team's pure-fn-marker pattern): set
 * when [LightMotes] is the shared celebration engine reused across the
 * milestone ignite, hidden egg, streak, and chapter-complete surfaces.
 * A refactor that forks a second particle system must consciously flip
 * this.
 */
internal const val lightMotesIsSharedCelebrationEngine: Boolean = true

/**
 * Per-mote alpha envelope over its own 0..1 progress:
 *  - 0.00..0.18 fade in (0 → 0.9)
 *  - 0.18..0.70 hold (0.9)
 *  - 0.70..1.00 fade out (0.9 → 0)
 * Pure so the curve is verifiable without a frame clock.
 */
internal fun moteAlpha(progress: Float): Float = when {
    progress < 0f -> 0f
    progress < 0.18f -> (progress / 0.18f) * 0.9f
    progress < 0.70f -> 0.9f
    progress < 1f -> ((1f - progress) / 0.30f).coerceIn(0f, 1f) * 0.9f
    else -> 0f
}

/**
 * The warm radial bloom's alpha over the whole-burst progress:
 * swells over the first 0.30, holds briefly, then fades by 0.85 so the
 * glow leaves a lingering warmth and is gone before the motes finish.
 * Pure for the same reason as [moteAlpha].
 */
internal fun moteBloomAlpha(progress: Float): Float = when {
    progress < 0f -> 0f
    progress < 0.30f -> (progress / 0.30f) * 0.45f
    progress < 0.55f -> 0.45f
    progress < 0.85f -> (1f - (progress - 0.55f) / 0.30f) * 0.45f
    else -> 0f
}

/** Draws a soft amber radial glow centered at [center]. Shared by the
 *  live burst and the reduced-motion static fallback. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMoteBloom(
    center: Offset,
    radius: Float,
    alpha: Float,
) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                BrassRamp.Brass300.copy(alpha = alpha),
                BrassRamp.Brass400.copy(alpha = alpha * 0.5f),
                Color.Transparent,
            ),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}

// region Previews

@Preview(name = "LightMotes — mid-burst (dark)", widthDp = 360, heightDp = 640)
@Composable
private fun PreviewLightMotesDark() = LibraryNocturneTheme(darkTheme = true) {
    PreviewMotesFrame(progressPinned = 0.5f)
}

@Composable
private fun PreviewMotesFrame(progressPinned: Float) {
    val r = Random(7L)
    val motes = List(18) { i ->
        Mote(
            x0 = r.nextFloat(),
            swayAmp = 0.03f + r.nextFloat() * 0.05f,
            swayFreq = 0.5f + r.nextFloat() * 0.9f,
            radiusDp = 2.0f + r.nextFloat() * 2.5f,
            phase = r.nextFloat(),
            rise = 0.85f + r.nextFloat() * 0.25f,
            tint = when (i % 3) {
                0 -> BrassRamp.Brass400
                1 -> BrassRamp.Brass300
                else -> BrassRamp.Brass100
            },
        )
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1F1424)),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val onePx = 1.dp.toPx()
            val bloomAlpha = moteBloomAlpha(progressPinned)
            if (bloomAlpha > 0.01f) {
                drawMoteBloom(Offset(w * 0.5f, h * 0.78f), h * 0.34f, bloomAlpha)
            }
            for (m in motes) {
                val mp = ((progressPinned + m.phase) % 1f)
                val alpha = moteAlpha(mp)
                if (alpha <= 0.01f) continue
                val swayRad = (mp * 2.0 * Math.PI * m.swayFreq).toFloat()
                val x = (m.x0 + m.swayAmp * sin(swayRad.toDouble()).toFloat())
                    .coerceIn(0.04f, 0.96f) * w
                val y = (1.02f - mp * m.rise * 1.06f) * h
                val rad = m.radiusDp * onePx
                drawCircle(m.tint.copy(alpha = m.tint.alpha * alpha * 0.30f), rad * 2.2f, Offset(x, y))
                drawCircle(m.tint.copy(alpha = m.tint.alpha * alpha), rad, Offset(x, y))
            }
        }
    }
}

// endregion
