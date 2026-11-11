package com.kingzcheung.xime.ui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class LongPressChoiceTest {
    private fun route(label: String, accentLetters: String = ""): Pair<String, String> {
        var path = ""
        var value = ""
        commitLongPressChoice(label, { path = "commit"; value = it }, { path = "key"; value = it }, accentLetters)
        return path to value
    }

    @Test
    fun lowercaseLetterJoinsPinyinLikeATap() {
        // h + 长按 g 选 g：应接到拼音里，不能先上屏首选「和」再上屏 g。
        assertEquals("key" to "g", route("g"))
    }

    @Test
    fun otherChoicesStillCommitDirectly() {
        assertEquals("commit" to "G", route("G"))
        assertEquals("commit" to "è", route("è"))
        assertEquals("commit" to "3", route("3"))
    }

    @Test
    fun accentLettersJoinTheWordBeingTyped() {
        // 西班牙语长按 a 选 á：接进正在打的词（cancio + ó + n），补全才认得出 canción。
        val spanish = com.kingzcheung.xime.settings.LetterLanguage.SPANISH.extraLetters
        assertEquals("key" to "á", route("á", spanish))
        assertEquals("key" to "ñ", route("ñ", spanish))
        assertEquals("commit" to "¿", route("¿", spanish))
        assertEquals("commit" to "Á", route("Á", spanish))
        // 德语 ß、俄语 ё 同样接进词里；韩语长按的 ㄲ 交给组字。
        assertEquals("key" to "ß", route("ß", com.kingzcheung.xime.settings.LetterLanguage.GERMAN.extraLetters))
        assertEquals("key" to "ё", route("ё", com.kingzcheung.xime.settings.LetterLanguage.RUSSIAN.extraLetters))
        assertEquals("key" to "ㄲ", route("ㄲ", com.kingzcheung.xime.settings.LetterLanguage.KOREAN.extraLetters))
        // 别的语言的字母不算：西班牙语键盘上的 ß 照旧直接上屏。
        assertEquals("commit" to "ß", route("ß", spanish))
        // 其他键盘照旧直接上屏。
        assertEquals("commit" to "á", route("á"))
    }

    @Test
    fun accentBubbleFollowsShift() {
        val italian = com.kingzcheung.xime.settings.LetterLanguage.ITALIAN.extraLetters
        // Shift 后气泡显示大写，和键帽一致；没 Shift 照旧小写。
        assertEquals(listOf("È", "É"), listOf("è", "é").map { longPressShownLabel(it, true, italian) })
        assertEquals("è", longPressShownLabel("è", false, italian))
        // 标点、别的语言的字母、没有单个大写的 ß 都不变。
        assertEquals("«", longPressShownLabel("«", true, italian))
        assertEquals("ñ", longPressShownLabel("ñ", true, italian))
        val german = com.kingzcheung.xime.settings.LetterLanguage.GERMAN.extraLetters
        assertEquals("ß", longPressShownLabel("ß", true, german))
        assertEquals("Ä", longPressShownLabel("ä", true, german))
        // 英文键盘（没有本语言字母）不改。
        assertEquals("g", longPressShownLabel("g", true, ""))
    }

    @Test
    fun everyLetterLanguageShowsCapitalAccentsUnderShift() {
        // 用户 11-09：不止意大利语，西、德、法、俄语键盘长按出的小写字母 Shift 后都要显示大写（ß 没有单个大写除外）。
        for (letters in com.kingzcheung.xime.settings.LetterLanguage.entries) {
            if (letters == com.kingzcheung.xime.settings.LetterLanguage.KOREAN) continue // 韩文字母没有大小写
            for (char in letters.extraLetters.filter { it.isLowerCase() && it != 'ß' }) {
                assertEquals("${letters.schemaId} $char", char.uppercase(),
                    longPressShownLabel(char.toString(), true, letters.extraLetters))
            }
        }
    }
}
