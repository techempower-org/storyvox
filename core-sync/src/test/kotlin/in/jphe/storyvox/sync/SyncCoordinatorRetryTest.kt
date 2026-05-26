package `in`.jphe.storyvox.sync

import `in`.jphe.storyvox.sync.coordinator.SyncOutcome
import `in`.jphe.storyvox.sync.coordinator.runWithRetry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #779 — `SyncOutcome.Transient` documents retry, but the
 * coordinator previously ran each syncer call exactly once. These tests
 * lock in the retry-with-bounded-backoff behaviour added to
 * `SyncCoordinator.runWithRetry`:
 *
 *  - `Ok` on first try → no retry.
 *  - `Permanent` on first try → no retry (per kdoc).
 *  - `Transient` → retried up to `backoffs.size` times, then surfaced.
 *  - `Transient` → `Ok` on a later attempt → final outcome is `Ok`.
 *  - Thrown exception → mapped to `Transient` and also retried.
 *
 * `runTest` gives us virtual time so the 1s/3s/9s prod delays cost zero
 * wall-clock seconds in the test JVM.
 */
class SyncCoordinatorRetryTest {

    private val BACKOFFS = longArrayOf(1_000L, 3_000L, 9_000L)

    @Test
    fun `Ok on first attempt — no retry`() = runTest {
        var calls = 0
        val result = runWithRetry("library", BACKOFFS) {
            calls++
            SyncOutcome.Ok(recordsAffected = 7)
        }
        assertEquals(1, calls)
        assertEquals(1, result.attempts)
        val outcome = result.outcome
        assertTrue(outcome is SyncOutcome.Ok)
        assertEquals(7, (outcome as SyncOutcome.Ok).recordsAffected)
    }

    @Test
    fun `Permanent on first attempt — no retry`() = runTest {
        var calls = 0
        val result = runWithRetry("library", BACKOFFS) {
            calls++
            SyncOutcome.Permanent("bad credentials")
        }
        assertEquals(1, calls)
        assertEquals(1, result.attempts)
        val outcome = result.outcome
        assertTrue(outcome is SyncOutcome.Permanent)
        assertEquals("bad credentials", (outcome as SyncOutcome.Permanent).message)
    }

    @Test
    fun `Transient every time — retries up to bound then surfaces Transient`() = runTest {
        var calls = 0
        val result = runWithRetry("library", BACKOFFS) {
            calls++
            SyncOutcome.Transient("dns timeout")
        }
        // 1 initial + 3 retries = 4 total attempts.
        assertEquals(4, calls)
        assertEquals(4, result.attempts)
        val outcome = result.outcome
        assertTrue(outcome is SyncOutcome.Transient)
        assertEquals("dns timeout", (outcome as SyncOutcome.Transient).message)
    }

    @Test
    fun `Transient then Ok — stops retrying, returns Ok`() = runTest {
        var calls = 0
        val result = runWithRetry("library", BACKOFFS) {
            calls++
            if (calls < 3) SyncOutcome.Transient("flaky") else SyncOutcome.Ok(2)
        }
        assertEquals(3, calls)
        assertEquals(3, result.attempts)
        val outcome = result.outcome
        assertTrue(outcome is SyncOutcome.Ok)
        assertEquals(2, (outcome as SyncOutcome.Ok).recordsAffected)
    }

    @Test
    fun `Transient then Permanent — stops retrying, returns Permanent`() = runTest {
        var calls = 0
        val result = runWithRetry("library", BACKOFFS) {
            calls++
            if (calls == 1) SyncOutcome.Transient("blip") else SyncOutcome.Permanent("schema mismatch")
        }
        // First attempt Transient → retry. Second attempt Permanent → stop.
        assertEquals(2, calls)
        assertEquals(2, result.attempts)
        val outcome = result.outcome
        assertTrue(outcome is SyncOutcome.Permanent)
        assertEquals("schema mismatch", (outcome as SyncOutcome.Permanent).message)
    }

    @Test
    fun `Thrown exception is mapped to Transient and retried`() = runTest {
        var calls = 0
        val result = runWithRetry("library", BACKOFFS) {
            calls++
            if (calls < 2) throw java.io.IOException("connection reset")
            SyncOutcome.Ok(0)
        }
        assertEquals(2, calls)
        assertEquals(2, result.attempts)
        assertTrue(result.outcome is SyncOutcome.Ok)
    }

    @Test
    fun `Thrown exception every time — surfaces Transient with exception message`() = runTest {
        var calls = 0
        val result = runWithRetry("library", BACKOFFS) {
            calls++
            throw java.io.IOException("offline")
        }
        assertEquals(4, calls)
        val outcome = result.outcome
        assertTrue(outcome is SyncOutcome.Transient)
        assertEquals("offline", (outcome as SyncOutcome.Transient).message)
    }

    @Test
    fun `Empty backoff array — no retries even on Transient`() = runTest {
        var calls = 0
        val result = runWithRetry("library", longArrayOf()) {
            calls++
            SyncOutcome.Transient("never gets a second chance")
        }
        assertEquals(1, calls)
        assertEquals(1, result.attempts)
        assertTrue(result.outcome is SyncOutcome.Transient)
    }
}
