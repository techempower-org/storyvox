package `in`.jphe.storyvox.llm.auth

import `in`.jphe.storyvox.llm.LlmError
import `in`.jphe.storyvox.llm.ProviderId
import `in`.jphe.storyvox.llm.di.LlmHttp
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import `in`.jphe.storyvox.llm.provider.executeAsync

/**
 * Issue #219 — RFC 7523 JWT-bearer-grant token source for Google
 * service-account auth. Mints OAuth2 access tokens from a parsed
 * [GoogleServiceAccount] and caches them in-process until ~5 min
 * before they expire.
 *
 * **Single instance per provider** — bound `@Singleton` in
 * [in.jphe.storyvox.llm.di.LlmModule] so the cache is shared across
 * all callers of [VertexProvider] within a single process. The cache
 * key is the SA's `clientEmail` + `privateKeyId` (so swapping the
 * uploaded SA JSON in Settings invalidates the cache).
 *
 * Thread safety: the refresh path is guarded by a [Mutex] so two
 * concurrent stream() calls landing during expiry don't burn two
 * tokens. The fast-path read (cache still valid) is lock-free —
 * just a volatile-style snapshot read.
 *
 * **No google-auth dependency.** Just OkHttp + the in-process JWT
 * signer in [GoogleJwtSigner].
 */
@Singleton
open class GoogleOAuthTokenSource(
    @LlmHttp private val http: OkHttpClient,
    private val clock: () -> Long,
) {
    /**
     * Hilt-friendly constructor — the `clock` parameter defaults to
     * the wall-clock reader. Kotlin's compiler default-args trick
     * isn't seen by Dagger (no `@JvmOverloads`), so we spell out the
     * production binding here. Tests use the primary constructor
     * with an injected clock for deterministic refresh timing.
     */
    @Inject
    constructor(@LlmHttp http: OkHttpClient) : this(http, { System.currentTimeMillis() / 1000L })

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val mutex = Mutex()

    /** Atomic cache entry. Volatile so the fast-path read sees writes
     *  from other threads without a lock. Null means "no token minted
     *  yet" — the next call mints one. */
    @Volatile
    private var cached: CachedToken? = null

    /**
     * Return a valid access token for [sa], minting a new one if the
     * cache is empty, the SA identity changed, or the existing token
     * expires within [REFRESH_SKEW_SECONDS].
     */
    open suspend fun accessToken(sa: GoogleServiceAccount): String {
        // Fast-path: cache hit, same SA, not within skew of expiry.
        val now = clock()
        cached?.let { c ->
            if (c.matches(sa) && c.isStillValid(now)) return c.accessToken
        }
        // Slow-path: contend for the refresh lock. Re-check inside
        // the lock so a parallel caller that already refreshed
        // doesn't trigger a second exchange.
        return mutex.withLock {
            val now2 = clock()
            cached?.let { c ->
                if (c.matches(sa) && c.isStillValid(now2)) return@withLock c.accessToken
            }
            val fresh = mint(sa, now2)
            cached = fresh
            fresh.accessToken
        }
    }

    /** Drop the cached token. Called by the credentials store when
     *  the user clears or replaces the SA JSON in Settings. */
    open fun invalidate() {
        cached = null
    }

    /** Sign a JWT, exchange it at [GoogleServiceAccount.tokenUri],
     *  return the parsed token + computed expiry. */
    private suspend fun mint(sa: GoogleServiceAccount, nowSeconds: Long): CachedToken {
        val jwt = GoogleJwtSigner.sign(
            sa = sa,
            scope = GoogleServiceAccount.SCOPE_CLOUD_PLATFORM,
            nowSecondsSinceEpoch = nowSeconds,
        )
        val form = FormBody.Builder()
            .add("grant_type", JWT_BEARER_GRANT)
            .add("assertion", jwt)
            .build()
        val request = Request.Builder()
            .url(sa.tokenUri)
            .header("content-type", "application/x-www-form-urlencoded")
            .post(form)
            .build()
        val resp = try {
            http.newCall(request).executeAsync()
        } catch (e: IOException) {
            throw LlmError.Transport(ProviderId.Vertex, e)
        }
        resp.use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                // OAuth error responses are JSON-shaped { error,
                // error_description }; we surface the raw body
                // truncated so a misconfigured SA (revoked key,
                // wrong project, missing IAM role) gives the user
                // something to grep.
                throw LlmError.AuthFailed(
                    ProviderId.Vertex,
                    "Token exchange failed (${r.code}): ${body.take(256)}",
                )
            }
            val parsed = try {
                json.decodeFromString(TokenResponse.serializer(), body)
            } catch (e: Exception) {
                throw LlmError.Transport(
                    ProviderId.Vertex,
                    IOException("Malformed token response: ${e.message}", e),
                )
            }
            return CachedToken(
                accessToken = parsed.accessToken,
                expiresAtSeconds = nowSeconds + parsed.expiresIn,
                clientEmail = sa.clientEmail,
                privateKeyId = sa.privateKeyId,
            )
        }
    }

    /** Immutable cache entry. `matches()` keys on identity so a
     *  swapped SA JSON invalidates implicitly. */
    private data class CachedToken(
        val accessToken: String,
        val expiresAtSeconds: Long,
        val clientEmail: String,
        val privateKeyId: String?,
    ) {
        fun matches(sa: GoogleServiceAccount): Boolean =
            clientEmail == sa.clientEmail && privateKeyId == sa.privateKeyId

        fun isStillValid(nowSeconds: Long): Boolean =
            nowSeconds + REFRESH_SKEW_SECONDS < expiresAtSeconds
    }

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        /** Lifetime in seconds. Google returns 3599 / 3600 in practice. */
        @SerialName("expires_in") val expiresIn: Long,
        @SerialName("token_type") val tokenType: String = "Bearer",
    )

    companion object {
        /** RFC 7523 §2.1 grant-type identifier. */
        const val JWT_BEARER_GRANT =
            "urn:ietf:params:oauth:grant-type:jwt-bearer"

        /** Refresh `expires_in - REFRESH_SKEW_SECONDS` early — gives
         *  in-flight requests a safe window even on a sluggish
         *  device clock or a slow token exchange. 5 minutes per the
         *  spec; long enough to outlast a paused stream but short
         *  enough to keep refresh churn low. */
        const val REFRESH_SKEW_SECONDS = 5 * 60L
    }
}
