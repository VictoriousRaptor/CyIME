package com.kingzcheung.xime.ui.keyboard

/**
 * 设置页把原来互锁的几对开关合成一个选择（用户 11-01「脱裤子放屁的设置项」）。
 * 只换设置页的写法：存的还是原来那几个值，键盘读法不变；旧值组合按 [of] 换算，效果和以前一样。
 * 原「手势预设」（滑动／长按／英文滑行）只是「角标输入方式」和「滑行输入」的组合，已去掉。
 */
enum class CornerInputChoice(val label: String) {
    /** 滑动出角标，长按没有反应。 */
    SWIPE("滑动输入"),
    /** 滑动出角标，长按弹出字母气泡（大小写、带声调的字母）。原「长按弹出字母气泡」开关。 */
    SWIPE_BUBBLE("滑动＋字母气泡"),
    /** 长按出上方角标，另一个方向滑动出下方角标。 */
    LONG_PRESS("长按输入");

    /** 选它时写回的值；选「长按输入」照旧顺带打开九键拂动（[InputGestureLocks.withSymbolInputMode]）。 */
    fun applyTo(settings: KeyboardInputPreferences): KeyboardInputPreferences = when (this) {
        SWIPE -> InputGestureLocks.withSymbolInputMode(settings, SymbolInputMode.SWIPE_UP).copy(letterLongPressMenu = false)
        SWIPE_BUBBLE -> InputGestureLocks.withSymbolInputMode(settings, SymbolInputMode.SWIPE_UP).copy(letterLongPressMenu = true)
        LONG_PRESS -> InputGestureLocks.withSymbolInputMode(settings, SymbolInputMode.LONG_PRESS).copy(letterLongPressMenu = false)
    }

    companion object {
        /** 角标“长按”时长按总是出角标，气泡出不来；加锁以前两个都开着的，照实际效果认作「长按输入」。 */
        fun of(settings: KeyboardInputPreferences): CornerInputChoice = when {
            // 开着 26 键滑行时字母键的滑动都给了滑行，角标只能长按出（键盘也按长按处理，见 KeyButton）。
            InputGestureLocks.glideCornerLock(settings) != null -> LONG_PRESS
            settings.symbolInputMode == SymbolInputMode.LONG_PRESS -> LONG_PRESS
            settings.letterLongPressMenu -> SWIPE_BUBBLE
            else -> SWIPE
        }
    }
}

/** 键帽上的字母：原「字母键显示大写」「中文键盘字母显示大写」两个互锁开关。 */
enum class KeyLabelCaseChoice(val label: String) {
    LOWER("小写"),
    UPPER("大写"),
    /** 中文时大写、英文时小写（按 Shift 才大写），像搜狗。 */
    CHINESE_UPPER("中文大写");

    fun applyTo(settings: KeyboardInputPreferences): KeyboardInputPreferences = settings.copy(
        uppercaseKeyLabels = this == UPPER, chineseUppercaseKeyLabels = this == CHINESE_UPPER)

    companion object {
        /** 两个都开着（加锁以前）时键盘按「一律大写」画，见 QwertyRowOptions。 */
        fun of(settings: KeyboardInputPreferences): KeyLabelCaseChoice = when {
            settings.uppercaseKeyLabels -> UPPER
            settings.chineseUppercaseKeyLabels -> CHINESE_UPPER
            else -> LOWER
        }
    }
}

/**
 * 空格长按和整个键盘滑动移光标：原「滑动移动光标」（长按空格／键盘滑动／关闭）和「长按空格」两处都能选
 * “长按空格移动光标”。现在长按空格是一个三选一，整个键盘滑动是一个开关；存的仍是 cursorGesture 和 spaceHold。
 * 长按空格=移动光标 ↔ cursorGesture=SPACE；开关开 ↔ KEYBOARD；其余 ↔ NONE。
 */
object CursorChoices {
    val spaceHoldChoices = listOf(SpaceHoldAction.CURSOR, SpaceHoldAction.REPEAT, SpaceHoldAction.VOICE_TOGGLE)

    fun spaceHold(settings: KeyboardInputPreferences): SpaceHoldAction = settings.effectiveSpaceHold

    /** 选「移动光标」：整个键盘滑动随之关掉（两个都要占手指拖动）；选另两项沿用 [KeyboardInputPreferences.withSpaceHold]。 */
    fun withSpaceHold(settings: KeyboardInputPreferences, action: SpaceHoldAction): KeyboardInputPreferences =
        if (action == SpaceHoldAction.CURSOR) settings.copy(cursorGesture = CursorGestureMode.SPACE)
        else settings.withSpaceHold(action)

    fun keyboardSwipe(settings: KeyboardInputPreferences): Boolean = settings.cursorGesture == CursorGestureMode.KEYBOARD

    /** 开关不能开的原因：长按空格正在移动光标，或被滑行输入锁住。 */
    fun keyboardSwipeLock(settings: KeyboardInputPreferences): String? = when {
        keyboardSwipe(settings) -> null
        settings.cursorGesture == CursorGestureMode.SPACE -> "长按空格已经用来移动光标，上面换成连续空格或语音才能开"
        else -> InputGestureLocks.lockFor(settings, InputGestureLock.Option.CURSOR_GESTURE, CursorGestureMode.KEYBOARD)?.message
    }

    fun withKeyboardSwipe(settings: KeyboardInputPreferences, on: Boolean): KeyboardInputPreferences =
        settings.copy(cursorGesture = if (on) CursorGestureMode.KEYBOARD else CursorGestureMode.NONE)
}

