package `in`.jphe.storyvox.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import `in`.jphe.storyvox.ui.theme.BrassRamp
import `in`.jphe.storyvox.ui.theme.LibraryNocturneTheme
import `in`.jphe.storyvox.ui.theme.LocalAnimationSpeedScale
import `in`.jphe.storyvox.ui.theme.LocalReducedMotion
import `in`.jphe.storyvox.ui.theme.LocalSpacing
import `in`.jphe.storyvox.ui.theme.scaleDurationMs

/**
 * Candela (v1.1.0) — the "ignite" milestone dialog. The centerpiece
 * easter egg: a candle being lit, not a pep-rally.
 *
 * **The ignite sequence** is driven by ONE master progress clock
 * (0→1 over the intro) with overlapping beat windows, so it reads as a
 * single continuous ignition rather than five discrete steps (the way
 * audio crossfades overlap):
 *  1. **Flame draws in**  [0.00, 0.28] — the flame teardrop scales up
 *     from a spark at the wick.
 *  2. **Radial glow blooms** [0.12, 0.50] — a warm amber wash swells
 *     outward behind the flame (overlaps the flame draw).
 *  3. **Light-motes rise**  from ~0.30 — [LightMotes] layered over the
 *     card; warm sparks drift upward.
 *  4. **Sound-wave arc rings** [0.45, 0.95] — three concentric arcs
 *     (the BrassVoiceIcon's soundwave, reborn as rings of light) pulse
 *     outward from the flame.
 *  5. **Settle to a gentle flicker** — once the intro completes an
 *     infinite-transition flicker takes over the flame's brightness so
 *     the candle stays alive while the card rests.
 *
 * Honors [LocalReducedMotion] / [LocalAnimationSpeedScale]: when motion
 * is off the whole sequence collapses to a static lit candle + warm
 * glow (no rising motes, no pulsing rings) — still warm, never blank.
 *
 * Copy is hand-tuned for v1.1 ("Candela 1.1 — read it your way") and
 * not templated, per the Milestone.kt per-release convention.
 */
@Composable
fun V110MilestoneDialog(
    onDismiss: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val reducedMotion = LocalReducedMotion.current
    val speedScale = LocalAnimationSpeedScale.current
    val frozen = reducedMotion || speedScale <= 0f

    // Master intro progress 0→1. Hand-driven via a frame loop so we can
    // hold at 1f afterward (vs. an infinite transition that would loop
    // the ignition). Frozen → pinned at 1f (fully lit, static).
    var intro by remember { mutableFloatStateOf(if (frozen) 1f else 0f) }
    LaunchedEffect(frozen) {
        if (frozen) { intro = 1f; return@LaunchedEffect }
        val durationMs = scaleDurationMs(IGNITE_INTRO_MS, speedScale).coerceAtLeast(1)
        val start = withFrameNanosStart()
        var now = start
        while (now - start < durationMs * 1_000_000L) {
            now = awaitFrameNanos()
            intro = ((now - start).toFloat() / (durationMs * 1_000_000f)).coerceIn(0f, 1f)
        }
        intro = 1f
    }

    // Post-intro flicker — a slow brightness shimmer on the flame so the
    // candle stays alive. Frozen → steady full brightness.
    val flicker: Float = if (frozen) 1f else {
        val t = rememberInfiniteTransition(label = "v110-flicker")
        val f by t.animateFloat(
            initialValue = 0.82f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = scaleDurationMs(2200, speedScale).coerceAtLeast(1),
                    easing = LinearEasing,
                ),
                repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
            ),
            label = "v110-flicker-v",
        )
        f
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            modifier = Modifier
                .widthIn(min = 300.dp, max = 380.dp)
                .padding(horizontal = spacing.lg),
            shape = MaterialTheme.shapes.large,
            color = MilestoneSubstrateV110,
            tonalElevation = 0.dp,
            shadowElevation = 12.dp,
            border = BorderStroke(width = 1.dp, color = BrassRamp.Brass500.copy(alpha = 0.55f)),
        ) {
            // Outer Box: the content Column stacks the flame band ABOVE the
            // text (so they never fight for the same rows), and the rising
            // motes are a separate full-card overlay drifting across both.
            Box {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // The candle stage gets its OWN dedicated vertical band at
                    // the top of the card — flame + glow + arc rings, with real
                    // breathing room above it so nothing jams the top edge. The
                    // text lives strictly below this band. `clip` is the hard
                    // guarantee that the soft radial glow (and the expanding arc
                    // rings) can never paint DOWN over the title — the canvas is
                    // bounded to exactly this band.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = spacing.lg)
                            .height(176.dp)
                            .clip(RectangleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        CandleStage(intro = intro, flicker = flicker, frozen = frozen)
                    }

                    Column(
                        modifier = Modifier
                            .padding(horizontal = spacing.lg)
                            .padding(top = spacing.md, bottom = spacing.lg)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.Start,
                        verticalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        Text(
                            text = "Candela 1.1 — read it your way",
                            style = MaterialTheme.typography.headlineSmall,
                            color = BrassRamp.Brass300,
                        )
                        Text(
                            text = "Every reader holds the light a little differently.",
                            style = MaterialTheme.typography.titleMedium,
                            color = MilestoneCreamV110,
                            modifier = Modifier.padding(top = spacing.xxs),
                        )
                        Text(
                            text = "Per-word glow, your own typeface, the colors that " +
                                "rest your eyes, the sources you already love — bring " +
                                "your own, and read it your way.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MilestoneCreamV110.copy(alpha = 0.88f),
                        )
                        Text(
                            text = "— from one small flame to the next",
                            style = MaterialTheme.typography.bodySmall,
                            color = MilestoneCreamV110.copy(alpha = 0.65f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = spacing.xs),
                            textAlign = TextAlign.End,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = spacing.sm),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            BrassButton(
                                label = "Continue",
                                onClick = onDismiss,
                                variant = BrassButtonVariant.Primary,
                            )
                        }
                    }
                }

                // Rising motes drift across the WHOLE card, layered over both
                // the flame band and the text (live only; LightMotes draws a
                // static bloom when frozen and fires onFinished immediately,
                // which is harmless here — the dialog's gate is the dismiss
                // tap, not the burst). matchParentSize keeps the overlay
                // exactly the size the content Column measured, so the card
                // never grows to fit the motes.
                if (!frozen) {
                    LightMotes(
                        onFinished = {},
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }
        }
    }
}

