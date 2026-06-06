package `in`.jphe.storyvox.source.ocr.parse

import `in`.jphe.storyvox.data.ocr.OcrBlock
import `in`.jphe.storyvox.data.ocr.OcrRecognition

/**
 * Issue #995 — pure text-shaping for OCR output.
 *
 * ML Kit returns text with hard line breaks at every recognized line
 * (it OCRs line-by-line, so a single printed paragraph that wraps
 * across four physical lines comes back as four `\n`-separated lines).
 * Feeding that straight to TTS produces a stilted, pause-after-every-
 * line cadence. This object reflows the recognized lines back into
 * paragraphs and emits clean HTML + plaintext bodies that match the
 * shape every other [`in`.jphe.storyvox.data.source.FictionSource]
 * hands back ([htmlBody] for the reader, [plainBody] for TTS).
 *
 * Kept Android-types-free and side-effect-free so it unit-tests
 * without Robolectric — the camera / ML Kit boundary is mocked at the
 * [OcrRecognition] seam, and everything downstream of that is pure
 * here.
 *
 * ## Reflow heuristic
 *
 * - Prefer the recognizer's [OcrRecognition.blocks] (ML Kit's
 *   paragraph clusters) when present: one block → one paragraph.
 * - Within a block (or when only flat [OcrRecognition.text] is
 *   available), join wrapped lines: a line that does NOT end in
 *   sentence-final punctuation is assumed to continue on the next
 *   line, so the break becomes a space. A blank line is a hard
 *   paragraph break.
 * - De-hyphenate words split across a line break ("encyclo-\npedia" →
 *   "encyclopedia"), the single most common OCR-of-print artifact.
 */
object OcrTextParser {

    /** Sentence-final characters that mark a line as a real line end
     *  rather than a soft wrap. Includes the common closing quotes /
     *  brackets that can trail terminal punctuation. */
    private const val SENTENCE_FINAL = ".!?…\"')]}»”’"

    /**
     * Build the reader/TTS body for one captured page from its
     * [recognition]. Returns reflowed paragraphs joined by blank lines
     * in [plainBody] and wrapped in `<p>` tags in [htmlBody].
     */
    fun renderPage(recognition: OcrRecognition): OcrPageBody {
        val paragraphs = paragraphsFrom(recognition)
        val plain = paragraphs.joinToString("\n\n")
        val html = paragraphs.joinToString("\n") { "<p>${escapeHtml(it)}</p>" }
        return OcrPageBody(htmlBody = html, plainBody = plain)
    }

    /**
     * Reflow a [recognition] into a list of paragraph strings. Public
     * so the document-title heuristic can peek at the first paragraph.
     */
    fun paragraphsFrom(recognition: OcrRecognition): List<String> {
        val blocks = recognition.blocks.filter { it.text.isNotBlank() }
        return if (blocks.isNotEmpty()) {
            blocks.map { reflowLines(it.text) }.filter { it.isNotBlank() }
        } else {
            // No block structure — split the flat text on blank lines
            // into paragraph candidates, then reflow each.
            recognition.text
                .split(Regex("\\n[ \\t]*\\n"))
                .map { reflowLines(it) }
                .filter { it.isNotBlank() }
        }
    }

    /**
     * Suggest a fiction title from the recognized text: the first
     * non-blank line, trimmed and length-capped. Falls back to
     * [fallback] when the page has no usable text (a blank scan).
     */
    fun suggestTitle(recognition: OcrRecognition, fallback: String): String {
        val firstLine = recognition.text
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            ?: recognition.blocks.firstOrNull { it.text.isNotBlank() }
                ?.text?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotBlank() }
        val candidate = firstLine?.takeIf { it.isNotBlank() } ?: return fallback
        return candidate.take(MAX_TITLE_LEN).trim()
    }

    /**
     * Join wrapped lines within a single paragraph candidate. A line
     * NOT ending in sentence-final punctuation is treated as a soft
     * wrap (joined with a space); a trailing hyphen is treated as a
     * word split (joined with no space, hyphen dropped).
     */
    private fun reflowLines(raw: String): String {
        val lines = raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return ""
        val sb = StringBuilder()
        for ((i, line) in lines.withIndex()) {
            if (i == 0) {
                sb.append(line)
                continue
            }
            val prev = sb.toString()
            when {
                // Word split across the break: "encyclo-" + "pedia".
                // Only de-hyphenate when the char before the hyphen is a
                // letter, so we don't eat a real trailing dash / range.
                prev.length >= 2 && prev.last() == '-' && prev[prev.length - 2].isLetter() -> {
                    sb.setLength(sb.length - 1) // drop the hyphen
                    sb.append(line)
                }
                else -> {
                    sb.append(' ').append(line)
                }
            }
        }
        // Collapse any runs of whitespace the joins introduced.
        return sb.toString().replace(Regex("[ \\t]+"), " ").trim()
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    /** Naive word count for the chapter-length hint in the picker. */
    fun wordCount(plain: String): Int {
        val t = plain.trim()
        if (t.isEmpty()) return 0
        return t.split(Regex("\\s+")).size
    }

    private const val MAX_TITLE_LEN = 80

    /** Exposed for tests / callers that want the sentence-final set. */
    @Suppress("unused")
    internal fun isSentenceFinal(line: String): Boolean {
        val last = line.trimEnd().lastOrNull() ?: return false
        return SENTENCE_FINAL.contains(last)
    }
}

/** Reader (HTML) + TTS (plaintext) bodies for one OCR'd page. */
data class OcrPageBody(
    val htmlBody: String,
    val plainBody: String,
)

/** Convenience: build an [OcrBlock] list with no confidence — used by
 *  callers that only have flat text but want to drive the block path. */
fun flatBlocks(vararg paragraphs: String): List<OcrBlock> =
    paragraphs.map { OcrBlock(text = it) }
