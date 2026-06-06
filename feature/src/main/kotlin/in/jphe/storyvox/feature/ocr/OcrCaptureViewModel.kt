package `in`.jphe.storyvox.feature.ocr

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.data.ocr.OcrImage
import `in`.jphe.storyvox.data.ocr.OcrResult
import `in`.jphe.storyvox.data.ocr.OcrTextRecognizer
import `in`.jphe.storyvox.source.ocr.config.OcrDocumentStore
import `in`.jphe.storyvox.source.ocr.config.OcrPage
import `in`.jphe.storyvox.source.ocr.parse.OcrTextParser
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Issue #995 — drives the OCR scan-to-read capture flow.
 *
 * The screen hands raw image bytes (from a CameraX capture or a gallery
 * pick) to [onImageCaptured]; this VM runs them through the on-device
 * [OcrTextRecognizer] seam, reflows the recognized text via
 * [OcrTextParser], and accumulates each scan as a page. When the user
 * finishes, [save] persists an `OcrDocument` through [OcrDocumentStore]
 * and emits the new fictionId so the screen can navigate into the
 * reader / start playback.
 *
 * All Android / ML Kit specifics stay behind the [OcrTextRecognizer]
 * and [OcrDocumentStore] interfaces, so this VM is plain JVM logic and
 * unit-testable with fakes (no Robolectric).
 */
@HiltViewModel
class OcrCaptureViewModel @Inject constructor(
    private val recognizer: OcrTextRecognizer,
    private val store: OcrDocumentStore,
) : ViewModel() {

    private val _state = MutableStateFlow(OcrCaptureUiState())
    val state: StateFlow<OcrCaptureUiState> = _state.asStateFlow()

    /**
     * Recognize text in a freshly captured / picked image and append it
     * as a page. [rotationDegrees] is the EXIF / sensor rotation so ML
     * Kit reads sideways captures upright.
     */
    fun onImageCaptured(bytes: ByteArray, rotationDegrees: Int = 0) {
        if (_state.value.isRecognizing) return
        _state.update { it.copy(isRecognizing = true, error = null) }
        viewModelScope.launch {
            when (val result = recognizer.recognize(OcrImage(bytes, rotationDegrees))) {
                is OcrResult.Failure -> _state.update {
                    it.copy(isRecognizing = false, error = result.message)
                }

                is OcrResult.Success -> {
                    val recognition = result.recognition
                    if (recognition.isEmpty) {
                        _state.update {
                            it.copy(
                                isRecognizing = false,
                                error = "No text found on that page. Hold steady and try again.",
                            )
                        }
                        return@launch
                    }
                    val body = OcrTextParser.renderPage(recognition)
                    _state.update { prev ->
                        val nextIndex = prev.pages.size
                        val newPage = OcrCapturedPage(
                            index = nextIndex,
                            title = "Page ${nextIndex + 1}",
                            text = body.plainBody,
                            wordCount = OcrTextParser.wordCount(body.plainBody),
                        )
                        // Seed the document title from the first page's
                        // first line if the user hasn't typed one.
                        val seededTitle = prev.title.ifBlank {
                            OcrTextParser.suggestTitle(recognition, fallback = "")
                        }
                        prev.copy(
                            isRecognizing = false,
                            error = null,
                            title = seededTitle,
                            pages = prev.pages + newPage,
                        )
                    }
                }
            }
        }
    }

    /** Update the user-editable document title. */
    fun onTitleChanged(title: String) {
        _state.update { it.copy(title = title) }
    }

    /** Drop a captured page (the user re-scans or discards a bad shot). */
    fun removePage(index: Int) {
        _state.update { prev ->
            val remaining = prev.pages
                .filterNot { it.index == index }
                .mapIndexed { i, p -> p.copy(index = i, title = "Page ${i + 1}") }
            prev.copy(pages = remaining)
        }
    }

    /** Clear a surfaced error once the user has acknowledged it. */
    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /**
     * Persist the accumulated pages as one document and emit its
     * fictionId via [OcrCaptureUiState.savedFictionId]. No-op when there
     * are no pages.
     */
    fun save() {
        val snapshot = _state.value
        if (snapshot.pages.isEmpty() || snapshot.isSaving) return
        _state.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            val title = snapshot.title.ifBlank { "Scanned document" }.trim()
            val pages = snapshot.pages.map { OcrPage(index = it.index, title = it.title, text = it.text) }
            val fictionId = store.save(title = title, pages = pages)
            _state.update { it.copy(isSaving = false, savedFictionId = fictionId) }
        }
    }

    /** Consume the navigation signal so it fires exactly once. */
    fun onNavigatedToSaved() {
        _state.update { it.copy(savedFictionId = null) }
    }
}

/** UI state for the OCR capture screen. */
@Immutable
data class OcrCaptureUiState(
    val title: String = "",
    val pages: List<OcrCapturedPage> = emptyList(),
    val isRecognizing: Boolean = false,
    val isSaving: Boolean = false,
    /** User-facing recoverable error (OCR failed, blank page). */
    val error: String? = null,
    /** Non-null exactly once after a successful [OcrCaptureViewModel.save];
     *  the screen navigates into the new fiction then calls
     *  [OcrCaptureViewModel.onNavigatedToSaved]. */
    val savedFictionId: String? = null,
) {
    val canSave: Boolean get() = pages.isNotEmpty() && !isSaving && !isRecognizing
    val totalWords: Int get() = pages.sumOf { it.wordCount }
}

/** One captured + recognized page held in the capture session. */
@Immutable
data class OcrCapturedPage(
    val index: Int,
    val title: String,
    val text: String,
    val wordCount: Int,
)
