package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.ui.theme.KeyShapes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.kingzcheung.xime.keyboard.GestureAction
import com.kingzcheung.xime.settings.DisplayMode
import com.kingzcheung.xime.settings.GestureDef
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.swipeHandlerFor
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.util.SubcharHelper

/**
 * 笔画键盘布局 — 与 [T9KeyboardLayout] 同一套排布（用户 11-05：第 2 层数字要有 9 和 0，空格旁不要「.」）。
 *
 * 布局说明：
 * - 左侧候选区：。？ ！ ~ 4 个常用标点，纵向排列；下面 !@#。
 * - 中间主键区（上滑出角标数字 1-9）：
 *   第1行：一(h)1 | 丨(s)2 | 丿(p)3
 *   第2行：丶(n)4 | 乛(z)5 | *6
 *   第3行：分词7 | ，8 | 。9
 *   第4行：123 | 空格 | 语言键（同九键）
 * - 右侧功能键区：退格 | 重输 | 0 | 确定（同九键）
 */
@Composable
fun StrokeKeyboardLayout(
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
    onGestureAction: ((GestureAction, String) -> Unit)? = null,
) {
    KeyboardKeySpacingScope(modifier, columns = 5f * 3f / 3.4f,
        policy = KeyVisualPolicy.T9, allowShrink = isFloatingMode, applyGutter = true,
        growthSpacing = keySpacingX?.value to keySpacingY?.value) { bodyModifier ->
    StrokeKeyboardSwipeOverlay(
        modifier = bodyModifier,
        keyboardBackgroundColor = keyboardBackgroundColor,
        keyCornerRadius = keyCornerRadius,
        keyTextColor = keyTextColor,
        isFloatingMode = isFloatingMode,
        onKeyPress = onKeyPress,
        keyBackgroundColor = keyBackgroundColor,
        specialKeyBackgroundColor = specialKeyBackgroundColor,
        bubbleBackgroundColor = bubbleBackgroundColor,
        shadowEnabled = shadowEnabled,
        shadowElevation = shadowElevation,
        shadowShapeRadius = shadowShapeRadius,
        onKeyPressDown = onKeyPressDown,
        specialKeyTextColor = specialKeyTextColor,
        keySpacingX = keySpacingX,
        keySpacingY = keySpacingY,
        onGestureAction = onGestureAction,
    )
    }
}

// ─── 滑动气泡覆盖层 ────────────────────────────────────────────────

