package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.settings.InputScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
