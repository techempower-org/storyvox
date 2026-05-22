package `in`.jphe.storyvox.source.standardebooks

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
import `in`.jphe.storyvox.source.standardebooks.di.StandardEbooksCache
import `in`.jphe.storyvox.source.standardebooks.net.SeBookEntry
import `in`.jphe.storyvox.source.standardebooks.net.SeHtmlParser
import `in`.jphe.storyvox.source.standardebooks.net.SeListPage
import `in`.jphe.storyvox.source.standardebooks.net.StandardEbooksApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #375 — Standard Ebooks as a fiction backend. SE hand-curates
 * public-domain classics from Project Gutenberg into typographically
 * polished, CC0-dedicated editions (~900 titles as of 2026). The
 * differentiation over plain Gutenberg (#237) is editorial: cover art,
 * consistent typography, proofreading. For a TTS app the cleaner
 * source text matters less than the catalog as a *curated shelf* —
 * Gutenberg is the warehouse, SE is the bookstore.
 *
 * Same two-phase model as `:source-gutenberg`. The catalog surface
 * (Browse → Popular / NewReleases / Search / by-tag) lists titles via
 * the public HTML listing at `/ebooks?view=list&per-page=48&page=N`
 * (see [StandardEbooksApi] for why-not-OPDS). When the user "adds a
 * book to library," we download the recommended-compatible EPUB once
 * from standardebooks.org, persist it at
 * `cacheDir/standardebooks/<author>__<book>.epub`, and from then on
 * chapter rendering reads spine items out of that local file via the
 * parser already on `:source-epub`.
 *
 * Legal posture: zero ToS friction. Every release is dedicated to the
 * public domain under [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/);
 * we send a `storyvox-standardebooks/1.0` User-Agent so any rate-limit
 * hits route to a real contact.
 */
@SourcePlugin(
    id = SourceIds.STANDARD_EBOOKS,
    displayName = "Standard Ebooks",
    // #436 — fresh-install discoverability: chip on by default.
    defaultEnabled = true,
    category = SourceCategory.Text,
    supportsFollow = false,
    supportsSearch = true,
    description = "Hand-curated, typographically polished public-domain classics · tap-to-add downloads EPUB",
    sourceUrl = "https://standardebooks.org",
)
@Singleton
internal class StandardEbooksSource @Inject constructor(
    private val api: StandardEbooksApi,
    @StandardEbooksCache private val cacheDir: File,
) : FictionSource, `in`.jphe.storyvox.data.source.UrlMatcher {

    /** Issue #472 — `standardebooks.org/ebooks/<author>/<title>` URL. */
    override fun matchUrl(url: String): `in`.jphe.storyvox.data.source.RouteMatch? {
        val m = STANDARD_EBOOKS_URL_PATTERN.matchEntire(url.trim()) ?: return null
        return `in`.jphe.storyvox.data.source.RouteMatch(
            sourceId = SourceIds.STANDARD_EBOOKS,
            fictionId = "${SourceIds.STANDARD_EBOOKS}:${m.groupValues[1]}/${m.groupValues[2]}",
            confidence = 0.95f,
            label = "Standard Ebooks book",
        )
    }

    // EpubParser is a Kotlin object — stateless across calls. Reused
    // verbatim from `:source-epub` (where it was authored for #235);
    // dependency is declared in source-standard-ebooks/build.gradle.kts.

    override val id: String = SourceIds.STANDARD_EBOOKS
    override val displayName: String = "Standard Ebooks"

    /**
     * In-memory cache of the parsed EpubBook keyed by fictionId. SE
     * EPUBs are similar in size to PG (1-5 MB); caching means a chapter
     * open after the first one is a hash lookup, not a re-parse. The
     * cache is per-process; rebuilds cheaply from the on-disk `.epub`
     * after a process restart.
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
        // SE's subject taxonomy uses lowercase-hyphenated slugs
        // (`science-fiction`, `childrens`, `fiction`). The picker in
        // [genres] emits display-cased names; map them back to slugs
        // here so a tap on "Science Fiction" hits `tags[]=science-fiction`.
        api.byTag(genre.toSeTagSlug(), page).map { it.toListPage(page) }

    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> {
        val term = query.term.trim()
        if (term.isEmpty()) {
            return FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))
        }
        val page = query.page ?: 1
        return api.search(term, page).map { it.toListPage(page) }
    }

    override suspend fun genres(): FictionResult<List<String>> =
        // SE's subject taxonomy is small and fixed — emit the curated
        // fiction-leaning subset directly. The order matches SE's own
        // tag pickers ("Fiction" first as the broadest catch-all).
        FictionResult.Success(
            listOf(
                "Fiction",
                "Adventure",
                "Fantasy",
                "Horror",
                "Mystery",
                "Science Fiction",
                "Children's",
                "Shorts",
            ),
        )

    // ─── detail ────────────────────────────────────────────────────────

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        val (authorSlug, bookSlug) = parseSeId(fictionId)
            ?: return FictionResult.NotFound("Not a Standard Ebooks fictionId: $fictionId")

        // Pull the per-book page first to gather title/author/description
        // — these are the only metadata pieces we surface in the detail
        // card that don't already come from the listing row. The EPUB
        // download below is the truth for chapter count.
        val pageHtml = when (val r = api.bookPage(authorSlug, bookSlug)) {
            is FictionResult.Success -> r.value
            is FictionResult.Failure -> return r
        }
        val description = SeHtmlParser.parseBookDescription(pageHtml)
        // Title/author fall back to the slug if the per-book page is
        // structurally unexpected; we still want to ship *something* to
        // the detail screen rather than fail the whole fetch.
        val displayTitle = bookSlug.slugToDisplay()
        val displayAuthor = authorSlug.slugToDisplay()

        val parsed = when (val r = ensureParsed(fictionId, authorSlug, bookSlug)) {
            is FictionResult.Success -> r.value
            is FictionResult.Failure -> return r
        }

        val summary = FictionSummary(
            id = fictionId,
            sourceId = SourceIds.STANDARD_EBOOKS,
            title = displayTitle,
            author = displayAuthor,
            description = description,
            coverUrl = coverGuess(authorSlug, bookSlug),
            tags = emptyList(),
            status = FictionStatus.COMPLETED, // SE titles are inherently complete.
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
        // SE has no per-user follow concept.
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
        authorSlug: String,
        bookSlug: String,
    ): FictionResult<EpubBook> {
        parsedCache[fictionId]?.let { return FictionResult.Success(it) }
        val onDisk = epubFileFor(authorSlug, bookSlug)
        if (onDisk.exists() && isLikelyZip(onDisk)) {
            return reparseFromDisk(fictionId)
        }
        // Issue #735 — releases v0.5.71 and earlier persisted SE's HTML
        // interstitial page to this path (the bug). If a non-zip file is
        // sitting here from a prior install, delete it before re-fetching
        // so the user doesn't stay stuck on a cached error.
        if (onDisk.exists()) {
            withContext(Dispatchers.IO) { onDisk.delete() }
        }
        val bytes = when (val r = api.downloadEpub(authorSlug, bookSlug)) {
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
            return FictionResult.NetworkError("Could not parse Standard Ebooks EPUB: ${e.message}", e)
        }
        parsedCache[fictionId] = parsed
        return FictionResult.Success(parsed)
    }

    private suspend fun reparseFromDisk(fictionId: String): FictionResult<EpubBook> {
        val (authorSlug, bookSlug) = parseSeId(fictionId)
            ?: return FictionResult.NotFound("Malformed fictionId: $fictionId")
        val onDisk = epubFileFor(authorSlug, bookSlug)
        if (!onDisk.exists()) {
            return FictionResult.NotFound("No cached EPUB for $fictionId")
        }
        // Issue #735 — a v0.5.71-era poisoned cache file (HTML
        // interstitial) gets here on cold start. Delete and surface a
        // distinct error so the next user action (re-add / refresh)
        // re-downloads cleanly.
        if (!isLikelyZip(onDisk)) {
            withContext(Dispatchers.IO) { onDisk.delete() }
            return FictionResult.NetworkError(
                "Cached EPUB was invalid — please retry to re-download.",
                java.io.IOException("non-zip cached EPUB"),
            )
        }
        val bytes = withContext(Dispatchers.IO) { onDisk.readBytes() }
        val parsed = try {
            withContext(Dispatchers.IO) { EpubParser.parseFromBytes(bytes) }
        } catch (e: EpubParseException) {
            return FictionResult.NetworkError(
                "Cached Standard Ebooks EPUB unparseable: ${e.message}",
                e,
            )
        }
        parsedCache[fictionId] = parsed
        return FictionResult.Success(parsed)
    }

    /** Cheap content check — every EPUB starts with the four-byte zip
     *  local-file-header signature `PK\x03\x04`. A file too small to
     *  contain that signature, or with anything else in those positions,
     *  is treated as a poisoned cache entry (see #735). */
    private fun isLikelyZip(file: File): Boolean = try {
        if (file.length() < 4) false else file.inputStream().use { input ->
            val header = ByteArray(4)
            val read = input.read(header)
            read == 4 &&
                header[0] == 'P'.code.toByte() &&
                header[1] == 'K'.code.toByte() &&
                header[2] == 0x03.toByte() &&
                header[3] == 0x04.toByte()
        }
    } catch (_: Throwable) {
        false
    }

    private fun epubFileFor(authorSlug: String, bookSlug: String): File =
        // Filename uses `__` as a separator between author and book
        // slugs so it's unambiguously reversible — SE slugs only contain
        // `[a-z0-9-]` (the URL pattern guarantees that), so `__` never
        // collides with a real slug character.
        File(cacheDir, "${authorSlug}__${bookSlug}.epub")

    /**
     * Best-guess cover URL from the slug pair. SE's listing thumbnails
     * carry a content-hash suffix (`-{8 hex chars}-cover.jpg`) that
     * we don't get from the slug, so we fall back to the per-book
     * page's `/downloads/cover-thumbnail.jpg` which is the same image
     * at the same dimensions, just without the hash-cache-busting URL.
     */
    private fun coverGuess(authorSlug: String, bookSlug: String): String =
        "https://standardebooks.org/ebooks/$authorSlug/$bookSlug/downloads/cover-thumbnail.jpg"
}

/** `standardebooks:{authorSlug}/{bookSlug}` → `(authorSlug, bookSlug)`.
 *  Returns null on malformed input. */
private fun parseSeId(fictionId: String): Pair<String, String>? {
    val rest = fictionId.substringAfter("standardebooks:", missingDelimiterValue = "")
    if (rest.isEmpty()) return null
    val slash = rest.indexOf('/')
    if (slash < 1 || slash == rest.length - 1) return null
    return rest.substring(0, slash) to rest.substring(slash + 1)
}

/** Issue #472 — Standard Ebooks URL pattern. */
internal val STANDARD_EBOOKS_URL_PATTERN: Regex = Regex(
    """^https?://(?:www\.)?standardebooks\.org/ebooks/([\w-]+)/([\w-]+)(?:/.*)?$""",
    RegexOption.IGNORE_CASE,
)

/** Build `standardebooks:{authorSlug}/{bookSlug}` from the listing-row
 *  pair. Mirrors how `:source-gutenberg` composes `gutenberg:{id}`. */
private fun seIdFor(authorSlug: String, bookSlug: String): String =
    "standardebooks:$authorSlug/$bookSlug"

/** Compose chapter id = `${fictionId}::${spineIdx}` so chapter lookups
 *  can recover the spine index without a separate map. Mirrors the
 *  OutlineSource + GutenbergSource pattern. */
private fun chapterIdFor(fictionId: String, spineIndex: Int): String =
    "${fictionId}::${spineIndex}"

private fun chapterIndexFrom(chapterId: String): Int? =
    chapterId.substringAfterLast("::", missingDelimiterValue = "")
        .takeIf { it.isNotEmpty() }
        ?.toIntOrNull()

/** Map an SeListPage to a storyvox ListPage. */
private fun SeListPage.toListPage(page: Int): ListPage<FictionSummary> =
    ListPage(
        items = results.map { it.toSummary() },
        page = page,
        hasNext = hasNext,
    )

private fun SeBookEntry.toSummary(): FictionSummary =
    FictionSummary(
        id = seIdFor(authorSlug, bookSlug),
        sourceId = SourceIds.STANDARD_EBOOKS,
        title = title,
        author = author,
        coverUrl = coverUrl,
        description = tags.take(4).joinToString(" · ").ifBlank { null },
        tags = tags,
        status = FictionStatus.COMPLETED,
    )

/** Map a display-cased genre label (the picker's surface) back to
 *  SE's lowercase-hyphenated subject slug. Keeps the surface stable
 *  even as SE adds new subjects: unknown labels fall through to a
 *  reasonable slugification (`lowercase + spaces→hyphens`). */
private fun String.toSeTagSlug(): String = when (lowercase()) {
    "fiction" -> "fiction"
    "adventure" -> "adventure"
    "fantasy" -> "fantasy"
    "horror" -> "horror"
    "mystery" -> "mystery"
    "science fiction", "scifi", "sci-fi" -> "science-fiction"
    "children's", "childrens", "children" -> "childrens"
    "shorts" -> "shorts"
    else -> lowercase().replace(' ', '-')
}

/** `dornford-yates` → `Dornford Yates`. Used as a fallback when the
 *  per-book page parse misses the title/author (defensive — the page
 *  is consistent today, but a markup shift shouldn't break detail). */
private fun String.slugToDisplay(): String =
    split('-').joinToString(" ") { piece ->
        piece.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

/** Cheap HTML→plaintext for the chapter body the engine receives.
 *  The downstream pipeline normalizes further; this just gets the
 *  visible text out of the wrapper tags so the TTS engine doesn't
 *  read out angle-bracket noise. */
private fun String.stripTags(): String =
    Regex("<[^>]+>").replace(this, " ")
        .replace(Regex("\\s+"), " ")
        .trim()
