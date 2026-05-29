package `in`.jphe.storyvox.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import `in`.jphe.storyvox.ui.R

/**
 * Categorized friendly-error kinds. Each kind maps 1:1 to a
 * `friendly_error_*` string resource; `Raw` carries the original
 * message through when no known pattern matches.
 *
 * Issue #789 — split out from `friendlyErrorMessage` so the pattern
 * matching can be unit-tested without a Compose harness while the
 * user-facing copy lives in `strings.xml` for i18n.
 */
enum class FriendlyErrorKind {
    Generic,
    NetworkLost,
    CloudflareChallenge,
    RateLimited,
    ServerError,
    AuthFailed,
    NotConfigured,
    UnexpectedResponse,
    Raw,
}

/**
 * Pure-Kotlin categorizer. Returns the matched [FriendlyErrorKind] and
 * — when the kind is [FriendlyErrorKind.Raw] — the original message
 * that should be surfaced as-is. For all other kinds the second
 * element of the pair is `null`; the caller resolves a string resource.
 *
 * Pre-#171 / pre-#789 history: error states across FictionDetail,
 * Browse, and source-mempalace surfaced strings like
 * `HTTP 0: Network timeout: timeout` and
 * `java.io.IOException: Palace host not configured` directly to the
 * user. The technical strings are useful for bug reports but read as
 * scary nonsense to a reader who just wanted to listen to a chapter.
 *
 * The mapping is intentionally lenient — we'd rather show one generic
 * "the realm is unreachable" than ten specific copies that drift
 * apart over time. Capture the technical detail in logcat for bug
 * reports; keep the user-facing copy short and recoverable-sounding.
 */
fun categorizeFriendlyError(raw: String?): Pair<FriendlyErrorKind, String?> {
    // #935 — blank input (whitespace-only or empty) used to fall through
    // to the `Raw to s` branch and surface an empty user-facing message.
    // Treat it the same as null: there's nothing actionable to show, so
    // route to the generic fallback copy.
    val s = raw?.takeIf { it.isNotBlank() } ?: return FriendlyErrorKind.Generic to null
    val lower = s.lowercase()
    return when {
        // Network timeouts / offline / DNS — by far the most common.
        // "HTTP 0:" is OkHttp's shape when the connection never opened.
        s.startsWith("HTTP 0") ||
        lower.contains("network timeout") ||
        lower.contains("sockettimeout") ||
        lower.contains("unknownhostexception") ||
        lower.contains("connection refused") ||
        lower.contains("failed to connect") ->
            FriendlyErrorKind.NetworkLost to null

        // Cloudflare or similar bot-challenge — we couldn't transparently
        // solve a challenge. User can retry; sometimes resolves on its own.
        lower.contains("cloudflare") ||
        lower.contains("challenge") ->
            FriendlyErrorKind.CloudflareChallenge to null

        // Rate-limited (our own gate or the server's 429).
        lower.contains("rate limited") ||
        lower.contains("too many requests") ||
        lower.contains("retry after") ->
            FriendlyErrorKind.RateLimited to null

        // Server errors. Don't blame the user.
        s.contains("HTTP 5") || lower.contains("server error") ->
            FriendlyErrorKind.ServerError to null

        // Auth — but only for fetch contexts (playback has its own typed
        // PlaybackError.AzureAuthFailed path).
        s.contains("HTTP 401") || s.contains("HTTP 403") ||
        lower.contains("authentication") ->
            FriendlyErrorKind.AuthFailed to null

        // Specific config gaps surfaced as IOException — Memory Palace
        // is the canonical example (PalaceDaemonApi throws on empty host).
        lower.contains("host not configured") ||
        lower.contains("not configured") ->
            FriendlyErrorKind.NotConfigured to null

        // Other 4xx — usually means something the user can't fix.
        s.contains("HTTP 4") ->
            FriendlyErrorKind.UnexpectedResponse to null

        // Fall back to the raw string. Either the source layer already
        // produced user-friendly copy, or we don't have a mapping yet.
        // Better to show something specific than to swallow detail.
        else -> FriendlyErrorKind.Raw to s
    }
}

/**
 * Maps a raw exception string to user-facing copy that doesn't leak
 * the shape of the underlying network stack. Call sites should pass
 * the raw message through this composable before setting it on
 * [ErrorBlock].
 *
 * Returns the raw message unchanged when no known pattern matches
 * (`FriendlyErrorKind.Raw`).
 */
@Composable
fun friendlyErrorMessage(raw: String?): String {
    val (kind, fallback) = categorizeFriendlyError(raw)
    return when (kind) {
        FriendlyErrorKind.Generic -> stringResource(R.string.friendly_error_generic)
        FriendlyErrorKind.NetworkLost -> stringResource(R.string.friendly_error_network_lost)
        FriendlyErrorKind.CloudflareChallenge -> stringResource(R.string.friendly_error_cloudflare_challenge)
        FriendlyErrorKind.RateLimited -> stringResource(R.string.friendly_error_rate_limited)
        FriendlyErrorKind.ServerError -> stringResource(R.string.friendly_error_server_error)
        FriendlyErrorKind.AuthFailed -> stringResource(R.string.friendly_error_auth_failed)
        FriendlyErrorKind.NotConfigured -> stringResource(R.string.friendly_error_not_configured)
        FriendlyErrorKind.UnexpectedResponse -> stringResource(R.string.friendly_error_unexpected_response)
        FriendlyErrorKind.Raw -> fallback ?: stringResource(R.string.friendly_error_generic)
    }
}
