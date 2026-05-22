package `in`.jphe.storyvox.source.gutenberg

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.SourceIds
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
import `in`.jphe.storyvox.data.source.model.map
import `in`.jphe.storyvox.source.epub.parse.EpubBook
import `in`.jphe.storyvox.source.epub.parse.EpubParseException
import `in`.jphe.storyvox.source.epub.parse.EpubParser
import `in`.jphe.storyvox.source.gutenberg.di.GutenbergCache
import `in`.jphe.storyvox.source.gutenberg.net.GutendexApi
import `in`.jphe.storyvox.source.gutenberg.net.GutendexBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #237 — Project Gutenberg as a fiction backend.
 *
 * Two-phase model. The catalog surface (Browse → Popular / New
 * Releases / Search / by-subject) is served by the
 * [Gutendex](https://gutendex.com/) JSON API — fast, cached
 * upstream, no PG-side load. When the user "adds a book to library,"
 * we download the EPUB once from gutenberg.org, persist the bytes at
 * `cacheDir/gutenberg/<id>.epub`, and from then on chapter rendering
 * reads spine items out of that local file via the parser already on
 * `:source-epub`.
 *
 * That hybrid keeps the user-visible identity ("Project Gutenberg
 * book") intact in Library and the picker chip while reusing the
 * EPUB pipeline (#235) for the heavy lifting of HTML extraction —
 * the same code path EPUB-imports take.
 *
 * Legal posture: zero ToS friction. PG actively encourages
 * programmatic access (their [robot
 * policy](https://www.gutenberg.org/policy/robot_access.html) is "be
 * polite, identify yourself, throttle"). Gutendex sits between us
 * and PG specifically to absorb catalog traffic; we send a
 * `storyvox-gutenberg/1.0` User-Agent so any rate-limit hits can be
 * routed to a real contact.
 */
@SourcePlugin(
    id = SourceIds.GUTENBERG,
    displayName = "Project Gutenberg",
    defaultEnabled = true,
    category = SourceCategory.Text,
    supportsFollow = false,
    supportsSearch = true,
    description = "70,000+ public-domain books via Gutendex · tap-to-add downloads EPUB once",
    sourceUrl = "https://www.gutenberg.org",
)
@Singleton
internal class GutenbergSource @Inject constructor(
    private val api: GutendexApi,
    @GutenbergCache private val cacheDir: File,
) : FictionSource, `in`.jphe.storyvox.data.source.UrlMatcher {

    // EpubParser is a Kotlin object — stateless across calls. Reused
    // verbatim from `:source-epub` (where it was authored for #235);
    // dependency is declared in source-gutenberg/build.gradle.kts.

    override val id: String = SourceIds.GUTENBERG
    override val displayName: String = "Project Gutenberg"

    /** Issue #472 — `gutenberg.org/ebooks/<id>` (canonical landing) and
     *  `gutenberg.org/files/<id>/...` (direct mirror). Cache-host
     *  `cache.gutenberg.org/cache/epub/<id>/...` is left to the EPUB-
     *  direct-link matcher (lower confidence) so a direct `.epub`
     *  download still resolves. */
    override fun matchUrl(url: String): `in`.jphe.storyvox.data.source.RouteMatch? {
        val m = GUTENBERG_URL_PATTERN.matchEntire(url.trim()) ?: return null
        return `in`.jphe.storyvox.data.source.RouteMatch(
            sourceId = SourceIds.GUTENBERG,
            fictionId = "gutenberg:${m.groupValues[1]}",
            confidence = 0.95f,
            label = "Project Gutenberg book",
        )
    }

    /**
     * In-memory cache of the parsed EpubBook keyed by fictionId. EPUB
     * parsing for a typical PG book (1-5 MB) is ~100ms; caching means
     * a chapter open after the first one is a hash lookup, not a
     * re-parse. The cache is per-process; rebuilds cheaply from the
     * on-disk `.epub` after a process restart.
     */
    private val parsedCache = mutableMapOf<String, EpubBook>()

    // ─── browse ────────────────────────────────────────────────────────

    override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> =
        api.popular(page).map { it.toListPage(page) }

    override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> =
        api.latestUpdates(page).map { it.toListPage(page) }

    override suspend fun byGenre(
        genre: String,
        page: Int,
    ): FictionResult<ListPage<FictionSummary>> =
        // Gutendex doesn't take an arbitrary genre as a query param —
        // PG subjects are free-form strings ("Fiction, English", "Love
        // stories", etc.) rather than a flat taxonomy. Search-by-subject
        // is available via `?topic=<keyword>` (fuzzy match against
        // subjects + bookshelves). We treat the genre as the topic
        // keyword so picks from the genre row still surface a sensible
        // subset.
        api.search(genre, page).map { it.toListPage(page) }

    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> {
        val term = query.term.trim()
        if (term.isEmpty()) {
            return FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))
        }
        return api.search(term, query.page ?: 1).map { it.toListPage(query.page ?: 1) }
    }

    override suspend fun genres(): FictionResult<List<String>> =
        // Curated short list — PG subjects are noisy as a picker (many
        // entries are bibliographic strings like "PR3306.A1"). These
        // seven cover the rough fiction landscape; users wanting more
        // specificity use Search instead.
        FictionResult.Success(
            listOf(
                "Adventure", "Fantasy", "Horror", "Mystery",
                "Romance", "Science Fiction", "Children",
            ),
        )

    // ─── detail ────────────────────────────────────────────────────────

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        val bookId = parseGutenbergId(fictionId)
            ?: return FictionResult.NotFound("Not a Gutenberg fictionId: $fictionId")

        // Pull catalog metadata first — cheap, gives us the canonical
        // title/author/subjects to surface even if the EPUB download
        // later fails.
        val catalog = when (val r = api.bookById(bookId)) {
            is FictionResult.Success -> r.value
            is FictionResult.Failure -> return r
        }

        // Ensure the EPUB is on disk + parsed so we can return a real
        // chapter list. detail()'s contract is "no chapter bodies" but
        // we need the spine for the ChapterInfo[] regardless.
        val parsed = when (val r = ensureParsed(fictionId, catalog)) {
            is FictionResult.Success -> r.value
            is FictionResult.Failure -> return r
        }

        val summary = FictionSummary(
            id = fictionId,
            sourceId = SourceIds.GUTENBERG,
            title = catalog.title,
            author = catalog.authorDisplay(),
            description = catalog.subjects.take(4).joinToString(" · ").ifBlank { null },
            coverUrl = catalog.coverUrl(),
            tags = catalog.subjects,
            status = FictionStatus.COMPLETED, // PG books are inherently complete.
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

    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        // PG has no per-user follow concept.
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    override suspend fun setFollowed(fictionId: String, followed: Boolean): FictionResult<Unit> =
        FictionResult.Success(Unit)

    // ─── helpers ───────────────────────────────────────────────────────

    /**
     * Idempotent EPUB acquire-and-parse. First call for a book
     * downloads + parses + caches; subsequent calls return the
     * cached EpubBook. Surfaces network and parse failures
     * separately so the caller can show the right error copy.
     */
    private suspend fun ensureParsed(
        fictionId: String,
        catalog: GutendexBook,
    ): FictionResult<EpubBook> {
        parsedCache[fictionId]?.let { return FictionResult.Success(it) }
        val onDisk = epubFileFor(fictionId)
            ?: return FictionResult.NotFound("Invalid Gutenberg id: $fictionId")
        if (onDisk.exists() && onDisk.length() > 0L) {
            return reparseFromDisk(fictionId)
        }
        val url = catalog.epubUrl()
            ?: return FictionResult.NotFound("No EPUB download available for ${catalog.title}")
        val bytes = when (val r = api.downloadEpub(url)) {
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
            return FictionResult.NetworkError("Could not parse Gutenberg EPUB: ${e.message}", e)
        }
        parsedCache[fictionId] = parsed
        return FictionResult.Success(parsed)
    }

    private suspend fun reparseFromDisk(fictionId: String): FictionResult<EpubBook> {
        val onDisk = epubFileFor(fictionId)
            ?: return FictionResult.NotFound("Invalid Gutenberg id: $fictionId")
        if (!onDisk.exists()) {
            return FictionResult.NotFound("No cached EPUB for $fictionId")
        }
        val bytes = withContext(Dispatchers.IO) { onDisk.readBytes() }
        val parsed = try {
            withContext(Dispatchers.IO) { EpubParser.parseFromBytes(bytes) }
        } catch (e: EpubParseException) {
            return FictionResult.NetworkError("Cached Gutenberg EPUB unparseable: ${e.message}", e)
        }
        parsedCache[fictionId] = parsed
        return FictionResult.Success(parsed)
    }

    // Issue #718 — refuse to construct a cache path for an unparseable
    // id. Previously this fell back to `0.epub`, collapsing every
    // malformed fictionId (`gutenberg:abc`, `gutenberg:`, deep-link
    // typos like `gutenberg:1342x`) onto the same on-disk file. First
    // write poisoned the cache; later reads returned the corrupt bytes
    // and surfaced an unparseable-EPUB error pointing at the disk
    // cache rather than the bad id. Callers short-circuit with NotFound.
    private fun epubFileFor(fictionId: String): File? {
        val id = parseGutenbergId(fictionId) ?: return null
        return File(cacheDir, "$id.epub")
    }
}

/** Issue #472 — Gutenberg URL pattern for the magic-link resolver. */
internal val GUTENBERG_URL_PATTERN: Regex = Regex(
    """^https?://(?:www\.)?gutenberg\.org/(?:ebooks|files)/(\d+)(?:/.*)?$""",
    RegexOption.IGNORE_CASE,
)

/** `gutenberg:1342` → `1342`; returns null on malformed input.
 *  Issue #718 — null must propagate up through `epubFileFor`; the
 *  prior `?: 0` fallback collapsed every malformed id onto a single
 *  on-disk file. Internal for test visibility. */
internal fun parseGutenbergId(fictionId: String): Int? =
    fictionId.substringAfter("gutenberg:", missingDelimiterValue = "")
        .takeIf { it.isNotEmpty() }
        ?.toIntOrNull()

/** Compose chapter id = `${fictionId}::${spineIdx}` so chapter
 *  lookups can recover the spine index without a separate map.
 *  Mirrors the OutlineSource pattern. */
private fun chapterIdFor(fictionId: String, spineIndex: Int): String =
    "${fictionId}::${spineIndex}"

private fun chapterIndexFrom(chapterId: String): Int? =
    chapterId.substringAfterLast("::", missingDelimiterValue = "")
        .takeIf { it.isNotEmpty() }
        ?.toIntOrNull()

/** Map one Gutendex page to a storyvox ListPage. Gutendex paginates
 *  with absolute next/previous URLs; we just need to know if `next`
 *  is non-null to drive hasMore. */
private fun `in`.jphe.storyvox.source.gutenberg.net.GutendexListPage.toListPage(
    page: Int,
): ListPage<FictionSummary> =
    ListPage(
        items = results.map { it.toSummary() },
        page = page,
        hasNext = next != null,
    )

private fun GutendexBook.toSummary(): FictionSummary =
    FictionSummary(
        id = "gutenberg:$id",
        sourceId = SourceIds.GUTENBERG,
        title = title,
        author = authorDisplay(),
        coverUrl = coverUrl(),
        description = subjects.take(4).joinToString(" · ").ifBlank { null },
        tags = subjects,
        status = FictionStatus.COMPLETED,
    )

/** Cheap HTML→plaintext for the chapter body the engine receives.
 *  The downstream pipeline normalizes further; this just gets the
 *  visible text out of the wrapper tags so the TTS engine doesn't
 *  read out angle-bracket noise.
 *
 *  Issue #442 — strip non-visible regions before stripping tags. PG
 *  EPUB spine entries are full HTML documents with `<head>` (style,
 *  meta, scripts), and a permissive `<[^>]+>` regex leaves the
 *  *contents* of those blocks in the output. On Frankenstein chapter
 *  one ("Letter I", spine[0]) that meant the synth queue saw the
 *  CSS text of the embedded stylesheet rather than the actual prose
 *  — Piper synthesised that as a long, slow, mostly-silent buffer
 *  while the user saw `state=PLAYING, position=0` indefinitely.
 *  Pre-strip `<head>...</head>`, `<script>...</script>`, and
 *  `<style>...</style>` (DOTALL across newlines) so only the visible
 *  body text survives. Case-insensitive because EPUB spine documents
 *  in the wild use both `<HEAD>` and `<head>`.
 */
internal fun String.stripTags(): String {
    val noHead = Regex("(?is)<head\\b[^>]*>.*?</head>").replace(this, " ")
    val noScript = Regex("(?is)<script\\b[^>]*>.*?</script>").replace(noHead, " ")
    val noStyle = Regex("(?is)<style\\b[^>]*>.*?</style>").replace(noScript, " ")
    val noComments = Regex("(?s)<!--.*?-->").replace(noStyle, " ")
    return Regex("<[^>]+>").replace(noComments, " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
