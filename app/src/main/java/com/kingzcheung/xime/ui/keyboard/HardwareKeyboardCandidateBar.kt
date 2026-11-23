package com.kingzcheung.xime.ui.keyboard

import android.graphics.Rect
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import com.kingzcheung.xime.settings.SettingsPreferences
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlin.math.ceil
import kotlin.math.floor
import com.kingzcheung.xime.service.HardwareCursorAnchor

private const val MAX_VISIBLE_CANDIDATES = com.kingzcheung.xime.service.HARDWARE_PAGE_SIZE
internal val HardwareCandidateCornerRadius = 8.dp

@Composable
fun HardwareKeyboardCandidateBar(
    inputText: String,
    preeditText: String,
    candidates: List<String>,
    hasNextPage: Boolean,
    hasPrevPage: Boolean,
    cursorAnchor: HardwareCursorAnchor?,
    highlightIndex: Int,
    cardBackgroundColor: Color,
    candidateTextColor: Color,
    activeColor: Color,
    selectedTextColor: Color = activeColor,
    onCandidateSelect: ((Int) -> Unit)? = null,
    onBoundsChanged: (Rect?) -> Unit = {},
    avoidBoundsInWindow: Rect? = null,
    preeditBackgroundColor: Color = cardBackgroundColor,
    comments: List<String> = emptyList(),
    onPrevious: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
    surface: (@Composable () -> Modifier)? = null,
    bottomDocked: Boolean = false,
    onPreeditBoundsChanged: (Rect?) -> Unit = {},
    showNumberLabels: Boolean = true,
    showPreedit: Boolean = true,
    frostedGlass: com.kingzcheung.xime.settings.FrostedGlassConfig = com.kingzcheung.xime.settings.FrostedGlassConfig(),
    /** 「自动避开输入区域」开启时传入光标位置：浮卡盖住这一行就上下挪开。与 [cursorAnchor] 跟随无关。 */
    avoidCaret: HardwareCursorAnchor? = null,
    commentsAreGlosses: Boolean = false,
    /** 候选行画了哪些、完整显示了几个（翻页按这个数走）。 */
    onShown: ((List<String>, Int) -> Unit)? = null,
    /**
     * 浮窗固定在这一边：工具条不动；卡片照样跟到正在打字的那一行旁边（[cursorAnchor]），
     * 不知道光标在哪时才贴着工具条放（见 [fixedHardwareCandidatePosition]）。
     */
    fixedEdge: com.kingzcheung.xime.settings.HardwareToolbarEdge? = null,
    /** 正在打字的光标和输入框（屏幕坐标）：卡片挡住正在打的字时报告，和浮条一起变淡。 */
    typingCaret: HardwareCursorAnchor? = null,
    typingEditor: HardwareCursorAnchor? = null,
    overlapFaded: Boolean = false,
    onCoversTyping: (Boolean) -> Unit = {},
) {
    val reportBounds by rememberUpdatedState(onBoundsChanged)
    val reportPreeditBounds by rememberUpdatedState(onPreeditBoundsChanged)
    val reportCovers by rememberUpdatedState(onCoversTyping)
    // 贴底时拼音画在浮条里候选左边（见 HardwareBarSteady.kt）：浮在浮条上方正好压在 App 的输入框上，
    // 还随每个字母变宽、躲光标行上下跳（用户 11-20）。
    val displayText = if (showPreedit && !bottomDocked) preeditText.ifEmpty { inputText } else ""
    if (displayText.isEmpty() && (candidates.isEmpty() || bottomDocked)) {
        LaunchedEffect(Unit) { reportBounds(null); reportPreeditBounds(null); reportCovers(false) }
        return
    }
    DisposableEffect(Unit) {
        onDispose { reportBounds(null); reportPreeditBounds(null); reportCovers(false) }
    }
    // 一次输入（拼音没清空）里：卡片只往右加宽不缩回，横向位置定在开始打字的地方（用户 11-20，与 Windows 一致）。
    // 以前每个字都按这组候选（注释时有时无）重算宽度、跟着光标横移，卡片一直晃。
    val inputActive = inputText.isNotEmpty() || preeditText.isNotEmpty()
    val heldWidth = remember(inputActive) { IntArray(1) }
    var startAnchor by remember { mutableStateOf<HardwareCursorAnchor?>(null) }
    // 记下这次输入开始后收到的第一个光标位置（光标位置可能晚一拍才到）。
    LaunchedEffect(inputActive, cursorAnchor == null) {
        if (!inputActive) startAnchor = null else if (startAnchor == null) startAnchor = cursorAnchor
    }
    val steadyAnchor = if (inputActive) hardwareSteadyAnchor(cursorAnchor, startAnchor) else cursorAnchor

    val density = LocalDensity.current
    val context = LocalContext.current
    val fontSize = rememberCandidateTextSize().sp
    val measurer = rememberTextMeasurer()
    val themeKey = com.kingzcheung.xime.ui.theme.LocalKeyboardPalette.current?.key ?: cardBackgroundColor
    val shadowRadius = with(density) { 1.dp.toPx() }
    val textSurface = remember(frostedGlass, cardBackgroundColor, themeKey, shadowRadius, candidateTextColor, selectedTextColor, activeColor) {
        if (frostedGlass.enabled) resolveFrostedTextSurface(cardBackgroundColor, candidateTextColor, selectedTextColor, activeColor, frostedGlass, themeKey, shadowRadius) else null
    }
    val visuals = CandidateBarVisuals(cardBackgroundColor, candidateTextColor, Color.Transparent,
        activeColor, selectedTextColor, preeditBackgroundColor = preeditBackgroundColor).withTextSurface(textSurface)
    val showCandidates = !bottomDocked && candidates.isNotEmpty()
    // Like Gboard's floating window: the chevron opens the page as a grid of several rows.
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(inputText.isEmpty() && preeditText.isEmpty()) { if (inputText.isEmpty() && preeditText.isEmpty()) expanded = false }
    LaunchedEffect(showCandidates) { if (!showCandidates) reportBounds(null) }

    LaunchedEffect(displayText) { if (displayText.isEmpty()) reportPreeditBounds(null) }

    val view = LocalView.current
    val viewLoc = remember { IntArray(2) }
    var hostPositionInRoot by remember { mutableStateOf<IntOffset?>(null) }
    var hostPositionInWindow by remember { mutableStateOf<IntOffset?>(null) }
    var cardSize by remember { mutableStateOf(IntSize.Zero) }
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().testTag("hardware-candidate-host").onGloballyPositioned { coordinates ->
            val position = coordinates.positionInRoot()
            hostPositionInRoot = IntOffset(position.x.roundToInt(), position.y.roundToInt())
            val windowPosition = coordinates.positionInWindow()
            hostPositionInWindow = IntOffset(windowPosition.x.roundToInt(), windowPosition.y.roundToInt())
        }
    ) {
        // Use the actual host and measured card, including split-window origins.
        // The insertion marker is already in screen pixels; zero is a valid position.
        val marginPx = with(density) { 8.dp.roundToPx() }
        val maxCardWidth = (maxWidth - 16.dp).coerceIn(1.dp, 560.dp)
        val maxCardHeight = (maxHeight - 16.dp).coerceAtLeast(1.dp)
        view.getLocationOnScreen(viewLoc)
        val origin = (hostPositionInRoot ?: IntOffset.Zero) + IntOffset(viewLoc[0], viewLoc[1])
        val toolbarExclusion = hostPositionInWindow?.let { hostOrigin ->
            avoidBoundsInWindow?.let { excluded ->
                HardwareCandidateExclusion(
                    excluded.left - hostOrigin.x, excluded.top - hostOrigin.y,
                    excluded.right - hostOrigin.x, excluded.bottom - hostOrigin.y,
                )
            }
        }
        val placement = hardwareCandidatePosition(
            constraints.maxWidth, constraints.maxHeight, cardSize.width, cardSize.height,
            steadyAnchor.takeIf { hostPositionInRoot != null && !bottomDocked }, origin.x, origin.y,
            marginPx, with(density) { 8.dp.roundToPx() },
            avoidBounds = toolbarExclusion,
        )
        val shownCount = candidates.size.coerceAtMost(MAX_VISIBLE_CANDIDATES)
        val commentStyle = TextStyle(fontSize = (fontSize.value * 11f / 19f).sp, fontFamily = AppFonts.commentFontFamily)
        val textWidth = candidates.take(MAX_VISIBLE_CANDIDATES).mapIndexed { index, text ->
            val comment = comments.getOrNull(index).orEmpty()
            measurer.measure(AnnotatedString(candidateNumberedText(text, index, showNumberLabels)),
                candidatePrimaryTextStyle(fontSize, AppFonts.candidateFontFamily, index == highlightIndex), softWrap = false).size.width +
                if (comment.isEmpty()) 0 else measurer.measure(AnnotatedString(comment), commentStyle, softWrap = false).size.width +
                    with(density) { 3.dp.roundToPx() }
        }.sum()
        // 卡片按候选自身的宽度定，要把行里真正画出来的都算上：每个候选左右留白 4dp×2 加间距 8dp、注释，
        // 以及展开、上一个、下一个三个 40dp 按钮里这一页实际出现的那几个。以前固定只留两个按钮的 104dp：
        // 翻到第二页多出「上一个」按钮，最后一个候选被挤掉，行报「只放得下 n-1 个」，下一组就只给 n-1 个、
        // 卡片按更少的字再变窄……一路缩到只剩一个（用户 11-18：悬浮状态翻到第二页只有一个候选，特别窄）。
        val buttons = (if (candidates.size > 1) 1 else 0) +
            (if (onPrevious != null && (hasPrevPage || highlightIndex > 0)) 1 else 0) +
            (if (onNext != null && (hasNextPage || highlightIndex < candidates.lastIndex)) 1 else 0)
        val naturalWidth = (with(density) { textWidth.toDp() } + (16 * shownCount + 40 * buttons + 12 + 8).dp)
            .coerceIn(minOf(160.dp, maxCardWidth), maxCardWidth)
        val contentWidth = if (!inputActive) naturalWidth else with(density) {
            heldWidth[0] = hardwareGrowOnlyWidth(heldWidth[0], naturalWidth.roundToPx(), 0, maxCardWidth.roundToPx())
            heldWidth[0].toDp()
        }
        // 卡片没顶到最宽时宽度是按这组候选算出来的，放不下只能是上面算少了：这时不报「放得下几个」，
        // 免得翻页步长跟着卡片一起缩（同上）。顶到最宽才是真放不下，照常报，按它分组。
        val reportFit = onShown.takeIf { contentWidth >= maxCardWidth }
        val dockBounds = if (bottomDocked) hostPositionInWindow?.let { originInWindow ->
            avoidBoundsInWindow?.let { IntOffset(it.left - originInWindow.x, it.top - originInWindow.y) }
        } else null
        // 光标所在行按整行宽避让：只要浮卡压住这一行的任何位置，正在打的字就被挡住。
        val caretLine = avoidCaret?.takeIf { hostPositionInRoot != null && it.top.isFinite() && it.bottom.isFinite() }
            ?.let { HardwareCandidateExclusion(0, (it.top - origin.y).roundToInt(), constraints.maxWidth, (it.bottom - origin.y).roundToInt()) }
        // 固定在某一边只固定工具条：知道光标就跟着正在打字的那一行（用户 11-22：注音固定在右侧时候选浮在屏幕中间，
        // 输入框在底下，眼睛要两边看）；不知道光标、光标不在屏幕里或关了「候选跟随光标」才贴着工具条。
        val followsCaret = hostPositionInRoot != null && hardwareAnchorInHost(steadyAnchor, origin.x, origin.y,
            constraints.maxWidth, constraints.maxHeight)
        val fixedToolbar = toolbarExclusion?.takeIf { fixedEdge != null && dockBounds == null && !followsCaret }
        // 贴着工具条放时也躲开正在打字的那一行（开着「自动避开输入区域」、知道光标而关了跟随时）：只上下挪。
        val (x, y) = avoidHardwareCaretLine(if (fixedEdge != null && fixedToolbar != null) fixedHardwareCandidatePosition(fixedEdge,
            fixedToolbar, cardSize.width, cardSize.height, constraints.maxWidth, constraints.maxHeight, marginPx)
        else HardwareCandidatePosition(
            dockBounds?.x?.coerceIn(marginPx, maxOf(marginPx, constraints.maxWidth - cardSize.width - marginPx)) ?: placement.x,
            dockBounds?.let { (it.y - cardSize.height - marginPx).coerceAtLeast(marginPx) } ?: placement.y),
            cardSize.width, cardSize.height, constraints.maxHeight, caretLine, toolbarExclusion,
            marginPx, with(density) { 4.dp.roundToPx() })
        // 卡片挡住正在打的字：报告给浮条，两者一起淡到 40% 透明（220ms），不挡了再恢复。
        val typingLine = if (hostPositionInRoot == null) null else hardwareTypingLine(
            typingCaret?.inHost(origin.x.toFloat(), origin.y.toFloat()),
            typingEditor?.inHost(origin.x.toFloat(), origin.y.toFloat()), marginPx * 3)
        val coversTyping = cardSize.width > 0 &&
            HardwareCandidateExclusion(x, y, x + cardSize.width, y + cardSize.height).overlaps(typingLine)
        LaunchedEffect(coversTyping) { reportCovers(coversTyping) }
        val overlapAlpha by androidx.compose.animation.core.animateFloatAsState(
            if (overlapFaded || coversTyping) HARDWARE_OVERLAP_ALPHA else 1f,
            androidx.compose.animation.core.tween(HARDWARE_OVERLAP_FADE_MS), label = "hardware-card-overlap")
        // This group has no painted background or pointer handler. Each visible surface reports
        // its own touch region, leaving the gap and the rest of the editor usable.
        Column(Modifier.absoluteOffset { IntOffset(x, y) }
            .graphicsLayer { alpha = overlapAlpha }.widthIn(max = maxCardWidth)
            .heightIn(max = maxCardHeight).onSizeChanged { cardSize = it },
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (displayText.isNotEmpty()) {
                val shape = RoundedCornerShape(4.dp)
                // Let the displayed text and its real padding determine the width. A second
                // text measurement can disagree with font resolution or pixel-rounded padding.
                Box(Modifier.widthIn(min = minOf(24.dp, maxCardWidth), max = maxCardWidth)
                    .clip(shape)
                    .then(surface?.invoke() ?: Modifier.background(preeditBackgroundColor))
                    .testTag("hardware-preedit-card")
                    .onGloballyPositioned { reportPreeditBounds(it.boundsInWindow().toAndroidBounds()) }
                    .glassTextHalo(textSurface?.halo)) {
                    PreeditLabel(displayText, visuals.copy(preeditBackgroundColor = Color.Transparent),
                        followTextTail = true)
                }
            }
            if (showCandidates) {
                val shape = RoundedCornerShape(HardwareCandidateCornerRadius)
                Box(Modifier.width(contentWidth).clip(shape)
                    .then(surface?.invoke() ?: Modifier.background(cardBackgroundColor))
                    .testTag("hardware-candidate-card")
                    .onGloballyPositioned { reportBounds(it.boundsInWindow().toAndroidBounds()) }
                    // 展开、收起和屏幕键盘的候选展开同一套动效：卡片高度按面板时长过渡，内容交叉淡入（用户 10-21）。
                    .animateContentSize(com.kingzcheung.xime.ui.theme.CyMotion.panel(expanded))
                    .padding(horizontal = 6.dp, vertical = 4.dp)
                    .glassTextHalo(textSurface?.halo)) {
                    androidx.compose.animation.Crossfade(expanded,
                        animationSpec = com.kingzcheung.xime.ui.theme.CyMotion.enter(com.kingzcheung.xime.ui.theme.CyMotion.MEDIUM),
                        label = "hardware-candidate-expand") { open ->
                    if (open) HardwareCandidateGrid(candidates.take(MAX_VISIBLE_CANDIDATES), highlightIndex,
                        visuals, onCandidateSelect, onCollapse = { expanded = false })
                    else Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            HardwareCandidateRow(candidates, comments, highlightIndex, hasPrevPage, hasNextPage,
                                visuals, onCandidateSelect, onPrevious, onNext, showNumberLabels, commentsAreGlosses,
                                onShown = reportFit)
                        }
                        if (candidates.size > 1) KeyboardToolbarButton({ expanded = true }, Color.Transparent,
                            modifier = Modifier.testTag("hardware-candidate-expand")) {
                            Icon(Icons.Default.ExpandMore, "展开候选", tint = visuals.textColor)
                        }
                    }
                    }
                }
            }
        }
    }
}

