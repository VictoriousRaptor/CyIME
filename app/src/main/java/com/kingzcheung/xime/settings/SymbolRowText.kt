package com.kingzcheung.xime.settings

/**
 * 「按键符号 → 整排输入」：一排键的符号写成一行字，一次改完一整排（用户 11-15：一个一个符号改太弱）。
 * 规则只有一条，和单个键「留空不放符号」一致（用户 11-19：空格子打 □ 反人类、填的时候空格被忽略）：
 * 一个字符一格，空格就是空着的格子；几个字的短语用「」括起来算一格。
 */
internal object SymbolRowText {
    const val PHRASE_OPEN = "「"
    const val PHRASE_CLOSE = "」"

    private fun codePoints(text: String): List<String> = buildList {
        var index = 0
        while (index < text.length) {
            val next = text.offsetByCodePoints(index, 1)
            add(text.substring(index, next))
            index = next
        }
    }

    /** 半角、全角空格都算空位：中文全角时按空格键打出来的是全角空格。 */
    private fun blank(cell: String) = cell.isBlank()

    /** 一格写成什么：空着写一个空格，一个字符原样，短语加「」。 */
    private fun cellText(cell: String): String = when {
        blank(cell) -> " "
        codePoints(cell).size == 1 -> cell
        else -> PHRASE_OPEN + cell + PHRASE_CLOSE
    }

    fun format(cells: List<String>): String = cells.joinToString("") { cellText(it) }

    /**
     * 读回 [count] 格：一个字符一格，空格是空位，「」里两个字以上是一格短语（「」里只有一个字或没有字时，「」各算一格符号）。
     * 写少了后面的格子空着，写多了的不要。前后的空格不去掉，它们就是空位。
     */
    fun parse(text: String, count: Int): List<String> = cells(text).let { cells ->
        List(count) { index -> cells.getOrNull(index).orEmpty() }
    }

    /** 这一行一共写了几格（多写、少写时提示用）。 */
    fun cellCount(text: String): Int = cells(text).size

    private fun cells(text: String): List<String> {
        val chars = codePoints(text)
        val cells = mutableListOf<String>()
        var index = 0
        while (index < chars.size) {
            val char = chars[index]
            if (char == PHRASE_OPEN) {
                val close = (index + 1 until chars.size).firstOrNull { chars[it] == PHRASE_CLOSE || chars[it] == PHRASE_OPEN || blank(chars[it]) }
                if (close != null && chars[close] == PHRASE_CLOSE && close - index - 1 >= 2) {
                    cells += chars.subList(index + 1, close).joinToString("")
                    index = close + 1
                    continue
                }
            }
            cells += if (blank(char)) "" else char
            index++
        }
        return cells
    }
}
