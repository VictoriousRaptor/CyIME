package com.kingzcheung.xime.clipboard.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** #45：其他同步服务（WebDAV 等）的拉取间隔可选，默认和以前一样 30 秒、键盘开着时不一直拉。 */
class PullIntervalTest {
    @Test fun defaultsStayAsBefore() {
        assertEquals(30_000L, DEFAULT_PULL_INTERVAL_MS)
        assertEquals(0L, foregroundPollMs(0, mesh = false, keepPulling = false, pullIntervalMs = DEFAULT_PULL_INTERVAL_MS))
        assertArrayEquals(intArrayOf(5, 10, 30, 60), PULL_INTERVAL_CHOICES_S)
    }

    @Test fun keepPullingUsesTheChosenInterval() {
        assertEquals(5_000L, foregroundPollMs(0, mesh = false, keepPulling = true, pullIntervalMs = 5_000))
        assertEquals(60_000L, foregroundPollMs(0, mesh = false, keepPulling = true, pullIntervalMs = 60_000))
    }

    @Test fun pluginDeclaredPollingIsUnchanged() {
        assertEquals(2_000L, foregroundPollMs(2_000, mesh = true, keepPulling = false, pullIntervalMs = 60_000))
        assertEquals(2_000L, foregroundPollMs(2_000, mesh = false, keepPulling = true, pullIntervalMs = 60_000))
    }

    @Test fun windowsSyncIgnoresKeepPulling() {
        assertEquals(0L, foregroundPollMs(0, mesh = true, keepPulling = true, pullIntervalMs = 5_000))
    }

    @Test fun oddValuesAreClamped() {
        assertEquals(5_000L, clampPullInterval(1_000))
        assertEquals(60_000L, clampPullInterval(600_000))
    }
}
