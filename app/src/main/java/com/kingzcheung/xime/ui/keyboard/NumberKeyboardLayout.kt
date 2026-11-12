package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.ui.theme.KeyShapes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.keyboard.KeyboardInputPage
import com.kingzcheung.xime.util.SubcharHelper
import com.kingzcheung.xime.settings.InputProfile
import com.kingzcheung.xime.settings.LayoutKind

/** A keypad's number page inherits the text page's geometry settings. */
internal fun numberKeyboardSpacingSection(profile: InputProfile, ascii: Boolean): String = when {
    !ascii && profile.layout.kind == LayoutKind.KANA_KEYPAD -> "japanese_kana"
    !ascii && profile.layout.kind == LayoutKind.T9 -> "t9"
    else -> "number"
}

/** Numeric keypad; navigation shares the text and symbol pages' mode-key preference. */
@Composable
fun NumberKeyboardLayout(
    onKeyPress: (String) -> Unit,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    specialKeyBackgroundColor: Color,
    bubbleBackgroundColor: Color = keyBackgroundColor,
    keyboardBackgroundColor: Color = Color.Transparent,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    keyCornerRadius: Dp = 8.dp,
    keySpacingX: Dp? = null,
    keySpacingY: Dp? = null,
    modifier: Modifier = Modifier,
    onKeyPressDown: ((String) -> Unit)? = null,
    isFloatingMode: Boolean = false,
    specialKeyTextColor: Color = Color.White,
    isJapaneseKana: Boolean = false,
    /** 回车键动作名（换行、发送、搜索等），与主键盘同一来源，决定回车键图标。 */
    enterKeyText: String = "确定",
) {
    if (isJapaneseKana) {
        CompositionLocalProvider(LocalKeyboardPunctuation provides LocalKeyboardPunctuation.current?.copy(numberPanel = true)) {
            JapaneseKanaKeyboardLayout(
                onKanaAction = { if (it is JapaneseKanaAction.Input) onKeyPress(it.romaji) },
                onKeyPress = onKeyPress,
                keyBackgroundColor = keyBackgroundColor,
                keyTextColor = keyTextColor,
                specialKeyBackgroundColor = specialKeyBackgroundColor,
                specialKeyTextColor = specialKeyTextColor,
                keyboardBackgroundColor = keyboardBackgroundColor,
                modifier = modifier,
                onKeyPressDown = onKeyPressDown,
                shadowEnabled = shadowEnabled,
                shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius,
                keyCornerRadius = keyCornerRadius,
                keySpacingX = keySpacingX,
                keySpacingY = keySpacingY,
                isFloatingMode = isFloatingMode,
                numberMode = true,
                enterKeyText = enterKeyText,
            )
        }
        return
    }
    KeyboardKeySpacingScope(modifier, columns = 5f * 3f / 3.4f,
        policy = KeyVisualPolicy.T9, allowShrink = isFloatingMode, applyGutter = true,
        growthSpacing = keySpacingX?.value to keySpacingY?.value) { bodyModifier ->

    val swipeBubble = rememberSwipeBubbleController()
    var keyboardBounds by remember { mutableStateOf(Rect(0f, 0f, 0f, 0f)) }
    val liveKeyboardBounds = remember { LiveBounds() }

    val isDarkTheme = keyTextColor == Color(0xFFE8EAED)


    fun processSwipeState(state: SwipeState, bounds: Rect) {
        // 按键和键盘都按这一刻的位置算，见 [LiveBounds]。
        val origin = liveKeyboardBounds.current(keyboardBounds, "keyboard")
        val newState = if (state.isSwipeDown && state.swipeText != null) {
            state.copy(charInfos = SubcharHelper.parseSwipeDownText(state.swipeText))
        } else state
        swipeBubble.update(
            newState,
            Rect(
                left = bounds.left - origin.left,
                top = bounds.top - origin.top,
                right = bounds.right - origin.left,
                bottom = bounds.bottom - origin.top
            )
        )
    }

    CompositionLocalProvider(LocalKeyCornerRadius provides keyCornerRadius,
        LocalKeyboardPunctuation provides LocalKeyboardPunctuation.current?.copy(numberPanel = true)) {
    Box(
        modifier = bodyModifier
            .onGloballyPositioned { coordinates ->
                keyboardBounds = coordinates.boundsInRoot()
                liveKeyboardBounds.coordinates = coordinates
            }
    ) {
        KeyboardSwipeBubbleOverlay(swipeBubble, bubbleBackgroundColor, keyTextColor, specialKeyTextColor, keyboardBounds.width)
        run {
            CompositionLocalProvider(
                LocalKeyVisualPadding provides PaddingValues(
                    horizontal = keySpacingX ?: 2.dp,
                    vertical = keySpacingY ?: 2.dp,
                )
            ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(bottom = KeyboardGridBottomMargin),
            ) {
                NumberRows(
                    onKeyPress = onKeyPress,
                    keyBackgroundColor = keyBackgroundColor,
                    keyTextColor = keyTextColor,
                    specialKeyBackgroundColor = specialKeyBackgroundColor,
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                    onKeyPressDown = onKeyPressDown,
                    specialKeyTextColor = specialKeyTextColor,
                    enterKeyText = enterKeyText,
                    onSwipeStateChange = ::processSwipeState
                )
            }
            }
        }

    }
    }
    }
}

