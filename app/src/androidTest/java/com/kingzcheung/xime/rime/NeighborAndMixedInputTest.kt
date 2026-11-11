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
