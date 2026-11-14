package com.kingzcheung.xime.rime

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 设置 › 语言与方案 › 中文输出 里的时间日期、表情、符号名，注音也要出（用户反馈 11-12：注音打「开心」「时间」不出）。
 * 三张表按简体词查，必须排在转正体（simplifier@zh_tw）之前；14 键是同一套滤镜。
 */
class ZhuyinSchemaFeaturesTest {
    private fun filters(name: String): List<String> {
        val text = File("src/main/assets/rime_chinese/$name.schema.yaml").readText()
        val block = text.substringAfter("  filters:\n").lineSequence()
            .takeWhile { it.startsWith("    ") || it.trimStart().startsWith("#") }
        return block.map { it.trim() }.filter { it.startsWith("- ") }.map { it.removePrefix("- ") }.toList()
    }

    @Test fun zhuyinRunsTheChineseOutputFiltersBeforeTaiwanForms() {
        val zhuyin = filters("cyime_zhuyin")
        val k14 = filters("pinyin_14jian")
        val zhTw = zhuyin.indexOf("simplifier@zh_tw")
        assertTrue(zhTw >= 0)
        for (feature in listOf("cyime_datetime", "simplifier@emoji", "simplifier@cyime_symbols")) {
            assertTrue("14 键应有 $feature", feature in k14)
            val at = zhuyin.indexOf(feature)
            assertTrue("注音缺 $feature", at >= 0)
            assertTrue("$feature 要在 zh_tw 之前（表按简体词查）", at < zhTw)
        }
        val text = File("src/main/assets/rime_chinese/cyime_zhuyin.schema.yaml").readText()
        assertTrue(text.contains("\nemoji:\n  option_name: emoji\n  opencc_config: emoji.json\n"))
        assertTrue(text.contains("\ncyime_symbols:\n  option_name: cyime_symbol_names\n  opencc_config: cyime_symbols.json\n"))
    }
}
