package `in`.jphe.storyvox.feature.techempower

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * Issue #517 + #608 — brass-tinted help icons (211 + Discord) surfaced
 * in the top-app-bar on Library and TechEmpower Home. Two single-tap
 * affordances for "I need help right now": 211 (social services) and
 * Discord (peer-support).
 *
 * Issue #775 — the 988 (Suicide & Crisis Lifeline) icon that used to
 * lead this row was removed for the current app stage; it can be
 * reinstated when the user base is large enough to warrant a proper
 * crisis-UX review with intervention experts.
 *
 * Why a shared composable: the icon pair appears identically on both
 * surfaces (Library + TechEmpower Home), and centralising the layout
 * + intent wiring means a behavioural change is a one-file edit
 * instead of keeping two copies in sync.
 *
 * Why a `Box.clickable` instead of `IconButton`: IconButton bakes in
 * a default 48dp ripple ring with its own padding semantics that
 * shrinks the visual glyph relative to a plain Box; the Boxes here
 * already provide the same 48dp touch target and align with the
 * other top-bar action visuals (SyncCloudIcon also uses a Box).
 *
 * Brass tint comes from `MaterialTheme.colorScheme.primary` — same
 * brass-on-warm-dark palette as every other top-app-bar action in the
 * app.
 *
 * v0.5.51 — first pass alongside the TechEmpower brand integration.
 * v0.5.61 — split 988 into its own icon (#608, TalkBack safety).
 * Issue #775 — 988 icon removed.
 */
@Composable
fun TechEmpowerHelpIcons() {
    val context = LocalContext.current

    // Issue #546 — fallback dialog state for the 211 helpline tap on a
    // device without telephony. The Discord icon is non-telephony so
    // it bypasses this state.
    var noTelephonyTarget by remember { mutableStateOf<EmergencyTarget?>(null) }

    // ─── Call 211 — local help / social services ─────────────────────
    // Single tap. tel: URI goes through ACTION_DIAL so the user lands
    // in the dialer with the number pre-filled but NOT auto-dialled
    // — accidental top-app-bar taps surface as "huh, the dialer
    // opened" rather than an actual phone call. ACTION_CALL would
    // require CALL_PHONE permission and is the wrong UX for an in-bar
    // shortcut.
    //
    // Issue #546 — routes through [dialOrSurfaceFallback] so devices
    // without telephony land on the explanatory fallback dialog
    // instead of the AOSP contact picker.
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(
                role = Role.Button,
                onClick = {
                    dialOrSurfaceFallback(
                        context = context,
                        target = EmergencyTarget.Help211,
                        onNoTelephony = { noTelephonyTarget = it },
                    )
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Phone,
            contentDescription =
                "Call 211, local help: housing, food, and mental health referrals.",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
    }

    Spacer(Modifier.width(8.dp))

    // ─── Open peer-support Discord ───────────────────────────────────
    // The Forum icon is the most thematically-correct standard Material
    // icon for "community chat" (Discord-specific icons aren't in the
    // M3 set). Two-step launcher: tries `discord://invite/{slug}` first
    // so installed Discord apps open native, falls back to the HTTPS
    // URL otherwise. See [launchDiscord] for the rationale.
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(
                role = Role.Button,
                onClick = { launchDiscord(context) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Forum,
            contentDescription = "Open the TechEmpower peer-support Discord.",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
    }

    // Issue #546 — render the fallback dialog when telephony is
    // absent. Same content/affordances as [TechEmpowerHomeScreen]'s
    // dialog so users see consistent recovery regardless of which
    // entry point they tapped (top-bar icon vs Emergency Help card).
    val target = noTelephonyTarget
    if (target != null) {
        NoTelephonyFallbackDialog(
            target = target,
            onDismiss = { noTelephonyTarget = null },
        )
    }
}
