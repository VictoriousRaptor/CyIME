package com.kingzcheung.xime.ui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 群友 11-23（荣耀 8p，小鹤双拼 26 键）：长按 O 默认停在 ö，离手指最近的不是大小写字母；
 * 点按是小写、长按松手还是小写。改成长按到点时大写在手指下并默认选中，小写挨着，带声调、变音的往外排。
 */
class LetterLongPressUpperFirstTest {
    private val o = listOf("o", "O", "ō", "ó", "ǒ", "ò", "ô", "õ", "ö", "ø")

    @Test fun `upper case sits under the finger and lower case next to it`() {
        val arranged = arrangeLongPressMenu(o, listOf("O", "o"), fingerCell = 8)
        assertEquals(listOf("ō", "ó", "ǒ", "ò", "ô", "õ", "ö", "ø", "O", "o"), arranged)
        assertEquals(8, arranged.indexOf("O"))
    }

    @Test fun `at the right edge lower case goes to the left`() {
        val arranged = arrangeLongPressMenu(o, listOf("O", "o"), fingerCell = 9)
        assertEquals("O", arranged[9])
        assertEquals("o", arranged[8])
        assertEquals(listOf("ō", "ó", "ǒ", "ò", "ô", "õ", "ö", "ø"), arranged.take(8))
    }

    @Test fun `left edge keeps the accents in reading order after the pair`() {
        val a = listOf("a", "A", "ā", "á", "ǎ", "à")
        assertEquals(listOf("A", "a", "ā", "á", "ǎ", "à"), arrangeLongPressMenu(a, listOf("A", "a"), fingerCell = 0))
    }

    @Test fun `two item menus put the default choice first`() {
        // 两项菜单从第一项起选：大写在前就是松手出大写，往右滑选小写。
        assertEquals(listOf("K", "k"), arrangeLongPressMenu(listOf("k", "K"), listOf("K", "k"), fingerCell = 0))
        assertEquals(listOf("k", "K"), arrangeLongPressMenu(listOf("k", "K"), listOf("k", "K"), fingerCell = 0))
    }

    @Test fun `nothing to arrange leaves the menu as it is`() {
        assertEquals(o, arrangeLongPressMenu(o, emptyList(), fingerCell = 3))
        assertEquals(o, arrangeLongPressMenu(o, listOf("X"), fingerCell = 3))
        assertEquals(listOf("a", "a"), arrangeLongPressMenu(listOf("a", "a"), listOf("a"), fingerCell = 1))
    }

    @Test fun `center items follow what a tap does not give`() {
        assertEquals(listOf("O", "o"), letterMenuCenterItems("o", o, isShifted = false, accentLetters = ""))
        // Shift 按着时点按已是大写，长按松手给小写。
        assertEquals(listOf("o", "O"), letterMenuCenterItems("o", o, isShifted = true, accentLetters = ""))
        assertEquals(listOf("K", "k"), letterMenuCenterItems("k", listOf("k", "K"), isShifted = false, accentLetters = ""))
    }

    @Test fun `fourteen key merged keys put both capitals first`() {
        // 11-24 长按方案：14 键 qw 松手不挪是 Q，挨着 W，再是 q w。
        assertEquals(listOf("Q", "W", "q", "w"), letterMenuCenterItems("qw", listOf("q", "w", "Q", "W"), false, ""))
        assertEquals(listOf("q", "w", "Q", "W"), letterMenuCenterItems("qw", listOf("q", "w", "Q", "W"), true, ""))
    }

    @Test fun `letter language keyboards and other keys are left alone`() {
        // 西班牙、德、俄语等字母语言键盘长按要的是 ñ、ä 这类，不动。
        assertEquals(emptyList<String>(), letterMenuCenterItems("n", listOf("n", "N", "ñ"), false, accentLetters = "ñ"))
        // 标点键、没有这对大小写的菜单都不动。
        assertEquals(emptyList<String>(), letterMenuCenterItems("qw", listOf("q", "w"), false, ""))
        assertEquals(emptyList<String>(), letterMenuCenterItems("'", listOf(",", "."), false, ""))
        assertEquals(emptyList<String>(), letterMenuCenterItems("o", listOf("ō", "ó"), false, ""))
        assertEquals(emptyList<String>(), letterMenuCenterItems("o", null, false, ""))
    }

    @Test fun `shift held and lower case picked from the bubble still types lower case`() {
        val single = com.kingzcheung.xime.viewmodel.ShiftMode.SINGLE
        val caps = com.kingzcheung.xime.viewmodel.ShiftMode.CAPS
        val off = com.kingzcheung.xime.viewmodel.ShiftMode.OFF
        // 平板 11-23：按着 Shift 长按 K，气泡选中 k，松手上屏的却是 K。
        assertEquals("k" to false, shiftedKeyPress("k", single, explicitLowercase = true))
        assertEquals("k" to false, shiftedKeyPress("k", caps, explicitLowercase = true))
        // 平常点按照旧由 Shift 决定；没开 Shift 时不变。
        assertEquals("K" to true, shiftedKeyPress("k", single, explicitLowercase = false))
        assertEquals("k" to false, shiftedKeyPress("k", off, explicitLowercase = true))
        // 选的是大写、或不是字母的照旧。
        assertEquals("K" to true, shiftedKeyPress("K", single, explicitLowercase = false))
        assertEquals("1" to true, shiftedKeyPress("1", single, explicitLowercase = true))
    }

    @Test fun `explicit lower case is only peeked for the same letter within a second`() {
        val lower = com.kingzcheung.xime.service.ExplicitLowercase
        lower.mark("k", now = 1_000)
        assertEquals(true, lower.isPending("k", now = 1_500))
        assertEquals(false, lower.isPending("j", now = 1_500))
        assertEquals(false, lower.isPending("k", now = 2_500))
        // 只看不取：按键线程仍能取走它，挡住句首自动大写。
        assertEquals(true, lower.consume("k", now = 1_600))
        assertEquals(false, lower.isPending("k", now = 1_700))
    }
}
