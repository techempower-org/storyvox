package `in`.jphe.storyvox.source.slack.net

import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.source.slack.config.SlackConfig
import `in`.jphe.storyvox.source.slack.config.SlackConfigState
import `in`.jphe.storyvox.source.slack.config.SlackDefaults
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Issue #454 — minimal Slack Web API client. Four endpoints:
 *
 *  - **`GET /api/auth.test`** — verify token + return workspace
 *    metadata (team name, team id, bot user id, workspace URL).
 *    Drives the Settings "authenticated as @bot in <workspace>"
 *    confirmation.
 *  - **`GET /api/conversations.list?types=public_channel,private_channel`** —
 *    paginated channel list. Each channel becomes one storyvox
 *    fiction in the channels-as-fictions mapping; v1 filters to
 *    `is_member=true` since bots only have history access to
 *    channels they've been invited to.
 *  - **`GET /api/conversations.history?channel={C}&limit={N}&cursor={c}`** —
 *    paginated channel history. Newest-first; the source layer
 *    reverses for chronological reading.
 *  - **`GET /api/users.info?user={U}`** — resolve `<@U012345>` user
 *    mentions to display names. Aggressively cached at the source
 *    layer because workspace user lists are stable.
 *
 * Auth: every call carries `Authorization: Bearer <token>` (Slack
 * also accepts `token=<…>` as a query parameter, but the bearer
 * form is the documented modern shape and keeps the token out of
 * server-side request logs). The token is read from [SlackConfig]
 * at call time so a runtime config change applies on the next
 * fetch without process restart.
 *
 * Rate limits: Slack uses **tier-based limits** rather than the
 * Discord-style per-route buckets. Tier 2 endpoints
 * (`conversations.list`, `conversations.history`,
 * `users.info`) cap at ~20 req/min per workspace per method; the
 * `auth.test` Tier 1 endpoint caps at ~1 req/min. On 429 the
 * response carries a `Retry-After` header (integer seconds) which
 * we surface through [FictionResult.RateLimited]. Storyvox's
 * read-only Browse + Detail workload rarely approaches the limit.
 *
 * Slack's response envelope: every method returns `{"ok": true,
 * ...}` on success or `{"ok": false, "error": "<code>"}` on
 * failure. **HTTP 200 is the norm even on logical errors** — the
 * status line is about transport, not API semantics. The decoder
 * inspects `ok` after parsing and maps known error codes
 * (`invalid_auth`, `token_revoked`, `account_inactive`,
 * `ratelimited`, `not_authed`) to the appropriate [FictionResult]
 * failure variant.
 *
 * No bundled bot token. Without a configured token, every endpoint
 * fast-fails with [FictionResult.AuthRequired] so the UI can route
 * the user to the Settings → Slack onboarding flow.
 */
@Singleton
internal class SlackApi @Inject constructor(
    private val client: OkHttpClient,
    private val config: SlackConfig,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /**
     * Verify the bot token + return workspace metadata. Drives the
     * Settings "authenticated as @bot in <workspace>" confirmation
     * plus caches the workspace name/url back into [SlackConfig] for
     * empty-state copy.
     */
    suspend fun authTest(): FictionResult<SlackAuthTest> {
        val state = config.current()
        requireToken<SlackAuthTest>(state)?.let { return it }
        val url = "${state.baseUrl}/api/auth.test"
        return getJson<SlackAuthTest>(url, state)
    }

    /**
     * List conversations (channels) in the workspace. Slack returns
     * both public and private channels in one response when the
     * caller asks for both types; v1 requests both shapes since the
     * source treats them identically. Pagination is cursor-based —
     * the [cursor] parameter is the [SlackResponseMetadata.nextCursor]
     * from a prior call (empty string = first page).
     */
    suspend fun listConversations(
        cursor: String = "",
        limit: Int = SlackDefaults.CHANNELS_PAGE_SIZE,
    ): FictionResult<SlackConversationsList> {
        val state = config.current()
        requireToken<SlackConversationsList>(state)?.let { return it }
        val builder = "${state.baseUrl}/api/conversations.list"
            .toHttpUrl().newBuilder()
            .addQueryParameter("types", "public_channel,private_channel")
            .addQueryParameter("limit", limit.coerceIn(1, 1000).toString())
            .addQueryParameter("exclude_archived", "false")
        if (cursor.isNotBlank()) builder.addQueryParameter("cursor", cursor)
        return getJson<SlackConversationsList>(builder.build().toString(), state)
    }

    /**
     * Fetch one page of message history for a channel, newest-first.
     *
     * Slack's API returns newest-first regardless of how the caller
     * asks; the source layer reverses for chronological reading.
     * [cursor] is the [SlackResponseMetadata.nextCursor] from a
     * prior call (empty string = first page = most-recent
     * [limit] messages).
     */
    suspend fun listMessages(
        channelId: String,
        cursor: String = "",
        limit: Int = SlackDefaults.HISTORY_PAGE_SIZE,
    ): FictionResult<SlackConversationsHistory> {
        val state = config.current()
        requireToken<SlackConversationsHistory>(state)?.let { return it }
        val builder = "${state.baseUrl}/api/conversations.history"
            .toHttpUrl().newBuilder()
            .addQueryParameter("channel", channelId)
            .addQueryParameter("limit", limit.coerceIn(1, 1000).toString())
        if (cursor.isNotBlank()) builder.addQueryParameter("cursor", cursor)
        return getJson<SlackConversationsHistory>(builder.build().toString(), state)
    }

    /**
     * Resolve a Slack user id to a display name. Used by the source
     * layer to expand `<@U012345>` mentions into something more
     * narratable. v1's source doesn't yet do mention rewriting (we
     * surface raw text in the chapter body), so this endpoint is
     * declared for the [`in`.jphe.storyvox.source.slack.SlackWorkspaceDirectory]
     * surface only — a follow-up can wire it into the chapter
     * renderer with workspace-scoped caching.
     */
    suspend fun userInfo(userId: String): FictionResult<SlackUserInfoResponse> {
        val state = config.current()
        requireToken<SlackUserInfoResponse>(state)?.let { return it }
        val url = "${state.baseUrl}/api/users.info"
            .toHttpUrl().newBuilder()
            .addQueryParameter("user", userId)
            .build().toString()
        return getJson<SlackUserInfoResponse>(url, state)
    }

    // ─── transport ────────────────────────────────────────────────────

    private inline fun <reified T> getJson(url: String, state: SlackConfigState): FictionResult<T> =
        when (val raw = doRequest(url, state)) {
            is FictionResult.Success -> try {
                val parsed = json.decodeFromString<T>(raw.value)
                // Inspect the {ok: false, error: "..."} envelope BEFORE
                // returning success. Slack returns HTTP 200 with a
                // failure envelope on most logical errors; we have to
                // peek at the `ok` field via a parallel decode to
                // surface the right failure variant.
                val envelope = runCatching {
                    json.decodeFromString<SlackError>(raw.value)
                }.getOrNull()
                if (envelope != null && !envelope.ok) {
                    mapSlackError<T>(envelope.error, envelope.warning)
                } else {
                    FictionResult.Success(parsed)
                }
            } catch (e: kotlinx.serialization.SerializationException) {
                FictionResult.NetworkError("Slack returned unexpected JSON shape", e)
            }
            is FictionResult.Failure -> raw
        }

    /** Map a Slack error code to the right [FictionResult] failure
     *  variant. Slack documents error codes per-method, but the
     *  auth + rate-limit codes are stable across all methods. */
    private fun <T> mapSlackError(code: String, warning: String?): FictionResult<T> {
        val message = listOfNotNull(code.takeIf { it.isNotBlank() }, warning)
            .joinToString(" / ")
            .ifBlank { "Slack returned an unspecified error" }
        return when (code) {
            "not_authed",
            "invalid_auth",
            "token_revoked",
            "token_expired",
            "account_inactive",
            "missing_scope",
            "no_permission" ->
                FictionResult.AuthRequired(message)
            "channel_not_found",
            "not_in_channel",
            "user_not_found",
            "not_found" ->
                FictionResult.NotFound(message)
            "ratelimited",
            "rate_limited" ->
                FictionResult.RateLimited(retryAfter = null, message = message)
            else ->
                FictionResult.NetworkError(message, IOException("slack error: $code"))
        }
    }

    /**
     * Single GET with Slack's required headers + structured failure
     * mapping. Token comes from [state] (a fresh snapshot per call)
     * so a Settings change applies on the next request.
     */
    private fun doRequest(
        url: String,
        state: SlackConfigState,
    ): FictionResult<String> {
        return try {
            val request = Request.Builder()
                .url(url)
                // `Bearer <token>` is Slack's documented modern auth
                // header form; the legacy `token` query parameter
                // works but exposes the token in server-side logs.
                .header("Authorization", "Bearer ${state.apiToken}")
                .header("Accept", "application/json")
                .header("User-Agent", SlackDefaults.USER_AGENT)
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                when {
                    resp.code == 401 || resp.code == 403 ->
                        FictionResult.AuthRequired(
                            "Slack rejected the bot token (HTTP ${resp.code})",
                        )
                    resp.code == 404 ->
                        FictionResult.NotFound("Slack resource not found")
                    resp.code == 429 ->
                        // Slack's Retry-After is integer seconds.
                        // Some upstream proxies emit fractional values;
                        // we parse loosely and round up.
                        FictionResult.RateLimited(
                            retryAfter = resp.header("Retry-After")
                                ?.toDoubleOrNull()
                                ?.let { kotlin.math.ceil(it).toLong().seconds },
                            message = "Slack rate-limited the request",
                        )
                    resp.code in 500..599 ->
                        FictionResult.NetworkError(
                            "Slack server error HTTP ${resp.code}",
                            IOException("HTTP ${resp.code}"),
                        )
                    !resp.isSuccessful && text.isBlank() ->
                        FictionResult.NetworkError(
                            "HTTP ${resp.code} from Slack",
                            IOException("HTTP ${resp.code}"),
                        )
                    // Slack returns HTTP 200 with `{ok: false}` for
                    // logical errors. The envelope decoder above
                    // handles that; pass the body through here.
                    else -> FictionResult.Success(text)
                }
            }
        } catch (e: IOException) {
            FictionResult.NetworkError(e.message ?: "fetch failed", e)
        }
    }

    /**
     * Fast-fail with [FictionResult.AuthRequired] if [state] has no
     * bot token configured.
     */
    private fun <T> requireToken(state: SlackConfigState): FictionResult<T>? {
        if (state.apiToken.isBlank()) {
            return FictionResult.AuthRequired(
                "Slack bot token not configured. " +
                    "Install a Slack app and paste its xoxb- " +
                    "Bot User OAuth Token in Settings → Library & Sync → Slack.",
            )
        }
        return null
    }
}
