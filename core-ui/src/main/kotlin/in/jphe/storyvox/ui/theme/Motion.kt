package `in`.jphe.storyvox.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/** Library Nocturne motion — slow, warm, deliberate. */
@Immutable
data class Motion(
    /** Standard 280ms with cubic in-out — page transitions, surface swaps. */
    val standardEasing: Easing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f),
    val standardDurationMs: Int = 280,

    /** Sentence-highlight 180ms — must keep up with TTS rate. */
    val sentenceEasing: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f),
    val sentenceDurationMs: Int = 180,

    /** Reader<->Audiobook swipe — warmer, with overshoot tolerance. */
    val swipeEasing: Easing = CubicBezierEasing(0.32f, 0.72f, 0.0f, 1.0f),
    val swipeDurationMs: Int = 360,
)

val LocalMotion = staticCompositionLocalOf { Motion() }

/**
 * Whether the user has "Remove animations" / "Reduce motion" enabled in
 * system settings. Provided at the app root by reading
 * `Settings.Global.ANIMATOR_DURATION_SCALE`.
 *
 * This is a runtime user-preference flag, deliberately kept separate from
 * [Motion]: [Motion] is `@Immutable` and describes the realm's motion
 * vocabulary, while this flag tells consumers whether to play any motion at
 * all right now. Nesting it inside [Motion] would silently break stable-
 * snapshot semantics.
 *
 * Reduced motion means *absent* motion, not shorter motion — consumers
 * should branch to instant transitions, not faster ones.
 *
 * Default `false` is a safe fallback for previews and tests that don't wire
 * a provider.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

/**
 * Issue #589 — global animation-speed master multiplier. Provided at
 * the NavHost root from `UiSettings.animationSpeedScale`; consumers
 * multiply this into every `tween(N)` duration via [tweenScaled] so
 * a single user pref scales every transition in storyvox.
 *
 * Supported tiers (mirrored from `:app`'s ANIMATION_SPEED_SCALE_TIERS):
 *  - `0f` = Off (instant; durations become 0)
 *  - `0.5f` = Slow (transitions take twice as long)
 *  - `1f` = Normal (default, identity multiplier)
 *  - `1.5f` = Brisk (~33% faster)
 *  - `2f` = Fast (half-duration)
 *
 * The multiplier is the *inverse* of duration: scale=2 means animations
 * play at 2× speed → durations are *divided* by 2 (see [scaleDurationMs]
 * for the exact formula). Off (scale=0) is special-cased to 0ms so
 * `LocalReducedMotion` parity holds (consumers branch to instant
 * transitions, not just "shorter motion").
 *
 * Default `1f` keeps existing behavior bit-identical for any
 * subtree that doesn't wire the provider (previews, tests).
 *
 * Pairs with [LocalReducedMotion]: when ReducedMotion is on, the
 * effective scale collapses to 0 (instant) regardless of the user
 * pref. See [effectiveAnimationSpeedScale].
 */
val LocalAnimationSpeedScale = staticCompositionLocalOf { 1f }

/**
 * Read the *effective* animation-speed scale, factoring in
 * [LocalReducedMotion]. ReducedMotion forces 0 (instant) regardless
 * of the user-chosen pref — the system flag is a stronger statement
 * than the in-app slider.
 */
@Composable
@ReadOnlyComposable
fun effectiveAnimationSpeedScale(): Float {
    if (LocalReducedMotion.current) return 0f
    return LocalAnimationSpeedScale.current
}

/**
 * Issue #589 — scale a tween duration in milliseconds by the active
 * [LocalAnimationSpeedScale]. The formula:
 *  - `scale == 0f` → `0` (instant; matches LocalReducedMotion)
 *  - else → `(durationMs / scale).toInt()`, floored at 1ms so we don't
 *    pass 0ms to a non-Off tween (Compose's tween(0) is a degenerate
 *    spec; better to short-circuit at the call site).
 *
 * Visible for testing / direct use by non-Composable seeds (e.g.
 * AnimatedContentTransitionScope's transition specs that resolve
 * outside the standard composable scope).
 */
fun scaleDurationMs(durationMs: Int, scale: Float): Int {
    if (scale <= 0f) return 0
    if (scale == 1f) return durationMs
    val scaled = (durationMs / scale).toInt()
    return scaled.coerceAtLeast(1)
}

/**
 * Issue #589 — `tween(N)` replacement that honors
 * [LocalAnimationSpeedScale] + [LocalReducedMotion]. Drop-in: change
 * `tween(280)` to `tweenScaled(280)`.
 *
 * When the effective scale is 0 (Off OR ReducedMotion), returns a
 * tween with `durationMillis = 0` — Compose treats that as effectively
 * instant. Otherwise scales the duration by the inverse of the scale
 * factor.
 *
 * The [easing] and [delayMillis] parameters mirror Compose's
 * `tween(...)`; we don't scale the delay because user-perceived gating
 * (e.g. "wait 500ms before fading the splash") is a different concern
 * from animation rate. If a future caller wants delays scaled too,
 * they should compose `scaleDurationMs(delay, scale)` at the call
 * site.
 */
