package com.kingzcheung.xime.ui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingDockGestureTest {
    /** 用户 11-10：一路往下拖到底，松手瞬间变固定、看不到渐变。拖到底之前的行程不算，到底后还得停住让渐变框走完。 */
    @Test fun travelBeforeReachingTheBottomDoesNotCount() {
        val gesture = FloatingDockGesture()
        // 卡片还没到底：走多远都不算。
        gesture.move(0f, 400f, false)
        assertFalse(gesture.armed)
        // 这一下卡片刚好挪到底边（手指没有比卡片多走）：只是放在底边，不准备贴底。
        gesture.move(0f, 0f, true)
        gesture.advanceHold(2_000, gesture.epoch)
        assertFalse(gesture.armed)
        assertFalse(floatingDockOnRelease(gesture.progress, gesture.armed, true))
        // 到底后手指再往下顶：开始准备，但渐变框从零开始，马上松手不贴底。
        gesture.move(0f, FLOATING_DOCK_ARM_PUSH_DP, true)
        assertTrue(gesture.armed)
        assertEquals(0f, gesture.progress, 0f)
        assertFalse(floatingDockOnRelease(gesture.progress, gesture.armed, true))
        gesture.advanceHold(FLOATING_DOCK_DURATION_MILLIS / 2, gesture.epoch)
        assertTrue(gesture.progress in 0.4f..0.6f)
        gesture.advanceHold(FLOATING_DOCK_DURATION_MILLIS, gesture.epoch)
        assertTrue(floatingDockOnRelease(gesture.progress, gesture.armed, true))
    }

    /** 他 10-23 定的 0.75 秒：比 0.5 秒不容易误贴，又不像 1 秒那么慢。 */
    @Test fun dockingWaitsThreeQuartersOfASecondOnTheEdge() {
        assertEquals(750L, FLOATING_DOCK_DURATION_MILLIS)
        val gesture = FloatingDockGesture()
        gesture.move(0f, 12f, true)
        val first = gesture.epoch
        gesture.advanceHold(299, first)
        assertTrue(gesture.progress < 1f)
        gesture.advanceHold(700, first)
        assertTrue(gesture.progress < 1f)
        // 卡片离开底边贴边区：作废；回来要重新往下顶、重新计时。
        gesture.move(0f, 0f, false)
        assertFalse(gesture.armed)
        gesture.move(0f, 12f, true)
        gesture.advanceHold(900, first)
        assertEquals(0f, gesture.progress, 0f)
        gesture.advanceHold(750, gesture.epoch)
        assertEquals(1f, gesture.progress, 0f)
        gesture.cancel()
        assertFalse(gesture.armed)
        assertEquals(0f, gesture.progress, 0f)
    }

    /** 准备好以后手指在底边晃一下（卡片还在贴边区，手指没再往下顶）不打断计时。 */
    @Test fun aSmallWobbleOnTheEdgeKeepsTheHold() {
        val gesture = FloatingDockGesture()
        gesture.move(0f, 10f, true)
        val epoch = gesture.epoch
        gesture.move(2f, 0f, true)
        gesture.move(-3f, 0f, true)
        assertTrue(gesture.armed)
        assertEquals(epoch, gesture.epoch)
        gesture.advanceHold(FLOATING_DOCK_DURATION_MILLIS, epoch)
        assertEquals(1f, gesture.progress, 0f)
    }

    @Test fun beingAtTheBottomOrDraggingSidewaysCannotArmDocking() {
        val gesture = FloatingDockGesture()
        gesture.move(120f, 30f, true)
        gesture.advanceHold(2000, gesture.epoch)
        assertFalse(gesture.armed)
        gesture.cancel()
        gesture.move(0f, 0f, true)
        gesture.advanceHold(2000, gesture.epoch)
        assertFalse(gesture.armed)
        assertTrue(modeSwitchPullProgress(150f, 90f) < 1f)
    }

    @Test
    fun edgeDetectionOnlyAcceptsTheBottomAboveNavigation() {
        fun edge(x: Float, y: Float) = floatingDockEdge(x, y, horizontalTravel = 30f, maxOffsetY = 500f, bottomInset = 20f)
        assertEquals(FloatingDockEdge.BOTTOM, edge(0f, 20f))
        assertEquals(FloatingDockEdge.BOTTOM, edge(0f, 0f))
        assertNull(edge(-30f, 200f))
        assertNull(edge(30f, 200f))
        assertNull(edge(0f, 500f))
        assertNull(edge(0f, 200f))
        assertNull(edge(0f, 33f))
    }
    @Test fun releasingDocksExactlyWhenThePreviewShowsTheDockedShape() {
        // The outline has reached the docked shape and the card is on the edge: docks.
        assertTrue(floatingDockOnRelease(previewProgress = 1f, onBottomEdge = true, hasDestination = true))
        // Still morphing: stays floating, as the half-drawn outline shows.
        assertFalse(floatingDockOnRelease(previewProgress = 0.9f, onBottomEdge = true, hasDestination = true))
        // Moved off the edge after the preview finished: no dock.
        assertFalse(floatingDockOnRelease(previewProgress = 1f, onBottomEdge = false, hasDestination = true))
        assertFalse(floatingDockOnRelease(previewProgress = 1f, onBottomEdge = true, hasDestination = false))
    }

    @Test fun pullingAFixedKeyboardUpHasResistanceBeforeItTurnsFloating() {
        // 轻轻一拖不动；过了死区才开始变，拉满一整段才算可以松手变悬浮。
        assertEquals(0f, modeSwitchPullProgress(FIXED_TO_FLOATING_DEAD_ZONE_DP), 0f)
        assertEquals(0.5f, modeSwitchPullProgress(FIXED_TO_FLOATING_DEAD_ZONE_DP + FIXED_TO_FLOATING_PULL_DP / 2f), 0.001f)
        assertTrue(modeSwitchPullProgress(FIXED_TO_FLOATING_DEAD_ZONE_DP + FIXED_TO_FLOATING_PULL_DP - 1f) < 1f)
        assertEquals(1f, modeSwitchPullProgress(400f), 0f)
    }
}
