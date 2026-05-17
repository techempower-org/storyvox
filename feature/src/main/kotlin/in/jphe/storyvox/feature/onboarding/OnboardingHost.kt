package `in`.jphe.storyvox.feature.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import `in`.jphe.storyvox.feature.api.FictionRepositoryUi
import `in`.jphe.storyvox.feature.api.PlaybackControllerUi
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi

/**
 * Issue #599 (v1.0 blocker) — the three-screen first-launch welcome
 * flow. Mounted at the navigation host root, *above* the existing
 * [VoicePickerGate], so a brand-new user sees the welcome before the
 * engineering-themed voice picker. Once the user finishes (or skips)
 * the welcome, [SettingsRepositoryUi.markOnboardingCompletedV1] flips
 * the persisted flag and the host disappears for the life of this
 * install (until Settings → Developer → Reset onboarding flips it
 * back for testing).
 *
 * State machine (three steps, forward-only — no Back, by design; if
 * the user needs to revisit a choice they completed earlier they can
 * use the corresponding affordance in the post-welcome app):
 *
 *   Welcome → VoicePicker → FirstFiction → done.
 *
 * Each transition is a fade — the screens share the same background
 * colour so a horizontal slide would feel like the user moved
 * "inside" the welcome, which is wrong; the welcome is a single
 * conceptual step composed of three pages.
 *
 * The host renders `content()` underneath itself unconditionally so
 * the embedded NavHost is alive when the user finally taps a CTA
 * that calls `navController.navigate(...)`. Same pattern as
 * VoicePickerGate; without this the route resolution races the
 * destination registration on cold launch.
 */
@Composable
fun OnboardingHost(
    onOpenTechEmpower: () -> Unit,
    onAddFromWebsite: (clipboardUrl: String?) -> Unit,
    onOpenVoiceLibrary: () -> Unit,
    /**
     * Issues #644 + #647 (v1.0) — invoked after the viewmodel has
     * resolved the first chapter of the TechEmpower Guides fiction
     * and queued it on the playback controller with `autoPlay=true`.
     * Routes the user to the PLAYING surface so they land on the
     * transport UI as audio starts. Distinct from [onOpenTechEmpower]
     * (the hub-landing legacy route) so a future variant of the
     * onboarding can keep the hub-stop path without touching this
     * one. Default no-op so a caller that doesn't set it falls back
     * to the legacy hub-routed CTA.
     */
    onOpenGuidesAndPlay: () -> Unit = onOpenTechEmpower,
    viewModel: OnboardingHostViewModel = hiltViewModel(),
    content: @Composable () -> Unit,
) {
    val show by viewModel.shouldShow.collectAsStateWithLifecycle()
    Box(modifier = Modifier.fillMaxSize()) {
        content()
        if (show) {
            // Forward-only three-step state machine. rememberSaveable
            // so a configuration change (rotation) doesn't snap the
            // user back to step 1 mid-flow.
            var step by rememberSaveable { mutableStateOf(OnboardingStep.Welcome) }
            val swallow = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    // Tap-swallow shield (same pattern as
                    // VoicePickerGate) — prevents underlying Library
                    // taps from leaking through the welcome overlay.
                    .clickable(
                        interactionSource = swallow,
                        indication = null,
                        onClick = {},
                    ),
            ) {
                AnimatedContent(
                    targetState = step,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "onboarding-step",
                ) { current ->
                    when (current) {
                        OnboardingStep.Welcome -> WelcomeScreen(
                            onGetStarted = { step = OnboardingStep.VoicePick },
                            onSkip = {
                                viewModel.markCompleted()
                            },
                        )
                        OnboardingStep.VoicePick -> VoicePickerOnboarding(
                            onContinue = { step = OnboardingStep.FirstFiction },
                            onSkip = { step = OnboardingStep.FirstFiction },
                            onMoreVoices = {
                                viewModel.markCompleted()
                                onOpenVoiceLibrary()
                            },
                        )
                        OnboardingStep.FirstFiction -> FirstFictionPicker(
                            // Issues #644 + #647 (v1.0) — bypass the
                            // TechEmpower Home hub AND the Guides
                            // fiction-detail stop. The viewmodel
                            // resolves the first chapter of the
                            // `notion:guides` fiction, queues it on
                            // the playback controller with
                            // `autoPlay=true`, then [onOpenGuidesAndPlay]
                            // navigates the user to the Playing
                            // surface so they land on the transport
                            // UI as the chapter warms. Net: two taps
                            // (the hub stop + the Guides-cover stop)
                            // disappear from the first-launch flow.
                            //
                            // Fallback path: if chapter resolution
                            // fails (network down, source unreachable,
                            // anonymous Notion 404), the viewmodel
                            // invokes [onOpenTechEmpower] instead so
                            // the user lands on the hub with the same
                            // four-card affordance they would have
                            // seen pre-fix — never a dead end.
                            onBrowseTechEmpower = {
                                viewModel.openGuidesAndAutoPlay(
                                    onPlaying = onOpenGuidesAndPlay,
                                    onFallbackToHub = onOpenTechEmpower,
                                )
                            },
                            onAddFromWebsite = { clip ->
                                viewModel.markCompleted()
                                onAddFromWebsite(clip)
                            },
                            onSkip = {
                                viewModel.markCompleted()
                            },
                        )
                    }
                }
            }
        }
    }
}

