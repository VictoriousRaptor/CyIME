package com.kingzcheung.xime.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** 纠错只在 26 键全拼、26 键双拼、九键全拼起作用；只开 14 键、九键双拼的人看到的纠错开关是灰的（11-03，11-16 加九键）。 */
class TypoCorrectionSupportTest {
    @Test fun onlyAlphabeticPinyinAndDoublePinyinCanCorrect() {
        assertEquals(TypoCorrectionSupport(fullPinyin = true, doublePinyin = false, nineKey = true), TypoCorrectionSupport.of(listOf("rime_ice", "t9_pinyin")))
        assertEquals(TypoCorrectionSupport(fullPinyin = false, doublePinyin = true), TypoCorrectionSupport.of(listOf("double_pinyin_flypy")))
        assertEquals(TypoCorrectionSupport(fullPinyin = false, doublePinyin = false, nineKey = true),
            TypoCorrectionSupport.of(listOf("t9_pinyin", "pinyin_14jian", "double_pinyin_flypy_14jian", "english")))
        assertEquals(TypoCorrectionSupport(fullPinyin = false, doublePinyin = false),
            TypoCorrectionSupport.of(listOf("pinyin_14jian", "double_pinyin_flypy_14jian", "english")))
        assertEquals(TypoCorrectionSupport(fullPinyin = true, doublePinyin = true), TypoCorrectionSupport.of(listOf("pinyin_qwjrtk", "double_pinyin_flypy")))
        assertEquals(TypoCorrectionSupport.ALL, TypoCorrectionSupport.of(listOf("pinyin_qwjrtk", "double_pinyin_flypy", "t9_pinyin")))
    }
}
