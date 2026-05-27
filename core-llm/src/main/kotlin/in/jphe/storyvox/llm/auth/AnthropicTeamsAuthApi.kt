package `in`.jphe.storyvox.llm.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Anthropic Teams OAuth 2.0 client (#181).
 *
 * Authorization Code + PKCE / S256 against `claude.ai/oauth/authorize`
 * and `console.anthropic.com/v1/oauth/token`. Mirrors the layered shape
 * of `:source-github`'s `DeviceFlowApi` — a thin HTTP wrapper that
 * exposes typed result variants; the calling ViewModel owns the state
 * machine (browser handoff, paste-code, exchange).
 *
 * Flow:
 *   1. [authorizeUrl] builds the URL the browser opens.
 *   2. User authorizes at `claude.ai`; Anthropic shows an authorization
 *      code on the redirect-page.
 *   3. User pastes the code back into storyvox.
 *   4. [exchangeCode] swaps the code (+ PKCE verifier) for a bearer + refresh.
 *   5. On expiry, [refreshToken] swaps the refresh token for a new bearer.
 *
 * Construction takes a vanilla [OkHttpClient] (no Bearer interceptor) —
 * the token endpoint takes only a JSON form body and responds with the
 * token; hitting it with an existing Authorization header would have
 * undefined behaviour.
 */
@Singleton
open class AnthropicTeamsAuthApi @Inject constructor(
    private val httpClient: OkHttpClient,
) {

    open val authorizeBaseUrl: String = AnthropicTeamsAuthConfig.AUTHORIZE_URL
    open val tokenUrl: String = AnthropicTeamsAuthConfig.TOKEN_URL

    /**
     * Build the URL the browser should open. The `state` parameter is
     * an opaque random nonce the caller stores and verifies on the
     * round-trip — same shape Anthropic's official client uses. The
     * caller passes back the pair `(state, challenge)` to verify the
     * redirect; PKCE [verifier] is held privately by the caller and
     * presented at exchange time.
     */
    open fun authorizeUrl(
        clientId: String,
        scopes: String,
        challenge: String,
        state: String,
    ): String {
        val params = listOf(
            "code" to "true",
            "client_id" to clientId,
            "response_type" to "code",
            "redirect_uri" to AnthropicTeamsAuthConfig.REDIRECT_URI,
            "scope" to scopes,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "state" to state,
        )
        val query = params.joinToString("&") { (k, v) ->
            "$k=${java.net.URLEncoder.encode(v, "UTF-8")}"
        }
        return "$authorizeBaseUrl?$query"
    }

    /** Step 4 — exchange the pasted authorization code for a bearer + refresh. */
    open suspend fun exchangeCode(
        clientId: String,
        code: String,
        verifier: String,
        state: String,
    ): TokenResult = withContext(Dispatchers.IO) {
        val body = ExchangeRequest(
            grantType = "authorization_code",
            code = code,
            redirectUri = AnthropicTeamsAuthConfig.REDIRECT_URI,
            clientId = clientId,
            codeVerifier = verifier,
            state = state,
        )
        val payload = JSON.encodeToString(ExchangeRequest.serializer(), body)
        post(payload)
    }

    /** Step 5 — refresh the bearer using the stored refresh token. */
    open suspend fun refreshToken(
        clientId: String,
        refreshToken: String,
    ): TokenResult = withContext(Dispatchers.IO) {
        val body = RefreshRequest(
            grantType = "refresh_token",
            refreshToken = refreshToken,
            clientId = clientId,
        )
        val payload = JSON.encodeToString(RefreshRequest.serializer(), body)
        post(payload)
    }

    private suspend fun post(payload: String): TokenResult {
        val req = Request.Builder()
            .url(tokenUrl)
            .post(payload.toRequestBody(JSON_MEDIA))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("User-Agent", USER_AGENT)
            .build()
        val response = try {
            httpClient.newCall(req).await()
        } catch (e: IOException) {
            return TokenResult.NetworkError(e)
        }
        return response.use { r ->
            val raw = r.body?.string().orEmpty()
            val parsed = try {
                JSON.decodeFromString(TokenResponse.serializer(), raw)
            } catch (e: Throwable) {
                return@use if (r.isSuccessful) {
                    TokenResult.MalformedResponse(e.message ?: "parse failed")
                } else {
                    TokenResult.HttpError(r.code, r.message)
                }
            }
            when {
                parsed.accessToken != null -> TokenResult.Success(
                    accessToken = parsed.accessToken,
                    refreshToken = parsed.refreshToken,
                    expiresInSeconds = parsed.expiresIn ?: 0,
                    scopes = parsed.scope.orEmpty(),
                    tokenType = parsed.tokenType.orEmpty(),
                )
                parsed.error == "invalid_grant" -> TokenResult.InvalidGrant(
                    message = parsed.errorDescription,
                )
                parsed.error != null -> TokenResult.AnthropicError(
                    code = parsed.error,
                    message = parsed.errorDescription,
                )
                !r.isSuccessful -> TokenResult.HttpError(r.code, r.message)
                else -> TokenResult.MalformedResponse(
                    "no access_token and no error in response",
                )
            }
        }
    }

    @Serializable
    private data class ExchangeRequest(
        @SerialName("grant_type") val grantType: String,
        val code: String,
        @SerialName("redirect_uri") val redirectUri: String,
        @SerialName("client_id") val clientId: String,
        @SerialName("code_verifier") val codeVerifier: String,
        val state: String,
    )

    @Serializable
    private data class RefreshRequest(
        @SerialName("grant_type") val grantType: String,
        @SerialName("refresh_token") val refreshToken: String,
        @SerialName("client_id") val clientId: String,
    )

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String? = null,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("token_type") val tokenType: String? = null,
        @SerialName("expires_in") val expiresIn: Long? = null,
        val scope: String? = null,
        val error: String? = null,
        @SerialName("error_description") val errorDescription: String? = null,
    )

    companion object {
        const val USER_AGENT: String = "storyvox/0.4 (+https://github.com/jphein/storyvox)"
        private val JSON_MEDIA = "application/json".toMediaType()
        private val JSON: Json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
        }
    }
}

/** Outcome of [AnthropicTeamsAuthApi.exchangeCode] / [AnthropicTeamsAuthApi.refreshToken]. */
sealed class TokenResult {
    data class Success(
        val accessToken: String,
        /** Refresh token — null on refresh-flow if Anthropic doesn't rotate it. */
        val refreshToken: String?,
        /** Lifetime in seconds; 0 means "didn't say." */
        val expiresInSeconds: Long,
        val scopes: String,
        val tokenType: String,
    ) : TokenResult()

    /**
     * `invalid_grant` — refresh token was revoked / expired, or the
     * authorization code was reused. Caller surfaces "Session expired —
     * sign in again."
     */
    data class InvalidGrant(val message: String?) : TokenResult()

    /** Catch-all Anthropic error code (e.g. `invalid_client`, `invalid_request`). */
    data class AnthropicError(val code: String, val message: String?) : TokenResult()

    data class NetworkError(val cause: Throwable) : TokenResult()
    data class HttpError(val code: Int, val message: String) : TokenResult()
    data class MalformedResponse(val message: String) : TokenResult()
}

/** Suspending bridge over OkHttp's enqueue → callback API. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }

        override fun onFailure(call: Call, e: IOException) {
            if (cont.isCancelled) return
            cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation {
        runCatching { cancel() }
    }
}
