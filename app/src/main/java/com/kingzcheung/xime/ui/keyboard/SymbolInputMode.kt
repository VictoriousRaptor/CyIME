package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.keyboard.GestureAction

enum class SymbolInputMode(val label: String) {
    // Keep the stored enum name compatible with existing preferences.
    SWIPE_UP("滑动输入"), LONG_PRESS("长按输入")
}

/** Existing up/down configuration remains the top/bottom slot, including custom values.
 * Apply after long-press adaptation so its disabled top-slot gesture follows the same direction.
 * Corner-symbol keys and clear/undo follow this setting; kana flick gestures keep their contracts.
 */
internal fun KeyGestureActions.withSymbolSwipeDirection(reverse: Boolean): KeyGestureActions =
    if (reverse) this else copy(
        upText = downText, downText = upText,
        onUp = onDown, onDown = onUp,
        upPreviewText = downPreviewText, downPreviewText = upPreviewText,
        upPreviewFromTop = downPreviewFromTop, downPreviewFromTop = upPreviewFromTop,
        blockUpSwipe = blockDownSwipe, blockDownSwipe = blockUpSwipe,
    )

/** Function keys opt in explicitly; repeat, tap and horizontal editing actions stay unchanged. */
internal fun RepeatingKeyActions.withSymbolSwipeDirection(reverse: Boolean): RepeatingKeyActions =
    if (reverse) this else copy(
        onUp = onDown, onDown = onUp,
        upLabel = downLabel?.replaceFirst("下滑", "上滑"),
        downLabel = upLabel?.replaceFirst("上滑", "下滑"),
        swappedVertical = !swappedVertical,
    )

/** Only literal input opts in; editing commands and dedicated function keys keep their gestures. */
internal fun symbolInputValue(value: String?, action: GestureAction?): String? =
    value?.takeIf { it.isNotBlank() && (action == null || action == GestureAction.COMMIT) }

/** 「长按输入」原来写死的时长；设置项 [KeyboardInputPreferences.symbolLongPressSeconds] 的默认值。 */
internal const val DEFAULT_SYMBOL_LONG_PRESS_MS = 300L

/**
 * 「长按输入」（关闭角标滑动）：按满 [timeoutMillis] 立刻输入上方角标，不等松手；
 * 同时把气泡换成刚输入的角标，松手消失（用户 11-08，各布局、各语言一样）。
 */
internal fun KeyGestureActions.withSymbolInput(
    mode: SymbolInputMode, symbol: String?, keepSwipe: Boolean = false,
    timeoutMillis: Long = DEFAULT_SYMBOL_LONG_PRESS_MS,
): KeyGestureActions {
    if (mode != SymbolInputMode.LONG_PRESS || symbol.isNullOrBlank() || onUp == null ||
        onLongPress != null || longPressDrawableIds.isNotEmpty()) return this
    val commitSymbol = onUp
    return copy(
        onUp = if (keepSwipe) onUp else null,
        blockUpSwipe = !keepSwipe,
        longPressTimeoutMillis = timeoutMillis,
        // The timeout callback commits once; no release-time menu may commit a second value.
        longPressItems = emptyList(),
        onLongPressSelect = null,
        onLongPress = { commitSymbol(symbol) },
        longPressBubbleText = symbol,
    )
}

/**
 * 26 键字母滑行开着时（用户 11-11「关闭数字行，滑行输入打不出 !@#$%^&*()」）：字母键往哪个方向滑都是滑行，
 * 只靠长按出上角标，下角标那一层就打不到了。这时长按弹出上下两个角标，默认选上角标：不动松手出上角标，
 * 往右挪一格松手出下角标（和「长按弹出字母气泡」同一个选法）。键上印的位置不变，开关滑行符号不挪。
 * 只有一个角标时照「长按输入」按满立刻输入。西班牙语等长按出带重音字母的键（[upper] 为空、已有长按选项）不动。
 * 选中后走原来的上滑／下滑那条路上屏，所以自定义的角标动作（不是直接上屏的）也照旧。
 */
internal fun KeyGestureActions.withGlideCornerMenu(
    upper: String?, lower: String?, timeoutMillis: Long = DEFAULT_SYMBOL_LONG_PRESS_MS,
): KeyGestureActions {
    if (onLongPress != null || longPressDrawableIds.isNotEmpty()) return this
    val commitUpper = onUp
    val commitLower = onDown
    val top = upper?.takeIf { it.isNotBlank() && commitUpper != null }
    val bottom = lower?.takeIf { it.isNotBlank() && commitLower != null && it != top }
    if (top == null && longPressItems.isNotEmpty()) return this
    val items = listOfNotNull(top, bottom)
    if (items.isEmpty()) return this
    val pick: (String) -> Unit = { chosen ->
        if (top != null && chosen == top) commitUpper?.invoke(top) else commitLower?.invoke(bottom.orEmpty())
    }
    return copy(
        onUp = null, blockUpSwipe = true, onDown = null, blockDownSwipe = true,
        longPressTimeoutMillis = timeoutMillis,
        longPressItems = if (items.size > 1) items else emptyList(),
        onLongPressSelect = if (items.size > 1) pick else null,
        onLongPress = if (items.size > 1) null else ({ pick(items.single()) }),
        longPressBubbleText = items.singleOrNull(),
    )
}
