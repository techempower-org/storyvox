package `in`.jphe.storyvox.llm.provider

import `in`.jphe.storyvox.llm.LlmConfig
import `in`.jphe.storyvox.llm.LlmCredentialsStore
import `in`.jphe.storyvox.llm.LlmError
import `in`.jphe.storyvox.llm.LlmMessage
import `in`.jphe.storyvox.llm.LlmProvider
import `in`.jphe.storyvox.llm.ProbeResult
import `in`.jphe.storyvox.llm.ProviderId
import `in`.jphe.storyvox.llm.di.LlmHttp
import java.io.IOException
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * AWS Bedrock streaming client. Hits `converse-stream` and parses the
 * binary event-stream response into text deltas.
 *
 * Wire format: see `cloud-chat-assistant/llm_stream.py:_build_bedrock_request`
 * — the same request body shape (`modelId`, `messages` with content
 * arrays, `inferenceConfig`, `system` array). Auth is BYOK
 * access-key + secret + region; credentials live in
 * [LlmCredentialsStore] (encrypted prefs) and the SigV4 signature is
 * computed in-app via [SigV4Signer]. No AWS SDK dependency.
 *
 * Streamed response framing is **not** SSE — it's the AWS binary
 * event-stream format ([EventStreamParser]). Frames carry
 * `contentBlockDelta` events whose payload `delta.text` is the next
 * token chunk.
 */
