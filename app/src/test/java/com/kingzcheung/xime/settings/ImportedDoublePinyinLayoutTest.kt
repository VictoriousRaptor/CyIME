package com.kingzcheung.xime.settings

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #160：写明双拼的导入方案也能用自建合并键布局。 */
class ImportedDoublePinyinLayoutTest {
    private val layout = "custom_pinyin_" + "3".repeat(32)

    @After fun clear() = InputProfiles.updateInstalled(emptyList())

    private fun schema(header: String, alphabet: String = "zyxwvutsrqponmlkjihgfedcba") = """
        |schema:
        |  schema_id: imported_sp
        |  name: 导入双拼
        |$header
        |speller:
        |  alphabet: $alphabet
        |  algebra:
        |    __include: imported_algebra:/flypy
        |""".trimMargin()

    @Test fun onlySchemesThatSayTheyAreChineseDoublePinyinCount() {
        val found = DoublePinyinSchemes.importedDoublePinyin("imported_sp",
            schema("  language: zh\n  input_scheme: double_pinyin"))!!
        assertEquals("导入双拼", found.name)
        assertFalse(found.semicolon)
        // 没写语言也算；写了别的语言、没写双拼、id 对不上、CyIME 生成的都不算。
        assertEquals("imported_sp", DoublePinyinSchemes.importedDoublePinyin("imported_sp", schema("  input_scheme: double_pinyin"))?.id)
        assertNull(DoublePinyinSchemes.importedDoublePinyin("imported_sp", schema("  language: ja\n  input_scheme: double_pinyin")))
        assertNull(DoublePinyinSchemes.importedDoublePinyin("imported_sp", schema("  language: zh")))
        assertNull(DoublePinyinSchemes.importedDoublePinyin("other", schema("  input_scheme: double_pinyin")))
        assertNull(DoublePinyinSchemes.importedDoublePinyin("imported_sp",
            DoublePinyinScheme.GENERATED_MARK + "imported_sp\n" + schema("  input_scheme: double_pinyin")))
        // ing 在分号上的方案（微软双拼）：合并键布局没有分号键，并到 L 上。
        assertTrue(DoublePinyinSchemes.importedDoublePinyin("imported_sp",
            schema("  input_scheme: double_pinyin", alphabet = "zyxwvutsrqponmlkjihgfedcba;"))!!.semicolon)
    }

    @Test fun theLayoutVersionIncludesTheOriginalAndOnlyAppendsMergedKeys() {
        val id = DoublePinyinScheme.layoutVariantId("imported_sp", layout)
        val text = DoublePinyinScheme.importedLayoutSchema("imported_sp", "导入\"双拼\"", id, "我的布局", listOf("ui", "q", "we"), semicolon = true)
        assertTrue(text.startsWith(DoublePinyinScheme.GENERATED_MARK + id + "\n"))
        assertTrue(text.contains("  schema_id: $id\n  name: \"导入\\\"双拼\\\"\"\n  language: zh\n  input_scheme: double_pinyin\n"))
        assertTrue(text.contains("__include: imported_sp.schema:/\n"))
        assertTrue(text.contains("    translator/prism: $id\n"))
        assertTrue(text.contains("    translator/enable_correction: false\n"))
        assertTrue(text.contains("    speller/algebra/+:\n" +
            "      - \"derive/;/l/\"\n" +
            "      - \"derive/^(.{0})[i](.*)$/\$1u\$2/\"\n" +
            "      - \"derive/^(.{0})[e](.*)$/\$1w\$2/\"\n" +
            "      - \"derive/^(.{1})[i](.*)$/\$1u\$2/\"\n" +
            "      - \"derive/^(.{1})[e](.*)$/\$1w\$2/\"\n"))
        // CyIME 读方案列表时认得出它是中文双拼。
        val meta = SchemaManager.extractSchemaBlock(text)!!
        assertTrue(meta.contains("input_scheme: double_pinyin"))
    }

    @Test fun aRegisteredImportedVersionIsACompanionOfItsSchemeAndLayout() {
        val id = DoublePinyinScheme.layoutVariantId("imported_sp", layout)
        assertFalse(DoublePinyinSchemes.isOnLayout(id))
        InputProfiles.updateInstalled(listOf(
            SchemaMeta("imported_sp", "导入双拼", language = InputLanguage.CHINESE, scheme = InputScheme.DOUBLE_PINYIN),
            SchemaMeta(id, "导入双拼", language = InputLanguage.CHINESE, scheme = InputScheme.DOUBLE_PINYIN),
            // 不是写明双拼的不算。
            SchemaMeta("someone_$layout", "别的", scheme = InputScheme.EXTERNAL)))
        assertTrue(DoublePinyinSchemes.isOnLayout(id))
        assertEquals("imported_sp", DoublePinyinSchemes.baseId(id))
        assertEquals(layout, DoublePinyinSchemes.layoutOf(id))
        assertEquals(listOf("imported_sp", layout), CyimeInputDefaults.companionNeeds(id))
        assertNull(CyimeInputDefaults.companionNeeds("someone_$layout"))
        // 导入方案没有键位表：键盘照旧按方案自己的规则，不借小鹤的编码。
        assertNull(DoublePinyinSchemes.find(id))
    }
}
