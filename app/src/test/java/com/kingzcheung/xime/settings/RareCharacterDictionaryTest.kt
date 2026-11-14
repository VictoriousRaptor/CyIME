package com.kingzcheung.xime.settings

import com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Rare characters generated from rime-ice 41448.dict.yaml (tasks-chinese.gradle.kts; 瀺 2026-10-30, 槻 2026-10-31 were missing). */
class RareCharacterDictionaryTest {
    private val root = File("build/generated/chinese-assets/rime_ice")

    @Test fun offByDefaultAndOutsideTheMainDictionary() {
        assertFalse(KeyboardInputPreferences().rareCharacters)
        // Imported into rime_ice the list cost the nine-key replay +140% dictionary entries per key.
        assertFalse("cyime_rare" in DictionaryLookup.importTables(File(root, "rime_ice.dict.yaml")))
        assertFalse(File(root, "cn_dicts/41448.dict.yaml").exists())
        assertTrue(File(root, "rime_ice.schema.yaml").readText().contains("    - lua_translator@*cyime_rare_chars\n"))
        assertTrue(File(root, "lua/cyime_rare_chars.lua").isFile)
        // Nine-key full pinyin reads it too (digits of one syllable); 14 keys do not.
        assertTrue(File("src/main/assets/rime_chinese/t9_pinyin.schema.yaml").readText().contains("    - lua_translator@*cyime_rare_chars\n"))
        // Double pinyin (GitHub #72): the base scheme carries the translator and the flag the generated schemes copy.
        val flypy = File(root, "double_pinyin_flypy.schema.yaml").readText()
        assertTrue(flypy.contains("    - script_translator\n    - lua_translator@*cyime_rare_chars\n"))
        assertTrue(flypy.contains("\ncyime_rare_double_pinyin: true\n"))
        // 14 keys, full pinyin and double pinyin (2026-11-02): merged keys are read through the scheme's own algebra;
        // schemes generated from a key table copy both lines from the built-in 14-key flypy.
        for (name in listOf("pinyin_14jian", "double_pinyin_flypy_14jian")) {
            val k14 = File("src/main/assets/rime_chinese/$name.schema.yaml").readText()
            assertTrue(name, k14.contains("    - script_translator\n    # 生僻字（设置「生僻字」，默认关；关着时只看一下开关就返回），见 lua/cyime_rare_chars.lua。\n    - lua_translator@*cyime_rare_chars\n"))
            assertTrue(name, k14.contains("\ncyime_rare_merged_keys: true\n"))
        }
        val generated = DoublePinyinScheme.FLYPY.copy(id = DoublePinyinScheme.ZIRANMA_ID)
            .k14Schema(File("src/main/assets/rime_chinese/double_pinyin_flypy_14jian.schema.yaml").readText())
        assertTrue(generated.contains("    - lua_translator@*cyime_rare_chars\n"))
        assertTrue(generated.contains("\ncyime_rare_merged_keys: true\n"))
    }

    @Test fun onlyMissingBmpCharactersWithKnownReadings() {
        val common = File(root, "cn_dicts/8105.dict.yaml").readLines().dropWhile { it.trimEnd() != "..." }.drop(1)
            .filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }
        val commonText = common.map { it[0] }.toSet()
        val commonCodes = common.map { it[1] }.toSet()
        val rare = File(root, "cyime_rare.txt").readLines().filter { it.isNotEmpty() }.map { it.split('\t') }
        assertTrue(listOf("瀺", "chan") in rare)
        // 槻 went into the main dictionary 2026-11-12 with 吔、蚵、榎、塁; the rare list no longer repeats them.
        listOf("槻", "吔", "蚵", "榎", "塁").forEach { ch -> assertFalse(ch, rare.any { it[0] == ch }) }
        rare.forEach { row ->
            assertEquals(row.toString(), 2, row.size)
            assertEquals(row.toString(), 1, row[0].length)
            assertTrue(row.toString(), Character.isIdeographic(row[0][0].code))
            assertFalse(row.toString(), row[0] in commonText)
            assertTrue(row.toString(), row[1] in commonCodes)
        }
    }
}
