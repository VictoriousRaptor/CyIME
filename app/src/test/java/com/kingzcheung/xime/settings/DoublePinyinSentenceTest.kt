package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 双拼整句和全拼一样由词组模型挑（2026-11-03 用户转来：「组合词不太能打出来」，小鹤双拼 tkgjxp 只出 听感谢，
 * 挺感谢 打不出来）。tasks-chinese.gradle.kts 给小鹤双拼加上全拼的组句规则和 t9/sentence_rescore；
 * 双拼的键不带元音，引擎按两键一音节判断够不够两个音节（t9/rescore_keys_per_syllable）。
 */
class DoublePinyinSentenceTest {
    private val generated = File("build/generated/chinese-assets/rime_ice")

    private fun section(text: String, name: String) =
        Regex("(?ms)^$name:\\n(.*?)(?=^\\S|\\z)").find(text)?.groupValues?.get(1).orEmpty()

    private fun line(block: String, key: String) =
        Regex("(?m)^  $key: (.*)$").find(block)?.groupValues?.get(1)

    @Test fun flypyMakesSentencesLikeFullPinyin() {
        val full = File(generated, "rime_ice.schema.yaml").readText()
        val flypy = File(generated, "double_pinyin_flypy.schema.yaml").readText()
        val fullTranslator = section(full, "translator")
        val flypyTranslator = section(flypy, "translator")
        for (key in listOf("sentence_char_homophones", "sentence_position_penalty", "sentence_position_final_only",
                "sentence_position_initial_only", "sentence_cutoff_threshold")) {
            assertEquals(key, line(fullTranslator, key), line(flypyTranslator, key))
        }
        // 11-26：给词组模型挑的整句和全拼一样多（16 句、短输入 32 句、短输入每段两个词、换前缀的整句、权重相近的第二个词）。
        // 原来双拼只有 4 句、每段一个词，同一个模型、同一句话比全拼差（我吗让我早点回家、她还在睡觉呢）。
        for (key in listOf("max_sentences", "sentence_word_homophones", "sentence_word_max_syllables",
                "sentence_other_prefixes", "sentence_near_homophone_margin", "sentence_short_max_sentences")) {
            assertEquals(key, line(fullTranslator, key), line(flypyTranslator, key))
        }
        assertEquals("16", line(flypyTranslator, "max_sentences"))
        val t9 = section(flypy, "t9")
        for (key in listOf("rescore_rime_weight", "near_homophone_penalty")) {
            assertEquals(key, line(section(full, "t9"), key), line(t9, key))
        }
        assertEquals("true", line(t9, "sentence_rescore"))
        assertEquals("2", line(t9, "rescore_keys_per_syllable"))
        assertEquals(line(section(full, "t9"), "ngram_model"), line(t9, "ngram_model"))
        assertEquals("false", line(t9, "ngram_sentence_start"))
        // 全拼照旧按元音段判断（zdky 这类简拼保持 Rime 原顺序）。
        assertEquals(null, line(section(full, "t9"), "rescore_keys_per_syllable"))
    }

    @Test fun customDoublePinyinSchemesKeepTheSentenceSettings() {
        val base = File(generated, "double_pinyin_flypy.schema.yaml").readText()
        val schema = DoublePinyinScheme.ZIRANMA.schema(base)
        assertTrue(schema.contains("\n  max_sentences: 16\n"))
        assertTrue(schema.contains("\n  sentence_short_max_sentences: 32\n"))
        assertTrue(schema.contains("\nt9:\n  sentence_rescore: true\n  rescore_keys_per_syllable: 2\n"))
    }

    @Test fun engineCountsDoublePinyinSyllablesByKeys() {
        val rescore = File("src/main/jni/cyime_rime/t9_sentence_rescore.cc").readText()
        assertTrue(rescore.contains("groups = keys / keys_per_syllable;"))
        val options = File("src/main/jni/cyime_rime/t9_script_options.cc").readText()
        assertTrue(options.contains("config->GetInt(\"t9/rescore_keys_per_syllable\", &keys_per_syllable);"))
        // 两份 librime 补丁必须相同（Gradle 先打 patches/ 那份，CMake 再打 jni/cmake 那份）。
        // 「短输入」双拼按键数算音节（全拼数元音段，双拼的键没有元音）。
        val short = "input_vowel_groups_ = (letters + keys - 1) / keys;"
        for (path in listOf("src/main/jni/cmake/RimeDictionary.patch", "../patches/cyime-librime.patch"))
            assertEquals(path, 2, File(path).readText().split(short).size)
        val call = "t9_ngram_sentence_start_, t9_rescore_keys_per_syllable_, t9_rescore_rime_weight_, t9_tail_correction_margin_, t9_slip_correction_margin_,"
        for (path in listOf("src/main/jni/cmake/RimeDictionary.patch", "../patches/cyime-librime.patch"))
            assertEquals(path, 2, File(path).readText().split(call).size)
    }
}
