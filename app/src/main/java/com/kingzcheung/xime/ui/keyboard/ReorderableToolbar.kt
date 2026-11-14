package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.keyboard.ToolbarAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 长按两秒后拖动换位，松手保存；普通点按与横向浏览沿用原行为。 */
@Composable
internal fun ReorderableToolbar(
    actions: List<ToolbarAction>, accent: Color, onReorder: ((List<String>) -> Unit)?,
    modifier: Modifier = Modifier, content: @Composable (ToolbarAction) -> Unit,
) {
    val ids = actions.map { it.item.id }
    var order by remember { mutableStateOf(ids) }
    var dragged by remember { mutableStateOf<String?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var rowBounds by remember { mutableStateOf(Rect.Zero) }
    val bounds = remember { mutableMapOf<String, Rect>() }
    val latestIds by rememberUpdatedState(ids)
    val save by rememberUpdatedState(onReorder)
    val scroll = rememberScrollState()
    val edge = with(LocalDensity.current) { 24.dp.toPx() }
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val inlineEditor = LocalInlineQuickActionEditor.current
    // The inline editor already owns the draft order; render it in the same frame so
    // placement animations see the old and new slots as one change.
    val displayedOrder = if (inlineEditor?.active == true || dragged == null) ids else order
    LaunchedEffect(ids) { if (dragged == null) order = ids }
    fun moveToPointer() {
        val source = dragged ?: return
        // 按位置槽位换序，避免同一帧移动和松手用到尚未重排的旧 id 坐标而反向换回。
        val centers = order.mapNotNull { bounds[it]?.center?.x }.sorted()
        if (centers.size != order.size) return
        val from = order.indexOf(source)
        val to = centers.indices.minByOrNull { abs(centers[it] - pointer.x) } ?: return
        if (from >= 0 && to >= 0 && from != to) order = order.toMutableList().apply { add(to, removeAt(from)) }
    }
    LaunchedEffect(dragged) {
        while (dragged != null) {
            val delta = when { pointer.x < rowBounds.left + edge -> -8f; pointer.x > rowBounds.right - edge -> 8f; else -> 0f }
            if (delta != 0f) { scroll.scrollBy(delta); moveToPointer() }
            delay(30)
        }
    }
    LaunchedEffect(inlineEditor?.dragging) {
        while (inlineEditor?.dragging != null) {
            val point = inlineEditor.pointer
            val delta = if (point.y !in rowBounds.top..rowBounds.bottom) 0f else when {
                point.x < rowBounds.left + edge -> -8f
                point.x > rowBounds.right - edge -> 8f
                else -> 0f
            }
            if (delta != 0f && scroll.scrollBy(delta) != 0f) {
                withFrameNanos { }
                inlineEditor.refreshHover()
            }
            delay(30)
        }
    }
    BoxWithConstraints(modifier.testTag("toolbar-order-row").onGloballyPositioned { rowBounds = it.boundsInRoot() }
        .pointerInput(onReorder != null) {
            if (save != null) awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (down.isConsumed) return@awaitEachGesture
                var completed = false
                val timer = scope.launch {
                    delay(2000L)
                    // 按着的是带长按列表的按钮、列表正开着：手指在选语言，不拖走按钮
                    if (ToolbarPressMenuGate.open) return@launch
                    pointer = rowBounds.topLeft + down.position
                    dragged = order.firstOrNull { bounds[it]?.contains(pointer) == true }
                    if (dragged != null) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.isConsumed) break
                        if (dragged != null) {
                            change.consume()
                            pointer = rowBounds.topLeft + change.position
                            moveToPointer()
                            if (!change.pressed) { completed = true; break }
                        } else if (!change.pressed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                    }
                    if (completed && order != latestIds) save?.invoke(order)
                    else if (!completed) order = latestIds
                } finally {
                    timer.cancel()
                    dragged = null
                }
            }
        }) {
        // 每个图标占等宽一格，整格可点；放不下时按最小格宽横向滚动。
        val slotWidth = maxOf(maxWidth / displayedOrder.size.coerceAtLeast(1), 36.dp)
        Row(Modifier.horizontalScroll(scroll, enabled = inlineEditor?.dragging == null).widthIn(min = maxWidth),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            displayedOrder.forEach { id ->
                val action = actions.firstOrNull { it.item.id == id } ?: return@forEach
                key(id) {
                    Box(Modifier.width(slotWidth).testTag("toolbar-slot")
                        .onGloballyPositioned { bounds[id] = Rect(it.positionInRoot(), Size(it.size.width.toFloat(), it.size.height.toFloat())) },
                        contentAlignment = Alignment.Center) {
                        content(action)
                        // 拖动外框沿按钮胶囊描边，与按下、开启时的底色同尺寸。
                        if (dragged == id) Box(Modifier.size(toolbarPillSize(slotWidth)).border(2.dp, accent, CircleShape)
                            .testTag("toolbar-drag-outline"))
                    }
                }
            }
        }
    }
}
