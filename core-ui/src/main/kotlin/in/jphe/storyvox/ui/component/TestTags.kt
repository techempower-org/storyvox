package `in`.jphe.storyvox.ui.component

/**
 * Stable, kebab-case test-tag identifiers for the core navigable Compose
 * surface — the addressing layer the UI-test stack (Maestro flows +
 * instrumented Compose tests) selects by.
 *
 * **Why a constants object instead of inline string literals**: UI-test
 * selectors are a contract between the app and the test suite. A pixel-
 * or copy-based selector breaks the moment a layout shifts or a label is
 * reworded; a `testTag` survives both. Centralizing the strings here means
 * (a) the test suite imports the same symbol the UI sets, so a rename is a
 * compile error rather than a silently-broken flow, and (b) the tags are
 * discoverable in one place rather than scattered across screen files.
 *
 * **Convention**: kebab-case, `<area>-<element>` (e.g. `nav-browse`,
 * `library-fab`, `reader-body`). Tags are append-only and stable — adding
 * is free; renaming or removing one is a breaking change for downstream
 * Maestro flows and must be coordinated.
 *
 * Set via `Modifier.testTag(TestTags.NavBrowse)`. These are **non-functional**
 * — they only populate the Compose semantics tree and have zero effect on
 * behavior, layout, or accessibility (a11y announcements come from
 * `contentDescription` / `semantics`, which are untouched).
 */
object TestTags {
    // ── Bottom-nav destinations ──────────────────────────────────────────
    // One per HomeTab entry. Derived from the tab name so the dock and the
    // selectors can't drift; see [navTab].
    const val NavPlaying = "nav-playing"
    const val NavLibrary = "nav-library"
    const val NavBrowse = "nav-browse"
    const val NavVoices = "nav-voices"
    const val NavSettings = "nav-settings"

    /**
     * Stable nav tag for a [HomeTab], e.g. `HomeTab.Browse` → `"nav-browse"`.
     * The dock applies this per cell so a flow can tap a destination by
     * `nav-<tab>` without depending on cell order or visible label.
     */
    fun navTab(tab: HomeTab): String = "nav-" + tab.name.lowercase()

    // ── Library ──────────────────────────────────────────────────────────
    const val LibraryList = "library-list"
    const val LibraryItem = "library-item"
    const val LibraryFab = "library-fab"

    // ── Browse ───────────────────────────────────────────────────────────
    const val BrowseSearchField = "browse-search-field"
    const val BrowseSourceList = "browse-source-list"
    const val BrowseResults = "browse-results"
    const val BrowseFilter = "browse-filter"

    // ── Reader ───────────────────────────────────────────────────────────
    const val ReaderBody = "reader-body"
    const val ReaderPlay = "reader-play"
    const val ReaderBack = "reader-back"

    // ── Reader highlights (#1079 phase 2) ────────────────────────────────
    // The select-text → highlight flow. A Maestro flow drives: drag-select
    // body text → [HighlightSheet] appears → pick a swatch in
    // [HighlightPalette] (+ optional [HighlightNoteField]) → [HighlightConfirm];
    // then tap a saved highlight → [HighlightEditSheet] → [HighlightSave] or
    // [HighlightDelete]. Tagged on the sheet container + each control so the
    // flow selects them without depending on visible copy.
    const val HighlightSheet = "highlight-sheet"
    const val HighlightEditSheet = "highlight-edit-sheet"
    const val HighlightPalette = "highlight-palette"
    const val HighlightNoteField = "highlight-note-field"
    const val HighlightConfirm = "highlight-confirm"
    const val HighlightSave = "highlight-save"
    const val HighlightDelete = "highlight-delete"

    // ── Voices ───────────────────────────────────────────────────────────
    const val VoiceList = "voice-list"

    // ── Settings ─────────────────────────────────────────────────────────
    const val SettingsList = "settings-list"

    // ── Common dialogs / sheets ──────────────────────────────────────────
    const val DialogConfirm = "dialog-confirm"
    const val DialogDismiss = "dialog-dismiss"
}
