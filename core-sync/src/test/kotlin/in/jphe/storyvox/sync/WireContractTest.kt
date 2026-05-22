package `in`.jphe.storyvox.sync

import `in`.jphe.storyvox.sync.client.HttpInstantBackend
import `in`.jphe.storyvox.sync.client.InstantHttpTransport
import `in`.jphe.storyvox.sync.client.SignedInUser
import `in`.jphe.storyvox.sync.client.TransportResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-format contract tests — issue #714.
 *
 * # Why this exists
 *
 * Issue #691 took down every storyvox domain because `WsInstantBackend`
 * silently assumed `{entity: [rows]}` but InstantDB's WS endpoint sends
 * raw EAV triples. No test failed; production crashed. The fix (#708)
 * moved to the admin HTTP API, which *does* return `{entity: [rows]}`
 * — but nothing was stopping the next drift from happening exactly the
 * same way.
 *
 * These tests pin the contract on both sides of the wire:
 *
 *  - **Request shape** — the body [HttpInstantBackend] sends must match
 *    the recorded `query-request-*.json` / `transact-request-*.json`
 *    fixture *structurally*. If someone changes the envelope key
 *    (`query` → `q`, `steps` → `tx-steps`, `inference?` → `inference`),
 *    this test breaks. The existing [HttpInstantBackendTest] uses
 *    `String.contains` which would not catch a rename to `"qquery"`
 *    or whitespace stripping; structural parsing does.
 *
 *  - **Response shape** — the parser must produce a [RowSnapshot] with
 *    the correct payload + updatedAt when fed the recorded response
 *    fixtures. If the server starts returning `updated_at` instead of
 *    `updatedAt`, or wraps rows in a `data` envelope, the contract
 *    test breaks here before it hits a user device.
 *
 * # When a fixture needs to change
 *
 * Update the fixture *and* the production parser in the same PR. Do
 * not coerce a parser into matching a stale fixture, and do not
 * regenerate a fixture from production output to "make the test
 * pass" — that defeats the purpose of pinning the contract. The
 * fixtures are source-of-truth for what we believe the server does,
 * and divergence means we have something real to investigate.
 */
class WireContractTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun loadFixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("instant-wire/$name")
            ?.bufferedReader()?.use { it.readText() }
            ?: error("missing wire fixture: instant-wire/$name")

    private fun loadJson(name: String): JsonElement =
        json.parseToJsonElement(loadFixture(name))

    private val user = SignedInUser(userId = "u-1", email = "x@y.com", refreshToken = "rt-1")

    // ----- Capturing transport ---------------------------------------------

    private class CapturingTransport(
        private val responseBody: String,
        private val code: Int = 200,
    ) : InstantHttpTransport {
        var lastUrl: String? = null
        var lastBody: String? = null
        var lastHeaders: Map<String, String>? = null
        override suspend fun postJson(
            url: String,
            jsonBody: String,
            headers: Map<String, String>,
        ): TransportResult {
            lastUrl = url
            lastBody = jsonBody
            lastHeaders = headers
            return TransportResult(code, responseBody)
        }
    }

    // ----- Query request shape ---------------------------------------------

    @Test fun `query request body matches the recorded fixture structurally`() = runTest {
        val transport = CapturingTransport(loadFixture("query-response-blobs-miss.json"))
        val backend = HttpInstantBackend("test-app", transport)

        backend.fetch(user, entity = "blobs", id = "settings:u-1")

        val actual = json.parseToJsonElement(transport.lastBody!!).jsonObject
        val expected = loadJson("query-request-blobs.json").jsonObject

        // The envelope keys are what catches drift; assert each branch
        // explicitly rather than `assertEquals` on the whole tree, so a
        // failure points at the renamed key, not "they differ somewhere."

        // 1. Top-level keys must match exactly. A new required field or
        //    a renamed `inference?` → `inference` shows up here.
        assertEquals(
            "top-level keys diverged from fixture",
            expected.keys.sorted(), actual.keys.sorted(),
        )

        // 2. `inference?` flag — the trailing `?` is part of the key
        //    name in Instant's wire format. A formatter that strips it
        //    will silently disable schema inference on the server.
        assertEquals(
            "inference? flag missing or renamed",
            expected["inference?"]!!.jsonPrimitive.boolean,
            actual["inference?"]!!.jsonPrimitive.boolean,
        )

        // 3. The InstaQL query envelope: `query.<entity>.$.where.id`.
        //    This is the path that was wrong in #691.
        val actualQuery = actual["query"]!!.jsonObject
        val expectedQuery = expected["query"]!!.jsonObject
        assertEquals(expectedQuery.keys, actualQuery.keys)
        assertEquals("settings:u-1",
            actualQuery["blobs"]!!.jsonObject["\$"]!!.jsonObject["where"]!!
                .jsonObject["id"]!!.jsonPrimitive.contentOrNull,
        )
    }

    @Test fun `query request swaps entity name into the InstaQL path`() = runTest {
        // The fixture pins `blobs`, but the same envelope is used for
        // `sets`, `positions`, etc. (see InstantBackend.kt kdoc).
        // A regression that hardcoded the entity to "blobs" would not
        // be caught by the fixture-as-blobs test above.
        val transport = CapturingTransport(loadFixture("query-response-blobs-miss.json"))
        val backend = HttpInstantBackend("test-app", transport)

        backend.fetch(user, entity = "positions", id = "u-1:fic-99")

        val actual = json.parseToJsonElement(transport.lastBody!!).jsonObject
        val query = actual["query"]!!.jsonObject
        assertTrue("entity key must be the caller's entity",
            query.containsKey("positions"))
        assertFalse("entity key must NOT be the fixture's hardcoded `blobs`",
            query.containsKey("blobs"))
        assertEquals("u-1:fic-99",
            query["positions"]!!.jsonObject["\$"]!!.jsonObject["where"]!!
                .jsonObject["id"]!!.jsonPrimitive.contentOrNull,
        )
    }

    // ----- Query response parsing -----------------------------------------

    @Test fun `query response hit parses into RowSnapshot with payload and updatedAt`() = runTest {
        val transport = CapturingTransport(loadFixture("query-response-blobs-hit.json"))
        val backend = HttpInstantBackend("test-app", transport)

        val res = backend.fetch(user, entity = "blobs", id = "settings:u-1")
        val row = res.getOrThrow()
        assertNotNull("hit fixture must deserialize to a row", row)

        // Cross-check the row matches what the fixture itself declares
        // — so a change to the fixture and a change to the parser stay
        // in lockstep.
        val fixtureRow = loadJson("query-response-blobs-hit.json")
            .jsonObject["blobs"]!!.jsonArray[0].jsonObject
        assertEquals(
            fixtureRow["payload"]!!.jsonPrimitive.contentOrNull,
            row!!.payload,
        )
        assertEquals(
            fixtureRow["updatedAt"]!!.jsonPrimitive.long,
            row.updatedAt,
        )
    }

    @Test fun `query response miss returns null without surfacing as error`() = runTest {
        val transport = CapturingTransport(loadFixture("query-response-blobs-miss.json"))
        val backend = HttpInstantBackend("test-app", transport)

        val res = backend.fetch(user, entity = "blobs", id = "settings:u-1")
        assertTrue("empty array is a hit-with-no-row, not an error", res.isSuccess)
        assertNull("missing row maps to null", res.getOrThrow())
    }

    @Test fun `query response with no entity key at all returns null`() = runTest {
        // Defensive: pre-#708 some server responses omitted the entity
        // key entirely when the namespace didn't yet exist. Treat that
        // the same as an empty array — null, not an exception.
        val transport = CapturingTransport(loadFixture("query-response-blobs-no-entity-key.json"))
        val backend = HttpInstantBackend("test-app", transport)

        val res = backend.fetch(user, entity = "blobs", id = "settings:u-1")
        assertTrue(res.isSuccess)
        assertNull(res.getOrThrow())
    }

    @Test fun `query 403 surfaces the server's message verbatim`() = runTest {
        val transport = CapturingTransport(
            loadFixture("query-response-error-403.json"),
            code = 403,
        )
        val backend = HttpInstantBackend("test-app", transport)

        val res = backend.fetch(user, entity = "blobs", id = "x")
        assertTrue(res.isFailure)
        val msg = res.exceptionOrNull()?.message.orEmpty()
        // Don't substring-match the whole sentence — the fixture might
        // grow more detail. Match the distinguishing token.
        assertTrue("error must contain server's `message` field, got: $msg",
            msg.contains("permission denied"))
    }

    // ----- Transact request shape -----------------------------------------

    @Test fun `transact request body matches the recorded fixture structurally`() = runTest {
        val transport = CapturingTransport(loadFixture("transact-response-ok.json"))
        val backend = HttpInstantBackend("test-app", transport)

        backend.upsert(
            user,
            entity = "blobs",
            id = "settings:u-1",
            payload = "{\"theme\":\"dark\"}",
            updatedAt = 1716393600000L,
        )

        val actual = json.parseToJsonElement(transport.lastBody!!).jsonObject
        val expected = loadJson("transact-request-update.json").jsonObject

        // 1. Top-level keys: `steps` + `throw-on-missing-attrs?`. The
        //    JS admin SDK uses `steps`, NOT `tx-steps` (which was the
        //    legacy WS envelope). #708 fixed this; pinning it here
        //    keeps it fixed.
        assertEquals(
            "top-level keys diverged from fixture",
            expected.keys.sorted(), actual.keys.sorted(),
        )
        assertTrue("steps envelope required", actual.containsKey("steps"))
        assertFalse("must not regress to legacy tx-steps", actual.containsKey("tx-steps"))

        // 2. throw-on-missing-attrs? must be false — true would reject
        //    rows in namespaces the server hasn't seen yet, which is
        //    fatal on a fresh InstantDB app.
        assertEquals(false, actual["throw-on-missing-attrs?"]!!.jsonPrimitive.boolean)

        // 3. The step tuple shape: ["update", entity, id, {payload, updatedAt}].
        //    Positional, not keyed — InstantDB transact steps are
        //    arrays, not objects.
        val steps = actual["steps"]!!.jsonArray
        assertEquals("exactly one step per upsert", 1, steps.size)
        val step = steps[0].jsonArray
        assertEquals("step must be a 4-tuple", 4, step.size)
        assertEquals("update", step[0].jsonPrimitive.contentOrNull)
        assertEquals("blobs", step[1].jsonPrimitive.contentOrNull)
        assertEquals("settings:u-1", step[2].jsonPrimitive.contentOrNull)
        val patch = step[3].jsonObject
        assertEquals("{\"theme\":\"dark\"}", patch["payload"]!!.jsonPrimitive.contentOrNull)
        assertEquals(1716393600000L, patch["updatedAt"]!!.jsonPrimitive.long)

        // 4. Sanity-check that the fixture itself satisfies the same
        //    structural rules — if someone edits the fixture into
        //    nonsense, the contract test should call it out.
        val expectedStep = expected["steps"]!!.jsonArray[0].jsonArray
        assertEquals("update", expectedStep[0].jsonPrimitive.contentOrNull)
        assertEquals(4, expectedStep.size)
    }

    // ----- Transact response parsing --------------------------------------

    @Test fun `transact 200 response is accepted regardless of body shape`() = runTest {
        // The backend only checks the status code for transact; the
        // body is server-defined and we deliberately don't pin its
        // shape (because it's evolved across InstantDB versions:
        // {"tx-id":N} vs {"transaction_id":"..."} vs {}). This test
        // ensures we don't accidentally start requiring a field.
        val transport = CapturingTransport(loadFixture("transact-response-ok.json"))
        val backend = HttpInstantBackend("test-app", transport)

        val res = backend.upsert(user, "blobs", "id", "p", 1L)
        assertTrue("200 + recorded body must succeed", res.isSuccess)
    }

    @Test fun `transact 400 surfaces the server's validation message`() = runTest {
        val transport = CapturingTransport(
            loadFixture("transact-response-error-400.json"),
            code = 400,
        )
        val backend = HttpInstantBackend("test-app", transport)

        val res = backend.upsert(user, "blobs", "id", "p", 1L)
        assertTrue(res.isFailure)
        val msg = res.exceptionOrNull()?.message.orEmpty()
        assertTrue("must surface server validation message, got: $msg",
            msg.contains("validation error"))
    }

    // ----- Fixture self-tests ---------------------------------------------
    //
    // If a fixture file is malformed JSON, every other test in this
    // class becomes meaningless — better to fail fast with a clear
    // pointer at the offending file than to debug "why is everything
    // returning null?".

    @Test fun `all fixtures are well-formed JSON`() {
        val files = listOf(
            "query-request-blobs.json",
            "query-response-blobs-hit.json",
            "query-response-blobs-miss.json",
            "query-response-blobs-no-entity-key.json",
            "query-response-error-403.json",
            "transact-request-update.json",
            "transact-response-ok.json",
            "transact-response-error-400.json",
        )
        for (f in files) {
            try {
                json.parseToJsonElement(loadFixture(f))
            } catch (e: Exception) {
                throw AssertionError("fixture $f is not valid JSON: ${e.message}", e)
            }
        }
    }

    @Test fun `query response hit fixture has the documented row shape`() {
        // Pins the row shape on the fixture side too — if someone
        // updates the fixture to drop `updatedAt`, this fails before
        // the parser tests do, with a more pointed message.
        val row = loadJson("query-response-blobs-hit.json")
            .jsonObject["blobs"]!!.jsonArray.first().jsonObject
        assertTrue("row must have an id", row["id"] is JsonPrimitive)
        assertTrue("row must have a payload string",
            row["payload"]!!.jsonPrimitive.contentOrNull != null)
        assertTrue("row must have a numeric updatedAt",
            row["updatedAt"]!!.jsonPrimitive.long > 0)
    }
}
