package `in`.jphe.storyvox.sync.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * Production [InstantBackend] backed by the InstantDB admin HTTP API
 * (`POST /admin/query`, `POST /admin/transact`).
 *
 * # Why HTTP, not the runtime WebSocket
 *
 * The first cut (v0.5.41, `WsInstantBackend`) tried to drive sync over
 * `wss://.../runtime/session`. That endpoint streams its `add-query-ok`
 * results as raw EAV triples wrapped in a `datalog-result` envelope, and
 * relying on the attribute catalog delivered in `init-ok` to map UUIDs
 * back to field names. Every storyvox device hit the production server
 * and crashed every domain with:
 *
 *   ```
 *   Retrying: remote fetch: Element class kotlinx.serialization.json.JsonArray
 *   (Kotlin reflection is not available) is not a JsonObject
 *   ```
 *
 * — see issue #691. The naive `.jsonObject` cast at the top of the read
 * path was wrong from day one: `result` is a JsonArray, not a
 * JsonObject keyed by entity name. Re-implementing the EAV/attrs join in
 * Kotlin is a non-trivial amount of code (see `extractTriples` +
 * `createStore` in `instantdb/instant @ Reactor.js`) and it would have
 * had to track schema across reconnects.
 *
 * The admin HTTP API takes the same InstaQL query *and returns
 * materialized rows keyed by entity name*. That is exactly what we want
 * — and per the docs (`https://www.instantdb.com/docs/backend`) it
 * accepts `as-token`-impersonation auth with no admin token: the client
 * acts as the signed-in user under that user's permissions, identical
 * to the client-side SDK. So we get a documented JSON shape and a
 * shorter critical path.
 *
 * v2 (issue tbd) can hoist this back onto a long-lived WebSocket once
 * we want real-time push from server → device. For now (one-shot
 * pull/push on sync rounds), HTTP is the right primitive.
 *
 * # Wire shape (extracted from `client/packages/admin/src/index.ts`)
 *
 * **Query** — `POST {api}/admin/query?app_id=<appId>`
 *
 *   Headers:
 *     content-type: application/json
 *     app-id: <appId>
 *     as-token: <user refresh token>
 *
 *   Body:
 *     {
 *       "query": { "<entity>": { "$": { "where": { "id": "<uuid>" } } } },
 *       "inference?": false
 *     }
 *
 *   `<uuid>` is a deterministic UUID v3 derived from `"$domain:$userId"`
 *   via [SyncIds.rowUuid]. InstantDB requires entity IDs to be UUIDs.
 *
 *   Response (200): `{ "<entity>": [ { "id": "...", "payload": "...",
 *                                       "updatedAt": <long> }, ... ] }`
 *
 * **Transact** — `POST {api}/admin/transact?app_id=<appId>`
 *
 *   Same headers. Body:
 *     {
 *       "steps": [ ["update", "<entity>", "<id>",
 *                   { "payload": "...", "updatedAt": <long> }] ],
 *       "throw-on-missing-attrs?": false
 *     }
 *
 *   Response (200): server-defined; we only check the status code.
 *
 * Errors come back as 4xx/5xx with `{ "message": "..." }`. Network
 * failures surface as the transport's synthetic `code = 0`.
 */
