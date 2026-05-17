package `in`.jphe.storyvox.feature.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.jphe.storyvox.playback.diagnostics.WaitReason
import `in`.jphe.storyvox.ui.component.MagicSpinner
import `in`.jphe.storyvox.ui.theme.LocalMotion
import `in`.jphe.storyvox.ui.theme.LocalReducedMotion
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * "Why are we waiting?" — the user-facing magical panel that surfaces a
 * typed [WaitReason] whenever no audio is reaching the speakers. Lives
 * above the cover in [AudiobookView]; renders in Library Nocturne brass
 * with a slow-pulsing sigil, the reason headline, an optional thin
 * progress bar, a secondary explanatory line, and (for recoverable
 * reasons) a brass-bordered Retry chip.
 *
 * The panel is bound by [AnimatedVisibility] to `reason != null`, so it
 * slides into view when audio stops and slides out when playback
 * resumes — never popping the rest of the player UI.
 *
 * Accessibility: the whole panel is a polite live region; TalkBack
 * announces a new reason as it arrives. Each variant carries its own
 * `contentDescription` because the secondary line + retry chip add
 * context the headline alone can't convey.
 */
@Composable
fun WhyAreWeWaitingPanel(
    reason: WaitReason?,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val spacing = LocalSpacing.current
    val motion = LocalMotion.current
    val reducedMotion = LocalReducedMotion.current

    AnimatedVisibility(
        visible = reason != null,
        enter = fadeIn(tween(motion.standardDurationMs, easing = motion.standardEasing)) +
            expandVertically(tween(motion.standardDurationMs, easing = motion.standardEasing)),
        exit = fadeOut(tween(motion.standardDurationMs, easing = motion.standardEasing)) +
            shrinkVertically(tween(motion.standardDurationMs, easing = motion.standardEasing)),
        modifier = modifier,
    ) {
        // Snapshot the reason so the composable body doesn't recompose
        // back to null mid-exit-animation; AnimatedVisibility keeps the
        // last non-null reason on screen during exit.
        val r = reason ?: return@AnimatedVisibility

        val brass = MaterialTheme.colorScheme.primary
        // 5 % brass washed onto the surface background — visible against
        // the Library Nocturne dark surface without competing with the
        // cover for attention.
        val panelBg = brass.copy(alpha = 0.08f)
            .compositeOver(MaterialTheme.colorScheme.surface)
        val borderColor = brass.copy(alpha = 0.30f)

        // Issue #614 (v1.0 blocker) — pre-fix the panel re-announced its
        // full message on every state transition, including rapid
        // engine flips (Warming → Buffering → Warming) and animation
        // recompositions that happened to re-evaluate the contentDesc
        // string. TalkBack on R5CRB0W66MK ended up reading the full
        // 2-sentence panel description multiple times per second under
        // load, drowning out the chapter audio it was supposed to
        // narrate over.
        //
        // Fix: coalesce. We hold the "currently announced" string in a
        // mutableState, then debounce-update it after 350 ms of no
        // change. The actual semantics node always carries the latest
        // *stable* string, so TalkBack only sees a fresh value when
        // the diagnostic genuinely settled. Identical-content
        // transitions (the same WaitReason re-emitted) never bump the
        // state, so they never trigger a polite-region announcement.
        //
        // Issue #646 (v1.0 polish, 2026-05-16) — AudioRouteChange (and
        // any other reason with both a headline + secondary line) was
        // still triple-announcing on TalkBack:
        //   1. The parent's merged contentDesc reads
        //      "Audio output changed.. Switching to the new speaker…"
        //      (note the DOUBLE period — `r.message` already ends in
        //      "." so the legacy `"${r.message}. …"` concat appended
        //      another).
        //   2. AND each child Text (headline, secondary line) was
        //      announced as its own node because the parent's
        //      `Modifier.semantics { … }` didn't merge descendants —
        //      it only set the live-region + contentDesc. Children
        //      stayed visible to TalkBack as separate semantic nodes.
        //
        // Two-part fix:
        //   - Switch to `Modifier.semantics(mergeDescendants = true) { … }`
        //     so the children fold into one node and TalkBack reads
        //     the announced string ONCE per state change.
        //   - Use `joinHeadlineAndBody` to glue headline + secondary
        //     line without a double period, regardless of whether
        //     either side already ends in punctuation. The result is
        //     "Audio output changed. Switching to the new speaker…"
        //     — one clean sentence pair, one TalkBack utterance.
        val intended = joinHeadlineAndBody(r.message, secondaryLineFor(r))
        var announced by remember { mutableStateOf(intended) }
        LaunchedEffect(intended) {
            // Coalesce window. Rapid Warming ⇄ Buffering oscillations
            // (sub-300 ms apart on slow-voice cold start) collapse to
            // the *last* state inside the window instead of announcing
            // each one. 350 ms is long enough to ride out the engine's
            // transition jitter but short enough that a genuine state
            // change is felt within a TalkBack listener's reaction
            // window.
            if (announced != intended) {
                delay(350)
                announced = intended
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(5.dp))
                .background(panelBg)
                .border(width = 1.dp, color = borderColor, shape = RoundedCornerShape(5.dp))
                .padding(horizontal = spacing.md, vertical = spacing.sm)
                // mergeDescendants=true folds the headline Text +
                // secondary Text + retry chip into a single semantic
                // node so TalkBack announces the panel ONCE per state
                // change, not once per child. See issue #646 comment
                // above. The retry chip retains its own `Role.Button`
                // semantic (set on its inner clickable) so it stays
                // independently focusable when TalkBack enters
                // explore-by-touch on the panel.
                .semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = announced
                },
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            // Headline row — pulsing brass sigil + EB Garamond headline.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                PulsingBrassSigil(
                    color = brass,
                    reducedMotion = reducedMotion,
                )
                Text(
                    text = r.message,
                    color = brass,
                    fontFamily = FontFamily.Serif, // EB Garamond proxy — Library Nocturne maps Serif → EB Garamond
                    fontWeight = FontWeight.Medium,
                    fontSize = 18.sp,
                    modifier = Modifier.weight(1f),
                )
            }

            // Optional thin progress bar. 2 dp tall at 70 % opacity per
            // spec; only shown when the reason carries a known fraction.
            r.progressFraction?.let { frac ->
                LinearProgressIndicator(
                    progress = { frac.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .alpha(0.70f),
                    color = brass,
                    trackColor = brass.copy(alpha = 0.15f),
                    strokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }

            // Secondary line — small Inter (proxy: SansSerif) 12 sp at
            // brass-50, explains *what's happening* in one sentence.
            Text(
                text = secondaryLineFor(r),
                color = brass.copy(alpha = 0.65f),
                fontFamily = FontFamily.SansSerif,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth(),
            )

            // Optional action chip — brass-bordered, right-aligned. Only
            // rendered when the reason is user-actionable.
            if (r.isUserActionable) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    val (label, action) = chipLabelAndActionFor(r, onRetry, onOpenSettings)
                    BrassActionChip(
                        label = label,
                        onClick = action,
                        borderColor = borderColor,
                        textColor = brass,
                    )
                }
            }
        }
    }
}

