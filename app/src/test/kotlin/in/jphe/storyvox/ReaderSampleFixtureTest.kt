package `in`.jphe.storyvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Layer 1 (deterministic reader seed) — pins the bundled plaintext
 * fixture and the character offsets the highlight-verification tests
 * depend on. See docs/testing/SEED.md.
 *
 * The seed runs the fixture through the #1000 TXT import path:
 * `EpubSource.textBook` HTML-escapes the body and wraps it in
 * `<pre>…</pre>`, then the chapter's `plainBody` is `stripHtml` of that
 * (tags → spaces, whitespace runs collapsed, trimmed). The reader and
 * highlight layer key on `plainBody`, so the offsets below are into the
 * post-strip string, NOT the raw file.
 *
 * This test reproduces that exact transform against the real asset bytes
 * (read off disk) so a change to the fixture OR the strip logic trips
 * here — before it silently shifts every highlight assertion downstream.
 * Pure JVM: no Android types, no Robolectric (CLAUDE.md).
 */
class ReaderSampleFixtureTest {

    /** Mirror of EpubSource.textBook's escape + <pre> wrap, then its
     *  stripHtml. Kept in lockstep with source-epub by this test's own
     *  assertions — if the production transform changes, the expected
     *  string below changes with it (and SEED.md must follow). */
    private fun displayedText(raw: String): String {
        val escaped = raw
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        val htmlBody = "<pre>$escaped</pre>"
        return htmlBody
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun fixtureBytes(): ByteArray {
        // app module dir is the JVM test working directory under Gradle.
        val f = File("src/main/assets/sample/candela-reader-sample.txt")
        assertTrue("fixture asset must exist at ${f.absolutePath}", f.exists())
        return f.readBytes()
    }

    @Test
    fun fixture_isPureAscii() {
        // ASCII-only guarantees the HTML-escape is a no-op, which keeps
        // the offsets below trivially derivable from the displayed text.
        val raw = String(fixtureBytes(), Charsets.UTF_8)
        assertTrue("fixture must be pure ASCII", raw.all { it.code in 0..127 })
    }

    @Test
    fun displayedText_isTheKnownString() {
        val raw = String(fixtureBytes(), Charsets.UTF_8)
        val expected =
            "The lamplighter walked the quiet street at dusk. " +
                "She lifted her pole to each lamp and a small flame answered. " +
                "One by one the windows of the town began to glow."
        assertEquals(expected, displayedText(raw))
        assertEquals(159, displayedText(raw).length)
    }

    @Test
    fun knownPassageOffsets_areStable() {
        val s = displayedText(String(fixtureBytes(), Charsets.UTF_8))
        // Primary highlight-verification passage.
        assertEquals(4, s.indexOf("lamplighter"))
        assertEquals(15, s.indexOf("lamplighter") + "lamplighter".length)
        // Spot-check the rest of the documented table.
        assertEquals(0, s.indexOf("The lamplighter"))
        assertEquals(27, s.indexOf("quiet street"))
        assertEquals(43, s.indexOf("dusk"))
        assertEquals(88, s.indexOf("small flame answered"))
        assertEquals(125, s.indexOf("windows of the town"))
        assertEquals(154, s.indexOf("glow"))
    }
}
