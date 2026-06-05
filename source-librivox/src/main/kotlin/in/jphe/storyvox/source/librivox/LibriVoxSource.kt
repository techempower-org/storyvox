package `in`.jphe.storyvox.source.librivox

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.filter.FilterDimension
import `in`.jphe.storyvox.data.source.filter.FilterState
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
import `in`.jphe.storyvox.source.librivox.net.LibriVoxApi
import `in`.jphe.storyvox.source.librivox.net.LibriVoxBook
import `in`.jphe.storyvox.source.librivox.net.LibriVoxSection
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #1015 — `:source-librivox`, storyvox's first **pre-recorded**
 * (human-narrated) source.
 *
 * Where every text backend hands plaintext to the TTS pipeline, LibriVox
 * hands back the volunteer *recording*: each book's section carries an
 * archive.org MP3 in [ChapterContent.audioUrl], which the playback layer
 * routes through Media3 / ExoPlayer and the TTS pipeline is bypassed
 * entirely (issue #373). The shape is the same audio-stream backend
 * `:source-radio` introduced — this source contributes zero engine
 * changes; it's pure metadata + a JSON catalog client.
 *
 * ## Mapping
 *
 * - **Book → [FictionSummary] / [FictionDetail]**. The book id is the
 *   storyvox fictionId verbatim (`"47"` → `librivox` fiction id `"47"`),
 *   so the id alone is enough to rebuild on a second device via
 *   `MetadataBackfillWorker` (no `Fiction.sourceUrl` round-trip needed —
 *   LibriVox is NOT in `SourceIds.idNeedsSourceUrlToRebuild`).
 * - **Section → chapter**. The chapterId is `"<bookId>:<section_number>"`.
 *   Each chapter's [ChapterContent.audioUrl] is the section's
 *   `listen_url`; text bodies are empty so EnginePlayer takes the
 *   Media3 branch.
 *
 * ## Two API shapes (lazy section hydration)
 *
 * The LibriVox listing/search calls **omit** the per-section array — a
 * 50-book browse page would otherwise drag down thousands of MP3 URLs
 * the user never opens. So [popular] / [search] map the lean book
 * records to summaries, and only [fictionDetail] / [chapter] pay the
 * `extended=1` single-book fetch that returns the sections. See
 * [LibriVoxApi] for the endpoint detail.
 *
 * ## Audio-stream backend invariant (issue #373)
 *
 * Every chapter this source produces has `audioUrl != null` and both
 * text bodies empty — EnginePlayer's audio-vs-TTS branch keys off
 * exactly that shape. Keep these in lockstep with `:core-playback`'s
 * expectations or playback regresses.
 */
@SourcePlugin(
    id = SourceIds.LIBRIVOX,
    displayName = "LibriVox",
    defaultEnabled = true,
    category = SourceCategory.AudioStream,
    supportsFollow = false,
    supportsSearch = true,
    description = "Free public-domain audiobooks read by volunteers · archive.org MP3 stream (bypasses TTS) · no account",
    sourceUrl = "https://librivox.org",
)
@Singleton
internal class LibriVoxSource @Inject constructor(
    private val api: LibriVoxApi,
) : FictionSource {

    override val id: String = SourceIds.LIBRIVOX
    override val displayName: String = "LibriVox"

    // ─── filters ──────────────────────────────────────────────────────

    /**
     * LibriVox's catalog API searches on `title` and `author`
     * substrings; `language` is filtered client-side against the
     * `language` field each book carries (the API's own `?language=`
     * facet is unreliable across the catalog, so we narrow locally for
     * predictable results).
     *
     * Free-text inputs rather than a select picker: LibriVox spans 30+
     * languages and the author space is the entire public-domain canon —
     * an autocomplete picker is a v2 surface, same call `:source-radio`
     * made for the Radio Browser facets.
     */
    override fun filterDimensions(): List<FilterDimension> = listOf(
        FilterDimension.Text(
            key = "title",
            label = "Title",
            placeholder = "e.g. Pride and Prejudice",
        ),
        FilterDimension.Text(
            key = "author",
            label = "Author",
            placeholder = "e.g. Austen, Dickens, Verne",
        ),
        FilterDimension.Text(
            key = "language",
            label = "Language",
            placeholder = "e.g. English, French, German",
        ),
    )

    /**
     * Stash filter state in [SearchQuery] using sentinel prefixes in
     * `tags`. The universal SearchQuery has no dedicated title / author /
     * language slots, so [search] picks the values back out by prefix.
     * Mirrors the pattern the Radio / AO3 / Gutenberg sources use for
     * their per-source axes.
     */
    override fun applyFilters(base: SearchQuery, state: FilterState): SearchQuery {
        var q = base
        state.stringVal("title")?.takeIf { it.isNotBlank() }?.let { t ->
            q = q.copy(tags = q.tags + "$TITLE_PREFIX$t")
        }
        state.stringVal("author")?.takeIf { it.isNotBlank() }?.let { a ->
            q = q.copy(tags = q.tags + "$AUTHOR_PREFIX$a")
        }
        state.stringVal("language")?.takeIf { it.isNotBlank() }?.let { l ->
            q = q.copy(tags = q.tags + "$LANGUAGE_PREFIX$l")
        }
        return q
    }

    // ─── browse ────────────────────────────────────────────────────────

    override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> {
        val safePage = page.coerceAtLeast(1)
        val offset = (safePage - 1) * LibriVoxApi.PAGE_SIZE
        return when (val result = api.list(offset = offset, limit = LibriVoxApi.PAGE_SIZE)) {
            is FictionResult.Success -> {
                val items = result.value.map { it.toSummary() }
                FictionResult.Success(
                    ListPage(
                        items = items,
                        page = safePage,
                        // A full page implies there's probably another;
                        // a short page is the end of the catalog.
                        hasNext = items.size >= LibriVoxApi.PAGE_SIZE,
                    ),
                )
            }
            is FictionResult.Failure -> result
        }
    }

    override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> =
        // LibriVox's feed has no documented "recently added" ordering on
        // the public API; the default listing order is the most stable
        // thing to surface, so the Latest tab mirrors popular() rather
        // than 404-ing the paginator.
        popular(page)

    override suspend fun byGenre(
        genre: String,
        page: Int,
    ): FictionResult<ListPage<FictionSummary>> =
        // The public audiobooks feed exposes no genre facet (genre lives
        // on a separate, unstable endpoint); honest empty rather than a
        // fake bucket. Genre browsing is a v2 surface.
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> {
        val term = query.term.trim()
        // Peel filter state back out of [SearchQuery] — [applyFilters]
        // stashes title / author / language under sentinel prefixes.
        val titleFilter = query.tags
            .firstOrNull { it.startsWith(TITLE_PREFIX) }
            ?.removePrefix(TITLE_PREFIX)
        val authorFilter = query.tags
            .firstOrNull { it.startsWith(AUTHOR_PREFIX) }
            ?.removePrefix(AUTHOR_PREFIX)
        val languageFilter = query.tags
            .firstOrNull { it.startsWith(LANGUAGE_PREFIX) }
            ?.removePrefix(LANGUAGE_PREFIX)

        // The free-text term feeds the title search when no explicit
        // title filter is set; an explicit title filter wins. Author
        // comes only from the dedicated filter chip.
        val titleQuery = titleFilter?.takeIf { it.isNotBlank() } ?: term.ifBlank { null }
        val authorQuery = authorFilter?.takeIf { it.isNotBlank() }

        if (titleQuery == null && authorQuery == null && languageFilter.isNullOrBlank()) {
            // Nothing to search on — mirror the popular() shape so the
            // Search tab feels populated rather than blank-on-arrival.
            return popular(page = 1)
        }

        return when (
            val result = api.search(
                title = titleQuery,
                author = authorQuery,
                limit = LibriVoxApi.PAGE_SIZE,
            )
        ) {
            is FictionResult.Success -> {
                // Language is narrowed client-side (see filterDimensions
                // kdoc) — the API's language facet is unreliable, the
                // per-book `language` field is authoritative.
                val filtered = if (!languageFilter.isNullOrBlank()) {
                    result.value.filter {
                        it.language.contains(languageFilter, ignoreCase = true)
                    }
                } else {
                    result.value
                }
                FictionResult.Success(
                    ListPage(
                        items = filtered.map { it.toSummary() },
                        page = 1,
                        // Search is a single server page; the paginator
                        // stops after it.
                        hasNext = false,
                    ),
                )
            }
            is FictionResult.Failure -> result
        }
    }

    override suspend fun genres(): FictionResult<List<String>> =
        FictionResult.Success(emptyList())

    // ─── detail ────────────────────────────────────────────────────────

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        val bookId = bookIdFromFictionId(fictionId)
        return when (val result = api.byId(bookId)) {
            is FictionResult.Success -> {
                val book = result.value
                FictionResult.Success(
                    FictionDetail(
                        summary = book.toSummary(),
                        chapters = book.sections.mapIndexed { index, section ->
                            section.toChapterInfo(book.id, index)
                        },
                        wordCount = null,
                        lastUpdatedAt = null,
                    ),
                )
            }
            is FictionResult.Failure -> result
        }
    }

    override suspend fun chapter(
        fictionId: String,
        chapterId: String,
    ): FictionResult<ChapterContent> {
        val bookId = bookIdFromFictionId(fictionId)
        return when (val result = api.byId(bookId)) {
            is FictionResult.Success -> {
                val book = result.value
                val idx = book.sections.indexOfFirst {
                    chapterIdFor(book.id, it) == chapterId
                }
                if (idx < 0) {
                    return FictionResult.NotFound(
                        "Unknown LibriVox chapter: $fictionId / $chapterId",
                    )
                }
                val section = book.sections[idx]
                if (section.listenUrl.isBlank()) {
                    return FictionResult.NotFound(
                        "LibriVox section has no audio URL: $chapterId",
                    )
                }
                FictionResult.Success(
                    ChapterContent(
                        info = section.toChapterInfo(book.id, idx),
                        // Issue #373 — audio chapters carry empty text
                        // bodies; EnginePlayer sees `audioUrl != null` and
                        // routes through Media3 instead of TTS.
                        htmlBody = "",
                        plainBody = "",
                        audioUrl = section.listenUrl,
                    ),
                )
            }
            is FictionResult.Failure -> result
        }
    }

    // ─── auth-gated ────────────────────────────────────────────────────

    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    override suspend fun setFollowed(fictionId: String, followed: Boolean): FictionResult<Unit> =
        FictionResult.Success(Unit)

    // ─── helpers ───────────────────────────────────────────────────────

    private fun LibriVoxBook.toSummary(): FictionSummary =
        FictionSummary(
            id = id,
            sourceId = SourceIds.LIBRIVOX,
            title = title.ifBlank { "Untitled" },
            author = authorLabel(),
            // LibriVox descriptions are HTML; the reader / detail layer
            // sanitizes. Surface the raw description plus a language /
            // year breadcrumb so the card has context even before the
            // detail fetch.
            description = buildString {
                append(description.trim())
                val meta = buildList {
                    if (language.isNotBlank()) add(language)
                    if (copyrightYear.isNotBlank()) add(copyrightYear)
                }
                if (meta.isNotEmpty()) {
                    if (isNotEmpty()) append("\n\n")
                    append(meta.joinToString(" · "))
                }
            }.ifBlank { null },
            coverUrl = null,
            tags = buildList {
                if (language.isNotBlank()) add(language)
                add("audiobook")
                add("public domain")
            },
            // LibriVox recordings are finished works (the project only
            // releases completed books) — COMPLETED reads correctly.
            status = FictionStatus.COMPLETED,
            chapterCount = numSections.toIntOrNull(),
        )

    private fun LibriVoxSection.toChapterInfo(bookId: String, index: Int): ChapterInfo =
        ChapterInfo(
            id = chapterIdFor(bookId, this),
            sourceChapterId = sectionNumber.ifBlank { (index + 1).toString() },
            index = index,
            title = title.ifBlank { "Section ${sectionNumber.ifBlank { (index + 1).toString() }}" },
        )

    companion object {
        /**
         * Build the storyvox-scoped chapterId for [section] within
         * [bookId]. Format is `"<bookId>:<section_number>"` — stable
         * across fetches because LibriVox section numbers are fixed once
         * a book is catalogued. Falls back to the section's own id when
         * the number is blank (defensive; the live API always populates
         * `section_number`).
         */
        internal fun chapterIdFor(bookId: String, section: LibriVoxSection): String {
            val seg = section.sectionNumber.ifBlank { section.id }
            return "$bookId:$seg"
        }

        /**
         * Extract the LibriVox book id from a fictionId. The book id IS
         * the fictionId for LibriVox (no prefix), so this is identity —
         * the helper exists so the call sites read intentfully and so a
         * future id-shape change has one place to live.
         */
        internal fun bookIdFromFictionId(fictionId: String): String = fictionId.trim()

        const val USER_AGENT: String =
            "storyvox-librivox/1.0 (+https://github.com/techempower-org/storyvox)"

        /**
         * Sentinel prefixes used by [applyFilters] to smuggle the
         * title / author / language filters through [SearchQuery.tags].
         * The universal SearchQuery has no per-source slots for these;
         * [search] picks the values back out by prefix.
         */
        internal const val TITLE_PREFIX = "title:"
        internal const val AUTHOR_PREFIX = "author:"
        internal const val LANGUAGE_PREFIX = "language:"
    }
}
