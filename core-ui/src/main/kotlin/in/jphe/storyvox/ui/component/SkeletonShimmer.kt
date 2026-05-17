package `in`.jphe.storyvox.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.ui.theme.LocalAnimationSpeedScale
import `in`.jphe.storyvox.ui.theme.LocalReducedMotion
import `in`.jphe.storyvox.ui.theme.scaleDurationMs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Library Nocturne loading shimmer — a calm 1200ms opacity pulse so it feels
 * deliberate, not anxious. Reverse repeat keeps rise/fall symmetric.
 */
@Composable
fun shimmerAlpha(): Float {
    // #486 Phase 2 / #480 — under reduced motion the shimmer
    // collapses to a static mid-alpha (0.6 — between min 0.35 and
    // max 0.85). The skeleton still reads as a placeholder rectangle;
    // we just don't animate the breathing pulse, which is the
    // decorative bit a vestibular-sensitive user wants out of their
    // peripheral vision.
    if (LocalReducedMotion.current) return 0.6f
    // #589 — animation-speed pref also collapses to static at Off
    // (scale == 0f). Same shape as ReducedMotion: the slider's "Off"
    // tier is a stronger statement than per-component motion.
    val speedScale = LocalAnimationSpeedScale.current
    if (speedScale <= 0f) return 0.6f
    val transition = rememberInfiniteTransition(label = "skeleton-shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = scaleDurationMs(1200, speedScale).coerceAtLeast(1),
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeleton-alpha",
    )
    return alpha
}

/** A rounded surface that pulses opacity in the brass palette. */
@Composable
fun SkeletonBlock(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.small,
    color: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
) {
    val alpha = shimmerAlpha()
    Box(
        modifier = modifier
            .clip(shape)
            .alpha(alpha)
            .background(color),
    )
}

/**
 * A skeleton tile with a slow-rotating brass arcane sigil at its center.
 *
 * Reads as "magic is happening" rather than the generic alpha-pulse rectangle
 * users associate with stale UI. The sigil is a six-pointed star inside a ring,
 * with three brass-graded layers rotating at different rates to give a mild
 * parallax. Drops onto the same `surfaceContainerHigh` substrate as
 * [SkeletonBlock] so the skeleton grid remains visually unified.
 *
 * @param modifier the cell shape — typically the cover slot.
 * @param shape clip shape for the tile (defaults to [MaterialTheme.shapes.medium]).
 * @param glyphSize the diameter of the sigil glyph; default 56dp reads at all
 *   reasonable card sizes (140dp+ wide).
 */
@Composable
fun MagicSkeletonTile(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    glyphSize: Dp = 56.dp,
) {
    // #486 Phase 2 / #480 — under reduced motion the sigil freezes
    // at a deliberate resting pose (outer 0°, inner 30°, full pulse).
    // The brass glyph still reads as a placeholder; the rotating
    // parallax goes away.
    // #589 — same shape for animation-speed Off (scale == 0f): the
    // slider's strongest tier collapses to static, matching reduced
    // motion. Non-zero scales multiply each rotation period.
    val reducedMotion = LocalReducedMotion.current
    val speedScale = LocalAnimationSpeedScale.current
    val frozen = reducedMotion || speedScale <= 0f
    val transition = rememberInfiniteTransition(label = "skeleton-sigil")
    val outerRotation: Float = if (frozen) 0f else {
        val v by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = scaleDurationMs(12_000, speedScale).coerceAtLeast(1),
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
                    durationMillis = scaleDurationMs(18_000, speedScale).coerceAtLeast(1),
                    easing = LinearEasing,
                ),
            ),
            label = "inner",
        )
        v
    }
    val pulse: Float = if (frozen) 1.0f else {
        val v by transition.animateFloat(
            initialValue = 0.55f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = scaleDurationMs(1800, speedScale).coerceAtLeast(1),
                    easing = LinearEasing,
                ),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulse",
        )
        v
    }

    val brass = MaterialTheme.colorScheme.primary
    val brassDim = brass.copy(alpha = 0.45f)
    val brassFaint = brass.copy(alpha = 0.18f)
    val substrate = MaterialTheme.colorScheme.surfaceContainerHigh

    Box(
        modifier = modifier.clip(shape).background(substrate),
        contentAlignment = Alignment.Center,
    ) {
        // Subtle radial wash so the sigil reads against a slightly-darker
        // center, like brass on aged leather.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            substrate.copy(alpha = 0.0f),
                            substrate.copy(alpha = 0.4f),
                        ),
                    ),
                ),
        )
        Canvas(modifier = Modifier.size(glyphSize)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension / 2f
            val ringStroke = (radius * 0.06f).coerceAtLeast(1.5f)
            val starStroke = (radius * 0.08f).coerceAtLeast(2.0f)

            // Faint outer ring (static)
            drawCircle(
                color = brassFaint,
                radius = radius * 0.95f,
                center = center,
                style = Stroke(width = ringStroke),
            )

            // Outer rotating ring with tick marks
            rotate(outerRotation, pivot = center) {
                drawCircle(
                    color = brassDim.copy(alpha = brassDim.alpha * pulse),
                    radius = radius * 0.78f,
                    center = center,
                    style = Stroke(
                        width = ringStroke * 0.8f,
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(radius * 0.18f, radius * 0.10f),
                            phase = 0f,
                        ),
                    ),
                )
            }

            // Inner six-pointed star (two overlaid triangles)
            rotate(innerRotation, pivot = center) {
                val starRadius = radius * 0.55f
                drawTriangle(center, starRadius, 0f, brass.copy(alpha = pulse), starStroke)
                drawTriangle(center, starRadius, 60f, brass.copy(alpha = pulse * 0.85f), starStroke)
            }

            // Center dot — like the lit candle
            drawCircle(
                color = brass.copy(alpha = pulse),
                radius = radius * 0.06f,
                center = center,
            )
        }
    }
}

/**
 * Helper: draws an equilateral triangle inscribed in [radius] around [center],
 * rotated by [degreesOffset]. Stroke only, brass-tinted. Internal so the
 * static [MonogramSigilTile] placeholder (FictionCoverThumb.kt) draws the
 * same geometry as the loading [MagicSkeletonTile] without forking helpers.
 */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTriangle(
    center: Offset,
    radius: Float,
    degreesOffset: Float,
    color: Color,
    strokeWidth: Float,
) {
    val path = Path()
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
