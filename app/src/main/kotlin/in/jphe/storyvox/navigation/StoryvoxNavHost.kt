package `in`.jphe.storyvox.navigation

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import `in`.jphe.storyvox.auth.AuthWebViewScreen
import `in`.jphe.storyvox.auth.anthropic.AnthropicTeamsSignInScreen
import `in`.jphe.storyvox.auth.github.GitHubSignInScreen
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.source.github.auth.GitHubAuthConfig
import `in`.jphe.storyvox.feature.browse.BrowseScreen
import `in`.jphe.storyvox.feature.chat.ChatScreen
import `in`.jphe.storyvox.feature.debug.DebugScreen
import `in`.jphe.storyvox.feature.engine.VoicePickerGate
import `in`.jphe.storyvox.feature.onboarding.OnboardingHost
import `in`.jphe.storyvox.feature.fiction.FictionDetailScreen
import `in`.jphe.storyvox.feature.follows.FollowsScreen
import `in`.jphe.storyvox.feature.library.LibraryScreen
import `in`.jphe.storyvox.feature.reader.HybridReaderScreen
import `in`.jphe.storyvox.feature.reader.NowPlayingDock
import `in`.jphe.storyvox.feature.reader.NowPlayingDockViewModel
import `in`.jphe.storyvox.feature.settings.AboutSettingsScreen
import `in`.jphe.storyvox.feature.settings.AccessibilitySettingsScreen
import `in`.jphe.storyvox.feature.settings.AccountSettingsScreen
import `in`.jphe.storyvox.feature.settings.AdvancedSettingsScreen
import `in`.jphe.storyvox.feature.settings.AppearanceSettingsScreen
import `in`.jphe.storyvox.feature.settings.AiSettingsScreen
import `in`.jphe.storyvox.feature.settings.CloudVoicesSettingsScreen
import `in`.jphe.storyvox.feature.settings.MemoryPalaceSettingsScreen
import `in`.jphe.storyvox.feature.settings.PerformanceSettingsScreen
import `in`.jphe.storyvox.feature.settings.ReadingSettingsScreen
import `in`.jphe.storyvox.feature.settings.SettingsHubScreen
import `in`.jphe.storyvox.feature.settings.SettingsScreen
import `in`.jphe.storyvox.feature.settings.VoiceAndPlaybackSettingsScreen
import `in`.jphe.storyvox.feature.settings.pronunciation.PronunciationDictScreen
import `in`.jphe.storyvox.feature.techempower.TechEmpowerAboutScreen
import `in`.jphe.storyvox.feature.techempower.TechEmpowerHomeScreen
import `in`.jphe.storyvox.feature.voicelibrary.VoiceLibraryScreen
import `in`.jphe.storyvox.ui.component.BottomTabBar
import `in`.jphe.storyvox.ui.component.HomeTab
import `in`.jphe.storyvox.ui.component.SideNavRail
import `in`.jphe.storyvox.ui.layout.isAtLeastTablet
import `in`.jphe.storyvox.ui.theme.LocalMotion
import `in`.jphe.storyvox.ui.theme.LocalReducedMotion

object StoryvoxRoutes {
    const val PLAYING = "playing"
    const val LIBRARY = "library"
    const val FOLLOWS = "follows"
    const val BROWSE = "browse"
    const val FICTION_DETAIL = "fiction/{fictionId}"
    const val READER = "reader/{fictionId}/{chapterId}"
    const val AUDIOBOOK = "audiobook/{fictionId}/{chapterId}"
    /** Issue #440 — Settings hub (section index). The gear-icon
     *  destination as of v0.5.38; previously dumped users into the
     *  flat-scroll [SETTINGS] page with no top-of-page map. Each row
     *  on the hub routes into a dedicated subscreen; the legacy long
     *  [SETTINGS] page is preserved as an "All settings" escape hatch
     *  for power users who want everything on one searchable page. */
    const val SETTINGS_HUB = "settings/hub"
    const val SETTINGS = "settings"
    const val SETTINGS_PRONUNCIATION = "settings/pronunciation"
    const val VOICE_LIBRARY = "settings/voices"
    /** Issue #218 — Settings → AI → Sessions: review past chats and
     *  recap history, navigate back into the chat surface, delete
     *  individual sessions. */
    const val SETTINGS_AI_SESSIONS = "settings/ai/sessions"
    /** Vesper (v0.4.97) — Settings → Developer → Debug. Pipeline/engine/
     *  Azure live diagnostics + 20-event ring + clipboard export for bug
     *  reports. */
    const val SETTINGS_DEBUG = "settings/debug"
    /** Phase 3 / Plugin manager (#404) — Settings → Plugins. Registry-
     *  driven plugin manager: brass-edged card grid with toggle +
     *  capability chips + tap-for-details. */
    const val SETTINGS_PLUGINS = "settings/plugins"
    // ─── Follow-up to #440 — dedicated subscreen routes for the seven
    // hub cards that previously fell through to the legacy long-scroll
    // [SETTINGS] page. Grouped together so a rebase against a parallel
    // nav-restructure branch is mechanical. ───────────────────────────
    /** Settings → Voice & Playback. Voice library link, Speed, Pitch,
     *  punctuation cadence, HQ pitch interpolation, Pronunciation link. */
    const val SETTINGS_VOICE_PLAYBACK = "settings/voice-playback"
    /** Settings → Reading. Theme override, sleep-shake. */
    const val SETTINGS_READING = "settings/reading"
    /** Settings → Performance. Catch-up Pause, buffer slider, expert
     *  expander with warm-up, voice determinism, parallel synth. */
    const val SETTINGS_PERFORMANCE = "settings/performance"
    /** Settings → AI. Provider chip strip, per-provider config rows,
     *  test connection, grounding switches, memory toggle, actions
     *  toggle, Sessions link. */
    const val SETTINGS_AI = "settings/ai"
    /** Settings → Accessibility (Phase 1 scaffold, v0.5.42). High-
     *  contrast toggle, reduced-motion toggle, larger-touch-targets,
     *  screen-reader pause slider, speak-chapter-mode radio, font-
     *  scale override, reading-direction override. Phase 2 agents wire
     *  the actual behavior; Phase 1 only persists the prefs. */
    const val SETTINGS_ACCESSIBILITY = "settings/accessibility"
    /** Settings → Appearance (v0.5.59, #cover-style-toggle). Book-cover
     *  fallback style picker (Monogram / Branded / Cover only) with a
     *  live preview chip-row. Future visual-style knobs land here. */
    const val SETTINGS_APPEARANCE = "settings/appearance"
    /** Settings → Advanced (v1 settings-bundle-7). Power-user knobs:
     *  Android Auto bucket size (#598) and future integration tunables. */
    const val SETTINGS_ADVANCED = "settings/advanced"
    /** Settings → Cloud Voices (#712, follow-up to #404 + #702). Dedicated
     *  destination for the Azure Speech BYOK key + region + test-connection
     *  flow that previously dumped the user onto the 3,600-line legacy
     *  [SETTINGS] long-scroll page when they tapped "configure" on the
     *  Azure plugin row. Hosts the same [AzureSection] composable as the
     *  legacy page, so the two surfaces stay byte-identical. */
    const val SETTINGS_CLOUD_VOICES = "settings/cloud-voices"
    /** Settings → Account. Royal Road sign-in, GitHub OAuth + scope. */
    const val SETTINGS_ACCOUNT = "settings/account"
    /** Settings → Memory Palace. Daemon host, API key, test probe. */
    const val SETTINGS_MEMORY_PALACE = "settings/memory-palace"
    /** Settings → About. Version + sigil name + build hash + the
     *  v0.5.00 milestone pill. */
    const val SETTINGS_ABOUT = "settings/about"
    /**
     * Generic WebView sign-in destination, parameterized by sourceId
     * (#426 PR2). Royal Road = `auth/webview/royalroad`, AO3 =
     * `auth/webview/ao3`. The screen reads the sourceId arg, picks the
     * right source-module WebView composable, and routes captured
     * cookies through [AuthViewModel] with the matching sourceId.
     *
     * Callers should use [authWebView] instead of building the path
     * manually so the helper centralizes the sourceId encoding.
     */
    const val AUTH_WEBVIEW = "auth/webview/{sourceId}"

