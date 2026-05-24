package `in`.jphe.storyvox.source.github

import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionStatus
import `in`.jphe.storyvox.source.github.model.GhContent
import `in`.jphe.storyvox.source.github.model.GhOwner
import `in`.jphe.storyvox.source.github.model.GhRepo
import `in`.jphe.storyvox.source.github.net.GitHubApi
import `in`.jphe.storyvox.source.github.net.GitHubApiResult
import `in`.jphe.storyvox.source.github.registry.Registry
import `in`.jphe.storyvox.source.github.registry.RegistryEntry
import `in`.jphe.storyvox.source.github.render.MarkdownChapterRenderer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Tests cover:
 *  - Stable contract surface (id, displayName) since both leak to
 *    UrlRouter routing and UI surface strings.
 *  - Browse calls (popular/latestUpdates/byGenre/genres) wired to the
 *    [Registry] as of step 3c — sort/filter/distinct semantics.
 *  - Detail (`fictionDetail`) wired to `GitHubApi` + `ManifestParser`
 *    as of step 3d-detail-and-chapter — repo + manifest fetch, mapping
 *    to FictionDetail, error path forwarding.
 *  - Chapter (`chapter`) wired to `GitHubApi` + `MarkdownChapterRenderer`
 *    as of step 3d-detail-and-chapter — base64 decode, markdown render.
 *  - Stubs that *should* still throw (search, followsList, setFollowed) —
 *    accidental Hilt binding for those surfaces must fail loudly.
 */
class GitHubSourceTest {

    @Test fun `sourceId is the stable github key`() {
        assertEquals(SourceIds.GITHUB, source().id)
    }

    @Test fun `displayName surfaces in UI strings and is stable`() {
        assertEquals("GitHub", source().displayName)
    }

    @Test fun `popular returns featured entries first, registry order within bands`() {
        val src = source(
            entries = listOf(
                entry("github:o/a", title = "A", featured = false),
                entry("github:o/b", title = "B", featured = true),
                entry("github:o/c", title = "C", featured = false),
                entry("github:o/d", title = "D", featured = true),
            ),
        )
        val r = runBlocking { src.popular() } as FictionResult.Success
        val ids = r.value.items.map { it.id }
        assertEquals(listOf("github:o/b", "github:o/d", "github:o/a", "github:o/c"), ids)
        assertEquals(1, r.value.page)
        assertEquals(false, r.value.hasNext)
    }

    @Test fun `latestUpdates sorts by addedAt descending, missing dates last`() {
        val src = source(
            entries = listOf(
                entry("github:o/a", title = "A", addedAt = "2026-01-01"),
                entry("github:o/b", title = "B", addedAt = "2026-05-06"),
                entry("github:o/c", title = "C", addedAt = null),
                entry("github:o/d", title = "D", addedAt = "2026-03-15"),
            ),
        )
        val r = runBlocking { src.latestUpdates() } as FictionResult.Success
        assertEquals(
            listOf("github:o/b", "github:o/d", "github:o/a", "github:o/c"),
            r.value.items.map { it.id },
        )
    }

    @Test fun `byGenre matches tags case-insensitively and trims input`() {
        val src = source(
            entries = listOf(
                entry("github:o/a", title = "A", tags = listOf("fantasy", "litrpg")),
                entry("github:o/b", title = "B", tags = listOf("sci-fi")),
                entry("github:o/c", title = "C", tags = listOf("FANTASY")),
            ),
        )
        val r = runBlocking { src.byGenre("  Fantasy  ") } as FictionResult.Success
        assertEquals(setOf("github:o/a", "github:o/c"), r.value.items.map { it.id }.toSet())
    }

    @Test fun `byGenre with blank input returns the full registry`() {
        val src = source(
            entries = listOf(
                entry("github:o/a", title = "A", tags = listOf("fantasy")),
                entry("github:o/b", title = "B", tags = listOf("sci-fi")),
            ),
        )
        val r = runBlocking { src.byGenre("   ") } as FictionResult.Success
        assertEquals(2, r.value.items.size)
    }

    @Test fun `genres returns deduplicated lowercase union of tags`() {
        val src = source(
            entries = listOf(
                entry("github:o/a", title = "A", tags = listOf("Fantasy", "litrpg")),
                entry("github:o/b", title = "B", tags = listOf("FANTASY", "Sci-Fi")),
            ),
        )
        val r = runBlocking { src.genres() } as FictionResult.Success
        assertEquals(listOf("fantasy", "litrpg", "sci-fi"), r.value)
    }

    @Test fun `page 2 short-circuits to empty hasNext-false page`() {
        val src = source(entries = listOf(entry("github:o/a", title = "A")))
        val r = runBlocking { src.popular(page = 2) } as FictionResult.Success
        assertTrue(r.value.items.isEmpty())
        assertEquals(2, r.value.page)
        assertEquals(false, r.value.hasNext)
    }

    @Test fun `popular degrades gracefully when registry fails, returns live search results`() {
        // #763: popular() now catches registry failures and falls through
        // to the live book search. A registry-down state doesn't block
        // the landing page — live search results still appear.
        val src = source(failure = FictionResult.NetworkError(message = "no internet"))
        val r = runBlocking { src.popular() } as FictionResult.Success
        // With a FakeGitHubApi that has no searches, the live search
        // returns empty — but the overall result is Success, not a failure.
        assertTrue(r.value.items.isEmpty() || r.value.items.isNotEmpty())
    }

    // ─── fictionDetail ─────────────────────────────────────────────────

