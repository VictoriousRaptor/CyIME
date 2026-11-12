package com.kingzcheung.xime.ui.keyboard

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.core.graphics.withSave
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.keyboard.KeyboardDimensions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val BubbleBodyHeight = KeyboardDimensions.BubbleHeightDown
private val BubbleCornerRadius = KeyboardDimensions.BubbleCornerRadius
private val BubbleScreenMargin = 4.dp

private val bubblePath = Path()
private val bubbleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
private val bubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
private val bubbleMeasurePaint = Paint(Paint.ANTI_ALIAS_FLAG)
private val bubbleBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
private val bubbleLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
private val bubbleIconPaint = Paint()
private val SHADOW_COLOR = android.graphics.Color.argb(0x26, 0, 0, 0)

data class BubbleDrawData(
    val boxLeft: Float,
    val boxTop: Float,
    val pathBodyLeft: Float,
    val pathBodyWidth: Float,
    val pointerLeftInBox: Float,
    val keyWidthPx: Float,
    val bodyHeightPx: Float,
    val pointerHeightPx: Float,
    val cornerRadiusPx: Float,
    val isLeftFlush: Boolean,
    val isRightFlush: Boolean,
    val bgColor: Int,
    val textColor: Int,
    val displayText: String?,
    val isLongPressMode: Boolean,
    val longPressItems: List<String>,
    val selectedLongPressIndex: Int,
    val bodyWidth: Float,
    val textStartX: Float,
    val keyLabelTypeface: Typeface,
    val shadowRadiusPx: Float,
    val textSizePx: Float,
    val selectedFontSizePx: Float,
    val normalFontSizePx: Float,
    val selectedBgRadiusPx: Float,
    val longPressIconBitmaps: List<Bitmap> = emptyList(),
    val accentArgb: Int = 0xFF8F73E2.toInt(),
    val gapPx: Float = 0f,
    /** 动画中的高亮位置（可在两项之间）；静止时等于 [selectedLongPressIndex]。 */
    val selectedPosition: Float = selectedLongPressIndex.toFloat(),
    /** 长按选项从按键气泡展开的进度：0 是按键上方的圆气泡，1 是完整的选项条。 */
    val reveal: Float = 1f,
    /** 整个气泡的透明度，出现和松手淡出用。 */
    val alpha: Float = 1f,
)

/** 按压气泡抬起后的滞留时长：随抬起瞬间消失看不清键位提示（主流输入法约 50~80ms），取中值。 */
private const val PRESS_BUBBLE_RELEASE_DELAY_MS = 60L

/**
 * 各布局共享的气泡状态持有器。
 *
 * 封装"按压气泡抬起后短暂滞留"逻辑：抬起瞬间不清空状态，而是保留气泡内容与位置
 * [PRESS_BUBBLE_RELEASE_DELAY_MS] 毫秒再消失；期间任意新手势（新键按压/滑动/长按）
 * 会立即取消滞留并切换为新气泡，快速连打无延迟感。
 * 仅对按压气泡滞留——滑动选择与长按选择的气泡在松手时语义上已结束（候选已提交），立即消失。
 */
