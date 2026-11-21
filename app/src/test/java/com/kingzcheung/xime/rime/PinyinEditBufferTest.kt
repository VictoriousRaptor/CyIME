package com.kingzcheung.xime.rime

import org.junit.Assert.*
import org.junit.Test

class PinyinEditBufferTest {
    @Test fun limitsEditingToPhoneticChineseModes() {
        listOf("rime_ice", "t9_pinyin", "pinyin_14jian", "double_pinyin_flypy").forEach { assertTrue(PinyinEditBuffer.supports(it)) }
        listOf("japanese", "japanese_kana", "wubi86", "english").forEach { assertFalse(PinyinEditBuffer.supports(it)) }
    }
    @Test fun keepsRealBoundariesAndUmlautAsV() {
        assertEquals("nv'er", PinyinEditBuffer.normalized("Nü er"))
        assertEquals("ni'hao", PinyinEditBuffer.normalized("ni hao"))
    }
    @Test fun mergingAdjacentSeparatorsPreservesTheCaret() {
        assertEquals("a'b" to 2, PinyinEditBuffer.mergeSeparators("a''b", 2))
        assertEquals("a'b" to 2, PinyinEditBuffer.mergeSeparators("a'''b", 4))
        assertEquals("a'b" to 3, PinyinEditBuffer.mergeSeparators("a'''b", 5))
        assertEquals("a'b" to 0, PinyinEditBuffer.mergeSeparators("a'b", 0))
    }
    /** 11-09 平板：ni'hao 在 ni 后面连删两下，剩下的开头分隔符显示成「‘hao」，光标在它后面也删不掉。 */
    @Test fun leadingSeparatorsLeftByDeletingAreDropped() {
        assertEquals("hao" to 0, PinyinEditBuffer.dropLeadingSeparators("'hao", 0))
        assertEquals("hao" to 0, PinyinEditBuffer.dropLeadingSeparators("''hao", 1))
        assertEquals("hao" to 2, PinyinEditBuffer.dropLeadingSeparators("'hao", 3))
        assertEquals("" to 0, PinyinEditBuffer.dropLeadingSeparators("'", 1))
        // 中间和末尾的分隔符不动。
        assertEquals("n'hao" to 1, PinyinEditBuffer.dropLeadingSeparators("n'hao", 1))
        assertEquals("ni'" to 3, PinyinEditBuffer.dropLeadingSeparators("ni'", 3))
    }
    @Test fun mapsCaretOnBothSidesOfASeparator() {
        assertEquals(2, mapCodeOffset("ni'hao", "ni'hao", 2))
        assertEquals(3, mapCodeOffset("ni'hao", "ni'hao", 3))
        assertEquals(2, mapCodeOffset("ni'hao", "nihao", 3))
        assertEquals(3, mapCodeOffset("ni''hao", "ni'hao", 4))
    }
    @Test fun engineBoundariesAreVisibleButDoNotChangeTheEditableCode() {
        val display = PinyinEditDisplay("xiugaishuru", "xiu'gai'shu'ru")
        assertEquals("xiu'gai'shu'ru", display.text)
        assertEquals(3, display.rawOffset(3))
        assertEquals(3, display.rawOffset(4))
        assertEquals(4, display.displayOffset(3))
        assertEquals(11, display.rawOffset(display.text.length))
        val explicit = PinyinEditDisplay("ni''hao", "ni'hao")
        assertEquals("ni''hao", explicit.text)
        assertEquals(4, explicit.rawOffset(4))
    }
    @Test fun incompatibleOrOldPreeditNeverRewritesRawKeys() {
        assertEquals("nih", PinyinEditDisplay("nih", "ni'hao").text)
        assertEquals("nihk", PinyinEditDisplay("nihk", "ni'hao").text)
        assertEquals("ni'426", PinyinEditBuffer.normalizedT9("ni 426"))
    }
    @Test fun nineKeyDigitsShowEngineReadingWithReversibleCaretOffsets() {
        val display = PinyinEditDisplay("ni'426", "ni'hao", true)
        assertEquals("ni'hao", display.text)
        for (i in 0..6) assertEquals(i, display.rawOffset(display.displayOffset(i)))
        assertEquals("ni'hen", PinyinEditDisplay("ni436", "ni'hen", true).text)
        // A stale reading cannot replace literal letters the user explicitly entered.
        assertEquals("ni'426", PinyinEditDisplay("ni'426", "mi'hao", true).text)
    }

