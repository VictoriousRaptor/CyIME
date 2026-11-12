package com.kingzcheung.xime.ui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户 11-01「脱裤子放屁的设置项」：互锁的几对开关在设置页合成一个选择，存的值不变。
 * 这里检查旧值组合换算成的选项和键盘实际效果一致，选了以后写回的值也和以前一样。
 */
class InputGesturePresetsTest {
    private val base = KeyboardInputPreferences(cursorGesture = CursorGestureMode.SPACE)

    @Test fun `corner input choice reads old values the way the keyboard behaves`() {
        assertEquals(CornerInputChoice.SWIPE, CornerInputChoice.of(base))
        assertEquals(CornerInputChoice.SWIPE_BUBBLE, CornerInputChoice.of(base.copy(letterLongPressMenu = true)))
        assertEquals(CornerInputChoice.LONG_PRESS, CornerInputChoice.of(base.copy(symbolInputMode = SymbolInputMode.LONG_PRESS)))
        // 加锁以前两个都开着：长按总是出角标、气泡出不来，按实际效果算「长按输入」。
        assertEquals(CornerInputChoice.LONG_PRESS, CornerInputChoice.of(
            base.copy(symbolInputMode = SymbolInputMode.LONG_PRESS, letterLongPressMenu = true)))
    }

    @Test fun `every corner input choice is recognised after applying it and keeps unrelated settings`() {
        for (start in listOf(base, base.copy(reverseSymbolSwipe = true, keyTextScale = 1.3f, letterLongPressMenu = true))) {
            for (choice in CornerInputChoice.entries) {
                val applied = choice.applyTo(start)
                assertEquals(choice, CornerInputChoice.of(applied))
                assertEquals(start.reverseSymbolSwipe, applied.reverseSymbolSwipe)
                assertEquals(start.keyTextScale, applied.keyTextScale, 0f)
            }
        }
        val swipe = CornerInputChoice.SWIPE.applyTo(base)
        assertEquals(SymbolInputMode.SWIPE_UP, swipe.symbolInputMode)
        assertFalse(swipe.letterLongPressMenu)
        val bubble = CornerInputChoice.SWIPE_BUBBLE.applyTo(base)
        assertEquals(SymbolInputMode.SWIPE_UP, bubble.symbolInputMode)
        assertTrue(bubble.letterLongPressMenu)
        // 选长按：照旧顺带打开九键拂动，字母气泡关掉（长按被角标占了）。
        val longPress = CornerInputChoice.LONG_PRESS.applyTo(base.copy(letterLongPressMenu = true))
        assertEquals(SymbolInputMode.LONG_PRESS, longPress.symbolInputMode)
        assertTrue(longPress.t9FlickLetters)
        assertFalse(longPress.letterLongPressMenu)
    }

    @Test fun `key label case choice maps the two old switches`() {
        assertEquals(KeyLabelCaseChoice.LOWER, KeyLabelCaseChoice.of(base))
        assertEquals(KeyLabelCaseChoice.UPPER, KeyLabelCaseChoice.of(base.copy(uppercaseKeyLabels = true)))
        assertEquals(KeyLabelCaseChoice.CHINESE_UPPER, KeyLabelCaseChoice.of(base.copy(chineseUppercaseKeyLabels = true)))
        // 加锁以前两个都开着：键盘按「一律大写」画（QwertyRowOptions）。
        assertEquals(KeyLabelCaseChoice.UPPER, KeyLabelCaseChoice.of(
            base.copy(uppercaseKeyLabels = true, chineseUppercaseKeyLabels = true)))
        for (choice in KeyLabelCaseChoice.entries) {
            val applied = choice.applyTo(base.copy(uppercaseKeyLabels = true, chineseUppercaseKeyLabels = true))
            assertEquals(choice, KeyLabelCaseChoice.of(applied))
            assertFalse("$choice writes at most one", applied.uppercaseKeyLabels && applied.chineseUppercaseKeyLabels)
        }
    }

