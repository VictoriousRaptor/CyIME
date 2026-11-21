package com.kingzcheung.xime.ui.keyboard

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 右上角锁定区：候选栏右端一格，冲出键盘顶边、右边也算；其他地方都不算。 */
class SpaceVoiceLockZoneTest {
    // 候选栏 1080 宽、120 高，从根坐标 (0, 600) 开始；一格 = 1080 * 0.16 = 172.8。
    private val bar = Rect(0f, 600f, 1080f, 720f)

    @Test fun `the right end of the toolbar locks`() {
        assertTrue(spaceVoiceLockZoneContains(bar, 150f, Offset(1000f, 660f)))
        assertTrue("left edge of the slot", spaceVoiceLockZoneContains(bar, 150f, Offset(908f, 700f)))
    }

    @Test fun `overshooting above or past the right edge still locks`() {
        assertTrue(spaceVoiceLockZoneContains(bar, 150f, Offset(1060f, 520f)))
        assertTrue(spaceVoiceLockZoneContains(bar, 150f, Offset(1120f, 650f)))
    }

    @Test fun `the rest of the toolbar and the keys below do not`() {
        assertFalse("middle of the toolbar", spaceVoiceLockZoneContains(bar, 150f, Offset(540f, 660f)))
        assertFalse("top-right key, below the toolbar", spaceVoiceLockZoneContains(bar, 150f, Offset(1000f, 760f)))
    }

    @Test fun `narrow bars keep a finger-sized slot`() {
        val narrow = Rect(0f, 0f, 300f, 80f)
        // 0.16 * 300 = 48 < 150：用最小宽度。
        assertTrue(spaceVoiceLockZoneContains(narrow, 150f, Offset(160f, 40f)))
        assertFalse(spaceVoiceLockZoneContains(narrow, 150f, Offset(140f, 40f)))
    }

    @Test fun `no bar measured yet means no lock`() {
        assertFalse(spaceVoiceLockZoneContains(Rect.Zero, 150f, Offset(0f, 0f)))
    }

    @Test fun `swipe-up cancel needs a full key height above the space and never fires in the corner`() {
        assertFalse("half a key up", spaceVoiceCancelArmed(lockArmed = false, yInKey = -50f, keyHeight = 100f))
        assertFalse("exactly on the line", spaceVoiceCancelArmed(lockArmed = false, yInKey = -100f, keyHeight = 100f))
        assertTrue("past the line", spaceVoiceCancelArmed(lockArmed = false, yInKey = -130f, keyHeight = 100f))
        assertFalse("past the line but in the lock slot", spaceVoiceCancelArmed(lockArmed = true, yInKey = -130f, keyHeight = 100f))
    }
}
