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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.util.SubcharHelper
import com.kingzcheung.xime.service.CandidateState
import com.kingzcheung.xime.service.usesT9CandidateNavigation
import com.kingzcheung.xime.settings.InputProfile

/**
 * 笔画键盘布局 — 与 [T9KeyboardLayout] 同一套排布（用户 11-05：第 2 层数字要有 9 和 0，空格旁不要「.」）。
 *
 * 布局说明：
 * - 左侧符号列：和九键侧栏同一套（，。？！… 一屏四格，可上下滑，末尾「编辑」）；下面 !@#。
 * - 中间主键区（上滑出角标数字 1-9）：
 *   第1行：一(h)1 | 丨(s)2 | 丿(p)3
 *   第2行：丶(n)4 | 乛(z)5 | *6
 *   第3行：分词7 | ，8 | 。9
 *   第4行：123 | 空格 | 语言键（同九键）
 * - 右侧功能键区：退格 | 重输 | 0（打字时是「下一项」）| 确定（同九键）
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
    /** 打着笔画或给着联想时 0 键是「下一项」（同九键，11-18）；不传时 0 键只打 0。 */
    candidateState: State<CandidateState>? = null,
    callbacks: KeyboardCallbacks? = null,
    inputProfile: InputProfile? = null,
    /** 回车键的动作名（[EnterKeyAction.label]）；键面照旧写「确定」，只用来判断长按回车换行（#418）。 */
    enterKeyText: String = "确定",
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
        candidateState = candidateState,
        callbacks = callbacks,
        inputProfile = inputProfile,
        enterKeyText = enterKeyText,
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
    candidateState: State<CandidateState>? = null,
    callbacks: KeyboardCallbacks? = null,
    inputProfile: InputProfile? = null,
    enterKeyText: String = "确定",
) {
    val configuration = LocalConfiguration.current
    val isLandscape = !isFloatingMode && configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    val swipeBubble = rememberSwipeBubbleController()
    var keyboardBounds by remember { mutableStateOf(Rect(0f, 0f, 0f, 0f)) }
    val liveKeyboardBounds = remember { LiveBounds() }
    val longPressMenuKeyboard: () -> Rect = remember(liveKeyboardBounds) { { liveKeyboardBounds.current(keyboardBounds, "keyboard") } }

    val isDarkTheme = keyTextColor == Color(0xFFE8EAED)


    fun processSwipeState(state: SwipeState, bounds: Rect) {
        // 按键和键盘都按这一刻的位置算，见 [LiveBounds]。
        val origin = liveKeyboardBounds.current(keyboardBounds, "keyboard")
        val newState = if (state.isSwipeDown && state.swipeText != null) {
            state.copy(charInfos = SubcharHelper.parseSwipeDownText(state.swipeText))
        } else {
            state
        }
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

    // 键缝动效的光层（默认关，关着时是 null）。
    val gapLight = rememberKeyGapLight()

    CompositionLocalProvider(LocalKeyCornerRadius provides keyCornerRadius, LocalLongPressMenuKeyboard provides longPressMenuKeyboard,
        LocalKeyGapLight provides gapLight) {
    Box(
        modifier = modifier
            .onGloballyPositioned { coordinates ->
                keyboardBounds = coordinates.boundsInRoot()
                liveKeyboardBounds.coordinates = coordinates
            }
            .padding(bottom = if (isFloatingMode || isLandscape) 0.dp else 0.dp),
    ) {
        // 键缝动效的光层：最先画 = 在所有键帽底下，只从键缝里露出来。
        KeyGapLightLayer(gapLight, Modifier.matchParentSize())
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
                        candidateState = candidateState,
                        callbacks = callbacks,
                        inputProfile = inputProfile,
                        enterKeyText = enterKeyText,
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
                        candidateState = candidateState,
                        callbacks = callbacks,
                        inputProfile = inputProfile,
                        enterKeyText = enterKeyText,
                    )
                }
            }
        }
    }
    }
}

// ─── 笔画键盘三列主体 ──────────────────────────────────────────────