class SwipeBubbleController(
    private val scope: CoroutineScope,
    showPressBubble: Boolean = false,
) {
    private var pressBubbleEnabled = showPressBubble
    var state by mutableStateOf(SwipeState())
        private set
    var keyBounds by mutableStateOf(Rect(0f, 0f, 0f, 0f))
        private set
    private var releaseJob: Job? = null
    private var overlappingPress = false
    internal var onPreviewChanged: ((SwipeState, Rect) -> Unit)? = null
    /** 这次清空是按键气泡松手停留走完（可以淡出），不是拂动、关开关这类要立即清掉的。 */
    internal var clearedAfterRelease = false
        private set

    private fun publish(value: SwipeState, bounds: Rect = keyBounds) {
        if (state == value && keyBounds == bounds) return
        state = value
        keyBounds = bounds
        onPreviewChanged?.invoke(value, bounds)
    }

    fun dispose() {
        releaseJob?.cancel()
        releaseJob = null
        overlappingPress = false
    }

    fun setPressBubbleEnabled(enabled: Boolean) {
        if (pressBubbleEnabled == enabled) return
        pressBubbleEnabled = enabled
        if (!enabled && state.isPressed && !state.isLongPress && !state.isSwiping) {
            dispose()
            publish(SwipeState())
        }
    }

    fun update(newState: SwipeState, bounds: Rect) {
        val gestureActive = newState.isSwiping || newState.isPressed || newState.isLongPress
        // The old finger can lift while the next key is still held. Its release
        // must not move the preview back or start a timer for the new finger.
        if (!gestureActive && newState.keyFlick == null &&
            (releaseJob != null || (overlappingPress && keyBounds != bounds))) return
        // A vertical flick is rendered inside its key, including below the commit
        // threshold. Clear an existing tap bubble immediately instead of delaying it.
        if (newState.keyFlick != null) {
            dispose()
            publish(SwipeState())
            return
        }
        // 默认关闭时，普通按下/抬起不能让整个键盘重组或启动延迟协程。
        // 拂动/长按是选择反馈，仍需显示；退出这些反馈时只清一次状态。
        if (!pressBubbleEnabled && !newState.isSwiping && !newState.isLongPress) {
            dispose()
            if (state.isSwiping || state.isLongPress || state.isPressed) publish(SwipeState())
            return
        }
        if (newState.isPressed && state.isPressed && bounds != keyBounds && releaseJob == null)
            overlappingPress = true
        if (!gestureActive) overlappingPress = false
        releaseJob?.cancel()
        releaseJob = null
        val prev = state
        if (!gestureActive &&
            prev.isPressed && prev.pressedText != null &&
            !prev.isSwiping && !prev.isLongPress
        ) {
            releaseJob = scope.launch {
                delay(PRESS_BUBBLE_RELEASE_DELAY_MS)
                releaseJob = null
                clearedAfterRelease = true
                try { publish(SwipeState()) } finally { clearedAfterRelease = false }
            }
            return
        }
        publish(newState, bounds)
    }
}

@Composable
fun rememberSwipeBubbleController(): SwipeBubbleController {
    val scope = rememberCoroutineScope()
    val showPressBubble = LocalKeyboardInputPreferences.current.showPressBubble
    val controller = remember(scope) { SwipeBubbleController(scope, showPressBubble) }
    SideEffect { controller.setPressBubbleEnabled(showPressBubble) }
    DisposableEffect(controller) { onDispose { controller.dispose() } }
    return controller
}

@Composable
fun rememberSwipeBubbleDrawData(
    swipeState: SwipeState,
    keyBounds: Rect,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    accentColor: Color = Color(0xFF8F73E2),
    keyWidth: Float,
    keyboardWidth: Float,
): BubbleDrawData? {
    val context = LocalContext.current
    val showPressBubble = LocalKeyboardInputPreferences.current.showPressBubble
    if (swipeState.keyFlick != null) return null
    if (!swipeState.isSwiping && !(showPressBubble && swipeState.isPressed) && !swipeState.isLongPress) return null

    val textColor = (if (swipeState.isDanger) Color.White else keyTextColor).toArgb()
    val longPressIconBitmaps = remember(swipeState.longPressDrawableIds, textColor) {
        swipeState.longPressDrawableIds.mapNotNull { id ->
            val drawable = context.resources.getDrawable(id, context.theme)?.mutate() ?: return@mapNotNull null
            drawable.setTint(textColor)
            val w = drawable.intrinsicWidth.coerceAtLeast(1)
            val h = drawable.intrinsicHeight.coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val bmpCanvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(bmpCanvas)
            bitmap
        }
    }

    return measureSwipeBubble(swipeState, keyBounds, keyBackgroundColor, keyTextColor, accentColor,
        keyWidth, keyboardWidth, LocalDensity.current, bubbleReferenceHeightDp(), longPressIconBitmaps)
}

@Composable
internal fun bubbleReferenceHeightDp() =
    if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) 44f else 56f

