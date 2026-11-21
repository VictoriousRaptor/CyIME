package com.kingzcheung.xime.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 编码显示在输入框时长按空格挪光标（用户 11-18：「拼音显示在输入框的时候无法移动，但是候选词是跟着移动而变化的」）。
 * 宿主文字核对得上就照文字放；取不到文字或文字是旧的，用宿主报过的组合区起点；两样都没有就不动。
 */
class InlineCaretPositionTest {
    private val code = "ni'hao'wo"

    @Test fun hostTextMatchesPlacesCaretInsideCode() {
        val content = "前文$code"
        assertEquals(2 + 6, inlineCaretPosition(code, code.length, 6, content, 0, content.length, content.length, -1))
        // 宿主只给了后半截文字：位置加上起始偏移。
        assertEquals(10 + 2 + 3, inlineCaretPosition(code, code.length, 3, content, 10, content.length, content.length, -1))
    }

    @Test fun noHostTextFallsBackToReportedSpan() {
        assertEquals(5 + 3, inlineCaretPosition(code, code.length, 3, null, -1, -1, -1, reportedStart = 5))
    }

    @Test fun staleHostTextFallsBackToReportedSpan() {
        // 网页宿主给的还是打字前的文字。
        val stale = "前文ni'hao"
        assertEquals(2 + 0, inlineCaretPosition(code, code.length, 0, stale, 0, stale.length, stale.length, reportedStart = 2))
    }

    @Test fun nothingKnownLeavesCaretAlone() {
        assertNull(inlineCaretPosition(code, code.length, 3, null, -1, -1, -1, -1))
        val other = "完全不同的文字"
        assertNull(inlineCaretPosition(code, code.length, 3, other, 0, other.length, other.length, -1))
        assertNull(inlineCaretPosition(code, code.length, code.length + 1, null, -1, -1, -1, 5))
    }

    @Test fun reportedStartOnlyAfterHostReportsOurSpan() {
        val tracker = InlineCompositionTracker()
        tracker.selectionReported(4, 4, -1, -1)
        tracker.shown(code)
        assertEquals(-1, tracker.reportedStart)
        tracker.selectionReported(4 + code.length, 4 + code.length, 4, 4 + code.length)
        assertEquals(4, tracker.reportedStart)
        tracker.ended()
        assertEquals(-1, tracker.reportedStart)
    }
}
