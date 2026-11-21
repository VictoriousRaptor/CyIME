package com.kingzcheung.xime.service

/**
 * 固定键盘按键区左右两边挡住系统边缘手势（设置「按键区挡住屏幕边缘手势」，默认关）。
 *
 * 11-20 荣耀用户：系统边缘手势（侧滑返回、侧边应用栏）和边缘防误触会把贴着屏幕左右边按下的键吞掉，
 * 快速打 yao、miao、liang 时 a 键连同下一个键一起丢；改成三键导航后基本不再出现。
 * 系统每条边最多认 200dp 高的排除区，所以从按键区底边往上量，先盖住下面几排按键（a 所在的中排在内）。
 * 底部留白和系统导航那一条不算按键区，不占这 200dp。
 */
data class KeyboardEdgeExclusion(
    /** 按键区底边到容器底边的距离（底部留白 + 系统导航避让），px。 */
    val keysBottomInsetPx: Int,
    /** 按键区（含候选栏）高度，px。 */
    val keysHeightPx: Int,
)

/** 一条排除区，容器坐标，px。 */
internal data class EdgeBand(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** 系统每条边认的排除区高度上限（Android 10 起），dp。 */
internal const val EDGE_EXCLUSION_MAX_HEIGHT_DP = 200f

/** 每条排除区的宽度，dp：比系统边缘手势的触发宽度（默认约 24dp，灵敏度调高时更宽）宽一些。 */
internal const val EDGE_EXCLUSION_WIDTH_DP = 48f

internal fun keyboardEdgeExclusionBands(widthPx: Int, heightPx: Int, spec: KeyboardEdgeExclusion?, density: Float): List<EdgeBand> {
    if (spec == null || widthPx <= 0 || heightPx <= 0 || spec.keysHeightPx <= 0) return emptyList()
    val bottom = (heightPx - spec.keysBottomInsetPx.coerceAtLeast(0)).coerceAtMost(heightPx)
    val maxHeight = (EDGE_EXCLUSION_MAX_HEIGHT_DP * density).toInt()
    val top = (bottom - minOf(spec.keysHeightPx, maxHeight)).coerceAtLeast(0)
    if (bottom <= top) return emptyList()
    val band = minOf((EDGE_EXCLUSION_WIDTH_DP * density).toInt(), widthPx / 2)
    return listOf(EdgeBand(0, top, band, bottom), EdgeBand(widthPx - band, top, widthPx, bottom))
}
