package com.kingzcheung.xime.ui.keyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemicolonShiftKeyTest {
    @Test
    fun shiftBecomesIngOnlyWhileAFinalIsAwaited() {
        // 空闲、打完一个音节时 Shift 照旧；打了声母（或零声母的 o）等韵母时是 ing。
        assertFalse(awaitsDoublePinyinFinal(""))
        assertTrue(awaitsDoublePinyinFinal("b"))
        assertTrue(awaitsDoublePinyinFinal("o"))
        assertFalse(awaitsDoublePinyinFinal("b;"))
        assertTrue(awaitsDoublePinyinFinal("b;g"))
        assertFalse(awaitsDoublePinyinFinal("b;gr"))
        // 手动分隔符不算键。
        assertTrue(awaitsDoublePinyinFinal("b;'g"))
        assertFalse(awaitsDoublePinyinFinal("b;'gr"))
    }

    /** 11-15 双拼辅助码：打着拼音时 Shift 是「辅」；没打字、已经点过「辅」、或正等韵母（ing 在 Shift 上）时不是。 */
    @Test
    fun shiftBecomesAuxOnlyWhileComposingBeforeTheTrigger() {
        assertFalse(auxCodeKeyShown("", ingShown = false))
        assertTrue(auxCodeKeyShown("hc", ingShown = false))
        assertFalse(auxCodeKeyShown("hc`", ingShown = false))
        assertFalse(auxCodeKeyShown("hc`nz", ingShown = false))
        assertFalse(auxCodeKeyShown("h", ingShown = true))
    }
}
