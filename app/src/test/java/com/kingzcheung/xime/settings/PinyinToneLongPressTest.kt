package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 中文 26 键长按字母的气泡（角标选「滑动＋字母气泡」时）：大小写加拼音四声（用户 11-08：长按字母要能出带调拼音）。
 * 西文变音（â ö ø ß ç…）不放在中文键盘上（用户 11-24「中文去掉 仅英文支持 最多留一个 ø」「英语 西欧对应的键盘才需要」）：
 * 英文键盘只有 o 带 ø，其余变音只在德法等西欧键盘上。
 */
class PinyinToneLongPressTest {
    private fun repoFile(rel: String): File {
        for (base in listOf(File("."), File("app"), File(".."))) {
            val f = File(base, rel)
            if (f.exists()) return f
        }
        error("file not found: $rel")
    }

    /** 中文 26 键（keyboard.qwerty）或英文（qwerty_en）里某个字母的长按列表。 */
    private fun longPress(key: String, english: Boolean): List<String> {
        val text = repoFile("src/main/assets/xime.yaml").readText()
        val section = if (english) text.substringAfter("\n  qwerty_en:\n").substringBefore("\n  qwerty_")
            else text.substringAfter("\n  qwerty:\n").substringBefore("\n  qwerty_en:\n")
        val line = section.lines().first { it.trimStart().startsWith("$key: { tap: \"$key\"") }
        val values = line.substringAfter("long_press:").substringAfter("values: [").substringBefore("]")
        return Regex("\"([^\"]*)\"").findAll(values).map { it.groupValues[1] }.toList()
    }

    @Test fun chineseVowelsOfferOnlyCaseAndPinyinTones() {
        val expected = mapOf(
            "a" to listOf("a", "A", "ā", "á", "ǎ", "à"),
            "e" to listOf("e", "E", "ē", "é", "ě", "è"),
            "i" to listOf("i", "I", "ī", "í", "ǐ", "ì"),
            "o" to listOf("o", "O", "ō", "ó", "ǒ", "ò"),
            "u" to listOf("u", "U", "ū", "ú", "ǔ", "ù", "ü"),
            "v" to listOf("v", "V", "ü", "ǖ", "ǘ", "ǚ", "ǜ"),
        )
        for ((key, list) in expected) assertEquals(key, list, longPress(key, english = false))
    }

    @Test fun chineseConsonantsHaveNoWesternAccents() {
        for (key in "bcdfghjklmnpqrstwxyz".map { it.toString() })
            assertEquals(key, listOf(key, key.uppercase()), longPress(key, english = false))
    }

    @Test fun englishKeepsOnlyOSlash() {
        assertEquals(listOf("o", "O", "ø"), longPress("o", english = true))
        for (key in "aeiucnsy".map { it.toString() })
            assertTrue("$key 英文键盘不该有西文变音", longPress(key, english = true).all { it.equals(key, ignoreCase = true) })
    }
}
