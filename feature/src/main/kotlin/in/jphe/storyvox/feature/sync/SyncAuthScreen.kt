package `in`.jphe.storyvox.feature.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.ui.component.MagicCircularProgress

/**
 * Brass-themed sign-in screen for the InstantDB sync layer.
 *
 * One screen, three field configurations:
 *  - Email entry → "Send code"
 *  - Code entry → "Verify"
 *  - Signed in → status + "Sign out"
 *
 * Why one screen rather than a multi-step navigation graph: the user
 * journey is linear and short, and a Compose `when (state)` keeps the
 * transitions trivially testable and lossless under config changes.
 * The headline copy is intentionally explanatory ("syncs your
 * library, settings, and AI keys across devices") so a first-time
 * user understands the value before tapping an unfamiliar text field.
 */
@Composable
fun SyncAuthScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SyncAuthViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(title = { Text("Sync") })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            Icon(
                imageVector = Icons.Filled.CloudSync,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Keep your library safe",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Sync your library, follows, reading positions, " +
                    "bookmarks, pronunciation dictionary, and (with a passphrase) " +
                    "your AI keys across devices. If you uninstall, sign in again " +
                    "and everything comes back.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))

            when (val current = state) {
                is SignInState.SignedOut -> SignedOutForm(
                    email = current.email,
                    error = current.error,
                    onEmailChange = viewModel::updateEmail,
                    onSubmit = viewModel::sendCode,
                )
                is SignInState.SendingCode -> InProgress("Sending code to ${current.email}…")
                is SignInState.CodePrompt -> CodePromptForm(
                    email = current.email,
                    code = current.code,
                    error = current.error,
                    onCodeChange = viewModel::updateCode,
                    onSubmit = viewModel::verifyCode,
                    onReset = viewModel::reset,
                )
                is SignInState.Verifying -> InProgress("Verifying…")
                is SignInState.SignedIn -> SignedInPanel(
                    email = current.user.email ?: "(no email on file)",
                    onSignOut = viewModel::signOut,
                    onClose = onClose,
                )
            }
        }
    }
}

@Composable
private fun SignedOutForm(
    email: String,
    error: String?,
    onEmailChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    OutlinedTextField(
        value = email,
        onValueChange = onEmailChange,
        label = { Text("Email") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth(),
        isError = error != null,
        supportingText = error?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
    )
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = onSubmit,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(),
    ) { Text("Send code") }
}

@Composable
private fun CodePromptForm(
    email: String,
    code: String,
    error: String?,
    onCodeChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onReset: () -> Unit,
) {
    Text(
        text = "Code sent to $email. Check your inbox.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = code,
        onValueChange = onCodeChange,
        label = { Text("6-digit code") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
        isError = error != null,
        supportingText = error?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
    )
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = onSubmit,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Verify") }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = onReset,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Use a different email") }
}

@Composable
private fun InProgress(label: String) {
    // a11y (#484): give the spinner a contentDescription so TalkBack
    // announces "Loading, <label>" instead of staying silent during a
    // multi-second OAuth handshake. Marked as a live region so the
    // announcement is re-spoken when the label flips between states.
    // v1.0 polish (2026-05-16) — JP audit flagged the Material
    // CircularProgressIndicator as the "weird arc spinning around"
    // visible across the app's loading surfaces. Swap to
    // MagicCircularProgress so OAuth handoff (sign-in → e-mail magic
    // link → token exchange) shows the same brass sigil family as
    // every other Library Nocturne loading state. The semantics live
    // region stays identical so TalkBack's "Loading, <label>"
    // announcement is preserved.
    MagicCircularProgress(
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .size(48.dp)
            .semantics {
                contentDescription = "Loading: $label"
                liveRegion = LiveRegionMode.Polite
            },
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SignedInPanel(
    email: String,
    onSignOut: () -> Unit,
    onClose: () -> Unit,
) {
    Text(
        text = "Signed in",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = email,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(24.dp))
    Button(
        onClick = onClose,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Done") }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = onSignOut,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Sign out") }
}
