package `in`.jphe.storyvox.source.readability

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.RouteMatch
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.UrlMatcher
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.ChapterInfo
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionStatus
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.data.source.plugin.SourceCategory
import `in`.jphe.storyvox.data.source.plugin.SourcePlugin
import `in`.jphe.storyvox.source.readability.extract.ReadabilityExtractor
import `in`.jphe.storyvox.source.readability.net.ReadabilityFetcher
import java.net.URI
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #472 — the catch-all article extractor for the magic-link
 * paste-anything flow. Lives at the bottom of the resolver cascade:
 * if none of the 17 specialised backends claim a URL, Readability4J
 * takes a swing at it and produces a single-chapter "article" fiction.
 *
 * ## What's a fiction here
 *
 * One pasted URL = one fiction = one chapter. The chapter body is the
 * Readability-extracted main content. There's no chapter-list, no
 * polling, no follow concept — the extracted article is a frozen
 * snapshot. The UI labels these clearly ("Single article via
 * Readability — won't auto-fetch new chapters") so the user knows
 * they're not enrolling in a feed.
 *
 * ## Id encoding
 *
 * `readability:<sha256-of-url-truncated-to-16-hex>`. The hash is
 * deterministic so re-pasting the same URL maps to the same fiction
 * row (dedupe by id is implicit in Room's `@PrimaryKey`). 16 hex chars
 * is 64 bits of distinguishability — plenty for the typical Library
 * size while keeping the id short enough to fit in deep links.
 *
 * ## Browse semantics
 *
 * `popular` / `latestUpdates` / `byGenre` / `search` all return empty
 * lists. The user reaches readability fictions exclusively through
 * the magic-link sheet — there's no catalog to browse. `genres` is
 * empty for the same reason.
 *
 * ## Why `defaultEnabled = true`
 *
 * Per the issue's "ship v1 with Readability catch-all" directive: the
 * whole point is "no URL is a dead-end", which requires the source to
 * be available on every fresh install. Disabling it would re-introduce
 * the dead-end branch the catch-all is designed to remove.
 */
@SourcePlugin(
    id = SourceIds.READABILITY,
    displayName = "Readability",
    defaultEnabled = true,
    category = SourceCategory.Text,
    supportsFollow = false,
    supportsSearch = false,
    description = "Catch-all article extractor — paste any URL · single chapter",
    sourceUrl = "",
)
@Singleton
internal class ReadabilitySource @Inject constructor(
    private val fetcher: ReadabilityFetcher,
    private val extractor: ReadabilityExtractor,
    // Issue #989 — durable URL lookup (the persisted `Fiction.sourceUrl`)
    // so a fiction synced from another device (or surviving a process
    // restart / cache-clear) can still be rebuilt. The in-memory
    // [urlCache] remains the hot-path fast lane for the same-session
    // paste-then-fetch flow; this is the cold fallback.
    private val rememberedUrls: `in`.jphe.storyvox.data.source.RememberedUrlStore,
) : FictionSource, UrlMatcher {

    override val id: String = SourceIds.READABILITY
    override val displayName: String = "Readability"

    // ─── browse — no catalog ──────────────────────────────────────────

    override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> =
        emptyPage()

    override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> =
        emptyPage()

    override suspend fun byGenre(
        genre: String,
        page: Int,
    ): FictionResult<ListPage<FictionSummary>> = emptyPage()

    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> =
        emptyPage()

    override suspend fun genres(): FictionResult<List<String>> =
        FictionResult.Success(emptyList())

    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        emptyPage()

    override suspend fun setFollowed(
        fictionId: String,
        followed: Boolean,
    ): FictionResult<Unit> = FictionResult.Success(Unit)

    // ─── detail / chapter — the actual extraction work ────────────────

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        val url = persistedUrlFor(fictionId)
            ?: return FictionResult.NotFound(
                "Readability fictionId $fictionId has no remembered URL — paste the URL again from the Magic-add sheet to rebuild this fiction.",
            )

        return when (val r = extractFromUrl(url)) {
            is FictionResult.Success -> {
                val extracted = r.value
                val summary = FictionSummary(
                    id = fictionId,
                    sourceId = SourceIds.READABILITY,
                    title = extracted.title,
                    author = extracted.byline,
                    description = extracted.excerpt,
                    chapterCount = 1,
                    status = FictionStatus.COMPLETED,
                )
                val chapter = ChapterInfo(
                    id = chapterIdFor(fictionId),
                    sourceChapterId = url,
                    index = 0,
                    title = extracted.title,
                    wordCount = extracted.wordCount,
                )
                FictionResult.Success(
                    FictionDetail(
                        summary = summary,
                        chapters = listOf(chapter),
                    ),
                )
            }
            is FictionResult.Failure -> r
        }
    }

    override suspend fun chapter(
        fictionId: String,
        chapterId: String,
    ): FictionResult<ChapterContent> {
        val url = persistedUrlFor(fictionId)
            ?: return FictionResult.NotFound(
                "Readability fictionId $fictionId has no remembered URL.",
            )
        return when (val r = extractFromUrl(url)) {
            is FictionResult.Success -> {
                val extracted = r.value
                val info = ChapterInfo(
                    id = chapterId,
                    sourceChapterId = url,
                    index = 0,
                    title = extracted.title,
                    wordCount = extracted.wordCount,
                )
                FictionResult.Success(
                    ChapterContent(
                        info = info,
                        htmlBody = extracted.contentHtml,
                        plainBody = extracted.contentText,
                    ),
                )
            }
            is FictionResult.Failure -> r
        }
    }

    // ─── UrlMatcher — claim any plausible HTTP(S) URL ─────────────────

    override fun matchUrl(url: String): RouteMatch? {
        val trimmed = url.trim()
        if (!looksLikeHttpUrl(trimmed)) return null
        return RouteMatch(
            sourceId = SourceIds.READABILITY,
            fictionId = fictionIdFor(trimmed),
            confidence = READABILITY_CONFIDENCE,
            label = "Article via Readability",
        )
    }

    // ─── helpers ──────────────────────────────────────────────────────

    private suspend fun extractFromUrl(
        url: String,
    ): FictionResult<ReadabilityExtractor.Extracted> {
        return when (val r = fetcher.fetch(url)) {
            is FictionResult.Success -> {
                val extracted = extractor.extract(url, r.value)
                if (extracted == null) {
                    FictionResult.NetworkError(
                        "Could not extract readable content from $url — the page may not be article-shaped.",
                    )
                } else {
                    FictionResult.Success(extracted)
                }
            }
            is FictionResult.Failure -> r
        }
    }

    /**
     * Resolve the original URL for [fictionId] so detail/chapter can be
     * re-extracted (the id is a non-reversible hash of the URL).
     *
     * Two tiers:
     *  1. [urlCache] — the in-memory fast lane, populated by `matchUrl`
     *     during the same-session paste-then-fetch happy path.
     *  2. [rememberedUrls] — the durable `Fiction.sourceUrl` column
     *     (issue #989). Survives process death and, crucially, is filled
     *     in by `LibrarySyncer` for a fiction added on another device —
     *     so a synced placeholder hydrates instead of dead-ending on
     *     "has no remembered URL". A hit warms the in-memory cache so
     *     subsequent same-session lookups skip the DAO.
     *
     * Returns null only when neither tier knows the URL (a genuinely
     * un-rebuildable row — e.g. a pre-#989 placeholder added before the
     * column existed); callers surface a clear NotFound in that case.
     */
    private suspend fun persistedUrlFor(fictionId: String): String? =
        urlCache[fictionId]
            ?: rememberedUrls.rememberedUrl(fictionId)?.also { urlCache[fictionId] = it }

    private fun fictionIdFor(url: String): String {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(HASH_LENGTH)
        val id = "${SourceIds.READABILITY}:$hash"
        // Memoise the URL so a same-session detail/chapter fetch can
        // look it back up. See kdoc on persistedUrlFor.
        urlCache[id] = url
        return id
    }

    private fun chapterIdFor(fictionId: String): String = "$fictionId::c0"

    private suspend fun emptyPage(): FictionResult<ListPage<FictionSummary>> =
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    private fun looksLikeHttpUrl(input: String): Boolean {
        // Cheap pre-filter: must start with http(s):// and parse as a URI
        // with a non-empty host. We deliberately don't reach for okhttp's
        // HttpUrl.parse here because matchUrl is called on every paste-
        // debounce tick (300ms while the user types) and we want zero
        // allocation overhead on every keystroke.
        if (input.length < 8) return false
        val lower = input.substring(0, 8).lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        return try {
            val uri = URI(input)
            !uri.host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }

    private companion object {
        /** Pinned lowest competing confidence per issue #472. Other
         *  backends always win on host+path matches; only "host
         *  unrecognized" inputs end up here. */
        const val READABILITY_CONFIDENCE: Float = 0.1f
        const val HASH_LENGTH: Int = 16
        /** In-process URL memoisation — see kdoc on [persistedUrlFor].
         *  Singleton-scoped because the enclosing class is `@Singleton`,
         *  so this map survives the lifetime of the process. */
        private val urlCache = mutableMapOf<String, String>()
    }
}
