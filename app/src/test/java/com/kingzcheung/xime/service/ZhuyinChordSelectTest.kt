package com.kingzcheung.xime.service

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 注音（大千）实体键盘：Shift+数字（Rime）、Ctrl+数字（微软注音）选第 N 个候选，光按数字照旧是注音键（用户 11-22）。 */
class ZhuyinChordSelectTest {
    private fun index(code: Int, shift: Boolean = false, ctrl: Boolean = false, alt: Boolean = false, meta: Boolean = false) =
        zhuyinChordDigitIndex(code, shift, ctrl, alt, meta)

    @Test fun shiftOrCtrlWithNumberRowPicksThatCandidate() {
        for (n in 1..9) {
            assertEquals(n - 1, index(KeyEvent.KEYCODE_0 + n, shift = true))
            assertEquals(n - 1, index(KeyEvent.KEYCODE_0 + n, ctrl = true))
        }
        assertEquals(9, index(KeyEvent.KEYCODE_0, shift = true))
        assertEquals(9, index(KeyEvent.KEYCODE_0, ctrl = true))
    }

    @Test fun plainDigitsStayZhuyinKeys() {
        for (n in 0..9) assertNull(index(KeyEvent.KEYCODE_0 + n))
    }

    @Test fun otherChordsAndKeysAreLeftAlone() {
        assertNull(index(KeyEvent.KEYCODE_1, shift = true, ctrl = true))
        assertNull(index(KeyEvent.KEYCODE_1, ctrl = true, alt = true))
        assertNull(index(KeyEvent.KEYCODE_1, shift = true, meta = true))
        assertNull(index(KeyEvent.KEYCODE_NUMPAD_1, shift = true))
        assertNull(index(KeyEvent.KEYCODE_NUMPAD_1, ctrl = true))
        assertNull(index(KeyEvent.KEYCODE_A, ctrl = true))
        assertNull(index(KeyEvent.KEYCODE_COMMA, shift = true))
    }
}