internal fun measureSwipeBubble(swipeState: SwipeState, keyBounds: Rect, keyBackgroundColor: Color,
    keyTextColor: Color, accentColor: Color, keyWidth: Float, keyboardWidth: Float, density: Density,
    referenceHeightDp: Float, longPressIconBitmaps: List<Bitmap> = emptyList(),
    keyLabelTypeface: Typeface = AppFonts.keyLabelTypeface): BubbleDrawData? {
    val isLongPressMode = swipeState.isLongPress && swipeState.longPressItems.isNotEmpty()
    val displayText = if (isLongPressMode) null
        else if (swipeState.isPressed) swipeState.pressedText
        else swipeState.swipeText
    if (!isLongPressMode && displayText.isNullOrEmpty()) return null

    val contentScale = adaptiveKeyContentScale(
        keyHeightDp = keyBounds.height / density.density,
        referenceHeightDp = referenceHeightDp,
    )
    val bubbleScale = adaptiveBubbleScale(contentScale)
    val bodyHeightPx = with(density) { BubbleBodyHeight.toPx() } * bubbleScale
    // 尖端完整覆盖按下的按键（与按键同高），宽体锚定按键顶部悬在上方（见 boxTop）。
    // 全部基于真实按键 bounds 计算，不再用 KeyHeight 估算值——
    // 键盘高度被调大时估算失准，宽体会下沉进按键被手指挡住。
    val pointerHeightPx = keyBounds.height
    val cornerRadiusPx = with(density) { BubbleCornerRadius.toPx() } * bubbleScale
    val screenMarginPx = with(density) { BubbleScreenMargin.toPx() }
    val keyWidthPx = keyWidth
    // Same look as the kana flick disc: a single choice is a round bubble, several form a capsule.
    val minBodyWidthPx = bodyHeightPx
    val shadowRadiusPx = with(density) { 4.dp.toPx() } * bubbleScale

    val accentArgb = accentColor.toArgb()
    val isDarkTheme = keyTextColor == Color(0xFFE8EAED)
    val bgColor = (if (swipeState.isDanger) {
        if (swipeState.isSwipeDown) Color(0xFF1A73E8) else Color(0xFFD93025)
    } else keyBackgroundColor).toArgb()
    val textColor = (if (swipeState.isDanger) Color.White else keyTextColor).toArgb()

    bubbleMeasurePaint.textSize = with(density) { 16.sp.toPx() } * bubbleScale

    // A single long-press choice (the digit of a nine-key flick key) is the round bubble too.
    val bodyWidth = if (isLongPressMode && swipeState.longPressItems.size == 1 && swipeState.longPressDrawableIds.isEmpty()) {
        maxOf(bubbleMeasurePaint.measureText(swipeState.longPressItems.first()) + with(density) { 20.dp.toPx() } * bubbleScale, minBodyWidthPx)
    } else if (isLongPressMode) {
        val cellMin = if (swipeState.longPressDrawableIds.isNotEmpty())
            swipeState.longPressItems.size
        else
            maxOf(swipeState.longPressItems.size, 3)
        KeyboardKeyMetrics.longPressMenuWidthPx(keyWidthPx, cellMin, keyboardWidth, screenMarginPx)
    } else {
        maxOf(bubbleMeasurePaint.measureText(displayText!!) + with(density) { 20.dp.toPx() } * bubbleScale, minBodyWidthPx)
    }

    val textSizePx = with(density) { 16.sp.toPx() } * bubbleScale
    val selectedFontSizePx = with(density) { 20.sp.toPx() } * bubbleScale
    val normalFontSizePx = with(density) { 16.sp.toPx() } * bubbleScale
    val selectedBgRadiusPx = with(density) { 6.dp.toPx() } * bubbleScale

    val pointerCenterX = keyBounds.left + keyBounds.width / 2f
    val bodyLeft = (pointerCenterX - bodyWidth / 2f).coerceIn(
        screenMarginPx,
        maxOf(screenMarginPx, keyboardWidth - bodyWidth - screenMarginPx)
    )
    val bodyRight = bodyLeft + bodyWidth
    val pointerLeft = pointerCenterX - keyWidthPx / 2f
    val boxLeft = minOf(bodyLeft, pointerLeft)
    // 浮窗与日语假名圆盘同样悬在按键上方留一点空隙；按键区只保留顶部的小三角指向浮窗。
    val gapPx = with(density) { 6.dp.toPx() } * bubbleScale
    val boxTop = keyBounds.top - bodyHeightPx - gapPx
    val bodyLeftInBox = bodyLeft - boxLeft
    val pointerLeftInBox = pointerLeft - boxLeft
    val pathBodyLeft = bodyLeftInBox
    val pathBodyWidth = bodyWidth

    val paddingPx = with(density) { 10.dp.toPx() } * bubbleScale

    return BubbleDrawData(
        boxLeft = boxLeft,
        boxTop = boxTop,
        pathBodyLeft = pathBodyLeft,
        pathBodyWidth = pathBodyWidth,
        pointerLeftInBox = pointerLeftInBox,
        keyWidthPx = keyWidthPx,
        bodyHeightPx = bodyHeightPx,
        pointerHeightPx = pointerHeightPx + gapPx,
        cornerRadiusPx = cornerRadiusPx,
        isLeftFlush = false,
        isRightFlush = false,
        bgColor = bgColor,
        textColor = textColor,
        displayText = displayText,
        isLongPressMode = isLongPressMode,
        longPressItems = swipeState.longPressItems,
        selectedLongPressIndex = swipeState.selectedLongPressIndex,
        bodyWidth = bodyWidth,
        textStartX = bodyLeftInBox + paddingPx,
        keyLabelTypeface = keyLabelTypeface,
        shadowRadiusPx = shadowRadiusPx,
        textSizePx = textSizePx,
        selectedFontSizePx = selectedFontSizePx,
        normalFontSizePx = normalFontSizePx,
        selectedBgRadiusPx = selectedBgRadiusPx,
        longPressIconBitmaps = longPressIconBitmaps,
        accentArgb = accentArgb,
        gapPx = gapPx,
    )
}

