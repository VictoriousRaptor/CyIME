package com.kingzcheung.xime.ui.keyboard

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 候选栏量宽度：向上取整、同字同样式只量一次、跨按键记着最近的、超出容量丢最久没用的。 */
class CandidateTextWidthsTest {
    private var measured = 0
    private fun widths(capacity: Int = 256) = CandidateTextWidths(capacity) { text, style ->
        measured++
        text.length * style.fontSize.value + 0.25f
    }
    private val normal = TextStyle(fontSize = 18.sp)
    private val bold = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold)

    @Test fun widthIsRoundedUpLikeTextMeasurer() {
        assertEquals(37, widths().of("我们", normal))
    }

    @Test fun sameTextAndStyleIsMeasuredOnceAcrossKeys() {
        val w = widths()
        repeat(3) { w.of("我们", normal); w.of(AnnotatedString("我们"), normal) }
        assertEquals(1, measured)
        // 首选加粗是另一种样式，要另量。
        w.of("我们", bold)
        assertEquals(2, measured)
    }

    @Test fun oldestUnusedWidthsAreDropped() {
        val w = widths(capacity = 2)
        w.of("一", normal); w.of("二", normal)
        w.of("一", normal)          // 一 刚用过
        w.of("三", normal)          // 丢掉 二
        assertEquals(2, w.size)
        measured = 0
        w.of("一", normal)
        assertEquals(0, measured)
        w.of("二", normal)
        assertEquals(1, measured)
    }

    /** 源码检查：排窗口量宽度走 CandidateTextWidths，只有两行排版还用整段排版。 */
    @Test fun theStripMeasuresWidthsWithoutLayingOut() {
        val source = listOf("src/main/java/com/kingzcheung/xime/ui/keyboard/CandidateBar.kt",
            "app/src/main/java/com/kingzcheung/xime/ui/keyboard/CandidateBar.kt").map { java.io.File(it) }
            .first { it.isFile }.readText()
        val strip = source.substring(source.indexOf("internal fun FixedCandidateStrip("))
            .substringBefore("private fun ScrollableCandidateRow(")
        assertTrue(strip.contains("candidateTextWidths(density, fontFamilyResolver, layoutDirection)"))
        assertEquals(1, Regex("""measurer\.measure\(""").findAll(strip).count())
        assertTrue(strip.contains("val wrapped = measurer.measure("))
        assertFalse(strip.contains("softWrap = false).size.width"))
    }
}
