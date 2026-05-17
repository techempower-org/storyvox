package `in`.jphe.storyvox.source.notion.config

/**
 * Issue #390 — baked-in defaults for the Notion fiction backend.
 *
 * [TECHEMPOWER_DATABASE_ID] points at TechEmpower's **Resources**
 * Notion database — the searchable database of free tech resources
 * for individuals with low income, their families, and nonprofit
 * organizations. The id is sourced from
 * [`techempower/site.config.ts`](https://github.com/techempower-org/techempower.org)
 * `pageUrlOverrides` line 47–48 (`'/resources': '2a3d7068…'`), which
 * is what the techempower.org website hits when a user navigates to
 * `/resources` — the same id is queryable as a Notion database via
 * the official REST API.
 *
 * Why a database (not a page):
 *  - Storyvox's [`:source-notion`] queries `databases/query/{id}`.
 *    Notion's data model distinguishes page-blocks from database-
 *    blocks; the website's `rootNotionPageId` (`0959e445…`) is the
 *    Guides *page* and would 404 against the database endpoint.
 *    `2a3d7068…` is a real database — the Resources collection
 *    underneath the root page — and that's what the API can read.
 *  - Existing users with a non-default databaseId persisted keep
 *    their value; this default only applies on fresh installs.
 *
 * Auth caveat: storyvox uses an integration-token PAT model. To
 * read TechEmpower's Resources database the user must have a Notion
 * integration shared with the database. The current `:source-notion`
 * UX assumes the user pastes their own integration token; for the
 * "techempower content out of the box" experience to fully work
 * without setup, TechEmpower would need to either share the
 * Resources DB publicly with a known token, or ship a bundled
 * read-only token (issue #393 tracks that decision).
 *
 * Format note: Notion accepts both the hyphenated UUID form
 * (`xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`) and the compact 32-hex
 * form in its REST API. We store + emit the compact form here.
 */
object NotionDefaults {
    /**
     * The TechEmpower Resources database id, from
     * `techempower/site.config.ts` line 48 (`pageUrlOverrides`,
     * `/resources` route). Searchable database of free tech resources
     * for low-income families, surfaced as the storyvox Notion
     * backend's default content target on fresh installs (#390).
     */
    const val TECHEMPOWER_DATABASE_ID: String =
        "2a3d706803c649409e74e9ce5ccd4c4b"

    /**
     * Issue #393 — TechEmpower's root **page** id (the parent of the
     * Resources database and all 8 Guide pages). Used in
     * `NotionMode.ANONYMOUS_PUBLIC` mode: storyvox loads this page's
     * child blocks via the unofficial `loadPageChunk` endpoint and
     * expands collection_view children + page children into individual
     * fictions. Sourced from `techempower/site.config.ts`
     * `rootNotionPageId` (line 5) — the same id `techempower.org` uses
     * as its top-level Notion page.
     */
    const val TECHEMPOWER_ROOT_PAGE_ID: String =
        "0959e44599984143acabc80187305001"

    /** Notion REST API host. */
    const val BASE_URL: String = "https://api.notion.com"

    /**
     * Issue #393 — base URL for the *unofficial* Notion API
     * (`www.notion.so/api/v3`). This is the same surface
     * `react-notion-x`'s `notion-client` package hits — it's how the
     * notion.site renderer works without a token. Notion serves
     * `loadPageChunk`, `queryCollection`, `syncRecordValuesMain`, and
     * `getPublicPageData` on this host without `Authorization`.
     *
     * Caveat: this endpoint is undocumented and Notion may change it.
     * We pin to the v3 path and add structured error decoding so we
     * surface useful messages if Notion shifts shape.
     */
    const val UNOFFICIAL_BASE_URL: String = "https://www.notion.so/api/v3"

    /**
     * Notion REST API version pin. Notion's API requires a
     * `Notion-Version` header on every request; we pin to the
     * 2022-06-28 stable contract because (a) every endpoint storyvox
     * needs (`databases/query`, `blocks/{id}/children`, `pages/{id}`)
     * is stable there, and (b) Notion adds breaking changes silently
     * on the latest version moniker. Pin + upgrade-on-purpose beats
     * follow-the-trunk.
     */
    const val API_VERSION: String = "2022-06-28"

    /**
     * User-Agent used by all storyvox→Notion HTTP traffic. Identifies
     * the project + gives a contact path so Notion's abuse / rate-limit
     * tooling can route concerns somewhere useful.
     */
    const val USER_AGENT: String =
        "storyvox-notion/1.0 (+https://github.com/techempower-org/storyvox)"