/**
 * The lit-candle canvas: a warm radial bloom, a flame teardrop, and the
 * pulsing soundwave-arc rings — all derived from the single [intro]
 * master clock with overlapping windows, plus the post-intro [flicker]
 * on flame brightness.
 */
@Composable
private fun CandleStage(intro: Float, flicker: Float, frozen: Boolean) {
    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val cx = w * 0.5f
        // Wick / flame base — lifted to just below the band's vertical
        // center (was 0.62h, which sank the glow against the band's lower
        // edge and crowded the title below). The whole candle composition
        // (flame, glow, rings) hangs off this single anchor so it moves as
        // one unit, and a smaller glow radius leaves a dark margin before
        // the band bottom — the breathing room JP asked for.
        val baseY = h * 0.56f
        // Single shared center for the radial glow and the arc rings, so
        // the light blooms from one point and stays vertically balanced
        // within the band.
        val glowCenterY = baseY - h * 0.12f

        // Beat 2 — radial glow [0.12, 0.50], holds afterward at a calm
        // resting alpha so the card stays warm. Radius trimmed 0.46h→0.40h
        // so the soft outer falloff lands inside the band with margin.
        val glowProgress = remap(intro, 0.12f, 0.50f)
        val glowAlpha = (0.15f + 0.35f * glowProgress) * (0.85f + 0.15f * flicker)
        val glowRadius = h * 0.40f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    BrassRamp.Brass300.copy(alpha = glowAlpha),
                    BrassRamp.Brass400.copy(alpha = glowAlpha * 0.45f),
                    Color.Transparent,
                ),
                center = Offset(cx, glowCenterY),
                radius = glowRadius,
            ),
            radius = glowRadius,
            center = Offset(cx, glowCenterY),
        )

        // Beat 4 — soundwave arc rings [0.45, 0.95]. Three concentric
        // arcs expanding outward, fading as they grow; the BrassVoiceIcon
        // soundwave reborn as rings of light. Static (frozen) → skip.
        if (!frozen) {
            val ringProgress = remap(intro, 0.45f, 0.95f)
            if (ringProgress > 0f) {
                for (i in 0 until 3) {
                    // Stagger each ring by 1/3 so they chase outward.
                    val rp = (ringProgress - i * 0.18f).coerceIn(0f, 1f)
                    if (rp <= 0f) continue
                    val radius = h * (0.10f + rp * 0.30f)
                    val ringAlpha = (1f - rp) * 0.5f
                    drawCircle(
                        color = BrassRamp.Brass200.copy(alpha = ringAlpha),
                        radius = radius,
                        center = Offset(cx, glowCenterY),
                        style = Stroke(width = (h * 0.012f).coerceAtLeast(1.5f)),
                    )
                }
            }
        }

        // Beat 1 — flame teardrop [0.00, 0.28], scales from a spark.
        val flameProgress = remap(intro, 0.00f, 0.28f)
        val flameScale = flameProgress
        if (flameScale > 0.02f) {
            val flameH = h * 0.30f * flameScale
            val flameW = h * 0.13f * flameScale
            val tipY = baseY - flameH
            val brightness = 0.65f + 0.35f * flicker
            // Outer flame (amber).
            drawFlame(cx, baseY, tipY, flameW, BrassRamp.Brass400.copy(alpha = 0.85f * brightness))
            // Inner flame (bright cream core), smaller.
            drawFlame(
                cx,
                baseY - flameH * 0.06f,
                tipY + flameH * 0.30f,
                flameW * 0.55f,
                BrassRamp.Brass100.copy(alpha = 0.95f * brightness),
            )
        }

        // The wick base — a small warm dot anchoring the flame even at
        // the very first frame (so the "spark" has somewhere to live).
        drawCircle(
            color = BrassRamp.Brass300.copy(alpha = 0.8f),
            radius = (h * 0.012f).coerceAtLeast(2f),
            center = Offset(cx, baseY),
        )
    }
}

