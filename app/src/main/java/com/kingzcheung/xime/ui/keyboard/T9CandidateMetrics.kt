package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.settings.SettingsPreferences

internal data class T9CandidateMetrics(val rowHeightDp: Float, val fontSizeSp: Float,
    /**
     * 打字时左栏读音的字号：和候选栏的候选字一样大，跟「候选字大小」走，每个读音再各自缩到放得下（[FittedRailText]）。
     * 11-13 改成 19sp 乘按键字号和键盘放大倍数，平板上比候选字大一半（用户 11-15：巨大无比）。
     */
    val pinyinFontSizeSp: Float = fontSizeSp)

/** 默认「候选字大小」下符号列表的字号；读音和候选字同大。 */
private const val SYMBOL_RAIL_BASE_SP = 15f

/**
 * Stable four-row rhythm. Text follows the 候选字大小 setting [candidateSp] (用户 11-15：九键拼音列、输入的拼音、
 * 候选字、候选栏图标跟同一个字号走) and grows with the keyboard at half the key speed, like the candidate bar
 * ([candidateBarScale]); [contentScale] is the key content growth of the rail cells.
 */
internal fun t9CandidateMetrics(widthDp: Float, panelHeightDp: Float,
    fontScale: Float, candidateSp: Float, contentScale: Float): T9CandidateMetrics {
    val rowHeight = (panelHeightDp / 4f).coerceAtLeast(32f)
    val growth = 1f + (contentScale - 1f) / 2f
    // Width is fitted against the real font in Compose, not an ASCII-width estimate.
    fun fit(sp: Float) = minOf(sp,
        (rowHeight - 6f).coerceAtLeast(1f) / (fontScale.coerceAtLeast(0.1f) * 1.4f),
        (widthDp - 6f).coerceAtLeast(1f) / fontScale.coerceAtLeast(0.1f))
    val size = candidateSp / SettingsPreferences.DEFAULT_CANDIDATE_TEXT_SIZE
    return T9CandidateMetrics(rowHeight, fit(SYMBOL_RAIL_BASE_SP * size * growth), fit(candidateSp * growth))
}
