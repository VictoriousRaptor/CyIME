package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.settings.InputProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QwertyRowOptionsTest {
    @Test fun staggerPresetsMatchPhysicalAndPhoneKeyboards() {
        val frame = LetterKeyboardFrame(listOf(10, 9, 7))
        // 实体键盘（ANSI）：A 行右移 1/4 键，Z 行右移 3/4 键。
        val physical = frame.geometry(1f, resolvedQwertyStagger(QwertyStaggerMode.PHYSICAL, 0f, 40f, null))
        assertEquals(0.25f, physical.middleRowInsetDp, 0.0001f)
        assertEquals(0.75f, physical.outerKeyWeight, 0.0001f)
        // 手机常见：S 占 [1.5, 2.5]，Z 也从 1.5 开始，上下对齐。
        val phone = frame.geometry(1f, resolvedQwertyStagger(QwertyStaggerMode.PHONE, 1f, 40f, null))
        assertEquals(phone.middleRowInsetDp + 1f, phone.outerKeyWeight, 0.0001f)
        // 自定义可继续向右，保持字母宽度，左右功能键仍有空间。
        val extra = frame.geometry(1f, resolvedQwertyStagger(QwertyStaggerMode.CUSTOM, -1f, 40f, null))
        assertEquals(0.75f, extra.middleRowInsetDp, 0.0001f)
        assertEquals(2.25f, extra.outerKeyWeight, 0.0001f)
        assertEquals(0.75f, extra.deleteKeyWeight, 0.0001f)
        assertEquals(-1f, resolvedQwertyStagger(QwertyStaggerMode.CUSTOM, -9f, 40f, null), 0.0001f)
        // 原有设置和自动按尺寸的曲线不变。
        assertEquals(0.3f, resolvedQwertyStagger(QwertyStaggerMode.CUSTOM, 0.3f, 40f, null), 0.0001f)
        assertEquals(1f, resolvedQwertyStagger(QwertyStaggerMode.CUSTOM, 7f, 40f, null), 0.0001f)
        assertEquals(wideQwertyStagger(90f, 98f), resolvedQwertyStagger(QwertyStaggerMode.AUTO, 0f, 90f, 98f), 0.0001f)
    }

    @Test fun customSliderMovesRowsInItsDirectionAndSnapsToPhoneLayout() {
        val frame = LetterKeyboardFrame(listOf(10, 9, 7))
        val left = frame.geometry(1f, customStaggerFromSlider(-1f))
        val right = frame.geometry(1f, customStaggerFromSlider(1f))
        assertTrue(left.outerKeyWeight < right.outerKeyWeight)
        assertEquals(0f, customStaggerFromSlider(0.04f), 0f)
        assertEquals(0f, customStaggerFromSlider(-0.04f), 0f)
        assertEquals(-0.1f, customStaggerFromSlider(0.1f), 0f)
    }

    @Test fun spacePunctuationFollowsTheChosenKeysAndWidth() {
        // 只开一个键时角标换成缺的那个符号，同宽度：默认只开逗号也能打出句号（用户 10-19 报的回归）。
        assertEquals(Pair(SpacePunctuationKey("，", "。"), null), spacePunctuationKeys(comma = true, period = false, fullWidth = true))
        assertEquals(Pair(SpacePunctuationKey(",", "."), null), spacePunctuationKeys(comma = true, period = false, fullWidth = false))
        assertEquals(Pair(SpacePunctuationKey(",", "."), null),
            spacePunctuationKeys(comma = true, period = false, fullWidth = true, english = true))
        val periodOnly = spacePunctuationKeys(comma = false, period = true, fullWidth = true)
        assertNull(periodOnly.first)
        assertEquals(SpacePunctuationKey("。", "，"), periodOnly.second)
        assertEquals(Pair(null, null), spacePunctuationKeys(comma = false, period = false, fullWidth = true))
        // 日文的全角逗号是 、。
        assertEquals(Pair(SpacePunctuationKey("、", ","), SpacePunctuationKey("。", ".")),
            spacePunctuationKeys(comma = true, period = true, fullWidth = true, japanese = true))
        assertEquals(SpacePunctuationKey("、", "。"),
            spacePunctuationKeys(comma = true, period = false, fullWidth = true, japanese = true).first)
        // 两个都开：副字符是另一种宽度，主字符跟随全角／半角状态。
        assertEquals(Pair(SpacePunctuationKey("，", ","), SpacePunctuationKey("。", ".")),
            spacePunctuationKeys(comma = true, period = true, fullWidth = true))
        assertEquals(Pair(SpacePunctuationKey(",", "，"), SpacePunctuationKey(".", "。")),
            spacePunctuationKeys(comma = true, period = true, fullWidth = false))
        // 英文不分全角半角：两个都开时只有 , 和 .，没有副字符。
        assertEquals(Pair(SpacePunctuationKey(",", ""), SpacePunctuationKey(".", "")),
            spacePunctuationKeys(comma = true, period = true, fullWidth = false, english = true))
    }

    @Test fun splitSpaceFollowsTheKeysLeftOfIt() {
        val b = QwertyBottomRowWeights.Standard
        // 没有左侧逗号时空格多一个键宽，中线仍在 5。
        val (left, right) = SplitKeyboardGeometry.spaceSplit(b, b.space + b.punctuation, b.mode * 2f)
        assertEquals(5f, b.mode * 2f + left, 0.0001f)
        assertEquals(b.space + b.punctuation, left + right, 0.0001f)
    }

    @Test fun syllableSplitOnlyForChinesePinyinWhenSwitchedOn() {
        val pinyin = InputProfiles.describe("rime_ice")
        // 默认关：任何布局都和以前一样。
        assertFalse(syllableSplitActive(false, ascii = false, profile = pinyin))
        assertTrue(syllableSplitActive(true, ascii = false, profile = pinyin))
        // 14 键全拼、双拼 26/14 键同样有拼音音节。
        for (id in listOf("pinyin_14jian", "double_pinyin_flypy", "double_pinyin_flypy_14jian"))
            assertTrue(id, syllableSplitActive(true, ascii = false, profile = InputProfiles.describe(id)))
        // 英文状态、注音、仓颉、日文没有拼音音节：反例，开关开着也不加。
        assertFalse(syllableSplitActive(true, ascii = true, profile = pinyin))
        for (id in listOf("cyime_zhuyin", "cyime_cangjie", "japanese"))
            assertFalse(id, syllableSplitActive(true, ascii = false, profile = InputProfiles.describe(id)))
    }

    @Test fun chineseUppercaseLabelsShowTheLanguageOnTheKeys() {
        val pinyin = InputProfiles.describe("rime_ice")
        val off = KeyboardInputPreferences()
        val chinese = off.copy(chineseUppercaseKeyLabels = true)
        // 默认关：中英文都和以前一样小写（大写只看 Shift）。
        assertFalse(letterLabelsUppercase(off, ascii = false, profile = pinyin))
        // 开了：中文大写，切到英文回到小写；双拼、14 键也是中文。
        assertTrue(letterLabelsUppercase(chinese, ascii = false, profile = pinyin))
        assertFalse(letterLabelsUppercase(chinese, ascii = true, profile = pinyin))
        for (id in listOf("double_pinyin_flypy", "pinyin_14jian"))
            assertTrue(id, letterLabelsUppercase(chinese, ascii = false, profile = InputProfiles.describe(id)))
        // 日文罗马字不是中文：照旧小写。
        assertFalse(letterLabelsUppercase(chinese, ascii = false, profile = InputProfiles.describe("japanese")))
        // 「字母键显示大写」一律大写，英文也大写（与以前相同）。
        assertTrue(letterLabelsUppercase(off.copy(uppercaseKeyLabels = true), ascii = true, profile = pinyin))
    }

    @Test fun syllableSplitSitsOnTheCommaKeyOrTheOnlyPunctuationKey() {
        val (comma, period) = spacePunctuationKeys(comma = true, period = true, fullWidth = true)
        assertEquals(true to false, syllableSplitOnPunctuation(true, comma, period))
        // 只开逗号键：仍在逗号键上；只开句号键：挪到句号键；两个都关：没有。
        val commaOnly = spacePunctuationKeys(comma = true, period = false, fullWidth = true)
        assertEquals(true to false, syllableSplitOnPunctuation(true, commaOnly.first, commaOnly.second))
        val periodOnly = spacePunctuationKeys(comma = false, period = true, fullWidth = true)
        assertEquals(false to true, syllableSplitOnPunctuation(true, periodOnly.first, periodOnly.second))
        assertEquals(false to false, syllableSplitOnPunctuation(true, null, null))
        assertEquals(false to false, syllableSplitOnPunctuation(false, comma, period))
    }

    @Test fun onlyTheApostropheCornerBecomesTheSeparator() {
        assertTrue(cornerTypesSeparator(true, "'"))
        // 其他角标照旧当符号上屏；开关关着时 ' 也照旧。
        for (symbol in listOf("’", "\"", ",", "-", "=")) assertFalse(symbol, cornerTypesSeparator(true, symbol))
        assertFalse(cornerTypesSeparator(false, "'"))
    }

    @Test fun shiftTypesTheSeparatorOnlyWhileComposing() {
        assertTrue(shiftTapTypesSeparator(active = true, composing = true))
        // 没在打拼音、或开关关着（含英文、注音等不生效的方案）：Shift 照旧切换大小写。
        assertFalse(shiftTapTypesSeparator(active = true, composing = false))
        assertFalse(shiftTapTypesSeparator(active = false, composing = true))
        assertFalse(syllableSplitActive(true, ascii = true, profile = InputProfiles.describe("rime_ice")))
    }

    /** 用户 11-16：英文和西、德、法、意语用同两个开关打撇号（what's）；中文的分词不受影响。 */
    @Test fun apostropheKeysForWordsWhenSwitchedOn() {
        val pinyin = InputProfiles.describe("rime_ice")
        // 默认关：和以前一样。
        assertFalse(apostropheSplitActive(false, ascii = true, profile = pinyin))
        // 中文键盘的英文状态、英文、字母语言。
        assertTrue(apostropheSplitActive(true, ascii = true, profile = pinyin))
        for (id in listOf(com.kingzcheung.xime.settings.InputModes.ENGLISH, "french", "italian", "german", "spanish", "malay"))
            assertTrue(id, apostropheSplitActive(true, ascii = false, profile = InputProfiles.describe(id)))
        // 反例：中文打拼音（那时是分词）、俄语韩语、日语罗马字、英文单词方案（字母先进组字区）。
        // 越南语打词时字母会换成带声调的，Shift 也不当撇号。
        for (id in listOf("rime_ice", "double_pinyin_flypy", "russian", "korean", "vietnamese", "japanese",
                com.kingzcheung.xime.settings.InputModes.ENGLISH_WORDS))
            assertFalse(id, apostropheSplitActive(true, ascii = false, profile = InputProfiles.describe(id)))
        // Shift 只在打词时写 '：光标前是字母才算，刚打过 ' 或没在打词时照旧是 Shift。
        assertTrue(typingWord("what"))
        assertFalse(typingWord(""))
        assertFalse(typingWord("what'"))
        assertFalse(typingWord("2"))
    }
}
