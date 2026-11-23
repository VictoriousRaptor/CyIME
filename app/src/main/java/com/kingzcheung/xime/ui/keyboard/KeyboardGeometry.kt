package com.kingzcheung.xime.ui.keyboard

import kotlin.math.roundToInt

/**
 * 悬浮键盘大屏宽度基准与共用面板最小高度。
 *
 * 手机上宽度通过 floatingResizeMinWidthDp 自适应，避免 400dp 把宽度锁成全屏；
 * 高度仍保持两个方向共用同一基准，尺寸调节不切换布局比例。
 */
internal const val FLOATING_RESIZE_MIN_WIDTH_DP = 400
/**
 * 固定与悬浮共用的面板下限：44dp 工具栏 + 四行各 48dp（Material 最小触控高度）+ 上下 8dp 边距，
 * 键面约 43dp，九键退格「清空」、重输的文字放得下。原 228dp 每行只有 44dp，文字压到图标上（用户 2026-10-30）。
 * 再高会让小手机横屏的默认键盘挤掉剪贴板搜索结果区（ClipboardPanelMatrixTest）。
 */
internal const val FLOATING_RESIZE_MIN_HEIGHT_DP = 244

/**
 * 悬浮调节专用高度范围。
 *
 * 不能复用普通键盘的 keyboardHeightBounds：普通键盘的范围很窄，悬浮调节会几乎拖不动；
 * 也不能直接放到整屏高，否则九键会被拉成夸张的长条。
 * 下限恒为 [FLOATING_RESIZE_MIN_HEIGHT_DP]；可用高度不足时由调用方按
 * [floatingResizeMinHeightDp] 夹到可用区，不产生越界初值。
 */
internal fun floatingResizeHeightBounds(hostHeightDp: Int, landscape: Boolean): IntRange {
    val host = hostHeightDp.coerceAtLeast(1)
    val min = FLOATING_RESIZE_MIN_HEIGHT_DP
    // 放宽大屏纵向调节空间；独立的高宽比上限继续防止拉成长柱。
    val maxByScreen = (host * 70 / 100) - FLOATING_DRAG_BAR_HEIGHT_DP
    val max = maxByScreen.coerceAtLeast(min)
    return min..max
}

/** 最小宽度：手机基准下限与可用宽度取小（[availableWidthDp] 已扣边距）。 */
internal fun floatingResizeMinWidthDp(availableWidthDp: Int): Int =
    availableWidthDp.coerceAtLeast(1).let { available ->
        // 400dp 在手机上会被夹成全屏宽，导致没有缩小空间；大屏仍保留可用尺寸。
        (available * 0.65f).roundToInt().coerceIn(260, FLOATING_RESIZE_MIN_WIDTH_DP)
            .coerceAtMost(available)
    }

/** 最小高度：手机基准下限与可用高度取小（[availableHeightDp] 已扣边距）。 */
internal fun floatingResizeMinHeightDp(availableHeightDp: Int): Int =
    minOf(FLOATING_RESIZE_MIN_HEIGHT_DP, availableHeightDp.coerceAtLeast(1))

/** 旧版本为方形按键吸附存下的悬浮高度可以低到这里（含工具栏，不含拖条）；只用来读已存高度，拖动不再低于 [FLOATING_RESIZE_MIN_HEIGHT_DP]。 */
internal const val FLOATING_RESIZE_ABSOLUTE_MIN_HEIGHT_DP = 160

/**
 * 悬浮键盘拖小的下限按屏幕短边算（用户 11-03「横屏下悬浮窗还不够小…平板上最小尺寸太小，手机上太大」）：
 * 内容高（含工具栏，不含拖条）= 短边 × 0.42，取整到 10dp，夹在 170–300dp；宽 = 高 × 1.6，同样取整到 10dp，
 * 各种屏幕上最小时的样子一样。手机（短边约 384–411dp）170×270dp，比原来的 244×400（横屏）小约 30%；
 * 平板 SM-X800（短边 934dp）300×480dp。调节界面拖边拖角、点亮的四角直接拖都只用这一组，横竖屏同一个值。
 */