    /**
     * Issue #393 / v0.5.26 — four-fiction layout for TechEmpower in
     * anonymous mode. Browse → Notion surfaces **four tiles** (one per
     * top-level section of the techempower.org navigation):
     *
     *  1. **Guides** — has 7 chapters, one per individual guide page
     *     (How to use TechEmpower, Free internet, EV incentives, EBT
     *     balance, Findhelp, Password manager, Free cell service).
     *     Each chapter's body is the rendered Notion page of that
     *     guide. Chapter list is hand-curated from
     *     `techempower/site.config.ts` `pageUrlOverrides` so the order
     *     matches the website.
     *
     *     Issue #558 — "EBT spending" was dropped from the list in
     *     v0.5.65 because the hardcoded page id
     *     (`16f7018ad93542652b2b16c44464b1c3`) returns a Notion
     *     ValidationError 400 — the page was either deleted or
     *     re-numbered upstream, and there's no surviving body to
     *     fetch. Pre-fix this stranded auto-advance on chapter 4 →
     *     chapter 5 because the 30s body-wait blew past Notion's
     *     immediate 400 + WorkManager's retry backoff. The data
     *     drop is paired with [PrerenderTriggers.onChapterOpened]
     *     body-prefetching to keep the rest of the cascade fluid.
     *  2. **Resources** — has N chapters (~80), one per row in the
     *     TechEmpower Resources database. Each chapter's body is the
     *     resource page's content (resolved by `loadPageChunk` on the
     *     row's block id).
     *  3. **About** — single-chapter fiction with the About page content.
     *  4. **Donate** — single-chapter fiction with the Donate page content.
     *
     * Each fiction's stable id ("guides", "resources", "about", "donate")
     * is encoded into the FictionSummary id and parsed back at
     * fictionDetail/chapter call time. Adding a new fiction is a one-line
     * append to [techempowerFictions]; the delegate has no hardcoded
     * count.
     *
     * Why hand-curated guide list (instead of walking the root page's
     * children at runtime): the root page contains *both* navigation
     * scaffolding (the guide entries) and bridge text. We want the
     * chapter list to be exactly the user-facing guides, in the
     * website's order — not "whatever child blocks show up." A static
     * list also
     * keeps a cold Browse load fast (no second loadPageChunk to
     * discover children).
     */
    internal val techempowerFictions: List<`in`.jphe.storyvox.source.notion.TechEmpowerFiction> = listOf(
        `in`.jphe.storyvox.source.notion.TechEmpowerFiction.PageList(
            id = "guides",
            title = "Guides",
            description = "Step-by-step guides for free tech, EBT support, and digital safety.",
            chapters = listOf(
                "How to use TechEmpower.org" to "6c979ba4e43f48d7a4836e0027ea4178",
                "Free internet" to "bb5e537b083a417eb90ed9e984128c71",
                "EV incentives" to "758054e1a2ec4c1aa077202ffedec710",
                "EBT balance" to "272a4ee69520804fa68ad8c110af49f6",
                // Issue #558 — "EBT spending" removed in v0.5.65 because
                // the Notion page id 16f7018a-d935-4265-2b2b-16c44464b1c3
                // returns a Notion ValidationError 400. The page was
                // either deleted or re-numbered upstream; there's no
                // surviving body to fetch. Leaving the row in the
                // chapter list stranded auto-advance on the ch4 → ch5
                // boundary because every body fetch returned a fast
                // failure → WorkManager retry storm → 30s/60s timeout.
                // If/when the upstream page is restored, re-add it
                // here with the correct compact 32-hex page id.
                "Findhelp" to "992742a61e2e472b9b4a149f7aa74539",
                "Password manager" to "99b0ab9c7cce428e8c86e3143752aa1c",
                "Free cell service" to "7519ef16d7b74519acd9b8262a7beb84",
            ),
        ),
        `in`.jphe.storyvox.source.notion.TechEmpowerFiction.CollectionRows(
            id = "resources",
            title = "Resources",
            description = "Searchable database of free tech resources for individuals with low income, their families, and nonprofit organizations.",
            collectionBlockId = TECHEMPOWER_DATABASE_ID,
        ),
        `in`.jphe.storyvox.source.notion.TechEmpowerFiction.SinglePage(
            id = "about",
            title = "About",
            description = "About TechEmpower.org.",
            pageId = "dbf0ddece2ce468fb2bf9049e6322e8a",
        ),
        `in`.jphe.storyvox.source.notion.TechEmpowerFiction.SinglePage(
            id = "donate",
            title = "Donate",
            description = "Support TechEmpower's mission.",
            pageId = "59d8a4dab0cc484f8b044d33f240ce1d",
        ),
    )
}
