package `in`.jphe.storyvox.data.ocr

/**
 * Issue #995 — on-device OCR (optical character recognition) seam.
 *
 * Turns an image of printed text into recognized text. The production
 * implementation (`MlKitOcrTextRecognizer` in `:app`) wraps Google ML
 * Kit's bundled, offline Latin-script Text Recognition v2 model — no
 * network, privacy-preserving, works in low-connectivity settings.
 * That matters for the accessibility audience this feature serves
 * (blind / low-vision / dyslexic users turning a printed letter,
 * textbook, form, or medicine label into audio).
 *
 * ## Why this lives in :core-data
 *
 * Both `:source-ocr` (camera / gallery capture → fiction, #995) and
 * `:source-pdf` (scanned-PDF import, #996) need OCR. Putting the
 * interface here — alongside [`in`.jphe.storyvox.data.source.FictionSource]
 * and [`in`.jphe.storyvox.data.source.WebViewFetcher] — lets both leaf
 * source modules depend on the seam without depending on each other
 * (the leaf-source architecture forbids source↔source deps). The PDF
 * importer renders each page to a bitmap, hands the bytes here, and
 * reuses the exact same recognizer the camera flow uses.
 *
 * ## Android-types-free by design
 *
 * The interface deals in [OcrImage] (raw encoded bytes + rotation) so
 * the seam carries no `android.graphics.Bitmap` / `InputImage`
 * dependency. That keeps `:core-data` and `:source-ocr` unit-testable
 * without Robolectric — tests inject a fake recognizer that returns
 * canned [OcrRecognition]s (same posture as the EPUB `EpubFileReader`
 * fake in `:app`). The ML Kit impl decodes the bytes to an
 * `InputImage` on its side.
 */
interface OcrTextRecognizer {

    /**
     * Recognize text in [image].
     *
     * Implementations run entirely on-device and should be safe to call
     * from a background dispatcher. Recognition of a blank / textless
     * image is NOT an error — it returns an [OcrRecognition] with empty
     * [OcrRecognition.text] and no [OcrRecognition.blocks]. Genuine
     * failures (model load failure, undecodable bytes) surface as
     * [OcrResult.Failure] so callers can show a recoverable error
     * rather than crashing.
     */
    suspend fun recognize(image: OcrImage): OcrResult
}

/**
 * One image to OCR. [bytes] are the encoded image (JPEG or PNG — what
 * CameraX `ImageCapture` writes, or what a gallery `content://` Uri
 * yields). [rotationDegrees] is the EXIF / sensor rotation (0, 90, 180,
 * 270); ML Kit needs it to read sideways captures correctly. Callers
 * that have already rotated the bytes upright pass 0.
 */
data class OcrImage(
    val bytes: ByteArray,
    val rotationDegrees: Int = 0,
) {
    // Value-based equals/hashCode so test assertions and de-dup work on
    // the byte content rather than array identity.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OcrImage) return false
        return rotationDegrees == other.rotationDegrees && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + rotationDegrees
}

/** Result of one [OcrTextRecognizer.recognize] call. */
sealed interface OcrResult {

    /**
     * Recognition completed. [recognition] may be empty (a blank page);
     * that is a successful recognition of "no text", not a failure.
     */
    data class Success(val recognition: OcrRecognition) : OcrResult

    /**
     * Recognition could not run — model unavailable, image undecodable,
     * etc. [message] is user-facing; [cause] carries the original
     * throwable for logging.
     */
    data class Failure(val message: String, val cause: Throwable? = null) : OcrResult
}

/**
 * Recognized text from one image.
 *
 * [text] is the full recognized text with ML Kit's reading-order line
 * breaks preserved — good enough to narrate directly. [blocks] are the
 * recognizer's paragraph-level clusters (ML Kit `Text.TextBlock`s),
 * each a contiguous run of text with an optional confidence. The
 * `:source-ocr` parser uses blocks to decide paragraph boundaries when
 * assembling a chapter; #996's PDF importer can map one block-cluster
 * per page region.
 */
data class OcrRecognition(
    val text: String,
    val blocks: List<OcrBlock> = emptyList(),
) {
    /** True when the recognizer found no usable text (blank page). */
    val isEmpty: Boolean get() = text.isBlank() && blocks.all { it.text.isBlank() }

    companion object {
        val EMPTY = OcrRecognition(text = "", blocks = emptyList())
    }
}

/**
 * One recognized paragraph cluster. [confidence] is 0..1 when the
 * recognizer reports it (ML Kit does not always populate per-block
 * confidence; null means "unknown", not "zero").
 */
data class OcrBlock(
    val text: String,
    val confidence: Float? = null,
)
