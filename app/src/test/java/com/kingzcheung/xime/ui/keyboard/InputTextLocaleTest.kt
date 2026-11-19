package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.settings.InputScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputTextLocaleTest {
    @Test fun hanCharactersFollowTheInputLanguageNotTheSystem() {
        // 11-15: 日文系统上打中文，候选的「直」「骨」是日本写法；Gboard 跟着键盘语言换。
        assertEquals("zh-CN", inputTextLocaleTag(InputLanguage.CHINESE, InputScheme.PINYIN, false))
        assertEquals("zh-CN", inputTextLocaleTag(InputLanguage.CHINESE, InputScheme.WUBI, false))
        assertEquals("繁体输出开着按台湾写法", "zh-TW", inputTextLocaleTag(InputLanguage.CHINESE, InputScheme.PINYIN, true))
        assertEquals("zh-TW", inputTextLocaleTag(InputLanguage.CHINESE, InputScheme.ZHUYIN, false))
        assertEquals("zh-TW", inputTextLocaleTag(InputLanguage.CHINESE, InputScheme.CANGJIE, false))
        assertEquals("ja-JP", inputTextLocaleTag(InputLanguage.JAPANESE, InputScheme.ROMAJI, false))
        assertEquals("ko-KR", inputTextLocaleTag(InputLanguage.KOREAN, InputScheme.DIRECT, false))
        assertNull("字母语言跟系统走", inputTextLocaleTag(InputLanguage.ENGLISH, InputScheme.DIRECT, false))
        assertNull(inputTextLocaleTag(InputLanguage.FRENCH, InputScheme.DIRECT, false))
    }

    @Test fun committedHanTextCarriesTheInputLanguage() {
        // 11-16: 日语键盘打「自信」，上屏后输入框按中文写法画。上屏时带上 ja-JP。
        assertTrue(needsTextLocale("自信", "ja-JP"))
        assertTrue(needsTextLocale("じしん自信", "ja-JP"))
        assertTrue("扩展 B 区的字也算", needsTextLocale("𠮷", "ja-JP"))
        // 没有汉字（假名、谚文、字母、标点）不标；不知道写法（字母语言）不标。
        assertFalse(needsTextLocale("じしん", "ja-JP"))
        assertFalse(needsTextLocale("한국어", "ko-KR"))
        assertFalse(needsTextLocale("hello,", "zh-CN"))
        assertFalse(needsTextLocale("自信", null))
    }
}
