package `in`.jphe.storyvox.source.mempalace.net

import `in`.jphe.storyvox.source.mempalace.config.PalaceConfig
import `in`.jphe.storyvox.source.mempalace.config.PalaceConfigState
import `in`.jphe.storyvox.source.mempalace.di.PalaceHttp
import `in`.jphe.storyvox.source.mempalace.model.PalaceDrawer
import `in`.jphe.storyvox.source.mempalace.model.PalaceGraph
import `in`.jphe.storyvox.source.mempalace.model.PalaceHealth
import `in`.jphe.storyvox.source.mempalace.model.PalaceList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * HTTP client over palace-daemon's REST surface. Endpoints used:
 *
 *  - `GET /health` — reachability + version check.
 *  - `GET /graph` — wings + rooms structural snapshot (Browse).
 *  - `GET /list?wing=&room=&limit=&offset=` — paginated drawer listing
 *    by metadata (chapter list).
 *  - `POST /mcp` (with `mempalace_get_drawer`) — full drawer body
 *    (chapter content).
 *
 * All calls are `suspend`, dispatched to [Dispatchers.IO], cancellable
 * via OkHttp `Call.cancel()`, and never throw on HTTP/IO errors —
 * they return a [PalaceDaemonResult] variant.
 *
 * **LAN-only enforcement:** every request resolves the configured host
 * to an InetAddress and rejects non-LAN authorities except for the
 * explicit allowlist (`palace.jphe.in` is JP's reverse proxy onto the
 * home palace). See [isLanLikeAuthority].
 */