class HttpInstantBackend(
    private val appId: String,
    private val transport: InstantHttpTransport,
    private val apiUri: String = InstantClient.DEFAULT_API_URI,
) : InstantBackend {

    override val isConfigured: Boolean = appId.isNotBlank() && appId != PLACEHOLDER_APP_ID

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        isLenient = true
        coerceInputValues = true
    }

    override suspend fun fetch(
        user: SignedInUser,
        entity: String,
        id: String,
    ): Result<RowSnapshot?> = runCatching {
        if (!isConfigured) error(NOT_CONFIGURED)
        android.util.Log.d(TAG, "fetch: entity=$entity id=${id.take(12)}…")
        val body = json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("query", buildJsonObject {
                    put(entity, buildJsonObject {
                        put("$", buildJsonObject {
                            put("where", buildJsonObject {
                                put("id", JsonPrimitive(id))
                            })
                        })
                    })
                })
                put("inference?", JsonPrimitive(false))
            },
        )
        val res = transport.postJson(
            url = "$apiUri/admin/query?app_id=$appId",
            jsonBody = body,
            headers = adminHeaders(user.refreshToken),
        )
        if (!res.isSuccessful) {
            android.util.Log.w(TAG, "fetch: HTTP ${res.code} for $entity/${id.take(12)}…")
            error(parseError(res, "query"))
        }
        android.util.Log.d(TAG, "fetch: HTTP ${res.code}, body=${res.body.length} chars")

        // Response shape: `{ "<entity>": [ {id, payload, updatedAt, ...} ] }`.
        // Empty array (or missing key) means the row doesn't exist yet.
        val root = runCatching { json.parseToJsonElement(res.body).jsonObject }
            .getOrElse { error("query: response was not a JSON object") }
        val rows = root[entity]
            ?.let { runCatching { it.jsonArray }.getOrNull() }
            ?: return@runCatching null
        val row = rows.firstOrNull()
            ?.let { runCatching { it.jsonObject }.getOrNull() }
            ?: return@runCatching null
        val payload = row["payload"]?.let { it as? JsonPrimitive }?.contentOrNull
        val updatedAt = row["updatedAt"]?.let { it as? JsonPrimitive }?.long
        if (payload != null && updatedAt != null) RowSnapshot(payload, updatedAt) else null
    }

    override suspend fun upsert(
        user: SignedInUser,
        entity: String,
        id: String,
        payload: String,
        updatedAt: Long,
    ): Result<Unit> = runCatching {
        if (!isConfigured) error(NOT_CONFIGURED)
        android.util.Log.d(TAG, "upsert: entity=$entity id=${id.take(12)}… payload=${payload.length} chars")
        val body = json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("steps", buildJsonArray {
                    add(buildJsonArray {
                        add(JsonPrimitive("update"))
                        add(JsonPrimitive(entity))
                        add(JsonPrimitive(id))
                        add(buildJsonObject {
                            put("payload", JsonPrimitive(payload))
                            put("updatedAt", JsonPrimitive(updatedAt))
                        })
                    })
                })
                put("throw-on-missing-attrs?", JsonPrimitive(false))
            },
        )
        val res = transport.postJson(
            url = "$apiUri/admin/transact?app_id=$appId",
            jsonBody = body,
            headers = adminHeaders(user.refreshToken),
        )
        if (!res.isSuccessful) {
            android.util.Log.w(TAG, "upsert: HTTP ${res.code} for $entity/${id.take(12)}…")
            error(parseError(res, "transact"))
        }
        android.util.Log.d(TAG, "upsert: HTTP ${res.code} OK")
        Unit
    }

    private fun adminHeaders(refreshToken: String): Map<String, String> = mapOf(
        // Lowercase header keys — exactly how the JS admin SDK sets them
        // (see `client/packages/admin/src/index.ts`). HTTP headers are
        // case-insensitive on the wire, but the JS SDK uses lowercase so
        // we mirror that for grep-ability against the upstream source.
        "content-type" to "application/json",
        "app-id" to appId,
        "as-token" to refreshToken,
    )

    private fun parseError(res: TransportResult, op: String): String {
        if (res.code == 0) return "$op network failure: ${res.body.take(200)}"
        if (res.body.isBlank()) return "$op failed (HTTP ${res.code})"
        val m = runCatching {
            json.parseToJsonElement(res.body).jsonObject["message"]
                ?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return m?.takeIf { it.isNotBlank() }
            ?: "$op failed (HTTP ${res.code}): ${res.body.take(200)}"
    }

    companion object {
        const val PLACEHOLDER_APP_ID: String = "PLACEHOLDER"
        private const val NOT_CONFIGURED = "Sync backend not configured"
        private const val TAG = "InstantBackend"
    }
}

