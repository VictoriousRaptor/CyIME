package com.kingzcheung.xime.ui.keyboard

import android.graphics.Rect
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.positionOnScreen
import com.kingzcheung.xime.service.HardwareCursorAnchor
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.semantics.selected
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlin.math.floor
import kotlin.math.ceil

/** The full-screen host has no input handler: only this capsule enters the IME touchable region. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun HardwareKeyboardToolbar(
    onShowKeyboard: () -> Unit,
    onEmoji: () -> Unit,
    onClipboard: () -> Unit,
    onSwitchLanguage: () -> Unit,
    backgroundColor: Color,
    contentColor: Color,
    onBoundsChanged: (Rect?) -> Unit,
    position: HardwareToolbarPosition,
    onPositionChange: (HardwareToolbarPosition) -> Unit,
    cursorAnchor: HardwareCursorAnchor? = null,
    editorBounds: HardwareCursorAnchor? = null,
    avoidEditor: Boolean = true,
    dockAtEdge: Boolean = true,
    languageActions: KeyboardInputActions = KeyboardInputActions(),
    surface: (@Composable () -> Modifier)? = null,
    bottomDocked: Boolean = false,
    bottomCandidateContent: (@Composable () -> Unit)? = null,
    showCandidatesWhenFloating: Boolean = false,
    candidatesPending: Boolean = false,
    /** 候选是正在打的字（不是上屏后的联想）：这时表情、剪贴板、语音收进「更多」，位置让给候选。 */
    composing: Boolean = false,
    /** 候选换了（又打了字）就收起「更多」，回到候选。 */
    candidatesRevision: Long = 0L,
    languageLabel: String = "中",
    frostedGlass: com.kingzcheung.xime.settings.FrostedGlassConfig = com.kingzcheung.xime.settings.FrostedGlassConfig(),
    selectedTextColor: Color = contentColor,
    accentColor: Color = contentColor,
    onVoice: (() -> Unit)? = null,
    voiceActive: Boolean = false,
    /** Bottom-docked style: Samsung-like edge-to-edge strip instead of the floating long bar. */
    samsungBottomBar: Boolean = false,
    onHideKeyboard: (() -> Unit)? = null,
    /** 词库加载等状态提示：显示在候选区位置，加载完成后传 null。 */
    statusText: String? = null,
    /** 实体键盘 Caps Lock 状态：开启时在语言键角上显示 ⇪。 */
    capsLock: Boolean = false,
) {
    val themeKey = com.kingzcheung.xime.ui.theme.LocalKeyboardPalette.current?.key ?: backgroundColor
    val shadowRadius = with(LocalDensity.current) { 1.dp.toPx() }
    val textSurface = remember(frostedGlass, backgroundColor, themeKey, shadowRadius, contentColor, selectedTextColor, accentColor) {
        if (frostedGlass.enabled) resolveFrostedTextSurface(backgroundColor, contentColor, selectedTextColor, accentColor, frostedGlass, themeKey, shadowRadius) else null
    }
    val protectedContentColor = textSurface?.text ?: contentColor
    val currentBoundsCallback by rememberUpdatedState(onBoundsChanged)
    val currentPositionCallback by rememberUpdatedState(onPositionChange)
    val currentPosition by rememberUpdatedState(position)
    DisposableEffect(Unit) { onDispose { currentBoundsCallback(null) } }

    var hostScreenOrigin by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { hostScreenOrigin = it.positionOnScreen() },
        contentAlignment = AbsoluteAlignment.TopLeft) {
        val viewportHeight = maxHeight
        val density = LocalDensity.current
        // Keep normal margins when possible; do not shrink a 48dp recovery key merely for margins.
        val usableWidth = if (maxWidth >= 104.dp) maxWidth - 16.dp else maxWidth
        val vertical = hardwareToolbarIsVertical(maxWidth.value, maxHeight.value, position, dockAtEdge)
        val mode = if (vertical) HardwareToolbarMode.FULL else hardwareToolbarMode(usableWidth.value)
        // Current language and keyboard recovery remain direct actions at every width.
        // The whole surface already drags; the narrow form does not need a separate handle.
        val essentialStacked = mode == HardwareToolbarMode.KEYBOARD_ONLY && usableWidth < 96.dp
        val hasHandle = mode != HardwareToolbarMode.KEYBOARD_ONLY && usableWidth >= 88.dp
        val padding = if (hasHandle || (mode == HardwareToolbarMode.KEYBOARD_ONLY && usableWidth >= 104.dp)) 4.dp else 0.dp
        val buttonSize = minOf(48.dp, usableWidth).coerceAtLeast(0.dp)
        val horizontalWidth = when (mode) {
            HardwareToolbarMode.FULL -> if (onVoice != null) 272.dp else 232.dp
            HardwareToolbarMode.COMPACT -> 184.dp
            HardwareToolbarMode.KEYBOARD_ONLY -> if (essentialStacked) buttonSize else buttonSize * 2 + padding * 2
        }
        val showCandidateContent = bottomDocked || showCandidatesWhenFloating
        // A free toolbar only reserves space for its own suggestions. Composition
        // candidates live in the separate caret window. While a replacement request
        // is running, retain the previous width without keeping stale clickable words.
        var reservedCandidateSpace by remember { mutableStateOf(false) }
        val floatingCandidateSpace = showCandidatesWhenFloating &&
            (bottomCandidateContent != null || (candidatesPending && reservedCandidateSpace))
        val showCandidateStrip = hardwareToolbarCanShowCandidates(maxWidth.value, maxHeight.value, position, dockAtEdge) &&
            (bottomDocked || floatingCandidateSpace)
        SideEffect { reservedCandidateSpace = showCandidateStrip }
        val candidateRowHeight = hardwareCandidateRowHeight()
        // 手机竖屏等窄条：只留展开键盘和语言键，表情/剪贴板/语音收进「更多」，宽度让给候选。
        // 正在打字时宽条也这样收：五个图标占掉近 250dp，候选只剩四五个的位置（用户 11-13：打字时图标太多，候选显示不全）。
        // 上屏后的联想照旧（图标都在），不打字时也照旧。
        val hasCandidates = bottomCandidateContent != null
        val compactStrip = showCandidateStrip &&
            ((composing && hasCandidates) || hardwareCandidateStripIsCompact(minOf(720.dp, usableWidth).value))
        var moreExpanded by remember { mutableStateOf(false) }
        LaunchedEffect(compactStrip, hasCandidates, candidatesRevision) {
            if (!compactStrip || hasCandidates) moreExpanded = false
        }
        val samsung = samsungBottomBar && bottomDocked
        val targetWidth = if (samsung) maxWidth
            else if (showCandidateStrip) minOf(720.dp, usableWidth) else if (vertical) 56.dp else horizontalWidth
        val toolbarWidth by animateDpAsState(targetWidth, tween(220), label = "hardware-dock-width")
        val expectedHeight = if (vertical) horizontalWidth
            else if (essentialStacked) buttonSize * 2 + padding * 2
            else if (showCandidateStrip) candidateRowHeight + padding * 2
            else buttonSize + padding * 2 + if (mode == HardwareToolbarMode.COMPACT) 48.dp else 0.dp
        var measuredSize by remember(maxWidth, maxHeight, density) {
            mutableStateOf(with(density) { IntSize(toolbarWidth.roundToPx(), minOf(expectedHeight, maxHeight).roundToPx()) })
        }
        val renderedWidthPx = with(density) { toolbarWidth.roundToPx() }
        val geometry = with(density) {
            // Reserve the incoming row before its size animation reports a new height.
            // Otherwise a bottom-positioned capsule expands below the touchable host.
            val reservedHeight = maxOf(measuredSize.height, expectedHeight.roundToPx()).coerceAtMost(maxHeight.roundToPx())
            hardwareToolbarGeometry(maxWidth.roundToPx(), maxHeight.roundToPx(), renderedWidthPx, reservedHeight, 8.dp.roundToPx())
        }
        val currentGeometry by rememberUpdatedState(geometry)
        var dragging by remember { mutableStateOf<HardwareToolbarOffset?>(null) }
        val shape = if (samsungBottomBar && bottomDocked) androidx.compose.ui.graphics.RectangleShape
            else RoundedCornerShape(HardwareCandidateCornerRadius)
        val languageMenuActive = LocalSuppressCursorMove.current
        val touchSlop = LocalViewConfiguration.current.touchSlop
        val gap = with(density) { 12.dp.roundToPx() }
        val editor = editorBounds?.takeIf {
            (it.right - it.left) * (it.bottom - it.top) < constraints.maxWidth.toFloat() * constraints.maxHeight * 0.6f
        } ?: cursorAnchor?.let { HardwareCursorAnchor(it.left - gap * 3, it.top, it.right + gap * 3, it.bottom) }
        val exclusion = editor?.let { HardwareCandidateExclusion(
            (it.left - hostScreenOrigin.x).roundToInt(), (it.top - hostScreenOrigin.y).roundToInt(),
            (it.right - hostScreenOrigin.x).roundToInt(), (it.bottom - hostScreenOrigin.y).roundToInt()) }
        val resting = if (samsung) HardwareToolbarOffset(0f, (constraints.maxHeight - measuredSize.height).toFloat().coerceAtLeast(0f))
            else if (avoidEditor && !bottomDocked) avoidHardwareEditor(geometry, geometry.offset(position),
            renderedWidthPx, measuredSize.height, exclusion, gap) else geometry.offset(position)
        val animatedOffset by animateOffsetAsState(
            targetValue = (dragging ?: resting).let { Offset(it.x, it.y) },
            animationSpec = if (dragging != null) snap() else tween(220), label = "hardware-dock-position")
        val displayedOffset = dragging?.let { geometry.constrain(it.x, it.y) }
            ?: if (samsung) HardwareToolbarOffset(animatedOffset.x, animatedOffset.y)
            else geometry.constrain(animatedOffset.x, animatedOffset.y)
        val currentResting by rememberUpdatedState(displayedOffset)
        val currentDock by rememberUpdatedState(dockAtEdge)
        var gestureOrigin by remember { mutableStateOf<HardwareToolbarOffset?>(null) }
        // The receiver stays at the DOWN origin while its child follows the finger. It covers
        // the complete capsule, but never the transparent full-screen host.
        Box(Modifier.absoluteOffset {
            val origin = gestureOrigin ?: displayedOffset
            IntOffset(origin.x.roundToInt(), origin.y.roundToInt())
        }.width(toolbarWidth).height(maxOf(expectedHeight, with(density) { measuredSize.height.toDp() }).coerceAtMost(viewportHeight))
            .testTag("hardware-toolbar-drag")
            .semantics {
                contentDescription = "移动输入工具条"
                customActions = listOf(
                    CustomAccessibilityAction("靠左") { currentPositionCallback(currentPosition.copy(xFraction = 0f, yFraction = 0.5f)); true },
                    CustomAccessibilityAction("居中") { currentPositionCallback(currentPosition.copy(xFraction = 0.5f)); true },
                    CustomAccessibilityAction("靠右") { currentPositionCallback(currentPosition.copy(xFraction = 1f, yFraction = 0.5f)); true },
                    CustomAccessibilityAction("停靠底部") { currentPositionCallback(HardwareToolbarPosition()); true },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val origin = currentResting
                    gestureOrigin = origin
                    var moved = false
                    try {
                        while (true) {
                            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                            val distance = change.position - down.position
                            if (!languageMenuActive.value && !change.isConsumed && (moved || distance.getDistance() > touchSlop)) {
                                moved = true
                                dragging = currentGeometry.constrain(origin.x + distance.x, origin.y + distance.y)
                                change.consume() // cancels child clicks/long-press when this is a drag
                            }
                            if (!change.pressed) {
                                if (moved) currentPositionCallback(currentGeometry.positionAt(
                                    origin.x + distance.x, origin.y + distance.y, currentPosition, snapX = currentDock))
                                break
                            }
                        }
                    } finally { dragging = null; gestureOrigin = null }
                }
            }) {
        // Language is the physical rightmost action even when the host uses RTL layout.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, LocalFrostedTextSurface provides textSurface) {
        Column(
            Modifier.absoluteOffset {
                val origin = gestureOrigin ?: displayedOffset
                val offset = displayedOffset
                IntOffset((offset.x - origin.x).roundToInt(), (offset.y - origin.y).roundToInt())
            }
                .width(toolbarWidth).heightIn(max = viewportHeight)
                .onSizeChanged { measuredSize = it }
                .animateContentSize(tween(220))
                .onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInWindow()
                    currentBoundsCallback(Rect(floor(bounds.left).toInt(), floor(bounds.top).toInt(), ceil(bounds.right).toInt(), ceil(bounds.bottom).toInt()))
                }
                .clip(shape).then(surface?.invoke() ?: Modifier.background(backgroundColor))
                .testTag("hardware-keyboard-toolbar").padding(padding)
                .glassTextHalo(textSurface?.halo),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val auxiliaryActions: @Composable () -> Unit = {
                HardwareToolbarAction(Icons.Default.EmojiEmotions, "表情", "hardware-toolbar-emoji", protectedContentColor,
                    onClick = { moreExpanded = false; onEmoji() })
                HardwareToolbarAction(Icons.Default.ContentPaste, "剪贴板", "hardware-toolbar-clipboard", protectedContentColor,
                    onClick = { moreExpanded = false; onClipboard() })
                onVoice?.let { voice ->
                    HardwareToolbarAction(Icons.Default.Mic, "语音输入", "hardware-toolbar-voice", protectedContentColor,
                        active = voiceActive, onClick = { moreExpanded = false; voice() })
                }
            }
            val actions: @Composable () -> Unit = {
                if (hasHandle) Box(
                    Modifier.width(if (vertical) buttonSize else 32.dp).height(if (vertical) 32.dp else buttonSize),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.DragIndicator, null, tint = protectedContentColor, modifier = Modifier.size(20.dp)) }
                HardwareToolbarAction(Icons.Default.Keyboard, "展开屏幕键盘", "hardware-toolbar-keyboard", protectedContentColor, buttonSize, onClick = onShowKeyboard)
                if (mode == HardwareToolbarMode.FULL) auxiliaryActions()
                HardwareToolbarLanguageKey(protectedContentColor, languageActions, languageLabel, onSwitchLanguage, buttonSize, capsLock, accentColor)
            }
            if (samsung) {
                // Samsung-style docked bar: collapse at the left, the numbered candidates spread
                // across the full width, and the language key at the right.
                Row(Modifier.width((toolbarWidth - padding * 2).coerceAtLeast(0.dp)).height(candidateRowHeight)
                    .testTag("hardware-samsung-bar"), verticalAlignment = Alignment.CenterVertically) {
                    onHideKeyboard?.let { hide ->
                        HardwareToolbarAction(Icons.Default.KeyboardArrowDown, "收起", "hardware-toolbar-hide",
                            protectedContentColor, onClick = hide)
                    }
                    Box(Modifier.weight(1f).testTag("hardware-docked-candidates")) {
                        if (statusText != null) HardwareToolbarStatus(statusText, protectedContentColor)
                        else if (showCandidateContent) bottomCandidateContent?.invoke()
                    }
                    HardwareToolbarAction(Icons.Default.Keyboard, "展开屏幕键盘", "hardware-toolbar-keyboard", protectedContentColor, onClick = onShowKeyboard)
                    HardwareToolbarLanguageKey(protectedContentColor, languageActions, languageLabel, onSwitchLanguage,
                        capsLock = capsLock, capsColor = accentColor)
                }
            } else if (showCandidateStrip) {
                Row(Modifier.width((toolbarWidth - padding * 2).coerceAtLeast(0.dp)).height(candidateRowHeight),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (compactStrip && moreExpanded) Row(Modifier.weight(1f).testTag("hardware-toolbar-more-actions"),
                        horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        auxiliaryActions()
                    } else Box(Modifier.weight(1f).testTag("hardware-docked-candidates")) {
                        if (statusText != null) HardwareToolbarStatus(statusText, protectedContentColor)
                        else if (showCandidateContent) bottomCandidateContent?.invoke()
                    }
                    HardwareToolbarAction(Icons.Default.Keyboard, "展开屏幕键盘", "hardware-toolbar-keyboard", protectedContentColor, onClick = onShowKeyboard)
                    if (compactStrip) HardwareToolbarAction(Icons.Default.MoreHoriz, if (moreExpanded) "收起更多" else "更多",
                        "hardware-toolbar-more", protectedContentColor, size = 40.dp, active = moreExpanded,
                        onClick = { moreExpanded = !moreExpanded })
                    else auxiliaryActions()
                    HardwareToolbarLanguageKey(protectedContentColor, languageActions, languageLabel, onSwitchLanguage,
                        capsLock = capsLock, capsColor = accentColor)
                }
            }
            else if (vertical || essentialStacked) Column(horizontalAlignment = Alignment.CenterHorizontally) { actions() }
            else Row(Modifier.width((toolbarWidth - padding * 2).coerceAtLeast(0.dp)),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                if (statusText != null) Box(Modifier.weight(1f)) { HardwareToolbarStatus(statusText, protectedContentColor) }
                actions()
            }
            if (mode == HardwareToolbarMode.COMPACT) {
                // Narrow windows retain direct access without an overflow toggle or smaller keys.
                Row(
                    Modifier.width((toolbarWidth - padding * 2).coerceAtLeast(0.dp)).height(48.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    auxiliaryActions()
                }
            }
        }
        } // fixed action direction
        } // stationary gesture receiver
        val displayed = displayedOffset
        val dockTarget = geometry.positionAt(displayed.x, displayed.y, position)
        val previewBottom = dockTarget.isBottomDocked(dockAtEdge) && usableWidth >= 320.dp
        val previewSide = !previewBottom && dockAtEdge && hasHandle && maxHeight >= 248.dp &&
            (dockTarget.xFraction == 0f || dockTarget.xFraction == 1f)
        if (dragging != null && (previewBottom || previewSide)) {
            // Preview is a separate drawing-only layer; release performs the animated resize.
            val pw = if (previewBottom) minOf(720.dp, usableWidth) else 56.dp
            val ph = if (previewBottom) candidateRowHeight + 8.dp else horizontalWidth
            val previewGeometry = hardwareToolbarGeometry(constraints.maxWidth, constraints.maxHeight,
                with(density) { pw.roundToPx() }, with(density) { ph.roundToPx() }, with(density) { 8.dp.roundToPx() })
            val preview = previewGeometry.offset(dockTarget)
            Box(Modifier.absoluteOffset { IntOffset(preview.x.roundToInt(), preview.y.roundToInt()) }
                .width(pw).height(ph).border(2.dp, Color(0xFF64B5F6), shape)
                .testTag("hardware-toolbar-dock-preview"))
        }
    }
}

