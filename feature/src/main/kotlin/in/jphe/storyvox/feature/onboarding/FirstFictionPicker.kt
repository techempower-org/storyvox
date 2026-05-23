package `in`.jphe.storyvox.feature.onboarding

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Issue #599 (v1.0 blocker) — third of three first-launch welcome
 * screens. Closes the loop by giving the user three plain-English
 * choices for "what to listen to right now":
 *
 *   1. Browse TechEmpower's free guides — routes to the TechEmpower
 *      Home drill-down which already lands the user on the curated
 *      free content (anonymous Notion path, no token needed).
 *   2. Add a book from a website — routes to Library with the
 *      magic-add sheet pre-opened. If the user's clipboard contains a
 *      URL, the sheet pre-fills with it (chip the clipboard per the
 *      issue spec).
 *   3. Skip — show me everything — routes to plain Library, blank
 *      state.
 *
 * Visual: three full-width brass-bordered cards stacked vertically.
 * Each card has an icon, a plain-English title, a one-line body, and
 * a faint chevron-style right edge. Tapping anywhere on the card
 * fires its action — no "Continue" button after the choice, the tap
 * IS the choice.
 */
@Composable
fun FirstFictionPicker(
    onBrowseTechEmpower: () -> Unit,
    onAddFromWebsite: (clipboardUrl: String?) -> Unit,
    onSkip: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val ctx = LocalContext.current
    // Pull the clipboard URL once on entry so a stale clipboard tap
    // doesn't surprise the user mid-flow. The URL is passed through
    // [onAddFromWebsite] only if it actually looks like a URL — empty
    // / non-URL clipboards fall back to the plain "open magic-add
    // sheet" path.
    var clipboardUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        clipboardUrl = readClipboardUrl(ctx)
    }

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
        ) {
            Spacer(Modifier.height(spacing.lg))
            Text(
                stringResource(R.string.onboarding_first_fiction_headline),
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Medium,
                fontSize = 28.sp,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(spacing.sm))
            Text(
                stringResource(R.string.onboarding_first_fiction_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 420.dp),
            )
            Spacer(Modifier.height(spacing.lg))

            BigChoiceCard(
                icon = Icons.AutoMirrored.Outlined.MenuBook,
                title = stringResource(R.string.onboarding_pick_te_title),
                body = stringResource(R.string.onboarding_pick_te_body),
                onClick = onBrowseTechEmpower,
            )
            Spacer(Modifier.height(spacing.md))
            BigChoiceCard(
                icon = Icons.Outlined.Add,
                title = stringResource(R.string.onboarding_pick_add_title),
                body = if (clipboardUrl != null) {
                    stringResource(R.string.onboarding_pick_add_body_clipboard)
                } else {
                    stringResource(R.string.onboarding_pick_add_body_default)
                },
                onClick = { onAddFromWebsite(clipboardUrl) },
                highlighted = clipboardUrl != null,
            )
            Spacer(Modifier.height(spacing.md))
            BigChoiceCard(
                icon = Icons.Outlined.AutoStories,
                title = stringResource(R.string.onboarding_pick_skip_title),
                body = stringResource(R.string.onboarding_pick_skip_body),
                onClick = onSkip,
            )
            Spacer(Modifier.height(spacing.xl))
        }
    }
}

/**
 * A full-width brass-bordered card with icon + title + body.
 * [highlighted] true thickens the brass border to flag the
 * clipboard-aware "Add from website" affordance when there's a URL
 * waiting; otherwise all three cards share the same visual weight
 * so a first-time user reads "three peer options", not "one obvious
 * choice the others".
 */
@Composable
private fun BigChoiceCard(
    icon: ImageVector,
    title: String,
    body: String,
    onClick: () -> Unit,
    highlighted: Boolean = false,
) {
    val spacing = LocalSpacing.current
    val brass = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 480.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(brass.copy(alpha = if (highlighted) 0.20f else 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = brass,
                modifier = Modifier.size(24.dp),
            )
        }
        Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Read the system clipboard once and return the first http(s) URL we
 * find, or null if the clipboard is empty / non-URL. We intentionally
 * keep this *out* of a viewmodel — the clipboard is a per-screen
 * one-shot read, not a state to subscribe to. If the user paste-edits
 * mid-flow they can use the manual entry path on the next screen.
 *
 * Why we don't request CLIPBOARD permission: Android exposes
 * ClipboardManager without a permission on API 28- and gates it on
 * focus + foreground on API 29+. We read inside [LaunchedEffect] which
 * runs after composition (the activity has focus); on API 29+ a focus
 * race would result in null, which falls back gracefully to the
 * manual-paste path. A toast-on-paste UX is a v1.1 polish item.
 */
private fun readClipboardUrl(ctx: Context): String? = runCatching {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip = cm?.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    val text = clip.getItemAt(0).text?.toString()?.trim().orEmpty()
    when {
        text.startsWith("http://", ignoreCase = true) -> text
        text.startsWith("https://", ignoreCase = true) -> text
        else -> null
    }
}.getOrNull()
