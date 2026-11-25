package com.kingzcheung.xime.stats

import com.kingzcheung.xime.stats.TouchStatsData.Count
import com.kingzcheung.xime.stats.TouchStatsData.Fate
import com.kingzcheung.xime.stats.TouchStatsData.Kind
import com.kingzcheung.xime.stats.TouchStatsData.Outcome
import com.kingzcheung.xime.stats.TouchStatsData.UpSide
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 按键落点统计的记录逻辑：只看记下来的计数，对不对得上按键的经过。 */
class TouchStatsRecorderTest {
    private val r = TouchStatsRecorder()
    private var now = 10_000L
    private val b get() = r.data.board(r.data.lastBoard)

    /** 键宽 36dp、高 54dp；落点默认在正中（第 5 行第 5 列，格 55）。 */
    private fun down(name: String, x: Float = 18f, y: Float = 27f): Long {
        r.landing(x, y, 36f, 54f, now, now + 3)
        r.keyDown(name, now + 3)
        return r.landed()
    }

    private fun finish(token: Long, code: Int = Outcome.TAP, dx: Float = 0f, dy: Float = 0f, held: Long = 80) {
        now += held
        r.finish(token, code, dx, dy, now, now + 2)
        now += 40
    }

    private fun tap(name: String, x: Float = 18f, y: Float = 27f) = finish(down(name, x, y))

    /** 自己管手势的键：按下，过一会儿出字。 */
    private fun plain(name: String) {
        r.keyDown(name, now)
        now += 60
        r.keyEmit(name, now)
        now += 40
    }

    /** 空格、删除这类键带落点的一下（不跟手势）：键宽 144dp、高 54dp。 */
    private fun plainAt(name: String, x: Float, y: Float) {
        r.landing(x, y, 144f, 54f, now, now + 3, tracked = false)
        r.keyDown(name, now + 3)
        assertEquals(0L, r.landed())
        now += 60
        r.keyEmit(name, now)
        now += 40
    }

    private fun pinyin() = r.committed("kb/zh/pinyin/qwerty")

    @Test fun tapIsCountedInTheCellItLandedIn() {
        tap("q", x = 3.5f, y = 50f)
        assertEquals(1, b.counts[Count.KEY_DOWNS])
        assertEquals(1, b.counts[Count.CHAR_PRESSES])
        assertEquals(1, b.outcomes[Outcome.TAP])
        val key = b.keys.getValue("q")
        assertEquals(mapOf(9 * 10 + 0 to 1L), key.land.counts)
        assertEquals(1, key.sized)
        assertEquals(36.0, key.widthSum, 0.0)
        assertEquals(54.0, key.heightSum, 0.0)
        assertEquals(1, b.upSide[UpSide.INSIDE])
        assertEquals(1, b.lagDown[0])
        assertEquals(1, b.lagUp[0])
    }

    @Test fun pressWithoutOutputIsCountedWithItsReason() {
        finish(down("a"), Outcome.NONE_FAR, dx = 70f)
        finish(down("s", x = 2f, y = 2f), Outcome.NONE_TAKEN, dx = 2f)
        assertEquals(1, b.outcomes[Outcome.NONE_FAR])
        assertEquals(1, b.outcomes[Outcome.NONE_TAKEN])
        assertEquals(0, b.outcomes[Outcome.TAP])
        assertEquals(mapOf(55 to 1L), b.keys.getValue("a").lost.counts)
        assertEquals(mapOf(0 to 1L), b.keys.getValue("s").lost.counts)
        assertTrue(b.keys.getValue("a").land.counts.isEmpty())
        // 只有时间短、几乎没动的那一下算「像点按」。
        assertEquals(1, b.counts[Count.LOST_TAPS])
        assertEquals(2, b.driftNone.total)
    }

    @Test fun deleteRightAfterATapIsARegretAndTheNextLetterPairsWithIt() {
        tap("n")
        tap("i", x = 34f, y = 27f)
        plain("delete")
        assertEquals(1, b.counts[Count.DELETES])
        assertEquals(1, b.counts[Count.REGRETS])
        assertEquals(mapOf(59 to 1L), b.keys.getValue("i").regret.counts)
        assertTrue(b.keys.getValue("n").regret.counts.isEmpty())
        tap("o")
        assertEquals(1, b.counts[Count.RETYPED])
        assertEquals(mapOf(59 to 1L), b.retyped.getValue("i>o").counts)
        assertEquals(0, b.counts[Count.RETYPED_SAME])
    }

