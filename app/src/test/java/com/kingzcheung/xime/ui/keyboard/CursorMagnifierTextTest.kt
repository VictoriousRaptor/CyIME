package com.kingzcheung.xime.ui.keyboard

import androidx.compose.ui.unit.*
import com.kingzcheung.xime.service.HardwareCursorAnchor
import org.junit.Assert.*
import org.junit.Test

class CursorMagnifierTextTest {
    @Test fun caretKeepsTextOnBothSidesAtDocumentBoundaries() {
        assertEquals(CursorMagnifierText("左右", 0), CursorMagnifierText.from("左右", 0))
        assertEquals(CursorMagnifierText("左右", 1), CursorMagnifierText.from("左右", 1))
        assertEquals(CursorMagnifierText("左右", 2), CursorMagnifierText.from("左右", 2))
        assertNull(CursorMagnifierText.from("", 0))
        assertNull(CursorMagnifierText.from("左右", -1))
        assertNull(CursorMagnifierText.from("左右", 3))
    }

    @Test fun previewIsBoundedAndKeepsSurrogatePairs() {
        val text = "😀".repeat(100)
        val preview = CursorMagnifierText.from(text, 101)!!
        assertEquals(8, preview.caret)
        assertEquals("😀".repeat(8), preview.text)
        assertEquals(preview, CursorMagnifierText.from(text, 100))
        assertEquals(CursorMagnifierText("😀".repeat(4), 0), CursorMagnifierText.from(text, 0))
        assertEquals(CursorMagnifierText("😀".repeat(4), 8), CursorMagnifierText.from(text, text.length))
        assertEquals(CursorMagnifierText("😀 乙", 3), CursorMagnifierText.from("甲\n😀\t乙", 5))
    }

    @Test fun onlyTheCurrentLineIsShownAtBothSidesOfEachLineBreak() {
        for (separator in listOf("\n", "\r", "\r\n", "\u2028", "\u2029")) {
            val text = "上一行${separator}甲😀乙${separator}下一行"
            val start = 3 + separator.length
            assertEquals(CursorMagnifierText("上一行", 3), CursorMagnifierText.from(text, 3))
            for (caret in listOf(0, 1, 3, 4)) {
                assertEquals(CursorMagnifierText("甲😀乙", caret), CursorMagnifierText.from(text, start + caret))
            }
            assertEquals(CursorMagnifierText("下一行", 0), CursorMagnifierText.from(text, start + 4 + separator.length))
        }
        assertEquals(CursorMagnifierText("", 0), CursorMagnifierText.from("前\n\n后", 2))
        assertEquals(CursorMagnifierText("", 0), CursorMagnifierText.from("前\n", 2))
    }

    @Test fun longCurrentLineStillHonorsTheTextWindowAndCaretOffset() {
        val text = "前一行\n" + "😀".repeat(100) + "\n后一行"
        assertEquals(CursorMagnifierText("😀".repeat(8), 8), CursorMagnifierText.from(text, 105))
    }

    @Test fun actualLayoutLineBoundsExcludeSoftWrappedNeighbors() {
        val text = "上一行甲😀乙下一行"
        for (caret in listOf(3, 4, 6, 7)) {
            assertEquals(CursorMagnifierText("甲😀乙", caret - 3),
                CursorMagnifierText.from(text, caret, lineStart = 3, lineEnd = 7))
        }
        assertEquals(CursorMagnifierText("上一行", 3), CursorMagnifierText.from(text, 3, 0, 3))
        assertEquals(CursorMagnifierText("下一行", 0), CursorMagnifierText.from(text, 7, 7, text.length))
        assertEquals(CursorMagnifierText("", 0), CursorMagnifierText.from(text, 7, 7, 7))
    }

    @Test fun invalidLayoutLineBoundsDoNotMoveTheCaretToAnotherLine() {
        val text = "甲😀乙"
        for ((start, end, caret) in listOf(
            Triple(-1, 4, 0), Triple(0, 5, 0), Triple(3, 1, 3),
            Triple(1, 3, 0), Triple(1, 3, 4),
            Triple(2, 4, 3), Triple(0, 2, 1),
        )) {
            assertNull("start=$start end=$end caret=$caret", CursorMagnifierText.from(text, caret, start, end))
        }
    }

    @Test fun hugeTextRetainsOnlyFourCodePointsOnEachSide() {
        val text = "甲😀乙😁".repeat(100_000)
        val caret = text.length / 2
        val preview = CursorMagnifierText.from(text, caret)!!
        assertEquals("甲😀乙😁甲😀乙😁", preview.text)
        assertEquals(6, preview.caret)
        assertEquals(4, preview.text.codePointCount(0, preview.caret))
        assertEquals(4, preview.text.codePointCount(preview.caret, preview.text.length))
    }

    @Test fun popupFollowsTextInsteadOfKeyboardAndPrefersAboveTheText() {
        val screen = IntRect(0, 0, 400, 850)
        val position = CursorMagnifierPosition(HardwareCursorAnchor(200f, 300f, 200f, 324f), screen, IntOffset.Zero, 8)
        val capsule = IntSize(280, 56)
        assertEquals(IntOffset(60, 236), position.calculatePosition(IntRect(0, 520, 400, 850), IntSize(400, 850), LayoutDirection.Ltr, capsule))
        assertEquals(IntOffset(60, 236), position.calculatePosition(IntRect(40, 650, 350, 800), IntSize(400, 850), LayoutDirection.Rtl, capsule))
        // The IME window starts at screen y=520: a negative local y is essential to follow the editor.
        val ime = CursorMagnifierPosition(HardwareCursorAnchor(200f, 300f, 200f, 324f), screen, IntOffset(0, 520), 8)
        assertEquals(IntOffset(60, -284), ime.calculatePosition(IntRect(0, 0, 400, 330), IntSize(400, 330), LayoutDirection.Ltr, capsule))
    }

    @Test fun popupUsesBelowOnlyWhenAboveDoesNotFitAndClampsToScreenEdges() {
        val screen = IntRect(0, 0, 400, 850)
        val capsule = IntSize(280, 56)
        fun position(caret: HardwareCursorAnchor) = CursorMagnifierPosition(caret, screen, IntOffset.Zero, 8)
            .calculatePosition(IntRect.Zero, IntSize(400, 850), LayoutDirection.Ltr, capsule)
        assertEquals(IntOffset(0, 42), position(HardwareCursorAnchor(10f, 10f, 10f, 34f)))
        assertEquals(IntOffset(120, 736), position(HardwareCursorAnchor(390f, 800f, 390f, 824f)))
        val split = CursorMagnifierPosition(HardwareCursorAnchor(500f, 300f, 500f, 324f),
            IntRect(200, 100, 800, 700), IntOffset(200, 520), 8)
        assertEquals(IntOffset(160, -284), split.calculatePosition(IntRect.Zero, IntSize(600, 180), LayoutDirection.Ltr, capsule))
    }
}
