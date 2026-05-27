package `in`.jphe.storyvox.sync.client

import kotlinx.serialization.json.Json

/**
 * Thin client for the InstantDB runtime API.
 *
 * Why no third-party SDK: as of v0.5.10 of storyvox there is no first-party
 * Kotlin / Android SDK for InstantDB — only JS, React, Solid, Svelte. The
 * runtime HTTP endpoints (`/runtime/auth/...`) take just the app-id and
 * (where required) a refresh token, so we don't need server-side admin
 * credentials in the app. Endpoint shapes pulled from
 * `instantdb/instant @main client/packages/core/src/authAPI.ts`.
 *
 * v1 scope: magic-code email auth, refresh-token validation, sign-out. The
 * transact / subscribe path is layered on a separate websocket client that
 * is plugged in at the [transport] seam later — keeping auth on its own
 * makes the migration UX (sign in → start sync) trivially testable without
 * mocking the WS protocol.
 */
class InstantClient internal constructor(
    private val appId: String,
    private val apiUri: String,
    private val transport: InstantHttpTransport,
    private val json: Json = DEFAULT_JSON,
) {

    constructor(appId: String, transport: InstantHttpTransport)
            : this(appId, DEFAULT_API_URI, transport)

    /**
     * Email the user a magic code. Returns [SyncAuthResult.Ok] on a 200
     * regardless of whether the email actually maps to an existing user
     * (Instant treats unknown emails the same as known ones to avoid
     * leaking account presence; we mirror that).
     */
    suspend fun sendMagicCode(email: String): SyncAuthResult<Unit> {
        val body = json.encodeToString(
            SendMagicCodeRequest.serializer(),
            SendMagicCodeRequest(appId = appId, email = email),
        )
        val res = transport.postJson("$apiUri/runtime/auth/send_magic_code", body)
        if (!res.isSuccessful) return SyncAuthResult.Err(parseError(res, "send_magic_code"))
        // Defensive: only return Ok if the server actually said `sent: true`
        // — a 200 with `{sent: false}` would be a server-side bug, but we'd
        // rather surface it than silently proceed.
        val parsed = runCatching {
            json.decodeFromString(SendMagicCodeResponse.serializer(), res.body)
        }.getOrNull()
        return if (parsed?.sent == true) {
            SyncAuthResult.Ok(Unit)
        } else {
            SyncAuthResult.Err("Server did not confirm magic-code send")
        }
    }

    /**
     * Verify a magic code. Returns the [InstantUser] (with refresh token)
     * on success — caller is responsible for persisting the token in the
     * session store ([InstantSession]).
     */
    suspend fun verifyMagicCode(email: String, code: String): SyncAuthResult<InstantUser> {
        val body = json.encodeToString(
            VerifyMagicCodeRequest.serializer(),
            VerifyMagicCodeRequest(appId = appId, email = email, code = code),
        )
        val res = transport.postJson("$apiUri/runtime/auth/verify_magic_code", body)
        if (!res.isSuccessful) return SyncAuthResult.Err(parseError(res, "verify_magic_code"))
        val parsed = runCatching {
            json.decodeFromString(UserEnvelope.serializer(), res.body)
        }.getOrNull()
        return parsed?.let { SyncAuthResult.Ok(it.user) }
            ?: SyncAuthResult.Err("Server returned an unrecognised verify response")
    }

    /**
     * Validate that a stored refresh token is still good. Used at app
     * launch to decide whether the user is signed in. A 401 / 403 means
     * the token has been revoked or expired — caller should treat as
     * "sign-in required again" and not surface as a crash.
     */
    suspend fun verifyRefreshToken(refreshToken: String): SyncAuthResult<InstantUser> {
        val body = json.encodeToString(
            VerifyRefreshRequest.serializer(),
            VerifyRefreshRequest(appId = appId, refreshToken = refreshToken),
        )
        val res = transport.postJson("$apiUri/runtime/auth/verify_refresh_token", body)
        if (!res.isSuccessful) return SyncAuthResult.Err(parseError(res, "verify_refresh_token"))
        val parsed = runCatching {
            json.decodeFromString(UserEnvelope.serializer(), res.body)
        }.getOrNull()
        return parsed?.let { SyncAuthResult.Ok(it.user) }
            ?: SyncAuthResult.Err("Server returned an unrecognised verify response")
    }

    /** Sign the refresh token out server-side. Local state must be cleared
     *  by the caller — this only handles the network side. */
    suspend fun signOut(refreshToken: String): SyncAuthResult<Unit> {
        val body = json.encodeToString(
            SignOutRequest.serializer(),
            SignOutRequest(appId = appId, refreshToken = refreshToken),
        )
        val res = transport.postJson("$apiUri/runtime/signout", body)
        // Sign-out is best-effort: even if the network is down the user
        // should still feel "signed out" locally. So we don't propagate a
        // network-level failure as an error; only a clear server-side 4xx
        // is reported.
        return if (res.isSuccessful || res.code == 0) {
            SyncAuthResult.Ok(Unit)
        } else {
            SyncAuthResult.Err(parseError(res, "signout"))
        }
    }

    /** Pull a server-readable error string out of a non-2xx body. The
     *  Instant runtime returns `{ message: "..." }` for errors; we fall
     *  back to the raw body otherwise. */
    private fun parseError(res: TransportResult, op: String): String {
        if (res.body.isBlank()) return "$op failed (HTTP ${res.code})"
        // Quick-extract `"message":"..."` without a full DTO — the
        // server's error shape isn't part of the documented contract so
        // we treat it as opaque.
        val m = MESSAGE_REGEX.find(res.body)?.groupValues?.get(1)
        return m?.takeIf { it.isNotBlank() } ?: "$op failed: ${res.body.take(200)}"
    }

    companion object {
        /** Production InstantDB runtime URL. Pinned in code rather than
         *  exposed as a build config — there's no realistic self-hosted
         *  InstantDB deployment for end users today, and bumping requires
         *  a binary release anyway. */
        const val DEFAULT_API_URI: String = "https://api.instantdb.com"

        private val MESSAGE_REGEX = """"message"\s*:\s*"([^"]*)"""".toRegex()

        internal val DEFAULT_JSON: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
            coerceInputValues = true
        }
    }
}

/** Disjoint Ok/Err for auth + sync operations. Avoids leaking Throwables
 *  across coroutine boundaries (and double-wrap they get in Flow.catch). */
sealed interface SyncAuthResult<out T> {
    data class Ok<T>(val value: T) : SyncAuthResult<T>
    data class Err(val message: String) : SyncAuthResult<Nothing>
}
