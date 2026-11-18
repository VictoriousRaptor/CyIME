package com.kingzcheung.xime.ui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 长按出七八个变音字母时，每格按画出来的格宽选，起点是手指下那格（用户 11-15：挪一点就到头了）。 */
class LongPressMenuCellsTest {
    // 手机竖屏 26 键：一行十个键，每键 100 px，键盘 1000 px，选项条离边 10 px。
    private val key = 100f
    private val keyboard = 1000f
    private val margin = 10f

    private fun cells(keyLeft: Float, count: Int, icons: Boolean = false, width: Float? = keyboard) =
        KeyboardKeyMetrics.longPressMenuCells(keyLeft, key, count, icons, width, margin)

    @Test
    fun eightAccentsOnUStepOneKeyWidePerItem() {
        // 拼音 26 键的 u（第 7 个键）长按：u U ū ú ǔ ù û ü 八项。
        val u = cells(keyLeft = 600f, count = 8)
        // 整条八个键宽，碰右边往里挪。
        assertEquals(800f, u.width, 0.01f)
        assertEquals(keyboard - 800f - margin, u.left, 0.01f)
        assertEquals(key, u.cellWidth, 0.01f)
        // 长按到点时手指在键中间：选的是它正上方那格，不是第一项。
        val start = KeyboardKeyMetrics.longPressMenuIndex(650f, u, 8)
        assertEquals(4, start)
        // 往右挪三分之一个键还在原来那格；以前八项挤在一个键宽里，这一下就跳两三格。
        assertEquals(start, KeyboardKeyMetrics.longPressMenuIndex(683f, u, 8))
        assertTrue(longPressIndexByKeyWidth(33f, key, 8) >= 2)
        // 挪一个键宽正好换一格。
        assertEquals(start + 1, KeyboardKeyMetrics.longPressMenuIndex(750f, u, 8))
        assertEquals(start - 1, KeyboardKeyMetrics.longPressMenuIndex(550f, u, 8))
        // 往左挪四个键才到第一项。
        assertEquals(0, KeyboardKeyMetrics.longPressMenuIndex(650f - 4 * key, u, 8))
        assertEquals(1, KeyboardKeyMetrics.longPressMenuIndex(650f - 3 * key, u, 8))
    }

    @Test
    fun everyDrawnCellIsChosenUnderItsCentre() {
        for (count in 3..11) for (keyIndex in 0..9) {
            val c = cells(keyLeft = keyIndex * key, count = count)
            for (i in 0 until count) {
                val centre = c.left + (i + 0.5f) * c.cellWidth
                assertEquals("count=$count key=$keyIndex item=$i", i, KeyboardKeyMetrics.longPressMenuIndex(centre, c, count))
            }
            // 条一直在键盘里。
            assertTrue(c.left >= margin - 0.01f && c.left + c.width <= keyboard - margin + 0.01f)
        }
    }

    @Test
    fun widestMenuShrinksToKeyboardButNotToOneKey() {
        // 拼音 a 有十一项，比一行还宽：压到键盘宽，每格仍接近一个键宽。
        val a = cells(keyLeft = 50f, count = 11)
        assertEquals(keyboard - 2 * margin, a.width, 0.01f)
        assertTrue(a.cellWidth > key * 0.85f)
        assertEquals(margin, a.left, 0.01f)
    }

    @Test
    fun fingerPastTheEndsStaysOnTheLastItem() {
        val u = cells(keyLeft = 600f, count = 8)
        assertEquals(0, KeyboardKeyMetrics.longPressMenuIndex(-500f, u, 8))
        assertEquals(7, KeyboardKeyMetrics.longPressMenuIndex(5000f, u, 8))
    }

    @Test
    fun withoutKeyboardWidthTheMenuIsCentredOnTheKey() {
        val c = cells(keyLeft = 0f, count = 3, width = null)
        assertEquals(-100f, c.left, 0.01f)
        assertEquals(1, KeyboardKeyMetrics.longPressMenuIndex(50f, c, 3))
    }

    @Test
    fun twoCornerSymbolsKeepTheOldFeel() {
        // 上下角标两项：仍从第一项起，往右过四分之一键换第二项，往左不动。
        assertEquals(0, longPressIndexByKeyWidth(0f, key, 2))
        assertEquals(0, longPressIndexByKeyWidth(24f, key, 2))
        assertEquals(1, longPressIndexByKeyWidth(26f, key, 2))
        assertEquals(1, longPressIndexByKeyWidth(400f, key, 2))
        assertEquals(0, longPressIndexByKeyWidth(-100f, key, 2))
        // 单项（九键数字）只有一项。
        assertEquals(0, longPressIndexByKeyWidth(300f, key, 1))
    }

    @Test
    fun iconMenusUseOneCellPerIcon() {
        val c = cells(keyLeft = 400f, count = 3, icons = true)
        assertEquals(300f, c.width, 0.01f)
        assertEquals(300f, c.left, 0.01f)
    }
}
