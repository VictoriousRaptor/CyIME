package com.kingzcheung.xime.ui.keyboard

import androidx.compose.runtime.staticCompositionLocalOf

/** The physical ten-unit letter frame; key grouping and letter order belong to the layout. */
internal data class LetterKeyboardFrame(val rowUnits: List<Int>) {
    val columns: Float get() = 10f

    fun geometry(cellWidth: Float, stagger: Float): LetterRowGeometry {
        val progress = stagger.coerceIn(0f, 1f)
        val middleFree = columns - rowUnits[1]
        val middleStart = middleFree * (.5f - .25f * progress)
        val bottomFree = columns - rowUnits[2]
        val shift = bottomFree * (.5f - .25f * progress)
        return LetterRowGeometry(cellWidth * middleStart, shift,
            middleRowEndInsetDp = cellWidth * (middleFree - middleStart),
            deleteKeyWeight = bottomFree - shift, bottomLetterWeight = rowUnits[2].toFloat())
    }
}

/** Recognize actual row facts, not schema names: complete Latin alphabet, optionally one semicolon.
 * Merged keys retain their span; 14-key equal-width rendering is a separate layout property.
 * Numeric, kana-flick, stroke and arbitrary non-letter grids cannot enter this frame.
 */
internal fun letterKeyboardFrame(rows: List<List<String>>): LetterKeyboardFrame? {
    if (rows.size != 3 || rows.any { it.isEmpty() }) return null
    val keys = rows.flatten()
    if (keys.any { it.length !in 1..2 || it.any { c -> c !in 'a'..'z' && c != ';' } }) return null
    val letters = keys.joinToString("")
    if (letters.count { it == ';' } > 1 || letters.filter { it != ';' }.toList().sorted() != ('a'..'z').toList()) return null
    val units = rows.map { row -> row.sumOf { it.length } }
    // A custom editor's one spare cell may be moved to any row. typingRows removes it;
    // the complete alphabet check above limits the total missing capacity to one unit.
    return if (units[0] in 9..10 && units[1] in 9..10 && units[2] in 6..7)
        LetterKeyboardFrame(units) else null
}

/**
 * 「合并键均匀排布」（设置 → 布局，默认关）每多一个字母多占的宽度：两字母键是单字母键的 4/3 宽。
 * 关闭时合并键按字母数占格，与 26 键键缝严格对齐。
 */
internal const val EVEN_MERGED_EXTRA_PER_LETTER = 1f / 3f

/**
 * 字母键在本行所占的份数。[weighted] 为 false 时每键一份；严格对齐时按字母数；
 * [evenMerged] 时 n 字母键占 1 + (n-1)/3 份，单字母键相对变宽，整行仍铺满 26 键网格给这一行的宽度
 * （第二行缩进、Shift、删除键都不变）。
 */
internal fun letterKeySpan(key: String, weighted: Boolean, evenMerged: Boolean = false): Float = when {
    !weighted -> 1f
    evenMerged -> 1f + (key.length - 1).coerceAtLeast(0) * EVEN_MERGED_EXTRA_PER_LETTER
    else -> key.length.toFloat()
}

/**
 * 14 键大写、按住 Shift 拖动或按住符号键当 Ctrl 时，双字母键拆成两个单字母键（同 KeyboardLayout 的 renderRows）；
 * 拆开的两半各占原合并键的一半，拆开前后每个字母的位置和宽度不变。严格对齐时两半各 1 份，与单字母键相同。
 */
internal fun splitPairSpans(pairedRow: List<String>, evenMerged: Boolean): List<Float> = pairedRow.flatMap { key ->
    val span = letterKeySpan(key, true, evenMerged)
    if (key.length == 2 && key.all { it.lowercaseChar() in 'a'..'z' }) listOf(span / 2, span / 2) else listOf(span)
}