/** A simple flame teardrop: a quadratic path bulging out then tapering
 *  to a point at [tipY], centered on [cx]. */
private fun DrawScope.drawFlame(
    cx: Float,
    baseY: Float,
    tipY: Float,
    halfWidth: Float,
    color: Color,
) {
    val path = Path().apply {
        moveTo(cx, baseY)
        // Right side bulges out then curves to the tip.
        cubicTo(
            cx + halfWidth, baseY - (baseY - tipY) * 0.15f,
            cx + halfWidth * 0.8f, baseY - (baseY - tipY) * 0.65f,
            cx, tipY,
        )
        // Left side mirrors back down to the base.
        cubicTo(
            cx - halfWidth * 0.8f, baseY - (baseY - tipY) * 0.65f,
            cx - halfWidth, baseY - (baseY - tipY) * 0.15f,
            cx, baseY,
        )
        close()
    }
    drawPath(path = path, color = color)
}

/**
 * Remaps [value] from the window [[start], [end]] onto 0..1, clamped.
 * The overlapping-window trick that makes the ignite beats crossfade.
 * Pure — pinned by [V110MilestoneIgniteTest].
 */
internal fun remap(value: Float, start: Float, end: Float): Float {
    if (end <= start) return if (value >= end) 1f else 0f
    return ((value - start) / (end - start)).coerceIn(0f, 1f)
}

/** Total ignite-intro duration before the flame settles to a flicker. */
internal const val IGNITE_INTRO_MS: Int = 2200

/**
 * Structural canary: the v1.1 dialog's beats are sequenced (overlapping
 * windows on one master clock), not fired simultaneously. A refactor
 * that collapses them to a single AnimationSpec must flip this.
 */
internal const val v110IgniteBeatsAreSequenced: Boolean = true

/** Deep-purple substrate — same swatch as the v0.5.00 card. */
private val MilestoneSubstrateV110 = Color(0xFF1F1424)

/** Cream text — Brass100, lifted as a named const for previews. */
private val MilestoneCreamV110 = BrassRamp.Brass100

// region frame-clock helpers

/** First frame time in nanos (suspends one frame). */
private suspend fun withFrameNanosStart(): Long = awaitFrameNanos()

/** Suspends until the next frame and returns its time in nanos. */
private suspend fun awaitFrameNanos(): Long =
    androidx.compose.runtime.withFrameNanos { it }

// endregion

// region Previews

@Preview(name = "V110 ignite — settled (dark)", widthDp = 420, heightDp = 560)
@Composable
private fun PreviewV110Settled() = LibraryNocturneTheme(darkTheme = true) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 380.dp),
            shape = MaterialTheme.shapes.large,
            color = MilestoneSubstrateV110,
            border = BorderStroke(1.dp, BrassRamp.Brass500.copy(alpha = 0.55f)),
        ) {
            Column {
                Box(
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    // Pinned at fully-lit for the static preview frame.
                    CandleStage(intro = 1f, flicker = 1f, frozen = true)
                }
                Column(modifier = Modifier.padding(24.dp).alpha(0.99f)) {
                    Text(
                        "Candela 1.1 — read it your way",
                        style = MaterialTheme.typography.headlineSmall,
                        color = BrassRamp.Brass300,
                    )
                    Text(
                        "Every reader holds the light a little differently.",
                        style = MaterialTheme.typography.titleMedium,
                        color = MilestoneCreamV110,
                    )
                }
            }
        }
    }
}

// endregion