/** 一个被锁住的选项值：[option] 不能设成 [lockedValue]，原因是 [by] 这个设置。 */
data class InputGestureLock(val option: Option, val lockedValue: Any, val by: String) {
    enum class Option {
        SYMBOL_INPUT_MODE, LETTER_LONG_PRESS_MENU, GLIDE_TYPING, CURSOR_GESTURE, T9_FLICK_LETTERS,
        /** 双拼键位提示 ↔ 上方/下方角标提示（两个开关都用 [CORNER_HINTS]）。 */
        DOUBLE_PINYIN_KEY_HINTS, CORNER_HINTS,
        /** Shift 键分词 ↔ ing 放在 Shift 键上。 */
        SHIFT_SYLLABLE_SPLIT, SEMICOLON_ON_SHIFT,
        /** 字母键显示大写 ↔ 中文键盘字母显示大写。 */
        UPPERCASE_KEY_LABELS, CHINESE_UPPERCASE_KEY_LABELS,
    }

    /** 设置页在被锁的那一项下面显示这句。 */
    val message: String get() = "已被「$by」锁定"
}

/**
 * 明显冲突的设置互相加锁（用户 10-20 要求）：先设好的那一项把冲突的选项锁死，并说明是谁锁的。
 * - 英文、中文、九键滑行输入 ↔ 整个键盘滑动移光标：都是在字母上拖动。
 * - 角标“长按”时九键拂动必须开：长按被数字占了，不开拂动就没法指定字母。
 * - Shift 键分词 ↔ ing 放在 Shift 键上：打字时都把 Shift 键换成别的键。
 * 11-01 起这几对不再靠锁，设置页合成了一个选择（[CornerInputChoice]、[KeyLabelCaseChoice]）或自动处理：
 * 角标“长按” ↔ 字母气泡；字母键大写 ↔ 中文键盘大写；双拼键位提示 ↔ 上下角标提示（两个都开时自动分层显示，
 * 见 KeyButton 的 dpWithCorners）。对应的 [InputGestureLock.Option] 留着不删，旧代码引用照常编译，只是不再产生锁。
 * 两项都已开着（加锁以前开的）时都不锁，关掉哪个都行；设置页只在被锁的一项关着时置灰它。
 */
object InputGestureLocks {
    fun of(settings: KeyboardInputPreferences): List<InputGestureLock> = buildList {
        if (settings.t9GlideTyping && settings.cursorGesture != CursorGestureMode.KEYBOARD) {
            add(InputGestureLock(InputGestureLock.Option.T9_FLICK_LETTERS, true, "九键滑行"))
        } else if (settings.symbolInputMode == SymbolInputMode.LONG_PRESS) {
            add(InputGestureLock(InputGestureLock.Option.T9_FLICK_LETTERS, false, "按键角标的符号和数字：长按输入"))
        }
        // 26 键滑行开着时字母键往任何方向滑都是滑行，角标的滑动输入用不了：锁在「长按输入」，写明是哪个滑行锁的（11-08）。
        when {
            settings.glideTyping -> add(InputGestureLock(InputGestureLock.Option.SYMBOL_INPUT_MODE, SymbolInputMode.SWIPE_UP, "英文滑行"))
            settings.chineseGlideTyping -> add(InputGestureLock(InputGestureLock.Option.SYMBOL_INPUT_MODE, SymbolInputMode.SWIPE_UP, "中文滑行"))
        }
        if (settings.glideTyping) {
            add(InputGestureLock(InputGestureLock.Option.CURSOR_GESTURE, CursorGestureMode.KEYBOARD, "英文滑行"))
        } else if (settings.chineseGlideTyping) {
            add(InputGestureLock(InputGestureLock.Option.CURSOR_GESTURE, CursorGestureMode.KEYBOARD, "中文滑行"))
        } else if (settings.t9GlideTyping) {
            add(InputGestureLock(InputGestureLock.Option.CURSOR_GESTURE, CursorGestureMode.KEYBOARD, "九键滑行"))
        }
        if (settings.cursorGesture == CursorGestureMode.KEYBOARD) {
            add(InputGestureLock(InputGestureLock.Option.GLIDE_TYPING, true, "键盘面板滑动光标"))
        }
        if (settings.doublePinyinSemicolonOnShift) {
            add(InputGestureLock(InputGestureLock.Option.SHIFT_SYLLABLE_SPLIT, true, "ing 放在 Shift 键上"))
        } else if (settings.shiftSyllableSplit) {
            add(InputGestureLock(InputGestureLock.Option.SEMICOLON_ON_SHIFT, true, "Shift 键分词"))
        }
    }

    /** 选“长按”角标时顺带打开九键拂动（否则九键没法选字母）；设置页改角标方式时用它代替直接 copy。 */
    fun withSymbolInputMode(settings: KeyboardInputPreferences, mode: SymbolInputMode): KeyboardInputPreferences =
        settings.copy(symbolInputMode = mode,
            t9FlickLetters = settings.t9FlickLetters || mode == SymbolInputMode.LONG_PRESS)

    /** 角标的滑动输入被 26 键滑行锁住时返回那把锁（设置页置灰「滑动输入」两张卡并说明原因）。 */
    fun glideCornerLock(settings: KeyboardInputPreferences): InputGestureLock? =
        lockFor(settings, InputGestureLock.Option.SYMBOL_INPUT_MODE, SymbolInputMode.SWIPE_UP)

    /** 这个值能不能选；不能选时返回锁的原因，设置页据此置灰并显示 [InputGestureLock.message]。 */
    fun lockFor(settings: KeyboardInputPreferences, option: InputGestureLock.Option, value: Any): InputGestureLock? =
        of(settings).firstOrNull { it.option == option && it.lockedValue == value }
}