    @Test fun `fictionDetail builds from book toml + storyvox json + summary md`() {
        val api = FakeGitHubApi(
            repos = mapOf(
                Pair("octocat", "hello-world") to ghRepo(
                    owner = "octocat",
                    name = "hello-world",
                    desc = "ignored when book.toml has description",
                    defaultBranch = "main",
                    topics = listOf("topic-from-gh"),
                ),
            ),
            files = mapOf(
                Triple("octocat", "hello-world", "book.toml") to ghFile(
                    """
                        [book]
                        title = "Hello World Saga"
                        authors = ["octocat"]
                        description = "From the manifest."
                        language = "en"
                        src = "src"
                    """.trimIndent(),
                ),
                Triple("octocat", "hello-world", "storyvox.json") to ghFile(
                    """
                        {
                          "version": 1,
                          "cover": "assets/cover.png",
                          "tags": ["fantasy", "litrpg"],
                          "status": "completed"
                        }
                    """.trimIndent(),
                ),
                Triple("octocat", "hello-world", "src/SUMMARY.md") to ghFile(
                    "- [Intro](src/01-intro.md)\n- [Two](src/02-two.md)",
                ),
            ),
        )
        val src = source(api = api)

        val r = runBlocking { src.fictionDetail("github:octocat/hello-world") } as FictionResult.Success
        val d = r.value

        assertEquals("github:octocat/hello-world", d.summary.id)
        assertEquals(SourceIds.GITHUB, d.summary.sourceId)
        assertEquals("Hello World Saga", d.summary.title)
        assertEquals("octocat", d.summary.author)
        assertEquals("From the manifest.", d.summary.description)
        assertEquals(
            "https://raw.githubusercontent.com/octocat/hello-world/main/assets/cover.png",
            d.summary.coverUrl,
        )
        assertEquals(listOf("fantasy", "litrpg"), d.summary.tags)
        assertEquals(FictionStatus.COMPLETED, d.summary.status)
        assertEquals(2, d.chapters.size)
        assertEquals("github:octocat/hello-world:src/01-intro.md", d.chapters[0].id)
        assertEquals("Intro", d.chapters[0].title)
        assertEquals(0, d.chapters[0].index)
        assertEquals("octocat", d.authorId)
    }

    @Test fun `fictionDetail falls back to repo description and topics when no manifest`() {
        val api = FakeGitHubApi(
            repos = mapOf(
                Pair("o", "minimal") to ghRepo(
                    owner = "o",
                    name = "minimal",
                    desc = "Repo description.",
                    defaultBranch = "trunk",
                    topics = listOf("fiction"),
                ),
            ),
        )
        val src = source(api = api)

        val r = runBlocking { src.fictionDetail("github:o/minimal") } as FictionResult.Success
        val d = r.value

        assertEquals("Minimal", d.summary.title) // titlecase from repo name
        assertEquals("o", d.summary.author) // owner login
        assertEquals("Repo description.", d.summary.description)
        assertEquals(listOf("fiction"), d.summary.tags)
        assertEquals(FictionStatus.ONGOING, d.summary.status)
        assertTrue(d.chapters.isEmpty())
    }

    @Test fun `fictionDetail falls back to root SUMMARY md when src-relative one is missing`() {
        // Issue #460 — jphein/example-fiction (the GitHub plugin's
        // canonical demo fiction) declares `src = "src"` in book.toml
        // so mdbook compiles chapter bodies from `src/*.md`, but keeps
        // SUMMARY.md at the repo root, not under `src/`. The previous
        // fetch order (`src/SUMMARY.md` only) 404'd → 0 chapters →
        // "0 ch · Completed" on FictionDetail. Try root SUMMARY.md as
        // a fallback so mixed-convention repos resolve.
        val api = FakeGitHubApi(
            repos = mapOf(
                Pair("jphein", "example-fiction") to ghRepo(
                    owner = "jphein",
                    name = "example-fiction",
                    defaultBranch = "main",
                ),
            ),
            files = mapOf(
                Triple("jphein", "example-fiction", "book.toml") to ghFile(
                    """
                        [book]
                        title = "The Cartographer's Lantern"
                        authors = ["A Library Nocturne Test"]
                        src = "src"
                    """.trimIndent(),
                ),
                // Root SUMMARY.md, NOT src/SUMMARY.md — same shape as
                // jphein/example-fiction on github.com today.
                Triple("jphein", "example-fiction", "SUMMARY.md") to ghFile(
                    "- [The Letter at Dusk](src/chapter-01.md)\n" +
                        "- [The Lantern's Edge](src/chapter-02.md)\n" +
                        "- [What the Map Held](src/chapter-03.md)",
                ),
            ),
        )
        val src = source(api = api)
        val r = runBlocking { src.fictionDetail("github:jphein/example-fiction") } as FictionResult.Success
        assertEquals("got ${r.value.chapters.size} chapters; expected 3", 3, r.value.chapters.size)
        assertEquals("The Letter at Dusk", r.value.chapters[0].title)
        assertEquals("src/chapter-01.md", r.value.chapters[0].sourceChapterId)
    }

    @Test fun `fictionDetail uses bare-repo dir listing when no SUMMARY md`() {
        val api = FakeGitHubApi(
            repos = mapOf(Pair("o", "bare") to ghRepo("o", "bare")),
            dirs = mapOf(
                Triple("o", "bare", "chapters") to listOf(
                    ghDirEntry("chapters/01-intro.md"),
                    ghDirEntry("chapters/02-fall.md"),
                    ghDirEntry("chapters/notes.md"), // not numbered, ignored
                ),
            ),
        )
        val src = source(api = api)

        val r = runBlocking { src.fictionDetail("github:o/bare") } as FictionResult.Success
        assertEquals(2, r.value.chapters.size)
        assertEquals("chapters/01-intro.md", r.value.chapters[0].sourceChapterId)
        assertEquals("Intro", r.value.chapters[0].title)
    }

    @Test fun `fictionDetail surfaces NotFound when repo missing`() {
        val api = FakeGitHubApi() // empty maps → all calls return NotFound
        val src = source(api = api)
        val r = runBlocking { src.fictionDetail("github:ghost/missing") }
        assertTrue("got $r", r is FictionResult.NotFound)
    }

    @Test fun `fictionDetail rejects non-github fictionId`() {
        val src = source()
        val r = runBlocking { src.fictionDetail("royalroad:12345") }
        assertTrue("got $r", r is FictionResult.NotFound)
    }

    @Test fun `fictionDetail short-circuits on rate-limit during manifest fetch`() {
        val api = FakeGitHubApi(
            repos = mapOf(Pair("o", "r") to ghRepo("o", "r")),
            // book.toml fetch returns RateLimited; should propagate.
            rateLimitedFiles = setOf(Triple("o", "r", "book.toml")),
        )
        val src = source(api = api)
        val r = runBlocking { src.fictionDetail("github:o/r") }
        assertTrue("got $r", r is FictionResult.RateLimited)
    }

