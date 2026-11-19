package com.kingzcheung.xime.glide

import com.kingzcheung.xime.keyboard.LetterKeyRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * 11-16：滑 ni ma si le，首选之后出了「你吗斯里克」「你吗思路个」。这两个是按音节拼出来、词表里没有的编码
 * （nimasilike、nimasiluge），Rime 只能逐字凑句。备选只给词表里的编码；首选照旧交给 Rime。
 */
class ChineseGlideAlternativesTest {
    private val unit = 60f
    private val height = 80f
    private val keys: Map<Char, LetterKeyRect> = buildMap {
        listOf("qwertyuiop" to 0f, "asdfghjkl" to 0.5f, "zxcvbnm" to 1.5f).forEachIndexed { row, (letters, inset) ->
            letters.forEachIndexed { index, char -> put(char, LetterKeyRect((inset + index) * unit, row * height, unit, height)) }
        }
    }
    private val keyboard = GlideLexicon.keyboard(keys)
    private val entries = File("src/main/assets/glide_pinyin.txt").readLines().filter { it.isNotBlank() }.map { it.split(' ') }
    private val words = pinyinCodes(entries, null)
    private val listed = words.toHashSet()
    private val decoder = GlideDecoder(keyboard, words, syllables = glideSyllables(entries, null))

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

    @Test fun syllableRunsAreNotOfferedAsAlternatives() {
        var unlistedSeen = 0
        listOf("nimasile", "womenzoule", "tamenlaile", "nihaoma", "woxiangni", "jintianxiawu").forEachIndexed { index, code ->
            val (xs, ys) = trace(code, index)
            val candidates = decoder.decode(xs, ys)
            candidates.forEach { assertEquals(it.word, it.word in listed, it.listed) }
            unlistedSeen += candidates.drop(1).count { !it.listed }
            val alternatives = chineseGlideAlternatives(candidates)
            assertTrue("$code → $alternatives", alternatives.all { it in listed })
            assertEquals(candidates.drop(1).filter { it.word in listed }.map { it.word }, alternatives)
        }
        assertTrue("例子里应当有按音节拼出的备选被挡掉", unlistedSeen > 0)
    }

    @Test fun listedAlternativesStay() {
        val (xs, ys) = trace("shijian", 3)
        val candidates = decoder.decode(xs, ys)
        assertEquals("shijian", candidates.first().word)
        assertEquals(candidates.drop(1).filter { it.listed }.map { it.word }, chineseGlideAlternatives(candidates))
        assertTrue(chineseGlideAlternatives(candidates).isNotEmpty())
    }
}
