package com.kingzcheung.xime.settings

/**
 * 「按键符号 → 整排输入」：一排键的符号写成一行字，一次改完一整排（用户 11-15：一个一个符号改太弱）。
 * 每格都是单个字符时连着写（`1234567890`）；有的格子是短语时用空格隔开。空着的格子写 [EMPTY]。
 */
internal object SymbolRowText {
    const val EMPTY = "□"

    private fun codePoints(text: String): List<String> = buildList {
        var index = 0
        while (index < text.length) {
            val next = text.offsetByCodePoints(index, 1)
            add(text.substring(index, next))
            index = next
        }
    }

    fun format(cells: List<String>): String =
        cells.joinToString(if (cells.all { codePoints(it).size <= 1 }) "" else " ") { it.ifEmpty { EMPTY } }

    /** 读回 [count] 格：有空格就按空格分格，没有就一个字符一格；写少了后面的格子空着，写多了的丢掉。 */
    fun parse(text: String, count: Int): List<String> {
        val trimmed = text.trim()
        val parts = if (trimmed.any(Char::isWhitespace)) trimmed.split(Regex("\\s+")) else codePoints(trimmed)
        return List(count) { index -> parts.getOrNull(index)?.takeUnless { it == EMPTY }.orEmpty() }
    }
}