internal fun floatingResizeFloorScreenContentDp(screenShortSideDp: Int, physicalFloorDp: Int = Int.MAX_VALUE): Int =
    // 再和按毫米算的下限取小（用户 11-22「平板上最小尺寸还是很大」）：只会更小，手机上 170dp 本来就更小，不变。
    minOf(((screenShortSideDp.coerceAtLeast(1) * 0.42f / 10f).roundToInt() * 10).coerceIn(170, 300),
        physicalFloorDp.coerceAtLeast(FLOATING_RESIZE_ABSOLUTE_MIN_HEIGHT_DP))

/** 悬浮键盘拖小的最低内容高度（见 [floatingResizeFloorScreenContentDp]）；可用高度不够时取可用高度。 */
internal fun floatingResizeFloorContentDp(screenShortSideDp: Int, availableHeightDp: Int,
    physicalFloorDp: Int = Int.MAX_VALUE): Int =
    minOf(floatingResizeFloorScreenContentDp(screenShortSideDp, physicalFloorDp),
        (availableHeightDp - floatingFooterHeightDp(screenShortSideDp)).coerceAtLeast(1))

/** 悬浮键盘拖小的最小宽度：最低内容高 × 1.6 取整到 10dp；可用宽度不够时取可用宽度。 */
internal fun floatingResizeFloorWidthDp(screenShortSideDp: Int, availableWidthDp: Int,
    physicalFloorDp: Int = Int.MAX_VALUE): Int =
    ((floatingResizeFloorScreenContentDp(screenShortSideDp, physicalFloorDp) * 1.6f / 10f).roundToInt() * 10)
        .coerceAtMost(availableWidthDp.coerceAtLeast(1))

/** Android 标称：160dp 一英寸。读不到屏幕实际像素密度、或读数离谱时用它。 */
internal const val NOMINAL_MILLIMETERS_PER_DP = 25.4f / 160f

/**
 * 键盘最矮时每排按键至少多高（毫米，含键缝）。按真实物理尺寸定下限（用户 11-22「按绝对的尺度来算」）：
 * 同样 6 毫米一排，手机、细长屏手机、平板上手指按到的大小一样，不再随屏幕比例或 dp 换算忽大忽小。
 */
internal const val KEYBOARD_MIN_ROW_MM = 6.0f

/**
 * 1dp 的毫米数要像一块真屏幕：手机、平板实测都在 0.15–0.19 之间。部分机型报的 xdpi 是假的（固定 160、
 * 或和 densityDpi 差好几倍），超出 0.11–0.24 就不信，按 Android 标称算。
 */
internal fun plausibleMillimetersPerDp(measured: Float?): Float =
    measured?.takeIf { it.isFinite() && it in 0.11f..0.24f } ?: NOMINAL_MILLIMETERS_PER_DP

/**
 * 按物理尺寸算的键盘最低高度（dp，含 44dp 工具栏）：44dp 工具栏 + 四排各 [KEYBOARD_MIN_ROW_MM] 毫米 + 上下 8dp。
 * 只放宽不收紧：不高于原来的 [FLOATING_RESIZE_MIN_HEIGHT_DP]，所以老用户存着的高度一个都不会被往上推；
 * 不低于读旧悬浮高度用的绝对下限。SM-X800（1.875px/dp、266dpi，0.179mm/dp）≈186dp；S23 Ultra（0.185mm/dp）≈182dp；
 * 标称 0.159mm/dp 的细长屏手机 ≈203dp。原来固定键盘一律 244dp。
 */
internal fun physicalKeyboardFloorDp(millimetersPerDp: Float): Int {
    val mmPerDp = plausibleMillimetersPerDp(millimetersPerDp)
    val rows = 4f * KEYBOARD_MIN_ROW_MM / mmPerDp
    return (KEYBOARD_TOOLBAR_FLOOR_DP + rows + 8f).roundToInt()
        .coerceIn(FLOATING_RESIZE_ABSOLUTE_MIN_HEIGHT_DP, FLOATING_RESIZE_MIN_HEIGHT_DP)
}

/** 下限里的工具栏/候选栏那一截（dp），同 [FLOATING_RESIZE_MIN_HEIGHT_DP] 的算法。 */
private const val KEYBOARD_TOOLBAR_FLOOR_DP = 44f

