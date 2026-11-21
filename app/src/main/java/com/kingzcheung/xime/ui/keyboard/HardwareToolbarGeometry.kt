package com.kingzcheung.xime.ui.keyboard

/** Fractions of the currently available travel, retained across editors and rotations. */
internal data class HardwareToolbarPosition(val xFraction: Float = 0.5f, val yFraction: Float = 1f)

/** Only a drop near the bottom centre docks to the bottom; the lower corners dock to the sides. */
internal fun HardwareToolbarPosition.isBottomDocked(enabled: Boolean) = enabled && yFraction == 1f

/** Shared by rendering and key routing: hidden suggestions must never capture arrow keys. */
internal fun hardwareToolbarIsVertical(
    widthDp: Float, heightDp: Float, position: HardwareToolbarPosition, dockAtEdge: Boolean,
): Boolean {
    val usableWidth = if (widthDp >= 104f) widthDp - 16f else widthDp
    val bottomDocked = position.isBottomDocked(dockAtEdge) && widthDp >= 336f
    return !bottomDocked && dockAtEdge && (position.xFraction == 0f || position.xFraction == 1f) &&
        heightDp >= 248f && usableWidth >= 88f
}

internal fun hardwareToolbarCanShowCandidates(
    widthDp: Float, heightDp: Float, position: HardwareToolbarPosition, dockAtEdge: Boolean,
): Boolean = widthDp >= 336f && !hardwareToolbarIsVertical(widthDp, heightDp, position, dockAtEdge)

internal data class HardwareToolbarOffset(val x: Float, val y: Float)

internal enum class HardwareToolbarMode { FULL, COMPACT, KEYBOARD_ONLY }

internal fun hardwareToolbarMode(availableWidthDp: Float): HardwareToolbarMode = when {
    availableWidthDp >= 232f -> HardwareToolbarMode.FULL
    availableWidthDp >= 184f -> HardwareToolbarMode.COMPACT
    else -> HardwareToolbarMode.KEYBOARD_ONLY
}

internal data class HardwareToolbarGeometry(
    val minX: Float,
    val maxX: Float,
    val minY: Float,
    val maxY: Float,
    val snapDistancePx: Float = 32f,
) {
    fun offset(position: HardwareToolbarPosition): HardwareToolbarOffset = HardwareToolbarOffset(
        minX + (maxX - minX) * fraction(position.xFraction, 0.5f),
        minY + (maxY - minY) * fraction(position.yFraction, 1f),
    )

    fun constrain(x: Float, y: Float): HardwareToolbarOffset = HardwareToolbarOffset(
        if (x.isFinite()) x.coerceIn(minX, maxX) else minX,
        if (y.isFinite()) y.coerceIn(minY, maxY) else maxY,
    )

    fun positionAt(
        x: Float,
        y: Float,
        previous: HardwareToolbarPosition,
        snapX: Boolean = true,
    ): HardwareToolbarPosition {
        val bounded = constrain(x, y)
        val xFraction = if (maxX > minX) (bounded.x - minX) / (maxX - minX)
            else fraction(previous.xFraction, 0.5f)
        val yFraction = if (maxY > minY) (bounded.y - minY) / (maxY - minY)
            else fraction(previous.yFraction, 1f)
        val nearBottom = snapX && maxY > minY && maxY - bounded.y <= minOf(snapDistancePx, (maxY - minY) * 0.2f)
        // 与 Windows 端一致：只有拖到底部中间那一点附近才贴底边；底部其余位置照常吸侧边或自由停放，
        // 否则通栏的吸附范围伸到两侧，想停在侧边靠下时总被吸到底部中间（用户 2026-10-12 要求）。
        val bottom = nearBottom && kotlin.math.abs(bounded.x - (minX + maxX) / 2) <= snapDistancePx
        return HardwareToolbarPosition(
            if (bottom) 0.5f else if (snapX) when {
                maxX <= minX -> xFraction
                bounded.x - minX <= minOf(snapDistancePx, (maxX - minX) * 0.2f) -> 0f
                maxX - bounded.x <= minOf(snapDistancePx, (maxX - minX) * 0.2f) -> 1f
                kotlin.math.abs(bounded.x - (minX + maxX) / 2) <= snapDistancePx / 2 -> 0.5f
                else -> xFraction
            } else xFraction,
            // yFraction == 1 表示贴底（isBottomDocked），不贴底时停在最底也要略小于 1。
            if (bottom) 1f else if (nearBottom) minOf(yFraction, Math.nextDown(1f)) else yFraction,
        )
    }

    private fun fraction(value: Float, fallback: Float): Float =
        if (value.isFinite()) value.coerceIn(0f, 1f) else fallback
}

