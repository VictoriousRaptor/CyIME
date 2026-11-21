package com.kingzcheung.xime.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeapReclaimTest {
    private val never = Long.MIN_VALUE

    @Test fun reclaimsOnlyWhenKeyboardAndSettingsAreBothGone() {
        assertTrue(HeapReclaim.due(keyboardShown = false, startedActivities = 0, lastRunAt = never, now = 0))
        assertFalse(HeapReclaim.due(keyboardShown = true, startedActivities = 0, lastRunAt = never, now = 0))
        assertFalse(HeapReclaim.due(keyboardShown = false, startedActivities = 1, lastRunAt = never, now = 0))
    }

    @Test fun reclaimsAtMostEveryTenMinutes() {
        val last = 1_000_000L
        assertFalse(HeapReclaim.due(false, 0, last, last + HeapReclaim.MIN_INTERVAL_MS - 1))
        assertTrue(HeapReclaim.due(false, 0, last, last + HeapReclaim.MIN_INTERVAL_MS))
    }
}