    /** Build a concrete `auth/webview/<sourceId>` path for navigation. */
    fun authWebView(sourceId: String): String = "auth/webview/$sourceId"
    /** Issue #91 — GitHub Device Flow sign-in modal. */
    const val GITHUB_SIGN_IN = "auth/github/signin"
    /** Issue #181 — Anthropic Teams OAuth sign-in modal. */
    const val TEAMS_SIGN_IN = "auth/anthropic-teams/signin"
    /** Issue #500 — InstantDB cross-device sync sign-in surface.
     *  Email + magic-code flow. Opened by the magical first-launch
     *  onboarding card (post-VoicePickerGate) AND by the cloud-icon
     *  affordance in the Library top app bar. Lives at /auth/sync so
     *  it sits alongside the other auth destinations. */
    const val SYNC = "auth/sync"

    /**
     * Issue #517 — TechEmpower-as-default-use-case landing screen.
     * Reached via the brass-edged TechEmpower hero card pinned at the
     * top of the Library grid. Drill-down depth (no bottom-bar
     * destination) so the cold-launch Library landing isn't disrupted
     * — TechEmpower Home is *opt-in via tap*, not a default route.
     */
    const val TECHEMPOWER_HOME = "techempower/home"

    /**
     * Issue #517 — "About TechEmpower" sub-screen with mission
     * statement, donate flow, partnerships contact, 501(c)(3)
     * attribution. Drill-down from TechEmpower Home.
     */
    const val TECHEMPOWER_ABOUT = "techempower/about"
    /** Q&A chat about a fiction (#81 follow-up). One chat history per
     *  fictionId; the screen pulls fiction title + current chapter
     *  context internally for the system prompt.
     *
     *  Optional `prefill` query param (#188 character lookup): when the
     *  reader's long-press dialog routes here, it passes a prebuilt
     *  "Who is X?" question to seed the input field. The arg is read
     *  once on session creation by `ChatViewModel`; it does not auto-send.
     */
    const val CHAT = "chat/{fictionId}?prefill={prefill}"

    // Encode ids: GitHubSource fictionIds contain `/` (e.g. "github:owner/repo")
    // and chapterIds contain even more (e.g. "github:owner/repo:src/chapter-01.md"),
    // which break single-segment route matching. Compose Navigation auto-decodes
    // when reading args from SavedStateHandle, so encoding only at the call site is safe.
    fun fictionDetail(fictionId: String) = "fiction/${Uri.encode(fictionId)}"

    /** Issue #472 — Library tab opened with a Magic-add prefill payload.
     *  The shared URL is URL-encoded so Compose Navigation can carry it
     *  through the query-string; the LibraryScreen decodes and hands
     *  it to the viewmodel on first composition. We deliberately use
     *  a query parameter (not a path segment) so a bare /library hit
     *  still matches and a missing `sharedUrl` is null at the receiver. */
    fun libraryWithShare(sharedUrl: String): String =
        "$LIBRARY?${DeepLinkResolver.ARG_SHARED_URL}=${Uri.encode(sharedUrl)}"
    fun reader(fictionId: String, chapterId: String) = "reader/${Uri.encode(fictionId)}/${Uri.encode(chapterId)}"
    fun audiobook(fictionId: String, chapterId: String) = "audiobook/${Uri.encode(fictionId)}/${Uri.encode(chapterId)}"
    /** Build a chat route. `prefill` (optional, #188) seeds the chat
     *  input with a starter question; pass null/empty to leave the
     *  input untouched on open. The query value is percent-encoded so
     *  free-text questions ("Who is Frodo?") survive the URL roundtrip.
     *
     *  Compose Navigation matches the optional `?prefill={prefill}`
     *  template against routes with OR without the query string —
     *  omitting it when there's no prefill keeps the route minimal AND
     *  preserves the pre-existing single-segment shape that
     *  StoryvoxRoutesTest pins. */
    fun chat(fictionId: String, prefill: String? = null): String {
        val base = "chat/${Uri.encode(fictionId)}"
        return if (prefill.isNullOrEmpty()) base
        else "$base?prefill=${Uri.encode(prefill)}"
    }

    /**
     * Routes treated as "home" surfaces — bottom nav stays visible while
     * the user is on one of these. The set was pruned in the v0.5.40
     * restructure: only [LIBRARY] and [SETTINGS_HUB] are bottom-bar
     * destinations now, but FOLLOWS / BROWSE / VOICE_LIBRARY / PLAYING /
     * SETTINGS stay in HOME_ROUTES because they still render at the
     * "primary surface" depth (deep-linked or reached via in-Library
     * sub-tab navigation), and we want the bottom bar visible there
     * too. The bar's *selected* mapping (below) collapses any of these
     * to the LIBRARY pill since that's the umbrella destination now.
     */
    private val HOME_ROUTES = setOf(PLAYING, LIBRARY, FOLLOWS, BROWSE, VOICE_LIBRARY, SETTINGS_HUB, SETTINGS)
    /** Strip any nav query-arg suffix before checking — PR #475 (magic-link)
     *  registered LIBRARY as `library?sharedUrl={sharedUrl}`, so
     *  `currentBackStackEntryAsState()` reports `destination.route` with the
     *  full pattern attached, not the bare LIBRARY constant. Without this
     *  substring, the bottom nav vanishes the moment the user is on the
     *  Library tab — the home surface this set is supposed to govern.
     *  Reported on tablet + Flip3 v0.5.47. */
    fun isHome(route: String?) = route?.substringBefore("?") in HOME_ROUTES

    /** Issue #267 — Reader / Audiobook routes ARE the player surface, just
     *  reached via drill-down from a chapter row instead of via the
     *  Playing tab. Keep the bottom nav visible on those so the user can
     *  switch tabs (Browse another book, hit Library) without backing out
     *  of the player. The drill-down is still a back-stack push (so OS
     *  Back returns to the chapter list); the bottom bar is purely a
     *  cross-cutting nav surface here.
     *
     *  When a full mini-player strip lands (sibling of #267 — collapse
     *  the player into a top-of-bottom-bar mini), this set goes away and
     *  the strip becomes the always-present transport surface. Until
     *  then, "bottom nav present + player fills the body" is the
     *  Apple-Books pattern and the lowest-cost win. */
    private val PLAYER_ROUTES_WITH_BOTTOM_NAV = setOf(READER, AUDIOBOOK)
    fun showsBottomNav(route: String?): Boolean =
        isHome(route) || route in PLAYER_ROUTES_WITH_BOTTOM_NAV

    /**
     * Issue #678 — surfaces the persistent now-playing mini-dock when
     * the current route is NOT itself a player surface. Excludes the
     * Reader/Audiobook drill-downs (the dock would sit on top of the
     * same content the user is already looking at) and the PLAYING
     * home tab (HybridReaderScreen — same player surface, just reached
     * via the nav bar).
     *
     * Visibility is the AND of this gate + "a chapter is currently
     * loaded" (checked inside [NowPlayingDockViewModel]'s state). The
     * combined effect: the dock only appears once the user has played
     * at least one chapter this session, and stays out of the way
     * when they're already on the player surface.
     */
    fun hostsNowPlayingDock(route: String?): Boolean {
        val base = route?.substringBefore("?") ?: return false
        if (base == PLAYING) return false
        if (base == READER || base.startsWith("reader/")) return false
        if (base == AUDIOBOOK || base.startsWith("audiobook/")) return false
        return true
    }
}

