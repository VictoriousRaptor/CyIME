package com.kingzcheung.xime.service

/**
 * 九键滑行的备选（#214：高兴 划成 高中，晚安 划成 早餐，中国 划成 英国）。
 *
 * 九键上只差连按同一个键的词划出来是同一条线，首选只能按词频猜。一笔识别出的首选照常打入；
 * 其后几个编码各自最常用的词插在候选栏首选之后，点了直接上屏那个词。
 * 只在这一笔就是整个组合时给（划之前已经打了字的不给）；组合一变（接着打字、删字、上屏）就不再给。
 */
internal class T9GlideAlternatives {
    /**
     * [digits] 是这一笔打入的数字串。它还在一个个键地打入时（输入是它的前缀）不作废；
     * 出现过以后输入一变，或输入不是它的前缀（划之前已有组合）就作废。
     */
    data class Entry(
        val digits: String,
        val words: List<String>,
        val schema: String,
        val session: Long,
        val seen: Boolean = false,
    )

    @Volatile var entry: Entry? = null

    fun clear() { entry = null }

    /** 这一笔打入之前登记；没有备选时清掉上一笔的。 */
    fun offer(digits: String, words: List<String>, schema: String, session: Long) {
        val choices = words.filter { it.isNotEmpty() }.distinct().take(MAX)
        entry = if (digits.isEmpty() || choices.isEmpty()) null else Entry(digits, choices, schema, session)
    }

    /**
     * 当前输入正好是这一笔时，返回要插在首选之后的备选（已在候选里的词不重复给）；否则为空。
     * 在 T9 后台线程调用。
     */
    fun injections(
        inputText: String,
        candidates: List<String>,
        firstPage: Boolean,
        schema: String,
        session: Long,
    ): List<T9CandidateInjection> {
        val current = entry ?: return emptyList()
        if (current.schema != schema || current.session != session) { entry = null; return emptyList() }
        val digits = digitsOf(inputText)
        if (digits != current.digits) {
            if (current.seen || digits == null || !current.digits.startsWith(digits)) entry = null
            return emptyList()
        }
        if (!current.seen) entry = current.copy(seen = true)
        if (!firstPage) return emptyList()
        val shown = candidates.toHashSet()
        return current.words.filter { shown.add(it) }.map {
            T9CandidateInjection(null, PluginCandidateSnapshot(it, ""), glideInput = current.digits)
        }
    }

    /** 点了备选时核对：这一笔（[digits]）还登记着，并且已经整笔出现过（输入一变就会作废）。 */
    fun matches(digits: String, schema: String, session: Long): Boolean {
        val current = entry ?: return false
        return current.seen && current.digits == digits && current.schema == schema && current.session == session
    }

    companion object {
        /** 首选之外最多给几个。 */
        const val MAX = 2

        /** 九键输入换成数字串（去掉音节分隔）；含别的字符（已选了拼音、字母）时为空。 */
        fun digitsOf(input: String): String? {
            if (input.isEmpty()) return null
            val digits = StringBuilder(input.length)
            for (c in input) when (c) {
                in '2'..'9' -> digits.append(c)
                '\'', ' ' -> Unit
                else -> return null
            }
            return digits.toString().ifEmpty { null }
        }
    }
}
