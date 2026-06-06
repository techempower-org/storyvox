package `in`.jphe.storyvox.source.ocr.config

import kotlinx.coroutines.flow.Flow

/**
 * Issue #995 — abstraction over the OCR-source's persistent store of
 * scanned documents. The implementation lives in `:app` (DataStore +
 * JSON) so this source module stays free of Android Preferences
 * plumbing; this interface is what the source consumes.
 *
 * ## Why the OCR source stores its own content
 *
 * Unlike `:source-epub` (re-reads bytes from a SAF file) or
 * `:source-rss` (re-fetches the feed URL), an OCR capture is
 * transient — the camera frame is gone the moment the user leaves the
 * capture screen. The recognized *text* is the canonical artifact, so
 * the OCR source persists it directly. Each [OcrDocument] is one
 * capture session = one fiction; each [OcrPage] in it = one chapter.
 *
 * The recognized text persists as plain `String`s rather than any
 * Android type, so this interface (and the source) stay testable
 * without Robolectric — same posture as `EpubConfig`.
 */
interface OcrConfig {
    /** Hot stream of the user's scanned documents, newest first. */
    val documents: Flow<List<OcrDocument>>

    /** Synchronous snapshot for code paths that can't suspend. */
    suspend fun documents(): List<OcrDocument>

    /** One document by its [OcrDocument.fictionId], or null if absent. */
    suspend fun document(fictionId: String): OcrDocument?
}

/**
 * One scanned document = one fiction. [fictionId] is stable across
 * re-launches (minted at capture time from [createdAt] + a nonce).
 * [pages] are the captured pages in capture order; each becomes a
 * chapter.
 */
data class OcrDocument(
    val fictionId: String,
    val title: String,
    val createdAt: Long,
    val pages: List<OcrPage>,
)

/**
 * One captured page within a document = one chapter. [text] is the
 * recognized text (already normalized by the capture flow's parser);
 * [index] is the 0-based page order.
 */
data class OcrPage(
    val index: Int,
    val title: String,
    val text: String,
)

/** Issue #995 — derive a stable, collision-resistant fictionId for a
 *  new OCR capture. We can't hash content (the user may scan the same
 *  page twice on purpose), so we key on creation time + a short nonce.
 *  Caller supplies the nonce so this stays pure / testable. */
fun fictionIdForOcrCapture(createdAt: Long, nonce: String): String {
    val canonical = "$createdAt:$nonce"
    val hash = canonical.hashCode().toUInt().toString(16).padStart(8, '0')
    return "${`in`.jphe.storyvox.data.source.SourceIds.OCR}:$hash"
}

/**
 * Issue #995 — write-side companion to [OcrConfig]. The `:feature`
 * capture flow (`OcrCaptureScreen` / its ViewModel) calls this to
 * persist a finished scan; the `:app` DataStore impl owns the JSON
 * serialization. Kept separate from [OcrConfig] (read side) so the
 * read path stays a clean, no-mutation consumer — same split spirit as
 * `EpubConfigImpl` keeping its mutators off the `EpubConfig` interface.
 */
interface OcrDocumentStore {
    /**
     * Persist a freshly-captured document and return its assigned
     * [OcrDocument.fictionId]. [title] is the user-confirmed (or
     * OCR-suggested) title; [pages] are the captured pages in order.
     */
    suspend fun save(title: String, pages: List<OcrPage>): String

    /** Delete a scanned document by [fictionId]. No-op if absent. */
    suspend fun delete(fictionId: String)
}
