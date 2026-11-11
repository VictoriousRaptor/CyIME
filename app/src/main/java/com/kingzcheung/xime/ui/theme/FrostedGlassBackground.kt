package com.kingzcheung.xime.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.kingzcheung.xime.settings.BackgroundConfig
import com.kingzcheung.xime.settings.FrostedGlassConfig
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * The theme is a translucent tint over the real window behind the keyboard.
 * ImeGlassBlurWindow blurs that window; this modifier never fades text/icons.
 * Theme images keep their cached material rendering and do not capture the screen.
 */
/**
 * 「我的图片」开着玻璃时，背景图不模糊、不变淡：用户自己选的图要看得见（用户 10-31：被模糊掉了，什么都看不到）。
 * 按键、候选栏照旧按玻璃透明；内置图片主题不变。
 */
internal fun FrostedGlassConfig.forBackground(background: BackgroundConfig?): FrostedGlassConfig =
    if (CustomImageTheme.isCustomImage(background)) copy(blurRadiusDp = 0f, backgroundOpacity = 1f) else this

@Composable
internal fun Modifier.frostedGlassBackground(
    background: BackgroundConfig?,
    isDark: Boolean,
    fallbackColor: Color,
    config: FrostedGlassConfig,
    translucentSurface: Boolean = false,
    /** 动态背景时键盘本体的图交给这一层画（见 [BackgroundImageSlot]），这里只画图以外的底色。 */
    imageSlot: BackgroundImageSlot? = null,
): Modifier {
    @Suppress("NAME_SHADOWING") val background = background.withCurrentSlide()
    // Embedded keyboard surfaces sample the same window-space image. Popup windows
    // have their own coordinate origin and must keep their independent backdrop.
    val shared = LocalSharedKeyboardBackdrop.current
    val root = LocalView.current.rootView
    // 「柔化顶部边缘」：只对输入法窗口里的固定键盘；弹窗另有坐标原点，不跟。
    val feather = LocalGlassTopFeather.current?.takeIf { !translucentSurface && it.windowRoot === root }
    if (!translucentSurface && shared != null && root === shared.windowRoot) {
        val originState = remember { mutableStateOf(Offset.Zero) }
        var origin by originState
        val report = shared.reportSurfaceTop
        val surfaceKey = remember { Any() }
        DisposableEffect(report, surfaceKey) { onDispose { report(surfaceKey, null) } }
        if (imageSlot != null) SideEffect {
            // 图层里按原样画（缩放、位移由图层做）；柔化上沿也在图层外面那一层做，见 KeyboardBackgroundMotionLayer。
            val frame: () -> androidx.compose.ui.geometry.Rect? = {
                val offset = shared.frame.offsetIn(originState.value)
                androidx.compose.ui.geometry.Rect(offset, androidx.compose.ui.geometry.Size(
                    shared.frame.size.width.toFloat(), shared.frame.size.height.toFloat()))
            }
            val image = shared.image?.takeIf { shared.opacity > 0f }
            val draw: (DrawScope.() -> Unit)? = if (image == null) null else ({
                if (shared.frame.size.width > 0 && shared.frame.size.height > 0) {
                    val offset = shared.frame.offsetIn(originState.value)
                    // 不在这里裁成卡片大小：图层按整窗的 frame 平移、缩放，卡片比窗口窄时（横屏调窄）
                    // 裁过的图挪不了那么远，边上露底色。外面那层 clipToBounds 在变形之后再裁。
                    translate(offset.x, offset.y) {
                        drawImage(image, dstSize = shared.frame.size, alpha = shared.opacity,
                            filterQuality = FilterQuality.Medium)
                    }
                }
            })
            imageSlot.update(image, frame, draw)
        }
        return onGloballyPositioned {
            origin = it.positionInWindow()
            report(surfaceKey, origin.y)
        }.drawWithContent {
            if (shared.opacity > 0f) drawUnderTopFeather(feather?.top?.value?.minus(origin.y), feather?.heightPx ?: 0f) {
                val image = shared.image?.takeIf { shared.frame.size.width > 0 && shared.frame.size.height > 0 }
                if (image == null) drawRect(shared.fallback, alpha = shared.opacity)
                else {
                    val offset = shared.frame.offsetIn(origin)
                    // The window/inset frame and this surface may move on different layout passes.
                    // Tint only uncovered pixels so translucent glass is composited exactly once.
                    clipRect(offset.x, offset.y, offset.x + shared.frame.size.width,
                        offset.y + shared.frame.size.height, androidx.compose.ui.graphics.ClipOp.Difference) {
                        drawRect(shared.fallback, alpha = shared.opacity)
                    }
                    if (imageSlot == null) clipRect {
                        withSharedBackdropMotion(shared, offset) {
                            translate(offset.x, offset.y) {
                                drawImage(image, dstSize = shared.frame.size, alpha = shared.opacity,
                                    filterQuality = FilterQuality.Medium)
                            }
                        }
                    }
                }
            }
            drawContent()
        }
    }
    val normalized = config.normalized()
    var targetSize by remember { mutableStateOf(IntSize.Zero) }
    // Floating capsules resize during docking; keep their backdrop cache stable throughout.
    // 不模糊（「我的图片」）时画大一点，铺到长条上不发虚。
    val renderSize = if (translucentSurface) translucentBackdropSize(normalized.blurRadiusDp) else targetSize
    // 这张固定大小的图按条自己的比例铺满（多的裁掉），不再拉成条的形状：图片在实体键盘浮条上被压扁（用户 11-09）。
    // 渐变照旧拉满，起止颜色才落在条的两头。
    val coverImage = translucentSurface && background?.type == "image"
    val pixels = rememberFrostedBackgroundImage(background, isDark, fallbackColor, normalized, renderSize,
        clipboardInMotion = LocalClipboardBackdropMotion.current && !translucentSurface)
    val blurredImage = pixels.image
    if (imageSlot != null) SideEffect {
        val image = blurredImage?.takeIf { normalized.backgroundOpacity > 0f }
        val draw: (DrawScope.() -> Unit)? = if (image == null) null else ({
            val layer = pixels.layer(IntSize(size.width.roundToInt(), size.height.roundToInt()))
            if (layer != null) drawBackdropLayer(layer, normalized.backgroundOpacity)
            else drawImage(image, dstOffset = IntOffset.Zero, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                alpha = normalized.backgroundOpacity, filterQuality = FilterQuality.Medium)
        })
        imageSlot.update(image, { null }, draw)
    }

    // 底部圆角时没有共用背景，各层自己画；柔化上沿要知道自己在窗口里的位置。
    var featherOrigin by remember { mutableStateOf(0f) }
    val positioned = if (feather == null) this else onGloballyPositioned { featherOrigin = it.positionInWindow().y }
    return positioned.onSizeChanged { targetSize = it }.drawWithContent {
        // The cached theme bitmap is opaque. Draw exactly one source with material alpha,
        // or its fallback while loading; labels and keycaps remain outside this operation.
        val opacity = normalized.backgroundOpacity
        if (opacity > 0f) drawUnderTopFeather(feather?.top?.value?.minus(featherOrigin), feather?.heightPx ?: 0f) {
            val image = blurredImage
            val layer = pixels.layer(IntSize(size.width.roundToInt(), size.height.roundToInt()))
            if (layer != null) { if (imageSlot == null) drawBackdropLayer(layer, opacity) }
            else if (image == null) drawRect(fallbackColor, alpha = opacity)
            else if (imageSlot == null && coverImage) drawBackdropLayer(BackdropLayer(image,
                coverRect(IntSize(image.width, image.height), IntSize(size.width.roundToInt(), size.height.roundToInt())),
                null), opacity)
            else if (imageSlot == null) drawImage(
                image,
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                alpha = opacity,
                filterQuality = FilterQuality.Medium,
            )
        }
        drawContent()
    }
}

