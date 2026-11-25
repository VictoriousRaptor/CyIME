package com.kingzcheung.xime.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 红米 Android 12（11-23 录屏）：点「键盘调节」后应用收到「键盘高度 0」，把输入法收起了。
 * 调节期间报给应用的顶线要停在进调节前的位置。
 */
class ResizeInsetsFreezeTest {
    private val screenBottom = 2400

    @Test fun fixedKeyboardKeepsItsTopWhileResizing() {
        val freeze = ResizeInsetsFreeze()
        freeze.noteReported(1500, 1500)
        // 不再报成屏幕底（应用会当成键盘收起了）。
        assertEquals(1500 to 1500, freeze.duringResize(screenBottom))
    }

    @Test fun topStaysPutForTheWholeSession() {
        val freeze = ResizeInsetsFreeze()
        freeze.noteReported(1500, 1480)
        assertEquals(1500 to 1480, freeze.duringResize(screenBottom))
        // 调节里拖高拖低、切悬浮，系统每帧都重算：报的值一直不变。
        assertEquals(1500 to 1480, freeze.duringResize(screenBottom))
        assertEquals(1500 to 1480, freeze.duringResize(1000))
    }

    @Test fun floatingKeyboardStillReportsNothing() {
        val freeze = ResizeInsetsFreeze()
        freeze.noteReported(screenBottom, screenBottom)
        assertEquals(screenBottom to screenBottom, freeze.duringResize(screenBottom))
    }

    @Test fun nextSessionUsesTheNewTop() {
        val freeze = ResizeInsetsFreeze()
        freeze.noteReported(1500, 1500)
        freeze.duringResize(screenBottom)
        // 确认后键盘变高了，照常报新的顶线；下一次调节从新顶线开始。
        freeze.noteReported(1300, 1300)
        assertEquals(1300 to 1300, freeze.duringResize(screenBottom))
    }

    @Test fun nothingReportedYetFallsBackToOldBehaviour() {
        assertEquals(screenBottom to screenBottom, ResizeInsetsFreeze().duringResize(screenBottom))
    }

    @Test fun hidingTheKeyboardForgetsTheLastTop() {
        val freeze = ResizeInsetsFreeze()
        freeze.noteReported(1500, 1500)
        freeze.forget()
        assertEquals(screenBottom to screenBottom, freeze.duringResize(screenBottom))
    }
}
