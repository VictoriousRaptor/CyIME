package com.kingzcheung.xime.ui.settings

import com.kingzcheung.xime.ui.keyboard.HandwritingFrame
import com.kingzcheung.xime.ui.keyboard.NineGridFrame
import com.kingzcheung.xime.ui.keyboard.SideSymbolTarget
import org.junit.Assert.assertEquals
import org.junit.Test

class SideSymbolPagesTest {
    @Test fun handwritingBarsShareOnePageAndKeepTheirOwnKeys() {
        val pages = SideSymbolTarget.entries.filter { sideSymbolPage(it) == it }
        assertEquals(SideSymbolTarget.entries - SideSymbolTarget.HANDWRITING_ROW, pages)
        assertEquals(SideSymbolTarget.HANDWRITING, sideSymbolPage(SideSymbolTarget.HANDWRITING_ROW))
        assertEquals(listOf(SideSymbolTarget.HANDWRITING, SideSymbolTarget.HANDWRITING_ROW),
            sideSymbolPageTargets(SideSymbolTarget.HANDWRITING_ROW))
        assertEquals("手写", sideSymbolPageLabel(SideSymbolTarget.HANDWRITING))
        assertEquals("笔画侧栏", sideSymbolPageLabel(SideSymbolTarget.STROKE))
        // 设置键不变：两条栏各存各的。
        assertEquals("side_symbols_handwriting", SideSymbolTarget.HANDWRITING.prefKey)
        assertEquals("side_symbols_handwriting_row", SideSymbolTarget.HANDWRITING_ROW.prefKey)
    }

    @Test fun framesMatchTheKeyboards() {
        // 手写底排和 26 键底排同权重，展开键换成收起，位置不动。
        val normal = HandwritingFrame.bottomKeys(expanded = false)
        val expanded = HandwritingFrame.bottomKeys(expanded = true)
        assertEquals(listOf("symbol", "number", "expand", "space", "ime_switch", "enter"), normal.map { it.first })
        assertEquals(normal.map { it.second }, expanded.map { it.second })
        assertEquals(HandwritingFrame.bottom.total, normal.sumOf { it.second.toDouble() }.toFloat(), 1e-4f)
        // 九键、笔画最下一排正好是主键区宽。
        assertEquals(NineGridFrame.MAIN,
            NineGridFrame.BOTTOM_MODE + NineGridFrame.BOTTOM_SPACE + NineGridFrame.BOTTOM_LANGUAGE, 1e-4f)
    }
}
