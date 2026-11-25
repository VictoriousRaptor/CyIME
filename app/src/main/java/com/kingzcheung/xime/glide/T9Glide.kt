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
        codeEntries(entries, doublePinyin).map { it.first }

    /** [codes] 连同每个数字串取自词表的第几项（同一数字串只留排在最前的那项）；滑行的备选按它查要显示的词（#214）。 */
    internal fun codeEntries(entries: List<List<String>>, doublePinyin: DoublePinyinScheme? = null): List<Pair<String, Int>> =
        entries.withIndex().sortedBy { (rank, syllables) ->
            if (syllables.size == 1) rank * SINGLE_SYLLABLE_RANK_FACTOR else rank.toDouble()
        }.mapNotNull { (index, syllables) ->
            val letters = if (doublePinyin == null) syllables.joinToString("")
                else syllables.map { doublePinyin.code(it) }.takeIf { null !in it }?.joinToString("") ?: return@mapNotNull null
            (digits(letters) ?: return@mapNotNull null) to index
        }.distinctBy { it.first }

    /**
     * 滑行的备选（#214）：识别结果前几名的数字串 [codes]，首选之后各编码最常用的词。
     * 和首选划出来是同一条线的编码（只差连按同一个键，[sameLine] 按常用程度给出）排在前面：这些识别分不出来，
     * 只能靠词频猜，最可能是要的词（英国 9464486 的同一条线上有 新婚 946486、中国 94664486）。
     * 查不到的、和首选是同一个词的、重复的不要。
     */
    internal fun alternatives(codes: List<String>, sameLine: (String) -> List<String>, wordOf: (String) -> String?): List<String> =
        alternativeChoices(codes, "", sameLine, wordOf).map { it.second }

    /**
     * 一笔最终给的备选：[alternativeChoices] 前 [limit] 个；组合为空时，再补一个单字（他 11-23 要所有音节都滑得出）：
     * 同一条线上、或识别前几名里，第一个有单音节读法、又还没给出来的编码，给它最常用的单字。
     * - zhui 9484：那条线上 只会、一会、主题 都比它常用，9484 本身最常用的又是 一体，备选只给两个，追 永远轮不到。
     * - qiong 74664、ang 264：和 请 7464、从 2664 是同一条线，首选已是单字，另一个单字没位置。
     * - zeng 9364：9→3 直线路过 6，和 我能 96364 形状一样，在识别第二名。
     * 单音节词在词表里往后挪（[SINGLE_SYLLABLE_RANK_FACTOR]）是为了整词划得出，这里补回来，分音节划的人也划得出。
     * [sameLine] 按 [lines]（只差连按）给同一条线的编码，[sameShape] 按 [shapes]（连直线路过的键也不算）给。
     * [syllableWordOf] 给编码最常用的单字（这个编码没有单音节读法时为空）。
     */
    internal fun alternativesWithSyllable(codes: List<String>, before: String, limit: Int, sameLine: (String) -> List<String>,
        sameShape: (String) -> List<String>, wordOf: (String) -> String?, syllableWordOf: (String) -> String?): List<Pair<String, String>> {
        val shown = alternativeChoices(codes, before, sameLine, wordOf).take(limit)
        if (before.isNotEmpty() || limit <= 0) return shown
        val first = codes.firstOrNull() ?: return shown
        val words = shown.map { it.second } + listOfNotNull(wordOf(first))
        val shownCodes = shown.map { it.first } + first
        val line = straightLine(first)
        val syllable = (sameShape(line).asSequence().filter { straightLine(it) == line } + codes.asSequence().drop(1))
            .filter { it !in shownCodes }
            .mapNotNull { code -> syllableWordOf(code)?.takeIf { it.isNotEmpty() && it !in words }?.let { code to it } }
            .firstOrNull() ?: return shown
        return shown + syllable
    }

    /**
     * [alternatives] 连同各自的编码。[before] 是这一笔之前组合里已有的数字（先点了 hua 再划 xing 时是 482）：
     * 这时给的是「前面的组合 + 这个编码」整串最常用的词（482 + 9464 → 滑行），点了把整个组合上屏；
     * 整串在词表里没有的不给（只给半截会丢掉前面的字）。[before] 为空时和 [alternatives] 一样。
     */
    internal fun alternativeChoices(codes: List<String>, before: String, sameLine: (String) -> List<String>,
        wordOf: (String) -> String?): List<Pair<String, String>> {
        val first = codes.firstOrNull() ?: return emptyList()
        val firstWord = wordOf(before + first)
        return (sameLine(GlideDecoder.collapseRepeats(first)).asSequence() + codes.asSequence().drop(1))
            .filter { it != first }.distinct()
            .mapNotNull { code -> wordOf(before + code)?.takeIf { it.isNotEmpty() && it != firstWord }?.let { code to it } }
            .distinctBy { it.second }.toList()
    }

    /**
     * 同一条线上的编码按前面的组合挑（他 11-23：九键先打 hua 再划 xing，出来的永远是 zhong）。
     * xing 9464 和 zhong 94664 只差连按 6，划出来一模一样，单看词频永远是 zhong；组合里前面已有 hua（482）时，
     * 看「前面几个数字 + 这个编码」在词表里成不成词：滑行 4829464 比 华中 48294664 常用，就出 xing。
     * - 只在形状完全一样的编码之间挑（[sameShape] 按 [shapes] 给，只差连按、直线路过键的都算），形状不同的词不受影响。
     * - 前面取得越长越可信：先看最长的一段能不能和某个编码连成词，连不成再缩短，最短 [MIN_JOINT_PREFIX] 个数字；
     *   同样长时取词表里排得靠前的。前面那段要正好落在词的音节边界上（[syllableDigits] 给每个音节的数字）。
     * - 都连不成词时顺序不变。[indexOf] 是编码在词表里第几项（越小越常用），[syllableDigits] 给那一项各音节的数字。
     */
    internal fun preferJoint(codes: List<String>, before: String, sameShape: (String) -> List<String>,
        indexOf: (String) -> Int?, syllableDigits: (Int) -> List<String>?): List<String> {
        val first = codes.firstOrNull() ?: return codes
        if (before.length < MIN_JOINT_PREFIX) return codes
        val line = straightLine(first)
        val choices = (listOf(first) + sameShape(line)).filter { straightLine(it) == line }.distinct()
        if (choices.size < 2) return codes
        for (length in minOf(before.length, MAX_JOINT_PREFIX) downTo MIN_JOINT_PREFIX) {
            val prefix = before.takeLast(length)
            val best = choices.mapNotNull { code ->
                val index = indexOf(prefix + code) ?: return@mapNotNull null
                val parts = syllableDigits(index) ?: return@mapNotNull null
                // 前面那段正好是词开头的几个音节。
                var covered = 0
                val aligned = parts.any { covered += it.length; covered == length }
                if (aligned) code to index else null
            }.minByOrNull { it.second } ?: continue
            return if (best.first == first) codes else listOf(best.first) + codes.filter { it != best.first }
        }
        return codes
    }

    /** 按 [codes] 的顺序，把划出来是同一条线（去掉连按的重复键后相同）的编码归到一起。 */
    internal fun lines(codes: Iterable<String>): Map<String, List<String>> =
        codes.groupBy { GlideDecoder.collapseRepeats(it) }.filterValues { it.size > 1 }

    /** 按 [codes] 的顺序，把形状一样（[straightLine] 相同）的编码归到一起；比 [lines] 多了直线路过键的。 */
    internal fun shapes(codes: Iterable<String>): Map<String, List<String>> =
        codes.groupBy { straightLine(it) }.filterValues { it.size > 1 }

    /** 九键数字在 3×3 键区里的列、行（1 不是滑行键，也照位置算）。 */
    private val GRID: Map<Char, Pair<Int, Int>> = ('1'..'9').associateWith { (it - '1') % 3 to (it - '1') / 3 }

    /**
     * 划出来是哪条线：去掉连按的重复键，再去掉直线正好路过的键（9→6→3 的 6、6→5→4 的 5，在两边键的正中间）。
     * 9→3 本来就要路过 6，增 9364 和 我能 96364、心里 94654 和 中 94664 划出来一模一样，识别分不出，
     * 和只差连按的词一样只能靠词频、前面的组合和备选分（他 11-23 要所有音节都滑得出）。
     * 只用来按前面的组合挑（[preferJoint]）和补单字（[alternativesWithSyllable]）；备选的先后（[lines]）、识别、
     * [T9GlideMemory] 记的线都不变——把这些也算进同一条线，备选的两个位置被挤掉，手指偏一点时整词反而少出 14 个（自检）。
     */
    internal fun straightLine(code: String): String {
        val keys = StringBuilder(GlideDecoder.collapseRepeats(code))
        var i = 1
        while (i < keys.length - 1) {
            val a = GRID[keys[i - 1]]; val m = GRID[keys[i]]; val b = GRID[keys[i + 1]]
            if (a != null && m != null && b != null && a.first + b.first == 2 * m.first && a.second + b.second == 2 * m.second) {
                keys.deleteCharAt(i)
                if (i > 1) i--
            } else i++
        }
        return keys.toString()
    }

    /** 左栏里一笔滑行带出的读音：点了先把打入的数字串换成 [code]，再选这个读音（占 [digits] 个键）。 */
    data class LineReading(val pinyin: String, val spelling: String, val code: String, val digits: Int)

    /**
     * 滑行的左栏读音（#582）：打入的是 [typed]，和它划出来是同一条线（只差连按同一个键）的编码 [codes]
     * 开头能拼出、[typed] 自己拼不出的读音（[typed] 拼得出的左栏本来就有）。
     * xing 9464 和 xiong 94664 划出来一样，打入 94664 时左栏也要有 xing。九键双拼按 [doublePinyin] 的两键换码。
     */
    internal fun lineReadings(typed: String, codes: List<String>, doublePinyin: DoublePinyinScheme? = null): List<LineReading> {
        val line = GlideDecoder.collapseRepeats(typed)
        val syllables = DoublePinyinScheme.SYLLABLES.mapNotNull { syllable ->
            val spelling = (if (doublePinyin == null) syllable else doublePinyin.code(syllable)) ?: return@mapNotNull null
            digits(spelling)?.let { Triple(syllable, spelling, it) }
        }
        return codes.asSequence().filter { it != typed && GlideDecoder.collapseRepeats(it) == line }.distinct().flatMap { code ->
            syllables.filter { (_, _, keys) -> code.startsWith(keys) && !typed.startsWith(keys) }
                .sortedByDescending { it.third.length }
                .map { (syllable, spelling, keys) -> LineReading(syllable, spelling, code, keys.length) }
        }.distinctBy { it.pinyin }.take(MAX_LINE_READINGS).toList()
    }

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
    /**
     * 九键滑行轨迹至少多长（键宽、高平均）才按词表认。原来和 26 键一样 0.75：九键的键比高宽，上下相邻两键
     * （7→4 是 shi、3→6 是 fen）中心只隔一个键高，键宽 100、高 50 时才 0.72，整笔认不出，退回只取拐点的数字串
     * （744 是 → 74 起）。能走到这里的触摸已经由 DigitStroke 判成滑行（进了另一个键的中间、走了大半个键），
     * 不会是点按，这里只拦太短的。
     */
    const val MIN_PATH = 0.4f
    /** 按前面的组合挑同一条线时，前面最少看几个数字（一个数字的拼音太多，连成词也不说明什么）。 */
    const val MIN_JOINT_PREFIX = 2
    /** 前面最多看几个数字（词表里最长的词也就五六个音节）。 */
    const val MAX_JOINT_PREFIX = 18
    /** 一笔滑行最多往左栏加几个读音，见 [lineReadings]。 */
    const val MAX_LINE_READINGS = 6
}
