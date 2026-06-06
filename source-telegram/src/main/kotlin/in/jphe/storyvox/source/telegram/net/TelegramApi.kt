package `in`.jphe.storyvox.source.telegram.net

import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.source.telegram.config.TelegramConfig
import `in`.jphe.storyvox.source.telegram.config.TelegramConfigState
import `in`.jphe.storyvox.source.telegram.config.TelegramDefaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Issue #462 — minimal Telegram Bot API client. Three endpoints:
 *
 *  - **`GET /bot{TOKEN}/getMe`** — verify the token + return the
 *    bot identity. Drives the Settings "authenticated as @bot"
 *    confirmation.
 *  - **`GET /bot{TOKEN}/getUpdates?offset={n}&limit=100`** —
 *    poll for recent events. Storyvox uses this purely for
 *    channel discovery: any `channel_post` update reveals a
 *    channel the bot is a member of, so we derive the fiction
 *    list from observed `chat.id` values.
 *  - **`GET /bot{TOKEN}/getChat?chat_id={C}`** — channel
 *    metadata (title, description, @username).
 *
 * Auth: every Bot API call carries the token in the URL path
 * (`/bot{TOKEN}/method`) rather than in a header — Telegram's
 * documented convention. The token is read from [TelegramConfig]
 * at call time so a runtime config change applies on the next
 * fetch without process restart.
 *
 * Rate limits: Telegram's Bot API caps at ~30 messages/sec for
 * sending and is generous on reads (no documented per-minute
 * cap for `getUpdates` / `getChat`). On 429 the response carries
 * `parameters.retry_after` (seconds) which we surface as
 * [FictionResult.RateLimited]. Storyvox's read-only workload
 * rarely approaches the limit.
 *
 * No bundled bot token. Without a configured token, every
 * endpoint fast-fails with [FictionResult.AuthRequired] so the
 * UI can route the user to the Settings → Telegram onboarding
 * flow.
 *
 * **What v1 does NOT do**:
 *  - **No search**: Bot API has no full-text search endpoint.
 *    The `@SourcePlugin(supportsSearch = false)` annotation
 *    reflects this; the source's `search()` returns an empty
 *    page so the UI renders the "no results" empty state cleanly.
 *  - **No private chats / DMs**: Bot API can read DMs the bot
 *    is in, but storyvox's mapping treats `chat.type == "channel"`
 *    as the only valid fiction surface. Private content via
 *    MTProto user API is a separate, much larger integration.
 *  - **No message editing / sending**: read-only. Storyvox is
 *    a reader, not a client.
 *  - **No long-polling**: `getUpdates` is called synchronously
 *    on each Browse refresh. Long-polling would force a
 *    background coroutine + battery cost that doesn't fit the
 *    read-when-you-open-Browse usage pattern.
 */
