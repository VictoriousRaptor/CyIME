package com.kingzcheung.xime.ui.keyboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/**
 * 候选栏里每个候选在根布局里的位置，供 Shift 拖动用：从 Shift 拖到某个候选上松手，等于按住 Shift
 * 点这个候选（释义模式下有英文就上屏英文）。只在主线程读写。
 */
internal class CandidateDropTargets {
    private val targets = LinkedHashMap<Any, Pair<Rect, () -> Unit>>()

    /** Shift 拖动中的那条线（起点、手指，根布局坐标）；候选栏据此画出线在栏里的那一段，不被字母区截断。 */
    var dragLine by mutableStateOf<Pair<Offset, Offset>?>(null)
    /** 手指正指着的候选的位置；候选栏在它上面画高亮，换候选时高亮滑过去。 */
    var hoveredBounds by mutableStateOf<Rect?>(null)

    fun put(id: Any, bounds: Rect, select: () -> Unit) {
        if (bounds.isEmpty) targets.remove(id) else targets[id] = bounds to select
    }

    fun remove(id: Any) {
        targets.remove(id)
    }

    /** 落点所在的候选；返回它的选择动作。 */
    fun at(point: Offset): Pair<Rect, () -> Unit>? = targets.values.firstOrNull { it.first.contains(point) }

    /**
     * 横向离 [point] 最近的候选（左右不超过 [maxDx]、上下不超过 [maxDy]）；点在候选栏空白处时用（#274）。
     * 横向距离一样时取上下更近的。
     */
    fun nearest(point: Offset, maxDx: Float, maxDy: Float): (() -> Unit)? =
        targets.values.mapNotNull { (bounds, select) ->
            val dx = maxOf(bounds.left - point.x, 0f, point.x - bounds.right)
            val dy = maxOf(bounds.top - point.y, 0f, point.y - bounds.bottom)
            if (dx > maxDx || dy > maxDy) null else Triple(dx, dy, select)
        }.minWithOrNull(compareBy<Triple<Float, Float, () -> Unit>> { it.first }.thenBy { it.second })?.third

    companion object {
        /** 键盘界面只有一份候选栏，Shift 键和候选栏不在同一个组件树分支里，用同一个实例对接。 */
        val shared = CandidateDropTargets()
    }
}
