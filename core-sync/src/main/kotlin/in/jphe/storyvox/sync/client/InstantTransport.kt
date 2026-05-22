package `in`.jphe.storyvox.sync.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/**
 * Seam for HTTP traffic — the production wiring is OkHttp; tests substitute
 * a fake.
 *
 * Why an interface and not just OkHttp: the magic-code happy path is so
 * thin (4 endpoints, all POST JSON, no streaming) that decoupling lets unit
 * tests cover the InstantClient logic without spinning a MockWebServer
 * thread pool. The production class is a one-liner around OkHttp.
 *
 * **Issue #559 (v0.5.58)** — [postJson] is `suspend` for a reason: callers
 * from `:feature/sync` launch through `viewModelScope` which defaults to
 * `Dispatchers.Main.immediate`. A blocking `OkHttpClient.newCall().execute()`
 * on that dispatcher trips `StrictMode`'s `NetworkOnMainThreadException`
 * and kills the process the instant the user taps "Send code." The
 * production transport must dispatch onto IO; the interface contract is
 * "this may block, callers don't need to wrap it." Test fakes that
 * complete synchronously remain trivially correct because `suspend` is a
 * superset of blocking in the kotlinx-coroutines model.
 */
interface InstantHttpTransport {
    /** Returns the response body as a string. Throws [IOException] on
     *  network failure; returns the body even on non-2xx so callers can
     *  surface server-side error messages.
     *
     *  [headers] is optional — auth callers (magic-code, verify, signout)
     *  pass none and let the body carry the app-id. Data-plane callers
     *  (`/admin/query`, `/admin/transact`) require header-side auth
     *  (`app-id`, `as-token`) per the Instant admin HTTP API contract. */
    suspend fun postJson(
        url: String,
        jsonBody: String,
        headers: Map<String, String> = emptyMap(),
    ): TransportResult
}

data class TransportResult(
    val code: Int,
    val body: String,
) {
    val isSuccessful: Boolean get() = code in 200..299
}

/** Production transport. Reuses a single [OkHttpClient] for connection pooling. */
class OkHttpInstantTransport(
    private val client: OkHttpClient = defaultClient(),
) : InstantHttpTransport {

    override suspend fun postJson(
        url: String,
        jsonBody: String,
        headers: Map<String, String>,
    ): TransportResult =
        // Issue #559 — explicit IO dispatch. OkHttp's synchronous
        // `execute()` performs DNS + TCP + TLS on the calling thread,
        // and the magic-code endpoints aren't cached, so this is a
        // guaranteed-blocking path. `viewModelScope.launch { ... }`
        // by default runs on `Main.immediate`; without this switch the
        // first sign-in tap throws NetworkOnMainThreadException.
        withContext(Dispatchers.IO) {
            val builder = Request.Builder()
                .url(url)
                .post(jsonBody.toRequestBody(JSON_MEDIA))
            headers.forEach { (k, v) -> builder.header(k, v) }
            val req = builder.build()
            try {
                client.newCall(req).execute().use { resp: Response ->
                    TransportResult(
                        code = resp.code,
                        body = resp.body?.string().orEmpty(),
                    )
                }
            } catch (e: IOException) {
                // Wrap as a synthetic "0" code so callers can branch on
                // transient-network with the same shape as a real error body.
                TransportResult(code = 0, body = e.message ?: "network failure")
            }
        }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .build()
    }
}