@Singleton
internal class TelegramApi @Inject constructor(
    private val client: OkHttpClient,
    private val config: TelegramConfig,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /**
     * Verify the bot token + return the bot identity. Drives the
     * Settings "authenticated as @bot_name" confirmation. Failure
     * modes:
     *  - empty token → [FictionResult.AuthRequired]
     *  - 401 / 404 → [FictionResult.AuthRequired] (Telegram
     *    returns 404 on bad tokens because `/bot{bad}/getMe` is
     *    routing as path-not-found, not auth)
     *  - 429 → [FictionResult.RateLimited]
     */
    suspend fun getMe(): FictionResult<TelegramBotUser> {
        val state = config.current()
        requireToken<TelegramBotUser>(state)?.let { return it }
        val url = "${state.baseUrl}/bot${state.apiToken}/getMe"
        return getJson<TelegramBotUser>(url, state)
    }

    /**
     * Poll for recent updates. v1 uses this purely for channel
     * discovery: every `channel_post` update reveals a channel
     * the bot is a member of, so the caller dedupes by
     * `update.channel_post.chat.id` and maps each id to a
     * fiction.
     *
     * The `offset` parameter is Telegram's mechanism for marking
     * updates as seen — passing the highest `update_id + 1` from
     * a prior call confirms receipt and trims future responses.
     * For v1's channel-discovery use case we leave offset at 0
     * (re-fetch the full window every Browse refresh); a
     * follow-up could persist the offset for incremental updates.
     */
    suspend fun getUpdates(offset: Long = 0): FictionResult<List<TelegramUpdate>> {
        val state = config.current()
        requireToken<List<TelegramUpdate>>(state)?.let { return it }
        val url = "${state.baseUrl}/bot${state.apiToken}/getUpdates" +
            "?offset=$offset" +
            "&limit=${TelegramDefaults.GET_UPDATES_LIMIT}" +
            "&allowed_updates=%5B%22channel_post%22%2C%22edited_channel_post%22%5D"
        return getJson<List<TelegramUpdate>>(url, state)
    }

    /**
     * Fetch channel metadata. Used by [TelegramSource.fictionDetail]
     * to populate the fiction title + description from the
     * channel's own settings (admin-controlled).
     */
    suspend fun getChat(chatId: Long): FictionResult<TelegramChat> {
        val state = config.current()
        requireToken<TelegramChat>(state)?.let { return it }
        val url = "${state.baseUrl}/bot${state.apiToken}/getChat?chat_id=$chatId"
        return getJson<TelegramChat>(url, state)
    }

    // ─── transport ────────────────────────────────────────────────────

    private suspend inline fun <reified T> getJson(url: String, state: TelegramConfigState): FictionResult<T> =
        when (val raw = doRequest(url, state)) {
            is FictionResult.Success -> try {
                // Unwrap the {ok, result} envelope.
                val envelope = json.decodeFromString<TelegramEnvelope>(raw.value)
                when {
                    !envelope.ok -> {
                        val code = envelope.errorCode ?: 0
                        val desc = envelope.description.orEmpty()
                        when (code) {
                            401, 404 -> FictionResult.AuthRequired(
                                desc.ifBlank { "Telegram rejected the bot token" },
                            )
                            429 -> FictionResult.RateLimited(
                                retryAfter = envelope.parameters?.retryAfter
                                    ?.toLong()?.seconds,
                                message = desc.ifBlank { "Telegram rate-limited the request" },
                            )
                            else -> FictionResult.NetworkError(
                                desc.ifBlank { "Telegram Bot API error code $code" },
                                IOException("Telegram error $code"),
                            )
                        }
                    }
                    envelope.result == null ->
                        FictionResult.NetworkError(
                            "Telegram returned ok=true but no result payload",
                            IOException("missing result"),
                        )
                    else -> try {
                        FictionResult.Success(
                            json.decodeFromJsonElement(serializer<T>(), envelope.result),
                        )
                    } catch (e: kotlinx.serialization.SerializationException) {
                        FictionResult.NetworkError(
                            "Telegram returned unexpected result shape",
                            e,
                        )
                    }
                }
            } catch (e: kotlinx.serialization.SerializationException) {
                FictionResult.NetworkError("Telegram returned unexpected JSON envelope", e)
            }
            is FictionResult.Failure -> raw
        }

    /**
     * Single GET with Telegram's required headers + structured
     * failure mapping. Token is in the URL path (per Bot API
     * convention) so no Authorization header.
     *
     * Issue #585 — wrapped in [withContext]`(Dispatchers.IO)`, mirroring
     * `DiscordApi.doRequest`: the suspend caller's dispatcher (often
     * `Dispatchers.Main.immediate` via a Compose ViewModel scope) is
     * inherited without an explicit pin, and the underlying OkHttp
     * `execute()` blocks on DNS / TCP / TLS — fatal
     * `NetworkOnMainThreadException`.
     */
    private suspend fun doRequest(
        url: String,
        @Suppress("UNUSED_PARAMETER") state: TelegramConfigState,
    ): FictionResult<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", TelegramDefaults.USER_AGENT)
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                // Telegram's HTTP layer often returns 200 even on
                // logical errors (the {ok: false, error_code, ...}
                // envelope carries the real status). Only treat
                // transport-level failures (no body, connection
                // reset) as HTTP failures here; the envelope
                // decoder handles application-level errors above.
                when {
                    resp.code in 500..599 ->
                        FictionResult.NetworkError(
                            "Telegram server error HTTP ${resp.code}",
                            IOException("HTTP ${resp.code}"),
                        )
                    resp.code == 429 ->
                        // Some upstream proxies (Cloudflare in front
                        // of api.telegram.org) emit 429 with a
                        // Retry-After header before Telegram's own
                        // envelope kicks in. Forward the header
                        // value when present.
                        FictionResult.RateLimited(
                            retryAfter = resp.header("Retry-After")
                                ?.toDoubleOrNull()
                                ?.let { kotlin.math.ceil(it).toLong().seconds },
                            message = "Telegram rate-limited the request",
                        )
                    !resp.isSuccessful && text.isBlank() ->
                        FictionResult.NetworkError(
                            "HTTP ${resp.code} from Telegram",
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
     * Fast-fail with [FictionResult.AuthRequired] if [state] has
     * no bot token configured.
     */
    private fun <T> requireToken(state: TelegramConfigState): FictionResult<T>? {
        if (state.apiToken.isBlank()) {
            return FictionResult.AuthRequired(
                "Telegram bot token not configured. " +
                    "Create a bot via @BotFather in Telegram, then " +
                    "paste the token in Settings → Library & Sync → Telegram.",
            )
        }
        return null
    }
}

/** Look up the runtime [kotlinx.serialization.KSerializer] for [T].
 *  Inline so the reified-type erasure happens at the call site. */
internal inline fun <reified T> serializer(): kotlinx.serialization.KSerializer<T> =
    kotlinx.serialization.serializer()
