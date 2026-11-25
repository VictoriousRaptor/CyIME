package com.kingzcheung.xime.ui.keyboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** 所有键盘共用键帽内容尺度：跟随实际键帽，不能用屏幕尺寸放大悬浮键盘。 */
internal object KeyboardKeyMetrics {
    val FunctionIconSize = 20.dp
    val LabelSize = 18.sp

    fun longPressMenuWidthPx(keyWidth: Float, cells: Int, keyboardWidth: Float, margin: Float): Float =
        (cells.coerceAtLeast(1) * keyWidth.coerceAtLeast(1f))
            .coerceAtMost((keyboardWidth - 2f * margin).coerceAtLeast(1f))

    /** 长按选项条离屏幕边留的空。 */
    val LongPressMenuMargin = 4.dp

    /**
     * 长按选项条的左边和每格宽（和 [keyLeft] 同一坐标）。画气泡（[measureSwipeBubble]）和手指选哪一格
     * （[longPressMenuIndex]）都用它，选中的就是手指上方画着的那一格。
     * 文字项至少按三格宽排，图标项一项一格；条以按键为中心，碰到键盘边就往里挪。
     * [keyboardWidth] 不知道时（null）不往里挪。
     */
    fun longPressMenuCells(keyLeft: Float, keyWidth: Float, count: Int, iconMenu: Boolean,
        keyboardWidth: Float?, margin: Float): LongPressMenuCells {
        val n = count.coerceAtLeast(1)
        val cellMin = if (iconMenu) n else maxOf(n, 3)
        val bodyWidth = longPressMenuWidthPx(keyWidth, cellMin, keyboardWidth ?: Float.MAX_VALUE, margin)
        val centered = keyLeft + keyWidth / 2f - bodyWidth / 2f
        val left = if (keyboardWidth == null) centered
            else centered.coerceIn(margin, maxOf(margin, keyboardWidth - bodyWidth - margin))
        return LongPressMenuCells(left, bodyWidth, bodyWidth / n)
    }

    /**
     * 文字长按菜单一格的宽：贴着最宽的那一项（单个字母约 34dp），不再按键宽撑开（11-24 他：长按 H 气泡中间一大块空）。
     * [dpPx] 是 1dp 的像素数。画气泡和手指选格都用它。
     */
    fun longPressTextCellWidthPx(items: List<String>, dpPx: Float): Float {
        val units = items.maxOfOrNull { s -> s.sumOf { if (it.code < 0x2E80) 1.0 else 1.6 } }?.toFloat() ?: 1f
        return maxOf(34f * dpPx, units * 11f * dpPx + 14f * dpPx)
    }

    /**
     * 文字长按菜单的位置：第 [anchor] 格（默认项）正对按键中心，格宽见 [longPressTextCellWidthPx]；
     * 整排放不下时先把格子收窄，碰到键盘边就整排往里挪（这时默认项照样选中，手指按挪了几格选，见 [longPressMenuStep]）。
     */
    fun longPressTextMenuCells(keyLeft: Float, keyWidth: Float, items: List<String>, anchor: Int,
        keyboardWidth: Float?, margin: Float, dpPx: Float): LongPressMenuCells {
        val n = items.size.coerceAtLeast(1)
        val a = anchor.coerceIn(0, n - 1)
        val center = keyLeft + keyWidth / 2f
        val room = keyboardWidth?.let { (it - 2f * margin).coerceAtLeast(1f) }
        var cell = longPressTextCellWidthPx(items, dpPx)
        if (room != null && cell * n > room) cell = room / n
        if (keyboardWidth != null) {
            // 默认项对准按键时两头都要在键盘里：放不下先收窄格子（不窄过 22dp），每一格都画在手指够得着的地方。
            val leftFit = (center - margin) / (a + 0.5f)
            val rightFit = (keyboardWidth - margin - center) / (n - a - 0.5f)
            val fit = minOf(leftFit, rightFit)
            if (fit < cell) cell = maxOf(fit, minOf(cell, 22f * dpPx))
        }
        val width = cell * n
        val wanted = center - (a + 0.5f) * cell
        val left = if (keyboardWidth == null) wanted else wanted.coerceIn(margin, maxOf(margin, keyboardWidth - width - margin))
        return LongPressMenuCells(left, width, cell)
    }

    /** 从默认项 [anchor] 起手指横着挪了 [dx]，现在选第几格：每挪一格宽换一格，挪到头停在最后一项。 */
    fun longPressMenuStep(anchor: Int, dx: Float, cellWidth: Float, count: Int): Int {
        if (count <= 1 || cellWidth <= 0f) return 0
        return (anchor + kotlin.math.round(dx / cellWidth).toInt()).coerceIn(0, count - 1)
    }