@Composable
fun StoryvoxNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    // Issue #599 (v1.0 blocker) — first-launch welcome flow wraps the
    // VoicePickerGate so a brand-new user sees the plain-English three-
    // screen welcome BEFORE the engineering-themed voice picker. The
    // host's [shouldShow] flow gates on the persisted
    // `pref_onboarding_completed_v1` flag; once flipped it never re-
    // arms for the life of the install (Settings → Developer → Reset
    // onboarding flips it back for QA / dress rehearsal).
    //
    // Mounted OUTSIDE the VoicePickerGate so the gate's tap-swallow
    // shield doesn't trap welcome-screen taps. The welcome's own
    // shield handles its swallow.
    OnboardingHost(
        // Issues #644 + #647 (v1.0) — `onOpenTechEmpower` stays
        // wired to the hub destination so the viewmodel's fallback
        // path (chapter resolution failed) still lands on a working
        // surface. The happy path runs through [onOpenGuidesAndPlay]
        // below, which skips the hub AND the FictionDetail cover
        // stop in favor of dropping the user directly onto the
        // Playing transport surface with chapter 1 of the Guides
        // queued + auto-playing. The pre-fix two-step (hub →
        // Browse Resources → Guides → Play) collapses to a single
        // tap on the "Browse TechEmpower's free guides" card.
        onOpenTechEmpower = {
            navController.navigate(StoryvoxRoutes.TECHEMPOWER_HOME)
        },
        onOpenGuidesAndPlay = {
            // Route to PLAYING and pop the welcome's underlying
            // Library entry so OS-Back from the player surfaces
            // the Library (the cold-launch destination) — not a
            // ghost entry for the welcome screen the user already
            // dismissed. launchSingleTop collapses a stray double-
            // tap into a single navigation.
            navController.navigate(StoryvoxRoutes.PLAYING) {
                popUpTo(StoryvoxRoutes.LIBRARY)
                launchSingleTop = true
            }
        },
        onAddFromWebsite = { clipboardUrl ->
            // Route to Library with the magic-add sheet pre-opened.
            // If we read a URL off the clipboard, surface it via the
            // existing libraryWithShare route (the same query-arg
            // path system share-intents use). Otherwise we fall
            // through to plain Library — the magic-add affordance
            // is one tap away on the Library top bar.
            if (!clipboardUrl.isNullOrBlank()) {
                navController.navigate(StoryvoxRoutes.libraryWithShare(clipboardUrl))
            } else {
                navController.navigate(StoryvoxRoutes.LIBRARY) {
                    popUpTo(StoryvoxRoutes.LIBRARY) { inclusive = true }
                    launchSingleTop = true
                }
            }
        },
        onOpenVoiceLibrary = { navController.navigate(StoryvoxRoutes.VOICE_LIBRARY) },
    ) {
    VoicePickerGate(
        onOpenVoiceLibrary = { navController.navigate(StoryvoxRoutes.VOICE_LIBRARY) },
    ) {
        StoryvoxNavHostContent(navController, modifier)
        // Calliope (v0.5.00) — one-time graduation milestone dialog.
        // Mounted at the nav host level so it shows on top of
        // whatever the first launch landed on (Playing tab today,
        // possibly a deep-linked chapter tomorrow). The MilestoneVM
        // gates on BuildConfig.VERSION_NAME + a DataStore flag and
        // emits false-forever after first dismissal, so this composable
        // is a no-op on every launch after the first qualifying one.
        MilestoneDialogHost()
        // Issue #500 — magical InstantDB sign-in onboarding card.
        // Same mount-and-gate pattern as MilestoneDialogHost: the VM
        // returns false-forever after the user dismisses or completes
        // the flow, so the card is a no-op on every launch after the
        // first one. Sits alongside MilestoneDialogHost (not nested in
        // it) so the two compete for screen time on equal terms —
        // first-launch v0.5.39+ users see Calliope first (one-shot
        // graduation moment) and the sync card on the launch after.
        // Routes its "Sign in" tap to the SYNC destination via the
        // shared navController.
        `in`.jphe.storyvox.feature.sync.SyncOnboardingHost(
            onOpenSignIn = { navController.navigate(StoryvoxRoutes.SYNC) },
        )
    }
    }
}

/**
 * Thin Hilt-injection wrapper around [MilestoneDialog]. Reads the
 * `showDialog` StateFlow from [MilestoneViewModel] and renders the
 * brass thank-you card when it's true. Separate from
 * [StoryvoxNavHost] so the VM scope is the dialog's lifetime, not
 * the whole nav host.
 */
@Composable
private fun MilestoneDialogHost(
    viewModel: `in`.jphe.storyvox.feature.milestone.MilestoneViewModel =
        androidx.hilt.navigation.compose.hiltViewModel(),
) {
    val show by viewModel.showDialog.collectAsStateWithLifecycle()
    if (show) {
        `in`.jphe.storyvox.ui.component.MilestoneDialog(
            onDismiss = viewModel::dismiss,
        )
    }
}

