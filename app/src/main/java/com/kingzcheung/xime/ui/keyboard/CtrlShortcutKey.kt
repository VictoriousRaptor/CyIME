package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.keyboard.CtrlShortcuts
import com.kingzcheung.xime.settings.FrostedGlassConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 符号键按住当 Ctrl（用户 10-24）。拖到字母上松手发 Ctrl+字母，拖法和 Shift 拖动一样；
 * 也可以另一根手指直接点字母（见 [ShiftSlideTargets.ctrlTap]），都是按下就生效。
 * 字母键的快捷键键面要按满 [holdMillis]（同长按）或拖出符号键才渐变出来，点一下开符号不闪（用户 10-31）。
 * 没拖动、没点过字母、在 [holdMillis] 内松手才是点按，打开符号面板；
 * 超过时限或拖出去以后松手都不打开，没落在字母上就什么都不做。
 */
internal fun Modifier.ctrlSymbolKeyGesture(
    targets: ShiftSlideTargets,
    holdMillis: Long,
    onPressDown: () -> Unit,
    onOpenSymbols: () -> Unit,
    onShortcut: (Char) -> Unit,
    onPressedChange: (Boolean) -> Unit,
): Modifier = composed {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val dragSpace = LocalKeyDragSpace.current
    val hold by rememberUpdatedState(holdMillis)
    val down by rememberUpdatedState(onPressDown)
    val open by rememberUpdatedState(onOpenSymbols)
    val shortcut by rememberUpdatedState(onShortcut)
    val pressed by rememberUpdatedState(onPressedChange)
    val scope = rememberCoroutineScope()
    onGloballyPositioned { bounds = Rect(dragSpace.point(it), it.size.toSize()) }.pointerInput(targets) {
        awaitEachGesture {
            // 先于键帽自己的手势拿到按下（Initial），键帽只负责画。
            val first = awaitFirstDown(pass = PointerEventPass.Initial)
            first.consume()
            pressed(true)
            down()
            targets.beginCtrl { shortcut(it) }
            val faces = scope.launch {
                delay(hold)
                targets.showCtrlFaces()
            }
            var dragged = false
            var tap = false
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == first.id } ?: break
                    change.consume()
                    val pointer = bounds.topLeft + change.position
                    dragged = dragged || !bounds.contains(pointer) ||
                        (change.position - first.position).getDistance() > viewConfiguration.touchSlop
                    val target = if (dragged) targets.move(bounds, pointer) else null
                    if (!change.pressed) {
                        if (dragged) target?.key?.singleOrNull()?.let(shortcut)
                        else tap = !targets.ctrlUsed && change.uptimeMillis - first.uptimeMillis < hold
                        break
                    }
                }
            } finally {
                faces.cancel()
                targets.finish()
                pressed(false)
            }
            if (tap) open()
        }
    }
}

/** 快捷键键面渐变出来的时长；松手不渐变，立刻换回字母。 */
internal const val CTRL_FACE_FADE_MS = 250

/**
 * Ctrl 键面：中间写用途，左上角小字母（九键写数字，[cornerLabel]）；没有常用用途的字母只淡淡写字母，
 * 拖上去照样发 Ctrl+字母。
 */
@Composable
internal fun BoxScope.CtrlShortcutFace(
    letter: Char,
    textColor: Color,
    frostedGlass: FrostedGlassConfig,
    labelSp: Float,
    hintSize: TextUnit,
    hintsVisible: Boolean,
    keyWidthDp: Float,
    fontFamily: FontFamily?,
    cornerLabel: String = letter.uppercase(),
) {
    val action = CtrlShortcuts.label(letter)
    val fontScale = LocalDensity.current.fontScale
    if (action == null) {
        Text(letter.uppercase(), Modifier.align(Alignment.Center), color = keyHintColor(textColor, frostedGlass, 0.45f),
            fontSize = labelSp.sp, maxLines = 1, textAlign = TextAlign.Center, fontFamily = fontFamily)
        return
    }
    // 两个汉字占两倍字号宽，留出键帽左右的边。
    val actionSp = minOf(labelSp * 0.62f, keyWidthDp * 0.4f / fontScale)
    Text(action, Modifier.align(Alignment.Center), color = textColor, fontSize = actionSp.sp,
        lineHeight = (actionSp * 1.2f).sp, fontWeight = FontWeight.Medium, maxLines = 1, textAlign = TextAlign.Center)
    if (hintsVisible) Text(cornerLabel, Modifier.align(Alignment.TopStart).padding(start = 5.dp, top = 2.dp),
        color = keyHintColor(textColor, frostedGlass, 0.6f), fontSize = hintSize,
        lineHeight = (hintSize.value * 1.2f).sp, maxLines = 1, fontFamily = fontFamily)
}

/** [KeyboardModeKey] 里的符号键：按住当 Ctrl。 */
@Composable
internal fun CtrlSymbolKeyButton(
    text: String,
    targets: ShiftSlideTargets,
    holdMillis: Long,
    onOpenSymbols: () -> Unit,
    onShortcut: (Char) -> Unit,
    onPressDown: () -> Unit,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier,
    shadowEnabled: Boolean,
    shadowElevation: androidx.compose.ui.unit.Dp,
    shadowShapeRadius: androidx.compose.ui.unit.Dp,
) {
    var pressed by remember { mutableStateOf(false) }
    KeyButton(
        // 按满时限、字母键换成快捷键键面时，这个键自己也写 Ctrl（用户 11-13：按住时就是真正的 Ctrl）；点一下开符号不变。
        text = if (targets.ctrlFaces) "Ctrl" else text,
        fontSize = 16.sp,
        onClick = {},
        backgroundColor = backgroundColor,
        textColor = textColor,
        modifier = modifier.ctrlSymbolKeyGesture(targets, holdMillis, onPressDown, onOpenSymbols, onShortcut,
            onPressedChange = { pressed = it }),
        shadowEnabled = shadowEnabled,
        shadowElevation = shadowElevation,
        shadowShapeRadius = shadowShapeRadius,
        externalPressed = pressed,
    )
}
