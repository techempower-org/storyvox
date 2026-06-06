package `in`.jphe.storyvox.data.annotation

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import `in`.jphe.storyvox.data.annotation.AnnotationExportFormatter.ChapterGroup
import `in`.jphe.storyvox.data.annotation.AnnotationExportFormatter.Format
import `in`.jphe.storyvox.data.db.dao.AnnotationDao
import `in`.jphe.storyvox.data.db.dao.ChapterDao
import `in`.jphe.storyvox.data.db.dao.FictionDao
import `in`.jphe.storyvox.data.db.entity.Annotation
import `in`.jphe.storyvox.data.db.entity.Chapter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Issue #999 — result of [ExportAnnotationsUseCase]. Mirrors
 * [in.jphe.storyvox.source.epub.writer.EpubExportResult] so the FictionDetail
 * share-sheet plumbing (ACTION_SEND + SAF CreateDocument) transfers with no new
 * UI concepts. The one divergence: [mimeType] is per-format (annotations export
 * as `text/markdown` or `text/plain`) where EPUB is always `application/epub+zip`.
 */
data class AnnotationExportResult(
    val file: File,
    /** content:// URI granted via FileProvider — pass to ACTION_SEND / SAF. */
    val uri: Uri,
    /** Suggested user-facing filename for the SAF "Save…" path. */
    val suggestedFileName: String,
    /** `text/markdown` or `text/plain`, matching the chosen [Format]. */
    val mimeType: String,
    /** True when this fiction had no annotations — the file is still valid
     *  (header + "No highlights yet.") but the UI can choose to warn first. */
    val isEmpty: Boolean,
)

/**
 * Builds a Markdown / plain-text export of a fiction's highlights + notes and
 * stages it in `context.cacheDir/exports/` for the share-sheet or SAF Save-As.
 *
 * This is the seam between the flat DAO rows and the pure
 * [AnnotationExportFormatter]: [buildGroups] turns the per-fiction annotation
 * list (already chapter-index ordered by the DAO) plus the chapter titles into
 * the formatter's [ChapterGroup] shape, then [export] writes the formatted
 * string to disk and hands back a FileProvider URI.
 *
 * Threading: the file write + DB reads run on `Dispatchers.IO`. The caller
 * (FictionDetail ViewModel) launches into `viewModelScope`. Mirrors the
 * threading + FileProvider staging in `ExportFictionToEpubUseCase` (#117).
 */
@Singleton
class ExportAnnotationsUseCase @Inject constructor(
    private val annotationDao: AnnotationDao,
    private val chapterDao: ChapterDao,
    private val fictionDao: FictionDao,
) {

    /**
     * Build the export file. Throws [IllegalStateException] when there's no
     * Fiction row for [fictionId] — a bug at the call site, since the detail
     * screen only offers export for fictions it's already rendering.
     */
    suspend fun export(
        context: Context,
        fictionId: String,
        format: Format,
    ): AnnotationExportResult = withContext(Dispatchers.IO) {
        val fiction = fictionDao.get(fictionId)
            ?: error("No Fiction row for id=$fictionId — was export shown for an unknown fiction?")

        val annotations = annotationDao.annotationsForFiction(fictionId)
        val chapters = chapterDao.allChapters(fictionId)
        val groups = groupAnnotationsByChapter(annotations, chapters)

        val title = fiction.title.ifBlank { "Untitled" }
        val text = AnnotationExportFormatter.format(title, groups, format)

        val outDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val fileName = buildFileName(fiction.title.ifBlank { fiction.id }, format)
        val file = File(outDir, fileName)
        file.writeText(text)

        val uri = FileProvider.getUriForFile(
            context,
            // Authority matches the <provider> in app/AndroidManifest.xml —
            // the same one #117's EPUB export uses. Keep in sync with manifest.
            "${context.packageName}.fileprovider",
            file,
        )

        AnnotationExportResult(
            file = file,
            uri = uri,
            suggestedFileName = fileName,
            mimeType = format.mimeType(),
            isEmpty = annotations.isEmpty(),
        )
    }

    /**
     * Suggested name for the cache file + the SAF picker default. Slugified
     * title + timestamp keeps repeated exports distinguishable; extension
     * tracks the chosen [Format]. Mirrors the EPUB exporter's `buildFileName`.
     */
    private fun buildFileName(title: String, format: Format): String {
        val slug = title.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(60)
            .ifBlank { "fiction" }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        return "$slug-highlights-$stamp.${format.extension()}"
    }
}

/**
 * Pure grouping function — the testable core of the export. Given the flat
 * per-fiction annotation list and the chapter rows, produce the formatter's
 * [ChapterGroup] list in chapter reading order. No Android / Context / Room
 * dependency, so it's covered by a plain JVM unit test (we have no Robolectric).
 *
 * [annotations] arrives already ordered by chapter index then start offset
 * (the DAO's JOIN-ed `annotationsForFiction` query). We re-sort the resulting
 * groups by the chapter's [Chapter.index] anyway, so the export stays in
 * reading order even if the input order ever changes. An annotation whose
 * chapter row is missing (purged while the highlight awaits a sync reconcile)
 * sorts last rather than being dropped — losing a user's highlight silently is
 * worse than an out-of-place one. A blank chapter title falls back to
 * "Chapter N", matching the EPUB exporter's `title.ifBlank { "Chapter ${index + 1}" }`.
 */
internal fun groupAnnotationsByChapter(
    annotations: List<Annotation>,
    chapters: List<Chapter>,
): List<ChapterGroup> {
    if (annotations.isEmpty()) return emptyList()
    val chapterById = chapters.associateBy { it.id }
    return annotations.groupBy { it.chapterId }.entries
        .sortedBy { (chapterId, _) -> chapterById[chapterId]?.index ?: Int.MAX_VALUE }
        .map { (chapterId, anns) ->
            val ch = chapterById[chapterId]
            val title = ch?.title?.ifBlank { null }
                ?: ch?.let { "Chapter ${it.index + 1}" }
                ?: "Chapter"
            ChapterGroup(chapterTitle = title, annotations = anns)
        }
}

/** File extension for a chosen export [Format]. */
internal fun Format.extension(): String = when (this) {
    Format.MARKDOWN -> "md"
    Format.PLAIN -> "txt"
}

/** MIME type for a chosen export [Format] (drives the share-sheet `type`). */
internal fun Format.mimeType(): String = when (this) {
    Format.MARKDOWN -> "text/markdown"
    Format.PLAIN -> "text/plain"
}
