package com.kingzcheung.xime.ui.keyboard

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 拖动悬浮窗时投最高刷新率的票，松手撤票（用户 11-19）。 */
class ImeHighRefreshTest {
    @After fun reset() = ImeHighRefresh.resetForTest()

    @Test fun votesOnFirstHolderAndWithdrawsAfterTheLast() {
        val votes = mutableListOf<Boolean>()
        ImeHighRefresh.apply = { votes += it }
        ImeHighRefresh.acquire()
        ImeHighRefresh.acquire()
        ImeHighRefresh.release()
        assertTrue(ImeHighRefresh.active)
        ImeHighRefresh.release()
        assertFalse(ImeHighRefresh.active)
        assertEquals(listOf(true, false), votes)
    }

    @Test fun extraReleaseDoesNotGoNegative() {
        val votes = mutableListOf<Boolean>()
        ImeHighRefresh.apply = { votes += it }
        ImeHighRefresh.release()
        ImeHighRefresh.acquire()
        assertEquals(listOf(true), votes)
    }

    @Test fun serviceAttachedMidDragGetsTheVoteRightAway() {
        ImeHighRefresh.acquire()
        val votes = mutableListOf<Boolean>()
        ImeHighRefresh.apply = { votes += it }
        assertEquals(listOf(true), votes)
    }

    @Test fun picksHighestRateAtTheSameResolutionEvenWhenAlreadyThere() {
        val m60 = ImeHighRefresh.Mode(1, 1080, 2340, 60f)
        val m120 = ImeHighRefresh.Mode(2, 1080, 2340, 120f)
        val m144low = ImeHighRefresh.Mode(3, 720, 1560, 144f)
        assertEquals(2, ImeHighRefresh.highestModeId(m60, listOf(m60, m120, m144low)))
        assertEquals(2, ImeHighRefresh.highestModeId(m120, listOf(m60, m120, m144low)))
        assertNull(ImeHighRefresh.highestModeId(m60, listOf(m144low)))
    }
}
