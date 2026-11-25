package com.kingzcheung.xime.ui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

/** #426：上下滑输入符号的距离可调，默认和原来写死的 50dp 一样。 */
class SymbolSwipeDistanceTest {
    @Test fun defaultIsTheOldFixedDistance() {
        assertEquals(50f, KeyboardInputPreferences().symbolSwipeDp, 0f)
        assertEquals(50f, normalizeSymbolSwipeDistance(DEFAULT_SYMBOL_SWIPE_DP), 0f)
    }

    @Test fun clampsAndSnapsToFourDpSteps() {
        assertEquals(30f, normalizeSymbolSwipeDistance(10f), 0f)
        assertEquals(90f, normalizeSymbolSwipeDistance(200f), 0f)
        assertEquals(62f, normalizeSymbolSwipeDistance(61f), 0f)
        assertEquals(50f, normalizeSymbolSwipeDistance(Float.NaN), 0f)
    }
}
