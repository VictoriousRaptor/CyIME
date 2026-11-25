package com.kingzcheung.xime.ui.keyboard

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.kingzcheung.xime.rime.PinyinEditSession
import com.kingzcheung.xime.rime.PinyinEditDisplay

/**
 * 点开拼音编辑时拼音原来在哪：底色块在键盘窗口里的位置（像素）、底色、字号（sp）。
 * 编辑条从这里渐渐长成原来的样子（用户 11-21：小签点开要逐渐变大变长；气泡只要逐渐变长）。
 */
internal data class PreeditOrigin(val bounds: IntRect, val color: Color, val fontSp: Float)

/** 气泡、小签点开编辑前写进来，编辑条打开时读一次；键盘视图提供，没有时编辑条直接出现。 */
internal val LocalPreeditOrigin = compositionLocalOf<MutableState<PreeditOrigin?>?> { null }

/**
 * 点拼音时手指落点对到哪个光标位置（11-23 用户反馈：音节之间的空隙太窄，手指容易点错）：离某个音节分界（隔音符两边、
 * 开头、末尾）不到 [slopPx] 就落在那个分界上，否则照字落点。看得到的间距不变，只是分界两边的可点范围放宽。
 * [isSeparator] 是这段字里的分隔符（编辑条里是隔音符，屏上拼音还有空格、零宽空格）。
 */
internal fun snapPreeditTap(layout: TextLayoutResult, position: Offset, slopPx: Float,
    isSeparator: (Char) -> Boolean): Int {
    val text = layout.layoutInput.text.text
    val exact = layout.getOffsetForPosition(position)
    if (text.isEmpty()) return exact
    val line = layout.getLineForVerticalPosition(position.y)
    val start = layout.getLineStart(line)
    val end = layout.getLineEnd(line)
    var best = exact
    var bestDistance = Float.MAX_VALUE
    for (offset in start..end) {
        val boundary = offset == 0 || offset == text.length ||
            isSeparator(text[offset - 1]) || isSeparator(text[offset])
        if (!boundary) continue
        val distance = kotlin.math.abs(layout.getHorizontalPosition(offset, true) - position.x)
        if (distance < bestDistance) { bestDistance = distance; best = offset }
    }
    return if (bestDistance <= slopPx) best else exact
}

/** 编辑条里音节分界两边各放宽多少（约半个指尖）。 */
internal val PreeditTapSlop = 10.dp

/** 屏上拼音（气泡、小签、整行）里的分隔符。 */
internal fun isShownPreeditSeparator(c: Char): Boolean = c == ' ' || c == '\'' || c == '\u200B'

/**
 * 记下点屏上拼音时手指落在哪个字母后面，打开编辑时光标放在那里（11-23 用户反馈：原来点开光标总在最后）。
 * 外层可点的块用 [record]，里面的字用 [text]；点开时 [lettersBefore] 给出落点前面有几个字母，算不出来是 null（光标照旧）。
 */
internal class PreeditTapPoint {
    private var box: androidx.compose.ui.layout.LayoutCoordinates? = null
    private var textCoordinates: androidx.compose.ui.layout.LayoutCoordinates? = null
    private var textLayout: TextLayoutResult? = null
    private var down: Offset? = null

    /** 外层可点的块：记下它的坐标和手指按下的位置（不吃掉点按，点开照旧由它的 clickable 管）。 */
    fun record(): Modifier = Modifier
        .onGloballyPositioned { box = it }
        .pointerInput(this) {
            awaitEachGesture { down = awaitFirstDown(requireUnconsumed = false).position }
        }

    /** 里面那段字：记下它的坐标；排版结果交给 [layout]。 */
    fun textModifier(): Modifier = Modifier.onGloballyPositioned { textCoordinates = it }

    fun layout(result: TextLayoutResult) { textLayout = result }

