package `in`.jphe.storyvox.feature.browse

import `in`.jphe.storyvox.data.source.SourceIds

/**
 * Plugin-seam Phase 3 (#384) — side-table of per-source UI hints
 * keyed by sourceId. Lives in `:feature/browse` because the data here
 * is purely UI-shaped: chip strip labels, supported-tabs lists, filter
 * sheet variants, search-hint copy.
 *
 * The Phase 1/2 `BrowseSourceKey` enum carried this data inline on
 * each enum entry. Phase 3 deletes the enum in favour of iterating
 * `SourcePluginRegistry.descriptors`, but the per-source UI bits don't
 * belong on the `SourcePluginDescriptor` (which lives in `:core-data`
 * and shouldn't know about Compose tabs or filter shapes).
 *
 * The table is keyed by the `SourceIds` string constants. A future
 * out-of-tree backend that adds a new `SourceIds.X` constant must also
 * add its row here (or rely on the defaults — short label = registry
 * display name, supported tabs = Popular + Search).
 *
 * Adding a row is a single-file diff next to the source's
 * `@SourcePlugin` annotation backfill — far less invasive than the
 * Phase 1/2 17-touchpoint shape.
 */
internal object BrowseSourceUi {

    /**
     * Short label for the browse chip strip. The registry's
     * `displayName` is the formal "Archive of Our Own" / "Local EPUB
     * files" form, which doesn't fit on a chip on a narrow phone.
     * Falls through to the descriptor's `displayName` when no override
     * is present.
     */
    fun chipLabel(id: String, displayName: String): String = when (id) {
        SourceIds.ROYAL_ROAD -> "Royal Road"
        SourceIds.GITHUB -> "GitHub"
        // "Palace" instead of "Memory Palace" so the segmented source
        // picker doesn't break the chip label across two lines on
        // narrow phones (#148).
        SourceIds.MEMPALACE -> "Palace"
        SourceIds.RSS -> "RSS"
        SourceIds.EPUB -> "Local"
        SourceIds.OUTLINE -> "Wiki"
        SourceIds.GUTENBERG -> "Gutenberg"
        SourceIds.AO3 -> "AO3"
        SourceIds.STANDARD_EBOOKS -> "Standard Ebooks"
        SourceIds.WIKIPEDIA -> "Wikipedia"
        SourceIds.WIKISOURCE -> "Wikisource"
        // Issue #417 — :source-kvmr → :source-radio. The new canonical
        // chip says "Radio"; the legacy KVMR id is kept as an alias so
        // any code path still resolving through it stays consistent.
        SourceIds.RADIO -> "Radio"
        SourceIds.KVMR -> "Radio"
        SourceIds.NOTION_TECHEMPOWER -> "TechEmpower"
        SourceIds.NOTION_PAT -> "Notion"
        SourceIds.HACKERNEWS -> "Hacker News"
        SourceIds.ARXIV -> "arXiv"
        SourceIds.PLOS -> "PLOS"
        SourceIds.DISCORD -> "Discord"
        else -> displayName
    }

    /**
     * Tabs meaningful for [id]. [githubSignedIn] gates the auth-only
     * GitHub tabs (`MyRepos` #200, `Starred` #201, `Gists` #202).
     * [ao3SignedIn] gates the auth-only AO3 tabs
     * (`Ao3MySubscriptions` / `Ao3MarkedForLater`, #426 PR2).
     * Unknown ids fall through to a reasonable default of
     * Popular + Search.
     *
     * Issue #695 — when [supportsSearch] is `false`, the
     * [BrowseTab.Search] tab is stripped out of whichever branch
     * matched. Sources that declare `supportsSearch = false` on their
     * `@SourcePlugin` annotation (Slack, Telegram) used to hit the
     * `else` fallthrough below and surface a Search tab that did
     * nothing — typing a query returned `empty ListPage` and the user
     * concluded the source was broken. Filtering here is the
     * single-source-of-truth fix; the per-source branches stay as
     * "ideal default tab set" and the descriptor's annotation wins
     * when it disagrees. Defaulting to `true` keeps every existing
     * caller and test path unchanged.
     */
    fun supportedTabs(
        id: String,
        githubSignedIn: Boolean = false,
        ao3SignedIn: Boolean = false,
        supportsSearch: Boolean = true,
    ): List<BrowseTab> {
        val tabs = supportedTabsRaw(id, githubSignedIn, ao3SignedIn)
        return if (supportsSearch) tabs else tabs.filterNot { it == BrowseTab.Search }
    }

