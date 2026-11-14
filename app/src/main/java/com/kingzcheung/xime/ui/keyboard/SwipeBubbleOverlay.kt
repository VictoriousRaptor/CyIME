package com.kingzcheung.xime.ui.keyboard

import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import com.kingzcheung.xime.ui.theme.CyMotion
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 滑动、长按选项和拂动方向盘需要越过键盘边缘，浮窗只负责显示，触摸仍交给按键。
 * 普通点按由 [PressBubbleLayer] 直接画在键盘窗口里。
 */
internal val DisplayOnlyPopupProperties = PopupProperties(
    flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
    dismissOnBackPress = false, dismissOnClickOutside = false,
)

/** 悬浮键盘当前的旋转角；独立窗口里的气泡要跟着转，尖角才对得上按键。 */
internal val LocalFloatingKeyboardRotation = compositionLocalOf { 0f }

/** Only this overlay observes key presses. The keyboard rows don't depend on the preview. */
@Composable
internal fun KeyboardSwipeBubbleOverlay(controller: SwipeBubbleController, background: Color,
    text: Color, accent: Color, keyboardWidth: Float) {
    val pressBubble = LocalKeyboardInputPreferences.current.showPressBubble
    if (pressBubble) {
        PressBubbleLayer(controller, background, text, accent, keyboardWidth)
    }
    val choosing by remember(controller) { derivedStateOf { controller.state.isSwiping || controller.state.isLongPress } }
    val data = if (choosing) {
        val state = controller.state
        val bounds = controller.keyBounds
        rememberSwipeBubbleDrawData(state, bounds, background, text, accent,
            if (state.isSwiping || state.isPressed) bounds.width else 0f, keyboardWidth)
    } else null
    ChoiceBubbleHost(data, grownFromPressBubble = pressBubble)
}

/** 一次长按（或一次滑动出符号）里气泡的动画状态；换了键或换了选项就是新的一次。 */
private class ChoiceBubbleMotion(initialIndex: Int, longPress: Boolean, visibleAtOnce: Boolean) {
    /** 高亮停在第几项，动画中可在两项之间。 */
    val highlight = Animatable(initialIndex.toFloat())
    /** 长按选项从按键气泡展开；滑动符号气泡本来就是按键气泡的样子，不用展开。 */
    val reveal = Animatable(if (longPress) 0f else 1f)
    /** 按键气泡开着时它本来就在那儿，直接接着长；关着时淡入，免得凭空冒出来。 */
    val appear = Animatable(if (visibleAtOnce) 1f else 0f)
    /** 松手淡出。 */
    val exit = Animatable(1f)

    fun frame(data: BubbleDrawData): BubbleDrawData = data.copy(selectedPosition = highlight.value,
        reveal = reveal.value, alpha = appear.value * exit.value)
}

/**
 * 长按选项、滑动符号气泡的动效（参数都来自 [CyMotion]）：
 * - 长按到点时从按键气泡横向长成选项条（弹簧，略带回弹），选项随后显出；
 * - 手指在选项间划动时高亮跟着滑过去（弹簧，略带回弹），字号、深浅跟着渐变；
 * - 松手后 [CyMotion.EXIT_FAST] 内淡出，透明后再拆窗口，系统窗口退出动画不会再放一遍最后一帧。
 * 全部只在绘制时读动画值：不重组按键、不重新布局；选中哪一项、何时上屏只看手指，与动画无关。
 * 淡出中按下别的键立即换成新的气泡，不等淡出走完。
 */
@Composable
private fun ChoiceBubbleHost(data: BubbleDrawData?, grownFromPressBubble: Boolean) {
    val longPress = data?.takeIf { it.isLongPressMode }
    // 同一次长按：同一个键、同一组选项。
    val sessionKey = data?.let { listOf(it.isLongPressMode, it.longPressItems, it.boxLeft, it.boxTop) }
    val motion = if (data != null) remember(sessionKey) {
        ChoiceBubbleMotion(data.selectedLongPressIndex, longPress != null, grownFromPressBubble)
    } else null
    // 松手后还要画最后一帧淡出，留着最后的样子和它的动画状态。
    val retained = remember { arrayOfNulls<Any>(2) }
    var exitDone by remember { mutableStateOf(true) }
    if (data != null) {
        retained[0] = data
        retained[1] = motion
    }
    if (motion != null) {
        LaunchedEffect(motion) {
            launch { motion.appear.animateTo(1f, CyMotion.enter()) }
            motion.reveal.animateTo(1f, CyMotion.pop())
        }
        val target by rememberUpdatedState(longPress?.selectedLongPressIndex)
        LaunchedEffect(motion) {
            // 每换一项就从当前位置、当前速度接着走（新的 animateTo 打断旧的，速度不清零）。
            snapshotFlow { target }.collect { index ->
                if (index != null) launch { motion.highlight.animateTo(index.toFloat(), CyMotion.move()) }
            }
        }
    }
    LaunchedEffect(data != null) {
        if (data != null) {
            exitDone = false
            return@LaunchedEffect
        }
        val last = retained[1] as? ChoiceBubbleMotion
        if (!exitDone && last != null) {
            last.exit.animateTo(0f, CyMotion.exit(CyMotion.EXIT_FAST))
            // 透明的一帧画出来以后再拆窗口（同拼音气泡 11-06 的做法）。
            repeat(CHOICE_BUBBLE_CLEAR_FRAMES) { withFrameNanos { } }
        }
        retained[0] = null
        retained[1] = null
        exitDone = true
    }
    val shown = data ?: (retained[0] as? BubbleDrawData)?.takeIf { !exitDone }
    val shownMotion = motion ?: retained[1] as? ChoiceBubbleMotion
    if (shown != null && shownMotion != null) {
        // 每次手势一个新窗口：淡出中换了键，新气泡按新键重新定位，不沿用旧窗口的位置。
        key(shownMotion) { SwipeBubbleOverlay(shown, live = data != null, animate = shownMotion::frame) }
    }
}