    @Test fun `space hold and keyboard swipe split the old cursor gesture without changing stored values`() {
        // 原「滑动移动光标：长按空格」= 长按空格三选一的「移动光标」。
        assertEquals(SpaceHoldAction.CURSOR, CursorChoices.spaceHold(base))
        assertFalse(CursorChoices.keyboardSwipe(base))
        val keyboard = base.copy(cursorGesture = CursorGestureMode.KEYBOARD, spaceHold = SpaceHoldAction.VOICE_TOGGLE)
        assertTrue(CursorChoices.keyboardSwipe(keyboard))
        assertEquals(SpaceHoldAction.VOICE_TOGGLE, CursorChoices.spaceHold(keyboard))
        // 选「移动光标」写回 cursorGesture=SPACE；键盘面板滑动随之关掉。
        assertEquals(CursorGestureMode.SPACE, CursorChoices.withSpaceHold(keyboard, SpaceHoldAction.CURSOR).cursorGesture)
        // 从「移动光标」换成连续空格：和以前一样，移动光标改到键盘面板滑动（没被滑行锁住时）。
        val repeat = CursorChoices.withSpaceHold(base, SpaceHoldAction.REPEAT)
        assertEquals(SpaceHoldAction.REPEAT, CursorChoices.spaceHold(repeat))
        assertEquals(CursorGestureMode.KEYBOARD, repeat.cursorGesture)
        // 开关：开=KEYBOARD，关=NONE；长按空格的选择不动。
        assertEquals(CursorGestureMode.NONE, CursorChoices.withKeyboardSwipe(keyboard, false).cursorGesture)
        assertEquals(SpaceHoldAction.VOICE_TOGGLE, CursorChoices.spaceHold(CursorChoices.withKeyboardSwipe(keyboard, false)))
        assertEquals(CursorGestureMode.KEYBOARD, CursorChoices.withKeyboardSwipe(repeat.copy(cursorGesture = CursorGestureMode.NONE), true).cursorGesture)
    }

    @Test fun `keyboard swipe switch says why it cannot be turned on`() {
        assertNotNull(CursorChoices.keyboardSwipeLock(base))
        assertEquals("已被「英文滑行」锁定", CursorChoices.keyboardSwipeLock(
            base.copy(cursorGesture = CursorGestureMode.NONE, glideTyping = true)))
        assertNull(CursorChoices.keyboardSwipeLock(base.copy(cursorGesture = CursorGestureMode.NONE)))
        // 已经开着时不锁，关掉总行。
        assertNull(CursorChoices.keyboardSwipeLock(base.copy(cursorGesture = CursorGestureMode.KEYBOARD, glideTyping = true)))
    }

    /** 合成一个选择的几对不再产生锁；剩下的锁照旧。 */
    @Test fun `merged pairs no longer lock each other, the remaining locks still do`() {
        val everything = base.copy(symbolInputMode = SymbolInputMode.LONG_PRESS, letterLongPressMenu = true,
            doublePinyinKeyHints = true, uppercaseKeyLabels = true, chineseUppercaseKeyLabels = true)
        val merged = setOf(InputGestureLock.Option.SYMBOL_INPUT_MODE, InputGestureLock.Option.LETTER_LONG_PRESS_MENU,
            InputGestureLock.Option.DOUBLE_PINYIN_KEY_HINTS, InputGestureLock.Option.CORNER_HINTS,
            InputGestureLock.Option.UPPERCASE_KEY_LABELS, InputGestureLock.Option.CHINESE_UPPERCASE_KEY_LABELS)
        for (settings in listOf(base, everything)) {
            assertTrue(InputGestureLocks.of(settings).none { it.option in merged })
        }
        assertNotNull(InputGestureLocks.lockFor(base.copy(glideTyping = true),
            InputGestureLock.Option.CURSOR_GESTURE, CursorGestureMode.KEYBOARD))
        assertEquals("已被「键盘面板滑动光标」锁定", InputGestureLocks.lockFor(base.copy(cursorGesture = CursorGestureMode.KEYBOARD),
            InputGestureLock.Option.GLIDE_TYPING, true)?.message)
        assertEquals("已被「按键角标的符号和数字：长按输入」锁定", InputGestureLocks.lockFor(everything,
            InputGestureLock.Option.T9_FLICK_LETTERS, false)?.message)
        // Shift：先开的锁住另一个。
        assertEquals("已被「ing 放在 Shift 键上」锁定", InputGestureLocks.lockFor(base.copy(doublePinyinSemicolonOnShift = true),
            InputGestureLock.Option.SHIFT_SYLLABLE_SPLIT, true)?.message)
        assertEquals("已被「Shift 键分词」锁定", InputGestureLocks.lockFor(base.copy(shiftSyllableSplit = true),
            InputGestureLock.Option.SEMICOLON_ON_SHIFT, true)?.message)
        assertNull(InputGestureLocks.lockFor(base, InputGestureLock.Option.SHIFT_SYLLABLE_SPLIT, true))
        assertNull(InputGestureLocks.lockFor(base, InputGestureLock.Option.SEMICOLON_ON_SHIFT, true))
    }

