package com.kingzcheung.xime.ocr

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.*
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as DrawSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import com.kingzcheung.xime.camera.CameraPreviewSession
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow

/** Text or table output. Kept for this process only, so the shipped default stays plain text. */
internal object OcrTableMode { val format = MutableStateFlow<OcrTableLayout.Format?>(null) }

private val OCR_TABLE_CHOICES = listOf(null to "文字", OcrTableLayout.Format.MARKDOWN to "表格 · Markdown",
    OcrTableLayout.Format.CSV to "表格 · CSV", OcrTableLayout.Format.SPACES to "表格 · 空格分隔")

@Composable
internal fun OcrCameraPanel(
    active: Boolean, background: Color, textColor: Color, accent: Color,
    onClose: () -> Unit, onConfirmed: (String) -> Unit, modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val onResult by rememberUpdatedState(onConfirmed)
    var confirmed by remember { mutableStateOf(false) }
    var permission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var message by remember { mutableStateOf("将文字对准中间框") }
    var liveText by remember { mutableStateOf("") }
    var ready by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var capture by remember { mutableIntStateOf(0) }
    val captureRequest = remember { java.util.concurrent.atomic.AtomicInteger(0) }
    SideEffect { captureRequest.set(capture) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val galleryUri by OcrGallerySelection.uri.collectAsState()
    var galleryImage by remember(galleryUri) { mutableStateOf<Bitmap?>(null) }
    var loadingImage by remember(galleryUri) { mutableStateOf(false) }
    var imageTransform by remember(galleryUri, viewSize) { mutableStateOf(OcrImageTransform()) }
    var galleryJob by remember { mutableStateOf<Job?>(null) }
    val latestActive by rememberUpdatedState(active)
    val tableFormat by OcrTableMode.format.collectAsState()
    var tableMenu by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    // Table mode widens the frame to the space between the hint line and the floating controls.
    val scanFrame = if (tableFormat == null) OcrScanGeometry.TEXT else with(density) {
        OcrScanGeometry.table(viewSize.height, 44.dp.toPx(), 60.dp.toPx())
    }
    // The camera analyzer reads both off the main thread; a frame is read in the mode it was cropped for.
    val tableRequest = remember { AtomicReference<OcrTableLayout.Format?>(null) }
    val frameRequest = remember { AtomicReference(OcrScanGeometry.TEXT) }
    SideEffect { tableRequest.set(tableFormat); frameRequest.set(scanFrame) }
    LaunchedEffect(tableFormat) { liveText = "" }
    LaunchedEffect(capturing) { if (capturing) tableMenu = false }
    fun hint(text: String) = if (tableFormat == null) text else text.replace("将文字对准中间框", "将整张表格放进框内")
        .replace("将文字放入中间框", "将整张表格放进框内")
    LaunchedEffect(active, galleryUri) {
        galleryJob?.cancel(); capturing = false; liveText = ""; confirmed = false
    }
    LaunchedEffect(galleryUri) {
        val uri = galleryUri ?: return@LaunchedEffect
        liveText = ""; capturing = false; loadingImage = true
        try {
            galleryImage = withContext(Dispatchers.IO) { loadOcrGalleryImage(context, uri) }
            message = "移动或缩放图片，将文字放入中间框"
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { message = "无法读取图片，请重新选择" }
        finally { loadingImage = false }
    }
    val frameSize = remember { AtomicReference(IntSize.Zero) }
    val previewView = remember { PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
    } }
    LaunchedEffect(active) {
        permission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
    // 轻量版不带拍照识别模型和引擎（OpenCV）：缺哪个先给下载提示，下载完再开相机。null = 还在检查。
    var modelReady by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(active) { modelReady = withContext(Dispatchers.IO) { OcrModelFiles.available(context) && OcrEngine.available(context) } }
    Box(modifier.fillMaxSize().background(background).testTag("ocr-camera-panel")) {
        Box(Modifier.fillMaxSize().clipToBounds().onSizeChanged { viewSize = it; frameSize.set(it) }) {
            if (modelReady == false) OcrModelDownloadPrompt(textColor, accent, Modifier.align(Alignment.Center)) { modelReady = true }
            else if (galleryUri != null || (permission && active)) {
                if (galleryUri == null) AndroidView({ previewView }, Modifier.fillMaxSize())
                else {
                    val image = galleryImage
                    val pixels = remember(image) { image?.asImageBitmap() }
                    Canvas(Modifier.fillMaxSize().testTag("ocr-gallery-image")
                        .pointerInput(galleryUri, viewSize, capturing) {
                            if (!capturing) detectTransformGestures { centroid, pan, zoom, _ ->
                                imageTransform = imageTransform.gesture(viewSize.width, viewSize.height,
                                    centroid.x, centroid.y, pan.x, pan.y, zoom)
                                liveText = ""
                            }
                        }) {
                        drawRect(Color.White)
                        if (image != null && pixels != null && size.width > 0 && size.height > 0) {
                            val projection = OcrImageGeometry.projection(image.width, image.height,
                                size.width.toInt(), size.height.toInt(), imageTransform)
                            withTransform({ translate(projection.left, projection.top); scale(projection.scale, projection.scale, Offset.Zero) }) {
                                drawImage(pixels)
                            }
                        }
                    }
                }
                Canvas(Modifier.fillMaxSize().testTag("ocr-scan-mask")) {
                    val l = size.width * scanFrame.left
                    val r = size.width * scanFrame.right
                    val t = size.height * scanFrame.top
                    val b = size.height * scanFrame.bottom
                    val shade = Color.Black.copy(alpha = .62f)
                    drawRect(shade, size = DrawSize(size.width, t))
                    drawRect(shade, Offset(0f, b), DrawSize(size.width, size.height - b))
                    drawRect(shade, Offset(0f, t), DrawSize(l, b - t))
                    drawRect(shade, Offset(r, t), DrawSize(size.width - r, b - t))
                    drawRect(accent, Offset(l, t), DrawSize(r - l, b - t), style = Stroke(2.dp.toPx()))
                }
                // A preview is information, not another confirmation step or opaque panel.
                Text(if (capturing) "正在识别…" else if (loadingImage) "正在读取图片…" else liveText.ifBlank { hint(message) },
                    color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 48.dp, vertical = 8.dp)
                        .testTag("ocr-live-text"),
                    style = MaterialTheme.typography.labelMedium.copy(shadow = Shadow(Color.Black, Offset(0f, 1f), 4f)))
                // Output format: a small chip in the corner, options listed in place (no popup window in the IME).
                Column(Modifier.align(Alignment.TopStart).padding(start = 6.dp, top = 6.dp)) {
                    val chip = Modifier.clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = .45f))
                    Text(if (tableFormat == null) "文字" else "表格", color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = chip.clickable(enabled = !capturing && !confirmed) { tableMenu = !tableMenu }
                            .padding(horizontal = 8.dp, vertical = 5.dp).testTag("ocr-table-mode"))
                    if (tableMenu) Column(Modifier.padding(top = 4.dp).then(chip).padding(vertical = 2.dp).testTag("ocr-table-menu")) {
                        OCR_TABLE_CHOICES.forEach { (format, label) ->
                            Row(Modifier.clickable {
                                OcrTableMode.format.value = format; tableMenu = false
                            }.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Check, null, Modifier.size(14.dp),
                                    tint = if (format == tableFormat) Color.White else Color.Transparent)
                                Spacer(Modifier.width(6.dp))
                                Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
                if (galleryUri != null) IconButton(onClick = {
                    OcrGallerySelection.uri.value = null; liveText = ""; message = "将文字对准中间框"
                }, enabled = !capturing, modifier = Modifier.align(Alignment.TopEnd).testTag("ocr-return-camera")) {
                    Icon(Icons.Default.CameraAlt, "返回拍照", tint = Color.White)
                }
            } else Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("允许相机后，在键盘内拍照识字", color = textColor)
                TextButton(onClick = { DocumentOcrActivity.requestCamera(context) }) { Text("允许相机", color = accent) }
            }
        }
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .height(48.dp).testTag("ocr-floating-controls"),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { OcrGallerySelection.uri.value = null; onClose() },
                colors = ButtonDefaults.textButtonColors(containerColor = background.copy(alpha = .65f), contentColor = textColor)) { Text("取消") }
            FilledTonalButton(onClick = captureClick@ {
                if (capturing || confirmed) return@captureClick
                // The shutter itself confirms the stable text already visible to the user.
                if (galleryUri == null && liveText.isNotBlank()) {
                    confirmed = true
                    onResult(liveText)
                    return@captureClick
                }
                liveText = ""; capturing = true
                val image = galleryImage
                if (galleryUri == null) capture++
                else if (image != null) {
                    val transform = imageTransform
                    val size = viewSize
                    val table = tableFormat
                    val frame = scanFrame
                    galleryJob = scope.launch {
                        var strip: Bitmap? = null
                        val client = OcrRecognitionClient(context.applicationContext)
                        try {
                            val cropped = withContext(Dispatchers.Default) {
                                cropGalleryStrip(image, size.width, size.height, transform, frame).also { strip = it }
                            }
                            val quality = withContext(Dispatchers.Default) { OcrFrameQuality.assess(cropped) }
                            val result = if (quality == OcrFrameQuality.Verdict.READY) client.recognize(cropped, table) else ""
                            if (result.isNotBlank() && latestActive && !confirmed) {
                                confirmed = true
                                onResult(result.trim())
                            }
                            else message = if (table == null) "框内未识别到文字，请移动或缩放图片" else "框内未识别到表格文字，请移动或缩放图片"
                        } catch (_: TimeoutCancellationException) { message = "识别超时，请重试" }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { message = "识别失败，请重试" }
                        finally { strip?.recycle(); client.close(); capturing = false }
                    }
                } else capturing = false
            }, enabled = !capturing && !confirmed && active && viewSize.width > 0 && viewSize.height > 0 &&
                (if (galleryUri == null) permission && ready else galleryImage != null && !loadingImage),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = accent.copy(alpha = .9f), contentColor = background),
                modifier = Modifier.testTag("ocr-capture")) {
                Icon(Icons.Default.CameraAlt, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp));
                Text(if (galleryUri == null) "拍照识别" else "识别")
            }
            TextButton(onClick = { DocumentOcrActivity.pickImage(context) }, enabled = !capturing && !loadingImage,
                colors = ButtonDefaults.textButtonColors(containerColor = background.copy(alpha = .65f), contentColor = textColor),
                modifier = Modifier.testTag("ocr-gallery")) { Text("相册") }
        }
    }
    if (galleryUri == null && permission && active && viewSize.width > 0 && viewSize.height > 0) {
        DisposableEffect(owner, previewView, viewSize) {
            val alive = AtomicBoolean(true)
            val busy = AtomicBoolean(false)
            val main = ContextCompat.getMainExecutor(context)
            val client = OcrRecognitionClient(context.applicationContext)
            val stablePreview = OcrPreviewStability()
            var lastFrame = 0L
            var completedCapture = captureRequest.get()
            var job: Job? = null
            val camera = CameraPreviewSession(context, owner, previewView, Size(1920, 1080), onFrame = { frame ->
                    val now = SystemClock.elapsedRealtime()
                    val requestId = captureRequest.get()
                    val finalFrame = requestId > completedCapture
                    if (alive.get() && (finalFrame || now - lastFrame >= 700) && busy.compareAndSet(false, true)) {
                        lastFrame = now
                        val table = tableRequest.get()
                        if (table != null && !finalFrame) {
                            // A table is read once, on the shutter: a live preview would re-read every cell each 0.7 s.
                            main.execute { if (alive.get()) ready = true }
                            busy.set(false)
                        } else try {
                            val bitmap = cropCameraStrip(frame, frameSize.get(), frameRequest.get())
                            val quality = OcrFrameQuality.assess(bitmap)
                            main.execute {
                                if (!alive.get()) { bitmap.recycle(); busy.set(false) }
                                else {
                                    ready = true
                                    if (quality != OcrFrameQuality.Verdict.READY) {
                                        stablePreview.update(""); liveText = ""
                                        message = if (quality == OcrFrameQuality.Verdict.DARK) "光线不足，请对准有光的文字" else "将文字对准中间框"
                                        if (finalFrame) { completedCapture = requestId; capturing = false }
                                        bitmap.recycle(); busy.set(false)
                                        return@execute
                                    }
                                    job = scope.launch {
                                        try {
                                            val result = client.recognize(bitmap, table)
                                            if (alive.get()) {
                                                if (finalFrame) {
                                                    completedCapture = requestId
                                                    capturing = false
                                                    if (result.isNotBlank() && latestActive && !confirmed) {
                                                        confirmed = true
                                                        onResult(result.trim())
                                                    } else message = "框内未识别到文字，请对准后重拍"
                                                } else if (requestId == captureRequest.get()) {
                                                    liveText = stablePreview.update(result)
                                                    message = if (result.isBlank()) "框内未识别到清晰文字" else "将文字对准中间框"
                                                }
                                            }
                                        } catch (_: TimeoutCancellationException) {
                                            if (alive.get()) { liveText = ""; stablePreview.update(""); capturing = false; message = "识别超时，请重拍"; completedCapture = requestId }
                                        } catch (e: CancellationException) { throw e }
                                        catch (_: Exception) {
                                            if (alive.get()) { liveText = ""; stablePreview.update(""); capturing = false; message = "识别失败，请重拍"; completedCapture = requestId }
                                        } finally { bitmap.recycle(); busy.set(false) }
                                    }
                                }
                            }
                        } catch (_: Exception) { busy.set(false) }
                    }
            }, onError = { ready = false; capturing = false; message = "相机无法启动，请关闭后重试" })
            onDispose {
                alive.set(false); ready = false; camera.close(); job?.cancel(); client.close()
            }
        }
    }
}

internal fun cropCameraStrip(frame: ImageProxy, view: IntSize, scan: OcrScanFrame = OcrScanGeometry.TEXT): Bitmap {
    val raw = frame.toBitmap()
    val rect = frame.cropRect
    val matrix = Matrix().apply { postRotate(frame.imageInfo.rotationDegrees.toFloat()) }
    val upright = Bitmap.createBitmap(raw, rect.left, rect.top, rect.width(), rect.height(), matrix, true)
    if (upright !== raw) raw.recycle()
    try {
        val crop = OcrScanGeometry.crop(upright.width, upright.height, view.width, view.height, scan)
        val strip = Bitmap.createBitmap(upright, crop.left, crop.top, crop.width, crop.height)
        if (strip.width <= 1600) return strip.copy(Bitmap.Config.ARGB_8888, false).also { if (strip !== upright) strip.recycle() }
        return Bitmap.createScaledBitmap(strip, 1600, (strip.height * 1600f / strip.width).toInt().coerceAtLeast(1), true)
            .also { if (strip !== upright) strip.recycle() }
    } finally { upright.recycle() }
}
