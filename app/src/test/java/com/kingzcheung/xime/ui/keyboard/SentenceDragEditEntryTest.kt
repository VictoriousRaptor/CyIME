package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.settings.EngineProfile
import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.settings.InputLayout
import com.kingzcheung.xime.settings.InputProfile
import com.kingzcheung.xime.settings.InputScheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 拖选改局部给 26 键全拼和双拼；其它方案照旧用长按首选里的拆词重选，两种入口不同时出现。 */
class SentenceDragEditEntryTest {
    private fun profile(scheme: InputScheme, layout: InputLayout) =
        InputProfile(InputLanguage.CHINESE, scheme, layout, EngineProfile.Rime("test"))

    @Test fun onlyQwertyPinyinUsesDragEdit() {
        assertTrue(usesSentenceDragEdit(profile(InputScheme.DOUBLE_PINYIN, InputLayout.QWERTY)))
        assertTrue("全拼 26 键", usesSentenceDragEdit(profile(InputScheme.PINYIN, InputLayout.QWERTY)))
        assertFalse("双拼九键", usesSentenceDragEdit(profile(InputScheme.DOUBLE_PINYIN, InputLayout.T9)))
        assertFalse("双拼 14 键", usesSentenceDragEdit(profile(InputScheme.DOUBLE_PINYIN, InputLayout.MERGED14)))
        assertFalse("五笔", usesSentenceDragEdit(profile(InputScheme.WUBI, InputLayout.QWERTY)))
        assertFalse("全拼九键", usesSentenceDragEdit(profile(InputScheme.PINYIN, InputLayout.T9)))
    }
}
