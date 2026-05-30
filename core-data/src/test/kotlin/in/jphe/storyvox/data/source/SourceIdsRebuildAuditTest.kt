package `in`.jphe.storyvox.data.source

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #989 — pins the audit of which sources have an id that ISN'T
 * enough to rebuild the fiction without the original URL. Only the
 * hash-id sources (Readability/RSS/EPUB) qualify; every self-describing
 * id source must NOT, or we'd bloat the synced payload (and persist
 * URLs) for fictions that don't need them.
 */
class SourceIdsRebuildAuditTest {

    @Test fun `hash-id sources need the source URL to rebuild`() {
        assertTrue(SourceIds.idNeedsSourceUrlToRebuild(SourceIds.READABILITY))
        assertTrue(SourceIds.idNeedsSourceUrlToRebuild(SourceIds.RSS))
        assertTrue(SourceIds.idNeedsSourceUrlToRebuild(SourceIds.EPUB))
    }

    @Test fun `self-describing-id sources do not need a source URL`() {
        // A representative spread of the "id encodes everything to
        // re-fetch" sources — these rebuild from the id alone via
        // refreshDetail, so carrying a URL would be dead weight.
        val selfDescribing = listOf(
            SourceIds.ROYAL_ROAD, SourceIds.GUTENBERG, SourceIds.AO3,
            SourceIds.WIKIPEDIA, SourceIds.WIKISOURCE, SourceIds.GITHUB,
            SourceIds.OUTLINE, SourceIds.NOTION_TECHEMPOWER, SourceIds.NOTION_PAT,
            SourceIds.ARXIV, SourceIds.PLOS, SourceIds.HACKERNEWS,
            SourceIds.DISCORD, SourceIds.MATRIX, SourceIds.TELEGRAM,
            SourceIds.SLACK, SourceIds.PALACE, SourceIds.RADIO,
            SourceIds.STANDARD_EBOOKS, SourceIds.MEMPALACE,
        )
        for (id in selfDescribing) {
            assertFalse(
                "$id encodes a re-fetchable id and must not require a source URL",
                SourceIds.idNeedsSourceUrlToRebuild(id),
            )
        }
    }
}
