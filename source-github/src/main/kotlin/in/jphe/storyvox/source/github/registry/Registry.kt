package `in`.jphe.storyvox.source.github.registry

import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.source.github.di.GitHubHttp
import `in`.jphe.storyvox.source.github.net.GitHubJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.decodeFromStream
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Reads the curated `registry.json` from
 * `raw.githubusercontent.com/techempower-org/storyvox-registry/main/registry.json`
 * and caches it for the session.
 *
 * The registry is the source-of-truth for the **Featured** row at the
 * top of Browse → GitHub. Entries carry their display fields directly
 * (title, author, cover_url, description, tags) so the Featured row
 * renders without N round-trips to `getRepo`. Manifest enrichment
 * happens later — when the user opens an entry, step 3d reads
 * `book.toml` / `storyvox.json` from the actual repo.
 *
 * Caching contract: first read fetches, subsequent reads return the
 * cached value. Process death (or app re-launch) refetches. We don't
 * persist to disk — registry size is small (≤ a few KB) and the data
 * isn't load-bearing enough to need offline survival.
 */
@Singleton
internal open class Registry @Inject constructor(
    @GitHubHttp private val httpClient: OkHttpClient,
) {
    private val gate = Mutex()

    @Volatile
    private var cached: List<RegistryEntry>? = null

    /**
     * Returns the registry as a list of curator-authored entries.
     * Result is computed once per session under [gate] so concurrent
     * first callers don't N-fetch. Entries with non-`github:`
     * prefixes are filtered out — the registry shape is sourceId-
     * tagged, but only github entries are meaningful here.
     *
     * On network or parse failure, returns the matching
     * [FictionResult.Failure] without poisoning the cache — a later
     * call may succeed.
     */
    open suspend fun entries(): FictionResult<List<RegistryEntry>> {
        cached?.let { return FictionResult.Success(it) }
        return gate.withLock {
            cached?.let { return@withLock FictionResult.Success(it) }
            when (val r = fetch()) {
                is FictionResult.Success -> {
                    cached = r.value
                    r
                }
                is FictionResult.Failure -> r
            }
        }
    }

    /** Forces the next [entries] call to refetch. Used by tests. */
    internal fun invalidate() {
        cached = null
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun fetch(): FictionResult<List<RegistryEntry>> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(REGISTRY_URL)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .build()
        val response = try {
            httpClient.newCall(req).await()
        } catch (e: IOException) {
            return@withContext FictionResult.NetworkError(
                message = "Could not reach the GitHub registry",
                cause = e,
            )
        }
        response.use { r ->
            if (!r.isSuccessful) {
                return@withContext FictionResult.NetworkError(
                    message = "Registry HTTP ${r.code}: ${r.message}",
                )
            }
            val body = r.body ?: return@withContext FictionResult.NetworkError(
                message = "Registry returned empty body",
            )
            val doc = try {
                GitHubJson.decodeFromStream<RegistryDocument>(body.byteStream())
            } catch (e: SerializationException) {
                return@withContext FictionResult.NetworkError(
                    message = "Registry JSON parse error",
                    cause = e,
                )
            }
            val filtered = doc.fictions.filter { it.id.startsWith("github:") }
            FictionResult.Success(filtered)
        }
    }

    companion object {
        const val REGISTRY_URL: String =
            "https://raw.githubusercontent.com/techempower-org/storyvox-registry/main/registry.json"
        const val USER_AGENT: String =
            "storyvox/0.5 (+https://github.com/techempower-org/candela)"
    }
}

/** Suspending bridge over OkHttp's enqueue → callback API. Local copy
 *  to keep the registry independent of [`in`.jphe.storyvox.source.github
 *  .net.GitHubApi]'s internal helper; both wrap the same OkHttp idiom.
 */
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
    cont.invokeOnCancellation { runCatching { cancel() } }
}
