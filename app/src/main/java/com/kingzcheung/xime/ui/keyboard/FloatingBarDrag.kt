package com.kingzcheung.xime.ui.keyboard

import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 悬浮条松手后停在哪：在实体键盘浮条规则（[HardwareToolbarGeometry.positionAt]：底部中心 ±32dp 贴底、
 * 宽屏靠边 32dp 内吸边）之上，加上像 Gboard 那样「往屏幕外推」也算靠边。
 *
 * [x] 是没有夹到屏幕内的横坐标（拖动时允许伸出屏幕）。往外推超过 [dockPushPx] 就停到那一侧；
 * [sideDock] 为真时停靠到侧边的比例写成 0/1（和实体键盘浮条一样，渲染成竖条），
 * 不靠边时比例避开正好的 0/1，免得窄屏上横向只能挪十几 dp 时一碰边就被当成竖条靠边。
 */
internal fun HardwareToolbarGeometry.floatingBarDropPosition(
    x: Float,
    y: Float,
    previous: HardwareToolbarPosition,
    dockAtEdge: Boolean,
    sideDock: Boolean,
    dockPushPx: Float,
): HardwareToolbarPosition {
    val base = positionAt(x, y, previous, snapX = dockAtEdge)
    if (!dockAtEdge || !sideDock || !x.isFinite()) return base
    // 宽屏（能横向挪动的距离够大）照旧：离边 32dp 内就吸过去；窄屏只认往屏幕外推。
    val wide = maxX - minX >= snapDistancePx * 4
    val left = minX - x >= dockPushPx || (wide && x - minX <= snapDistancePx)
    val right = x - maxX >= dockPushPx || (wide && maxX - x <= snapDistancePx)
    if (left || right) {
        // 往外推到了侧边就停侧边，哪怕手指同时落在底部中间的贴底范围里。
        val yFraction = if (base.yFraction >= 1f) Math.nextDown(1f) else base.yFraction
        return HardwareToolbarPosition(if (left) 0f else 1f, yFraction)
    }
    return when (base.xFraction) {
        0f -> base.copy(xFraction = Math.nextUp(0f))
        1f -> base.copy(xFraction = Math.nextDown(1f))
        else -> base
    }
}

/**
 * 可拖动的悬浮条宿主：与实体键盘浮条同一套位置（[HardwareToolbarPosition] 比例位置）、
 * 同一套几何与吸边规则（[hardwareToolbarGeometry]、[HardwareToolbarGeometry.positionAt]）。
 * 手指移动超过触摸阈值才算拖动并取消按钮点击；松手后按比例位置记住，旋转、换输入框都保持。
 * 接收手势的外层在按下期间停在起点，只移动里面的内容，避免拖动时坐标系跟着跑。
 *
 * 给了 [sideDockSize] 时像 Gboard：拖动时左右可以伸出屏幕（至少留 72dp 在屏幕里），
 * 往侧边推过条宽的四分之一（24~96dp）松手就靠边变成竖条（和实体键盘浮条的竖条同一规则），
 * 拖动中用 [dockPreviewColor] 画出松手会停靠的轮廓。[content] 收到当前是不是竖条。
 * [onMovingChange]：拖动和松手后归位动画期间为真，宿主据此暂缓改 App 留空，免得 App 每帧跟着跳。
 */
