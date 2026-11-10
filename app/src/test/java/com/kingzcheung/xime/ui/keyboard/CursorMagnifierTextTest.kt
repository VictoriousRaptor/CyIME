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
        assertEquals(CursorMagnifierText("", 0), CursorMagnifierText.from("", 0))
        assertNull(CursorMagnifierText.from("左右", -1))
        assertNull(CursorMagnifierText.from("左右", 3))
    }

    @Test fun previewIsBoundedAndKeepsSurrogatePairsAndLineBreakOffsets() {
        val text = "😀".repeat(100)
        val preview = CursorMagnifierText.from(text, 101)!!
        assertEquals(64, preview.caret)
        assertEquals("😀".repeat(64), preview.text)
        assertEquals(CursorMagnifierText("甲↵😀 乙", 5), CursorMagnifierText.from("甲\n😀\t乙", 5))
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
