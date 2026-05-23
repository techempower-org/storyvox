package `in`.jphe.storyvox.feature.techempower

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import `in`.jphe.storyvox.data.TechEmpowerLinks
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Issue #517 — shared intent launchers for the TechEmpower surfaces.
 * Centralised so every "open Discord" entry point (top-app-bar icon,
 * TechEmpower Home card, About page) launches the same way.
 *
 * Discord launcher uses a two-step pattern:
 *  1. Try `discord://invite/{slug}` — Discord registers the
 *     `discord://` scheme on installed devices, so this opens the
 *     native app directly and the user lands inside the invite flow
 *     without a browser round-trip.
 *  2. On [ActivityNotFoundException] (Discord not installed),
 *     fall back to the HTTPS `discord.gg/{slug}` URL which opens in
 *     the user's browser → Discord's "Open in app or join via web"
 *     landing page.
 *
 * Why catch ActivityNotFoundException instead of querying the
 * PackageManager first: `queryIntentActivities` requires a
 * `<queries>` block in the manifest to inspect specific packages on
 * Android 11+, and a single try/catch avoids that manifest churn.
 * The fallthrough cost is one failed intent dispatch, not a UX
 * hiccup.
 */
internal fun launchDiscord(context: Context) {
    val deepLink = Intent(
        Intent.ACTION_VIEW,
        Uri.parse(TechEmpowerLinks.DISCORD_INVITE_DEEPLINK),
    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    try {
        context.startActivity(deepLink)
    } catch (_: ActivityNotFoundException) {
        // Discord not installed — fall through to the HTTPS URL.
        runCatching {
            context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(TechEmpowerLinks.DISCORD_INVITE_URL),
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
            )
        }
    }
}

/**
 * Issue #546 — single source of truth for the three Emergency Help
 * helplines + the everyday "Call 211" card. Each target bundles the
 * dial number with the label/body shown in the in-card chip and the
 * web fallback URL surfaced when telephony is unavailable.
 *
 * The web fallback is what saves the user on a WiFi-only tablet: a
 * `tel:` URI on a device without telephony routes to the AOSP contact
 * picker by default — which is exactly the wrong landing for someone
 * in a crisis. With a web URL in hand we can offer "open in your
 * browser" as a real alternative to dialling.
 *
 * 911 has no real web counterpart (you cannot place an emergency
 * dispatch through a webpage), so the web fallback is the FCC's
 * informational page on text-to-911 — the closest the public web gets
 * to "here is how you reach 911 without a regular voice call".
 */
internal enum class EmergencyTarget(
    val number: String,
    val label: String,
    val body: String,
    val webFallback: String,
) {
    Crisis988(
        number = TechEmpowerLinks.CRISIS_HELP_NUMBER,
        label = "Suicide & Crisis Lifeline",
        body = "National 24/7 mental health crisis support.",
        // 988lifeline.org has a Chat option that works WITHOUT
        // telephony — the user can reach a counsellor right now from
        // a WiFi-only device. This is the killer fallback for #546.
        webFallback = "https://988lifeline.org/chat/",
    ),
    Help211(
        number = TechEmpowerLinks.PRIMARY_HELP_NUMBER,
        label = "United Way social services",
        body = "Housing, food, utilities, mental health referrals.",
        // 211.org has a "search by ZIP" tool plus a chat option — both
        // work without telephony.
        webFallback = "https://www.211.org/",
    ),
    Dispatch911(
        number = TechEmpowerLinks.EMERGENCY_DISPATCH_NUMBER,
        label = "Emergency dispatch",
        body = "Life-threatening situations — police, fire, ambulance.",
        // 911 has no real WiFi-only equivalent — point the user at the
        // FCC's text-to-911 guidance, which is the official US
        // resource for "I can't make a voice call right now".
        webFallback = "https://www.fcc.gov/consumers/guides/what-you-need-know-about-text-911",
    ),
}

