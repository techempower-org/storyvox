package `in`.jphe.storyvox.feature.techempower

import `in`.jphe.storyvox.data.TechEmpowerLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #516 / #775 — pins the data contract for the TechEmpower
 * helpline affordances. After #775 removed the 988 and 911 surfaces,
 * only the 211 (United Way social services) constant survives.
 */
class EmergencyHelpCardContractTest {

    @Test
    fun `primary helpline constant is 211`() {
        assertEquals("211", TechEmpowerLinks.PRIMARY_HELP_NUMBER)
    }

    @Test
    fun `telUri produces canonical tel scheme for 211`() {
        assertEquals(
            "tel:211",
            TechEmpowerLinks.telUri(TechEmpowerLinks.PRIMARY_HELP_NUMBER),
        )
    }

    @Test
    fun `primary helpline constant is non-empty digit string`() {
        val n = TechEmpowerLinks.PRIMARY_HELP_NUMBER
        assertTrue("Helpline number must be non-blank: '$n'", n.isNotBlank())
        assertTrue(
            "Helpline number must be digits-only: '$n'",
            n.all { it.isDigit() },
        )
    }

    @Test
    fun `TechEmpowerHomeScreen composable still exists`() {
        val cls = runCatching {
            Class.forName("in.jphe.storyvox.feature.techempower.TechEmpowerHomeScreenKt")
        }.getOrNull()
        assertNotNull(
            "TechEmpowerHomeScreen composable missing.",
            cls,
        )
    }
}
