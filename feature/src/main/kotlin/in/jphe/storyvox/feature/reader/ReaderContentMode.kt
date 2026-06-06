package `in`.jphe.storyvox.feature.reader

/**
 * Which top-level surface [HybridReaderScreen] should render. Extracted
 * from the screen's inline branch logic so the decision is unit-testable
 * (the screen consumes the enum and renders the matching composable).
 */
enum class ReaderContentMode {
    /**
     * The brass loading card for an explicit drill-down
     * (`/reader/{fictionId}/{chapterId}`) whose chapter hasn't surfaced in
     * the global PlaybackController yet. Owns the loading affordance so the
     * screen never falls back to the global resume prompt — or, post-fix,
     * to a PRIOR book — while the requested chapter is still loading.
     */
    ExplicitLoading,

    /** Magical Resume prompt for the most-recent continue-listening entry. */
    ResumePrompt,

    /** "Your library awaits / Browse the realms" empty prompt. */
    ResumeEmpty,

    /** Render the player (AudiobookView) for the controller's loaded chapter. */
    Content,
}

/**
 * Decide the reader's top-level surface from the nav args + the GLOBAL
 * PlaybackController state. [HybridReaderScreen] renders the controller's
 * state, not its own nav args, so this function is the single gate that
 * keeps the explicit drill-down route from painting the wrong content.
 *
 * Issue #638 (v1.0) added the [ExplicitLoading] gate to stop the explicit
 * route from flashing the empty resume prompt during the cold-start
 * warm-up window (controller ids still BLANK).
 *
 * TechEmpower wrong-book regression (v1.1.1) — that gate was too narrow.
 * It only held while the controller's ids were blank. When the app
 * process survives from a prior session, the controller still holds the
 * PRIOR book's (non-blank) ids, so on a fresh explicit open of a
 * different fiction (e.g. Resources) the gate fell through to [Content]
 * and the screen painted the prior book until the requested chapter
 * finally loaded — or permanently, if the cold body-fetch timed out. The
 * fix: also hold [ExplicitLoading] while the controller is pointed at a
 * DIFFERENT fiction than the one the explicit route requested
 * ([argFictionId]). Once the controller catches up to [argFictionId] the
 * gate releases and the player renders.
 *
 * @param hasExplicitChapterArgs true on the `/reader/{f}/{c}` and
 *   `/audiobook/{f}/{c}` routes (both args present); false on the bare
 *   `/playing` tab.
 * @param argFictionId the fictionId from the explicit nav args, or null on
 *   the bare `/playing` route.
 * @param hasPlayback whether the controller's UI state has emitted (the
 *   reader's `state.playback != null`).
 * @param playbackFictionId the controller's currently-loaded fictionId.
 * @param playbackChapterId the controller's currently-loaded chapterId.
 * @param hasResumeEntry whether a most-recent continue-listening entry
 *   exists (picks [ResumePrompt] vs [ResumeEmpty]).
 * @param timedOut whether the load timer hit [LoadingPhase.TimedOut].
 */
internal fun readerContentMode(
    hasExplicitChapterArgs: Boolean,
    argFictionId: String?,
    hasPlayback: Boolean,
    playbackFictionId: String?,
    playbackChapterId: String?,
    hasResumeEntry: Boolean,
    timedOut: Boolean,
): ReaderContentMode {
    // The controller has no real chapter to render yet — three cases:
    //  (a) playback flow hasn't emitted (cold start, app-killed)
    //  (b) it emitted a default state with both ids null
    //  (c) the load timed out and we have a resume entry to fall back on
    val showPromptForNullPlayback = !hasPlayback
    val showPromptForBlankIds = hasPlayback &&
        playbackFictionId == null &&
        playbackChapterId == null
    val showPromptForTimedOutWithEntry = hasPlayback && timedOut && hasResumeEntry

    // Issue #638 + wrong-book fix — suppress the prompt branch on an
    // explicit-args entry while the controller is still warming up OR is
    // pointed at a DIFFERENT fiction than the one requested. The latter
    // clause is the regression fix: a surviving prior book must not paint
    // through the explicit route. Once the controller's fictionId matches
    // the requested argFictionId, this is false and the player renders.
    val explicitArgsPointAtDifferentFiction = hasExplicitChapterArgs &&
        argFictionId != null &&
        playbackFictionId != argFictionId
    val isExplicitArgsLoading = hasExplicitChapterArgs &&
        (showPromptForNullPlayback || showPromptForBlankIds || explicitArgsPointAtDifferentFiction)
    if (isExplicitArgsLoading) {
        return ReaderContentMode.ExplicitLoading
    }

    if (showPromptForNullPlayback || showPromptForBlankIds ||
        showPromptForTimedOutWithEntry
    ) {
        return if (hasResumeEntry) ReaderContentMode.ResumePrompt else ReaderContentMode.ResumeEmpty
    }

    return ReaderContentMode.Content
}
