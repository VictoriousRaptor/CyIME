package com.kingzcheung.xime.ui.keyboard

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
) {
    if (LocalOnBackPressedDispatcherOwner.current != null) BackHandler(onBack = onClose)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val scroll = rememberScrollState()
    val currentCaret by rememberUpdatedState(onCaret)
    val display = remember(session.text, session.isT9, session.protectedText, preedit) { PinyinEditDisplay(session.text, preedit.removePrefix(session.protectedText).trim(), session.isT9, includeAutomaticBoundaries = false) }
    val code = display.text.ifEmpty { " " }
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
    // 浮在应用上时和拼音气泡同一个底色、同一个不透明度，开玻璃时下面同样垫实时模糊（用户 11-16：
    // 气泡是实底、一点拼音编辑条就变透，不统一）。放在键盘内（inline）时仍是原来的键帽色淡底。
    val floatingColor = if (inline) null else floatingPreeditColor(
        com.kingzcheung.xime.ui.theme.LocalMaterialPalette.current?.surface ?: keyColor, keyColor)
    val codeColor = floatingColor?.let { readableThemeText(textColor, it.copy(alpha = 1f)) } ?: textColor.copy(alpha = 0.9f)
    val iconColor = floatingColor?.let { codeColor } ?: textColor
    val halo = remember(codeColor, floatingColor) { floatingPreeditHalo(codeColor, floatingColor) }
    val editorBounds = remember { mutableStateOf<IntRect?>(null) }
    val position = remember(gap) { object : PopupPositionProvider {
        override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
            layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset = IntOffset(
            anchorBounds.left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            (anchorBounds.top - popupContentSize.height - gap).coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)))
            .also { offset ->
                val bounds = IntRect(offset, popupContentSize)
                if (editorBounds.value != bounds) editorBounds.value = bounds
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
            val rowSurface = if (floatingColor == null) Modifier
                else Modifier.clip(RoundedCornerShape(4.dp)).background(floatingColor).glassTextHalo(halo)
            Row(Modifier.width(width).height(44.dp).testTag("preedit-editor")
                .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
              Row(Modifier.weight(1f).then(rowSurface), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).then(if (floatingColor == null) Modifier.preeditSurface(keyColor)
                        else Modifier.heightIn(min = PreeditStyle.SurfaceHeight).padding(horizontal = 8.dp))
                    .testTag("preedit-editor-surface"),
                    contentAlignment = Alignment.CenterStart) {
                    Text(code, color = codeColor, fontSize = PreeditStyle.FontSize, maxLines = 1, softWrap = false,
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
                                detectTapGestures { point -> layout?.let { currentCaret(display.rawOffset(it.getOffsetForPosition(point))) } }
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
                Box(Modifier.padding(start = 4.dp).size(32.dp).clip(RoundedCornerShape(4.dp))
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
                val start = with(density) { 8.dp.roundToPx() }
                val end = with(density) { 4.dp.roundToPx() }
                val vertical = with(density) { 6.dp.roundToPx() }
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
