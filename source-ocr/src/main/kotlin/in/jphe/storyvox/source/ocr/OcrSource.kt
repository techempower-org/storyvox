package `in`.jphe.storyvox.source.ocr

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.ChapterInfo
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionStatus
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.data.source.plugin.SourceCategory
import `in`.jphe.storyvox.data.source.plugin.SourcePlugin
import `in`.jphe.storyvox.source.ocr.config.OcrConfig
import `in`.jphe.storyvox.source.ocr.config.OcrDocument
import `in`.jphe.storyvox.source.ocr.parse.OcrTextParser
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #995 — scan-to-read backend.
 *
 * The user points the camera at printed text (or picks a photo), the
 * capture flow runs on-device ML Kit OCR via the
 * [`in`.jphe.storyvox.data.ocr.OcrTextRecognizer] seam, and the
 * recognized text is persisted as an [OcrDocument]. This source
 * surfaces those documents as fictions so the normal `EnginePlayer`
 * neural-voice pipeline narrates them with every reader feature
 * (highlight, auto-scroll, pronunciation dict).
 *
 * One capture session = one fiction; each captured page = one chapter.
 * Pure user-content backend: zero network, zero ToS surface — the user
 * owns the page they scanned; storyvox just reads it aloud. This is the
 * assistive-tech bridge from the printed world to accessible audio.
 *
 * The capture / OCR / persistence work happens in `:feature` (the
 * `OcrCaptureScreen`) + `:app` (the ML Kit recognizer & DataStore
 * store). This source is read-only over what that flow persisted —
 * same split as `:source-epub` (SAF plumbing in `:app`, source reads
 * the indexed list).
 */
@SourcePlugin(
    id = SourceIds.OCR,
    displayName = "Scanned text",
    // Fresh-install discoverability: chip on by default. The backend
    // lists nothing until the user scans something, but the chip is the
    // affordance that teaches new users the scan-to-read surface exists
    // — and it's the highest-leverage accessibility feature, so it
    // should not be buried behind a toggle.
    defaultEnabled = true,
    category = SourceCategory.Text,
    supportsFollow = false,
    supportsSearch = true,
    description = "Scan printed text with your camera · on-device OCR · zero-network",
    sourceUrl = "",
)
@Singleton
internal class OcrSource @Inject constructor(
    private val config: OcrConfig,
) : FictionSource {

    override val id: String = SourceIds.OCR
    override val displayName: String = "Scanned text"

    // ─── browse ────────────────────────────────────────────────────────

    override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> =
        listDocuments()

    override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> =
        listDocuments()

    override suspend fun byGenre(
        genre: String,
        page: Int,
    ): FictionResult<ListPage<FictionSummary>> =
        // Scanned documents carry no genre metadata. Surfacing nothing
        // is more honest than fabricating buckets.
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    override suspend fun genres(): FictionResult<List<String>> =
        FictionResult.Success(emptyList())

    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> {
        val term = query.term.trim().lowercase()
        if (term.isEmpty()) return listDocuments()
        val matched = config.documents()
            .filter { doc ->
                doc.title.lowercase().contains(term) ||
                    doc.pages.any { it.text.lowercase().contains(term) }
            }
            .map { it.toSummary() }
        return FictionResult.Success(ListPage(items = matched, page = 1, hasNext = false))
    }

    private suspend fun listDocuments(): FictionResult<ListPage<FictionSummary>> {
        val all = config.documents().map { it.toSummary() }
        return FictionResult.Success(ListPage(items = all, page = 1, hasNext = false))
    }

    // ─── detail ────────────────────────────────────────────────────────

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        val doc = config.document(fictionId)
            ?: return FictionResult.NotFound("Scanned document not found: $fictionId")

        val chapters = doc.pages.map { page ->
            ChapterInfo(
                id = chapterIdFor(fictionId, page.index),
                sourceChapterId = page.index.toString(),
                index = page.index,
                title = page.title,
                wordCount = OcrTextParser.wordCount(page.text),
            )
        }
        return FictionResult.Success(
            FictionDetail(summary = doc.toSummary(), chapters = chapters),
        )
    }

    override suspend fun chapter(
        fictionId: String,
        chapterId: String,
    ): FictionResult<ChapterContent> {
        val doc = config.document(fictionId)
            ?: return FictionResult.NotFound("Scanned document not found: $fictionId")

        val page = doc.pages.firstOrNull { chapterIdFor(fictionId, it.index) == chapterId }
            ?: return FictionResult.NotFound("Page not in document: $chapterId")

        val info = ChapterInfo(
            id = chapterId,
            sourceChapterId = page.index.toString(),
            index = page.index,
            title = page.title,
            wordCount = OcrTextParser.wordCount(page.text),
        )
        // The stored text is already reflowed plaintext (the capture
        // flow ran it through OcrTextParser before persisting). Wrap
        // each paragraph in <p> for the reader view.
        val html = page.text
            .split("\n\n")
            .filter { it.isNotBlank() }
            .joinToString("\n") { "<p>${escapeHtml(it.trim())}</p>" }
        return FictionResult.Success(
            ChapterContent(
                info = info,
                htmlBody = html,
                plainBody = page.text,
            ),
        )
    }

    // ─── auth-gated ─────────────────────────────────────────────────────

    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        // No upstream "follows" for local scans. Returning the document
        // list matches the user mental model: "the things I scanned."
        listDocuments()

    override suspend fun setFollowed(
        fictionId: String,
        followed: Boolean,
    ): FictionResult<Unit> = FictionResult.Success(Unit)
}

/** Compose a stable chapter id from the fictionId + the page index. */
private fun chapterIdFor(fictionId: String, pageIndex: Int): String =
    "$fictionId::p$pageIndex"

private fun OcrDocument.toSummary(): FictionSummary = FictionSummary(
    id = fictionId,
    sourceId = SourceIds.OCR,
    title = title,
    author = "",
    description = pages.firstOrNull()?.text?.take(160).orEmpty(),
    status = FictionStatus.COMPLETED, // a scan is a static capture
    chapterCount = pages.size,
)

private fun escapeHtml(s: String): String =
    s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
