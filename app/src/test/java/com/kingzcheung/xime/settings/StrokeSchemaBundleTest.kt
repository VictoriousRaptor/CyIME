package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 笔画键盘随包带 stroke 方案：键盘发出的笔画键和 * 都能被方案接住，码表按常用字频排序。 */
class StrokeSchemaBundleTest {
    private fun repoFile(rel: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, rel).takeIf { it.exists() }?.let { return it }
            File(dir, "app/$rel").takeIf { it.exists() }?.let { return it }
            dir = dir.parentFile
        }
        error("missing $rel")
    }

    private val schema by lazy { repoFile("src/main/assets/rime_chinese/stroke.schema.yaml").readText() }

    @Test
    fun `stroke keypad binds the bundled schema`() {
        val bindings = KeysConfigHelper.parseSchemaBindingsYamlText(repoFile("src/main/assets/xime.yaml").readText())
        assertEquals("stroke", bindings["stroke"])
        assertTrue(schema.contains("schema_id: stroke"))
        assertTrue(schema.contains("dictionary: stroke"))
        // CyIME 不带 luna_pinyin；方案不能依赖装不上的词典。
        assertTrue(schema.lines().filterNot { it.trimStart().startsWith("#") }.none { "luna_pinyin" in it })
    }

    @Test
    fun `speller accepts every stroke key and the wildcard`() {
        val alphabet = Regex("""(?m)^  alphabet: "([^"]*)"""").find(schema)!!.groupValues[1]
        for (key in listOf("h", "s", "p", "n", "z", "*")) assertTrue("alphabet misses $key", key in alphabet)
    }

    @Test
    fun `a separator may follow a separator and codes show as letters by default`() {
        // n 个分词号就是 n+1 个字（11-18）：分词号要能连着打、打在最后，不然第二个分词号把首选顶上屏。
        val initials = Regex("""(?m)^  initials: "([^"]*)"""").find(schema)!!.groupValues[1]
        for (key in listOf("h", "s", "p", "n", "z", "*", "'")) assertTrue("initials misses $key", key in initials)
        // 编码默认显示 h s p n z；显示成笔画由设置写方案补丁（SpellingPatches.strokeGlyphParts）。
        assertTrue(Regex("""(?m)^  preedit_format:""").find(schema) == null)
        assertTrue(Regex("""(?m)^\s+- xlit/hspnz/""").find(schema) == null)
    }

    @Test
    fun `lua modules named by the schema are bundled`() {
        val modules = Regex("""lua_(?:translator|filter)@\*(\w+)""").findAll(schema).map { it.groupValues[1] }.toList()
        assertEquals(listOf("cyime_stroke_wildcard", "cyime_stroke_phrase", "cyime_char_lists", "cyime_stroke_rank"), modules)
        for (module in modules) repoFile("src/main/assets/rime_chinese/lua/$module.lua")
    }

    @Test
    fun `generated table ranks common characters by frequency`() {
        val dict = repoFile("build/generated/stroke-assets/rime_chinese/stroke.dict.yaml").readLines()
        assertTrue(dict.contains("use_preset_vocabulary: false  # CyIME：字频见下方权重"))
        val weights = dict.dropWhile { it.trim() != "..." }.drop(1).map { it.split('\t') }
            .filter { it.size == 3 }.groupBy({ it[0] }, { it[2].toLong() }).mapValues { (_, w) -> w.max() }
        assertTrue(weights.size > 20000)
        // hs：十 常用，丅 生僻；无字频时两者同权按码位排，十 会落在后面。
        assertTrue(weights.getValue("十") > weights.getValue("丅"))
        assertTrue(weights.getValue("们") > weights.getValue("們"))
        // 部首、笔画符号与汉字同形，不进码表。
        assertTrue("⼀" !in weights && "㇀" !in weights)
    }

    @Test
    fun `phrase table for split input is compiled through a hidden dependency`() {
        // 用分词键隔开、每个字只打头几笔时出词：词表靠 stroke_phrase 依赖方案编译，不进语言菜单。
        assertTrue(Regex("""(?m)^  dependencies:\n    - stroke_phrase$""").containsMatchIn(schema))
        val helper = repoFile("src/main/assets/rime_chinese/stroke_phrase.schema.yaml").readText()
        assertTrue(helper.contains("dictionary: stroke_phrase"))
        assertTrue(helper.contains("enable_user_dict: false"))
        assertTrue("stroke_phrase" in CyimeInputDefaults.dependencies)
    }

    @Test
    fun `generated phrase table codes each word by the first stroke of every character`() {
        val rows = repoFile("build/generated/stroke-assets/rime_chinese/stroke_phrase.dict.yaml").readLines()
            .dropWhile { it.trim() != "..." }.drop(1).map { it.split('\t') }.filter { it.size == 3 }
        val codes = rows.groupBy({ it[0] }, { it[1] })
        assertTrue(codes.size > 100000)
        // 明 丨、目 丨、张 乛、胆 丿；哈 丨、中 丨、国 丨。
        assertEquals(listOf("sszp"), codes["明目张胆"])
        assertEquals(listOf("ss"), codes["哈哈"])
        assertTrue(rows.all { it[1].length == it[0].length && it[1].all { c -> c in "hspnz" } })
        // 只收两到四字的词。
        assertTrue(rows.all { it[0].length in 2..4 })
    }
}
