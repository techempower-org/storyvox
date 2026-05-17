package `in`.jphe.storyvox.feature.sync

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.jphe.storyvox.ui.theme.BookBodyFamily
import kotlinx.coroutines.delay

/**
 * Issue #500 — animated passphrase visualizer for the magical
 * InstantDB sign-in surface.
 *
 * The visualizer renders [words] in EB Garamond ([BookBodyFamily]) and
 * fades each word in sequence with a small stagger — the same
 * "word-by-word reveal" vibe as the realm-sigil generator on the web.
 * Used in three places:
 *  - [SyncOnboardingCard] header (decorative, illustrates the
 *    cross-device sync concept)
 *  - [SyncStatusSheet] passphrase preview (placeholder; v1 doesn't yet
 *    accept passphrase entry — the secrets-syncer's PassphraseProvider
 *    binding lives in `:app` and stays a no-op until a follow-up PR
 *    surfaces the input field. Documented in the issue's "Out of scope
 *    (later): Passphrase recovery flow")
 *  - [SyncCrossDeviceAnimation] — the cross-device handoff hook from
 *    the issue's section 3 ("animate the chapters flying in"). For v1
 *    this is the same word-reveal motion repurposed as a celebratory
 *    "your library is here" beat.
 *
 * Animation contract: each word holds at alpha 0 for `index *
 * staggerMs`, then fades to alpha 1 over `revealMs`. The compose
 * coroutine launches once per recomposition keyed on the [words] list
 * — if the caller swaps the word list the animation replays from the
 * start with the new entries.
 *
 * Why a custom row-of-Text rather than a single AnnotatedString with
 * span-level alpha: AnnotatedString's span alpha lerps are global per
 * draw frame, but we want per-word independent timing curves. A
 * FlowRow of N Text widgets, each with its own animateFloatAsState,
 * gives us that with no draw-layer math. The trade-off is one Text
 * per word (cheap at this surface — passphrases cap at ~6 words).
 *
 * Why FlowRow rather than Row (issue #655): on tablet portrait
 * (Tab A7 Lite, 800px wide) the 4-word demo list "candlelight ·
 * brass · vellum · starlight" overflows the SyncOnboardingCard's
 * inner padded width. Row clips horizontally and the last word
 * (longest tail) collapses into a 21×304px vertical letter-stack
 * because the Text widget gets a constraint of 1-column width but
 * unbounded height. FlowRow wraps gracefully onto a second line
 * when the words can't fit, preserving readability at every screen
 * size without forcing a smaller font on the wider phones/tablets
 * that *can* fit the row on a single line.
 *
 * Accessibility: the FlowRow is a single semantics node by default
 * — TalkBack reads the words in order without animation cues.
 * Callers needing to skip the animation entirely should pass
 * `staggerMs = 0` and `revealMs = 0` so the visualizer mounts at
 * full opacity.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PassphraseVisualizer(
    words: List<String>,
    modifier: Modifier = Modifier,
    staggerMs: Int = 240,
    revealMs: Int = 360,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        words.forEachIndexed { index, word ->
            WordReveal(
                word = word,
                delayMs = index * staggerMs,
                revealMs = revealMs,
            )
        }
    }
}

@Composable
private fun WordReveal(word: String, delayMs: Int, revealMs: Int) {
    var visible by remember(word, delayMs) { mutableStateOf(false) }
    LaunchedEffect(word, delayMs) {
        if (delayMs > 0) delay(delayMs.toLong())
        visible = true
    }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(
            durationMillis = revealMs,
            easing = LinearOutSlowInEasing,
        ),
        label = "passphrase-word-$word",
    )
    Text(
        text = word,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .alpha(alpha)
            .padding(vertical = 2.dp),
        fontFamily = BookBodyFamily,
        fontWeight = FontWeight.SemiBold,
        fontStyle = FontStyle.Italic,
        fontSize = 20.sp,
    )
}

/**
 * Sample word list for the marketing/onboarding visualizer. These are
 * decorative ONLY — the actual cross-device sync uses an email +
 * magic code via [SyncAuthScreen]; the passphrase concept lives at
 * the encrypted-secrets layer (see
 * [`in.jphe.storyvox.sync.crypto.UserDerivedKey`]) and is not
 * surfaced as user-typed text in v1.
 *
 * Curated for the Library Nocturne aesthetic: warm, bookish, slightly
 * mythic. The realm-sigil generator on jphe.in pulls from a similar
 * vocabulary; keeping the storyvox sample in the same family
 * reinforces the project family at the visual level.
 */
val DEMO_PASSPHRASE_WORDS: List<String> = listOf(
    "candlelight",
    "brass",
    "vellum",
    "starlight",
)
