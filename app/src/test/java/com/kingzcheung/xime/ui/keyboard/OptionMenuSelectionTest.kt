package com.kingzcheung.xime.ui.keyboard

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 工具栏长按「翻译」的列表：按住滑动时指着哪一行，和语言键的菜单同一套规矩。 */
class OptionMenuSelectionTest {
    private val rows = (0 until 4).map { Rect(100f, 200f + it * 40f, 260f, 240f + it * 40f) }

    @Test fun pointerOnARowPicksIt() {
        assertEquals(0, optionMenuSelection(rows, Offset(150f, 210f), null))
        assertEquals(2, optionMenuSelection(rows, Offset(150f, 285f), null))
        assertEquals(3, optionMenuSelection(rows, Offset(259f, 359f), 1))
    }

    @Test fun releasingBeforeEnteringTheListPicksNothing() {
        // 手指还在工具栏按钮上（列表上面）
        assertNull(optionMenuSelection(rows, Offset(150f, 150f), null))
        assertNull(optionMenuSelection(rows, Offset(50f, 250f), null))
    }

    @Test fun afterEnteringSlidingOutKeepsTheLastRow() {
        assertEquals(1, optionMenuSelection(rows, Offset(400f, 250f), 1))
        assertEquals(0, optionMenuSelection(rows, Offset(150f, 100f), 2))
        assertEquals(3, optionMenuSelection(rows, Offset(150f, 900f), 1))
    }

    @Test fun rowsNotLaidOutYetPickNothing() {
        assertNull(optionMenuSelection(emptyList(), Offset(150f, 210f), null))
    }
}
