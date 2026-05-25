package `in`.jphe.storyvox.source.github.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * GitHub Device Flow client (RFC 8628 + GitHub-specific extensions).
 *
 * Issue #91. Spec: docs/superpowers/specs/2026-05-08-github-oauth-design.md
 *
 * Two endpoints, both on the **website** domain (not `api.github.com`):
 *
 * - `POST https://github.com/login/device/code` — request a device + user
 *   code. Returns `verification_uri`, `user_code`, `device_code`,
 *   `expires_in` (typically 900s), `interval` (typically 5s).
 * - `POST https://github.com/login/oauth/access_token` — poll for the
 *   access token. Returns either `{access_token, scope, token_type}` or
 *   `{error}`. Errors during polling: `authorization_pending`, `slow_down`,
 *   `expired_token`, `access_denied`, plus a few bug-class errors.
 *
 * Why not auto-poll inside this class: polling state — the current
 * interval, the `expires_in` deadline, cancellation on user back-press —
 * lives in the ViewModel where the UI state machine sees it. This class
 * exposes the raw HTTP wrappers; [GitHubSignInViewModel] owns the
 * `slow_down` / `authorization_pending` / `expired_token` orchestration.
 *
 * Construction: pass a *vanilla* [OkHttpClient] (no auth interceptor) —
 * the device-code and access-token endpoints take only `client_id` in
 * the form body, never an Authorization header. Re-using the GitHub-
 * source's authed client would attach a Bearer header that GitHub's
 * device-code endpoint ignores at best, rejects at worst.
 */
@Singleton
open class DeviceFlowApi @Inject constructor(
    private val httpClient: OkHttpClient,
) {

    /**
     * Override hook for tests. Production uses [GitHubAuthConfig.DEVICE_CODE_URL].
     */
    open val deviceCodeUrl: String = GitHubAuthConfig.DEVICE_CODE_URL
    open val accessTokenUrl: String = GitHubAuthConfig.ACCESS_TOKEN_URL

    /**
     * Step 1 — request a device + user code.
     *
     * Returns [DeviceCodeResult.Success] with the user-visible code on a
     * 200, or one of the error variants on transport / GitHub failure.
     *
     * `open` so JVM unit tests in `:app` can override with canned results
     * without a MockWebServer.
     */
    open suspend fun requestDeviceCode(
        clientId: String,
        scopes: String,
    ): DeviceCodeResult = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("scope", scopes)
            .build()
        val req = Request.Builder()
            .url(deviceCodeUrl)
            .post(body)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .build()
        val response = try {
            httpClient.newCall(req).await()
        } catch (e: IOException) {
            return@withContext DeviceCodeResult.NetworkError(e)
        }
        response.use { r ->
            if (!r.isSuccessful) {
                return@use DeviceCodeResult.HttpError(r.code, r.message)
            }
            val raw = r.body?.string().orEmpty()
            try {
                val parsed = JSON.decodeFromString(DeviceCodeResponse.serializer(), raw)
                if (parsed.error != null) {
                    return@use DeviceCodeResult.GitHubError(parsed.error, parsed.errorDescription)
                }
                DeviceCodeResult.Success(
                    deviceCode = parsed.deviceCode
                        ?: return@use DeviceCodeResult.MalformedResponse("missing device_code"),
                    userCode = parsed.userCode
                        ?: return@use DeviceCodeResult.MalformedResponse("missing user_code"),
                    verificationUri = parsed.verificationUri
                        ?: GitHubAuthConfig.VERIFICATION_URL,
                    verificationUriComplete = parsed.verificationUriComplete,
                    expiresInSeconds = parsed.expiresIn ?: 900,
                    intervalSeconds = parsed.interval ?: 5,
                )
            } catch (e: Throwable) {
                DeviceCodeResult.MalformedResponse(e.message ?: "parse failed")
            }
        }
    }

    /**
     * Step 2 — single poll for the access token. The caller is responsible
     * for the polling cadence (interval, slow_down handling, expires_in
     * cap, cancellation).
     *
     * Returns:
     * - [TokenPollResult.Success] with the access token + granted scope,
     * - [TokenPollResult.Pending] for `authorization_pending` (caller polls again),
     * - [TokenPollResult.SlowDown] for `slow_down` (caller bumps interval +5s),
     * - [TokenPollResult.Expired] for `expired_token` (user too slow; restart),
     * - [TokenPollResult.Denied] for `access_denied` (user cancelled in browser),
     * - [TokenPollResult.GitHubError] for any other GitHub error code,
     * - [TokenPollResult.NetworkError] / [TokenPollResult.HttpError] / [TokenPollResult.MalformedResponse].
     */
    open suspend fun pollAccessToken(
        clientId: String,
        deviceCode: String,
    ): TokenPollResult = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("device_code", deviceCode)
            .add("grant_type", DEVICE_CODE_GRANT)
            .build()
        val req = Request.Builder()
            .url(accessTokenUrl)
            .post(body)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .build()
        val response = try {
            httpClient.newCall(req).await()
        } catch (e: IOException) {
            return@withContext TokenPollResult.NetworkError(e)
        }
        response.use { r ->
            // Note: GitHub returns HTTP 200 with `{error: "..."}` for
            // *every* polling-state error (authorization_pending, slow_down,
            // expired_token, access_denied, unsupported_grant_type, etc.) —
            // it's only HTTP-non-200 if the request itself was malformed.
            // So we parse the body before checking response.code.
            val raw = r.body?.string().orEmpty()
            val parsed = try {
                JSON.decodeFromString(AccessTokenResponse.serializer(), raw)
            } catch (e: Throwable) {
                return@use if (r.isSuccessful) {
                    TokenPollResult.MalformedResponse(e.message ?: "parse failed")
                } else {
                    TokenPollResult.HttpError(r.code, r.message)
                }
            }
            when {
                parsed.accessToken != null -> TokenPollResult.Success(
                    token = parsed.accessToken,
                    scopes = parsed.scope.orEmpty(),
                    tokenType = parsed.tokenType.orEmpty(),
                )
                parsed.error == "authorization_pending" -> TokenPollResult.Pending
                parsed.error == "slow_down" -> TokenPollResult.SlowDown
                parsed.error == "expired_token" -> TokenPollResult.Expired
                parsed.error == "access_denied" -> TokenPollResult.Denied
                parsed.error != null -> TokenPollResult.GitHubError(
                    code = parsed.error,
                    message = parsed.errorDescription,
                )
                else -> TokenPollResult.MalformedResponse(
                    "no access_token and no error in response",
                )
            }
        }
    }

    @Serializable
    private data class DeviceCodeResponse(
        @SerialName("device_code") val deviceCode: String? = null,
        @SerialName("user_code") val userCode: String? = null,
        @SerialName("verification_uri") val verificationUri: String? = null,
        @SerialName("verification_uri_complete") val verificationUriComplete: String? = null,
        @SerialName("expires_in") val expiresIn: Int? = null,
        @SerialName("interval") val interval: Int? = null,
        // GitHub returns `error` even on the device-code endpoint when the
        // client_id is wrong / the OAuth app doesn't have Device Flow enabled.
        val error: String? = null,
        @SerialName("error_description") val errorDescription: String? = null,
    )

    @Serializable
    private data class AccessTokenResponse(
        @SerialName("access_token") val accessToken: String? = null,
        val scope: String? = null,
        @SerialName("token_type") val tokenType: String? = null,
        val error: String? = null,
        @SerialName("error_description") val errorDescription: String? = null,
    )

    companion object {
        const val DEVICE_CODE_GRANT: String = "urn:ietf:params:oauth:grant-type:device_code"
        const val USER_AGENT: String = "storyvox/0.4 (+https://github.com/jphein/storyvox)"
        // ignoreUnknownKeys: GitHub freely adds new fields; don't break on them.
        // explicitNulls = false: keeps the wire format permissive on optional values.
        private val JSON: Json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
        }
    }
}

