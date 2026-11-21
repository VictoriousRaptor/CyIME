package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 仓颉五代：键盘的字根键帽、送给 Rime 的字母码和 cyime_cangjie 方案显示的字根三者对得上；构建出的码表按字频排。 */
class CangjieSchemeTest {
    private fun repoFile(rel: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, rel).takeIf { it.exists() }?.let { return it }
            File(dir, "app/$rel").takeIf { it.exists() }?.let { return it }
            dir = dir.parentFile
        }
        error("missing $rel")
    }

    private val schema by lazy { repoFile("src/main/assets/rime_chinese/cyime_cangjie.schema.yaml").readText() }
    private val ximeYaml by lazy { repoFile("src/main/assets/xime.yaml").readText() }

    /** 方案 preedit_format 的 xlit：字母码 → 屏上显示的字根。 */
    private fun radicals(): Map<Char, Char> {
        val (source, target) = Regex("""xlit\|([^|]+)\|([^|]+)\|""").find(schema.substringAfter("preedit_format:"))!!.destructured
        assertEquals(source.length, target.length)
        return source.zip(target).toMap()
    }

    @Test fun `every key cap shows the radical the schema shows for its code`() {
        assertEquals("qwerty_cangjie", KeysConfigHelper.parseSchemaBindingsYamlText(ximeYaml)["cyime_cangjie"])
        val rows = KeysConfigHelper.parseKeyboardLayoutYamlText(ximeYaml, "qwerty_cangjie") ?: error("xime.yaml 缺少 qwerty_cangjie rows")
        assertEquals(listOf(10, 9, 7), rows.map { it.size })
        val keys = KeysConfigHelper.parseKeyboardYamlSection(ximeYaml, "qwerty_cangjie") ?: error("xime.yaml 缺少 qwerty_cangjie keys")
        val alphabet = Regex("""(?m)^  alphabet: (\S+)""").find(schema)!!.groupValues[1]
        val shown = radicals()
        for (key in rows.flatten()) {
            val tap = keys[key]?.tap ?: error("$key 没有点按定义")
            assertEquals("$key 送自己的字母码", key, tap.value)
            assertTrue("$key not in the speller alphabet", key.single() in alphabet)
            assertEquals("$key 的键帽", shown.getValue(key.single()).toString(), tap.label)
        }
        // 候选注释（剩下的码）用同一套字根
        val comment = Regex("""xlit\|([^|]+)\|([^|]+)\|""").find(schema.substringAfter("comment_format:"))!!.destructured
        for ((code, radical) in shown) assertEquals(radical, comment.component2()[comment.component1().indexOf(code)])
    }

    @Test fun `cyime_cangjie is the Chinese cangjie scheme on 26 keys`() {
        val profile = InputProfiles.describe("cyime_cangjie")
        assertEquals(InputLanguage.CHINESE, profile.language)
        assertEquals(InputScheme.CANGJIE, profile.scheme)
        assertEquals(LayoutKind.ALPHABETIC, profile.layout.kind)
        assertFalse("仓颉没有拼音编辑", profile.capabilities.editablePinyin)
        // 字根键帽的 26 键就是中文的 26 键，否则布局面板多出一个「26键」，方案行里也没有仓颉（用户 10-31）
        assertEquals(InputLayout.QWERTY, InputProfiles.resolveLayout("qwerty_cangjie"))
        assertTrue("仓颉跟随中文码表的字母排列", LetterLayouts.appliesTo(InputLanguage.CHINESE, InputScheme.CANGJIE))
    }

    @Test fun `schema converts with a bundled config and needs no missing dictionary`() {
        assertTrue(schema.contains("schema_id: cyime_cangjie"))
        assertTrue(schema.contains("dictionary: cyime_cangjie"))
        // CyIME 不带 luna_pinyin / essay；方案不能依赖装不上的词典。
        assertTrue(schema.lines().filterNot { it.trimStart().startsWith("#") }.none { "luna_pinyin" in it || "reverse_lookup" in it })
        assertTrue(schema.contains("  option_name: simplification\n  opencc_config: cyime_t2s.json\n"))
        val config = repoFile("src/main/assets/rime_chinese/opencc/cyime_t2s.json").readText()
        val tables = Regex(""""file":\s*"([^"]+)"""").findAll(config).map { it.groupValues[1] }.toList()
        for (table in tables) assertTrue("$table 没有随包", repoFile("src/main/assets/rime_chinese/opencc/$table").isFile)
        // 只看实际引用的表；配置名里写着「no TSCharactersExt」不算
        assertFalse("会转出方框字的表不要", tables.any { it.startsWith("TSCharactersExt") })
    }

    @Test fun `generated table weights characters by use and encodes words itself`() {
        val dict = repoFile("build/generated/cangjie-assets/rime_chinese/cyime_cangjie.dict.yaml").readLines()
        assertTrue(dict.contains("use_preset_vocabulary: false"))
        val rows = dict.dropWhile { it.trim() != "..." }.drop(1).filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }
        fun weight(text: String, code: String) = rows.first { it[0] == text && it[1] == code }[2].toLong()
        // a：日 常用，曰 少见
        assertTrue(weight("日", "a") > weight("曰", "a"))
        // ommi：简化字 会 按大陆字频，排在日文的 伝 前面
        assertTrue(weight("会", "ommi") > weight("伝", "ommi"))
        // 兼容汉字、康熙部首不进码表（同形不同码位）
        assertTrue(rows.none { it[0].codePointAt(0) in 0xF900..0xFAFF || it[0].codePointAt(0) in 0x2F00..0x2FDF })
        // 词组不写编码，由 librime 编
        val words = rows.filter { it[1].isEmpty() }
        assertTrue(words.size > 50000)
        assertTrue(words.any { it[0] == "我們" })
        // 符号码保留原表次序
        assertTrue(weight("「", "yyyaa") > 0)
    }

    @Test fun `quick shares the cangjie keyboard and cuts codes to first and last letter`() {
        assertEquals("qwerty_cangjie", KeysConfigHelper.parseSchemaBindingsYamlText(ximeYaml)["cyime_quick"])
        val profile = InputProfiles.describe("cyime_quick")
        assertEquals(InputLanguage.CHINESE, profile.language)
        assertEquals(InputScheme.CANGJIE, profile.scheme)
        val quick = repoFile("src/main/assets/rime_chinese/cyime_quick.schema.yaml").readText()
        assertTrue(quick.contains("dictionary: cyime_quick"))
        // 键帽、编码栏字根与仓颉同一套
        for (format in listOf("preedit_format:", "comment_format:"))
            assertEquals(schema.substringAfter(format).lines()[1], quick.substringAfter(format).lines()[1])
        val dict = repoFile("build/generated/cangjie-assets/rime_chinese/cyime_quick.dict.yaml").readLines()
        val rows = dict.dropWhile { it.trim() != "..." }.drop(1).filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }
        assertTrue(rows.size > 25000)
        assertTrue(rows.all { it[1].length in 1..2 && it[1][0] !in "xz" })
        fun first(code: String) = rows.filter { it[1] == code }.maxBy { it[2].toLong() }[0]
        assertEquals("日", first("a"))
        assertEquals("的", first("hi")) // 的 hapi
        assertTrue(rows.any { it[0] == "我" && it[1] == "hi" }) // 我 hqi
    }

    /** 单字提前放在去重后面会先取走一批候选，傳、传都转成「传」时去不掉，同一码出两个「传」。 */
    @Test fun `single characters are lifted before duplicates are removed`() {
        for (file in listOf("cyime_cangjie", "cyime_quick")) {
            val filters = repoFile("src/main/assets/rime_chinese/$file.schema.yaml").readText()
                .substringAfter("  filters:\n").substringBefore("\n\n").lines().filter { it.trim().startsWith("- ") }.map { it.trim().removePrefix("- ") }
            // 日文汉字、韩文汉字、粤语字的开关（11-20）在最前面，关着时那些字不出。
            assertEquals(file, listOf("lua_filter@*cyime_char_lists", "simplifier", "single_char_filter", "uniquifier"), filters)
        }
    }
}
