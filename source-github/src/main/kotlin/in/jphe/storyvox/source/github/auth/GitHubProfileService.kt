package `in`.jphe.storyvox.source.github.auth

import `in`.jphe.storyvox.source.github.di.GitHubHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
 * Tiny client for `GET https://api.github.com/user`. Used post-token to
 * resolve the signed-in `@username` for the Settings UI display row.
 *
 * Issue #91. Spec: docs/superpowers/specs/2026-05-08-github-oauth-design.md
 *
 * Why a separate class (vs. extending `GitHubApi`): `GitHubApi` is
 * `internal` and its result type is `GitHubApiResult<T>` which is also
 * `internal` to the source module. The sign-in ViewModel lives in the
 * `:app` module and can't see internal types. This is a minimal public
 * facade — same authed OkHttp client (the [GitHubHttp]-qualified one
 * with the auth interceptor wired in), but a public result type.
 */
@Singleton
open class GitHubProfileService @Inject constructor(
    @GitHubHttp private val httpClient: OkHttpClient,
) {

    /**
     * Fetch the signed-in user's login + display name. Caller must have
     * already captured a token in [GitHubAuthRepository] — the auth
     * interceptor reads from there to attach the Bearer header.
     *
     * `open` so JVM unit tests can override with a canned [ProfileResult]
     * without spinning up a MockWebServer. Same pattern that
     * `GitHubApi`'s production methods use.
     */
    open suspend fun getCurrentUser(): ProfileResult = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(USER_URL)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", API_VERSION)
            .header("User-Agent", USER_AGENT)
            .build()
        val response = try {
            httpClient.newCall(req).await()
        } catch (e: IOException) {
            return@withContext ProfileResult.NetworkError(e)
        }
        response.use { r ->
            if (!r.isSuccessful) {
                return@use ProfileResult.HttpError(r.code, r.message)
            }
            val raw = r.body?.string().orEmpty()
            try {
                val parsed = JSON.decodeFromString(GhUser.serializer(), raw)
                ProfileResult.Success(login = parsed.login, name = parsed.name, id = parsed.id)
            } catch (e: Throwable) {
                ProfileResult.MalformedResponse(e.message ?: "parse failed")
            }
        }
    }

    @Serializable
    private data class GhUser(
        val login: String,
        val id: Long,
        val name: String? = null,
    )

    private companion object {
        const val USER_URL: String = "https://api.github.com/user"
        const val API_VERSION: String = "2022-11-28"
        const val USER_AGENT: String = "storyvox/0.4 (+https://github.com/jphein/storyvox)"
        val JSON: Json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
        }
    }
}

sealed class ProfileResult {
    data class Success(val login: String, val name: String?, val id: Long) : ProfileResult()
    data class NetworkError(val cause: Throwable) : ProfileResult()
    data class HttpError(val code: Int, val message: String) : ProfileResult()
    data class MalformedResponse(val message: String) : ProfileResult()
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
