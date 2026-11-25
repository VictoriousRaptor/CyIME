package com.kingzcheung.xime.settings

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.io.File

private fun createCustomPhraseFile(rimeDir: File, schemaId: String) {
    val dictName = PersonalDictManager.getCustomPhraseDictName(rimeDir, schemaId)
    File(rimeDir, "$dictName.txt").writeText("""# Rime table
# coding: utf-8
#@/db_name	custom_phrase
#@/db_type	tabledb
#
hello	hw
123	abc
""")
}

private fun mockContext(): Context {
    val tmpDir = File.createTempFile("mock_context", "").apply { delete(); mkdirs() }
    return mock {
        on { filesDir } doReturn tmpDir
    }
}

class PersonalDictManagerTest {

    @Test fun phraseEnterUsesItsOwnCodeWhenSeveralReadingsShareTheDigits() {
        val dir = java.nio.file.Files.createTempDirectory("t9-phrase-reading").toFile()
        try {
            File(dir, "custom_phrase.txt").writeText("……\thao\t5\n高处\tgao\t2\n")
            val readings = PersonalDictManager.t9PhraseReadings(dir)
            for ((phrase, code) in listOf("……" to "hao", "高处" to "gao")) {
                val preedit = readings.getValue(phrase to "426")
                assertEquals(code, com.kingzcheung.xime.service.rawCompositionCommitText("426", preedit, true))
            }
            org.junit.Assert.assertNull(readings["……" to "42"])
            // 编码就是数字的短语没有字母读音，不进这张表（编码栏照常用引擎的）。
            File(dir, "custom_phrase.txt").writeText("13800138000\t34\n")
            org.junit.Assert.assertTrue(PersonalDictManager.t9PhraseReadings(dir).isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `shared symbol shortcuts reach each keyboard without replacing private tables or manual translators`() {
        val context = mockContext()
        val dir = File(context.filesDir, "rime").apply { mkdirs() }
        val schemas = listOf("rime_ice", "double_pinyin_flypy", "t9_pinyin", "pinyin_14jian",
            "double_pinyin_flypy_t9", "double_pinyin_flypy_14jian")
        for (schema in schemas) {
            val dict = if (schema == "double_pinyin_flypy") "custom_phrase_double" else "custom_phrase"
            File(dir, "$schema.schema.yaml").writeText(if (schema == "double_pinyin_flypy_t9")
                DoublePinyinScheme.FLYPY.t9Schema() else "custom_phrase:\n  user_dict: $dict\n")
        }
        val privateFile = File(dir, "custom_phrase_double.txt").apply { writeText("专用短语\tqqw\t99\n") }
        val private14 = File(dir, "my_symbols.txt").apply { writeText("布局短语\tJJQ\t9\n") }
        File(dir, "pinyin_14jian.custom.yaml").writeText("patch:\n  \"custom_phrase/user_dict\": my_symbols\n")
        // Upgrade an existing nine-key installation where the translation was appended after the dictionary.
        File(dir, "t9_pinyin.custom.yaml").writeText("patch:\n  \"engine/translators/+\": [table_translator@custom_phrase]\n" +
            "  custom_phrase:\n    dictionary: \"\"\n    user_dict: custom_phrase\n    db_class: stabledb\n    initial_quality: 99\n")
        val entries = listOf("❤️" to "oof", "🟠" to "oof", "🟡" to "oof", "🟢" to "oof",
            "----------------" to "oog", "/" to "oog", "——" to "oov", "-" to "oov", "?" to "ow", "!" to "os")
            .map { (word, code) -> DictEntry(word, code, 9999999) } +
            listOf(DictEntry("短语甲", "qzs", 1), DictEntry("短语乙", "qzs", 9999999))
        PersonalDictManager.saveCustomPhrases(context, entries = entries)
        fun apply() = runBlocking { schemas.forEach { PersonalDictManager.ensureSchemaPack(context, it) } }
        apply()
        val configs = schemas.associateWith { File(dir, "$it.custom.yaml").readText() }
        apply()
        for ((schema, config) in configs) {
            assertEquals(config, File(dir, "$schema.custom.yaml").readText())
            assertTrue("$schema must query exact shortcuts before ordinary spelling", config.contains("engine/translators/@before 0"))
        }
        assertEquals(1, Regex("table_translator@custom_phrase").findAll(configs.getValue("t9_pinyin")).count())
        assertEquals("专用短语\tqqw\t99\n", privateFile.readText())
        assertEquals("布局短语\tJJQ\t9\n", private14.readText())
        assertEquals("my_symbols", PersonalDictManager.getCustomPhraseDictName(dir, "pinyin_14jian"))
        assertTrue(configs.getValue("double_pinyin_flypy").contains("table_translator@cyime_shared_phrase"))
        assertEquals(entries, PersonalDictManager.loadCustomPhrases(context))
        for ((dict, code) in listOf("custom_phrase_t9" to "663", "custom_phrase_k14" to "OOD")) {
            val rows = PersonalDictManager.parseStableDbEntries(File(dir, "$dict.txt").readText()).filter { it.code == code }
            assertEquals(entries.take(4).map { it.copy(code = code) }, rows)
        }
        // The host JNI probe consumes these actual settings outputs in a separate disposable Rime directory.
        System.getenv("CYIME_PHRASE_PROBE_DIR")?.let { path ->
            val export = File(path).apply { mkdirs() }
            dir.listFiles().orEmpty().filter { it.extension == "txt" || it.name.endsWith(".custom.yaml") }
                .forEach { it.copyTo(File(export, it.name), overwrite = true) }
            File(dir, "double_pinyin_flypy_t9.schema.yaml").copyTo(File(export, "double_pinyin_flypy_t9.schema.yaml"), overwrite = true)
        }
        PersonalDictManager.saveCustomPhrases(context, entries = entries.drop(1))
        apply()
        assertFalse(File(dir, "custom_phrase_k14.txt").readText().contains("❤️"))
        assertFalse(File(dir, "custom_phrase_t9.txt").readText().contains("❤️"))
    }

    // ── 九键、14 键的自定义短语按键码 ──

    @Test
    fun `phrase codes become the keys nine and fourteen keys send`() {
        assertEquals(listOf("39"), PersonalDictManager.PhraseKeys.T9.codes("dz"))
        assertEquals(listOf("94264"), PersonalDictManager.PhraseKeys.T9.codes("xiang"))
        // 14 键：点按出合并键大写，l、m 原样；整段左右滑出的字母照样能打。
        assertEquals(listOf("DZ", "dz"), PersonalDictManager.PhraseKeys.MERGED14.codes("dz"))
        assertEquals(listOf("lm"), PersonalDictManager.PhraseKeys.MERGED14.codes("lm"))
        assertEquals(listOf("QEATUCQZTZ", "wrstuvwxyz"), PersonalDictManager.PhraseKeys.MERGED14.codes("wrstuvwxyz"))
        assertEquals(listOf("39"), PersonalDictManager.PhraseKeys.T9.codes("D'Z"))
        // 编码里的数字照原样留着（11-24 数字短语打不出）；0、1 是九键的分词、空格键，九键打不进，不进九键副本。
        assertEquals(emptyList<String>(), PersonalDictManager.PhraseKeys.T9.codes("a1"))
        assertEquals(listOf("392"), PersonalDictManager.PhraseKeys.T9.codes("dz2"))
        // 九键用户直接填按的数字（dh 填 34）。
        assertEquals(listOf("34"), PersonalDictManager.PhraseKeys.T9.codes("34"))
        assertEquals(listOf("DZ1", "dz1"), PersonalDictManager.PhraseKeys.MERGED14.codes("dz1"))
        assertEquals(emptyList<String>(), PersonalDictManager.PhraseKeys.MERGED14.codes("a-1"))
        assertEquals(PersonalDictManager.PhraseKeys.T9, PersonalDictManager.PhraseKeys.of("t9_pinyin"))
        assertEquals(PersonalDictManager.PhraseKeys.MERGED14, PersonalDictManager.PhraseKeys.of("pinyin_14jian"))
        assertEquals(PersonalDictManager.PhraseKeys.MERGED14, PersonalDictManager.PhraseKeys.of("double_pinyin_flypy_14jian"))
        assertEquals(null, PersonalDictManager.PhraseKeys.of("rime_ice"))
    }

    @Test
    fun `keyed phrase copy follows the shared list and old patches are retargeted`() {
        val dir = File.createTempFile("rime", "").apply { delete(); mkdirs() }
        File(dir, "custom_phrase.txt").writeText("# Rime table\n#@/db_name\tcustom_phrase\n地址\tdz\t9\n我的邮箱\twdyx\n")
        PersonalDictManager.writeKeyedPhrases(dir, PersonalDictManager.PhraseKeys.T9)
        val t9 = File(dir, "custom_phrase_t9.txt").readText()
        assertTrue(t9.contains("#@/db_name\tcustom_phrase_t9\n"))
        assertEquals(listOf(DictEntry("地址", "39", 9), DictEntry("我的邮箱", "9399")),
            PersonalDictManager.parseStableDbEntries(t9))
        val patch = File(dir, "t9_pinyin.custom.yaml")
        patch.writeText("patch:\n  \"engine/translators/+\":\n    - table_translator@custom_phrase\n  \"custom_phrase\":\n" +
            "    dictionary: \"\"\n    user_dict: custom_phrase\n    db_class: stabledb\n")
        PersonalDictManager.retargetCustomPhraseDict(patch, "custom_phrase_t9")
        assertTrue(patch.readText().contains("    user_dict: custom_phrase_t9\n    db_class: stabledb\n"))
        assertTrue(patch.readText().contains("    - table_translator@custom_phrase\n"))
        assertEquals("custom_phrase_t9", PersonalDictManager.getCustomPhraseDictName(dir, "t9_pinyin"))
        dir.deleteRecursively()
    }

    // ── 双拼的自定义短语按双拼编码 ──

    @Test
    fun `full pinyin phrase codes become the codes each double pinyin scheme types`() {
        val flypy = DoublePinyinScheme.FLYPY
        // 单个声母按简拼（b站 的 b），翘舌声母换成这套双拼的键。
        assertEquals(listOf("bvj"), PersonalDictManager.doublePinyinCodes(flypy, "bzhan"))
        assertEquals(listOf("nihc"), PersonalDictManager.doublePinyinCodes(flypy, "nihao"))
        assertEquals(listOf("vsgo"), PersonalDictManager.doublePinyinCodes(flypy, "zhongguo"))
        assertEquals(listOf("uj"), PersonalDictManager.doublePinyinCodes(flypy, "shj"))
        assertEquals(listOf("dz"), PersonalDictManager.doublePinyinCodes(flypy, "dz"))
        // 少拆优先；' 是用户写的分隔；拆法并列时都给。
        assertEquals(listOf("xm"), PersonalDictManager.doublePinyinCodes(flypy, "xian"))
        assertEquals(listOf("xian"), PersonalDictManager.doublePinyinCodes(flypy, "xi'an"))
        assertEquals(listOf("fjgj", "fhan"), PersonalDictManager.doublePinyinCodes(flypy, "fangan"))
        assertEquals(listOf("lt"), PersonalDictManager.doublePinyinCodes(flypy, "lue"))
        assertEquals(listOf("baj"), PersonalDictManager.doublePinyinCodes(DoublePinyinScheme.ZNABC, "bzhan"))
        assertEquals(listOf("okyp"), PersonalDictManager.doublePinyinCodes(DoublePinyinScheme.MICROSOFT, "aoyun"))
        assertEquals(listOf("x;"), PersonalDictManager.doublePinyinCodes(DoublePinyinScheme.MICROSOFT, "xing"))
        // 拆不成拼音的不换。
        assertEquals(emptyList<String>(), PersonalDictManager.doublePinyinCodes(flypy, "wifi"))
        assertEquals(emptyList<String>(), PersonalDictManager.doublePinyinCodes(flypy, "wifi1"))
        // 数字段照原样接在后面。
        assertEquals(listOf("dmhx1"), PersonalDictManager.doublePinyinCodes(flypy, "dianhua1"))
    }

    // ── 字母开头、带数字的短语编码（dz1）：26 键、双拼、14 键打字中的数字进编码 ──

    @Test
    fun `digit phrase codes get a recognizer rule for exactly their prefixes`() {
        assertEquals(setOf("dz1", "dz12", "a1", "a1b"),
            PersonalDictManager.phraseDigitPrefixes(listOf("dz12", "abc", "1a", "dz1", "a1b", "x.1")))
        val dir = createTempDir()
        File(dir, "custom_phrase.txt").writeText("# Rime table\n#@/db_name\tcustom_phrase\n电话\tdh1\n地址\tdz\n")
        val patch = File(dir, "rime_ice.custom.yaml")
        PersonalDictManager.applyCustomPhraseTranslator(dir, "rime_ice", "custom_phrase")
        assertTrue(patch.readText().contains("tags: [abc, cyime_phrase_digits]"))
        val prefixes = PersonalDictManager.phraseDigitPrefixes(dir, null)
        assertEquals(setOf("dh1"), prefixes)
        PersonalDictManager.applyPhraseDigitPattern(patch, prefixes)
        assertTrue(patch.readText().contains("  \"recognizer/patterns/cyime_phrase_digits\": \"^(?:dh1)$\"\n"))
        // 14 键副本的编码是合并键大写，规则跟着副本走。
        val k14 = PersonalDictManager.keyedPhrases("pinyin_14jian", null)
        assertEquals(setOf("DG1", "dh1"), PersonalDictManager.phraseDigitPrefixes(dir, k14))
        PersonalDictManager.applyPhraseDigitPattern(patch, emptySet())
        assertTrue(!patch.readText().contains("recognizer/patterns"))
        assertTrue(patch.readText().contains("table_translator@custom_phrase"))
    }

    @Test
    fun `my phrases typed in full pinyin work on double pinyin keyboards`() {
        val context = mockContext()
        val dir = File(context.filesDir, "rime").apply { mkdirs() }
        val schemas = listOf("double_pinyin_flypy", "double_pinyin_microsoft", "double_pinyin_flypy_t9", "double_pinyin_flypy_14jian")
        for (schema in listOf("double_pinyin_flypy", "double_pinyin_microsoft")) {
            File(dir, "$schema.schema.yaml").writeText("custom_phrase:\n  user_dict: custom_phrase_double\n")
        }
        File(dir, "double_pinyin_flypy_t9.schema.yaml").writeText(DoublePinyinScheme.FLYPY.t9Schema())
        File(dir, "double_pinyin_flypy_14jian.schema.yaml").writeText("custom_phrase:\n  user_dict: custom_phrase\n")
        val privateFile = File(dir, "custom_phrase_double.txt").apply { writeText("专用短语\tqqw\t99\n") }
        // 上一版：双拼挂的是全拼码的共享表，九键双拼挂的是全拼数字码的那一份。
        File(dir, "double_pinyin_flypy.custom.yaml").writeText("patch:\n" +
            "  \"engine/translators/@before 0\": table_translator@cyime_shared_phrase\n" +
            "  \"cyime_shared_phrase\":\n    dictionary: \"\"\n    user_dict: custom_phrase\n    db_class: stabledb\n")
        File(dir, "double_pinyin_flypy_t9.custom.yaml").writeText("patch:\n" +
            "  \"engine/translators/@before 0\": table_translator@custom_phrase\n" +
            "  \"custom_phrase\":\n    dictionary: \"\"\n    user_dict: custom_phrase_t9\n    db_class: stabledb\n")
        PersonalDictManager.saveCustomPhrases(context, entries = listOf(DictEntry("B站", "bzhan", 1),
            DictEntry("你好呀", "nihao", 1), DictEntry("❤️", "oof", 1)))
        runBlocking { schemas.forEach { PersonalDictManager.ensureSchemaPack(context, it) } }
        fun codes(schema: String, word: String) = PersonalDictManager.parseStableDbEntries(
            File(dir, "custom_phrase_sp_$schema.txt").readText()).filter { it.word == word }.map { it.code }

        // 26 键：原编码留着，再加这套双拼的编码。
        assertEquals(listOf("bzhan", "bvj"), codes("double_pinyin_flypy", "B站"))
        assertEquals(listOf("nihao", "nihc"), codes("double_pinyin_flypy", "你好呀"))
        assertEquals(listOf("nihao", "nihk"), codes("double_pinyin_microsoft", "你好呀"))
        assertTrue("oof" in codes("double_pinyin_flypy", "❤️"))
        // 九键双拼按双拼键所在的数字，14 键双拼按合并键。
        assertEquals(listOf("29426", "285"), codes("double_pinyin_flypy_t9", "B站"))
        assertEquals(listOf("BZGAB", "bzhan", "BCJ", "bvj"), codes("double_pinyin_flypy_14jian", "B站"))

        // 小鹤自带的专用短语表不动，「我的短语」挂成换好编码的那一份。
        assertEquals("专用短语\tqqw\t99\n", privateFile.readText())
        assertEquals("custom_phrase_double", PersonalDictManager.getCustomPhraseDictName(dir, "double_pinyin_flypy"))
        val flypyPatch = File(dir, "double_pinyin_flypy.custom.yaml").readText()
        assertTrue(flypyPatch.contains("    user_dict: custom_phrase_sp_double_pinyin_flypy\n"))
        assertEquals(1, Regex("table_translator@cyime_shared_phrase").findAll(flypyPatch).count())
        assertTrue(File(dir, "double_pinyin_microsoft.custom.yaml").readText().contains("custom_phrase_sp_double_pinyin_microsoft"))
        for (schema in listOf("double_pinyin_flypy_t9", "double_pinyin_flypy_14jian")) {
            assertEquals("custom_phrase_sp_$schema", PersonalDictManager.getCustomPhraseDictName(dir, schema))
        }
        // 再跑一遍不再改配置。
        val configs = schemas.associateWith { File(dir, "$it.custom.yaml").readText() }
        runBlocking { schemas.forEach { PersonalDictManager.ensureSchemaPack(context, it) } }
        for ((schema, config) in configs) assertEquals(config, File(dir, "$schema.custom.yaml").readText())
    }

    @Test
    fun `a scheme's own phrase dictionary stays and my phrases get their own table`() {
        val context = mockContext()
        val dir = File(context.filesDir, "rime").apply { mkdirs() }
        // 万象：custom_phrase 块挂着自己的词典，它的 Lua 置顶词用这一块。
        val own = "custom_phrase:\n  dictionary: custom_phrase\n  prism: wanxiang_phrase\n  enable_user_dict: false\n"
        File(dir, "wanxiang.schema.yaml").writeText(own)
        File(dir, "rime_ice.schema.yaml").writeText("custom_phrase:\n  dictionary: \"\"\n  user_dict: custom_phrase\n")
        // 上一版已经把万象这一块整块换成了我们的。
        File(dir, "wanxiang.custom.yaml").writeText("patch:\n" +
            "  \"engine/translators/@before 0\": table_translator@custom_phrase\n" +
            "  \"custom_phrase\":\n    dictionary: \"\"\n    user_dict: custom_phrase\n    db_class: stabledb\n" +
            "    enable_completion: false\n    enable_sentence: false\n    initial_quality: 99\n" +
            "  \"menu/page_size\": 7\n")
        PersonalDictManager.saveCustomPhrases(context, entries = listOf(DictEntry("我的邮箱", "yx", 1)))
        runBlocking { listOf("wanxiang", "rime_ice").forEach { PersonalDictManager.ensureSchemaPack(context, it) } }

        val wanxiang = File(dir, "wanxiang.custom.yaml").readText()
        assertFalse(wanxiang.contains("table_translator@custom_phrase"))
        assertFalse(wanxiang.contains("\"custom_phrase\":"))
        assertTrue(wanxiang.contains("table_translator@cyime_shared_phrase"))
        assertTrue(wanxiang.contains("    user_dict: custom_phrase\n"))
        assertTrue(wanxiang.contains("\"menu/page_size\": 7"))
        assertTrue(File(dir, "rime_ice.custom.yaml").readText().contains("table_translator@custom_phrase"))
        assertTrue(PersonalDictManager.hasOwnPhraseDictionary(own))
        assertFalse(PersonalDictManager.hasOwnPhraseDictionary("custom_phrase:\n  dictionary: ''\n  user_dict: x\n"))
        assertFalse(PersonalDictManager.hasOwnPhraseDictionary("custom_phrase:\n  user_dict: custom_phrase_double\n"))
        // 再跑一遍不再改配置。
        runBlocking { PersonalDictManager.ensureSchemaPack(context, "wanxiang") }
        assertEquals(wanxiang, File(dir, "wanxiang.custom.yaml").readText())
    }

    // ── 个人词库（只读显示） ──

    @Test
    fun `parsePersonalDictEntries reads entries after marker tab-delimited`() {
        val text = "# comment\n---\nname: x\n...\n日\ta\n曰\ta\n郎\tivnl\n"
        assertEquals(
            listOf(DictEntry("日", "a"), DictEntry("曰", "a"), DictEntry("郎", "ivnl")),
            PersonalDictManager.parsePersonalDictEntries(text),
        )
    }

    @Test
    fun `parsePersonalDictEntries preserves spaces in code`() {
        val text = "...\n你好\tni hao\n世界\tshi jie\n"
        assertEquals(
            listOf(DictEntry("你好", "ni hao"), DictEntry("世界", "shi jie")),
            PersonalDictManager.parsePersonalDictEntries(text),
        )
    }

    @Test
    fun `parsePersonalDictEntries falls back to space delimiter if no tab`() {
        val text = "...\n你好 ni hao\n世界 shi jie\n"
        assertEquals(
            listOf(DictEntry("你好", "ni hao"), DictEntry("世界", "shi jie")),
            PersonalDictManager.parsePersonalDictEntries(text),
        )
    }

    @Test
    fun `parsePersonalDictEntries ignores comments and blank lines`() {
        val text = "...\n\n# comment\n测试\tce shi\n"
        assertEquals(
            listOf(DictEntry("测试", "ce shi")),
            PersonalDictManager.parsePersonalDictEntries(text),
        )
    }

    @Test
    fun `parsePersonalDictEntries returns empty for header-only file`() {
        val text = "name: user_simp\nversion: '1.0'\n...\n"
        assertTrue(PersonalDictManager.parsePersonalDictEntries(text).isEmpty())
    }

    @Test
    fun `parsePersonalDictEntries skips lines before marker`() {
        val text = "junk\nbefore\n...\nreal\tentry\n"
        assertEquals(
            listOf(DictEntry("real", "entry")),
            PersonalDictManager.parsePersonalDictEntries(text),
        )
    }

    @Test
    fun `readSchemaPacks returns user packs declared in schema`() {
        val rimeDir = createTempDir()
        java.io.File(rimeDir, "pinyin_simp.schema.yaml").writeText("""
translator:
  dictionary: pinyin_simp
  packs:
    - user_simp
    - user_extra
  preedit_format:
    - xform/a/b
""".trimIndent(), Charsets.UTF_8)
        val result = PersonalDictManager.run { readSchemaPacks(rimeDir, "pinyin_simp") }
        assertEquals(listOf("user_simp", "user_extra"), result)
    }

    @Test
    fun `readSchemaPacks returns empty when schema has no packs`() {
        val rimeDir = createTempDir()
        java.io.File(rimeDir, "wubi86.schema.yaml").writeText("translator:\n  dictionary: wubi86\n", Charsets.UTF_8)
        assertTrue(PersonalDictManager.run { readSchemaPacks(rimeDir, "wubi86") }.isEmpty())
    }

    @Test
    fun `readSchemaPacks parses real pinyin schema structure via kaml`() {
        val rimeDir = createTempDir()
        java.io.File(rimeDir, "pinyin_simp.schema.yaml").writeText("""
schema:
  schema_id: pinyin_simp
  name: 简体拼音
switches:
  - name: ascii_mode
    states: [ 中文, 西文 ]
engine:
  translators:
    - punct_translator
    - script_translator
    - reverse_lookup_translator
    - lua_translator@*uuid
translator:
  dictionary: pinyin_simp
  packs:
    - user_simp
  preedit_format:
    - xform/([nl])v/$1ü/
reverse_lookup:
  dictionary: stroke
  prefix: "`"
punctuator:
  import_preset: default
  __include: symbols:/punctuator
""".trimIndent(), Charsets.UTF_8)
        val result = PersonalDictManager.run { readSchemaPacks(rimeDir, "pinyin_simp") }
        assertEquals(listOf("user_simp"), result)
    }

    @Test
    fun `readSchemaPacks falls back to regex when yaml parse fails`() {
        val rimeDir = createTempDir()
        // 非法 YAML（引用未定义别名），kaml 解析失败后应回退正则
        java.io.File(rimeDir, "bad.schema.yaml").writeText("""
translator:
  dictionary: bad
  packs:
    - user_bad
foo: *undefined_alias
""".trimIndent(), Charsets.UTF_8)
        val result = PersonalDictManager.run { readSchemaPacks(rimeDir, "bad") }
        assertEquals(listOf("user_bad"), result)
    }

    @Test
    fun `resolvePersonalDictFile uses schema own packs name`() {
        val rimeDir = createTempDir()
        java.io.File(rimeDir, "pinyin_simp.schema.yaml").writeText("""
translator:
  dictionary: pinyin_simp
  packs:
    - user_simp
""".trimIndent())
        val file = PersonalDictManager.resolvePersonalDictFile(rimeDir, "pinyin_simp")
        assertEquals("user_simp.dict.yaml", file.name)
    }

    @Test
    fun `loadEntries reads schema own pack file`() {
        val context = mockContext()
        val rimeDir = java.io.File(context.filesDir, "rime")
        rimeDir.mkdirs()
        java.io.File(rimeDir, "pinyin_simp.schema.yaml").writeText("""
translator:
  dictionary: pinyin_simp
  packs:
    - user_simp
""".trimIndent(), Charsets.UTF_8)
        java.io.File(rimeDir, "user_simp.dict.yaml").writeText("# Rime dict\n---\nname: user_simp\n...\n你好\tni hao\n", Charsets.UTF_8)
        val entries = PersonalDictManager.loadEntries(context, "pinyin_simp")
        assertTrue(entries.any { it.word == "你好" && it.code == "ni hao" })
    }

    @Test
    fun `loadEntries returns empty when pack file missing`() {
        val context = mockContext()
        val rimeDir = java.io.File(context.filesDir, "rime")
        rimeDir.mkdirs()
        java.io.File(rimeDir, "pinyin_simp.schema.yaml").writeText("""
translator:
  dictionary: pinyin_simp
  packs:
    - user_simp
""".trimIndent(), Charsets.UTF_8)
        assertTrue(PersonalDictManager.loadEntries(context, "pinyin_simp").isEmpty())
    }

    // ── applyCustomPhrases：保存后让正在用的键盘读到 ──

    @Test
    fun `applyCustomPhrases deploys only when the first phrase adds the translator, and always tells the keyboard`() {
        val context = mockContext()
        val rimeDir = java.io.File(context.filesDir, "rime")
        rimeDir.mkdirs()
        java.io.File(rimeDir, "pinyin_simp.schema.yaml").writeText("speller:\n  alphabet: abc\n")
        createCustomPhraseFile(rimeDir, "pinyin_simp")
        var deploys = 0
        var notices = 0
        runBlocking {
            PersonalDictManager.applyCustomPhrases(context, "pinyin_simp", { deploys++ }, { notices++ })
        }
        assertTrue(java.io.File(rimeDir, "pinyin_simp.custom.yaml").readText().contains("table_translator@custom_phrase"))
        assertEquals(1, deploys)
        assertEquals(1, notices)
        // Later edits change only the phrase file: no deployment, but the open session must still re-read it.
        runBlocking {
            PersonalDictManager.applyCustomPhrases(context, "pinyin_simp", { deploys++ }, { notices++ })
        }
        assertEquals(1, deploys)
        assertEquals(2, notices)
    }

    // ── ensureSchemaPack：仅自定义短语 + 清理旧 merged patch ──

    @Test
    fun `ensureSchemaPack creates custom phrase translator when schema has entries`() {
        val context = mockContext()
        val rimeDir = java.io.File(context.filesDir, "rime")
        rimeDir.mkdirs()
        java.io.File(rimeDir, "pinyin_simp.schema.yaml").writeText("""
speller:
  alphabet: abc
  algebra:
    - erase/^xx$/
""".trimIndent())
        createCustomPhraseFile(rimeDir, "pinyin_simp")
        runBlocking { PersonalDictManager.ensureSchemaPack(context, "pinyin_simp") }
        val customFile = java.io.File(rimeDir, "pinyin_simp.custom.yaml")
        assertTrue("custom.yaml should be created", customFile.exists())
        val text = customFile.readText(Charsets.UTF_8)
        assertTrue(text.contains("table_translator@custom_phrase"))
        assertFalse("no merged dictionary patch", text.contains("translator/dictionary"))
    }

    @Test
    fun `ensureSchemaPack does not inject translator when no custom phrase entries`() {
        val context = mockContext()
        val rimeDir = java.io.File(context.filesDir, "rime")
        rimeDir.mkdirs()
        java.io.File(rimeDir, "wubi86.schema.yaml").writeText("""
speller:
  alphabet: abcdefghijklmnopqrstuvwxyz
  max_code_length: 4
""".trimIndent())
        runBlocking { PersonalDictManager.ensureSchemaPack(context, "wubi86") }
        val customFile = java.io.File(rimeDir, "wubi86.custom.yaml")
        if (customFile.exists()) {
            assertFalse("no translator when empty phrase file", customFile.readText(Charsets.UTF_8).contains("table_translator@custom_phrase"))
        }
        assertFalse("no merged dict created", java.io.File(rimeDir, "wubi86_merged.dict.yaml").exists())
    }

    @Test
    fun `ensureSchemaPack is no-op when schema file missing`() {
        val context = mockContext()
        val rimeDir = java.io.File(context.filesDir, "rime")
        rimeDir.mkdirs()
        // no schema file created
        runBlocking { PersonalDictManager.ensureSchemaPack(context, "nonexistent") }
        val customFile = java.io.File(rimeDir, "nonexistent.custom.yaml")
        assertFalse("no custom.yaml should be created for missing schema", customFile.exists())
    }

    @Test
    fun `ensureSchemaPack is idempotent`() {
        val context = mockContext()
        val rimeDir = java.io.File(context.filesDir, "rime")
        rimeDir.mkdirs()
        java.io.File(rimeDir, "pinyin_simp.schema.yaml").writeText("""
speller:
  alphabet: abc
  algebra:
    - erase/^xx$/
""".trimIndent())
        createCustomPhraseFile(rimeDir, "pinyin_simp")
        runBlocking { PersonalDictManager.ensureSchemaPack(context, "pinyin_simp") }
        runBlocking { PersonalDictManager.ensureSchemaPack(context, "pinyin_simp") }
        val text = java.io.File(rimeDir, "pinyin_simp.custom.yaml").readText(Charsets.UTF_8)
        assertEquals(1, text.split("table_translator@custom_phrase").size - 1)
    }

    @Test
    fun `ensureSchemaPack cleans stale merged patch and merged dict`() {
        val context = mockContext()
        val rimeDir = java.io.File(context.filesDir, "rime")
        rimeDir.mkdirs()
        java.io.File(rimeDir, "pinyin_simp.schema.yaml").writeText("""
speller:
  alphabet: abc
  algebra:
    - erase/^xx$/
""".trimIndent())
        // 模拟旧版本遗留：merged dict 转发文件 + custom.yaml 引用 merged 词典
        java.io.File(rimeDir, "pinyin_simp_merged.dict.yaml").writeText(
            "# Rime dict\n---\nname: pinyin_simp_merged\nversion: \"1.0\"\nsort: original\nimport_tables:\n  - pinyin_simp\n...\n",
            Charsets.UTF_8
        )
        java.io.File(rimeDir, "pinyin_simp.custom.yaml").writeText("""patch:
  "translator/dictionary": pinyin_simp_merged
""", Charsets.UTF_8)
        runBlocking { PersonalDictManager.ensureSchemaPack(context, "pinyin_simp") }
        assertFalse("stale merged dict should be removed", java.io.File(rimeDir, "pinyin_simp_merged.dict.yaml").exists())
        val customText = java.io.File(rimeDir, "pinyin_simp.custom.yaml").readText(Charsets.UTF_8)
        assertFalse("merged reference should be removed", customText.contains("pinyin_simp_merged"))
    }

    @Test
    fun `cleanupStaleMergedPatch removes merged dict and reference`() {
        val rimeDir = createTempDir()
        java.io.File(rimeDir, "wubi86_merged.dict.yaml").writeText(
            "# Rime dict\n---\nname: wubi86_merged\nversion: \"1.0\"\nsort: original\nimport_tables:\n  - wubi86\n...\n",
            Charsets.UTF_8
        )
        java.io.File(rimeDir, "wubi86.custom.yaml").writeText("""patch:
  "translator/dictionary": wubi86_merged
  "engine/filters/@before 0": custom_filter
""", Charsets.UTF_8)
        PersonalDictManager.cleanupStaleMergedPatch(rimeDir, "wubi86")
        assertFalse("merged dict should be deleted", java.io.File(rimeDir, "wubi86_merged.dict.yaml").exists())
        val text = java.io.File(rimeDir, "wubi86.custom.yaml").readText(Charsets.UTF_8)
        assertFalse("merged reference should be removed", text.contains("wubi86_merged"))
        assertTrue("other patches preserved", text.contains("custom_filter"))
    }

    @Test
    fun `cleanupStaleMergedPatch no-op when no stale patch`() {
        val rimeDir = createTempDir()
        java.io.File(rimeDir, "wubi86.custom.yaml").writeText("""patch:
  "engine/translators/+":
    - lua_translator@my_script
""", Charsets.UTF_8)
        PersonalDictManager.cleanupStaleMergedPatch(rimeDir, "wubi86")
        val text = java.io.File(rimeDir, "wubi86.custom.yaml").readText(Charsets.UTF_8)
        assertTrue("non-merged patches untouched", text.contains("lua_translator@my_script"))
    }

    // ── 自定义短语 ──

    private val stubHeader = """# Rime table
# coding: utf-8
#@/db_name	custom_phrase
#@/db_type	tabledb
#
"""

    @Test
    fun `parseStableDbEntries reads entries skipping header`() {
        val text = """# Rime table
# coding: utf-8
#@/db_name	custom_phrase
#@/db_type	tabledb
#
测试	ce shi
词条	ci tiao
"""
        val result = PersonalDictManager.parseStableDbEntries(text)
        assertEquals(
            listOf(DictEntry("测试", "ce shi"), DictEntry("词条", "ci tiao")),
            result
        )
    }

    @Test
    fun `parseStableDbEntries reads weight field`() {
        val text = """#
a	b	99
"""
        val result = PersonalDictManager.parseStableDbEntries(text)
        assertEquals(listOf(DictEntry("a", "b", 99)), result)
    }

    @Test
    fun `parseStableDbEntries handles optional weight`() {
        val text = """#
a	b	99
c	d
"""
        val result = PersonalDictManager.parseStableDbEntries(text)
        assertEquals(listOf(DictEntry("a", "b", 99), DictEntry("c", "d")), result)
    }

    @Test
    fun `buildStableDbText preserves header and appends entries`() {
        val entries = listOf(
            DictEntry("联系一下", "lxyx"),
            DictEntry("等等", "dd")
        )
        val result = PersonalDictManager.buildStableDbText(stubHeader, entries)
        assertTrue(result.startsWith("# Rime table"))
        assertTrue(result.contains("联系一下\tlxyx\n"))
        assertTrue(result.contains("等等\tdd\n"))
    }

    @Test
    fun `buildStableDbText includes weight when present`() {
        val entries = listOf(DictEntry("a", "b", 99))
        val result = PersonalDictManager.buildStableDbText(stubHeader, entries)
        assertTrue(result.contains("a\tb\t99\n"))
    }

    @Test
    fun `buildStableDbText omits weight when null`() {
        val entries = listOf(DictEntry("a", "b"))
        val result = PersonalDictManager.buildStableDbText(stubHeader, entries)
        assertTrue(result.contains("a\tb\n"))
    }

    @Test
    fun `stabledb round trip preserves weight`() {
        val original = listOf(DictEntry("联系一下", "lxyx", 99))
        val text = PersonalDictManager.buildStableDbText(stubHeader, original)
        val parsed = PersonalDictManager.parseStableDbEntries(text)
        assertEquals(original, parsed)
    }

    @Test
    fun `a multi line phrase stays one table row and reaches the nine key copy whole`() {
        val context = mockContext()
        val invoice = "名称：某某公司\n税号：91110000XXXX"
        val entries = listOf(DictEntry(invoice, "fpxx"), DictEntry("B站", "bzhan", 1))
        PersonalDictManager.saveCustomPhrases(context, entries = entries)
        val dir = File(context.filesDir, "rime")
        val rows = File(dir, "custom_phrase.txt").readLines().filterNot { it.isBlank() || it.startsWith('#') }
        assertEquals(listOf("名称：某某公司\u2028税号：91110000XXXX\tfpxx", "B站\tbzhan\t1"), rows)
        val loaded = PersonalDictManager.loadCustomPhrases(context)
        assertEquals(invoice, PhraseLineBreaks.typed(loaded.first().word))
        // Saving what was loaded does not change the file.
        val before = File(dir, "custom_phrase.txt").readText()
        PersonalDictManager.saveCustomPhrases(context, entries = loaded)
        assertEquals(before, File(dir, "custom_phrase.txt").readText())
        PersonalDictManager.writeKeyedPhrases(dir, PersonalDictManager.PhraseKeys.T9)
        val t9 = PersonalDictManager.parseStableDbEntries(File(dir, "custom_phrase_t9.txt").readText())
        assertEquals(DictEntry("名称：某某公司\u2028税号：91110000XXXX", "3799"), t9.first())
    }

    @Test
    fun `loadCustomPhrases when file missing returns empty`() {
        val context = mockContext()
        assertTrue(PersonalDictManager.loadCustomPhrases(context).isEmpty())
    }

    @Test
    fun `saveCustomPhrases writes to custom_phrase dot txt`() {
        val context = mockContext()
        val entries = listOf(DictEntry("kingzcheung@gmail.com", "yxdz", 99))
        PersonalDictManager.saveCustomPhrases(context, null, entries)
        val file = PersonalDictManager.getCustomPhraseFile(context)
        assertTrue(file.exists())
        val loaded = PersonalDictManager.parseStableDbEntries(file.readText(Charsets.UTF_8))
        assertTrue(loaded.any { it.word == "kingzcheung@gmail.com" })
    }

    @Test
    fun `applyCustomPhraseTranslator adds translator to custom yaml`() {
        val rimeDir = createTempDir()
        PersonalDictManager.applyCustomPhraseTranslator(rimeDir, "wubi86", "custom_phrase")
        val customFile = File(rimeDir, "wubi86.custom.yaml")
        assertTrue(customFile.exists())
        val text = customFile.readText(Charsets.UTF_8)
        assertTrue(text.contains("table_translator@custom_phrase"))
        assertTrue(text.contains("db_class: stabledb"))
    }

    @Test
    fun `applyCustomPhraseTranslator is idempotent`() {
        val rimeDir = createTempDir()
        PersonalDictManager.applyCustomPhraseTranslator(rimeDir, "wubi86", "custom_phrase")
        PersonalDictManager.applyCustomPhraseTranslator(rimeDir, "wubi86", "custom_phrase")
        val text = File(rimeDir, "wubi86.custom.yaml").readText(Charsets.UTF_8)
        assertEquals(1, text.split("table_translator@custom_phrase").size - 1)
    }

    @Test
    fun `applyCustomPhraseTranslator with custom dictName uses that dictName in config`() {
        val rimeDir = createTempDir()
        PersonalDictManager.applyCustomPhraseTranslator(rimeDir, "wubi86", "custom_phrase_double")
        val text = File(rimeDir, "wubi86.custom.yaml").readText(Charsets.UTF_8)
        assertTrue(text.contains("user_dict: custom_phrase_double"))
    }

    @Test
    fun `applyCustomPhraseTranslator with custom dictName is idempotent`() {
        val rimeDir = createTempDir()
        PersonalDictManager.applyCustomPhraseTranslator(rimeDir, "wubi86", "custom_phrase_double")
        PersonalDictManager.applyCustomPhraseTranslator(rimeDir, "wubi86", "custom_phrase_double")
        val text = File(rimeDir, "wubi86.custom.yaml").readText(Charsets.UTF_8)
        assertEquals(1, text.split("table_translator@custom_phrase").size - 1)
    }

    @Test
    fun `applyCustomPhraseTranslator preserves existing patches`() {
        val dir = createTempDir()
        val file = File(dir, "pinyin_simp.custom.yaml")
        file.writeText("""patch:
  engine/filters/@before/0:
    - lua_filter@custom_filter
  menu/page_size: 8
""", Charsets.UTF_8)
        PersonalDictManager.applyCustomPhraseTranslator(dir, "pinyin_simp", "custom_phrase")
        val text = file.readText(Charsets.UTF_8)
        assertTrue("Lua filter preserved", text.contains("lua_filter@custom_filter"))
        assertTrue("page_size preserved", text.contains("menu/page_size: 8"))
        assertTrue("custom_phrase added", text.contains("table_translator@custom_phrase"))
        assertTrue("custom_phrase config complete", text.contains("db_class: stabledb"))
    }

    @Test
    fun `getCustomPhraseDictName reads user_dict from custom yaml`() {
        val rimeDir = createTempDir()
        File(rimeDir, "wubi86.custom.yaml").writeText("""patch:
  "custom_phrase":
    user_dict: custom_phrase_double
""")
        val result = PersonalDictManager.run { getCustomPhraseDictName(rimeDir, "wubi86") }
        assertEquals("custom_phrase_double", result)
    }

    @Test
    fun `getCustomPhraseDictName returns default when no custom yaml`() {
        val rimeDir = createTempDir()
        val result = PersonalDictManager.run { getCustomPhraseDictName(rimeDir, "wubi86") }
        assertEquals("custom_phrase", result)
    }

    @Test
    fun `getCustomPhraseDictName returns default when no custom_phrase section`() {
        val rimeDir = createTempDir()
        File(rimeDir, "wubi86.custom.yaml").writeText("""patch:
  "translator/packs": ["user_simp_pinyin"]
""")
        val result = PersonalDictManager.run { getCustomPhraseDictName(rimeDir, "wubi86") }
        assertEquals("custom_phrase", result)
    }

    @Test fun `unrelated dictionaries are ignored and a keyed override follows the actual keys`() {
        val dir = createTempDir()
        File(dir, "t9_pinyin.schema.yaml").writeText("# custom_phrase: see settings\ntranslator:\n  user_dict: rime_ice\n")
        assertEquals("custom_phrase", PersonalDictManager.getCustomPhraseDictName(dir, "t9_pinyin"))
        val patch = File(dir, "t9_pinyin.custom.yaml")
        patch.writeText("patch:\n  'custom_phrase/user_dict': 'custom_phrase'  # retain my comment\n  translator/user_dict: rime_ice\n")
        PersonalDictManager.retargetCustomPhraseDict(patch, "custom_phrase_t9")
        assertEquals("custom_phrase_t9", PersonalDictManager.getCustomPhraseDictName(dir, "t9_pinyin"))
        assertTrue(patch.readText().contains("custom_phrase_t9  # retain my comment"))
        assertTrue(patch.readText().contains("translator/user_dict: rime_ice"))
    }

    @Test
    fun `saveCustomPhrases with schemaId writes to the correct custom dict file`() {
        val context = mockContext()
        val rimeDir = File(context.filesDir, "rime")
        rimeDir.mkdirs()
        File(rimeDir, "wubi86.custom.yaml").writeText("""patch:
  "custom_phrase":
    user_dict: custom_phrase_double
""")
        val entries = listOf(DictEntry("test", "ce", 1))
        PersonalDictManager.saveCustomPhrases(context, "wubi86", entries)
        val file = File(rimeDir, "custom_phrase_double.txt")
        assertTrue(file.exists())
        val loaded = PersonalDictManager.parseStableDbEntries(file.readText(Charsets.UTF_8))
        assertTrue(loaded.any { it.word == "test" })
    }

    @Test
    fun `saveCustomPhrases without schemaId writes to default custom_phrase dot txt`() {
        val context = mockContext()
        val entries = listOf(DictEntry("hello", "hw"))
        PersonalDictManager.saveCustomPhrases(context, null, entries)
        val file = PersonalDictManager.getCustomPhraseFile(context)
        assertTrue(file.exists())
        val loaded = PersonalDictManager.parseStableDbEntries(file.readText(Charsets.UTF_8))
        assertTrue(loaded.any { it.word == "hello" })
    }

    private fun createTempDir(): File {
        val dir = File.createTempFile("personal_dict_test_dir", "")
        dir.delete()
        dir.mkdirs()
        return dir
    }
}
