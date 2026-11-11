package com.kingzcheung.xime.ui.keyboard

import android.content.Context
import com.kingzcheung.xime.keyboard.ToolbarButton
import com.kingzcheung.xime.settings.LayoutKind
import com.kingzcheung.xime.settings.SettingsPreferences

/**
 * 布局手势开关（[ToolbarButton.LAYOUT_GESTURES]）和普通入口一样可在「排列」里隐藏、排序；
 * 只在适用的布局出现（排列页同样只列出当前键盘会出现的）；这里决定适用布局、开关状态和存储键。
 * 「滑行输入」按当前键盘管两个开关：英文 26 键是英文滑行，中文全拼、双拼 26 键（[chinese] 为真）是「中文滑行输入」，
 * 两个开关各自独立，开英文不会顺带开中文（用户 10-31）。九键（[nine] 为真）上它管「九键滑行」：
 * 以前九键菜单里没有滑行开关，开了九键滑行在键盘上关不掉（用户 11-09）。
 */
internal fun ToolbarButton.appliesTo(kind: LayoutKind): Boolean = when (this) {
    ToolbarButton.GLIDE -> kind == LayoutKind.ALPHABETIC || kind == LayoutKind.T9
    ToolbarButton.T9_FLICK -> kind == LayoutKind.T9
    ToolbarButton.FOURTEEN_SWIPE -> kind == LayoutKind.MERGED
    ToolbarButton.KANA_HINTS -> kind == LayoutKind.KANA_KEYPAD
    ToolbarButton.FLICK_DISC -> kind in setOf(LayoutKind.T9, LayoutKind.MERGED, LayoutKind.KANA_KEYPAD)
    else -> true
}

internal fun ToolbarButton.gestureOn(prefs: KeyboardInputPreferences, chinese: Boolean = false, nine: Boolean = false): Boolean = when (this) {
    ToolbarButton.GLIDE -> if (nine) prefs.t9GlideTyping else if (chinese) prefs.chineseGlideTyping else prefs.glideTyping
    ToolbarButton.T9_FLICK -> prefs.t9FlickLetters &&
        InputGestureLocks.lockFor(prefs, InputGestureLock.Option.T9_FLICK_LETTERS, true) == null
    ToolbarButton.FOURTEEN_SWIPE -> prefs.fourteenLetterSwipe
    ToolbarButton.KANA_HINTS -> prefs.kanaDirectionHints
    ToolbarButton.FLICK_DISC -> prefs.flickDirectionDisc
    else -> false
}

/** 同一含义的全部存储键（旧键随新键一起写，保持读取兼容）。 */
internal fun ToolbarButton.gestureKeys(chinese: Boolean = false, nine: Boolean = false): List<String> = when (this) {
    ToolbarButton.GLIDE -> if (nine) listOf("t9_glide_typing")
        else if (chinese) listOf(KeyboardInputPreferences.CHINESE_GLIDE_KEY) else listOf("glide_typing_v2", "glide_typing")
    ToolbarButton.T9_FLICK -> listOf("t9_flick_letters")
    ToolbarButton.FOURTEEN_SWIPE -> listOf("fourteen_letter_swipe")
    ToolbarButton.KANA_HINTS -> listOf("kana_direction_hints")
    ToolbarButton.FLICK_DISC -> listOf("flick_direction_disc_v2", "flick_direction_disc")
    else -> emptyList()
}

/** 只改这一项的键，避免用整份偏好覆盖其他设置刚写入的值。 */
internal fun ToolbarButton.flipGesture(context: Context, prefs: KeyboardInputPreferences, chinese: Boolean = false, nine: Boolean = false) {
    val next = !gestureOn(prefs, chinese, nine)
    // 和设置页同一套锁：被别的设置锁住时不改，提示是谁锁的。
    val option = when (this) {
        ToolbarButton.GLIDE -> InputGestureLock.Option.GLIDE_TYPING
        ToolbarButton.T9_FLICK -> InputGestureLock.Option.T9_FLICK_LETTERS
        else -> null
    }
    val lock = option?.let { InputGestureLocks.lockFor(prefs, it, next) }
    if (lock != null) {
        android.widget.Toast.makeText(context, "$label${lock.message}", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    val edit = SettingsPreferences.getPrefsPublic(context).edit()
    gestureKeys(chinese, nine).forEach { edit.putBoolean(it, next) }
    edit.apply()
}
