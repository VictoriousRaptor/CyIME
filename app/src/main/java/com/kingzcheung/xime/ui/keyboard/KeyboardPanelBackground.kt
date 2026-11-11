package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import com.kingzcheung.xime.settings.BackgroundConfig
import com.kingzcheung.xime.settings.FrostedGlassConfig
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.keyboardBackground

/** Draw on the actual panel content, never on a popup/window positioning host. */
@Composable
internal fun Modifier.keyboardPanelBackground(
    fallback: Color,
    glass: FrostedGlassConfig = LocalKeyboardInputPreferences.current.frostedGlass,
): Modifier {
    val themeBackground = KeyboardThemes.getRenderingScheme(SettingsPreferences.getKeyboardTheme(LocalContext.current)).keyboardBackground
    if (inheritsKeyboardBackground() && panelSeesKeyboardBackground(glass.enabled, themeBackground)) return this
    // 渐变/图片主题不能用一块主题底色代替（iOS 渐变的底色是纯黑）；纯色主题仍按调用方给的底色。
    val layered = themeBackground?.type == "gradient" || themeBackground?.type == "image"
    return if (glass.enabled || layered) keyboardBackground(themeBackground, fallback.luminance() < 0.5f, fallback, glass)
    else background(fallback)
}

/**
 * 面板（菜单、表情、剪贴板…）不另画背景，直接透出键盘本身那一整张：玻璃，或图片、渐变主题。
 * 图片、渐变是整块键盘一张图，面板另画一张会从面板顶重新摆放，和上面工具栏那一段接不上（用户 11-09：
 * 不开玻璃时工具栏和菜单是两张断开的背景）。纯色主题照旧画一块底色。
 * 面板下面的按键由 [excludeRevealedKeyboardPanels] 挖掉，两处用同一个判断。
 */
internal fun panelSeesKeyboardBackground(glassEnabled: Boolean, background: BackgroundConfig?): Boolean =
    glassEnabled || background?.type == "gradient" || background?.type == "image"

/**
 * Overlays drawn on top of the keys (cursor control, rejected commit). A panel hides the keys under it, but an
 * overlay cannot, and a glass panel background is see-through: the keys showed through the overlay's text.
 * On glass add an almost opaque wash of the theme background; without glass the panel background is opaque.
 */
@Composable
internal fun Modifier.keyboardOverlayBackground(background: Color): Modifier {
    val glass = LocalKeyboardInputPreferences.current.frostedGlass
    return keyboardPanelBackground(background, glass)
        .then(if (glass.enabled) Modifier.background(background.copy(alpha = .9f)) else Modifier)
}

/** Card, text and dim colors for an opaque card over the keyboard (confirmations, record actions). */
internal data class KeyboardCardColors(val card: Color, val text: Color, val scrim: Color)

/**
 * Keyboard palette, not the Material surface: the card looks like the keys of every theme (a white
 * Material surface showed on dark iOS). It covers other content, so it stays opaque even on glass.
 */
@Composable
internal fun keyboardCardColors(): KeyboardCardColors {
    val colors = MaterialTheme.colorScheme
    val palette = com.kingzcheung.xime.ui.theme.LocalKeyboardPalette.current
    val glass = LocalKeyboardInputPreferences.current.frostedGlass
    return KeyboardCardColors(
        card = palette?.key?.copy(alpha = 1f) ?: if (glass.enabled) colors.surface.copy(alpha = 1f) else colors.surfaceContainerHigh,
        text = palette?.text ?: colors.onSurface,
        scrim = colors.scrim.copy(alpha = .32f),
    )
}
