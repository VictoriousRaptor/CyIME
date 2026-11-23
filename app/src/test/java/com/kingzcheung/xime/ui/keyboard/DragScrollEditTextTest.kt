package com.kingzcheung.xime.ui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 翻译栏一行输入框：横着拖是滚字（不拖光标），滚动范围不超出这一行字。 */
class DragScrollEditTextTest {
    @Test fun horizontalDragPastSlopStartsScrolling() {
        assertTrue(isHorizontalDrag(dx = 30f, dy = 4f, touchSlop = 16))
        assertTrue(isHorizontalDrag(dx = -30f, dy = 10f, touchSlop = 16))
    }

    @Test fun tapsJitterAndVerticalMovesStayWithTheEditText() {
        assertFalse(isHorizontalDrag(dx = 10f, dy = 0f, touchSlop = 16))
        assertFalse(isHorizontalDrag(dx = 20f, dy = 40f, touchSlop = 16))
    }

    @Test fun shortTextDoesNotScroll() {
        // 字 200 宽，框 600 宽
        assertEquals(0, clampTextScroll(150, lineLeft = 0, lineRight = 200, visibleWidth = 600))
        assertEquals(0, clampTextScroll(-80, lineLeft = 0, lineRight = 200, visibleWidth = 600))
    }

    @Test fun longTextScrollsUntilTheLastCharacterReachesTheRightEdge() {
        assertEquals(300, clampTextScroll(300, lineLeft = 0, lineRight = 1000, visibleWidth = 600))
        assertEquals(400, clampTextScroll(900, lineLeft = 0, lineRight = 1000, visibleWidth = 600))
        assertEquals(0, clampTextScroll(-50, lineLeft = 0, lineRight = 1000, visibleWidth = 600))
    }

    @Test fun cursorScrolledOutOfTheFieldIsNotDrawn() {
        // 字 1000 宽、框 600 宽，光标在末尾（x = 1000）
        assertFalse(cursorOutsideView(1000f, scrollX = 400, visibleWidth = 600)) // 滚到末尾：光标贴右边，照画
        assertTrue(cursorOutsideView(1000f, scrollX = 0, visibleWidth = 600))    // 拖回开头：光标在框外右边
        assertTrue(cursorOutsideView(0f, scrollX = 400, visibleWidth = 600))     // 光标在开头、框滚到末尾
        assertFalse(cursorOutsideView(500f, scrollX = 0, visibleWidth = 600))
    }

    @Test fun textScrollGoesBackToStartWhenClearedOrLeft() {
        assertTrue(shouldResetTextScroll(textEmpty = true, cursorWanted = true, focused = true))   // 字清空：提示语从头画
        assertTrue(shouldResetTextScroll(textEmpty = false, cursorWanted = false, focused = true)) // 打字去了应用输入框
        assertTrue(shouldResetTextScroll(textEmpty = false, cursorWanted = true, focused = false)) // 框没焦点
        assertFalse(shouldResetTextScroll(textEmpty = false, cursorWanted = true, focused = true)) // 正在栏里打字、拖动：不动
    }
}