@Composable
private fun NumberRows(
    onKeyPress: (String) -> Unit,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    specialKeyBackgroundColor: Color,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    onKeyPressDown: ((String) -> Unit)? = null,
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)? = null,
    compactMode: Boolean = false,
    specialKeyTextColor: Color = Color.White,
    enterKeyText: String = "确定",
) {
    val modeWeight = 0.8f
    val modeCount = com.kingzcheung.xime.keyboard.modeSlotCount(LocalKeyboardInputPreferences.current.symbolKeyCycle)
    val symFontSize = if (compactMode) 14.sp else 18.sp
    val keyFontSize = if (compactMode) 16.sp else androidx.compose.ui.unit.TextUnit.Unspecified
    val ctrlFontSize = if (compactMode) 12.sp else androidx.compose.ui.unit.TextUnit.Unspecified
    // 左栏符号可在「按键符号 → 数字侧栏」改（用户 11-09）；没改过仍是 + - * /。四格一屏，多出的上下滑，末尾一格「编辑」。
    val symbols = SideSymbolTarget.NUMBER.resolve(LocalKeyboardInputPreferences.current.symbolsFor(SideSymbolTarget.NUMBER))
    Column(Modifier.fillMaxSize()) {
        // Numeric navigation uses the operator-column width, independent of the text row.
        Row(Modifier.fillMaxWidth().weight(3f)) {
            NumberSymbolRail(symbols = symbols, onKeyPress = onKeyPress, onKeyPressDown = onKeyPressDown,
                backgroundColor = keyBackgroundColor, textColor = keyTextColor, fontSize = symFontSize,
                shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius,
                modifier = Modifier.weight(0.8f).fillMaxHeight().testTag("number-operators")
                    .padding(scaledKeyVisualPadding()))
            Column(Modifier.weight(3.4f).fillMaxHeight()) {
                listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9")).forEach { row ->
                    Row(Modifier.fillMaxWidth().weight(1f)) {
                        row.forEach { key ->
                            KeyButton(text = key, onClick = { onKeyPress(key) },
                                backgroundColor = keyBackgroundColor, textColor = keyTextColor,
                                onPress = { onKeyPressDown?.invoke(key) }, modifier = Modifier.weight(1f),
                                shadowEnabled = shadowEnabled, shadowElevation = shadowElevation,
                                shadowShapeRadius = shadowShapeRadius, fontSize = keyFontSize)
                        }
                    }
                }
            }
            Column(Modifier.weight(0.8f).fillMaxHeight().testTag("number-actions")) {
                SwipeableIconKeyButton(
                    icon = rememberVectorPainter(Icons.AutoMirrored.Filled.Backspace),
                    onClick = { onKeyPress("delete") }, backgroundColor = specialKeyBackgroundColor,
                    iconColor = specialKeyTextColor, modifier = Modifier.weight(1f).testTag("number-delete-key"),
                    swipeText = "清空", onSwipe = { onKeyPress("clear_composition") },
                    onLongClick = { onKeyPress("delete") }, onPress = { onKeyPressDown?.invoke("delete") },
                    followSymbolSwipeDirection = true,
                    upFeedback = KeyFlickFeedback.DELETE,
                    swipeUpLabel = "上滑清空", downFeedback = KeyFlickFeedback.UNDO, swipeDownLabel = "下滑撤回",
                    onSwipeUp = { onKeyPress("clear_all") }, onSwipeDown = { onKeyPress("undo_clear") },
                    distanceDelete = true,
                    onSwipeStateChange = onSwipeStateChange,
                    shadowEnabled = shadowEnabled, shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius)
                SpaceKeyButton(schemaName = "空格", onClick = { onKeyPress("space") },
                    backgroundColor = keyBackgroundColor, textColor = keyTextColor,
                    modifier = Modifier.weight(1f), onPress = { onKeyPressDown?.invoke("space") },
                    shadowEnabled = shadowEnabled, shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius, fontSize = ctrlFontSize)
                // 表情在工具栏里已有入口；这里放邮箱、@人常用的 @，和小数点一样是直接上屏的字符键。
                KeyButton(text = "@", onClick = { onKeyPress("@") },
                    backgroundColor = keyBackgroundColor, textColor = keyTextColor,
                    modifier = Modifier.weight(1f).testTag("number-at-key"), onPress = { onKeyPressDown?.invoke("@") },
                    shadowEnabled = shadowEnabled, shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius, fontSize = keyFontSize)
            }
        }
        Row(Modifier.fillMaxWidth().weight(1f)) {
            for (slot in 1..modeCount) KeyboardModeKey(slot = slot, page = KeyboardInputPage.NUMBERS,
                onKeyPress = onKeyPress, backgroundColor = specialKeyBackgroundColor,
                textColor = specialKeyTextColor, modifier = Modifier.weight(modeWeight),
                onKeyPressDown = onKeyPressDown, shadowEnabled = shadowEnabled,
                shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius)
            KeyButton(text = "0", onClick = { onKeyPress("0") },
                backgroundColor = keyBackgroundColor, textColor = keyTextColor,
                modifier = Modifier.weight(3.4f - modeCount * modeWeight).testTag("number-zero"), onPress = { onKeyPressDown?.invoke("0") },
                shadowEnabled = shadowEnabled, shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius, fontSize = keyFontSize)
            // 点键上角标「:」（用户 11-09：IP 后面接端口 192.168.1.1:8080）。和空格两侧标点键同一个「底排标点固定上滑」：
            // 开着时上滑或长按输入，不随「反转上下滑方向」变成下滑（键在屏幕底边，往下滑不出去）。
            val bottomSwipeUp = LocalKeyboardInputPreferences.current.bottomPunctuationSwipeUp
            SwipeableKeyButton(text = ".", onClick = { onKeyPress(".") },
                backgroundColor = keyBackgroundColor, textColor = keyTextColor,
                modifier = Modifier.weight(0.8f).testTag("number-dot-key"), onPress = { onKeyPressDown?.invoke(".") },
                swipeText = NUMBER_DOT_ALTERNATE, symbolInputText = NUMBER_DOT_ALTERNATE,
                onSwipe = { onKeyPress(NUMBER_DOT_ALTERNATE) }, onSwipeStateChange = onSwipeStateChange,
                followSymbolSwipeDirection = !bottomSwipeUp, keepSymbolSwipeOnLongPress = bottomSwipeUp,
                shadowEnabled = shadowEnabled, shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius, fontSize = keyFontSize)
            ActionKeyButton(text = enterKeyText, onClick = { onKeyPress("enter") },
                backgroundColor = specialKeyBackgroundColor, textColor = specialKeyTextColor,
                modifier = Modifier.weight(0.8f), onPress = { onKeyPressDown?.invoke("enter") },
                shadowEnabled = shadowEnabled, shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius, fontSize = ctrlFontSize)
        }
    }
}

