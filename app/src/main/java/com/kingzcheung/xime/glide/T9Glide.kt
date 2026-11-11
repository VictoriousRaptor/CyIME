package com.kingzcheung.xime.glide

import com.kingzcheung.xime.keyboard.LetterKeyRect
import com.kingzcheung.xime.settings.DoublePinyinScheme
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 九键滑行：一笔划过几个数字键，按“这条线最像哪个词的九键编码”出数字串，再交给 Rime 照常出字。
 * 不再把手指路过的每个键都算进去（从 9 划到 4 会经过 8 或 5）；
 * 词表认不出时只取起点、拐弯处和终点的键（[t9TurnDigits]）。
 */
object T9Glide {
    private val DIGIT = HashMap<Char, Char>().apply {
        listOf("abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz").forEachIndexed { index, letters ->
            letters.forEach { put(it, '2' + index) }
        }
        put('v', '8')
    }

    /** 拼音字母串对应的九键数字（nihao → 64426）；有非字母时为空。 */
    fun digits(letters: String): String? = buildString {
        for (char in letters) append(DIGIT[char] ?: return null)
    }

    /**
     * 词表里每个词的九键编码，按常用程度排列；同一数字串只留第一次。九键双拼按 [doublePinyin] 逐音节换码再换数字。
     * 单音节词往后挪（排名乘 [SINGLE_SYLLABLE_RANK_FACTOR]）：九键上同一个键连按划不出来，你好 64426 和 年 6426、
     * 我们 96636 和 文 936 划出来是同一条线，原来按词频总是出单字。九键滑行是用来划整词的，单字点按更快。
     * 真人轨迹（11-10，38 人 161 条）首选从 70 条升到 140 条；常用单字合成轨迹从 267/293 降到 257/293。
     */
    internal fun codes(entries: List<List<String>>, doublePinyin: DoublePinyinScheme? = null): List<String> =
        entries.withIndex().sortedBy { (rank, syllables) ->
            if (syllables.size == 1) rank * SINGLE_SYLLABLE_RANK_FACTOR else rank.toDouble()
        }.map { it.value }.mapNotNull { syllables ->
            val letters = if (doublePinyin == null) syllables.joinToString("")
                else syllables.map { doublePinyin.code(it) }.takeIf { null !in it }?.joinToString("") ?: return@mapNotNull null
            digits(letters)
        }.distinct()

    /** 九键的键比宽高都不小，坐标单位取宽、高中位数的平均，横竖方向一样敏感。 */
    fun keyboard(keys: Map<Char, LetterKeyRect>): GlideKeyboard {
        val widths = keys.values.map { it.width }.sorted()
        val heights = keys.values.map { it.height }.sorted()
        return GlideKeyboard((widths[widths.size / 2] + heights[heights.size / 2]) / 2f,
            keys.mapValues { (_, rect) -> (rect.x + rect.width / 2f) to (rect.y + rect.height / 2f) })
    }

    /**
     * 词表认不出时的退路：起点、终点和明显拐弯（超过 [TURN_DEGREES] 度）处的键；
     * 拐点不在任何键上时跳过，相邻重复的只留一个。
     */
    fun turnDigits(xs: FloatArray, ys: FloatArray, keys: Map<Char, LetterKeyRect>): String {
        if (xs.isEmpty()) return ""
        val unit = keyboard(keys).keyUnit
        fun keyAt(x: Float, y: Float) = keys.entries.firstOrNull { (_, r) ->
            x >= r.x && x < r.x + r.width && y >= r.y && y < r.y + r.height }?.key
        // 按弧长每四分之一键取一点，去掉手指抖动带来的小拐弯。
        val (sx, sy) = resampleByStep(xs, ys, unit / 4f)
        val out = StringBuilder()
        fun add(key: Char?) { if (key != null && (out.isEmpty() || out.last() != key)) out.append(key) }
        add(keyAt(sx.first(), sy.first()))
        val span = 2
        for (i in span until sx.size - span) {
            val a = atan2(sy[i] - sy[i - span], sx[i] - sx[i - span])
            val b = atan2(sy[i + span] - sy[i], sx[i + span] - sx[i])
            var turn = Math.toDegrees(abs(b - a).toDouble())
            if (turn > 180) turn = 360 - turn
            if (turn >= TURN_DEGREES) add(keyAt(sx[i], sy[i]))
        }
        add(keyAt(sx.last(), sy.last()))
        return out.toString()
    }

    private fun resampleByStep(xs: FloatArray, ys: FloatArray, step: Float): Pair<FloatArray, FloatArray> {
        val outX = arrayListOf(xs[0]); val outY = arrayListOf(ys[0])
        var carry = 0f
        for (i in 1 until xs.size) {
            var x0 = xs[i - 1]; var y0 = ys[i - 1]
            var length = hypot(xs[i] - x0, ys[i] - y0)
            while (carry + length >= step && length > 0f) {
                val t = (step - carry) / length
                x0 += (xs[i] - x0) * t; y0 += (ys[i] - y0) * t
                outX += x0; outY += y0
                length = hypot(xs[i] - x0, ys[i] - y0); carry = 0f
            }
            carry += length
        }
        if (outX.last() != xs.last() || outY.last() != ys.last()) { outX += xs.last(); outY += ys.last() }
        return outX.toFloatArray() to outY.toFloatArray()
    }

    const val TURN_DEGREES = 50.0
    /** 单音节词在九键滑行词表里的排名倍数，见 [codes]。 */
    const val SINGLE_SYLLABLE_RANK_FACTOR = 4.0
    /** 起止点最远离首末键中心 0.75 个键（键宽、高平均）：键内任何位置起手都算，上下相邻的键不算。 */
    const val MAX_ENDPOINT = 0.75f
}