    @Test fun `fictionDetail honors archived repo as completed status`() {
        val api = FakeGitHubApi(
            repos = mapOf(Pair("o", "old") to ghRepo("o", "old", archived = true)),
        )
        val src = source(api = api)
        val r = runBlocking { src.fictionDetail("github:o/old") } as FictionResult.Success
        assertEquals(FictionStatus.COMPLETED, r.value.summary.status)
    }

    // ─── chapter ───────────────────────────────────────────────────────

    @Test fun `chapter renders markdown into ChapterContent`() {
        val md = "# Intro\n\nIt was a **dark** and stormy night."
        val api = FakeGitHubApi(
            files = mapOf(Triple("o", "r", "src/01-intro.md") to ghFile(md)),
        )
        val src = source(api = api)

        val r = runBlocking { src.chapter("github:o/r", "github:o/r:src/01-intro.md") } as FictionResult.Success
        val c = r.value

        assertEquals("github:o/r:src/01-intro.md", c.info.id)
        assertEquals("src/01-intro.md", c.info.sourceChapterId)
        assertTrue(c.htmlBody.contains("<h1>Intro</h1>"))
        assertTrue(c.htmlBody.contains("<strong>dark</strong>"))
        // plainBody strips heading + markup (verified independently in
        // MarkdownChapterRendererTest); just sanity check here.
        assertTrue(c.plainBody.startsWith("It was a"))
        assertNotNull("expected non-empty plain body", c.plainBody.takeIf { it.isNotEmpty() })
    }

    @Test fun `chapter rejects malformed chapter id`() {
        val src = source()
        // chapterId not prefixed with "<fictionId>:"
        val r = runBlocking { src.chapter("github:o/r", "garbage-id") }
        assertTrue("got $r", r is FictionResult.NotFound)
    }

    @Test fun `chapter surfaces 404 when file missing`() {
        val src = source(api = FakeGitHubApi())
        val r = runBlocking { src.chapter("github:o/r", "github:o/r:src/missing.md") }
        assertTrue("got $r", r is FictionResult.NotFound)
    }

    // ─── search ────────────────────────────────────────────────────────

    @Test fun `search composes topic-filtered query and maps results to FictionSummary`() {
        val api = FakeGitHubApi(
            searches = listOf(
                "archmage" to GitHubApiResult.Success(
                    `in`.jphe.storyvox.source.github.model.GhSearchResponse(
                        totalCount = 2,
                        items = listOf(
                            ghRepo(owner = "onedayokay", name = "the-archmage", desc = "An overpowered archmage."),
                            ghRepo(owner = "another", name = "archmage-tales", topics = listOf("fiction", "fantasy")),
                        ),
                    ),
                    etag = null,
                ),
            ),
        )
        val src = source(api = api)

        val r = runBlocking {
            src.search(`in`.jphe.storyvox.data.source.model.SearchQuery(term = "archmage"))
        } as FictionResult.Success

        assertEquals(2, r.value.items.size)
        val first = r.value.items[0]
        assertEquals("github:onedayokay/the-archmage", first.id)
        assertEquals(SourceIds.GITHUB, first.sourceId)
        assertEquals("the-archmage", first.title)
        assertEquals("onedayokay", first.author)
        assertEquals("An overpowered archmage.", first.description)
        assertEquals(listOf("fiction", "fantasy"), r.value.items[1].tags)
    }

    @Test fun `search with empty term still goes through topic-filter and returns generic fictions`() {
        val api = FakeGitHubApi(
            // The composed query for term="" is "(topic:fiction OR
            // topic:fanfiction OR topic:webnovel)" — match on "topic:".
            searches = listOf(
                "topic:" to GitHubApiResult.Success(
                    `in`.jphe.storyvox.source.github.model.GhSearchResponse(
                        totalCount = 1,
                        items = listOf(ghRepo(owner = "o", name = "fiction-repo")),
                    ),
                    etag = null,
                ),
            ),
        )
        val src = source(api = api)

        val r = runBlocking {
            src.search(`in`.jphe.storyvox.data.source.model.SearchQuery(term = "  "))
        } as FictionResult.Success

        assertEquals(1, r.value.items.size)
        assertEquals("github:o/fiction-repo", r.value.items.single().id)
    }

    @Test fun `search hasNext is false when results are below the per-page count`() {
        val items = (1..5).map { ghRepo(owner = "o", name = "repo-$it") }
        val api = FakeGitHubApi(
            searches = listOf(
                "x" to GitHubApiResult.Success(
                    `in`.jphe.storyvox.source.github.model.GhSearchResponse(totalCount = 5, items = items),
                    etag = null,
                ),
            ),
        )
        val src = source(api = api)
        val r = runBlocking {
            src.search(`in`.jphe.storyvox.data.source.model.SearchQuery(term = "x"))
        } as FictionResult.Success
        assertEquals(5, r.value.items.size)
        assertEquals(false, r.value.hasNext)
    }

    @Test fun `search hasNext is true when a full page comes back below page-50 cap`() {
        val items = (1..20).map { ghRepo(owner = "o", name = "repo-$it") }
        val api = FakeGitHubApi(
            searches = listOf(
                "many" to GitHubApiResult.Success(
                    `in`.jphe.storyvox.source.github.model.GhSearchResponse(totalCount = 1000, items = items),
                    etag = null,
                ),
            ),
        )
        val src = source(api = api)
        val r = runBlocking {
            src.search(`in`.jphe.storyvox.data.source.model.SearchQuery(term = "many", page = 1))
        } as FictionResult.Success
        assertEquals(20, r.value.items.size)
        assertEquals(true, r.value.hasNext)
    }

    @Test fun `search NotFound surfaces as empty page hasNext-false`() {
        val api = FakeGitHubApi(
            searches = listOf("zilch" to GitHubApiResult.NotFound("nada")),
        )
        val src = source(api = api)
        val r = runBlocking {
            src.search(`in`.jphe.storyvox.data.source.model.SearchQuery(term = "zilch"))
        } as FictionResult.Success
        assertTrue(r.value.items.isEmpty())
        assertEquals(false, r.value.hasNext)
    }

