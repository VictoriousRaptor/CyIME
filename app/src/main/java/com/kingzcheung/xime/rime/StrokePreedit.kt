package com.kingzcheung.xime.rime

/** 横竖撇点折：和笔画方案补丁的 xlit/hspnz/⼀⼁⼃⼂乛/、lua 里的 STROKE_GLYPHS 一样（康熙部首，折用键面上的乛，#412）。 */
private val STROKE_GLYPHS = mapOf('h' to '⼀', 's' to '⼁', 'p' to '⼃', 'n' to '⼂', 'z' to '乛')

/**
 * 「笔画编码显示成 一丨丿丶乛」打开时显示的编码：h s p n z 换成笔画，分词号、通配符 * 和已经选出的汉字原样留着。
 * 对上候选的那段方案补丁已经换好，这里补上 Rime 照原样给的那段字母（11-18：输入串不能一半字母一半笔画）。
 */
internal fun strokeGlyphs(preedit: String): String =
    buildString(preedit.length) { preedit.forEach { append(STROKE_GLYPHS[it] ?: it) } }
