package `in`.jphe.storyvox.navigation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Follow-up to #440 / #467 — pins the seven new subscreen route
 * constants and the rules they collectively follow:
 *
 *  - Every settings subscreen route starts with `settings/`. The
 *    NavHost routing them is prefix-agnostic, but the prefix is a
 *    semantic anchor that downstream code (deep-link parsers,
 *    analytics URI mapping, log-grep) leans on. Drift would break
 *    those silently.
 *  - Routes are unique. Two routes resolving to the same string
 *    would shadow each other in the NavHost.
 *  - The legacy [StoryvoxRoutes.SETTINGS] page is preserved as an
 *    "All settings" escape hatch and stays reachable. The hub's
 *    "All settings" row routes there explicitly.
 */
class SettingsSubscreenRoutesTest {

    private val newRoutes = listOf(
        StoryvoxRoutes.SETTINGS_VOICE_PLAYBACK,
        StoryvoxRoutes.SETTINGS_READING,
        StoryvoxRoutes.SETTINGS_PERFORMANCE,
        StoryvoxRoutes.SETTINGS_AI,
        StoryvoxRoutes.SETTINGS_ACCOUNT,
        StoryvoxRoutes.SETTINGS_MEMORY_PALACE,
        StoryvoxRoutes.SETTINGS_ABOUT,
    )

    @Test
    fun `seven new subscreen routes are declared`() {
        assertEquals(7, newRoutes.size)
    }

    @Test
    fun `every settings subscreen route uses the settings prefix`() {
        for (route in newRoutes) {
            assertTrue(
                "Route '$route' must start with 'settings/' so deep-link/log " +
                    "parsers can pin the settings scope by prefix.",
                route.startsWith("settings/"),
            )
        }
    }

    @Test
    fun `new subscreen routes are unique`() {
        val duplicates = newRoutes.groupingBy { it }.eachCount().filterValues { it > 1 }
        assertTrue(
            "Settings subscreen route duplication: $duplicates",
            duplicates.isEmpty(),
        )
    }

    @Test
    fun `new subscreen routes do not collide with pre-existing settings routes`() {
        val preExisting = setOf(
            StoryvoxRoutes.SETTINGS_HUB,
            StoryvoxRoutes.SETTINGS,
            StoryvoxRoutes.SETTINGS_PRONUNCIATION,
            StoryvoxRoutes.VOICE_LIBRARY,
            StoryvoxRoutes.SETTINGS_AI_SESSIONS,
            StoryvoxRoutes.SETTINGS_DEBUG,
            StoryvoxRoutes.SETTINGS_PLUGINS,
        )
        for (route in newRoutes) {
            assertTrue(
                "New subscreen route '$route' collides with a pre-existing settings route.",
                route !in preExisting,
            )
        }
    }

    @Test
    fun `legacy SETTINGS page is still reachable for the All settings escape hatch`() {
        assertEquals("settings", StoryvoxRoutes.SETTINGS)
    }

    @Test
    fun `SETTINGS_HUB stays the gear-icon destination`() {
        assertEquals("settings/hub", StoryvoxRoutes.SETTINGS_HUB)
    }

    /**
     * Pins that every NavHost call site wiring `onOpenAiSettings`
     * routes to [StoryvoxRoutes.SETTINGS_AI], not the legacy
     * [StoryvoxRoutes.SETTINGS] long-scroll page. Earlier revisions
     * dumped users into the 3,600-line legacy page when they tapped
     * "Open AI settings" from contexts like the recap modal, the
     * chat empty state, or the chat error banner — pages where the
     * user is specifically reaching for AI configuration. Routing
     * them through the dedicated AI subscreen is the whole point of
     * the hub follow-up to #440.
     *
     * Scans the NavHost source file textually because Compose-UI-test
     * infrastructure to introspect actual navigation wiring isn't on
     * the JVM unit-test path. A `git grep` regression net is cheaper
     * than nothing.
     */
    @Test
    fun `every onOpenAiSettings call site routes to the dedicated AI subscreen`() {
        val navHost = locateNavHostSource()
        val src = navHost.readText()
        val callSiteRegex = Regex(
            """onOpenAiSettings\s*=\s*\{\s*navController\.navigate\(StoryvoxRoutes\.([A-Z_]+)\)""",
        )
        val matches = callSiteRegex.findAll(src).map { it.groupValues[1] }.toList()
        assertTrue(
            "Expected at least one onOpenAiSettings = { navController.navigate(...) } " +
                "call site in StoryvoxNavHost.kt — did the file move?",
            matches.isNotEmpty(),
        )
        val nonAiTargets = matches.filter { it != "SETTINGS_AI" }
        assertTrue(
            "onOpenAiSettings must land on SETTINGS_AI, not the legacy long-scroll page. " +
                "Offending targets: $nonAiTargets",
            nonAiTargets.isEmpty(),
        )
    }

    private fun locateNavHostSource(): File {
        val candidates = listOf(
            // Default working directory when tests run from :app.
            File("src/main/kotlin/in/jphe/storyvox/navigation/StoryvoxNavHost.kt"),
            // Gradle sometimes runs with the repo root as CWD.
            File("app/src/main/kotlin/in/jphe/storyvox/navigation/StoryvoxNavHost.kt"),
        )
        val found = candidates.firstOrNull { it.exists() }
        if (found == null) {
            fail(
                "StoryvoxNavHost.kt not found at expected paths " +
                    "(cwd=${File(".").absolutePath}); update locateNavHostSource()",
            )
            error("unreachable") // fail() throws AssertionError; satisfies non-null return
        }
        return found
    }
}