    @Test fun sameKeyRetypedIsNotAPair() {
        tap("a"); plain("delete"); tap("a")
        assertEquals(1, b.counts[Count.RETYPED_SAME])
        assertEquals(0, b.counts[Count.RETYPED])
        assertTrue(b.retyped.isEmpty())
    }

    @Test fun twoDeletesPairEachPositionWithWhatReplacedIt() {
        tap("a"); tap("b", x = 1f, y = 1f); tap("c")
        plain("delete"); plain("delete")
        tap("x"); tap("c")
        // 只有紧跟在点按后面的那一次删除算「后悔」。
        assertEquals(1, b.counts[Count.REGRETS])
        assertEquals(mapOf("b>x" to mapOf(0 to 1L)), b.retyped.mapValues { it.value.counts })
        assertEquals(1, b.counts[Count.RETYPED])
        assertEquals(1, b.counts[Count.RETYPED_SAME])
        // b 不是紧接着被删的，改成了别的键：落点图上另记（紧接着删的 c 在 regret 里，不重复）。
        assertEquals(mapOf(0 to 1L), b.keys.getValue("b").changed.counts)
        assertTrue(b.keys.getValue("b").regret.counts.isEmpty())
        assertTrue(b.keys.getValue("c").changed.counts.isEmpty())
    }

    @Test fun regrettedTapRetypedAsAnotherKeyIsNotCountedTwiceOnTheMap() {
        tap("i", x = 34f, y = 27f); plain("delete"); tap("o")
        assertEquals(mapOf(59 to 1L), b.keys.getValue("i").regret.counts)
        assertTrue(b.keys.getValue("i").changed.counts.isEmpty())
    }

    @Test fun deleteAfterSpaceIsNotARegret() {
        tap("a"); plain("space"); plain("delete"); tap("b")
        assertEquals(0, b.counts[Count.REGRETS])
        assertEquals(0, b.counts[Count.RETYPED])
        assertTrue(b.retyped.isEmpty())
        // 它算空格的：空格出字以后紧接着删。
        assertEquals(1, b.counts[Count.SPACE_REGRETS])
    }

    @Test fun spaceLandingIsCountedWhenItEmitsAndMarkedWhenDeletedRightAfter() {
        plainAt("space", x = 100f, y = 2f)
        plainAt("space", x = 20f, y = 30f)
        plain("delete")
        val space = b.keys.getValue("space")
        // 144dp 宽的键上 100dp 是第 6 列，20dp 是第 1 列。
        assertEquals(mapOf(0 * 10 + 6 to 1L, 5 * 10 + 1 to 1L), space.land.counts)
        assertEquals(mapOf(5 * 10 + 1 to 1L), space.regret.counts)
        assertEquals(1, b.counts[Count.SPACE_REGRETS])
        assertEquals(2, space.sized)
        assertEquals(144f, space.maxWidth)
        // 空格、删除不算有落点的字母类按下。
        assertEquals(0, b.counts[Count.CHAR_PRESSES])
        assertEquals(0, b.outcomes.sum())
    }

    @Test fun spaceIsNotRegrettedWhenAnotherKeyCameBetween() {
        plainAt("space", x = 100f, y = 2f)
        tap("a"); plain("delete")
        assertEquals(0, b.counts[Count.SPACE_REGRETS])
        assertTrue(b.keys.getValue("space").regret.counts.isEmpty())
        assertEquals(1, b.counts[Count.REGRETS])
    }

    @Test fun heldDeleteAfterSpaceStillCountsTheSpace() {
        plainAt("space", x = 100f, y = 2f)
        r.keyDown("delete", now)          // 按住没抬，连删起来：每删一下再报一次按下
        now += 400
        r.keyDown("delete", now); r.keyEmit("delete", now)
        now += 50
        r.keyDown("delete", now); r.keyEmit("delete", now)
        assertEquals(1, b.counts[Count.SPACE_REGRETS])
        assertEquals(mapOf(6 to 1L), b.keys.getValue("space").regret.counts)
    }

