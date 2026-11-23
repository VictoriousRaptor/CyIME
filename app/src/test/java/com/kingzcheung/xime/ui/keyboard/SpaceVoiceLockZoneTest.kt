package com.kingzcheung.xime.ui.keyboard

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两把锁：以空格为中心左右对称，各自靠键区两边、离空格两端不太远；弧线以内和往外冲过头算锁定，
 * 中间（两条弧线之间）不算，留给上滑取消（用户 11-22：右上一把锁不对称）。
 */
class SpaceVoiceLockZoneTest {
    // 半径 70、离边 25、离空格最远 420、判定至少大出 60、弧线半径最大 170、离取消按钮至少 20
    // （约 2.6 倍密度的 26/10/168/24/64/8 dp）。
    private val m = SpaceVoiceLockMetrics(radius = 70f, margin = 25f, reach = 420f, slop = 60f, zone = 170f, gap = 20f)
    // 手机竖屏：键区 1080×800，从根坐标 (0, 700) 开始；空格在下面中间。
    private val phone = Rect(0f, 700f, 1080f, 1500f)
    private val phoneSpace = Rect(380f, 1340f, 700f, 1490f)
    private fun phoneLayout() = spaceVoiceLockLayout(phone, phoneSpace, m)!!

    @Test fun `on a phone the two locks sit in the top corners and the cancel button between them`() {
        val l = phoneLayout()
        assertEquals(Offset(95f, 795f), l.left)
        assertEquals(Offset(985f, 795f), l.right)
        assertEquals(Offset(540f, 795f), l.cancel)
        assertEquals(170f, l.zone, 0f)
    }

    @Test fun `on a wide keyboard both stay within reach of the space bar, symmetric around it`() {
        val l = spaceVoiceLockLayout(Rect(0f, 900f, 2560f, 1700f), Rect(900f, 1540f, 1600f, 1690f), m)!!
        assertEquals(Offset(480f, 995f), l.left)
        assertEquals(Offset(2020f, 995f), l.right)
        assertEquals(1250f, l.cancel.x, 0f)
    }

    @Test fun `a space bar off to one side keeps the locks symmetric around it`() {
        val l = spaceVoiceLockLayout(Rect(0f, 0f, 1080f, 800f), Rect(100f, 640f, 500f, 790f), m)!!
        assertEquals(300f - l.left.x, l.right.x - 300f, 0f)
        assertEquals(95f, l.left.x, 0f)
        assertEquals(300f, l.cancel.x, 0f)
    }

    @Test fun `floating keyboards keep both inside and shrink the arcs to leave the cancel button free`() {
        val floating = Rect(300f, 400f, 900f, 900f)
        val l = spaceVoiceLockLayout(floating, Rect(450f, 800f, 750f, 890f), m)!!
        assertTrue(l.left.x - m.radius >= floating.left && l.right.x + m.radius <= floating.right)
        assertTrue(l.zone < 170f)
        // 弧线到取消按钮边上还留着空。
        assertTrue(l.right.x - l.zone > l.cancel.x + m.radius)
        assertEquals(0, l.sideAt(l.cancel))
    }

    @Test fun `a very short key area keeps the buttons vertically inside`() {
        assertEquals(60f, spaceVoiceLockLayout(Rect(0f, 0f, 1080f, 120f), Rect.Zero, m)!!.left.y, 0f)
    }

    @Test fun `nothing measured yet or too narrow means no lock`() {
        assertNull(spaceVoiceLockLayout(Rect.Zero, Rect.Zero, m))
        assertNull(spaceVoiceLockLayout(Rect(0f, 0f, 150f, 800f), Rect.Zero, m))
        assertNull(spaceVoiceLockLayout(Rect(0f, 0f, 300f, 800f), Rect.Zero, m))
        assertFalse(SpaceVoiceLockState().zoneContains(Offset(0f, 0f)))
    }

