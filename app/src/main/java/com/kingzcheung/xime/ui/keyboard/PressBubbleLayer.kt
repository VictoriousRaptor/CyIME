package com.kingzcheung.xime.ui.keyboard

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.core.animate
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.core.graphics.withSave
import com.kingzcheung.xime.ui.theme.CyMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.floor

/** The decor overlay draws above the clipped keyboard card and has no touch or focus target. */
@Composable
internal fun PressBubbleLayer(controller: SwipeBubbleController, background: Color, text: Color,
    accent: Color, keyboardWidth: Float) {
    val root = LocalView.current.rootView
    val layer = remember(root) { PressBubbleDrawable(root) }
    val rotation = LocalFloatingKeyboardRotation.current
    val density = LocalDensity.current
    val referenceHeight = bubbleReferenceHeightDp()
    val typeface = AppFonts.keyLabelTypeface
    DisposableEffect(root, layer) {
        root.overlay.add(layer)
        onDispose { root.overlay.remove(layer) }
    }
    // 带着系统动画时长缩放的协程环境：松手淡出跟着系统「移除动画」走。
    val scope = rememberCoroutineScope()
    DisposableEffect(controller, layer, rotation, density, referenceHeight, keyboardWidth, background, text, accent, typeface) {
        var fadeJob: Job? = null
        var generation = 0
        val draw: (SwipeState, Rect) -> Unit = { state, bounds ->
            val data = if (state.isPlainPress()) measureSwipeBubble(state, bounds, background, text,
                accent, bounds.width, keyboardWidth, density, referenceHeight, keyLabelTypeface = typeface) else null
            fadeJob?.cancel()
            fadeJob = null
            val current = ++generation
            if (data == null && controller.clearedAfterRelease && layer.isShowing) {
                // 松手停留结束后淡出（[CyMotion.EXIT_FAST]），不是一下没了；淡出中按下别的键直接换成新气泡。
                fadeJob = scope.launch {
                    try {
                        animate(1f, 0f, animationSpec = CyMotion.exit(CyMotion.EXIT_FAST)) { value, _ -> layer.fade(value) }
                    } finally {
                        // 被新按键打断时新气泡已经画上，不能把它清掉。
                        if (current == generation) layer.show(null, rotation)
                    }
                }
            } else {
                layer.show(data, rotation)
            }
        }
        controller.onPreviewChanged = draw
        draw(controller.state, controller.keyBounds)
        onDispose {
            fadeJob?.cancel()
            controller.onPreviewChanged = null
        }
    }
    Spacer(Modifier.onGloballyPositioned { coordinates ->
        layer.anchorTo(coordinates.parentLayoutCoordinates ?: coordinates)
    }.semantics { if (controller.state.isPlainPress()) testTag = "key-choice-popup" })
}

private fun SwipeState.isPlainPress() =
    isPressed && !isSwiping && !isLongPress && keyFlick == null && !pressedText.isNullOrEmpty()

private class PressBubbleDrawable(private val host: View) : Drawable() {
    private val location = IntArray(2)
    private val transform = Matrix()
    private val area = RectF()
    private var anchor: LayoutCoordinates? = null
    private var rotation = 0f
    private var preview: BubbleDrawData? = null
    private var drawing: BubbleDrawData? = null
    private var fadeAlpha = 1f

    val isShowing get() = preview != null

    fun anchorTo(coordinates: LayoutCoordinates) {
        anchor = coordinates
        place()
    }

    /**
     * 每次显示时现算键盘在窗口里的位置，不用上次布局时记下的值：键盘窗口里的内容会在没有
     * Compose 重新布局的情况下挪动（语音悬浮条和键盘互换时的上浮动画、窗口高度变化），
     * 记下的旧位置会让气泡落到别的行（用户 11-06：按住 j，气泡出在最下面一排）。
     */
    private fun currentOrigin(): Offset? {
        val coordinates = anchor?.takeIf { it.isAttached } ?: return null
        val windowOrigin = coordinates.localToWindow(Offset.Zero)
        host.getLocationInWindow(location)
        return Offset(windowOrigin.x - location[0], windowOrigin.y - location[1])
    }

    fun show(data: BubbleDrawData?, angle: Float) {
        if (preview == data && rotation == angle && fadeAlpha == 1f) return
        preview = data
        rotation = angle
        fadeAlpha = 1f
        place()
    }

    /** 松手后的淡出：只改这一块的透明度重画，不动位置。 */
    fun fade(alpha: Float) {
        if (preview == null) return
        fadeAlpha = alpha.coerceIn(0f, 1f)
        invalidateSelf()
    }

    private fun place() {
        val data = preview
        val origin = if (data == null) null else currentOrigin()
        if (data == null || origin == null) {
            drawing = null
            setBounds(0, 0, 0, 0)
            return
        }
        val shadow = ceil(data.shadowRadiusPx)
        area.set(data.boxLeft - shadow, data.boxTop - shadow,
            data.boxLeft + maxOf(data.pathBodyLeft + data.pathBodyWidth, data.pointerLeftInBox + data.keyWidthPx) + shadow,
            data.boxTop + data.bodyHeightPx + data.pointerHeightPx + shadow)
        transform.setRotate(rotation)
        transform.postTranslate(origin.x, origin.y)
        transform.mapRect(area)
        val dx = area.left.coerceIn(0f, (host.width - area.width()).coerceAtLeast(0f)) - area.left
        val dy = area.top.coerceIn(0f, (host.height - area.height()).coerceAtLeast(0f)) - area.top
        transform.postTranslate(dx, dy)
        area.offset(dx, dy)
        drawing = if (rotation == 0f && dy != 0f) data.copy(pointerHeightPx = (data.pointerHeightPx - dy).coerceAtLeast(0f)) else data
        setBounds(floor(area.left).toInt(), floor(area.top).toInt(), ceil(area.right).toInt(), ceil(area.bottom).toInt())
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val data = drawing ?: return
        canvas.withSave {
            canvas.concat(transform)
            paintSwipeBubble(canvas, if (fadeAlpha < 1f) data.copy(alpha = fadeAlpha) else data)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Android")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
