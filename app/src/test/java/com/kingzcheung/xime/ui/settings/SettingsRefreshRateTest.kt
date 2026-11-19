package com.kingzcheung.xime.ui.settings

import com.kingzcheung.xime.ui.settings.SettingsRefreshRate.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsRefreshRateTest {
    private val m60 = Mode(1, 1080, 2340, 60f)
    private val m120 = Mode(2, 1080, 2340, 120f)
    private val m90 = Mode(3, 1080, 2340, 90f)
    private val big120 = Mode(4, 1440, 3120, 144f)

    @Test fun picksHighestRateAtSameResolution() {
        assertEquals(2, SettingsRefreshRate.pickHighest(m60, listOf(m60, m90, m120, big120)))
    }

    @Test fun neverChangesResolution() {
        assertNull(SettingsRefreshRate.pickHighest(m60, listOf(m60, big120)))
    }

    @Test fun leavesAloneWhenAlreadyHighest() {
        assertNull(SettingsRefreshRate.pickHighest(m120, listOf(m60, m90, m120)))
        assertNull(SettingsRefreshRate.pickHighest(m60, listOf(m60)))
    }
}
