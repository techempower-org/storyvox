package `in`.jphe.storyvox.llm.provider

import `in`.jphe.storyvox.llm.LlmConfig
import `in`.jphe.storyvox.llm.LlmCredentialsStore
import `in`.jphe.storyvox.llm.LlmError
import `in`.jphe.storyvox.llm.LlmMessage
import `in`.jphe.storyvox.llm.LlmProvider
import `in`.jphe.storyvox.llm.ProbeResult
import `in`.jphe.storyvox.llm.ProviderId
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
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
 * Anthropic Messages API streaming client. Wire format documented
 * here:
 *   https://docs.anthropic.com/en/api/messages-streaming
 *
 * BYOK auth via `x-api-key` header. The version pin
 * `anthropic-version: 2023-06-01` has been stable since '23 — the
 * API has added events on top, but the named version is the wire
 * shape we test against. Bumping is a one-line change if a future
 * version offers something we want.
 */
@Singleton
open class ClaudeApiProvider @Inject constructor(
    @LlmHttp private val http: OkHttpClient,
    private val store: LlmCredentialsStore,
    private val configFlow: Flow<LlmConfig>,
    private val json: Json,
) : LlmProvider {

    override val id: ProviderId = ProviderId.Claude

    /** Issue #216 — Anthropic supports function calling on every
     *  Claude model we surface. The chat layer offers tool use; the
     *  Settings actions toggle gates whether the catalog is actually
     *  passed to [chatWithTools]. */
    override val supportsTools: Boolean = true

    /** Issue #215 — Anthropic Messages API supports inline image
     *  content blocks across the Claude 3+ family (every model we
     *  ship). The chat layer attaches an image when the composer has
     *  one queued; we serialize it into a `{type:"image", source:…}`
     *  block inside the user message's content array. */
    override val supportsImages: Boolean = true

    /** Anthropic Messages endpoint. Open so tests can override to
     *  point at MockWebServer. */
    protected open val endpointUrl: String = ENDPOINT

    override fun stream(
        messages: List<LlmMessage>,
        systemPrompt: String?,
        model: String?,
    ): Flow<String> = flow {
        val cfg = configFlow.first()
        val apiKey = store.claudeApiKey()
            ?: throw LlmError.NotConfigured(ProviderId.Claude)
        val resolvedModel = (model ?: cfg.claudeModel).resolveAnthropic()

        // Issue #215 — when any message carries multi-modal parts we
        // dispatch to the image-request wire shape (content is an
        // array of typed blocks); otherwise the cheaper string-content
        // [AnthropicRequest] handles the text-only hot path.
        val payload: String = if (messages.any { it.parts != null }) {
            val body = AnthropicImageRequest(
                model = resolvedModel,
                maxTokens = MAX_TOKENS,
                messages = messages.map { msg ->
                    val content = ContentBlocks.anthropic(msg)
                        ?: listOf(textBlock(msg.content))
                    AnthropicToolMessage(role = msg.role.name, content = content)
                },
                system = systemPrompt,
                stream = true,
            )
            json.encodeToString(body)
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

        val request = Request.Builder()
            .url(endpointUrl)
            .header("x-api-key", apiKey)
            .header("anthropic-version", API_VERSION)
            .header("content-type", "application/json")
            .header("accept", "text/event-stream")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = try {
            http.newCall(request).executeAsync()
        } catch (e: IOException) {
            throw LlmError.Transport(ProviderId.Claude, e)
        }

        response.use { resp ->
            when {
                resp.code == 401 || resp.code == 403 ->
                    throw LlmError.AuthFailed(
                        ProviderId.Claude,
                        resp.message.ifBlank { "HTTP ${resp.code}" },
                    )
                !resp.isSuccessful -> {
                    val excerpt = resp.body?.string()?.take(256) ?: resp.message
                    throw LlmError.ProviderError(
                        ProviderId.Claude, resp.code, excerpt,
                    )
                }
            }
            val source = resp.body
                ?: throw LlmError.Transport(
                    ProviderId.Claude,
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
    }.flowOn(Dispatchers.IO)

    override suspend fun probe(): ProbeResult {
        val apiKey = store.claudeApiKey()
            ?: return ProbeResult.Misconfigured("No Claude API key")
        // 1-token POST — cheapest possible probe. Anthropic doesn't
        // expose a free reachability endpoint; this consumes ~1¢
        // worth of tokens, which is fine for a manually-triggered
        // Test connection button.
        val body = AnthropicRequest(
            model = LlmConfig().claudeModel.resolveAnthropic(),
            maxTokens = 1,
            messages = listOf(AnthropicMessage("user", "ping")),
            stream = false,
        )
        val request = Request.Builder()
            .url(endpointUrl)
            .header("x-api-key", apiKey)
            .header("anthropic-version", API_VERSION)
            .header("content-type", "application/json")
            .post(json.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return try {
            http.newCall(request).executeAsync().use { resp ->
                when {
                    resp.isSuccessful -> ProbeResult.Ok
                    resp.code == 401 || resp.code == 403 ->
                        ProbeResult.AuthError("Invalid Claude API key")
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
     * Issue #216 — tool-aware chat. When [tools] is empty, falls
     * through to plain [stream] (cheaper, streaming). When tools are
     * registered, switches to a non-streaming request so we can
     * parse the full content-blocks array in one shot — Anthropic's
     * `tool_use` blocks are easier to handle from the final response
     * than from the partial-JSON SSE deltas.
     *
     * Loop:
     *  1. Send messages + system + tools (non-streaming).
     *  2. If response.stop_reason == "tool_use", execute every
     *     `tool_use` block via [ToolRegistry.handler], append a
     *     `tool_result` user-message, re-request.
     *  3. Repeat up to [MAX_TOOL_ROUNDS] times — guards against the
     *     model getting stuck in a tool-calling loop.
     *  4. On the first `end_turn` (text-only) response, emit
     *     [ChatStreamEvent.TextDelta] with the joined text and stop.
     *
     * Emits [ChatStreamEvent.ToolCallStarted] / [ChatStreamEvent.ToolCallCompleted]
     * pairs before re-requesting so the UI can show progress.
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
            val apiKey = store.claudeApiKey()
                ?: throw LlmError.NotConfigured(ProviderId.Claude)
            val resolvedModel = (model ?: cfg.claudeModel).resolveAnthropic()

            val toolDecls = tools.catalog.map { spec ->
                AnthropicToolDecl(
                    name = spec.name,
                    description = spec.description,
                    inputSchema = spec.toAnthropicInputSchema(),
                )
            }

            // Build the seed turns. Each existing LlmMessage carries
            // plain text; wrap it in a single `text` block to match
            // the content-array shape Anthropic requires here.
            //
            // Issue #215 — when a message carries [LlmMessage.parts],
            // serialize those blocks instead of the plain-text content
            // (the latest user turn typically does, on an image-bearing
            // send).
            val conversation = ArrayList<AnthropicToolMessage>(
                messages.map { msg ->
                    val content = ContentBlocks.anthropic(msg)
                        ?: listOf(textBlock(msg.content))
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
                val response = postToolRequest(apiKey, body)
                val text = collectText(response)
                val toolCalls = collectToolCalls(response)
                val stopReason = response["stop_reason"]
                    ?.jsonPrimitive?.contentOrNull

                if (toolCalls.isEmpty() || stopReason != "tool_use") {
                    if (text.isNotEmpty()) emit(ChatStreamEvent.TextDelta(text))
                    return@flow
                }

                // Add the assistant's tool-call turn verbatim so the
                // follow-up's `tool_use_id` references resolve.
                val assistantContent = response["content"]?.jsonArray
                    ?.map { it as JsonElement } ?: emptyList()
                conversation.add(
                    AnthropicToolMessage(
                        role = "assistant",
                        content = assistantContent,
                    ),
                )

                // Run each tool, build a single user-turn carrying
                // every `tool_result` block. Anthropic requires all
                // tool_result blocks for one model turn to be batched
                // into one follow-up user message.
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
                    resultBlocks.add(
                        buildJsonObject {
                            put("type", "tool_result")
                            put("tool_use_id", call.id)
                            put("content", result.message)
                            if (result is ToolResult.Error) put("is_error", true)
                        },
                    )
                }
                conversation.add(
                    AnthropicToolMessage(role = "user", content = resultBlocks),
                )
            }
            // Bail-out — if we hit the loop cap, surface a tail text
            // so the user sees *something* even on a misbehaving model.
            emit(
                ChatStreamEvent.TextDelta(
                    "(Stopped after $MAX_TOOL_ROUNDS tool rounds.)",
                ),
            )
        }.flowOn(Dispatchers.IO)
    }

    private fun textBlock(text: String): JsonElement = buildJsonObject {
        put("type", "text")
        put("text", text)
    }

    private suspend fun postToolRequest(
        apiKey: String,
        body: AnthropicToolRequest,
    ): JsonObject {
        val request = Request.Builder()
            .url(endpointUrl)
            .header("x-api-key", apiKey)
            .header("anthropic-version", API_VERSION)
            .header("content-type", "application/json")
            .post(json.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val resp = try {
            http.newCall(request).executeAsync()
        } catch (e: IOException) {
            throw LlmError.Transport(ProviderId.Claude, e)
        }
        resp.use { r ->
            when {
                r.code == 401 || r.code == 403 ->
                    throw LlmError.AuthFailed(
                        ProviderId.Claude,
                        r.message.ifBlank { "HTTP ${r.code}" },
                    )
                !r.isSuccessful -> {
                    val excerpt = r.body?.string()?.take(256) ?: r.message
                    throw LlmError.ProviderError(
                        ProviderId.Claude, r.code, excerpt,
                    )
                }
            }
            val text = r.body?.string()
                ?: throw LlmError.Transport(
                    ProviderId.Claude,
                    IOException("Empty response body"),
                )
            return json.parseToJsonElement(text).jsonObject
        }
    }

    private fun collectText(response: JsonObject): String {
        val content = response["content"]?.jsonArray ?: return ""
        val sb = StringBuilder()
        for (block in content) {
            val obj = block.jsonObject
            if (obj["type"]?.jsonPrimitive?.contentOrNull == "text") {
                obj["text"]?.jsonPrimitive?.contentOrNull?.let(sb::append)
            }
        }
        return sb.toString()
    }

    private fun collectToolCalls(response: JsonObject): List<ToolCallRequest> {
        val content = response["content"]?.jsonArray ?: return emptyList()
        return content.mapNotNull { block ->
            val obj = block.jsonObject
            if (obj["type"]?.jsonPrimitive?.contentOrNull != "tool_use") return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val input = obj["input"]?.jsonObject ?: JsonObject(emptyMap())
            ToolCallRequest(id = id, name = name, arguments = input)
        }
    }

    /**
     * Map our canonical model name (e.g. "claude-haiku-4.5") to
     * Anthropic's wire format ("claude-haiku-4-5-20251001").
     * Direct port of `cloud-chat-assistant/llm_stream.py:MODEL_MAP`.
     * Falls through to the input string when no mapping exists, so
     * advanced users can paste a literal Anthropic model id into
     * the Settings dropdown if they want a model we haven't
     * canonicalized.
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
        /** Issue #216 — defensive cap on tool-call rounds per chat
         *  turn. The model very occasionally gets stuck in a loop
         *  (call → "let me also check" → call → ...). Five rounds
         *  is plenty for any v1 flow and bounds worst-case token
         *  spend at predictable levels. */
        const val MAX_TOOL_ROUNDS = 5
    }
}
