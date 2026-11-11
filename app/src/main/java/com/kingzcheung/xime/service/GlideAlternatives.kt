package com.kingzcheung.xime.service

import com.kingzcheung.xime.rime.RimeCandidate

/**
 * 中文滑行的备选（#294：只出一个结果；抬手落在两键之间，小鹤 dj 但 出成 dh 当）。
 *
 * 一笔识别出的前几个编码里，首选照常交给 Rime；其后 [MAX] 个各在独立会话里预览一次，
 * 取各自的第一个候选，插在候选栏首选之后。点它把这一笔的编码换成那个编码（组合还在，可以接着打），
 * 原来的首选换到备选里，点错了能点回来。编码一变（接着打字、删字、上屏）就不再给。
 */
internal class GlideAlternatives {
    /** [input] 是换成这个编码后的整个输入，[caret] 是换完后的光标。 */
    data class Choice(val text: String, val input: String, val caret: Int)

    /**
     * [input] 是这一笔打完后的整个输入；在它出现之前（这一笔的编码还在一个个键地打）不作废，
     * 出现过以后输入一变就作废。
     */
    data class Entry(
        val input: String,
        val caret: Int,
        val schema: String,
        val session: Long,
        val choices: List<Choice>,
        val seen: Boolean = false,
    )

    @Volatile var entry: Entry? = null

    fun clear() { entry = null }

    /** 首选之后插进备选；不是这一笔的输入、不是第一页、方案或输入框换了时原样返回。 */
    fun inject(
        base: CandidateTransformResult?,
        inputText: String,
        engineCandidates: List<RimeCandidate>,
        firstPage: Boolean,
        schema: String,
        session: Long,
    ): CandidateTransformResult? {
        val current = entry ?: return base
        if (current.schema != schema || current.session != session) { entry = null; return base }
        if (current.input != inputText) {
            if (current.seen) entry = null
            return base
        }
        if (!current.seen) entry = current.copy(seen = true)
        if (!firstPage) return base
        val candidates = base?.candidates ?: engineCandidates
        if (candidates.isEmpty()) return base
        val actions = base?.actions?.takeIf { it.size == candidates.size } ?: candidates.indices.map(CandidateAction::engine)
        val shown = candidates.mapTo(HashSet()) { it.text }
        val extra = current.choices.filter { it.text.isNotEmpty() && shown.add(it.text) }
        if (extra.isEmpty()) return base
        return CandidateTransformResult(
            candidates.take(1) + extra.map { RimeCandidate(it.text, "") } + candidates.drop(1),
            actions.take(1) + extra.map { CandidateAction.glide(it.text, it.input) } + actions.drop(1),
        )
    }

    /** 点了备选：返回要换成的那个，并把原来的首选 [firstText] 换进备选。不是当前这一笔的备选时为空。 */
    fun choose(input: String, firstText: String?): Choice? {
        val current = entry ?: return null
        val choice = current.choices.firstOrNull { it.input == input } ?: return null
        val original = firstText?.takeIf { it.isNotEmpty() }?.let { Choice(it, current.input, current.caret) }
        entry = current.copy(input = choice.input, caret = choice.caret, seen = false,
            choices = listOfNotNull(original) + current.choices.filter { it !== choice })
        return choice
    }

    companion object {
        /** 首选之外最多给几个：每个都要在独立会话里预览一次，抬手到出字会多等一点。 */
        const val MAX = 2

        /** 这一笔的编码插在光标处：输入不空、末尾不是分隔符时先补一个 '（与逐键打入时相同）。 */
        fun inserted(input: String, caret: Int, code: String): Pair<String, Int> {
            val at = caret.coerceIn(0, input.length)
            val separator = if (input.isNotEmpty() && input.last() != '\'') "'" else ""
            return input.substring(0, at) + separator + code + input.substring(at) to at + separator.length + code.length
        }
    }
}
