package com.kingzcheung.xime.glide

import com.kingzcheung.xime.keyboard.LetterKeyRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 11-17「划的时候候选已经对了，一松手字没了」：快划时抬手那一下手指还往前甩，轨迹末尾冲出最后一个键，
 * 起止点对不上任何词，整笔没有候选，什么都不出。认不出时去掉末尾一段再认。
 */
class GlideLiftOffTest {
    private val unit = 60f
    private val height = 80f
    private val keys: Map<Char, LetterKeyRect> = buildMap {
        listOf("qwertyuiop" to 0f, "asdfghjkl" to 0.5f, "zxcvbnm" to 1.5f).forEachIndexed { row, (letters, inset) ->
            letters.forEachIndexed { index, char -> put(char, LetterKeyRect((inset + index) * unit, row * height, unit, height)) }
        }
    }
    private val keyboard = GlideLexicon.keyboard(keys)
    private val words = File("src/main/assets/glide_pinyin.txt").readLines().filter { it.isNotBlank() }
        .map { it.replace(" ", "") }.distinct()
    private val decoder = GlideDecoder(keyboard, words)

    /** 依次经过各键中心的轨迹，末尾沿最后一段方向再甩出 [overshoot] 个键宽。 */
    private fun trace(code: String, overshoot: Float): Pair<FloatArray, FloatArray> {
        val centers = code.map { char -> keys.getValue(char).let { (it.x + it.width / 2) to (it.y + it.height / 2) } }
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        centers.zipWithNext().forEach { (a, b) ->
            for (step in 0 until 10) {
                val t = step / 10f
                xs += a.first + (b.first - a.first) * t; ys += a.second + (b.second - a.second) * t
            }
        }
        val (lx, ly) = centers.last(); xs += lx; ys += ly
        val (px, py) = centers[centers.size - 2]
        val length = kotlin.math.hypot(lx - px, ly - py)
        val dx = (lx - px) / length; val dy = (ly - py) / length
        for (k in 1..4) { xs += lx + dx * overshoot * unit * k / 4; ys += ly + dy * overshoot * unit * k / 4 }
        return xs.toFloatArray() to ys.toFloatArray()
    }

    @Test fun liftOffFlickNoLongerSwallowsTheWord() {
        for (code in listOf("jintian", "xiexie", "mingtian", "women")) {
            val (xs, ys) = trace(code, overshoot = 1.5f)
            // 改前：整笔认不出，这一笔什么都不出。
            assertTrue(code, decoder.decode(xs, ys).isEmpty())
            val recovered = GlideLexicon.decodeTrimmingTail(xs, ys, keyboard.keyUnit, decoder::decode)
            assertEquals(code, recovered.firstOrNull()?.word)
        }
    }

    @Test fun recognizedStrokesAreUntouched() {
        val (xs, ys) = trace("jintian", overshoot = 0f)
        val whole = decoder.decode(xs, ys)
        var calls = 0
        val result = GlideLexicon.decodeTrimmingTail(xs, ys, keyboard.keyUnit) { x, y -> calls++; decoder.decode(x, y) }
        assertEquals(whole, result)
        assertEquals(1, calls)
    }

    @Test fun shortStrokesAreNotTrimmed() {
        // 不到 1.5 个键宽：去掉一段后剩下不到一个键宽，不再试，照旧交给「短划按点按算」。
        val xs = floatArrayOf(30f, 50f, 70f, 90f, 110f); val ys = floatArrayOf(40f, 40f, 40f, 40f, 40f)
        var calls = 0
        val empty = emptyList<GlideCandidate>()
        assertSame(empty, GlideLexicon.decodeTrimmingTail(xs, ys, unit) { _, _ -> calls++; empty })
        assertEquals(1, calls)
    }

    @Test fun dropNoteHasNoCoordinatesOrText() {
        val (xs, ys) = trace("women", overshoot = 0f)
        val note = glideDropNote("no_candidates", keys, xs, ys, preview = true)
        assertTrue(note, note.startsWith("glide dropped reason=no_candidates points=${xs.size} lengthKeys="))
        assertTrue(note, note.contains("startOnLetter=true preview=true keys=26"))
        assertTrue(note, !note.contains("women"))
    }
}
