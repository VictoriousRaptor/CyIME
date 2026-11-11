package com.kingzcheung.xime.rime

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 长按首选整句、点中间一个词：前面的词选定，候选只剩这个词的写法，选好后整句照常上屏。 */
class SentenceWordReselectTest {
    private fun engine(): RimeEngine = runBlocking {
        assertTrue(RimeConfigHelper.prepareEngine(InstrumentationRegistry.getInstrumentation().targetContext))
        RimeEngine.getInstance().also {
            assertTrue(it.ensureSession()); assertTrue(it.switchSchema("rime_ice"))
            it.setOption("ascii_mode", false); it.clearQueuedComposition()
        }
    }

    @Test fun tappingAMiddleWordReselectsOnlyThatWord() {
        val e = engine()
        try {
            "woxiangqubeijing".forEach { e.processQueuedKeyAndGetResult(it.code, 0) }
            val sentence = e.getCandidates().first()
            val words = e.firstSentenceWords(sentence)
            assertNotNull("整句 $sentence 要能拆成词", words)
            assertTrue("至少两个词：$words", words!!.size >= 2)
            val target = if (words.size >= 3) 1 else words.lastIndex
            val revision = e.readQueuedComposition()!!.engineRevision
            val state = e.reselectSentenceWord(words, target, revision)
            assertNotNull(state)
            assertEquals("编码不改", "woxiangqubeijing", e.getInput())
            val choices = e.getAllCandidates(300).map { it.text }
            assertTrue("候选里要有被点的词 ${words[target]}：${choices.take(10)}", words[target] in choices)
            if (target < words.lastIndex) assertTrue("候选只剩这个词的写法，不再带后面的字：${choices.take(10)}",
                choices.none { it.startsWith(words[target] + words[target + 1]) })
            // 选回原来的词，再选首选，整句和原来一样上屏。
            val pick = e.selectCandidateAtRevision(choices.indexOf(words[target]),
                e.readQueuedComposition()!!.engineRevision, global = true)!!
            val committed = pick.committedText.ifEmpty {
                e.selectCandidateAtRevision(0, e.readQueuedComposition()!!.engineRevision)!!.committedText
            }
            assertEquals(sentence, committed)
        } finally { e.clearQueuedComposition() }
    }
}
