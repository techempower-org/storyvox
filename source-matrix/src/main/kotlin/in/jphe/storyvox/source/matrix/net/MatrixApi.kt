package `in`.jphe.storyvox.source.matrix.net

import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.source.matrix.config.MatrixConfig
import `in`.jphe.storyvox.source.matrix.config.MatrixConfigState
import `in`.jphe.storyvox.source.matrix.config.MatrixDefaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Issue #457 — minimal Matrix Client-Server API client. Six endpoints:
 *
 *  - **`GET /_matrix/client/v3/account/whoami`** — verify the access
 *    token and resolve the `@user:homeserver` id behind it. Drives
 *    the Settings "Signed in as …" confirmation.
 *  - **`GET /_matrix/client/v3/joined_rooms`** — list rooms the user
 *    has joined on the configured homeserver. Each room becomes one
 *    storyvox fiction.
 *  - **`GET /_matrix/client/v3/rooms/{roomId}/state/m.room.name`**
 *    + **`/state/m.room.topic`** — room metadata. Topic 404s are
 *    silenced and surfaced as a null description.
 *  - **`GET /_matrix/client/v3/rooms/{roomId}/messages?dir=b&limit=N&from=C`**
 *    — paginated message history walking backwards from `from`
 *    (or the room's current head when `from` is null). Newest-first
 *    in the chunk; the source layer reverses for chronological
 *    reading + applies coalescing.
 *  - **`GET /_matrix/client/v3/profile/{userId}/displayname`** —
 *    resolve a sender id to a display name. Cached per user-id at
 *    the source layer (Matrix profile lookups are expensive
 *    federation hops on the homeserver side).
 *
 * Auth: every call carries `Authorization: Bearer <access_token>`
 * (Matrix's standard Bearer scheme — unlike Discord's `Bot <token>`
 * literal-prefix oddity). The token + base URL come from
 * [MatrixConfig] at call time so a runtime Settings change applies
 * on the next fetch.
 *
 * Rate limits: Matrix uses `M_LIMIT_EXCEEDED` 429s with a
 * `Retry-After` header (seconds) and/or a `retry_after_ms` field in
 * the JSON error envelope. The transport prefers the header but
 * falls back to the JSON hint. Matrix homeservers typically use
 * per-token rate limits (matrix.org defaults are ~600 req/min for
 * authenticated traffic) — storyvox's read-only workload is well
 * under any reasonable cap.
 *
 * No bundled credentials. Without a configured token + homeserver,
 * every endpoint fast-fails with [FictionResult.AuthRequired] so
 * the UI can route the user to the Settings → Matrix onboarding flow.
 *
 * **Federation note (v1 simplification)**: profile lookups for users
 * on a different homeserver than the configured one are *still*
 * dispatched against the configured homeserver. The configured
 * homeserver maintains a federated cache of profile data it has
 * seen, so this works in practice. A v2 enhancement could parse the
 * `:homeserver` suffix from each user id and dispatch profile calls
 * to that user's home server directly — at the cost of multi-host
 * connection-pool fanout. Not needed for v1.
 */
@Singleton
internal class MatrixApi @Inject constructor(
    private val client: OkHttpClient,
    private val config: MatrixConfig,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /**
     * Verify the access token and resolve `@user:homeserver`.
     * Drives the Settings → Matrix confirmation row ("Signed in as
     * @alice:matrix.org") + the empty-state copy when the token is
     * bad.
     */
    suspend fun whoami(): FictionResult<MatrixWhoami> {
        val state = config.current()
        requireConfigured<MatrixWhoami>(state)?.let { return it }
        val url = "${state.baseUrl}/_matrix/client/${MatrixDefaults.API_VERSION}/account/whoami"
        return getJson<MatrixWhoami>(url, state)
    }

    /**
     * List rooms the user has joined on the configured homeserver.
     * Drives the Browse front page (one fiction per room) and the
     * Settings room-picker dropdown. Empty list is a legitimate
     * response (a user who hasn't joined anything yet); the UI
     * renders an empty state pointing at the user's Element /
     * Matrix client to join rooms first.
     */
    suspend fun listJoinedRooms(): FictionResult<MatrixJoinedRooms> {
        val state = config.current()
        requireConfigured<MatrixJoinedRooms>(state)?.let { return it }
        val url = "${state.baseUrl}/_matrix/client/${MatrixDefaults.API_VERSION}/joined_rooms"
        return getJson<MatrixJoinedRooms>(url, state)
    }

    /**
     * Fetch the room's `m.room.name` state event. 404 is a normal
     * "this room has no name set" response — we treat that as a
     * `Success` with an empty string so the caller falls back to the
     * room id as the display title.
     */
    suspend fun getRoomName(roomId: String): FictionResult<MatrixRoomName> {
        val state = config.current()
        requireConfigured<MatrixRoomName>(state)?.let { return it }
        val url = "${state.baseUrl}/_matrix/client/${MatrixDefaults.API_VERSION}" +
            "/rooms/${roomId.encodePath()}/state/m.room.name"
        return when (val r = getJson<MatrixRoomName>(url, state)) {
            is FictionResult.Success -> r
            // Matrix returns 404 when the state event isn't set;
            // surface that as an empty-name success so callers
            // don't have to special-case "room has no name".
            is FictionResult.NotFound -> FictionResult.Success(MatrixRoomName(name = ""))
            is FictionResult.Failure -> r
        }
    }

    /**
     * Fetch the room's `m.room.topic` state event. Same 404 → empty
     * normalisation as [getRoomName] — many rooms have no topic and
     * an empty topic should be a benign success at the source layer.
     */
    suspend fun getRoomTopic(roomId: String): FictionResult<MatrixRoomTopic> {
        val state = config.current()
        requireConfigured<MatrixRoomTopic>(state)?.let { return it }
        val url = "${state.baseUrl}/_matrix/client/${MatrixDefaults.API_VERSION}" +
            "/rooms/${roomId.encodePath()}/state/m.room.topic"
        return when (val r = getJson<MatrixRoomTopic>(url, state)) {
            is FictionResult.Success -> r
            is FictionResult.NotFound -> FictionResult.Success(MatrixRoomTopic(topic = ""))
            is FictionResult.Failure -> r
        }
    }

    /**
     * Walk room message history backwards from [from] (or the room's
     * current head when [from] is null). `dir=b` is the Matrix spec's
     * "backwards in time" parameter; combined with [limit] (1-100 per
     * the spec) this yields up to one page of newest-first events.
     * The source layer reverses to chronological + coalesces.
     */
    suspend fun listMessages(
        roomId: String,
        from: String? = null,
        limit: Int = MatrixDefaults.DEFAULT_MESSAGES_LIMIT,
    ): FictionResult<MatrixMessagesResponse> {
        val state = config.current()
        requireConfigured<MatrixMessagesResponse>(state)?.let { return it }
        val safeLimit = limit.coerceIn(1, 200)
        val url = "${state.baseUrl}/_matrix/client/${MatrixDefaults.API_VERSION}" +
            "/rooms/${roomId.encodePath()}/messages"
        val builder = url.toHttpUrl().newBuilder()
            .addQueryParameter("dir", "b")
            .addQueryParameter("limit", safeLimit.toString())
        if (!from.isNullOrBlank()) builder.addQueryParameter("from", from)
        return getJson<MatrixMessagesResponse>(builder.build().toString(), state)
    }

    /**
     * Resolve a `@user:homeserver` id to a display name. The Matrix
     * spec returns 200 with `{"displayname":null}` or 404 for users
     * who haven't set a display name — both are normalised to an
     * empty-displayname success here so the source layer can fall
     * back to the bare id without a special-case branch.
     */
    suspend fun getDisplayName(userId: String): FictionResult<MatrixDisplayName> {
        val state = config.current()
        requireConfigured<MatrixDisplayName>(state)?.let { return it }
        val url = "${state.baseUrl}/_matrix/client/${MatrixDefaults.API_VERSION}" +
            "/profile/${userId.encodePath()}/displayname"
        return when (val r = getJson<MatrixDisplayName>(url, state)) {
            is FictionResult.Success -> r
            is FictionResult.NotFound -> FictionResult.Success(MatrixDisplayName(displayname = null))
            is FictionResult.Failure -> r
        }
    }

    // ─── transport ────────────────────────────────────────────────────

    private suspend inline fun <reified T> getJson(url: String, state: MatrixConfigState): FictionResult<T> =
        when (val raw = doRequest(url, state)) {
            is FictionResult.Success -> try {
                FictionResult.Success(json.decodeFromString<T>(raw.value))
            } catch (e: kotlinx.serialization.SerializationException) {
                FictionResult.NetworkError("Matrix returned unexpected JSON shape", e)
            }
            is FictionResult.Failure -> raw
        }

    /**
     * Single GET with Matrix's standard auth headers + structured
     * failure mapping. Token + base URL come from [state] (a fresh
     * snapshot per call) so a Settings change applies on the next
     * request without process restart.
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
        state: MatrixConfigState,
    ): FictionResult<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                // Standard Bearer-token scheme per the Matrix spec.
                // Unlike Discord's literal "Bot" prefix, Matrix uses
                // the same shape as GitHub PATs and Slack OAuth.
                .header("Authorization", "Bearer ${state.accessToken}")
                .header("Accept", "application/json")
                .header("User-Agent", MatrixDefaults.USER_AGENT)
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val parsedError = decodeError(text)
                when {
                    resp.code == 401 || resp.code == 403 ->
                        FictionResult.AuthRequired(
                            parsedError?.humanMessage()
                                ?: "Matrix homeserver rejected the access token (HTTP ${resp.code})",
                        )
                    resp.code == 404 ->
                        FictionResult.NotFound(
                            parsedError?.humanMessage() ?: "Matrix resource not found",
                        )
                    resp.code == 429 -> {
                        // Prefer the HTTP Retry-After header (seconds);
                        // fall back to the JSON envelope's
                        // retry_after_ms when the header is missing
                        // (some homeservers omit the header even though
                        // the spec recommends it).
                        val headerSeconds = resp.header("Retry-After")?.toDoubleOrNull()
                        val retryAfter = headerSeconds
                            ?.let { kotlin.math.ceil(it).toLong().seconds }
                            ?: parsedError?.retryAfterMs?.milliseconds
                        FictionResult.RateLimited(
                            retryAfter = retryAfter,
                            message = parsedError?.humanMessage()
                                ?: "Matrix homeserver rate-limited the request",
                        )
                    }
                    !resp.isSuccessful ->
                        FictionResult.NetworkError(
                            parsedError?.humanMessage() ?: "HTTP ${resp.code} from Matrix homeserver",
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
     * Decode a Matrix structured error to a [MatrixError]. Matrix
     * returns `{ "errcode", "error", "retry_after_ms" }` on every
     * documented failure; the runCatching guards a non-JSON body
     * (reverse-proxy HTML pages from a misconfigured homeserver,
     * empty bodies on the worst transient failures).
     */
    private fun decodeError(body: String): MatrixError? = runCatching {
        if (body.isBlank()) return null
        json.decodeFromString<MatrixError>(body)
    }.getOrNull()

    /**
     * Fast-fail with [FictionResult.AuthRequired] if [state] has no
     * access token or no homeserver URL configured. Both are required
     * to dispatch a Matrix call; the source layer collapses them into
     * one error so the UI surface only needs to render one
     * "configure Matrix in Settings" empty state.
     */
    private fun <T> requireConfigured(state: MatrixConfigState): FictionResult<T>? {
        if (!state.isConfigured) {
            return FictionResult.AuthRequired(
                "Matrix not configured. Add your homeserver URL and access " +
                    "token in Settings → Library & Sync → Matrix.",
            )
        }
        return null
    }
}

/**
 * Compose a human-readable failure string from a [MatrixError]. The
 * Matrix spec includes both a machine-readable `errcode` and a
 * human `error` — we prefer the human field when present, falling
 * back to the errcode for the rare cases the homeserver leaves it
 * blank.
 */
internal fun MatrixError.humanMessage(): String? = when {
    error.isNotBlank() -> error
    errcode.isNotBlank() -> errcode
    else -> null
}

/**
 * Minimal URL-path encoder for Matrix room / event / user ids. Matrix
 * ids contain `!`, `:`, `@`, `$`, and `#` characters that are valid
 * URL path characters per RFC 3986 — but some homeservers (notably
 * older Synapse releases behind nginx) are picky about how `:` and
 * `!` are presented in the path. This helper percent-encodes the
 * minimum required set without depending on `java.net.URLEncoder`
 * (which encodes spaces as `+`, a query-string convention that
 * breaks path segments).
 */
internal fun String.encodePath(): String =
    buildString(length * 2) {
        for (c in this@encodePath) {
            when (c) {
                in 'A'..'Z', in 'a'..'z', in '0'..'9',
                '-', '_', '.', '~' -> append(c)
                else -> {
                    val bytes = c.toString().toByteArray(Charsets.UTF_8)
                    for (b in bytes) {
                        append('%')
                        append("%02X".format(b.toInt() and 0xFF))
                    }
                }
            }
        }
    }
