package `in`.jphe.storyvox.source.gutenberg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #718 — guard `parseGutenbergId` against silently routing
 * malformed fictionIds to the `0.epub` cache file.
 *
 * Pre-fix, `epubFileFor` used `parseGutenbergId(fictionId) ?: 0`,
 * which meant every unparseable id (`gutenberg:abc`, empty string,
 * deep-link typo `gutenberg:1342x`, no-prefix `1342`) shared a single
 * on-disk path. The first write poisoned the cache; later reads
 * surfaced a confusing "unparseable EPUB" error rather than an
 * invalid-id error.
 *
 * Post-fix, `parseGutenbergId` is the sole authority on what counts as
 * a valid fictionId. `null` propagates up to `NotFound("Invalid
 * Gutenberg id: ...")` rather than collapsing onto a sentinel.
 */
class ParseGutenbergIdTest {

    @Test
    fun `well-formed gutenberg id parses to int`() {
        assertEquals(1342, parseGutenbergId("gutenberg:1342"))
        assertEquals(0, parseGutenbergId("gutenberg:0"))
        assertEquals(70000, parseGutenbergId("gutenberg:70000"))
    }

    @Test
    fun `gutenberg id with trailing non-digits returns null`() {
        // Deep-link typo path from the issue. Pre-fix this hit `?: 0`
        // and collided with a real `gutenberg:0` cache entry.
        assertNull(parseGutenbergId("gutenberg:1342x"))
        assertNull(parseGutenbergId("gutenberg:1342abc"))
    }

    @Test
    fun `gutenberg id with no number after prefix returns null`() {
        // `gutenberg:` with nothing after — empty tail.
        assertNull(parseGutenbergId("gutenberg:"))
    }

    @Test
    fun `id without gutenberg prefix returns null`() {
        // `substringAfter("gutenberg:", missingDelimiterValue = "")`
        // returns "" when the prefix is missing, which then trips the
        // isNotEmpty filter.
        assertNull(parseGutenbergId("1342"))
        assertNull(parseGutenbergId("royalroad:1342"))
    }

    @Test
    fun `empty string returns null`() {
        assertNull(parseGutenbergId(""))
    }

    @Test
    fun `non-numeric tail returns null`() {
        // Pure garbage. Pre-fix this also hit `?: 0`.
        assertNull(parseGutenbergId("gutenberg:abc"))
        assertNull(parseGutenbergId("gutenberg:not-a-number"))
    }
}
