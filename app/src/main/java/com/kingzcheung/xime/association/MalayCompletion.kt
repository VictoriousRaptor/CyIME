package com.kingzcheung.xime.association

/**
 * 马来语补全（用户 11-16 要的马来语键盘）。
 * - 叠词：马来语复数、强调都用叠词，中间是连字符（kanak-kanak、rama-rama、orang-orang）。连字符接进正在打的词，
 *   打 kanak- 时第一个候选就是 kanak-kanak；接着打 kanak-k 也先给它，后面按连字符后面那段照常补（sayur-m → sayur-mayur）。
 * - 马来西亚、新加坡打字常夹英文：马来语词表补不满时用英文补全补上（meet → meeting）。马来语词排在前面。
 * 补全词表里没有带连字符的词（字幕词频按连字符拆开了）。
 */
internal object MalayCompletion {
    const val HYPHEN = '-'

    suspend fun complete(
        typed: String,
        limit: Int,
        malay: suspend (String) -> List<String>,
        english: suspend (String, Int) -> List<String>,
    ): List<String> {
        if (typed.isEmpty() || limit <= 0) return emptyList()
        if (HYPHEN in typed) return reduplicated(typed, limit, malay)
        val words = malay(typed)
        if (words.size >= limit) return words.take(limit)
        val seen = words.mapTo(HashSet()) { it.lowercase() }
        return words + english(typed, limit).filter { it != typed && seen.add(it.lowercase()) }.take(limit - words.size)
    }

    private suspend fun reduplicated(typed: String, limit: Int, malay: suspend (String) -> List<String>): List<String> {
        val tail = typed.substringAfterLast(HYPHEN)
        val head = typed.substring(0, typed.length - tail.length)
        val word = head.dropLast(1).substringAfterLast(HYPHEN)
        if (word.isEmpty() || !word.all { it.isLetter() }) return emptyList()
        // 后半个词跟着前半个的大小写：Kanak- 补 Kanak-kanak，KANAK- 补 KANAK-KANAK。
        val again = when {
            tail.isNotEmpty() -> WordLexicon.cased(word.lowercase(), tail)
            word.length > 1 && word.all { it.isUpperCase() } -> word
            else -> word.lowercase()
        }
        val repeat = (head + again).takeIf { again.startsWith(tail, ignoreCase = true) }
        val rest = if (tail.isEmpty()) emptyList() else malay(tail).map { head + it }
        return (listOfNotNull(repeat) + rest).filter { it != typed }.distinct().take(limit)
    }
}
