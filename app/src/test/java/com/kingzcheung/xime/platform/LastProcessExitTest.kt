package com.kingzcheung.xime.platform

import android.app.ApplicationExitInfo
import org.junit.Assert.assertTrue
import org.junit.Test

class LastProcessExitTest {
    @Test fun swipeKillIsSpelledOut() {
        val line = LastProcessExit.describe(ApplicationExitInfo.REASON_USER_REQUESTED, "remove task", 100, 5 * 60_000L)
        assertTrue(line, "划掉卡片" in line && "reason=10" in line && "remove task" in line && "5 分钟前" in line)
    }

    @Test fun unknownReasonKeepsTheNumber() {
        val line = LastProcessExit.describe(99, null, 400, -1)
        assertTrue(line, "原因 99" in line && "系统说明：无" in line && "0 分钟前" in line)
    }
}
