package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp

/**
 * 「数字行」（设置 → 键盘布局 → 数字键盘，默认关）：26 键、14 键字母上方加一排，键和顺序在「按键符号 → 数字行」里改。
 * 键盘总高度不变，数字行比字母行矮一点（字母行的 [NUMBER_ROW_HEIGHT_WEIGHT]），字母键少缩一些。
 */
internal const val NUMBER_ROW_HEIGHT_WEIGHT = 0.8f

/** 数字行占几排高：默认矮一点；开了「数字行和字母一样高」（#444，默认关）就是整排。 */
internal fun numberRowHeightWeight(fullHeight: Boolean): Float = if (fullHeight) 1f else NUMBER_ROW_HEIGHT_WEIGHT

/** 分体中缝按行登记位置（[SplitGapSpacer]）；字母行用 0-3，数字行用一个不会撞上的行号。 */
internal const val NUMBER_ROW_SPLIT_ROW = 9

/**
 * 这张字母键盘要不要数字行：
 * - 全局「数字行」开启时，注音（大千首排本身就是数字位）不加，其余字母键盘显示；
 * - 密码输入框且开启「密码框自动数字行」时，26 键（[is26Key]）自动弹出数字行。
 */
internal fun numberRowShown(
    setting: Boolean,
    zhuyin: Boolean,
    passwordField: Boolean = false,
    passwordAutoNumberRow: Boolean = false,
    is26Key: Boolean = true,
): Boolean = !zhuyin && (setting || (passwordField && passwordAutoNumberRow && is26Key))

/** 分体时左半的键数：一半归左手，单数时多出的一个给右手；不分体或只有一个键时 -1。 */
internal fun numberRowSplitAt(count: Int, split: Boolean): Int = if (split && count >= 2) count / 2 else -1

/**
 * 每个键的份数：不分体时平分整行；分体时左右两半各占一半宽，中缝和字母行的中缝对齐，
 * 键数是单数也不会把缝挤偏。
 */
internal fun numberRowSpans(count: Int, splitAt: Int): List<Float> =
    if (splitAt !in 1 until count) List(count) { 1f }
    else List(count) { index -> if (index < splitAt) 1f / splitAt else 1f / (count - splitAt) }

/** 电脑键盘上数字键按 Shift 出的符号；中英文都一样，不跟全角/半角。 */
private val NUMBER_SHIFT_SYMBOLS = "1234567890".zip("!@#$%^&*()").associate { (digit, symbol) -> digit.toString() to symbol.toString() }

/**
 * 数字行键上滑、下滑输入的符号：和电脑键帽一样，1 上是 !，……，0 上是 )（11-15 起不画在键上，见 [NumberKeyRow]）。跟着数字走，「按键符号 → 数字行」里改了顺序也不变；
 * 不是数字的格子（自己加的符号、短语）没有角标。开了数字行，这些符号从 26 键第一排挪到这里（用户 11-01 定）。
 */
internal fun numberRowShiftSymbol(key: String): String? = NUMBER_SHIFT_SYMBOLS[key]

/**
 * Shift 切换到大写模式（单次、锁定、按住）时，数字键的键帽与点按输入切换为上方对应的特殊符号（1→! … 0→)）；
 * 自己加进数字行的符号或短语保持原样。
 */
internal fun numberRowKeyText(key: String, shifted: Boolean): String =
    if (shifted) numberRowShiftSymbol(key) ?: key else key

/** 数字键长按弹出的分数（用户 11-15：数字行不画角标，长按直接弹菜单 ½ 到 ⅛）。 */
internal val NUMBER_ROW_FRACTIONS = listOf("½", "⅓", "¼", "⅕", "⅙", "⅐", "⅛")

/** 数字行这个键长按弹出的菜单：数字键都是 [NUMBER_ROW_FRACTIONS]，自己加的符号、短语没有。 */
internal fun numberRowLongPressItems(key: String): List<String>? =
    NUMBER_ROW_FRACTIONS.takeIf { numberRowShiftSymbol(key) != null }

/**
 * 数字行的一排键：点按把键上写的原样上屏（[onInput]，和字母键上角标的数字同一条路，不按序号选候选）；
 * Shift 切换到大写模式时，数字键切换为上方对应的特殊符号（[numberRowKeyText]）；
 * 打着中文时先上屏高亮的候选再接数字（11-21，见 ImeTextCommit.literalSubmission）。
 * 键上不画角标（用户 11-15）；数字键长按弹出 [numberRowLongPressItems]，挪到哪格松手输入哪格，
 * 按多久和「长按输入」设的一样。上滑、下滑照旧输入 [numberRowShiftSymbol]，只是不画出来。
 */
@Composable
internal fun NumberKeyRow(
    keys: List<String>,
    splitAt: Int,
    splitGap: Dp,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    shadowEnabled: Boolean,
    shadowElevation: Dp,
    shadowShapeRadius: Dp,
    onInput: (String) -> Unit,
    onKeyPressDown: ((String) -> Unit)?,
    onKeyRelease: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
    /** 长按菜单和按键气泡画在键盘上（[KeyboardLayout] 的 processSwipeState）；null 时不画。 */
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)? = null,
    /** Shift 处于大写模式（单次/锁定/按住）时，数字键切换为上方对应特殊符号（1→! … 0→)）。 */
    shifted: Boolean = false,
    /** 快速连按早于重组时读取实时 Shift 状态；默认跟随 [shifted]。 */
    isShiftedNow: () -> Boolean = { shifted },
) {
    val longPressMs = LocalKeyboardInputPreferences.current.symbolLongPressMs
    val spans = numberRowSpans(keys.size, splitAt)
    Row(modifier.fillMaxWidth().testTag("number-row")) {
        keys.forEachIndexed { index, key ->
            if (index == splitAt) SplitGapSpacer(NUMBER_ROW_SPLIT_ROW, splitGap)
            val displayText = numberRowKeyText(key, shifted)
            val shift = numberRowShiftSymbol(key)
            val swipeSymbol = if (shifted) key.takeIf { shift != null } else shift
            val commitSwipe: ((String) -> Unit)? = swipeSymbol?.let { symbol -> { _: String -> onInput(symbol) } }
            SwipeableKeyButton(
                text = displayText,
                onClick = { onInput(numberRowKeyText(key, isShiftedNow())) },
                onSwipe = commitSwipe,
                onSwipeDown = commitSwipe,
                longPressItems = numberRowLongPressItems(key),
                onLongPressSelect = onInput,
                longPressTimeoutMillis = longPressMs,
                onSwipeStateChange = onSwipeStateChange,
                backgroundColor = keyBackgroundColor,
                textColor = keyTextColor,
                modifier = Modifier.weight(spans[index]).testTag("number-row-key:$index"),
                onPress = { onKeyPressDown?.invoke(key) },
                onRelease = { onKeyRelease?.invoke(key) },
                shadowEnabled = shadowEnabled,
                shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius,
            )
        }
    }
}