/** Outcome of [DeviceFlowApi.requestDeviceCode]. */
sealed class DeviceCodeResult {
    data class Success(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val verificationUriComplete: String?,
        val expiresInSeconds: Int,
        val intervalSeconds: Int,
    ) : DeviceCodeResult()
    /** Request never reached GitHub (DNS, TLS, dropped connection, ...). */
    data class NetworkError(val cause: Throwable) : DeviceCodeResult()
    /** GitHub answered with a non-2xx HTTP status. */
    data class HttpError(val code: Int, val message: String) : DeviceCodeResult()
    /**
     * GitHub answered 200 but the JSON body carried an `error` field —
     * typically `device_flow_disabled` (OAuth app misconfigured) or a
     * client_id format error.
     */
    data class GitHubError(val code: String, val description: String?) : DeviceCodeResult()
    /** 200 OK but the body was missing required fields or didn't parse. */
    data class MalformedResponse(val message: String) : DeviceCodeResult()
}

/** Outcome of [DeviceFlowApi.pollAccessToken]. */
sealed class TokenPollResult {
    data class Success(
        val token: String,
        /** Space-separated list of granted scopes (may differ from requested). */
        val scopes: String,
        val tokenType: String,
    ) : TokenPollResult()

    /** User hasn't confirmed in the browser yet. Caller polls again at the same interval. */
    data object Pending : TokenPollResult()

    /** GitHub thinks we're polling too fast. Caller bumps interval by +5s and retries. */
    data object SlowDown : TokenPollResult()

    /** Device code expired (>900s since issuance). Caller restarts the flow with a fresh code. */
    data object Expired : TokenPollResult()

    /** User clicked "Cancel" / "Deny" in the browser. Caller surfaces the cancellation. */
    data object Denied : TokenPollResult()

    /**
     * Catch-all for other GitHub-issued errors. [code] examples:
     * `unsupported_grant_type`, `incorrect_client_credentials`,
     * `incorrect_device_code`, `device_flow_disabled`. None are recoverable
     * by polling; they all bubble up as a generic "Sign-in failed" with a
     * report path.
     */
    data class GitHubError(val code: String, val message: String?) : TokenPollResult()

    data class NetworkError(val cause: Throwable) : TokenPollResult()
    data class HttpError(val code: Int, val message: String) : TokenPollResult()
    data class MalformedResponse(val message: String) : TokenPollResult()
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
