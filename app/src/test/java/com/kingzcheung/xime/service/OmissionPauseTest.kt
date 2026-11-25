package com.kingzcheung.xime.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmissionPauseTest {
    private fun typed(pause: OmissionPause, gapMs: Long, letters: Int, start: Long = 1_000L): Long {
        var now = start
        for (length in 1..letters) { pause.onComposition(length, now); now += gapMs }
        return now
    }

    /** 打得快的人（每键 100 ms）和没有节奏数据时仍是原来的 180 ms。 */
    @Test fun fastTypingKeepsTheOriginalPause() {
        assertEquals(180L, OmissionPause().delayMs())
        val pause = OmissionPause()
        typed(pause, 100L, 10)
        assertEquals(180L, pause.delayMs())
    }

    /** 每键 260 ms 打字时 180 ms 的停顿几乎每个键都触发补算；按节奏等到 390 ms，打字途中不再补算。 */
    @Test fun slowerTypingWaitsLongerThanItsOwnKeyGap() {
        val pause = OmissionPause()
        typed(pause, 260L, 12)
        assertEquals(390L, pause.delayMs())
        val slow = OmissionPause()
        typed(slow, 900L, 12)
        assertEquals(450L, slow.delayMs())
    }

    /** 两段拼音之间的停顿、删字、整段换掉都不算字母间隔。 */
    @Test fun onlySingleLetterStepsCountAsTypingRhythm() {
        val pause = OmissionPause()
        var now = typed(pause, 300L, 6)
        assertEquals(450L, pause.delayMs())
        pause.onComposition(0, now); now += 50L         // 上屏
        pause.onComposition(1, now); now += 2_000L      // 新拼音第一个字母不算
        pause.onComposition(2, now); now += 50L         // 2 秒的间隔是停顿，不算
        pause.onComposition(1, now); now += 50L         // 退格
        pause.onComposition(5, now); now += 50L         // 粘贴/改拼音
        pause.onComposition(4, now)
        assertEquals(450L, pause.delayMs())
    }

    /** 某个长度的补算连续两次超时，之后这么长和更长的拼音不再补算；短的照常；过了重试时间再试。 */
    @Test fun slowRefineSkipsThisAndLongerInputsForAWhile() {
        val pause = OmissionPause(budgetMs = 100L, retryMs = 60_000L)
        assertTrue(pause.withinBudget(40, 0L))
        pause.onRefined(40, 400L, 9_000L)
        pause.onRefined(42, 400L, 10_000L)
        assertFalse(pause.withinBudget(40, 10_001L))
        assertFalse(pause.withinBudget(60, 10_001L))
        assertTrue(pause.withinBudget(20, 10_001L))
        assertTrue(pause.withinBudget(40, 70_001L))
    }

    /** 偶尔一次超时不停：同样长度下一次不慢就当没发生过，中间隔着一次不慢的两次超时不算连续。 */
    @Test fun aSingleSlowRefineDoesNotSkip() {
        val pause = OmissionPause()
        pause.onRefined(34, 124L, 1_000L)
        assertTrue(pause.withinBudget(34, 1_001L))
        assertTrue(pause.withinBudget(60, 1_001L))
        pause.onRefined(36, 28L, 2_000L)
        pause.onRefined(33, 110L, 3_000L)
        assertTrue(pause.withinBudget(34, 3_001L))
    }

    /** 各档各算各的：平板实测的四次超时（102～128 ms）长度都不在同一档，一次也不该停。 */
    @Test fun slowRefinesOfDifferentLengthsDoNotAddUp() {
        val pause = OmissionPause()
        pause.onRefined(34, 124L, 1_000L)
        pause.onRefined(28, 110L, 2_000L)
        pause.onRefined(13, 102L, 3_000L)
        pause.onRefined(41, 128L, 4_000L)
        for (length in 4..80) assertTrue(pause.withinBudget(length, 4_001L))
    }

    /** 快的机器测不到超时，什么都不跳过。 */
    @Test fun fastRefinesNeverSkip() {
        val pause = OmissionPause()
        for (length in 4..80) pause.onRefined(length, 30L, length.toLong())
        for (length in 4..80) assertTrue(pause.withinBudget(length, 100L))
    }

    /** 过了重试时间再测得不慢，就恢复补算，之后要再连续两次超时才停。 */
    @Test fun aLaterFastRefineClearsTheBucket() {
        val pause = OmissionPause(retryMs = 60_000L)
        pause.onRefined(33, 300L, 5L)
        pause.onRefined(33, 300L, 6L)
        assertFalse(pause.withinBudget(33, 7L))
        pause.onRefined(35, 20L, 60_007L)
        assertTrue(pause.withinBudget(33, 60_008L))
        pause.onRefined(33, 300L, 60_009L)
        assertTrue(pause.withinBudget(33, 60_010L))
    }

    /** 过了重试时间再测还是慢，马上接着停，不用再等第二次（真慢的机器每个重试时间只多等一次）。 */
    @Test fun aStillSlowRefineAfterTheRetrySkipsAgainAtOnce() {
        val pause = OmissionPause(retryMs = 60_000L)
        pause.onRefined(33, 300L, 5L)
        pause.onRefined(33, 300L, 6L)
        assertTrue(pause.withinBudget(33, 60_006L))
        pause.onRefined(33, 300L, 60_007L)
        assertFalse(pause.withinBudget(33, 60_008L))
        assertFalse(pause.withinBudget(33, 120_006L))
        assertTrue(pause.withinBudget(33, 120_007L))
    }

    /** 停顿后要不要让引擎补查：全拼看漏键、顺序两个开关；双拼少打一个键跟漏键，多按一下、打反要先开着邻键纠错。 */
    @Test fun pauseLookupFollowsTheTypoSwitches() {
        val omission = com.kingzcheung.xime.settings.SpellingPatches.Typo.OMISSION
        val swap = com.kingzcheung.xime.settings.SpellingPatches.Typo.TRANSPOSITION
        val extra = com.kingzcheung.xime.settings.SpellingPatches.Typo.EXTRA
        fun wanted(doublePinyin: Boolean, neighbor: Boolean, vararg on: com.kingzcheung.xime.settings.SpellingPatches.Typo) =
            pauseLookupWanted(doublePinyin, neighbor) { it in on }
        // 26 键全拼和原来一样：漏键、顺序有一个开着就查，多键不在停顿后查。
        assertTrue(wanted(false, false, omission))
        assertTrue(wanted(false, false, swap))
        assertFalse(wanted(false, true, extra))
        assertFalse(wanted(false, true))
        // 双拼：漏键不看邻键纠错；多键、顺序在邻键纠错关着时不起作用。
        assertTrue(wanted(true, false, omission))
        assertTrue(wanted(true, true, extra))
        assertTrue(wanted(true, true, swap))
        assertFalse(wanted(true, false, extra, swap))
        assertFalse(wanted(true, true))
    }

    /** 14 键（11-20）停顿纠错查合并键的大写代表字母和 l、m；26 键照旧只查小写。 */
    @Test fun mergedKeysInputIsLookedUpOnlyOn14Keys() {
        assertTrue(pauseInputLetters("GAOlAO", merged14 = true))
        assertTrue(pauseInputLetters("BUGAOmA", merged14 = true))
        assertTrue(pauseInputLetters("nihao", merged14 = false))
        assertFalse(pauseInputLetters("GAOlAO", merged14 = false))
        // 选过拼音后留下的精确字母（小写 a、s 等）纠错索引不认。
        assertFalse(pauseInputLetters("BUGaO", merged14 = true))
        assertFalse(pauseInputLetters("GAO1", merged14 = true))
        // 只有合并键的代表字母算：别的大写（26 键里 Shift 打的大写）不算。
        assertFalse(pauseInputLetters("GAOW", merged14 = true))
    }
}
