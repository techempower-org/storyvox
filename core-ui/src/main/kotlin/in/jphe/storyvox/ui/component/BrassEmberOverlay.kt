package `in`.jphe.storyvox.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.ui.theme.LocalAnimationSpeedScale
import `in`.jphe.storyvox.ui.theme.LocalReducedMotion
import `in`.jphe.storyvox.ui.theme.scaleDurationMs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Library Nocturne "candle ember" overlay — a thin layer of slowly-
 * drifting brass dots that rise out of the bottom of [modifier]'s bounds
 * with a slight horizontal sway. Read as warm sparks from a lit candle,
 * the realm's ambient motif. Designed to layer over a static or near-
 * static element (typically the player cover during the warming window)
 * to add "alive, working" affordance without competing with the focal
 * artwork.
 *
 * Each ember:
 *  - Starts at a random x within the bottom 15 % of the canvas.
 *  - Rises along a `phase 0..1` axis, where 0 = bottom and 1 = top.
 *  - Sways horizontally with a per-ember sine offset so they don't all
 *    march in lockstep.
 *  - Fades in over the first 15 % of its lifetime, holds at alpha, then
 *    fades out over the last 25 % — so embers ghost into existence and
 *    snuff out before the top edge of the cover.
 *  - Has a per-ember radius drawn from `radiusDp.toPx() * 0.6..1.0`.
 *
 * The whole thing is one [Canvas] driven by a single infinite-transition
 * `phase` cycle — cheap to render, and ember positions are derived from
 * a `remember`d seeded [Random] so the constellation is stable across
 * recompositions (no flicker-jitter when an ancestor recomposes).
 *
 * Honors:
 *  - [LocalReducedMotion]: when on, the overlay draws a single static
 *    "lit ember" at the cover's bottom-center (resting position) — a
 *    deliberate "candle is lit" still rather than a blank canvas. The
 *    overlay isn't load-bearing for any signal, so a calm static form
 *    is appropriate.
 *  - [LocalAnimationSpeedScale] (#589): the ember rise period scales by
 *    the user's animation-speed pref. Off (scale == 0f) freezes the
 *    same way as reduced motion.
 *
 * @param modifier sizing modifier — typically the same size as the
 *   underlying cover image (`Modifier.matchParentSize()` inside a [Box]
 *   is the common call site).
 * @param emberCount how many embers to draw. Default 6 — calm,
 *   atmospheric, not snowy.
 * @param baseColor the brass tint; defaults to brass primary.
 * @param radiusDp the maximum ember radius (per-ember radius is drawn
 *   from `radiusDp * 0.6..1.0` of this value).
 * @param riseDurationMs how long a single ember takes to traverse the
 *   bounds (bottom → top). Default 5600 ms — slow and deliberate to
 *   match the Library Nocturne motion vocabulary.
 */
@Composable
fun BrassEmberOverlay(
    modifier: Modifier = Modifier,
    emberCount: Int = 6,
    baseColor: Color = MaterialTheme.colorScheme.primary,
    radiusDp: Dp = 3.dp,
    riseDurationMs: Int = 5600,
) {
    val reducedMotion = LocalReducedMotion.current
    val speedScale = LocalAnimationSpeedScale.current
    val frozen = reducedMotion || speedScale <= 0f

    // Per-ember constants. Seeded so the constellation is stable. We
    // deliberately don't hoist this into a stable `data class` — the
    // arrays are local to the composable's lifetime and remembered
    // across recompositions.
    val random = remember { Random(0xBEEF) }
    val xOffsets = remember { FloatArray(emberCount) { random.nextFloat() } }
    val phaseOffsets = remember { FloatArray(emberCount) { random.nextFloat() } }
    val swayFreqs = remember { FloatArray(emberCount) { 0.6f + random.nextFloat() * 0.8f } }
    val swayAmps = remember { FloatArray(emberCount) { 0.04f + random.nextFloat() * 0.05f } }
    val radiusScales = remember { FloatArray(emberCount) { 0.6f + random.nextFloat() * 0.4f } }

    val phase: Float = if (frozen) 0.10f else {
        val scaled = scaleDurationMs(riseDurationMs, speedScale).coerceAtLeast(1)
        val transition = rememberInfiniteTransition(label = "brass-ember-overlay")
        val p by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = scaled, easing = LinearEasing),
            ),
            label = "ember-phase",
        )
        p
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val maxR = radiusDp.toPx()
        if (frozen) {
            // Static "candle is lit" pose — one slightly-larger ember
            // resting near the bottom-center, at full opacity. Mirrors
            // MagicSpinner's 135° resting pose: visible, deliberate,
            // doesn't read as a stuck spinner.
            drawCircle(
                color = baseColor.copy(alpha = 0.55f),
                radius = maxR,
                center = Offset(w * 0.5f, h * 0.92f),
            )
            return@Canvas
        }
        for (i in 0 until emberCount) {
            // Wrap each ember's progress so they're spread across the
            // cycle (not all rising in unison).
            val emberPhase = ((phase + phaseOffsets[i]) % 1f)
            // Per-ember alpha curve:
            //   0.00..0.15 — fade in (alpha 0 → 0.85)
            //   0.15..0.75 — hold (alpha 0.85)
            //   0.75..1.00 — fade out (alpha 0.85 → 0)
            val alpha = when {
                emberPhase < 0.15f -> (emberPhase / 0.15f) * 0.85f
                emberPhase < 0.75f -> 0.85f
                else -> ((1f - emberPhase) / 0.25f).coerceIn(0f, 1f) * 0.85f
            }
            if (alpha <= 0.01f) continue

            // x = base offset + sine sway. The sine argument uses
            // emberPhase (which is bounded 0..1) so each ember has its
            // own smooth horizontal wobble.
            val swayRad = (emberPhase * 2.0 * Math.PI * swayFreqs[i]).toFloat()
            val x = (xOffsets[i] + swayAmps[i] * sin(swayRad.toDouble()).toFloat())
                .coerceIn(0.05f, 0.95f) * w
            // y = rise from 92% (just below bottom edge) → -2% (just
            // above top edge). The "off-screen" margins on either end
            // let the fade-in / fade-out happen below / above the
            // visible region, so embers don't pop into existence at
            // the cover edge.
            val y = (0.92f - emberPhase * 0.94f) * h

            drawCircle(
                color = baseColor.copy(alpha = alpha),
                radius = maxR * radiusScales[i],
                center = Offset(x, y),
            )
        }
    }
}
