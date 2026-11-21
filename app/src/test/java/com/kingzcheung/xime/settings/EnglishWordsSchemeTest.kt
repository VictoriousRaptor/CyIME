package com.kingzcheung.xime.settings

import com.kingzcheung.xime.rime.PinyinEditBuffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 英文单词方案（用户 11-09）：和「英文 · 直接输入」并存的独立英文方案，字母先进组字区、从候选里选词上屏，
 * 编码可挪光标改、编码位置等都照拼音；直接输入和中文方案不受影响。
 */
class EnglishWordsSchemeTest {
    private fun repoFile(rel: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, rel).takeIf { it.exists() }?.let { return it }
            File(dir, "app/$rel").takeIf { it.exists() }?.let { return it }
            dir = dir.parentFile
        }
        error("missing $rel")
    }

    private val schemaFile by lazy { repoFile("src/main/assets/rime_chinese/cyime_english.schema.yaml") }
    private val schema by lazy { schemaFile.readText() }
    private val words = SchemaInfo(InputModes.ENGLISH_WORDS, "英文单词", "", "", "")
    private val chinese = SchemaInfo("rime_ice", "雾凇拼音", "", "", "")
    private val japanese = SchemaInfo("japanese", "日语", "", "", "")

    @After fun resetBindings() { KeysConfigHelper.clearSchemaBindingsForTest() }

    @Test fun `words is its own English scheme beside direct input`() {
        val profile = InputProfiles.describe(InputModes.ENGLISH_WORDS)
        assertEquals(InputLanguage.ENGLISH, profile.language)
        assertEquals(InputScheme.WORDS, profile.scheme)
        assertEquals("单词", InputScheme.WORDS.displayName)
        assertEquals(InputLayout.QWERTY, profile.layout)
        assertEquals(EngineProfile.Rime(InputModes.ENGLISH_WORDS), profile.engineProfile)
        assertEquals("word", InputProfiles.schemePreview(profile))
        // 和拼音一样能挪光标改编码；编码分大小写（Hello 不被改成 hello）。
        assertTrue(profile.capabilities.editablePinyin)
        assertTrue(profile.capabilities.caseSensitiveCode)
        assertFalse(profile.typesLettersDirectly)
        // 直接输入照旧逐字上屏，中文、西班牙语不变。
        val direct = InputProfiles.describe(InputModes.ENGLISH)
        assertEquals(InputScheme.DIRECT, direct.scheme)
        assertTrue(direct.typesLettersDirectly)
        assertFalse(direct.capabilities.editablePinyin)
        assertTrue(InputProfiles.describe("spanish").typesLettersDirectly)
        assertFalse(InputProfiles.describe("rime_ice").typesLettersDirectly)
        assertFalse(InputProfiles.describe("rime_ice").capabilities.caseSensitiveCode)
        // 英文选的字母排列（Colemak 等）两种英文方案都用。
        assertTrue(LetterLayouts.appliesTo(InputLanguage.ENGLISH, InputScheme.WORDS))
    }

    @Test fun `English offers both schemes on the same 26 keys`() {
        val all = InputModes.available(listOf(chinese, words), InputModes.defaultModeOrder)
        val english = all.filter { it.language == InputLanguage.ENGLISH }
        // 没选过时英文仍是直接输入在前（默认行为不变）。
        assertEquals(listOf(InputModes.ENGLISH, InputModes.ENGLISH_WORDS), english.map { it.schemaId })
        assertEquals(listOf(InputScheme.DIRECT, InputScheme.WORDS), InputProfileSelection.schemes(all, InputLanguage.ENGLISH))
        assertEquals(InputModes.ENGLISH, InputProfileSelection.preferred(all, InputLanguage.ENGLISH, null)?.schemaId)
        // 布局栏只有一个「26键」（单词方案绑的英文键盘 qwerty_en 也算 26 键）。
        val row = InputProfileSelection.layoutRow(english, all).map { it.id }
        assertEquals(1, row.count { it == InputLayout.QWERTY.id })
        assertFalse("qwerty_en" in row)
        assertEquals(listOf(InputModes.ENGLISH, InputModes.ENGLISH_WORDS),
            InputProfileSelection.schemeEntries(all, english.first()).map { it.schemaId })
    }

    @Test fun `words scheme is listed but never enabled by default`() {
        assertTrue(InputModes.ENGLISH_WORDS in CyimeInputDefaults.listedWhenOff)
        assertFalse(InputModes.ENGLISH_WORDS in CyimeInputDefaults.firstDefaults(freshInstall = true))
        assertFalse(InputModes.ENGLISH_WORDS in CyimeInputDefaults.firstDefaults(freshInstall = false))
        assertFalse(InputModes.ENGLISH_WORDS in CyimeInputDefaults.dependencies)
        assertTrue(CyimeInputDefaults.visibleSchema(InputModes.ENGLISH_WORDS, setOf(InputModes.ENGLISH_WORDS)))
    }

    @Test fun `switching to English follows the chosen scheme`() {
        val entries = InputModes.available(listOf(chinese, japanese, words))
        // 选了单词：换到单词方案，不开 ascii。
        assertEquals(EnglishSchemes.Target(InputModes.ENGLISH_WORDS, asciiMode = false),
            EnglishSchemes.target(entries, InputModes.ENGLISH_WORDS, "rime_ice", "rime_ice"))
        // 没选过、选的是直接输入、或单词方案没启用：照旧在当前方案上开 ascii。
        assertEquals(EnglishSchemes.Target("rime_ice", asciiMode = true), EnglishSchemes.target(entries, null, "rime_ice", "rime_ice"))
        assertEquals(EnglishSchemes.Target("japanese", asciiMode = true),
            EnglishSchemes.target(entries, InputModes.ENGLISH, "japanese", "japanese"))
        assertEquals(EnglishSchemes.Target("rime_ice", asciiMode = true),
            EnglishSchemes.target(InputModes.available(listOf(chinese)), InputModes.ENGLISH_WORDS, "rime_ice", "rime_ice"))
        // 选的不是英文方案（存了个中文方案 id）也不会换过去。
        assertEquals(EnglishSchemes.Target("rime_ice", asciiMode = true), EnglishSchemes.target(entries, "japanese", "rime_ice", "rime_ice"))
        // 正在单词方案上改选直接输入：先回母语方案再开 ascii；没有母语方案可回就不切。
        assertEquals(EnglishSchemes.Target("rime_ice", asciiMode = true),
            EnglishSchemes.target(entries, InputModes.ENGLISH, InputModes.ENGLISH_WORDS, "rime_ice"))
        assertNull(EnglishSchemes.target(entries, InputModes.ENGLISH, InputModes.ENGLISH_WORDS, null))
        assertNull(EnglishSchemes.target(entries, InputModes.ENGLISH, InputModes.ENGLISH_WORDS, InputModes.ENGLISH_WORDS))
        assertTrue(EnglishSchemes.isEnglishSchema(InputModes.ENGLISH_WORDS, entries))
        assertFalse(EnglishSchemes.isEnglishSchema(InputModes.ENGLISH, entries))
        assertFalse(EnglishSchemes.isEnglishSchema("rime_ice", entries))
    }

    @Test fun `language key on the words scheme goes back to the language before English`() {
        val available = setOf(InputLanguage.CHINESE, InputLanguage.JAPANESE, InputLanguage.ENGLISH)
        val order = listOf(InputLanguage.ENGLISH, InputLanguage.CHINESE, InputLanguage.JAPANESE)
        // 不在单词方案上：母语就是方案自己的语言。
        assertEquals(InputLanguage.JAPANESE, EnglishSchemes.nativeLanguage(InputLanguage.JAPANESE, InputLanguage.CHINESE, order, available))
        assertEquals(InputLanguage.JAPANESE, EnglishSchemes.nativeLanguage(InputLanguage.ENGLISH, InputLanguage.JAPANESE, order, available))
        // 没记下（开机就在单词方案上）或那个语言关了：按语言顺序取第一个非英文语言。
        assertEquals(InputLanguage.CHINESE, EnglishSchemes.nativeLanguage(InputLanguage.ENGLISH, null, order, available))
        assertEquals(InputLanguage.CHINESE, EnglishSchemes.nativeLanguage(InputLanguage.ENGLISH, InputLanguage.KOREAN, order, available))
        // 只开了英文：没有可回的语言，语言键不切。
        assertEquals(InputLanguage.ENGLISH, EnglishSchemes.nativeLanguage(InputLanguage.ENGLISH, null, order, setOf(InputLanguage.ENGLISH)))
        assertEquals(InputLanguage.CHINESE, LanguageSwitchPreferences.target(InputLanguage.ENGLISH,
            EnglishSchemes.nativeLanguage(InputLanguage.ENGLISH, null, order, available), LanguageSwitchOptions(), order, available))
    }

    @Test fun `editing the code keeps typed capitals only in the words scheme`() {
        assertEquals("Hello", PinyinEditBuffer.normalized("Hello", keepCase = true))
        assertEquals("don't", PinyinEditBuffer.normalized("don't", keepCase = true))
        assertEquals("hello", PinyinEditBuffer.normalized("Hello", keepCase = false))
        assertEquals("ni'hao", PinyinEditBuffer.normalized("Ni hao", keepCase = false))
        assertTrue(PinyinEditBuffer.keepsCase(InputModes.ENGLISH_WORDS))
        assertFalse(PinyinEditBuffer.keepsCase("rime_ice"))
        assertTrue(PinyinEditBuffer.supports(InputModes.ENGLISH_WORDS))
        assertFalse(PinyinEditBuffer.supports(InputModes.ENGLISH))
    }

    @Test fun `bundled schema declares English words and composes like pinyin`() {
        val meta = assertNotNullAndGet(SchemaManager.parseSchemaYaml(schemaFile))
        assertEquals(InputModes.ENGLISH_WORDS, meta.schemaId)
        assertEquals(InputLanguage.ENGLISH, meta.language)
        assertEquals(InputScheme.WORDS, meta.scheme)
        val alphabet = Regex("""(?m)^  alphabet: "([^"]*)"""").find(schema)!!.groupValues[1]
        val initials = Regex("""(?m)^  initials: "([^"]*)"""").find(schema)!!.groupValues[1]
        for (c in ('a'..'z') + ('A'..'Z')) assertTrue("$c", c in alphabet && c in initials)
        // 撇号算字母（don't），但单打的 ' 是标点。
        assertTrue('\'' in alphabet)
        assertFalse('\'' in initials)
        // 默认 recognizer 会把大写开头当原文，单词方案不能用。
        val processors = schema.substringAfter("  processors:").substringBefore("  segmentors:")
            .lines().filterNot { it.trimStart().startsWith("#") }.joinToString("\n")
        assertFalse(processors.contains("recognizer"))
        assertTrue(processors.contains("speller") && processors.contains("selector") && processors.contains("express_editor"))
        // 整词和补全两个翻译器，同一个词典。
        assertEquals(2, Regex("""(?m)^  dictionary: cyime_english$""").findAll(schema).count())
        assertTrue(schema.contains("    - table_translator\n    - table_translator@completion\n"))
        // 标点半角（组字时先上屏首选再出标点）。
        val half = schema.substringAfter("  half_shape:")
        for (p in listOf(",", ".", "?", "!", "'")) assertTrue(p, half.contains("    \"$p\": \"$p\""))
        assertEquals("cyime_english", KeysConfigHelper.parseSchemaBindingsYamlText(repoFile("src/main/assets/xime.yaml").readText())
            .entries.single { it.value == "qwerty_en" }.key)
    }

    @Test fun `lua modules named by the schema are bundled`() {
        val modules = Regex("""lua_(?:translator|filter)@\*(\w+)""").findAll(schema).map { it.groupValues[1] }.toList()
        assertEquals(listOf("cyime_en_memory", "cyime_english_words"), modules)
        // cyime_en_memory 和雾凇共用（构建时拷进 rime_ice/lua）；单词方案自己的过滤器随中文资源装。
        repoFile("data/cyime_en_memory.lua")
        val filter = repoFile("src/main/assets/rime_chinese/lua/cyime_english_words.lua").readText()
        assertTrue(filter.contains("func = cyime_english_words"))
        assertTrue(filter.contains("Candidate(\"raw\""))
    }

    @Test fun `generated dictionary ranks common words and keeps spellings`() {
        val dict = repoFile("build/generated/chinese-assets/rime_ice/cyime_english.dict.yaml").readLines()
        val rows = dict.dropWhile { it.trim() != "..." }.drop(1).map { it.split('\t') }.filter { it.size == 3 }
        assertTrue(rows.size > 20000)
        val weight = rows.associate { (it[0] + "\t" + it[1]) to it[2].toInt() }
        assertTrue(weight.getValue("the\tthe") > weight.getValue("they\tthey"))
        assertTrue(weight.getValue("car\tcar") > 1)
        // 大小写照常用写法；缩写不打撇号也查得到，非词的去撇号写法让给缩写。
        assertNotNull(weight["I\ti"])
        assertNotNull(weight["January\tjanuary"])
        assertTrue(weight.getValue("don't\tdont") > 1000)
        assertNull(weight["dont\tdont"])
        assertTrue(weight.getValue("its\tits") > weight.getValue("it's\tits"))
        // 编码全是小写字母（和撇号），大写由方案的拼写运算派生。
        assertTrue(rows.all { row -> row[1].first() in 'a'..'z' && row[1].all { it in 'a'..'z' || it == '\'' } })
    }

    private fun <T> assertNotNullAndGet(value: T?): T { assertNotNull(value); return value!! }
}
