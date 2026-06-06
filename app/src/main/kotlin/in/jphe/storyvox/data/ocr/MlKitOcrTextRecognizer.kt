package `in`.jphe.storyvox.data.ocr

import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Issue #995 — production [OcrTextRecognizer] backed by Google ML Kit's
 * bundled Latin-script Text Recognition v2 model.
 *
 * Bundled (not Play-Services-downloaded) so the first scan works
 * offline with no model fetch — the right posture for an accessibility
 * tool used in low-connectivity settings. Runs entirely on-device:
 * nothing about the user's scanned letter / medicine label / form
 * leaves the phone.
 *
 * The ML Kit `Text` result preserves reading-order line breaks; we keep
 * those in [OcrRecognition.text] and map each `Text.TextBlock` to an
 * [OcrBlock] so the `:source-ocr` parser can reflow on paragraph
 * boundaries. This is the same recognizer #996's scanned-PDF importer
 * reuses (it renders each PDF page to a bitmap → bytes → here).
 */
@Singleton
class MlKitOcrTextRecognizer @Inject constructor() : OcrTextRecognizer {

    // The default-options Latin recognizer is process-wide reusable.
    private val client by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    override suspend fun recognize(image: OcrImage): OcrResult {
        val inputImage = withContext(Dispatchers.Default) {
            val bitmap = BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size)
                ?: return@withContext null
            InputImage.fromBitmap(bitmap, normalizeRotation(image.rotationDegrees))
        } ?: return OcrResult.Failure("Couldn't read that image.")

        return suspendCancellableCoroutine { cont ->
            client.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val blocks = visionText.textBlocks.map { block ->
                        OcrBlock(text = block.text)
                    }
                    cont.resume(OcrResult.Success(OcrRecognition(text = visionText.text, blocks = blocks)))
                }
                .addOnFailureListener { e ->
                    cont.resume(OcrResult.Failure("Text recognition failed.", e))
                }
        }
    }

    /** ML Kit's InputImage accepts only 0/90/180/270. Snap anything
     *  else to the nearest right angle. */
    private fun normalizeRotation(degrees: Int): Int = when (((degrees % 360) + 360) % 360) {
        in 45 until 135 -> 90
        in 135 until 225 -> 180
        in 225 until 315 -> 270
        else -> 0
    }
}