@Composable
private fun StrokeKeyboardSwipeOverlay(
    modifier: Modifier,
    keyboardBackgroundColor: Color,
    keyCornerRadius: Dp,
    keyTextColor: Color,
    isFloatingMode: Boolean,
    onKeyPress: (String) -> Unit,
    keyBackgroundColor: Color,
    specialKeyBackgroundColor: Color,
    bubbleBackgroundColor: Color = keyBackgroundColor,
    shadowEnabled: Boolean,
    shadowElevation: Dp,
    shadowShapeRadius: Dp,
onKeyPressDown: ((String) -> Unit)?,
    specialKeyTextColor: Color,
    keySpacingX: Dp? = null,
    keySpacingY: Dp? = null,
    onGestureAction: ((GestureAction, String) -> Unit)? = null,
) {
    val configuration = LocalConfiguration.current
    val isLandscape = !isFloatingMode && configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    val swipeBubble = rememberSwipeBubbleController()
    var keyboardBounds by remember { mutableStateOf(Rect(0f, 0f, 0f, 0f)) }

    val isDarkTheme = keyTextColor == Color(0xFFE8EAED)


    fun processSwipeState(state: SwipeState, bounds: Rect) {
        val newState = if (state.isSwipeDown && state.swipeText != null) {
            state.copy(charInfos = SubcharHelper.parseSwipeDownText(state.swipeText))
        } else {
            state
        }
        swipeBubble.update(
            newState,
            Rect(
                left = bounds.left - keyboardBounds.left,
                top = bounds.top - keyboardBounds.top,
                right = bounds.right - keyboardBounds.left,
                bottom = bounds.bottom - keyboardBounds.top
            )
        )
    }

    CompositionLocalProvider(LocalKeyCornerRadius provides keyCornerRadius) {
    Box(
        modifier = modifier
            .onGloballyPositioned { coordinates ->
                keyboardBounds = coordinates.boundsInRoot()
            }
            .padding(bottom = if (isFloatingMode || isLandscape) 0.dp else 0.dp),
    ) {
        KeyboardSwipeBubbleOverlay(swipeBubble, bubbleBackgroundColor, keyTextColor, specialKeyTextColor, keyboardBounds.width)
        if (isLandscape) {
            // 横屏：去除原左侧符号面板（符号走符号键盘），
            // 笔画布局撑满键盘区域（与全键盘横屏同款 50dp 边距）
            CompositionLocalProvider(
                LocalKeyVisualPadding provides PaddingValues(
                    horizontal = keySpacingX ?: 2.dp,
                    vertical = keySpacingY ?: 2.dp,
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 4.dp, end = 4.dp, bottom = KeyboardGridBottomMargin),
                ) {
                    StrokeKeyboardContent(
                        onKeyPress = onKeyPress,
                        keyBackgroundColor = keyBackgroundColor,
                        keyTextColor = keyTextColor,
                        specialKeyBackgroundColor = specialKeyBackgroundColor,
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                        onKeyPressDown = onKeyPressDown,
                        onSwipeStateChange = ::processSwipeState,
                        specialKeyTextColor = specialKeyTextColor,
                        compactMode = true,
                        onGestureAction = onGestureAction,
                    )
                }
            }
        } else {
            CompositionLocalProvider(
                LocalKeyVisualPadding provides PaddingValues(
                    horizontal = keySpacingX ?: 2.dp,
                    vertical = keySpacingY ?: 2.dp,
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 4.dp, end = 4.dp, bottom = KeyboardGridBottomMargin),
                    horizontalArrangement = Arrangement.Start
                ) {
                    StrokeKeyboardContent(
                        onKeyPress = onKeyPress,
                        keyBackgroundColor = keyBackgroundColor,
                        keyTextColor = keyTextColor,
                        specialKeyBackgroundColor = specialKeyBackgroundColor,
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                        onKeyPressDown = onKeyPressDown,
                        onSwipeStateChange = ::processSwipeState,
                        specialKeyTextColor = specialKeyTextColor,
                        compactMode = false,
                        onGestureAction = onGestureAction,
                    )
                }
            }
        }
    }
    }
}

// ─── 笔画键盘三列主体 ──────────────────────────────────────────────

private data class StrokeKeyDef(
    val mainLabel: String,
    val swipeDigit: String,
    val commit: String,
)

/** 笔画键的滑动配置：回调 + 提示文本（keyboard.stroke.keys 配置，无配置时回退内置默认）。 */
private data class StrokeKeySwipes(
    val symbolInputText: String? = null,
    val onSwipeUp: (() -> Unit)? = null,
    val onSwipeDown: (() -> Unit)? = null,
    val swipeUpText: String? = null,
    val swipeDownText: String? = null,
    /** 上滑键面提示（空串 = 显式不印键面，bubble 模式用；null = 回退 swipeText） */
    val swipeUpKeyLabel: String? = null,
    val swipeDownKeyLabel: String? = null,
)

private val strokeKeys = listOf(
    StrokeKeyDef("一", "1", "h"),
    StrokeKeyDef("丨", "2", "s"),
    StrokeKeyDef("丿", "3", "p"),
    StrokeKeyDef("丶", "4", "n"),
    StrokeKeyDef("乛", "5", "z"),
)