/**
 * 笔画主键区的一个位置。
 * - [id] 固定，就是默认的键面字：xime.yaml 的 keyboard.stroke.keys 按它配手势。键面字改了 id 也不变，原来的上下滑配置照旧对得上。
 * - [swipeDigit] 跟着位置走：右上角的数字和默认上滑打的数字都不随键面字换（用户 11-17 转来：对调后 6、7 叠在一起）。
 * - [commit] 是默认点按送出的值；keys.<id>.tap 配了就换成配的，见 [strokeTapFor]。
 */
internal data class StrokeKeyDef(
    val id: String,
    val swipeDigit: String,
    val commit: String,
) {
    /** 默认键面字。 */
    val mainLabel: String get() = id
}

/** 笔画键的滑动配置：回调 + 提示文本（keyboard.stroke.keys 配置，无配置时回退内置默认）。 */
private data class StrokeKeySwipes(
    val symbolInputText: String? = null,
    /** 下滑直接上屏的符号：「上下滑输入符号」关着时这一滑不输入、键上不画（用户 11-16）。 */
    val symbolInputDownText: String? = null,
    val onSwipeUp: (() -> Unit)? = null,
    val onSwipeDown: (() -> Unit)? = null,
    val swipeUpText: String? = null,
    val swipeDownText: String? = null,
    /** 上滑键面提示（空串 = 显式不印键面，bubble 模式用；null = 回退 swipeText） */
    val swipeUpKeyLabel: String? = null,
    val swipeDownKeyLabel: String? = null,
)

/**
 * 主键区九个位置，从左到右、从上到下。默认排法同各家笔画键盘：第二行末尾是 *，第三行开头是分词
 * （11-16 合进来的对调撤回：贡献者 11-17 说默认不该为少数人改，想对调的自己用 keys.<id>.tap 配）。
 */
internal val strokeMainKeys = listOf(
    StrokeKeyDef("一", "1", "h"),
    StrokeKeyDef("丨", "2", "s"),
    StrokeKeyDef("丿", "3", "p"),
    StrokeKeyDef("丶", "4", "n"),
    StrokeKeyDef("乛", "5", "z"),
    StrokeKeyDef("*", "6", "*"),
    StrokeKeyDef("分词", "7", "word_separator"),
    StrokeKeyDef("，", "8", "，"),
    StrokeKeyDef("。", "9", "。"),
)

/** 五个笔画键（一 丨 丿 丶 乛）。 */
internal val strokeKeys = strokeMainKeys.take(5)

/** 一个位置点按时显示什么、做什么：[label] 是键面字，[action]、[value] 是点下去的功能。 */
internal data class StrokeTap(val label: String, val action: GestureAction?, val value: String)

/**
 * keys.<id>.tap：键面字和送出值成对配。
 * - 没配 tap 时用默认（键面字 = id，送出 [StrokeKeyDef.commit]）。
 * - 只写了 value 没写 label 时，value 是某个位置的默认送出值（h、word_separator、* 等）就显示那个位置的字，
 *   免得对调以后键面还是原来的字、和功能对不上；别的 value 原样显示。
 * - 只写了 label 时送出 label。
 * 送出值和内置的一样走 onKeyPress（h/s/p/n/z 是笔画，word_separator 是分词），action 不是 commit 时走手势动作。
 */
internal fun strokeTapFor(def: StrokeKeyDef, tap: GestureDef?): StrokeTap {
    if (tap == null || (tap.label.isEmpty() && tap.value.isEmpty() && tap.action == GestureAction.COMMIT))
        return StrokeTap(def.id, GestureAction.COMMIT, def.commit)
    val value = tap.value.ifEmpty { tap.label }
    val label = tap.label.ifEmpty { strokeMainKeys.firstOrNull { it.commit == value }?.id ?: value }.ifEmpty { def.id }
    return StrokeTap(label, tap.action, value)
}