private const val CHOICE_BUBBLE_CLEAR_FRAMES = 3

/**
 * 键盘转过时：Popup 的锚点是转过后的布局原点，气泡在布局里的位置也按同一角度转到窗口坐标；
 * 窗口取转后气泡的外接框，画布里再把气泡转回去。
 */
@Composable
private fun RotatedSwipeBubble(data: BubbleDrawData, shadow: Float, width: Int, height: Int, rotation: Float,
    live: Boolean, animate: (BubbleDrawData) -> BubbleDrawData) {
    val density = LocalDensity.current
    val radians = Math.toRadians(rotation.toDouble())
    val c = cos(radians).toFloat()
    val s = sin(radians).toFloat()
    val xs = floatArrayOf(0f, width * c, width * c - height * s, -height * s)
    val ys = floatArrayOf(0f, width * s, width * s + height * c, height * c)
    val minX = xs.min()
    val minY = ys.min()
    val boxWidth = ceil(xs.max() - minX).toInt().coerceAtLeast(1)
    val boxHeight = ceil(ys.max() - minY).toInt().coerceAtLeast(1)
    val localX = data.boxLeft - shadow
    val localY = data.boxTop - shadow
    val offsetX = localX * c - localY * s + minX
    val offsetY = localX * s + localY * c + minY
    val anchorView = LocalView.current
    val placement = remember(anchorView) { PopupWindowPlacement(anchorView) }
    val correction = PopupWindowCorrection.offsetFor(placement.origin)
    val provider = remember(offsetX, offsetY, correction) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset = placement.place(
                IntOffset((anchorBounds.left + offsetX).roundToInt(), (anchorBounds.top + offsetY).roundToInt()))
        }
    }
    Popup(popupPositionProvider = provider, properties = DisplayOnlyPopupProperties) {
        VerifyPopupWindowPlacement(placement)
        Canvas(Modifier.size(with(density) { boxWidth.toDp() }, with(density) { boxHeight.toDp() })
            .then(if (live) Modifier.testTag("key-choice-popup") else Modifier)) {
            withTransform({
                translate(-minX, -minY)
                rotate(rotation, androidx.compose.ui.geometry.Offset.Zero)
            }) {
                drawSwipeBubble(animate(data.copy(boxLeft = shadow, boxTop = shadow)))
            }
        }
    }
}

internal fun swipeBubbleWindowPosition(anchor: IntRect, window: IntSize, popup: IntSize,
    left: Float, top: Float, shadow: Float): IntOffset = IntOffset(
    (anchor.left + left - shadow).roundToInt().coerceIn(0, (window.width - popup.width).coerceAtLeast(0)),
    (anchor.top + top - shadow).roundToInt().coerceIn(0, (window.height - popup.height).coerceAtLeast(0)),
)

/**
 * Separate non-focusable window: keyboard-card clipping/opacity must not cut off the choices.
 * [animate] adds the current animation values while drawing; [live] is false while it fades out
 * after the finger lifted, when it is no longer a choice.
 */
@Composable
internal fun SwipeBubbleOverlay(data: BubbleDrawData?, live: Boolean = true,
    animate: (BubbleDrawData) -> BubbleDrawData = { it }) {
    if (data == null) return
    val density = LocalDensity.current
    val shadow = ceil(data.shadowRadiusPx)
    val width = ceil(maxOf(data.pathBodyLeft + data.pathBodyWidth,
        data.pointerLeftInBox + data.keyWidthPx) + shadow * 2).toInt()
    val height = ceil(data.bodyHeightPx + data.pointerHeightPx + shadow * 2).toInt()
    val rotation = LocalFloatingKeyboardRotation.current
    if (isFloatingRotated(rotation)) {
        RotatedSwipeBubble(data, shadow, width, height, rotation, live, animate)
        return
    }
    var displacedY by remember { mutableFloatStateOf(0f) }
    val currentData by rememberUpdatedState(data)
    val anchorView = LocalView.current
    val placement = remember(anchorView) { PopupWindowPlacement(anchorView) }
    // 系统摆浮窗的参照和键盘窗口差了多少（见 [PopupWindowPlacement]）；变了换一个 provider，浮窗立刻重摆。
    val correction = PopupWindowCorrection.offsetFor(placement.origin)
    val provider = remember(shadow, correction) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val bubble = currentData
                val position = swipeBubbleWindowPosition(anchorBounds, windowSize, popupContentSize,
                    bubble.boxLeft, bubble.boxTop, shadow)
                displacedY = position.y + shadow - (anchorBounds.top + bubble.boxTop)
                return placement.place(position)
            }
        }
    }
    Popup(popupPositionProvider = provider, properties = DisplayOnlyPopupProperties) {
        VerifyPopupWindowPlacement(placement)
        // 出现、展开、高亮滑动和淡出都画在这块画布里（见 ChoiceBubbleHost）；选择只看手指坐标，动画不影响命中。
        Canvas(Modifier.size(with(density) { width.toDp() }, with(density) { height.toDp() })
            .then(if (live) Modifier.testTag("key-choice-popup") else Modifier)) {
            drawSwipeBubble(animate(data.copy(boxLeft = shadow, boxTop = shadow,
                // At the physical screen edge, shorten the stem instead of moving its tip off the key.
                pointerHeightPx = (data.pointerHeightPx - displacedY).coerceAtLeast(0f))))
        }
    }
}
