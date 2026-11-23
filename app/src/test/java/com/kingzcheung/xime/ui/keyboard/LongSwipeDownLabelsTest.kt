package com.kingzcheung.xime.ui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 导入的乱序十七把下滑标签写成韵母助记：超过 4 个字符的改写在键底部一行，整行不截断（用户群 11-22）。 */
class LongSwipeDownLabelsTest {
    @Test fun `超过 4 个字符才算长标签`() {
        assertFalse(isLongSwipeDownLabel(null))
        assertFalse(isLongSwipeDownLabel("+"))
        assertFalse(isLongSwipeDownLabel("a ia"))
        assertFalse(isLongSwipeDownLabel(" ai "))
        assertTrue(isLongSwipeDownLabel("a ia ua"))
        assertTrue(isLongSwipeDownLabel("ang iao"))
    }

    @Test fun `助记整行写出，不截成 4 个字符`() {
        // 手机竖屏 17 键：键宽约 57dp、高约 50dp，角标 10sp。
        val line = fitSwipeDownLine("ian uang", 57f - 6f, 50f * 0.42f, 10f, 1f)!!
        assertEquals("ian uang", line.lines.joinToString(" "))
        assertTrue(line.fontSp >= 6f)
        val one = fitSwipeDownLine("a ia ua", 57f - 6f, 50f * 0.42f, 10f, 1f)!!
        assertEquals(listOf("a ia ua"), one.lines)
        assertEquals(10f, one.fontSp, 0.01f)
    }

    @Test fun `窄键先缩字号，再在空格处分两行`() {
        val narrow = fitSwipeDownLine("iang ui", 30f, 30f, 10f, 1f)!!
        assertEquals("iang ui", narrow.lines.joinToString(" "))
        assertTrue(narrow.lines.size == 2 || narrow.fontSp < 10f)
    }
}
