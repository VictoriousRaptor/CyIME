package com.kingzcheung.xime.service
import org.junit.Assert.*
import org.junit.Test
class InputTextPolicyTest {
    @Test fun punctuationAndWhitespaceTerminatePredictions() {
        listOf("你好，", "hello!", "“引号”", "hello ", "", "你好\n", "🙂", "1", "你好2", "2026", "０.０５").forEach { assertFalse(it, canPredictAfter(it)) }
        listOf("你好", "输入法", "word").forEach { assertTrue(it, canPredictAfter(it)) }
    }
    @Test fun aPhraseOfTwoOrMoreHanEndsAWholeWord() {
        listOf("你在吃饭莫", "吃饭", "OK好的", "𠀀你好").forEach { assertTrue(it, endsWithWholeWord(it)) }
        // A single character committed alone may still begin a word (我 → 我们); letters are not Han.
        listOf("莫", "我", "ok了", "word", "", "你好2", "你好，").forEach { assertFalse(it, endsWithWholeWord(it)) }
    }
    @Test fun literalPunctuationNeverMistakesControlKeysOrLetters() {
        listOf(",", "。", "！", "/", "@", "(").forEach { assertTrue(it, isLiteralPunctuation(it)) }
        listOf("delete", "space", "abc", "s", "4", "").forEach { assertFalse(it, isLiteralPunctuation(it)) }
    }
    // 11-22 笔画通配键：* 是标点，以前直接上屏、进不了引擎；笔画方案里它是编码键，别的方案、西文照旧是标点。
    @Test fun strokeWildcardGoesToTheEngineOnlyInStroke() {
        val stroke = com.kingzcheung.xime.settings.InputProfiles.describe("stroke")
        assertTrue(isLiteralPunctuation("*"))
        assertTrue(strokeWildcardIsCode("*", false, stroke))
        assertTrue(strokeWildcardIsCode("＊", false, stroke))
        assertFalse(strokeWildcardIsCode("*", true, stroke))
        assertFalse(strokeWildcardIsCode("，", false, stroke))
        assertFalse(strokeWildcardIsCode("h", false, stroke))
        assertFalse(strokeWildcardIsCode("*", false, com.kingzcheung.xime.settings.InputProfiles.describe("rime_ice")))
    }
    // 2026-11-02「标点上屏首选」：只有断句标点先上屏首选，括号、引号、@ 照旧先上屏字母。
    @Test fun onlySentencePunctuationCommitsTheFirstCandidate() {
        listOf(",", "，", ".", "。", "?", "？", "!", "！", "、", ";", "；", ":", "：", "……").forEach { assertTrue(it, isSentencePunctuation(it)) }
        listOf("@", "#", "（", "(", "“", "《", "'", "-", "/", "1", "a", "space", "").forEach { assertFalse(it, isSentencePunctuation(it)) }
    }

    @org.junit.Test fun widthNormalizesBothAsciiAndPresetChinesePunctuation() {
        assertEquals("、。", punctuationWidth(",.", true, japanese = true))
        org.junit.Assert.assertEquals(",.?!;:()[]+-*/", punctuationWidth("，。？！；：（）［］＋－＊／", false))
        org.junit.Assert.assertEquals("，。？！；：（）［］＋－＊／", punctuationWidth(",.?!;:()[]+-*/", true))
        for (full in listOf(false, true)) for (value in listOf("你好，世界", "1.05", "hi!", "😀", "→", "×")) {
            org.junit.Assert.assertEquals(value, punctuationWidth(value, full))
        }
        org.junit.Assert.assertEquals("\"\"''", punctuationWidth("“”‘’", false))
    }

    // 2026-10-16：中文屏幕键盘的符号都是固定字符，全角／半角下都原样上屏。
    @Test fun chineseKeyboardSymbolsAreFixedCharacters() {
        for (full in listOf(true, false)) for (value in listOf(",", ".", "?", ":", ";", "<", ">", "[", "]", "\"", "'",
            "，", "。", "？", "：", "；", "《", "》", "【", "】", "！", "（", "）", "～", "!", "@", "(", ")", "-", "`",
            "、", "…", "—", "·", "¥")) {
            assertEquals(value, keyboardLiteralWidth(value, full, false, chineseKeyboard = true))
        }
        // 实体键盘和英文仍走完整转换。
        assertEquals("：（）", keyboardLiteralWidth(":()", true, false))
    }

    @Test fun japaneseKeyboardSpacesAndDigitsFollowWidth() {
        assertEquals("　", keyboardLiteralWidth(" ", true, true))
        assertEquals(" ", keyboardLiteralWidth("　", false, true))
        assertEquals("０１２３４５６７８９", keyboardLiteralWidth("0123456789", true, true))
        assertEquals("0123456789", keyboardLiteralWidth("０１２３４５６７８９", false, true))
        assertEquals("１２．５＋３", keyboardLiteralWidth("12.5+3", true, true, numberPanel = true))
        assertEquals("12.5+3", keyboardLiteralWidth("１２．５＋３", false, true, numberPanel = true))
        assertEquals("、。", keyboardLiteralWidth(",.", true, true))
        assertEquals("よy", keyboardLiteralWidth("よy", true, true))
    }

