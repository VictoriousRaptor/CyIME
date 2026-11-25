package com.kingzcheung.xime.keyboard

/** Input pages share the same navigation, independently of language and text layout. */
enum class KeyboardInputPage { TEXT, SYMBOLS, NUMBERS }

data class KeyboardModeTarget(val label: String, val action: String)

fun modeSlotCount(cycleEnabled: Boolean): Int = if (cycleEnabled) 1 else 2

fun modeSlotTarget(page: KeyboardInputPage, slot: Int, textLabel: String, cycleEnabled: Boolean = false): KeyboardModeTarget {
    require(slot in 1..modeSlotCount(cycleEnabled))
    if (cycleEnabled) return when (page) {
        KeyboardInputPage.TEXT -> KeyboardModeTarget("?123", "symbol")
        KeyboardInputPage.SYMBOLS -> KeyboardModeTarget("123", "number")
        KeyboardInputPage.NUMBERS -> KeyboardModeTarget(textLabel, "abc")
    }
    return when {
        slot == 1 && page == KeyboardInputPage.SYMBOLS -> KeyboardModeTarget(textLabel, "abc")
        slot == 1 -> KeyboardModeTarget("!@#", "symbol")
        page == KeyboardInputPage.NUMBERS -> KeyboardModeTarget(textLabel, "abc")
        else -> KeyboardModeTarget("123", "number")
    }
}

/**
 * 两个模式键从左往右的排法：默认 !@# 在左、123 在右；开了「123 和 !@# 对调」（#499，默认关）就反过来。
 * 主键盘、符号页、数字页同一个排法，所以点哪个键进去，返回键就在同一个位置。
 */
fun modeSlotOrder(swapped: Boolean, cycleEnabled: Boolean = false): List<Int> = when {
    cycleEnabled -> listOf(1)
    swapped -> listOf(2, 1)
    else -> listOf(1, 2)
}

/** Secondary pages never become the text destination, including nested tool overlays. */
fun KeyboardPage.textMainType(): MainType = when (this) {
    is KeyboardPage.Main -> type
    is KeyboardPage.Panel -> returnTo
    is KeyboardPage.Overlay -> behind.textMainType()
}

/** Tool overlays cover an input surface without replacing it or ending its input mode. */
fun KeyboardPage.underlyingPage(): KeyboardPage = when (this) {
    is KeyboardPage.Overlay -> behind.underlyingPage()
    else -> this
}

/** First page contains everyday punctuation; specialist categories remain available. */
fun commonSymbolsFor(textLabel: String): List<String> = when (textLabel) {
    "ABC" -> listOf(".", ",", "?", "!", "'", "\"", ":", ";", "-", "_", "/", "\\",
        "@", "#", "$", "%", "&", "*", "+", "=", "(", ")", "[", "]", "{", "}", "<", ">", "~", "`", "…", "°")
    "あいう" -> listOf("、", "。", "？", "！", "ー", "〜", "・", "…", "「", "」", "『", "』",
        "（", "）", "［", "］", "：", "；", "／", "＼", "@", "#", "%", "&", "+", "-", "×", "÷", "=", "♡", "♪", "☆")
    else -> listOf("，", "。", "？", "！", "、", "：", "；", "…", "“", "”", "‘", "’", "（", "）", "《", "》",
        "【", "】", "—", "～", "@", "#", "￥", "%", "&", "*", "+", "-", "=", "/", "\\", "·")
}