fun DrawScope.drawSwipeBubble(data: BubbleDrawData) {
    drawIntoCanvas { paintSwipeBubble(it.nativeCanvas, data) }
}

internal fun paintSwipeBubble(canvas: android.graphics.Canvas, data: BubbleDrawData) {
    val alpha = data.alpha.coerceIn(0f, 1f)
    if (alpha <= 0f) return
    canvas.withSave {
        canvas.translate(data.boxLeft, data.boxTop)

        // 选项按完整的条排，画出来的底板从按键上方的圆气泡横向长到整条（[BubbleDrawData.reveal]）。
        val fullLeft = data.pathBodyLeft
        val fullRight = fullLeft + data.pathBodyWidth
        val reveal = data.reveal.coerceIn(0f, 1f)
        val startWidth = minOf(data.bodyHeightPx, data.pathBodyWidth)
        val startCenter = bubbleRevealStartCenter(data.pointerLeftInBox + data.keyWidthPx / 2f, fullLeft, fullRight, startWidth)
        val bodyLeft = lerp(startCenter - startWidth / 2f, fullLeft, reveal)
        val bodyRight = lerp(startCenter + startWidth / 2f, fullRight, reveal)
        val radius = data.bodyHeightPx / 2f
        bubbleFillPaint.color = fade(data.bgColor, alpha)
        bubbleFillPaint.setShadowLayer(data.shadowRadiusPx, 0f, 0f, fade(SHADOW_COLOR, alpha))
        canvas.drawRoundRect(bodyLeft, 0f, bodyRight, data.bodyHeightPx, radius, radius, bubbleFillPaint)

        // Small triangle on the pressed key's top edge, as on the kana keys.
        val keyTop = data.bodyHeightPx + data.gapPx
        if (data.pointerHeightPx > data.gapPx + 1f) {
            val keyCenter = data.pointerLeftInBox + data.keyWidthPx / 2f
            val halfBase = minOf(data.keyWidthPx, data.pointerHeightPx - data.gapPx) * 0.16f
            val depth = halfBase * 1.06f
            bubblePath.rewind()
            bubblePath.moveTo(keyCenter - halfBase, keyTop)
            bubblePath.lineTo(keyCenter, keyTop + depth)
            bubblePath.lineTo(keyCenter + halfBase, keyTop)
            bubblePath.close()
            bubbleBgPaint.color = fade(withAlpha(data.accentArgb, 0x99), alpha)
            canvas.drawPath(bubblePath, bubbleBgPaint)
        }

        if (data.isLongPressMode) {
            canvas.save()
            canvas.clipRect(bodyLeft, 0f, bodyRight, data.bodyHeightPx)
            val cellWidth = data.pathBodyWidth / data.longPressItems.size
            // 展开过半才显出选项，免得字挤在窄条里。
            val itemsAlpha = alpha * ((reveal - 0.35f) / 0.65f).coerceIn(0f, 1f)
            val position = data.selectedPosition.coerceIn(0f, (data.longPressItems.size - 1).toFloat())

            // Light disc behind the chosen item only, like the kana preview; it slides between items.
            bubbleBgPaint.color = fade(withAlpha(data.textColor, 0x33), itemsAlpha)
            canvas.drawCircle(fullLeft + (position + 0.5f) * cellWidth, data.bodyHeightPx / 2f,
                minOf(cellWidth, data.bodyHeightPx) * 0.42f, bubbleBgPaint)

            data.longPressItems.forEachIndexed { index, item ->
                val itemLeft = fullLeft + index * cellWidth
                // 1 = 高亮正停在这一项，0 = 离开一格以上；字号、深浅跟着高亮渐变。
                val emphasis = (1f - kotlin.math.abs(position - index)).coerceIn(0f, 1f)
                if (data.longPressIconBitmaps.isNotEmpty() && index < data.longPressIconBitmaps.size) {
                    val icon = data.longPressIconBitmaps[index]
                    val iconSize = data.bodyHeightPx * 0.45f
                    val iconLeft = itemLeft + (cellWidth - iconSize) / 2f
                    val iconTop = (data.bodyHeightPx - iconSize) / 2f
                    bubbleIconPaint.alpha = (itemsAlpha * 255f).toInt().coerceIn(0, 255)
                    canvas.drawBitmap(
                        icon, null,
                        android.graphics.RectF(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize),
                        bubbleIconPaint
                    )
                } else {
                    val restAlpha = data.textColor ushr 24
                    bubbleLabelPaint.color = fade(if (emphasis >= 1f) data.textColor else withAlpha(data.textColor,
                        (0xBF + (restAlpha - 0xBF) * emphasis).toInt().coerceIn(0, 255)), itemsAlpha)
                    bubbleLabelPaint.textSize = lerp(data.normalFontSizePx, data.selectedFontSizePx, emphasis)
                    bubbleLabelPaint.textAlign = Paint.Align.CENTER
                    bubbleLabelPaint.isFakeBoldText = emphasis >= 0.5f
                    bubbleLabelPaint.typeface = data.keyLabelTypeface
                    val textY = data.bodyHeightPx / 2f - (bubbleLabelPaint.fontMetrics.ascent + bubbleLabelPaint.fontMetrics.descent) / 2f
                    canvas.drawText(item, itemLeft + cellWidth / 2f, textY, bubbleLabelPaint)
                }
            }
            canvas.restore()
        } else if (data.displayText != null) {
            canvas.save()
            canvas.clipRect(bodyLeft, 0f, bodyRight, data.bodyHeightPx)
            bubbleTextPaint.color = fade(data.textColor, alpha)
            bubbleTextPaint.textSize = data.selectedFontSizePx
            bubbleTextPaint.textAlign = Paint.Align.CENTER
            bubbleTextPaint.typeface = data.keyLabelTypeface
            bubbleTextPaint.isFakeBoldText = true
            val textCenterX = fullLeft + data.pathBodyWidth / 2f
            val textY = data.bodyHeightPx / 2f - (bubbleTextPaint.fontMetrics.ascent + bubbleTextPaint.fontMetrics.descent) / 2f
            canvas.drawText(data.displayText, textCenterX, textY, bubbleTextPaint)
            canvas.restore()
        }
    }
}

