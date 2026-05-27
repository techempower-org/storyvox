package `in`.jphe.storyvox.auth.github

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.ui.a11y.LocalAccessibleTouchTargets
import `in`.jphe.storyvox.ui.a11y.accessibleSize
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.component.MagicSpinner
import `in`.jphe.storyvox.ui.theme.LocalSpacing

/**
 * GitHub Device Flow sign-in modal (#91).
 *
 * Lives in `:app` so it can depend on both `:source-github` (auth surface)
 * and `:core-ui` (BrassButton + spacing). Mirrors the placement of
 * [`in`.jphe.storyvox.auth.AuthWebViewScreen] for the Royal Road WebView
 * path.
 *
 * Flow:
 *  1. User taps "Sign in to GitHub" → screen pushed → ViewModel.start().
 *  2. Idle/RequestingCode show a spinner.
 *  3. AwaitingUser shows the user code, "Open browser" button, "Copy code"
 *     button. Polling runs in the background.
 *  4. On success → Captured → toast → onSignedIn() pops the screen.
 *  5. On failure / denied / expired → retry button cycles back to start().
 */
@Composable
fun GitHubSignInScreen(
    onSignedIn: () -> Unit,
    onCancelled: () -> Unit,
    viewModel: GitHubSignInViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val spacing = LocalSpacing.current

    // Kick off the device-code request when the screen appears.
    LaunchedEffect(Unit) {
        viewModel.start()
    }

    LaunchedEffect(state) {
        if (state is SignInState.Captured) {
            val login = (state as SignInState.Captured).login
            val msg = if (login != null) "Signed in to GitHub as @$login" else "Signed in to GitHub"
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
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
                    "Sign in to GitHub",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "Sign-in lifts the unauthenticated 60 req/hr cap to 5,000 req/hr and unlocks " +
                        "your repository readmes as fictions. We ask for the smallest scope possible: " +
                        "read:user public_repo. We never see your password.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StateBody(
                    state = state,
                    onRetry = { viewModel.start() },
                    onCopyCode = { code ->
                        copyToClipboard(context, code)
                        Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
                    },
                    onOpenBrowser = { uri ->
                        openInBrowser(context, uri)
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
    onRetry: () -> Unit,
    onCopyCode: (String) -> Unit,
    onOpenBrowser: (String) -> Unit,
) {
    val spacing = LocalSpacing.current
    when (state) {
        SignInState.Idle, SignInState.RequestingCode -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                modifier = Modifier.fillMaxWidth(),
            ) {
                MagicSpinner(modifier = Modifier.size(24.dp))
                Text("Asking GitHub for a code…", style = MaterialTheme.typography.bodyMedium)
            }
        }
        is SignInState.AwaitingUser -> {
            Text(
                "Step 1 — Open this page in your browser",
                style = MaterialTheme.typography.titleMedium,
            )
            BrassButton(
                label = "Open github.com/login/device",
                onClick = {
                    onOpenBrowser(state.verificationUriComplete ?: state.verificationUri)
                },
                variant = BrassButtonVariant.Primary,
            )
            Text(
                "Step 2 — Enter this code",
                style = MaterialTheme.typography.titleMedium,
            )
            // Big monospaced code block — exact rendering of the user code
            // is the whole point of this screen.
            Text(
                text = state.userCode,
                // a11y (#483): displaySmall is already 36sp on the
                // typography ramp; drop the redundant `fontSize = 36.sp`
                // override and let the theme own the value. The
                // monospace + bold + letter-spacing tweaks stay because
                // they're load-bearing for the device-code readout
                // (mono prevents 0/O confusion, the spacing helps the
                // user read the code over the phone if needed).
                style = MaterialTheme.typography.displaySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp,
                ),
                color = MaterialTheme.colorScheme.primary,
            )
            BrassButton(
                label = "Copy code",
                onClick = { onCopyCode(state.userCode) },
                variant = BrassButtonVariant.Secondary,
            )
            Text(
                "Storyvox is checking with GitHub every ${state.intervalSeconds} seconds. " +
                    "Once you authorize in the browser, this screen will close automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                MagicSpinner(modifier = Modifier.size(18.dp))
                Text(
                    "Waiting for confirmation…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                Text("Fetching your profile…", style = MaterialTheme.typography.bodyMedium)
            }
        }
        is SignInState.Captured -> {
            // Shouldn't render — the Captured state triggers onSignedIn()
            // via LaunchedEffect, which pops this screen. Brief defensive
            // text in case of a race.
            Text(
                if (state.login != null) "Signed in as @${state.login}" else "Signed in",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        SignInState.Denied -> {
            Text(
                "Sign-in cancelled.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                "You declined storyvox at the GitHub authorization page. " +
                    "Tap below to try again.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BrassButton(
                label = "Try again",
                onClick = onRetry,
                variant = BrassButtonVariant.Primary,
            )
        }
        SignInState.Expired -> {
            Text(
                "Code expired",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                "The 8-character code is only valid for 15 minutes. Tap below to get a new one.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BrassButton(
                label = "Get a new code",
                onClick = onRetry,
                variant = BrassButtonVariant.Primary,
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

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("GitHub device code", text))
}

private fun openInBrowser(context: Context, uri: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(context, "No browser available", Toast.LENGTH_LONG).show()
    }
}
