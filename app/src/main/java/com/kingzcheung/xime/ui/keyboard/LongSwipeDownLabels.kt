package com.kingzcheung.xime.ui.keyboard

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 下滑标签写得长的键盘：导入的乱序十七把下滑标签写成韵母助记（"a ia ua"、"ang iao"），
 * 标准键面的下滑角标只写得下 [CORNER_LABEL_MAX_CHARS] 个字符，长的被截掉；
 * 紧凑键面（button_layout: compact）写得全，但主标签被挤到左上角（用户群 11-22）。
 *
 * 当前键盘只要有一个键的下滑标签（键上显示的，不算只给气泡的）超过 4 个字符，整块键盘的下滑标签都改写在
 * 键底部单独一行、居中，主标签上移半行和它一起居中；一行放不下先缩字号，再不行在空格处分两行，
 * 排法同双拼键位提示的韵母（[fitDoublePinyinFinals]）。内置键盘键上显示的下滑角标都不超过 4 个字符，不受影响。
 */
internal val LocalLongSwipeDownLabels = staticCompositionLocalOf { false }

/** 标准键面角上的上、下滑标签最多写几个字符（多了截掉）。 */
internal const val CORNER_LABEL_MAX_CHARS = 4

internal fun isLongSwipeDownLabel(label: String?): Boolean =
    label != null && label.trim().length > CORNER_LABEL_MAX_CHARS

/** 键底部那一行怎么排：按空格分词，汉字按 1em 估宽，拉丁字母沿用韵母提示的 0.58em。 */
internal fun fitSwipeDownLine(label: String, widthDp: Float, heightDp: Float, baseSp: Float, fontScale: Float): DoublePinyinFinalsLayout? {
    val words = label.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return if (label.any { it.code >= 0x2E80 }) fitDoublePinyinFinals(words, widthDp, heightDp, baseSp, fontScale, 1f)
    else fitDoublePinyinFinals(words, widthDp, heightDp, baseSp, fontScale)
}