/** Expanded floating candidates: rows of five, each numbered for the digit keys. */
@Composable
private fun HardwareCandidateGrid(
    candidates: List<String>, highlightIndex: Int, visuals: CandidateBarVisuals,
    onCandidateSelect: ((Int) -> Unit)?, onCollapse: () -> Unit,
) {
    val fontSize = rememberCandidateTextSize().sp
    Column(Modifier.testTag("hardware-candidate-grid")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            KeyboardToolbarButton(onCollapse, Color.Transparent, modifier = Modifier.testTag("hardware-candidate-collapse")) {
                Icon(Icons.Default.ExpandLess, "收起候选", tint = visuals.textColor)
            }
        }
        candidates.chunked(5).forEachIndexed { row, words ->
            Row(Modifier.fillMaxWidth()) {
                words.forEachIndexed { column, word ->
                    val index = row * 5 + column
                    val selected = index == highlightIndex
                    Box(Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(6.dp))
                        .background(if (selected) visuals.accentColor.copy(alpha = .18f) else Color.Transparent)
                        .clickable { onCandidateSelect?.invoke(index) }.testTag("hardware-grid-candidate:$index"),
                        contentAlignment = Alignment.Center) {
                        Text(candidateNumberedText(word, index, true), color = if (selected) visuals.selectedTextColor else visuals.textColor,
                            fontSize = fontSize, maxLines = 1, softWrap = false)
                    }
                }
                repeat(5 - words.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private fun androidx.compose.ui.geometry.Rect.toAndroidBounds() = Rect(
    floor(left).toInt(), floor(top).toInt(), ceil(right).toInt(), ceil(bottom).toInt())

/** The exact same single-row renderer is used beside the caret and inside the dock. */
@Composable
internal fun hardwareCandidateRowHeight(): androidx.compose.ui.unit.Dp =
    (candidateItemHeight(SettingsPreferences.getCandidateTextSize(LocalContext.current).sp) + 8.dp)
        .coerceAtLeast(48.dp)

@Composable
internal fun HardwareCandidateRow(
    candidates: List<String>, comments: List<String>, highlightIndex: Int,
    hasPrevPage: Boolean, hasNextPage: Boolean, visuals: CandidateBarVisuals,
    onCandidateSelect: ((Int) -> Unit)?, onPrevious: (() -> Unit)?, onNext: (() -> Unit)?,
    showNumberLabels: Boolean = true,
    commentsAreGlosses: Boolean = false,
    /** 画了哪些候选、其中前几个完整显示（窄工具条放不下 10 个时翻页按它走）。 */
    onShown: ((List<String>, Int) -> Unit)? = null,
) {
    val protectedVisuals = visuals.withTextSurface(LocalFrostedTextSurface.current)
    val context = LocalContext.current
    val shownWords = candidates.take(MAX_VISIBLE_CANDIDATES)
    val reportShown by rememberUpdatedState(onShown)
    Row(verticalAlignment = Alignment.CenterVertically) {
        FixedCandidateStrip(shownWords, comments = comments,
            visuals = protectedVisuals,
            // 只在这一行从第一个排起时报（高亮把这一行往后挪了就是空表，不算）。
            callbacks = CandidateBarCallbacks(onCandidateSelect = { onCandidateSelect?.invoke(it) },
                onVisibleCandidatesChanged = { visible -> if (visible.isNotEmpty()) reportShown?.invoke(shownWords, visible.size) }),
            fontSize = SettingsPreferences.getCandidateTextSize(context).sp,
            highlightIndex = highlightIndex, showNumberLabels = showNumberLabels, itemSpacing = 8.dp,
            commentsAreGlosses = commentsAreGlosses, glossEdgeCounts = true,
            modifier = Modifier.weight(1f).padding(vertical = 4.dp))
        if (candidates.isNotEmpty() && onPrevious != null && (hasPrevPage || highlightIndex > 0)) {
            KeyboardToolbarButton(onPrevious, Color.Transparent,
                modifier = Modifier.testTag("hardware-candidate-previous")) {
                Icon(Icons.Default.ChevronLeft, "上一候选", tint = protectedVisuals.textColor)
            }
        }
        if (candidates.isNotEmpty() && onNext != null && (hasNextPage || highlightIndex < candidates.lastIndex)) {
            KeyboardToolbarButton(onNext, Color.Transparent,
                modifier = Modifier.testTag("hardware-candidate-next")) {
                Icon(Icons.Default.ChevronRight, "下一候选", tint = protectedVisuals.textColor)
            }
        }
    }
}