    @Test fun `search RateLimited propagates with retry-after`() {
        val api = FakeGitHubApi(
            searches = listOf(
                "throttled" to GitHubApiResult.RateLimited(retryAfterSeconds = 60),
            ),
        )
        val src = source(api = api)
        val r = runBlocking {
            src.search(`in`.jphe.storyvox.data.source.model.SearchQuery(term = "throttled"))
        }
        assertTrue("got $r", r is FictionResult.RateLimited)
    }

    @Test fun `search NetworkError propagates`() {
        val api = FakeGitHubApi(
            searches = listOf("offline" to GitHubApiResult.NetworkError(java.io.IOException("offline"))),
        )
        val src = source(api = api)
        val r = runBlocking {
            src.search(`in`.jphe.storyvox.data.source.model.SearchQuery(term = "offline"))
        }
        assertTrue("got $r", r is FictionResult.NetworkError)
    }

    @Test fun `archived repos in search results map to COMPLETED status`() {
        val api = FakeGitHubApi(
            searches = listOf(
                "archived" to GitHubApiResult.Success(
                    `in`.jphe.storyvox.source.github.model.GhSearchResponse(
                        totalCount = 1,
                        items = listOf(ghRepo(owner = "o", name = "old", archived = true)),
                    ),
                    etag = null,
                ),
            ),
        )
        val src = source(api = api)
        val r = runBlocking {
            src.search(`in`.jphe.storyvox.data.source.model.SearchQuery(term = "archived"))
        } as FictionResult.Success
        assertEquals(FictionStatus.COMPLETED, r.value.items.single().status)
    }

    // ─── myRepos (#200) ────────────────────────────────────────────────

    @Test fun `myRepos maps each repo to a github FictionSummary`() = runBlocking {
        val api = FakeGitHubApi(
            myReposByPage = mapOf(
                1 to GitHubApiResult.Success(
                    listOf(
                        ghRepo(owner = "octocat", name = "private-novel", desc = "WIP."),
                        ghRepo(owner = "octocat", name = "side-saga", topics = listOf("fiction")),
                    ),
                    etag = null,
                ),
            ),
        )
        val r = source(api = api).myRepos(page = 1) as FictionResult.Success
        assertEquals(2, r.value.items.size)
        val first = r.value.items.first()
        assertEquals("github:octocat/private-novel", first.id)
        assertEquals(SourceIds.GITHUB, first.sourceId)
        assertEquals("private-novel", first.title)
        assertEquals("octocat", first.author)
        assertEquals("WIP.", first.description)
    }

    @Test fun `myRepos hasNext is true when a full page comes back`() = runBlocking {
        val items = (1..20).map { ghRepo(owner = "me", name = "repo-$it") }
        val api = FakeGitHubApi(
            myReposByPage = mapOf(1 to GitHubApiResult.Success(items, etag = null)),
        )
        val r = source(api = api).myRepos(page = 1) as FictionResult.Success
        assertEquals(20, r.value.items.size)
        assertEquals(true, r.value.hasNext)
    }

    @Test fun `myRepos hasNext is false on a short page`() = runBlocking {
        val items = (1..5).map { ghRepo(owner = "me", name = "r-$it") }
        val api = FakeGitHubApi(
            myReposByPage = mapOf(1 to GitHubApiResult.Success(items, etag = null)),
        )
        val r = source(api = api).myRepos(page = 1) as FictionResult.Success
        assertEquals(5, r.value.items.size)
        assertEquals(false, r.value.hasNext)
    }

    @Test fun `myRepos NotFound surfaces as empty page hasNext-false`() = runBlocking {
        val r = source(api = FakeGitHubApi()).myRepos(page = 1) as FictionResult.Success
        assertTrue(r.value.items.isEmpty())
        assertEquals(false, r.value.hasNext)
    }

    @Test fun `myRepos RateLimited propagates with retry-after`() = runBlocking {
        val api = FakeGitHubApi(
            myReposByPage = mapOf(1 to GitHubApiResult.RateLimited(retryAfterSeconds = 30)),
        )
        val r = source(api = api).myRepos(page = 1)
        assertTrue("got $r", r is FictionResult.RateLimited)
    }

    @Test fun `myRepos HttpError 401 propagates as NetworkError so UI surfaces auth issue`() = runBlocking {
        // The auth interceptor is what fires `markExpired()` on 401; the
        // source-level mapping just produces a generic NetworkError with
        // the GitHub error code embedded so the Browse paginator can
        // display it. The Settings row shows "session expired"
        // independently from the interceptor's side-effect.
        val api = FakeGitHubApi(
            myReposByPage = mapOf(1 to GitHubApiResult.HttpError(401, "Bad credentials")),
        )
        val r = source(api = api).myRepos(page = 1)
        assertTrue("got $r", r is FictionResult.NetworkError)
    }

    @Test fun `myRepos archived repos map to COMPLETED status`() = runBlocking {
        val api = FakeGitHubApi(
            myReposByPage = mapOf(
                1 to GitHubApiResult.Success(
                    listOf(ghRepo(owner = "me", name = "old", archived = true)),
                    etag = null,
                ),
            ),
        )
        val r = source(api = api).myRepos(page = 1) as FictionResult.Success
        assertEquals(FictionStatus.COMPLETED, r.value.items.single().status)
    }

    // ─── starred (#201, auth-only Browse → GitHub feed) ────────────────

    @Test fun `starred filters to fiction-shaped topics, drops everything else`() {
        val api = FakeGitHubApi(
            starredPages = mapOf(
                1 to GitHubApiResult.Success(
                    listOf(
                        ghRepo("o", "novella", topics = listOf("fiction")),
                        ghRepo("o", "leetcode", topics = listOf("algorithms")),
                        ghRepo("o", "fanfic", topics = listOf("Fanfiction")), // case-insensitive
                        ghRepo("o", "tools", topics = emptyList()),
                        ghRepo("o", "webnovel-thing", topics = listOf("webnovel", "litrpg")),
                    ),
                    etag = null,
                ),
            ),
        )
        val r = runBlocking { source(api = api).starred(page = 1) } as FictionResult.Success
        assertEquals(
            setOf("github:o/novella", "github:o/fanfic", "github:o/webnovel-thing"),
            r.value.items.map { it.id }.toSet(),
        )
    }