@Composable
@ReadOnlyComposable
fun <T> tweenScaled(
    durationMillis: Int,
    delayMillis: Int = 0,
    easing: Easing = androidx.compose.animation.core.FastOutSlowInEasing,
): TweenSpec<T> {
    val scale = effectiveAnimationSpeedScale()
    return tween(
        durationMillis = scaleDurationMs(durationMillis, scale),
        delayMillis = delayMillis,
        easing = easing,
    )
}

/**
 * Non-composable variant for callers that need a [FiniteAnimationSpec]
 * outside a composable scope (e.g. AnimatedContent transition lambdas
 * that resolve in the parent scope). The caller passes the *current*
 * scale explicitly; typically read once via
 * `LocalAnimationSpeedScale.current` at the composable boundary and
 * forwarded down.
 */
fun <T> tweenScaledNonComposable(
    durationMillis: Int,
    scale: Float,
    delayMillis: Int = 0,
    easing: Easing = androidx.compose.animation.core.FastOutSlowInEasing,
): TweenSpec<T> = tween(
    durationMillis = scaleDurationMs(durationMillis, scale),
    delayMillis = delayMillis,
    easing = easing,
)

/**
 * Issue #590 — particle/confetti intensity preset. Controls density
 * of decorative particle overlays (the brass-ember overlay from #650,
 * the chapter-completion confetti) so users on slower devices or
 * users sensitive to busy backgrounds can dial them down — and users
 * who love the magical realm vibe can dial them up.
 *
 * - [None] — no particles. Decorative overlays render empty (or skip
 *   themselves entirely). Equivalent to the pre-#650 baseline.
 * - [Subtle] — current 6-ember overlay; calm and atmospheric.
 * - [Lush] — 12 embers + extra flare on chapter completion. For
 *   users who want a more vivid Library Nocturne ambiance.
 *
 * Pairs with [LocalReducedMotion] — when ReducedMotion is on, the
 * overlay separately freezes its motion, but particle COUNT still
 * follows this pref so a reduced-motion user can pick "Subtle" or
 * "Lush" particle counts as static decoration.
 */
enum class ParticleIntensity {
    None, Subtle, Lush;

    /** Multiplier applied to the default ember count. */
    val emberCountMultiplier: Float
        get() = when (this) {
            None -> 0f
            Subtle -> 1f
            Lush -> 2f
        }
}

val LocalParticleIntensity = staticCompositionLocalOf { ParticleIntensity.Subtle }

/**
 * Issue #591 — skeleton-shimmer style preset. Controls how loading
 * placeholders look:
 *
 * - [Off] — plain rectangle (no animation, no sigil). Cheapest to
 *   render; matches the bare-minimum loading affordance.
 * - [Pulse] — alpha-pulse rectangle (the pre-#650 baseline).
 * - [Sigil] — 3-layered rotating sigil + brass-pulse (the v0.5.66
 *   default; what `MagicSkeletonTile` renders today).
 */
enum class SkeletonStyle {
    Off, Pulse, Sigil
}

val LocalSkeletonStyle = staticCompositionLocalOf { SkeletonStyle.Sigil }

/**
 * Issue #592 — brass alpha-pulse intensity preset. The brass pulse
 * is a slow alpha breath that sits on top of the realm's loading
 * dots, the WhyAreWeWaitingPanel sigil, and the
 * [MagicSkeletonTile] center dot. The amplitude controls how deep
 * the pulse breathes:
 *
 * - [Subtle] — narrow pulse band (0.7 → 1.0). Calmer, less attention-
 *   grabbing. Recommended for users sensitive to busy peripheral
 *   motion.
 * - [Standard] — middle band (0.55 → 1.0). The v0.5.66 default;
 *   what every brass-pulse site renders today.
 * - [Bold] — wide pulse band (0.4 → 1.0). More vivid, for users
 *   who want the loading state to read as actively working.
 *
 * Stored as a [BrassPulseLevel] and exposed as
 * [LocalBrassPulseRange] — consumers read the ClosedFloatingPointRange
 * directly and use its [ClosedFloatingPointRange.start] as
 * `initialValue` + [ClosedFloatingPointRange.endInclusive] as
 * `targetValue` on their `animateFloat` calls.
 */
enum class BrassPulseLevel {
    Subtle, Standard, Bold;

    /** Alpha range for [animateFloat] initial/target values. */
    val range: ClosedFloatingPointRange<Float>
        get() = when (this) {
            Subtle -> 0.7f..1.0f
            Standard -> 0.55f..1.0f
            Bold -> 0.4f..1.0f
        }
}

val LocalBrassPulseRange = staticCompositionLocalOf<ClosedFloatingPointRange<Float>> { 0.55f..1.0f }