/** The three pages of the welcome flow, in display order. */
internal enum class OnboardingStep { Welcome, VoicePick, FirstFiction }

@HiltViewModel
class OnboardingHostViewModel @Inject constructor(
    private val settings: SettingsRepositoryUi,
    /**
     * Issues #644 + #647 (v1.0) — used by [openGuidesAndAutoPlay] to
     * resolve the first chapter of the TechEmpower Guides fiction
     * (`notion:guides`). The first emission of [chaptersFor] is the
     * source of truth; we take its head element. Anonymous-mode
     * Notion fetches return a non-empty list within ~1 s of the
     * source's first-subscription refresh, so a single
     * [Flow.first] is safe here as a one-shot resolve.
     */
    private val fictionRepo: FictionRepositoryUi,
    /**
     * Issues #644 + #647 — used by [openGuidesAndAutoPlay] to queue
     * the resolved chapter with `autoPlay=true`. The controller
     * handles voice warming + chapter download internally; the
     * onboarding host just kicks off the load and routes to the
     * Playing surface so the user lands on a live transport UI.
     */
    private val playback: PlaybackControllerUi,
) : ViewModel() {

    /** True iff the welcome flow should be on screen right now. Maps
     *  the inverse of [SettingsRepositoryUi.onboardingCompletedV1] —
     *  show the flow until the user completes / skips it.
     *
     *  Eager start so the initial DataStore read overlaps with the
     *  cold-launch composition; otherwise a brand-new install would
     *  briefly see the Library underneath before the welcome
     *  composed in. */
    val shouldShow: StateFlow<Boolean> = settings.onboardingCompletedV1
        .map { !it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Called from any of the screens' completion / skip paths. The
     *  DataStore write is fire-and-forget; the [shouldShow] flow
     *  flips false on the next emission and the host self-removes
     *  from the composition. */
    fun markCompleted() {
        viewModelScope.launch { settings.markOnboardingCompletedV1() }
    }

    /**
     * Issues #644 + #647 (v1.0) — the "Browse TechEmpower's free
     * guides" CTA's payload. Resolves the first chapter of the
     * Guides fiction (`notion:guides`, served by the anonymous-mode
     * Notion source), queues it on [playback] with `autoPlay=true`,
     * marks onboarding complete, and routes the user to the Playing
     * surface via [onPlaying]. If the chapter list comes back empty
     * (network failure, source unreachable, anonymous 404), we fall
     * back to [onFallbackToHub] so the user still lands on the
     * TechEmpower Home hub instead of a dead screen — the legacy
     * pre-fix behavior was hub-first anyway, so the fallback is
     * strictly a worst-case parity guarantee.
     *
     * Why the fallback is null-or-empty (not the more general
     * try/catch): [FictionRepositoryUi.chaptersFor] is documented to
     * emit a non-null List on every observation; the failure modes
     * surface as empty lists (network refresh failed before any
     * cache landed) rather than exceptions. The flow's
     * first-subscription refresh runs as a side effect, so the head
     * element is whatever the cache + refresh produced together.
     *
     * The viewmodel calls [markCompleted] BEFORE the playback queue
     * so the onboarding host self-removes from the composition
     * immediately; if the user backgrounds the app mid-warm we don't
     * want them seeing the welcome again on next launch just because
     * playback hadn't started yet.
     */
    fun openGuidesAndAutoPlay(
        onPlaying: () -> Unit,
        onFallbackToHub: () -> Unit,
    ) {
        viewModelScope.launch {
            // Mark completed up-front. The DataStore write is
            // fire-and-forget; we don't await it because the
            // onboarding host's [shouldShow] flow will pick up the
            // change on its next emission, and the user is about to
            // either hit Playing (chapter resolved) or the hub
            // (fallback) — neither needs the welcome flow back.
            settings.markOnboardingCompletedV1()
            // Resolve the first chapter of the Guides fiction in
            // three steps:
            //
            //  1. Seed the local Fiction DAO with the Notion
            //     anonymous-mode source's "popular" tiles. This is
            //     what writes the `notion:guides` row into the DB
            //     with the correct `sourceId="notion"`. Without it,
            //     [FictionRepository.refreshDetail] falls back to
            //     `SourceIds.ROYAL_ROAD` for an unknown row and the
            //     refresh silently fails — chapters never land. This
            //     mirrors the implicit pre-seeding the Browse tab's
            //     Notion plugin does when the user opens it via
            //     TechEmpower Home → Browse Resources in the legacy
            //     8-tap flow.
            //
            //  2. Drive the refresh side-effect via [fictionById]'s
            //     first emission. We don't care about the emitted
            //     UiFiction value; the important thing is that
            //     `refreshDetail("notion:guides")` has completed by
            //     the time this returns.
            //
            //  3. Wait for [chaptersFor] to emit a non-empty list and
            //     take the first chapter. The observeFiction flow
            //     emits as soon as the Room row lands from the
            //     refresh; chaptersFor combines it with the
            //     observePlayedChapterIds flow.
            //
            // All three steps share a single [withTimeoutOrNull]
            // budget so a flaky network or a slow Notion endpoint
            // can't hang the onboarding flow indefinitely. On
            // timeout we fall back to the TechEmpower Home hub so
            // the user has a working surface to land on.
            val firstChapter = withTimeoutOrNull(GUIDES_RESOLVE_TIMEOUT_MS) {
                runCatching {
                    // Step 1: seed Notion tiles into the local DB.
                    // We intentionally ignore the boolean return —
                    // step 2 will still fail-fast if seeding didn't
                    // populate the row, and we'd rather fall through
                    // to the hub than swallow a network failure
                    // here.
                    fictionRepo.seedPopularForSource(NOTION_SOURCE_ID)
                    // Step 2: drive the per-fiction refresh.
                    fictionRepo.fictionById(GUIDES_FICTION_ID).firstOrNull()
                    // Step 3: wait for the chapter list to land.
                    fictionRepo.chaptersFor(GUIDES_FICTION_ID)
                        .first { it.isNotEmpty() }
                        .first()
                }.getOrNull()
            }
            if (firstChapter == null) {
                // Fallback: source didn't surface a chapter list in
                // time. Land the user on the TechEmpower Home hub
                // (the legacy CTA target) so they have an obvious
                // path forward — Browse Resources → Guides — instead
                // of dead-ending on the Playing surface with no
                // chapter queued.
                onFallbackToHub()
                return@launch
            }
            // Queue the chapter and route. `autoPlay=true` lets the
            // playback controller start audio as soon as the voice
            // is warm and the chapter body lands.
            playback.startListening(
                fictionId = GUIDES_FICTION_ID,
                chapterId = firstChapter.id,
                charOffset = 0,
                autoPlay = true,
            )
            onPlaying()
        }
    }

    internal companion object {
        /**
         * The Notion anonymous-mode source's `notion:guides` fiction
         * — the TechEmpower curated guides PageList. Inline here (and
         * mirrored in TechEmpowerHomeScreen's chip strip) so the
         * onboarding doesn't take a runtime dependency on the
         * source-notion module just to know one identifier.
         */
        const val GUIDES_FICTION_ID: String = "notion:guides"

        /**
         * Source id for the anonymous-mode Notion delegate that
         * serves the `notion:guides` fiction. Inline here (mirrors
         * `SourceIds.NOTION` in :core-data) so the onboarding doesn't
         * take a module dependency on `:core-data` just for one
         * string constant. If `SourceIds.NOTION` ever moves to a
         * different string, a one-grep audit catches this site.
         */
        const val NOTION_SOURCE_ID: String = "notion"

        /**
         * Upper bound on how long [openGuidesAndAutoPlay] waits for
         * the anonymous-Notion seed + refresh to land a chapter
         * list. Once exceeded we fall back to the TechEmpower Home
         * hub rather than spinning a quiet welcome screen for the
         * user.
         *
         * 8000 ms is a generous budget for a cold-launch Notion
         * round-trip on a hotel-wifi connection (seeding + refresh
         * each typically clear in <1.5 s on a normal home network,
         * but the welcome flow runs on whatever wifi the user has
         * at the moment of first launch). On timeout the user
         * still gets a working surface via the hub fallback, so a
         * conservative budget doesn't lock anyone out.
         */
        const val GUIDES_RESOLVE_TIMEOUT_MS: Long = 8_000L
    }
}
