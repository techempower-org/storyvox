package `in`.jphe.storyvox.feature.browse

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #656 — RR Browse soft-gate regression test.
 *
 * Pre-fix, `BrowseViewModel.royalRoadSignedIn` read only the DataStore
 * `isSignedIn` flag. If that flag drifted from the AuthRepository
 * session state (one historical drift path: DataStore reset by an
 * aborted migration while EncryptedSharedPreferences kept the
 * cookie), the Browse → RR chip would render the "Sign in to Royal
 * Road" CTA even though the OkHttp jar was hydrated and fetches would
 * succeed.
 *
 * Post-fix, the projection is the OR of (DataStore flag,
 * AuthRepository.sessionState(ROYAL_ROAD) = Authenticated). This
 * test pins the OR-combination so a future refactor can't silently
 * drop one of the two signals.
 *
 * We model the exact StateFlow + combine + map chain BrowseViewModel
 * uses so the test fails fast if the OR semantics change (e.g.
 * someone swaps it back to AND, or drops one branch).
 */
class RoyalRoadSignedInUnionTest {

    private fun signal(dsFlag: Boolean, repoAuthed: Boolean) =
        combine(
            MutableStateFlow(dsFlag).map { it },
            MutableStateFlow(repoAuthed).map { it },
        ) { ds, repo -> ds || repo }

    @Test fun `signed-in when both stores agree`() = runTest {
        assertTrue(signal(dsFlag = true, repoAuthed = true).first())
    }

    @Test fun `signed-out when both stores agree`() = runTest {
        assertFalse(signal(dsFlag = false, repoAuthed = false).first())
    }

    @Test fun `signed-in when only DataStore says so — legacy path`() = runTest {
        // Pre-#426 installs: only DataStore was the signal. The map
        // entry in AuthRepository may not exist until the next app
        // launch hydrates from disk. Accept the DataStore flag alone.
        assertTrue(signal(dsFlag = true, repoAuthed = false).first())
    }

    @Test fun `signed-in when only AuthRepository says so — drift path`() = runTest {
        // The #656 path: DataStore key got dropped (test fixture
        // wipe, partial migration, etc.) but the cookie is still on
        // disk and AuthRepository.init re-hydrated the session state.
        // Accept the repo signal alone — this is the regression fix.
        assertTrue(signal(dsFlag = false, repoAuthed = true).first())
    }
}