    @Test fun editorKeepsItsEditableSeparatorsWhenEngineCaretChangesSegmentation() {
        assertEquals("ni'hao", PinyinEditDisplay("ni'hao", "ni h ao", includeAutomaticBoundaries = false).text)
        assertEquals("nihao", PinyinEditDisplay("nihao", "ni hao", includeAutomaticBoundaries = false).text)
        assertEquals("你ni'hao", pinyinPreviewText("你ni hao"))
        assertEquals("xiu'gai'shu'ru", pinyinPreviewText("xiu gai shu ru"))
        assertEquals("ni'hao", editablePreeditText("ni hao", "double_pinyin_flypy", false))
        assertEquals("ni hao", editablePreeditText("ni hao", "double_pinyin_flypy", true))
        assertEquals("ni hao", editablePreeditText("ni hao", "english", false))
    }

    @Test fun nineKeyCaretInsideTheReadingMapsToTheKeysBeforeIt() {
        // xu'yao|jia'ge: candidates must cover the five keys of xu yao, not the whole code.
        val text = "xu'yao'jia'ge"
        assertEquals(5, codeKeysBefore(text, 6))
        assertEquals(5, codeKeysBefore(text, 7))
        assertEquals(5, offsetAfterCodeKeys("9892654243", 5))
        // Manual delimiters in the code are skipped both ways; the caret stays before them.
        assertEquals(5, offsetAfterCodeKeys("98926'54243", 5))
        assertEquals(6, offsetAfterCodeKeys(text, codeKeysBefore("98926'54243", 5)))
        assertEquals(0, offsetAfterCodeKeys("9892654243", codeKeysBefore(text, 0)))
        assertEquals(10, offsetAfterCodeKeys("9892654243", codeKeysBefore(text, text.length)))
        assertEquals(text.length, offsetAfterCodeKeys(text, 99))
    }

    @Test fun keysLeftAfterAPartialChoiceKeepTheReadingTheUserSaw() {
        // xu'ya|o'jia'ge: 需 takes xu, the rest still reads yao jia ge; 呀 then leaves o jia ge.
        assertEquals("yao jia ge", dropReadingKeys("xu yao jia ge", 2))
        assertEquals("ao jia ge", dropReadingKeys("xu yao jia ge", 3))
        assertEquals("o jia ge", dropReadingKeys("yao jia ge", 2))
        assertEquals("", dropReadingKeys("xu yao", 5))
    }

    @Test fun doublePinyinTapInTheInputBoxMovesTheEditCaretToThoseKeys() {
        // 11-08: 小鹤双拼, tapping after shuang and deleting removed the last key instead.
        val session = PinyinEditSession(1, "double_pinyin_flypy", "ulpb", "", "", "ulpb", 4, false)
        assertEquals(2, session.editOffset("shuang pin", 6))
        assertEquals(2, session.editOffset("shuang'pin", 7))
        assertEquals(4, session.editOffset("shuang'pin", 10))
        assertEquals(6, session.copy(caret = 2).inlineOffset("shuang'pin"))
        val confirmed = PinyinEditSession(1, "double_pinyin_flypy", "nihcul", "nihc", "你好", "ul", 2, false)
        assertEquals(0, confirmed.editOffset("你好shuang", 2))
        assertEquals(2, confirmed.editOffset("你好shuang", 8))
        // Full pinyin keeps its own one-to-one path; unrelated text is still rejected.
        assertNull(PinyinEditSession(1, "rime_ice", "ulpb", "", "", "ulpb", 4, false).editOffset("shuang'pin", 6))
        assertNull(session.editOffset("ni'hao", 2))
    }

    @Test fun microsoftDoublePinyinSemicolonStaysInTheEditableCode() {
        // #365: 微软双拼 p;vg（凭证）里的分号被当成不认识的字符，前面的 p 改不了。
        assertEquals("p;vg", PinyinEditBuffer.normalized("p;vg", false, true))
        assertEquals("p;'vg", PinyinEditBuffer.normalized("p; vg", false, true))
        assertEquals("pvg", PinyinEditBuffer.normalized("p;vg", false, false))
        assertTrue(PinyinEditBuffer.keepsSemicolon("double_pinyin_mspy"))
        assertFalse(PinyinEditBuffer.keepsSemicolon("rime_ice"))
        // 编码里的分号也能对上输入框里显示的全拼，点 ping 后面落在 p; 后面。
        val session = PinyinEditSession(1, "double_pinyin_mspy", "p;vg", "", "", "p;vg", 4, false)
        assertEquals(2, session.editOffset("ping'zheng", 4))
        assertEquals(0, session.editOffset("ping'zheng", 0))
    }
}
