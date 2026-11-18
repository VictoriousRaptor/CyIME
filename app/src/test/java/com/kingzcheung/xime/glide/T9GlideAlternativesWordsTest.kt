package com.kingzcheung.xime.glide

import com.kingzcheung.xime.keyboard.LetterKeyRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * #214：九键上只差连按同一个键的词划出来是同一条线（高兴/高中、安全/版权、中国/英国、晚安/早餐），首选只能靠词频猜。
 * 首选照常；同一笔的前几个编码各自最常用的词作为备选，要找的词应在首选或备选里。
 */
class T9GlideAlternativesWordsTest {
    private val w = 120f
    private val h = 90f
    private val keys: Map<Char, LetterKeyRect> = buildMap {
        for (digit in 2..9) {
            val index = digit - 1
            put('0' + digit, LetterKeyRect(index % 3 * (w + 6f), index / 3 * (h + 6f), w, h))
        }
    }
    private val entries = File("src/main/assets/glide_pinyin.txt").readLines().filter { it.isNotBlank() }.map { it.trim().split(' ') }
    private val words = File("src/main/assets/glide_pinyin_words.txt").readLines()
    private val codeEntries = T9Glide.codeEntries(entries)
    private val decoder = GlideDecoder(T9Glide.keyboard(keys), codeEntries.map { it.first }, T9Glide.MAX_ENDPOINT)
    private val index = codeEntries.toMap()
    private val wordOf: (String) -> String? = { code -> index[code]?.let { words.getOrNull(it) } }
    private val lines = T9Glide.lines(codeEntries.map { it.first })
    private val sameLine: (String) -> List<String> = { lines[it].orEmpty() }

    private fun center(digit: Char) = keys.getValue(digit).let { (it.x + it.width / 2) to (it.y + it.height / 2) }
    private fun collapse(code: String) = buildString { for (c in code) if (isEmpty() || last() != c) append(c) }

    private fun trace(digits: String, random: Random, noise: Float = 0.3f): Pair<FloatArray, FloatArray> {
        val points = collapse(digits).map { digit -> center(digit).let { (x, y) ->
            x + (random.nextFloat() - .5f) * 2 * noise * w to y + (random.nextFloat() - .5f) * 2 * noise * h } }
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        points.zipWithNext().forEach { (a, b) ->
            for (step in 0 until 16) {
                val t = step / 16f
                xs += a.first + (b.first - a.first) * t + (random.nextFloat() - .5f) * 6f
                ys += a.second + (b.second - a.second) * t + (random.nextFloat() - .5f) * 6f
            }
        }
        xs += points.last().first; ys += points.last().second
        return xs.toFloatArray() to ys.toFloatArray()
    }

    @Test fun theWordListIsGeneratedLineForLineWithTheCodes() {
        assertEquals(entries.size, words.size)
        assertTrue(words.all { it.isNotEmpty() })
        val lines = File("src/main/assets/glide_pinyin.txt").readLines().zip(words).toMap()
        assertEquals("高兴", lines["gao xing"])
        assertEquals("晚安", lines["wan an"])
        assertEquals("模型", lines["mo xing"])
    }

    @Test fun alternativesSkipMissingRepeatedAndFirstWords() {
        val table = mapOf("12" to "高中", "23" to "高兴", "34" to "高中", "45" to "", "56" to "高兴")
        val none: (String) -> List<String> = { emptyList() }
        assertEquals(listOf("高兴"), T9Glide.alternatives(listOf("12", "23", "34", "45", "56", "67"), none, table::get))
        assertEquals(emptyList<String>(), T9Glide.alternatives(listOf("12"), none, table::get))
    }

    /** 和首选同一条线的编码排在识别出的其他编码前面（英国 之后先给 新婚、中国，再给 效果）。 */
    @Test fun codesOnTheSameLineComeFirst() {
        assertEquals(listOf("9464486", "946486", "94664486"), lines["946486"]?.take(3))
        assertEquals(listOf("新婚", "中国", "效果"),
            T9Glide.alternatives(listOf("9464486", "9426486", "946486"), sameLine, wordOf))
        assertEquals(listOf("高兴"), T9Glide.alternatives(listOf("42694664"), sameLine, wordOf))
    }

    /** 1.15.89 上划不出来的词：要在首选或两个备选里（用户报告与 #214、#328）。 */
    @Test fun reportedWordsAreAmongTheFirstCandidateOrItsAlternatives() {
        for (word in listOf("高兴", "安全", "中国", "晚安", "模型")) {
            val digits = T9Glide.digits(entries[words.indexOf(word)].joinToString(""))!!
            val random = Random(word.hashCode())
            var found = 0
            val runs = 20
            repeat(runs) {
                val (xs, ys) = trace(digits, random)
                val codes = decoder.decode(xs, ys, limit = 3).map { it.word }
                val shown = listOfNotNull(codes.firstOrNull()?.let(wordOf)) + T9Glide.alternatives(codes, sameLine, wordOf).take(2)
                if (word in shown) found++
            }
            println("t9 glide alternatives: $word $found/$runs")
            assertTrue("$word $found/$runs", found >= runs * 0.8)
        }
    }

    /** 首选不变：多取两名不影响第一名。 */
    @Test fun theFirstCandidateIsTheSameWithAlternatives() {
        val random = Random(11)
        for (entry in entries.take(200)) {
            val target = T9Glide.digits(entry.joinToString("")) ?: continue
            if (collapse(target).length < 2) continue
            val (xs, ys) = trace(target, random)
            assertEquals(decoder.decode(xs, ys, limit = 1).firstOrNull()?.word, decoder.decode(xs, ys, limit = 3).firstOrNull()?.word)
        }
    }
}
