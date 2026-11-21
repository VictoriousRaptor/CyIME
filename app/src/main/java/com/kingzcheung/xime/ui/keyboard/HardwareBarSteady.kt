package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.service.HardwareCursorAnchor

/**
 * 实体键盘浮条打字时稳住（用户 11-20：固定在底部，每打一个字母浮条就乱晃、宽度在变、停不下来，
 * 还挡住输入框；想要 Gboard 那样固定在底栏，只是里面的内容带点动画）。规则和 Windows 端一致：
 *
 * - 打字时条子宽度固定、位置不动，右侧按钮收成一条细边，把位置让给候选；停手 [HARDWARE_TOOLS_RETURN_MS] 后放回来。
 *   字与字之间候选会短暂为空、上屏后又出联想，不能让按钮跟着每个字进进出出。
 * - 长句只往右加宽：同一次输入里候选卡、拼音格只长不缩，左边不动。
 * - 「避开输入区域」只上下挪，不横着跳；候选卡的横向位置在一次输入里定在开始打字的地方。
 * - 挡住正在打的字时，条子和候选在 [HARDWARE_OVERLAP_FADE_MS] 内一起淡到 [HARDWARE_OVERLAP_ALPHA]，不挡了再恢复。
 */
internal const val HARDWARE_TOOLS_RETURN_MS = 1200L

/** 挡住正在打的字时的不透明度（40% 透明）。 */
internal const val HARDWARE_OVERLAP_ALPHA = 0.6f

internal const val HARDWARE_OVERLAP_FADE_MS = 220

/** 贴底时拼音格最多占候选长条的这么多。 */
internal const val HARDWARE_PREEDIT_SLOT_FRACTION = 0.35f

/**
 * 只长不缩的宽度：按 [stepPx] 一档一档长，最宽 [maxPx]。
 * 一次输入里多打一个字母，候选不跟着往右挪；长到一档才挪一次。[stepPx] 为 0 时不分档。
 */
internal fun hardwareGrowOnlyWidth(current: Int, natural: Int, stepPx: Int, maxPx: Int): Int {
    if (maxPx <= 0) return 0
    val stepped = if (stepPx <= 0) natural else ((natural + stepPx - 1) / stepPx) * stepPx
    return maxOf(current, stepped).coerceIn(0, maxPx)
}

/**
 * 正在打字的那一行（宿主内坐标）：知道输入框就取输入框左右边到光标这一行，不知道就取光标左右各 [padPx]。
 * 拼音直接写在输入框里时，正在打的字在光标左边，光一个光标框不够。
 */
internal fun hardwareTypingLine(caret: HardwareCandidateExclusion?, editor: HardwareCandidateExclusion?, padPx: Int): HardwareCandidateExclusion? {
    if (caret == null || caret.bottom <= caret.top) return null
    val box = editor?.takeIf { it.right > it.left && it.left <= caret.left && caret.right <= it.right }
    return HardwareCandidateExclusion(box?.left ?: (caret.left - padPx), caret.top, box?.right ?: (caret.right + padPx), caret.bottom)
}

internal fun HardwareCandidateExclusion.overlaps(other: HardwareCandidateExclusion?): Boolean =
    other != null && left < other.right && other.left < right && top < other.bottom && other.top < bottom &&
        right > left && bottom > top

/** 屏幕坐标的光标/输入框换成宿主内坐标。 */
internal fun HardwareCursorAnchor.inHost(originX: Float, originY: Float): HardwareCandidateExclusion? {
    if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) return null
    return HardwareCandidateExclusion(
        kotlin.math.floor(left - originX).toInt(), kotlin.math.floor(top - originY).toInt(),
        kotlin.math.ceil(right - originX).toInt(), kotlin.math.ceil(bottom - originY).toInt())
}

/**
 * 一次输入里候选卡的横向位置定在开始打字时的光标处，纵向跟着光标换行走（换行了还得躲开新的一行）。
 * [start] 是这次输入开始时的光标，没开始打字时传 null，原样跟光标。
 */
internal fun hardwareSteadyAnchor(live: HardwareCursorAnchor?, start: HardwareCursorAnchor?): HardwareCursorAnchor? =
    if (live == null || start == null) live else HardwareCursorAnchor(start.left, live.top, start.right, live.bottom)
