package com.kingzcheung.xime.ui.keyboard

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.MultiParagraphIntrinsics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.resolveDefaults
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.ceil

/**
 * 候选栏排窗口时只要宽度：只算一行的固有宽度，不排版；最近量过的跨按键记着。
 *
 * 结果和 `TextMeasurer.measure(text, style, softWrap = false).size.width` 一样（那边不限宽、不折行时宽度就是
 * 固有宽度向上取整），只是不建排版对象。平板 84 分段计时（11-13）：候选栏每键自身耗时 1.41 ms 领先，大头是
 * 每键把放得下的候选和栏尾几个逐个整段排版一遍量宽度，画的时候 Text 又排一遍。
 * 同一组参数（字体解析、密度、方向）下有效；参数变了换一个新的。只在主线程（组合）用。
 */
internal class CandidateTextWidths(
    private val capacity: Int = 256,
    /** 一段字在这个样式下不折行的固有宽度（像素，未取整）。 */
    private val intrinsicWidth: (AnnotatedString, TextStyle) -> Float,
) {
    private data class Key(val text: AnnotatedString, val style: TextStyle)

    private val widths = object : LinkedHashMap<Key, Int>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Int>?): Boolean = size > capacity
    }

    /** 不折行时的宽度（像素，向上取整）。 */
    fun of(text: AnnotatedString, style: TextStyle): Int =
        widths.getOrPut(Key(text, style)) { ceil(intrinsicWidth(text, style)).toInt() }

    fun of(text: String, style: TextStyle): Int = of(AnnotatedString(text), style)

    internal val size: Int get() = widths.size
}

/** 和 TextMeasurer 同样的算法（resolveDefaults + MultiParagraphIntrinsics），只取固有宽度。 */
internal fun candidateTextWidths(
    density: Density, fontFamilyResolver: FontFamily.Resolver, layoutDirection: LayoutDirection,
): CandidateTextWidths = CandidateTextWidths { text, style ->
    MultiParagraphIntrinsics(
        annotatedString = text,
        style = resolveDefaults(style, layoutDirection),
        placeholders = emptyList(),
        density = density,
        fontFamilyResolver = fontFamilyResolver,
    ).maxIntrinsicWidth
}