/** 点按送进引擎的值：五个笔画、通配 *、分词、数字和字母；别的（，。等符号）按符号上屏，见 [StrokeKeyboardContent]。 */
internal fun strokeTapIsLiteral(value: String): Boolean =
    value != "word_separator" && value != "*" && value.isNotEmpty() && value.none { it.isLetterOrDigit() || it == '_' }

/** 主键区这一刻的键面字（设置页画键盘草图用）。 */
internal fun strokeMainLabels(): List<String> =
    strokeMainKeys.map { strokeTapFor(it, KeysConfigHelper.getStrokeKeyGesture(it.id)?.tap).label }

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
    candidateState: State<CandidateState>? = null,
    callbacks: KeyboardCallbacks? = null,
    inputProfile: InputProfile? = null,
    enterKeyText: String = "确定",
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
            symbolInputDownText = gesture.swipeDown?.let { symbolInputValue(it.value.ifEmpty { it.label }, it.action) },
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
            modifier = Modifier.fillMaxHeight().weight(NineGridFrame.SIDE),
            verticalArrangement = Arrangement.Top
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(NineGridFrame.SIDE_PANEL_ROWS)
                    .padding(scaledKeyVisualPadding())
                    // 键缝动效：左栏不是键，按它不画；登记位置是为了线能绕着它走。
                    .then(keyGapLightSource(GapLightKind.NONE))
                    .then(symbolPanelShadowModifier)
                    .clip(RoundedCornerShape(LocalKeyCornerRadius.current))
                    .background(frostedKeyColor(keyBackgroundColor, keyTextColor, frostedGlass))
            ) {
                // 和九键左栏一样：一屏四格，多出的上下滑，右缘有滚动条；末尾一格「编辑」打开「按键符号 → 笔画侧栏」（11-18 贡献者）。
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val rowHeight = maxHeight / NineGridFrame.SIDE_VISIBLE_SLOTS
                    val railState = androidx.compose.foundation.lazy.rememberLazyListState()
                    LazyColumn(
                        state = railState,
                        modifier = Modifier.fillMaxSize().testTag("stroke-side-symbols")
                            .railScrollIndicator(railState, keyTextColor.copy(alpha = 0.35f)),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        itemsIndexed(strokeSideSymbols) { index, symbol ->
                            StrokeSymbolItem(
                                text = symbol,
                                preserveWidth = customSymbols != null,
                                onClick = { commitSymbol(symbol) },
                                onPress = { onKeyPressDown?.invoke(symbol) },
                                backgroundColor = keyBackgroundColor,
                                textColor = keyTextColor,
                                fontSize = symbolFontSize,
                                modifier = Modifier.fillMaxWidth().height(rowHeight).testTag("stroke-side-symbol:$index")
                            )
                        }
                        item(key = "edit") {
                            SideRailEditItem(SideSymbolTarget.STROKE, keyTextColor, keyBackgroundColor,
                                iconSize = (symbolFontSize.value * 1.1f).dp, onPress = { onKeyPressDown?.invoke("edit") },
                                modifier = Modifier.fillMaxWidth().height(rowHeight).testTag("stroke-side-symbols-edit"))
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
            modifier = Modifier.fillMaxHeight().weight(NineGridFrame.MAIN),
        ) {
            // 九个位置三排：前五个是笔画键，后四个是 * 分词 ， 。；点按和键面字按 keys.<id>.tap，角标数字、上下滑按位置。
            strokeMainKeys.chunked(3).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    row.forEach { key ->
                        val tap = strokeTapFor(key, KeysConfigHelper.getStrokeKeyGesture(key.id)?.tap)
                        val onClick: () -> Unit = {
                            when (val action = tap.action) {
                                // ，。这类符号走和左栏一样的上屏路：打着笔画时先上屏高亮的候选再接符号（11-18 贡献者），
                                // 不再越过还没选的笔画先把符号打出去。笔画、* 、分词、数字照旧送进引擎。
                                GestureAction.COMMIT -> if (strokeTapIsLiteral(tap.value))
                                    (inputActions.onCommitText ?: onKeyPress)(tap.value) else onKeyPress(tap.value)
                                null, GestureAction.NONE -> Unit
                                else -> onGestureAction?.invoke(action, tap.value)
                            }
                        }
                        val onPress: () -> Unit = { onKeyPressDown?.invoke(tap.value) }
                        if (key in strokeKeys) StrokeKeyItem(
                            mainLabel = tap.label,
                            swipeDigit = key.swipeDigit,
                            onClick = onClick,
                            onPress = onPress,
                            backgroundColor = keyBackgroundColor,
                            textColor = keyTextColor,
                            modifier = Modifier.weight(1f).testTag("stroke-key:${key.id}"),
                            shadowEnabled = shadowEnabled,
                            shadowElevation = shadowElevation,
                            shadowShapeRadius = shadowShapeRadius,
                            strokeFontSize = strokeFontSize,
                            compactMode = compactMode,
                            onSwipeStateChange = onSwipeStateChange,
                            swipes = swipesFor(key.id, key.swipeDigit),
                        ) else StrokeDigitKey(
                            digit = tap.label, swipeDigit = key.swipeDigit,
                            onClick = onClick,
                            onPress = onPress,
                            backgroundColor = keyBackgroundColor,
                            textColor = keyTextColor,
                            modifier = Modifier.weight(1f).testTag("stroke-key:${key.id}"),
                            shadowEnabled = shadowEnabled,
                            shadowElevation = shadowElevation,
                            shadowShapeRadius = shadowShapeRadius,
                            fontSize = strokeFontSize,
                            onSwipeStateChange = onSwipeStateChange,
                            swipes = swipesFor(key.id, key.swipeDigit),
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                val languageOnLeft = LocalKeyboardInputPreferences.current.languageKeyOnLeft
                @Composable fun LanguageKey() {
                    LanguageKeyButton(
                        icon = rememberVectorPainter(Icons.Default.Language),
                        onClick = { onKeyPress("ime_switch") },
                        backgroundColor = specialKeyBackgroundColor, iconColor = specialKeyTextColor,
                        modifier = Modifier.weight(NineGridFrame.BOTTOM_LANGUAGE),
                        onPress = { onKeyPressDown?.invoke("ime_switch") },
                        shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius,
                    )
                }
                if (!LocalKeyboardInputPreferences.current.symbolKeyCycle) KeyboardModeKey(
                    slot = 2, onKeyPress = onKeyPress,
                    backgroundColor = specialKeyBackgroundColor, textColor = specialKeyTextColor,
                    modifier = Modifier.weight(NineGridFrame.BOTTOM_MODE), onKeyPressDown = onKeyPressDown,
                    shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius,
                )
                if (languageOnLeft) LanguageKey()
                StrokeSpaceButton(
                    onKeyPress = onKeyPress,
                    onKeyPressDown = onKeyPressDown,
                    backgroundColor = keyBackgroundColor,
                    textColor = keyTextColor,
                    modifier = Modifier.weight(NineGridFrame.spaceWeight(LocalKeyboardInputPreferences.current.symbolKeyCycle)),
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                )
                if (!languageOnLeft) LanguageKey()
            }
        }

        // ── 第3列：功能键区 ──
        // 「笔画去掉 0 键，回车加高」（用户 11-17 转来，默认关）：和九键同款，0 键没了、回车占两格，0 在回车上往角标方向滑。
        val mergeZero = LocalKeyboardInputPreferences.current.strokeMergeZeroIntoEnter
        Column(
            modifier = Modifier.fillMaxHeight().weight(NineGridFrame.ACTION).testTag("stroke-right-rail"),
        ) {
            SwipeableIconKeyButton(
                icon = rememberVectorPainter(Icons.AutoMirrored.Filled.Backspace),
                onClick = { onKeyPress("delete") },
                onLongClick = { onKeyPress("delete") },
                backgroundColor = specialKeyBackgroundColor,
                iconColor = specialKeyTextColor,
                modifier = Modifier.weight(1f).testTag("stroke-delete-key"),
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
            // 0 与九键同位（重输下面）。打着笔画、或刚上屏给着联想时是「下一项」（11-18 贡献者：做成和九键一样）：
            // 点一下、右滑标亮下一个候选，左滑回上一个，空格和标点上屏标亮的那个；其余时候点按打 0，和 1-9 的上滑数字走同一条路。
            // 只在「是不是下一项」变化时重组这一键，每打一笔只重组候选栏。
            val nextCandidate by remember(candidateState, inputProfile) {
                androidx.compose.runtime.derivedStateOf {
                    val state = candidateState?.value
                    state != null && inputProfile != null && callbacks != null && state.usesT9CandidateNavigation(inputProfile)
                }
            }
            if (!mergeZero) NineKeyButton(
                digit = "", letters = if (nextCandidate) "下一项" else "0",
                icon = if (nextCandidate) rememberVectorPainter(Icons.AutoMirrored.Filled.KeyboardArrowRight) else null,
                iconSizeDp = T9NextCandidateIconDp,
                onClick = {
                    val state = candidateState?.value
                    if (nextCandidate && state != null && callbacks != null) {
                        callbacks.onDismissPreeditEditor?.invoke()
                        callbacks.onNextCandidate(state)
                    } else onKeyPress("0")
                },
                onSwipeLeft = if (nextCandidate) ({
                    candidateState?.value?.let { state ->
                        callbacks?.onDismissPreeditEditor?.invoke()
                        callbacks?.onPreviousCandidate?.invoke(state)
                    }
                }) else null,
                onSwipeRight = if (nextCandidate) ({
                    candidateState?.value?.let { state ->
                        callbacks?.onDismissPreeditEditor?.invoke()
                        callbacks?.onNextCandidate?.invoke(state)
                    }
                }) else null,
                backgroundColor = keyBackgroundColor, textColor = keyTextColor,
                modifier = Modifier.weight(1f).testTag("stroke-zero-key"),
                onPress = { onKeyPressDown?.invoke("0") },
                onSwipeStateChange = onSwipeStateChange,
                shadowEnabled = shadowEnabled, shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius,
            )
            val cornerSymbolInput = LocalKeyboardInputPreferences.current.cornerSymbolInput
            if (mergeZero) T9MergedEnterKey(
                text = "确定", onClick = { onKeyPress("enter") },
                // 往角标方向滑打 0，和 1～9 的上滑数字同一条路；关了「上下滑输入符号」就不接，键上也不画 0。
                // 和九键一样，打着笔画时不接（那时原来的 0 键是「下一项」）。
                onZero = if (cornerSymbolInput && !nextCandidate) ({ onKeyPress("0") }) else null,
                showZeroHint = hintsActive && swipeHints.up && cornerSymbolInput,
                backgroundColor = specialKeyBackgroundColor, textColor = specialKeyTextColor,
                modifier = Modifier.weight(2f).testTag("stroke-enter-key"),
                onPress = { onKeyPressDown?.invoke("enter") },
                onSwipeStateChange = onSwipeStateChange,
                shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius,
            ) else ActionKeyButton(
                text = "确定",
                onClick = { onKeyPress("enter") },
                // 「回车发送」开着、回车会发送时，长按回车换行，和 26 键、九键一样（#418 笔画用户在微信里换不了行）。
                onLongClick = enterLongPressNewline(enterKeyText) {
                    onGestureAction?.invoke(GestureAction.COMMAND, com.kingzcheung.xime.keyboard.CtrlShortcuts.SHIFT_ENTER_COMMAND)
                },
                backgroundColor = specialKeyBackgroundColor,
                textColor = specialKeyTextColor,
                modifier = Modifier.weight(1f).testTag("stroke-enter-key"),
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
    // 和字母键一样松手也给「按键抬起震动」（用户 11-23：九键抬起震动无效）。
    val currentOnRelease by rememberUpdatedState(keyReleaseOrShared(null, onPress != null))
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
                    currentOnRelease?.invoke()
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
        symbolInputDownText = swipes.symbolInputDownText,
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
        symbolInputDownText = swipes.symbolInputDownText,
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
