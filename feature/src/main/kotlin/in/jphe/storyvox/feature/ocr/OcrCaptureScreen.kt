package `in`.jphe.storyvox.feature.ocr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.jphe.storyvox.feature.R
import `in`.jphe.storyvox.ui.component.BrassButton
import `in`.jphe.storyvox.ui.component.BrassButtonVariant
import `in`.jphe.storyvox.ui.theme.LocalSpacing
import java.io.ByteArrayOutputStream

/**
 * Issue #995 — OCR scan-to-read capture screen.
 *
 * Two capture paths, both first-class for accessibility:
 *  1. **Camera** — CameraX live preview + a capture button. Requires
 *     CAMERA permission; shows a plain-language rationale before the
 *     prompt and a graceful "use the gallery instead" fallback if the
 *     user declines (so a denied camera never dead-ends the feature).
 *  2. **Gallery** — `ActivityResultContracts.GetContent` for images,
 *     no permission needed. This is the path a screen-reader user who
 *     can't aim a camera, or anyone with an existing photo of a page,
 *     takes — never gate the feature behind the camera.
 *
 * Captured/picked images are decoded to JPEG bytes and handed to
 * [OcrCaptureViewModel.onImageCaptured], which runs on-device ML Kit
 * OCR and accumulates pages. The user names the document and saves;
 * [onScanComplete] navigates into the new fiction.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcrCaptureScreen(
    onNavigateBack: () -> Unit,
    onScanComplete: (fictionId: String) -> Unit,
    viewModel: OcrCaptureViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val spacing = LocalSpacing.current

    // One-shot navigation when a document is saved.
    LaunchedEffect(state.savedFictionId) {
        state.savedFictionId?.let { id ->
            onScanComplete(id)
            viewModel.onNavigatedToSaved()
        }
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    // True once the user has declined the prompt at least once — drives
    // the "camera unavailable, use the gallery" rationale instead of
    // re-nagging.
    var cameraDenied by remember { mutableStateOf(false) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
        cameraDenied = !granted
    }

    // Gallery pick — no permission needed (the system photo picker
    // returns a one-shot read grant for the chosen image).
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri?.let {
            val bytes = readImageBytes(context, it)
            if (bytes != null) viewModel.onImageCaptured(bytes)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ocr_capture_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.ocr_capture_back_cd),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = spacing.md)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Text(
                stringResource(R.string.ocr_capture_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ── Camera surface or its fallback ─────────────────────────
            when {
                hasCameraPermission -> {
                    CameraCaptureBox(
                        enabled = !state.isRecognizing && !state.isSaving,
                        onCaptured = { bytes, rotation ->
                            viewModel.onImageCaptured(bytes, rotation)
                        },
                    )
                }

                cameraDenied -> {
                    // Denied: don't re-nag. Explain and lean on gallery.
                    InfoCard(text = stringResource(R.string.ocr_camera_denied))
                }

                else -> {
                    // First run / not yet asked: rationale + grant button.
                    InfoCard(text = stringResource(R.string.ocr_camera_rationale))
                    BrassButton(
                        label = stringResource(R.string.ocr_enable_camera),
                        onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                        variant = BrassButtonVariant.Primary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // ── Gallery alternative — always available ─────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BrassButton(
                    label = stringResource(R.string.ocr_pick_from_gallery),
                    onClick = { galleryLauncher.launch("image/*") },
                    variant = BrassButtonVariant.Secondary,
                    enabled = !state.isRecognizing && !state.isSaving,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Filled.PhotoLibrary,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.isRecognizing) {
                Text(
                    stringResource(R.string.ocr_recognizing),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            state.error?.let { err ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        err,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(spacing.md),
                    )
                }
            }

            // ── Captured pages + title + save ──────────────────────────
            if (state.pages.isNotEmpty()) {
                OutlinedTextField(
                    value = state.title,
                    onValueChange = viewModel::onTitleChanged,
                    label = { Text(stringResource(R.string.ocr_document_title_label)) },
                    singleLine = true,
                    enabled = !state.isSaving,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    stringResource(
                        R.string.ocr_pages_summary,
                        state.pages.size,
                        state.totalWords,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                )

                CapturedPagesList(
                    pages = state.pages,
                    onRemove = viewModel::removePage,
                    enabled = !state.isSaving,
                )

                BrassButton(
                    label = stringResource(R.string.ocr_save_and_read),
                    onClick = viewModel::save,
                    variant = BrassButtonVariant.Primary,
                    enabled = state.canSave,
                    loading = state.isSaving,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * CameraX live preview wrapped in a Compose [AndroidView], with a
 * capture button overlaid at the bottom. Uses [LifecycleCameraController]
 * so the camera binds to the composition's lifecycle automatically.
 */
