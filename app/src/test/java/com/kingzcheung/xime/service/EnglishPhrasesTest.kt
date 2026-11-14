package com.kingzcheung.xime.service

import com.kingzcheung.xime.settings.DictEntry
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class EnglishPhrasesTest {
    private val phrases = listOf(
        DictEntry("me@example.com", "yx"),
        DictEntry("我的邮箱", "yx", 5),
        DictEntry("https://example.com", "yxw"),
        DictEntry("hello", "hello"),
    )

    @Test
    fun onlyTheWholeCodeMatchesIgnoringCase() {
        // 和中文拼音里的短语一样只认完整编码：yx 不给 yxw 那条；权重高的先。
        assertEquals(listOf("我的邮箱", "me@example.com"), EnglishPhrases.matching("yx", phrases))
        assertEquals(listOf("我的邮箱", "me@example.com"), EnglishPhrases.matching("YX", phrases))
        assertEquals(emptyList<String>(), EnglishPhrases.matching("y", phrases))
        // 短语和打的字母一样：不重复给。
        assertEquals(emptyList<String>(), EnglishPhrases.matching("hello", phrases))
        assertEquals(emptyList<String>(), EnglishPhrases.matching("", phrases))
        assertEquals(emptyList<String>(), EnglishPhrases.matching("y x", phrases))
    }

    @Test
    fun phrasesGoBeforeCompletionsWithoutDuplicates() {
        assertEquals(listOf("me@example.com", "yxa", "yxb"),
            EnglishPhrases.merge(listOf("me@example.com"), listOf("yxa", "yxb"), 5))
        assertEquals(listOf("yxa", "yxb"), EnglishPhrases.merge(emptyList(), listOf("yxa", "yxb"), 5))
        assertEquals(listOf("a", "b"), EnglishPhrases.merge(listOf("a"), listOf("a", "b", "c"), 2))
    }

    @Test
    fun keepsOnlyLetterCodesFromThePhraseFile() {
        val file = File.createTempFile("custom_phrase", ".txt")
        try {
            file.writeText("我的邮箱\tyx\t5\n13800138000\t138\n混合\ta1\n第一行\u2028第二行\tdz\n")
            assertEquals(listOf("yx"), EnglishPhrases.phrases(file).map { it.code })
        } finally {
            file.delete()
        }
    }
}