    @Test fun deleteLandingIsCountedWhenItEmits() {
        tap("m")
        plainAt("delete", x = 3f, y = 27f)
        assertEquals(mapOf(5 * 10 + 0 to 1L), b.keys.getValue("delete").land.counts)
        assertEquals(1, b.counts[Count.REGRETS])
    }

    @Test fun letterDeletedThenSpaceIsPairedWithSpace() {
        tap("h"); tap("a"); tap("o"); tap("n", x = 18f, y = 52f)
        plain("delete")
        plainAt("space", x = 70f, y = 3f)
        assertEquals(1, b.counts[Count.RETYPED])
        assertEquals(mapOf("n>space" to mapOf(9 * 10 + 5 to 1L)), b.retyped.mapValues { it.value.counts })
        assertEquals(mapOf(95 to 1L), b.keys.getValue("n").regret.counts)
    }

    @Test fun twoLettersDeletedThenSpaceIsNotPaired() {
        tap("h"); tap("a"); tap("o")
        plain("delete"); plain("delete"); plain("space")
        assertEquals(0, b.counts[Count.RETYPED])
        assertTrue(b.retyped.isEmpty())
    }

    @Test fun pairKeepsWhereTheFingerLifted() {
        // 落在 p 的左沿，抬手时已经滑出了左边（到了 o 上）。
        finish(down("p", x = 2f, y = 27f), dx = -6f)
        plain("delete"); tap("o")
        assertEquals(mapOf("p>o" to mapOf(TouchStatsData.pairIndex(50, UpSide.LEFT) to 1L)), b.retyped.mapValues { it.value.counts })
        assertEquals(UpSide.LEFT * 100 + 50, TouchStatsData.pairIndex(50, UpSide.LEFT))
        assertEquals(50, TouchStatsData.pairIndex(50, UpSide.INSIDE))
    }

    @Test fun fateSplitsLetterTapsByOverlapAndTimeSinceThePreviousKey() {
        // 键盘出来后的第一下算「更久」；第二下离上一次按下 120 毫秒（80 按着 + 40 间隔）。
        tap("n"); tap("i")
        // 两指交叠：按着 h 又按 a。
        val h = down("h"); r.pressBegan(0)
        now += 30
        val a = down("a", x = 35f); r.pressBegan(1)
        finish(h); finish(a)
        plain("delete"); tap("s")
        val last = TouchStatsData.GAP_BINS - 1
        val expected = mapOf(
            TouchStatsData.fateIndex(Fate.TAP, false, last) to 1L,          // n
            TouchStatsData.fateIndex(Fate.TAP, false, 4) to 2L,             // i：120 毫秒；s：离删除键按下 100 毫秒
            TouchStatsData.fateIndex(Fate.TAP, true, 4) to 1L,              // h：也是 120 毫秒，被 a 交叠
            TouchStatsData.fateIndex(Fate.TAP, true, 1) to 1L,              // a：按下离 h 按下 30 毫秒
            TouchStatsData.fateIndex(Fate.REGRET, true, 1) to 1L,           // a 紧接着被删
            TouchStatsData.fateIndex(Fate.CHANGED, true, 1) to 1L,          // a 改成了 s
        )
        assertEquals(expected, b.fate.counts)
    }

    @Test fun earlyTapIsOverlapped() {
        val n = down("n"); r.pressBegan(0)
        now += 50
        val i = down("i"); r.pressBegan(1)
        r.early(n, now + 30)
        finish(i); finish(n, Outcome.TAP_EARLY)
        assertEquals(2, (0 until TouchStatsData.GAP_BINS).sumOf { b.fate[TouchStatsData.fateIndex(Fate.TAP, true, it)] })
        assertEquals(0, (0 until TouchStatsData.GAP_BINS).sumOf { b.fate[TouchStatsData.fateIndex(Fate.TAP, false, it)] })
    }

    @Test fun correctedPickIsAChangedTapToo() {
        pinyin()
        tap("n"); tap("i"); tap("j", x = 1f, y = 27f); tap("a"); tap("o")
        r.picked("ni jao=hao")
        assertEquals(1, (0 until TouchStatsData.GAP_BINS).sumOf { b.fate[TouchStatsData.fateIndex(Fate.CHANGED, false, it)] })
        // 选纠错候选改的看 corrected，不进落点图的 changed。
        assertTrue(b.keys.getValue("j").changed.counts.isEmpty())
    }

