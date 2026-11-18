package com.kingzcheung.xime.ui.keyboard

import org.junit.Assert.*
import org.junit.Test

class T9CandidateMetricsTest {
    @Test fun rowHeightTracksPanelInsteadOfChangingWithCandidateCount() {
        val phone = t9CandidateMetrics(54f, 168f, 1f, 19f, 1f)
        val tablet = t9CandidateMetrics(130f, 300f, 1f, 19f, 1.5f)
        assertEquals(42f, phone.rowHeightDp, 0.01f)
        assertEquals(75f, tablet.rowHeightDp, 0.01f)
        assertTrue(tablet.fontSizeSp > phone.fontSizeSp)
        assertTrue(tablet.pinyinFontSizeSp > phone.pinyinFontSizeSp)
    }
    // 用户 11-15：九键拼音列跟「候选字大小」走，和候选栏的候选字一样大，平板上不再大得离谱。
    @Test fun pinyinMatchesTheCandidateTextAndGrowsLikeTheCandidateBar() {
        assertEquals(19f, t9CandidateMetrics(54f, 168f, 1f, 19f, 1f).pinyinFontSizeSp, 0.01f)
        assertEquals(22f, t9CandidateMetrics(54f, 168f, 1f, 22f, 1f).pinyinFontSizeSp, 0.01f)
        // Keys 1.5x the phone default: the candidate bar is 1.25x, so is the rail.
        val tablet = t9CandidateMetrics(130f, 300f, 1f, 19f, 1.5f)
        assertEquals(19f * 1.25f, tablet.pinyinFontSizeSp, 0.01f)
        assertEquals(15f * 1.25f, tablet.fontSizeSp, 0.01f)
    }
    @Test fun symbolsFollowTheSameSetting() {
        val small = t9CandidateMetrics(54f, 168f, 1f, 15f, 1f)
        val large = t9CandidateMetrics(54f, 168f, 1f, 22f, 1f)
        assertTrue(large.fontSizeSp > small.fontSizeSp)
        assertTrue(large.pinyinFontSizeSp > large.fontSizeSp)
    }
    @Test fun pinyinStillFitsTheRowWithLargeSystemText() {
        val bigText = t9CandidateMetrics(54f, 168f, 2f, 22f, 1f)
        assertTrue(bigText.pinyinFontSizeSp * 2f * 1.4f <= bigText.rowHeightDp - 6f + 0.01f)
    }
    @Test fun shortPanelsScrollInsteadOfCrushingOptions() {
        val small = t9CandidateMetrics(50f, 90f, 1f, 19f, 1f)
        assertEquals(32f, small.rowHeightDp, 0.01f)
    }
    @Test fun largeSystemTextStillFitsTheLongestSyllable() {
        val metrics = t9CandidateMetrics(54f, 168f, 2f, 19f, 1f)
        assertTrue(metrics.fontSizeSp > 0f)
        assertTrue(metrics.fontSizeSp * 2 * 1.4f <= metrics.rowHeightDp - 4f)
    }
}
