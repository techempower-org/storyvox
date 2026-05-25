package `in`.jphe.storyvox.source.radio

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
import `in`.jphe.storyvox.source.radio.config.RadioConfig
import `in`.jphe.storyvox.source.radio.net.RadioBrowserApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #417 — `:source-radio` (generalized from `:source-kvmr`).
 *
 * Each radio station is exactly one fiction with exactly one chapter
 * ("Live") whose [ChapterContent.audioUrl] points at the station's
 * stream. The v0.5.20 audio-stream backend wiring in `:core-playback`
 * routes audioUrl through Media3 / ExoPlayer — this source contributes
 * zero engine changes; it's pure metadata.
 *
 * ## Two roster sources
 *
 * - **Curated stations** in [RadioStations.curated] — hand-picked seed
 *   list (KVMR, Capital Public Radio, KQED, KCSB, SomaFM Groove Salad
 *   as of v0.5.32). Always surfaced on `popular()`.
 * - **Starred Radio Browser imports** from [RadioConfig.starredStations]
 *   — stations the user has searched + starred from Browse → Radio →
 *   Search. Surfaced on `popular()` alongside the curated set,
 *   appended after the curated entries (the seed list stays at the
 *   top of the picker for predictability).
 *
 * Free-text search hits [RadioBrowserApi.byName] — the same CC0
 * directory the star action draws from — and maps results to
 * `FictionSummary`s. Tapping star on a result is the :app layer's
 * concern; the source exposes the result list, and the UI persists
 * the user's choice via [RadioConfig.star].
 *
 * ## Migration / id stability
 *
 * The curated KVMR entry's `id` is `"kvmr"` (matching the v0.5.20
 * `LIVE_FICTION_ID = "kvmr:live"`), so existing persisted KVMR
 * fictions resolve through the renamed source unchanged. The
 * `SourceIds.KVMR` constant is kept in `:core-data` as a one-cycle
 * alias for the matching `@StringKey` legacy binding in the radio
 * Hilt module; a follow-up release can drop the alias.
 *
 * ## Audio-stream backend invariant (issue #373)
 *
 * Every chapter this source produces has `audioUrl != null` and both
 * text bodies empty. EnginePlayer's audio-vs-TTS branch keys off
 * exactly that shape — keep these in lockstep with
 * `:core-playback`'s expectations or playback regresses.
 */
