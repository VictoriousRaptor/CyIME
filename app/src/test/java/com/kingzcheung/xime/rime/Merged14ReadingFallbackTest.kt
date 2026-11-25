package com.kingzcheung.xime.rime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 14 键编码条：首选是英文词、混合词或没有读音的短语时不再显示大写原码（用户 11-13「1.15.68 修的十四键 bug 又回来了」）。
 * 1.15.69 起 14 键能打英文和混合词，1.15.82 起 B站 这类混合词按权重常排第一，它们的注释对不上合并键。
 */
class Merged14ReadingFallbackTest {
    @Test fun firstReadingThatCoversEveryKeyWins() {
        assertEquals("ni'hao", merged14PreeditFrom("BUGaO", "BUGaO", listOf("ni hao", "bu gao")))
    }

    @Test fun englishTopShowsItsOwnLetters() {
        // ok：o 在 op、k 在 jk；英文词没有拼音注释，用它自己的字母。
        assertEquals("ok", merged14PreeditFrom("OJ", "OJ", listOf("", "ok")))
    }

    @Test fun mixedOrUnreadTopFallsBackToTheNextReading() {
        // 首选 B站 的注释是空的，第二个 霸占 的读音 ba zhan 对得上每个键。
        assertEquals("ba'zhan", merged14PreeditFrom("BaZGaB", "BaZGaB", listOf("", "ba zhan")))
    }

    @Test fun nothingFitsKeepsRimesPreedit() {
        assertEquals("QE", merged14PreeditFrom("QE", "QE", listOf("", "zhong guo", "")))
        assertEquals("QE", merged14PreeditFrom("QE", "QE", emptyList()))
    }
}
