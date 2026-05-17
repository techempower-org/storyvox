package `in`.jphe.storyvox.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.ui.theme.LocalAnimationSpeedScale
import `in`.jphe.storyvox.ui.theme.LocalBrassPulseRange
import `in`.jphe.storyvox.ui.theme.LocalReducedMotion
import `in`.jphe.storyvox.ui.theme.LocalSpacing
import `in`.jphe.storyvox.ui.theme.scaleDurationMs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Brass arcane sigil ring — single rotating dashed circle in the realm
 * brass tone. Used wherever storyvox is waiting on something (Royal Road
 * fetch, voice-model download, sherpa-onnx warming up to first sentence).
 *
 * Visually quieter than [MagicSkeletonTile] (which adds a six-pointed
 * star + pulsing alpha), so it composes cleanly as an overlay behind a
 * play button or as a small inline indicator.
 *
 * Honors [LocalReducedMotion] (freezes at 135°) and
 * [LocalAnimationSpeedScale] (#589 — the user's animation-speed master
 * multiplier scales the rotation period so the sigil ring rotates at
 * the same rate as every other transition in the app).
 *
 * @param strokeWidth thickness of the ring; defaults to 2.dp.
 * @param dashLengthFraction fraction of the circumference used by the
 *   dash pattern. Smaller = more "magical sweep" feel.
 * @param color stroke color. Defaults to brass primary, but inside a
 *   Primary [BrassButton] (brass background) the caller passes
 *   `LocalContentColor` (= onPrimary) so the spinner stays visible
 *   against the brass fill.
 */
@Composable
fun MagicSpinner(
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 2.dp,
    dashLengthFraction: Float = 0.18f,
    durationMs: Int = 1600,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val brass = color
    // #486 Phase 2 / #480 — MagicSpinner is the universal "we're
    // waiting on something" affordance. Under reduced motion the
    // rotation freezes at a steady angle (135°) so it still reads
    // as a magical-sigil glyph rather than disappearing. Functional
    // signal (something is loading) is preserved by the spinner's
    // existence and the accompanying "Loading…" text (callers always
    // pair it with status copy).
    val reducedMotion = LocalReducedMotion.current
    // #589 — scale the rotation period by the user's animation-speed
    // pref. We use scaleDurationMs directly (not tweenScaled) because
    // infiniteRepeatable's tween needs a stable spec, and we want
    // scale == 0f to fall through to the reducedMotion branch (which
    // already freezes the rotation). Off is "no motion", same shape.
    val speedScale = LocalAnimationSpeedScale.current
    val scaledDurationMs = scaleDurationMs(durationMs, speedScale).coerceAtLeast(1)
    val transition = rememberInfiniteTransition(label = "magic-spinner")
    val angle: Float = if (reducedMotion || speedScale <= 0f) {
        135f
    } else {
        val animated by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(scaledDurationMs, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "rotation",
        )
        animated
    }

    Canvas(modifier = modifier.rotate(angle)) {
        val stroke = strokeWidth.toPx()
        val diameter = size.minDimension - stroke
        val circumference = (Math.PI * diameter).toFloat()
        val dash = circumference * dashLengthFraction.coerceIn(0.05f, 0.5f)
        val gap = circumference - dash
        drawArc(
            color = brass,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(stroke / 2f, stroke / 2f),
            size = androidx.compose.ui.geometry.Size(diameter, diameter),
            style = Stroke(
                width = stroke,
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, gap), 0f),
            ),
        )
    }
}

/**
 * MagicSpinner + status text, vertically stacked. The standard "we're
 * waiting on something magical" inline composable.
 */
