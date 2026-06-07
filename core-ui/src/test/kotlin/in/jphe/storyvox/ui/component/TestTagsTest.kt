package `in`.jphe.storyvox.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract guard for [TestTags]. These tags are the selector contract
 * between the app and the UI-test stack (Maestro flows + instrumented
 * Compose tests). A rename or drift here silently breaks every flow that
 * addresses the tag, and Maestro failures surface far from the cause —
 * so pin the exact strings and the [TestTags.navTab] derivation here, in
 * the fast unit-test set, where a break is a compile/assert error.
 */
class TestTagsTest {

    @Test
    fun `navTab derives the documented per-destination tag`() {
        // The dock applies `nav-<tab>` per cell; the Maestro flows select
        // by these exact strings. Keep the derivation and the published
        // constants in lock-step.
        assertEquals(TestTags.NavPlaying, TestTags.navTab(HomeTab.Playing))
        assertEquals(TestTags.NavLibrary, TestTags.navTab(HomeTab.Library))
        assertEquals(TestTags.NavBrowse, TestTags.navTab(HomeTab.Browse))
        assertEquals(TestTags.NavVoices, TestTags.navTab(HomeTab.Voices))
        assertEquals(TestTags.NavSettings, TestTags.navTab(HomeTab.Settings))
    }

    @Test
    fun `every HomeTab has a stable nav tag`() {
        // No HomeTab may be left without a `nav-<tab>` selector — a new
        // dock destination added without a tag would be unaddressable by
        // the test stack.
        HomeTab.entries.forEach { tab ->
            val tag = TestTags.navTab(tab)
            assertEquals("nav-" + tab.name.lowercase(), tag)
            assertTrue("nav tag for ${tab.name} must be non-blank", tag.isNotBlank())
        }
    }

    @Test
    fun `core selector strings are stable and kebab-case`() {
        // Downstream Maestro flows hard-code these. A rename is a breaking
        // change; this asserts the published values so a careless edit
        // fails here rather than in a flaky device run.
        assertEquals("library-list", TestTags.LibraryList)
        assertEquals("library-item", TestTags.LibraryItem)
        assertEquals("library-fab", TestTags.LibraryFab)
        assertEquals("browse-search-field", TestTags.BrowseSearchField)
        assertEquals("browse-source-list", TestTags.BrowseSourceList)
        assertEquals("browse-results", TestTags.BrowseResults)
        assertEquals("browse-filter", TestTags.BrowseFilter)
        assertEquals("reader-body", TestTags.ReaderBody)
        assertEquals("reader-play", TestTags.ReaderPlay)
        assertEquals("reader-back", TestTags.ReaderBack)
        assertEquals("voice-list", TestTags.VoiceList)
        assertEquals("settings-list", TestTags.SettingsList)
        assertEquals("dialog-confirm", TestTags.DialogConfirm)
        assertEquals("dialog-dismiss", TestTags.DialogDismiss)
    }

    @Test
    fun `highlight selector strings are stable and kebab-case`() {
        // #1079 phase 2 — the in-reader highlight flow (select → create
        // sheet → confirm; tap → edit sheet → save/delete). The Maestro
        // highlight flow drives these exact strings, so pin them here: a
        // rename breaks the flow far from the cause.
        assertEquals("highlight-sheet", TestTags.HighlightSheet)
        assertEquals("highlight-edit-sheet", TestTags.HighlightEditSheet)
        assertEquals("highlight-palette", TestTags.HighlightPalette)
        assertEquals("highlight-note-field", TestTags.HighlightNoteField)
        assertEquals("highlight-confirm", TestTags.HighlightConfirm)
        assertEquals("highlight-save", TestTags.HighlightSave)
        assertEquals("highlight-delete", TestTags.HighlightDelete)
    }
}
