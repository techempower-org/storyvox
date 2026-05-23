package `in`.jphe.storyvox.source.ao3

import `in`.jphe.storyvox.data.db.dao.AuthDao
import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.filter.FilterDimension
import `in`.jphe.storyvox.data.source.filter.FilterState
import `in`.jphe.storyvox.data.source.plugin.SourceCategory
import `in`.jphe.storyvox.data.source.plugin.SourcePlugin
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.ChapterInfo
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionStatus
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.data.source.model.SearchOrder
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.source.ao3.di.Ao3Cache
import `in`.jphe.storyvox.source.ao3.net.Ao3Api
import `in`.jphe.storyvox.source.ao3.net.Ao3AtomFeed
import `in`.jphe.storyvox.source.ao3.net.Ao3FeedEntry
import `in`.jphe.storyvox.source.epub.parse.EpubBook
import `in`.jphe.storyvox.source.epub.parse.EpubParseException
import `in`.jphe.storyvox.source.epub.parse.EpubParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #381 — Archive of Our Own as a fiction backend.
 *
 * Two-phase model, mirroring [GutenbergSource][in.jphe.storyvox.source.gutenberg.GutenbergSource]:
 *
 *  - **Catalog** — per-tag Atom feeds at
 *    `https://archiveofourown.org/tags/<tag>/feed.atom`. Each entry
 *    carries title + author + summary + tags + work id, enough to
 *    populate the Browse grid without a detail fetch.
 *
 *  - **Content** — per-work EPUB downloads at
 *    `/downloads/<work_id>/<slug>.epub`. The bytes go straight to
 *    [EpubParser]; chapter rendering then walks the EPUB spine the
 *    same way Gutenberg / local EPUB do (#235 / #237).
 *
 * AO3 doesn't expose a unified catalog — discovery is fundamentally
 * per-tag. v1 ships a curated handful of fandoms in the Browse
 * picker (see [FANDOM_TAGS]); when no genre is selected the source
 * defaults to the Marvel Cinematic Universe feed (the curated list's
 * largest fandom) so the picker has something to render on first
 * open. Users wanting other fandoms use Search (deferred — AO3's
 * search is HTML-only and we don't want any scraping in v1) or a
 * future tag-picker UI.
 *
 * #408 — the Atom feed URL is built from each tag's numeric AO3 id
 * rather than its slug. AO3 dropped the slug→numeric redirect
 * sometime in early 2026; `GET /tags/<slug>/feed.atom` now returns
 * 404 outright. The numeric form (`/tags/<id>/feed.atom`) returns
 * the same Atom XML it always has. See [FANDOM_TAGS] for the
 * resolved (name, id) pairs.
 *
 * Legal posture: AO3 is run by the Organization for Transformative
 * Works, a 501(c)(3). Their ToS draws a hard line at commercial
 * scraping and paid-access apps. storyvox is free, open-source, and
 * uses only the two official surfaces above — no HTML scraping, no
 * paywall, no ads. The User-Agent identifies us with a contact URL so
 * OTW Ops can route any concerns to a real address.
 *
 * Caveat carried in the issue: some "Archive Warning: Choose Not to
 * Use Warnings" works require a logged-in session for the EPUB
 * download. We surface those as [FictionResult.AuthRequired] today
 * and let the UI render a friendly "sign-in required" empty state.
 * Sign-in is a deliberate follow-up (#381 mentions threading the
 * AUTH_WEBVIEW pattern from #211 through).
 */
@SourcePlugin(
    id = SourceIds.AO3,
    displayName = "Archive of Our Own",
    // #436 — fresh-install discoverability: chip on by default.
    defaultEnabled = true,
    category = SourceCategory.Text,
    supportsFollow = false,
    supportsSearch = true,
    description = "14M+ fanfiction works via per-tag Atom feeds + official EPUB downloads",
    sourceUrl = "https://archiveofourown.org",
)
@Singleton
internal class Ao3Source @Inject constructor(
    private val api: Ao3Api,
    @Ao3Cache private val cacheDir: File,
    // #426 PR2 — read the captured AO3 username off the `auth_cookie`
    // row. The WebView capture path (`AuthViewModel.captureCookies`)
    // stuffs the username into [AuthCookie.userId] for the AO3
    // source; subscriptions / Marked-for-Later endpoints are
    // username-keyed (`/users/<u>/subscriptions`), so this is how
    // they find the right URL. AuthDao injection avoids reaching
    // through AuthRepository (which doesn't expose a userId
    // accessor on its public interface), keeping the PR1 scaffold
    // untouched while letting PR2 read the data it persisted.
    private val authDao: AuthDao,
) : FictionSource, `in`.jphe.storyvox.data.source.UrlMatcher, Ao3AuthedSource {

    override val id: String = SourceIds.AO3
    override val displayName: String = "Archive of Our Own"

    /** Issue #472 — `archiveofourown.org/works/<id>` URL. */
    override fun matchUrl(url: String): `in`.jphe.storyvox.data.source.RouteMatch? {
        val m = AO3_URL_PATTERN.matchEntire(url.trim()) ?: return null
        return `in`.jphe.storyvox.data.source.RouteMatch(
            sourceId = SourceIds.AO3,
            fictionId = "ao3:${m.groupValues[1]}",
            confidence = 0.95f,
            label = "AO3 work",
        )
    }

    /**
     * In-memory cache of parsed EpubBook keyed by fictionId. AO3
     * works range from drabbles (10 KB) to multi-million-word novels
     * (50+ MB EPUBs); cache hits return the parsed spine without
     * re-running EpubParser, which can take a noticeable hit on the
     * larger end. Per-process; rebuilds from the on-disk `.epub`
     * after a process restart.
     */
    private val parsedCache = mutableMapOf<String, EpubBook>()

    override fun filterDimensions(): List<FilterDimension> = listOf(
        FilterDimension.Sort(
            options = listOf(
                FilterDimension.SortOption("relevance", "Default"),
                FilterDimension.SortOption("popularity", "Popular"),
                FilterDimension.SortOption("last_update", "Newest"),
            ),
        ),
        FilterDimension.Select(
            key = "category",
            label = "Category",
            options = listOf(
                "Harry Potter", "Marvel", "Star Wars", "Sherlock",
                "Doctor Who", "Supernatural", "Original Work", "Anime",
            ),
        ),
        FilterDimension.Select(
            key = "rating",
            label = "Rating",
            options = listOf("General", "Teen", "Mature", "Explicit"),
        ),
        FilterDimension.Select(
            key = "completion",
            label = "Completion",
            options = listOf("Complete", "In Progress"),
        ),
        // AO3's four canonical archive warnings. TagSet with
        // allowExclude=true so users can include OR exclude — matches
        // AO3's own search UI which has separate include/exclude pickers
        // for warnings. Until AO3 search() is wired (currently a no-op
        // per the comment on [search]), the chosen values flow into
        // SearchQuery.tags/excludeTags but have no functional effect;
        // the declaration is what surfaces the filter to the user.
        FilterDimension.TagSet(
            key = "warnings",
            label = "Archive warnings",
            options = listOf(
                "Major Character Death",
                "Graphic Depictions Of Violence",
                "Rape/Non-Con",
                "Underage",
            ),
            allowExclude = true,
        ),
    )

    override fun applyFilters(base: SearchQuery, state: FilterState): SearchQuery {
        var q = base
        state.stringVal("sort")?.let { sortId ->
            q = q.copy(
                orderBy = when (sortId) {
                    "popularity" -> SearchOrder.POPULARITY
                    "last_update" -> SearchOrder.LAST_UPDATE
                    else -> SearchOrder.RELEVANCE
                },
            )
        }
        state.stringVal("category")?.takeIf { it.isNotBlank() }?.let { cat ->
            q = q.copy(tags = q.tags + cat.lowercase())
        }
        state.stringSetVal("warnings")?.let { w ->
            q = q.copy(
                tags = q.tags + w.included.map { it.lowercase() },
                excludeTags = q.excludeTags + w.excluded.map { it.lowercase() },
            )
        }
        return q
    }

    // ─── browse ────────────────────────────────────────────────────────

    /**
     * No global "popular" surface on AO3 — works are popular *within*
     * tags. v1 maps Popular = the default-tag feed (MCU; see
     * [DEFAULT_TAG_ID]). The genre row picks specific fandoms; when
     * the user has selected a fandom via [byGenre], that takes over
     * and this never fires.
     */
    override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> =
        feedAsListPage(DEFAULT_TAG_ID, page)

    /**
     * Same surface as [popular] — the Atom feed is sorted by recency,
     * not popularity. AO3 doesn't expose a "popular this week"
     * endpoint without HTML scraping, which v1 explicitly opts out of.
     * Both tabs surface the same recent-first feed; this keeps the UI
     * coherent (Browse → AO3 always shows fresh content) without
     * pretending we have a popularity signal we don't.
     */
    override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> =
        feedAsListPage(DEFAULT_TAG_ID, page)

    override suspend fun byGenre(
        genre: String,
        page: Int,
    ): FictionResult<ListPage<FictionSummary>> {
        // #408 — the genre string is the display name from [genres()];
        // map it back to the canonical AO3 numeric tag id baked into
        // [FANDOM_TAGS]. Unknown names fall back to the default tag
        // rather than 404'ing, mirroring the same conservative
        // posture [popular] uses.
        val tagId = tagIdFor(genre) ?: DEFAULT_TAG_ID
        return feedAsListPage(tagId, page)
    }

    /**
     * Search is deferred per the issue spec — AO3's `/works/search`
     * returns an HTML listing only (no Atom or JSON equivalent), and
     * v1 commits to zero scraping. Return an empty page so the UI
     * renders the no-results empty state cleanly; a future PR
     * (tracked in the #381 follow-up list) can either revisit HTML
     * parsing or layer search over the per-tag feeds.
     */
    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> =
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    /**
     * Curated tag list for the Browse genre picker. Three fandoms
     * hand-picked for breadth — the v1 surface intentionally avoids
     * the full AO3 tag taxonomy (a million-plus tags don't fit in a
     * picker). A follow-up issue will let the user add their own
     * tags; until then this list is the surface. See [FANDOM_TAGS]
     * for the (name, numeric-tag-id) pairs.
     */
    override suspend fun genres(): FictionResult<List<String>> =
        FictionResult.Success(FANDOM_TAGS.map { it.first })

    // ─── detail ────────────────────────────────────────────────────────

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        val workId = parseAo3Id(fictionId)
            ?: return FictionResult.NotFound("Not an AO3 fictionId: $fictionId")

        // Ensure the EPUB is on disk + parsed so we can return a real
        // chapter list. detail()'s contract is "no chapter bodies"
        // but we need the spine for the ChapterInfo[] regardless —
        // same flow Gutenberg uses. Unlike Gutenberg we don't have a
        // separate catalog API to fall back on for metadata; the
        // EPUB itself carries the canonical title/author.
        val parsed = when (val r = ensureParsed(fictionId, workId)) {
            is FictionResult.Success -> r.value
            is FictionResult.Failure -> return r
        }

        val summary = FictionSummary(
            id = fictionId,
            sourceId = SourceIds.AO3,
            title = parsed.title.ifBlank { "AO3 work $workId" },
            author = parsed.author,
            // EpubBook doesn't expose a synopsis field — AO3 EPUBs
            // carry the work's summary in the first spine item
            // (the "Preface" page) which would require an extra
            // parse pass to extract. v1 leaves description null
            // and lets the user read the summary inline as the
            // first chapter, matching the AO3 web experience.
            description = null,
            coverUrl = null, // AO3 EPUBs ship a generic cover; skip.
            tags = emptyList(),
            // AO3 works can be ongoing or completed; the EPUB itself
            // doesn't reliably distinguish (the work-page HTML carries
            // the "Status: Completed" flag but we don't fetch that
            // in v1). Conservative default = Ongoing — the UI's
            // "may update" affordance is more truthful than a wrong
            // "Completed" badge.
            status = FictionStatus.ONGOING,
            chapterCount = parsed.chapters.size,
        )
        val chapters = parsed.chapters.map { ch ->
            ChapterInfo(
                id = chapterIdFor(fictionId, ch.index),
                sourceChapterId = ch.id,
                index = ch.index,
                title = ch.title,
            )
        }
        return FictionResult.Success(FictionDetail(summary = summary, chapters = chapters))
    }

    override suspend fun chapter(
        fictionId: String,
        chapterId: String,
    ): FictionResult<ChapterContent> {
        val parsed = parsedCache[fictionId]
            ?: return when (val r = reparseFromDisk(fictionId)) {
                is FictionResult.Success -> chapter(fictionId, chapterId)
                is FictionResult.Failure -> r
            }
        val idx = chapterIndexFrom(chapterId)
            ?: return FictionResult.NotFound("Malformed chapter id: $chapterId")
        val ch = parsed.chapters.getOrNull(idx)
            ?: return FictionResult.NotFound("Chapter $idx out of range (have ${parsed.chapters.size})")
        val info = ChapterInfo(
            id = chapterId,
            sourceChapterId = ch.id,
            index = ch.index,
            title = ch.title,
        )
        return FictionResult.Success(
            ChapterContent(
                info = info,
                htmlBody = ch.htmlBody,
                plainBody = ch.htmlBody.stripTags(),
            ),
        )
    }

    // ─── auth-gated ────────────────────────────────────────────────────

    /**
     * AO3's per-user "Subscriptions" surface — works the signed-in
     * user has subscribed to for update notifications (#426 PR2).
     *
     * Pre-PR2 this returned an empty page on purpose (the WebView
     * sign-in scaffold wasn't built yet). PR2 wires the real
     * endpoint: `/users/<username>/subscriptions`, paginated, HTML
     * parsed via [Ao3WorksIndexParser]. The username is captured
     * by the WebView during sign-in and persisted into the
     * `auth_cookie.userId` column for the AO3 row; we read it
     * back here.
     *
     * Returns [FictionResult.AuthRequired] when no AO3 session is
     * captured (no row in `auth_cookie` for `sourceId="ao3"`, or
     * the row's `userId` is null — defensive against a row that
     * was written before PR2's userId-capture landed). The UI
     * renders the brass "Sign in to AO3" CTA on that branch.
     */
    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        // The "Follows" surface on the FictionSource contract maps to
        // AO3's subscriptions list (closest analogue). Delegating
        // through [subscriptions] keeps the implementation in one
        // place — the Browse → My Subscriptions chip routes here
        // explicitly via [Ao3AuthedSource.subscriptions], the existing
        // Follows tab routes through this entry point unchanged.
        subscriptions(page = page)

    override suspend fun setFollowed(fictionId: String, followed: Boolean): FictionResult<Unit> =
        // AO3 subscriptions are managed inline on the work page (the
        // "Subscribe" link does a POST under the user's session). v1
        // keeps Subscribe a no-op so the rest of the AO3 surface
        // composes cleanly; a follow-up will wire the real toggle
        // through the same authed client used by
        // [followsList] / [markedForLater].
        FictionResult.Success(Unit)

    // ─── Ao3AuthedSource ───────────────────────────────────────────────

    override suspend fun subscriptions(page: Int): FictionResult<ListPage<FictionSummary>> {
        val username = readUsername()
            ?: return FictionResult.AuthRequired(
                "Sign in to AO3 to see your subscribed works",
            )
        return api.subscriptions(username = username, page = page)
    }

    /**
     * AO3's per-user "Marked for Later" surface (#426 PR2) — works
     * the user has tagged with the eye-icon affordance on a work's
     * Reading History page.
     *
     * Same shape as [subscriptions] — fetches `/users/<u>/readings?show=marked`,
     * routes the body through [Ao3WorksIndexParser]. Surfaced via
     * [BrowseTab.Ao3MarkedForLater] in the AO3 chip strip when the
     * user is signed in.
     *
     * AO3 only populates the Marked-for-Later list when the user
     * has "History" enabled in their preferences. When history is
     * off, the page renders an empty notice with no `<li class="work
     * blurb">` cards; the parser returns an empty [ListPage] and
     * the UI's empty-state copy explains the dependency.
     */
    override suspend fun markedForLater(page: Int): FictionResult<ListPage<FictionSummary>> {
        val username = readUsername()
            ?: return FictionResult.AuthRequired(
                "Sign in to AO3 to see your Marked for Later",
            )
        return api.markedForLater(username = username, page = page)
    }

    /**
     * Read the AO3 username captured during WebView sign-in. Lives
     * in the `auth_cookie.userId` column for the AO3 row; written
     * there by [`AuthViewModel.captureCookies`][in.jphe.storyvox.feature.auth.AuthViewModel]
     * when it splits the captured cookie map's `__storyvox_user`
     * pseudo-cookie out before hydrating the OkHttp jar.
     *
     * Returns null when either:
     *  - no AO3 session has been captured (anonymous mode), or
     *  - a pre-PR2 row exists with `userId = null` (the WebView
     *    capture path didn't persist a username before PR2 landed).
     *    The UI surfaces both branches as `AuthRequired`; the
     *    user signs in (again) to populate the column.
     */
    private suspend fun readUsername(): String? =
        authDao.get(SourceIds.AO3)?.userId?.takeIf { it.isNotBlank() }

    // ─── helpers ───────────────────────────────────────────────────────

    private suspend fun feedAsListPage(
        tagId: Long,
        page: Int,
    ): FictionResult<ListPage<FictionSummary>> {
        return when (val r = api.tagFeed(tagId, page)) {
            is FictionResult.Success -> FictionResult.Success(r.value.toListPage(page))
            is FictionResult.Failure -> r
        }
    }

    /**
     * Idempotent EPUB acquire-and-parse. First call for a work
     * downloads + parses + caches; subsequent calls return the cached
     * EpubBook. Surfaces network and parse failures separately so the
     * caller can show the right error copy.
     */
    private suspend fun ensureParsed(
        fictionId: String,
        workId: Long,
    ): FictionResult<EpubBook> {
        parsedCache[fictionId]?.let { return FictionResult.Success(it) }
        val onDisk = epubFileFor(fictionId)
        if (onDisk.exists() && onDisk.length() > 0L) {
            return reparseFromDisk(fictionId)
        }
        val bytes = when (val r = api.downloadEpub(workId)) {
            is FictionResult.Success -> r.value
            is FictionResult.Failure -> return r
        }
        withContext(Dispatchers.IO) {
            onDisk.parentFile?.mkdirs()
            onDisk.writeBytes(bytes)
        }
        val parsed = try {
            withContext(Dispatchers.IO) { EpubParser.parseFromBytes(bytes) }
        } catch (e: EpubParseException) {
            return FictionResult.NetworkError("Could not parse AO3 EPUB: ${e.message}", e)
        }
        parsedCache[fictionId] = parsed
        return FictionResult.Success(parsed)
    }

    private suspend fun reparseFromDisk(fictionId: String): FictionResult<EpubBook> {
        val onDisk = epubFileFor(fictionId)
        if (!onDisk.exists()) {
            return FictionResult.NotFound("No cached EPUB for $fictionId")
        }
        val bytes = withContext(Dispatchers.IO) { onDisk.readBytes() }
        val parsed = try {
            withContext(Dispatchers.IO) { EpubParser.parseFromBytes(bytes) }
        } catch (e: EpubParseException) {
            return FictionResult.NetworkError("Cached AO3 EPUB unparseable: ${e.message}", e)
        }
        parsedCache[fictionId] = parsed
        return FictionResult.Success(parsed)
    }

    private fun epubFileFor(fictionId: String): File {
        val id = parseAo3Id(fictionId) ?: 0L
        return File(cacheDir, "$id.epub")
    }

    /** Map a user-visible fandom name back to its AO3 numeric tag
     *  id. Lookup is case-sensitive against the canonical names in
     *  [FANDOM_TAGS]. Returns null when the user picked something
     *  outside the curated list — callers (just [byGenre] today)
     *  fall back to the default tag rather than 404'ing. */
    private fun tagIdFor(name: String): Long? =
        FANDOM_TAGS.firstOrNull { it.first == name }?.second

    companion object {
        /**
         * Curated fandom tags for the v1 Browse picker. Chosen for
         * breadth — heavyweight commercial fandoms (Marvel, Star
         * Wars) plus a long-form genre canon (Good Omens). Keep the
         * list short on purpose: AO3 has a million-plus tags and a
         * picker has to make a choice. Users wanting more
         * specificity get the tag-picker follow-up.
         *
         * #408 — these are now `(displayName, numericTagId)` pairs.
         * AO3 dropped the slug→tag-id redirect that used to back
         * `/tags/<slug>/feed.atom` (now returns 404), so the Atom
         * feed URL has to be built from the numeric tag id
         * directly. The numeric ids below were resolved by parsing
         * the `<link rel="alternate" type="application/atom+xml">`
         * out of each tag's `/tags/<slug>/works` HTML page; that
         * page still serves under the slug form and exposes the
         * canonical feed URL, e.g. `/tags/414093/feed.atom` for
         * Marvel Cinematic Universe.
         *
         * IDs are baked at build time — there's no per-app
         * autocomplete resolver in v1 (a future tag-picker UI is
         * tracked in the #408 follow-up). If AO3 ever renumbers a
         * canonical tag (extremely rare — the id is a primary key)
         * the corresponding genre tab will start returning empty
         * Atom feeds and the line below needs an update.
         */
        val FANDOM_TAGS: List<Pair<String, Long>> = listOf(
            "Marvel Cinematic Universe" to 414093L,
            "Star Wars - All Media Types" to 101375L,
            "Good Omens (TV)" to 27251507L,
        )

        /**
         * Default tag id for the Popular / NewReleases tabs when the
         * user hasn't picked a fandom from the genre row. Points at
         * "Marvel Cinematic Universe" (id 414093) — the broadest of
         * the curated fandoms, with the highest steady-state work
         * count, so the Popular tab always has something to render
         * on first open. Previously this was "Original Work" by
         * slug, but resolving its numeric id requires a Cloudflare-
         * fronted HTML fetch that's been flaky at build time; the
         * follow-up tag-picker (#408 referenced issue) will let the
         * user pick their own default.
         */
        const val DEFAULT_TAG_ID: Long = 414093L
    }
}

/** Issue #472 — AO3 work URL pattern. Captures the numeric work id. */
internal val AO3_URL_PATTERN: Regex = Regex(
    """^https?://(?:www\.)?archiveofourown\.org/works/(\d+)(?:/.*)?(?:\?.*)?$""",
    RegexOption.IGNORE_CASE,
)

/** `ao3:12345` → `12345`; returns null on malformed input. */
private fun parseAo3Id(fictionId: String): Long? =
    fictionId.substringAfter("ao3:", missingDelimiterValue = "")
        .takeIf { it.isNotEmpty() }
        ?.toLongOrNull()

/** Compose chapter id = `${fictionId}::${spineIdx}` so chapter
 *  lookups can recover the spine index without a separate map.
 *  Mirrors the OutlineSource / GutenbergSource pattern. */
private fun chapterIdFor(fictionId: String, spineIndex: Int): String =
    "${fictionId}::${spineIndex}"

private fun chapterIndexFrom(chapterId: String): Int? =
    chapterId.substringAfterLast("::", missingDelimiterValue = "")
        .takeIf { it.isNotEmpty() }
        ?.toIntOrNull()

/**
 * Map one Atom feed to a storyvox ListPage. The Atom feed itself
 * doesn't advertise a `hasNext` — AO3 silently truncates at the
 * configured page count and returns an empty entries list past
 * the end. We treat any full-looking page (20 entries, AO3's
 * fixed page size) as having a next; a short page terminates.
 */
private fun Ao3AtomFeed.toListPage(page: Int): ListPage<FictionSummary> =
    ListPage(
        items = entries.map { it.toSummary() },
        page = page,
        hasNext = entries.size >= AO3_FEED_PAGE_SIZE,
    )

/** AO3's per-tag Atom feed is hardcoded to 20 entries per page. */
private const val AO3_FEED_PAGE_SIZE = 20

private fun Ao3FeedEntry.toSummary(): FictionSummary =
    FictionSummary(
        id = "ao3:$workId",
        sourceId = SourceIds.AO3,
        title = title.ifBlank { "AO3 work $workId" },
        author = authorDisplay,
        coverUrl = null, // AO3 has no per-work cover image API.
        description = summary?.stripTags(),
        tags = tags,
        // AO3 feeds don't carry a completion flag — works are
        // assumed ongoing until the user opens them (the EPUB's
        // metadata may carry "Status: Completed"). Conservative
        // default Ongoing — see [Ao3Source.fictionDetail] for the
        // same rationale.
        status = FictionStatus.ONGOING,
    )

/** Cheap HTML→plaintext for the AO3 summary blocks. The feed
 *  wraps summaries in `<p>` and sometimes inline `<a>`/`<em>`;
 *  the engine receives the visible text without the tag noise.
 *
 *  Issue #444 — the XmlPullParser decodes XML entities once (so the
 *  feed's `&amp;` lands as `&`), but AO3's summary *content* itself
 *  contains HTML entities (`&amp;` for `&`, `&lt;` for `<`, etc.)
 *  because the summary body is HTML stored inside the Atom payload.
 *  After XML decoding we still have a layer of HTML entities to
 *  unescape, or the user sees `&amp;` literal in the description (and
 *  TTS narrates "amp"). The `decodeHtmlEntities()` pass below handles
 *  the common named entities plus numeric `&#NN;` / `&#xNN;` forms.
 *  Same shape the AO3 Atom feed actually emits — verified against the
 *  "The Snap Decides Differently" summary in the issue repro
 *  ("Clint Barton &amp;amp; Kate Bishop" → "Clint Barton & Kate Bishop"
 *  end-to-end). */
private fun String.stripTags(): String =
    Regex("<[^>]+>").replace(this, " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .decodeHtmlEntities()

/** Decode the common HTML named entities + numeric character references.
 *  Conservative — only handles the entities AO3 actually emits in
 *  summaries: `&amp;`, `&lt;`, `&gt;`, `&quot;`, `&apos;`, `&nbsp;`,
 *  plus numeric `&#NN;` (decimal) and `&#xNN;` (hex). Anything else
 *  (rare named entities like `&hellip;`) passes through unchanged
 *  rather than crashing — Android's android.text.Html.fromHtml would
 *  decode them all but pulls in the platform's full HTML parser.
 *
 *  Iterates until the output stops shrinking so double-encoded inputs
 *  like `&amp;amp;` (the literal sequence AO3's summary HTML contains
 *  when the source text has a `&` character) round-trip cleanly to a
 *  single `&`. The fixed-point loop is bounded by string length and
 *  terminates within 2-3 iterations on every payload we've seen.
 *  Caveat: pathological inputs like `&amp;` → `&` → `&` (no further
 *  decode possible) terminate on the second pass; arbitrarily-deep
 *  nesting would also terminate because each iteration must consume
 *  at least one `;`. */
internal fun String.decodeHtmlEntities(): String {
    if (indexOf('&') < 0) return this
    val entityPattern = Regex("""&(#x[0-9a-fA-F]+|#\d+|[a-zA-Z][a-zA-Z0-9]+);""")
    var current = this
    while (true) {
        val next = entityPattern.replace(current) { m ->
            val body = m.groupValues[1]
            when {
                body.startsWith("#x") || body.startsWith("#X") ->
                    body.drop(2).toIntOrNull(16)?.toChar()?.toString() ?: m.value
                body.startsWith("#") ->
                    body.drop(1).toIntOrNull()?.toChar()?.toString() ?: m.value
                else -> when (body.lowercase()) {
                    "amp" -> "&"
                    "lt" -> "<"
                    "gt" -> ">"
                    "quot" -> "\""
                    "apos" -> "'"
                    "nbsp" -> " "
                    "hellip" -> "…"
                    "mdash" -> "—"
                    "ndash" -> "–"
                    "rsquo", "lsquo" -> "'"
                    "rdquo", "ldquo" -> "\""
                    else -> m.value
                }
            }
        }
        if (next == current) return current
        current = next
    }
}
