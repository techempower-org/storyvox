package `in`.jphe.storyvox.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #981 — the id→sourceId mapping that lets the back-fill worker
 * hydrate placeholder rows whose persisted sourceId was wrong. The cases
 * here are the exact id shapes pulled off JP's stuck library:
 *  - bare numeric Royal Road ids ("8894", "146000")
 *  - radio station-prefixed ids ("somafm-groove-salad:live")
 *  - well-formed colon-prefixed ids ("gutenberg:84", "ao3:123", …)
 */
class FictionSourceIdResolverTest {

    // A representative bound set (matches the production registry keys
    // that matter for these cases).
    private val bound = setOf(
        "royalroad", "radio", "kvmr", "gutenberg", "ao3", "arxiv",
        "github", "wikipedia", "notion", "readability",
    )

    @Test fun `bare numeric id resolves to royalroad`() {
        assertEquals("royalroad", FictionSourceIdResolver.resolve("8894", bound, storedSourceId = "8894"))
        assertEquals("royalroad", FictionSourceIdResolver.resolve("146000", bound, storedSourceId = "146000"))
    }

    @Test fun `radio station prefixed id resolves to radio`() {
        // "somafm-groove-salad" is not a bound key; the colon shape +
        // unbound prefix routes to radio.
        assertEquals(
            "radio",
            FictionSourceIdResolver.resolve("somafm-groove-salad:live", bound, storedSourceId = "somafm-groove-salad"),
        )
    }

    @Test fun `well-formed colon-prefixed id uses its prefix`() {
        assertEquals("gutenberg", FictionSourceIdResolver.resolve("gutenberg:84", bound, storedSourceId = "gutenberg"))
        assertEquals("ao3", FictionSourceIdResolver.resolve("ao3:85536036", bound, storedSourceId = "ao3"))
        assertEquals("wikipedia", FictionSourceIdResolver.resolve("wikipedia:Jeffrey_Chen", bound, storedSourceId = "wikipedia"))
    }

    @Test fun `stored sourceId is trusted when already bound`() {
        // A row written by the normal browse path: id is bare-numeric but
        // sourceId is already correct — don't second-guess it.
        assertEquals("royalroad", FictionSourceIdResolver.resolve("156215", bound, storedSourceId = "royalroad"))
    }

    @Test fun `kvmr legacy alias prefix resolves to itself when bound`() {
        // kvmr is bound (migration alias), so a kvmr:live id keeps it.
        assertEquals("kvmr", FictionSourceIdResolver.resolve("kvmr:live", bound, storedSourceId = "kvmr"))
    }

    @Test fun `unbound source returns null`() {
        // A colon-prefixed id whose prefix is unbound AND radio not bound.
        assertNull(FictionSourceIdResolver.resolve("telegram:123", setOf("gutenberg"), storedSourceId = "telegram"))
        // A bare id when royalroad isn't bound (degenerate build).
        assertNull(FictionSourceIdResolver.resolve("8894", setOf("gutenberg"), storedSourceId = "8894"))
    }

    @Test fun `resolveByShape returns royalroad for colon-less and prefix otherwise`() {
        assertEquals("royalroad", FictionSourceIdResolver.resolveByShape("8894"))
        assertEquals("gutenberg", FictionSourceIdResolver.resolveByShape("gutenberg:84"))
        assertEquals("somafm-groove-salad", FictionSourceIdResolver.resolveByShape("somafm-groove-salad:live"))
    }
}