/**
 * 气泡展开起点（按键上方的圆气泡）的中心，夹在整条 [fullLeft]..[fullRight] 里。
 * 单个字的按键气泡、滑动符号气泡本身就是圆的，起点宽度等于整条，两头算出来理论上相等；
 * 但 fullLeft 不是 0 时（最左边的 Q 键气泡被挪进屏幕边距，或按键比气泡宽）浮点舍入会让右界比左界小一点点，
 * coerceIn 遇到这种空区间直接抛异常，画气泡时整个输入法闪退（11-11 荣耀 WIN 用户按 Q 闪退）。右界不小于左界。
 */
internal fun bubbleRevealStartCenter(pointerCenter: Float, fullLeft: Float, fullRight: Float, startWidth: Float): Float {
    val lowest = fullLeft + startWidth / 2f
    val highest = fullRight - startWidth / 2f
    return pointerCenter.coerceIn(lowest, maxOf(lowest, highest))
}

private fun lerp(start: Float, stop: Float, fraction: Float) = if (fraction >= 1f) stop else start + (stop - start) * fraction

/** 按 [alpha] 再乘一次透明度；alpha 为 1 时原样返回，静止时画法与以前完全相同。 */
private fun fade(argb: Int, alpha: Float): Int =
    if (alpha >= 1f) argb else withAlpha(argb, ((argb ushr 24) * alpha).toInt().coerceIn(0, 255))

private fun withAlpha(argb: Int, alpha: Int): Int = (argb and 0x00FFFFFF) or (alpha shl 24)
