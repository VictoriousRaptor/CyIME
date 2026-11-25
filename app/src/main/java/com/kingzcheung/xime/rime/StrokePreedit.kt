package com.kingzcheung.xime.rime

/** 横竖撇点折：和笔画方案补丁的 xlit/hspnz/⼀⼁⼃⼂乛/、lua 里的 STROKE_GLYPHS 一样（康熙部首，折用键面上的乛，#412）。 */
private val STROKE_GLYPHS = mapOf('h' to '⼀', 's' to '⼁', 'p' to '⼃', 'n' to '⼂', 'z' to '乛')

/**
 * 「笔画编码显示成 一丨丿丶乛」打开时显示的编码：h s p n z 换成笔画，分词号、通配符 * 和已经选出的汉字原样留着。
 * 对上候选的那段方案补丁已经换好，这里补上 Rime 照原样给的那段字母（11-18：输入串不能一半字母一半笔画）。
 */
internal fun strokeGlyphs(preedit: String): String =
    buildString(preedit.length) { preedit.forEach { append(STROKE_GLYPHS[it] ?: it) } }

private val STROKE_LETTERS = STROKE_GLYPHS.entries.associate { (letter, glyph) -> glyph to letter }

/** [strokeGlyphs] 反过来：笔画换回 h s p n z，别的原样。一个对一个，位置不变。 */
internal fun strokeLetters(text: String): String =
    buildString(text.length) { text.forEach { append(STROKE_LETTERS[it] ?: it) } }

/**
 * 编码编辑条里显示的笔画编码：组字区现在显示的是笔画（「笔画编码显示成 一丨丿丶乛」开着）时，编辑条也换成笔画，
 * 和组字区一样（11-22 他转来的笔画用户改动；折用键面上的乛，#412）；关着时照旧字母。* 和分词号原样。
 * 一个字母换一个笔画，光标位置、点哪改哪都不受影响。
 */
internal fun strokeEditorText(code: String, shownPreedit: String): String =
    if (shownPreedit.any { it in STROKE_LETTERS }) strokeGlyphs(code) else code

/**
 * 笔画分词打出的词（一个分词段一个字，11-18）拆成一个个字：长按首选时一个字一个按钮，点哪个字就重选哪个字，
 * 和拼音九键等方案长按首选拆词是同一个操作（11-23 群友：笔画也要能拆开改）。[code] 是这个候选盖住的那段编码。
 * 段数和字数对不上、有空段（还没打笔画的字）、带通配符或别的字符时为 null（不拆）。
 */
internal fun strokeSegmentChars(shown: String, code: String): List<String>? {
    if (code.isEmpty() || code.any { it !in "hspnz'" }) return null
    val parts = code.split('\'')
    if (parts.size < 2 || parts.any { it.isEmpty() }) return null
    val chars = ArrayList<String>(parts.size)
    var i = 0
    while (i < shown.length) {
        val next = i + Character.charCount(shown.codePointAt(i))
        chars += shown.substring(i, next)
        i = next
    }
    return chars.takeIf { it.size == parts.size }
}

/** 第 [index] 段（从 0 数）笔画在 [code] 里的结束位置：这段最后一笔后面、分词号前面。 */
internal fun strokeSegmentEnd(code: String, index: Int): Int {
    var end = -1
    repeat(index + 1) { end = code.indexOf('\'', end + 1).let { if (it < 0) code.length else it } }
    return end
}
