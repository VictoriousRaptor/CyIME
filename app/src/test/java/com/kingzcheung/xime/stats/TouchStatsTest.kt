package com.kingzcheung.xime.stats

import com.kingzcheung.xime.stats.TouchStatsData.Count
import com.kingzcheung.xime.stats.TouchStatsData.Outcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 记录入口的开关：公开的包里不记，自用包里跟着「打字统计」和无痕走。 */
class TouchStatsTest {
    private var now = 1_000L
    private var recording = true

    @Before fun setUp() {
        TouchStats.clear()
        TouchStats.clock = { now }
        TouchStats.boardSuffix = { "" }
    }

    @After fun tearDown() {
        TouchStats.gate = null
        TouchStats.clear()
    }

    private fun on() { TouchStats.gate = { recording } }

    /** 按 [TypingStats.onKeyDown] 的做法：没在记时不转给 [TouchStats.keyDown]。 */
    private fun tap(name: String, density: Float = 2f) {
        TouchStats.landing(36f, 54f, 72, 108, density, now)
        if (TouchStats.gate?.invoke() == true) TouchStats.keyDown(name)
        val token = TouchStats.landed()
        now += 80
        TouchStats.finish(token, Outcome.TAP, 0f, 0f, density, now)
        now += 40
    }

    @Test fun nothingIsRecordedInABuildWithoutIt() {
        tap("a")
        TouchStats.pressBegan(1); TouchStats.count(Count.EARLY_SPACE); TouchStats.keyEmit("space")
        TouchStats.dropped("key_not_ready"); TouchStats.keyDown("b"); TouchStats.picked("nu=ni"); TouchStats.committed("kb/zh/pinyin/qwerty")
        assertTrue(TouchStats.snapshot().isEmpty())
        assertEquals(TouchStatsData.UNKNOWN_BOARD, TouchStats.snapshot().lastBoard)
        assertEquals(false, TouchStats.hidden())
        assertNull(TouchStats.takeText())
    }

    @Test fun recordsInDpWhenOn() {
        on()
        tap("a")
        val board = TouchStats.snapshot().boards.getValue(TouchStatsData.UNKNOWN_BOARD)
        assertEquals(1, board.counts[Count.KEY_DOWNS])
        assertEquals(1, board.outcomes[Outcome.TAP])
        val key = board.keys.getValue("a")
        // 72×108 像素、密度 2：键是 36×54dp，落在正中那一格。
        assertEquals(36f, key.maxWidth)
        assertEquals(54.0, key.heightSum, 0.0)
        assertEquals(mapOf(55 to 1L), key.land.counts)
    }

    @Test fun followsTheSwitchAndIncognito() {
        on()
        recording = false
        tap("a")
        TouchStats.pressBegan(0); TouchStats.dropped("key_not_ready"); TouchStats.keyEmit("space")
        assertTrue(TouchStats.snapshot().isEmpty())
        recording = true
        tap("b")
        assertEquals(setOf("b"), TouchStats.snapshot().boards.getValue(TouchStatsData.UNKNOWN_BOARD).keys.keys)
    }

    @Test fun pressThatBeganWhileOffIsNotFinishedLater() {
        on()
        recording = false
        TouchStats.landing(36f, 54f, 72, 108, 2f, now)
        val token = TouchStats.landed()
        recording = true
        TouchStats.early(token)
        TouchStats.finish(token, Outcome.TAP, 0f, 0f, 2f, now)
        assertEquals(0, token)
        assertTrue(TouchStats.snapshot().isEmpty())
    }

    @Test fun boardComesFromSoftKeyboardCommitsWithTheSuffix() {
        on()
        TouchStats.boardSuffix = { "/land" }
        TouchStats.committed("kb/zh/pinyin/qwerty")
        TouchStats.committed("voice")
        TouchStats.committed(null)
        tap("a")
        assertEquals(setOf("kb/zh/pinyin/qwerty/land"), TouchStats.snapshot().boards.keys)
        // 横屏的全拼仍然算全拼。
        assertTrue(TouchStatsRecorder.fullPinyin("kb/zh/pinyin/qwerty/land"))
    }

    @Test fun savingTakesTheTextOnceAndMergeAddsWhatWasStored() {
        on()
        tap("a")
        assertEquals(true, TouchStats.hidden())
        val text = TouchStats.takeText()
        assertNotNull(text)
        assertEquals(false, TouchStats.hidden())
        assertNull(TouchStats.takeText())
        TouchStats.merge(TouchStatsData.decode(text!!))
        val board = TouchStats.snapshot().boards.getValue(TouchStatsData.UNKNOWN_BOARD)
        assertEquals(2, board.counts[Count.KEY_DOWNS])
        assertEquals(2, board.keys.getValue("a").land.total)
    }

    @Test fun hidingTheKeyboardDropsUnfinishedPresses() {
        on()
        TouchStats.landing(36f, 54f, 72, 108, 2f, now); TouchStats.keyDown("a"); val a = TouchStats.landed()
        TouchStats.hidden()
        TouchStats.finish(a, Outcome.TAP, 0f, 0f, 2f, now + 50)
        assertEquals(0, TouchStats.snapshot().boards.getValue(TouchStatsData.UNKNOWN_BOARD).outcomes.sum())
    }

    @Test fun earlyAndOverlapAndDroppedGoThrough() {
        on()
        TouchStats.landing(10f, 10f, 72, 108, 2f, now); TouchStats.keyDown("n"); val n = TouchStats.landed()
        TouchStats.pressBegan(0)
        now += 30
        TouchStats.landing(10f, 10f, 72, 108, 2f, now); TouchStats.keyDown("i"); val i = TouchStats.landed()
        TouchStats.pressBegan(1)
        now += 50
        TouchStats.early(n)
        TouchStats.finish(i, Outcome.TAP, 0f, 0f, 2f, now)
        TouchStats.finish(n, Outcome.TAP_EARLY, 0f, 0f, 2f, now + 30)
        TouchStats.keyDown("space"); TouchStats.count(Count.EARLY_SPACE); TouchStats.keyEmit("space")
        TouchStats.dropped("key_pending_commit")
        val board = TouchStats.snapshot().boards.getValue(TouchStatsData.UNKNOWN_BOARD)
        assertEquals(1, board.outcomes[Outcome.TAP_EARLY])
        assertEquals(1, board.outcomes[Outcome.TAP])
        assertEquals(0, board.inversions.sum())
        assertEquals(listOf(1L, 1L, 0L, 0L), board.overlap.toList())
        assertEquals(1, board.counts[Count.EARLY_SPACE])
        assertEquals(mapOf("key_pending_commit" to 1L), board.dropped)
    }
}
