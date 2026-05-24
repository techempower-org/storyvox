package `in`.jphe.storyvox.feature.techempower

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.data.TechEmpowerLinks
import `in`.jphe.storyvox.ui.R as UiR
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Issue #517 — TechEmpower Home screen. The dedicated landing for
 * "I'm here for TechEmpower's resources" — reached via the
 * brass-edged TechEmpower hero card pinned at the top of Library.
 *
 * Structure (top to bottom):
 *  - Brass-on-warm-dark top-app-bar with back arrow + help icons
 *    (phone for 211, forum for Discord) on the right.
 *  - Hero: TechEmpower sun-disk logo + mission tagline.
 *  - Brass-edged card grid:
 *      1. Browse Resources → opens Browse (Notion's anonymous-mode
 *         four-fiction layout is the default tile set today).
 *      2. Peer Support Discord → ACTION_VIEW Discord invite URL.
 *      3. Call 211 → ACTION_DIAL tel:211 (United Way social services).
 *      4. About TechEmpower → drill-down to [TechEmpowerAboutScreen].
 *  - Featured guides strip: horizontal row of the 8 hand-curated
 *    TechEmpower guide chapters (read directly off the anonymous
 *    Notion delegate's curated list — keeps this screen plumbing-
 *    free; if the guide list grows, this strip grows with it).
 *
 * Why no Hilt VM: the surface is entirely static content (constants
 * + intents) plus the curated guides list. Adding a VM here would be
 * a no-op layer between the screen and the constants module.
 *
 * Library Nocturne palette throughout — JP's design call #3
 * explicitly excludes TechEmpower warm-earth-tones from the storyvox
 * engine UI. TechEmpower's branding lands through *content* (logo +
 * copy), not theme.
 *
 * v0.5.51 — first pass.
 * v0.5.52 — added Emergency Help card (issue #516).
 * Issue #775 — Emergency Help card (988 / 911) removed; 211 affordance
 * stays as a regular grid card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TechEmpowerHomeScreen(
    onBack: () -> Unit,
    onOpenBrowse: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenFiction: (String) -> Unit,
) {
    val spacing = LocalSpacing.current
    val context = LocalContext.current

    // Issue #546 — when telephony is absent (WiFi-only tablets), pop a
    // fallback dialog instead of routing the user into the contact
    // picker.
    var noTelephonyTarget by remember { mutableStateOf<EmergencyTarget?>(null) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "TechEmpower",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to Library",
                        )
                    }
                },
                actions = {
                    // Same paired phone + Discord brass icons as the
                    // Library top-app-bar — see [TechEmpowerHelpIcons]
                    // kdoc for the design rationale.
                    TechEmpowerHelpIcons()
                },
            )
        },
    ) { scaffoldPadding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            contentPadding = PaddingValues(spacing.md),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding),
        ) {
            // ─── Hero (full span) ─────────────────────────────────
            item(span = { GridItemSpan(maxLineSpan) }) {
                TechEmpowerHero()
            }

            // ─── Get-help + content cards ─────────────────────────
            item {
                TechEmpowerCard(
                    title = "Browse Resources",
                    body = "Free tech guides, EBT support, and digital safety — read or listen.",
                    icon = null,
                    onClick = onOpenBrowse,
                )
            }
            item {
                TechEmpowerCard(
                    title = "Peer Support",
                    body = "Join our Discord community. Real people, no scripts.",
                    icon = Icons.Filled.Forum,
                    onClick = { launchDiscord(context) },
                )
            }
            // Issue #546 — surface a "no-telephony" fallback dialog if
            // the device can't dial. Without this guard, WiFi-only
            // tablets like the Tab A7 Lite route ACTION_DIAL to the
            // system Contacts picker — exactly the wrong UX when the
            // user explicitly wants to reach 211.
            item {
                TechEmpowerCard(
                    title = "Call 211",
                    body = "Local help — housing, food, utilities, mental health referrals via United Way.",
                    icon = Icons.Filled.Phone,
                    onClick = {
                        dialOrSurfaceFallback(
                            context = context,
                            target = EmergencyTarget.Help211,
                            onNoTelephony = { noTelephonyTarget = it },
                        )
                    },
                )
            }
            // Issue #775 — the Emergency Help card (988 / 911) that
            // sat between Call 211 and About TechEmpower was removed
            // for the current app stage. 211 stays as a regular grid
            // card above; the crisis affordances can be reinstated as
            // a new card here once the UX review with intervention
            // experts lands.
            item {
                TechEmpowerCard(
                    title = "About TechEmpower",
                    body = "Our mission, donate, partnerships, and contact.",
                    icon = Icons.Filled.Info,
                    onClick = onOpenAbout,
                )
            }

            // ─── Featured guides strip (full span) ────────────────
            item(span = { GridItemSpan(maxLineSpan) }) {
                FeaturedGuidesStrip(onOpenFiction = onOpenFiction)
            }
        }
    }

    // Issue #546 — no-telephony fallback dialog. The user explicitly
    // asked to dial a helpline; on a WiFi-only tablet we owe them a
    // clear "this device can't make calls" message plus actionable
    // recovery (copy the number, open the helpline's web resource).
    // Routing them to a contact picker — the AOSP default fallback
    // for ACTION_DIAL on a device without telephony — is dangerous in
    // a crisis.
    val target = noTelephonyTarget
    if (target != null) {
        NoTelephonyFallbackDialog(
            target = target,
            onDismiss = { noTelephonyTarget = null },
        )
    }
}