    @Test fun correctedPickPointsAtTheWrongTap() {
        pinyin()
        tap("n"); tap("i"); tap("j", x = 1f, y = 27f); tap("a"); tap("o")
        r.picked("ni jao=hao")
        assertEquals(1, b.counts[Count.CORRECTED_PICKS])
        assertEquals(0, b.counts[Count.CORRECTED_UNMATCHED])
        assertEquals(mapOf("j>h" to mapOf(50 to 1L)), b.corrected.mapValues { it.value.counts })
    }

    @Test fun extraLetterAndSwapInACorrectedPick() {
        pinyin()
        tap("n"); tap("i"); tap("h"); tap("a", x = 1f, y = 1f); tap("a", x = 35f, y = 53f); tap("o")
        r.picked("ni haao=hao")
        // 多打的是前一个 a（和候选栏画删除线的那个一致）。
        assertEquals(mapOf("a>" to mapOf(0 to 1L)), b.corrected.mapValues { it.value.counts })
        tap("n"); tap("i"); tap("h"); tap("o"); tap("a")
        r.picked("ni hoa=hao")
        assertEquals(2, b.counts[Count.CORRECTED_PICKS])
        assertEquals(1, b.counts[Count.SWAPS])
        assertEquals(0, b.counts[Count.SWAPS_FROM_ORDER])
    }

    @Test fun partlyPickedCompositionKeepsTheRestForTheNextPick() {
        pinyin()
        tap("n"); tap("u"); tap("h"); tap("s", x = 35f, y = 27f); tap("o")
        r.picked("nu=ni")
        r.picked("hso=hao")
        assertEquals(2, b.counts[Count.CORRECTED_PICKS])
        assertEquals(setOf("u>i", "s>a"), b.corrected.keys)
        assertEquals(mapOf(59 to 1L), b.corrected.getValue("s>a").counts)
    }

    @Test fun pressReleasedAfterALaterKeyIsAnInversionAndMarksTheSwap() {
        pinyin()
        tap("n"); tap("i"); tap("h")
        val a = down("a")
        now += 30
        val o = down("o")
        finish(o)
        finish(a)
        assertEquals(1, b.inversions[Kind.CHAR * Kind.SIZE + Kind.CHAR])
        assertEquals(1, b.inversions.sum())
        r.picked("ni hoa=hao")
        assertEquals(1, b.counts[Count.SWAPS])
        assertEquals(1, b.counts[Count.SWAPS_FROM_ORDER])
    }

    @Test fun earlyTapKeepsPressOrder() {
        val n = down("n")
        now += 30
        val i = down("i", x = 34f, y = 27f)
        now += 60
        r.early(n, now)
        finish(i, held = 0)
        finish(n, Outcome.TAP_EARLY)
        assertEquals(1, b.outcomes[Outcome.TAP_EARLY])
        assertEquals(1, b.outcomes[Outcome.TAP])
        assertEquals(0, b.inversions.sum())
        assertEquals(1, b.keys.getValue("n").land.total)
        // 最后出字的是 i：紧接着的删除记在 i 上。
        plain("delete")
        assertEquals(mapOf(59 to 1L), b.keys.getValue("i").regret.counts)
        assertTrue(b.keys.getValue("n").regret.counts.isEmpty())
    }

    @Test fun spaceEmittedAfterALaterLetterIsAnInversion() {
        r.keyDown("space", now)
        now += 30
        val a = down("a")
        finish(a)
        r.keyEmit("space", now)
        assertEquals(1, b.inversions[Kind.SPACE * Kind.SIZE + Kind.CHAR])
        assertEquals(1, b.inversions.sum())
    }

    @Test fun keysInPressOrderAreNoInversion() {
        tap("a"); plain("space"); tap("b"); plain("delete"); plain("enter"); tap("c")
        assertEquals(0, b.inversions.sum())
        assertEquals(6, b.counts[Count.KEY_DOWNS])
        assertEquals(3, b.counts[Count.CHAR_PRESSES])
    }

