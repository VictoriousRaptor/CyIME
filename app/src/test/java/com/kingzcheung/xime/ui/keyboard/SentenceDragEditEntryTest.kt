package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.settings.EngineProfile
import com.kingzcheung.xime.settings.InputLanguage
import com.kingzcheung.xime.settings.InputLayout
import com.kingzcheung.xime.settings.InputProfile
import com.kingzcheung.xime.settings.InputScheme
import com.kingzcheung.xime.settings.LayoutKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 拖选改局部给 26 键和内置 14 键的全拼、双拼；其它方案照旧用长按首选里的拆词重选，两种入口不同时出现。 */
class SentenceDragEditEntryTest {
    private fun profile(scheme: InputScheme, layout: InputLayout) =
        InputProfile(InputLanguage.CHINESE, scheme, layout, EngineProfile.Rime("test"))

    @Test fun qwertyAnd14KeyPinyinUseDragEdit() {
        assertTrue(usesSentenceDragEdit(profile(InputScheme.DOUBLE_PINYIN, InputLayout.QWERTY)))
        assertTrue("全拼 26 键", usesSentenceDragEdit(profile(InputScheme.PINYIN, InputLayout.QWERTY)))
        assertFalse("双拼九键", usesSentenceDragEdit(profile(InputScheme.DOUBLE_PINYIN, InputLayout.T9)))
        assertTrue("全拼 14 键", usesSentenceDragEdit(profile(InputScheme.PINYIN, InputLayout.MERGED14)))
        assertTrue("双拼 14 键", usesSentenceDragEdit(profile(InputScheme.DOUBLE_PINYIN, InputLayout.MERGED14)))
        assertFalse("自建合并键布局", usesSentenceDragEdit(profile(InputScheme.PINYIN,
            InputLayout("custom_17", "自建", LayoutKind.MERGED))))
        assertFalse("五笔", usesSentenceDragEdit(profile(InputScheme.WUBI, InputLayout.QWERTY)))
        assertFalse("全拼九键", usesSentenceDragEdit(profile(InputScheme.PINYIN, InputLayout.T9)))
    }

    /** 14 键改局部时按合并键（qw）接的是大写代表字母（Q），同打字时送给引擎的；26 键照旧小写。 */
    @Test fun mergedKeysTypeTheirRepresentativeLetter() {
        assertEquals("Q", sentenceEditKey("qw", merged14 = true))
        assertEquals("B", sentenceEditKey("bn", merged14 = true))
        assertEquals("l", sentenceEditKey("l", merged14 = true))
        assertEquals("a", sentenceEditKey("a", merged14 = true))
        assertEquals("a", sentenceEditKey("A", merged14 = false))
        assertEquals("'", sentenceEditKey("'", merged14 = false))
        assertNull("26 键没有合并键", sentenceEditKey("qw", merged14 = false))
        assertNull(sentenceEditKey("delete", merged14 = true))
        assertNull(sentenceEditKey("1", merged14 = true))
    }

    /** 拖选默认关；开着也只给超过 6 个字的首选，短句长按照旧出调词频的卡片（11-24）。 */
    @Test fun dragEditOnlyWhenOnAndForLongSentences() {
        assertFalse(sentenceDragApplies(null, enabled = true))
        assertFalse(sentenceDragApplies("甲".repeat(2), enabled = true))
        assertFalse(sentenceDragApplies("甲".repeat(6), enabled = true))
        assertTrue(sentenceDragApplies("甲".repeat(7), enabled = true))
        assertFalse(sentenceDragApplies("甲".repeat(12), enabled = false))
        assertEquals(7, SENTENCE_DRAG_MIN_CHARS)
    }
}
