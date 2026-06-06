package `in`.jphe.storyvox.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.ui.theme.BrassRamp

/** Number of taps on the sigil glyph that lights the hidden candle. */
internal const val CANDLE_EGG_TAP_THRESHOLD: Int = 7

/** The whisper revealed when the candle lights — the meaning of the
 *  name. Pure const so the egg copy is testable + single-sourced. */
internal const val CANDLE_EGG_WHISPER: String =
    "Candela — the SI unit of luminous intensity"

/**
 * Candela (v1.1.0) — the hidden candle easter egg.
 *
 * Wraps the sigil glyph ([content], typically a [MagicSkeletonTile]) in
 * an invisible tap-counter. Tapping it [CANDLE_EGG_TAP_THRESHOLD] times
 * lights a small [LightMotes] flame-burst over the glyph and whispers
 * "[CANDLE_EGG_WHISPER]" in a brass tooltip. Self-contained and
 * discoverable: nothing changes on taps 1–6, so it stays a secret; the
 * 7th tap rewards the curious. Re-arms after the whisper fades, so it
 * can be found again.
 *
 * Deliberately NOT gated by a one-time DataStore flag — unlike the
 * milestone celebrations, this is a re-discoverable delight, not a
 * one-shot announcement.
 *
 * The wrapper keeps [MagicSkeletonTile] pure: the tile only draws; this
 * layer owns the tap behavior. The tap target is the glyph's own
 * bounds (it sizes to [content]).
 */
@Composable
fun CandleTapEgg(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var taps by remember { mutableIntStateOf(0) }
    var lit by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier.wrapContentSize(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.clickable(
                interactionSource = interaction,
                indication = null, // silent — no ripple gives away the secret
            ) {
                if (!lit) {
                    taps += 1
                    if (taps >= CANDLE_EGG_TAP_THRESHOLD) {
                        taps = 0
                        lit = true
                    }
                }
            },
        ) {
            content()
        }

        // The flame-burst overlay, sized to the glyph bounds, mounted
        // only while lit. LightMotes drives the rise + bloom; onFinished
        // clears `lit`, re-arming the egg.
        if (lit) {
            LightMotes(
                onFinished = { lit = false },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // The whisper tooltip — a small brass caption below the glyph,
        // fading in with the burst and out when it clears.
        AnimatedVisibility(
            visible = lit,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter),
        ) {
            Text(
                text = CANDLE_EGG_WHISPER,
                style = MaterialTheme.typography.bodySmall,
                color = BrassRamp.Brass100,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .background(
                        color = Color(0xFF1F1424).copy(alpha = 0.92f),
                        shape = RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        // Safety: if a host keeps us composed, ensure a lit egg always
        // eventually clears even if LightMotes' onFinished is missed
        // (e.g. reduced motion fires it next-frame; this is a no-op
        // backstop, not the primary path).
        if (lit) {
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(4000)
                lit = false
            }
        }
    }
}
