package `in`.jphe.storyvox.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Candela (v1.1.0) — pins the streak-tier taglines. Each celebration
 * tier has its own line; anything off-tier falls back to a generic
 * line (defensive — the host only ever passes a real tier).
 */
class BooksLitBadgeTest {

    @Test fun `each tier has its own tagline`() {
        assertEquals("Your first flame.", streakTagline(1))
        assertEquals("Five kept alight.", streakTagline(5))
        assertEquals("Ten and still burning.", streakTagline(10))
        assertEquals("A constellation of finished tales.", streakTagline(25))
    }

    @Test fun `off-tier values get a safe generic line`() {
        assertEquals("Books lit.", streakTagline(2))
        assertEquals("Books lit.", streakTagline(0))
        assertEquals("Books lit.", streakTagline(100))
    }

    @Test fun `every real streak tier has a non-generic tagline`() {
        // Ties the badge copy to the canonical tier set so adding a tier
        // without copy is caught here, not on-device.
        bookStreakTiers.forEach { tier ->
            assertTrue(
                "tier $tier is missing its own tagline",
                streakTagline(tier) != "Books lit.",
            )
        }
    }
}