@Composable
fun MagicLoadingStatus(
    text: String,
    modifier: Modifier = Modifier,
    spinnerSize: Dp = 28.dp,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier.padding(spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MagicSpinner(modifier = Modifier.size(spinnerSize))
        Spacer(Modifier.height(spacing.xs))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Library Nocturne replacement for Material's `CircularProgressIndicator`
 * — a layered brass sigil: an outer dashed ring rotating clockwise + an
 * inner six-pointed star rotating counter-clockwise + a faint static
 * outer guide ring. Same visual family as [MagicSkeletonTile] but sized
 * for use as an inline progress affordance (24–64 dp typical) instead of
 * a full cover-slot placeholder.
 *
 * Replaces the Material `CircularProgressIndicator`'s sweeping arc (the
 * "weird arc spinning around" — JP's audit, 2026-05-16, v0.5.65) with
 * something that reads as deliberate magic instead of a generic OS
 * spinner. The arc was a thin material-default sweep that visually felt
 * out of place against the rest of Library Nocturne's slow brass-on-warm-
 * dark motion vocabulary.
 *
 * Honors [LocalReducedMotion] (both rings freeze at a deliberate resting
 * pose — outer 0°, inner 30° — matching [MagicSkeletonTile]'s static-mode
 * pose so the static-glyph reads as one consistent realm sigil across
 * the loading surfaces).
 *
 * Honors [LocalAnimationSpeedScale] (#589) — both rotation periods are
 * scaled by the user's animation-speed pref, so cranking the slider to
 * Slow / Fast scales the loading indicator at the same factor as page
 * transitions.
 *
 * Sized to be a drop-in for `CircularProgressIndicator(modifier =
 * Modifier.size(N.dp))` — pass the same modifier you would to the
 * Material widget. The glyph fills the Canvas, so the visual diameter
 * is the modifier's size minus the stroke width.
 *
 * @param modifier sizing modifier — pass `Modifier.size(48.dp)` etc.
 * @param color stroke color; defaults to brass primary.
 * @param outerPeriodMs rotation period of the outer dashed ring;
 *   default 5000 ms (slow, deliberate; ~12 RPM).
 * @param innerPeriodMs rotation period of the inner six-pointed star;
 *   default 8000 ms in the *opposite* direction so the parallax reads
 *   as two independent layers, not one rigid sigil.
 */
@Composable
fun MagicCircularProgress(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    outerPeriodMs: Int = 5000,
    innerPeriodMs: Int = 8000,
) {
    val reducedMotion = LocalReducedMotion.current
    val speedScale = LocalAnimationSpeedScale.current
    val frozen = reducedMotion || speedScale <= 0f
    val transition = rememberInfiniteTransition(label = "magic-circular-progress")

    val outerRotation: Float = if (frozen) 0f else {
        val v by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = scaleDurationMs(outerPeriodMs, speedScale)
                        .coerceAtLeast(1),
                    easing = LinearEasing,
                ),
            ),
            label = "outer",
        )
        v
    }
    val innerRotation: Float = if (frozen) 30f else {
        val v by transition.animateFloat(
            initialValue = 0f,
            targetValue = -360f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = scaleDurationMs(innerPeriodMs, speedScale)
                        .coerceAtLeast(1),
                    easing = LinearEasing,
                ),
            ),
            label = "inner",
        )
        v
    }
    // #592 — brass-pulse range from user pref. Pre-#592 this was
    // hardcoded at 0.65..1.0; v1 widens / narrows it to match the
    // user's chosen amplitude. We bias the initial slightly toward
    // the pulse range's lower bound but clamp to 0.5..end so the
    // spinner pulse never collapses to invisible at "Subtle" or
    // overshoots at "Bold".
    val pulseRange = LocalBrassPulseRange.current
    val pulseStart = (pulseRange.start + 0.1f).coerceIn(0.5f, pulseRange.endInclusive)
    val pulse: Float = if (frozen) 1.0f else {
        val v by transition.animateFloat(
            initialValue = pulseStart,
            targetValue = pulseRange.endInclusive,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = scaleDurationMs(1800, speedScale)
                        .coerceAtLeast(1),
                    easing = LinearEasing,
                ),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulse",
        )
        v
    }

    val brass = color
    val brassDim = brass.copy(alpha = 0.45f)
    val brassFaint = brass.copy(alpha = 0.18f)

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension / 2f
        val ringStroke = (radius * 0.10f).coerceAtLeast(1.5f)
        val starStroke = (radius * 0.12f).coerceAtLeast(2.0f)

        // Faint static outer guide ring — gives the rotating layers a
        // bounded substrate to read against.
        drawCircle(
            color = brassFaint,
            radius = radius * 0.95f,
            center = center,
            style = Stroke(width = ringStroke * 0.6f),
        )

        // Outer rotating dashed ring
        rotate(outerRotation, pivot = center) {
            drawCircle(
                color = brassDim.copy(alpha = brassDim.alpha * pulse),
                radius = radius * 0.78f,
                center = center,
                style = Stroke(
                    width = ringStroke * 0.85f,
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(radius * 0.20f, radius * 0.10f),
                        phase = 0f,
                    ),
                ),
            )
        }

        // Inner six-pointed star — two overlaid triangles, brass at full
        // pulse opacity for visual punch.
        rotate(innerRotation, pivot = center) {
            val starRadius = radius * 0.50f
            drawCircularStarTriangle(center, starRadius, 0f, brass.copy(alpha = pulse), starStroke)
            drawCircularStarTriangle(center, starRadius, 60f, brass.copy(alpha = pulse * 0.85f), starStroke)
        }

        // Lit center "candle" — the focal dot.
        drawCircle(
            color = brass.copy(alpha = pulse),
            radius = radius * 0.08f,
            center = center,
        )
    }
}

/**
 * Helper: draws an equilateral triangle inscribed in [radius] around
 * [center], rotated by [degreesOffset]. Mirrors [drawTriangle] in
 * SkeletonShimmer.kt — kept package-private here so the call site
 * compiles without forcing SkeletonShimmer's helper to be visible to
 * external modules.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCircularStarTriangle(
    center: Offset,
    radius: Float,
    degreesOffset: Float,
    color: Color,
    strokeWidth: Float,
) {
    val path = androidx.compose.ui.graphics.Path()
    for (i in 0..2) {
        val angleDeg = -90f + degreesOffset + i * 120f
        val rad = Math.toRadians(angleDeg.toDouble())
        val x = center.x + radius * cos(rad).toFloat()
        val y = center.y + radius * sin(rad).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
    )
}
