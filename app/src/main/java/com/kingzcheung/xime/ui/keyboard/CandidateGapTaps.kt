package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp

/** 点在候选栏空白处时，离候选左右多远以内算点它（候选之间的空隙、「<」和第一个候选之间那一段都在这以内）。 */
internal val CANDIDATE_GAP_TAP_REACH_X = 12.dp

/** 上下多远以内算点它：候选比栏矮，栏的上下边沿都要算上。 */
internal val CANDIDATE_GAP_TAP_REACH_Y = 24.dp

/**
 * 候选栏里没有候选接着的地方（候选上下的留白、候选之间的空隙、「<」和第一个候选之间那一段）自己接住轻点，
 * 松手时选横向最近的候选（#274）。
 *
 * 以前这些地方谁都不接：点在「这」的左下边，要么什么都没发生，要么被「<」放大后的触摸范围（Compose 把小于
 * 48dp 的可点区域自动放大）接走，成了「取消输入」，打好的拼音整个没了，看上去就是吞字，点到第三下才上屏。
 *
 * 只接候选自己、横滑列表都没接的那一下：候选、按钮照旧先拿到；拖动、长按、被列表当成滑动的不选。
 * 这块区域本身要铺满栏高，才能比「<」放大的范围先被点中。
 */
@Composable
internal fun Modifier.candidateGapTaps(): Modifier {
    val dragSpace = LocalKeyDragSpace.current
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    return this
        .onGloballyPositioned { coordinates[0] = it }
        .pointerInput(dragSpace) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // 候选（或滑动中的列表）已经接住了这一下。
                if (down.isConsumed) return@awaitEachGesture
                // 不管手指有没有出这块区域（「<」右边那段只有 4dp 宽），只看有没有被别人接走、挪没挪远。
                var released: PointerInputChange? = null
                while (released == null) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                    if (change.isConsumed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop)
                        return@awaitEachGesture
                    if (!change.pressed) released = change
                }
                val up = released ?: return@awaitEachGesture
                if (up.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) return@awaitEachGesture
                val node = coordinates[0]?.takeIf { it.isAttached } ?: return@awaitEachGesture
                val select = CandidateDropTargets.shared.nearest(dragSpace.point(node, down.position),
                    CANDIDATE_GAP_TAP_REACH_X.toPx(), CANDIDATE_GAP_TAP_REACH_Y.toPx()) ?: return@awaitEachGesture
                up.consume()
                select()
            }
        }
}
