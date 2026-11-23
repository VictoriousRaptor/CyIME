package com.kingzcheung.xime.ui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SideSymbolTargetTest {
    @Test fun eachBarFallsBackToTheOldSharedListUntilEditedAlone() {
        val shared = KeyboardInputPreferences(fixedSymbols = "，\n。\n……")
        assertEquals(listOf("，", "。", "……"), shared.symbolsFor(SideSymbolTarget.T9))
        val own = shared.copy(sideSymbols = mapOf("t9" to "@\n#\n\n"))
        assertEquals(listOf("@", "#"), own.symbolsFor(SideSymbolTarget.T9))
        // 其他栏不受九键单独设置影响。
        assertEquals(listOf("，", "。", "……"), own.symbolsFor(SideSymbolTarget.STROKE))
        assertNull(KeyboardInputPreferences().symbolsFor(SideSymbolTarget.COMMON))
    }

    @Test fun cellsKeepTheirSpaces() {
        // 用户 11-22：九键侧栏填「, 」，后面的空格要原样存、原样上屏。
        assertEquals(listOf(", ", " 。", "a b"), SideSymbolTarget.parse(", \n 。\na b\n"))
        assertEquals(listOf(" "), SideSymbolTarget.parse(" "))
        assertEquals(listOf(", "), SideSymbolTarget.parse(", \r\n"))
        assertNull(SideSymbolTarget.parse("\n\n"))
        assertEquals(", ", SideSymbolTarget.cellText(", "))
        assertEquals("ab ", SideSymbolTarget.cellText("a\nb\r "))
        val own = KeyboardInputPreferences(sideSymbols = mapOf("t9" to "，\n, \n。"))
        assertEquals(listOf("，", ", ", "。"), own.symbolsFor(SideSymbolTarget.T9))
    }

    @Test fun fixedSlotBarsAlwaysShowTheirSlotCount() {
        assertEquals(listOf("！", "，", "。"), SideSymbolTarget.HANDWRITING.resolve(listOf("！")))
        assertEquals(listOf("a", "b", "c"), SideSymbolTarget.HANDWRITING.resolve(listOf("a", "b", "c", "d")))
        assertEquals(listOf("；", "：", "！", "？", "，", "。"), SideSymbolTarget.HANDWRITING_ROW.resolve(null))
    }

    @Test fun numberRailKeepsItsOperatorsUntilEditedAlone() {
        // 旧的共用列表不顶掉数字键盘左栏：没单独改过就是原来的 + - * /。
        val shared = KeyboardInputPreferences(fixedSymbols = "，\n。\n……")
        assertNull(shared.symbolsFor(SideSymbolTarget.NUMBER))
        assertEquals(listOf("+", "-", "*", "/"), SideSymbolTarget.NUMBER.resolve(shared.symbolsFor(SideSymbolTarget.NUMBER)))
        // 改过就是自己的，不限格数（多出四格时键盘上下滑）。
        val own = shared.copy(sideSymbols = mapOf("number" to "+\n-\n*\n/\n:\n%"))
        assertEquals(listOf("+", "-", "*", "/", ":", "%"), SideSymbolTarget.NUMBER.resolve(own.symbolsFor(SideSymbolTarget.NUMBER)))
        assertEquals("side_symbols_number", SideSymbolTarget.NUMBER.prefKey)
        assertEquals(true, SideSymbolTarget.NUMBER.vertical)
    }
}