    // Regression (2026-10-11, second report): full-width space/digits/letters must stay typeable in Japanese.
    @Test fun japaneseFullWidthLettersAndTheToggleStayAvailable() {
        assertEquals("ＣｙＩＭＥ　１２", fullWidthLetters(keyboardLiteralWidth("CyIME 12", true, true)))
        assertEquals("CyIME 12", keyboardLiteralWidth("CyIME 12", false, true))
        org.junit.Assert.assertTrue("全角／半角 must be reachable from the default menu",
            com.kingzcheung.xime.keyboard.ToolbarButton.FULL_SHAPE in com.kingzcheung.xime.keyboard.ToolbarButton.DEFAULT_MENU)
    }
    @Test fun otherLanguagesKeepTheirSpaceAndNumericBehavior() {
        assertEquals(" ", keyboardLiteralWidth(" ", true, false))
        assertEquals("123", keyboardLiteralWidth("123", true, false))
        assertEquals("1.5", keyboardLiteralWidth("1.5", true, false, numberPanel = true))
        assertEquals("，", keyboardLiteralWidth(",", true, false))
        assertEquals(",", keyboardLiteralWidth(",", false, false))
    }
    @Test fun explicitlyChosenSymbolsKeepTheirExactCodepointsAcrossWidthAndLanguage() {
        val literals = listOf("-", "－", ",", "，", ".", "。", "123", "１２３", " ", "　", "a-b",
            "(*´▽｀*)", "(＾▽＾)", "¯\\_(ツ)_/¯", "(╯°□°）╯︵ ┻━┻")
        for (full in listOf(false, true)) for (japanese in listOf(false, true))
            for (numberPanel in listOf(false, true)) for (literal in literals) {
                assertEquals(literal, keyboardLiteralWidth(literal, full, japanese, numberPanel, preserveWidth = true))
            }
        assertEquals("－", keyboardLiteralWidth("-", true, false))
        assertEquals("-", keyboardLiteralWidth("－", false, false))
    }

    @Test fun preeditCaretMarksThePinyinPositionOnlyWhenItMapsToTheInput() {
        assertEquals("ni| hao", markPreeditCaret("ni hao", "nihao", 2, 0))
        assertEquals("nih|ao", markPreeditCaret("nihao", "nihao", 3, 0))
        assertEquals("|nihao", markPreeditCaret("nihao", "nihao", 0, 0))
        assertEquals("你h|ao", markPreeditCaret("你hao", "nihao", 3, 2))
        assertNull("caret at the end shows nothing", markPreeditCaret("ni hao", "nihao", 5, 0))
        assertNull("double pinyin display does not map to the code", markPreeditCaret("ni hao", "nihk", 2, 0))
        // 双拼：Rime 只转换光标前的编码，光标后原样显示，仍能标出位置。
        assertEquals("ni|hk", markPreeditCaret("ni" + "hk", "nihk", 2, 0))
        assertEquals("ni hao|ma", markPreeditCaret("ni hao" + "ma", "nihaoma", 5, 0))
    }

    @Test fun physicalArrowsStepOneLetterAndStopAtBothEnds() {
        assertEquals(4, com.kingzcheung.xime.rime.compositionCaretStep("nihao", 5, 0, -1))
        assertEquals(1, com.kingzcheung.xime.rime.compositionCaretStep("nihao", 0, 0, 1))
        assertNull("no wrap at the start", com.kingzcheung.xime.rime.compositionCaretStep("nihao", 0, 0, -1))
        assertNull("no wrap at the end", com.kingzcheung.xime.rime.compositionCaretStep("nihao", 5, 0, 1))
        assertNull("confirmed text is not editable", com.kingzcheung.xime.rime.compositionCaretStep("nihao", 2, 2, -1))
        // 手打分隔符两侧显示同一个光标位置，一步跨过。
        assertEquals(1, com.kingzcheung.xime.rime.compositionCaretStep("ni'hao", 3, 0, -1))
        assertEquals(4, com.kingzcheung.xime.rime.compositionCaretStep("ni'hao", 2, 0, 1))
    }

    @Test fun aSeparatorAfterADigitStaysHalfWidth() {
        // #527：实体键盘中文模式，3 后打 . 要 3.，不是 3。
        assertTrue(digitSeparatorStaysHalfWidth(".", "3"))
        assertTrue(digitSeparatorStaysHalfWidth(",", "价格1"))
        assertTrue(digitSeparatorStaysHalfWidth(":", "12"))
        assertFalse("不在数字后照常", digitSeparatorStaysHalfWidth(".", "你好"))
        assertFalse(digitSeparatorStaysHalfWidth(".", ""))
        assertFalse(digitSeparatorStaysHalfWidth(".", null))
        assertFalse("全角数字不算", digitSeparatorStaysHalfWidth(".", "３"))
        assertFalse("问号不是数字分隔", digitSeparatorStaysHalfWidth("?", "3"))
        assertEquals(".", keyboardLiteralWidth(".", full = true, japanese = false, before = "3"))
        assertEquals("。", keyboardLiteralWidth(".", full = true, japanese = false, before = "好"))
    }
}
