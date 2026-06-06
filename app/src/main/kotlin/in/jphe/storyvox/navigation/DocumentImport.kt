package `in`.jphe.storyvox.navigation

/**
 * Issue #1000 — "Open With" file association. The kind of document an
 * inbound `ACTION_VIEW` / `ACTION_SEND` intent is carrying, decided
 * purely from its mime type and (as a fallback) its filename
 * extension. This is the seam the unit tests exercise: no Android
 * `Uri`, `Intent`, or `ContentResolver` — just the (mime, name) pair
 * the activity reads off the intent.
 */
enum class ImportKind {
    /** `application/epub+zip` (or a `.epub` filename). Routed through
     *  the existing `:source-epub` import path. */
    Epub,

    /** `text/plain` (or any `text/...` subtype, or a `.txt` / `.text` /
     *  `.md` filename). Imported as a single-chapter book via the same
     *  EPUB-backed import store (the EPUB source synthesises a one-
     *  chapter book from the plaintext body). */
    Text,

    /** `application/pdf` (or a `.pdf` filename). Gated on #996 (Somnia's
     *  PDF text-extraction path). Until that lands the resolver maps PDF
     *  to [Unsupported]; the manifest intent-filter + this branch are
     *  the hook #996 wires its extractor into — see
     *  [DocumentImportClassifier.PDF_ENABLED]. */
    Pdf,

    /** Anything we don't advertise an intent-filter for, or a payload
     *  whose mime + extension we can't confidently bucket. The resolver
     *  declines (returns null) rather than guessing. */
    Unsupported,
}

/**
 * Issue #1000 — pure mapping from an inbound document's mime type and
 * filename to an [ImportKind]. Kept Android-free so it unit-tests
 * without Robolectric (CLAUDE.md: JUnit 4, no Robolectric).
 *
 * File managers and email clients are wildly inconsistent about the
 * mime they attach to a share/open-with intent: a `.epub` may arrive
 * as `application/epub+zip`, `application/octet-stream`, or no mime at
 * all. We therefore try the mime first and fall back to the filename
 * extension — matching either is enough to claim the document.
 */
object DocumentImportClassifier {

    /**
     * #996 hook — flip to `true` once Somnia's PDF text-extraction path
     * (`:source-pdf`) is merged and wired into the import store. While
     * `false`, PDFs classify as [ImportKind.Unsupported] so the resolver
     * declines them (the manifest still advertises the filter, behind
     * the same gate, so we don't appear in the chooser for a type we
     * can't yet open). Keeping the branch live + tested means #996 is a
     * one-line flip + a store wire, not a re-derivation.
     */
    const val PDF_ENABLED: Boolean = false

    private val EPUB_MIMES = setOf(
        "application/epub+zip",
        "application/x-epub+zip",
        "application/epub",
    )
    private val PDF_MIMES = setOf(
        "application/pdf",
        "application/x-pdf",
    )

    /**
     * Classify a document by its [mimeType] (as reported on the intent's
     * `type` or resolved from the content Uri) and its [fileName] (the
     * Uri's last path segment or the DocumentFile display name). Either
     * may be null/blank — a file manager that supplies neither yields
     * [ImportKind.Unsupported].
     */
    fun classify(mimeType: String?, fileName: String?): ImportKind {
        val mime = mimeType?.trim()?.lowercase()?.substringBefore(';')?.takeIf { it.isNotEmpty() }
        val ext = fileName?.trim()?.lowercase()
            ?.substringBefore('?')?.substringBefore('#')
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.takeIf { it.isNotEmpty() }

        // Mime is the strongest signal when it's specific.
        when {
            mime in EPUB_MIMES -> return ImportKind.Epub
            mime in PDF_MIMES -> return pdfOrUnsupported()
            mime == "text/plain" -> return ImportKind.Text
        }

        // Generic / wildcard mimes (octet-stream, text/*, or absent) —
        // fall back to the filename extension.
        when (ext) {
            "epub" -> return ImportKind.Epub
            "pdf" -> return pdfOrUnsupported()
            "txt", "text", "md", "markdown" -> return ImportKind.Text
        }

        // A bare `text/*` (e.g. text/markdown) with no useful extension
        // is still plaintext we can read aloud.
        if (mime != null && mime.startsWith("text/")) return ImportKind.Text

        return ImportKind.Unsupported
    }

    private fun pdfOrUnsupported(): ImportKind =
        if (PDF_ENABLED) ImportKind.Pdf else ImportKind.Unsupported
}
