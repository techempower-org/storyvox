package `in`.jphe.storyvox.auth.anthropic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.llm.auth.AnthropicTeamsAuthApi
import `in`.jphe.storyvox.llm.auth.AnthropicTeamsAuthConfig
import `in`.jphe.storyvox.llm.auth.AnthropicTeamsAuthRepository
import `in`.jphe.storyvox.llm.auth.PkcePair
import `in`.jphe.storyvox.llm.auth.TokenResult
import java.security.SecureRandom
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State machine for the Anthropic Teams (OAuth) sign-in modal (#181).
 *
 * Mirrors the shape of `:app`'s `GitHubSignInViewModel` — the screen
 * consumes a [SignInState] StateFlow and reports back via
 * [openBrowser] (start the flow), [submitCode] (paste-callback), and
 * [cancel].
 *
 * Flow:
 *   1. [openBrowser] → generates a PKCE pair + state nonce, returns the
 *      authorize URL the screen should hand to a CustomTabsIntent.
 *      State transitions Idle → AwaitingCode.
 *   2. After authorizing in the browser, the user copies the
 *      authorization code Anthropic shows on the redirect page and
 *      pastes it into the modal.
 *   3. [submitCode] → POSTs to the token endpoint, captures the
 *      bearer + refresh + scopes. State transitions
 *      AwaitingCode → Capturing → Captured.
 *   4. On any error → Failure(retryable) and the user can [openBrowser]
 *      again.
 *
 * Why paste-the-code rather than scheme intent-filter: the public
 * Claude Code OAuth client is registered with `console.anthropic.com/
 * oauth/code/callback`, not a `storyvox://` custom scheme. The browser
 * lands on Anthropic's callback page which renders the code in a
 * copy-able block; the user pastes it back. No app-side intent-filter
 * state to manage, no race between the browser callback and a stale
 * activity instance, no "Open with…" picker to confuse users.
 */
@HiltViewModel
class AnthropicTeamsSignInViewModel @Inject constructor(
    private val authApi: AnthropicTeamsAuthApi,
    private val auth: AnthropicTeamsAuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<SignInState>(SignInState.Idle)
    val state: StateFlow<SignInState> = _state.asStateFlow()

    /** Test-only seam — production reads the constant. */
    internal var clientIdOverride: String? = null
    private val clientId: String
        get() = clientIdOverride ?: AnthropicTeamsAuthConfig.DEFAULT_CLIENT_ID

    /**
     * Hold the PKCE verifier + state nonce across the browser handoff.
     * The Compose screen exists for the full flow; if the user
     * background+kills+reopens the screen, they restart with [openBrowser].
     */
    private var pendingVerifier: String? = null
    private var pendingState: String? = null

    /**
     * Generate fresh PKCE + state, transition to AwaitingCode, and
     * return the URL the caller should hand to the system browser.
     */
    fun openBrowser(): String {
        val pkce = PkcePair.generate()
        val state = randomStateNonce()
        pendingVerifier = pkce.verifier
        pendingState = state
        val url = authApi.authorizeUrl(
            clientId = clientId,
            scopes = AnthropicTeamsAuthConfig.DEFAULT_SCOPES,
            challenge = pkce.challenge,
            state = state,
        )
        _state.value = SignInState.AwaitingCode(authorizeUrl = url)
        return url
    }

    /**
     * Exchange the pasted code for a bearer + refresh.
     *
     * The string Anthropic renders on the `console.anthropic.com/oauth/code/callback`
     * page is `<code>#<state>` — the authorization code, a literal `#`, and the
     * state nonce that was passed on the authorize URL. Claude Code's CLI does
     * the same split (see Anthropic's `Bw8` manual-flow handler), and the token
     * endpoint rejects the request as `invalid_grant` ("Invalid 'code' in request")
     * if the unsplit string is sent as `code` — #692.
     *
     * We split on the first `#`, send only the prefix as `code`, and prefer the
     * state from the pasted string over the stored nonce (matches Claude Code's
     * shape; the two must match anyway since the browser round-trip echoes our
     * nonce back). Whitespace is trimmed defensively for users pasting via a
     * screen-reader's copy-with-trailing-newline behaviour.
     */
    fun submitCode(rawCode: String) {
        val trimmed = rawCode.trim().takeIf { it.isNotBlank() }
            ?: run {
                _state.value = SignInState.Failure(
                    message = "Paste the authorization code Anthropic showed you in the browser.",
                    retryable = true,
                )
                return
            }
        val verifier = pendingVerifier ?: run {
            _state.value = SignInState.Failure(
                message = "Sign-in state missing — tap \"Open browser\" again.",
                retryable = true,
            )
            return
        }
        val code = trimmed.substringBefore('#')
        val pastedState = trimmed.substringAfter('#', missingDelimiterValue = "")
        if (code.isBlank()) {
            _state.value = SignInState.Failure(
                message = "That looks like just the `#state` half — copy the whole code from the Anthropic page.",
                retryable = true,
            )
            return
        }
        val stateNonce = pastedState.ifBlank { pendingState.orEmpty() }
        _state.value = SignInState.Capturing
        viewModelScope.launch {
            when (val r = authApi.exchangeCode(
                clientId = clientId,
                code = code,
                verifier = verifier,
                state = stateNonce,
            )) {
                is TokenResult.Success -> {
                    val now = System.currentTimeMillis()
                    auth.captureSession(
                        bearer = r.accessToken,
                        refreshToken = r.refreshToken,
                        expiresAtEpochMillis = now + r.expiresInSeconds * 1000L,
                        scopes = r.scopes.ifBlank {
                            AnthropicTeamsAuthConfig.DEFAULT_SCOPES
                        },
                    )
                    pendingVerifier = null
                    pendingState = null
                    _state.value = SignInState.Captured
                }
                is TokenResult.InvalidGrant -> {
                    _state.value = SignInState.Failure(
                        message = "Anthropic rejected that code — get a fresh one and try again. " +
                            (r.message ?: ""),
                        retryable = true,
                    )
                }
                is TokenResult.AnthropicError -> {
                    _state.value = SignInState.Failure(
                        message = "Anthropic error: ${r.code}${r.message?.let { " — $it" } ?: ""}",
                        retryable = true,
                    )
                }
                is TokenResult.NetworkError -> {
                    _state.value = SignInState.Failure(
                        message = "Network error — ${r.cause.message ?: "request failed"}",
                        retryable = true,
                    )
                }
                is TokenResult.HttpError -> {
                    _state.value = SignInState.Failure(
                        message = "Anthropic returned HTTP ${r.code} — ${r.message}",
                        retryable = true,
                    )
                }
                is TokenResult.MalformedResponse -> {
                    _state.value = SignInState.Failure(
                        message = "Couldn't read Anthropic's response — ${r.message}",
                        retryable = true,
                    )
                }
            }
        }
    }

    fun cancel() {
        pendingVerifier = null
        pendingState = null
        _state.value = SignInState.Idle
    }

    /** Generates a 32-byte URL-safe Base64 nonce — enough entropy
     *  to make `state` collisions cosmologically unlikely. */
    private fun randomStateNonce(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.URL_SAFE or
                android.util.Base64.NO_PADDING or
                android.util.Base64.NO_WRAP,
        )
    }
}

sealed class SignInState {
    object Idle : SignInState()
    /** Browser handoff in progress. The screen renders the URL the
     *  user is about to land on (or just landed on) and the paste
     *  field for the resulting code. */
    data class AwaitingCode(val authorizeUrl: String) : SignInState()
    /** Token exchange in flight. */
    object Capturing : SignInState()
    /** Bearer + refresh persisted; the screen pops back. */
    object Captured : SignInState()
    data class Failure(val message: String, val retryable: Boolean) : SignInState()
}
