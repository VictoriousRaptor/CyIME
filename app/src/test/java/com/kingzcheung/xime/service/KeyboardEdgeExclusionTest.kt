package com.kingzcheung.xime.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 按键区左右两边挡住系统边缘手势：从按键区底边往上，最多 200dp；底部留白不算；关着时不挡。 */
class KeyboardEdgeExclusionTest {
    private val density = 3f

    @Test fun offWhenNoSpec() {
        assertTrue(keyboardEdgeExclusionBands(1080, 900, null, density).isEmpty())
    }

    @Test fun coversKeysFromTheirBottomEdgeUpTo200dp() {
        // 键盘 280dp 高（含候选栏），下面留白 + 导航避让 40dp，容器 320dp。
        val bands = keyboardEdgeExclusionBands(1080, 960, KeyboardEdgeExclusion(120, 840), density)
        assertEquals(listOf(EdgeBand(0, 240, 144, 840), EdgeBand(936, 240, 1080, 840)), bands)
    }

    @Test fun shortKeyboardIsCoveredWhole() {
        val bands = keyboardEdgeExclusionBands(1080, 600, KeyboardEdgeExclusion(0, 450), density)
        assertEquals(EdgeBand(0, 150, 144, 600), bands.first())
    }

    @Test fun narrowContainerSplitsWidthInHalf() {
        val bands = keyboardEdgeExclusionBands(200, 600, KeyboardEdgeExclusion(0, 600), density)
        assertEquals(EdgeBand(0, 0, 100, 600), bands[0])
        assertEquals(EdgeBand(100, 0, 200, 600), bands[1])
    }

    @Test fun nothingBeforeLayout() {
        assertTrue(keyboardEdgeExclusionBands(0, 0, KeyboardEdgeExclusion(0, 600), density).isEmpty())
    }
}
