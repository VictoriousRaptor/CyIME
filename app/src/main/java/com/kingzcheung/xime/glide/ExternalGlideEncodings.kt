package com.kingzcheung.xime.glide

/**
 * 导入的自定义编码方案（如万象拼音的自然码 26 键）滑行时按哪种编码出码（#303）。中文滑行只认得全拼和双拼的划法，
 * 导入方案的编码规则读不出来，由用户在「键盘布局 › 滑行输入」里按方案选：全拼，或某一套双拼。没选的方案照旧不滑行。
 *
 * 存在一个字符串设置里，每行「方案编号\t编码」；编码是 [PINYIN] 或双拼方案编号（DoublePinyinScheme.id）。
 */
object ExternalGlideEncodings {
    const val KEY = "glide_external_encodings"
    const val PINYIN = "pinyin"

    fun parse(value: String?): Map<String, String> = value.orEmpty().lineSequence().mapNotNull { line ->
        val tab = line.indexOf('\t')
        if (tab <= 0 || tab >= line.length - 1) null else line.substring(0, tab) to line.substring(tab + 1)
    }.toMap()

    fun format(encodings: Map<String, String>): String = encodings.entries
        .filter { (id, encoding) -> valid(id) && valid(encoding) }
        .sortedBy { it.key }.joinToString("\n") { "${it.key}\t${it.value}" }

    /** 改一个方案的编码；[encoding] 为空就是不滑行。 */
    fun with(encodings: Map<String, String>, schemaId: String, encoding: String?): Map<String, String> =
        if (encoding.isNullOrEmpty()) encodings - schemaId else encodings + (schemaId to encoding)

    private fun valid(s: String) = s.isNotEmpty() && '\t' !in s && '\n' !in s
}
