package `in`.jphe.storyvox.llm.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Parsed Google Cloud service-account JSON — the file a user downloads
 * from `IAM & Admin → Service Accounts → Keys → Add Key → JSON` in the
 * GCP console.
 *
 * Issue #219 — alternative to the BYOK API-key path for Vertex AI. The
 * user uploads this JSON via Settings → AI → Vertex; we encrypt-at-rest
 * the raw JSON text (so re-parsing is always available) and use the
 * embedded RSA private key to sign a short-lived JWT, which we trade
 * at `token_uri` for an OAuth2 access token (RFC 7523 — JWT-bearer
 * grant). The access token then rides on every Vertex request as
 * `Authorization: Bearer …`.
 *
 * **No google-auth-library dependency.** The JWT signing is ~30 lines
 * of `java.security` (RSA-SHA256 over PKCS#8) and the token exchange
 * is one `POST application/x-www-form-urlencoded` — the rest of the
 * google-auth library (transport adapters, refresh handlers, credential
 * source chains) is dead weight for a single-SA, single-scope client.
 *
 * **Privacy posture.** We keep `clientEmail`, `projectId`, `tokenUri`
 * — they're non-secret identifiers. `privateKey` is the secret; the
 * encrypted-prefs entry that stores the JSON treats the whole blob as
 * sensitive so the PEM never leaks. We never log any field; the OkHttp
 * logging interceptor in production redacts the `Authorization` header
 * the same way it does the existing API-key one.
 */
@Serializable
data class GoogleServiceAccount(
    /** Always `"service_account"` for the JSON keys we accept. We
     *  reject any other `type` (e.g. `authorized_user`, `external_account`)
     *  during parse rather than surface a confusing OAuth failure
     *  later. */
    val type: String,
    @SerialName("project_id") val projectId: String,
    @SerialName("private_key_id") val privateKeyId: String? = null,
    /** PKCS#8 PEM — starts with `-----BEGIN PRIVATE KEY-----`. */
    @SerialName("private_key") val privateKey: String,
    @SerialName("client_email") val clientEmail: String,
    @SerialName("client_id") val clientId: String? = null,
    /** OAuth2 token endpoint — universally
     *  `https://oauth2.googleapis.com/token` in production but
     *  configurable so tests can point at MockWebServer. */
    @SerialName("token_uri") val tokenUri: String,
) {
    companion object {
        /** OAuth scope that grants access to Vertex AI / Generative
         *  Language API. The single broad scope matches what gcloud's
         *  `application-default credentials` flow grants. */
        const val SCOPE_CLOUD_PLATFORM =
            "https://www.googleapis.com/auth/cloud-platform"

        private val parser = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        /**
         * Parse + validate. Throws [IllegalArgumentException] if any
         * required field is missing or `type` isn't `service_account`.
         *
         * Why not a sealed `Result`? Settings UI calls this from a
         * SAF-picker callback; an exception with a human-readable
         * message is exactly what the toast wants to display. The
         * caller catches and renders.
         */
        fun parse(json: String): GoogleServiceAccount {
            val sa = try {
                parser.decodeFromString(serializer(), json)
            } catch (e: Exception) {
                throw IllegalArgumentException(
                    "Not a valid JSON file: ${e.message ?: "parse error"}",
                    e,
                )
            }
            require(sa.type == "service_account") {
                "Expected type=service_account, got type=${sa.type}. " +
                    "Make sure you exported a service-account key, not " +
                    "an `authorized_user` or `external_account` JSON."
            }
            require(sa.privateKey.contains("BEGIN PRIVATE KEY")) {
                "private_key field doesn't look like a PKCS#8 PEM " +
                    "(missing BEGIN PRIVATE KEY marker)."
            }
            require(sa.clientEmail.contains('@')) {
                "client_email doesn't look like an email address."
            }
            // We don't enforce https on token_uri at parse time —
            // MockWebServer-backed tests need http://localhost URIs,
            // and the production OAuth library doesn't validate the
            // scheme either (it trusts the SA JSON, which itself came
            // from an authenticated GCP console download). Real
            // production SAs always carry https URIs; an http one
            // would fail the OAuth round-trip naturally.
            require(sa.tokenUri.isNotBlank()) {
                "token_uri is empty."
            }
            return sa
        }
    }
}
