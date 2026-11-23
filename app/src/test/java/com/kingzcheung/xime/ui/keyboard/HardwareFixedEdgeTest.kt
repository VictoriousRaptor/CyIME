package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.service.HardwareCursorAnchor
import com.kingzcheung.xime.settings.HardwareKeyboardOptions
import com.kingzcheung.xime.settings.HardwareToolbarEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 实体键盘浮窗固定在上下左右某一边（用户 11-20 转来的反馈：浮窗乱飘、挡输入框）。 */
class HardwareFixedEdgeTest {
    @Test fun defaultStaysFreeDragging() {
        assertNull(HardwareKeyboardOptions().fixedEdge)
        assertNull(HardwareToolbarEdge.fromKey(null))
        assertNull(HardwareToolbarEdge.fromKey("free"))
        HardwareToolbarEdge.entries.forEach { assertEquals(it, HardwareToolbarEdge.fromKey(it.key)) }
    }

    @Test fun edgesMapOntoTheExistingDockRules() {
        // 底部 = 原来的贴底；左右 = 原来靠边的竖条；顶部是横条，不算贴底。
        assertTrue(HardwareToolbarEdge.BOTTOM.toolbarPosition().isBottomDocked(true))
        assertFalse(HardwareToolbarEdge.TOP.toolbarPosition().isBottomDocked(true))
        assertTrue(hardwareToolbarIsVertical(800f, 1200f, HardwareToolbarEdge.LEFT.toolbarPosition(), true))
        assertTrue(hardwareToolbarIsVertical(800f, 1200f, HardwareToolbarEdge.RIGHT.toolbarPosition(), true))
        assertFalse(hardwareToolbarIsVertical(800f, 1200f, HardwareToolbarEdge.TOP.toolbarPosition(), true))
        assertTrue(hardwareToolbarCanShowCandidates(800f, 1200f, HardwareToolbarEdge.BOTTOM.toolbarPosition(), true))
    }

    private val view = 1000 to 2000
    private fun place(edge: HardwareToolbarEdge, bar: HardwareCandidateExclusion, w: Int = 300, h: Int = 100) =
        fixedHardwareCandidatePosition(edge, bar, w, h, view.first, view.second, 16)

    /** 不知道光标在哪（或关了跟随）时才用这条：卡片贴着工具条。 */
    @Test fun cardSitsBesideTheToolbarWithoutACaret() {
        assertEquals(HardwareCandidatePosition(300, 136), place(HardwareToolbarEdge.TOP, HardwareCandidateExclusion(300, 16, 800, 120)))
        assertEquals(HardwareCandidatePosition(300, 1764), place(HardwareToolbarEdge.BOTTOM, HardwareCandidateExclusion(300, 1880, 800, 1984)))
        assertEquals(HardwareCandidatePosition(136, 700), place(HardwareToolbarEdge.LEFT, HardwareCandidateExclusion(16, 700, 120, 1300)))
        assertEquals(HardwareCandidatePosition(564, 700), place(HardwareToolbarEdge.RIGHT, HardwareCandidateExclusion(880, 700, 984, 1300)))
    }

    @Test fun cardStaysOnScreenWhenTheSideIsTooNarrow() {
        val p = place(HardwareToolbarEdge.LEFT, HardwareCandidateExclusion(16, 700, 900, 1300))
        assertEquals(1000 - 300 - 16, p.x)
        val q = place(HardwareToolbarEdge.RIGHT, HardwareCandidateExclusion(100, 700, 200, 1300))
        assertEquals(16, q.x)
    }

    /**
     * 用户 11-22：固定在右侧时候选浮在屏幕中间，输入框在底下。知道光标时只固定工具条，卡片照跟随光标的规则
     * 放到正在打字那一行的上面（下面放不下），不压这一行，也不压右边的竖工具条。
     */
    @Test fun knownCaretInsideTheHostIsFollowedEvenWhenFixed() {
        val caret = HardwareCursorAnchor(60f, 1880f, 62f, 1930f)
        assertTrue(hardwareAnchorInHost(caret, 0, 0, view.first, view.second))
        val toolbar = HardwareCandidateExclusion(920, 700, 984, 1300)
        val p = hardwareCandidatePosition(view.first, view.second, 600, 120, caret, 0, 0, 16, 8, avoidBounds = toolbar)
        assertEquals(60, p.x)
        assertEquals(1880 - 8 - 120, p.y)
        assertFalse(HardwareCandidateExclusion(p.x, p.y, p.x + 600, p.y + 120).overlaps(HardwareCandidateExclusion(0, 1880, 1000, 1930)))
        assertFalse(HardwareCandidateExclusion(p.x, p.y, p.x + 600, p.y + 120).overlaps(toolbar))
    }

    @Test fun missingOrOffscreenCaretFallsBackToTheToolbar() {
        assertFalse(hardwareAnchorInHost(null, 0, 0, view.first, view.second))
        assertFalse(hardwareAnchorInHost(HardwareCursorAnchor(Float.NaN, 0f, 1f, 10f), 0, 0, view.first, view.second))
        assertFalse(hardwareAnchorInHost(HardwareCursorAnchor(10f, 2100f, 12f, 2150f), 0, 0, view.first, view.second))
        // 分屏时宿主不在屏幕原点：按宿主内坐标判断。
        assertTrue(hardwareAnchorInHost(HardwareCursorAnchor(1010f, 50f, 1012f, 90f), 1000, 0, view.first, view.second))
        assertFalse(hardwareAnchorInHost(HardwareCursorAnchor(10f, 50f, 12f, 90f), 1000, 0, view.first, view.second))
    }
}