@SourcePlugin(
    id = SourceIds.RADIO,
    displayName = "Radio",
    defaultEnabled = true,
    category = SourceCategory.AudioStream,
    supportsFollow = false,
    supportsSearch = true,
    description = "Live community / public / college / specialty radio · Media3 stream (bypasses TTS) · CC0 Radio Browser search",
    sourceUrl = "https://www.radio-browser.info",
)
@Singleton
internal class RadioSource @Inject constructor(
    private val api: RadioBrowserApi,
    private val config: RadioConfig,
) : FictionSource, `in`.jphe.storyvox.data.source.UrlMatcher {

    override val id: String = SourceIds.RADIO
    override val displayName: String = "Radio"

    /** Issue #472 — direct audio-stream URLs (`.mp3`, `.m4a`, `.aac`,
     *  `.ogg`, `.m3u`, `.m3u8` extensions). The radio backend wraps
     *  these as ad-hoc "Custom Station" fictions. Audio MIME-type
     *  sniffing would require a HEAD request; v1 relies on extension
     *  alone for the matcher's synchronous contract. */
    override fun matchUrl(url: String): `in`.jphe.storyvox.data.source.RouteMatch? {
        val m = Regex(
            """^https?://[^?#]+\.(mp3|m4a|aac|ogg|opus|m3u8?|pls)(?:[?#].*)?$""",
            RegexOption.IGNORE_CASE,
        ).matchEntire(url.trim()) ?: return null
        // Hash the URL so the fictionId is stable across re-paste.
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(url.trim().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)
        return `in`.jphe.storyvox.data.source.RouteMatch(
            sourceId = SourceIds.RADIO,
            fictionId = "${SourceIds.RADIO}:custom:$hash",
            confidence = 0.85f,
            label = "Audio stream",
        )
    }

    // ─── filters ───────────────────────────────────────────────────────

    /**
     * Issue #795 — Radio Browser's `/json/stations/search` endpoint
     * supports country, language, and tag facets that the v1 source
     * was discarding. The filter sheet now surfaces them as free-text
     * Text dimensions (the directory's value space is too large to
     * enumerate as Select chips — JP's "german jazz" search is one
     * combination among thousands of country×language×tag triples).
     */
    override fun filterDimensions(): List<FilterDimension> = listOf(
        FilterDimension.Text(
            key = "country",
            label = "Country",
            placeholder = "e.g. The United States Of America",
        ),
        FilterDimension.Text(
            key = "countryCode",
            label = "Country code",
            placeholder = "e.g. US, DE, GB",
        ),
        FilterDimension.Text(
            key = "language",
            label = "Language",
            placeholder = "e.g. english, spanish",
        ),
        FilterDimension.Text(
            key = "tag",
            label = "Tag",
            placeholder = "e.g. jazz, classical, news",
        ),
    )

    /**
     * Persists Radio Browser facets onto the SearchQuery via sentinel
     * tags (`country:`, `countryCode:`, `language:`, `tag:`) so [search]
     * can pull them back out without growing new SearchQuery fields.
     * The sentinels are namespaced to this source's tag space; they
     * never leak past [RadioSource.search].
     */
    override fun applyFilters(base: SearchQuery, state: FilterState): SearchQuery {
        var q = base
        state.stringVal("country")?.takeIf { it.isNotBlank() }?.let {
            q = q.copy(tags = q.tags + "country:${it.trim()}")
        }
        state.stringVal("countryCode")?.takeIf { it.isNotBlank() }?.let {
            q = q.copy(tags = q.tags + "countryCode:${it.trim()}")
        }
        state.stringVal("language")?.takeIf { it.isNotBlank() }?.let {
            q = q.copy(tags = q.tags + "language:${it.trim()}")
        }
        state.stringVal("tag")?.takeIf { it.isNotBlank() }?.let {
            q = q.copy(tags = q.tags + "tag:${it.trim()}")
        }
        return q
    }

    // ─── browse ────────────────────────────────────────────────────────

    override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> {
        // The roster fits on one page (curated + starred is on the
        // order of dozens). Page 2+ returns empty so the paginator
        // stops requesting.
        if (page > 1) {
            return FictionResult.Success(ListPage(items = emptyList(), page = page, hasNext = false))
        }
        val curated = RadioStations.curated.map { it.toSummary() }
        val starred = config.snapshot().map { it.toSummary() }
        // Curated first, starred appended after — the seed list keeps a
        // predictable top-of-list position so JP's KVMR shortcut never
        // moves around as the starred set grows.
        return FictionResult.Success(
            ListPage(
                items = curated + starred,
                page = 1,
                hasNext = false,
            ),
        )
    }

    override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> =
        // Radio is a continuous live medium — there's no notion of
        // "latest update" beyond "the station just keeps playing." We
        // mirror popular() so a stale tab pointer doesn't 404 the
        // paginator (matches the legacy :source-kvmr behavior).
        popular(page)

    override suspend fun byGenre(
        genre: String,
        page: Int,
    ): FictionResult<ListPage<FictionSummary>> =
        // Genre faceting against ~5 curated stations isn't useful;
        // honest empty rather than a fake bucket.
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> {
        val term = query.term.trim()
        // Pull the Radio Browser facets back off the tag sentinels
        // [applyFilters] writes.
        val country = query.tags.firstOrNull { it.startsWith("country:") }
            ?.removePrefix("country:")?.takeIf { it.isNotBlank() }
        val countryCode = query.tags.firstOrNull { it.startsWith("countryCode:") }
            ?.removePrefix("countryCode:")?.takeIf { it.isNotBlank() }
        val language = query.tags.firstOrNull { it.startsWith("language:") }
            ?.removePrefix("language:")?.takeIf { it.isNotBlank() }
        val tag = query.tags.firstOrNull { it.startsWith("tag:") }
            ?.removePrefix("tag:")?.takeIf { it.isNotBlank() }
        val anyFacet = country != null || countryCode != null ||
            language != null || tag != null

        if (term.isEmpty() && !anyFacet) {
            // Match the popular() shape for empty-query so the Search
            // tab feels populated rather than blank-on-arrival.
            return popular(page = 1)
        }

        // Local match against the curated set + starred imports first
        // (zero network, instant) — this is what kept the old
        // :source-kvmr "search for kvmr" surface fast. Anything beyond
        // the local matches comes from the Radio Browser API call.
        // Local search only applies a name term — country/language/tag
        // facets always route to the remote API for proper coverage.
        val lowered = term.lowercase()
        val localMatches = if (term.isNotEmpty()) {
            (RadioStations.curated + config.snapshot())
                .filter { station ->
                    station.displayName.lowercase().contains(lowered) ||
                        station.tags.any { it.lowercase().contains(lowered) } ||
                        station.country.lowercase().contains(lowered)
                }
                .map { it.toSummary() }
        } else {
            emptyList()
        }

        // Hit Radio Browser for the long-tail. Failures here don't sink
        // the whole search — local matches still come back even if the
        // network call fails.
        val remoteMatches = when (
            val result = api.search(
                name = term.takeIf { it.isNotEmpty() },
                country = country,
                countryCode = countryCode,
                language = language,
                tag = tag,
            )
        ) {
            is FictionResult.Success -> result.value.map { it.toSummary() }
            is FictionResult.Failure -> emptyList()
        }

        // De-dupe by id so a starred Radio Browser station doesn't show
        // up twice when it also matches the live API call.
        val seen = mutableSetOf<String>()
        val combined = (localMatches + remoteMatches).filter { seen.add(it.id) }

        return FictionResult.Success(
            ListPage(items = combined, page = 1, hasNext = false),
        )
    }

    override suspend fun genres(): FictionResult<List<String>> =
        FictionResult.Success(emptyList())

    // ─── detail ────────────────────────────────────────────────────────

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        val station = resolveStation(fictionId)
            ?: return FictionResult.NotFound("Unknown radio fiction id: $fictionId")
        return FictionResult.Success(
            FictionDetail(
                summary = station.toSummary(),
                chapters = listOf(liveChapterInfo(station)),
            ),
        )
    }

    override suspend fun chapter(
        fictionId: String,
        chapterId: String,
    ): FictionResult<ChapterContent> {
        val station = resolveStation(fictionId)
            ?: return FictionResult.NotFound("Unknown radio chapter: $fictionId / $chapterId")
        if (chapterId != liveChapterIdFor(station)) {
            return FictionResult.NotFound("Unknown radio chapter: $fictionId / $chapterId")
        }
        return FictionResult.Success(
            ChapterContent(
                info = liveChapterInfo(station),
                // Issue #373 — audio chapters have empty text bodies.
                // EnginePlayer sees `audioUrl != null` and routes
                // through Media3 instead of the TTS pipeline.
                htmlBody = "",
                plainBody = "",
                audioUrl = station.streamUrl,
            ),
        )
    }

    // ─── auth-gated ────────────────────────────────────────────────────

    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    override suspend fun setFollowed(fictionId: String, followed: Boolean): FictionResult<Unit> =
        FictionResult.Success(Unit)

    // ─── helpers ───────────────────────────────────────────────────────

    /**
     * Map a `"<stationId>:live"` fictionId back to the station
     * descriptor. Looks in the curated list first, then the starred
     * imports — same order as `popular()` so id collisions resolve
     * predictably (curated wins).
     */
    internal suspend fun resolveStation(fictionId: String): RadioStation? {
        val stationId = stationIdFromFictionId(fictionId) ?: return null
        return RadioStations.byId(stationId)
            ?: config.snapshot().firstOrNull { it.id == stationId }
    }

    private fun RadioStation.toSummary(): FictionSummary =
        FictionSummary(
            id = fictionIdFor(this),
            sourceId = SourceIds.RADIO,
            title = displayName,
            // Issue #449 — using the Radio Browser API's `country`
            // field as the author label produced "by United States"
            // bylines on Library / Resume / History cards, which read
            // as broken data ("authored by a country?"). The byline
            // for a live stream maps cleanly onto "Live radio" — the
            // country still surfaces on the FictionDetail screen via
            // tags + description, so we don't lose the data, we just
            // stop pretending it's an author. Stations with a real
            // "broadcaster" / "owner" field (a separate Radio Browser
            // facet) could in theory populate this with a true byline
            // later; for v0.5.40 the conservative one-string label is
            // the right floor.
            author = "Live radio",
            description = description,
            coverUrl = null,
            tags = tags,
            // ONGOING reads correctly for a live stream — the
            // broadcast is continuously updating. COMPLETED would
            // misrepresent a perpetually-on station.
            status = FictionStatus.ONGOING,
            chapterCount = 1,
        )

    private fun liveChapterInfo(station: RadioStation): ChapterInfo =
        ChapterInfo(
            id = liveChapterIdFor(station),
            sourceChapterId = "live",
            index = 0,
            title = "Live",
        )

    companion object {
        /**
         * Build the storyvox-scoped fictionId for [station]. Format is
         * `"<station.id>:live"` — preserves the v0.5.20+ `kvmr:live`
         * shape so persisted KVMR rows resolve unchanged after the
         * :source-kvmr → :source-radio rename.
         */
        internal fun fictionIdFor(station: RadioStation): String = "${station.id}:live"

        /**
         * Build the storyvox-scoped chapterId for [station]. Format is
         * `"<station.id>:live:0"` — same v0.5.20+ shape.
         */
        internal fun liveChapterIdFor(station: RadioStation): String = "${station.id}:live:0"

        /**
         * Extract the station id from a fictionId. Returns null when
         * the id doesn't follow the `<stationId>:live` shape.
         */
        internal fun stationIdFromFictionId(fictionId: String): String? {
            val idx = fictionId.lastIndexOf(":live")
            if (idx <= 0) return null
            // Tolerate the chapterId shape `<id>:live:0` too — same prefix.
            return fictionId.substring(0, idx)
        }

        /**
         * Legacy v0.5.20+ KVMR fictionId, preserved verbatim so any
         * persisted row referencing it still resolves through the
         * renamed source. The `kvmr` station id in
         * [RadioStations.curated] is what makes this work.
         */
        const val LEGACY_KVMR_FICTION_ID: String = "kvmr:live"

        /** Legacy v0.5.20+ KVMR chapterId — same preservation rationale. */
        const val LEGACY_KVMR_CHAPTER_ID: String = "kvmr:live:0"

        const val USER_AGENT: String =
            "storyvox-radio/0.5.32 (+https://github.com/techempower-org/storyvox)"
    }
}
