package com.kingzcheung.xime.rime

import org.junit.Assert.assertEquals
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

    /** default.custom.yaml 标点表里每个键的写法：{ 是字典，[ 是列表，其余是字符串。 */
    private fun defaultPunctuationKinds(): Map<String, Char> {
        val text = File("src/main/assets/default.custom.yaml").readText().replace("\r\n", "\n")
        val kinds = mutableMapOf<String, Char>()
        var shape: String? = null
        for (line in text.substringAfter("\n  punctuator:\n").lineSequence()) {
            if (line.isNotBlank() && !line.startsWith("    ")) break
            when {
                line.startsWith("    ") && !line.startsWith("      ") ->
                    shape = line.trim().substringBefore(":").takeIf { it == "full_shape" || it == "half_shape" }
                shape != null && line.startsWith("      ") -> {
                    val entry = Regex("""^\s+("(?:\\.|[^"])*"|[^:\s]+):\s*(\S)""").find(line) ?: continue
                    val key = entry.groupValues[1].removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\")
                    kinds["$shape/$key"] = entry.groupValues[2][0]
                }
            }
        }
        return kinds
    }

    /**
     * 方案的标点段 import_preset: default 时，librime 把方案自己的标点整段并进 default.custom.yaml 的标点表：
     * 那边是列表或字符串的键，方案里写成 { commit: … } 就是往非字典里并字典，报 incompatible node type，
     * 整个方案编不出来（11-22 注音：全角 < > 写成 commit，新装的人注音没有候选，按键原样上屏）。
     */
    @Test fun punctuationPatchesNeverMergeAMapIntoADefaultListOrString() {
        val defaults = defaultPunctuationKinds()
        assertEquals('[', defaults["full_shape/<"])
        assertEquals('{', defaults["half_shape/<"])
        val schemas = File("src/main/assets/rime_chinese").listFiles { f -> f.name.endsWith(".schema.yaml") }!!
        var checked = 0
        for (file in schemas) {
            val text = file.readText().replace("\r\n", "\n")
            if (!text.contains("\npunctuator:\n")) continue
            val block = text.substringAfter("\npunctuator:\n").lineSequence()
                .takeWhile { it.isBlank() || it.startsWith(" ") }.toList()
            if (block.none { it.trim() == "import_preset: default" }) continue
            val patch = block.dropWhile { it.trim() != "__patch:" }.drop(1).takeWhile { it.startsWith("    ") }
            for (line in patch) {
                val entry = Regex("""^\s+((?:full_shape|half_shape)/\S+?):\s*(\S)""").find(line) ?: continue
                val key = entry.groupValues[1].removeSuffix("/=").removeSuffix("/+")
                val kind = defaults[key] ?: continue
                checked++
                if (entry.groupValues[2] == "{") {
                    assertEquals("${file.name} 的 $key 写成字典，default.custom.yaml 那边不是字典，方案会编不出来", '{', kind)
                }
            }
        }
        assertTrue("注音的四条 Shift 标点都该查到", checked >= 4)
    }
}
