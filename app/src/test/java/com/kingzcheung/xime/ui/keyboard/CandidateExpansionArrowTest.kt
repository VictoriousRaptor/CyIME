package com.kingzcheung.xime.ui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * 候选展开箭头转动时，箭头尖从左边过（11-22 他：「应该改为从左侧旋转，远古版本就修了不知道哪个版本又改回来了」）。
 * 0.1.5 用 ▴ 转 -180°，尖从左边转下去；11-01 换成 ▾ 没改角度，尖改从右边转上去。换图标或改角度都要过这个测试。
 */
class CandidateExpansionArrowTest {
    /** 图标静止时尖朝哪（屏幕坐标，y 向下）。 */
    private fun restingTip(): Pair<Float, Float> = when (CANDIDATE_EXPANSION_ARROW.name) {
        "Filled.KeyboardArrowDown" -> 0f to 1f
        "Filled.KeyboardArrowUp" -> 0f to -1f
        else -> error("换了展开图标 ${CANDIDATE_EXPANSION_ARROW.name}：在这里写上它的尖朝哪")
    }

    /** Modifier.rotate 正角度是屏幕上的顺时针。 */
    private fun tipAt(degrees: Float): Pair<Float, Float> {
        val (x, y) = restingTip()
        val r = Math.toRadians(degrees.toDouble())
        return (x * cos(r) - y * sin(r)).toFloat() to (x * sin(r) + y * cos(r)).toFloat()
    }

    @Test fun expandedArrowPointsTheOtherWay() {
        val (x, y) = tipAt(CANDIDATE_EXPANSION_ARROW_EXPANDED_DEGREES)
        assertEquals(0f, x, 0.001f)
        assertEquals(-restingTip().second, y, 0.001f)
    }

    /** 展开（0 → 目标角）和收回（目标角 → 0）走的是同一段角度，中间每一步尖都在左边。 */
    @Test fun tipPassesOnTheLeft() {
        for (step in 1..9) {
            val (x, _) = tipAt(CANDIDATE_EXPANSION_ARROW_EXPANDED_DEGREES * step / 10f)
            assertTrue("转到 ${step * 10}% 时尖在 x=$x，应在左边", x < 0f)
        }
    }
}
