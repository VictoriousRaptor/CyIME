package com.kingzcheung.xime.glide

import com.kingzcheung.xime.speech.WordLanguageModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** 11-16：中文滑行按前文选编码；只加分不扣分，没有前文、没有模型时结果不变。 */
class ChineseGlideContextTest {
    /** 词表：ids 按顺序；bigram 只认 [pairs]，其余按单字频率退回（等于 unigram，加成为 0）。 */
    private class FakeLm(words: List<String>, private val pairs: Map<Pair<String, String>, Double>) : WordLanguageModel {
        private val ids = words.withIndex().associate { it.value to it.index }
        private val names = words
        override fun id(text: String) = ids[text] ?: -1
        override fun unigram(id: Int) = -10.0
        override fun bigram(history: Int, word: Int) = pairs[names[history] to names[word]] ?: -10.5
        override fun trigram(history2: Int, history: Int, word: Int) = bigram(history, word)
    }

    private val words = mapOf("yukuai" to "愉快", "yikuai" to "一块", "yiqi" to "一起")
    private val lm = FakeLm(listOf("我们", "们", "愉快", "一块", "一起", "去"), mapOf(("我们" to "一块") to -6.0, ("们" to "一起") to -2.0))
    private val candidates = listOf(GlideCandidate("yukuai", 1.00f), GlideCandidate("yikuai", 1.03f), GlideCandidate("yikuaier", 1.05f, listed = false))

    @Test fun contextLiftsTheWordThatFollows() {
        // 一块 跟在 我们 后面比单独出现常见 4 nat：减 0.06，超过 0.03 的形状差。
        val ranked = ChineseGlideContext.rerank(candidates, "明天我们", words::get, lm)
        assertEquals(listOf("yikuai", "yukuai", "yikuaier"), ranked.map { it.word })
        assertEquals(1.03f - ChineseGlideContext.WEIGHT * 4f, ranked.first().cost, 1e-5f)
    }

    @Test fun noContextNoModelNoChange() {
        assertSame(candidates, ChineseGlideContext.rerank(candidates, null, words::get, lm))
        assertSame(candidates, ChineseGlideContext.rerank(candidates, "", words::get, lm))
        assertSame(candidates, ChineseGlideContext.rerank(candidates, "我们", words::get, null))
        // 前文末尾没有模型认识的词。
        assertSame(candidates, ChineseGlideContext.rerank(candidates, "嗯嗯", words::get, lm))
    }

    @Test fun onlyBoostsNeverPenalizes() {
        // 去 后面的搭配都没见过（bigram 比 unigram 低）：不扣分，原样。
        assertSame(candidates, ChineseGlideContext.rerank(candidates, "去", words::get, lm))
    }

    @Test fun historyIsTheLongestKnownSuffix() {
        assertEquals(lm.id("我们"), ChineseGlideContext.history("明天我们 ", lm))
        assertEquals(lm.id("们"), ChineseGlideContext.history("他们", lm))
        assertEquals(-1, ChineseGlideContext.history("嗯", lm))
    }

    @Test fun liftIsCapped() {
        val big = FakeLm(listOf("我们", "一块"), mapOf(("我们" to "一块") to 0.0))
        val ranked = ChineseGlideContext.rerank(listOf(GlideCandidate("yukuai", 1f), GlideCandidate("yikuai", 2f)), "我们", words::get, big)
        assertEquals(2f - ChineseGlideContext.WEIGHT * ChineseGlideContext.MAX_LIFT.toFloat(), ranked.first { it.word == "yikuai" }.cost, 1e-5f)
    }

    @Test fun releaseUsesThePreviewLeftOfTheSameGesture() {
        var reads = 0
        val read = { reads++; "明天kai hui" }
        // 这一笔开过预览：光标前已有预览写进去的组合文字，用预览开头取的那份，不再现读。
        assertEquals("明天", ChineseGlideContext.leftAtRelease(7, 7, "明天", read))
        assertEquals(null, ChineseGlideContext.leftAtRelease(7, 7, null, read))
        assertEquals(0, reads)
        // 没开预览、或预览是上一笔的：抬手时现读。
        assertEquals("明天kai hui", ChineseGlideContext.leftAtRelease(7, -1, null, read))
        assertEquals("明天kai hui", ChineseGlideContext.leftAtRelease(8, 7, "明天", read))
        assertEquals(2, reads)
    }
}
