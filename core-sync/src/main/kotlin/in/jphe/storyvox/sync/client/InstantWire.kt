package `in`.jphe.storyvox.sync.client

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire-format data classes for the InstantDB runtime API.
 *
 * We're not using the JS SDK (no Kotlin port exists). These types mirror the
 * actual JSON the public runtime/auth HTTP endpoints take and return,
 * extracted from instantdb/instant `client/packages/core/src/authAPI.ts`.
 * Field naming uses `@SerialName` because Instant's API uses kebab-case for
 * some fields (`app-id`, `refresh-token`) and snake_case for others — we
 * keep idiomatic Kotlin camelCase on this side and let the serializer do the
 * translation. See `docs/sync.md` for the architecture overview.
 */

/* ----- Magic-code auth (runtime/auth/send_magic_code) ----- */

@Serializable
internal data class SendMagicCodeRequest(
    @SerialName("app-id") val appId: String,
    val email: String,
)

@Serializable
internal data class SendMagicCodeResponse(
    val sent: Boolean,
)

/* ----- Verify code (runtime/auth/verify_magic_code) ----- */

@Serializable
internal data class VerifyMagicCodeRequest(
    @SerialName("app-id") val appId: String,
    val email: String,
    val code: String,
    @SerialName("refresh-token") val refreshToken: String? = null,
)

/**
 * The user envelope Instant returns from any auth verification endpoint.
 * The full record has extras (created_at, etc.) but we only consume what we
 * need — kotlinx-serialization's `ignoreUnknownKeys` (set on the Json
 * instance in [InstantClient]) drops the rest.
 */
@Serializable
data class InstantUser(
    val id: String,
    val email: String? = null,
    @SerialName("refresh_token") val refreshToken: String,
)

@Serializable
internal data class UserEnvelope(
    val user: InstantUser,
)

/* ----- Verify refresh token (runtime/auth/verify_refresh_token) ----- */

@Serializable
internal data class VerifyRefreshRequest(
    @SerialName("app-id") val appId: String,
    @SerialName("refresh-token") val refreshToken: String,
)

/* ----- Sign out (runtime/signout) ----- */

@Serializable
internal data class SignOutRequest(
    @SerialName("app_id") val appId: String,
    @SerialName("refresh_token") val refreshToken: String,
)

// Transaction steps are built inline in [HttpInstantBackend.upsert] —
// the v1 sync layer only ever issues `["update", entity, id, fields]`,
// which is a 5-line buildJsonArray that doesn't earn its own type.
