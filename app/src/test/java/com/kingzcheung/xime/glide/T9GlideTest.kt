package com.kingzcheung.xime.glide

import com.kingzcheung.xime.keyboard.LetterKeyRect
import com.kingzcheung.xime.settings.DoublePinyinScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * 九键滑行（别的用户 11-03：路过的每个键都被算进去，正确率低）：按词表比对整条线，
 * 9 划到 4 是 94，不再带上路过的 8 或 5；同一个键连着两次（你好 64426）也能划出来。
 */
class T9GlideTest {
    private val w = 120f
    private val h = 90f
    private val keys: Map<Char, LetterKeyRect> = buildMap {
        for (digit in 2..9) {
            val index = digit - 1
            put('0' + digit, LetterKeyRect(index % 3 * (w + 6f), index / 3 * (h + 6f), w, h))
        }
    }
    private val entries = File("src/main/assets/glide_pinyin.txt").readLines().filter { it.isNotBlank() }.map { it.trim().split(' ') }
    private val decoder = GlideDecoder(T9Glide.keyboard(keys), T9Glide.codes(entries), T9Glide.MAX_ENDPOINT)

    private fun center(digit: Char) = keys.getValue(digit).let { (it.x + it.width / 2) to (it.y + it.height / 2) }
    private fun collapse(code: String) = buildString { for (c in code) if (isEmpty() || last() != c) append(c) }

    /** 经过各键（拐弯处离键中心最多 [noise] 个键）的折线，线上带一点抖动。 */
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

    @Test fun lettersMapToNineKeyDigits() {
        assertEquals("64426", T9Glide.digits("nihao"))
        assertEquals("94", T9Glide.digits("xi"))
        val flypy = DoublePinyinScheme.BUILTINS.first { it.id == "double_pinyin_flypy" }
        // 小鹤双拼 zhong guo = vs go → 87 46
        assertEquals("8746", T9Glide.codes(listOf(listOf("zhong", "guo")), flypy).single())
    }

    @Test fun aStraightLineThatCrossesOtherKeysTypesOnlyItsEnds() {
        val (xs, ys) = trace("94", Random(1), noise = 0f)
        assertEquals("94", decoder.decode(xs, ys, limit = 1).single().word)
        assertEquals("94", T9Glide.turnDigits(xs, ys, keys))
    }

    /** 常用词合成轨迹：数字串完全对上的比例（旧做法“路过就算”在同样的轨迹上只有约三成）。 */
    @Test fun commonWordsDecodeToTheirDigitsWithinTheLatencyBudget() {
        val random = Random(7)
        var total = 0; var right = 0
        val started = System.nanoTime()
        for (entry in entries.take(500)) {
            val target = T9Glide.digits(entry.joinToString("")) ?: continue
            if (collapse(target).length < 2) continue
            val (xs, ys) = trace(target, random)
            total++
            if (decoder.decode(xs, ys, limit = 1).firstOrNull()?.word == target) right++
        }
        val perWordMs = (System.nanoTime() - started) / 1e6 / total
        println("t9 glide: ${decoder.size} templates, $right/$total exact, ${"%.2f".format(perWordMs)} ms/word")
        assertTrue("accuracy $right/$total", right >= total * 0.85)
        assertTrue("too slow: $perWordMs ms", perWordMs < 50.0)
    }

    /**
     * 同一个键连按划不出来：你好 64426 与 年 6426、我们 96636 与 文 936 是同一条线（用户 11-10：九键划词老出单字）。
     * 九键滑行用来划整词，同一条线先出多音节的词。
     */
    @Test fun aWordBeatsTheSingleSyllableOnTheSameLine() {
        for ((word, digits) in listOf("nihao" to "64426", "women" to "96636", "niubi" to "64824")) {
            val (xs, ys) = trace(digits, Random(3), noise = 0f)
            assertEquals(word, digits, decoder.decode(xs, ys, limit = 1).single().word)
        }
    }
}
