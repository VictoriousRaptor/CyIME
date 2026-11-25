package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** #298：中英混合词、常用串用「我的短语」批量导入；导不进的行列出来。 */
class PhraseImportTest {
    @Test fun gboardExportUsesTheShortcutAsCode() {
        val text = "# Gboard Dictionary version:1\nxmsu\t小米SU7\tzh-CN\nbl\tBootloader模式\tzh-CN\n\t没有快捷码\tzh-CN\n"
        val result = PhraseImport.parse(text)
        assertEquals(listOf(DictEntry("小米SU7", "xmsu"), DictEntry("Bootloader模式", "bl")), result.entries)
        assertEquals(listOf("没有快捷码\tzh-CN"), result.skipped.map { it.trimStart() })
    }

    @Test fun phraseThenCodeWithTabsOrCommas() {
        val text = "﻿B超\tbchao\t5\r\nC罗,cluo\n# 注释\n赛博朋克2077\tsaibo2077\n维生素C\n\n"
        val result = PhraseImport.parse(text)
        assertEquals(listOf(DictEntry("B超", "bchao", 5), DictEntry("C罗", "cluo")), result.entries)
        assertEquals(listOf("赛博朋克2077\tsaibo2077", "维生素C"), result.skipped)
    }

    @Test fun codesAreLowercasedAndExistingPhrasesNotAddedTwice() {
        val parsed = PhraseImport.parse("AI智能体\tAIZNT\nAI智能体\taiznt\n123云盘\tyunpan\n").entries
        assertEquals(listOf(DictEntry("AI智能体", "aiznt"), DictEntry("AI智能体", "aiznt"), DictEntry("123云盘", "yunpan")), parsed)
        val added = PhraseImport.merge(listOf(DictEntry("123云盘", "yunpan")), parsed)
        assertEquals(listOf(DictEntry("AI智能体", "aiznt")), added)
    }

    @Test fun messageListsTheFirstSkippedLines() {
        assertEquals("已导入 2 条短语", PhraseImport.message(2, 0, emptyList()))
        assertEquals("已导入 0 条短语，1 条已经有了，跳过 6 行（没有编码，或编码不是字母）：a、b、c、d、e…",
            PhraseImport.message(0, 1, listOf("a", "b", "c", "d", "e", "f")))
    }
}
