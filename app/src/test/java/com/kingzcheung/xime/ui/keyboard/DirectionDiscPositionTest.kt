package com.kingzcheung.xime.ui.keyboard

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #60 第 7 点：九键 PQRS、WXYZ 拂动时方向盘最下面的 R、Y 画到屏幕外。方向盘要整个在窗口里。 */
class DirectionDiscPositionTest {
    // 1080 宽的手机，键盘窗口 900 高；九键一行约 160 高，方向盘 112dp × 2.75 ≈ 308。
    private val window = IntSize(1080, 900)
    private val disc = IntSize(308, 308)
    private val margin = 16

    private fun assertInside(anchor: IntRect) {
        val at = directionDiscPosition(anchor, window, disc, margin)
        assertTrue("left $at", at.x >= margin)
        assertTrue("right $at", at.x + disc.width <= window.width - margin)
        assertTrue("top $at", at.y >= margin)
        assertTrue("bottom $at", at.y + disc.height <= window.height - margin)
    }

    @Test fun thirdRowKeysShowTheWholeDiscAboveTheKey() {
        val pqrs = IntRect(0, 540, 270, 700)
        val wxyz = IntRect(540, 540, 810, 700)
        for (key in listOf(pqrs, wxyz)) {
            assertInside(key)
            // 整个方向盘在键的上面，最下面一格不会压到键下面那一排。
            val at = directionDiscPosition(key, window, disc, margin)
            assertEquals(key.top - margin, at.y + disc.height)
        }
    }

    @Test fun edgeAndTopRowKeysStayInsideTheWindow() {
        assertInside(IntRect(0, 220, 120, 380))
        assertInside(IntRect(960, 220, 1080, 380))
        // 第一排上面放不下：贴顶边，不出窗口。
        assertInside(IntRect(270, 60, 540, 220))
    }
}