internal fun hardwareToolbarGeometry(
    viewWidth: Int,
    viewHeight: Int,
    toolbarWidth: Int,
    toolbarHeight: Int,
    marginPx: Int,
): HardwareToolbarGeometry {
    fun range(extent: Int, toolbarExtent: Int): Pair<Float, Float> {
        val travel = (extent.coerceAtLeast(0) - toolbarExtent.coerceAtLeast(0)).coerceAtLeast(0)
        val margin = marginPx.coerceAtLeast(0).coerceAtMost(travel / 2)
        return margin.toFloat() to (travel - margin).toFloat()
    }
    val (minX, maxX) = range(viewWidth, toolbarWidth)
    val (minY, maxY) = range(viewHeight, toolbarHeight)
    return HardwareToolbarGeometry(minX, maxX, minY, maxY, marginPx.coerceAtLeast(1) * 4f)
}

/** Relocate only on overlap, without overwriting the user's saved position. */
internal fun avoidHardwareEditor(
    geometry: HardwareToolbarGeometry, preferred: HardwareToolbarOffset,
    width: Int, height: Int, excluded: HardwareCandidateExclusion?, gap: Int,
): HardwareToolbarOffset {
    if (excluded == null || excluded.right <= excluded.left || excluded.bottom <= excluded.top) return preferred
    val left = excluded.left.toFloat() - gap
    val top = excluded.top.toFloat() - gap
    val right = excluded.right.toFloat() + gap
    val bottom = excluded.bottom.toFloat() + gap
    fun overlap(p: HardwareToolbarOffset) = (minOf(p.x + width, right) - maxOf(p.x, left)).coerceAtLeast(0f) *
        (minOf(p.y + height, bottom) - maxOf(p.y, top)).coerceAtLeast(0f)
    if (overlap(preferred) == 0f) return preferred
    // 只上下挪开，不横着跳：光标每打一个字都往右走，横向避让会让工具条跟着光标左右跳（用户 11-20，与 Windows 一致）。
    return listOf(preferred, geometry.constrain(preferred.x, top - height),
        geometry.constrain(preferred.x, bottom)).minWith(compareBy<HardwareToolbarOffset> { overlap(it) }.thenBy {
            val dx = it.x - preferred.x; val dy = it.y - preferred.y; dx * dx + dy * dy
        })
}

/**
 * 固定在某一边时工具条的位置：底部 = 原来的贴底（[isBottomDocked]）；左右 = 原来靠边的竖条，上下居中；
 * 顶部 = 顶边居中的横条（不占 App 底部，不给 App 留白）。
 */
internal fun com.kingzcheung.xime.settings.HardwareToolbarEdge.toolbarPosition(): HardwareToolbarPosition = when (this) {
    com.kingzcheung.xime.settings.HardwareToolbarEdge.TOP -> HardwareToolbarPosition(0.5f, 0f)
    com.kingzcheung.xime.settings.HardwareToolbarEdge.BOTTOM -> HardwareToolbarPosition(0.5f, 1f)
    com.kingzcheung.xime.settings.HardwareToolbarEdge.LEFT -> HardwareToolbarPosition(0f, 0.5f)
    com.kingzcheung.xime.settings.HardwareToolbarEdge.RIGHT -> HardwareToolbarPosition(1f, 0.5f)
}