/**
 * 14 键第二排末尾的单字母 l 是否画到行尾。严格对齐时画到行尾，左边缘仍落在 26 键 l 的左边缘、右边与 p 对齐；
 * 「合并键均匀排布」打开时不画到行尾，右边留空和 a 左边一样宽，两边对称（用户 11-11）。只改画的范围，触摸范围不变。
 */
internal fun fourteenHomeRowLastKeyToEdge(fourteenKey: Boolean, homeRow: List<String>, evenMerged: Boolean): Boolean =
    fourteenKey && !evenMerged && homeRow.lastOrNull()?.length == 1

/**
 * 14 键第二排左右留空。「合并键均匀排布」打开时两边取平均，a 左边和 l 右边一样宽：平板上 26 键第二排按实体键盘错位
 * （左窄右宽），照搬过来 l 右边会比 a 左边宽好几倍。总留空不变，键宽不变；严格对齐和其他布局原样返回。
 */
internal fun fourteenHomeRowInsets(fourteenKey: Boolean, evenMerged: Boolean, start: Float, end: Float): Pair<Float, Float> =
    if (fourteenKey && evenMerged) ((start + end) / 2f).let { it to it } else start to end

/** 当前键盘是否按「合并键均匀排布」分键宽：自建/内置字母排列和 14 键生效，分体键盘照旧严格对齐。 */
internal val LocalEvenMergedKeys = staticCompositionLocalOf { false }

/** 14 键末尾单字母 m 从删除键借的宽度。无错位时删除键与 Shift 等宽，m 不借，与 26 键 m 对齐；
 * 宽屏错位把删除键加宽后，m 分走富余的一半（最多一个键宽），删除键仍不窄于 Shift 与删除键的平均宽。 */
internal fun fourteenMBorrowedWeight(sideKeyWeight: Float, deleteKeyWeight: Float): Float =
    ((deleteKeyWeight - sideKeyWeight) / 2f).coerceIn(0f, 1f)

/** Typewise-style honeycomb: 6/7/6/7 single letters, the short rows offset by half a key. */
internal fun isHoneycomb(rows: List<List<String>>): Boolean =
    rows.map { it.size } == com.kingzcheung.xime.settings.CustomKeyboardLayout.HONEYCOMB_ROWS

/** Seven columns; the third row puts Shift and delete in the two half-key margins. */
internal val HoneycombRowGeometry = LetterRowGeometry(0f, .5f, deleteKeyWeight = .5f, bottomLetterWeight = 6f)

internal fun letterKeyboardColumns(rows: List<List<String>>, fourteenKey: Boolean): Float =
    letterKeyboardFrame(rows)?.columns ?: if (isHoneycomb(rows)) 7f
    else if (fourteenKey) 10f else rows.firstOrNull()?.size?.toFloat() ?: 10f

internal fun letterKeyboardBottomRow(rows: List<List<String>>, fourteenKey: Boolean): QwertyBottomRowWeights =
    if (fourteenKey || letterKeyboardFrame(rows) != null || isHoneycomb(rows) || rows.firstOrNull()?.size == 10)
        QwertyBottomRowWeights.Standard else QwertyBottomRowWeights.Legacy

/**
 * 第一行一格的宽度（dp），给 xime.custom.yaml 的 geometry.middle_row_inset 当单位：
 * 键不按字母数占格时一格就是第一行一个键宽，按字母数占格（14 键）时是一个字母宽。
 * [cellWidthDp] 是键盘按 [columns] 列分出的格宽。
 */
internal fun firstRowCellDp(cellWidthDp: Float, columns: Float, firstRow: List<String>, weighted: Boolean): Float {
    val units = firstRow.sumOf { letterKeySpan(it, weighted).toDouble() }.toFloat()
    if (!cellWidthDp.isFinite() || cellWidthDp <= 0f || units <= 0f) return cellWidthDp.coerceAtLeast(0f)
    return cellWidthDp * columns / units
}
