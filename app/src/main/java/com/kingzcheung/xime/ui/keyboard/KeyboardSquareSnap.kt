package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.settings.LayoutKind

/** Visual cap geometry, sharing the renderer's gutters and adaptive gaps. */
internal data class KeyboardSquareSnap(val columns: Float, val split: Boolean, val keypad: Boolean,
    /** 分体中缝占整张键盘宽度的比例。 */
    val gapFraction: Float = 0.1f,
    /** 「加宽按键」：与渲染同一套留白和键缝（分体不加宽）。 */
    val wideKeys: Boolean = false,
    /** 键盘体分几排高（字母键盘 4 排，开了「数字行」再加 [NUMBER_ROW_HEIGHT_WEIGHT]），方形按字母键算。 */
    val rows: Float = 4f,
    /** 「候选字大小」超过 22sp 时候选栏放大的倍数（[candidateTextZoom]）；候选栏变高，按键区相应变矮。 */
    val candidateZoom: Float = 1f) {
    val section get() = if (keypad) "t9" else "qwerty"

    fun capDifference(rect: ResizeRect, density: Float, extra: Float,
        floating: Boolean, spacing: Pair<Float?, Float?>): Float {
        val width = rect.width / density
        val panelHeight = (rect.height - extra) / density
        val bodyHeight = panelHeight - candidateBarHeightDp(candidateBarScale(width, panelHeight) * candidateZoom)
        val fraction = if (split) SplitKeyboardGeometry.keyWidthFraction(width, gapFraction,
            SplitKeyboardGeometry.gutterDp(floating)) else 1f
        val widened = wideKeys && !split
        val policy = (if (keypad) KeyVisualPolicy.T9 else KeyVisualPolicy.Qwerty)
            .let { if (widened) it.widened() else it }
            .copy(maxKeyWidth = Float.MAX_VALUE)
        val metrics = keyboardGridMetrics(policy, width, bodyHeight, columns, rows = rows,
            horizontalInsetDp = if (widened) KeyVisualPolicy.WideKeysGutter else 8f,
            allowShrink = floating, widthFraction = fraction, growthSpacing = spacing)
        val cellWidth = metrics.cellWidthDp
        return cellWidth - resolvedVisualGap(spacing.first, metrics.insetX ?: 0f) -
            (metrics.cellHeightDp - resolvedVisualGap(spacing.second, metrics.insetY ?: 0f) - 2f * metrics.extraInsetY)
    }

    /**
     * 当前宽度下按键恰好为正方形时的面板总高度（px，含 [extra]）；
     * 范围内没有方形点时返回 null。
     */
    fun squareTotalHeightPx(rect: ResizeRect, density: Float, extra: Float, floating: Boolean,
        spacing: Pair<Float?, Float?>, minTotalDp: Float = 100f, maxTotalDp: Float = 800f): Float? {
        fun diff(totalDp: Float) = capDifference(
            ResizeRect(rect.left, rect.bottom - totalDp * density, rect.right, rect.bottom),
            density, extra, floating, spacing)
        var low = minTotalDp
        var high = maxTotalDp
        // 面板越高按键越高：差值随高度单调下降；两端同号说明范围内没有方形点。
        if (diff(low) < 0f || diff(high) > 0f) return null
        repeat(24) {
            val mid = (low + high) / 2f
            if (diff(mid) > 0f) low = mid else high = mid
        }
        return (low + high) / 2f * density
    }

    companion object {
        fun forLayout(kind: LayoutKind, split: Boolean, gapFraction: Float = 0.1f,
            wideKeys: Boolean = false, numberRow: Boolean = false): KeyboardSquareSnap? = when (kind) {
            LayoutKind.ALPHABETIC -> KeyboardSquareSnap(10f, split, false, gapFraction, wideKeys,
                rows = 4f + if (numberRow) NUMBER_ROW_HEIGHT_WEIGHT else 0f)
            LayoutKind.T9 -> KeyboardSquareSnap(5f * 3f / 3.4f, false, true, wideKeys = wideKeys)
            else -> null
        }
    }
}

/** Only snap the dragged edge, within six dp; leaving that band releases immediately. */
internal fun ResizeRect.snapSquareKeys(handle: ResizeHandle, geometry: KeyboardSquareSnap?,
    bounds: ResizeRect, density: Float, extra: Float, floating: Boolean,
    spacing: Pair<Float?, Float?>, thresholdDp: Float = 6f): ResizeRect {
    if (geometry == null || handle == ResizeHandle.NONE) return this
    // Reaching the window boundary wins over the optional square detent.
    // Otherwise a square point six dp short of the edge could prevent full width again.
    val atWidthBoundary = when (handle) {
        ResizeHandle.LEFT, ResizeHandle.TOP_LEFT, ResizeHandle.BOTTOM_LEFT -> left <= bounds.left + 0.5f * density
        ResizeHandle.RIGHT, ResizeHandle.TOP_RIGHT, ResizeHandle.BOTTOM_RIGHT -> right >= bounds.right - 0.5f * density
        else -> false
    }
    if (atWidthBoundary) return this
    val horizontal = handle in listOf(ResizeHandle.LEFT, ResizeHandle.RIGHT,
        ResizeHandle.TOP_LEFT, ResizeHandle.TOP_RIGHT, ResizeHandle.BOTTOM_LEFT, ResizeHandle.BOTTOM_RIGHT)
    fun shifted(delta: Float): ResizeRect = when (handle) {
        ResizeHandle.LEFT, ResizeHandle.TOP_LEFT, ResizeHandle.BOTTOM_LEFT -> copy(left = left + delta)
        ResizeHandle.RIGHT, ResizeHandle.TOP_RIGHT, ResizeHandle.BOTTOM_RIGHT -> copy(right = right + delta)
        ResizeHandle.TOP -> copy(top = top + delta)
        ResizeHandle.BOTTOM -> copy(bottom = bottom + delta)
        else -> this
    }
    val tolerance = thresholdDp * density
    var low = -tolerance
    var high = tolerance
    fun error(delta: Float) = geometry.capDifference(shifted(delta), density, extra, floating, spacing)
    val increasing = (horizontal && handle in listOf(ResizeHandle.RIGHT, ResizeHandle.TOP_RIGHT,
        ResizeHandle.BOTTOM_RIGHT)) || handle == ResizeHandle.TOP
    if (error(low) * error(high) > 0f) return this
    repeat(20) {
        val mid = (low + high) / 2f
        if ((error(mid) < 0f) == increasing) low = mid else high = mid
    }
    val snapped = shifted((low + high) / 2f)
    return if (snapped.left >= bounds.left && snapped.right <= bounds.right &&
        snapped.top >= bounds.top && snapped.bottom <= bounds.bottom &&
        snapped.width > 0f && snapped.height > 0f) snapped else this
}