    private fun supportedTabsRaw(
        id: String,
        githubSignedIn: Boolean,
        ao3SignedIn: Boolean,
    ): List<BrowseTab> = when (id) {
        SourceIds.ROYAL_ROAD -> listOf(
            BrowseTab.Popular,
            BrowseTab.NewReleases,
            BrowseTab.BestRated,
            BrowseTab.Search,
        )
        SourceIds.GITHUB -> buildList {
            add(BrowseTab.Popular)
            add(BrowseTab.NewReleases)
            if (githubSignedIn) {
                add(BrowseTab.MyRepos)
                add(BrowseTab.Starred)
                add(BrowseTab.Gists)
            }
            add(BrowseTab.Search)
        }
        SourceIds.MEMPALACE -> listOf(BrowseTab.Popular, BrowseTab.NewReleases)
        SourceIds.RSS -> listOf(BrowseTab.NewReleases, BrowseTab.Popular, BrowseTab.Search)
        SourceIds.EPUB -> listOf(BrowseTab.Popular, BrowseTab.Search)
        SourceIds.OUTLINE -> listOf(BrowseTab.Popular, BrowseTab.Search)
        SourceIds.GUTENBERG -> listOf(BrowseTab.Popular, BrowseTab.NewReleases, BrowseTab.Search)
        // Issue #445 — AO3 declared `supportsSearch = true` on its
        // plugin manifest but the tab list omitted BrowseTab.Search,
        // so the icon disappeared on the chip → users had no way to
        // search a 50-deep Popular feed and concluded the capability
        // was missing. The underlying Ao3Source.search() returns empty
        // for v0.5.40 (the feed-keyed catalog doesn't expose a search
        // endpoint AO3 will accept anonymously), but adding the tab
        // surface keeps the chip-row consistent across sources that
        // advertise search. The Search tab's empty results will be
        // replaced by a proper AO3 search implementation in a follow-up.
        //
        // #426 PR2 — when the user has signed in to AO3, the chip
        // strip gains "My Subscriptions" and "Marked for Later"
        // entries. Signed-out users see the same 3-tab strip we
        // shipped pre-#426.
        SourceIds.AO3 -> buildList {
            add(BrowseTab.Popular)
            add(BrowseTab.NewReleases)
            if (ao3SignedIn) {
                add(BrowseTab.Ao3MySubscriptions)
                add(BrowseTab.Ao3MarkedForLater)
            }
            add(BrowseTab.Search)
        }
        SourceIds.STANDARD_EBOOKS -> listOf(BrowseTab.Popular, BrowseTab.NewReleases, BrowseTab.Search)
        SourceIds.WIKIPEDIA -> listOf(BrowseTab.Popular, BrowseTab.Search)
        SourceIds.WIKISOURCE -> listOf(BrowseTab.Popular, BrowseTab.Search)
        // Issue #417 — Radio gains a Search tab (Radio Browser API).
        // The legacy KVMR alias keeps Popular-only because v0.5.20+
        // persisted resolutions through the alias never expected a
        // Search tab anyway.
        SourceIds.RADIO -> listOf(BrowseTab.Popular, BrowseTab.Search)
        SourceIds.KVMR -> listOf(BrowseTab.Popular)
        SourceIds.NOTION_TECHEMPOWER -> listOf(BrowseTab.Popular, BrowseTab.Search)
        SourceIds.NOTION_PAT -> listOf(BrowseTab.Popular, BrowseTab.Search)
        SourceIds.HACKERNEWS -> listOf(BrowseTab.Popular, BrowseTab.Search)
        SourceIds.ARXIV -> listOf(BrowseTab.Popular, BrowseTab.Search)
        SourceIds.PLOS -> listOf(BrowseTab.Popular, BrowseTab.Search)
        SourceIds.DISCORD -> listOf(BrowseTab.Popular, BrowseTab.Search)
        else -> listOf(BrowseTab.Popular, BrowseTab.Search)
    }

    /** Issue #271 — per-source subtitle for the Search empty state. */
    fun searchHint(id: String, displayName: String): String = when (id) {
        SourceIds.ROYAL_ROAD -> "Find fictions across Royal Road"
        SourceIds.GITHUB -> "Search indexed GitHub repositories"
        SourceIds.MEMPALACE -> "Search your MemPalace knowledge base"
        SourceIds.RSS -> "Search your subscribed feeds"
        SourceIds.EPUB -> "Search your local EPUB library"
        SourceIds.OUTLINE -> "Search your Outline notes"
        SourceIds.GUTENBERG -> "Search Project Gutenberg's 70,000+ public-domain books"
        SourceIds.AO3 -> "Search AO3 by tag, fandom, or character"
        SourceIds.STANDARD_EBOOKS -> "Search Standard Ebooks' hand-curated public-domain classics"
        SourceIds.WIKIPEDIA -> "Search Wikipedia — narrate any article"
        SourceIds.WIKISOURCE -> "Search Wikisource — transcribed public-domain texts"
        // Issue #417 — Radio search drives the Radio Browser API.
        SourceIds.RADIO -> "Search Radio Browser — community, public, and college stations worldwide"
        SourceIds.KVMR -> "Tune in to KVMR — live community radio from Nevada City"
        SourceIds.NOTION_TECHEMPOWER -> "Search TechEmpower guides & resources"
        SourceIds.NOTION_PAT -> "Search your configured Notion database"
        SourceIds.HACKERNEWS -> "Search Hacker News stories (Algolia-backed full-text)"
        SourceIds.ARXIV -> "Search arXiv — open-access academic papers"
        SourceIds.PLOS -> "Search PLOS — open-access peer-reviewed science"
        SourceIds.DISCORD -> "Search messages in your selected Discord server"
        else -> "Search $displayName"
    }
}

