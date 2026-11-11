package com.kingzcheung.xime.glide

import com.kingzcheung.xime.keyboard.LetterKeyRect
import com.kingzcheung.xime.settings.DoublePinyinScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * #294：小鹤双拼连划「yb du yi」（印度一）不在滑行词表里，整词比对把它挤成最像的词，看起来吞字。
 * 现在另按音节逐键认，词表外的连划也能出整串编码；词表里的常用词名次不变。
 */
class SyllableGlideTest {
    private val unit = 60f
    private val height = 80f
    private val keys: Map<Char, LetterKeyRect> = buildMap {
        listOf("qwertyuiop" to 0f, "asdfghjkl" to 0.5f, "zxcvbnm" to 1.5f).forEachIndexed { row, (letters, inset) ->
            letters.forEachIndexed { index, char -> put(char, LetterKeyRect((inset + index) * unit, row * height, unit, height)) }
        }
    }
    private val keyboard = GlideLexicon.keyboard(keys)
    private val entries = File("src/main/assets/glide_pinyin.txt").readLines().filter { it.isNotBlank() }.map { it.split(' ') }
    private val flypy = DoublePinyinScheme.BUILTINS.first { it.id == "double_pinyin_flypy" }

    private fun trace(code: String, seed: Int): Pair<FloatArray, FloatArray> {
        val random = Random(seed)
        val centers = code.map { char -> keys.getValue(char).let { (it.x + it.width / 2) to (it.y + it.height / 2) } }
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        centers.zipWithNext().forEach { (a, b) ->
            for (step in 0 until 10) {
                val t = step / 10f
                xs += a.first + (b.first - a.first) * t + (random.nextFloat() - .5f) * 10f
                ys += a.second + (b.second - a.second) * t + (random.nextFloat() - .5f) * 10f
            }
        }
        xs += centers.last().first; ys += centers.last().second
        return xs.toFloatArray() to ys.toFloatArray()
    }

    private fun code(phrase: String, scheme: DoublePinyinScheme?) = phrase.split(' ').joinToString("") { scheme?.code(it) ?: it }

    /** 词表里没有、但每个音节都划得清楚的连划。 */
    private val unlisted = listOf("yin du yi", "wo zai jia li", "ta lai le ma", "ni qu na er", "mai ge bao")

    private val common = listOf("ni hao", "wo men", "shi jian", "zhong guo", "jin tian", "ming tian", "peng you",
        "gong zuo", "xue xi", "shen me", "zen me", "ke yi", "xian zai", "wen ti", "dian hua", "shou ji", "dian nao",
        "kuai di", "fang jian", "xie xie", "hai shi", "yi jing", "ran hou", "zhi dao", "mei you", "ta men",
        "gong si", "shang ban", "chi fan", "hui jia")

    @Test fun unlistedRunsAreReadSyllableBySyllable() {
        for (scheme in listOf(flypy, null)) {
            val words = pinyinCodes(entries, scheme)
            val listed = words.toHashSet()
            val withSyllables = GlideDecoder(keyboard, words, syllables = glideSyllables(entries, scheme))
            val wordsOnly = GlideDecoder(keyboard, words)
            unlisted.forEachIndexed { index, phrase ->
                val code = code(phrase, scheme)
                assertFalse("$code 已在词表里，换一个例子", code in listed)
                val (xs, ys) = trace(code, index)
                assertFalse("只比整词时认不出 $code", wordsOnly.decode(xs, ys).firstOrNull()?.word == code)
                val result = withSyllables.decode(xs, ys).map { it.word }
                assertEquals("${scheme?.name ?: "全拼"} $phrase→${result.take(3)}", code, result.firstOrNull())
            }
        }
    }

    @Test fun listedWordsKeepTheirPlace() {
        for (scheme in listOf(flypy, null)) {
            val words = pinyinCodes(entries, scheme)
            val withSyllables = GlideDecoder(keyboard, words, syllables = glideSyllables(entries, scheme))
            val wordsOnly = GlideDecoder(keyboard, words)
            common.forEachIndexed { index, phrase ->
                val (xs, ys) = trace(code(phrase, scheme), index)
                // 首选不变；音节认出的词表外编码只排在后面当备选。
                assertEquals(phrase, wordsOnly.decode(xs, ys).first().word, withSyllables.decode(xs, ys).first().word)
            }
        }
    }

    /** 连按同一个键（小鹤 chi = ii）在同一处认两次；双拼每个音节两键。 */
    @Test fun syllablesFollowTheScheme() {
        val glide = SyllableGlide(keyboard, glideSyllables(entries, flypy))
        val (xs, ys) = trace("ybduyi", 0)
        val codes = glide.decode(xs, ys)
        assertEquals("ybduyi", codes.first())
        assertTrue(codes.all { it.length % 2 == 0 })
        // 词表外的编码里，只是末键多按几下的写法（同一条线）不重复出现。
        val listed = pinyinCodes(entries, flypy).toHashSet()
        val decoder = GlideDecoder(keyboard, listed.toList(), syllables = glideSyllables(entries, flypy))
        val shapes = decoder.decode(xs, ys).map { it.word }.filter { it !in listed }.map { GlideDecoder.collapseRepeats(it) }
        assertEquals(shapes.distinct(), shapes)
        assertEquals(listOf("yin", "du", "yi").map { flypy.code(it) }, listOf("yb", "du", "yi"))
    }

    @Test fun longRunsDecodeWithinTheLatencyBudget() {
        val decoder = GlideDecoder(keyboard, pinyinCodes(entries, flypy), syllables = glideSyllables(entries, flypy))
        val traces = (unlisted + common).mapIndexed { index, phrase -> trace(code(phrase, flypy), index) } +
            listOf(trace(code("wo men jin tian xia wu qu kan dian ying", flypy), 99))
        traces.forEach { (xs, ys) -> decoder.decode(xs, ys) }
        val started = System.nanoTime()
        traces.forEach { (xs, ys) -> decoder.decode(xs, ys) }
        val perRunMs = (System.nanoTime() - started) / 1e6 / traces.size
        println("glide syllables flypy: ${"%.2f".format(perRunMs)} ms/decode")
        assertTrue("too slow: $perRunMs ms", perRunMs < 50.0)
    }
}
