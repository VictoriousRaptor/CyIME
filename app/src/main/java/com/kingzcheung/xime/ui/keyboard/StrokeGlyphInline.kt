package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.em

/**
 * 笔画编码自己画（11-23 群友截图：编码条里的 一丨丿丶乛 和 * 太大、粗细不一）。
 *
 * 组字区显示成笔画时，横竖撇点是康熙部首（⼀⼁⼃⼂），折是汉字乛，系统常常从不同字体里找这几个字，
 * 所以粗细、大小各不一样，而且都是整个汉字那么大，比拼音字母大一圈。这里不再用字体画，
 * 每一笔占一个字母的位置，按同一条线宽自己画，高度和拼音字母差不多；* 也按同一线宽画。
 * 只换画法：文字本身不变（每一笔仍是原来那一个字符），光标、点按位置、复制出来的内容都和原来一样。
 */
internal val LocalStrokeGlyphDrawing = compositionLocalOf { false }

/** 这些字符画成笔画；和 rime/StrokePreedit.kt 的 STROKE_GLYPHS（折用乛，#412）对应，* 是通配符。 */
private val DRAWN_STROKES = mapOf('⼀' to "h", '⼁' to "s", '⼃' to "p", '⼂' to "n", '乛' to "z", '*' to "w")
private const val INLINE_PREFIX = "cyime-stroke-"

/** 画笔画的那一格：宽高按字号算（em），底边坐在基线上，和 h、p 这类拼音字母一样高（约 0.7em）。 */
private const val STROKE_CELL_WIDTH = 0.74f
private const val STROKE_CELL_HEIGHT = 0.7f
/** 线宽占一格高度的比例：约 0.09em，和常规字重的拼音字母笔画差不多粗。 */
private const val STROKE_WIDTH = 0.13f

/**
 * 有要画的笔画时把它们换成占位（显示用的字符不变，长度不变）；没有或 [enabled] 为 false 时原样返回。
 * * 只在同一串里有笔画时才画：笔画显示关着（hspnz 字母）时字母和 * 都照字体原样。
 */
internal fun AnnotatedString.withDrawnStrokes(enabled: Boolean): AnnotatedString {
    if (!enabled || none { it in DRAWN_STROKES && it != '*' }) return this
    val source = this
    return buildAnnotatedString {
        var start = 0
        source.forEachIndexed { index, c ->
            val kind = DRAWN_STROKES[c] ?: return@forEachIndexed
            if (start < index) append(source.subSequence(start, index))
            appendInlineContent(INLINE_PREFIX + kind, c.toString())
            start = index + 1
        }
        if (start < source.length) append(source.subSequence(start, source.length))
    }
}

internal fun String.withDrawnStrokes(enabled: Boolean): AnnotatedString = AnnotatedString(this).withDrawnStrokes(enabled)

/** 和 [withDrawnStrokes] 配套给 Text 的 inlineContent；[color] 用这段文字的字色。 */
@Composable
internal fun strokeInlineContent(color: Color): Map<String, InlineTextContent> = remember(color) {
    DRAWN_STROKES.values.associate { kind ->
        (INLINE_PREFIX + kind) to InlineTextContent(
            Placeholder(STROKE_CELL_WIDTH.em, STROKE_CELL_HEIGHT.em, PlaceholderVerticalAlign.AboveBaseline)) {
            Canvas(Modifier.fillMaxSize()) { drawStroke(kind, color) }
        }
    }
}

/**
 * 笔画键帽上的 一丨丿丶乛 也自己画（11-26 群友截图：新版键帽笔画又细又不统一，乛特别细，要回到粗而统一的样子）。
 * 键帽字原来交给系统字体，各家手机字体里这五个字粗细、大小不一样；这里和编码条用同一套笔形，线宽加粗到
 * 和键面汉字的笔画看着一样重。键帽上整个标签就是这一笔时才画，键上的值、读屏文字不变。
 */
private val KEYCAP_STROKES = mapOf('一' to "h", '丨' to "s", '丿' to "p", '丶' to "n", '乛' to "z")
private const val KEYCAP_INLINE_ID = "cyime-keycap-stroke"
/** 键帽那一格：约一个汉字大小（0.86em 见方），线宽 0.14em。 */
private const val KEYCAP_CELL = 0.86f
private const val KEYCAP_STROKE_WIDTH = 0.14f / KEYCAP_CELL

/** 标签整个是一笔时返回画成笔画用的文字（字符不变，只换成占位）；不是时 null，照字体显示。 */
internal fun keycapStrokeLabel(label: AnnotatedString): AnnotatedString? {
    if (label.length != 1 || label[0] !in KEYCAP_STROKES) return null
    return buildAnnotatedString { appendInlineContent(KEYCAP_INLINE_ID, label.text) }
}

/** 和 [keycapStrokeLabel] 配套的 inlineContent；[label] 是那一笔原来的字。 */
@Composable
internal fun keycapStrokeInlineContent(label: String, color: Color): Map<String, InlineTextContent> = remember(label, color) {
    val kind = label.singleOrNull()?.let { KEYCAP_STROKES[it] }
    if (kind == null) emptyMap() else mapOf(KEYCAP_INLINE_ID to InlineTextContent(
        Placeholder(KEYCAP_CELL.em, KEYCAP_CELL.em, PlaceholderVerticalAlign.Center)) {
        Canvas(Modifier.fillMaxSize()) { drawStroke(kind, color, KEYCAP_STROKE_WIDTH) }
    })
}

/** 一格里画一笔（坐标按格子宽高的比例）。所有笔画同一线宽、圆头，看着是同一种字。 */
internal fun DrawScope.drawStroke(kind: String, color: Color, widthRatio: Float = STROKE_WIDTH) {
    val w = size.width
    val h = size.height
    val line = Stroke(width = h * widthRatio, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(w * x, h * y)
    when (kind) {
        // 横：一格正中（和小写字母的中线差不多高）。
        "h" -> drawLine(color, p(.12f, .5f), p(.88f, .5f), line.width, StrokeCap.Round)
        // 竖：从顶到底。
        "s" -> drawLine(color, p(.5f, .06f), p(.5f, .94f), line.width, StrokeCap.Round)
        // 撇：右上往左下，略带弧。
        "p" -> drawPath(Path().apply {
            moveTo(w * .72f, h * .06f)
            quadraticBezierTo(w * .66f, h * .66f, w * .18f, h * .94f)
        }, color, style = line)
        // 点：左上往右下的一小笔。
        "n" -> drawLine(color, p(.38f, .26f), p(.64f, .7f), line.width, StrokeCap.Round)
        // 折（乛）：先横，再往左下撇出。
        "z" -> drawPath(Path().apply {
            moveTo(w * .12f, h * .18f)
            lineTo(w * .8f, h * .18f)
            quadraticBezierTo(w * .7f, h * .66f, w * .36f, h * .94f)
        }, color, style = line)
        // 通配符 *：三条线交叉成六个角，比字体里的 * 低、和笔画一样粗。
        "w" -> {
            val c = p(.5f, .5f)
            val r = minOf(w, h) * .3f
            for (deg in listOf(90.0, 30.0, -30.0)) {
                val rad = Math.toRadians(deg)
                val dx = (kotlin.math.cos(rad) * r).toFloat()
                val dy = (kotlin.math.sin(rad) * r).toFloat()
                drawLine(color, Offset(c.x - dx, c.y - dy), Offset(c.x + dx, c.y + dy), line.width, StrokeCap.Round)
            }
        }
    }
}