    @Test fun overlapComesFromTheOrderQueue() {
        r.pressBegan(0); r.pressBegan(1); r.pressBegan(0); r.pressBegan(7)
        assertArrayEquals(longArrayOf(2, 1, 0, 1), b.overlap)
        r.count(Count.EARLY_SPACE); r.count(Count.EARLY_REPEAT); r.count(Count.EARLY_REPEAT); r.count(999)
        assertEquals(1, b.counts[Count.EARLY_SPACE])
        assertEquals(2, b.counts[Count.EARLY_REPEAT])
    }

    @Test fun samKeyPressedAgainSoonAndNearby() {
        val first = down("u", x = 10f, y = 20f)
        now += 80
        r.finish(first, Outcome.TAP, 2f, 0f, now, now)
        now += 30
        tap("u", x = 14f, y = 21f)      // 抬手点 (12, 20) 旁边 2dp 多
        now = now - 40 + 500
        tap("u", x = 34f, y = 50f)      // 隔得久，也离得远
        assertEquals(1, b.sameGap[3])
        assertEquals(1, b.sameGapNear[3])
        assertEquals(1, b.sameGap[TouchStatsData.SAME_BINS - 1])
        assertEquals(1, b.sameGapNear.sum())
    }

    @Test fun deleteThatDidNotDeleteOneLetterStopsThePairing() {
        tap("a"); tap("b")
        r.keyDown("delete", now)      // 拖动删除：没有照常出一次删除
        now += 400
        tap("c")
        assertEquals(1, b.counts[Count.DELETE_OTHER])
        plain("delete")
        assertEquals(1, b.counts[Count.REGRETS])
        tap("d")
        assertEquals(setOf("c>d"), b.retyped.keys)
    }

    @Test fun repeatingDeleteStopsThePairing() {
        tap("a"); tap("b"); tap("c")
        r.keyDown("delete", now)
        now += 60
        r.keyEmit("delete", now)
        now += 400
        r.keyEmit("delete", now)      // 长按连删的第二下
        now += 100
        tap("x")
        assertEquals(1, b.counts[Count.REGRETS])
        assertEquals(1, b.counts[Count.DELETE_OTHER])
        assertEquals(0, b.counts[Count.RETYPED])
        assertTrue(b.retyped.isEmpty())
    }

    @Test fun longPressOrSwipeIsNotATypedLetter() {
        tap("n")
        finish(down("i"), Outcome.SWIPE, dy = -60f)
        plain("delete")
        tap("o")
        assertEquals(1, b.outcomes[Outcome.SWIPE])
        assertEquals(0, b.counts[Count.REGRETS])
        assertEquals(0, b.counts[Count.RETYPED])
        assertTrue(b.keys.getValue("i").land.counts.isEmpty())
    }

    @Test fun correctedPickOutsideFullPinyinIsNotAttributed() {
        r.committed("kb/zh/double_pinyin/qwerty")
        tap("n"); tap("u")
        r.picked("nu=ni")
        assertEquals(0, b.counts[Count.CORRECTED_PICKS])
        assertEquals(1, b.counts[Count.CORRECTED_UNMATCHED])
        assertTrue(b.corrected.isEmpty())
    }

    @Test fun correctedPickThatDoesNotMatchTheTapsIsNotAttributed() {
        pinyin()
        tap("n"); tap("i")
        r.picked("hso=hao")
        assertEquals(1, b.counts[Count.CORRECTED_UNMATCHED])
        assertTrue(b.corrected.isEmpty())
    }

    @Test fun plainPickForgetsTheTaps() {
        pinyin()
        tap("n"); tap("u")
        r.picked("")
        r.picked("nu=ni")
        assertEquals(1, b.counts[Count.CORRECTED_UNMATCHED])
        plain("delete"); tap("x")
        assertEquals(0, b.counts[Count.RETYPED])
    }

    @Test fun onlySoftKeyboardCommitsChooseTheBoard() {
        tap("a")
        r.committed("voice"); r.committed("hw/en"); r.committed(null)
        assertEquals(TouchStatsData.UNKNOWN_BOARD, r.data.lastBoard)
        r.committed("kb/en/direct/qwerty")
        tap("b"); tap("c")
        assertEquals(1, r.data.board(TouchStatsData.UNKNOWN_BOARD).counts[Count.KEY_DOWNS])
        assertEquals(2, r.data.board("kb/en/direct/qwerty").counts[Count.KEY_DOWNS])
    }

