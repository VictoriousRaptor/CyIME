package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.PopupPositionProvider
import kotlinx.coroutines.delay
import com.kingzcheung.xime.service.HardwareCursorAnchor
import kotlin.math.roundToInt

/** Screen geometry stays independent of the IME window, which can occupy only the keyboard. */
internal class CursorMagnifierPosition(
    private val caret: HardwareCursorAnchor,
    private val screen: IntRect,
    private val windowOrigin: IntOffset,
    private val gap: Int,
) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
        layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val maxX = (screen.right - popupContentSize.width).coerceAtLeast(screen.left)
        val maxY = (screen.bottom - popupContentSize.height).coerceAtLeast(screen.top)
        val x = ((caret.left + caret.right) / 2 - popupContentSize.width / 2f).roundToInt()
        val above = caret.top.roundToInt() - popupContentSize.height - gap
        val below = caret.bottom.roundToInt() + gap
        val y = if (above >= screen.top) above else if (below <= maxY) below else above
        return IntOffset(x.coerceIn(screen.left, maxX), y.coerceIn(screen.top, maxY)) - windowOrigin
    }
}

/** A bounded, transient view of the active caret; never persisted or logged. */
data class CursorMagnifierText(val text: String, val caret: Int) {
    companion object {
        fun from(text: CharSequence, caret: Int): CursorMagnifierText? {
            if (caret !in 0..text.length) return null
            // Keep UTF-16 pairs intact at the caret and at both window edges.
            fun boundary(offset: Int): Int = if (offset in 1 until text.length &&
                Character.isHighSurrogate(text[offset - 1]) && Character.isLowSurrogate(text[offset])) offset - 1 else offset
            val at = boundary(caret)
            val start = boundary((at - 64).coerceAtLeast(0))
            val end = boundary((at + 64).coerceAtMost(text.length))
            val shown = text.subSequence(start, end).map {
                when (it) { '\n', '\r' -> '↵'; '\t' -> ' '; else -> it }
            }.joinToString("")
            return CursorMagnifierText(shown, at - start)
        }
    }
}

/** Reads only while space is held. Cancelling the effect also discards in-flight replies. */
@Composable
internal fun CursorMagnifier(
    sessionId: Long,
    readText: (suspend () -> CursorMagnifierText?)?,
    background: Color,
    foreground: Color,
    accent: Color,
    composition: CursorMagnifierText? = null,
    readAnchor: (() -> HardwareCursorAnchor?)? = null,
    onTrackingChange: ((Long, Boolean) -> Unit)? = null,
    widthDp: Float = 280f,
) {
    val currentReadAnchor by rememberUpdatedState(readAnchor)
    val currentTrackingChange by rememberUpdatedState(onTrackingChange)
    var caret by remember(sessionId) { mutableStateOf<HardwareCursorAnchor?>(null) }
    DisposableEffect(sessionId) {
        currentTrackingChange?.invoke(sessionId, true)
        onDispose { currentTrackingChange?.invoke(sessionId, false) }
    }
    LaunchedEffect(sessionId) {
        while (true) {
            caret = currentReadAnchor?.invoke()
            delay(32L)
        }
    }
    var preview by remember(sessionId, readText) { mutableStateOf<CursorMagnifierText?>(null) }
    LaunchedEffect(sessionId, readText, composition != null) {
        preview = null
        if (composition == null && readText != null) while (true) {
            preview = readText()
            delay(80L)
        }
    }
    val shown = composition ?: preview ?: return
    val anchor = caret ?: return
    val view = LocalView.current
    val screenRect = android.graphics.Rect()
    @Suppress("DEPRECATION")
    view.display?.getRectSize(screenRect)
    if (screenRect.isEmpty) screenRect.set(0, 0, view.resources.displayMetrics.widthPixels, view.resources.displayMetrics.heightPixels)
    if (!anchor.left.isFinite() || !anchor.top.isFinite() || !anchor.right.isFinite() || !anchor.bottom.isFinite() ||
        anchor.bottom <= anchor.top || anchor.right < screenRect.left || anchor.left > screenRect.right ||
        anchor.bottom < screenRect.top || anchor.top > screenRect.bottom) return
    val location = IntArray(2).also(view::getLocationOnScreen)
    val inWindow = IntArray(2).also(view::getLocationInWindow)
    val origin = IntOffset(location[0] - inWindow[0], location[1] - inWindow[1])
    val density = LocalDensity.current
    val gap = with(density) { 8.dp.roundToPx() }
    val availableWidth = with(density) { screenRect.width().toDp() - 16.dp }.coerceAtLeast(1.dp)
    val screen = IntRect(screenRect.left, screenRect.top, screenRect.right, screenRect.bottom)
    Popup(popupPositionProvider = CursorMagnifierPosition(anchor, screen, origin, gap),
        properties = PopupProperties(focusable = false, dismissOnBackPress = false,
            dismissOnClickOutside = false, clippingEnabled = false)) {
        CursorMagnifierCapsule(shown, background, foreground, accent,
            Modifier.width(minOf(normalizeCursorMagnifierWidth(widthDp).dp, availableWidth)))
    }
}

/** Presentation only: no pointer handler, focus request, or editor operation. */
@Composable
internal fun CursorMagnifierCapsule(
    preview: CursorMagnifierText,
    background: Color,
    foreground: Color,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = TextStyle(color = foreground, fontSize = minOf(26f, 32f / density.fontScale).sp)
    val layout = remember(preview.text, style, density) {
        measurer.measure(preview.text, style, softWrap = false, maxLines = 1)
    }
    val shape = RoundedCornerShape(50)
    Canvas(modifier.height(56.dp).shadow(6.dp, shape).clip(shape)
        .background(background.copy(alpha = 1f))
        .border(1.dp, foreground.copy(alpha = 0.18f), shape)
        .testTag("cursor-magnifier")
        .semantics { contentDescription = "光标放大镜" }) {
        val x = size.width / 2f
        val caretX = layout.getHorizontalPosition(preview.caret.coerceIn(0, preview.text.length), true)
        drawText(layout, topLeft = Offset(x - caretX, (size.height - layout.size.height) / 2f))
        drawLine(accent, Offset(x, 12.dp.toPx()), Offset(x, size.height - 12.dp.toPx()), 2.dp.toPx())
    }
}