@Composable
internal fun DraggableFloatingBar(
    position: HardwareToolbarPosition,
    onPositionChange: (HardwareToolbarPosition) -> Unit,
    barWidth: Dp,
    modifier: Modifier = Modifier,
    dockAtEdge: Boolean = true,
    sideDockSize: DpSize? = null,
    dockPreviewColor: Color? = null,
    onMovingChange: (Boolean) -> Unit = {},
    content: @Composable (vertical: Boolean) -> Unit,
) {
    val density = LocalDensity.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val currentPosition by rememberUpdatedState(position)
    val currentPositionChange by rememberUpdatedState(onPositionChange)
    val currentDock by rememberUpdatedState(dockAtEdge)
    val currentMovingChange by rememberUpdatedState(onMovingChange)
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = AbsoluteAlignment.TopLeft) {
        val vertical = sideDockSize != null &&
            hardwareToolbarIsVertical(maxWidth.value, maxHeight.value, position, dockAtEdge)
        val marginPx = with(density) { 8.dp.roundToPx() }
        // 横条的高度量出来后记住：竖条拖向底部时，贴底轮廓按横条的大小画。
        var horizontalHeightPx by remember { mutableStateOf(with(density) { 56.dp.roundToPx() }) }
        var barHeightPx by remember(vertical) {
            mutableStateOf(if (vertical) with(density) { sideDockSize!!.height.roundToPx() } else horizontalHeightPx)
        }
        val horizontalWidthPx = with(density) { barWidth.roundToPx() }
        val widthPx = if (vertical) with(density) { sideDockSize!!.width.roundToPx() } else horizontalWidthPx
        val geometry = hardwareToolbarGeometry(constraints.maxWidth, constraints.maxHeight, widthPx, barHeightPx, marginPx)
        val currentGeometry by rememberUpdatedState(geometry)
        // 拖动时横向可伸出屏幕的量：至少留 72dp 在屏幕里；没开靠边竖条时照旧不出屏幕。
        val overhangPx = if (sideDockSize == null) 0f
            else (widthPx - with(density) { 72.dp.toPx() }).coerceAtLeast(0f)
        val dockPushPx = with(density) { (horizontalWidthPx / 4f).coerceIn(24.dp.toPx(), 96.dp.toPx()) }
        fun HardwareToolbarGeometry.dragBounded(x: Float, y: Float): HardwareToolbarOffset {
            val inside = constrain(x, y)
            return HardwareToolbarOffset(if (x.isFinite()) x.coerceIn(minX - overhangPx, maxX + overhangPx) else inside.x, inside.y)
        }
        val currentDragBounded by rememberUpdatedState<(Float, Float) -> HardwareToolbarOffset>(
            { x, y -> currentGeometry.dragBounded(x, y) })
        val currentDockPush by rememberUpdatedState(dockPushPx)
        val sideDock = sideDockSize != null
        var dragging by remember { mutableStateOf<HardwareToolbarOffset?>(null) }
        var gestureOrigin by remember { mutableStateOf<HardwareToolbarOffset?>(null) }
        val resting = geometry.offset(position)
        val animated by animateOffsetAsState((dragging ?: resting).let { Offset(it.x, it.y) },
            if (dragging != null) snap() else tween(220), label = "floating-bar-position")
        val displayed = dragging ?: geometry.dragBounded(animated.x, animated.y)
        val currentDisplayed by rememberUpdatedState(displayed)
        val moving = dragging != null || animated != Offset(resting.x, resting.y)
        LaunchedEffect(moving) { currentMovingChange(moving) }
        // 拖动中松手会停到哪：只在目标变了时重组，不是每帧。
        val dropTarget by remember(geometry, position, dockAtEdge, sideDock, dockPushPx) {
            derivedStateOf {
                dragging?.let { geometry.floatingBarDropPosition(it.x, it.y, position, dockAtEdge, sideDock, dockPushPx) }
            }
        }
        if (dockPreviewColor != null) dropTarget?.let { target ->
            val targetVertical = sideDock && hardwareToolbarIsVertical(maxWidth.value, maxHeight.value, target, dockAtEdge)
            if (targetVertical || target.isBottomDocked(dockAtEdge)) {
                val previewW = if (targetVertical) with(density) { sideDockSize!!.width.roundToPx() } else horizontalWidthPx
                val previewH = if (targetVertical) with(density) { sideDockSize!!.height.roundToPx() } else horizontalHeightPx
                val at = hardwareToolbarGeometry(constraints.maxWidth, constraints.maxHeight, previewW, previewH, marginPx)
                    .offset(target)
                val shape = RoundedCornerShape(HardwareCandidateCornerRadius)
                Box(Modifier.absoluteOffset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }
                    .size(with(density) { previewW.toDp() }, with(density) { previewH.toDp() })
                    .clip(shape).background(dockPreviewColor.copy(alpha = 0.12f))
                    .border(2.dp, dockPreviewColor.copy(alpha = 0.8f), shape)
                    .testTag("floating-bar-dock-preview"))
            }
        }
        Box(
            Modifier.absoluteOffset {
                val origin = gestureOrigin ?: displayed
                IntOffset(origin.x.roundToInt(), origin.y.roundToInt())
            }.width(with(density) { widthPx.toDp() })
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val origin = currentDisplayed
                        gestureOrigin = origin
                        var moved = false
                        try {
                            while (true) {
                                val change = awaitPointerEvent(PointerEventPass.Initial).changes
                                    .firstOrNull { it.id == down.id } ?: break
                                val distance = change.position - down.position
                                if (!change.isConsumed && (moved || distance.getDistance() > touchSlop)) {
                                    moved = true
                                    dragging = currentDragBounded(origin.x + distance.x, origin.y + distance.y)
                                    change.consume() // 拖动时取消里面按钮的点击和长按
                                }
                                if (!change.pressed) {
                                    if (moved) {
                                        val drop = currentDragBounded(origin.x + distance.x, origin.y + distance.y)
                                        currentPositionChange(currentGeometry.floatingBarDropPosition(
                                            drop.x, drop.y, currentPosition, currentDock, sideDock, currentDockPush))
                                    }
                                    break
                                }
                            }
                        } finally { dragging = null; gestureOrigin = null }
                    }
                },
        ) {
            Box(Modifier.absoluteOffset {
                val origin = gestureOrigin ?: displayed
                IntOffset((displayed.x - origin.x).roundToInt(), (displayed.y - origin.y).roundToInt())
            }.onSizeChanged {
                barHeightPx = it.height
                if (!vertical) horizontalHeightPx = it.height
            }) { content(vertical) }
        }
    }
}
