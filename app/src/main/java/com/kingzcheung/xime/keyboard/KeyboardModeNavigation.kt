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
