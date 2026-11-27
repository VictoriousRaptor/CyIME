package com.kingzcheung.xime.keyboard

import org.junit.Assert.*
import org.junit.Test

class KeyboardModeNavigationTest {
    @Test fun mergeUsesOneTextKeyAndPreservesBothSecondaryTargets() {
        assertEquals(listOf(1), modeSlotOrder(swapped = false, cycleEnabled = true))
        assertEquals(listOf(1), modeSlotOrder(swapped = true, cycleEnabled = true))
        for (label in listOf("中文", "ABC", "あいう", "한국어")) {
            assertEquals(KeyboardModeTarget("?123", "symbol"), modeSlotTarget(KeyboardInputPage.TEXT, 1, label, true))
            for (page in listOf(KeyboardInputPage.SYMBOLS, KeyboardInputPage.NUMBERS)) for (slot in 1..2) {
                assertEquals(modeSlotTarget(page, slot, label), modeSlotTarget(page, slot, label, true))
            }
        }
    }

    @Test fun turningMergeOffRestoresBothIndependentTargetsOnEveryPage() {
        assertEquals(listOf(1, 2), modeSlotOrder(swapped = false))
        for (page in KeyboardInputPage.entries) for (slot in 1..2) {
            assertEquals(modeSlotTarget(page, slot, "中文"), modeSlotTarget(page, slot, "中文", false))
        }
    }

    @Test fun allSixTransitionsUseTheTwoFixedSlots() {
        for (label in listOf("中文", "ABC", "あいう")) {
            assertEquals(listOf(KeyboardModeTarget("!@#", "symbol"), KeyboardModeTarget("123", "number")),
                (1..2).map { modeSlotTarget(KeyboardInputPage.TEXT, it, label) })
            assertEquals(listOf(KeyboardModeTarget(label, "abc"), KeyboardModeTarget("123", "number")),
                (1..2).map { modeSlotTarget(KeyboardInputPage.SYMBOLS, it, label) })
            assertEquals(listOf(KeyboardModeTarget("!@#", "symbol"), KeyboardModeTarget(label, "abc")),
                (1..2).map { modeSlotTarget(KeyboardInputPage.NUMBERS, it, label) })
        }
    }

    @Test fun commonPageUsesTheOriginalLanguageAndHasNoDuplicateKeys() {
        for (label in listOf("中文", "ABC", "あいう")) {
            val symbols = commonSymbolsFor(label)
            assertEquals(32, symbols.size)
            assertEquals(symbols.size, symbols.distinct().size)
        }
        assertTrue(commonSymbolsFor("ABC").take(8).containsAll(listOf("?", "!", ",", ".")))
        assertTrue(commonSymbolsFor("中文").take(8).containsAll(listOf("？", "！", "，", "。")))
        assertTrue(commonSymbolsFor("あいう").take(8).containsAll(listOf("、", "。", "ー", "・")))
    }

    @Test fun nestedSecondaryPagesNeverBecomeTheReturnDestination() {
        for (main in MainType.entries) {
            val text = KeyboardPage.Main(main)
            val number = KeyboardPage.Panel(PanelType.NUMBER, main)
            val symbol = KeyboardPage.Overlay(OverlayRoute.Symbol, emptyList(), number)
            val tool = KeyboardPage.Overlay(OverlayRoute.Menu, listOf(OverlayRoute.Symbol), symbol)
            for (page in listOf(text, number, symbol, tool)) assertEquals(main, page.textMainType())
        }
    }

    @Test fun toolsKeepTheExactUnderlyingInputSurfaceIncludingNumberAndHandwriting() {
        val surfaces = MainType.entries.map { KeyboardPage.Main(it) } +
            PanelType.entries.map { KeyboardPage.Panel(it, MainType.HANDWRITING) }
        val routes = listOf(OverlayRoute.Menu, OverlayRoute.SchemaList, OverlayRoute.Clipboard(),
            OverlayRoute.Emoji, OverlayRoute.Symbol, OverlayRoute.Edit, OverlayRoute.SplitWords("文字"),
            OverlayRoute.ToolbarCustomize, OverlayRoute.ToolPanel)
        for (surface in surfaces) {
            assertSame(surface, surface.underlyingPage())
            for (route in routes) {
                val overlay = KeyboardPage.Overlay(route, emptyList(), surface)
                val nested = KeyboardPage.Overlay(OverlayRoute.Menu, listOf(route), overlay)
                assertSame(surface, overlay.underlyingPage())
                assertSame(surface, nested.underlyingPage())
            }
        }
    }

    /** #499：对调后主键盘 123 在左；进了数字页，左边那个键就是返回，和点进去的是同一个位置。默认不变。 */
    @Test fun swappedModeKeysKeepTheReturnKeyWhereItWasPressed() {
        org.junit.Assert.assertEquals(listOf(1, 2), modeSlotOrder(false))
        org.junit.Assert.assertFalse(com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences().swapModeKeys)
        val order = modeSlotOrder(true)
        org.junit.Assert.assertEquals(listOf("123", "!@#"), order.map { modeSlotTarget(KeyboardInputPage.TEXT, it, "中").label })
        org.junit.Assert.assertEquals("abc", modeSlotTarget(KeyboardInputPage.NUMBERS, order.first(), "中").action)
        org.junit.Assert.assertEquals("abc", modeSlotTarget(KeyboardInputPage.SYMBOLS, order.last(), "中").action)
    }
}
