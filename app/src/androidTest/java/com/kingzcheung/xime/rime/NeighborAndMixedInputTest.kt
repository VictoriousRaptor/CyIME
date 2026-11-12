package com.kingzcheung.xime.rime

import androidx.test.platform.app.InstrumentationRegistry
import com.kingzcheung.xime.keyboard.LetterKeyRect
import com.kingzcheung.xime.keyboard.encodeLetterNeighbors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NeighborAndMixedInputTest {
    private fun engine(): RimeEngine = runBlocking {
        assertTrue(RimeConfigHelper.prepareEngine(InstrumentationRegistry.getInstrumentation().targetContext))
        RimeEngine.getInstance().also {
            assertTrue(it.ensureSession()); assertTrue(it.switchSchema("rime_ice"))
            it.setOption("ascii_mode", false); it.clearQueuedComposition()
        }
    }
    private fun graph() = encodeLetterNeighbors(listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").flatMapIndexed { row, text ->
        text.mapIndexed { col, key -> key to LetterKeyRect(col * 40f + row * 8f, row * 60f, 40f, 60f) }
    }.toMap())

    @Test fun adjacentTypoOffersNativeSelectableCorrectionWithoutRewritingInput() {
        val e = engine()
        try {
            e.setNeighborMap(graph())
            "nihso".forEach { e.processQueuedKeyAndGetResult(it.code, 0) }
            assertEquals("nihso", e.getInput())
            val all = e.getAllCandidates(300)
            val candidate = all.firstOrNull { it.text == "你好" }
            assertNotNull("adjacent s→a must offer 你好: ${all.take(20).map { it.text }}", candidate)
            val revision = e.readQueuedComposition()!!.engineRevision
            assertEquals("你好", e.selectCandidateAtRevision(all.indexOf(candidate!!), revision, global = true)!!.committedText)
            e.clearQueuedComposition()
            e.setNeighborMap("")
            "nihso".forEach { e.processQueuedKeyAndGetResult(it.code, 0) }
            assertFalse("disabled geometry must not use hard-coded QWERTY", e.getAllCandidates(300).any { it.text == "你好" })
        } finally { e.setNeighborMap(""); e.clearQueuedComposition() }
    }

    @Test fun correctedSyllableCanBeSelectedAndUndoneWithoutRewritingCode() {
        val e = engine()
        try {
            e.setNeighborMap(graph())
            "nihso".forEach { e.processQueuedKeyAndGetResult(it.code, 0) }
            val first = e.readPinyinSyllableChoices(e.currentCandidateRevision(), false)!!
            val ni = first.options.indexOfFirst { it.pinyin == "ni" }
            assertTrue("ni must be available", ni >= 0)
            assertNotNull(e.selectPinyinSyllable(first, ni))
            val second = e.readPinyinSyllableChoices(e.currentCandidateRevision(), false)!!
            val hao = second.options.indexOfFirst { it.pinyin == "hao" }
            assertTrue("corrected hao must be available: ${second.labels}", hao >= 0)
            assertNotNull(e.selectPinyinSyllable(second, hao))
            assertEquals("nihso", e.getInput())
            assertTrue(e.getCandidates().contains("你好"))
            val selected = e.readPinyinSyllableChoices(e.currentCandidateRevision(), false)!!
            assertNotNull(e.undoPinyinSyllable(selected))
            assertEquals("nihso", e.getInput())
        } finally { e.setNeighborMap(""); e.clearQueuedComposition() }
    }

    /**
     * 2026-11-11 (1.15.76): with 邻键纠错 on, ok gave 偶看 / 空 kong / 🈳 / 欧卡 and no ok on the first row; with it
     * off ok was 6th, behind OK. Short English typed in Chinese 26 keys ranks the same with the switch on or off, a
     * whole English word comes before guessed abbreviations even when the dictionary also has it in capitals (OK),
     * two letters never grow into a longer corrected syllable, and a one-key slip on two letters (wp for wo) is
     * still corrected. Inputs that are no English word (wp, nh) keep a Chinese first candidate.
     */
    @Test fun shortEnglishRanksTheSameWithNeighbourCorrectionOnOrOff() {
        val e = engine()
        fun top(input: String): List<String> {
            e.clearQueuedComposition()
            input.forEach { e.processQueuedKeyAndGetResult(it.code, 0) }
            return e.getAllCandidates(40).map { it.text }.also { e.clearQueuedComposition() }
        }
        try {
            for (input in listOf("ok", "hi", "no", "yes", "app")) {
                e.setNeighborMap("")
                val off = top(input)
                e.setNeighborMap(graph())
                val on = top(input)
                assertTrue("$input must be in the first two with correction on: ${on.take(8)}", on.indexOf(input) in 0..1)
                assertEquals("$input: first candidate must not depend on 邻键纠错 (off ${off.take(5)}, on ${on.take(5)})",
                    off.first(), on.first())
                if (input.length == 2) assertEquals("$input must come first: ${off.take(8)}", input, off.first())
            }
            for (input in listOf("wp", "nh")) {
                for (map in listOf("", graph())) {
                    e.setNeighborMap(map)
                    val first = top(input).first()
                    assertTrue("$input must keep a Chinese first candidate (correction ${map.isNotEmpty()}): $first",
                        first.isNotEmpty() && first.all { it.code in 0x3400..0x9fff })
                }
            }
            e.setNeighborMap(graph())
            val ok = top("ok")
            assertEquals("ok: ${ok.take(8)}", "ok", ok.first())
            assertFalse("two letters must not grow into a corrected kong: ${ok.take(10)}", ok.take(10).contains("空"))
            // A one-key slip on a whole two-letter syllable is still corrected: wp offers wo (我 and its homophones).
            e.clearQueuedComposition()
            "wp".forEach { e.processQueuedKeyAndGetResult(it.code, 0) }
            val page = e.readQueuedComposition()!!.candidates
            assertTrue("wp must still offer a wo correction: ${page.map { it.text + "/" + it.correction }}",
                page.any { it.correction.contains("wp=wo") })
        } finally { e.setNeighborMap(""); e.clearQueuedComposition() }
    }

    @Test fun englishAndChineseWordsCommitInTheSameChineseMode() {
        val e = engine()
        try {
            e.setNeighborMap(graph())
            val output = StringBuilder()
            for ((input, expected) in listOf("nihao" to "你好", "hello" to "hello", "shijie" to "世界", "dacall" to "打call")) {
                input.forEach { e.processQueuedKeyAndGetResult(it.code, 0) }
                val all = e.getAllCandidates(200)
                val candidate = all.firstOrNull { it.text == expected }
                assertNotNull("$input: ${all.take(15).map { it.text }}", candidate)
                val revision = e.readQueuedComposition()!!.engineRevision
                output.append(e.selectCandidateAtRevision(all.indexOf(candidate!!), revision, global = true)!!.committedText)
                assertFalse(e.isAsciiMode())
            }
            assertEquals("你好hello世界打call", output.toString())
        } finally { e.setNeighborMap(""); e.clearQueuedComposition() }
    }
}
