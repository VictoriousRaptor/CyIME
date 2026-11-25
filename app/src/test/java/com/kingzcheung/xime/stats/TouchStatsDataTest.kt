package com.kingzcheung.xime.stats

import com.kingzcheung.xime.stats.TouchStatsData.Count
import com.kingzcheung.xime.stats.TouchStatsData.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchStatsDataTest {
    private fun sample(): TouchStatsData {
        val r = TouchStatsRecorder()
        var now = 5_000L
        fun tap(name: String, x: Float, y: Float, code: Int = Outcome.TAP) {
            r.landing(x, y, 36f, 54f, now, now + 5)
            r.keyDown(name, now + 5)
            val token = r.landed()
            now += 90
            r.finish(token, code, 3f, -2f, now, now + 9)
            now += 50
        }
        r.committed("kb/zh/pinyin/qwerty")
        tap("n", 5f, 5f); tap("u", 30f, 40f); tap("=", 1f, 1f); tap("/", 2f, 2f, Outcome.NONE_FAR)
        r.keyDown("delete", now); r.keyEmit("delete", now + 10); now += 100
        tap("h", 18f, 27f); tap("s", 35f, 27f)
        r.keyDown("delete", now); r.keyEmit("delete", now + 10); now += 100
        tap("a", 3f, 27f); tap("o", 18f, 27f)
        r.picked("ha=he o")
        // 删两下改前一个字母（x 不是紧接着被删的），再带落点按一下空格、紧接着删掉。
        tap("x", 4f, 4f); tap("y", 5f, 5f)
        r.keyDown("delete", now); r.keyEmit("delete", now + 10); now += 100
        r.keyDown("delete", now); r.keyEmit("delete", now + 10); now += 100
        tap("z", 6f, 6f)
        r.landing(70f, 3f, 144f, 54f, now, now + 2, tracked = false); r.keyDown("space", now + 2); r.landed(); r.keyEmit("space", now + 60); now += 100
        r.keyDown("delete", now); r.keyEmit("delete", now + 10); now += 100
        r.pressBegan(0); r.pressBegan(2)
        r.count(Count.EARLY_SPACE)
        r.dropped("key_not_ready")
        r.committed("kb/en/direct/qwerty")
        tap("q", 1f, 53f)
        return r.data
    }

    @Test fun encodeDecodeRoundTrip() {
        val data = sample()
        val text = data.encode()
        val back = TouchStatsData.decode(text)
        assertEquals(text, back.encode())
        assertEquals("kb/en/direct/qwerty", back.lastBoard)
        val zh = back.boards.getValue("kb/zh/pinyin/qwerty")
        assertEquals(setOf("s>a", "x>z"), zh.retyped.keys)
        assertEquals(1, zh.keys.getValue("x").changed.total)
        assertEquals(1, zh.keys.getValue("space").regret.total)
        assertEquals(1, zh.counts[Count.SPACE_REGRETS])
        assertEquals(data.boards.getValue("kb/zh/pinyin/qwerty").fate.counts, zh.fate.counts)
        assertTrue(zh.fate.total >= 8)
        assertEquals(setOf("a>e"), zh.corrected.keys)
        assertEquals(1L, zh.dropped["key_not_ready"])
        assertEquals(1, zh.counts[Count.EARLY_SPACE])
        // 键名里的等号、斜杠不破坏文件的一行一项。
        assertTrue("=" in zh.keys && "/" in zh.keys)
        assertEquals(36f, zh.keys.getValue("n").minWidth)
        assertEquals(36f, zh.keys.getValue("n").maxWidth)
    }

    @Test fun storedTextHoldsOnlyCountsAndBuiltInNames() {
        val text = sample().encode()
        // 每一行都是「名字=数字、冒号、逗号、小数点」，名字只有键名、输入方式和固定的几个词。
        for (line in text.lines().filter { it.isNotEmpty() }) {
            val eq = line.indexOf('=')
            assertTrue(line, eq > 0)
            val name = line.substring(0, eq)
            val value = line.substring(eq + 1)
            if (name == "last" || name == "b") continue
            assertTrue(line, value.all { it.isDigit() || it in ":,.-E" })
        }
    }

    @Test fun plusAddsCountsAndKeepsTheNewerBoard() {
        val a = sample()
        val b = sample()
        val sum = a.plus(b)
        val one = a.boards.getValue("kb/zh/pinyin/qwerty")
        val two = sum.boards.getValue("kb/zh/pinyin/qwerty")
        assertEquals(one.counts[Count.KEY_DOWNS] * 2, two.counts[Count.KEY_DOWNS])
        assertEquals(one.outcomes[Outcome.TAP] * 2, two.outcomes[Outcome.TAP])
        assertEquals(one.keys.getValue("n").land.total * 2, two.keys.getValue("n").land.total)
        assertEquals(2, two.retyped.getValue("s>a").total)
        assertEquals(2L, two.dropped["key_not_ready"])
        assertEquals(one.keys.getValue("n").sized * 2, two.keys.getValue("n").sized)
        // 相加不改原来的两份。
        assertEquals(1, one.retyped.getValue("s>a").total)
        val fresh = TouchStatsData()
        assertEquals("kb/en/direct/qwerty", fresh.plus(a).lastBoard)
        assertEquals("kb/en/direct/qwerty", a.plus(fresh).lastBoard)
    }

    @Test fun cellsAndBins() {
        assertEquals(0, TouchStatsData.cell(0f, 0f))
        assertEquals(99, TouchStatsData.cell(1f, 1f))
        assertEquals(99, TouchStatsData.cell(3f, 9f))
        assertEquals(0, TouchStatsData.cell(-1f, -0.2f))
        assertEquals(5 * 10 + 4, TouchStatsData.cell(0.49f, 0.5f))
        val centre = (TouchStatsData.DRIFT_SIDE / 2) * TouchStatsData.DRIFT_SIDE + TouchStatsData.DRIFT_SIDE / 2
        assertEquals(centre, TouchStatsData.driftCell(0f, 0f))
        assertEquals(centre + 1, TouchStatsData.driftCell(4f, 1f))
        assertEquals(centre - TouchStatsData.DRIFT_SIDE, TouchStatsData.driftCell(0f, -4f))
        assertEquals(TouchStatsData.DRIFT_SIDE * TouchStatsData.DRIFT_SIDE - 1, TouchStatsData.driftCell(900f, 900f))
        assertEquals(0, TouchStatsData.driftCell(-900f, -900f))
        assertEquals(0, TouchStatsData.bin(-5L, 25L, 10))
        assertEquals(9, TouchStatsData.bin(100_000L, 25L, 10))
        assertEquals(3, TouchStatsData.bin(99L, 25L, 10))
    }

    @Test fun brokenTextIsSkippedLineByLine() {
        val text = "garbage\nv=1\nlast=kb/zh/pinyin/qwerty\ncounts=5\nb=kb/zh/pinyin/qwerty\ncounts=7,x,3\n" +
            "k/q/land=4:2,bad,9:x,-1:5,7:0\nk/q/size=2,72.0,108.0,36.0,36.0\nk/q/unknown=1\ntt/999=1:1\ntt/1=3:4\nwhat=ever\n"
        val data = TouchStatsData.decode(text)
        val board = data.boards.getValue("kb/zh/pinyin/qwerty")
        assertEquals(7, board.counts[0])
        assertEquals(0, board.counts[1])
        assertEquals(3, board.counts[2])
        assertEquals(mapOf(4 to 2L), board.keys.getValue("q").land.counts)
        assertEquals(2, board.keys.getValue("q").sized)
        assertEquals(mapOf(3 to 4L), board.travelTime[1].counts)
        assertTrue(TouchStatsData.decode("").isEmpty())
        assertTrue(TouchStatsData.decode("\u0000\u0001===").isEmpty())
    }

    @Test fun namesAreBounded() {
        val board = TouchStatsData.Board("kb/zh/pinyin/qwerty")
        repeat(TouchStatsData.MAX_KEYS + 20) { board.key("k$it").land.add(0) }
        assertEquals(TouchStatsData.MAX_KEYS + 1, board.keys.size)
        assertEquals(20, board.keys.getValue(TouchStatsData.OTHER_KEY).land.total)
        repeat(TouchStatsData.MAX_PAIRS + 5) { board.pair(board.retyped, "p$it")?.add(1) }
        assertEquals(TouchStatsData.MAX_PAIRS, board.retyped.size)
        repeat(TouchStatsData.MAX_REASONS + 5) { board.drop("r$it") }
        assertEquals(TouchStatsData.MAX_REASONS, board.dropped.size)
        val data = TouchStatsData()
        repeat(TouchStatsData.MAX_BOARDS + 5) { data.board("kb/x/y/$it").counts[0]++ }
        assertEquals(TouchStatsData.MAX_BOARDS + 1, data.boards.size)
        assertEquals(5, data.boards.getValue(TouchStatsData.UNKNOWN_BOARD).counts[0])
        assertFalse(data.isEmpty())
    }
}