/**
 * Pulsing brass sigil — a 14 dp rotating brass dashed ring (MagicSpinner
 * with the standard 2-second period) overlaid with a subtle alpha pulse.
 * Used at the panel headline so "we're waiting on output" reads as the
 * same brass-sigil-family as every other Library Nocturne loading
 * surface (skeleton tiles, MagicCircularProgress, cover-warm spinner)
 * instead of a generic alpha-pulse dot. Pre-v1.0-polish (2026-05-16)
 * this was a 12 dp circle that faded between two alphas — visually
 * indistinguishable from a status LED, lost the realm-language of the
 * surrounding UI.
 *
 * Honors reduced motion: when on, the spinner freezes at MagicSpinner's
 * 135° resting pose AND the alpha pulse collapses to a steady mid-
 * value (0.75), so the sigil holds as a static brass glyph rather than
 * disappearing.
 */
@Composable
private fun PulsingBrassSigil(
    color: Color,
    reducedMotion: Boolean,
) {
    val alpha = if (reducedMotion) 0.85f else {
        val transition = androidx.compose.animation.core
            .rememberInfiniteTransition(label = "why-waiting-sigil")
        val a by transition.animateFloat(
            initialValue = 0.65f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1500, easing = androidx.compose.animation.core.LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "sigil-alpha",
        )
        a
    }
    Box(
        modifier = Modifier
            .size(14.dp)
            .alpha(alpha),
    ) {
        MagicSpinner(
            modifier = Modifier.size(14.dp),
            color = color,
            strokeWidth = 1.5.dp,
            durationMs = 2400,
            dashLengthFraction = 0.22f,
        )
    }
}

