package `in`.jphe.storyvox.sync

import `in`.jphe.storyvox.sync.client.InstantClient
import `in`.jphe.storyvox.sync.client.InstantHttpTransport
import `in`.jphe.storyvox.sync.client.SyncAuthResult
import `in`.jphe.storyvox.sync.client.TransportResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the magic-code auth path against a recorded-shape fake transport.
 * The fake records the request URL + body so we can assert the wire shape
 * matches what `instantdb/instant` actually expects on the runtime
 * endpoints (kebab-case `app-id`, `refresh-token`).
 */
class InstantClientTest {

    private class FakeTransport(
        private val responses: Map<String, TransportResult>,
    ) : InstantHttpTransport {
        val calls = mutableListOf<Pair<String, String>>()
        override suspend fun postJson(
            url: String,
            jsonBody: String,
            headers: Map<String, String>,
        ): TransportResult {
            calls += url to jsonBody
            // Match by suffix so the test doesn't need to spell the full URL.
            val match = responses.entries.firstOrNull { url.endsWith(it.key) }
            return match?.value ?: TransportResult(500, "no fake for $url")
        }
    }

    @Test fun `sendMagicCode hits the right endpoint with kebab-case appId`() = runTest {
        val transport = FakeTransport(mapOf(
            "/runtime/auth/send_magic_code" to TransportResult(200, """{"sent":true}"""),
        ))
        val client = InstantClient("test-app", transport)
        val r = client.sendMagicCode("user@example.com")
        assertTrue(r is SyncAuthResult.Ok)
        assertEquals(1, transport.calls.size)
        val (url, body) = transport.calls.single()
        assertTrue("hits send_magic_code", url.endsWith("/runtime/auth/send_magic_code"))
        // The wire format must use kebab-case "app-id" — this is the
        // exact field instantdb/instant's authAPI.ts posts.
        assertTrue("body has app-id field", body.contains("\"app-id\":\"test-app\""))
        assertTrue("body has email", body.contains("\"email\":\"user@example.com\""))
    }

    @Test fun `verifyMagicCode returns the user envelope on success`() = runTest {
        val responseBody = """{"user":{"id":"u-1","email":"x@y.com","refresh_token":"rt-1"}}"""
        val transport = FakeTransport(mapOf(
            "/runtime/auth/verify_magic_code" to TransportResult(200, responseBody),
        ))
        val client = InstantClient("test-app", transport)
        val r = client.verifyMagicCode("x@y.com", "123456")
        check(r is SyncAuthResult.Ok)
        assertEquals("u-1", r.value.id)
        assertEquals("x@y.com", r.value.email)
        assertEquals("rt-1", r.value.refreshToken)
    }

    @Test fun `verifyRefreshToken returns Err on 401`() = runTest {
        val transport = FakeTransport(mapOf(
            "/runtime/auth/verify_refresh_token" to TransportResult(401, """{"message":"token expired"}"""),
        ))
        val client = InstantClient("test-app", transport)
        val r = client.verifyRefreshToken("stale-token")
        check(r is SyncAuthResult.Err)
        assertTrue(r.message.contains("token expired"))
    }

    @Test fun `signOut tolerates network failure (best-effort)`() = runTest {
        val transport = FakeTransport(mapOf(
            "/runtime/signout" to TransportResult(0, "io"),
        ))
        val client = InstantClient("test-app", transport)
        // Sign-out's contract is "if we can't reach the server, still
        // act locally as signed out" — the client should not surface
        // a network error to the caller. Local state cleanup is the
        // caller's job.
        val r = client.signOut("rt-1")
        assertTrue(r is SyncAuthResult.Ok)
    }

    @Test fun `sendMagicCode treats sent=false as an error`() = runTest {
        val transport = FakeTransport(mapOf(
            "/runtime/auth/send_magic_code" to TransportResult(200, """{"sent":false}"""),
        ))
        val client = InstantClient("test-app", transport)
        val r = client.sendMagicCode("user@example.com")
        // Defensive — a 200 with sent:false should be surfaced rather
        // than letting the UI advance to the code-entry screen with no
        // code in the user's inbox.
        check(r is SyncAuthResult.Err)
    }
}