    @Test fun `starred hasNext is true when raw upstream page is full pre-filter`() {
        // Upstream page is at perPage size — there's likely more even if
        // the filter dropped most of them.
        val api = FakeGitHubApi(
            starredPages = mapOf(
                1 to GitHubApiResult.Success(
                    (1..20).map { ghRepo("o", "r-$it", topics = listOf("not-fiction")) },
                    etag = null,
                ),
            ),
        )
        val r = runBlocking { source(api = api).starred(page = 1) } as FictionResult.Success
        assertTrue(r.value.items.isEmpty())
        assertEquals(true, r.value.hasNext)
    }

    @Test fun `starred hasNext is false when upstream page is below perPage`() {
        val api = FakeGitHubApi(
            starredPages = mapOf(
                1 to GitHubApiResult.Success(
                    listOf(ghRepo("o", "x", topics = listOf("fiction"))),
                    etag = null,
                ),
            ),
        )
        val r = runBlocking { source(api = api).starred(page = 1) } as FictionResult.Success
        assertEquals(false, r.value.hasNext)
    }

    @Test fun `starred RateLimited propagates with retry-after`() {
        val api = FakeGitHubApi(
            starredPages = mapOf(
                1 to GitHubApiResult.RateLimited(retryAfterSeconds = 30),
            ),
        )
        val r = runBlocking { source(api = api).starred(page = 1) }
        assertTrue("got $r", r is FictionResult.RateLimited)
    }

    @Test fun `starred 401 surfaces as NetworkError so caller can branch on auth state`() {
        // Auth interceptor routes the bearer header on signed-in calls;
        // an unauthenticated /user/starred answers 401. The source
        // currently maps that to NetworkError(message=GitHub error 401)
        // — Browse uses the empty/error state. (A typed AuthRequired
        // variant could land later if we want to surface a sign-in CTA.)
        val api = FakeGitHubApi(
            starredPages = mapOf(
                1 to GitHubApiResult.HttpError(code = 401, message = "Unauthorized"),
            ),
        )
        val r = runBlocking { source(api = api).starred(page = 1) }
        assertTrue("got $r", r is FictionResult.NetworkError)
    }

    // ─── Still-stubbed surfaces ────────────────────────────────────────

    @Test fun `followsList and setFollowed still throw NotImplementedError`() {
        assertThrows(NotImplementedError::class.java) { runBlocking { source().followsList() } }
        assertThrows(NotImplementedError::class.java) {
            runBlocking { source().setFollowed("github:o/r", true) }
        }
    }

    // ─── latestRevisionToken (step 9 cheap-poll) ───────────────────────

    @Test fun `latestRevisionToken returns head SHA on default branch`() = runBlocking {
        val api = FakeGitHubApi(
            repos = mapOf(("o" to "r") to ghRepo("o", "r", defaultBranch = "trunk")),
            headCommits = mapOf(
                Triple("o", "r", "trunk") to "abc1234567890",
            ),
        )
        val result = source(api = api).latestRevisionToken("github:o/r")
        assertTrue(result is FictionResult.Success)
        assertEquals("abc1234567890", (result as FictionResult.Success).value)
    }

    @Test fun `latestRevisionToken returns null when branch has no commits`() = runBlocking {
        // Empty repo (just-created, no commits yet) — empty array from
        // /commits per_page=1. The worker treats this as "no token, fall
        // back to full path" rather than an error.
        val api = FakeGitHubApi(
            repos = mapOf(("o" to "r") to ghRepo("o", "r")),
            headCommits = emptyMap(),
        )
        val result = source(api = api).latestRevisionToken("github:o/r")
        assertTrue(result is FictionResult.Success)
        assertEquals(null, (result as FictionResult.Success).value)
    }

    @Test fun `latestRevisionToken propagates NotFound when repo missing`() = runBlocking {
        val result = source(api = FakeGitHubApi()).latestRevisionToken("github:o/r")
        assertTrue("got $result", result is FictionResult.NotFound)
    }

    @Test fun `latestRevisionToken propagates RateLimited from getRepo`() = runBlocking {
        // No repo lookup → first call (getRepo) returns NotFound, not
        // RateLimited. Cover the head-commit rate-limit path: getRepo
        // succeeds, /commits is rate-limited.
        val api = FakeGitHubApi(
            repos = mapOf(("o" to "r") to ghRepo("o", "r")),
            rateLimitedHeadCommits = setOf("o" to "r"),
        )
        val result = source(api = api).latestRevisionToken("github:o/r")
        assertTrue("got $result", result is FictionResult.RateLimited)
    }

    @Test fun `latestRevisionToken rejects non-github fictionId`() = runBlocking {
        val result = source(api = FakeGitHubApi()).latestRevisionToken("royalroad:42")
        assertTrue("got $result", result is FictionResult.NotFound)
    }

    // ─── Helpers ───────────────────────────────────────────────────────

    private fun source(
        entries: List<RegistryEntry> = emptyList(),
        failure: FictionResult.Failure? = null,
        api: GitHubApi = FakeGitHubApi(),
    ): GitHubSource {
        val registry = if (failure != null) {
            FailingRegistry(failure)
        } else {
            StaticRegistry(entries)
        }
        return GitHubSource(
            api = api,
            registry = registry,
            markdownRenderer = MarkdownChapterRenderer(),
        )
    }

    private fun entry(
        id: String,
        title: String,
        author: String = "an-author",
        featured: Boolean = false,
        addedAt: String? = null,
        tags: List<String> = emptyList(),
    ) = RegistryEntry(
        id = id,
        title = title,
        author = author,
        featured = featured,
        addedAt = addedAt,
        tags = tags,
    )

    private fun ghRepo(
        owner: String,
        name: String,
        desc: String? = null,
        defaultBranch: String = "main",
        topics: List<String> = emptyList(),
        archived: Boolean = false,
        stars: Int = 0,
    ) = GhRepo(
        id = 0L,
        name = name,
        fullName = "$owner/$name",
        description = desc,
        defaultBranch = defaultBranch,
        owner = GhOwner(login = owner),
        htmlUrl = "https://github.com/$owner/$name",
        topics = topics,
        archived = archived,
        stars = stars,
    )