    fun lettersBefore(slopPx: Float): Int? {
        val point = down ?: return null
        val outer = box?.takeIf { it.isAttached } ?: return null
        val inner = textCoordinates?.takeIf { it.isAttached } ?: return null
        val result = textLayout ?: return null
        val offset = snapPreeditTap(result, inner.localPositionOf(outer, point), slopPx, ::isShownPreeditSeparator)
        return com.kingzcheung.xime.rime.preeditLettersBefore(result.layoutInput.text.text, offset)
    }
}

/** 编辑条从气泡、小签处长成原样的时长。 */
internal const val PREEDIT_GROW_MS = 180

private fun lerpPx(start: Int, stop: Int, fraction: Float): Int = start + ((stop - start) * fraction).toInt()

/** A non-focusable editing strip above the IME: no replacement keyboard or extra row of keys. */
@Composable
internal fun PreeditEditorBar(
    session: PinyinEditSession,
    keyColor: Color,
    textColor: Color,
    accentColor: Color,
    onClose: () -> Unit,
    onCaret: (Int) -> Unit,
    onReplace: (String) -> Unit,
    preedit: String = session.text,
    inline: Boolean = false,
    onCaretBounds: ((com.kingzcheung.xime.service.HardwareCursorAnchor?) -> Unit)? = null,
    /** 从哪儿长出来（见 [PreeditOrigin]）；null 时直接是原样。 */
    openFrom: PreeditOrigin? = null,
) {
    if (LocalOnBackPressedDispatcherOwner.current != null) BackHandler(onBack = onClose)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val scroll = rememberScrollState()
    val currentCaret by rememberUpdatedState(onCaret)
    val display = remember(session.text, session.isT9, session.protectedText, preedit) { PinyinEditDisplay(session.text, preedit.removePrefix(session.protectedText).trim(), session.isT9, includeAutomaticBoundaries = false) }
    val stroke = remember(session.schemaId) { com.kingzcheung.xime.rime.PinyinEditBuffer.isStrokeCode(session.schemaId) }
    // 笔画编码：组字区显示成笔画时编辑条也显示笔画（一个字母换一个笔画，光标、点按位置不变）。
    val code = (if (stroke) com.kingzcheung.xime.rime.strokeEditorText(display.text,
        preedit.removePrefix(session.protectedText)) else display.text).ifEmpty { " " }
    val displayCaret = display.displayOffset(session.caret)
    LaunchedEffect(session.caret, code, layout) {
        layout?.takeIf { it.layoutInput.text.text == code }?.getCursorRect(displayCaret.coerceIn(0, code.length))?.let {
            if (it.left < scroll.value) scroll.scrollTo(it.left.toInt().coerceAtLeast(0))
            else if (it.right > scroll.value + scroll.viewportSize)
                scroll.scrollTo((it.right - scroll.viewportSize + 16).toInt().coerceAtLeast(0))
        }
    }
    val density = LocalDensity.current
    val gap = with(density) { 2.dp.roundToPx() }
    // 打开这一下从哪儿长出来：只认打开时的那个位置，之后小签、气泡再动也不跟。
    val origin = remember { openFrom.takeIf { !inline } }
    val grow = remember { Animatable(if (origin == null) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (grow.value < 1f) grow.animateTo(1f, tween(PREEDIT_GROW_MS, easing = FastOutSlowInEasing))
    }
    // 浮在应用上时和拼音气泡同一个底色、同一个不透明度，开玻璃时下面同样垫实时模糊（用户 11-16：
    // 气泡是实底、一点拼音编辑条就变透，不统一）。放在键盘内（inline）时仍是原来的键帽色淡底。
    val floatingColor = if (inline) null else floatingPreeditColor(
        com.kingzcheung.xime.ui.theme.LocalMaterialPalette.current?.surface ?: keyColor, keyColor)
    val codeColor = floatingColor?.let { readableThemeText(textColor, it.copy(alpha = 1f)) } ?: textColor.copy(alpha = 0.9f)
    val iconColor = floatingColor?.let { codeColor } ?: textColor
    val halo = remember(codeColor, floatingColor) { floatingPreeditHalo(codeColor, floatingColor) }
    val editorBounds = remember { mutableStateOf<IntRect?>(null) }
    val position = remember(gap, origin) { object : PopupPositionProvider {
        override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
            layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
            // 长大途中：左边、底边从原来那块的位置挪到原样的位置（大小每帧在变，弹窗每帧都会重新算位置）。
            val t = grow.value
            val left = origin?.let { lerpPx(it.bounds.left, anchorBounds.left, t) } ?: anchorBounds.left
            val bottom = origin?.let { lerpPx(it.bounds.bottom, anchorBounds.top - gap, t) } ?: (anchorBounds.top - gap)
            return IntOffset(
            left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            (bottom - popupContentSize.height).coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)))
            .also { offset ->
                val bounds = IntRect(offset, popupContentSize)
                if (editorBounds.value != bounds) editorBounds.value = bounds
            }
        }
    } }
    val content: @Composable (Dp) -> Unit = { width ->
            val caretBoundsModifier = if (onCaretBounds == null) Modifier else {
                val view = LocalView.current
                var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
                fun reportCaret() {
                    val c = coordinates?.takeIf { it.isAttached }
                    val result = layout?.takeIf { it.layoutInput.text.text == code }
                    val anchor = if (c == null || result == null) null else {
                        val rect = result.getCursorRect(displayCaret.coerceIn(0, code.length))
                        val top = c.localToRoot(Offset(rect.left, rect.top))
                        val bottom = c.localToRoot(Offset(rect.left, rect.bottom))
                        val origin = IntArray(2).also(view::getLocationOnScreen)
                        if (!top.x.isFinite() || !top.y.isFinite() || !bottom.x.isFinite() || !bottom.y.isFinite()) null else
                            com.kingzcheung.xime.service.HardwareCursorAnchor(
                                minOf(top.x, bottom.x) + origin[0], minOf(top.y, bottom.y) + origin[1],
                                maxOf(top.x, bottom.x) + origin[0], maxOf(top.y, bottom.y) + origin[1])
                    }
                    onCaretBounds(anchor)
                }
                SideEffect { reportCaret() }
                DisposableEffect(Unit) { onDispose { onCaretBounds(null) } }
                Modifier.onGloballyPositioned { coordinates = it; reportCaret() }
            }
            // 浮着时拼音和关闭键合成一整块底（和气泡一样一块），模糊也只垫这一块；放在键盘内时照旧两块。
            // 光晕的图层要在浮窗自己的组合里建，不能从外面带进来。
            // 长大途中：整行从原来那块的大小长到原样，内边距从 0 长到原样，底色、字号也跟着过渡；t=0 时整行就是原来那块。
            val t = if (origin == null) 1f else grow.value
            val startWidth = origin?.let { with(density) { it.bounds.width.toDp() } } ?: width
            val startHeight = origin?.let { with(density) { it.bounds.height.toDp() } } ?: 44.dp
            val surfaceColor = floatingColor?.let { target ->
                origin?.let { androidx.compose.ui.graphics.lerp(it.color, target, t) } ?: target }
            val rowSurface = if (surfaceColor == null) Modifier
                else Modifier.clip(RoundedCornerShape(4.dp)).background(surfaceColor).glassTextHalo(halo)
            val placed = editorBounds.value.takeIf { !inline }
            Row(Modifier.width(lerp(startWidth, width, t)).height(lerp(startHeight, 44.dp, t)).testTag("preedit-editor")
                .then(if (placed != null) Modifier.semantics { this[PreeditPopupBoundsKey] = placed } else Modifier)
                .padding(start = 8.dp * t, end = 4.dp * t, top = 6.dp * t, bottom = 6.dp * t),
                verticalAlignment = Alignment.CenterVertically) {
              Row(Modifier.weight(1f).then(rowSurface), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).then(if (floatingColor == null) Modifier.preeditSurface(keyColor)
                        else Modifier.heightIn(min = PreeditStyle.SurfaceHeight).padding(horizontal = 8.dp))
                    .testTag("preedit-editor-surface"),
                    contentAlignment = Alignment.CenterStart) {
                    // 笔画编码自己画，粗细、大小统一（11-23）；显示的字符不变，光标、点按位置照旧。
                    Text(code.withDrawnStrokes(stroke), color = codeColor, inlineContent = strokeInlineContent(codeColor), fontSize = origin?.let { (it.fontSp + (PreeditStyle.FontSize.value - it.fontSp) * t).sp } ?: PreeditStyle.FontSize, maxLines = 1, softWrap = false,
                        onTextLayout = { layout = it }, modifier = Modifier.fillMaxWidth().horizontalScroll(scroll)
                            .testTag("preedit-editor-code")
                            .then(caretBoundsModifier)
                            .semantics {
                                editableText = AnnotatedString(session.text)
                                textSelectionRange = TextRange(session.caret)
                                setSelection { start, end, _ ->
                                    if (start != end) false else { currentCaret(start.coerceIn(0, session.text.length)); true }
                                }
                                setText { onReplace(it.text); true }
                            }
                            .pointerInput(code) {
                                // 点：靠近音节分界就落在分界上（11-23 用户反馈：分词间隔小，容易点错）；拖：照字走，细调用。
                                val slop = PreeditTapSlop.toPx()
                                detectTapGestures { point -> layout?.let {
                                    currentCaret(display.rawOffset(snapPreeditTap(it, point, slop) { c -> c == '\'' }))
                                } }
                            }
                            .pointerInput(code) {
                                detectDragGestures { change, _ ->
                                    change.consume()
                                    layout?.let { currentCaret(display.rawOffset(it.getOffsetForPosition(change.position))) }
                                }
                            }
                            .drawBehind {
                                layout?.takeIf { it.layoutInput.text.text == code }?.getCursorRect(displayCaret.coerceIn(0, code.length))?.let {
                                    drawLine(accentColor, Offset(it.left, it.top), Offset(it.left, it.bottom), 2.dp.toPx())
                                }
                            })
                }
                Box(Modifier.alpha(t).padding(start = 4.dp).size(32.dp).clip(RoundedCornerShape(4.dp))
                    .then(if (floatingColor == null) Modifier.background(keyColor.copy(alpha = 0.62f)) else Modifier)
                    .clickable(onClick = onClose).testTag("preedit-editor-close"), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Close, "关闭拼音编辑", tint = iconColor, modifier = Modifier.size(24.dp))
                }
              }
            }
    }
    BoxWithConstraints(if (inline) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().height(0.dp)) {
        val width = maxWidth
        if (inline) content(width)
        else {
            val glass = LocalKeyboardInputPreferences.current.frostedGlass.normalized()
            if (floatingPreeditBlurBehind(glass)) {
                // 底色块＝整行去掉内边距（左 8、右 4、上下各 6dp），圆角 4dp，和上面 rowSurface 一致。
                // 长大途中内边距跟着从 0 长到原样。
                val t = if (origin == null) 1f else grow.value
                val start = with(density) { (8.dp * t).roundToPx() }
                val end = with(density) { (4.dp * t).roundToPx() }
                val vertical = with(density) { (6.dp * t).roundToPx() }
                PreeditBubbleBlur(editorBounds.value?.let {
                    IntRect(it.left + start, it.top + vertical, it.right - end, it.bottom - vertical)
                }, glass.blurRadiusDp, with(density) { 4.dp.toPx() })
            }
            Popup(popupPositionProvider = position,
                properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
                content(width)
            }
        }
    }
}

internal object PreeditStyle {
    /** 输入的拼音跟「候选字大小」一起放大缩小（用户 11-15：有人说拼音太小）；默认字号下仍是 18sp。 */
    val FontSize: androidx.compose.ui.unit.TextUnit
        @androidx.compose.runtime.Composable get() = (18f * candidateSizeFactor(rememberCandidateTextSize())).sp
    val SurfaceHeight = 32.dp
}

internal fun Modifier.preeditSurface(color: Color): Modifier = heightIn(min = PreeditStyle.SurfaceHeight)
    .clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = color.alpha * 0.62f)).padding(horizontal = 8.dp)
