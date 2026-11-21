package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.service.HardwareCursorAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 实体键盘浮条打字时稳住（用户 11-20：贴底打字时浮条乱晃、宽度一直变，还挡住输入框）。 */
class HardwareBarSteadyTest {
    @Test fun widthOnlyGrowsWithinOneInput() {
        // 多打一个字母、注释一会有一会没有，宽度都不缩回去；分档时一档内不变。
        var w = hardwareGrowOnlyWidth(0, 50, 40, 300)
        assertEquals(80, w)
        w = hardwareGrowOnlyWidth(w, 75, 40, 300)
        assertEquals(80, w)
        w = hardwareGrowOnlyWidth(w, 30, 40, 300)
        assertEquals(80, w)
        w = hardwareGrowOnlyWidth(w, 81, 40, 300)
        assertEquals(120, w)
        // 最宽不超过上限；不分档时按原宽度。
        assertEquals(300, hardwareGrowOnlyWidth(w, 999, 40, 300))
        assertEquals(213, hardwareGrowOnlyWidth(200, 213, 0, 560))
        assertEquals(200, hardwareGrowOnlyWidth(200, 150, 0, 560))
        assertEquals(0, hardwareGrowOnlyWidth(10, 50, 40, 0))
    }

    @Test fun candidateCardStaysWhereTypingStartedButFollowsLineBreaks() {
        val start = HardwareCursorAnchor(100f, 200f, 102f, 230f)
        val later = HardwareCursorAnchor(260f, 200f, 262f, 230f)
        assertEquals(start, hardwareSteadyAnchor(later, start))
        val wrapped = HardwareCursorAnchor(20f, 240f, 22f, 270f)
        assertEquals(HardwareCursorAnchor(100f, 240f, 102f, 270f), hardwareSteadyAnchor(wrapped, start))
        // 不在打字：照常跟光标；没有光标就没有。
        assertEquals(later, hardwareSteadyAnchor(later, null))
        assertNull(hardwareSteadyAnchor(null, start))
    }

    @Test fun fadesOnlyWhenCoveringTheLineBeingTyped() {
        val caret = HardwareCandidateExclusion(300, 500, 302, 530)
        val editor = HardwareCandidateExclusion(40, 480, 760, 560)
        val line = hardwareTypingLine(caret, editor, 24)!!
        // 输入框里光标这一行，左右到输入框边：拼音写在框里时正在打的字在光标左边。
        assertEquals(HardwareCandidateExclusion(40, 500, 760, 530), line)
        assertTrue(HardwareCandidateExclusion(60, 510, 200, 560).overlaps(line))
        // 输入框其他行、框外都不算。
        assertFalse(HardwareCandidateExclusion(60, 532, 700, 560).overlaps(line))
        assertFalse(HardwareCandidateExclusion(0, 600, 800, 660).overlaps(line))
        // 不知道输入框（或光标不在框里）：光标左右各留一段。
        assertEquals(HardwareCandidateExclusion(276, 500, 326, 530), hardwareTypingLine(caret, null, 24))
        assertEquals(HardwareCandidateExclusion(276, 500, 326, 530),
            hardwareTypingLine(caret, HardwareCandidateExclusion(400, 480, 760, 560), 24))
        assertNull(hardwareTypingLine(null, editor, 24))
        assertFalse(HardwareCandidateExclusion(0, 0, 800, 600).overlaps(null))
    }

    @Test fun screenAnchorsConvertIntoHostCoordinates() {
        assertEquals(HardwareCandidateExclusion(90, 150, 93, 181),
            HardwareCursorAnchor(100.5f, 200f, 102.2f, 230.4f).inHost(10f, 50f))
        assertNull(HardwareCursorAnchor(Float.NaN, 0f, 1f, 1f).inHost(0f, 0f))
    }

    @Test fun editorAvoidanceNeverJumpsSideways() {
        // 光标每打一个字往右走：浮条只能上下躲，横向位置不变（以前会跳到输入框左边或右边）。
        val g = hardwareToolbarGeometry(800, 600, 232, 56, 8)
        val start = g.offset(HardwareToolbarPosition(0.5f, 0.5f))
        for (caretX in listOf(200, 300, 400, 500, 600)) {
            val moved = avoidHardwareEditor(g, start, 232, 56,
                HardwareCandidateExclusion(caretX - 36, start.y.toInt() + 10, caretX + 38, start.y.toInt() + 40), 12)
            assertEquals(start.x, moved.x, 0f)
        }
        // 上下都放不下（整屏被挡）时也不横跳。
        assertEquals(start.x, avoidHardwareEditor(g, start, 232, 56, HardwareCandidateExclusion(0, 0, 800, 600), 12).x, 0f)
    }
}
