package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.settings.LayoutKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「数字行」（默认关）：键和顺序、分体左右各半、注音不加、方形吸附按字母键算。 */
class NumberRowTest {
    @Test fun offByDefaultAndShowsOneToZero() {
        val prefs = KeyboardInputPreferences()
        assertFalse(prefs.numberRow)
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"), prefs.numberRowKeys())
    }

    @Test fun ownOrderWinsAndBlankCellsAreDropped() {
        val prefs = KeyboardInputPreferences(sideSymbols = mapOf("number_row" to "0\n1\n\n2\n3\n4\n5\n6\n7\n8\n9"))
        assertEquals(listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9"), prefs.numberRowKeys())
        // 也能放符号或短语，格数不限。
        val mixed = KeyboardInputPreferences(sideSymbols = mapOf("number_row" to "1\n2\n3\n@\n#"))
        assertEquals(listOf("1", "2", "3", "@", "#"), mixed.numberRowKeys())
    }

    @Test fun theOldSharedSymbolListNeverReplacesTheDigits() {
        // 旧的共用快捷符号列表只给九键、笔画等老栏兜底；数字行没单独改过就是 1-0。
        val prefs = KeyboardInputPreferences(fixedSymbols = "，\n。\n……")
        assertEquals(SideSymbolTarget.NUMBER_ROW_DEFAULT, prefs.numberRowKeys())
        assertEquals(listOf("，", "。", "……"), prefs.symbolsFor(SideSymbolTarget.T9))
    }

    @Test fun zhuyinKeepsItsOwnTopRow() {
        assertTrue(numberRowShown(setting = true, zhuyin = false))
        assertFalse(numberRowShown(setting = true, zhuyin = true))
        assertFalse(numberRowShown(setting = false, zhuyin = false))
    }

    @Test fun splitGivesEachHandHalfTheRowWhateverTheKeyCount() {
        assertEquals(-1, numberRowSplitAt(10, split = false))
        assertEquals(5, numberRowSplitAt(10, split = true))
        assertEquals(5, numberRowSplitAt(11, split = true))
        assertEquals(-1, numberRowSplitAt(1, split = true))
        assertEquals(List(10) { 1f }, numberRowSpans(10, -1))
        for (count in listOf(10, 11, 12, 7)) {
            val at = numberRowSplitAt(count, split = true)
            val spans = numberRowSpans(count, at)
            assertEquals(count, spans.size)
            // 左右两半一样宽：中缝落在正中，和字母行的中缝对齐。
            assertEquals(spans.take(at).sum(), spans.drop(at).sum(), 1e-5f)
        }
    }

    @Test fun squareSnapStillMeansSquareLetterKeys() {
        val plain = KeyboardSquareSnap.forLayout(LayoutKind.ALPHABETIC, false)!!
        val withRow = KeyboardSquareSnap.forLayout(LayoutKind.ALPHABETIC, false, numberRow = true)!!
        assertEquals(4f, plain.rows)
        assertEquals(4f + NUMBER_ROW_HEIGHT_WEIGHT, withRow.rows)
        val seed = ResizeRect(0f, 0f, 520f, 300f)
        val a = plain.squareTotalHeightPx(seed, 1f, 16f, false, 6f to 10f)!!
        val b = withRow.squareTotalHeightPx(seed, 1f, 16f, false, 6f to 10f)!!
        // 多一排数字，字母键要方就得更高。
        assertTrue(b > a)
    }

    @Test fun fullHeightNumberRowIsOffByDefaultAndMatchesLetterRows() {
        assertFalse(KeyboardInputPreferences().numberRowFullHeight)
        assertEquals(NUMBER_ROW_HEIGHT_WEIGHT, numberRowHeightWeight(false))
        assertEquals(1f, numberRowHeightWeight(true))
        val full = KeyboardSquareSnap.forLayout(LayoutKind.ALPHABETIC, false, numberRow = true, numberRowFullHeight = true)!!
        assertEquals(5f, full.rows)
        // 只开「一样高」没开数字行：什么都不变。
        assertEquals(4f, KeyboardSquareSnap.forLayout(LayoutKind.ALPHABETIC, false, numberRowFullHeight = true)!!.rows)
    }
}
