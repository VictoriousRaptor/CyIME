package com.kingzcheung.xime.ui.keyboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.State
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/** Visual progress only. The gesture recognizer still owns release/cancellation. */
enum class KeyFlickOrigin { TOP_END, BOTTOM_START, TOP, BOTTOM, LEFT, RIGHT }

data class KeyFlickPreview(val text: String, val progress: Float, val origin: KeyFlickOrigin) {
    constructor(text: String, progress: Float, fromTop: Boolean) : this(text, progress,
        if (fromTop) KeyFlickOrigin.TOP_END else KeyFlickOrigin.BOTTOM_START)

    val fromTop get() = origin == KeyFlickOrigin.TOP_END || origin == KeyFlickOrigin.TOP
}

internal fun keyFlickPreview(
    dx: Float, dy: Float, touchSlop: Float, threshold: Float,
    upText: String?, downText: String?,
    upFromTop: Boolean = true, downFromTop: Boolean = false,
): KeyFlickPreview? {
    val distance = abs(dy)
    if (distance <= touchSlop || distance <= abs(dx) * 1.1f) return null
    val text = (if (dy > 0f) downText else upText)?.takeIf { it.isNotEmpty() } ?: return null
    val progress = ((distance - touchSlop) / (threshold - touchSlop).coerceAtLeast(1f)).coerceIn(0f, 1f)
    return KeyFlickPreview(text, progress, if (dy > 0f) downFromTop else upFromTop)
}

internal fun horizontalKeyFlickPreview(
    dx: Float, dy: Float, touchSlop: Float, threshold: Float, leftText: String?, rightText: String?,
): KeyFlickPreview? {
    val distance = abs(dx)
    if (distance <= touchSlop || distance <= abs(dy) * 1.1f) return null
    val text = (if (dx < 0f) leftText else rightText)?.takeIf { it.isNotEmpty() } ?: return null
    val progress = ((distance - touchSlop) / (threshold - touchSlop).coerceAtLeast(1f)).coerceIn(0f, 1f)
    return KeyFlickPreview(text, progress, if (dx < 0f) KeyFlickOrigin.LEFT else KeyFlickOrigin.RIGHT)
}

/**
 * In-key flick labels per direction. The direction under the finger follows it exactly; when the
 * finger re-aims, the previous direction's label shrinks back to its side instead of vanishing.
 * Ending the gesture clears everything at once, as before.
 */
internal class KeyFlickFade(private val texts: Map<KeyFlickOrigin, String>, private val progress: List<State<Float>>) {
    val strongest: Float get() = progress.maxOf { it.value }
    fun labels(): List<KeyFlickPreview> = KeyFlickOrigin.entries.mapIndexedNotNull { index, origin ->
        val value = progress[index].value
        texts[origin]?.takeIf { value > 0.001f }?.let { KeyFlickPreview(it, value, origin) }
    }
}

@Composable
internal fun rememberKeyFlickFade(preview: KeyFlickPreview?): KeyFlickFade {
    // The preview itself drives recomposition; this only remembers each side's last text.
    val texts = remember { HashMap<KeyFlickOrigin, String>() }
    if (preview == null) texts.clear() else texts[preview.origin] = preview.text
    // 换方向时新方向不再一下子整个跳出来（用户 11-22「上下左右拖动老是跳变」）：从原来那一角用 90 ms 追上手指，
    // 追上以后照旧紧跟手指；旧方向照旧 120 ms 缩回去。同一方向里来回拖始终紧跟，不加延迟。
    val entering = remember { HashSet<KeyFlickOrigin>() }
    val previous = remember { arrayOfNulls<KeyFlickOrigin>(1) }
    if (preview == null) entering.clear()
    else if (previous[0] != null && previous[0] != preview.origin) entering.add(preview.origin)
    previous[0] = preview?.origin
    val progress = KeyFlickOrigin.entries.map { origin ->
        val active = preview?.origin == origin
        val target = if (active) preview!!.progress else 0f
        val catchingUp = active && origin in entering
        val state = animateFloatAsState(target, when {
            preview == null -> snap()
            catchingUp -> tween(90)
            active -> snap()
            else -> tween(120)
        }, label = "flick-${origin.name}")
        if (catchingUp && abs(state.value - target) < 0.02f) entering.remove(origin)
        state
    }
    return KeyFlickFade(texts.toMap(), progress)
}

/**
 * 手指往哪个方向拖（不是符号从哪一角来：「反转角标滑动方向」时下拉拉的是上角标）。
 * [upFromTop]/[downFromTop] 是这个键上滑、下滑的预览各从哪一边出来（[KeyGestureActions.upPreviewFromTop]）。
 */
internal fun keyFlickDragDirection(preview: KeyFlickPreview, upFromTop: Boolean, downFromTop: Boolean): KanaFlickDirection =
    when (preview.origin) {
        KeyFlickOrigin.LEFT -> KanaFlickDirection.LEFT
        KeyFlickOrigin.RIGHT -> KanaFlickDirection.RIGHT
        else -> if (preview.fromTop == downFromTop && preview.fromTop != upFromTop) KanaFlickDirection.DOWN else KanaFlickDirection.UP
    }

/**
 * 四向键（14 键双字母键）拖动时画出触发范围（用户 11-22：像日语那样显示方向和触发夹角）：
 * 两条 45° 分界线标出四个方向各占的夹角，手指所在方向的扇区随拖动距离加深，到触发距离时最深。
 * 和日语假名方向盘同一画法；这类键松手落在哪个扇区就出哪个方向（[KeyGestureActions.freeDirection]），画的就是判定范围。
 */
