package com.kingzcheung.xime.ui.keyboard

import kotlin.math.roundToInt

/**
 * 普通悬浮态的四角调大小：拖完悬浮键盘、点一下底部拖动条或切成悬浮后，卡片外四角亮出弧形角标；
 * 亮着时按住角标拖动就直接改键盘大小（对角不动），和调节界面拖四角用同一套尺寸上下限与高宽比保护，
 * 松手按调节界面确认时的方式保存。坐标都是悬浮宿主的本地像素。
 */

/** 角标把手露在卡片外的距离：盖住画在卡片外 [PositionHighlightGap] 处的弧形角标，再多留一点给手指。 */
internal const val FLOATING_CORNER_HANDLE_OUTSIDE_DP = 24f

/** 角标把手伸进卡片的距离：只压住角上一小块，角标亮着的那一下不去挡工具栏两头的按钮。 */
internal const val FLOATING_CORNER_HANDLE_INSIDE_DP = 12f

internal val FLOATING_CORNER_HANDLES = listOf(
    ResizeHandle.TOP_LEFT, ResizeHandle.TOP_RIGHT, ResizeHandle.BOTTOM_LEFT, ResizeHandle.BOTTOM_RIGHT,
)

/** [handle] 角的把手热区：卡片外 [outsidePx]、卡片内 [insidePx] 的方块。不是角时返回 null。 */
internal fun ResizeRect.cornerHandleRect(handle: ResizeHandle, outsidePx: Float, insidePx: Float): ResizeRect? {
    val x = when (handle) {
        ResizeHandle.TOP_LEFT, ResizeHandle.BOTTOM_LEFT -> left
        ResizeHandle.TOP_RIGHT, ResizeHandle.BOTTOM_RIGHT -> right
        else -> return null
    }
    val y = if (handle == ResizeHandle.TOP_LEFT || handle == ResizeHandle.TOP_RIGHT) top else bottom
    // 往卡片里面的方向：左边、上边往右往下，右边、下边往左往上。
    val inwardX = if (x == left) 1f else -1f
    val inwardY = if (y == top) 1f else -1f
    val x1 = x - inwardX * outsidePx
    val x2 = x + inwardX * insidePx
    val y1 = y - inwardY * outsidePx
    val y2 = y + inwardY * insidePx
    return ResizeRect(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2))
}

/** 一次拖角的边界与尺寸上下限（像素，含底部拖动条），按下时算一次。 */
internal data class FloatingCornerResizeLimits(
    val bounds: ResizeRect,
    val minWidth: Float,
    val minHeight: Float,
    val maxWidth: Float,
    val maxHeight: Float,
    val dragBarPx: Float,
)

/**
 * 和调节界面拖悬浮四角同一套范围：宽高下限按屏幕短边算（[floatingResizeFloorWidthDp]、[floatingResizeFloorContentDp]），上限是屏宽；
 * 高度用悬浮调节高度范围。现在已经比下限小（之前吸附到方形按键点）时不强行撑大。
 */
internal fun floatingCornerResizeLimits(
    start: ResizeRect,
    hostWidthPx: Float,
    hostHeightPx: Float,
    bottomInsetPx: Float,
    screenWidthDp: Int,
    landscape: Boolean,
    density: Float,
    /** 屏幕短边（dp），决定拖小的下限；和调节界面同一个算法。 */
    screenShortSideDp: Int = minOf(screenWidthDp, (hostHeightPx / density.coerceAtLeast(0.1f)).roundToInt()),
    /** 按真实物理尺寸算的最低高度（[physicalKeyboardFloorDp]）；不传时只按屏幕短边。 */
    physicalFloorDp: Int = Int.MAX_VALUE,
): FloatingCornerResizeLimits {
    val d = density.coerceAtLeast(0.1f)
    val bounds = floatingViewportBounds(hostWidthPx, hostHeightPx, bottomInsetPx)
    val dragBarPx = floatingFooterHeightDp(screenShortSideDp) * d
    val availableWidthDp = (bounds.width / d).roundToInt()
    val availableHeightDp = (bounds.height / d).roundToInt()
    val widthRange = keyboardWidthBounds(screenWidthDp)
    val minWidthDp = floatingResizeFloorWidthDp(screenShortSideDp, availableWidthDp, physicalFloorDp)
    val heightRange = floatingResizeHeightBounds((hostHeightPx / d).roundToInt(), landscape)
    val minContentDp = floatingResizeFloorContentDp(screenShortSideDp, availableHeightDp, physicalFloorDp)
    return FloatingCornerResizeLimits(
        bounds = bounds,
        minWidth = (minWidthDp * d).coerceAtMost(start.width),
        minHeight = (minContentDp * d + dragBarPx).coerceAtMost(start.height),
        maxWidth = minOf(bounds.width, widthRange.last * d),
        maxHeight = heightRange.last * d + dragBarPx,
        dragBarPx = dragBarPx,
    )
}

/**
 * 拖角一步：[raw] 是只按上下限夹过的累计矩形（下一步接着在它上面加），返回值的第二项是显示出来的矩形，
 * 再按高宽比保护（贴近屏幕边时补回拖慢的距离，和调节界面一样）。分体键盘两半贴边，只改高度。
 */
internal fun floatingCornerResizeStep(
    start: ResizeRect,
    raw: ResizeRect,
    handle: ResizeHandle,
    dx: Float,
    dy: Float,
    limits: FloatingCornerResizeLimits,
    aspect: KeyboardAspectLimits?,
    heightOnly: Boolean,
    density: Float,
): Pair<ResizeRect, ResizeRect> {
    val next = raw.dragBy(
        handle = handle,
        dx = if (heightOnly) 0f else dx,
        dy = dy,
        bounds = limits.bounds,
        minWidth = limits.minWidth,
        minHeight = limits.minHeight,
        maxWidth = limits.maxWidth,
        maxHeight = limits.maxHeight,
    )
    val shown = if (aspect == null || heightOnly) next
        else start.resizeWithReachableWidth(next, handle, limits.bounds, aspect, density, limits.dragBarPx)
    return next to shown
}
