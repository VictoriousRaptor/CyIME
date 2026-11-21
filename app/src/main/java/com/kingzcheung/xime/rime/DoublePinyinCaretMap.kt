package com.kingzcheung.xime.rime

import com.kingzcheung.xime.settings.DoublePinyinScheme

/**
 * 26 键双拼：输入框里的编码显示成全拼（shuang'pin），编辑缓冲却是双拼键（ulpb），两边逐字对不上。
 * 按方案键位把显示的每个音节对回它的键，得到两边对应的位置：音节前、声母后、音节后。
 * 光标后 Rime 原样回显的键一字对一键，只打了声母的 sh/ch/zh 对一个键。对不上时 [build] 为 null。
 */
internal class DoublePinyinCaretMap private constructor(
    /** (显示位置, 键位置)，两列都不减。 */
    private val points: List<Pair<Int, Int>>,
) {
    /** 点在显示的音节中间时落到最近的分界；一样近时取后面的。 */
    fun rawOffset(display: Int): Int {
        points.firstOrNull { it.first == display }?.let { return it.second }
        val lower = points.lastOrNull { it.first < display } ?: return points.first().second
        val upper = points.firstOrNull { it.first > display } ?: return lower.second
        return if (display - lower.first < upper.first - display) lower.second else upper.second
    }

    /** 键位置对应的显示位置；分隔符两边都对得上时取分隔符前面。 */
    fun displayOffset(raw: Int): Int =
        (points.firstOrNull { it.second == raw } ?: points.lastOrNull { it.second < raw } ?: points.first()).first

    companion object {
        fun build(display: String, raw: String, scheme: DoublePinyinScheme): DoublePinyinCaretMap? {
            val shown = display.lowercase().replace('ü', 'v')
            val points = mutableListOf(0 to 0)
            var d = 0
            var r = 0
            while (d < shown.length) {
                if (shown[d] == '\'') {
                    d++
                    if (r < raw.length && raw[r] == '\'') r++
                    points += d to r
                    continue
                }
                if (r < raw.length && raw[r] == '\'') {
                    r++
                    points += d to r
                    continue
                }
                if (r >= raw.length) return null
                val chunkEnd = shown.indexOf('\'', d).let { if (it < 0) shown.length else it }
                var matched = false
                for (end in chunkEnd downTo d + 1) {
                    val syllable = shown.substring(d, end)
                    val code = scheme.code(syllable) ?: continue
                    if (!raw.startsWith(code, r)) continue
                    val initial = DoublePinyinScheme.SPECIAL_INITIALS.firstOrNull { syllable.startsWith(it) }?.length ?: 1
                    if (code.length == 2 && initial < syllable.length) points += (d + initial) to (r + 1)
                    d = end
                    r += code.length
                    matched = true
                    break
                }
                if (!matched) {
                    val initialKey = DoublePinyinScheme.SPECIAL_INITIALS.firstOrNull { shown.startsWith(it, d) }
                        ?.let { scheme.initials[it] }
                    when {
                        initialKey != null && raw[r] == initialKey -> { d += 2; r++ }
                        shown[d] == raw[r] -> { d++; r++ }
                        else -> return null
                    }
                }
                points += d to r
            }
            if (raw.drop(r).any { it != '\'' }) return null
            if (r < raw.length) points += d to raw.length
            return DoublePinyinCaretMap(points)
        }
    }
}

/**
 * 双拼编辑栏里是双拼键（woxlvidc…），一个音节两个键。点在一个音节的两个键中间时，光标落到音节分界：
 * 不然选了光标前的词，后面的键从半个音节开始重新切分，拼音条变成乱码（11-19 小鹤双拼：点中间选词，后面的拼音没了）。
 * [reading] 是引擎的读音（显示全拼时 wo'xiang，不显示时 wo xl），对得上的音节才算分界；光标后原样回显的键按两个一组。
 * 读音对不上的地方不动光标。[direction] 0 取最近的分界（一样近取后面），1 往后，-1 往前。
 */
internal fun snapDoublePinyinCaret(raw: String, reading: String, scheme: DoublePinyinScheme?, caret: Int, direction: Int = 0): Int {
    val boundaries = sortedSetOf(0)
    var r = 0
    fun skipSeparators() { while (r < raw.length && raw[r] == '\'') { boundaries += r; r++; boundaries += r } }
    skipSeparators()
    for (syllable in reading.lowercase().replace('ü', 'v').split(' ', '\'').filter { it.isNotEmpty() }) {
        val code = scheme?.code(syllable)
        when {
            code != null && raw.startsWith(code, r) -> r += code.length
            raw.startsWith(syllable, r) -> {
                // 原样回显的一串键（光标后面的部分）：两个键一个音节。
                for (i in 2 until syllable.length step 2) boundaries += r + i
                r += syllable.length
            }
            else -> break
        }
        boundaries += r
        skipSeparators()
    }
    val position = caret.coerceIn(0, raw.length)
    if (position in boundaries || position > boundaries.last()) return position
    val before = boundaries.floor(position) ?: 0
    val after = boundaries.ceiling(position) ?: return position
    return when {
        direction > 0 -> after
        direction < 0 -> before
        position - before < after - position -> before
        else -> after
    }
}