@Singleton
open class BedrockProvider @Inject constructor(
    @LlmHttp private val http: OkHttpClient,
    private val store: LlmCredentialsStore,
    private val configFlow: Flow<LlmConfig>,
    private val json: Json,
) : LlmProvider {

    override val id: ProviderId = ProviderId.Bedrock

    /** Open so tests can override to point at MockWebServer. The
     *  region in the URL is used for SigV4's credential scope, so
     *  test subclasses also need to override [signingRegion] to match
     *  whatever region they want signed requests for. */
    protected open fun endpointUrl(region: String, modelId: String): String =
        "https://bedrock-runtime.$region.amazonaws.com/model/" +
            "${URLEncoder.encode(modelId, "UTF-8").replace("+", "%20")}/converse-stream"

    /** Override in tests to pin the region used for SigV4's
     *  credential scope, independent of the actual hostname. */
    protected open val signingRegion: String? = null

    override fun stream(
        messages: List<LlmMessage>,
        systemPrompt: String?,
        model: String?,
    ): Flow<String> = flow {
        val cfg = configFlow.first()
        val accessKey = store.bedrockAccessKey()
        val secretKey = store.bedrockSecretKey()
        if (accessKey.isNullOrBlank() || secretKey.isNullOrBlank()) {
            throw LlmError.NotConfigured(ProviderId.Bedrock)
        }
        val canonical = (model ?: cfg.bedrockModel)
        val modelId = canonical.resolveBedrock()
            ?: throw LlmError.ProviderError(
                ProviderId.Bedrock, 0,
                "Unknown Bedrock model: $canonical",
            )
        val region = cfg.bedrockRegion.ifBlank { DEFAULT_REGION }
        val url = endpointUrl(region, modelId)

        val body = BedrockRequest(
            modelId = modelId,
            messages = messages.map {
                BedrockMessage(
                    role = it.role.name,
                    content = listOf(BedrockTextBlock(it.content)),
                )
            },
            inferenceConfig = BedrockInferenceConfig(maxTokens = MAX_TOKENS),
            system = systemPrompt?.takeIf { it.isNotBlank() }
                ?.let { listOf(BedrockTextBlock(it)) },
        )
        val payload = json.encodeToString(body).toByteArray(Charsets.UTF_8)
        val signed = SigV4Signer.sign(
            method = "POST",
            url = url,
            payload = payload,
            accessKey = accessKey,
            secretKey = secretKey,
            region = signingRegion ?: region,
        )

        val builder = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
        // OkHttp sets Host itself; we omit it from headers (the signer
        // includes it only because the canonical-request needs it).
        for ((k, v) in signed) {
            if (k.equals("host", ignoreCase = true)) continue
            builder.header(k, v)
        }
        val request = builder.build()

        val response = try {
            http.newCall(request).executeAsync()
        } catch (e: IOException) {
            throw LlmError.Transport(ProviderId.Bedrock, e)
        }

        response.use { resp ->
            when {
                resp.code == 401 || resp.code == 403 ->
                    throw LlmError.AuthFailed(
                        ProviderId.Bedrock,
                        resp.message.ifBlank { "HTTP ${resp.code}" },
                    )
                !resp.isSuccessful -> {
                    val excerpt = resp.body?.string()?.take(256) ?: resp.message
                    throw LlmError.ProviderError(
                        ProviderId.Bedrock, resp.code, excerpt,
                    )
                }
            }
            val source = resp.body
                ?: throw LlmError.Transport(
                    ProviderId.Bedrock,
                    IOException("Empty response body"),
                )
            // Streaming pump — read raw bytes (NOT lines; this is binary
            // framing), accumulate in a buffer, drain complete frames as
            // they arrive. Keeps frames intact across OkHttp chunk
            // boundaries.
            val src = source.source()
            var buf = ByteArray(0)
            try {
                while (!src.exhausted()) {
                    val chunk = ByteArray(READ_BUF_SIZE)
                    val n = src.read(chunk)
                    if (n <= 0) break
                    buf = buf + chunk.copyOf(n)
                    val (tokens, consumed) = EventStreamParser.decodeTokens(buf, json)
                    for (t in tokens) emit(t)
                    if (consumed > 0) {
                        buf = if (consumed >= buf.size) ByteArray(0)
                        else buf.copyOfRange(consumed, buf.size)
                    }
                }
            } finally {
                src.close()
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun probe(): ProbeResult {
        val accessKey = store.bedrockAccessKey()
        val secretKey = store.bedrockSecretKey()
        if (accessKey.isNullOrBlank() || secretKey.isNullOrBlank()) {
            return ProbeResult.Misconfigured("No Bedrock access key + secret")
        }
        // Bedrock has no free reachability endpoint — same situation
        // as Anthropic. We send a 1-token converse-stream request
        // against the configured model. ~1¢ worth of tokens; fine for
        // a manually-triggered Test connection button.
        val cfg = configFlow.first()
        val modelId = cfg.bedrockModel.resolveBedrock()
            ?: return ProbeResult.Misconfigured(
                "Unknown Bedrock model: ${cfg.bedrockModel}",
            )
        val region = cfg.bedrockRegion.ifBlank { DEFAULT_REGION }
        val url = endpointUrl(region, modelId)
        val body = BedrockRequest(
            modelId = modelId,
            messages = listOf(
                BedrockMessage("user", listOf(BedrockTextBlock("ping"))),
            ),
            inferenceConfig = BedrockInferenceConfig(maxTokens = 1),
        )
        val payload = json.encodeToString(body).toByteArray(Charsets.UTF_8)
        val signed = SigV4Signer.sign(
            "POST", url, payload, accessKey, secretKey,
            signingRegion ?: region,
        )
        val builder = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
        for ((k, v) in signed) {
            if (k.equals("host", ignoreCase = true)) continue
            builder.header(k, v)
        }
        return try {
            http.newCall(builder.build()).executeAsync().use { resp ->
                when {
                    resp.isSuccessful -> ProbeResult.Ok
                    resp.code == 401 || resp.code == 403 ->
                        ProbeResult.AuthError("Invalid AWS credentials")
                    else -> ProbeResult.NotReachable(
                        "Bedrock returned ${resp.code}",
                    )
                }
            }
        } catch (e: IOException) {
            ProbeResult.NotReachable(e.message ?: "Connection failed")
        }
    }

    /**
     * Map our canonical model name to Bedrock's wire model ID. Direct
     * port of the `bedrock` column in
     * `cloud-chat-assistant/llm_stream.py:MODEL_MAP`. Accepts a literal
     * Bedrock id pass-through (anything containing ":" or starting
     * with `us.`) so advanced users aren't constrained to the canonical
     * list. Returns null on completely unknown input.
     */
    private fun String.resolveBedrock(): String? {
        BEDROCK_MODEL_MAP[this]?.let { return it }
        // Pass-through for explicit Bedrock IDs (e.g.
        // "us.anthropic.claude-haiku-4-5-20251001-v1:0"). Bedrock
        // model IDs always contain "." and many contain ":".
        if (contains(":") || startsWith("us.") || startsWith("eu.")) return this
        return null
    }

    private companion object {
        const val DEFAULT_REGION = "us-east-1"
        const val MAX_TOKENS = 1024
        const val READ_BUF_SIZE = 4096

        /** Canonical → Bedrock wire id. Mirrors the `bedrock` column in
         *  cloud-chat-assistant's `MODEL_MAP`. The full list lives there;
         *  we surface a curated subset that matches what the Settings UI
         *  shows. */
        val BEDROCK_MODEL_MAP = mapOf(
            "claude-haiku-4.5" to "us.anthropic.claude-haiku-4-5-20251001-v1:0",
            "claude-sonnet-4.7" to "us.anthropic.claude-sonnet-4-7",
            "claude-opus-4.7" to "us.anthropic.claude-opus-4-7",
            "claude-sonnet-4.6" to "us.anthropic.claude-sonnet-4-6",
            "claude-opus-4.6" to "us.anthropic.claude-opus-4-6-v1",
            "claude-sonnet-4.5" to "us.anthropic.claude-sonnet-4-5-20250929-v1:0",
            "claude-opus-4.5" to "us.anthropic.claude-opus-4-5-20251101-v1:0",
            "nova-pro" to "us.amazon.nova-pro-v1:0",
            "nova-lite" to "us.amazon.nova-lite-v1:0",
            "llama4-maverick-17b" to "us.meta.llama4-maverick-17b-instruct-v1:0",
            "llama4-scout-17b" to "us.meta.llama4-scout-17b-instruct-v1:0",
        )
    }
}

/**
 * Canonical model ids surfaced in the Settings UI for Bedrock. Mirror
 * of cloud-chat-assistant's `BEDROCK_MODELS` map keys (curated).
 */
object BedrockModels {
    val canonical: List<String> = listOf(
        "claude-haiku-4.5",
        "claude-sonnet-4.6",
        "claude-opus-4.6",
        "claude-sonnet-4.7",
        "claude-opus-4.7",
        "nova-pro",
        "nova-lite",
        "llama4-maverick-17b",
    )

    /** AWS regions where Bedrock currently offers the canonical models
     *  in [canonical]. Hardcoded — `Bedrock.ListFoundationModels` would
     *  be the dynamic source of truth, but it requires a separate IAM
     *  permission and we already have a fixed model list. */
    val regions: List<String> = listOf(
        "us-east-1",
        "us-west-2",
        "eu-central-1",
        "ap-northeast-1",
    )
}
