package `in`.jphe.storyvox.source.discord.net

import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.source.discord.config.DiscordConfig
import `in`.jphe.storyvox.source.discord.config.DiscordConfigState
import `in`.jphe.storyvox.source.discord.config.DiscordDefaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Issue #403 — minimal Discord REST API client. Four endpoints:
 *
 *  - **`GET /api/v10/users/@me/guilds`** — guilds the bot has been
 *    invited to. Drives the Settings server picker.
 *  - **`GET /api/v10/guilds/{id}/channels`** — channels in a guild.
 *    Each text channel becomes one storyvox fiction.
 *  - **`GET /api/v10/channels/{id}/messages?limit=100&before=<id>`** —
 *    paginated channel history. Newest-first; the source layer
 *    reverses for chronological reading + applies coalescing.
 *  - **`GET /api/v10/guilds/{id}/messages/search?content=<q>`** —
 *    full-text search restricted to the configured guild.
 *
 * Auth: every call carries `Authorization: Bot <token>` (the literal
 * word "Bot" is required by Discord — without it the API responds
 * with 401 even with a valid token). The token is read from
 * [DiscordConfig] at call time so a runtime config change applies
 * on the next fetch without process restart.
 *
 * Rate limits: Discord uses both per-route and global rate limits.
 * On 429 the response carries a `Retry-After` header (seconds, may
 * be fractional but we round up to whole seconds) which we surface
 * through [FictionResult.RateLimited]. Discord also includes an
 * `X-RateLimit-Bucket` header per route for finer-grained limits;
 * v1 treats the bucket as opaque (we just back off on 429), which
 * is fine for storyvox's read-only workload — we don't issue
 * concurrent calls against the same bucket.
 *
 * No bundled bot token. Without a configured token, every endpoint
 * fast-fails with [FictionResult.AuthRequired] so the UI can route
 * the user to the Settings → Discord onboarding flow.
 */
