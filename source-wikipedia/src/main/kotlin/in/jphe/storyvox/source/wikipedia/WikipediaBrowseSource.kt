package `in`.jphe.storyvox.source.wikipedia

import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage

/**
 * Wikipedia-specific browse surfaces exposed across the module
 * boundary (#796).
 *
 * Mirrors [`Ao3AuthedSource`][in.jphe.storyvox.source.ao3.Ao3AuthedSource]
 * and [`GitHubAuthedSource`][in.jphe.storyvox.source.github.GitHubAuthedSource]
 * — the base [`in`.jphe.storyvox.data.source.FictionSource] surface
 * covers source-agnostic concerns (Popular / NewReleases / Search),
 * but the "On This Day" and "In the News" clusters are Wikipedia-only
 * shapes that don't map onto any of those tabs. Exposing them through
 * a dedicated interface lets the app-module Browse adapter wire each
 * chip-strip tab to the right cluster without depending on
 * `WikipediaSource`'s internal implementation type.
 *
 * Neither surface is auth-gated — both read from the same public
 * `feed/featured` payload the Popular tab already consumes — but the
 * cluster-to-tab routing still doesn't fit the `popular`/`latestUpdates`
 * contract, hence the side-interface.
 *
 * Hilt binds [WikipediaSource] to this interface in
 * [`in`.jphe.storyvox.source.wikipedia.di.WikipediaBindings].
 */
interface WikipediaBrowseSource {

    /**
     * "On This Day" — curated events that happened on this calendar
     * day in history, flattened to one summary per event (the most
     * prominent referenced article, with the event year + text as the
     * description). Single-page: [page] > 1 returns an empty
     * [ListPage]. The `onthisday` cluster is present on every date, so
     * this fetches the same "yesterday UTC" date as the Popular tab for
     * consistency.
     */
    suspend fun onThisDay(page: Int): FictionResult<ListPage<FictionSummary>>

    /**
     * "In the News" — the current-events right-rail from Wikipedia's
     * homepage, one summary per story (the most prominent linked
     * article, with the story HTML stripped to plain text as the
     * description). Single-page: [page] > 1 returns an empty
     * [ListPage]. The `news` cluster only appears on the **current**
     * UTC day, so this fetches today (UTC) rather than yesterday.
     */
    suspend fun inTheNews(page: Int): FictionResult<ListPage<FictionSummary>>
}