    @Test fun `inside either arc and overshooting outward lock, each on its own side`() {
        val l = phoneLayout()
        assertEquals("on the right button", 1, l.sideAt(Offset(985f, 795f)))
        assertEquals("on the left button", -1, l.sideAt(Offset(95f, 795f)))
        assertEquals("inside the right arc, lower-left", 1, l.sideAt(Offset(872f, 908f)))
        assertEquals("inside the left arc, lower-right", -1, l.sideAt(Offset(208f, 908f)))
        assertEquals("above the keyboard on the right", 1, l.sideAt(Offset(1000f, 600f)))
        assertEquals("above the keyboard on the left", -1, l.sideAt(Offset(80f, 600f)))
        assertEquals("past the right edge", 1, l.sideAt(Offset(1100f, 760f)))
        assertEquals("past the left edge", -1, l.sideAt(Offset(-20f, 760f)))
    }

    @Test fun `the middle between the arcs and the keys below them do not lock`() {
        val l = phoneLayout()
        assertEquals("straight up", 0, l.sideAt(Offset(540f, 760f)))
        assertEquals("straight up out of the keyboard", 0, l.sideAt(Offset(540f, 600f)))
        assertEquals("just outside the right arc", 0, l.sideAt(Offset(780f, 800f)))
        assertEquals("below the right arc", 0, l.sideAt(Offset(985f, 1000f)))
        assertEquals("below the left arc", 0, l.sideAt(Offset(95f, 1000f)))
    }

    @Test fun `the cancel button counts as cancel even below the line`() {
        val state = SpaceVoiceLockState().apply { host = phone; space = phoneSpace; metrics = m }
        assertTrue(state.cancelButtonContains(Offset(540f, 895f)))
        assertFalse(state.cancelButtonContains(Offset(540f, 1000f)))
        assertEquals(-1, state.zoneSide(Offset(95f, 795f)))
        assertTrue(spaceVoiceCancelArmed(lockArmed = false, yInKey = -50f, keyHeight = 100f, onCancelButton = true))
        assertFalse(spaceVoiceCancelArmed(lockArmed = true, yInKey = -50f, keyHeight = 100f, onCancelButton = true))
    }

    @Test fun `when armed the button leans toward the finger at most a little`() {
        assertEquals(Offset(3f, 4f), spaceVoiceLockPull(Offset(0f, 0f), Offset(3f, 4f), 20f))
        assertEquals(Offset(12f, 16f), spaceVoiceLockPull(Offset(0f, 0f), Offset(30f, 40f), 20f))
        assertEquals(Offset.Zero, spaceVoiceLockPull(Offset(0f, 0f), Offset.Unspecified, 20f))
    }

    @Test fun `swipe-up cancel needs a full key height above the space and never fires on a lock`() {
        assertFalse("half a key up", spaceVoiceCancelArmed(lockArmed = false, yInKey = -50f, keyHeight = 100f))
        assertFalse("exactly on the line", spaceVoiceCancelArmed(lockArmed = false, yInKey = -100f, keyHeight = 100f))
        assertTrue("past the line", spaceVoiceCancelArmed(lockArmed = false, yInKey = -130f, keyHeight = 100f))
        assertFalse("past the line but on a lock", spaceVoiceCancelArmed(lockArmed = true, yInKey = -130f, keyHeight = 100f))
    }

    @Test fun `lifting off keeps an armed lock armed, but never arms by itself`() {
        assertTrue("in the zone", spaceVoiceLockArmed(inZone = true, released = false, wasArmed = false))
        assertFalse("moved out while still down", spaceVoiceLockArmed(inZone = false, released = false, wasArmed = true))
        assertTrue("lift-off jitter out of the zone", spaceVoiceLockArmed(inZone = false, released = true, wasArmed = true))
        assertFalse("released outside, never armed", spaceVoiceLockArmed(inZone = false, released = true, wasArmed = false))
    }

    @Test fun `the button looks armed exactly when letting go would lock`() {
        val state = SpaceVoiceLockState()
        state.armed = true
        assertFalse("microphone not on yet: no button, no lock", state.lockReady)
        state.holding = true
        assertTrue(state.lockReady)
        state.armed = false
        assertFalse(state.lockReady)
    }
}