/**
 * Issue #546 — entry point for every "dial a helpline" affordance on
 * the TechEmpower surfaces. Probes [PackageManager.FEATURE_TELEPHONY]
 * BEFORE firing ACTION_DIAL so that WiFi-only devices land on a real
 * fallback dialog instead of the AOSP contact-picker (the documented
 * default behaviour when no dialer activity is registered).
 *
 * Why a single `hasSystemFeature` check is sufficient: telephony is
 * the hardware feature backing the dialer app on AOSP. A device that
 * lacks `android.hardware.telephony` cannot place a call, and the
 * ACTION_DIAL intent reliably falls through to a contact picker in
 * that state. The check is fast (constant-time lookup against the
 * system features table) and has no permission requirements.
 *
 * 911 is handled identically — even though "emergency call routing on
 * WiFi-only devices" is a real concept in some regions, in practice
 * Android tablets without telephony cannot place 911 calls. The
 * fallback dialog still surfaces the number for a user to dial from
 * another device or via the FCC text-to-911 path.
 */
internal fun dialOrSurfaceFallback(
    context: Context,
    target: EmergencyTarget,
    onNoTelephony: (EmergencyTarget) -> Unit,
) {
    val hasTelephony = context.packageManager
        .hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
    if (!hasTelephony) {
        onNoTelephony(target)
        return
    }
    runCatching {
        context.startActivity(
            Intent(
                Intent.ACTION_DIAL,
                Uri.parse(TechEmpowerLinks.telUri(target.number)),
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
        )
    }.onFailure {
        // ACTION_DIAL failed even with telephony reported — extremely
        // rare (a custom ROM with no dialer) but the fallback dialog
        // is the right UX here too.
        onNoTelephony(target)
    }
}

/**
 * Issue #546 — fallback dialog shown when a user taps a helpline on a
 * device without telephony. Surfaces the number prominently, offers
 * copy-to-clipboard (so they can dial from another device), and a
 * "Open in browser" affordance pointing at the helpline's web
 * counterpart (988 chat, 211 search, FCC text-to-911 guidance).
 *
 * Why an AlertDialog instead of a bottom sheet: a crisis surface
 * needs a modal, focus-stealing affordance with a single primary
 * action. Bottom sheets dismiss on outside-tap and stack with the
 * scaffold; AlertDialog is the right semantic for "we owe you an
 * explanation before you proceed".
 *
 * Why no telephony icon: the user just tried to make a call — a
 * crossed-out phone glyph reads as failure-shaming. Plain text is
 * kinder: "This device can't make calls."
 *
 * Lives here (next to [dialOrSurfaceFallback] + [EmergencyTarget])
 * because both [TechEmpowerHomeScreen]'s Emergency Help card AND
 * [TechEmpowerHelpIcons]'s top-app-bar phone icon need this surface,
 * and centralising the composable keeps the recovery UX consistent
 * across entry points.
 */
@Composable
internal fun NoTelephonyFallbackDialog(
    target: EmergencyTarget,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val spacing = LocalSpacing.current
    val brass = MaterialTheme.colorScheme.primary
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "This device can't make calls",
                style = MaterialTheme.typography.titleMedium,
                color = brass,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Text(
                    text = "${target.label} — call ${target.number} from a phone, or use the web option below.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                // The number itself, large-and-brass — matches the
                // EmergencyDialButton visual ranking so the user
                // anchors on it as the actionable atom.
                Text(
                    text = target.number,
                    style = MaterialTheme.typography.displaySmall,
                    color = brass,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                // Two side-by-side affordances: copy the number, open
                // the web fallback. Spacing.md gap so the user can hit
                // either with a thumb-down tap.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    TextButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as? ClipboardManager
                            cm?.setPrimaryClip(
                                ClipData.newPlainText(target.label, target.number),
                            )
                            Toast.makeText(
                                context,
                                "${target.number} copied",
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                    ) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(spacing.xs))
                        Text(stringResource(R.string.techempower_copy))
                    }
                    TextButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse(target.webFallback),
                                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                                )
                            }
                        },
                    ) {
                        Icon(
                            Icons.Filled.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(spacing.xs))
                        Text(stringResource(R.string.techempower_open_web))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.techempower_close)) }
        },
    )
}
