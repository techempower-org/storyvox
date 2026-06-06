package `in`.jphe.storyvox.source.epub.parse

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issues #1035 and #1021 — OPF manifest hrefs are URI references, so
 * they can be percent-encoded (`Chapter%201.xhtml`, #1035) and relative
 * with `./`/`../` segments or a leading slash (#1021). But
 * `java.util.zip` exposes `ZipEntry.name` as the literal *decoded*,
 * root-relative path. The old `joinPath` did neither decode nor segment
 * normalization, so the entry-map lookup missed and `mapIndexedNotNull`
 * silently dropped those chapters.
 *
 * These tests pin [EpubParser.resolveHref] (and its [EpubParser.percentDecode]
 * helper) — both pure Kotlin, deliberately decoupled from
 * `android.util.Xml` so the path-resolution logic is testable without
 * Robolectric. A regression in either bug reappears here before a reader
 * loses a chapter.
 */
class EpubParserResolveHrefTest {

    private companion object {
        // The bare root-level chapter leaf, reused as both an input href and
        // an expected resolved path across several cases. CH1 is the leaf;
        // OEBPS_CH1 is that leaf joined under the OPF dir. Naming both keeps
        // each assertion self-documenting and clears DeepSource KT-W1042's
        // duplicate-literal gate (the literal recurs 3+ times otherwise).
        const val CH1 = "ch1.xhtml"
        const val OEBPS_CH1 = "OEBPS/$CH1"
    }

    // ── #1035: percent-encoded hrefs ─────────────────────────────

    @Test fun `percent-encoded space resolves to the decoded entry name`() {
        // Calibre "Save to disk" emits href="Chapter%201.xhtml" while the
        // zip entry is physically "Chapter 1.xhtml".
        assertEquals("Chapter 1.xhtml", EpubParser.resolveHref("", "Chapter%201.xhtml"))
    }

    @Test fun `utf8 percent-encoded accent decodes`() {
        // "Café.xhtml" → "Caf%C3%A9.xhtml" (UTF-8 of é = C3 A9).
        assertEquals("Café.xhtml", EpubParser.resolveHref("", "Caf%C3%A9.xhtml"))
    }

    @Test fun `utf8 percent-encoded cjk decodes`() {
        // "第1章.xhtml" → each CJK char is 3 UTF-8 bytes.
        assertEquals(
            "第1章.xhtml",
            EpubParser.resolveHref("", "%E7%AC%AC1%E7%AB%A0.xhtml"),
        )
    }

    @Test fun `plus sign is preserved not turned into space`() {
        // Unlike URLDecoder, a literal '+' is a valid path char and must
        // NOT become a space (that only holds for form-url-encoding).
        assertEquals("a+b.xhtml", EpubParser.resolveHref("", "a+b.xhtml"))
    }

    @Test fun `malformed percent escape is left verbatim`() {
        // "%2G" / a trailing "%" aren't valid escapes — leave them as-is
        // rather than throwing, so a single bad href can't sink the book.
        assertEquals("a%2Gb.xhtml", EpubParser.resolveHref("", "a%2Gb.xhtml"))
        assertEquals("trailing%", EpubParser.resolveHref("", "trailing%"))
    }

    // ── #1021: relative segments + leading slash ─────────────────

    @Test fun `parent-dir segment is resolved against nested opf dir`() {
        // OPF at OEBPS/content.opf (opfDir = "OEBPS") referencing
        // ../text/ch1.xhtml must resolve to text/ch1.xhtml.
        assertEquals("text/ch1.xhtml", EpubParser.resolveHref("OEBPS", "../text/ch1.xhtml"))
    }

    @Test fun `current-dir segment collapses`() {
        assertEquals(OEBPS_CH1, EpubParser.resolveHref("OEBPS", "./ch1.xhtml"))
    }

    @Test fun `leading slash is stripped`() {
        // Zip entry names are root-relative; a leading-slash absolute
        // href must not double-prefix or keep the slash.
        assertEquals(OEBPS_CH1, EpubParser.resolveHref("OEBPS", "/OEBPS/ch1.xhtml"))
    }

    @Test fun `simple join when href is in the opf dir`() {
        assertEquals(OEBPS_CH1, EpubParser.resolveHref("OEBPS", CH1))
    }

    @Test fun `empty opf dir leaves a root-level href untouched`() {
        assertEquals(CH1, EpubParser.resolveHref("", CH1))
    }

    @Test fun `nested opf dir with deep parent traversal`() {
        // opfDir two levels deep, href climbs back up to a sibling tree.
        assertEquals(
            "images/cover.xhtml",
            EpubParser.resolveHref("OEBPS/xhtml", "../../images/cover.xhtml"),
        )
    }

    @Test fun `parent traversal past root is clamped not escaped`() {
        // A pathological "../../.." can't produce a leading "../" — zip
        // names are root-relative, so we clamp at root.
        assertEquals(CH1, EpubParser.resolveHref("OEBPS", "../../../ch1.xhtml"))
    }

    // ── #1035 + #1021 combined: encoded AND relative ─────────────

    @Test fun `percent-encoded and parent-relative href resolves both`() {
        // The case a single normalize-href step must close: decode the
        // %20 AND resolve the ../ in one pass.
        assertEquals(
            "text/Chapter 1.xhtml",
            EpubParser.resolveHref("OEBPS", "../text/Chapter%201.xhtml"),
        )
    }

    // ── percentDecode unit coverage ──────────────────────────────

    @Test fun `percentDecode short-circuits plain strings`() {
        assertEquals("plain.xhtml", EpubParser.percentDecode("plain.xhtml"))
    }

    @Test fun `percentDecode handles consecutive escapes`() {
        assertEquals("a b c", EpubParser.percentDecode("a%20b%20c"))
    }
}
