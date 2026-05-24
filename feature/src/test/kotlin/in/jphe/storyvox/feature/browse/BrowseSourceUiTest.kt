package `in`.jphe.storyvox.feature.browse

import `in`.jphe.storyvox.data.source.SourceIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plugin-seam Phase 3 (#384) — unit tests for the [BrowseSourceUi]
 * side-table. Verifies the lookup tables return the expected per-
 * source UI hints for all 17 in-tree backends, with sensible defaults
 * for ids the table doesn't know about (out-of-tree plugin posture).
 */
class BrowseSourceUiTest {

    @Test fun `chipLabel returns concise label for every in-tree source`() {
        val expected = mapOf(
            SourceIds.ROYAL_ROAD to "Royal Road",
            SourceIds.GITHUB to "GitHub",
            SourceIds.MEMPALACE to "Palace",
            SourceIds.RSS to "RSS",
            SourceIds.EPUB to "Local",
            SourceIds.OUTLINE to "Wiki",
            SourceIds.GUTENBERG to "Gutenberg",
            SourceIds.AO3 to "AO3",
            SourceIds.STANDARD_EBOOKS to "Standard Ebooks",
            SourceIds.WIKIPEDIA to "Wikipedia",
            SourceIds.WIKISOURCE to "Wikisource",
            // Issue #417 — :source-kvmr → :source-radio. Both ids
            // resolve to the "Radio" chip during the one-cycle
            // migration overlap.
            SourceIds.RADIO to "Radio",
            SourceIds.KVMR to "Radio",
            // Issue #770 — split NOTION into TECHEMPOWER + PAT.
            SourceIds.NOTION_TECHEMPOWER to "TechEmpower",
            SourceIds.NOTION_PAT to "Notion",
            SourceIds.HACKERNEWS to "Hacker News",
            SourceIds.ARXIV to "arXiv",
            SourceIds.PLOS to "PLOS",
            SourceIds.DISCORD to "Discord",
        )

        // 19 = 11 catalog backends + Radio/Kvmr alias +
        // Notion TechEmpower + Notion PAT + HN + arXiv + PLOS + Discord.
        // Slack and Telegram intentionally fall through to the registry's
        // displayName (chip strip uses the same string as the Settings →
        // Plugins label).
        assertEquals(19, expected.size)
        for ((id, label) in expected) {
            assertEquals(
                "Unexpected chip label for $id",
                label,
                BrowseSourceUi.chipLabel(id, "fallback"),
            )
        }
    }

    @Test fun `chipLabel falls through to displayName for unknown ids`() {
        assertEquals("Custom Backend", BrowseSourceUi.chipLabel("custom-id", "Custom Backend"))
    }

    @Test fun `supportedTabs gives a non-empty list for every in-tree source`() {
        for (id in IN_TREE_IDS) {
            val tabs = BrowseSourceUi.supportedTabs(id)
            assertTrue("Expected non-empty tabs for $id", tabs.isNotEmpty())
            assertTrue("Expected Popular tab for $id", BrowseTab.Popular in tabs)
        }
    }

    @Test fun `supportedTabs adds auth-only github tabs when signed in`() {
        val anon = BrowseSourceUi.supportedTabs(SourceIds.GITHUB, githubSignedIn = false)
        val authed = BrowseSourceUi.supportedTabs(SourceIds.GITHUB, githubSignedIn = true)

        assertFalse(BrowseTab.MyRepos in anon)
        assertFalse(BrowseTab.Starred in anon)
        assertFalse(BrowseTab.Gists in anon)
        assertTrue(BrowseTab.MyRepos in authed)
        assertTrue(BrowseTab.Starred in authed)
        assertTrue(BrowseTab.Gists in authed)
    }

    /**
     * Issue #695 — sources that declare `supportsSearch = false` on
     * their `@SourcePlugin` annotation (Slack, Telegram) used to fall
     * through to the default `Popular + Search` branch and surface a
     * Search tab that did nothing. The new `supportsSearch` parameter
     * strips Search from whatever branch matched.
     */
    @Test fun `supportedTabs strips Search when descriptor declares supportsSearch false`() {
        val withSearch = BrowseSourceUi.supportedTabs(SourceIds.SLACK, supportsSearch = true)
        val withoutSearch = BrowseSourceUi.supportedTabs(SourceIds.SLACK, supportsSearch = false)
        assertTrue(
            "Slack with supportsSearch=true should keep Search (legacy default)",
            BrowseTab.Search in withSearch,
        )
        assertFalse(
            "Slack with supportsSearch=false must not surface a Search tab",
            BrowseTab.Search in withoutSearch,
        )
        // Popular still present — only the Search tab is filtered.
        assertTrue(BrowseTab.Popular in withoutSearch)
    }

    /**
     * Issue #695 — defense-in-depth: even sources whose explicit branch
     * lists `Search` should honor the descriptor flag. Verifies the
     * filter runs after the per-source branch, not only on the `else`
     * fallthrough.
     */
    @Test fun `supportedTabs strips Search even from sources with an explicit branch`() {
        // Wikipedia has an explicit `Popular + Search` branch.
        val filtered = BrowseSourceUi.supportedTabs(SourceIds.WIKIPEDIA, supportsSearch = false)
        assertFalse(
            "Wikipedia branch lists Search explicitly; descriptor flag must still win",
            BrowseTab.Search in filtered,
        )
        assertTrue(BrowseTab.Popular in filtered)
    }

    /**
     * Issue #695 — the `supportsSearch` parameter defaults to `true`,
     * matching the pre-fix behavior for every caller that doesn't yet
     * thread the descriptor flag through.
     */
    @Test fun `supportedTabs default supportsSearch preserves legacy behavior`() {
        val withDefault = BrowseSourceUi.supportedTabs(SourceIds.WIKIPEDIA)
        val withExplicitTrue = BrowseSourceUi.supportedTabs(SourceIds.WIKIPEDIA, supportsSearch = true)
        assertEquals(withDefault, withExplicitTrue)
    }

    @Test fun `searchHint returns sensible copy for every in-tree source`() {
        // Issue #695 — sources that declare `supportsSearch = false`
        // (Slack, Telegram) never reach the Search tab's empty state,
        // so a dedicated `searchHint` entry is unreachable copy. The
        // generic "Search <displayName>" fallback is fine for them.
        // Every other in-tree source must override.
        val noSearchSources = setOf(SourceIds.SLACK, SourceIds.TELEGRAM)
        for (id in IN_TREE_IDS - noSearchSources) {
            val hint = BrowseSourceUi.searchHint(id, displayName = "FallbackName")
            assertTrue(
                "Expected non-empty searchHint for $id (got: '$hint')",
                hint.isNotBlank(),
            )
            // Default `else` branch uses the displayName; in-tree
            // sources should NOT fall through to that branch.
            assertFalse(
                "$id fell through to the default searchHint",
                hint == "Search FallbackName",
            )
        }
    }

    @Test fun `searchHint falls through to displayName for unknown ids`() {
        assertEquals("Search My Backend", BrowseSourceUi.searchHint("custom-id", "My Backend"))
    }

    private companion object {
        /** All in-tree backends. Adding a row here when a new backend
         *  lands is the explicit "did we add the UI hint?" audit step.
         *  Slack and Telegram were missing pre-#695 — adding them here
         *  surfaces any future drop-through-to-default bug for
         *  supportsSearch=false sources via the existing smoke loops. */
        val IN_TREE_IDS = setOf(
            SourceIds.ROYAL_ROAD, SourceIds.GITHUB, SourceIds.MEMPALACE,
            SourceIds.RSS, SourceIds.EPUB, SourceIds.OUTLINE,
            SourceIds.GUTENBERG, SourceIds.AO3, SourceIds.STANDARD_EBOOKS,
            SourceIds.WIKIPEDIA, SourceIds.WIKISOURCE,
            // Issue #417 — RADIO is canonical; KVMR alias kept for
            // one cycle to cover persisted-id resolution.
            SourceIds.RADIO, SourceIds.KVMR,
            // Issue #770 — split NOTION into TECHEMPOWER + PAT.
            SourceIds.NOTION_TECHEMPOWER, SourceIds.NOTION_PAT,
            SourceIds.HACKERNEWS, SourceIds.ARXIV,
            SourceIds.PLOS, SourceIds.DISCORD,
            // Issue #695 — Slack/Telegram declare supportsSearch=false;
            // the smoke loops verify their tab list still includes
            // Popular and isn't accidentally empty.
            SourceIds.SLACK, SourceIds.TELEGRAM,
        )
    }
}
