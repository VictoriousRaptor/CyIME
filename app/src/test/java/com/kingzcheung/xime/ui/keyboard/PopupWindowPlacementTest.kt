package com.kingzcheung.xime.ui.keyboard

import androidx.compose.ui.unit.IntOffset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 长按、滑动气泡和方向盘浮窗：系统摆的位置和键盘窗口坐标对不上时补回去（用户 11-12：14 键长按 QW，浮标飞到最下面）。 */
class PopupWindowPlacementTest {
    @After fun tearDown() = PopupWindowCorrection.reset()

    @Test fun `摆对了不补`() {
        assertNull(popupWindowCorrection(IntOffset(100, 1368), IntOffset(100, 1368), IntOffset.Zero))
        assertNull(popupWindowCorrection(IntOffset(100, 1368), IntOffset(101, 1367), IntOffset.Zero))
    }

    @Test fun `系统把浮窗摆低了一截就往上补同样多`() {
        // 截图里的情形：横向对，纵向低了约一个键盘高。
        val fix = popupWindowCorrection(IntOffset(100, 1368), IntOffset(100, 2052), IntOffset.Zero)
        assertEquals(IntOffset(0, -684), fix)
    }

    @Test fun `已经补过还差一点就在原来的差值上再补`() {
        val fix = popupWindowCorrection(IntOffset(100, 1368), IntOffset(100, 1378), IntOffset(0, -684))
        assertEquals(IntOffset(0, -694), fix)
    }

    @Test fun `差值只用在量出它的那个键盘窗口`() {
        val fixed = IntOffset(0, 1372)
        PopupWindowCorrection.set(fixed, IntOffset(0, -684))
        assertEquals(IntOffset(0, -684), PopupWindowCorrection.offsetFor(fixed))
        // 窗口位置变了（悬浮、换高度）：旧差值不能照搬，重新量。
        assertEquals(IntOffset.Zero, PopupWindowCorrection.offsetFor(IntOffset(0, 900)))
        PopupWindowCorrection.set(fixed, IntOffset.Zero)
        assertEquals(IntOffset.Zero, PopupWindowCorrection.offsetFor(fixed))
    }
}
