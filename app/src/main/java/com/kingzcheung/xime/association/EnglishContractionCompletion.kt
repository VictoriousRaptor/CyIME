package com.kingzcheung.xime.association

/**
 * 英文补全里的缩写（表是滑行共用的 english_contractions.txt）：
 * - 打全了去撇号的写法（im、whats、its）：缩写排在补全第一个，候选栏最前面仍是打的原词（im、its）；
 * - 补全里冒出来的去撇号写法本身不是词的（dont、whats）换成缩写，本身也是常用词的（its、were、well、ill）原样留着。
 * 不认识的写法原样返回，中文里夹英文（Rime）不走这里。
 */
internal object EnglishContractionCompletion {
    /** 本身也是常用英文词的去撇号写法：补全里不换成缩写。 */
    private val PLAIN_WORDS = setOf("its", "were", "well", "ill", "hell", "shell", "id", "wed", "shed", "lets", "cant", "wont")

    /** [formOf]：小写去撇号写法 → 缩写（i'm 开头已大写成 I'm），不是缩写为空。 */
    fun apply(typed: String, completions: List<String>, limit: Int, formOf: (String) -> String?): List<String> {
        if (typed.isEmpty() || limit <= 0) return completions
        val key = typed.lowercase()
        val typedForm = formOf(key)?.let { cased(it, typed) }
        val shown = completions.map { word ->
            val lower = word.lowercase()
            if (lower in PLAIN_WORDS) word else formOf(lower)?.let { cased(it, typed) } ?: word
        }
        if (typedForm == null && shown == completions) return completions
        val result = ArrayList<String>(completions.size + 1)
        typedForm?.let(result::add)
        for ((index, word) in shown.withIndex()) {
            // 原词已经是候选栏第一个，补全里不再重复。
            if (typedForm != null && completions[index].lowercase() == key) continue
            if (word !in result) result += word
        }
        return result.take(limit)
    }

    /**
     * 打的词里已经有撇号（what'、don't）：缩写表里以它开头的写法（[forms]，I'm 开头已大写）跟着大小写给出，
     * 和打的一模一样的不再重复（候选栏最前面就是打的原词）。
     */
    fun withApostrophe(typed: String, forms: List<String>, limit: Int): List<String> {
        if (typed.isEmpty() || limit <= 0) return emptyList()
        val key = typed.lowercase()
        // 只打了一个字母加撇号（I'、i'）看不出是不是全大写，只跟首字母。
        val oneLetter = typed.count(Char::isLetter) <= 1
        return forms.filter { it.lowercase().startsWith(key) && it.lowercase() != key }
            .map { if (!oneLetter) cased(it, typed) else if (typed.first().isUpperCase()) it.replaceFirstChar(Char::uppercaseChar) else it }
            .distinct().take(limit)
    }

    /** 跟着打的大小写：全大写 → DON'T，首字母大写 → Don't。 */
    private fun cased(form: String, typed: String): String = when {
        typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> form.uppercase()
        typed.first().isUpperCase() -> form.replaceFirstChar(Char::uppercaseChar)
        else -> form
    }
}
