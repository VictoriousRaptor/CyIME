package com.kingzcheung.xime.ui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

/** #362：符号键当 Ctrl 的时限最短能调到 0.1 秒；默认还是 1 秒。 */
class SymbolKeyHoldRangeTest {
    @Test fun shortestIsOneTenthAndDefaultStaysOne() {
        assertEquals(0.1f, normalizeSymbolKeyHoldSeconds(0.1f))
        assertEquals(0.1f, normalizeSymbolKeyHoldSeconds(0f))
        assertEquals(0.3f, normalizeSymbolKeyHoldSeconds(0.3f))
        assertEquals(2f, normalizeSymbolKeyHoldSeconds(5f))
        assertEquals(1f, normalizeSymbolKeyHoldSeconds(Float.NaN))
        assertEquals(1f, KeyboardInputPreferences().symbolKeyHoldSeconds)
    }
}
