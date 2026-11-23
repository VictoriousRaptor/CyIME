package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.service.HardwareCursorAnchor
import kotlin.math.roundToInt

internal data class HardwareCandidatePosition(val x: Int, val y: Int)

/** Host-local rectangle, kept independent of Android for positioning tests. */
internal data class HardwareCandidateExclusion(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Position a measured candidate card in the actual IME host, using screen-space cursor bounds. */
internal fun hardwareCandidatePosition(
    viewWidth: Int,
    viewHeight: Int,
    cardWidth: Int,
    cardHeight: Int,
    anchor: HardwareCursorAnchor?,
    hostScreenX: Int,
    hostScreenY: Int,
    marginPx: Int,
    gapPx: Int,
    avoidBounds: HardwareCandidateExclusion? = null,
): HardwareCandidatePosition {
    val width = viewWidth.coerceAtLeast(0)
    val height = viewHeight.coerceAtLeast(0)
    val measuredWidth = cardWidth.coerceAtLeast(0)
    val measuredHeight = cardHeight.coerceAtLeast(0)
    fun availableRange(extent: Int, cardExtent: Int): IntRange {
        val end = (extent - cardExtent).coerceAtLeast(0)
        // Small windows may not have room for both normal margins.
        val margin = marginPx.coerceAtLeast(0).coerceAtMost(end / 2)
        return margin..(end - margin)
    }
    val xRange = availableRange(width, measuredWidth)
    val yRange = availableRange(height, measuredHeight)
    fun avoidToolbar(preferred: HardwareCandidatePosition): HardwareCandidatePosition {
        val excluded = avoidBounds ?: return preferred
        if (excluded.left >= excluded.right || excluded.top >= excluded.bottom ||
            measuredWidth == 0 || measuredHeight == 0
        ) return preferred
        val gap = gapPx.coerceAtLeast(0).toLong()
        val left = excluded.left.toLong() - gap
        val top = excluded.top.toLong() - gap
        val right = excluded.right.toLong() + gap
        val bottom = excluded.bottom.toLong() + gap
        fun overlap(position: HardwareCandidatePosition): Long {
            val overlapWidth = (minOf(position.x.toLong() + measuredWidth, right) -
                maxOf(position.x.toLong(), left)).coerceAtLeast(0)
            val overlapHeight = (minOf(position.y.toLong() + measuredHeight, bottom) -
                maxOf(position.y.toLong(), top)).coerceAtLeast(0)
            return overlapWidth * overlapHeight
        }
        if (overlap(preferred) == 0L) return preferred
        fun clamped(x: Long, y: Long) = HardwareCandidatePosition(
            x.coerceIn(xRange.first.toLong(), xRange.last.toLong()).toInt(),
            y.coerceIn(yRange.first.toLong(), yRange.last.toLong()).toInt(),
        )
        // Keep the caret-aligned placement whenever possible. Otherwise move the shortest
        // distance to one side of the toolbar, preserving viewport bounds in tiny windows.
        return listOf(
            preferred,
            clamped(preferred.x.toLong(), top - measuredHeight),
            clamped(preferred.x.toLong(), bottom),
            clamped(left - measuredWidth, preferred.y.toLong()),
            clamped(right, preferred.y.toLong()),
        ).minWith(compareBy<HardwareCandidatePosition> { overlap(it) }.thenBy {
            val dx = it.x.toDouble() - preferred.x
            val dy = it.y.toDouble() - preferred.y
            dx * dx + dy * dy
        })
    }
    val fallback = HardwareCandidatePosition((width - measuredWidth).coerceAtLeast(0) / 2, yRange.last)
    if (anchor == null || !anchor.left.isFinite() || !anchor.top.isFinite() ||
        !anchor.right.isFinite() || !anchor.bottom.isFinite() ||
        anchor.left > anchor.right || anchor.top > anchor.bottom
    ) return avoidToolbar(fallback)

    val left = anchor.left.toDouble() - hostScreenX
    val right = anchor.right.toDouble() - hostScreenX
    val top = anchor.top.toDouble() - hostScreenY
    val bottom = anchor.bottom.toDouble() - hostScreenY
    // Zero and boundary coordinates are valid; only fully outside anchors are hidden.
    if (right < 0.0 || left > width || bottom < 0.0 || top > height) return avoidToolbar(fallback)

    val gap = gapPx.coerceAtLeast(0)
    val below = bottom + gap
    val preferredY = if (below <= yRange.last) below else top - measuredHeight - gap
    return avoidToolbar(HardwareCandidatePosition(
        left.coerceIn(xRange.first.toDouble(), xRange.last.toDouble()).roundToInt(),
        preferredY.coerceIn(yRange.first.toDouble(), yRange.last.toDouble()).roundToInt(),
    ))
}

/**
 * 拼音/候选浮卡盖住正在输入的那一行时，只上下挪到这一行之外，不跟着光标横向走；
 * 两边都放得下时取离原位置近的，并尽量不压工具条。[caretLine] 与 [toolbar] 都是宿主内坐标。
 */
internal fun avoidHardwareCaretLine(
    preferred: HardwareCandidatePosition,
    cardWidth: Int,
    cardHeight: Int,
    viewHeight: Int,
    caretLine: HardwareCandidateExclusion?,
    toolbar: HardwareCandidateExclusion?,
    marginPx: Int,
    gapPx: Int,
): HardwareCandidatePosition {
    if (caretLine == null || caretLine.bottom <= caretLine.top || cardWidth <= 0 || cardHeight <= 0) return preferred
    fun overlap(p: HardwareCandidatePosition, r: HardwareCandidateExclusion?, gap: Int): Long {
        if (r == null || r.right <= r.left || r.bottom <= r.top) return 0L
        val w = (minOf(p.x.toLong() + cardWidth, r.right.toLong() + gap) - maxOf(p.x.toLong(), r.left.toLong() - gap)).coerceAtLeast(0)
        val h = (minOf(p.y.toLong() + cardHeight, r.bottom.toLong() + gap) - maxOf(p.y.toLong(), r.top.toLong() - gap)).coerceAtLeast(0)
        return w * h
    }
    val gap = gapPx.coerceAtLeast(0)
    if (overlap(preferred, caretLine, gap) == 0L) return preferred
    val end = (viewHeight - cardHeight).coerceAtLeast(0)
    val margin = marginPx.coerceAtLeast(0).coerceAtMost(end / 2)
    fun at(y: Int) = HardwareCandidatePosition(preferred.x, y.coerceIn(margin, end - margin))
    return listOf(preferred, at(caretLine.top - gap - cardHeight), at(caretLine.bottom + gap))
        .minWith(compareBy<HardwareCandidatePosition> { overlap(it, caretLine, gap) }
            .thenBy { overlap(it, toolbar, 0) }
            .thenBy { kotlin.math.abs(it.y - preferred.y) })
}

/** 光标（屏幕坐标）落在宿主 [width]×[height] 里：和 [hardwareCandidatePosition] 判断能不能跟光标用同一条规则。 */
internal fun hardwareAnchorInHost(anchor: HardwareCursorAnchor?, hostScreenX: Int, hostScreenY: Int,
    width: Int, height: Int): Boolean {
    if (anchor == null || !anchor.left.isFinite() || !anchor.top.isFinite() || !anchor.right.isFinite() ||
        !anchor.bottom.isFinite() || anchor.left > anchor.right || anchor.top > anchor.bottom) return false
    val left = anchor.left.toDouble() - hostScreenX
    val right = anchor.right.toDouble() - hostScreenX
    val top = anchor.top.toDouble() - hostScreenY
    val bottom = anchor.bottom.toDouble() - hostScreenY
    return !(right < 0.0 || left > width.coerceAtLeast(0) || bottom < 0.0 || top > height.coerceAtLeast(0))
}

/**
 * 浮窗固定在某一边、又不知道光标在哪时（或关了「候选跟随光标」），拼音和候选卡贴着工具条放：顶部的放在工具条下面，
 * 底部的放在上面，左侧的放在右边，右侧的放在左边；放不下时夹在屏幕内。[toolbar] 是宿主内坐标。
 */
internal fun fixedHardwareCandidatePosition(
    edge: com.kingzcheung.xime.settings.HardwareToolbarEdge,
    toolbar: HardwareCandidateExclusion,
    cardWidth: Int,
    cardHeight: Int,
    viewWidth: Int,
    viewHeight: Int,
    marginPx: Int,
): HardwareCandidatePosition {
    fun clampX(x: Int) = x.coerceIn(marginPx, maxOf(marginPx, viewWidth - cardWidth - marginPx))
    fun clampY(y: Int) = y.coerceIn(marginPx, maxOf(marginPx, viewHeight - cardHeight - marginPx))
    return when (edge) {
        com.kingzcheung.xime.settings.HardwareToolbarEdge.TOP ->
            HardwareCandidatePosition(clampX(toolbar.left), clampY(toolbar.bottom + marginPx))
        com.kingzcheung.xime.settings.HardwareToolbarEdge.BOTTOM ->
            HardwareCandidatePosition(clampX(toolbar.left), clampY(toolbar.top - cardHeight - marginPx))
        com.kingzcheung.xime.settings.HardwareToolbarEdge.LEFT ->
            HardwareCandidatePosition(clampX(toolbar.right + marginPx), clampY(toolbar.top))
        com.kingzcheung.xime.settings.HardwareToolbarEdge.RIGHT ->
            HardwareCandidatePosition(clampX(toolbar.left - cardWidth - marginPx), clampY(toolbar.top))
    }
}
