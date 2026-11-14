package com.kingzcheung.xime.ui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 长按时限：按键手势和键缝动效的长按进度用同一个数；算法和原来写在手势里的那一行一样。 */
class LongPressDelayTest {
    private val base = KeyGestureActions(text = "a", onTap = {}, onPress = {}, onRelease = {}, onPreview = {})

    @Test fun `no long press means no delay`() {
        assertNull(base.longPressDelayMillis(500L))
        // 只给了时长，没有长按动作也没有长按菜单：这个键没有长按。
        assertNull(base.copy(longPressTimeoutMillis = 300L).longPressDelayMillis(500L))
    }

    @Test fun `a menu waits 400 ms and a bare action waits the system timeout`() {
        assertEquals(400L, base.copy(longPressItems = listOf("1")).longPressDelayMillis(500L))
        assertEquals(500L, base.copy(onLongPress = {}).longPressDelayMillis(500L))
    }

    @Test fun `an explicit timeout wins`() {
        assertEquals(300L, base.copy(longPressItems = listOf("1"), longPressTimeoutMillis = 300L).longPressDelayMillis(500L))
        assertEquals(250L, base.copy(onLongPress = {}, longPressTimeoutMillis = 250L).longPressDelayMillis(500L))
    }

    @Test fun `long press input uses the configured delay`() {
        // 「长按输入」角标：按满设置里的时长就输入。
        val symbol = base.copy(upText = "1", onUp = { _: String -> })
            .withSymbolInput(SymbolInputMode.LONG_PRESS, "1", timeoutMillis = 350L)
        assertEquals(350L, symbol.longPressDelayMillis(500L))
    }
}
