package com.kingzcheung.xime.ui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 候选栏横拉的阻力和门槛（用户 11-20：拉过门槛松手展开，不够就弹回，两个方向都算）。 */
class CandidatePullGesturesTest {
    private val threshold = 96f

    @Test fun pullFollowsTheFingerAtHalfSpeedInEitherDirection() {
        assertEquals(50f, pulledDistance(100f, threshold), 0.01f)
        assertEquals(-50f, pulledDistance(-100f, threshold), 0.01f)
        assertEquals(0f, pulledDistance(0f, threshold), 0.01f)
    }

    @Test fun pullStopsAtOneAndAHalfThresholds() {
        assertEquals(144f, pulledDistance(1000f, threshold), 0.01f)
        assertEquals(-144f, pulledDistance(-1000f, threshold), 0.01f)
    }

    @Test fun releasesOnlyPastTheThreshold() {
        assertFalse(pullReleases(95f, threshold))
        assertTrue(pullReleases(96f, threshold))
        assertTrue(pullReleases(-96f, threshold))
        assertFalse(pullReleases(-95f, threshold))
        assertFalse(pullReleases(100f, 0f))
    }
}
