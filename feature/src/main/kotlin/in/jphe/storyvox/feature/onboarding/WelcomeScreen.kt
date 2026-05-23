package `in`.jphe.storyvox.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.component.MagicSkeletonTile
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Issue #599 (v1.0 blocker) — first of three first-launch welcome
 * screens. The 5-year-old-friendly entry point: animated brass sigil,
 * three-word benefit headline ("Stories, read aloud."), plain-English
 * body line, ONE primary "Get started" CTA, and a small "I've used
 * storyvox before" skip link.
 *
 * Visual deliberately quiet — the animated [MagicSkeletonTile] re-uses
 * the existing rotating six-pointed sigil from VoicePickerGate at a
 * narrower 160dp × 220dp so it reads as a "page header sigil" rather
 * than a cover-shaped tile.
 *
 * Why three words for the headline: a 5-year-old reading aloud (or a
 * TalkBack user listening) parses "Stories, read aloud" instantly. The
 * earlier "Welcome to storyvox" was app-name-first which is wrong for
 * a first-launch reader who hasn't built brand attachment yet — they
 * need to know *what the app does* in the first half-second.
 *
 * Why a single "Get started" button (not "Continue"): "Continue"
 * implies they're mid-flow. "Get started" is the verb a 5-year-old
 * understands as "begin doing the thing". Per the issue spec.
 */
@Composable
fun WelcomeScreen(
    onGetStarted: () -> Unit,
    onSkip: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(spacing.lg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.height(spacing.xl))
            MagicSkeletonTile(
                modifier = Modifier.size(width = 160.dp, height = 220.dp),
                shape = MaterialTheme.shapes.medium,
                glyphSize = 88.dp,
            )
            Spacer(Modifier.height(spacing.xl))
            // EB Garamond is mapped to FontFamily.Serif in Library
            // Nocturne's typography — the same proxy WhyAreWeWaitingPanel
            // uses for its brass headline. 32sp gives the three-word
            // benefit headline the page-anchor weight it needs without
            // wrapping on a 360dp narrow phone.
            Text(
                stringResource(R.string.onboarding_welcome_headline),
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Medium,
                fontSize = 32.sp,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(spacing.md))
            Text(
                stringResource(R.string.onboarding_welcome_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 360.dp),
            )
            Spacer(Modifier.height(spacing.xl))
            BrassButton(
                label = stringResource(R.string.onboarding_get_started),
                onClick = onGetStarted,
                variant = BrassButtonVariant.Primary,
            )
            Spacer(Modifier.height(spacing.sm))
            BrassButton(
                label = stringResource(R.string.onboarding_skip_welcome),
                onClick = onSkip,
                variant = BrassButtonVariant.Text,
            )
            Spacer(Modifier.height(spacing.xl))
        }
    }
}