/** 数字键盘点键的上角标：IP 地址后接端口用。 */
internal const val NUMBER_DOT_ALTERNATE = ":"

/**
 * 数字键盘左栏：整列一个键帽（圆角、阴影画在外框上，和九键左栏一样），一屏四格，
 * 多出的上下滑（右缘滚动条滑动时出现），末尾一格「编辑」打开「按键符号 → 数字侧栏」。
 */
@Composable
private fun NumberSymbolRail(
    symbols: List<String>,
    onKeyPress: (String) -> Unit,
    onKeyPressDown: ((String) -> Unit)?,
    backgroundColor: Color,
    textColor: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    shadowEnabled: Boolean,
    shadowElevation: Dp,
    shadowShapeRadius: Dp,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val frostedGlass = LocalKeyboardInputPreferences.current.frostedGlass
    val shapes = KeyShapes.current
    val shadowModifier = remember(shadowEnabled, shadowElevation, shadowShapeRadius, shapes, density, backgroundColor, frostedGlass.enabled) {
        if (shadowEnabled && !frostedGlass.enabled) {
            val offsetPx = with(density) { shadowElevation.toPx() }
            val cornerPx = with(density) { shapes.radius(shadowShapeRadius).toPx() }
            val color = crispShadowColor(backgroundColor)
            Modifier.drawBehind {
                drawRoundRect(color = color, topLeft = Offset(0f, offsetPx), size = size, cornerRadius = CornerRadius(cornerPx))
            }
        } else Modifier
    }
    BoxWithConstraints(modifier
        .then(shadowModifier)
        .clip(RoundedCornerShape(LocalKeyCornerRadius.current))
        .background(frostedKeyColor(backgroundColor, textColor, frostedGlass))) {
        val rowHeight = maxHeight / 4
        val railState = rememberLazyListState()
        LazyColumn(state = railState, modifier = Modifier.fillMaxSize().testTag("number-symbol-rail")
            .railScrollIndicator(railState, textColor.copy(alpha = 0.35f))) {
            itemsIndexed(symbols) { index, symbol ->
                NumberSymbolKey(text = symbol, onClick = { onKeyPress(symbol) },
                    onPress = { onKeyPressDown?.invoke(symbol) }, backgroundColor = backgroundColor,
                    textColor = textColor, fontSize = fontSize,
                    modifier = Modifier.fillMaxWidth().height(rowHeight).testTag("number-symbol:$index"))
            }
            item(key = "edit") {
                SideRailEditItem(SideSymbolTarget.NUMBER, textColor, backgroundColor,
                    iconSize = (fontSize.value * 1.1f).dp, onPress = { onKeyPressDown?.invoke("edit") },
                    modifier = Modifier.fillMaxWidth().height(rowHeight).testTag("number-symbols-edit"))
            }
        }
    }
}

