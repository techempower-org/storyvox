package `in`.jphe.storyvox.auth

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.feature.auth.AuthViewModel
import `in`.jphe.storyvox.feature.auth.CaptureState
import `in`.jphe.storyvox.source.ao3.auth.Ao3AuthWebView
import `in`.jphe.storyvox.source.royalroad.auth.RoyalRoadAuthWebView
import `in`.jphe.storyvox.ui.a11y.LocalAccessibleTouchTargets
import `in`.jphe.storyvox.ui.a11y.accessibleSize
import `in`.jphe.storyvox.ui.component.MagicSpinner

/**
 * Hosts the per-source login WebView and pipes captured cookies into
 * [AuthViewModel]. Lives in `:app` so it can depend on both `:feature`
 * (for the Hilt-injected ViewModel) and the source modules (for the
 * actual WebView Composables). `:feature` stays free of source-specific
 * code.
 *
 * Lifecycle:
 *  - User taps "Sign in" → navigation pushes this screen with a
 *    `sourceId` argument (defaults to Royal Road for legacy call sites).
 *  - WebView loads the source's sign-in URL — the source-owned composable
 *    (RoyalRoadAuthWebView / Ao3AuthWebView) owns the URL literals and the
 *    cookie-capture watch loop.
 *  - User completes the form (or cancels via the back arrow).
 *  - On the source's identity-cookie appearance, the WebView fires
 *    [AuthViewModel.captureCookies] with the captured map and the
 *    `sourceId`. Once the VM transitions to [CaptureState.Captured], we
 *    trigger [onSignedIn] which the NavHost wires to `popBackStack()`.
 *
 * #426 PR2 — the `sourceId` arg was added so AO3 sign-in routes through
 * the same screen. The previous shape (no arg, hardcoded RR) is preserved
 * via the default value at the route definition in
 * [`StoryvoxRoutes.AUTH_WEBVIEW`].
 */
@Composable
fun AuthWebViewScreen(
    sourceId: String,
    onSignedIn: () -> Unit,
    onCancelled: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val capture by viewModel.captureState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(capture) {
        if (capture is CaptureState.Captured) {
            // Toast outlives the Composable's destruction, unlike a Snackbar
            // anchored to a Scaffold that's about to be popped.
            val provider = when (sourceId) {
                SourceIds.AO3 -> "Archive of Our Own"
                else -> "Royal Road"
            }
            Toast.makeText(context, "Signed in to $provider", Toast.LENGTH_SHORT).show()
            onSignedIn()
        }
    }

    Scaffold { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Per-source WebView dispatch. Each source owns its own
            // composable with the right URL literals and identity-cookie
            // watch loop; the AuthViewModel receives the captured map and
            // routes to the right hydrator via the sourceId we pass back.
            when (sourceId) {
                SourceIds.AO3 -> Ao3AuthWebView(
                    onSession = { session ->
                        viewModel.captureCookies(
                            cookies = session.cookies,
                            sourceId = SourceIds.AO3,
                        )
                    },
                    onCancelled = onCancelled,
                )
                // Default to Royal Road for any unknown sourceId — keeps the
                // pre-#426-PR2 nav-route shape (no arg) bit-identical, since
                // a missing nav arg resolves to `null` → falls into this
                // branch and the RR WebView fires.
                else -> RoyalRoadAuthWebView(
                    onSession = { session ->
                        viewModel.captureCookies(
                            cookies = session.cookies,
                            sourceId = SourceIds.ROYAL_ROAD,
                        )
                    },
                    onCancelled = onCancelled,
                )
            }

            // #479 Phase 2: drop the .size(40.dp) so M3's 48dp default
            // applies (clears the WCAG 2.5.5 minimum). #486's
            // accessibleSize() additionally upgrades to 64dp when the
            // user toggles "Larger touch targets" or Switch Access is
            // active.
            FilledIconButton(
                onClick = onCancelled,
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

            if (capture is CaptureState.Capturing) {
                MagicSpinner(
                    // TalkBack #160 — without contentDescription the spinner
                    // announces nothing, leaving a screen-reader user
                    // wondering if the OAuth flow froze. Mirror the visible
                    // semantics of "we're working on it".
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(40.dp)
                        .semantics { contentDescription = "Loading sign-in page" },
                )
            }
        }
    }
}
