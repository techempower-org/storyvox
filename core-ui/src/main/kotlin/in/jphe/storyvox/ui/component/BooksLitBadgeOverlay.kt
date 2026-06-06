package `in`.jphe.storyvox.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.ui.theme.BrassRamp
import `in`.jphe.storyvox.ui.theme.LibraryNocturneTheme
import `in`.jphe.storyvox.ui.theme.LocalReducedMotion
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Candela (v1.1.0) — the book-streak celebration overlay: a
 * [LightMotes] burst with a centered "N books lit" glow-badge that
 * fades in with the motes and clears when they finish.
 *
 * Shown when the user crosses a streak tier (1/5/10/25 finished books).
 * The badge copy reads "N books lit" — the candle metaphor: each book
 * finished is another flame kept. [onFinished] fires when the [LightMotes]
 * burst completes (or immediately under reduced motion), which the host
 * uses to clear the overlay.
 *
 * The badge carries a [LiveRegionMode.Polite] semantics announcement so
 * TalkBack speaks the milestone even though it's a transient visual.
 */
@Composable
fun BooksLitBadgeOverlay(
    booksLit: Int,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val reducedMotion = LocalReducedMotion.current

    var badgeVisible by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { badgeVisible = true }

    val label = if (booksLit == 1) "1 book lit" else "$booksLit books lit"

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // The rising motes carry the celebration; onFinished is driven
        // off the burst so the badge and motes leave together.
        LightMotes(onFinished = onFinished, modifier = Modifier.fillMaxSize())

        AnimatedVisibility(
            visible = badgeVisible,
            enter = if (reducedMotion) fadeIn() else fadeIn() + scaleIn(initialScale = 0.85f),
            exit = fadeOut(),
        ) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = BooksLitSubstrate,
                tonalElevation = 0.dp,
                shadowElevation = 10.dp,
                border = BorderStroke(1.dp, BrassRamp.Brass500.copy(alpha = 0.6f)),
                modifier = Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = "Reading streak: $label"
                },
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.md),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(spacing.xxs),
                ) {
                    Text(
                        text = "🕯",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleLarge,
                        color = BrassRamp.Brass200,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = streakTagline(booksLit),
                        style = MaterialTheme.typography.bodySmall,
                        color = BrassRamp.Brass100.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** Tier-specific tagline under the "N books lit" count. Pure — pinned
 *  by [BooksLitBadgeTest]. */
internal fun streakTagline(booksLit: Int): String = when (booksLit) {
    1 -> "Your first flame."
    5 -> "Five kept alight."
    10 -> "Ten and still burning."
    25 -> "A constellation of finished tales."
    else -> "Books lit."
}

private val BooksLitSubstrate = androidx.compose.ui.graphics.Color(0xFF1F1424)

// region Previews

@Preview(name = "Books lit badge — tier 5 (dark)", widthDp = 360, heightDp = 640)
@Composable
private fun PreviewBooksLitBadge() = LibraryNocturneTheme(darkTheme = true) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(0.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = BooksLitSubstrate,
            border = BorderStroke(1.dp, BrassRamp.Brass500.copy(alpha = 0.6f)),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("🕯", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "5 books lit",
                    style = MaterialTheme.typography.titleLarge,
                    color = BrassRamp.Brass200,
                )
                Text(
                    streakTagline(5),
                    style = MaterialTheme.typography.bodySmall,
                    color = BrassRamp.Brass100.copy(alpha = 0.7f),
                )
            }
        }
    }
}

// endregion
