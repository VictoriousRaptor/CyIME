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
        assertFalse(KeyboardInputPreferences().japaneseKanji)
        assertFalse(KeyboardInputPreferences().koreanHanja)
        assertFalse(KeyboardInputPreferences().cantoneseCharacters)
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
        // 吔、蚵 went into the main dictionary 2026-11-12; the rare list no longer repeats them.
        // 槻、榎、塁 (and 沢) are Japanese: 11-20 they are in cyime_japanese.txt, neither here nor in the main dictionary.
        listOf("槻", "吔", "蚵", "榎", "塁", "沢").forEach { ch -> assertFalse(ch, rare.any { it[0] == ch }) }
        rare.forEach { row ->
            assertEquals(row.toString(), 2, row.size)
            assertEquals(row.toString(), 1, row[0].length)
            assertTrue(row.toString(), Character.isIdeographic(row[0][0].code))
            assertFalse(row.toString(), row[0] in commonText)
            assertTrue(row.toString(), row[1] in commonCodes)
        }
    }

    /**
     * 他 11-20：沢 他从没见过，凭什么打得出来；是日语的字就叫日语的字，词组也一样。日文汉字、韩文汉字、粤语字各一张表、
     * 一个开关，不在主词库，也不在「生僻字」里。
     */
    @Test fun japaneseKoreanAndCantoneseHaveTheirOwnLists() {
        fun rows(name: String) = File(root, name).readLines().filter { it.isNotEmpty() }.map { it.split('\t') }
        val rare = rows("cyime_rare.txt").map { it[0] }.toSet()
        val japanese = rows("cyime_japanese.txt")
        val korean = rows("cyime_korean.txt")
        val cantonese = rows("cyime_cantonese.txt")
        listOf("沢" to "ze", "槻" to "gui", "榎" to "jia", "塁" to "lei", "県" to "xian")
            .forEach { (ch, code) -> assertTrue(ch, listOf(ch, code) in japanese) }
        assertTrue(listOf("榎本武扬", "jia ben wu yang") in japanese)
        assertTrue(listOf("四糸乃", "si si nai") in japanese)
        assertTrue(listOf("乫", "jia") in korean)
        assertTrue(listOf("佢", "qu") in cantonese)
        for (list in listOf(japanese, korean, cantonese)) list.forEach { row ->
            assertEquals(row.toString(), 2, row.size)
            assertFalse(row.toString(), row[0] in rare)
        }
        // 中文也在用的留在中文：糸 砦 疋 碁 袴 竜 还在生僻字里，宏碁、石碁镇、者竜乡、糸部 还在主词库。
        listOf("糸", "砦", "疋", "碁", "袴", "竜").forEach { assertTrue(it, it in rare) }
        // 只是多一个粤语义项的字（㩒 同擒）还算中文生僻字。
        assertTrue("㩒" in rare)
        fun mainTexts(name: String) = File(root, "cn_dicts/$name.dict.yaml").readLines().dropWhile { it.trimEnd() != "..." }
            .drop(1).filter { it.isNotBlank() && !it.startsWith("#") }.map { it.substringBefore('\t') }.toSet()
        val main = listOf("base", "ext", "tencent", "others").flatMap { mainTexts(it) }.toSet()
        val japaneseChars = japanese.map { it[0] }.filter { it.codePointCount(0, it.length) == 1 }.toSet()
        main.forEach { word -> assertFalse(word, word.codePoints().anyMatch { String(Character.toChars(it)) in japaneseChars }) }
        listOf("榎本武扬", "栃木县", "三个県", "糸守镇", "四糸乃").forEach { assertFalse(it, it in main) }
        listOf("宏碁", "石碁镇", "者竜乡", "糸部", "古砦仫佬族乡").forEach { assertTrue(it, it in main) }
        val terms = File(root, "cyime_terms.dict.yaml").readText()
        listOf("槻\t", "榎\t", "塁\t").forEach { assertFalse(it, terms.contains("\n$it")) }
    }

    /**
     * 他 11-20：开了开关还缺不缺字？常用字在大字表里的少见读音（谜 mei）也跟生僻字开关；手写、双拼、仓颉、注音都要一样。
     */
    @Test fun everyInputMethodFollowsTheSwitches() {
        val common = File(root, "cn_dicts/8105.dict.yaml").readLines().dropWhile { it.trimEnd() != "..." }.drop(1)
            .filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }
        val readings = File(root, "cyime_rare_readings.txt").readLines().filter { it.isNotEmpty() }.map { it.split('\t') }
        assertTrue(listOf("谜", "mei") in readings)
        readings.forEach { row ->
            assertEquals(row.toString(), 2, row.size)
            assertTrue(row.toString(), common.any { it[0] == row[0] })
            assertFalse(row.toString(), common.any { it[0] == row[0] && it[1] == row[1] })
        }
        // 注音：按注音自己的拼写规则查四张表。
        val zhuyin = File("src/main/assets/rime_chinese/cyime_zhuyin.schema.yaml").readText()
        assertTrue(zhuyin.contains("    - script_translator\n"))
        assertTrue(zhuyin.contains("    - lua_translator@*cyime_rare_chars\n"))
        assertTrue(zhuyin.contains("\ncyime_rare_merged_keys: true\n"))
        // 仓颉、速成、笔画：码表按字形什么字都有，日文、韩文、粤语三类按开关滤掉。
        for (name in listOf("cyime_cangjie", "cyime_quick", "stroke")) {
            assertTrue(name, File("src/main/assets/rime_chinese/$name.schema.yaml").readText().contains("    - lua_filter@*cyime_char_lists\n"))
        }
        assertTrue(File("src/main/assets/rime_chinese/lua/cyime_char_lists.lua").isFile)
        // 手写中文用同一份表（CharacterSwitchLists 读 assets 里的 rime_ice/）。
        val japanese = com.kingzcheung.xime.settings.CharacterSwitchLists.parse(File(root, "cyime_japanese.txt").readText())
        assertTrue("沢" in japanese && "槻" in japanese)
        assertFalse("榎本武扬" in japanese)
    }
}
