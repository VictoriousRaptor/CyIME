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

/**
 * 九键首选上拖选了第 [start] 到 [end] 个字（不含 [end]）时怎么重选：从拖选开头所在的词起重选（前面的词照原样选定），
 * 光标放到拖选的最后一个字后面，候选就只给拖选的这几个字（11-20，原来九键只有长按拆词、一次只能换一个词）。
 * [reading] 是整句的拼音（首选的注释），一字一个音节时按拖选末尾算数字键数；对不上时为 -1，光标照长按拆词放到开头那个词后面。
 * 返回 (开头所在的词序号, 从这个词开头到拖选末尾的数字键数或 -1)；拖选超出整句或词里有 BMP 以外的字时为 null。
 */
internal fun t9DragReselect(words: List<String>, reading: String, start: Int, end: Int): Pair<Int, Int>? {
    if (words.isEmpty() || words.any { it.isEmpty() || it.length != it.codePointCount(0, it.length) }) return null
    val total = words.sumOf { it.length }
    if (start < 0 || end <= start || end > total) return null
    var offset = 0
    var first = -1
    var firstStart = 0
    for ((index, word) in words.withIndex()) {
        if (start < offset + word.length) { first = index; firstStart = offset; break }
        offset += word.length
    }
    if (first < 0) return null
    val syllables = reading.trim().split(' ', '\'').filter { it.isNotEmpty() }
    if (syllables.size != total || syllables.any { s -> s.any { it !in 'a'..'z' && it != 'ü' } }) return first to -1
    return first to syllables.subList(firstStart, end).sumOf { it.length }
}
