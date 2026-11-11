package com.kingzcheung.xime.service

import org.junit.Assert.*
import org.junit.Test

class EnglishAutoSpaceTest {
    @Test fun completionGetsTheSpaceInTheSameReplacement() {
        val state = CandidateState(pendingEnglishText = "hel", associationCandidates = listOf("hello", "help"))
        var replaced: Pair<String, String>? = null
        assertTrue(commitAssociationSelection(state, 1, { error("must replace") }, { old, new -> replaced = old to new; true }, suffix = " "))
        assertEquals("hel" to "hello ", replaced)
        assertEquals("hello", associationWordAt(state, 1))
    }

    @Test fun originalWordOnlyAddsTheSpace() {
        val state = CandidateState(pendingEnglishText = "hel", associationCandidates = listOf("hello"))
        // 11-09 起原词在输入框里时只把光标处的原词换成带空格的（不再另外上屏一个空格）。
        var replaced: Pair<String, String>? = null
        assertTrue(commitAssociationSelection(state, 0, { error("only the suffix is new") }, { old, new -> replaced = old to new; true }, suffix = " "))
        assertEquals("hel" to "hel ", replaced)
        assertEquals("hel", associationWordAt(state, 0))
    }

    @Test fun offByDefaultLeavesSelectionUnchanged() {
        val state = CandidateState(pendingEnglishText = "hel", associationCandidates = listOf("hello"))
        assertTrue(commitAssociationSelection(state, 0, { error("nothing to add") }, { _, _ -> error("already typed") }))
        var replaced = ""
        assertTrue(commitAssociationSelection(state, 1, { error("must replace") }, { _, new -> replaced = new; true }))
        assertEquals("hello", replaced)
    }

    @Test fun nextKeyRules() {
        // 紧接着的空格不重复；句末标点挪到空格前面；右括号去掉空格；其他键照常。
        assertEquals("", EnglishAutoSpace.replacementFor("space"))
        for (p in listOf(".", ",", "?", "!", ":", ";")) assertEquals("$p ", EnglishAutoSpace.replacementFor(p))
        for (p in listOf(")", "]", "}")) assertEquals(p, EnglishAutoSpace.replacementFor(p))
        for (k in listOf("a", "A", "1", "(", "'", "\"", "-", "@", "，", "。", "enter", "delete", "ime_switch"))
            assertNull(k, EnglishAutoSpace.replacementFor(k))
    }

    @Test fun pageSwitchKeysKeepTheAutoSpaceForTheSymbolTypedNext() {
        for (k in listOf("shift", "number", "common_symbol", "abc", "mode_change")) assertTrue(k, EnglishAutoSpace.isNeutral(k))
        for (k in listOf("space", ",", "a", "ime_switch", "enter", "emoji")) assertFalse(k, EnglishAutoSpace.isNeutral(k))
    }

    @Test fun onlyTheNextKeyIsAffected() {
        val autoSpace = EnglishAutoSpace()
        assertFalse(autoSpace.isArmed)
        autoSpace.arm("hello")
        assertTrue(autoSpace.isArmed)
        assertEquals("hello ", autoSpace.take())
        assertNull(autoSpace.take())
        autoSpace.arm("hello")
        autoSpace.disarm()
        assertNull(autoSpace.take())
    }
    @Test fun punctuationSpaceFollowsWordsNotNumbers() {
        // 字母后面的英文标点补空格（Hello, world）；西班牙语重音字母也是字母。
        for (p in listOf(",", ".", "?", "!", ":", ";")) assertTrue(p, EnglishAutoSpace.spacesAfterPunctuation(p, "o", ""))
        assertTrue(EnglishAutoSpace.spacesAfterPunctuation(",", "ñ", null))
        assertTrue(EnglishAutoSpace.spacesAfterPunctuation(".", ")", ""))
        assertTrue(EnglishAutoSpace.spacesAfterPunctuation(",", "\"", ""))
        // 3.14、1,000、10:30 不拆开；问号、感叹号、分号跟在数字后面照样补。
        for (p in listOf(",", ".", ":")) assertFalse(p, EnglishAutoSpace.spacesAfterPunctuation(p, "3", ""))
        for (p in listOf("?", "!", ";")) assertTrue(p, EnglishAutoSpace.spacesAfterPunctuation(p, "5", ""))
    }

    @Test fun punctuationSpaceSkipsOtherContexts() {
        // 输入框开头、空白、标点、汉字后面不补；光标后已是空白不补；其他符号不补。
        assertFalse(EnglishAutoSpace.spacesAfterPunctuation(",", "", ""))
        assertFalse(EnglishAutoSpace.spacesAfterPunctuation(",", null, null))
        assertFalse(EnglishAutoSpace.spacesAfterPunctuation(",", " ", ""))
        assertFalse(EnglishAutoSpace.spacesAfterPunctuation(".", ".", ""))
        assertFalse(EnglishAutoSpace.spacesAfterPunctuation(",", "好", ""))
        assertFalse(EnglishAutoSpace.spacesAfterPunctuation(",", "o", " "))
        assertFalse(EnglishAutoSpace.spacesAfterPunctuation(",", "o", "\n"))
        assertTrue(EnglishAutoSpace.spacesAfterPunctuation(",", "o", "w"))
        for (k in listOf("-", "'", "@", "/", "(", ")", "，", "。", "a", "space", ""))
            assertFalse(k, EnglishAutoSpace.spacesAfterPunctuation(k, "o", ""))
    }

    @Test fun punctuationSpaceArmsWithThePunctuation() {
        // 补的空格交给同一套“下一个键”规则：光标前应是“标点+空格”。
        val autoSpace = EnglishAutoSpace()
        autoSpace.arm(",")
        assertEquals(", ", autoSpace.take())
        assertEquals(". ", EnglishAutoSpace.replacementFor("."))
    }
}
