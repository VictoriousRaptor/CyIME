package com.kingzcheung.xime.ui.keyboard

import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.kingzcheung.xime.settings.SettingsPreferences

/** Shared persisted choice; independent of keyboard dimensions and insets. */
@Composable
internal fun rememberRoundedKeyboardBottom(): Boolean =
    rememberBooleanChoice(SettingsPreferences.KEY_ROUNDED_KEYBOARD_BOTTOM, SettingsPreferences::roundedKeyboardBottom)

/** 固定键盘顶部两角是否圆角，和底部圆角同一种读法。 */
@Composable
internal fun rememberRoundedKeyboardTop(): Boolean =
    rememberBooleanChoice(SettingsPreferences.KEY_ROUNDED_KEYBOARD_TOP, SettingsPreferences::roundedKeyboardTop)

/** 玻璃「柔化顶部边缘」开关，同一种读法（设置页改了键盘立刻跟）。 */
@Composable
internal fun rememberGlassSoftTopEdge(): Boolean =
    rememberBooleanChoice(SettingsPreferences.KEY_GLASS_SOFT_TOP_EDGE, SettingsPreferences::glassSoftTopEdge)

@Composable
internal fun rememberInlineQuickActionEditing(): Boolean =
    rememberBooleanChoice(SettingsPreferences.KEY_INLINE_QUICK_ACTION_EDITING, SettingsPreferences::inlineQuickActionEditing)

@Composable
internal fun rememberFloatingGeometryLocked(): Boolean =
    rememberBooleanChoice(SettingsPreferences.KEY_FLOATING_GEOMETRY_LOCKED, SettingsPreferences::floatingGeometryLocked)

@Composable
private fun rememberBooleanChoice(prefKey: String, read: (android.content.Context) -> Boolean): Boolean {
    val context = LocalContext.current
    var value by remember(context, prefKey) { mutableStateOf(read(context)) }
    DisposableEffect(context, prefKey) {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == prefKey) {
                value = read(context)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return value
}

internal fun useDarkNavigationIcons(rounded: Boolean, systemDark: Boolean, keyTextLuminance: Float): Boolean =
    if (rounded) !systemDark else keyTextLuminance < 0.5f

/**
 * 键盘顶部样式三选一（用户 11-01：主题页的「柔化顶部边缘」并进「键盘顶部样式」）。
 * 存的仍是「顶部圆角」「柔化顶部边缘」两个值，老用户不用迁移；[of] 和键盘实际画法一致：
 * 圆角优先（圆角缺口里没法渐变），柔化只在玻璃开着、顶部直角时生效。
 */
internal enum class KeyboardTopStyle(val label: String, val roundedTop: Boolean, val softTopEdge: Boolean) {
    ROUNDED("圆角", roundedTop = true, softTopEdge = false),
    SQUARE("直角", roundedTop = false, softTopEdge = false),
    SOFT("柔化", roundedTop = false, softTopEdge = true);

    companion object {
        fun of(roundedTop: Boolean, softTopEdge: Boolean, glass: Boolean): KeyboardTopStyle = when {
            roundedTop -> ROUNDED
            softTopEdge && glass -> SOFT
            else -> SQUARE
        }
    }
}
