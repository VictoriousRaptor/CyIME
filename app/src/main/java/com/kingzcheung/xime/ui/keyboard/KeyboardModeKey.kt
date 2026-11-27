package com.kingzcheung.xime.ui.keyboard

import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.keyboard.KeyboardInputPage
import com.kingzcheung.xime.keyboard.modeSlotTarget

val LocalModeSlotWeight = staticCompositionLocalOf { 0.8f }
val LocalTextModeLabel = staticCompositionLocalOf { "中文" }

/** A mode key is deliberately a text key, never a language/globe key. */
@Composable
internal fun KeyboardModeKey(
    slot: Int,
    page: KeyboardInputPage = KeyboardInputPage.TEXT,
    onKeyPress: (String) -> Unit,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    onKeyPressDown: ((String) -> Unit)? = null,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    /** 26 键传入：符号键按住当 Ctrl，拖到字母上发 Ctrl+字母（用户 10-24）。 */
    ctrlTargets: ShiftSlideTargets? = null,
    onCtrlShortcut: ((Char) -> Unit)? = null,
) {
    val prefs = LocalKeyboardInputPreferences.current
    val merged = page == KeyboardInputPage.TEXT && prefs.symbolKeyCycle
    val target = modeSlotTarget(page, slot, LocalTextModeLabel.current, merged)
    val context = LocalContext.current
    val openTarget = {
        val action = if (merged && KeyboardInputPreferences.lastSymbolOrNumberPage(context) == KeyboardInputPage.NUMBERS) {
            "number"
        } else target.action
        onKeyPress(action)
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalKeyboardPunctuation provides null) {
    if (ctrlTargets != null && onCtrlShortcut != null && target.action == "symbol") CtrlSymbolKeyButton(
        text = target.label,
        targets = ctrlTargets,
        holdMillis = prefs.symbolKeyHoldMs,
        onOpenSymbols = openTarget,
        onShortcut = onCtrlShortcut,
        onPressDown = { onKeyPressDown?.invoke("mode_change") },
        backgroundColor = backgroundColor,
        textColor = textColor,
        modifier = modifier.testTag("mode-slot-$slot"),
        shadowEnabled = shadowEnabled,
        shadowElevation = shadowElevation,
        shadowShapeRadius = shadowShapeRadius,
    ) else KeyButton(
        text = target.label,
        fontSize = 16.sp,
        onClick = openTarget,
        onPress = { onKeyPressDown?.invoke("mode_change") },
        backgroundColor = backgroundColor,
        textColor = textColor,
        modifier = modifier.testTag("mode-slot-$slot").semantics {
            if (target.action == "abc") contentDescription = "返回键盘"
            onClick { openTarget(); true }
        },
        shadowEnabled = shadowEnabled,
        shadowElevation = shadowElevation,
        shadowShapeRadius = shadowShapeRadius,
    )
    }
}