@Singleton
open class PalaceDaemonApi @Inject constructor(
    @PalaceHttp private val httpClient: OkHttpClient,
    private val config: PalaceConfig,
) {

    open suspend fun health(): PalaceDaemonResult<PalaceHealth> =
        get("/health")

    open suspend fun graph(): PalaceDaemonResult<PalaceGraph> =
        get("/graph")

    open suspend fun list(
        wing: String? = null,
        room: String? = null,
        limit: Int = 20,
        offset: Int = 0,
    ): PalaceDaemonResult<PalaceList> {
        val params = buildString {
            append("?limit=").append(limit).append("&offset=").append(offset)
            if (wing != null) append("&wing=").append(URLEncoder.encode(wing, Charsets.UTF_8.name()))
            if (room != null) append("&room=").append(URLEncoder.encode(room, Charsets.UTF_8.name()))
        }
        return get("/list$params")
    }

    /**
     * Fetch a single drawer's full content via the daemon's `/mcp`
     * passthrough. The daemon proxies MCP tool calls; we wrap the
     * `mempalace_get_drawer` tool with a JSON-RPC envelope and unwrap
     * the `result.content[0].text` field, which is itself JSON-encoded
     * (an artefact of the MCP protocol shape).
     */
    open suspend fun getDrawer(drawerId: String): PalaceDaemonResult<PalaceDrawer> =
        withContext(Dispatchers.IO) {
            val cfg = config.current()
            if (!cfg.isConfigured) {
                return@withContext PalaceDaemonResult.NotReachable(
                    IOException("Palace host not configured"),
                )
            }
            val baseUrl = baseUrlOrNull(cfg)
                ?: return@withContext PalaceDaemonResult.HostRejected(cfg.host)

            val payload = """
                {"jsonrpc":"2.0","id":1,"method":"tools/call",
                 "params":{"name":"mempalace_get_drawer",
                           "arguments":{"drawer_id":${'"'}${escapeJsonString(drawerId)}${'"'}}}}
            """.trimIndent()

            val req = Request.Builder()
                .url("$baseUrl/mcp")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .applyAuth(cfg.apiKey)
                .build()

            val response = try {
                httpClient.newCall(req).await()
            } catch (e: IOException) {
                return@withContext PalaceDaemonResult.NotReachable(e)
            }

            response.use { r ->
                if (!r.isSuccessful) return@use mapHttpFailure(r)
                val body = r.body ?: return@use PalaceDaemonResult.ParseError(
                    IOException("Empty response body"),
                )
                try {
                    val text = unwrapMcpTextResult(body.string())
                        ?: return@use PalaceDaemonResult.ParseError(
                            IOException("MCP envelope missing text result"),
                        )
                    val drawer = JSON.decodeFromString(PalaceDrawer.serializer(), text)
                    PalaceDaemonResult.Success(drawer)
                } catch (e: SerializationException) {
                    PalaceDaemonResult.ParseError(e)
                } catch (e: IOException) {
                    PalaceDaemonResult.ParseError(e)
                }
            }
        }

    private suspend inline fun <reified T> get(path: String): PalaceDaemonResult<T> =
        withContext(Dispatchers.IO) {
            val cfg = config.current()
            if (!cfg.isConfigured) {
                return@withContext PalaceDaemonResult.NotReachable(
                    IOException("Palace host not configured"),
                )
            }
            val baseUrl = baseUrlOrNull(cfg)
                ?: return@withContext PalaceDaemonResult.HostRejected(cfg.host)

            val req = Request.Builder()
                .url("$baseUrl$path")
                .get()
                .applyAuth(cfg.apiKey)
                .build()

            val response = try {
                httpClient.newCall(req).await()
            } catch (e: IOException) {
                return@withContext PalaceDaemonResult.NotReachable(e)
            }
            response.use { r -> mapResponse<T>(r) }
        }

    @OptIn(ExperimentalSerializationApi::class)
    private inline fun <reified T> mapResponse(r: Response): PalaceDaemonResult<T> {
        if (r.isSuccessful) {
            val body = r.body ?: return PalaceDaemonResult.ParseError(
                IOException("Empty response body"),
            )
            return try {
                val parsed = JSON.decodeFromStream<T>(body.byteStream())
                PalaceDaemonResult.Success(parsed)
            } catch (e: SerializationException) {
                PalaceDaemonResult.ParseError(e)
            }
        }
        return mapHttpFailure(r)
    }

    private fun mapHttpFailure(r: Response): PalaceDaemonResult<Nothing> = when (r.code) {
        401 -> PalaceDaemonResult.Unauthorized(r.message.ifBlank { "Unauthorized" })
        404 -> PalaceDaemonResult.NotFound(r.message.ifBlank { "Not found" })
        503 -> PalaceDaemonResult.Degraded(r.message.ifBlank { "Palace collection unavailable" })
        else -> PalaceDaemonResult.HttpError(r.code, r.message)
    }

    private fun Request.Builder.applyAuth(apiKey: String): Request.Builder {
        if (apiKey.isNotBlank()) header("X-API-Key", apiKey)
        return this
    }

    /**
     * Compose the daemon base URL from the user-supplied host. Returns
     * null when the resolved authority isn't LAN-like (defense in depth
     * against typos sending palace data over the open internet).
     *
     * Accepted authorities:
     *  - `*.local` (mDNS) — never resolves outside the LAN.
     *  - RFC1918 IPv4 / IPv6 site-local addresses.
     *  - Loopback (`127.0.0.0/8`, `::1`) — for local dev.
     *  - `palace.jphe.in` — explicit allowlist for JP's reverse proxy
     *    onto the home palace. Documented as a known per-deployment
     *    hardcode; see spec.
     */
    internal fun baseUrlOrNull(cfg: PalaceConfigState): String? {
        val raw = cfg.host.trim().removeSuffix("/")
        if (raw.isEmpty()) return null
        // Honor user-typed scheme if present (https://palace.jphe.in for
        // TLS-fronted setups), otherwise default to http:// for bare LAN
        // daemons that don't have TLS yet. Previously we stripped + forced
        // http:// unconditionally, which made TLS-fronted proxies hit a
        // 308 redirect storyvox couldn't follow. See #342.
        //
        // Single case-insensitive regex strip handles mixed-case typos like
        // `Https://host` — Copilot caught this on the first pass where the
        // case-insensitive startsWith and case-sensitive removePrefix were
        // out of sync, leaving `Https://host` un-stripped.
        val schemeMatch = SCHEME_PREFIX.find(raw)
        val scheme = schemeMatch?.value?.lowercase() ?: "http://"
        val withoutScheme = if (schemeMatch != null) {
            raw.substring(schemeMatch.range.last + 1)
        } else {
            raw
        }
        if (withoutScheme.isEmpty()) return null
        // Reject paths in the host field — we own the path entirely.
        if ('/' in withoutScheme) return null

        val candidate = "$scheme$withoutScheme"
        val uri = try {
            URI(candidate)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val authority = uri.host ?: return null

        if (!isLanLikeAuthority(authority)) return null
        return candidate
    }

    private fun isLanLikeAuthority(host: String): Boolean {
        if (host.equals("palace.jphe.in", ignoreCase = true)) return true
        if (host.endsWith(".local", ignoreCase = true)) return true
        // For numeric / hostname literals, resolve and inspect.
        val addr = try {
            InetAddress.getByName(host)
        } catch (_: UnknownHostException) {
            // Unknown host is "not LAN-like" by definition (we can't
            // verify it's safe). The daemon call will surface this
            // separately as NotReachable when the request goes out.
            return false
        }
        return addr.isLoopbackAddress ||
            addr.isSiteLocalAddress ||
            addr.isAnyLocalAddress ||
            addr.isLinkLocalAddress
    }

    /**
     * MCP responses come back as JSON-RPC envelopes:
     *   { "result": { "content": [ { "type": "text", "text": "<json>" } ] } }
     * The `text` field holds the actual tool output as a JSON string,
     * which we then re-parse against [PalaceDrawer].
     *
     * Returns the inner JSON text, or null when the envelope shape
     * differs (caller treats null as ParseError).
     */
    internal fun unwrapMcpTextResult(envelope: String): String? {
        return try {
            val root = JSON.parseToJsonElement(envelope).jsonObject
            val result = root["result"]?.jsonObject ?: return null
            // Some MCP error envelopes are signalled by a top-level boolean.
            if (result["isError"]?.jsonPrimitive?.boolean == true) return null
            val content = result["content"] as? kotlinx.serialization.json.JsonArray
                ?: return null
            val first = content.firstOrNull()?.jsonObject ?: return null
            first["text"]?.jsonPrimitive?.contentOrNull
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun escapeJsonString(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        internal val JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        /** Case-insensitive match for `http://` or `https://` at start of
         *  string. Single regex avoids the case-mismatch bug between a
         *  case-insensitive startsWith and case-sensitive removePrefix. */
        private val SCHEME_PREFIX = Regex("^https?://", RegexOption.IGNORE_CASE)
    }
}

/** Suspending bridge over OkHttp's enqueue → callback API. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }

        override fun onFailure(call: Call, e: IOException) {
            if (cont.isCancelled) return
            cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation {
        runCatching { cancel() }
    }
}

@Suppress("unused")
private fun JsonElement.asObjectOrNull(): JsonObject? =
    runCatching { this.jsonObject }.getOrNull()