/** 悬浮小条（实体键盘浮条、候选条）固定按这个大小画一张背景：模糊的小一点就够，不模糊时 1024 宽。 */
internal fun translucentBackdropSize(blurRadiusDp: Float): IntSize =
    if (blurRadiusDp < 1f) IntSize(1024, 512) else IntSize(512, 256)

@Composable
internal fun rememberFrostedBackgroundImage(
    background: BackgroundConfig?,
    isDark: Boolean,
    fallbackColor: Color,
    config: FrostedGlassConfig,
    renderSize: IntSize,
    clipboardInMotion: Boolean = LocalClipboardBackdropMotion.current,
): FrostedBackdropPixels {
    val context = LocalContext.current.applicationContext
    val density = LocalDensity.current.density
    val normalized = config.normalized()
    val source = if (background?.type == "image") {
        if (isDark) background.srcDark ?: background.src else background.src
    } else null
    // 记下解码的是哪张：换图（轮播）时新图解出来之前不重画，接着显示旧的，不闪一下底色。
    var loaded by remember { mutableStateOf<Pair<String?, FrostedSourceImage?>?>(null) }
    LaunchedEffect(context, source) {
        val decoded = source?.takeIf { it.isNotBlank() }?.let {
            withContext(Dispatchers.IO) { decodeFrostedImage(context, it) }
        }
        loaded = source to decoded
    }
    val sourceReady = source.isNullOrBlank() || loaded?.first == source
    val sourceImage = loaded?.takeIf { it.first == source }?.second

    val resizeCache = remember { ClipboardBackdropSizeCache() }
    val cachedSize = resizeCache.resolve(renderSize, clipboardInMotion)
    var blurredImage by remember(background?.copy(src = null, srcDark = null, fit = null, zoom = null, alignX = null, alignY = null),
        isDark, fallbackColor) { mutableStateOf<ImageBitmap?>(null) }
    var renderedFor by remember(background?.copy(src = null, srcDark = null, fit = null, zoom = null, alignX = null, alignY = null),
        isDark, fallbackColor) { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(background, isDark, fallbackColor, sourceImage, sourceReady, cachedSize, density, normalized.blurRadiusDp) {
        if (cachedSize.width <= 0 || cachedSize.height <= 0 || !sourceReady) return@LaunchedEffect
        blurredImage = withContext(Dispatchers.Default) {
            val coroutineContext = currentCoroutineContext()
            renderFrostedBackground(
                background, isDark, fallbackColor.toArgb(), sourceImage, cachedSize,
                normalized.blurRadiusDp * density,
                checkCancelled = { coroutineContext.ensureActive() },
            ).asImageBitmap()
        }
        renderedFor = cachedSize
    }

    // 拉剪贴板时的整图，见 [ResizingBackdropImage]。只在拖动、回弹和其后重画的那一下存在。
    var picture by remember(background, isDark, fallbackColor, normalized.blurRadiusDp) {
        mutableStateOf<ResizingBackdropImage?>(null)
    }
    LaunchedEffect(clipboardInMotion, background, isDark, fallbackColor, sourceImage, cachedSize, density, normalized.blurRadiusDp) {
        val image = sourceImage
        val imageBackground = background?.takeIf { it.type == "image" }
        if (!clipboardInMotion || picture != null || imageBackground == null || image == null ||
            cachedSize.width <= 0 || cachedSize.height <= 0) return@LaunchedEffect
        picture = withContext(Dispatchers.Default) {
            val coroutineContext = currentCoroutineContext()
            renderResizingBackdrop(
                imageBackground, isDark, fallbackColor.toArgb(), image, cachedSize,
                normalized.blurRadiusDp * density,
                checkCancelled = { coroutineContext.ensureActive() },
            )
        }
    }
    val isImage = background?.type == "image" && sourceImage != null
    val draw = backdropResizeDraw(clipboardInMotion, picture != null, isImage, renderedFor, renderSize)
    if (picture != null && draw != BackdropResizeDraw.Picture) SideEffect { picture = null }
    return FrostedBackdropPixels(blurredImage, renderedFor, clipboardInMotion, isImage,
        picture.takeIf { draw == BackdropResizeDraw.Picture })
}

private data class FrostedSourceImage(val bitmap: Bitmap, val originalWidth: Int, val originalHeight: Int)

private fun decodeFrostedImage(context: Context, source: String): FrostedSourceImage? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    openThemeImageStream(context, source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
        val options = BitmapFactory.Options().apply { inSampleSize = 1 }
        while (max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 2048) {
            options.inSampleSize *= 2
        }
        openThemeImageStream(context, source)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?.let { FrostedSourceImage(it, bounds.outWidth, bounds.outHeight) }
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}

private fun renderFrostedBackground(
    background: BackgroundConfig?,
    isDark: Boolean,
    fallback: Int,
    image: FrostedSourceImage?,
    targetSize: IntSize,
    blurRadiusPx: Float,
    checkCancelled: () -> Unit,
): Bitmap {
    // Fine details disappear under blur, so keep that cache small. Radius zero retains
    // full keyboard resolution (bounded for unusually large displays).
    val maxDimension = if (blurRadiusPx < 1f) 2048 else 512
    val scale = min(1f, maxDimension.toFloat() / max(targetSize.width, targetSize.height))
    val width = (targetSize.width * scale).roundToInt().coerceAtLeast(1)
    val height = (targetSize.height * scale).roundToInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val base = background?.let { resolveSolidColorHex(it, isDark) }
            ?.let { (0xff000000L or it).toInt() } ?: fallback
        canvas.drawColor(base or 0xff000000.toInt())
        val colors = background?.takeIf { it.type == "gradient" }?.let {
            if (isDark) it.colorsDark ?: it.colors else it.colors
        }
        if (colors != null && colors.size >= 2) {
            val radians = Math.toRadians((background?.angle ?: 0).toDouble())
            val x = cos(radians).toFloat()
            val y = sin(radians).toFloat()
            val divisor = (abs(x) + abs(y)).coerceAtLeast(0.00001f)
            paint.shader = LinearGradient(
                width * (0.5f - x / divisor * 0.5f), height * (0.5f - y / divisor * 0.5f),
                width * (0.5f + x / divisor * 0.5f), height * (0.5f + y / divisor * 0.5f),
                colors.map { (0xff000000L or it).toInt() }.toIntArray(), null, Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.shader = null
        }
        if (background?.type == "image" && image != null) {
            canvas.save()
            canvas.scale(width.toFloat() / targetSize.width, height.toFloat() / targetSize.height)
            canvas.drawBitmap(
                image.bitmap, null,
                frostedImageBounds(image, targetSize, background), paint,
            )
            canvas.restore()
            // 与非玻璃时相同的图片遮罩，否则玻璃下图片原样露出，键面文字压在花图上。
            val overlayAlpha = (if (isDark) background.overlayAlphaDark ?: background.overlayAlpha
                else background.overlayAlpha)?.coerceIn(0f, 1f)
            if (overlayAlpha != null && overlayAlpha > 0f) {
                val overlay = (if (isDark) background.overlayColorDark ?: background.overlayColor
                    else background.overlayColor) ?: 0L
                canvas.drawColor(((overlayAlpha * 255).roundToInt() shl 24) or (overlay and 0xFFFFFF).toInt())
            }
        }
        checkCancelled()
        if (blurRadiusPx >= 1f) {
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            blurFrostedPixels(pixels, width, height, blurRadiusPx * scale, checkCancelled)
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        }
        return bitmap
    } catch (error: Throwable) {
        bitmap.recycle()
        throw error
    }
}

/**
 * The whole picture for [ResizingBackdropImage], as [renderFrostedBackground] would show it (overlay, blur),
 * at the resolution it has in [viewport]. Its place is computed per frame from the current size.
 */
private fun renderResizingBackdrop(
    background: BackgroundConfig,
    isDark: Boolean,
    fallback: Int,
    image: FrostedSourceImage,
    viewport: IntSize,
    blurRadiusPx: Float,
    checkCancelled: () -> Unit,
): ResizingBackdropImage {
    val placed = frostedImageBounds(image, viewport, background)
    val maxDimension = if (blurRadiusPx < 1f) 2048 else 512
    val scale = minOf(1f, maxDimension / max(placed.width(), placed.height()).coerceAtLeast(1f),
        image.bitmap.width / placed.width().coerceAtLeast(1f))
    val width = (placed.width() * scale).roundToInt().coerceAtLeast(1)
    val height = (placed.height() * scale).roundToInt().coerceAtLeast(1)
    val overlayAlpha = (if (isDark) background.overlayAlphaDark ?: background.overlayAlpha
        else background.overlayAlpha)?.coerceIn(0f, 1f) ?: 0f
    val overlay = Color(0xff000000L or ((if (isDark) background.overlayColorDark ?: background.overlayColor
        else background.overlayColor) ?: 0L))
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
        val canvas = Canvas(bitmap)
        canvas.drawBitmap(image.bitmap, null, android.graphics.Rect(0, 0, width, height),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        if (overlayAlpha > 0f) canvas.drawColor(overlay.copy(alpha = overlayAlpha).toArgb())
        checkCancelled()
        if (blurRadiusPx >= 1f) {
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            blurFrostedPixels(pixels, width, height, blurRadiusPx * scale, checkCancelled)
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        }
    } catch (error: Throwable) {
        bitmap.recycle()
        throw error
    }
    val uncovered = androidx.compose.ui.graphics.lerp(Color(fallback or 0xff000000.toInt()),
        overlay, overlayAlpha)
    return ResizingBackdropImage(bitmap.asImageBitmap(), uncovered) { size ->
        val rect = frostedImageBounds(image, size, background)
        androidx.compose.ui.geometry.Rect(rect.left, rect.top, rect.right, rect.bottom)
    }
}

private fun frostedImageBounds(image: FrostedSourceImage, size: IntSize, background: BackgroundConfig): RectF {
    val width = size.width.toFloat()
    val height = size.height.toFloat()
    val sourceWidth = image.originalWidth.toFloat()
    val sourceHeight = image.originalHeight.toFloat()
    val fit = background.fit ?: "cover"
    if (fit == "fill") return RectF(0f, 0f, width, height)
    if (fit == "none") return RectF(0f, 0f, sourceWidth, sourceHeight)
    // 与不开玻璃时同一套摆放（含「我的图片」的缩放与对齐）；没盖满处是上面先铺的底色。
    val frame = placeThemeImage(sourceWidth, sourceHeight, width, height, fit,
        background.zoom, background.alignX, background.alignY)
    return RectF(frame.left, frame.top, frame.right, frame.bottom)
}