    @Test fun whereTheFingerLiftedRelativeToTheKey() {
        finish(down("a", x = 30f, y = 27f), dx = 10f)       // 抬手在 x=40，出了右边
        finish(down("a", x = 18f, y = 4f), dy = -9f)        // 出了上边
        finish(down("a", x = 18f, y = 27f), dx = 3f, dy = 3f)
        assertEquals(1, b.upSide[UpSide.RIGHT])
        assertEquals(1, b.upSide[UpSide.ABOVE])
        assertEquals(1, b.upSide[UpSide.INSIDE])
        assertEquals(3, b.driftTap.total)
        assertEquals(1, b.driftTap[TouchStatsData.driftCell(10f, 0f)])
    }

    @Test fun droppedInputIsCountedByReason() {
        r.dropped("key_not_ready"); r.dropped("key_not_ready"); r.dropped("touch_before_ready")
        assertEquals(mapOf("key_not_ready" to 2L, "touch_before_ready" to 1L), b.dropped)
    }

    @Test fun landingWithoutAKeyNameIsNotRecorded() {
        r.landing(1f, 1f, 36f, 54f, now, now)
        assertEquals(0, r.landed())
        r.finish(0, Outcome.TAP, 0f, 0f, now, now)
        r.early(0, now)
        assertTrue(r.data.isEmpty())
        // 落点没被取走时，不能算到下一个没有落点的键头上。
        r.keyDown("space", now)
        assertEquals(0, b.counts[Count.CHAR_PRESSES])
        assertEquals(1, b.counts[Count.KEY_DOWNS])
    }

    @Test fun timeBetweenKeyDowns() {
        tap("a")                // 按下后 80 抬，再过 40
        tap("b")
        now += 5_000
        tap("c")
        assertEquals(1, b.gap[(120 / TouchStatsData.GAP_STEP_MS).toInt()])
        assertEquals(1, b.gap[TouchStatsData.GAP_BINS - 1])
        assertEquals(2, b.gap.sum())
    }

    @Test fun emitLongAfterThePressIsNotMatched() {
        r.keyDown("space", now)             // 长按空格（语音、移光标），没有出空格
        now += 500
        tap("a")
        now += 4_000
        r.keyEmit("space", now)             // 别处来的空格
        assertEquals(0, b.inversions.sum())
    }

    @Test fun travelAndTimePerOutcome() {
        finish(down("a"), Outcome.NONE_DRIFT, dx = 30f, dy = 40f, held = 130)
        val index = TouchStatsData.bin(50f, TouchStatsData.TRAVEL_STEP_DP, TouchStatsData.TRAVEL_BINS) * TouchStatsData.TIME_BINS +
            TouchStatsData.bin(130L, TouchStatsData.TIME_STEP_MS, TouchStatsData.TIME_BINS)
        assertEquals(mapOf(index to 1L), b.travelTime[Outcome.NONE_DRIFT].counts)
        assertTrue(b.travelTime[Outcome.TAP].counts.isEmpty())
    }

    @Test fun resetDropsUnfinishedPresses() {
        val a = down("a")
        tap("n")
        r.reset()
        r.finish(a, Outcome.TAP, 0f, 0f, now, now)
        plain("delete")
        assertEquals(1, b.outcomes[Outcome.TAP])
        assertEquals(0, b.counts[Count.REGRETS])
        assertNull(b.keys["a"]?.land?.counts?.get(55))
    }

    @Test fun keyNamesAreShortBuiltInNames() {
        assertEquals("q", TouchStatsRecorder.keyName("Q"))
        assertEquals("ui", TouchStatsRecorder.keyName("ui"))
        assertEquals("space", TouchStatsRecorder.keyName("space"))
        assertEquals(TouchStatsData.OTHER_KEY, TouchStatsRecorder.keyName("select_reset"))
        assertEquals(TouchStatsData.OTHER_KEY, TouchStatsRecorder.keyName(""))
        assertTrue(TouchStatsRecorder.fullPinyin("kb/zh/pinyin/qwerty"))
        assertTrue(!TouchStatsRecorder.fullPinyin("kb/zh/double_pinyin/qwerty"))
        assertTrue(!TouchStatsRecorder.fullPinyin("kb/en/direct/qwerty"))
        assertTrue(!TouchStatsRecorder.fullPinyin("?"))
    }
}