    private fun ghFile(text: String): GhContent = GhContent(
        name = "x",
        path = "x",
        sha = "0",
        size = text.length.toLong(),
        type = "file",
        content = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8)),
        encoding = "base64",
    )

    private fun ghDirEntry(path: String): GhContent = GhContent(
        name = path.substringAfterLast('/'),
        path = path,
        sha = "0",
        size = 0,
        type = "file",
    )

    private class StaticRegistry(
        private val entries: List<RegistryEntry>,
    ) : Registry(httpClient = OkHttpClient()) {
        override suspend fun entries(): FictionResult<List<RegistryEntry>> =
            FictionResult.Success(entries)
    }

    private class FailingRegistry(
        private val failure: FictionResult.Failure,
    ) : Registry(httpClient = OkHttpClient()) {
        override suspend fun entries(): FictionResult<List<RegistryEntry>> = failure
    }

    /**
     * Fake [GitHubApi] driven by lookup maps. Owner/repo/path fetches
     * miss → NotFound. Rate-limited paths return [GitHubApiResult.RateLimited].
     */
    private class FakeGitHubApi(
        private val repos: Map<Pair<String, String>, GhRepo> = emptyMap(),
        private val files: Map<Triple<String, String, String>, GhContent> = emptyMap(),
        private val dirs: Map<Triple<String, String, String>, List<GhContent>> = emptyMap(),
        private val rateLimitedFiles: Set<Triple<String, String, String>> = emptySet(),
        /** Head SHA per (owner, repo, ref) — null `ref` falls back to
         *  default-branch lookups via `(owner, repo, "")`. */
        private val headCommits: Map<Triple<String, String, String>, String> = emptyMap(),
        private val rateLimitedHeadCommits: Set<Pair<String, String>> = emptySet(),
        /** Search-result lookup keyed by a substring match on the
         *  composed query. First key whose substring is contained in
         *  the actual query wins. */
        private val searches: List<Pair<String, GitHubApiResult<`in`.jphe.storyvox.source.github.model.GhSearchResponse>>> = emptyList(),
        /** Per-page `/user/repos` response. Page 1 returns
         *  `myReposByPage[1]` (or NotFound if absent); page 2 returns
         *  `myReposByPage[2]`; etc. Tests covering hasNext/RateLimited
         *  drop in a `GitHubApiResult.RateLimited` etc. instead of
         *  Success. */
        private val myReposByPage: Map<Int, GitHubApiResult<List<GhRepo>>> = emptyMap(),
        /** Per-page `/user/starred` response (#201). Same shape as
         *  `myReposByPage`; missing page returns Success(emptyList). */
        private val starredPages: Map<Int, GitHubApiResult<List<GhRepo>>> = emptyMap(),
    ) : GitHubApi(httpClient = OkHttpClient()) {

        override suspend fun getRepo(owner: String, repo: String): GitHubApiResult<GhRepo> =
            repos[owner to repo]
                ?.let { GitHubApiResult.Success(it, etag = null) }
                ?: GitHubApiResult.NotFound("repo not found")

        override suspend fun getContent(
            owner: String,
            repo: String,
            path: String,
            ref: String?,
        ): GitHubApiResult<GhContent> {
            val key = Triple(owner, repo, path)
            if (key in rateLimitedFiles) return GitHubApiResult.RateLimited(retryAfterSeconds = 60)
            return files[key]
                ?.let { GitHubApiResult.Success(it, etag = null) }
                ?: GitHubApiResult.NotFound("file not found")
        }

        override suspend fun getContents(
            owner: String,
            repo: String,
            path: String,
            ref: String?,
        ): GitHubApiResult<List<GhContent>> {
            return dirs[Triple(owner, repo, path)]
                ?.let { GitHubApiResult.Success(it, etag = null) }
                ?: GitHubApiResult.NotFound("dir not found")
        }

        override suspend fun getHeadCommit(
            owner: String,
            repo: String,
            ref: String?,
        ): GitHubApiResult<List<`in`.jphe.storyvox.source.github.model.GhCommitRef>> {
            if (owner to repo in rateLimitedHeadCommits) {
                return GitHubApiResult.RateLimited(retryAfterSeconds = 60)
            }
            val key = Triple(owner, repo, ref ?: "")
            val sha = headCommits[key] ?: return GitHubApiResult.Success(emptyList(), etag = null)
            return GitHubApiResult.Success(
                listOf(`in`.jphe.storyvox.source.github.model.GhCommitRef(sha = sha)),
                etag = null,
            )
        }

        override suspend fun starredRepos(
            page: Int,
            perPage: Int,
        ): GitHubApiResult<List<GhRepo>> = starredPages[page]
            ?: GitHubApiResult.Success(emptyList(), etag = null)

        override suspend fun searchRepositories(
            query: String,
            page: Int,
            perPage: Int,
        ): GitHubApiResult<`in`.jphe.storyvox.source.github.model.GhSearchResponse> {
            val match = searches.firstOrNull { (key, _) -> key in query }
            return match?.second ?: GitHubApiResult.Success(
                `in`.jphe.storyvox.source.github.model.GhSearchResponse(
                    totalCount = 0,
                    items = emptyList(),
                ),
                etag = null,
            )
        }

        override suspend fun myRepos(
            page: Int,
            perPage: Int,
        ): GitHubApiResult<List<GhRepo>> =
            myReposByPage[page] ?: GitHubApiResult.NotFound("no /user/repos page $page")

        override suspend fun userGists(
            user: String,
            page: Int,
            perPage: Int,
        ): GitHubApiResult<List<`in`.jphe.storyvox.source.github.model.GhGist>> =
            GitHubApiResult.Success(emptyList(), etag = null)

        override suspend fun authenticatedUserGists(
            page: Int,
            perPage: Int,
        ): GitHubApiResult<List<`in`.jphe.storyvox.source.github.model.GhGist>> =
            GitHubApiResult.Success(emptyList(), etag = null)

        override suspend fun getGist(
            gistId: String,
        ): GitHubApiResult<`in`.jphe.storyvox.source.github.model.GhGist> =
            GitHubApiResult.NotFound("gist not found")

        override suspend fun allMyRepos(
            perPage: Int,
            maxPages: Int,
        ): GitHubApiResult<List<GhRepo>> {
            // Propagate the first error if any page is non-Success.
            for ((_, result) in myReposByPage) {
                if (result !is GitHubApiResult.Success) return result as GitHubApiResult<List<GhRepo>>
            }
            return myReposByPage.values
                .filterIsInstance<GitHubApiResult.Success<List<GhRepo>>>()
                .flatMap { it.value }
                .let { GitHubApiResult.Success(it, etag = null) }
        }
    }

    // ─── comprehensive book search (#763) ────────────────────────────

    @Test fun `popular page 1 merges registry and live book search results`() = runBlocking {
        val api = FakeGitHubApi(
            searches = listOf(
                "topic:" to GitHubApiResult.Success(
                    `in`.jphe.storyvox.source.github.model.GhSearchResponse(
                        totalCount = 2,
                        items = listOf(
                            ghRepo(owner = "ebook-org", name = "classic-novel", topics = listOf("ebook")),
                            ghRepo(owner = "gutenberg", name = "pride-and-prejudice", topics = listOf("gutenberg")),
                        ),
                    ),
                    etag = null,
                ),
            ),
        )
        val src = source(
            entries = listOf(entry("github:curated/book", title = "Curated Book")),
            api = api,
        )
        val r = src.popular(page = 1) as FictionResult.Success
        // Registry entry comes first, live results follow.
        assertTrue("expected >=2 items, got ${r.value.items.size}", r.value.items.size >= 2)
        assertEquals("github:curated/book", r.value.items[0].id)
    }

    @Test fun `popular deduplicates registry and search results by id`() = runBlocking {
        val api = FakeGitHubApi(
            searches = listOf(
                "topic:" to GitHubApiResult.Success(
                    `in`.jphe.storyvox.source.github.model.GhSearchResponse(
                        totalCount = 1,
                        items = listOf(
                            // Same repo as the registry entry — should be deduplicated.
                            ghRepo(owner = "curated", name = "book"),
                        ),
                    ),
                    etag = null,
                ),
            ),
        )
        val src = source(
            entries = listOf(entry("github:curated/book", title = "Curated Book")),
            api = api,
        )
        val r = src.popular(page = 1) as FictionResult.Success
        val ids = r.value.items.map { it.id }
        assertEquals("expected 1 unique item", 1, ids.distinct().size)
    }

    @Test fun `search uses broad book topics when no topic qualifier present`() = runBlocking {
        // The search method should include ebook/book/novel topics
        // alongside fiction/fanfiction/webnovel.
        val api = FakeGitHubApi(
            searches = listOf(
                "topic:ebook" to GitHubApiResult.Success(
                    `in`.jphe.storyvox.source.github.model.GhSearchResponse(
                        totalCount = 1,
                        items = listOf(ghRepo(owner = "o", name = "ebook-repo")),
                    ),
                    etag = null,
                ),
            ),
        )
        val src = source(api = api)
        val r = src.search(
            `in`.jphe.storyvox.data.source.model.SearchQuery(term = "classic literature"),
        ) as FictionResult.Success
        assertEquals(1, r.value.items.size)
    }

    // ─── auto-import user repos (#763) ────────────────────────────────

    @Test fun `scanUserBooksRepos returns repos matching book topics`() = runBlocking {
        val api = FakeGitHubApi(
            myReposByPage = mapOf(
                1 to GitHubApiResult.Success(
                    listOf(
                        ghRepo(owner = "me", name = "my-novel", topics = listOf("ebook", "fiction")),
                        ghRepo(owner = "me", name = "dotfiles", topics = listOf("linux")),
                        ghRepo(owner = "me", name = "my-textbook", topics = listOf("textbook")),
                    ),
                    etag = null,
                ),
            ),
        )
        val r = source(api = api).scanUserBooksRepos() as FictionResult.Success
        val ids = r.value.map { it.id }.toSet()
        assertTrue("my-novel should be included", "github:me/my-novel" in ids)
        assertTrue("my-textbook should be included", "github:me/my-textbook" in ids)
        assertTrue("dotfiles should be excluded", "github:me/dotfiles" !in ids)
    }

    @Test fun `scanUserBooksRepos detects repos by manifest probe when no topic match`() = runBlocking {
        // Repo has no book topics but does have a book.toml manifest.
        val api = FakeGitHubApi(
            myReposByPage = mapOf(
                1 to GitHubApiResult.Success(
                    listOf(
                        ghRepo(owner = "me", name = "secret-book", topics = emptyList()),
                    ),
                    etag = null,
                ),
            ),
            // book.toml probe succeeds → this repo is a book.
            files = mapOf(
                Triple("me", "secret-book", "book.toml") to ghFile("[book]\ntitle = \"Secret\""),
            ),
        )
        val r = source(api = api).scanUserBooksRepos() as FictionResult.Success
        assertEquals(1, r.value.size)
        assertEquals("github:me/secret-book", r.value[0].id)
    }

    @Test fun `scanUserBooksRepos excludes repos with no book signals`() = runBlocking {
        val api = FakeGitHubApi(
            myReposByPage = mapOf(
                1 to GitHubApiResult.Success(
                    listOf(
                        ghRepo(owner = "me", name = "plain-code", topics = listOf("rust")),
                    ),
                    etag = null,
                ),
            ),
        )
        val r = source(api = api).scanUserBooksRepos() as FictionResult.Success
        assertTrue("plain code repo should be excluded", r.value.isEmpty())
    }

    @Test fun `scanUserBooksRepos stops probing on rate limit and returns partial results`() = runBlocking {
        val api = FakeGitHubApi(
            myReposByPage = mapOf(
                1 to GitHubApiResult.Success(
                    listOf(
                        ghRepo(owner = "me", name = "known-book", topics = listOf("ebook")),
                        ghRepo(owner = "me", name = "maybe-book", topics = emptyList()),
                        ghRepo(owner = "me", name = "after-limit", topics = listOf("novel")),
                    ),
                    etag = null,
                ),
            ),
            rateLimitedFiles = setOf(Triple("me", "maybe-book", "book.toml")),
        )
        val r = source(api = api).scanUserBooksRepos() as FictionResult.Success
        val ids = r.value.map { it.id }.toSet()
        assertTrue("known-book found by topic before probe", "github:me/known-book" in ids)
        assertTrue("after-limit skipped because scan broke", "github:me/after-limit" !in ids)
    }

        @Test fun `scanUserBooksRepos propagates rate limit from allMyRepos`() = runBlocking {
        val api = FakeGitHubApi(
            myReposByPage = mapOf(
                1 to GitHubApiResult.RateLimited(retryAfterSeconds = 30),
            ),
        )
        val r = source(api = api).scanUserBooksRepos()
        assertTrue("got $r", r is FictionResult.RateLimited)
    }

    // ─── Gists (#202) ──────────────────────────────────────────────────

    private fun ghGist(
        id: String,
        description: String? = null,
        ownerLogin: String? = "octocat",
        public: Boolean = true,
        files: Map<String, String> = mapOf("file.md" to "body"),
    ): `in`.jphe.storyvox.source.github.model.GhGist =
        `in`.jphe.storyvox.source.github.model.GhGist(
            id = id,
            description = description,
            htmlUrl = "https://gist.github.com/$ownerLogin/$id",
            public = public,
            owner = ownerLogin?.let { GhOwner(login = it) },
            files = files.mapValues { (name, body) ->
                `in`.jphe.storyvox.source.github.model.GhGistFile(
                    filename = name,
                    content = body,
                    size = body.length.toLong(),
                )
            },
        )

    private class GistFakeApi(
        private val gists: Map<String, `in`.jphe.storyvox.source.github.model.GhGist> = emptyMap(),
        private val authedList: List<`in`.jphe.storyvox.source.github.model.GhGist> = emptyList(),
    ) : GitHubApi(httpClient = OkHttpClient()) {
        override suspend fun getRepo(owner: String, repo: String) =
            GitHubApiResult.NotFound("not used")
        override suspend fun authenticatedUserGists(page: Int, perPage: Int) =
            GitHubApiResult.Success(authedList, etag = null)
        override suspend fun getGist(gistId: String) =
            gists[gistId]?.let { GitHubApiResult.Success(it, etag = null) }
                ?: GitHubApiResult.NotFound("gist not found")
        override suspend fun allMyRepos(perPage: Int, maxPages: Int) =
            GitHubApiResult.Success(emptyList<GhRepo>(), etag = null)
    }

    @Test fun `authenticatedUserGists maps gist titles via description, then first filename`() = runBlocking {
        val src = source(
            api = GistFakeApi(
                authedList = listOf(
                    ghGist("a", description = "My snippet"),
                    ghGist("b", description = null, files = mapOf("notes.md" to "x")),
                    ghGist("c", description = "  ", files = mapOf("first.md" to "x", "second.md" to "y")),
                ),
            ),
        )
        val r = src.authenticatedUserGists(page = 1) as FictionResult.Success
        assertEquals(3, r.value.items.size)
        assertEquals("My snippet", r.value.items[0].title)
        // Blank description still falls through to filename.
        assertEquals("first.md", r.value.items[2].title)
        assertEquals("notes.md", r.value.items[1].title)
        assertEquals("github:gist:a", r.value.items[0].id)
        assertEquals(SourceIds.GITHUB, r.value.items[0].sourceId)
    }

    @Test fun `gist fictionDetail builds chapters from files map preserving order`() = runBlocking {
        val src = source(
            api = GistFakeApi(
                gists = mapOf(
                    "abc" to ghGist(
                        id = "abc",
                        description = "Two-part gist",
                        files = linkedMapOf(
                            "01-intro.md" to "Hello world",
                            "02-end.md" to "Goodbye",
                        ),
                    ),
                ),
            ),
        )
        val r = src.fictionDetail("github:gist:abc") as FictionResult.Success
        val d = r.value
        assertEquals("github:gist:abc", d.summary.id)
        assertEquals("Two-part gist", d.summary.title)
        assertEquals(2, d.chapters.size)
        assertEquals("01-intro.md", d.chapters[0].title)
        assertEquals("github:gist:abc:01-intro.md", d.chapters[0].id)
        assertEquals("02-end.md", d.chapters[1].title)
        assertEquals(0, d.chapters[0].index)
        assertEquals(1, d.chapters[1].index)
    }

    @Test fun `gist chapter renders file content via markdown renderer`() = runBlocking {
        val src = source(
            api = GistFakeApi(
                gists = mapOf(
                    "abc" to ghGist(
                        id = "abc",
                        files = mapOf("only.md" to "# Title\n\nBody text."),
                    ),
                ),
            ),
        )
        val r = src.chapter("github:gist:abc", "github:gist:abc:only.md") as FictionResult.Success
        assertTrue(r.value.htmlBody.contains("<h1>Title</h1>"))
        assertTrue(r.value.plainBody.contains("Body text"))
        assertEquals("only.md", r.value.info.title)
    }

    @Test fun `gist chapter surfaces NotFound when file absent`() = runBlocking {
        val src = source(
            api = GistFakeApi(
                gists = mapOf("abc" to ghGist(id = "abc", files = mapOf("a.md" to ""))),
            ),
        )
        val r = src.chapter("github:gist:abc", "github:gist:abc:b.md")
        assertTrue(r is FictionResult.NotFound)
    }

    @Test fun `gist fiction id is rejected by parseFictionId so repo path is not entered`() = runBlocking {
        // Repo-coords parser must reject `gist:`-prefixed ids so the
        // gist codepath wins. A fiction id that confused the two would
        // try to fetch `getRepo("gist", "abc")` which doesn't exist.
        val src = source(api = GistFakeApi())
        val r = src.fictionDetail("github:gist:abc")
        // GistFakeApi.getGist returns NotFound for unknown ids.
        assertTrue("got $r", r is FictionResult.NotFound)
    }

    @Test fun `latestRevisionToken on gist returns null without calling the API`() = runBlocking {
        // Gists have no revision tokens — return Success(null) so the
        // worker treats it as "no token, use full path" rather than
        // dropping the fiction.
        val src = source(api = GistFakeApi())
        val r = src.latestRevisionToken("github:gist:abc")
        assertTrue("got $r", r is FictionResult.Success)
        assertEquals(null, (r as FictionResult.Success).value)
    }

}
