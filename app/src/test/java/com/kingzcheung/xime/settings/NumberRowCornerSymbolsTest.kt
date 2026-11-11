package com.kingzcheung.xime.settings

import com.kingzcheung.xime.ui.keyboard.numberRowShiftSymbol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 开了「数字行」时的角标（用户 11-01 定）：数字行和电脑键帽一样带 ! @ # …，
 * 26 键第一排的 20 格换成另一套，第二、三排不变；关着时和原来一模一样。
 */
class NumberRowCornerSymbolsTest {
    private val letters = QwertySwipeSymbols.keys.map(Char::toString)
    private val firstRow = QwertySwipeSymbols.rows[0].map(Char::toString)

    @Test fun `number keys carry the pc shift symbols and follow the digit`() {
        assertEquals("!@#$%^&*()", "1234567890".map { numberRowShiftSymbol(it.toString()) }.joinToString(""))
        // 自己加进数字行的符号、短语没有角标。
        assertNull(numberRowShiftSymbol("@"))
        assertNull(numberRowShiftSymbol("，"))
    }

    @Test fun `first row gets the new symbols and the other rows stay`() {
        for (english in listOf(false, true)) {
            for (key in letters) {
                val up = QwertySwipeSymbols.up(english, numberRow = true).getValue(key)
                val down = QwertySwipeSymbols.down(english, numberRow = true).getValue(key)
                if (key in firstRow) {
                    assertTrue("$key 上方不再是数字", up.none(Char::isDigit))
                    assertTrue("$key 下方不再是数字行的 Shift 符号", down !in "!@#$%^&*()".map(Char::toString))
                } else {
                    assertEquals(QwertySwipeSymbols.up(english).getValue(key), up)
                    assertEquals(QwertySwipeSymbols.down(english).getValue(key), down)
                }
            }
        }
        assertEquals("「」『』℃←→√★?", firstRow.joinToString("") { QwertySwipeSymbols.up(false, true).getValue(it) })
        assertEquals("+×÷≈≤≥°\\{}", firstRow.joinToString("") { QwertySwipeSymbols.down(false, true).getValue(it) })
        // 第一排上方没有半角写法，中英同一格同一个。
        for (key in firstRow) assertEquals(key, QwertySwipeSymbols.up(false, true).getValue(key), QwertySwipeSymbols.up(true, true).getValue(key))
    }

    @Test fun `chinese board has each symbol once and everything the english board had`() {
        val chinese = letters.flatMap { listOf(QwertySwipeSymbols.up(false, true).getValue(it), QwertySwipeSymbols.down(false, true).getValue(it)) } +
            "1234567890".mapNotNull { numberRowShiftSymbol(it.toString()) }
        assertEquals(chinese.size, chinese.toSet().size)
        val english = letters.flatMap { listOf(QwertySwipeSymbols.up(true).getValue(it), QwertySwipeSymbols.down(true).getValue(it)) }
        assertEquals(emptyList<String>(), english.filter { symbol -> symbol.none(Char::isDigit) && symbol !in chinese })
    }

    @Test fun `saved old first row counts as preset only while the number row is on`() {
        // 以前点「恢复预设」会把 1 和 ! 写成修改；开数字行后它们照样让位给新的一套。
        val saved = mapOf("up.q" to "1", "down.q" to "!", "up.w" to "★")
        val on = QwertySwipeSymbols.apply("q", null, saved, null, numberRow = true)!!
        assertEquals("「", on.swipeUp!!.value)
        assertEquals("+", on.swipeDown!!.value)
        // 自己改成别的符号的格子照旧。
        assertEquals("★", QwertySwipeSymbols.apply("w", null, saved, null, numberRow = true)!!.swipeUp!!.value)
        val off = QwertySwipeSymbols.apply("q", null, saved, null, numberRow = false)!!
        assertEquals("1", off.swipeUp!!.value)
        assertEquals("!", off.swipeDown!!.value)
    }
}
