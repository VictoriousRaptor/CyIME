package com.kingzcheung.xime.settings

/**
 * 「我的短语」从文件批量导入（#298：Gboard 个人词库里「小米SU7」「Bootloader模式」这类中英混合词，
 * 词库导入收不了，只能一条条手加短语）。
 *
 * 认两种文件：
 * - Gboard 导出的个人词库（第一行 `# Gboard Dictionary`）：每行「快捷码 Tab 词 Tab 语言」，快捷码当编码；
 * - 其他：每行「短语 Tab 编码」（可以再跟一列权重，和 custom_phrase.txt 一样），或用逗号隔开。
 * 编码只收字母（可带空格、隔音符 '），转成小写；没有编码或编码里有别的字符的行跳过，并列出来。
 */
object PhraseImport {
    data class Result(val entries: List<DictEntry>, val skipped: List<String>)

    private val codePattern = Regex("[a-z][a-z' ]*")

    fun parse(text: String): Result {
        val lines = text.removePrefix("﻿").lines().map { it.trimEnd('\r') }
        val gboard = lines.firstOrNull()?.startsWith("# Gboard Dictionary") == true
        val entries = mutableListOf<DictEntry>()
        val skipped = mutableListOf<String>()
        for (line in lines) {
            if (line.isBlank() || line.startsWith("#")) continue
            val cols = if ('\t' in line) line.split('\t') else line.split(',')
            val word = (if (gboard) cols.getOrNull(1) else cols.getOrNull(0))?.trim().orEmpty()
            val code = (if (gboard) cols.getOrNull(0) else cols.getOrNull(1))?.trim()?.lowercase().orEmpty()
            if (word.isEmpty() || !codePattern.matches(code)) { skipped += line.trim(); continue }
            val weight = if (gboard) null else cols.getOrNull(2)?.trim()?.toIntOrNull()
            entries += DictEntry(PhraseLineBreaks.stored(word), code.trim(), weight)
        }
        return Result(entries, skipped)
    }

    /** 已有的短语（同一个词、同一个编码）不再加一遍；文件里自己重复的也只加一次。 */
    fun merge(current: List<DictEntry>, imported: List<DictEntry>): List<DictEntry> {
        val known = current.map { it.word to it.code }.toMutableSet()
        return imported.filter { known.add(it.word to it.code) }
    }

    /** 导入以后显示的一句话；跳过的行列出前 [SHOWN_SKIPPED] 行。 */
    fun message(added: Int, existing: Int, skipped: List<String>): String = buildString {
        append("已导入 $added 条短语")
        if (existing > 0) append("，$existing 条已经有了")
        if (skipped.isNotEmpty()) {
            append("，跳过 ${skipped.size} 行（没有编码，或编码不是字母）：")
            append(skipped.take(SHOWN_SKIPPED).joinToString("、"))
            if (skipped.size > SHOWN_SKIPPED) append("…")
        }
    }

    const val SHOWN_SKIPPED = 5
}
