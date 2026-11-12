package com.kingzcheung.xime.settings

import com.kingzcheung.xime.ui.keyboard.numberRowShiftSymbol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 开了「数字行」时的角标（用户 11-01 定）：数字行和电脑键帽一样带 ! @ # …，第二、三排不变；关着时和原来一模一样。
 * 用户 11-11「开启数字行多了一堆乱七八糟的符号」：26 键第一排只留中文缺的 + \ { } ?，英文全空，日语不再重复画数字。
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
        assertEquals(listOf("", "", "", "", "", "", "", "", "", "?"), firstRow.map { QwertySwipeSymbols.up(false, true).getValue(it) })
        assertEquals(listOf("+", "", "", "", "", "", "", "\\", "{", "}"), firstRow.map { QwertySwipeSymbols.down(false, true).getValue(it) })
        // 英文第二、三排已经有 ? \ + { }：第一排全空。
        for (key in firstRow) {
            assertEquals("", QwertySwipeSymbols.up(true, true).getValue(key))
            assertEquals("", QwertySwipeSymbols.down(true, true).getValue(key))
        }
    }

    @Test fun `each board has each symbol once and every ascii symbol with the number row`() {
        val shift = "1234567890".mapNotNull { numberRowShiftSymbol(it.toString()) }
        for (english in listOf(false, true)) {
            val corners = letters.flatMap { listOf(QwertySwipeSymbols.up(english, true).getValue(it), QwertySwipeSymbols.down(english, true).getValue(it)) }
                .filter { it.isNotEmpty() }
            val board = corners + shift
            // 第一排不和第二、三排或数字行重复（英文第二、三排本身的重复是原来就有的，不在这里管）。
            val firstRowSymbols = firstRow.flatMap { listOf(QwertySwipeSymbols.up(english, true).getValue(it), QwertySwipeSymbols.down(english, true).getValue(it)) }
                .filter { it.isNotEmpty() }
            for (symbol in firstRowSymbols) assertEquals("english=$english $symbol 只出现一次", 1, board.count { it == symbol })
            // 逗号、句号在空格两侧的键上，不算。
            val missing = "!\"#$%&'()*+-/:;<=>?@[\\]^_`{|}~".map(Char::toString).filter { it !in board }
            assertEquals("english=$english 缺的半角符号", emptyList<String>(), missing)
        }
    }

    @Test fun `saved old first row counts as preset only while the number row is on`() {
        // 以前点「恢复预设」会把 1 和 ! 写成修改；开数字行后它们照样让位给新的一套。
        val saved = mapOf("up.q" to "1", "down.q" to "!", "up.w" to "★", "up.o" to "★", "down.w" to "×", "down.i" to "|", "up.p" to "※")
        val on = QwertySwipeSymbols.apply("q", null, saved, null, numberRow = true)!!
        assertEquals("", on.swipeUp!!.value)
        assertEquals("+", on.swipeDown!!.value)
        // 11-11 以前数字行预设里同一格的符号（O 上 ★、W 下 ×、I 下 |、P 上 ※）也算预设，换成新的一套。
        assertEquals("", QwertySwipeSymbols.apply("o", null, saved, null, numberRow = true)!!.swipeUp!!.value)
        assertEquals("", QwertySwipeSymbols.apply("w", null, saved, null, numberRow = true)!!.swipeDown!!.value)
        assertEquals("\\", QwertySwipeSymbols.apply("i", null, saved, null, numberRow = true)!!.swipeDown!!.value)
        assertEquals("?", QwertySwipeSymbols.apply("p", null, saved, null, numberRow = true)!!.swipeUp!!.value)
        // 自己改成别的符号的格子照旧（★ 原来在 O，放到 W 上就是自己改的）。
        assertEquals("★", QwertySwipeSymbols.apply("w", null, saved, null, numberRow = true)!!.swipeUp!!.value)
        val off = QwertySwipeSymbols.apply("q", null, saved, null, numberRow = false)!!
        assertEquals("1", off.swipeUp!!.value)
        assertEquals("!", off.swipeDown!!.value)
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