@Composable
private fun StrokeKeyboardContent(
    onKeyPress: (String) -> Unit,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    specialKeyBackgroundColor: Color,
    shadowEnabled: Boolean,
    shadowElevation: Dp,
    shadowShapeRadius: Dp,
    onKeyPressDown: ((String) -> Unit)?,
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)?,
    specialKeyTextColor: Color,
    compactMode: Boolean = false,
    onGestureAction: ((GestureAction, String) -> Unit)? = null,
) {
    val strokeFontSize = if (compactMode) 13.sp else 16.sp
    val symbolFontSize = if (compactMode) 11.sp else 13.sp
    val specialCtxTextColor = if (compactMode) specialKeyTextColor
        else (if (keyTextColor == Color(0xFFE8EAED)) Color.White
              else Color(0xFF1A73E8))


    // 笔画键滑动手势（keyboard.stroke.keys，热重载经 configVersion 感知）：
    // 有配置走配置（可覆盖上滑/新增下滑动作），无配置回退内置默认（上滑提交对应数字）。
    // COMMIT 沿用 onKeyPress（保持笔画模式数字的按键路由语义）。
    // 提示开关只控制提示显示；组件内上滑触发只看回调绑定，提示关闭/横屏紧凑时手势仍可用。
    // 上滑键面提示尊重 display: bubble（仅气泡不印键面）。
    val configVersion by KeysConfigHelper.configVersion.collectAsState()
    val swipeHints = rememberSwipeHintsEnabled()
    val hintsActive = !compactMode
    // 左侧快捷符号列来自 xime.yaml keyboard.stroke.side_symbols（可自定义，>4 滚动显示）
    val customSymbols = LocalKeyboardInputPreferences.current.symbolsFor(SideSymbolTarget.STROKE)
    val inputActions = LocalKeyboardInputActions.current
    val commitSymbol = (if (customSymbols != null) inputActions.onCommitExactText else null)
        ?: inputActions.onCommitText ?: onKeyPress
    val strokeSideSymbols = customSymbols
        ?: remember(configVersion) { KeysConfigHelper.getStrokeSideSymbols() }

    // 符号面板统一阴影（与九键左栏候选面板同款样式）
    val density = LocalDensity.current
    val frostedGlass = LocalKeyboardInputPreferences.current.frostedGlass
    val symbolPanelShadowModifier = remember(shadowEnabled, shadowElevation, shadowShapeRadius, KeyShapes.current, density, keyBackgroundColor, frostedGlass.enabled) {
        if (shadowEnabled && !frostedGlass.enabled) {
            val offsetPx = with(density) { shadowElevation.toPx() }
            val cornerPx = with(density) { KeyShapes.current.radius(shadowShapeRadius).toPx() }
            val color = crispShadowColor(keyBackgroundColor)
            Modifier.drawBehind {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(0f, offsetPx),
                    size = size,
                    cornerRadius = CornerRadius(cornerPx)
                )
            }
        } else Modifier
    }

    fun swipesFor(id: String, fallbackDigit: String): StrokeKeySwipes {
        val gesture = KeysConfigHelper.getStrokeKeyGesture(id)
        if (gesture == null || (gesture.swipeUp == null && gesture.swipeDown == null)) {
            // 回退行为与内置默认（display: "key"）一致：仅键面提示，无滑动气泡
            return StrokeKeySwipes(
                symbolInputText = fallbackDigit,
                onSwipeUp = { onKeyPress(fallbackDigit) },
                swipeUpKeyLabel = if (swipeHints.up && hintsActive) fallbackDigit else null,
            )
        }
        fun hint(def: GestureDef?): String? =
            def?.let { it.label.ifEmpty { it.value } }
        // display 三态：key=仅键面提示（无气泡）、bubble=仅滑动气泡、both=键面+气泡。
        // SwipeableKeyButton 键面提示取 swipeUpKeyLabel ?: swipeText（null 回退气泡文本），
        // bubble 模式传空串显式压制键面显示；气泡仅 bubble/both 时传（key 关闭气泡）。
        val swipeUpKeyLabel = when {
            !swipeHints.up || !hintsActive -> null
            gesture.swipeUp?.display == DisplayMode.BUBBLE -> ""
            else -> hint(gesture.swipeUp)
        }
        return StrokeKeySwipes(
            symbolInputText = gesture.swipeUp?.let { symbolInputValue(it.value.ifEmpty { it.label }, it.action) },
            onSwipeUp = swipeHandlerFor(gesture.swipeUp, onKeyPress, onGestureAction),
            onSwipeDown = swipeHandlerFor(gesture.swipeDown, onKeyPress, onGestureAction),
            swipeUpText = if (swipeHints.up && hintsActive &&
                gesture.swipeUp?.display != DisplayMode.KEY) hint(gesture.swipeUp) else null,
            swipeDownText = if (swipeHints.down && hintsActive &&
                gesture.swipeDown?.display != DisplayMode.KEY) hint(gesture.swipeDown) else null,
            swipeUpKeyLabel = swipeUpKeyLabel,
            swipeDownKeyLabel = gesture.swipeDown?.let { def ->
                if (swipeHints.down && hintsActive && def.display != DisplayMode.BUBBLE)
                    def.label.ifEmpty { def.value } else null
            },
        )
    }

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.Start
    ) {
        // ── 第1列：左侧符号区（面板样式与九键左栏对齐：统一圆角/阴影/内边距） ──
        Column(
            modifier = Modifier.fillMaxHeight().weight(0.8f),
            verticalArrangement = Arrangement.Top
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(3f)
                    .padding(scaledKeyVisualPadding())
                    .then(symbolPanelShadowModifier)
                    .clip(RoundedCornerShape(LocalKeyCornerRadius.current))
                    .background(frostedKeyColor(keyBackgroundColor, keyTextColor, frostedGlass))
            ) {
                if (strokeSideSymbols.size <= 4) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        strokeSideSymbols.forEach { symbol ->
                            StrokeSymbolItem(
                                text = symbol,
                                preserveWidth = customSymbols != null,
                                onClick = { commitSymbol(symbol) },
                                onPress = { onKeyPressDown?.invoke(symbol) },
                                backgroundColor = keyBackgroundColor,
                                textColor = keyTextColor,
                                fontSize = symbolFontSize,
                                modifier = Modifier.fillMaxWidth().weight(1f)
                            )
                        }
                    }
                } else {
                    // 超过 4 个滚动显示（对齐九键 side_symbols 体验），条目定高、由面板圆角统一裁切
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        itemsIndexed(strokeSideSymbols) { _, symbol ->
                            StrokeSymbolItem(
                                text = symbol,
                                preserveWidth = customSymbols != null,
                                onClick = { commitSymbol(symbol) },
                                onPress = { onKeyPressDown?.invoke(symbol) },
                                backgroundColor = keyBackgroundColor,
                                textColor = keyTextColor,
                                fontSize = symbolFontSize,
                                modifier = Modifier.fillMaxWidth().height(if (compactMode) 26.dp else 32.dp)
                            )
                        }
                    }
                }
            }
            KeyboardModeKey(
                slot = 1, onKeyPress = onKeyPress,
                backgroundColor = specialKeyBackgroundColor, textColor = specialKeyTextColor,
                modifier = Modifier.fillMaxWidth().weight(1f), onKeyPressDown = onKeyPressDown,
                shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius,
            )
        }

        // ── 第2列：笔画键区 ──
        Column(
            modifier = Modifier.fillMaxHeight().weight(3.4f),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                strokeKeys.take(3).forEach { key ->
                    StrokeKeyItem(
                        mainLabel = key.mainLabel,
                        swipeDigit = key.swipeDigit,
                        onClick = { onKeyPress(key.commit) },
                        onPress = { onKeyPressDown?.invoke(key.commit) },
                        backgroundColor = keyBackgroundColor,
                        textColor = keyTextColor,
                        modifier = Modifier.weight(1f),
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                        strokeFontSize = strokeFontSize,
                        compactMode = compactMode,
                        onSwipeStateChange = onSwipeStateChange,
                        swipes = swipesFor(key.mainLabel, key.swipeDigit),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                strokeKeys.drop(3).forEach { key ->
                    StrokeKeyItem(
                        mainLabel = key.mainLabel,
                        swipeDigit = key.swipeDigit,
                        onClick = { onKeyPress(key.commit) },
                        onPress = { onKeyPressDown?.invoke(key.commit) },
                        backgroundColor = keyBackgroundColor,
                        textColor = keyTextColor,
                        modifier = Modifier.weight(1f),
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                        strokeFontSize = strokeFontSize,
                        compactMode = compactMode,
                        onSwipeStateChange = onSwipeStateChange,
                        swipes = swipesFor(key.mainLabel, key.swipeDigit),
                    )
                }
                StrokeDigitKey(
                    digit = "*", swipeDigit = "6",
                    onClick = { onKeyPress("*") },
                    onPress = { onKeyPressDown?.invoke("*") },
                    backgroundColor = keyBackgroundColor,
                    textColor = keyTextColor,
                    modifier = Modifier.weight(1f),
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                    fontSize = strokeFontSize,
                    onSwipeStateChange = onSwipeStateChange,
                    swipes = swipesFor("*", "6"),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                StrokeDigitKey(
                    digit = "分词", swipeDigit = "7",
                    onClick = { onKeyPress("word_separator") },
                    onPress = { onKeyPressDown?.invoke("word_separator") },
                    backgroundColor = keyBackgroundColor,
                    textColor = keyTextColor,
                    modifier = Modifier.weight(1f),
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                    fontSize = strokeFontSize,
                    onSwipeStateChange = onSwipeStateChange,
                    swipes = swipesFor("分词", "7"),
                )
                StrokeDigitKey(
                    digit = "，", swipeDigit = "8",
                    onClick = { onKeyPress("，") },
                    onPress = { onKeyPressDown?.invoke("，") },
                    backgroundColor = keyBackgroundColor,
                    textColor = keyTextColor,
                    modifier = Modifier.weight(1f),
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                    fontSize = strokeFontSize,
                    onSwipeStateChange = onSwipeStateChange,
                    swipes = swipesFor("，", "8"),
                )
                StrokeDigitKey(
                    digit = "。", swipeDigit = "9",
                    onClick = { onKeyPress("。") },
                    onPress = { onKeyPressDown?.invoke("。") },
                    backgroundColor = keyBackgroundColor,
                    textColor = keyTextColor,
                    modifier = Modifier.weight(1f),
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                    fontSize = strokeFontSize,
                    onSwipeStateChange = onSwipeStateChange,
                    swipes = swipesFor("。", "9"),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                if (!LocalKeyboardInputPreferences.current.symbolKeyCycle) KeyboardModeKey(
                    slot = 2, onKeyPress = onKeyPress,
                    backgroundColor = specialKeyBackgroundColor, textColor = specialKeyTextColor,
                    modifier = Modifier.weight(0.8f), onKeyPressDown = onKeyPressDown,
                    shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius,
                )
                StrokeSpaceButton(
                    onKeyPress = onKeyPress,
                    onKeyPressDown = onKeyPressDown,
                    backgroundColor = keyBackgroundColor,
                    textColor = keyTextColor,
                    modifier = Modifier.weight(if (LocalKeyboardInputPreferences.current.symbolKeyCycle) 2.6f else 1.8f),
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                )
                // 与九键底排同位：原来这里是半角「.」（上游遗留），语言键原在第 3 行占着 9 的位置。
                LanguageKeyButton(
                    icon = rememberVectorPainter(Icons.Default.Language),
                    onClick = { onKeyPress("ime_switch") },
                    backgroundColor = specialKeyBackgroundColor, iconColor = specialKeyTextColor,
                    modifier = Modifier.weight(0.8f),
                    onPress = { onKeyPressDown?.invoke("ime_switch") },
                    shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius,
                )
            }
        }

        // ── 第3列：功能键区 ──
        Column(
            modifier = Modifier.fillMaxHeight().weight(0.8f),
        ) {
            SwipeableIconKeyButton(
                icon = rememberVectorPainter(Icons.AutoMirrored.Filled.Backspace),
                onClick = { onKeyPress("delete") },
                onLongClick = { onKeyPress("delete") },
                backgroundColor = specialKeyBackgroundColor,
                iconColor = specialKeyTextColor,
                modifier = Modifier.weight(1f),
                swipeText = if (compactMode) null else "清空",
                onSwipe = { onKeyPress("clear_composition") },
                onPress = { onKeyPressDown?.invoke("delete") },
                followSymbolSwipeDirection = true,
                upFeedback = KeyFlickFeedback.DELETE,
                swipeUpLabel = "上滑清空",
                downFeedback = KeyFlickFeedback.UNDO,
                swipeDownLabel = "下滑撤回",
                onSwipeUp = { onKeyPress("clear_all") },
                onSwipeDown = { onKeyPress("undo_clear") },
                distanceDelete = true,
                onSwipeStateChange = onSwipeStateChange,
                shadowEnabled = shadowEnabled,
                shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius,
            )
            ResetKey(
                onClick = { onKeyPress("clear_composition") },
                onPress = { onKeyPressDown?.invoke("clear") },
                backgroundColor = specialKeyBackgroundColor,
                textColor = specialKeyTextColor,
                modifier = Modifier.weight(1f),
                shadowEnabled = shadowEnabled,
                shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius,
                compactMode = compactMode,
            )
            // 0 与九键同位（重输下面）；点按和 1-9 的上滑数字走同一条路。
            NineKeyButton(
                digit = "", letters = "0",
                onClick = { onKeyPress("0") },
                backgroundColor = keyBackgroundColor, textColor = keyTextColor,
                modifier = Modifier.weight(1f),
                onPress = { onKeyPressDown?.invoke("0") },
                onSwipeStateChange = onSwipeStateChange,
                shadowEnabled = shadowEnabled, shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius,
            )
            ActionKeyButton(
                text = "确定",
                onClick = { onKeyPress("enter") },
                backgroundColor = specialKeyBackgroundColor,
                textColor = specialKeyTextColor,
                modifier = Modifier.weight(1f),
                onPress = { onKeyPressDown?.invoke("enter") },
                shadowEnabled = shadowEnabled,
                shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius,
                fontSize = androidx.compose.ui.unit.TextUnit.Unspecified,
            )
        }
    }
}

// ─── 子组件 ───────────────────────────────────────────────────────

@Composable
private fun StrokeSymbolItem(
    text: String,
    onClick: () -> Unit,
    onPress: (() -> Unit)?,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 13.sp,
    preserveWidth: Boolean = false,
) {
    var isPressed by remember { mutableStateOf(false) }
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnPress by rememberUpdatedState(onPress)
    // 面板样式与九键左栏（CandidateItem）一致：条目自身透明、按压时垫底层加深，
    // 圆角/阴影由外层面板统一负责
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(if (isPressed) frostedKeyColor(
                backgroundColor, textColor, LocalKeyboardInputPreferences.current.frostedGlass,
                legacyStateColor = backgroundColor.copy(alpha = 0.7f), pressed = true,
            ) else Color.Transparent)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    isPressed = true
                    currentOnPress?.invoke()
                    tryAwaitRelease()
                    isPressed = false
                }, onTap = { currentOnClick() })
            },
        contentAlignment = Alignment.Center
    ) {
        val fittedSize = keyLabelSizeSp(text, fontSize.value, maxWidth.value, maxHeight.value, LocalDensity.current.fontScale)
        Text(
            text = if (preserveWidth) text else punctuationKeyLabel(text),
            color = textColor,
            fontSize = fittedSize.sp,
            lineHeight = (fittedSize * 1.2f).sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1,
            fontFamily = AppFonts.candidateFontFamily
        )
    }
}

