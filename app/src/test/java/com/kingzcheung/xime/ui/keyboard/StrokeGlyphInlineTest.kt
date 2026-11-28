package com.kingzcheung.xime.ui.keyboard

import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** 11-23：编码条里的笔画自己画；显示的字符不变，光标、点按位置照旧。 */
class StrokeGlyphInlineTest {
    private val inlineTag = "androidx.compose.foundation.text.inlineContent"

    @Test fun strokesAndWildcardBecomeDrawnCellsWithTheSameText() {
        val shown = "根⼀⼁'乛*⼃⼂"
        val drawn = shown.withDrawnStrokes(true)
        assertEquals(shown, drawn.text)
        val cells = drawn.getStringAnnotations(inlineTag, 0, drawn.length)
        assertEquals(listOf(1, 2, 4, 5, 6, 7), cells.map { it.start })
        assertEquals(cells.map { it.start + 1 }, cells.map { it.end })
    }

    @Test fun lettersAndOtherSchemesStayAsText() {
        val letters = AnnotatedString("hs'z*")
        assertSame(letters, letters.withDrawnStrokes(true))           // 笔画显示关着：字母和 * 照字体
        val glyphs = AnnotatedString("⼀⼁*")
        assertSame(glyphs, glyphs.withDrawnStrokes(false))           // 不是笔画方案
    }

    /** 11-26：笔画键帽的 一丨丿丶乛 也自己画，键上的字不变；别的标签照字体。 */
    @Test fun keycapStrokesAreDrawnOnlyWhenTheWholeLabelIsOneStroke() {
        for (label in listOf("一", "丨", "丿", "丶", "乛")) {
            val drawn = keycapStrokeLabel(AnnotatedString(label))!!
            assertEquals(label, drawn.text)
            assertEquals(1, drawn.getStringAnnotations(inlineTag, 0, drawn.length).size)
        }
        for (label in listOf("分词", "*", "一二", "⼀", "h", "")) assertNull(label, keycapStrokeLabel(AnnotatedString(label)))
    }
}
