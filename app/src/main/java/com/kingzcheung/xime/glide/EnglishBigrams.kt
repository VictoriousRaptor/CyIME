package com.kingzcheung.xime.glide

import kotlin.math.ln

/**
 * 英文双词表：给“前一个词 + 这个词”打分，滑行连着划时用来连同前一个词一起纠正（i 划到 m、n 之间再划 done → I'm done）。
 * 词与 english.txt 一样去掉撇号、全小写（tools/glide_lab/build_english_bigrams.py 生成）：
 * 「词 分数」是 -ln P(词) × 10，「前词 后词 分数」是 -ln P(后|前) × 10；没见过的词对按 0.1 × 单词概率估算，
 * 表里没有单词概率的词按 english.txt 排名估一个很小的值。
 * 词对按两个词的序号压成一个 Int 排序存放，查表二分，二十多万对约 1.3 MB。
 */
class EnglishBigrams(vocabulary: List<String>, lines: Sequence<String>) {
    private val ids = HashMap<String, Int>(vocabulary.size * 2)
    /** 序号 → 词，查「后面常跟什么词」用（[next]）。 */
    private val words: Array<String>
    private val unigram: FloatArray
    private val keys: IntArray
    private val scores: ByteArray

    init {
        vocabulary.forEach { word -> if (word.isNotEmpty() && word !in ids) ids[word] = ids.size }
        words = Array(ids.size) { "" }.also { array -> ids.forEach { (word, id) -> array[id] = word } }
        var total = 0.0
        for (rank in 0 until ids.size) total += 1.0 / (rank + 10)
        unigram = FloatArray(ids.size) { rank -> (-ln(RANK_SHARE / (rank + 10) / total)).toFloat() }
        val entries = ArrayList<Long>()
        for (line in lines) {
            val parts = line.split(' ')
            val score = parts.last().toIntOrNull()?.takeIf { it in 0..255 } ?: continue
            when (parts.size) {
                2 -> ids[parts[0]]?.let { unigram[it] = score / 10f }
                3 -> {
                    val a = ids[parts[0]] ?: continue
                    val b = ids[parts[1]] ?: continue
                    entries += (key(a, b).toLong() shl 8) or score.toLong()
                }
            }
        }
        entries.sort()
        keys = IntArray(entries.size) { (entries[it] shr 8).toInt() }
        scores = ByteArray(entries.size) { (entries[it] and 0xff).toByte() }
    }

    val size: Int get() = keys.size

    /** 只看这个词本身（没有前词）的代价 -ln P(word)，单位 nat。 */
    fun cost(word: String): Float = ids[word]?.let { unigram[it] } ?: UNKNOWN

    /** [previous] 后面接 [word] 的代价 -ln P(word|previous)，单位 nat；前词为空或不认识时只看这个词本身。 */
    fun cost(previous: String?, word: String): Float {
        val b = ids[word] ?: return UNKNOWN
        val a = previous?.let { ids[it] } ?: return unigram[b]
        val index = keys.binarySearch(key(a, b))
        return if (index >= 0) (scores[index].toInt() and 0xff) / 10f else unigram[b] + UNSEEN_COST
    }

    /**
     * 表里跟在 [previous] 后面的词，最常跟的在前，最多 [limit] 个（#101 英文下一词）。前词不认识时为空。
     * 同一个前词的词对按序号排在一起，二分找到开头后只看这一段。
     */
    fun next(previous: String, limit: Int): List<String> {
        val a = ids[previous] ?: return emptyList()
        val from = key(a, 0)
        val to = from + VOCAB_LIMIT
        var index = keys.binarySearch(from).let { if (it < 0) -it - 1 else it }
        val found = ArrayList<Int>()
        while (index < keys.size && keys[index] < to) found += index++
        return found.sortedBy { scores[it].toInt() and 0xff }.take(limit).map { words[keys[it] - from] }
    }

    private fun key(a: Int, b: Int) = a * VOCAB_LIMIT + b

    companion object {
        private const val VOCAB_LIMIT = 1 shl 15
        /** 没见过的词对：0.1 × 单词概率，即多 -ln 0.1。 */
        private val UNSEEN_COST = (-ln(0.1)).toFloat()
        /** 表里缺单词概率时按排名估算的那一份（与生成脚本的 RANK_UNIGRAM 一致）。 */
        private const val RANK_SHARE = 0.001
        private const val UNKNOWN = 25f
    }
}

/** 一种划法：[previous] 为空表示前面没有可改的滑行词。 */
data class GlideChoice(val previous: String?, val word: String, val cost: Float)

