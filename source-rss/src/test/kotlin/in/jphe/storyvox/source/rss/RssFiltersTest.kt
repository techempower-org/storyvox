package `in`.jphe.storyvox.source.rss

import `in`.jphe.storyvox.data.source.filter.FilterDimension
import `in`.jphe.storyvox.data.source.filter.FilterState
import `in`.jphe.storyvox.data.source.filter.FilterValue
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.SearchOrder
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.source.rss.config.RssConfig
import `in`.jphe.storyvox.source.rss.config.RssSubscription
import `in`.jphe.storyvox.source.rss.net.RssFetcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * RSS filter wiring — sort axis honored in [RssSource.search], the
 * decorative DateRange dimension dropped from [RssSource.filterDimensions].
 *
 * Background: RSS had a Sort + DateRange dimension declared but only
 * partially wired. `search()` ignored the `orderBy`; DateRange was
 * never read at all, lying to users who picked it. The fix wires Sort
 * and drops DateRange — RSS summary cards aren't hydrated with dates
 * (those need a per-feed network fetch, which the source intentionally
 * defers — see RssSource.listSubscriptions for the rationale).
 */
class RssFiltersTest {

    private fun fakeConfig(subs: List<RssSubscription>) = object : RssConfig {
        private val state = MutableStateFlow(subs)
        override val subscriptions: Flow<List<RssSubscription>> = state.asStateFlow()
        override suspend fun snapshot(): List<RssSubscription> = state.value
        override suspend fun addFeed(url: String) {
            state.value = state.value + RssSubscription(
                fictionId = "rss:${url.hashCode()}",
                url = url,
            )
        }
        override suspend fun removeFeed(fictionId: String) {
            state.value = state.value.filterNot { it.fictionId == fictionId }
        }
    }

    private fun source(subs: List<RssSubscription>): RssSource =
        RssSource(fakeConfig(subs), RssFetcher(OkHttpClient()))

    private fun sub(url: String) = RssSubscription(
        url = url,
        fictionId = "rss:${url.hashCode()}",
    )

    @Test fun `filterDimensions exposes Sort only (no decorative DateRange)`() {
        val dims = source(emptyList()).filterDimensions()
        // Pre-fix this returned [Sort, DateRange]. DateRange was never
        // read — drop it rather than lie to the user.
        assertEquals(1, dims.size)
        val sort = dims.first() as FilterDimension.Sort
        // "last_update" was the third option pre-fix; it's gone now
        // because the un-hydrated summary cards have no date to sort by.
        val optionIds = sort.options.map { it.id }.toSet()
        assertEquals(setOf("relevance", "title"), optionIds)
    }

    @Test fun `applyFilters maps title sort to SearchOrder TITLE`() {
        val q = source(emptyList()).applyFilters(
            base = SearchQuery(),
            state = FilterState(
                values = mapOf("sort" to FilterValue.StringVal("title")),
            ),
        )
        assertEquals(SearchOrder.TITLE, q.orderBy)
    }

    @Test fun `applyFilters maps default sort to RELEVANCE`() {
        val q = source(emptyList()).applyFilters(
            base = SearchQuery(),
            state = FilterState(
                values = mapOf("sort" to FilterValue.StringVal("relevance")),
            ),
        )
        assertEquals(SearchOrder.RELEVANCE, q.orderBy)
    }

    @Test fun `search with TITLE order sorts subscriptions alphabetically`() = runTest {
        // Reversed input order so the sort actually has to do work.
        val subs = listOf(
            sub("https://zebra.example.com/feed"),
            sub("https://apple.example.com/feed"),
            sub("https://mango.example.com/feed"),
        )
        val result = source(subs).search(
            SearchQuery(orderBy = SearchOrder.TITLE),
        ) as FictionResult.Success
        val titles = result.value.items.map { it.title }
        assertEquals(
            listOf("apple.example.com", "mango.example.com", "zebra.example.com"),
            titles,
        )
    }

    @Test fun `search with default (RELEVANCE) order preserves config snapshot order`() = runTest {
        // RELEVANCE on the un-hydrated summary list is a no-op — the
        // user's configured subscription order survives.
        val subs = listOf(
            sub("https://zebra.example.com/feed"),
            sub("https://apple.example.com/feed"),
        )
        val result = source(subs).search(SearchQuery()) as FictionResult.Success
        assertEquals(
            listOf("zebra.example.com", "apple.example.com"),
            result.value.items.map { it.title },
        )
    }

    @Test fun `search applies term filter and sort together`() = runTest {
        val subs = listOf(
            sub("https://zebra.example.com/feed"),
            sub("https://news.example.com/feed"),
            sub("https://apple.example.com/feed"),
        )
        // Term "example" matches all three — sort is what surfaces.
        val result = source(subs).search(
            SearchQuery(term = "example", orderBy = SearchOrder.TITLE),
        ) as FictionResult.Success
        assertEquals(
            listOf("apple.example.com", "news.example.com", "zebra.example.com"),
            result.value.items.map { it.title },
        )
    }

    @Test fun `search with empty term and no filters surfaces every subscription`() = runTest {
        val subs = listOf(sub("https://a.example.com/feed"), sub("https://b.example.com/feed"))
        val result = source(subs).search(SearchQuery()) as FictionResult.Success
        // Pre-fix `search` short-circuited to listSubscriptions on
        // empty term — same observable shape; just confirm the
        // contract didn't regress.
        assertEquals(2, result.value.items.size)
    }

    @Test fun `search with non-matching term returns empty list`() = runTest {
        val subs = listOf(sub("https://example.com/feed"))
        val result = source(subs).search(
            SearchQuery(term = "nonexistent"),
        ) as FictionResult.Success
        assertTrue(result.value.items.isEmpty())
    }

    @Test fun `unknown sort values fall back to RELEVANCE (snapshot order)`() {
        // Defensive — a future caller passing an arbitrary string
        // (or a stale FilterState from a removed option) must not
        // crash; the resolver falls through to RELEVANCE.
        val q = source(emptyList()).applyFilters(
            base = SearchQuery(),
            state = FilterState(
                values = mapOf("sort" to FilterValue.StringVal("nonsense_value")),
            ),
        )
        assertEquals(SearchOrder.RELEVANCE, q.orderBy)
    }

    @Test fun `last_update sort id is no longer accepted (decorative date removed)`() {
        // The "last_update" option used to live in the Sort enum but
        // there was no date on summary cards to honor it — UI showed
        // the chip, sort did nothing. Pin that it's gone, so a
        // resurrected option needs a deliberate code change.
        val sort = source(emptyList()).filterDimensions().first() as FilterDimension.Sort
        assertFalse(
            "last_update sort must not reappear without re-implementation",
            sort.options.any { it.id == "last_update" },
        )
    }
}
