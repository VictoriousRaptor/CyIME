package com.kingzcheung.xime.ui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 11-24 长按方案：默认项正对按住的键，格子贴着字宽，其余项往键盘中间那侧排，手指挪一格换一格，
 * 每个键的每一项手指都挪得到（他：右边缘 P 选不到另一个、长按 H 气泡偏到别的键上、O 旁边是 ö）。
 */
class LongPressTextMenuTest {
    private val dp = 2.625f // 手机 420dpi
    private val margin = 4f * dp

    private data class Layout(val items: List<String>, val anchor: Int, val cells: LongPressMenuCells)

    private fun layout(keyLeft: Float, keyWidth: Float, keyboard: Float, items: List<String>,
                       near: List<String>, fixed: Boolean = false): Layout {
        val (ordered, anchor) = KeyboardKeyMetrics.longPressMenuOrder(items, near, fixed, keyLeft + keyWidth / 2f, keyboard)
        return Layout(ordered, anchor, KeyboardKeyMetrics.longPressTextMenuCells(keyLeft, keyWidth, ordered, anchor, keyboard, margin, dp))
    }

    /** 手指从键中间出发、只在键盘里横着挪，能不能选到第 [i] 项。 */
    private fun reachable(l: Layout, keyCenter: Float, keyboard: Float, i: Int): Boolean =
        (0..keyboard.toInt()).any { x ->
            KeyboardKeyMetrics.longPressMenuStep(l.anchor, x - keyCenter, l.cells.cellWidth, l.items.size) == i
        }

    @Test fun `right half keys open toward the middle with the default under the finger`() {
        val kb = 1080f; val key = 108f
        // P 在最右：p 排在 P 左边，P 在手指上。
        val p = layout(9 * key, key, kb, listOf("p", "P"), listOf("P", "p"))
        assertEquals(listOf("p", "P"), p.items)
        assertEquals(1, p.anchor)
        // Q 在最左：q 排在 Q 右边。
        val q = layout(0f, key, kb, listOf("q", "Q"), listOf("Q", "q"))
        assertEquals(listOf("Q", "q"), q.items)
        assertEquals(0, q.anchor)
    }

    @Test fun `o keeps tones next to the pair, nearest first`() {
        val kb = 1080f; val key = 108f
        val o = layout(8 * key, key, kb, listOf("o", "O", "ō", "ó", "ǒ", "ò"), listOf("O", "o"))
        // O 在右半边：从手指往左依次 O o ō ó ǒ ò。
        assertEquals(listOf("ò", "ǒ", "ó", "ō", "o", "O"), o.items)
        assertEquals(5, o.anchor)
    }

    @Test fun `two item bubble is two cells wide and its default sits over the key`() {
        val kb = 1080f; val key = 108f
        val h = layout(key / 2 + 5 * key, key, kb, listOf("h", "H"), listOf("H", "h"))
        val keyCenter = key / 2 + 5.5f * key
        // 不再撑成三个键宽：两格、每格贴字宽（约 34dp）。
        assertEquals(2 * 34f * dp, h.cells.width, 0.5f)
        // 默认项 H 那一格的中心就是按键中心。
        assertEquals(keyCenter, h.cells.left + (h.anchor + 0.5f) * h.cells.cellWidth, 0.5f)
    }

    @Test fun `t9 row keeps reference order with the digit in the middle`() {
        val kb = 1080f; val col = kb / 5
        val five = layout(2 * col, col, kb, listOf("J", "K", "L", "5", "j", "k", "l"), listOf("5"), fixed = true)
        assertEquals(listOf("J", "K", "L", "5", "j", "k", "l"), five.items)
        assertEquals(3, five.anchor)
        // 右列 9 也不翻转。
        val nine = layout(3 * col, col, kb, listOf("W", "X", "Y", "Z", "9", "w", "x", "y", "z"), listOf("9"), fixed = true)
        assertEquals(4, nine.anchor)
        assertEquals("W", nine.items.first())
    }

    @Test fun `moving one cell changes one item and the drawn cell is the chosen one`() {
        val kb = 1080f; val key = 108f
        val a = layout(key / 2, key, kb, listOf("a", "A", "ā", "á", "ǎ", "à"), listOf("A", "a"))
        val center = key
        for (i in a.items.indices) {
            val drawnCenter = a.cells.left + (i + 0.5f) * a.cells.cellWidth
            assertEquals("item $i", i, KeyboardKeyMetrics.longPressMenuStep(a.anchor, drawnCenter - center, a.cells.cellWidth, a.items.size))
        }
    }

    @Test fun `every item of every key is reachable on phone and tablet`() {
        // 26 键一行十个、九键五列、14 键五列；手机 1080 px、平板 2560 px 宽。
        for (kb in listOf(1080f, 2560f)) {
            val key = kb / 10
            val rows = listOf("qwertyuiop", "asdfghjkl")
            rows.forEachIndexed { r, letters ->
                letters.forEachIndexed { c, ch ->
                    val left = r * key / 2 + c * key
                    val items = listOf(ch.toString(), ch.uppercase(), "1", "2", "3", "4")
                    val l = layout(left, key, kb, items, listOf(ch.uppercase(), ch.toString()))
                    // 菜单在键盘里。
                    assertTrue(l.cells.left >= margin - 0.5f && l.cells.left + l.cells.width <= kb - margin + 0.5f)
                    for (i in l.items.indices) assertTrue("kb=$kb $ch item $i", reachable(l, left + key / 2, kb, i))
                }
            }
            val col = kb / 5
            for (c in 1..3) {
                val items = listOf("P", "Q", "R", "S", "7", "p", "q", "r", "s")
                val l = layout(c * col, col, kb, items, listOf("7"), fixed = true)
                for (i in items.indices) assertTrue("t9 kb=$kb col=$c item $i", reachable(l, c * col + col / 2, kb, i))
            }
            for (c in 0..4) {
                val l = layout(c * col, col, kb, listOf("o", "p", "O", "P"), listOf("O", "P", "o", "p"))
                for (i in l.items.indices) assertTrue("14 kb=$kb col=$c item $i", reachable(l, c * col + col / 2, kb, i))
            }
        }
    }

    @Test fun `too many items shrink the cells to fit the keyboard`() {
        val kb = 400f
        val l = layout(0f, 40f, kb, (1..20).map { it.toString() }, listOf("1"))
        assertTrue(l.cells.left >= margin - 0.5f)
        assertTrue(l.cells.left + l.cells.width <= kb - margin + 0.5f)
    }
}
