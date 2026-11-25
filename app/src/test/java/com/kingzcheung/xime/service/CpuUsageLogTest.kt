package com.kingzcheung.xime.service

import com.kingzcheung.xime.service.CpuUsageLog.Snapshot
import com.kingzcheung.xime.service.CpuUsageLog.ThreadTicks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuUsageLogTest {
    @Test fun parsesThreadNameWithSpacesAndParens() {
        val line = "1234 (Rime (io) 2) S 1 2 3 4 5 6 7 8 9 10 150 30 0 0 20 0"
        assertEquals(ThreadTicks("Rime (io) 2", 180), CpuUsageLog.parseStat(line))
        assertNull(CpuUsageLog.parseStat("garbage"))
    }

    @Test fun topThreadsMergesPoolsAndIgnoresReusedIds() {
        val before = mapOf(1 to ThreadTicks("main", 100), 2 to ThreadTicks("pool-1", 10), 3 to ThreadTicks("old", 50))
        val after = mapOf(
            1 to ThreadTicks("main", 130),
            2 to ThreadTicks("pool-1", 20),
            3 to ThreadTicks("pool-1", 40), // tid 3 reused by another thread: counted from 0
            4 to ThreadTicks("idle", 0),
        )
        assertEquals(listOf("pool-1" to 50L, "main" to 30L), CpuUsageLog.topThreads(before, after, 5))
    }

    @Test fun shortQuietHiddenIntervalIsNotLogged() {
        val a = Snapshot(0, 1000, emptyMap(), emptyMap())
        val b = Snapshot(10_000, 1005, emptyMap(), emptyMap())
        assertNull(CpuUsageLog.describe("hidden", a, b, 10.0))
    }

    @Test fun busyHiddenIntervalNamesTheThreads() {
        val a = Snapshot(0, 0, mapOf(7 to ThreadTicks("Spinner", 0)), mapOf(":asr" to (99 to 0L)))
        val b = Snapshot(30_000, 3_000, mapOf(7 to ThreadTicks("Spinner", 290)), mapOf(":asr" to (99 to 50L)))
        val line = CpuUsageLog.describe("hidden", a, b, 10.0)!!
        assertTrue(line, line.startsWith("hidden 30 s: cpu 3000 ms (6000 ms/min)"))
        assertTrue(line, line.contains("other :asr 500 ms"))
        assertTrue(line, line.contains("top Spinner 2900 ms"))
    }

    @Test fun restartedOtherProcessCountsFromZero() {
        val a = Snapshot(0, 0, emptyMap(), mapOf(":inference" to (10 to 900L)))
        val b = Snapshot(120_000, 0, emptyMap(), mapOf(":inference" to (11 to 30L)))
        assertEquals(listOf(":inference" to 300L), CpuUsageLog.otherProcessDeltas(a, b, 10.0))
    }
}
