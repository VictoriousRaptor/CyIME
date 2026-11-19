package com.kingzcheung.xime.glide

import com.kingzcheung.xime.speech.WordLanguageModel
import com.kingzcheung.xime.speech.WordNgram
import java.io.File

/**
 * 中文滑行按前文选编码（11-16：U、I、O 挨着，「一块」「愉快」划出来几乎一样，只看形状和词频分不开）。
 *
 * 组合为空时，光标前已上屏的文字里取最后一个词，查词组模型（rime/t9_ngram.lm，九键和整句共用的那份）：
 * 候选编码最常用的词跟在它后面比单独出现更常见多少（ln P(词|前一个词) − ln P(词)），常见多少就减多少代价。
 * 只加分不扣分：前文没见过的搭配照旧按形状和词频排；没装词组模型、没有前文时结果不变。
 * 权重 [WEIGHT] 用两份日常句子（daily_chat、everyday_clauses）的模拟划线选定，见 swipe-uio-1116。
 */
internal object ChineseGlideContext {
    /** 每 1 nat 的搭配加成抵多少代价（键宽）；词表名次的先验整段只占 [GlideDecoder.PRIOR_WEIGHT]。 */
    const val WEIGHT = 0.015f
    /** 加成上限（nat），免得一个罕见搭配把形状差很多的词顶上来。 */
    const val MAX_LIFT = 6.0
    /** 前文最多往前看几个字找“前一个词”。 */
    const val HISTORY_CHARS = 4

    /** [left] 是光标前的已上屏文字，[wordOf] 把编码换成它最常用的词。 */
    fun rerank(candidates: List<GlideCandidate>, left: String?, wordOf: (String) -> String?, lm: WordLanguageModel?): List<GlideCandidate> {
        if (lm == null || left.isNullOrEmpty() || candidates.size < 2) return candidates
        val history = history(left, lm)
        if (history < 0) return candidates
        var changed = false
        val scored = candidates.map { candidate ->
            val lift = if (candidate.listed) lift(history, wordOf(candidate.word), lm) else 0.0
            if (lift <= 0.0) candidate else { changed = true; candidate.copy(cost = candidate.cost - (WEIGHT * lift).toFloat()) }
        }
        return if (!changed) candidates else scored.sortedWith(compareBy<GlideCandidate> { it.cost }.thenBy { it.word })
    }

    /** 前文末尾最长的、词组模型认识的词；都不认识时为 -1。 */
    internal fun history(left: String, lm: WordLanguageModel): Int {
        val text = left.trimEnd()
        for (n in minOf(HISTORY_CHARS, text.length) downTo 1) {
            val id = lm.id(text.takeLast(n))
            if (id >= 0) return id
        }
        return -1
    }

    private fun lift(history: Int, word: String?, lm: WordLanguageModel): Double {
        val id = word?.let(lm::id) ?: return 0.0
        if (id < 0) return 0.0
        return (lm.bigram(history, id) - lm.unigram(id)).coerceIn(0.0, MAX_LIFT)
    }

    /**
     * 抬手时用哪份前文。开着实时预览时，预览写进输入框的组合文字（拼音或词）也在光标前，抬手时再读会把它当前文，
     * 预览和抬手后就按两份前文选编码；这一笔开过预览（[previewGesture] 是这一笔）就用预览开头取的 [previewLeft]，没开过才现读。
     */
    fun leftAtRelease(gesture: Int, previewGesture: Int, previewLeft: String?, read: () -> String?): String? =
        if (gesture >= 0 && previewGesture == gesture) previewLeft else read()

    @Volatile private var cached: Pair<String, WordNgram>? = null

    /** rime/t9_ngram.lm（完整或精简版）；没装或读不了时为空。按路径、大小、修改时间缓存，换了档就重新打开。 */
    fun model(filesDir: File): WordLanguageModel? {
        val file = File(filesDir, "rime/t9_ngram.lm")
        if (!file.isFile) return null
        val key = "${file.absolutePath}:${file.length()}:${file.lastModified()}"
        cached?.let { if (it.first == key) return it.second }
        return runCatching { WordNgram.open(file) }.getOrNull()?.also { cached = key to it }
    }
}