@Composable
private fun StrokeKeyItem(
    mainLabel: String,
    swipeDigit: String,
    onClick: () -> Unit,
    onPress: (() -> Unit)?,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    strokeFontSize: androidx.compose.ui.unit.TextUnit = 16.sp,
    compactMode: Boolean = false,
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)? = null,
    swipes: StrokeKeySwipes = StrokeKeySwipes(),
) {
    SwipeableKeyButton(
        text = mainLabel,
        onClick = onClick,
        backgroundColor = backgroundColor,
        textColor = textColor,
        fontSize = strokeFontSize,
        modifier = modifier,
        onPress = onPress,
        onSwipe = swipes.onSwipeUp?.let { handler -> { _: String -> handler() } },
        onSwipeDown = swipes.onSwipeDown?.let { handler -> { _: String -> handler() } },
        onSwipeStateChange = onSwipeStateChange,
        badgeText = swipeDigit,
        swipeText = swipes.swipeUpText,
        symbolInputText = swipes.symbolInputText,
        swipeDownText = swipes.swipeDownText,
        swipeUpKeyLabel = swipes.swipeUpKeyLabel,
        swipeDownKeyLabel = swipes.swipeDownKeyLabel,
        shadowEnabled = shadowEnabled,
        shadowElevation = shadowElevation,
        shadowShapeRadius = shadowShapeRadius,
        longPressItems = null,
    )
}