/** Like Gboard's pill: a plain icon in a full-size touch target; the circle shows only while pressed or on. */
@Composable
internal fun HardwareToolbarAction(
    icon: ImageVector,
    description: String,
    tag: String,
    contentColor: Color,
    size: Dp = 48.dp,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        Modifier.size(size).clickable(source, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description; selected = active }.testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(minOf(36.dp, size)).clip(CircleShape)
            .background(contentColor.copy(alpha = if (active) .22f else if (pressed) .14f else 0f)),
            contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = contentColor, modifier = Modifier.size(minOf(20.dp, size)))
        }
    }
}

@Composable
internal fun HardwareToolbarLanguageKey(
    color: Color, actions: KeyboardInputActions, label: String, onClick: () -> Unit, size: Dp = 48.dp,
    capsLock: Boolean = false, capsColor: Color = color,
) {
    androidx.compose.runtime.CompositionLocalProvider(LocalKeyboardInputActions provides actions) {
        Box(Modifier.size(size).semantics(mergeDescendants = true) {
            contentDescription = "当前语言：$label${if (capsLock) "，大写锁定已开启" else ""}，点按切换，长按选择语言"
        }.testTag("hardware-toolbar-language")) {
            LanguageKeyButton(onClick = onClick, backgroundColor = Color.Transparent, textColor = color,
                languageLabel = label, fontSize = 18.sp, shadowEnabled = false, shadowShapeRadius = 24.dp)
            // Caps Lock 指示叠在语言键右上角，不占候选区宽度。
            if (capsLock) androidx.compose.material3.Text("⇪", Modifier.align(Alignment.TopEnd)
                .padding(top = 3.dp, end = 4.dp).testTag("hardware-toolbar-caps"),
                color = capsColor, fontSize = 12.sp, lineHeight = 12.sp)
        }
    }
}

/** 词库加载中：在实体键盘浮条上说明为什么暂时只能打英文。 */
@Composable
private fun HardwareToolbarStatus(text: String, color: Color) {
    Row(Modifier.padding(horizontal = 8.dp).testTag("hardware-toolbar-status"), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.CircularProgressIndicator(Modifier.size(14.dp), color = color, strokeWidth = 2.dp)
        androidx.compose.material3.Text(text, Modifier.padding(start = 8.dp), color = color, fontSize = 13.sp, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

/** 候选长条在此宽度以下（手机竖屏）把表情、剪贴板、语音收进「更多」。 */
internal fun hardwareCandidateStripIsCompact(stripWidthDp: Float): Boolean = stripWidthDp < 560f
