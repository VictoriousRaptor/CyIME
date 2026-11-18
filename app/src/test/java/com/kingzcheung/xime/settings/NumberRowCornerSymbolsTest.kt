package com.kingzcheung.xime.settings

import com.kingzcheung.xime.ui.keyboard.numberRowShiftSymbol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 开了「数字行」时的角标（用户 11-01 定）：数字行和电脑键帽一样带 ! @ # …，第二、三排不变；关着时和原来一模一样。
 * 用户 11-11「开启数字行多了一堆乱七八糟的符号」：日语不再重复画数字。
 * 用户 11-15：第一排不能空着，下方照旧 ! @ # …，上方放别处没有的符号（各套布局见 CornerVariantSymbolsTest）。
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
        // 用户 11-15：开数字行时 Q 到 P 这一排不能空着。下方照旧是 ! @ # …（位置和不开数字行时一样），上方换成别处没有的符号。
        for (english in listOf(false, true)) {
            for (key in letters) {
                val up = QwertySwipeSymbols.up(english, numberRow = true).getValue(key)
                val down = QwertySwipeSymbols.down(english, numberRow = true).getValue(key)
                if (key in firstRow) {
                    assertTrue("$key 上方不再是数字", up.isNotEmpty() && up.none(Char::isDigit))
                    assertEquals(QwertySwipeSymbols.down(english).getValue(key), down)
                } else {
                    assertEquals(QwertySwipeSymbols.up(english).getValue(key), up)
                    assertEquals(QwertySwipeSymbols.down(english).getValue(key), down)
                }
            }
        }
        assertEquals(listOf("“", "”", "‘", "’", "·", "+", "\\", "{", "}", "?"), firstRow.map { QwertySwipeSymbols.up(false, true).getValue(it) })
        assertEquals("!@#$%^&*()", firstRow.joinToString("") { QwertySwipeSymbols.down(false, true).getValue(it) })
    }

    @Test fun `first row top symbols are new and every ascii symbol is reachable with the number row`() {
        for (english in listOf(false, true)) {
            val corners = letters.flatMap { listOf(QwertySwipeSymbols.up(english, true).getValue(it), QwertySwipeSymbols.down(english, true).getValue(it)) }
            // 第一排上方不和别的格子重复。
            for (symbol in firstRow.map { QwertySwipeSymbols.up(english, true).getValue(it) })
                assertEquals("english=$english $symbol 只出现一次", 1, corners.count { it == symbol })
            // 逗号、句号在空格两侧的键上，不算。
            val missing = "!\"#$%&'()*+-/:;<=>?@[\\]^_`{|}~".map(Char::toString).filter { it !in corners }
            assertEquals("english=$english 缺的半角符号", emptyList<String>(), missing)
        }
    }

    @Test fun `japanese first row stops repeating the digits only while the number row is on`() {
        val q = KeyGestureConfig(tap = GestureDef(label = "q", value = "q"), swipeUp = GestureDef(label = "1", value = "1"))
        val a = KeyGestureConfig(swipeUp = GestureDef(label = "@", value = "@"))
        try {
            KeysConfigHelper.numberRowCorners = false
            assertEquals(q, KeysConfigHelper.withoutNumberRowDigits("q", q))
            KeysConfigHelper.numberRowCorners = true
            val stripped = KeysConfigHelper.withoutNumberRowDigits("q", q)
            assertEquals("", stripped.swipeUp!!.label)
            assertEquals(com.kingzcheung.xime.keyboard.GestureAction.NONE, stripped.swipeUp!!.action)
            assertEquals(q.tap, stripped.tap)
            // 不是数字的角标、不在第一排的键照旧。
            assertEquals(a, KeysConfigHelper.withoutNumberRowDigits("a", a))
            val p = KeyGestureConfig(swipeUp = GestureDef(label = "※", value = "※"))
            assertEquals(p, KeysConfigHelper.withoutNumberRowDigits("p", p))
        } finally {
            KeysConfigHelper.numberRowCorners = false
        }
    }
}
