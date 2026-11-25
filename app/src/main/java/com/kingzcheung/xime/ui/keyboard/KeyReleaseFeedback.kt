package com.kingzcheung.xime.ui.keyboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 「按键抬起震动」的统一入口：值是 [KeyboardCallbacks.onKeyRelease]，由 [KeyboardLayoutScreen] 和数字/常用符号面板提供。
 *
 * 26 键、14 键、大千的字母键一直自己传 onRelease；九键、笔画、假名、数字、常用符号键盘，以及各布局的
 * Shift、符/123、!@#、重输、左栏符号这些键以前没接，开了抬起震动松手也不震（用户 11-23：九键无效，14/26 键有效）。
 * 现在键自己没传 onRelease、但按下有反馈（传了 onPress）的，松手走这里，和字母键一样。
 */
val LocalKeyReleaseFeedback = staticCompositionLocalOf<((String) -> Unit)?> { null }

/**
 * 抬起反馈不分键类型（FeedbackManager 里松手一律是 KEYBOARD_RELEASE / 轻的 tick），
 * 所以兜底时不必知道是哪个键，传这个占位名。
 */
internal const val SHARED_KEY_RELEASE = "key_release"

/**
 * 键自己传了 [onRelease] 就用它；没传、但按下有反馈（[hasPressFeedback]）时用 [LocalKeyReleaseFeedback]；
 * 按下本来就不震的键（没有 onPress）松手也不震。
 */
@Composable
internal fun keyReleaseOrShared(onRelease: (() -> Unit)?, hasPressFeedback: Boolean): (() -> Unit)? {
    val shared = LocalKeyReleaseFeedback.current
    if (onRelease != null) return onRelease
    if (!hasPressFeedback || shared == null) return null
    return { shared(SHARED_KEY_RELEASE) }
}
