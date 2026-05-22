package `in`.jphe.storyvox.sync

import `in`.jphe.storyvox.sync.client.HttpInstantBackend
import `in`.jphe.storyvox.sync.client.InstantHttpTransport
import `in`.jphe.storyvox.sync.client.SignedInUser
import `in`.jphe.storyvox.sync.client.TransportResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the admin-HTTP-API data plane (issue #691).
 *
 * The original `WsInstantBackend` (now removed) had zero on-the-wire test
 * coverage and only ever ran against `FakeInstantBackend` in unit tests
 * — which masked the bug that took down every domain at runtime. These
 * tests assert the request shape that `instantdb/instant @ main`'s
 * admin SDK uses on `POST /admin/query` and `POST /admin/transact`, so a
 * future wire-format drift cannot land silently.
 */
class HttpInstantBackendTest {

    private class FakeTransport(
        private val responses: Map<String, TransportResult>,
    ) : InstantHttpTransport {
        data class Call(val url: String, val body: String, val headers: Map<String, String>)
        val calls = mutableListOf<Call>()
        override suspend fun postJson(
            url: String,
            jsonBody: String,
            headers: Map<String, String>,
        ): TransportResult {
            calls += Call(url, jsonBody, headers)
            val match = responses.entries.firstOrNull { url.contains(it.key) }
            return match?.value ?: TransportResult(500, """{"message":"no fake for $url"}""")
        }
    }

    private val user = SignedInUser(userId = "u-1", email = "x@y.com", refreshToken = "rt-1")

    @Test fun `fetch hits admin query with app-id query string and as-token header`() = runTest {
        val transport = FakeTransport(mapOf(
            "/admin/query" to TransportResult(200, """{"blobs":[]}"""),
        ))
        val backend = HttpInstantBackend("test-app", transport)

        val res = backend.fetch(user, entity = "blobs", id = "settings:u-1")

        assertTrue(res.isSuccess)
        assertNull(res.getOrThrow())
        val call = transport.calls.single()
        assertTrue("URL includes app_id query", call.url.endsWith("/admin/query?app_id=test-app"))
        // Headers must match the JS admin SDK exactly — content-type,
        // app-id, as-token. Critical: the `as-token` header is what gives
        // us auth without an admin token (`asUser({token})` impersonation).
        assertEquals("application/json", call.headers["content-type"])
        assertEquals("test-app", call.headers["app-id"])
        assertEquals("rt-1", call.headers["as-token"])
        // Body shape: { query: { blobs: { $: { where: { id: "settings:u-1" } } } }, inference?: false }
        assertTrue("body has query envelope", call.body.contains("\"query\""))
        assertTrue("body filters by id", call.body.contains("\"id\":\"settings:u-1\""))
        assertTrue("body has inference? flag", call.body.contains("\"inference?\":false"))
    }

    @Test fun `fetch returns the row when the server returns one`() = runTest {
        val transport = FakeTransport(mapOf(
            "/admin/query" to TransportResult(
                200,
                """{"blobs":[{"id":"settings:u-1","payload":"hello","updatedAt":12345}]}""",
            ),
        ))
        val backend = HttpInstantBackend("test-app", transport)

        val res = backend.fetch(user, entity = "blobs", id = "settings:u-1")
        val row = res.getOrThrow()
        assertNotNull(row)
        assertEquals("hello", row!!.payload)
        assertEquals(12345L, row.updatedAt)
    }

    @Test fun `fetch returns null when the response array is empty`() = runTest {
        val transport = FakeTransport(mapOf(
            "/admin/query" to TransportResult(200, """{"blobs":[]}"""),
        ))
        val backend = HttpInstantBackend("test-app", transport)

        val res = backend.fetch(user, entity = "blobs", id = "missing:u-1")
        assertTrue(res.isSuccess)
        assertNull(res.getOrThrow())
    }

    @Test fun `fetch returns null when the entity key is missing entirely`() = runTest {
        val transport = FakeTransport(mapOf(
            "/admin/query" to TransportResult(200, """{}"""),
        ))
        val backend = HttpInstantBackend("test-app", transport)

        val res = backend.fetch(user, entity = "blobs", id = "missing:u-1")
        assertTrue(res.isSuccess)
        assertNull(res.getOrThrow())
    }

    @Test fun `fetch surfaces server error message on 4xx`() = runTest {
        val transport = FakeTransport(mapOf(
            "/admin/query" to TransportResult(
                403,
                """{"message":"permission denied"}""",
            ),
        ))
        val backend = HttpInstantBackend("test-app", transport)

        val res = backend.fetch(user, entity = "blobs", id = "x")
        assertTrue(res.isFailure)
        assertTrue(
            "error surfaces server message",
            res.exceptionOrNull()?.message?.contains("permission denied") == true,
        )
    }

    @Test fun `fetch is robust against the historical EAV bug (issue 691)`() = runTest {
        // The pre-fix code crashed on this shape because it tried to do
        // `.jsonObject.get(entity)` against a JsonArray. The new HTTP path
        // doesn't even hit this shape — but a future regression must not
        // re-introduce the bug, so we assert null-on-junk rather than throw.
        val transport = FakeTransport(mapOf(
            "/admin/query" to TransportResult(200, """{"blobs":"not-an-array"}"""),
        ))
        val backend = HttpInstantBackend("test-app", transport)
        val res = backend.fetch(user, entity = "blobs", id = "x")
        assertTrue("malformed entity value yields null, not crash", res.isSuccess)
        assertNull(res.getOrThrow())
    }

    @Test fun `upsert posts a single update step with payload and updatedAt`() = runTest {
        val transport = FakeTransport(mapOf(
            "/admin/transact" to TransportResult(200, """{"tx-id":42}"""),
        ))
        val backend = HttpInstantBackend("test-app", transport)

        val res = backend.upsert(
            user,
            entity = "blobs",
            id = "settings:u-1",
            payload = "encoded-blob",
            updatedAt = 99L,
        )
        assertTrue(res.isSuccess)

        val call = transport.calls.single()
        assertTrue(call.url.endsWith("/admin/transact?app_id=test-app"))
        assertEquals("rt-1", call.headers["as-token"])
        // Body: { steps: [["update","blobs","settings:u-1",{payload,updatedAt}]], "throw-on-missing-attrs?": false }
        // — the key is `steps`, NOT `tx-steps`, per the admin SDK.
        assertTrue("body has steps envelope", call.body.contains("\"steps\""))
        assertFalse("body must NOT use tx-steps", call.body.contains("\"tx-steps\""))
        assertTrue("body includes update verb", call.body.contains("\"update\""))
        assertTrue("body includes entity", call.body.contains("\"blobs\""))
        assertTrue("body includes id", call.body.contains("\"settings:u-1\""))
        assertTrue("body includes payload", call.body.contains("\"payload\":\"encoded-blob\""))
        assertTrue("body includes updatedAt", call.body.contains("\"updatedAt\":99"))
    }

    @Test fun `upsert surfaces server error on transact failure`() = runTest {
        val transport = FakeTransport(mapOf(
            "/admin/transact" to TransportResult(
                400,
                """{"message":"validation error"}""",
            ),
        ))
        val backend = HttpInstantBackend("test-app", transport)

        val res = backend.upsert(user, "blobs", "id", "payload", 1L)
        assertTrue(res.isFailure)
        assertTrue(
            res.exceptionOrNull()?.message?.contains("validation error") == true,
        )
    }

    @Test fun `placeholder appId disables the backend`() = runTest {
        val transport = FakeTransport(emptyMap())
        val backend = HttpInstantBackend(HttpInstantBackend.PLACEHOLDER_APP_ID, transport)
        assertFalse(backend.isConfigured)
        val r1 = backend.fetch(user, "blobs", "x")
        val r2 = backend.upsert(user, "blobs", "x", "p", 1L)
        assertTrue(r1.isFailure)
        assertTrue(r2.isFailure)
        assertEquals("no network calls made", 0, transport.calls.size)
    }
}