/**
 * 滑行候选按上下文重新排序。[current] 是这一笔的候选；[previous] 是紧挨着的上一笔滑行的候选（首个为已上屏的词），
 * 为空表示上一个词不是滑行出来的、不能改；[before] 是再前面一个词（编辑框里光标前的词），没有为空。
 * 每种组合的代价 = 只算轨迹的代价 × [SHAPE_SCALE] + 双词代价（词频已在双词表里，不再加识别时的词频先验）；
 * 只在前 [PAIR_TOP] 个候选里组合，计算量是固定的。
 */
fun rankGlideChoices(
    lm: EnglishBigrams,
    before: String?,
    previous: List<GlideCandidate>?,
    current: List<GlideCandidate>,
): List<GlideChoice> {
    val words = current.take(PAIR_TOP)
    if (previous.isNullOrEmpty()) return words.map { word ->
        GlideChoice(null, word.word, word.shape * SHAPE_SCALE + lm.cost(before?.lowercase(), word.word.lowercase()))
    }.sortedBy { it.cost }
    return previous.take(PAIR_TOP).flatMap { prior ->
        val head = prior.word.lowercase()
        val priorCost = prior.shape * SHAPE_SCALE + lm.cost(before?.lowercase(), head)
        words.map { word -> GlideChoice(prior.word, word.word, priorCost + word.shape * SHAPE_SCALE + lm.cost(head, word.word.lowercase())) }
    }.sortedBy { it.cost }
}

const val PAIR_TOP = 5
/** 轨迹代价（单位：键宽）折成 nat 的倍数。 */
const val SHAPE_SCALE = 32f

/**
 * 滑行词表补充词（english_glide_words.txt，tools/glide_lab/build_english_bigrams.py 生成）：english.txt 是去掉脏话的网页常用词，
 * 没有 fuck、god、huh、alright、goodbye 这类常用词。每行「词 插入排名」，只给滑行和滑行双词表用，英文补全不读。
 */
fun glideExtraWords(lines: Sequence<String>): List<Pair<String, Int>> = lines.mapNotNull { line ->
    if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
    val parts = line.trim().split(' ')
    val rank = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
    parts[0] to rank
}.toList()

/**
 * 滑行用的英文缩写（english_contractions.txt，tools/glide_lab/build_english_bigrams.py 生成）。
 * 滑行词表和双词表都用去撇号的写法（im、dont），上屏时换成缩写（I'm、don't）；
 * 去撇号写法本身也常用的（were、well、hell）不换，缩写排在候选里。english.txt 没有的写法按给定排名插进词表。
 */
class EnglishContractions(lines: Sequence<String>) {
    private class Entry(val form: String, val rank: Int?, val offerOnly: Boolean)
    private val entries = LinkedHashMap<String, Entry>()

    init {
        for (line in lines) {
            if (line.isBlank() || line.startsWith("#")) continue
            val parts = line.trim().split(' ')
            if (parts.size < 2) continue
            entries[parts[0]] = Entry(parts[1], parts.getOrNull(2)?.toIntOrNull(), parts.last() == "?")
        }
    }

    /**
     * english.txt（去空白、小写、按第一次出现去重）加上插进去的写法和 [extra]（滑行补充词，见 [glideExtraWords]）；
     * 同一排名时插入的在前，缩写又在补充词前。
     */
    fun vocabulary(english: List<String>, extra: List<Pair<String, Int>> = emptyList()): List<String> {
        val words = english.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        val inserted = (entries.entries.mapNotNull { (stem, entry) -> entry.rank?.let { stem to it } } + extra).sortedBy { it.second }
        val known = words.toHashSet()
        val result = ArrayList<String>(words.size + inserted.size)
        var next = 0
        for ((index, word) in words.withIndex()) {
            while (next < inserted.size && inserted[next].second <= index) {
                inserted[next].first.takeIf { it !in known }?.let(result::add); next++
            }
            result += word
        }
        while (next < inserted.size) { inserted[next].first.takeIf { it !in known }?.let(result::add); next++ }
        return result
    }

    /** 上屏的写法：直接换的缩写换掉，I 开头的缩写大写（i'm → I'm）。 */
    fun display(word: String): String {
        val entry = entries[word]?.takeIf { !it.offerOnly } ?: return word
        return if (entry.form.startsWith("i'")) "I" + entry.form.drop(1) else entry.form
    }

    /** 表里所有缩写的写法（I 开头的大写，i'm → I'm），按表里的顺序；打了撇号时的英文补全用（见 EnglishContractionCompletion.withApostrophe）。 */
    fun forms(): List<String> = entries.values.map { if (it.form.startsWith("i'")) "I" + it.form.drop(1) else it.form }

    /** 原词也常用、只排进候选的缩写；没有为空。 */
    fun offer(word: String): String? = entries[word]?.takeIf { it.offerOnly }?.form
}