@Composable
private fun StoryvoxNavHostContent(
    navController: NavHostController,
    modifier: Modifier,
) {
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route
    val showHomeNav = StoryvoxRoutes.showsBottomNav(currentRoute)

    val reducedMotion = LocalReducedMotion.current

    // Issue #629 — at 600 dp+ screen width, use a NavigationRail at the
    // start edge instead of the phone-shaped bottom bar. The 4-tab
    // bottom-nav strip is fine on a 360-dp phone but wastes the wider
    // tablet screen, forcing the content area into a phone-shaped
    // column with a giant empty bottom margin. The rail pins to the
    // left at 80 dp and the content area expands across the rest.
    //
    // Verified across the 600 dp threshold:
    //  - R83W80CAFZB (Tab A7 Lite, 800×1280 portrait, 600 dp width) → rail
    //  - R5CRB0W66MK (Flip3 unfolded, 720×1768, 360 dp inner) → bottom bar
    //  - foldable Flip3 cover screen (260 dp) → bottom bar
    val useSideRail = isAtLeastTablet() && showHomeNav

    // Shared nav-target resolution: both the side rail (tablet) and the
    // bottom bar (phone) drive the same {Playing, Library, Browse,
    // Voices, Settings} dock, so the selected tab + onSelect logic is
    // identical across both surfaces. Extracting them here keeps the two
    // surfaces in lockstep — a future tab addition lands in one place.
    //
    // v0.5.72 — Browse is first-class. The BROWSE route lights the Browse
    // pill (not Library), so the user always knows where they are. The
    // Library sub-tabs (Follows, Inbox, History) still collapse to the
    // Library pill since those remain under the Library umbrella.
    val selectedTab = when (currentRoute?.substringBefore("?")) {
        StoryvoxRoutes.SETTINGS_HUB,
        StoryvoxRoutes.SETTINGS -> HomeTab.Settings
        StoryvoxRoutes.VOICE_LIBRARY -> HomeTab.Voices
        StoryvoxRoutes.PLAYING -> HomeTab.Playing
        StoryvoxRoutes.BROWSE -> HomeTab.Browse
        // Library + Follows + Inbox + History sub-tabs and
        // Reader / Audiobook drill-downs all light the Library pill —
        // Library is still the umbrella for that whole tree.
        else -> HomeTab.Library
    }
    // Issue #678 — gate the now-playing mini-dock on (a) route eligibility
    // (the dock hides on the Reader / Audiobook / Playing surfaces — the
    // dock would otherwise stack on top of the same content) and (b) the
    // viewmodel reporting a chapter currently loaded. The dock composable
    // bails internally when fictionId/chapterId is null, so the wrapping
    // `if` here is purely a perf optimization: skip the VM materialization
    // when the route would suppress the dock anyway.
    val showNowPlayingDock = StoryvoxRoutes.hostsNowPlayingDock(currentRoute)
    val onOpenReaderFromDock: (String, String) -> Unit = { fictionId, chapterId ->
        navController.navigate(StoryvoxRoutes.reader(fictionId, chapterId))
    }
    val onSelectTab: (HomeTab) -> Unit = { tab ->
        val target = when (tab) {
            HomeTab.Library -> StoryvoxRoutes.LIBRARY
            HomeTab.Playing -> StoryvoxRoutes.PLAYING
            HomeTab.Browse -> StoryvoxRoutes.BROWSE
            HomeTab.Voices -> StoryvoxRoutes.VOICE_LIBRARY
            HomeTab.Settings -> StoryvoxRoutes.SETTINGS_HUB
        }
        // Issue #918 — strip query-parameter suffixes before comparing.
        // The Library route is registered as "library?sharedUrl={...}"
        // so `destination.route` includes the query template. Without
        // stripping, tapping Library while on Library would re-navigate
        // (target "library" != currentRoute "library?sharedUrl={...}").
        // More critically, the mismatch caused `popUpTo` with the bare
        // route string to silently fail on drill-down stacks (Library →
        // FictionDetail → Reader), making bottom-nav tabs unresponsive.
        val baseRoute = currentRoute?.substringBefore("?")
        if (target != baseRoute) {
            // Pop everything above the start destination so tab
            // switches don't accumulate, then push the target.
            // `launchSingleTop` collapses repeated taps on the active
            // tab.
            //
            // Issue #761 — saveState / restoreState preserve scroll
            // position, search queries, selected sub-tabs, and ViewModel
            // state across bottom-nav switches. Compose Navigation saves
            // the entire composable tree's SavedStateHandle when the tab
            // is popped, and restores it when the user returns. This is
            // the standard Compose bottom-nav pattern from the Now in
            // Android reference app and the official navigation docs.
            //
            // Issue #918 — use `findStartDestination().id` instead of
            // the bare route string for `popUpTo`. The Library composable
            // is registered with an optional query parameter
            // ("library?sharedUrl={sharedUrl}") so the destination's
            // route pattern differs from the `StoryvoxRoutes.LIBRARY`
            // constant ("library"). Using the destination ID bypasses
            // route-string matching entirely and reliably finds the
            // start destination in the back stack — the same pattern the
            // Now in Android reference app uses.
            //
            // Library is the start destination and always lives at the
            // bottom of the back stack — its NavBackStackEntry (and
            // ViewModel / composable state) persist naturally. Using
            // restoreState for Library would push saved drill-down
            // routes (Reader, AudiobookView) back on top, so tapping
            // "Library" after playing would land on the Reader instead
            // of the Library grid. Disable restoreState for Library;
            // other tabs restore normally.
            navController.navigate(target) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = target != StoryvoxRoutes.LIBRARY
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            // Bottom bar renders on phone-width screens only — at 600 dp+
            // the side rail (rendered below as a Row peer of the Scaffold
            // body content) carries the dock.
            //
            // Issue #678 — the persistent now-playing mini-dock sits in a
            // Column above the BottomTabBar on phones. Wrapping both in
            // the same `bottomBar` slot lets the Scaffold reserve the
            // combined height for content padding automatically, so
            // screens don't need any per-screen awareness of whether the
            // dock is showing. The same Column also handles the
            // bottom-bar-hidden surfaces (onboarding / voice picker
            // gates): showHomeNav=false collapses the whole slot.
            if (showHomeNav && !useSideRail) {
                androidx.compose.foundation.layout.Column {
                    if (showNowPlayingDock) {
                        NowPlayingDock(onOpenReader = onOpenReaderFromDock)
                    }
                    BottomTabBar(
                        // v0.5.48 — `{Library, Playing, Voices, Settings}` dock
                        // per JP feedback restoring Playing + Voices alongside
                        // the v0.5.40 Settings primary slot. The base-route
                        // stripping handles PR #475's magic-link query-arg
                        // suffix (`library?sharedUrl=...`).
                        selected = selectedTab,
                        onSelect = onSelectTab,
                    )
                }
            }
        },
    ) { padding ->
        val motion = LocalMotion.current

        // Library Nocturne motion vocabulary for screen transitions.
        //
        // Home tabs (Playing/Library/Follows/Browse/Settings) cross-fade only — the
        // bottom bar is shared across them, and a horizontal slide would shear the
        // bar visually. Drill-down routes slide in from the right + fade, the
        // standard "push" motion; the pop reverses direction.
        //
        // When the user has "Remove animations" / "Reduce motion" on, every factory
        // is null → NavHost interprets that as no transition (instant cut), which is
        // what motion-sensitive users actually want, not a "shorter" version.
        val homeEnter: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition)? =
            if (reducedMotion) null else {
                { fadeIn(animationSpec = tween(motion.standardDurationMs, easing = motion.standardEasing)) }
            }
        val homeExit: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition)? =
            if (reducedMotion) null else {
                { fadeOut(animationSpec = tween(motion.standardDurationMs, easing = motion.standardEasing)) }
            }

        // 320ms swipe with the existing swipeEasing curve — distinct from the 280ms
        // standard so a forward navigation feels intentional, not abrupt.
        val pushEnter: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition)? =
            if (reducedMotion) null else {
                {
                    slideInHorizontally(
                        animationSpec = tween(motion.swipeDurationMs, easing = motion.swipeEasing),
                        initialOffsetX = { fullWidth -> fullWidth / 6 },
                    ) + fadeIn(animationSpec = tween(motion.swipeDurationMs, easing = motion.swipeEasing))
                }
            }
        val pushExit: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition)? =
            if (reducedMotion) null else {
                { fadeOut(animationSpec = tween(motion.swipeDurationMs, easing = motion.swipeEasing)) }
            }
        val popEnter: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition)? =
            if (reducedMotion) null else {
                { fadeIn(animationSpec = tween(motion.swipeDurationMs, easing = motion.swipeEasing)) }
            }
        val popExit: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition)? =
            if (reducedMotion) null else {
                {
                    slideOutHorizontally(
                        animationSpec = tween(motion.swipeDurationMs, easing = motion.swipeEasing),
                        targetOffsetX = { fullWidth -> fullWidth / 6 },
                    ) + fadeOut(animationSpec = tween(motion.swipeDurationMs, easing = motion.swipeEasing))
                }
            }

        // Issue #629 — when the side rail is active (tablet+),
        // wrap the NavHost in a Row so the rail sits at the start
        // edge and the content area uses the remaining width via
        // Modifier.weight(1f). On phone width the wrapper Row
        // collapses to just the NavHost (the rail is null) so the
        // existing layout stays identical at < 600 dp.
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            if (useSideRail) {
                SideNavRail(
                    selected = selectedTab,
                    onSelect = onSelectTab,
                )
            }
            // Issue #678 — on tablets the bottomBar slot is empty (the
            // side-rail carries primary nav), so the dock can't ride
            // along with BottomTabBar like it does on phones. Wrap the
            // NavHost in a Column so the dock pins to the bottom edge
            // of the content area while the NavHost takes the rest of
            // the height via weight(1f). On phones (`useSideRail=false`)
            // the wrapping Column collapses to a noop containing just
            // the NavHost — the dock there lives in the Scaffold's
            // bottomBar slot above [BottomTabBar].
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.weight(1f).fillMaxSize(),
            ) {
            NavHost(
                navController = navController,
                // NavHost takes the remaining vertical space inside the
                // wrapping Column via weight(1f); the dock (if shown,
                // tablet only) pins to the bottom edge of the column.
                // Restructure (v0.5.40) — Library is the start destination
                // and primary umbrella surface. Playing (HybridReaderScreen)
                // is reached via the Resume card on the Library tab when
                // the user has a continue-listening entry; if they don't,
                // landing on Library showed them an empty grid before too,
                // but the empty-state copy now invites them to Browse
                // (which is one Library sub-tab away, not a separate
                // bottom-bar destination).
                startDestination = StoryvoxRoutes.LIBRARY,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
            composable(
                StoryvoxRoutes.PLAYING,
                enterTransition = homeEnter,
                exitTransition = homeExit,
                popEnterTransition = homeEnter,
                popExitTransition = homeExit,
            ) {
                HybridReaderScreen(
                    onPickVoice = { navController.navigate(StoryvoxRoutes.VOICE_LIBRARY) },
                    onOpenAiSettings = { navController.navigate(StoryvoxRoutes.SETTINGS_AI) },
                    onOpenSettings = { navController.navigate(StoryvoxRoutes.SETTINGS_HUB) },
                    onOpenChat = { fId, prefill -> navController.navigate(StoryvoxRoutes.chat(fId, prefill)) },
                    // ResumeEmptyPrompt's "Browse the realms" CTA — only
                    // matters when the user has no continue-listening
                    // entry at all (first launch / wiped data). The
                    // populated ResumePrompt doesn't navigate; tapping
                    // Resume reloads the chapter in place.
                    onBrowse = {
                        // Restructure (v0.5.40) — Browse is no longer a
                        // bottom-bar destination; it lives inside the
                        // Library tab. The empty-state "Browse the
                        // realms" CTA could open the standalone BROWSE
                        // route (still in the nav graph) or route to
                        // LIBRARY and let the user tap the Browse
                        // sub-tab. We open BROWSE directly here so the
                        // CTA's verb still matches its destination
                        // exactly.
                        // Issue #918 — use findStartDestination().id for
                        // popUpTo (see onSelectTab comment for rationale).
                        navController.navigate(StoryvoxRoutes.BROWSE) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    // Issue #437 — Back arrow on the PLAYING destination.
                    // When PLAYING was entered directly (notification tap,
                    // bottom-nav tap, deep link) there's nothing to pop
                    // back to; fall back to LIBRARY (the v0.5.39 start
                    // destination) so the back arrow always feels active.
                    onBack = {
                        if (!navController.popBackStack()) {
                            navController.navigate(StoryvoxRoutes.LIBRARY) {
                                popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    },
                    // Issue #955 — brass MenuBook on the player top-bar
                    // jumps into the currently-playing fiction's detail
                    // page (cover + full chapter list). Routes via the
                    // normal nav push so the back stack restores the
                    // player on Back.
                    onOpenLibrary = { fId ->
                        navController.navigate(StoryvoxRoutes.fictionDetail(fId))
                    },
                )
            }
            composable(
                // Issue #472 — Library route accepts an optional
                // `sharedUrl` query param so a system share-intent can
                // route a URL into the Magic-add sheet. Default null;
                // the LibraryScreen reads the arg via the
                // [DeepLinkResolver.ARG_SHARED_URL] key.
                "${StoryvoxRoutes.LIBRARY}?${DeepLinkResolver.ARG_SHARED_URL}={${DeepLinkResolver.ARG_SHARED_URL}}",
                arguments = listOf(
                    androidx.navigation.navArgument(DeepLinkResolver.ARG_SHARED_URL) {
                        type = androidx.navigation.NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
                enterTransition = homeEnter,
                exitTransition = homeExit,
                popEnterTransition = homeEnter,
                popExitTransition = homeExit,
            ) { backStackEntry ->
                val sharedUrl = backStackEntry.arguments
                    ?.getString(DeepLinkResolver.ARG_SHARED_URL)
                    ?.let { Uri.decode(it) }
                LibraryScreen(
                    sharedUrl = sharedUrl,
                    onOpenFiction = { id -> navController.navigate(StoryvoxRoutes.fictionDetail(id)) },
                    onOpenReader = { f, c -> navController.navigate(StoryvoxRoutes.reader(f, c)) },
                    // v0.5.72 — Browse is a first-class bottom-nav tab
                    // now; the standalone Browse route owns RR + AO3
                    // sign-in deep-links. Follows is still embedded
                    // here, so its sign-in CTA still threads through.
                    onOpenFollowsSignIn = { navController.navigate(StoryvoxRoutes.authWebView(SourceIds.ROYAL_ROAD)) },
                    // Issue #500 — cloud-icon tap on the Library top
                    // app bar routes to the InstantDB sign-in surface
                    // (the same SyncAuthScreen the onboarding card
                    // opens). One destination, one mental model.
                    onOpenSync = { navController.navigate(StoryvoxRoutes.SYNC) },
                    // Issue #517 — TechEmpower hero card tap. Routes
                    // to the dedicated TechEmpower Home drill-down.
                    onOpenTechEmpower = {
                        navController.navigate(StoryvoxRoutes.TECHEMPOWER_HOME)
                    },
                    // Issue #383 — Inbox row tap deep-link. The URI is a
                    // pre-resolved `storyvox://reader/<fid>/<cid>` or
                    // `storyvox://fiction/<fid>` string. Decode here and
                    // route via the existing nav graph — same destinations
                    // as the History tap path, just chosen by URL prefix.
                    onOpenInboxLink = { uri ->
                        val readerPrefix = "storyvox://reader/"
                        val fictionPrefix = "storyvox://fiction/"
                        when {
                            uri.startsWith(readerPrefix) -> {
                                val rest = uri.removePrefix(readerPrefix)
                                val slash = rest.indexOf('/')
                                if (slash > 0) {
                                    val fid = rest.substring(0, slash)
                                    val cid = rest.substring(slash + 1)
                                    navController.navigate(StoryvoxRoutes.reader(fid, cid))
                                }
                            }
                            uri.startsWith(fictionPrefix) -> {
                                val fid = uri.removePrefix(fictionPrefix)
                                if (fid.isNotBlank()) {
                                    navController.navigate(StoryvoxRoutes.fictionDetail(fid))
                                }
                            }
                            else -> {
                                // Unknown scheme — silently ignore. The
                                // event row stays marked-read since the VM
                                // already fired markRead before this
                                // callback. Future-source URIs that we
                                // don't decode here just become quiet
                                // dismissals rather than crashes.
                            }
                        }
                    },
                )
            }
            composable(
                StoryvoxRoutes.FOLLOWS,
                enterTransition = homeEnter,
                exitTransition = homeExit,
                popEnterTransition = homeEnter,
                popExitTransition = homeExit,
            ) {
                FollowsScreen(
                    onOpenFiction = { id -> navController.navigate(StoryvoxRoutes.fictionDetail(id)) },
                    onOpenSignIn = { navController.navigate(StoryvoxRoutes.authWebView(SourceIds.ROYAL_ROAD)) },
                )
            }
            composable(
                StoryvoxRoutes.BROWSE,
                enterTransition = homeEnter,
                exitTransition = homeExit,
                popEnterTransition = homeEnter,
                popExitTransition = homeExit,
            ) {
                BrowseScreen(
                    onOpenFiction = { id -> navController.navigate(StoryvoxRoutes.fictionDetail(id)) },
                    // #241 / #211 — RR sign-in CTA shared between Browse
                    // (anonymous-listing CTA) and FictionDetail (Follow
                    // button) and Settings → Royal Road.
                    onOpenRoyalRoadSignIn = { navController.navigate(StoryvoxRoutes.authWebView(SourceIds.ROYAL_ROAD)) },
                    // #426 PR2 — AO3 sign-in CTA on the Browse → AO3 chip.
                    onOpenAo3SignIn = { navController.navigate(StoryvoxRoutes.authWebView(SourceIds.AO3)) },
                )
            }

            composable(
                route = StoryvoxRoutes.FICTION_DETAIL,
                arguments = listOf(navArgument("fictionId") { type = NavType.StringType }),
                enterTransition = pushEnter,
                exitTransition = pushExit,
                popEnterTransition = popEnter,
                popExitTransition = popExit,
            ) {
                FictionDetailScreen(
                    onOpenReader = { f, c -> navController.navigate(StoryvoxRoutes.reader(f, c)) },
                    onBack = { navController.popBackStack() },
                    // #211 — Follow on Royal Road button routes to the
                    // shared sign-in WebView when the user is anonymous.
                    onOpenRoyalRoadSignIn = { navController.navigate(StoryvoxRoutes.authWebView(SourceIds.ROYAL_ROAD)) },
                )
            }

            composable(
                route = StoryvoxRoutes.READER,
                arguments = listOf(
                    navArgument("fictionId") { type = NavType.StringType },
                    navArgument("chapterId") { type = NavType.StringType },
                ),
                enterTransition = pushEnter,
                exitTransition = pushExit,
                popEnterTransition = popEnter,
                popExitTransition = popExit,
            ) {
                HybridReaderScreen(
                    onPickVoice = { navController.navigate(StoryvoxRoutes.VOICE_LIBRARY) },
                    onOpenAiSettings = { navController.navigate(StoryvoxRoutes.SETTINGS_AI) },
                    onOpenSettings = { navController.navigate(StoryvoxRoutes.SETTINGS_HUB) },
                    onOpenChat = { fId, prefill -> navController.navigate(StoryvoxRoutes.chat(fId, prefill)) },
                    // Issue #677 follow-up — BookFinishedOverlay's "Browse
                    // more" CTA and ResumeEmptyPrompt's "Browse the realms"
                    // need a working onBrowse on the drill-down reader
                    // route, not just the PLAYING tab. Pre-fix the default
                    // no-op `{}` made both buttons dead — the user finished
                    // a book via FictionDetail → Reader and had no path to
                    // Browse without OS-backing out first.
                    // Issue #918 — use findStartDestination().id for
                    // popUpTo (see onSelectTab comment for rationale).
                    onBrowse = {
                        navController.navigate(StoryvoxRoutes.BROWSE) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    // Issue #437 — Back arrow on deep-linked reader /
                    // audiobook destinations. Falls back to LIBRARY if
                    // the back stack was empty (cold-launch deep link).
                    onBack = {
                        if (!navController.popBackStack()) {
                            navController.navigate(StoryvoxRoutes.LIBRARY) {
                                popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    },
                    // Issue #955 — see PLAYING-route comment.
                    onOpenLibrary = { fId ->
                        navController.navigate(StoryvoxRoutes.fictionDetail(fId))
                    },
                )
            }

            composable(
                route = StoryvoxRoutes.AUDIOBOOK,
                arguments = listOf(
                    navArgument("fictionId") { type = NavType.StringType },
                    navArgument("chapterId") { type = NavType.StringType },
                ),
                enterTransition = pushEnter,
                exitTransition = pushExit,
                popEnterTransition = popEnter,
                popExitTransition = popExit,
            ) {
                HybridReaderScreen(
                    onPickVoice = { navController.navigate(StoryvoxRoutes.VOICE_LIBRARY) },
                    onOpenAiSettings = { navController.navigate(StoryvoxRoutes.SETTINGS_AI) },
                    onOpenSettings = { navController.navigate(StoryvoxRoutes.SETTINGS_HUB) },
                    onOpenChat = { fId, prefill -> navController.navigate(StoryvoxRoutes.chat(fId, prefill)) },
                    // Issue #677 follow-up — same onBrowse wiring as the
                    // READER route above. See the comment there.
                    // Issue #918 — use findStartDestination().id for
                    // popUpTo (see onSelectTab comment for rationale).
                    onBrowse = {
                        navController.navigate(StoryvoxRoutes.BROWSE) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    // Issue #437 — Back arrow on deep-linked reader /
                    // audiobook destinations. Falls back to LIBRARY if
                    // the back stack was empty (cold-launch deep link).
                    onBack = {
                        if (!navController.popBackStack()) {
                            navController.navigate(StoryvoxRoutes.LIBRARY) {
                                popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    },
                    // Issue #955 — see PLAYING-route comment.
                    onOpenLibrary = { fId ->
                        navController.navigate(StoryvoxRoutes.fictionDetail(fId))
                    },
                )
            }

            composable(
                route = StoryvoxRoutes.CHAT,
                arguments = listOf(
                    navArgument("fictionId") { type = NavType.StringType },
                    // Optional prefill for the input field, passed by the
                    // reader's long-press character lookup (#188). Default
                    // is empty string — ChatViewModel treats blank as "no
                    // prefill" and leaves the input untouched. Compose
                    // Navigation only honors `defaultValue` when the param
                    // is declared optional via `?prefill={prefill}` in the
                    // route template (see StoryvoxRoutes.CHAT).
                    navArgument("prefill") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
                enterTransition = pushEnter,
                exitTransition = pushExit,
                popEnterTransition = popEnter,
                popExitTransition = popExit,
            ) {
                ChatScreen(
                    onBack = { navController.popBackStack() },
                    onOpenAiSettings = { navController.navigate(StoryvoxRoutes.SETTINGS_AI) },
                    onOpenVoiceLibrary = { navController.navigate(StoryvoxRoutes.VOICE_LIBRARY) },
                )
            }

            // Issue #440 — Settings hub. New gear-icon destination as of
            // v0.5.38. The hub presents an ordered list of section cards;
            // every named section routes to its own dedicated subscreen.
            // Only the explicit "All settings" escape-hatch row routes to
            // [SETTINGS] (the legacy long-scroll page); the per-section
            // fallback that earlier hub revisions used is gone. Uses
            // homeEnter/Exit (same as SETTINGS) so the hub feels like a
            // peer of the bottom-tab surfaces, not a deep stack push.
            composable(
                StoryvoxRoutes.SETTINGS_HUB,
                enterTransition = homeEnter,
                exitTransition = homeExit,
                popEnterTransition = homeEnter,
                popExitTransition = homeExit,
            ) {
                SettingsHubScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onOpenAllSettings = { navController.navigate(StoryvoxRoutes.SETTINGS) },
                    onOpenVoiceLibrary = { navController.navigate(StoryvoxRoutes.VOICE_LIBRARY) },
                    onOpenPluginManager = { navController.navigate(StoryvoxRoutes.SETTINGS_PLUGINS) },
                    onOpenAiSessions = { navController.navigate(StoryvoxRoutes.SETTINGS_AI_SESSIONS) },
                    onOpenPronunciationDict = { navController.navigate(StoryvoxRoutes.SETTINGS_PRONUNCIATION) },
                    onOpenDebug = { navController.navigate(StoryvoxRoutes.SETTINGS_DEBUG) },
                    onOpenVoicePlayback = { navController.navigate(StoryvoxRoutes.SETTINGS_VOICE_PLAYBACK) },
                    onOpenReading = { navController.navigate(StoryvoxRoutes.SETTINGS_READING) },
                    onOpenPerformance = { navController.navigate(StoryvoxRoutes.SETTINGS_PERFORMANCE) },
                    onOpenAi = { navController.navigate(StoryvoxRoutes.SETTINGS_AI) },
                    onOpenAccessibility = { navController.navigate(StoryvoxRoutes.SETTINGS_ACCESSIBILITY) },
                    onOpenAppearance = { navController.navigate(StoryvoxRoutes.SETTINGS_APPEARANCE) },
                    onOpenAccount = { navController.navigate(StoryvoxRoutes.SETTINGS_ACCOUNT) },
                    onOpenMemoryPalace = { navController.navigate(StoryvoxRoutes.SETTINGS_MEMORY_PALACE) },
                    onOpenAbout = { navController.navigate(StoryvoxRoutes.SETTINGS_ABOUT) },
                    onOpenAdvanced = { navController.navigate(StoryvoxRoutes.SETTINGS_ADVANCED) },
                )
            }

            composable(
                StoryvoxRoutes.SETTINGS,
                enterTransition = homeEnter,
                exitTransition = homeExit,
                popEnterTransition = homeEnter,
                popExitTransition = homeExit,
            ) {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                SettingsScreen(
                    onOpenVoiceLibrary = { navController.navigate(StoryvoxRoutes.VOICE_LIBRARY) },
                    onOpenSignIn = { navController.navigate(StoryvoxRoutes.authWebView(SourceIds.ROYAL_ROAD)) },
                    onOpenGitHubSignIn = { navController.navigate(StoryvoxRoutes.GITHUB_SIGN_IN) },
                    onOpenGitHubRevoke = {
                        // Remote revoke deep-link — opens
                        // `github.com/settings/applications` in the system
                        // browser. Local sign-out alone leaves storyvox's
                        // authorization recorded on GitHub's side; this is
                        // how users tear it down fully. Issue #91.
                        runCatching {
                            ctx.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse(GitHubAuthConfig.SETTINGS_APPLICATIONS_URL),
                                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                            )
                        }
                    },
                    onOpenTeamsSignIn = { navController.navigate(StoryvoxRoutes.TEAMS_SIGN_IN) },
                    onOpenPronunciationDict = { navController.navigate(StoryvoxRoutes.SETTINGS_PRONUNCIATION) },
                    onOpenAiSessions = { navController.navigate(StoryvoxRoutes.SETTINGS_AI_SESSIONS) },
                    onOpenDebug = { navController.navigate(StoryvoxRoutes.SETTINGS_DEBUG) },
                    onOpenPluginManager = { navController.navigate(StoryvoxRoutes.SETTINGS_PLUGINS) },
                    onNavigateBack = { navController.popBackStack() },
                )
            }
            composable(
                StoryvoxRoutes.SETTINGS_PLUGINS,
                enterTransition = pushEnter,
                exitTransition = pushExit,
                popEnterTransition = popEnter,
                popExitTransition = popExit,
            ) {
                `in`.jphe.storyvox.feature.settings.plugins.PluginManagerScreen(
                    onNavigateBack = { navController.popBackStack() },
                    // #501 — Voice bundles section deep-links into the
                    // Voice Library for per-family voice management.
                    // Issue #712 (follow-up to #702) — the "configure
                    // Azure" CTA on the Azure plugin row now lands on
                    // the dedicated [SETTINGS_CLOUD_VOICES] subscreen
                    // instead of dumping the user onto the legacy
                    // long-scroll page.
                    onOpenVoiceLibrary = { navController.navigate(StoryvoxRoutes.VOICE_LIBRARY) },
                    onOpenAzureSettings = { navController.navigate(StoryvoxRoutes.SETTINGS_CLOUD_VOICES) },
                )
            }
            composable(
                StoryvoxRoutes.SETTINGS_DEBUG,
                enterTransition = pushEnter,
                exitTransition = pushExit,
                popEnterTransition = popEnter,
                popExitTransition = popExit,
            ) {
                DebugScreen(onBack = { navController.popBackStack() })
            }
            composable(
                StoryvoxRoutes.SETTINGS_AI_SESSIONS,
                enterTransition = pushEnter,
                exitTransition = pushExit,
                popEnterTransition = popEnter,
                popExitTransition = popExit,
            ) {
                `in`.jphe.storyvox.feature.sessions.SessionsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenChat = { fictionId ->
                        navController.navigate(StoryvoxRoutes.chat(fictionId))
                    },
                )
            }
            composable(
                StoryvoxRoutes.SETTINGS_PRONUNCIATION,
                enterTransition = pushEnter,
                exitTransition = pushExit,
                popEnterTransition = popEnter,
                popExitTransition = popExit,
            ) {
                PronunciationDictScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            // ─── Follow-up to #440 — dedicated subscreens for the seven
            // hub cards that previously fell through to the legacy long-
            // scroll [SETTINGS] page. Each uses push enter/exit because
            // it's reached from the hub via drill-down, not as a peer
            // home tab. ───────────────────────────────────────────────
            composable(
                StoryvoxRoutes.SETTINGS_VOICE_PLAYBACK,
                enterTransition = pushEnter,
                exitTransition = pushExit,
                popEnterTransition = popEnter,
                popExitTransition = popExit,
            ) {
                VoiceAndPlaybackSettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenVoiceLibrary = { navController.navigate(StoryvoxRoutes.VOICE_LIBRARY) },
                    onOpenPronunciationDict = { navController.navigate(StoryvoxRoutes.SETTINGS_PRONUNCIATION) },
                )
            }
            composable(
                StoryvoxRoutes.SETTINGS_READING,
                enterTransition = pushEnter,
                exitTransition = pushExit,
                popEnterTransition = popEnter,
                popExitTransition = popExit,
            ) {
                ReadingSettingsScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                StoryvoxRoutes.SETTINGS_PERFORMANCE,
                enterTransition = pushEnter,
                exitTransition = pushExit,
                popEnterTransition = popEnter,
                popExitTransition = popExit,
            ) {
                PerformanceSettingsScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                StoryvoxRoutes.SETTINGS_AI,
                enterTransition = pushEnter,
                exitTransition = pushExit,
                popEnterTransition = popEnter,
                popExitTransition = popExit,
            ) {
                AiSettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenAiSessions = { navController.navigate(StoryvoxRoutes.SETTINGS_AI_SESSIONS) },
                    onOpenTeamsSignIn = { navController.navigate(StoryvoxRoutes.TEAMS_SIGN_IN) },
                )
            }
            // Phase 1 scaffold (v0.5.42) — Accessibility subscreen. Push
            // transitions mirror the rest of the hub-routed subscreens
            // (Voice & Playback, Reading, Performance, AI, Account,
            // Memory Palace, About). Phase 2 wires the actual a11y
            // behavior; Phase 1 only persists the prefs.
            composable(
                StoryvoxRoutes.SETTINGS_ACCESSIBILITY,
                enterTransition = pushEnter,
                exitTransition = pushExit,
                popEnterTransition = popEnter,
                popExitTransition = popExit,
            ) {
                AccessibilitySettingsScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            // v0.5.59 (#cover-style-toggle) — Appearance subscreen. Book-
            // cover fallback style picker (Monogram / Branded / Cover
            // only) with a live preview chip-row.
            composable(
                StoryvoxRoutes.SETTINGS_APPEARANCE,
                enterTransition = pushEnter,
                exitTransition = pushExit,
                popEnterTransition = popEnter,
                popExitTransition = popExit,
            ) {
                AppearanceSettingsScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            // v1 settings-bundle-7 — Advanced subscreen. Power-user
            // integration tunables. v1 ships #598 (Android Auto
            // bucket size); future Advanced rows land here.
            composable(
                StoryvoxRoutes.SETTINGS_ADVANCED,
                enterTransition = pushEnter,
                exitTransition = pushExit,
                popEnterTransition = popEnter,
                popExitTransition = popExit,
            ) {
                AdvancedSettingsScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            // Issue #712 (follow-up to #404 + #702) — Cloud Voices
            // subscreen. Azure BYOK key + region + test-connection +
            // offline-fallback toggle, hosted in the same [AzureSection]
            // composable the legacy long-scroll page uses, so the two
            // surfaces stay byte-identical. Reached from the Plugin
            // Manager's Azure row "configure" CTA; previously that tap
            // dumped users onto the legacy page (#712).
            composable(
                StoryvoxRoutes.SETTINGS_CLOUD_VOICES,
                enterTransition = pushEnter,
                exitTransition = pushExit,
                popEnterTransition = popEnter,
                popExitTransition = popExit,
            ) {
                CloudVoicesSettingsScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                StoryvoxRoutes.SETTINGS_ACCOUNT,
                enterTransition = pushEnter,
                exitTransition = pushExit,
                popEnterTransition = popEnter,
                popExitTransition = popExit,
            ) {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                AccountSettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenSignIn = { navController.navigate(StoryvoxRoutes.authWebView(SourceIds.ROYAL_ROAD)) },
                    onOpenGitHubSignIn = { navController.navigate(StoryvoxRoutes.GITHUB_SIGN_IN) },
                    onOpenGitHubRevoke = {
                        // Mirrors the SETTINGS legacy page's revoke handler:
                        // opens github.com/settings/applications in the
                        // system browser so users can tear down storyvox's
                        // authorization remotely. Local sign-out alone
                        // leaves it recorded on GitHub's side. Issue #91.
                        runCatching {
                            ctx.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse(GitHubAuthConfig.SETTINGS_APPLICATIONS_URL),
                                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                            )
                        }
                    },
                )
            }
            composable(
                StoryvoxRoutes.SETTINGS_MEMORY_PALACE,
                enterTransition = pushEnter,
                exitTransition = pushExit,
                popEnterTransition = popEnter,
                popExitTransition = popExit,
            ) {
                MemoryPalaceSettingsScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                StoryvoxRoutes.SETTINGS_ABOUT,
                enterTransition = pushEnter,
                exitTransition = pushExit,
                popEnterTransition = popEnter,
                popExitTransition = popExit,
            ) {
                AboutSettingsScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                StoryvoxRoutes.VOICE_LIBRARY,
                enterTransition = homeEnter,
                exitTransition = homeExit,
                popEnterTransition = homeEnter,
                popExitTransition = homeExit,
            ) {
                VoiceLibraryScreen()
            }
            composable(
                StoryvoxRoutes.AUTH_WEBVIEW,
                arguments = listOf(
                    navArgument("sourceId") {
                        type = NavType.StringType
                        defaultValue = SourceIds.ROYAL_ROAD
                    },
                ),
                enterTransition = pushEnter,
                exitTransition = pushExit,
                popEnterTransition = popEnter,
                popExitTransition = popExit,
            ) { backStackEntry ->
                // #426 PR2 — sourceId arg picks the per-source WebView
                // (RR vs AO3) inside [AuthWebViewScreen]. Defaults to
                // ROYAL_ROAD so any pre-PR2 navigation literal that
                // somehow misses the path-param falls into the legacy
                // RR path rather than crashing.
                val sourceId = backStackEntry.arguments
                    ?.getString("sourceId")
                    ?: SourceIds.ROYAL_ROAD
                AuthWebViewScreen(
                    sourceId = sourceId,
                    onSignedIn = { navController.popBackStack() },
                    onCancelled = { navController.popBackStack() },
                )
            }
            composable(
                StoryvoxRoutes.GITHUB_SIGN_IN,
                enterTransition = pushEnter,
                exitTransition = pushExit,
                popEnterTransition = popEnter,
                popExitTransition = popExit,
            ) {
                GitHubSignInScreen(
                    onSignedIn = { navController.popBackStack() },
                    onCancelled = { navController.popBackStack() },
                )
            }
            composable(
                StoryvoxRoutes.TEAMS_SIGN_IN,
                enterTransition = pushEnter,
                exitTransition = pushExit,
                popEnterTransition = popEnter,
                popExitTransition = popExit,
            ) {
                AnthropicTeamsSignInScreen(
                    onSignedIn = { navController.popBackStack() },
                    onCancelled = { navController.popBackStack() },
                )
            }
            // Issue #500 — InstantDB email + magic-code sign-in.
            // Same push/pop transition family as the other auth
            // surfaces (RR WebView, GitHub, Teams) so the navigation
            // motion vocabulary stays consistent. The SyncAuthScreen
            // owns its own brass-themed scaffold and routes back via
            // [onClose] when the user finishes or aborts.
            composable(
                StoryvoxRoutes.SYNC,
                enterTransition = pushEnter,
                exitTransition = pushExit,
                popEnterTransition = popEnter,
                popExitTransition = popExit,
            ) {
                `in`.jphe.storyvox.feature.sync.SyncAuthScreen(
                    onClose = { navController.popBackStack() },
                )
            }
            // Issue #517 — TechEmpower as default use case. Two new
            // drill-down destinations reached from the brass-edged
            // TechEmpower hero card pinned at the top of Library.
            // Drill-down depth (push enter/exit transitions) — not a
            // bottom-bar destination — so cold-launch still lands on
            // Library and TechEmpower Home is opt-in via tap.
            composable(
                StoryvoxRoutes.TECHEMPOWER_HOME,
                enterTransition = pushEnter,
                exitTransition = pushExit,
                popEnterTransition = popEnter,
                popExitTransition = popExit,
            ) {
                TechEmpowerHomeScreen(
                    onBack = { navController.popBackStack() },
                    onOpenBrowse = {
                        // Browse Resources tap routes to standalone
                        // Browse — same destination as the empty-state
                        // "Browse the realms" CTA on Playing. The
                        // anonymous-mode Notion source is the v0.5.48
                        // position-1 plugin in the Browse tab strip,
                        // so users land on TechEmpower's four-fiction
                        // tile set (Guides / Resources / About /
                        // Donate) immediately.
                        // Issue #918 — use findStartDestination().id for
                        // popUpTo (see onSelectTab comment for rationale).
                        navController.navigate(StoryvoxRoutes.BROWSE) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onOpenAbout = {
                        navController.navigate(StoryvoxRoutes.TECHEMPOWER_ABOUT)
                    },
                    onOpenFiction = { fictionId ->
                        navController.navigate(StoryvoxRoutes.fictionDetail(fictionId))
                    },
                )
            }
            composable(
                StoryvoxRoutes.TECHEMPOWER_ABOUT,
                enterTransition = pushEnter,
                exitTransition = pushExit,
                popEnterTransition = popEnter,
                popExitTransition = popExit,
            ) {
                TechEmpowerAboutScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            } // closes NavHost(...) builder lambda
            // Issue #678 — tablet-only dock placement. On phone width
            // (useSideRail=false) the dock rides along with the
            // BottomTabBar inside the Scaffold's bottomBar slot; on
            // tablet there's no bottom bar so the dock pins to the
            // bottom of this content Column instead.
            if (useSideRail && showNowPlayingDock) {
                NowPlayingDock(onOpenReader = onOpenReaderFromDock)
            }
            } // Issue #678 — closes the content Column wrapping NavHost + tablet dock
        } // Issue #629 — closes the Row wrapper added for tablet side-rail layout
    }
}

object DeepLinkResolver {
    private val FICTION_PATH = Regex("^/fiction/(\\d+)(/.*)?$")

    /** Extras the playback notification's tap intent carries — see
     *  [in.jphe.storyvox.playback.StoryvoxPlaybackService] session activity. */
    const val EXTRA_OPEN_READER_FICTION_ID = "storyvox.open_reader.fiction_id"
    const val EXTRA_OPEN_READER_CHAPTER_ID = "storyvox.open_reader.chapter_id"

    /** Issue #472 — query-string carried on the Library route when a
     *  shared URL needs to land in the Magic-add sheet. The Library
     *  screen pulls this off the nav arguments on first composition
     *  and pre-fills the sheet via `viewModel.openAddByUrlPrefilled`. */
    const val ARG_SHARED_URL = "sharedUrl"

    fun resolve(intent: Intent): String? {
        // Notification tap → reader for the currently-playing chapter.
        val rf = intent.getStringExtra(EXTRA_OPEN_READER_FICTION_ID)
        val rc = intent.getStringExtra(EXTRA_OPEN_READER_CHAPTER_ID)
        if (!rf.isNullOrBlank() && !rc.isNullOrBlank()) {
            return StoryvoxRoutes.reader(rf, rc)
        }
        // Issue #472 — ACTION_SEND share-intent path. Any app (browser,
        // podcast client, RSS reader, social share menu) can share a
        // URL into storyvox; the activity routes to Library with the
        // URL surfaced as a query arg, and LibraryScreen opens the
        // Magic-add sheet pre-populated. The resolver itself doesn't
        // run UrlResolver here — that decision is the viewmodel's
        // job and would couple this pure-function helper to Hilt-
        // resolved deps.
        if (intent.action == Intent.ACTION_SEND) {
            val shared = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
            if (shared.isNotBlank() && looksLikeUrl(shared)) {
                return StoryvoxRoutes.libraryWithShare(shared)
            }
            return null
        }
        // Open-with deep link from royalroad.com.
        if (intent.action != Intent.ACTION_VIEW) return null
        val data: Uri = intent.data ?: return null
        if (data.scheme != "https") return null
        val host = data.host ?: return null
        if (host != "royalroad.com" && host != "www.royalroad.com") return null
        val path = data.path ?: return null
        val match = FICTION_PATH.matchEntire(path) ?: return null
        val id = match.groupValues[1]
        return StoryvoxRoutes.fictionDetail(id)
    }

    /** Lightweight URL sniffer — accepts http(s) URLs only. Apps that
     *  share plaintext frequently emit "title\nURL" or "URL extra
     *  text" via Intent.EXTRA_TEXT; we extract the first http(s)
     *  token if the body is multi-line, otherwise require the whole
     *  string to look like a URL. */
    private fun looksLikeUrl(text: String): Boolean {
        if (text.startsWith("http://", ignoreCase = true) ||
            text.startsWith("https://", ignoreCase = true)
        ) return true
        // Multi-line share — find the first http(s) token.
        return text.lineSequence()
            .mapNotNull { line ->
                line.split(' ', '\t', '\n').firstOrNull { tok ->
                    tok.startsWith("http://", ignoreCase = true) ||
                        tok.startsWith("https://", ignoreCase = true)
                }
            }
            .firstOrNull() != null
    }
}
