package com.kingzcheung.xime.rime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 简/繁开关要真的能转：每个方案引用的 OpenCC 配置和它用到的 .ocd2 表都要随包，
 * 否则 librime 加载失败、静默不转（1.15.6 之前 rime_ice 的「繁」就是这样）。
 * 引擎实测见 tools/ime_lab/host_replay/TraditionalOutputProbe.java。
 */
class OpenCcAssetsTest {
    private val opencc = File("src/main/assets/rime_chinese/opencc")
    // 雾凇自带的 opencc（表情 emoji.json + emoji.txt/others.txt）由 tasks-chinese.gradle.kts 生成到这里，
    // 和 rime_chinese/opencc 一起由 BundledRimeSync 装进同一个 rime/opencc，九键、14 键方案也能引用。
    private val iceOpencc = File("build/generated/chinese-assets/rime_ice/opencc")
    private val configPattern = Regex("""^\s*opencc_config:\s*(\S+\.json)""", RegexOption.MULTILINE)
    private val tablePattern = Regex(""""file":\s*"([^"]+)"""")

    private fun configsOf(text: String) = configPattern.findAll(text).map { it.groupValues[1] }.toList()

    @Test fun everyTraditionalizeConfigShipsWithItsTables() {
        val schemas = File("src/main/assets/rime_chinese").listFiles { f -> f.name.endsWith(".schema.yaml") }!!.toList()
        val referenced = schemas.flatMap { configsOf(it.readText()) }.toSet()
        assertTrue("九键、14 键应引用台湾正体配置", "cyime_s2tw.json" in referenced)
        // 26 键和双拼由 tasks-chinese.gradle.kts 从 rime-ice 生成，同样改成这张表。
        assertTrue(File("build-logic/tasks-chinese.gradle.kts").readText().contains("opencc_config: cyime_s2tw.json"))
        for (config in referenced + setOf("s2t.json", "s2tw.json", "s2hk.json", "cyime_s2tw.json")) {
            val dir = listOf(opencc, iceOpencc).firstOrNull { File(it, config).isFile }
            assertTrue("缺少 $config", dir != null)
            val file = File(dir!!, config)
            val tables = tablePattern.findAll(file.readText()).map { it.groupValues[1] }.toList()
            assertTrue("$config 没有引用任何表", tables.isNotEmpty())
            tables.forEach { assertTrue("$config 用到的 $it 没有随包", File(dir, it).isFile) }
        }
    }

    @Test fun taiwanFormsComeLastInTheChain() {
        val chain = File(opencc, "cyime_s2tw.json").readText()
        assertTrue(chain.indexOf("CyimeTWPhrases.ocd2") > chain.indexOf("TWVariants.ocd2"))
        val forms = File("../tools/opencc/cyime_tw_phrases.txt").readLines().associate { it.substringBefore('\t') to it.substringAfter('\t') }
        assertEquals("台", forms["臺"])
        assertEquals("計畫", forms["計劃"])
        assertEquals("了解", forms["瞭解"])
    }

    // 繁体输出时用户写进「我的短语」的词（user_table）不经通用表，改走 cyime_s2tw_user.json：
    // 余宛如这类姓「余」的名字照原样出；其余候选仍走 traditionalize，「其余」「十余年」照旧转「餘」。
    @Test fun userPhrasesKeepSurnameForms() {
        val user = File(opencc, "cyime_s2tw_user.json").readText()
        assertTrue(user.indexOf("CyimeUserKeep.txt") in 0 until user.indexOf("STCharacters.ocd2"))
        assertEquals("余\t余", File(opencc, "CyimeUserKeep.txt").readLines().first())
        assertTrue(File(opencc, "cyime_s2tw.json").readText().contains("CyimeSurnames.txt"))
        val schemas = listOf("t9_pinyin", "pinyin_14jian", "double_pinyin_flypy_14jian")
        for (name in schemas) {
            val text = File("src/main/assets/rime_chinese/$name.schema.yaml").readText()
            assertTrue("$name 缺 traditionalize_user", text.contains("- simplifier@traditionalize_user"))
            assertTrue("$name 应引用 cyime_s2tw_user.json", "cyime_s2tw_user.json" in configsOf(text))
            assertTrue("$name 的 traditionalize 应排除 user_table", text.contains("excluded_types: [user_table]"))
        }
        assertTrue(File("build-logic/tasks-chinese.gradle.kts").readText().contains("cyime_s2tw_user.json"))
    }
}
