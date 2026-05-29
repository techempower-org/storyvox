package `in`.jphe.storyvox.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #789 — coverage for `categorizeFriendlyError`, the pure-Kotlin
 * half of the FriendlyError split. The Composable wrapper just maps
 * each [FriendlyErrorKind] to a string resource; the matching logic
 * is what can drift without notice, so this is where the regression
 * fence lives.
 */
class FriendlyErrorCategorizationTest {

    @Test
    fun `null raw maps to Generic with no fallback`() {
        val (kind, fallback) = categorizeFriendlyError(null)
        assertEquals(FriendlyErrorKind.Generic, kind)
        assertNull(fallback)
    }

    @Test
    fun `empty raw maps to Generic with no fallback`() {
        // #935 — empty string used to land on `Raw to ""`, surfacing an
        // empty error to the user. Must behave the same as null.
        val (kind, fallback) = categorizeFriendlyError("")
        assertEquals(FriendlyErrorKind.Generic, kind)
        assertNull(fallback)
    }

    @Test
    fun `whitespace-only raw maps to Generic with no fallback`() {
        // #935 — whitespace-only (spaces, tabs, newlines) is the other
        // shape of "nothing to show" we used to leak through.
        val (kind, fallback) = categorizeFriendlyError("   \t\n  ")
        assertEquals(FriendlyErrorKind.Generic, kind)
        assertNull(fallback)
    }

    @Test
    fun `HTTP 0 timeout maps to NetworkLost`() {
        assertEquals(
            FriendlyErrorKind.NetworkLost,
            categorizeFriendlyError("HTTP 0: Network timeout: timeout").first
        )
    }

    @Test
    fun `unknown host maps to NetworkLost`() {
        assertEquals(
            FriendlyErrorKind.NetworkLost,
            categorizeFriendlyError("java.net.UnknownHostException: royalroad.com").first
        )
    }

    @Test
    fun `socket timeout maps to NetworkLost`() {
        assertEquals(
            FriendlyErrorKind.NetworkLost,
            categorizeFriendlyError("java.net.SocketTimeoutException").first
        )
    }

    @Test
    fun `connection refused maps to NetworkLost`() {
        assertEquals(
            FriendlyErrorKind.NetworkLost,
            categorizeFriendlyError("Failed to connect to host: Connection refused").first
        )
    }

    @Test
    fun `cloudflare challenge maps to CloudflareChallenge`() {
        assertEquals(
            FriendlyErrorKind.CloudflareChallenge,
            categorizeFriendlyError("Cloudflare challenge could not be solved").first
        )
        assertEquals(
            FriendlyErrorKind.CloudflareChallenge,
            categorizeFriendlyError("got a JS challenge page").first
        )
    }

    @Test
    fun `rate limited maps to RateLimited`() {
        assertEquals(
            FriendlyErrorKind.RateLimited,
            categorizeFriendlyError("HTTP 429: Too Many Requests").first
        )
        assertEquals(
            FriendlyErrorKind.RateLimited,
            categorizeFriendlyError("rate limited by gate").first
        )
        assertEquals(
            FriendlyErrorKind.RateLimited,
            categorizeFriendlyError("Retry after 30s").first
        )
    }

    @Test
    fun `5xx maps to ServerError`() {
        assertEquals(
            FriendlyErrorKind.ServerError,
            categorizeFriendlyError("HTTP 500: Internal Server Error").first
        )
        assertEquals(
            FriendlyErrorKind.ServerError,
            categorizeFriendlyError("HTTP 503").first
        )
    }

    @Test
    fun `401 and 403 map to AuthFailed`() {
        assertEquals(
            FriendlyErrorKind.AuthFailed,
            categorizeFriendlyError("HTTP 401: Unauthorized").first
        )
        assertEquals(
            FriendlyErrorKind.AuthFailed,
            categorizeFriendlyError("HTTP 403: Forbidden").first
        )
    }

    @Test
    fun `not configured maps to NotConfigured`() {
        assertEquals(
            FriendlyErrorKind.NotConfigured,
            categorizeFriendlyError("Palace host not configured").first
        )
        assertEquals(
            FriendlyErrorKind.NotConfigured,
            categorizeFriendlyError("Source is not configured yet").first
        )
    }

    @Test
    fun `other 4xx maps to UnexpectedResponse`() {
        assertEquals(
            FriendlyErrorKind.UnexpectedResponse,
            categorizeFriendlyError("HTTP 418: I'm a teapot").first
        )
        assertEquals(
            FriendlyErrorKind.UnexpectedResponse,
            categorizeFriendlyError("HTTP 422: Unprocessable Entity").first
        )
    }

    @Test
    fun `unmatched maps to Raw with the original message as fallback`() {
        val (kind, fallback) = categorizeFriendlyError("The chapter list is empty.")
        assertEquals(FriendlyErrorKind.Raw, kind)
        assertEquals("The chapter list is empty.", fallback)
    }

    @Test
    fun `match order — 401 beats generic 4xx`() {
        // The fence here: 401 must hit AuthFailed before "HTTP 4" fires
        // UnexpectedResponse. If someone reorders the when branches and
        // breaks this, the user sees "unexpected response" for a
        // re-paste-credentials situation, which is confusing copy.
        assertEquals(
            FriendlyErrorKind.AuthFailed,
            categorizeFriendlyError("HTTP 401: Unauthorized").first
        )
    }

    @Test
    fun `match order — 429 beats generic 4xx`() {
        // Same fence for rate-limited: "too many requests" must win
        // over the generic HTTP 4 branch.
        assertEquals(
            FriendlyErrorKind.RateLimited,
            categorizeFriendlyError("HTTP 429: Too Many Requests").first
        )
    }
}