@Composable
internal fun BoxScope.KeyFlickSector(direction: KanaFlickDirection, color: Color, progress: Float) {
    Canvas(Modifier.matchParentSize().testTag("key-flick-sector:${direction.name}")) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val s = minOf(size.width, size.height) / 2f
        val line = color.copy(alpha = color.alpha * 0.35f)
        val stroke = 1.dp.toPx()
        drawLine(line, Offset(cx - s, cy - s), Offset(cx + s, cy + s), stroke)
        drawLine(line, Offset(cx - s, cy + s), Offset(cx + s, cy - s), stroke)
    }
    KanaDirectionIndicator(direction, color.copy(alpha = color.alpha * (0.15f + 0.30f * progress.coerceIn(0f, 1f))))
}

@Composable
internal fun BoxScope.KeyFlickLabels(
    fade: KeyFlickFade, color: Color, widthDp: Float, heightDp: Float, hintSizeSp: Float, labelSizeSp: Float,
    /** 用图标代替文字的方向（如 0 键左滑「上一项」用左箭头），图标最终大小为 [iconSizeDp]。 */
    icons: Map<KeyFlickOrigin, Painter> = emptyMap(),
    iconSizeDp: Float = KeyboardKeyMetrics.FunctionIconSize.value,
) {
    for (label in fade.labels()) {
        val icon = icons[label.origin]
        if (icon != null) KeyFlickIcon(label, icon, color, widthDp, heightDp, iconSizeDp)
        else KeyFlickLabel(label, color, widthDp, heightDp, hintSizeSp, labelSizeSp)
    }
}

/** 和 [KeyFlickLabel] 一样从原来那一侧移到键中央、由小变大，只是画的是图标。 */
@Composable
internal fun BoxScope.KeyFlickIcon(
    preview: KeyFlickPreview,
    icon: Painter,
    color: Color,
    widthDp: Float,
    heightDp: Float,
    iconSizeDp: Float,
) {
    val progress = preview.progress
    val size = iconSizeDp * (0.6f + 0.4f * progress)
    Icon(
        painter = icon,
        contentDescription = preview.text,
        tint = keyHintColor(color, LocalKeyboardInputPreferences.current.frostedGlass, color.alpha * (0.6f + 0.4f * progress)),
        modifier = Modifier.align(Alignment.Center).size(size.dp).testTag("key-flick-preview").graphicsLayer {
            val x = ((widthDp - size) / 2f - 2f).coerceAtLeast(0f).dp.toPx() * (1f - progress)
            val y = ((heightDp - size) / 2f - 2f).coerceAtLeast(0f).dp.toPx() * (1f - progress)
            translationX = when (preview.origin) {
                KeyFlickOrigin.TOP_END, KeyFlickOrigin.RIGHT -> x
                KeyFlickOrigin.BOTTOM_START, KeyFlickOrigin.LEFT -> -x
                else -> 0f
            }
            translationY = when (preview.origin) {
                KeyFlickOrigin.TOP_END, KeyFlickOrigin.TOP -> -y
                KeyFlickOrigin.BOTTOM_START, KeyFlickOrigin.BOTTOM -> y
                else -> 0f
            }
        },
    )
}

/** Move the existing corner hint into the key's centre, without changing its bounds. */
@Composable
internal fun BoxScope.KeyFlickLabel(
    preview: KeyFlickPreview,
    color: Color,
    widthDp: Float,
    heightDp: Float,
    hintSizeSp: Float,
    labelSizeSp: Float,
) {
    val density = LocalDensity.current
    val label = punctuationKeyLabel(preview.text)
    // labelSizeSp already includes the keyboard/user scaling; fit long action labels once.
    val targetSize = KeyboardKeyMetrics.labelSizeSp(label, labelSizeSp * 1.15f, widthDp, heightDp,
        density.fontScale, scaleOverride = 1f)
    val progress = preview.progress
    val fontSize = hintSizeSp + (targetSize - hintSizeSp) * progress
    var textSize by remember(label) { mutableStateOf(IntSize.Zero) }
    Text(
        text = label,
        color = keyHintColor(color, LocalKeyboardInputPreferences.current.frostedGlass, color.alpha * (0.6f + 0.4f * progress)),
        fontSize = fontSize.sp,
        lineHeight = (fontSize * 1.2f).sp,
        fontWeight = FontWeight.Normal,
        fontFamily = AppFonts.keyFontFamily,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        onTextLayout = { textSize = it.size },
        modifier = Modifier.align(Alignment.Center).testTag("key-flick-preview").graphicsLayer {
            val x = (with(density) { widthDp.dp.toPx() } / 2f - textSize.width / 2f -
                with(density) { 5.dp.toPx() }).coerceAtLeast(0f) * (1f - progress)
            val y = (with(density) { heightDp.dp.toPx() } / 2f - textSize.height / 2f -
                with(density) { 2.dp.toPx() }).coerceAtLeast(0f) * (1f - progress)
            translationX = when (preview.origin) {
                KeyFlickOrigin.TOP_END, KeyFlickOrigin.RIGHT -> x
                KeyFlickOrigin.BOTTOM_START, KeyFlickOrigin.LEFT -> -x
                else -> 0f
            }
            translationY = when (preview.origin) {
                KeyFlickOrigin.TOP_END, KeyFlickOrigin.TOP -> -y
                KeyFlickOrigin.BOTTOM_START, KeyFlickOrigin.BOTTOM -> y
                else -> 0f
            }
        },
    )
}
