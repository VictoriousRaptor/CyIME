package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import com.kingzcheung.xime.settings.KeysConfigHelper

/**
 * 双拼编码打了单数个键（声母或零声母的第一键已经打了，正等韵母）。分隔符 ' 和空格不算键。
 * 「ing 放在 Shift 键上」开着时，这时左下角的 Shift 键换成 ing。
 */
internal fun awaitsDoublePinyinFinal(input: String): Boolean =
    input.count { it != '\'' && it != ' ' } % 2 == 1

/**
 * 双拼辅助码开着时 Shift 键什么时候变成「辅」：正在打拼音、还没点过「辅」（编码里没有引导符 `），
 * 也不是在等韵母（那一下留给 ing，只有微软双拼开了「ing 放在 Shift 键上」才会有）。
 */
internal fun auxCodeKeyShown(input: String, ingShown: Boolean): Boolean =
    input.isNotEmpty() && com.kingzcheung.xime.settings.SpellingPatches.AUX_CODE_TRIGGER !in input && !ingShown

/**
 * 左下角那一格：[ingActive] 为真时是 ing 键（点按送分号，微软双拼的 ing）；[auxActive] 为真时是「辅」
 * （点按送辅码引导符 `，接着打形码筛候选，见 SpellingPatches.AuxCode）；否则是平常的 [shift]。
 * 状态在这里读，打字时只重组这一格，不重组整个键盘。
 */
@Composable
internal fun SemicolonShiftKey(
    ingActive: State<Boolean>?,
    auxActive: State<Boolean>? = null,
    onKeyPress: (String) -> Unit,
    onKeyPressDown: ((String) -> Unit)?,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier,
    shadowEnabled: Boolean,
    shadowElevation: Dp,
    shadowShapeRadius: Dp,
    shift: @Composable () -> Unit,
) {
    if (ingActive?.value == true) {
        SwipeableKeyButton(
            text = "ing",
            onClick = { onKeyPress(";") },
            // Same press-down feedback (vibration, sound) as the letter keys; GitHub #180.
            onPress = { onKeyPressDown?.invoke(";") },
            backgroundColor = backgroundColor,
            textColor = textColor,
            modifier = modifier.fillMaxHeight().testTag("shift-key-ing"),
            shadowEnabled = shadowEnabled,
            shadowElevation = shadowElevation,
            shadowShapeRadius = shadowShapeRadius,
        )
    } else if (auxActive?.value == true) {
        val trigger = com.kingzcheung.xime.settings.SpellingPatches.AUX_CODE_TRIGGER
        SwipeableKeyButton(
            text = "辅",
            onClick = { onKeyPress(trigger) },
            onPress = { onKeyPressDown?.invoke(trigger) },
            backgroundColor = backgroundColor,
            textColor = textColor,
            modifier = modifier.fillMaxHeight().testTag("shift-key-aux"),
            shadowEnabled = shadowEnabled,
            shadowElevation = shadowElevation,
            shadowShapeRadius = shadowShapeRadius,
        )
    } else shift()
}

/**
 * 微软双拼的分号键：没在打字时点它按全角／半角设置出标点（ImeKeyRouter.semicolonPunctuation），
 * 全角时键面写「；」，和上屏的一样；打字中照旧是 ing。其他方案、英文键盘的分号键不变。
 */
@Composable
internal fun semicolonKeyLabel(key: String, isAsciiMode: Boolean, label: String): String =
    if (key == ";" && label == ";" && LocalKeyboardPunctuation.current?.full == true &&
        KeysConfigHelper.semicolonIsCode(isAsciiMode)) "；" else label
