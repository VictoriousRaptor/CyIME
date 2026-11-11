package com.kingzcheung.xime.rime

/**
 * 长按首选整句时拆出的词（点一个词只重选它）。[components] 是引擎整句的组成，每段「词:权重:长度;」。
 * 拼起来等于显示的 [shown] 就照用；繁体输出等改了字形、字数不变时按各词字数切 [shown]；
 * 对不上或不足两个词时为 null，长按卡片就不出拆词。
 */
internal fun splitSentenceWords(shown: String, components: String): List<String>? {
    val parts = components.split(';').filter { it.isNotEmpty() }.map { part ->
        val fields = part.split(':')
        if (fields.size >= 3) fields.dropLast(2).joinToString(":") else part
    }
    if (parts.size < 2 || parts.any { it.isEmpty() }) return null
    if (parts.joinToString("") == shown) return parts
    val lengths = parts.map { it.codePointCount(0, it.length) }
    if (lengths.sum() != shown.codePointCount(0, shown.length)) return null
    var offset = 0
    return lengths.map { count ->
        val end = shown.offsetByCodePoints(offset, count)
        shown.substring(offset, end).also { offset = end }
    }
}

/** 九键候选注释（拼音）里的字母数，即这个词占几个数字键；注释里有拼音以外的字时为 0（不挪光标）。 */
internal fun t9KeysOfReading(comment: String): Int {
    val reading = comment.trim()
    if (reading.isEmpty() || reading.any { it !in 'a'..'z' && it != 'ü' && it != ' ' && it != '\'' }) return 0
    return reading.count { it in 'a'..'z' || it == 'ü' }
}