    @Test fun `twenty six key glide locks corner symbols to long press and says which glide`() {
        val glide = base.copy(glideTyping = true)
        assertEquals(CornerInputChoice.LONG_PRESS, CornerInputChoice.of(glide))
        assertEquals("已被「英文滑行」锁定", InputGestureLocks.glideCornerLock(glide)?.message)
        assertEquals("已被「中文滑行」锁定", InputGestureLocks.glideCornerLock(base.copy(chineseGlideTyping = true))?.message)
        // 用户 11-11「只有九键不会跳转」：九键滑行占了 2～9 键的滑动，同样锁成长按；整个键盘滑动移光标时九键不滑行，不锁。
        val nine = base.copy(t9GlideTyping = true)
        assertEquals("已被「九键滑行」锁定", InputGestureLocks.glideCornerLock(nine)?.message)
        assertEquals(CornerInputChoice.LONG_PRESS, CornerInputChoice.of(CornerInputChoice.SWIPE.applyTo(nine)))
        assertEquals("关掉九键滑行，存的选择照旧", CornerInputChoice.SWIPE, CornerInputChoice.of(nine.copy(t9GlideTyping = false)))
        assertNull(InputGestureLocks.glideCornerLock(nine.copy(cursorGesture = CursorGestureMode.KEYBOARD)))
        assertNull(InputGestureLocks.glideCornerLock(base))
        assertEquals(CornerInputChoice.SWIPE, CornerInputChoice.of(base))
    }

    @Test fun `choosing long press turns nine key flick on`() {
        assertTrue(InputGestureLocks.withSymbolInputMode(base, SymbolInputMode.LONG_PRESS).t9FlickLetters)
        assertEquals(false, InputGestureLocks.withSymbolInputMode(base, SymbolInputMode.SWIPE_UP).t9FlickLetters)
    }

    @Test fun `keyboard top style reads the two old switches the way the keyboard draws`() {
        assertEquals(KeyboardTopStyle.ROUNDED, KeyboardTopStyle.of(roundedTop = true, softTopEdge = false, glass = true))
        // 圆角开着时柔化本来就不生效（圆角缺口里没法渐变）。
        assertEquals(KeyboardTopStyle.ROUNDED, KeyboardTopStyle.of(roundedTop = true, softTopEdge = true, glass = true))
        assertEquals(KeyboardTopStyle.SOFT, KeyboardTopStyle.of(roundedTop = false, softTopEdge = true, glass = true))
        // 没开玻璃时柔化不起作用，看到的是直角。
        assertEquals(KeyboardTopStyle.SQUARE, KeyboardTopStyle.of(roundedTop = false, softTopEdge = true, glass = false))
        assertEquals(KeyboardTopStyle.SQUARE, KeyboardTopStyle.of(roundedTop = false, softTopEdge = false, glass = true))
        for (style in KeyboardTopStyle.entries) {
            assertEquals(style, KeyboardTopStyle.of(style.roundedTop, style.softTopEdge, glass = true))
        }
    }
}