/**
 * Hero section — sun-disk logo + mission tagline. Reads as the
 * "you've arrived at TechEmpower" landmark. Logo is rendered at a
 * fixed 96dp so it has presence without dominating the card grid
 * underneath.
 */
@Composable
private fun TechEmpowerHero() {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Image(
            painter = painterResource(id = UiR.drawable.techempower_sun),
            contentDescription = "TechEmpower sun-disk logo",
            modifier = Modifier
                .size(96.dp)
                .clip(MaterialTheme.shapes.large),
            contentScale = ContentScale.Fit,
        )
        // Issue #603 (v1.0). Lead with a plain-English benefit headline
        // ("Free help — read out loud.") instead of TechEmpower's
        // marketing tagline. The TechEmpower mission tagline still
        // surfaces on the About sub-screen where readers go to learn
        // about the nonprofit; on this landing card we answer the
        // first-time visitor's actual question — "what is this app
        // for, in plain words?".
        Text(
            text = "Free help — read out loud.",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Books, guides, and how-tos. " +
                "TechEmpower's free library — pick something and we'll read it to you.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Brass-edged card used for the four primary actions on TechEmpower
 * Home. 1.5dp brass outline reads as "this is the lead action" without
 * shouting; same vocabulary as the brass-edged cards on the Settings
 * hub and Plugin manager landing.
 *
 * The accompanying [icon] is optional — Browse Resources has no
 * single-icon analogue (it's "all of TechEmpower's content"), and a
 * card without an icon balances the grid layout. The other three
 * cards have a leading icon that anchors the affordance.
 */
@Composable
private fun TechEmpowerCard(
    title: String,
    body: String,
    icon: ImageVector?,
    onClick: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val brass = MaterialTheme.colorScheme.primary
    val substrate = MaterialTheme.colorScheme.surfaceContainerHigh
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .clip(MaterialTheme.shapes.large)
            .background(substrate)
            .border(
                width = 1.5.dp,
                color = brass.copy(alpha = 0.55f),
                shape = MaterialTheme.shapes.large,
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(spacing.md),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = brass,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.height(spacing.xs))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = brass,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
            )
        }
    }
}

// [EmergencyTarget], [dialOrSurfaceFallback], and the no-telephony
// fallback dialog live in [TechEmpowerIntents.kt] because they're
// shared between the "Call 211" card here AND the top-app-bar phone
// icon in [TechEmpowerHelpIcons]. Keeping them in one place means a
// behavioural change (e.g., add a new helpline, swap a fallback URL)
// is a one-file edit.
//
// Issue #775 — the Emergency Help card composable + its
// EmergencyDialButton helper lived here until v0.5.87; both were
// removed alongside the 988 / 911 affordances. The grid card list in
// [TechEmpowerHomeScreen] now stops at "Call 211" + "About
// TechEmpower".

/**
 * Horizontal strip of the eight hand-curated TechEmpower guides. The
 * fiction id is `notion:guides` (the anonymous-mode delegate's
 * PageList entry), and each chapter inside is the row payload — but
 * for this strip we go one level deeper: open the fiction-detail
 * page for `notion:guides`, where the user picks a chapter.
 *
 * We don't surface chapter-level deep-links from this strip because
 * the strip's job is to advertise the *category*, not commit the user
 * to a specific guide. The single-tap-to-fiction-detail pattern
 * matches every other "open fiction" surface in the app.
 *
 * If the curated guide list ever grows past ~12 entries, LazyRow's
 * built-in horizontal scrolling absorbs the growth without changing
 * the rest of the screen.
 */
@Composable
private fun FeaturedGuidesStrip(onOpenFiction: (String) -> Unit) {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = "Featured guides",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "Step-by-step help for free internet, EBT, password safety, and more.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(spacing.xs))
        // Pull the curated guide titles inline so the strip doesn't
        // need to suspend on a Notion API round-trip just to render
        // its title row — these labels are the SAME labels that
        // `:source-notion`'s [NotionDefaults.techempowerFictions]
        // serves as chapter titles for the "Guides" PageList fiction.
        // Keeping them inline here means the strip renders instantly
        // on cold launch (no network), and the actual *content* is
        // still served by the Notion source when the user taps.
        val guideTitles = listOf(
            "How to use TechEmpower.org",
            "Free internet",
            "EV incentives",
            "EBT balance",
            "EBT spending",
            "Findhelp",
            "Password manager",
            "Free cell service",
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            contentPadding = PaddingValues(vertical = spacing.xs),
        ) {
            items(guideTitles) { title ->
                GuideChip(
                    title = title,
                    onClick = {
                        // Open the "Guides" fiction at fiction-detail
                        // depth. The Notion fiction id format is
                        // `notion:<sectionId>`; the anonymous delegate
                        // owns the section-id space.
                        onOpenFiction("notion:guides")
                    },
                )
            }
        }
    }
}

/**
 * Single brass-tinted chip in the featured-guides strip. Reads as a
 * leaf affordance — wide enough for two lines of title, narrow enough
 * that ~3 fit on the Flip3 cover width.
 */
@Composable
private fun GuideChip(title: String, onClick: () -> Unit) {
    val spacing = LocalSpacing.current
    val brass = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(width = 140.dp, height = 88.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(
                width = 1.dp,
                color = brass.copy(alpha = 0.40f),
                shape = MaterialTheme.shapes.medium,
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = brass,
            textAlign = TextAlign.Center,
            maxLines = 3,
        )
    }
}
