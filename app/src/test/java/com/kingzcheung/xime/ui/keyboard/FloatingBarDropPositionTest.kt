package com.kingzcheung.xime.ui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 语音悬浮条松手停哪：像 Gboard 往屏幕外推才靠边变竖条，其余照实体键盘浮条的规则。 */
class FloatingBarDropPositionTest {
    // 手机竖屏：411 宽，横条 379 宽，横向只能挪 16 像素；推过条宽四分之一（约 95）才算靠边。
    private val phone = hardwareToolbarGeometry(411, 800, 379, 56, 8)
    private val push = 379 / 4f

    private fun drop(geometry: HardwareToolbarGeometry, x: Float, y: Float, sideDock: Boolean = true) =
        geometry.floatingBarDropPosition(x, y, HardwareToolbarPosition(), dockAtEdge = true, sideDock = sideDock, dockPushPx = push)

    @Test fun `a slight sideways wobble on a phone does not dock to the side`() {
        for (x in listOf(-20f, 0f, phone.minX, phone.maxX, phone.maxX + 30f)) {
            val result = drop(phone, x, 300f)
            assertTrue("x=$x", result.xFraction != 0f && result.xFraction != 1f)
            assertTrue(!hardwareToolbarIsVertical(411f, 800f, result, true))
        }
    }

    @Test fun `pushing past the screen edge docks to that side as a vertical bar`() {
        val left = drop(phone, phone.minX - push - 1f, 300f)
        assertEquals(0f, left.xFraction)
        assertTrue(hardwareToolbarIsVertical(411f, 800f, left, true))
        val right = drop(phone, phone.maxX + push + 1f, 300f)
        assertEquals(1f, right.xFraction)
    }

    @Test fun `pushed off the side at the bottom docks to the side, not the bottom`() {
        val result = drop(phone, phone.minX - push - 1f, phone.maxY)
        assertEquals(0f, result.xFraction)
        assertTrue(result.yFraction < 1f)
    }

    @Test fun `bottom centre still docks to the bottom`() {
        val centre = (phone.minX + phone.maxX) / 2
        assertEquals(HardwareToolbarPosition(0.5f, 1f), drop(phone, centre, phone.maxY))
    }

    @Test fun `without side docking it is the physical keyboard rule unchanged`() {
        for (x in listOf(-200f, 0f, 16f, 300f)) {
            assertEquals(phone.positionAt(x, 300f, HardwareToolbarPosition()), drop(phone, x, 300f, sideDock = false))
        }
    }

    @Test fun `wide screens dock within 32 like the physical keyboard bar`() {
        val tablet = hardwareToolbarGeometry(1200, 800, 400, 56, 8)
        assertEquals(0f, drop(tablet, tablet.minX + 20f, 300f).xFraction)
        assertEquals(1f, drop(tablet, tablet.maxX - 20f, 300f).xFraction)
        val middle = drop(tablet, tablet.minX + 200f, 300f).xFraction
        assertNotEquals(0f, middle)
        assertNotEquals(1f, middle)
    }

    @Test fun `a docked vertical bar stays docked near the edge and comes off when pulled away`() {
        val vertical = hardwareToolbarGeometry(411, 800, 56, 200, 8)
        assertEquals(0f, drop(vertical, vertical.minX + 20f, 300f).xFraction)
        val pulled = drop(vertical, vertical.minX + 150f, 300f)
        assertTrue(pulled.xFraction > 0f && pulled.xFraction < 1f)
        assertTrue(!hardwareToolbarIsVertical(411f, 800f, pulled, true))
    }
}