/** 左栏里的一格：底色由整列画，这里只画按下态和字。 */
@Composable
private fun NumberSymbolKey(
    text: String,
    onClick: () -> Unit,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    onPress: (() -> Unit)? = null,
    fontSize: androidx.compose.ui.unit.TextUnit = 18.sp,
) {
    var isPressed by remember { mutableStateOf(false) }
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnPress by rememberUpdatedState(onPress)
    val density = LocalDensity.current
    val frostedGlass = LocalKeyboardInputPreferences.current.frostedGlass
    BoxWithConstraints(
        modifier = modifier
            .background(if (isPressed) frostedKeyColor(backgroundColor, textColor, frostedGlass,
                legacyStateColor = backgroundColor.copy(alpha = 0.7f), pressed = true) else Color.Transparent)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    isPressed = true
                    currentOnPress?.invoke()
                    tryAwaitRelease()
                    isPressed = false
                }, onTap = { currentOnClick() })
            }, contentAlignment = Alignment.Center
    ) {
        val fittedSize = keyLabelSizeSp(text, fontSize.value, maxWidth.value, maxHeight.value - 4f, density.fontScale)
        Text(
            text = punctuationKeyLabel(text),
            color = textColor,
            fontSize = fittedSize.sp,
            lineHeight = (fittedSize * 1.2f).sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            modifier = Modifier.padding(vertical = 2.dp),
            fontFamily = AppFonts.keyFontFamily
        )
    }
}
