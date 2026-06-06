package `in`.jphe.storyvox.data.annotation

import `in`.jphe.storyvox.data.db.entity.Annotation

/**
 * Issue #999 — pure formatter that renders a fiction's highlights + notes as
 * Markdown or plain text for the share-sheet export. No Android / Context /
 * IO dependency: the caller resolves the fiction title and groups the
 * annotations by chapter (with chapter titles), then this object turns that
 * already-resolved shape into a string. Keeping it pure makes the export
 * format unit-testable without standing up Room or a FileProvider, and keeps
 * the surface (FictionDetail VM) free of formatting logic.
 *
 * The input is pre-grouped and pre-ordered by the caller (the DAO already
 * returns annotations in chapter-index then start-offset order), so this
 * formatter does no sorting — it just walks the groups in the order given.
 */
object AnnotationExportFormatter {

    /** One chapter's worth of annotations, in the order they should render. */
    data class ChapterGroup(
        val chapterTitle: String,
        val annotations: List<Annotation>,
    )

    enum class Format { MARKDOWN, PLAIN }

    /**
     * Render [groups] for a fiction titled [fictionTitle]. Empty groups (a
     * chapter with no annotations) are skipped; an entirely empty export
     * yields a header + a "no highlights" line so the shared file is never
     * confusingly blank.
     */
    fun format(
        fictionTitle: String,
        groups: List<ChapterGroup>,
        format: Format,
    ): String = when (format) {
        Format.MARKDOWN -> markdown(fictionTitle, groups)
        Format.PLAIN -> plain(fictionTitle, groups)
    }

    private fun markdown(fictionTitle: String, groups: List<ChapterGroup>): String {
        val nonEmpty = groups.filter { it.annotations.isNotEmpty() }
        val sb = StringBuilder()
        sb.append("# ").append(fictionTitle).append("\n\n")
        sb.append("_Highlights & notes").append("_\n\n")
        if (nonEmpty.isEmpty()) {
            sb.append("No highlights yet.\n")
            return sb.toString()
        }
        for (group in nonEmpty) {
            sb.append("## ").append(group.chapterTitle).append("\n\n")
            for (a in group.annotations) {
                // Block-quote the highlighted text; collapse internal newlines
                // so a multi-line selection stays one quote block.
                val quote = a.quotedText.replace("\n", " ").trim()
                sb.append("> ").append(quote).append("\n")
                if (!a.note.isNullOrBlank()) {
                    sb.append("\n").append(a.note.trim()).append("\n")
                }
                sb.append("\n")
            }
        }
        return sb.toString()
    }

    private fun plain(fictionTitle: String, groups: List<ChapterGroup>): String {
        val nonEmpty = groups.filter { it.annotations.isNotEmpty() }
        val sb = StringBuilder()
        sb.append(fictionTitle).append("\n")
        sb.append("Highlights & notes").append("\n\n")
        if (nonEmpty.isEmpty()) {
            sb.append("No highlights yet.\n")
            return sb.toString()
        }
        for (group in nonEmpty) {
            sb.append(group.chapterTitle).append("\n")
            sb.append("-".repeat(group.chapterTitle.length.coerceAtLeast(1))).append("\n\n")
            for (a in group.annotations) {
                val quote = a.quotedText.replace("\n", " ").trim()
                sb.append("“").append(quote).append("”").append("\n")
                if (!a.note.isNullOrBlank()) {
                    sb.append("  Note: ").append(a.note.trim()).append("\n")
                }
                sb.append("\n")
            }
        }
        return sb.toString()
    }
}
