package `in`.jphe.storyvox.llm.provider

import `in`.jphe.storyvox.llm.LlmConfig
import `in`.jphe.storyvox.llm.LlmCredentialsStore
import `in`.jphe.storyvox.llm.LlmError
import `in`.jphe.storyvox.llm.LlmMessage
import `in`.jphe.storyvox.llm.LlmProvider
import `in`.jphe.storyvox.llm.ProbeResult
import `in`.jphe.storyvox.llm.ProviderId
import `in`.jphe.storyvox.llm.auth.AnthropicTeamsAuthApi
import `in`.jphe.storyvox.llm.auth.AnthropicTeamsAuthConfig
import `in`.jphe.storyvox.llm.auth.AnthropicTeamsAuthRepository
import `in`.jphe.storyvox.llm.auth.TokenResult
import `in`.jphe.storyvox.llm.di.LlmHttp
import `in`.jphe.storyvox.llm.sse.SseLineParser
import `in`.jphe.storyvox.llm.tools.ChatStreamEvent
import `in`.jphe.storyvox.llm.tools.ToolCallRequest
import `in`.jphe.storyvox.llm.tools.ToolRegistry
import `in`.jphe.storyvox.llm.tools.ToolResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Anthropic Teams (OAuth) provider — issue #181.
 *
 * Wire format is identical to [ClaudeApiProvider] (Anthropic Messages
 * API, SSE `content_block_delta`); only the auth differs:
 *
 * - Bearer token in `Authorization: Bearer <token>` instead of `x-api-key`.
 * - `anthropic-beta: oauth-2025-04-20` header is required for the OAuth
 *   bearer to be accepted (without it Anthropic responds 401 with "use
 *   an API key instead").
 * - On 401 we run the refresh-token flow once; if that also fails the
 *   user is bounced back to the sign-in screen.
 *
 * The bearer is short-lived (typically hours). [ensureFreshToken]
 * pre-emptively refreshes when the persisted `expires_at` is within
 * [REFRESH_LEAD_MILLIS]; that way listeners don't get a single failed
 * request mid-recap before the refresh kicks in. The retry-on-401
 * behaviour is the safety net for the case where Anthropic invalidates
 * the token early (revocation, server-side rotation).
 */
@Singleton
open class AnthropicTeamsProvider @Inject constructor(
    @LlmHttp private val http: OkHttpClient,
    private val store: LlmCredentialsStore,
    private val configFlow: Flow<LlmConfig>,
    private val authApi: AnthropicTeamsAuthApi,
    private val authRepo: AnthropicTeamsAuthRepository,
    private val json: Json,
) : LlmProvider {

    override val id: ProviderId = ProviderId.Teams

    /** Issue #216 — same Messages API as Claude direct, so the same
     *  tool-use shape applies. */
    override val supportsTools: Boolean = true

    /** Issue #215 — same Messages API as Claude direct, so image
     *  content blocks serialize the same way. The Teams bearer auth
     *  is the only thing that differs from [ClaudeApiProvider]. */
    override val supportsImages: Boolean = true

    /** Override hook for tests — Messages API endpoint. */
    protected open val endpointUrl: String = ENDPOINT

    /** Serialize concurrent refresh attempts so a recap + a chat both
     *  hitting an expired token only refresh once. */
    private val refreshLock = Mutex()

    override fun stream(
        messages: List<LlmMessage>,
        systemPrompt: String?,
        model: String?,
    ): Flow<String> = flow {
        val cfg = configFlow.first()
        val resolvedModel = (model ?: cfg.claudeModel).resolveAnthropic()

        // Issue #215 — image-bearing messages serialize to the typed-
        // block content array; text-only chats keep the lighter
        // string-content [AnthropicRequest] shape.
        val payload: String = if (messages.any { it.parts != null }) {
            val imageBody = AnthropicImageRequest(
                model = resolvedModel,
                maxTokens = MAX_TOKENS,
                messages = messages.map { msg ->
                    val content = ContentBlocks.anthropic(msg)
                        ?: listOf(buildJsonObject {
                            put("type", "text")
                            put("text", msg.content)
                        })
                    AnthropicToolMessage(role = msg.role.name, content = content)
                },
                system = systemPrompt,
                stream = true,
            )
            json.encodeToString(imageBody)
        } else {
            val body = AnthropicRequest(
                model = resolvedModel,
                maxTokens = MAX_TOKENS,
                messages = messages.map {
                    AnthropicMessage(it.role.name, it.content)
                },
                system = systemPrompt,
                stream = true,
            )
            json.encodeToString(body)
        }

        // Refresh proactively if the persisted expires_at is close.
        ensureFreshToken()

        val firstAttempt = doStreamRequest(payload)
        if (firstAttempt.code != 401) {
            firstAttempt.use { resp -> consumeResponse(resp).collect { emit(it) } }
            return@flow
        }
        // 401 — try one refresh + retry, mirroring how the GitHub
        // interceptor treats 401 as "token's gone". If the refresh
        // itself fails, bubble up AuthFailed and let the user re-sign-in.
        firstAttempt.close()
        if (!refreshOrInvalidate()) {
            throw LlmError.AuthFailed(
                ProviderId.Teams,
                "Teams session expired — sign in again.",
            )
        }
        val retry = doStreamRequest(payload)
        retry.use { resp ->
            when {
                resp.code == 401 || resp.code == 403 ->
                    throw LlmError.AuthFailed(
                        ProviderId.Teams,
                        resp.message.ifBlank { "HTTP ${resp.code}" },
                    )
                !resp.isSuccessful -> {
                    val excerpt = resp.body?.string()?.take(256) ?: resp.message
                    throw LlmError.ProviderError(
                        ProviderId.Teams, resp.code, excerpt,
                    )
                }
            }
            consumeResponse(resp).collect { emit(it) }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun doStreamRequest(payload: String): okhttp3.Response {
        val bearer = store.teamsBearerToken()
            ?: throw LlmError.NotConfigured(ProviderId.Teams)
        val request = Request.Builder()
            .url(endpointUrl)
            .header("Authorization", "Bearer $bearer")
            .header("anthropic-version", API_VERSION)
            .header("anthropic-beta", AnthropicTeamsAuthConfig.OAUTH_BETA_HEADER)
            .header("content-type", "application/json")
            .header("accept", "text/event-stream")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return try {
            http.newCall(request).executeAsync()
        } catch (e: IOException) {
            throw LlmError.Transport(ProviderId.Teams, e)
        }
    }

    private fun consumeResponse(resp: okhttp3.Response): Flow<String> = flow {
        when {
            resp.code == 401 || resp.code == 403 ->
                throw LlmError.AuthFailed(
                    ProviderId.Teams,
                    resp.message.ifBlank { "HTTP ${resp.code}" },
                )
            !resp.isSuccessful -> {
                val excerpt = resp.body?.string()?.take(256) ?: resp.message
                throw LlmError.ProviderError(
                    ProviderId.Teams, resp.code, excerpt,
                )
            }
        }
        val source = resp.body
            ?: throw LlmError.Transport(
                ProviderId.Teams,
                IOException("Empty response body"),
            )
        source.source().use { src ->
            while (!src.exhausted()) {
                val line = src.readUtf8Line() ?: break
                val token = SseLineParser.anthropic(line, json) ?: continue
                emit(token)
            }
        }
    }

    /**
     * Refresh proactively if the stored expiry is within
     * [REFRESH_LEAD_MILLIS]. No-op if there's no refresh token (the user
     * never completed the OAuth flow), or if the bearer is comfortably
     * fresh, or if the expiry is unknown (legacy / probe-only sessions).
     */
    private suspend fun ensureFreshToken() {
        val expiresAt = store.teamsExpiresAt()
        if (expiresAt <= 0L) return // unknown — fall through; 401 retry will catch it
        val now = System.currentTimeMillis()
        if (now < expiresAt - REFRESH_LEAD_MILLIS) return
        refreshOrInvalidate()
    }

    /**
     * Run the refresh flow once. Returns true iff the bearer was
     * replaced with a fresh one; false on any failure (no refresh
     * token, network error, `invalid_grant`, etc.). On
     * `invalid_grant` the session is wiped — the user has to sign in
     * again.
     */
    private suspend fun refreshOrInvalidate(): Boolean = refreshLock.withLock {
        val refresh = store.teamsRefreshToken() ?: return@withLock false
        when (val r = authApi.refreshToken(
            clientId = AnthropicTeamsAuthConfig.DEFAULT_CLIENT_ID,
            refreshToken = refresh,
        )) {
            is TokenResult.Success -> {
                val now = System.currentTimeMillis()
                store.setTeamsSession(
                    bearer = r.accessToken,
                    refreshToken = r.refreshToken ?: refresh,
                    expiresAtEpochMillis = now + r.expiresInSeconds * 1000L,
                    scopes = r.scopes.ifBlank { store.teamsScopes().orEmpty() },
                )
                true
            }
            is TokenResult.InvalidGrant -> {
                // Refresh token revoked — wipe the entire session so
                // Settings flips back to "not signed in" and the user
                // re-runs the OAuth flow. Keeping the dead refresh
                // token would just keep failing. Also flip the in-memory
                // session flag so any UI subscribed to it sees the
                // change without polling the credential store.
                authRepo.clearSession()
                false
            }
            is TokenResult.AnthropicError,
            is TokenResult.NetworkError,
            is TokenResult.HttpError,
            is TokenResult.MalformedResponse -> false
        }
    }

    override suspend fun probe(): ProbeResult {
        if (store.teamsBearerToken() == null) {
            return ProbeResult.Misconfigured("Sign in to Teams first")
        }
        ensureFreshToken()
        val bearer = store.teamsBearerToken()
            ?: return ProbeResult.Misconfigured("Sign in to Teams first")
        // 1-token POST against the Messages API. Anthropic doesn't
        // expose a free-tier reachability endpoint, but the cost is
        // negligible (~1 cent) and the OAuth bearer + beta header
        // round-trip is exactly what the Recap flow uses.
        val body = AnthropicRequest(
            model = LlmConfig().claudeModel.resolveAnthropic(),
            maxTokens = 1,
            messages = listOf(AnthropicMessage("user", "ping")),
            stream = false,
        )
        val request = Request.Builder()
            .url(endpointUrl)
            .header("Authorization", "Bearer $bearer")
            .header("anthropic-version", API_VERSION)
            .header("anthropic-beta", AnthropicTeamsAuthConfig.OAUTH_BETA_HEADER)
            .header("content-type", "application/json")
            .post(json.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return try {
            http.newCall(request).executeAsync().use { resp ->
                when {
                    resp.isSuccessful -> ProbeResult.Ok
                    resp.code == 401 || resp.code == 403 -> {
                        // One-shot refresh-and-retry, mirroring the
                        // stream path. If refresh succeeds, retry the
                        // probe; if it fails, surface the auth error.
                        if (refreshOrInvalidate()) {
                            // Recursive retry — bounded depth is 1
                            // because refreshOrInvalidate clears the
                            // token on failure, so the next call sees
                            // null bearer and returns Misconfigured.
                            probe()
                        } else {
                            ProbeResult.AuthError("Teams session expired — sign in again")
                        }
                    }
                    else -> ProbeResult.NotReachable(
                        "Anthropic returned ${resp.code}",
                    )
                }
            }
        } catch (e: IOException) {
            ProbeResult.NotReachable(e.message ?: "Connection failed")
        }
    }

    /**
     * Issue #216 — tool-aware chat. Same wire shape as
     * [ClaudeApiProvider.chatWithTools], plus the Teams-specific
     * bearer + refresh-on-401 dance. We share the [ToolCallRequest]
     * parser by inlining the same content-block walk; the duplication
     * is tolerable for v1 (two providers) and avoids cross-class
     * helpers leaking package-private state.
     */
    override fun chatWithTools(
        messages: List<LlmMessage>,
        systemPrompt: String?,
        model: String?,
        tools: ToolRegistry,
    ): Flow<ChatStreamEvent> {
        if (tools.catalog.isEmpty()) {
            return stream(messages, systemPrompt, model)
                .map { ChatStreamEvent.TextDelta(it) }
        }
        return flow {
            val cfg = configFlow.first()
            val resolvedModel = (model ?: cfg.claudeModel).resolveAnthropic()
            val toolDecls = tools.catalog.map { spec ->
                AnthropicToolDecl(
                    name = spec.name,
                    description = spec.description,
                    inputSchema = spec.toAnthropicInputSchema(),
                )
            }
            // Issue #215 — when a message carries image parts the
            // content array picks them up via [ContentBlocks.anthropic];
            // otherwise we wrap the plain text in a single text block.
            val conversation = ArrayList<AnthropicToolMessage>(
                messages.map { msg ->
                    val content = ContentBlocks.anthropic(msg)
                        ?: listOf(buildJsonObject {
                            put("type", "text")
                            put("text", msg.content)
                        })
                    AnthropicToolMessage(role = msg.role.name, content = content)
                },
            )

            var round = 0
            while (round < MAX_TOOL_ROUNDS) {
                round++
                val body = AnthropicToolRequest(
                    model = resolvedModel,
                    maxTokens = MAX_TOKENS,
                    messages = conversation,
                    system = systemPrompt,
                    tools = toolDecls,
                    stream = false,
                )
                val response = postToolRequestWithRefresh(json.encodeToString(body))
                val content = response["content"]?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList())
                val text = buildString {
                    for (block in content) {
                        val obj = block.jsonObject
                        if (obj["type"]?.jsonPrimitive?.contentOrNull == "text") {
                            obj["text"]?.jsonPrimitive?.contentOrNull?.let(::append)
                        }
                    }
                }
                val toolCalls = content.mapNotNull { block ->
                    val obj = block.jsonObject
                    if (obj["type"]?.jsonPrimitive?.contentOrNull != "tool_use") return@mapNotNull null
                    val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val input = obj["input"]?.jsonObject ?: JsonObject(emptyMap())
                    ToolCallRequest(id = id, name = name, arguments = input)
                }
                val stopReason = response["stop_reason"]?.jsonPrimitive?.contentOrNull
                if (toolCalls.isEmpty() || stopReason != "tool_use") {
                    if (text.isNotEmpty()) emit(ChatStreamEvent.TextDelta(text))
                    return@flow
                }
                conversation.add(
                    AnthropicToolMessage(
                        role = "assistant",
                        content = content.map { it as JsonElement },
                    ),
                )
                val resultBlocks = ArrayList<JsonElement>(toolCalls.size)
                for (call in toolCalls) {
                    emit(ChatStreamEvent.ToolCallStarted(call))
                    val handler = tools.handler(call.name)
                    val result = if (handler == null) {
                        ToolResult.Error("Unknown tool: ${call.name}")
                    } else {
                        try {
                            handler.execute(call.arguments)
                        } catch (t: Throwable) {
                            ToolResult.Error(
                                "Tool ${call.name} failed: ${t.message ?: t.javaClass.simpleName}",
                            )
                        }
                    }
                    emit(ChatStreamEvent.ToolCallCompleted(call.id, call.name, result))
                    resultBlocks.add(buildJsonObject {
                        put("type", "tool_result")
                        put("tool_use_id", call.id)
                        put("content", result.message)
                        if (result is ToolResult.Error) put("is_error", true)
                    })
                }
                conversation.add(
                    AnthropicToolMessage(role = "user", content = resultBlocks),
                )
            }
            emit(
                ChatStreamEvent.TextDelta(
                    "(Stopped after $MAX_TOOL_ROUNDS tool rounds.)",
                ),
            )
        }.flowOn(Dispatchers.IO)
    }

    /** Issue #216 — POST a non-streaming JSON body to the Messages API
     *  with the same refresh-on-401 dance as [stream]. Returns the
     *  parsed top-level response object. */
    private suspend fun postToolRequestWithRefresh(payload: String): JsonObject {
        ensureFreshToken()
        val first = doToolRequest(payload)
        if (first.code != 401) {
            return first.use { parseToolResponse(it) }
        }
        first.close()
        if (!refreshOrInvalidate()) {
            throw LlmError.AuthFailed(
                ProviderId.Teams,
                "Teams session expired — sign in again.",
            )
        }
        val retry = doToolRequest(payload)
        return retry.use { parseToolResponse(it) }
    }

    private suspend fun doToolRequest(payload: String): okhttp3.Response {
        val bearer = store.teamsBearerToken()
            ?: throw LlmError.NotConfigured(ProviderId.Teams)
        val request = Request.Builder()
            .url(endpointUrl)
            .header("Authorization", "Bearer $bearer")
            .header("anthropic-version", API_VERSION)
            .header("anthropic-beta", AnthropicTeamsAuthConfig.OAUTH_BETA_HEADER)
            .header("content-type", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return try {
            http.newCall(request).executeAsync()
        } catch (e: IOException) {
            throw LlmError.Transport(ProviderId.Teams, e)
        }
    }

    private fun parseToolResponse(resp: okhttp3.Response): JsonObject {
        when {
            resp.code == 401 || resp.code == 403 ->
                throw LlmError.AuthFailed(
                    ProviderId.Teams,
                    resp.message.ifBlank { "HTTP ${resp.code}" },
                )
            !resp.isSuccessful -> {
                val excerpt = resp.body?.string()?.take(256) ?: resp.message
                throw LlmError.ProviderError(
                    ProviderId.Teams, resp.code, excerpt,
                )
            }
        }
        val text = resp.body?.string()
            ?: throw LlmError.Transport(
                ProviderId.Teams,
                IOException("Empty response body"),
            )
        return json.parseToJsonElement(text).jsonObject
    }

    /**
     * Mirror of [ClaudeApiProvider.resolveAnthropic] — Teams uses the
     * same model id space (it's the same Messages API) so the canonical
     * → wire-name mapping is identical.
     */
    private fun String.resolveAnthropic(): String = when (this) {
        "claude-opus-4.7" -> "claude-opus-4-7"
        "claude-sonnet-4.7" -> "claude-sonnet-4-7"
        "claude-opus-4.6" -> "claude-opus-4-6"
        "claude-sonnet-4.6" -> "claude-sonnet-4-6"
        "claude-haiku-4.5" -> "claude-haiku-4-5-20251001"
        "claude-opus-4.5" -> "claude-opus-4-5-20251101"
        "claude-sonnet-4.5" -> "claude-sonnet-4-5-20250929"
        else -> this
    }

    private companion object {
        const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        const val API_VERSION = "2023-06-01"
        const val MAX_TOKENS = 1024

        /** Refresh the bearer if the persisted expiry is within 60 s.
         *  Long enough to absorb a slow request + slow refresh; short
         *  enough that the listener doesn't see the bearer rotate
         *  every other recap. */
        const val REFRESH_LEAD_MILLIS = 60_000L

        /** See [ClaudeApiProvider.MAX_TOOL_ROUNDS]. */
        const val MAX_TOOL_ROUNDS = 5
    }
}
