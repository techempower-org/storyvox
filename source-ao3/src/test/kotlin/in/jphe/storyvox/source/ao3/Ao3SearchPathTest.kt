package `in`.jphe.storyvox.source.ao3

import `in`.jphe.storyvox.source.ao3.net.Ao3Api
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pin the AO3 `/works/search` URL shape across the filter axes
 * declared in [Ao3Source.filterDimensions] — sort, category (fandom),
 * rating, completion, archive warnings, and excluded tags. The bug
 * these tests guard against is the regression where filter state was
 * silently dropped on the way to the URL builder (rating/completion
 * declared in the UI but never wired through; warnings/category/sort
 * present in [Ao3Source.applyFilters] but never read by [Ao3Source.search]).
 *
 * Pinning the URL shape directly (rather than asserting against
 * [Ao3Source.search] end-to-end with a fake API) keeps the test
 * fast and makes any future URL-encoding tweak surface as a single
 * loud red line.
 */
class Ao3SearchPathTest {

    @Test
    fun `searchPath term-only matches the legacy shape`() {
        // No filters → just the query param. The page-1 case stays
        // page-less in the URL (AO3 accepts page=1 explicitly but the
        // legacy URL didn't include it and we keep that shape).
        val path = Ao3Api.searchPath(query = "marvel")
        assertEquals("/works/search?work_search%5Bquery%5D=marvel", path)
    }

    @Test
    fun `searchPath appends page query for paginated requests`() {
        val path = Ao3Api.searchPath(query = "marvel", page = 3)
        assertTrue("must carry page=3", path.contains("&page=3"))
        assertTrue(
            "page must be last in the param order",
            path.endsWith("&page=3"),
        )
    }

    @Test
    fun `searchPath encodes spaces and special chars in the query`() {
        val path = Ao3Api.searchPath(query = "hello world & goodnight")
        // Spaces → '+' (URLEncoder default), '&' → '%26'.
        assertTrue("must encode space as '+'", path.contains("hello+world"))
        assertTrue("must encode literal '&' in the value", path.contains("%26"))
        // The structural '&' between params is still a literal '&'.
        // Term-only path has no second param, so this URL has no
        // structural '&' at all.
        assertFalse("term-only path must not have an unencoded '&'", path.contains("&"))
    }

    @Test
    fun `searchPath emits rating_ids params in sorted order`() {
        val path = Ao3Api.searchPath(
            query = "marvel",
            ratingIds = setOf(13, 10),
        )
        // %5B = '[', %5D = ']'. Sorted: 10 then 13.
        val ratingParam = "work_search%5Brating_ids%5D%5B%5D"
        val tenIdx = path.indexOf("$ratingParam=10")
        val thirteenIdx = path.indexOf("$ratingParam=13")
        assertTrue("rating_ids[]=10 must be present", tenIdx > 0)
        assertTrue("rating_ids[]=13 must be present", thirteenIdx > 0)
        assertTrue("10 must precede 13 in the URL", tenIdx < thirteenIdx)
    }

    @Test
    fun `searchPath emits complete=T for complete works`() {
        val path = Ao3Api.searchPath(query = "marvel", complete = true)
        assertTrue(
            "must include complete=T",
            path.contains("work_search%5Bcomplete%5D=T"),
        )
    }

    @Test
    fun `searchPath emits complete=F for in-progress works`() {
        val path = Ao3Api.searchPath(query = "marvel", complete = false)
        assertTrue(
            "must include complete=F",
            path.contains("work_search%5Bcomplete%5D=F"),
        )
    }

    @Test
    fun `searchPath omits complete when null`() {
        val path = Ao3Api.searchPath(query = "marvel", complete = null)
        assertFalse(
            "must not include complete when null",
            path.contains("work_search%5Bcomplete%5D"),
        )
    }

    @Test
    fun `searchPath emits fandom_names param`() {
        val path = Ao3Api.searchPath(
            query = "",
            fandom = "Harry Potter",
        )
        // 'Harry Potter' encodes as 'Harry+Potter'.
        assertTrue(
            "must include fandom_names=Harry+Potter",
            path.contains("work_search%5Bfandom_names%5D=Harry+Potter"),
        )
    }

    @Test
    fun `searchPath omits blank fandom`() {
        val path = Ao3Api.searchPath(query = "marvel", fandom = "  ")
        assertFalse(
            "blank fandom must be omitted",
            path.contains("fandom_names"),
        )
    }