@Composable
private fun StrokeDigitKey(
    digit: String,
    swipeDigit: String,
    onClick: () -> Unit,
    onPress: (() -> Unit)?,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    fontSize: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)? = null,
    swipes: StrokeKeySwipes = StrokeKeySwipes(),
) {
    SwipeableKeyButton(
        text = digit,
        onClick = onClick,
        backgroundColor = backgroundColor,
        textColor = textColor,
        modifier = modifier,
        onPress = onPress,
        onSwipe = swipes.onSwipeUp?.let { handler -> { _: String -> handler() } },
        onSwipeDown = swipes.onSwipeDown?.let { handler -> { _: String -> handler() } },
        onSwipeStateChange = onSwipeStateChange,
        badgeText = swipeDigit,
        swipeText = swipes.swipeUpText,
        symbolInputText = swipes.symbolInputText,
        swipeDownText = swipes.swipeDownText,
        swipeUpKeyLabel = swipes.swipeUpKeyLabel,
        swipeDownKeyLabel = swipes.swipeDownKeyLabel,
        shadowEnabled = shadowEnabled,
        shadowElevation = shadowElevation,
        shadowShapeRadius = shadowShapeRadius,
    )
}

@Composable
private fun StrokeSpaceButton(
    onKeyPress: (String) -> Unit,
    onKeyPressDown: ((String) -> Unit)?,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
) {

    SpaceKeyButton(onClick = { onKeyPress("space") }, backgroundColor = backgroundColor,
        textColor = textColor, schemaName = "空格", modifier = modifier,
        onPress = { onKeyPressDown?.invoke("space") }, shadowEnabled = shadowEnabled,
        shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius)
}