@Singleton
internal class DiscordApi @Inject constructor(
    private val client: OkHttpClient,
    private val config: DiscordConfig,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /**
     * List guilds the bot has been invited to. Drives the Settings
     * server picker. Failure modes:
     *  - 401 / 403 → [FictionResult.AuthRequired] (bad / revoked
     *    token or missing scope)
     *  - 429 → [FictionResult.RateLimited] with the server-provided
     *    Retry-After window
     */
    suspend fun listGuilds(): FictionResult<List<DiscordGuild>> {
        val state = config.current()
        requireToken<List<DiscordGuild>>(state)?.let { return it }
        val url = "${state.baseUrl}/api/${state.apiVersion}/users/@me/guilds"
        return getJson<List<DiscordGuild>>(url, state)
    }

    /**
     * List channels in a guild. Categories / voice / stage channels
     * are returned by Discord; the source layer filters to text +
     * announcement types before surfacing them as fictions.
     */
    suspend fun listChannels(guildId: String): FictionResult<List<DiscordChannel>> {
        val state = config.current()
        requireToken<List<DiscordChannel>>(state)?.let { return it }
        val url = "${state.baseUrl}/api/${state.apiVersion}/guilds/$guildId/channels"
        return getJson<List<DiscordChannel>>(url, state)
    }

    /**
     * Fetch one page of messages from a channel, newest-first.
     *
     * `before` is the snowflake id of the oldest message we already
     * have; passing null fetches the most recent [limit] messages.
     * Discord clamps `limit` to 100; we forward that bound here.
     * The source layer reverses the list for chronological reading
     * + applies coalescing.
     */
    suspend fun listMessages(
        channelId: String,
        before: String? = null,
        limit: Int = 100,
    ): FictionResult<List<DiscordMessage>> {
        val state = config.current()
        requireToken<List<DiscordMessage>>(state)?.let { return it }
        val safeLimit = limit.coerceIn(1, 100)
        val builder = "${state.baseUrl}/api/${state.apiVersion}/channels/$channelId/messages"
            .toHttpUrl().newBuilder()
            .addQueryParameter("limit", safeLimit.toString())
        if (!before.isNullOrBlank()) builder.addQueryParameter("before", before)
        return getJson<List<DiscordMessage>>(builder.build().toString(), state)
    }

    /**
     * Search messages within a guild. Discord's search endpoint
     * accepts a `content` parameter for free-form full-text matching
     * plus optional channel-scope filters; v1 uses just the content
     * match (server-wide).
     *
     * Note: Discord's search index has a documented ~30s ingestion
     * delay so very-recent messages may not surface. That's a server
     * property storyvox can't compensate for; the user-visible
     * symptom is "I posted a message and search doesn't find it for
     * a minute" — acceptable.
     */
    suspend fun searchMessages(
        guildId: String,
        query: String,
    ): FictionResult<DiscordSearchResponse> {
        val state = config.current()
        requireToken<DiscordSearchResponse>(state)?.let { return it }
        val url = "${state.baseUrl}/api/${state.apiVersion}/guilds/$guildId/messages/search"
            .toHttpUrl().newBuilder()
            .addQueryParameter("content", query)
            .build().toString()
        return getJson<DiscordSearchResponse>(url, state)
    }

    // ─── transport ────────────────────────────────────────────────────

    private suspend inline fun <reified T> getJson(url: String, state: DiscordConfigState): FictionResult<T> =
        when (val raw = doRequest(url, state)) {
            is FictionResult.Success -> try {
                FictionResult.Success(json.decodeFromString<T>(raw.value))
            } catch (e: kotlinx.serialization.SerializationException) {
                FictionResult.NetworkError("Discord returned unexpected JSON shape", e)
            }
            is FictionResult.Failure -> raw
        }

    /**
     * Single GET with Discord's required headers + structured
     * failure mapping. Token comes from [state] (a fresh snapshot per
     * call) so a Settings change applies on the next request.
     *
     * Issue #585 — wrapped in [withContext]`(Dispatchers.IO)`. See
     * `ArxivApi.getRaw` for the full context: the suspend caller's
     * dispatcher (often `Dispatchers.Main.immediate` via a Compose
     * ViewModel scope) is inherited without an explicit pin, and the
     * underlying OkHttp `execute()` blocks on DNS / TCP / TLS — fatal
     * `NetworkOnMainThreadException`.
     */
    private suspend fun doRequest(
        url: String,
        state: DiscordConfigState,
    ): FictionResult<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                // Literal "Bot" prefix per Discord's auth spec. Without
                // it Discord 401s even with a valid token — this is the
                // single most common bot-onboarding mistake.
                .header("Authorization", "Bot ${state.apiToken}")
                .header("Accept", "application/json")
                .header("User-Agent", DiscordDefaults.USER_AGENT)
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                when {
                    resp.code == 401 || resp.code == 403 ->
                        FictionResult.AuthRequired(
                            decodeErrorMessage(text)
                                ?: "Discord rejected the bot token (HTTP ${resp.code})",
                        )
                    resp.code == 404 ->
                        FictionResult.NotFound(
                            decodeErrorMessage(text) ?: "Discord resource not found",
                        )
                    resp.code == 429 ->
                        // Discord's Retry-After is in seconds; may be a
                        // float ("1.5") so we parse loosely and round up.
                        // The header may also be missing on the very
                        // worst rate-limit responses (gateway HTML);
                        // null retryAfter is acceptable — the caller
                        // backs off conservatively.
                        FictionResult.RateLimited(
                            retryAfter = resp.header("Retry-After")
                                ?.toDoubleOrNull()
                                ?.let { kotlin.math.ceil(it).toLong().seconds },
                            message = decodeErrorMessage(text)
                                ?: "Discord rate-limited the request",
                        )
                    !resp.isSuccessful ->
                        FictionResult.NetworkError(
                            decodeErrorMessage(text) ?: "HTTP ${resp.code} from Discord",
                            IOException("HTTP ${resp.code}"),
                        )
                    else -> FictionResult.Success(text)
                }
            }
        } catch (e: IOException) {
            FictionResult.NetworkError(e.message ?: "fetch failed", e)
        }
    }

    /**
     * Decode a Discord structured error to a human string. Discord
     * returns `{ "code", "message" }` on every documented failure;
     * the runCatching guards a non-JSON body (gateway HTML errors,
     * empty bodies on the worst transient failures).
     */
    private fun decodeErrorMessage(body: String): String? = runCatching {
        if (body.isBlank()) return null
        val err = json.decodeFromString<DiscordError>(body)
        err.message.ifBlank { null }
    }.getOrNull()

    /**
     * Fast-fail with [FictionResult.AuthRequired] if [state] has no
     * bot token. Other paths (no server id) are not the API's
     * concern; the source layer handles them with the same shape
     * but a different message.
     */
    private fun <T> requireToken(state: DiscordConfigState): FictionResult<T>? {
        if (state.apiToken.isBlank()) {
            return FictionResult.AuthRequired(
                "Discord bot token not configured. " +
                    "Paste a token in Settings → Library & Sync → Discord.",
            )
        }
        return null
    }
}