    @Test
    fun `searchPath emits archive_warning_ids params in sorted order`() {
        val path = Ao3Api.searchPath(
            query = "marvel",
            warningIds = setOf(20, 17, 18),
        )
        val warningParam = "work_search%5Barchive_warning_ids%5D%5B%5D"
        val seventeenIdx = path.indexOf("$warningParam=17")
        val eighteenIdx = path.indexOf("$warningParam=18")
        val twentyIdx = path.indexOf("$warningParam=20")
        assertTrue("warning 17 must be present", seventeenIdx > 0)
        assertTrue("warning 18 must be present", eighteenIdx > 0)
        assertTrue("warning 20 must be present", twentyIdx > 0)
        assertTrue("17 < 18", seventeenIdx < eighteenIdx)
        assertTrue("18 < 20", eighteenIdx < twentyIdx)
    }

    @Test
    fun `searchPath emits excluded_tag_names as comma-joined list`() {
        val path = Ao3Api.searchPath(
            query = "marvel",
            excludedTagNames = setOf("Underage", "Major Character Death"),
        )
        // Sorted alphabetically, comma-joined, then URL-encoded
        // (comma → %2C, space → +).
        assertTrue(
            "must include excluded_tag_names with comma-joined value",
            path.contains(
                "work_search%5Bexcluded_tag_names%5D=" +
                    "Major+Character+Death%2CUnderage",
            ),
        )
    }

    @Test
    fun `searchPath emits sort_column for popularity`() {
        val path = Ao3Api.searchPath(query = "marvel", sortColumn = "kudos_count")
        assertTrue(
            "must include sort_column=kudos_count",
            path.contains("work_search%5Bsort_column%5D=kudos_count"),
        )
    }

    @Test
    fun `searchPath emits sort_column for newest`() {
        val path = Ao3Api.searchPath(query = "marvel", sortColumn = "revised_at")
        assertTrue(
            "must include sort_column=revised_at",
            path.contains("work_search%5Bsort_column%5D=revised_at"),
        )
    }

    @Test
    fun `searchPath stacks every filter axis into a single URL`() {
        // The integration shape — every dimension declared in
        // filterDimensions wired in at once. Verifies the order is
        // deterministic and nothing collides.
        val path = Ao3Api.searchPath(
            query = "marvel",
            page = 2,
            ratingIds = setOf(11, 12),
            complete = true,
            fandom = "Marvel Cinematic Universe",
            warningIds = setOf(17),
            excludedTagNames = setOf("Underage"),
            sortColumn = "kudos_count",
        )
        // The expected param order: query, ratings (sorted),
        // complete, fandom, warnings (sorted), excluded, sort, page.
        val expected = "/works/search?" +
            "work_search%5Bquery%5D=marvel" +
            "&work_search%5Brating_ids%5D%5B%5D=11" +
            "&work_search%5Brating_ids%5D%5B%5D=12" +
            "&work_search%5Bcomplete%5D=T" +
            "&work_search%5Bfandom_names%5D=Marvel+Cinematic+Universe" +
            "&work_search%5Barchive_warning_ids%5D%5B%5D=17" +
            "&work_search%5Bexcluded_tag_names%5D=Underage" +
            "&work_search%5Bsort_column%5D=kudos_count" +
            "&page=2"
        assertEquals(expected, path)
    }

    @Test
    fun `searchPath drops empty page parameter on page 1`() {
        // page=1 isn't useful in the URL — AO3 treats path with no
        // page param as page 1.
        val path = Ao3Api.searchPath(query = "marvel", page = 1)
        assertFalse("page=1 must not appear", path.contains("page="))
    }

    // ─── rating + warning ID mappings ──────────────────────────────────

    @Test
    fun `ratingIdFor maps AO3 rating labels to AO3 form ids`() {
        assertEquals(10, Ao3Source.ratingIdFor("General"))
        assertEquals(11, Ao3Source.ratingIdFor("Teen"))
        assertEquals(12, Ao3Source.ratingIdFor("Mature"))
        assertEquals(13, Ao3Source.ratingIdFor("Explicit"))
        assertEquals(null, Ao3Source.ratingIdFor("Unknown"))
    }

    @Test
    fun `warningIdFor maps the four canonical archive warnings`() {
        assertEquals(17, Ao3Source.warningIdFor("Graphic Depictions Of Violence"))
        assertEquals(18, Ao3Source.warningIdFor("Major Character Death"))
        assertEquals(19, Ao3Source.warningIdFor("Underage"))
        assertEquals(20, Ao3Source.warningIdFor("Rape/Non-Con"))
        assertEquals(null, Ao3Source.warningIdFor("Something Else"))
    }
}
