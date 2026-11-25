package com.kingzcheung.xime.ui.keyboard

import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.math.roundToInt

internal val LocalFourteenKeyLayout = staticCompositionLocalOf { false }

internal fun normalizeLetterSwipeDistance(value: Float): Float =
    (((value.takeIf { it.isFinite() } ?: 24f).coerceIn(12f, 72f) / 2f).roundToInt() * 2f)

/** 上下滑输入符号的默认距离：和原来写死在按键手势里的 50dp 一样（#426 之前）。 */
internal const val DEFAULT_SYMBOL_SWIPE_DP = 50f

/** 上下滑输入符号的距离，30–90dp，每档 4dp；读到坏值按默认。 */
internal fun normalizeSymbolSwipeDistance(value: Float): Float =
    (((value.takeIf { it.isFinite() } ?: DEFAULT_SYMBOL_SWIPE_DP).coerceIn(30f, 90f) - 30f) / 4f).roundToInt() * 4f + 30f

/** 14 键固定有字母对；自建布局只有合并格才有，单字母键和功能键不参与。 */
internal fun letterSwipeAvailable(fourteenKey: Boolean, customHasMergedGroups: Boolean): Boolean =
    fourteenKey || customHasMergedGroups

/** Only paired alphabet keys participate; function keys and single letters never do. */
internal fun fourteenKeyLetters(group: String?, enabled: Boolean): Pair<String, String>? =
    group?.takeIf { enabled && it.length == 2 && it.all { char -> char.lowercaseChar() in 'a'..'z' } }
        ?.let { it[0].toString() to it[1].toString() }
