package com.kingzcheung.xime.ui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

/** 滑到哪个键按哪个键时炫光跟着手指走（用户 11-16，像三星键盘）。 */
class SlideGlowStepTest {
    /** 一个键依次收到的 slideLit，返回每一步的动作。 */
    private fun walk(vararg states: Boolean?): List<SlideGlowStep> {
        var before: Boolean? = null
        return states.map { now -> slideGlowStep(before, now).also { before = now } }
    }

    @Test fun startKeyFadesWhenFingerSlidesAwayAndLightsAgainWhenItComesBack() {
        // 起手的键：开始滑（false）淡出；滑回来（true）再亮；又滑走淡出；松手（null）不再动。
        assertEquals(listOf(SlideGlowStep.RELEASE, SlideGlowStep.PRESS, SlideGlowStep.RELEASE, SlideGlowStep.NONE),
            walk(false, true, false, null))
    }

    @Test fun passedKeyLightsOnEnterAndFadesOnLeave() {
        assertEquals(listOf(SlideGlowStep.PRESS, SlideGlowStep.NONE, SlideGlowStep.RELEASE), walk(true, true, null))
    }

    @Test fun keyUnderFingerAtReleaseFades() {
        // 松手时手指下的键（true → null）淡出，和点按松手一样。
        assertEquals(SlideGlowStep.RELEASE, slideGlowStep(true, null))
    }

    @Test fun keysTheFingerNeverTouchesStayDark() {
        assertEquals(listOf(SlideGlowStep.NONE, SlideGlowStep.NONE), walk(null, null))
    }
}