/** 已保存或拖出的悬浮高度的合法范围：下限放到绝对下限，上限同 [floatingResizeHeightBounds]。 */
internal fun floatingStoredHeightBounds(hostHeightDp: Int, landscape: Boolean): IntRange {
    val bounds = floatingResizeHeightBounds(hostHeightDp, landscape)
    return FLOATING_RESIZE_ABSOLUTE_MIN_HEIGHT_DP.coerceAtMost(bounds.first)..bounds.last
}

/** 悬浮键盘允许的最大高宽比。只限制畸形，不强制等比缩放。 */
internal fun floatingResizeMaxAspect(landscape: Boolean): Float = if (landscape) 0.95f else 1.35f

/** 高度包含44dp工具栏与四行按键，不含悬浮拖条及系统导航栏。 */
/**
 * 固定键盘高度范围。[minHeightDp] 默认是原来的 244dp：算默认高度时一律用默认值，默认高度和 1.15.104 一样；
 * 拖动和读已存高度时传按物理尺寸算的 [physicalKeyboardFloorDp]（见 KeyboardHeightProfiles.fixedBounds）。
 */
internal fun keyboardHeightBounds(screenHeight: Int, landscape: Boolean, navInset: Int = 24,
    minHeightDp: Int = FLOATING_RESIZE_MIN_HEIGHT_DP): IntRange {
    val max = if (landscape) (screenHeight - navInset - FLOATING_DRAG_BAR_HEIGHT_DP - 24).coerceAtLeast(160)
        // 竖屏上限是屏幕高度的一半（含工具栏）；原来 45%，手大的人拉到顶还嫌矮（GitHub #74，用户 11-02 同意）。只放宽上限，不动已存高度和默认高度。
        else (screenHeight * 50 / 100).coerceAtLeast(FLOATING_RESIZE_MIN_HEIGHT_DP)
    // 固定与悬浮共享面板下限，不能随平板屏幕高度放大最小值。（1.14.25 规则，用户 2026-10-19 要求改回）
    // 11-22 起拖动用的下限按毫米算（[minHeightDp]），只会比 244dp 低，不会更高。
    val min = minHeightDp.coerceAtMost(FLOATING_RESIZE_MIN_HEIGHT_DP).coerceAtMost(max)
    return min..max
}

internal fun floatingKeyboardWidth(screenWidth: Int, screenHeight: Int, height: Int, portraitHeight: Int, wide: Boolean): Int {
    val landscape = screenWidth > screenHeight
    // 没有用户保存尺寸时给一个真正可用的默认值：竖屏约 78%，横屏约 48% 屏宽。
    // 不再从普通键盘高度反推宽度，避免横屏默认尺寸忽大忽小。
    val ratio = if (landscape) (if (wide) 0.52f else 0.48f) else 0.78f
    return ((screenWidth * ratio / 10f).roundToInt() * 10)
        .coerceIn(keyboardWidthBounds(screenWidth))
}

/** 悬浮键盘宽度范围（dp）。 */
internal fun keyboardWidthBounds(screenWidth: Int): IntRange {
    val cap = screenWidth.coerceAtLeast(1)
    return floatingResizeMinWidthDp(cap)..cap
}

/**
 * 悬浮卡片宽度解析：设置过宽度就用设置值（夹在 [keyboardWidthBounds] 内），
 * 未设置（0）时按高度推导，保持历史外观。
 */
internal fun resolvedFloatingWidth(
    screenWidth: Int,
    screenHeight: Int,
    height: Int,
    portraitHeight: Int,
    wide: Boolean,
    overrideWidth: Int,
): Int = if (overrideWidth > 0) {
    overrideWidth.coerceIn(keyboardWidthBounds(screenWidth))
} else {
    floatingKeyboardWidth(screenWidth, screenHeight, height, portraitHeight, wide)
}

/** 固定卡片与调整框共同解析旧尺寸；0 仍表示全宽。 */
internal fun resolvedFixedKeyboardWidth(availableWidthDp: Int, savedWidthDp: Int): Int =
    if (savedWidthDp > 0) savedWidthDp.coerceIn(keyboardWidthBounds(availableWidthDp))
    else availableWidthDp.coerceAtLeast(1)
