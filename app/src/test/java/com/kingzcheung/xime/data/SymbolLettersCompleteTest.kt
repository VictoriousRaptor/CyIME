package com.kingzcheung.xime.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** #449：花体等字母页每种字体都是完整、按顺序的 A–Z / a–z（Unicode 缺的那几个用字母符号区的同形字补上）。 */
class SymbolLettersCompleteTest {
    @Test fun everyLetterStyleRunsAToZInOrder() {
        val letters = SymbolData.categories.first { it.id == "englishLetters" }.symbols
        val styled = letters.map { symbol ->
            val name = Character.getName(symbol.codePointAt(0)).removePrefix("MATHEMATICAL ")
                .replace("BLACK-LETTER", "FRAKTUR")
            name.substringBeforeLast(' ') to name.substringAfterLast(' ')
        }
        val runs = mutableListOf<Pair<String, StringBuilder>>()
        for ((style, letter) in styled) {
            if (runs.lastOrNull()?.first != style) runs += style to StringBuilder()
            runs.last().second.append(letter)
        }
        assertEquals("每种字体只出现一段", runs.size, runs.map { it.first }.distinct().size)
        for ((style, seq) in runs) assertEquals(style, "ABCDEFGHIJKLMNOPQRSTUVWXYZ", seq.toString())
    }
}