/**
 * Brass-bordered action chip used for the panel's optional retry /
 * settings affordance. Keeps the visual lighter than a Material Button
 * (which would compete with the play control downstream).
 */
@Composable
private fun BrassActionChip(
    label: String,
    onClick: () -> Unit,
    borderColor: Color,
    textColor: Color,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .border(width = 1.dp, color = borderColor, shape = RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .semantics { contentDescription = label },
    ) {
        Text(
            text = label,
            color = textColor,
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
        )
    }
}

/**
 * Issue #646 — glue a headline + secondary body together for the
 * panel's TalkBack contentDescription without ever producing a double
 * period. The legacy `"$headline. $body"` concat happily produced
 * "Audio output changed.. Switching…" when [headline] already ended
 * in punctuation. We strip a single trailing sentence-terminator from
 * [headline] before re-appending exactly one period + a space; if
 * [body] is blank we skip the join entirely so a one-liner reason
 * doesn't leave a dangling separator.
 *
 * Kept package-internal so the unit test can pin the no-double-period
 * invariant for every WaitReason headline. The function intentionally
 * stays lossless on non-terminator punctuation ("Hang tight…",
 * "Loading the chapter") — only periods get the dedupe pass since
 * those are what the original message literals end in.
 */
internal fun joinHeadlineAndBody(headline: String, body: String): String {
    val trimmedBody = body.trim()
    val trimmedHead = headline.trimEnd()
    if (trimmedBody.isEmpty()) return trimmedHead
    // Strip exactly one trailing period from the headline so the
    // join site contributes a single ". " separator. Other terminators
    // (`…`, `!`, `?`) are left intact — they're already sentence ends
    // and pair fine with a space + body.
    val headWithoutTrailingPeriod = if (trimmedHead.endsWith(".")) {
        trimmedHead.dropLast(1).trimEnd()
    } else {
        trimmedHead
    }
    return "$headWithoutTrailingPeriod. $trimmedBody"
}

/**
 * Per-variant secondary line copy. Each variant gets one sentence that
 * explains *what* storyvox is waiting on, in plain language. EB Garamond
 * headline + Inter explanation = library card aesthetic.
 */
internal fun secondaryLineFor(reason: WaitReason): String = when (reason) {
    // Plain-English secondary lines (#606, v1.0). Every line is one
    // short sentence that a child or TalkBack listener can parse on
    // first hearing. Notable replacements:
    //   - "The pipeline is rendering the next sentence's audio"
    //     (engineering jargon) → "Just a moment — the next bit is
    //     almost ready." (concrete, human).
    //   - "If this persists, tap retry to nudge the pipeline" →
    //     "Tap Retry if it doesn't come back on its own." (action
    //     described in plain terms, no "pipeline" mention).
    is WaitReason.WarmingVoice ->
        "Each voice gets ready once when you start."
    is WaitReason.LoadingChapter ->
        "Getting the chapter ready to read aloud."
    is WaitReason.BufferingNextSentence ->
        "Just a moment — the next bit is almost ready."
    is WaitReason.NetworkSlow ->
        "Your internet is taking longer than usual."
    is WaitReason.FocusLost ->
        "We'll pick up right where you left off."
    WaitReason.AudioRouteChange ->
        "Switching to the new speaker or headphones."
    WaitReason.DeviceMuted ->
        "Use the volume buttons on the side of your phone."
    is WaitReason.VoiceDownloadFailed ->
        "Check your internet connection, then try again."
    is WaitReason.AudioOutputStuck ->
        "Tap Retry if it doesn't come back on its own."
}

/**
 * Per-variant label + action for the optional retry chip. Pairs with
 * [WaitReason.isUserActionable] — only the variants where actionable is
 * true reach this function.
 */
internal fun chipLabelAndActionFor(
    reason: WaitReason,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
): Pair<String, () -> Unit> = when (reason) {
    WaitReason.DeviceMuted -> "Open settings" to onOpenSettings
    is WaitReason.AudioOutputStuck -> "Retry" to onRetry
    is WaitReason.VoiceDownloadFailed -> "Retry" to onRetry
    is WaitReason.NetworkSlow -> "Retry" to onRetry
    is WaitReason.FocusLost -> "Resume" to onRetry
    WaitReason.AudioRouteChange -> "Resume" to onRetry
    else -> "Retry" to onRetry
}