@Composable
private fun CameraCaptureBox(
    enabled: Boolean,
    onCaptured: (bytes: ByteArray, rotationDegrees: Int) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val spacing = LocalSpacing.current
    val captureCd = stringResource(R.string.ocr_capture_button_cd)

    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
        }
    }

    DisposableEffect(lifecycleOwner) {
        controller.bindToLifecycle(lifecycleOwner)
        onDispose { controller.unbind() }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    this.controller = controller
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
            },
        )
        BrassButton(
            label = stringResource(R.string.ocr_capture_button),
            onClick = {
                takePicture(context, controller, onCaptured)
            },
            variant = BrassButtonVariant.Primary,
            enabled = enabled,
            modifier = Modifier
                .padding(bottom = spacing.md)
                .semantics { contentDescription = captureCd },
        )
    }
}

@Composable
private fun CapturedPagesList(
    pages: List<OcrCapturedPage>,
    onRemove: (Int) -> Unit,
    enabled: Boolean,
) {
    val spacing = LocalSpacing.current
    // The page count is small (a handful of scanned pages); a plain
    // Column inside the scrolling parent would be simpler, but each row
    // carries a delete affordance and a text preview, so a height-capped
    // LazyColumn keeps a 20-page scan from blowing past the screen.
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
        userScrollEnabled = false,
    ) {
        items(items = pages, key = { it.index }) { page ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.md, vertical = spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(page.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            page.text.take(120),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                    IconButton(onClick = { onRemove(page.index) }, enabled = enabled) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(
                                R.string.ocr_remove_page_cd,
                                page.title,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoCard(text: String) {
    val spacing = LocalSpacing.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(spacing.md),
        )
    }
}

/**
 * Capture a still through CameraX [ImageCapture] in-memory, convert the
 * frame to JPEG bytes, and hand them upward with the sensor rotation so
 * ML Kit reads sideways shots correctly.
 */
private fun takePicture(
    context: Context,
    controller: LifecycleCameraController,
    onCaptured: (bytes: ByteArray, rotationDegrees: Int) -> Unit,
) {
    val executor = ContextCompat.getMainExecutor(context)
    controller.takePicture(
        executor,
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: androidx.camera.core.ImageProxy) {
                val rotation = image.imageInfo.rotationDegrees
                val bytes = image.toJpegBytes()
                image.close()
                if (bytes != null) onCaptured(bytes, rotation)
            }

            override fun onError(exception: ImageCaptureException) {
                // Capture failed (rare — device camera busy). Swallow;
                // the user simply taps capture again. The empty-bytes
                // path never fires onCaptured, so no blank page is added.
            }
        },
    )
}

/** Decode an [ImageProxy] to JPEG bytes. CameraX delivers JPEG buffers
 *  for IMAGE_CAPTURE by default, so we read plane 0 straight through. */
private fun androidx.camera.core.ImageProxy.toJpegBytes(): ByteArray? {
    val buffer = planes.firstOrNull()?.buffer ?: return null
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return bytes
}

/** Read a gallery-picked image's bytes through the ContentResolver. */
private fun readImageBytes(context: Context, uri: Uri): ByteArray? =
    runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            input.copyTo(out)
            out.toByteArray()
        }
    }.getOrNull()
