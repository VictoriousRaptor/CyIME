package com.kingzcheung.xime.ui.keyboard

internal data class T9CandidateMetrics(val rowHeightDp: Float, val fontSizeSp: Float,
    /**
     * 打字时左栏读音的字号（用户 11-13：拼音太小）：比符号列表大，每个读音再各自缩到放得下（[FittedRailText]），
     * 不再让所有读音都按最长的 shuang 一起缩小。
     */
    val pinyinFontSizeSp: Float = fontSizeSp)

/** 左栏读音的基准字号（符号列表仍是 15）。 */
internal const val PINYIN_RAIL_BASE_SP = 19f

/** Stable four-row rhythm; a long valid syllable reserves the same width for every item. */
internal fun t9CandidateMetrics(widthDp: Float, panelHeightDp: Float,
    fontScale: Float, textScale: Float, contentScale: Float): T9CandidateMetrics {
    val rowHeight = (panelHeightDp / 4f).coerceAtLeast(32f)
    // Width is fitted against the real font in Compose, not an ASCII-width estimate.
    val fontSize = minOf(15f * contentScale * textScale,
        (rowHeight - 6f).coerceAtLeast(1f) / (fontScale.coerceAtLeast(0.1f) * 1.4f),
        (widthDp - 6f).coerceAtLeast(1f) / fontScale.coerceAtLeast(0.1f))
    val pinyinFontSize = minOf(PINYIN_RAIL_BASE_SP * contentScale * textScale,
        (rowHeight - 6f).coerceAtLeast(1f) / (fontScale.coerceAtLeast(0.1f) * 1.4f),
        (widthDp - 6f).coerceAtLeast(1f) / fontScale.coerceAtLeast(0.1f))
    return T9CandidateMetrics(rowHeight, fontSize, pinyinFontSize)
}
