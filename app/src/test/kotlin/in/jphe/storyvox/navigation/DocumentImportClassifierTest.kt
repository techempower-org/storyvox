package `in`.jphe.storyvox.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #1000 — the (mime, filename) → [ImportKind] mapping is the
 * load-bearing decision in the "Open With" path: it decides whether an
 * inbound document is opened as an EPUB, read as plaintext, deferred to
 * the PDF path (#996), or declined. Pure (no Android types), so it runs
 * as a plain JUnit test — no Robolectric.
 */
class DocumentImportClassifierTest {

    // ── EPUB ────────────────────────────────────────────────────────

    @Test
    fun epubMime_classifiesEpub() {
        assertEquals(ImportKind.Epub, classify("application/epub+zip", "book.epub"))
        assertEquals(ImportKind.Epub, classify("application/x-epub+zip", "book"))
        assertEquals(ImportKind.Epub, classify("APPLICATION/EPUB+ZIP", "book.epub"))
    }

    @Test
    fun epubExtension_withGenericMime_classifiesEpub() {
        // The common file-manager case: octet-stream + a .epub name.
        assertEquals(ImportKind.Epub, classify("application/octet-stream", "novel.epub"))
        assertEquals(ImportKind.Epub, classify(null, "novel.EPUB"))
    }

    @Test
    fun epubExtension_withSecondDot_classifiesEpub() {
        assertEquals(ImportKind.Epub, classify(null, "book.v2.epub"))
    }

    // ── Text ────────────────────────────────────────────────────────

    @Test
    fun textPlainMime_classifiesText() {
        assertEquals(ImportKind.Text, classify("text/plain", "notes.txt"))
        assertEquals(ImportKind.Text, classify("text/plain; charset=utf-8", "notes.txt"))
    }

    @Test
    fun textExtensions_withGenericMime_classifyText() {
        assertEquals(ImportKind.Text, classify("application/octet-stream", "letter.txt"))
        assertEquals(ImportKind.Text, classify(null, "letter.text"))
        assertEquals(ImportKind.Text, classify(null, "README.md"))
        assertEquals(ImportKind.Text, classify(null, "README.markdown"))
    }

    @Test
    fun textWildcardMime_classifiesText() {
        // text/markdown with no useful extension is still readable prose.
        assertEquals(ImportKind.Text, classify("text/markdown", "doc"))
    }

    // ── PDF (gated on #996) ──────────────────────────────────────────

    @Test
    fun pdf_isUnsupportedWhileGated() {
        // PDF_ENABLED is false until #996 lands — until then PDFs must
        // NOT be opened (the EPUB parser would choke on the bytes).
        assertEquals(false, DocumentImportClassifier.PDF_ENABLED)
        assertEquals(ImportKind.Unsupported, classify("application/pdf", "report.pdf"))
        assertEquals(ImportKind.Unsupported, classify(null, "report.pdf"))
        assertEquals(ImportKind.Unsupported, classify("application/x-pdf", "report"))
    }

    // ── Unsupported / edge ───────────────────────────────────────────

    @Test
    fun unknownTypes_areUnsupported() {
        assertEquals(ImportKind.Unsupported, classify("image/png", "photo.png"))
        assertEquals(ImportKind.Unsupported, classify("application/zip", "archive.zip"))
        assertEquals(ImportKind.Unsupported, classify(null, "data.bin"))
    }

    @Test
    fun nothingToGoOn_isUnsupported() {
        assertEquals(ImportKind.Unsupported, classify(null, null))
        assertEquals(ImportKind.Unsupported, classify("", ""))
        assertEquals(ImportKind.Unsupported, classify("   ", "   "))
    }

    @Test
    fun mimeTakesPrecedenceOverMisleadingExtension() {
        // A real EPUB mime wins even if the name lacks/contradicts it.
        assertEquals(ImportKind.Epub, classify("application/epub+zip", "mystery.dat"))
        // text/plain mime wins over a .epub-looking name.
        assertEquals(ImportKind.Text, classify("text/plain", "weird.epub.txt"))
    }

    @Test
    fun extensionWithQueryOrFragment_isStripped() {
        // last-path-segment Uris sometimes still carry ?/# tails.
        assertEquals(ImportKind.Epub, classify(null, "book.epub?token=abc"))
        assertEquals(ImportKind.Text, classify(null, "notes.txt#section"))
    }

    private fun classify(mime: String?, name: String?): ImportKind =
        DocumentImportClassifier.classify(mime, name)
}
