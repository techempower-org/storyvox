package `in`.jphe.storyvox.source.ao3.net

import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.source.ao3.parser.Ao3WorksIndexParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #381 — minimal client for [Archive of Our Own](https://archiveofourown.org/).
 *
 * Surfaces:
 *
 *  1. **Per-tag Atom feeds** (anonymous) — `/tags/<tag>/feed.atom`.
 *     See [tagFeed].
 *  2. **Per-work EPUB download** (anonymous + authed) — `/downloads/<id>/…`.
 *     See [downloadEpub]. Authed when the user has signed in via the
 *     #426 PR2 WebView flow; the authed client's cookie jar attaches
 *     `_otwarchive_session` so Archive-Warning-gated works return
 *     real EPUB bytes instead of the "you must agree" interstitial.
 *  3. **Per-user works indexes** (authed; #426 PR2) — `/users/<u>/subscriptions`
 *     and `/users/<u>/readings?show=marked`. Both pages are HTML
 *     index lists parsed via [Ao3WorksIndexParser]. AO3 has no
 *     JSON / Atom variant for these surfaces; the HTML shape is
 *     stable and well-defined.
 *
 * The `@Ao3Http` client is anonymous (no cookie jar); the
 * `@Ao3AuthedHttp` client carries the [Ao3CookieJar] so subscription /
 * Marked-for-Later requests attach the authed session. Splitting
 * the clients (rather than always using the authed one) avoids
 * attaching the user's session cookie to anonymous catalog
 * requests — keeps the cookie usage minimal and reduces server-side
 * log noise.
 *
 * Identifies politely on every request — non-commercial, free, with a
 * contact URL in the User-Agent so OTW Ops can route any concerns to a
 * real address rather than blocking the whole CIDR.
 */
@Singleton
internal class Ao3Api @Inject constructor(
    @`in`.jphe.storyvox.source.ao3.di.Ao3Http private val client: OkHttpClient,
    @`in`.jphe.storyvox.source.ao3.di.Ao3AuthedHttp private val authedClient: OkHttpClient,
) {
    /**
     * `GET /tags/<tagId>/feed.atom` — the listing surface. Returns
     * the raw XML body for [Ao3AtomFeed.parse] to turn into typed
     * entries. AO3 paginates this feed via a `?page=N` query param;
     * the feed itself is fixed at ~20 entries per page.
     *
     * #408 — the URL now keys on AO3's internal numeric tag id, not
     * the slug. AO3 dropped the slug→numeric redirect that used to
     * back `GET /tags/<slug>/feed.atom`; that URL now returns 404
     * outright. The numeric form has always worked and returns
     * byte-identical Atom XML, so this is the minimum-surface fix.
     * No slug encoding is needed — numeric ids are URL-safe by
     * construction. See [Ao3Source.FANDOM_TAGS] for the resolved
     * (name, id) pairs the [Ao3Source] passes in.
     */
    suspend fun tagFeed(tagId: Long, page: Int = 1): FictionResult<Ao3AtomFeed> {
        val path = tagFeedPath(tagId, page)
        return withContext(Dispatchers.IO) {
            requestText(client, path).let { res ->
                when (res) {
                    is FictionResult.Success -> {
                        try {
                            FictionResult.Success(Ao3AtomFeed.parse(res.value))
                        } catch (e: Exception) {
                            FictionResult.NetworkError(
                                "AO3 Atom feed for tag id $tagId unparseable: ${e.message}",
                                e,
                            )
                        }
                    }
                    is FictionResult.Failure -> res
                }
            }
        }
    }

    /**
     * `GET /users/<username>/subscriptions?page=N` — the user's
     * subscribed-works index (#426 PR2). Returns a parsed
     * [ListPage] of [FictionSummary] cards. AO3 surfaces work
     * subscriptions alongside other subscription types (users,
     * series); we filter to `<li class="work blurb">` via the
     * parser so series / user subscriptions don't leak in as
     * "fictions".
     *
     * Requires the authed client (cookie jar carries
     * `_otwarchive_session`). Anonymous requests would 302 to
     * `/users/login`; the parser would return an empty list. The
     * caller (Ao3Source) is responsible for short-circuiting to
     * `AuthRequired` when no session is captured.
     */
    suspend fun subscriptions(username: String, page: Int = 1): FictionResult<ListPage<FictionSummary>> {
        val path = subscriptionsPath(username, page)
        return withContext(Dispatchers.IO) {
            when (val res = requestText(authedClient, path)) {
                is FictionResult.Success -> {
                    if (looksLikeLogin(res.value)) {
                        FictionResult.AuthRequired(
                            "AO3 returned the login form for $username's subscriptions — session expired?",
                        )
                    } else {
                        try {
                            FictionResult.Success(Ao3WorksIndexParser.parse(res.value, page))
                        } catch (e: Exception) {
                            FictionResult.NetworkError(
                                "AO3 subscriptions for $username unparseable: ${e.message}",
                                e,
                            )
                        }
                    }
                }
                is FictionResult.Failure -> res
            }
        }
    }

    /**
     * `GET /users/<username>/readings?show=marked&page=N` — the user's
     * Marked-for-Later index (#426 PR2). Same HTML shape as the
     * subscriptions page; routes through the same parser.
     *
     * AO3 only shows Marked-for-Later entries when the user has
     * "History" enabled in their preferences. When the user has
     * disabled history, AO3 surfaces a notice with no `<li class="work
     * blurb">` cards; the parser returns an empty [ListPage] and the
     * UI renders an empty state.
     */
    suspend fun markedForLater(username: String, page: Int = 1): FictionResult<ListPage<FictionSummary>> {
        val path = markedForLaterPath(username, page)
        return withContext(Dispatchers.IO) {
            when (val res = requestText(authedClient, path)) {
                is FictionResult.Success -> {
                    if (looksLikeLogin(res.value)) {
                        FictionResult.AuthRequired(
                            "AO3 returned the login form for $username's Marked-for-Later — session expired?",
                        )
                    } else {
                        try {
                            FictionResult.Success(Ao3WorksIndexParser.parse(res.value, page))
                        } catch (e: Exception) {
                            FictionResult.NetworkError(
                                "AO3 Marked-for-Later for $username unparseable: ${e.message}",
                                e,
                            )
                        }
                    }
                }
                is FictionResult.Failure -> res
            }
        }
    }

    /**
     * Direct EPUB download from `/downloads/<work_id>/<slug>.epub`.
     * The slug is a sanitized title — anything reasonably URL-safe
     * works since AO3 redirects to the canonical filename based on
     * `work_id` alone. We pass `"storyvox.epub"` to keep the URL
     * short and identifiable in network logs.
     *
     * Returns the raw bytes for [EpubParser][in.jphe.storyvox.source.epub.parse.EpubParser]
     * to consume. PR2 of #426 — the authed client is used here too
     * so that "Archive-Warning: Choose Not to Use Warnings" works
     * download correctly once the user has signed in.
     */
    suspend fun downloadEpub(workId: Long): FictionResult<ByteArray> = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/downloads/$workId/storyvox.epub"
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/epub+zip")
                .get()
                .build()
            authedClient.newCall(req).execute().use { resp ->
                val ctype = resp.header("Content-Type").orEmpty()
                when {
                    resp.code == 404 -> FictionResult.NotFound("EPUB not available for work $workId")
                    resp.code == 401 || resp.code == 403 ->
                        FictionResult.AuthRequired(
                            "AO3 sign-in required for work $workId (likely Archive-Warning gated)",
                        )
                    !resp.isSuccessful -> FictionResult.NetworkError(
                        "HTTP ${resp.code} downloading AO3 EPUB",
                        IOException("HTTP ${resp.code}"),
                    )
                    // Archive-Warning-gated works return 200 with the
                    // HTML "You must agree" interstitial instead of a
                    // real EPUB. Detect by Content-Type and surface as
                    // AuthRequired so the UI can show the right copy
                    // (sign-in is the follow-up that will resolve it).
                    !ctype.contains("epub", ignoreCase = true) &&
                        !ctype.contains("octet-stream", ignoreCase = true) &&
                        !ctype.contains("zip", ignoreCase = true) ->
                        FictionResult.AuthRequired(
                            "AO3 returned non-EPUB ($ctype) for work $workId — likely Archive-Warning gated; sign-in is a follow-up.",
                        )
                    else -> {
                        val bytes = resp.body?.bytes()
                            ?: return@withContext FictionResult.NetworkError(
                                "empty AO3 EPUB body",
                                IOException("empty body"),
                            )
                        FictionResult.Success(bytes)
                    }
                }
            }
        } catch (e: IOException) {
            FictionResult.NetworkError(e.message ?: "AO3 EPUB download failed", e)
        }
    }

    /**
     * `GET /works/search?work_search[query]=<term>&page=<N>` — AO3's
     * full-text work search. Returns an HTML listing page with the
     * same `<li class="work blurb">` card shape that subscriptions
     * and Marked-for-Later use, so the existing [Ao3WorksIndexParser]
     * handles the body directly.
     *
     * Anonymous — no auth cookie needed. AO3's search is available to
     * logged-out users; the results may exclude Archive-Warning-gated
     * works but that's AO3's standard anonymous behavior.
     *
     * Filter params mirror AO3's `/works/search` form:
     *  - [ratingIds] → `work_search[rating_ids][]` (10/11/12/13)
     *  - [complete] → `work_search[complete]` ("T"|"F", omit for both)
     *  - [fandom] → `work_search[fandom_names]`
     *  - [warningIds] → `work_search[archive_warning_ids][]`
     *  - [excludedTagNames] → `work_search[excluded_tag_names]` (comma-joined)
     *  - [sortColumn] → `work_search[sort_column]` ("kudos_count"|"revised_at"|null)
     */
    suspend fun searchWorks(
        query: String,
        page: Int = 1,
        ratingIds: Set<Int> = emptySet(),
        complete: Boolean? = null,
        fandom: String? = null,
        warningIds: Set<Int> = emptySet(),
        excludedTagNames: Set<String> = emptySet(),
        sortColumn: String? = null,
    ): FictionResult<ListPage<FictionSummary>> {
        val path = searchPath(
            query = query,
            page = page,
            ratingIds = ratingIds,
            complete = complete,
            fandom = fandom,
            warningIds = warningIds,
            excludedTagNames = excludedTagNames,
            sortColumn = sortColumn,
        )
        return withContext(Dispatchers.IO) {
            when (val res = requestText(client, path)) {
                is FictionResult.Success -> {
                    try {
                        FictionResult.Success(Ao3WorksIndexParser.parse(res.value, page))
                    } catch (e: Exception) {
                        FictionResult.NetworkError(
                            "AO3 search results unparseable: ${e.message}",
                            e,
                        )
                    }
                }
                is FictionResult.Failure -> res
            }
        }
    }

    /** Generic text-body GET against the supplied client. Sync OkHttp
     *  `execute()` wrapped in `withContext(Dispatchers.IO)` for the
     *  same reason as the Gutenberg client — `suspend` alone doesn't
     *  hop off the main thread; without this wrapper every fetch from
     *  the UI thread crashes with `NetworkOnMainThreadException`. */
    private suspend fun requestText(
        client: OkHttpClient,
        path: String,
    ): FictionResult<String> = withContext(Dispatchers.IO) {
        val url = BASE_URL + path
        try {
            val req = Request.Builder()
                .url(url)
                .header("Accept", "text/html, application/atom+xml, application/xml;q=0.9, */*;q=0.5")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                when {
                    resp.code == 404 -> FictionResult.NotFound("AO3: $path not found")
                    !resp.isSuccessful -> FictionResult.NetworkError(
                        "HTTP ${resp.code} from $url",
                        IOException("HTTP ${resp.code}"),
                    )
                    else -> {
                        val text = resp.body?.string()
                            ?: return@withContext FictionResult.NetworkError(
                                "empty body",
                                IOException("empty body"),
                            )
                        FictionResult.Success(text)
                    }
                }
            }
        } catch (e: IOException) {
            FictionResult.NetworkError(e.message ?: "fetch failed", e)
        }
    }

    companion object {
        const val BASE_URL = "https://archiveofourown.org"

        /**
         * Build the per-tag Atom feed path for `tagId` at `page`.
         * Exposed package-private so the unit tests can pin the URL
         * shape without exercising the OkHttp client. See
         * [tagFeed] for the call site.
         */
        internal fun tagFeedPath(tagId: Long, page: Int = 1): String =
            if (page <= 1) {
                "/tags/$tagId/feed.atom"
            } else {
                "/tags/$tagId/feed.atom?page=$page"
            }

        /**
         * `/users/<username>/subscriptions[?page=N]` — the authed
         * subscriptions index. Exposed package-private so the unit
         * tests can pin the URL shape without exercising the OkHttp
         * client. PR2 of #426.
         */
        internal fun subscriptionsPath(username: String, page: Int = 1): String =
            if (page <= 1) {
                "/users/$username/subscriptions"
            } else {
                "/users/$username/subscriptions?page=$page"
            }

        /**
         * `/users/<username>/readings?show=marked[&page=N]` — the
         * authed Marked-for-Later index. Exposed package-private for
         * the same URL-shape pinning rationale. PR2 of #426.
         */
        internal fun markedForLaterPath(username: String, page: Int = 1): String =
            if (page <= 1) {
                "/users/$username/readings?show=marked"
            } else {
                "/users/$username/readings?show=marked&page=$page"
            }

        /**
         * Identifies storyvox in the User-Agent. AO3's Terms of Service
         * draw a sharp line between commercial and non-commercial
         * automated access; storyvox is free, open-source, and uses
         * only official endpoints (Atom feeds + EPUB downloads + per-
         * user index pages once signed in), so we surface the contact
         * URL up front. If OTW Ops ever wants to reach out, the project's
         * GitHub Issues are the door.
         */
        /**
         * `/works/search?work_search[...]=...[&page=N]` — AO3's work
         * search URL builder. Exposed package-private for URL-shape
         * pinning in unit tests. Params are emitted in a deterministic
         * order (query → ratings → complete → fandom → warnings →
         * excluded tags → sort → page) so the test assertions can pin
         * exact strings without flake.
         *
         * Empty / null filter values are skipped — passing no filters
         * round-trips to the original term-only URL.
         */
        internal fun searchPath(
            query: String,
            page: Int = 1,
            ratingIds: Set<Int> = emptySet(),
            complete: Boolean? = null,
            fandom: String? = null,
            warningIds: Set<Int> = emptySet(),
            excludedTagNames: Set<String> = emptySet(),
            sortColumn: String? = null,
        ): String {
            val params = mutableListOf<Pair<String, String>>()
            params += "work_search[query]" to query
            // AO3 accepts repeated `rating_ids[]` params for OR semantics;
            // emit in sorted order so the URL is deterministic.
            for (id in ratingIds.toSortedSet()) {
                params += "work_search[rating_ids][]" to id.toString()
            }
            if (complete != null) {
                params += "work_search[complete]" to if (complete) "T" else "F"
            }
            fandom?.takeIf { it.isNotBlank() }?.let {
                params += "work_search[fandom_names]" to it
            }
            for (id in warningIds.toSortedSet()) {
                params += "work_search[archive_warning_ids][]" to id.toString()
            }
            if (excludedTagNames.isNotEmpty()) {
                params += "work_search[excluded_tag_names]" to
                    excludedTagNames.toSortedSet().joinToString(",")
            }
            sortColumn?.takeIf { it.isNotBlank() }?.let {
                params += "work_search[sort_column]" to it
            }
            if (page > 1) {
                params += "page" to page.toString()
            }
            val qs = params.joinToString("&") { (k, v) ->
                java.net.URLEncoder.encode(k, "UTF-8") + "=" +
                    java.net.URLEncoder.encode(v, "UTF-8")
            }
            return "/works/search?$qs"
        }

        const val USER_AGENT = "storyvox-ao3/1.0 (+https://github.com/jphein/storyvox)"

        /**
         * Cheap signal that AO3 returned the login form instead of
         * the requested user-index page. AO3 doesn't 401/403 on an
         * expired session — it 302s to `/users/login`, which OkHttp
         * follows transparently, leaving us with a 200 + login HTML.
         * Detect the form's distinctive class so we can surface
         * `AuthRequired` instead of silently rendering empty.
         */
        internal fun looksLikeLogin(body: String): Boolean =
            body.contains("id=\"new_user\"") ||
                body.contains("name=\"user[login]\"") ||
                body.contains("class=\"new_user\"")
    }
}
