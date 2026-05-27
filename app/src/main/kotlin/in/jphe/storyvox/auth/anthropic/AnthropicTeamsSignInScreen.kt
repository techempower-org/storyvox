package `in`.jphe.storyvox.auth.anthropic

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.ui.a11y.LocalAccessibleTouchTargets
import `in`.jphe.storyvox.ui.a11y.accessibleSize
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.component.MagicSpinner
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * Anthropic Teams (OAuth) sign-in screen (#181).
 *
 * Mirrors `:app`'s `GitHubSignInScreen` shape — a full-screen modal
 * pushed onto the nav stack from Settings → AI → "Sign in to Teams".
 *
 * Flow:
 *  1. Idle / first-render — explains what's about to happen, big
 *     "Open browser" button.
 *  2. AwaitingCode — `CustomTabsIntent` launched on `claude.ai`, screen
 *     shows the paste field plus a "Re-open browser" link if the user
 *     dismissed the tab without authorizing.
 *  3. Capturing — spinner while the code-for-token POST is in flight.
 *  4. Captured — toast + popBackStack() back to Settings.
 *  5. Failure — error copy + retry button.
 */
@Composable
fun AnthropicTeamsSignInScreen(
    onSignedIn: () -> Unit,
    onCancelled: () -> Unit,
    viewModel: AnthropicTeamsSignInViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val spacing = LocalSpacing.current

    LaunchedEffect(state) {
        if (state is SignInState.Captured) {
            Toast.makeText(context, "Signed in to Anthropic Teams", Toast.LENGTH_SHORT).show()
            onSignedIn()
        }
    }

    Scaffold { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(spacing.lg),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                Text(
                    "Sign in to Anthropic Teams",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "Storyvox uses your Claude.ai workspace login (no API key) so " +
                        "Recap, character lookup, and chat run against your Teams " +
                        "subscription — costs land on your console.anthropic.com bill, " +
                        "not on storyvox.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StateBody(
                    state = state,
                    onOpenBrowser = {
                        val url = viewModel.openBrowser()
                        openInCustomTab(context, url)
                    },
                    onSubmit = viewModel::submitCode,
                    onRetry = {
                        val url = viewModel.openBrowser()
                        openInCustomTab(context, url)
                    },
                )
            }

            // #479 Phase 2 — was .size(40.dp), now M3 48dp default
            // plus the #486 enlarged-targets opt-in.
            FilledIconButton(
                onClick = {
                    viewModel.cancel()
                    onCancelled()
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .accessibleSize(
                        enlargedFlag = LocalAccessibleTouchTargets.current,
                        base = 48.dp,
                    ),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel sign-in")
            }
        }
    }
}

@Composable
private fun StateBody(
    state: SignInState,
    onOpenBrowser: () -> Unit,
    onSubmit: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val spacing = LocalSpacing.current
    when (state) {
        SignInState.Idle -> {
            Text(
                "Step 1 — open Anthropic in your browser",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Tapping the button opens claude.ai in a Custom Tab. Sign in, " +
                    "click Authorize, and Anthropic will hand you back a code to paste here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BrassButton(
                label = "Open browser",
                onClick = onOpenBrowser,
                variant = BrassButtonVariant.Primary,
            )
        }
        is SignInState.AwaitingCode -> {
            var draft by remember { mutableStateOf("") }
            Text(
                "Step 2 — paste the authorization code",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Anthropic shows the code on the page after you click Authorize. " +
                    "Copy it and paste it below.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("Authorization code") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                BrassButton(
                    label = "Sign in",
                    onClick = { onSubmit(draft) },
                    variant = BrassButtonVariant.Primary,
                )
                BrassButton(
                    label = "Re-open browser",
                    onClick = onOpenBrowser,
                    variant = BrassButtonVariant.Text,
                )
            }
        }
        SignInState.Capturing -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                modifier = Modifier.fillMaxWidth(),
            ) {
                MagicSpinner(modifier = Modifier.size(24.dp))
                Text("Exchanging code with Anthropic…", style = MaterialTheme.typography.bodyMedium)
            }
        }
        SignInState.Captured -> {
            // Captured fires popBackStack via LaunchedEffect; brief
            // defensive copy in case of a recomposition race.
            Text(
                "Signed in.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        is SignInState.Failure -> {
            Text(
                "Sign-in failed",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                state.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.retryable) {
                BrassButton(
                    label = "Try again",
                    onClick = onRetry,
                    variant = BrassButtonVariant.Primary,
                )
            }
        }
    }
}

/**
 * Open the URL in a Chrome Custom Tab. Falls back to a plain
 * `Intent.ACTION_VIEW` if no Custom-Tabs-aware browser is installed
 * (rare but cheap to handle: GitHub's sign-in screen does the same).
 */
private fun openInCustomTab(context: Context, url: String) {
    val customTab = runCatching {
        CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setUrlBarHidingEnabled(false)
            .build()
            .also { it.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }.getOrNull()
    if (customTab != null) {
        runCatching { customTab.launchUrl(context, Uri.parse(url)) }
            .onFailure { fallbackOpen(context, url) }
    } else {
        fallbackOpen(context, url)
    }
}

private fun fallbackOpen(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(context, "No browser available", Toast.LENGTH_LONG).show()
    }
}