    /**
     * 文字长按菜单从左到右怎么排、默认项在第几格。
     * [nearFirst] 是离手指从近到远的顺序（第一项是默认项）。键在键盘左半边时其余项往右排，右半边往左排：
     * 都往键盘中间那侧展开，边上的键不用往屏幕外挪（11-24 他：右边缘 P 选不到另一个）。
     * [fixedOrder] 时原样排、默认项是 [nearFirst] 的第一项所在格（九键「J K L 5 j k l」照参照图数字居中）。
     */
    fun longPressMenuOrder(items: List<String>, nearFirst: List<String>, fixedOrder: Boolean,
        keyCenterX: Float, keyboardWidth: Float?): Pair<List<String>, Int> {
        if (items.isEmpty()) return items to 0
        val lead = nearFirst.filter { it in items }.distinct()
        if (fixedOrder) return items to (lead.firstOrNull()?.let { items.indexOf(it) } ?: 0).coerceAtLeast(0)
        val outward = lead + items.filter { it !in lead }
        val rightHalf = keyboardWidth != null && keyCenterX > keyboardWidth / 2f + 0.5f
        return if (rightHalf) outward.reversed() to outward.size - 1 else outward to 0
    }

    /** 手指在 [x]（和 [cells] 同一坐标）时落在第几格。 */
    fun longPressMenuIndex(x: Float, cells: LongPressMenuCells, count: Int): Int {
        if (count <= 1 || cells.cellWidth <= 0f) return 0
        return kotlin.math.floor((x - cells.left) / cells.cellWidth).toInt().coerceIn(0, count - 1)
    }

    fun contentScale(widthDp: Float, heightDp: Float): Float {
        // Compose 按像素分配等权行列，兄弟键可能相差不足 1dp；字号不能跟着逐键抖动。
        val scale = adaptiveKeyContentScale(minOf(widthDp, heightDp))
        return (scale * 8f).roundToInt() / 8f
    }

    /** 文本和图标使用相同放大倍率，额外限制长标签、大系统字号和矮键帽。 */
    fun labelSizeSp(text: String, baseSp: Float, widthDp: Float, heightDp: Float,
        fontScale: Float, textScale: Float = 1f, scaleOverride: Float? = null): Float {
        val scale = fontScale.coerceAtLeast(0.1f)
        val widthUnits = text.sumOf { if (it.code < 0x2E80) 0.6 else 1.0 }.toFloat().coerceAtLeast(1f)
        return minOf(baseSp * (scaleOverride ?: contentScale(widthDp, heightDp)) * textScale,
            (widthDp - 4f).coerceAtLeast(1f) / (widthUnits * scale * 1.08f),
            (heightDp - 2f).coerceAtLeast(1f) / (scale * 1.4f)).coerceAtLeast(1f)
    }

    fun iconSizeDp(widthDp: Float, heightDp: Float, baseDp: Float = FunctionIconSize.value, scaleOverride: Float? = null): Float =
        minOf(baseDp * (scaleOverride ?: contentScale(widthDp, heightDp)),
            (minOf(widthDp, heightDp) - 4f).coerceAtLeast(1f))

    fun hintOffsetDp(heightDp: Float, hintSizeSp: Float, fontScale: Float, contentScale: Float): Float =
        adaptiveHintOffsetDp(contentScale)
            // 矮键上提示贴顶只留 1dp（行高本身上方还有约 1dp 留白），给下面的图标多让 1dp。
            .coerceAtMost(((heightDp - hintSizeSp * fontScale * 1.2f) / 2f - 1f).coerceAtLeast(0f))

    /**
     * 图标键上方的提示文字（清空、重输）会不会压到图标：文字中心在键中心上方 [hintCenterAboveDp]。
     * Material 图标的字形约占图标框的 75%，汉字字形约等于字号；两者之间至少留 1dp，
     * 放不下（矮键、大字号）时只画图标。
     */
    fun hintClearsIcon(hintCenterAboveDp: Float, hintSizeSp: Float, fontScale: Float, iconDp: Float): Boolean =
        hintCenterAboveDp - hintSizeSp * fontScale / 2f >= iconDp * 0.375f + 1f
}

/** Grid siblings share one scale; sub-pixel cell allocation must not change their font size. */
internal val LocalKeyboardKeyContentScale = staticCompositionLocalOf<Float?> { null }

@Composable
internal fun keyContentScale(widthDp: Float, heightDp: Float): Float =
    LocalKeyboardKeyContentScale.current ?: KeyboardKeyMetrics.contentScale(widthDp, heightDp)

@Composable
internal fun keyLabelSizeSp(text: String, baseSp: Float, widthDp: Float, heightDp: Float,
    fontScale: Float, textScale: Float = 1f): Float =
    KeyboardKeyMetrics.labelSizeSp(text, baseSp, widthDp, heightDp, fontScale, textScale,
        keyContentScale(widthDp, heightDp))

@Composable
internal fun keyIconSizeDp(widthDp: Float, heightDp: Float, baseDp: Float = KeyboardKeyMetrics.FunctionIconSize.value): Float =
    KeyboardKeyMetrics.iconSizeDp(widthDp, heightDp, baseDp, keyContentScale(widthDp, heightDp))

/** 长按选项条：[left] 起，共 [width] 宽，每格 [cellWidth]。 */
internal data class LongPressMenuCells(val left: Float, val width: Float, val cellWidth: Float)

/**
 * 键盘这一刻在窗口里的位置（和按键 boundsInRoot 同一坐标），给长按选项条算靠边往里挪了多少。
 * 每个键盘布局在根上提供；没提供时选项条按不靠边算。
 */
internal val LocalLongPressMenuKeyboard = staticCompositionLocalOf<(() -> Rect)?> { null }
