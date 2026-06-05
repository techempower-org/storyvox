package `in`.jphe.storyvox.source.readability.net

import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.source.readability.di.ReadabilityHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin okhttp wrapper for the magic-link Readability backend. One
 * shot per URL: GET, sniff content-type, fail-fast on anything that
 * doesn't look like HTML the extractor can chew on.
 *
 * Separate from the app-wide OkHttpClient via the [ReadabilityHttp]
 * qualifier so slow-loading article pages don't poison the shared
 * pool's timeouts.
 */
@Singleton
class ReadabilityFetcher @Inject constructor(
    @ReadabilityHttp private val client: OkHttpClient,
) {

    /**
     * Fetch the raw HTML at [url]. Returns the body string on success
     * or a [FictionResult.Failure] mapped from the response state.
     */
    suspend fun fetch(url: String): FictionResult<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> FictionResult.NotFound(
                        "Article at $url returned 404",
                    )
                    response.code == 429 -> FictionResult.RateLimited(
                        retryAfter = null,
                        message = "Article server rate-limited the request (429)",
                    )
                    !response.isSuccessful -> FictionResult.NetworkError(
                        "Article fetch failed: HTTP ${response.code}",
                    )
                    else -> {
                        val contentType = response.header("Content-Type")?.lowercase().orEmpty()
                        // Reject obviously-non-HTML payloads early — we'd just
                        // produce a garbage extraction otherwise. PDFs and
                        // pure-binary endpoints belong on follow-up backends.
                        if (contentType.isNotBlank() &&
                            !contentType.contains("html") &&
                            !contentType.contains("xml")
                        ) {
                            FictionResult.NetworkError(
                                "Article fetch returned $contentType — Readability handles HTML/XML only.",
                            )
                        } else {
                            val body = response.body?.string()
                            if (body.isNullOrBlank()) {
                                FictionResult.NetworkError("Article body was empty")
                            } else {
                                FictionResult.Success(body)
                            }
                        }
                    }
                }
            }
        } catch (e: IOException) {
            FictionResult.NetworkError("Article fetch IO error: ${e.message}", cause = e)
        }
    }

    private companion object {
        // Polite UA — identifies us as a personal audiobook reader so a
        // server admin who notices the traffic can see what it's for.
        // Same convention as the RSS / Wikipedia / Outline backends.
        const val USER_AGENT = "storyvox-readability/1.0 (+https://github.com/techempower-org/candela)"
    }
}
