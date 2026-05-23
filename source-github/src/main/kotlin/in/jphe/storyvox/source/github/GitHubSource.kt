package `in`.jphe.storyvox.source.github

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
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.source.github.manifest.BookManifest
import `in`.jphe.storyvox.source.github.manifest.ManifestChapter
import `in`.jphe.storyvox.source.github.manifest.ManifestParser
import `in`.jphe.storyvox.source.github.model.GhGist
import `in`.jphe.storyvox.source.github.model.GhRepo
import `in`.jphe.storyvox.source.github.model.decodedText
import `in`.jphe.storyvox.source.github.net.GitHubApi
import `in`.jphe.storyvox.source.github.net.GitHubApiResult
import `in`.jphe.storyvox.source.github.registry.Registry
import `in`.jphe.storyvox.source.github.registry.RegistryEntry
import `in`.jphe.storyvox.source.github.registry.toSummary
import `in`.jphe.storyvox.source.github.render.MarkdownChapterRenderer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * GitHub [FictionSource]. Fully wired in step 3d-detail-and-chapter:
 *
 *  - **Browse** (popular/latestUpdates/byGenre/genres): backed by the
 *    curated [Registry] (step 3c).
 *  - **Detail** (`fictionDetail`): fetches `book.toml`, `storyvox.json`,
 *    `SUMMARY.md` from the repo + repo metadata, runs them through
 *    [ManifestParser] (step 3d-manifest), and maps to [FictionDetail].
 *    Falls back to repo `chapters/` or `src/` directory listings when
 *    no `SUMMARY.md` is present (the bare-repo path).
 *  - **Chapter** (`chapter`): fetches the file's base64 body from
 *    `/contents`, decodes, runs through [MarkdownChapterRenderer]
 *    (step 3d-markdown).
 *  - **Search**: deferred to step 3-search (spec sequence step 8).
 *  - **Auth-gated** (followsList, setFollowed): deferred to step 3f.
 *
 * Hilt binding lives in [`in`.jphe.storyvox.source.github.di
 * .GitHubBindings] — `@IntoMap @StringKey(SourceIds.GITHUB)`. Active
 * as of this PR; `addByUrl(github URL)` flows end-to-end through the
 * multi-source map (#35) → `sourceFor(SourceIds.GITHUB)` →
 * `fictionDetail` → `upsertDetail`.
 */
@SourcePlugin(
    id = SourceIds.GITHUB,
    displayName = "GitHub fiction",
    // #436 — fresh-install discoverability: all 17 backend chips visible
    // in Browse by default; users prune via Settings → Plugins. The chip
    // strip is the only place new users learn these backends exist, so
    // opt-in-by-default hid 12/17 of the catalog from fresh installs.
    defaultEnabled = true,
    category = SourceCategory.Text,
    supportsFollow = false,
    supportsSearch = true,
    description = "Repo READMEs as fictions · OAuth Device Flow unlocks 5000/hr + private repos",
    sourceUrl = "https://github.com",
)
@Singleton
internal class GitHubSource @Inject constructor(
    private val api: GitHubApi,
    private val registry: Registry,
    private val markdownRenderer: MarkdownChapterRenderer,
) : FictionSource, GitHubAuthedSource {

    override val id: String = SourceIds.GITHUB
    override val displayName: String = "GitHub"

    override fun filterDimensions(): List<FilterDimension> = listOf(
        FilterDimension.Sort(
            options = listOf(
                FilterDimension.SortOption("relevance", "Best match"),
                FilterDimension.SortOption("popularity", "Stars"),
                FilterDimension.SortOption("last_update", "Updated"),
            ),
        ),
        FilterDimension.NumberRange(
            key = "minStars",
            label = "Minimum stars",
            min = 0f,
            max = 100000f,
            step = 100f,
        ),
        FilterDimension.Select(
            key = "language",
            label = "Language",
            options = listOf(
                "kotlin", "java", "python", "javascript", "typescript",
                "go", "rust", "swift", "ruby", "c", "cpp", "csharp",
            ),
        ),
        // SPDX license keys accepted by GitHub's `license:` qualifier.
        // Curated to the most-common OSS licenses; the GitHub API will
        // 422 on unknown values, so this is a closed list rather than
        // free text. https://docs.github.com/en/search-github/searching-on-github/searching-for-repositories#search-by-license
        FilterDimension.Select(
            key = "license",
            label = "License",
            options = listOf(
                "mit", "apache-2.0", "gpl-3.0", "gpl-2.0",
                "bsd-3-clause", "bsd-2-clause", "isc", "mpl-2.0",
                "lgpl-3.0", "agpl-3.0", "unlicense", "cc0-1.0",
            ),
        ),
        FilterDimension.DateRange(
            key = "pushedSince",
            label = "Pushed since",
            presets = listOf(
                FilterDimension.DatePreset("any", "Any time"),
                FilterDimension.DatePreset("7d", "Last 7 days"),
                FilterDimension.DatePreset("30d", "Last 30 days"),
                FilterDimension.DatePreset("90d", "Last 90 days"),
                FilterDimension.DatePreset("1y", "Last year"),
            ),
        ),
        FilterDimension.TagSet(
            key = "topics",
            label = "Topics",
            options = emptyList(),
            allowExclude = false,
        ),
        FilterDimension.Toggle(
            key = "excludeArchived",
            label = "Exclude archived",
        ),
    )

    override fun applyFilters(base: SearchQuery, state: FilterState): SearchQuery {
        // GitHub's `/search/repositories?q=...` consumes qualifier-laden
        // query strings (`stars:>=N language:X pushed:>=DATE topic:T
        // archived:false sort:stars`). We compose those qualifiers into
        // the SearchQuery.term so `GitHubSource.search()` passes the
        // augmented term verbatim to the API. SearchQuery's structured
        // fields (genres, tags, statuses, ...) don't translate to GitHub
        // search and are intentionally left untouched.
        val parts = mutableListOf<String>()
        if (base.term.isNotBlank()) parts += base.term.trim()

        state.rangeVal("minStars")?.min?.let { minStars ->
            if (minStars > 0f) parts += "stars:>=${minStars.toInt()}"
        }
        state.stringVal("language")?.takeIf { it.isNotBlank() }?.let { lang ->
            parts += "language:${lang.lowercase()}"
        }
        state.stringVal("license")?.takeIf { it.isNotBlank() }?.let { license ->
            parts += "license:${license.lowercase()}"
        }
        state.stringVal("pushedSince")?.takeIf { it != "any" && it.isNotBlank() }?.let { preset ->
            val today = java.time.LocalDate.now()
            val cutoff = when (preset) {
                "7d" -> today.minusDays(7)
                "30d" -> today.minusDays(30)
                "90d" -> today.minusDays(90)
                "1y" -> today.minusYears(1)
                else -> null
            }
            if (cutoff != null) parts += "pushed:>=${cutoff}"
        }
        state.stringSetVal("topics")?.let { topics ->
            topics.included.forEach { tag ->
                val clean = tag.trim().lowercase()
                if (clean.isNotEmpty()) parts += "topic:$clean"
            }
        }
        if (state.boolVal("excludeArchived") == true) {
            parts += "archived:false"
        }
        state.stringVal("sort")?.let { sortId ->
            when (sortId) {
                "popularity" -> parts += "sort:stars"
                "last_update" -> parts += "sort:updated"
                else -> Unit
            }
        }

        return base.copy(term = parts.joinToString(" "))
    }

    override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> =
        registryPage(page) { entries ->
            entries.sortedByDescending { it.featured }
        }

    override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> =
        registryPage(page) { entries ->
            entries.sortedByDescending { it.addedAt.orEmpty() }
        }

    override suspend fun byGenre(
        genre: String,
        page: Int,
    ): FictionResult<ListPage<FictionSummary>> =
        registryPage(page) { entries ->
            val needle = genre.trim().lowercase()
            if (needle.isBlank()) entries
            else entries.filter { it.tags.any { tag -> tag.equals(needle, ignoreCase = true) } }
        }

    override suspend fun genres(): FictionResult<List<String>> {
        return when (val r = registry.entries()) {
            is FictionResult.Success -> FictionResult.Success(
                r.value.flatMap { it.tags }
                    .map { it.lowercase() }
                    .distinct()
                    .sorted(),
            )
            is FictionResult.Failure -> r
        }
    }

    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> {
        // Compose the GitHub search query: pin to fiction-shaped topics
        // so we don't dredge generic repos, then append the user's
        // search term verbatim. GitHub topic OR-syntax is `topic:a
        // OR topic:b` — covers a few synonym tags at once. RR-shaped
        // SearchQuery filter fields (genres, tags, statuses,
        // requireWarnings, etc.) don't translate to GitHub today and
        // are ignored.
        //
        // The GitHub filter sheet (step 8c) composes its own
        // qualifier-laden query (stars:, language:, pushed:, sort:,
        // and possibly its own topic:) and stuffs it into
        // SearchQuery.term. Skip our default topic prefix when the
        // term already contains a `topic:` qualifier so we don't
        // double-up — the filter layer is more authoritative when
        // it's chosen to override.
        val term = query.term.trim()
        val gh = buildString {
            if (!term.contains("topic:", ignoreCase = true)) {
                append("(topic:fiction OR topic:fanfiction OR topic:webnovel)")
                if (term.isNotEmpty()) append(' ')
            }
            if (term.isNotEmpty()) append(term)
        }

        return when (val r = api.searchRepositories(gh, page = query.page)) {
            is GitHubApiResult.Success -> {
                val items = r.value.items.map { it.toFictionSummary() }
                FictionResult.Success(
                    ListPage(
                        items = items,
                        page = query.page,
                        // GitHub search caps at 1000 results across all
                        // pages; signal end-of-list when items < per_page
                        // OR we've reached the cap.
                        hasNext = items.isNotEmpty() && items.size >= 20 && query.page < 50,
                    ),
                )
            }
            is GitHubApiResult.NotFound -> FictionResult.Success(
                ListPage(items = emptyList(), page = query.page, hasNext = false),
            )
            is GitHubApiResult.RateLimited -> FictionResult.RateLimited(
                retryAfter = r.retryAfterSeconds?.let { it.toDuration(DurationUnit.SECONDS) },
            )
            is GitHubApiResult.HttpError -> FictionResult.NetworkError(
                message = "GitHub error ${r.code}: ${r.message}",
            )
            is GitHubApiResult.NetworkError -> FictionResult.NetworkError(
                message = "Could not reach GitHub",
                cause = r.cause,
            )
            is GitHubApiResult.ParseError -> FictionResult.NetworkError(
                message = "Malformed search response",
                cause = r.cause,
            )
        }
    }

    override suspend fun starred(page: Int): FictionResult<ListPage<FictionSummary>> {
        return when (val r = api.starredRepos(page = page, perPage = MY_REPOS_PER_PAGE)) {
            is GitHubApiResult.Success -> {
                // GitHub's `/user/starred` doesn't accept search qualifiers,
                // so the topic filter is applied client-side. Same shape as
                // the public Browse → GitHub: keep repos topic-tagged as
                // fiction-shaped, drop everything else. The page may end up
                // smaller than `perPage` post-filter; that's expected and
                // doesn't end pagination on its own — we only stop when
                // the upstream returns fewer than `perPage` raw items.
                val filtered = r.value.filter { it.matchesFictionTopics() }
                FictionResult.Success(
                    ListPage(
                        items = filtered.map { it.toFictionSummary() },
                        page = page,
                        // Raw page size, pre-filter, decides hasNext:
                        // a full upstream page means there's likely more.
                        hasNext = r.value.size >= MY_REPOS_PER_PAGE,
                    ),
                )
            }
            is GitHubApiResult.NotFound -> FictionResult.NotFound(message = r.message)
            is GitHubApiResult.RateLimited -> FictionResult.RateLimited(
                retryAfter = r.retryAfterSeconds?.let { it.toDuration(DurationUnit.SECONDS) },
            )
            is GitHubApiResult.HttpError -> FictionResult.NetworkError(
                message = "GitHub error ${r.code}: ${r.message}",
            )
            is GitHubApiResult.NetworkError -> FictionResult.NetworkError(
                message = "Could not reach GitHub",
                cause = r.cause,
            )
            is GitHubApiResult.ParseError -> FictionResult.NetworkError(
                message = "Malformed starred response",
                cause = r.cause,
            )
        }
    }

    private fun GhRepo.matchesFictionTopics(): Boolean =
        topics.any { it.equals("fiction", ignoreCase = true) ||
            it.equals("fanfiction", ignoreCase = true) ||
            it.equals("webnovel", ignoreCase = true) }

    /**
     * `GET /user/repos` — auth-gated listing of the signed-in user's
     * repos (#200). Maps each row through [toFictionSummary] so they
     * route through the same `fictionDetail(github:owner/repo)` path
     * as curated-registry entries; manifest detection still applies,
     * so a repo without `book.toml` falls back to the bare-repo dir
     * listing. `hasNext` infers from page-fill (no Link-header parsing
     * yet) — a full page strongly implies another exists; a short
     * page is always the last one.
     */
    override suspend fun myRepos(page: Int): FictionResult<ListPage<FictionSummary>> =
        when (val r = api.myRepos(page = page, perPage = MY_REPOS_PER_PAGE)) {
            is GitHubApiResult.Success -> {
                val items = r.value.map { it.toFictionSummary() }
                FictionResult.Success(
                    ListPage(
                        items = items,
                        page = page,
                        hasNext = items.size >= MY_REPOS_PER_PAGE,
                    ),
                )
            }
            is GitHubApiResult.NotFound -> FictionResult.Success(
                ListPage(items = emptyList(), page = page, hasNext = false),
            )
            is GitHubApiResult.RateLimited -> FictionResult.RateLimited(
                retryAfter = r.retryAfterSeconds?.let { it.toDuration(DurationUnit.SECONDS) },
            )
            is GitHubApiResult.HttpError -> FictionResult.NetworkError(
                message = "GitHub error ${r.code}: ${r.message}",
            )
            is GitHubApiResult.NetworkError -> FictionResult.NetworkError(
                message = "Could not reach GitHub",
                cause = r.cause,
            )
            is GitHubApiResult.ParseError -> FictionResult.NetworkError(
                message = "Malformed /user/repos response",
                cause = r.cause,
            )
        }

    /**
     * Map a GitHub repo into the cross-source [FictionSummary]. Cover
     * URL is intentionally null — the manifest's storyvox.json.cover
     * lives in the repo content, not the API response, so search
     * results don't have it. The user opens the fiction → fictionDetail
     * resolves the manifest → the detail card gets the cover. Tags
     * fall back to GitHub topics; the manifest's storyvox.json.tags
     * (if any) overrides that on the detail page.
     */
    private fun GhRepo.toFictionSummary(): FictionSummary = FictionSummary(
        id = "${SourceIds.GITHUB}:${fullName.lowercase()}",
        sourceId = SourceIds.GITHUB,
        title = name,
        author = owner.login,
        coverUrl = null,
        description = description,
        tags = topics,
        status = if (archived) FictionStatus.COMPLETED else FictionStatus.ONGOING,
        chapterCount = null,
        rating = null,
    )

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        // Gist fictions take a separate codepath: no manifest, no
        // bare-repo dir-listing fallback — just the gist's `files` map
        // mapped 1:1 into chapters.
        parseGistFictionId(fictionId)?.let { gistId ->
            return gistFictionDetail(fictionId, gistId)
        }
        val coords = parseFictionId(fictionId)
            ?: return FictionResult.NotFound(message = "Not a GitHub fiction id: $fictionId")
        val (owner, repo) = coords

        // Existence + metadata. NotFound here means the repo doesn't
        // exist; surface verbatim so the caller's add-by-URL flow can
        // tell the user.
        val ghRepo: GhRepo = when (val r = api.getRepo(owner, repo)) {
            is GitHubApiResult.Success -> r.value
            is GitHubApiResult.NotFound -> return FictionResult.NotFound(message = r.message)
            is GitHubApiResult.RateLimited -> return FictionResult.RateLimited(
                retryAfter = r.retryAfterSeconds?.let { it.toDuration(DurationUnit.SECONDS) },
            )
            is GitHubApiResult.HttpError -> return FictionResult.NetworkError(
                message = "GitHub error ${r.code}: ${r.message}",
            )
            is GitHubApiResult.NetworkError -> return FictionResult.NetworkError(
                message = "Could not reach GitHub",
                cause = r.cause,
            )
            is GitHubApiResult.ParseError -> return FictionResult.NetworkError(
                message = "Malformed repo response",
                cause = r.cause,
            )
        }

        val branch = ghRepo.defaultBranch

        // Manifest candidates. Each is best-effort: a 404 just means
        // the author didn't author that file. Anything else (rate
        // limit, network) is a hard failure — we propagate it via the
        // [OptionalText.failureOrNull] field so the caller short-
        // circuits with the right `FictionResult.Failure` variant.
        val bookTomlOpt = fetchOptionalText(owner, repo, "book.toml", branch)
        bookTomlOpt.failureOrNull?.let { return it }
        // #123 — HonKit / legacy-GitBook fallback. Only fetched when
        // no book.toml exists; saves a wasted HTTP round-trip for the
        // common mdbook case. book.toml wins when both are present.
        val bookJsonOpt = if (bookTomlOpt.text.isNullOrBlank()) {
            fetchOptionalText(owner, repo, "book.json", branch)
        } else null
        bookJsonOpt?.failureOrNull?.let { return it }
        val storyvoxJsonOpt = fetchOptionalText(owner, repo, "storyvox.json", branch)
        storyvoxJsonOpt.failureOrNull?.let { return it }
        // mdbook puts SUMMARY.md under `src/`; HonKit puts it at
        // repo root (or wherever book.json's structure.summary
        // points). guessSrcDir reads book.toml; for HonKit we
        // fetch "SUMMARY.md" at root.
        //
        // Issue #460 — the "Cartographer's Lantern" test repo
        // (jphein/example-fiction) advertises `src = "src"` in
        // book.toml (so mdbook will compile content from `src/*.md`)
        // but keeps SUMMARY.md at the repo *root*, not under `src/`.
        // mdbook's docs strictly require SUMMARY.md under `src/`, but
        // many real-world repos drift from the spec — and the
        // "FictionDetail shows 0 ch" outcome reads as a broken plugin,
        // not a malformed repo. Try the src-relative path first, then
        // fall back to repo root when the first attempt 404s. Two
        // ranging fetches in the bare-fallback case (one extra HTTP
        // call) is cheaper than a wrong "0 ch" verdict; on the happy
        // path (well-formed mdbook), the first fetch succeeds and we
        // never make the second call.
        val srcDirGuess = guessSrcDir(bookTomlOpt.text)
        val isHonKit = bookTomlOpt.text.isNullOrBlank() &&
            !bookJsonOpt?.text.isNullOrBlank()
        val summaryMdOpt: OptionalText = if (isHonKit) {
            fetchOptionalText(owner, repo, "SUMMARY.md", branch)
        } else {
            val srcRelative = fetchOptionalText(owner, repo, "$srcDirGuess/SUMMARY.md", branch)
            if (srcRelative.text.isNullOrBlank() && srcRelative.failureOrNull == null) {
                // src-relative SUMMARY.md doesn't exist; fall back to repo
                // root. The fallback is for mixed-convention repos like
                // jphein/example-fiction (src=src for content + root
                // SUMMARY.md for chapter list).
                fetchOptionalText(owner, repo, "SUMMARY.md", branch)
            } else {
                srcRelative
            }
        }
        summaryMdOpt.failureOrNull?.let { return it }

        val bookToml = bookTomlOpt.text
        val bookJson = bookJsonOpt?.text
        val storyvoxJson = storyvoxJsonOpt.text
        val summaryMd = summaryMdOpt.text

        val bareRepoPaths = if (summaryMd.isNullOrBlank()) {
            listBareRepoPaths(owner, repo, branch, srcDirGuess)
        } else {
            emptyList()
        }

        val manifest = ManifestParser.parse(
            fictionId = fictionId,
            bookToml = bookToml,
            bookJson = bookJson,
            storyvoxJson = storyvoxJson,
            summaryMd = summaryMd,
            bareRepoPaths = bareRepoPaths,
        )

        return FictionResult.Success(toFictionDetail(fictionId, ghRepo, manifest))
    }

    override suspend fun chapter(
        fictionId: String,
        chapterId: String,
    ): FictionResult<ChapterContent> {
        // Gist chapter id format: `<fictionId>:<filename>`. Gists have
        // no commit history; we re-fetch the whole gist and pick the
        // file out — the listing-stub `content` field is null, so this
        // is the only path that yields the body.
        parseGistFictionId(fictionId)?.let { gistId ->
            return gistChapter(fictionId, gistId, chapterId)
        }
        val coords = parseFictionId(fictionId)
            ?: return FictionResult.NotFound(message = "Not a GitHub fiction id: $fictionId")
        val (owner, repo) = coords
        // Chapter id format per spec line 141: `<fictionId>:<path>`.
        val path = chapterId.removePrefix("$fictionId:").trimStart('/')
        if (path.isEmpty() || path == chapterId) {
            return FictionResult.NotFound(message = "Malformed chapter id: $chapterId")
        }

        return when (val r = api.getContent(owner, repo, path)) {
            is GitHubApiResult.Success -> {
                val text = r.value.decodedText()
                    ?: return FictionResult.NetworkError(
                        message = "Chapter at $path was not a base64-encoded file",
                    )
                val info = ChapterInfo(
                    id = chapterId,
                    sourceChapterId = path,
                    index = 0, // caller sets ordering from FictionDetail.chapters
                    title = path.substringAfterLast('/').removeSuffix(".md"),
                )
                FictionResult.Success(markdownRenderer.render(info, text))
            }
            is GitHubApiResult.NotFound -> FictionResult.NotFound(message = r.message)
            is GitHubApiResult.RateLimited -> FictionResult.RateLimited(
                retryAfter = r.retryAfterSeconds?.let { it.toDuration(DurationUnit.SECONDS) },
            )
            is GitHubApiResult.HttpError -> FictionResult.NetworkError(
                message = "GitHub error ${r.code}: ${r.message}",
            )
            is GitHubApiResult.NetworkError -> FictionResult.NetworkError(
                message = "Could not reach GitHub",
                cause = r.cause,
            )
            is GitHubApiResult.ParseError -> FictionResult.NetworkError(
                message = "Malformed chapter response",
                cause = r.cause,
            )
        }
    }

    override suspend fun followsList(
        @Suppress("UNUSED_PARAMETER") page: Int,
    ): FictionResult<ListPage<FictionSummary>> =
        throw NotImplementedError(STEP_3F_AUTH)

    override suspend fun setFollowed(
        @Suppress("UNUSED_PARAMETER") fictionId: String,
        @Suppress("UNUSED_PARAMETER") followed: Boolean,
    ): FictionResult<Unit> =
        throw NotImplementedError(STEP_3F_AUTH)

    /**
     * Cheap-poll revision token: head commit SHA on the repo's default
     * branch. The poll worker compares against the previously-stored
     * token and skips the heavier `fictionDetail` round-trip when they
     * match. Step 9 in the GitHub-source spec.
     *
     * Two API calls per check (`getRepo` for `default_branch` then
     * `/commits?sha={branch}&per_page=1` for the head SHA), against a
     * full `fictionDetail` of repo + book.toml + storyvox.json +
     * SUMMARY.md (4-5 calls + parsing). Net win even if no skip-eligible
     * fictions exist yet, because the parsing alone is the dominant
     * cost.
     *
     * Failures (network, rate-limit, 404) come back as the equivalent
     * `FictionResult.Failure` variants; the worker treats those as
     * "fall back to the full path" rather than aborting the whole poll.
     */
    override suspend fun latestRevisionToken(fictionId: String): FictionResult<String?> {
        // Gists have no commit history exposed via REST; fall back to
        // the full `fictionDetail` path on every poll for now. Returning
        // null instead of NotFound so the worker treats it as "no
        // cheap-poll token, run the full path" rather than dropping the
        // fiction entirely.
        if (parseGistFictionId(fictionId) != null) return FictionResult.Success(null)
        val coords = parseFictionId(fictionId)
            ?: return FictionResult.NotFound(message = "Not a GitHub fiction id: $fictionId")
        val (owner, repo) = coords

        val branch = when (val r = api.getRepo(owner, repo)) {
            is GitHubApiResult.Success -> r.value.defaultBranch
            is GitHubApiResult.NotFound -> return FictionResult.NotFound(message = r.message)
            is GitHubApiResult.RateLimited -> return FictionResult.RateLimited(
                retryAfter = r.retryAfterSeconds?.let { it.toDuration(DurationUnit.SECONDS) },
            )
            is GitHubApiResult.NetworkError -> return FictionResult.NetworkError(
                message = "Could not reach GitHub",
                cause = r.cause,
            )
            is GitHubApiResult.HttpError -> return FictionResult.NetworkError(
                message = "GitHub error ${r.code}: ${r.message}",
            )
            is GitHubApiResult.ParseError -> return FictionResult.NetworkError(
                message = "Malformed repo response",
                cause = r.cause,
            )
        }

        return when (val r = api.getHeadCommit(owner, repo, branch)) {
            is GitHubApiResult.Success -> {
                // Empty list means the branch has no commits yet — treat
                // as "no revision known", caller falls back to the full
                // path. A real repo always has at least one commit.
                FictionResult.Success(r.value.firstOrNull()?.sha)
            }
            is GitHubApiResult.NotFound -> FictionResult.NotFound(message = r.message)
            is GitHubApiResult.RateLimited -> FictionResult.RateLimited(
                retryAfter = r.retryAfterSeconds?.let { it.toDuration(DurationUnit.SECONDS) },
            )
            is GitHubApiResult.NetworkError -> FictionResult.NetworkError(
                message = "Could not reach GitHub",
                cause = r.cause,
            )
            is GitHubApiResult.HttpError -> FictionResult.NetworkError(
                message = "GitHub error ${r.code}: ${r.message}",
            )
            is GitHubApiResult.ParseError -> FictionResult.NetworkError(
                message = "Malformed commits response",
                cause = r.cause,
            )
        }
    }

    // ─── helpers ───────────────────────────────────────────────────────

    /** Result wrapper for a candidate manifest file fetch. */
    private data class OptionalText(
        val text: String?,
        val failureOrNull: FictionResult.Failure?,
    )

    /**
     * Fetch a file's text body, treating 404 as "absent" (returns
     * null text + null failure). Other failures populate
     * [OptionalText.failureOrNull] so the caller can short-circuit.
     */
    private suspend fun fetchOptionalText(
        owner: String,
        repo: String,
        path: String,
        ref: String,
    ): OptionalText = when (val r = api.getContent(owner, repo, path, ref)) {
        is GitHubApiResult.Success -> OptionalText(r.value.decodedText(), null)
        is GitHubApiResult.NotFound -> OptionalText(null, null) // file just isn't there
        is GitHubApiResult.RateLimited -> OptionalText(
            null,
            FictionResult.RateLimited(
                retryAfter = r.retryAfterSeconds?.let { it.toDuration(DurationUnit.SECONDS) },
            ),
        )
        is GitHubApiResult.HttpError -> OptionalText(
            null,
            FictionResult.NetworkError(message = "GitHub error ${r.code}: ${r.message}"),
        )
        is GitHubApiResult.NetworkError -> OptionalText(
            null,
            FictionResult.NetworkError(message = "Could not reach GitHub", cause = r.cause),
        )
        is GitHubApiResult.ParseError -> OptionalText(
            null,
            FictionResult.NetworkError(message = "Malformed file response", cause = r.cause),
        )
    }

    /**
     * Best-effort `[book].src` extraction for the SUMMARY.md path
     * lookup. Re-parsed from book.toml here rather than threading it
     * through ManifestParser because we need it before the parser
     * runs (we feed it the SUMMARY contents as one of its inputs).
     * Default to `src` per mdbook convention.
     */
    private fun guessSrcDir(bookToml: String?): String {
        if (bookToml == null) return "src"
        val m = Regex("""(?m)^\s*src\s*=\s*"([^"]*)"\s*$""").find(bookToml) ?: return "src"
        return m.groupValues[1].ifBlank { "src" }
    }

    /**
     * Listing for the bare-repo fallback: try `chapters/` first, then
     * the manifest-claimed src dir. Returns an empty list on any
     * failure — bare-repo is itself a fallback, so a missing dir just
     * means "nothing to fall back to."
     */
    private suspend fun listBareRepoPaths(
        owner: String,
        repo: String,
        ref: String,
        srcDir: String,
    ): List<String> {
        val candidates = listOf("chapters", srcDir).distinct()
        for (dir in candidates) {
            val r = api.getContents(owner, repo, dir, ref)
            if (r is GitHubApiResult.Success) {
                val files = r.value.filter { it.type == "file" }.map { it.path }
                if (files.isNotEmpty()) return files
            }
        }
        return emptyList()
    }

    private fun toFictionDetail(
        fictionId: String,
        repo: GhRepo,
        manifest: BookManifest,
    ): FictionDetail = FictionDetail(
        summary = FictionSummary(
            id = fictionId,
            sourceId = SourceIds.GITHUB,
            title = manifest.title,
            author = manifest.author,
            // Cover URL is repo-relative in the manifest; resolve
            // against raw.githubusercontent for direct image fetch.
            coverUrl = manifest.coverPath?.let { rawUrl(repo, it) },
            description = manifest.description ?: repo.description,
            tags = manifest.tags.ifEmpty { repo.topics },
            status = parseStatus(manifest.status, repo),
            chapterCount = manifest.chapters.size,
            rating = null,
        ),
        chapters = manifest.chapters.toChapterInfos(fictionId),
        genres = manifest.tags,
        wordCount = null,
        views = null,
        followers = repo.stars.takeIf { it > 0 },
        lastUpdatedAt = null,
        authorId = repo.owner.login,
    )

    private fun List<ManifestChapter>.toChapterInfos(fictionId: String): List<ChapterInfo> =
        mapIndexed { index, ch ->
            ChapterInfo(
                id = "$fictionId:${ch.path}",
                sourceChapterId = ch.path,
                index = index,
                title = ch.title,
            )
        }

    private fun rawUrl(repo: GhRepo, path: String): String {
        val cleanPath = path.trimStart('/')
        return "https://raw.githubusercontent.com/${repo.fullName}/${repo.defaultBranch}/$cleanPath"
    }

    private fun parseStatus(raw: String?, repo: GhRepo): FictionStatus = when {
        raw?.equals("completed", ignoreCase = true) == true -> FictionStatus.COMPLETED
        raw?.equals("hiatus", ignoreCase = true) == true -> FictionStatus.HIATUS
        raw?.equals("dropped", ignoreCase = true) == true -> FictionStatus.DROPPED
        repo.archived -> FictionStatus.COMPLETED
        else -> FictionStatus.ONGOING
    }

    /** `github:owner/repo` → `(owner, repo)` or null. Rejects gist
     *  fiction ids (those use the [GIST_PREFIX] sub-prefix). */
    private fun parseFictionId(fictionId: String): Pair<String, String>? {
        val stripped = fictionId.removePrefix("${SourceIds.GITHUB}:")
        if (stripped == fictionId) return null
        if (stripped.startsWith(GIST_PREFIX)) return null
        val slash = stripped.indexOf('/')
        if (slash <= 0 || slash == stripped.length - 1) return null
        return stripped.substring(0, slash) to stripped.substring(slash + 1)
    }

    /**
     * `github:gist:<id>` → `<id>` or null. The `gist:` sub-prefix
     * disambiguates gist fictions from owner/repo fictions, since
     * gist ids overlap the alphanumeric space that owner/repo path
     * segments occupy. Must be checked before [parseFictionId].
     */
    private fun parseGistFictionId(fictionId: String): String? {
        val stripped = fictionId.removePrefix("${SourceIds.GITHUB}:")
        if (stripped == fictionId) return null
        if (!stripped.startsWith(GIST_PREFIX)) return null
        val gistId = stripped.removePrefix(GIST_PREFIX)
        if (gistId.isBlank() || '/' in gistId) return null
        return gistId
    }

    // ─── gists ────────────────────────────────────────────────────────

    /**
     * Public list of [user]'s gists, mapped to [FictionSummary] for the
     * Browse → GitHub → Gists tab (#202). Excludes secret gists by
     * construction (the public `/users/{user}/gists` endpoint never
     * surfaces them, even with a token); the [authenticatedUserGists]
     * path is what the Settings-account-aware flow uses to include
     * private/secret gists.
     *
     * Gist titles fall back through: [GhGist.description] →
     * first-file filename → "Untitled gist". GitHub UIs use the same
     * order; matching it keeps storyvox consistent with what users
     * see at gist.github.com.
     */
    override suspend fun userGists(user: String, page: Int): FictionResult<ListPage<FictionSummary>> =
        gistsPage(api.userGists(user, page = page), page)

    /**
     * Authenticated-user gists — `GET /gists`. Includes secret gists
     * when the bearer token has the `gist` scope. The OkHttp
     * interceptor attaches the token automatically; an anonymous call
     * comes back as a 401 → mapped to `NetworkError(403)` by the API
     * layer, which the caller surfaces as "sign in to see your gists."
     */
    override suspend fun authenticatedUserGists(page: Int): FictionResult<ListPage<FictionSummary>> =
        gistsPage(api.authenticatedUserGists(page = page), page)

    private fun gistsPage(
        result: GitHubApiResult<List<GhGist>>,
        page: Int,
    ): FictionResult<ListPage<FictionSummary>> = when (result) {
        is GitHubApiResult.Success -> {
            val items = result.value.map { it.toFictionSummary() }
            FictionResult.Success(
                ListPage(
                    items = items,
                    page = page,
                    // Default per_page=30 on the gists endpoints; signal
                    // end-of-list when the page came back short.
                    hasNext = items.size >= 30,
                ),
            )
        }
        is GitHubApiResult.NotFound -> FictionResult.Success(
            ListPage(items = emptyList(), page = page, hasNext = false),
        )
        is GitHubApiResult.RateLimited -> FictionResult.RateLimited(
            retryAfter = result.retryAfterSeconds?.let { it.toDuration(DurationUnit.SECONDS) },
        )
        is GitHubApiResult.HttpError -> FictionResult.NetworkError(
            message = "GitHub error ${result.code}: ${result.message}",
        )
        is GitHubApiResult.NetworkError -> FictionResult.NetworkError(
            message = "Could not reach GitHub",
            cause = result.cause,
        )
        is GitHubApiResult.ParseError -> FictionResult.NetworkError(
            message = "Malformed gists response",
            cause = result.cause,
        )
    }

    private fun GhGist.toFictionSummary(): FictionSummary {
        val title = description?.takeIf { it.isNotBlank() }
            ?: files.keys.firstOrNull()
            ?: "Untitled gist"
        return FictionSummary(
            id = "${SourceIds.GITHUB}:$GIST_PREFIX$id",
            sourceId = SourceIds.GITHUB,
            title = title,
            author = owner?.login.orEmpty(),
            coverUrl = null,
            description = if (public) null else "Secret gist",
            tags = listOfNotNull(files.values.firstOrNull()?.language?.lowercase()),
            // Gists have no lifecycle in the fiction sense; mark them
            // ONGOING so they don't render with the COMPLETED badge.
            status = FictionStatus.ONGOING,
            chapterCount = files.size,
            rating = null,
        )
    }

    private suspend fun gistFictionDetail(
        fictionId: String,
        gistId: String,
    ): FictionResult<FictionDetail> = when (val r = api.getGist(gistId)) {
        is GitHubApiResult.Success -> FictionResult.Success(toGistFictionDetail(fictionId, r.value))
        is GitHubApiResult.NotFound -> FictionResult.NotFound(message = r.message)
        is GitHubApiResult.RateLimited -> FictionResult.RateLimited(
            retryAfter = r.retryAfterSeconds?.let { it.toDuration(DurationUnit.SECONDS) },
        )
        is GitHubApiResult.HttpError -> FictionResult.NetworkError(
            message = "GitHub error ${r.code}: ${r.message}",
        )
        is GitHubApiResult.NetworkError -> FictionResult.NetworkError(
            message = "Could not reach GitHub",
            cause = r.cause,
        )
        is GitHubApiResult.ParseError -> FictionResult.NetworkError(
            message = "Malformed gist response",
            cause = r.cause,
        )
    }

    private fun toGistFictionDetail(fictionId: String, gist: GhGist): FictionDetail {
        val title = gist.description?.takeIf { it.isNotBlank() }
            ?: gist.files.keys.firstOrNull()
            ?: "Untitled gist"
        val author = gist.owner?.login.orEmpty()
        // Each file in the gist's `files` map becomes a chapter. Single-
        // file gists get one chapter titled after the file (the title
        // field already shows the gist description); multi-file gists
        // get the file map order GitHub returned, which matches the UI.
        val chapters = gist.files.entries.mapIndexed { index, (filename, _) ->
            ChapterInfo(
                id = "$fictionId:$filename",
                sourceChapterId = filename,
                index = index,
                title = filename,
            )
        }
        return FictionDetail(
            summary = FictionSummary(
                id = fictionId,
                sourceId = SourceIds.GITHUB,
                title = title,
                author = author,
                coverUrl = null,
                description = if (gist.public) gist.description else "Secret gist",
                tags = listOfNotNull(gist.files.values.firstOrNull()?.language?.lowercase()),
                status = FictionStatus.ONGOING,
                chapterCount = chapters.size,
                rating = null,
            ),
            chapters = chapters,
            genres = emptyList(),
            wordCount = null,
            views = null,
            followers = null,
            lastUpdatedAt = null,
            authorId = author.takeIf { it.isNotBlank() },
        )
    }

    private suspend fun gistChapter(
        fictionId: String,
        gistId: String,
        chapterId: String,
    ): FictionResult<ChapterContent> {
        val filename = chapterId.removePrefix("$fictionId:")
        if (filename.isBlank() || filename == chapterId) {
            return FictionResult.NotFound(message = "Malformed gist chapter id: $chapterId")
        }
        return when (val r = api.getGist(gistId)) {
            is GitHubApiResult.Success -> {
                val file = r.value.files[filename]
                    ?: return FictionResult.NotFound(message = "Gist file not found: $filename")
                val text = file.content
                    ?: return FictionResult.NetworkError(
                        message = "Gist file $filename had no inline content (truncated by GitHub)",
                    )
                val info = ChapterInfo(
                    id = chapterId,
                    sourceChapterId = filename,
                    index = 0,
                    title = filename,
                )
                // Gist files are typically markdown / plaintext; the
                // existing markdown renderer handles both (a non-md
                // source still yields a usable plainBody — markdown's
                // graceful degradation is good enough here).
                FictionResult.Success(markdownRenderer.render(info, text))
            }
            is GitHubApiResult.NotFound -> FictionResult.NotFound(message = r.message)
            is GitHubApiResult.RateLimited -> FictionResult.RateLimited(
                retryAfter = r.retryAfterSeconds?.let { it.toDuration(DurationUnit.SECONDS) },
            )
            is GitHubApiResult.HttpError -> FictionResult.NetworkError(
                message = "GitHub error ${r.code}: ${r.message}",
            )
            is GitHubApiResult.NetworkError -> FictionResult.NetworkError(
                message = "Could not reach GitHub",
                cause = r.cause,
            )
            is GitHubApiResult.ParseError -> FictionResult.NetworkError(
                message = "Malformed gist response",
                cause = r.cause,
            )
        }
    }

    private suspend fun registryPage(
        page: Int,
        transform: (List<RegistryEntry>) -> List<RegistryEntry>,
    ): FictionResult<ListPage<FictionSummary>> {
        if (page > 1) {
            return FictionResult.Success(
                ListPage(items = emptyList(), page = page, hasNext = false),
            )
        }
        return when (val r = registry.entries()) {
            is FictionResult.Success -> FictionResult.Success(
                ListPage(
                    items = transform(r.value).map { it.toSummary() },
                    page = 1,
                    hasNext = false,
                ),
            )
            is FictionResult.Failure -> r
        }
    }

    private companion object {
        const val STEP_3F_AUTH = "GitHub source auth-gated calls not implemented yet — lands in step 3f (optional PAT support)"

        /** Per-page size for the auth-only feeds (#200, #201). 20 mirrors
         *  the search endpoint default so BrowsePaginator hands the user
         *  a consistent grid density across tabs. */
        const val MY_REPOS_PER_PAGE: Int = 20

        /**
         * Sub-prefix that separates a gist fiction id from an
         * owner/repo fiction id. Both share the `github:` source
         * prefix; `github:gist:<id>` resolves to the gist source path,
         * `github:<owner>/<repo>` to the repo source path. Public
         * because [latestRevisionToken] doesn't yet support gists and
         * the cheap-poll worker uses this prefix to skip them.
         */
        const val GIST_PREFIX = "gist:"
    }
}
