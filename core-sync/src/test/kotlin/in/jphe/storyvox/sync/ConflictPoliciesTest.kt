package `in`.jphe.storyvox.sync

import `in`.jphe.storyvox.sync.coordinator.ConflictPolicies
import `in`.jphe.storyvox.sync.coordinator.Stamped
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflictPoliciesTest {

    @Test fun `lastWriteWins picks the higher updatedAt`() {
        val local = Stamped(value = "A", updatedAt = 100L)
        val remote = Stamped(value = "B", updatedAt = 200L)
        assertEquals(remote, ConflictPolicies.lastWriteWins(local, remote))
    }

    @Test fun `lastWriteWins tie goes to local`() {
        val local = Stamped("A", 100L)
        val remote = Stamped("B", 100L)
        assertEquals(local, ConflictPolicies.lastWriteWins(local, remote))
    }

    @Test fun `unionWithTombstones merges and respects deletes`() {
        val local = setOf("a", "b", "c")
        val remote = setOf("c", "d", "e")
        val tombs = setOf("b") // we removed b on some device
        val merged = ConflictPolicies.unionWithTombstones(local, remote, tombs)
        assertEquals(setOf("a", "c", "d", "e"), merged)
        assertFalse("b should be tombstoned out", "b" in merged)
    }

    @Test fun `unionWithTombstones empty inputs`() {
        val empty: Set<String> = emptySet()
        assertEquals(empty, ConflictPolicies.unionWithTombstones(empty, empty, empty))
    }

    @Test fun `unionWithTombstones tombstones not present in either side`() {
        // Tombstone for an id that has already been propagated everywhere
        // is a no-op (the id is gone, the tombstone is harmless).
        val merged = ConflictPolicies.unionWithTombstones(setOf("a"), setOf("a"), setOf("z"))
        assertEquals(setOf("a"), merged)
    }

    @Test fun `unionWithTombstoneStamps applies the freshness window to expired tombstones`() {
        // Issue #360 finding 3 (argus): an expired tombstone (older
        // than `tombstoneTtlMs` from `now`) does NOT block the
        // corresponding id from appearing in the merged set.
        val local = setOf("a")
        val remote = setOf<String>()
        val ttlMs = 1000L
        val now = 10_000L
        // Tombstone for "a" was recorded WAY before the TTL window.
        val expiredTombs = mapOf("a" to 5_000L) // delta = 5000ms > 1000ms TTL
        val merged = ConflictPolicies.unionWithTombstoneStamps(
            local = local, remote = remote, tombstones = expiredTombs,
            now = now, tombstoneTtlMs = ttlMs,
        )
        // "a" survives the expired tombstone.
        assertTrue("a" in merged)
    }

    @Test fun `unionWithTombstoneStamps still blocks within the freshness window`() {
        // A fresh tombstone (within the TTL) DOES block the id, same
        // as the old behaviour. This guards the "delete still
        // propagates" contract from being accidentally regressed.
        val local = setOf("a", "b")
        val remote = setOf("a", "b")
        val ttlMs = 1000L
        val now = 10_000L
        val freshTombs = mapOf("b" to 9_500L) // delta = 500ms < 1000ms TTL
        val merged = ConflictPolicies.unionWithTombstoneStamps(
            local = local, remote = remote, tombstones = freshTombs,
            now = now, tombstoneTtlMs = ttlMs,
        )
        assertTrue("a" in merged)
        assertFalse("b should be blocked by fresh tombstone", "b" in merged)
    }

    @Test fun `maxScalar picks the higher`() {
        assertEquals(200, ConflictPolicies.maxScalar(100, 200))
        assertEquals(200, ConflictPolicies.maxScalar(200, 100))
        assertEquals(200, ConflictPolicies.maxScalar(200, 200))
    }

    @Test fun `maxScalarStamped picks the newer updatedAt regardless of value`() {
        val local = Stamped(value = 9999, updatedAt = 100L)
        val remote = Stamped(value = 1, updatedAt = 200L)
        // Even though local has a higher value, remote is more recent.
        assertEquals(remote, ConflictPolicies.maxScalarStamped(local, remote))
    }

    @Test fun `maxScalarStamped tie on updatedAt prefers higher value`() {
        val local = Stamped(value = 5, updatedAt = 100L)
        val remote = Stamped(value = 10, updatedAt = 100L)
        assertEquals(remote, ConflictPolicies.maxScalarStamped(local, remote))
    }

    @Test fun `maxScalarStamped tie on updatedAt and value prefers local`() {
        val local = Stamped(value = 5, updatedAt = 100L)
        val remote = Stamped(value = 5, updatedAt = 100L)
        assertTrue(local === ConflictPolicies.maxScalarStamped(local, remote))
    }

    // ── mergeStampedMap (#978 field-level merge) ───────────────────

    @Test fun `mergeStampedMap unions disjoint keys — both survive`() {
        val local = mapOf("theme" to Stamped("dark", 1L))
        val remote = mapOf("speed" to Stamped("1.5", 1L))
        val merged = ConflictPolicies.mergeStampedMap(local, remote)
        assertEquals(2, merged.size)
        assertEquals("dark", merged["theme"]!!.value)
        assertEquals("1.5", merged["speed"]!!.value)
    }

    @Test fun `mergeStampedMap newest-per-key wins independently`() {
        val local = mapOf(
            "theme" to Stamped("light", 2_000L), // older
            "speed" to Stamped("3.0", 9_000L),   // newer
        )
        val remote = mapOf(
            "theme" to Stamped("dark", 5_000L),  // newer → wins
            "speed" to Stamped("1.0", 1_000L),   // older
        )
        val merged = ConflictPolicies.mergeStampedMap(local, remote)
        assertEquals("dark", merged["theme"]!!.value)
        assertEquals("3.0", merged["speed"]!!.value)
    }

    @Test fun `mergeStampedMap tie on a key prefers local value`() {
        val local = mapOf("k" to Stamped("LOCAL", 100L))
        val remote = mapOf("k" to Stamped("REMOTE", 100L))
        val merged = ConflictPolicies.mergeStampedMap(local, remote)
        assertEquals("LOCAL", merged["k"]!!.value)
    }

    @Test fun `mergeStampedMap empty remote returns local unchanged`() {
        val local = mapOf("k" to Stamped("v", 1L))
        assertEquals(local, ConflictPolicies.mergeStampedMap(local, emptyMap()))
    }

    @Test fun `mergeStampedMap empty local returns remote unchanged`() {
        val remote = mapOf("k" to Stamped("v", 1L))
        assertEquals(remote, ConflictPolicies.mergeStampedMap(emptyMap(), remote))
    }

    @Test fun `mergeStampedMap keeps each side's local-only keys plus newest shared`() {
        val local = mapOf("a" to Stamped("la", 5L), "shared" to Stamped("ls", 5L))
        val remote = mapOf("b" to Stamped("rb", 5L), "shared" to Stamped("rs", 9L))
        val merged = ConflictPolicies.mergeStampedMap(local, remote)
        assertEquals("la", merged["a"]!!.value)
        assertEquals("rb", merged["b"]!!.value)
        assertEquals("rs", merged["shared"]!!.value) // remote newer (9 > 5)
        assertEquals(9L, merged["shared"]!!.updatedAt)
    }
}
