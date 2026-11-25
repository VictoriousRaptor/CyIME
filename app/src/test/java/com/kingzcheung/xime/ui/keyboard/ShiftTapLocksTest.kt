package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.viewmodel.KeyboardShiftState
import com.kingzcheung.xime.viewmodel.ShiftMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 用户 11-23 转群友：想点一下 Shift 就一直大写。加「点一下 Shift 锁定大写」，默认关，关着照旧。
 */
class ShiftTapLocksTest {
    @Test fun `default keeps single tap and double tap lock`() {
        assertFalse("默认关", KeyboardInputPreferences().shiftTapLocks)
        assertEquals("shift_single", shiftTapAction(tapLocks = false, doubleTap = false))
        assertEquals("shift_caps", shiftTapAction(tapLocks = false, doubleTap = true))
    }

    @Test fun `with the option every tap toggles the lock`() {
        assertEquals("shift_caps", shiftTapAction(tapLocks = true, doubleTap = false))
        assertEquals("shift_caps", shiftTapAction(tapLocks = true, doubleTap = true))
    }

    /** 键盘把 shift_caps 交给 doubleTap：点一下锁定，打字不掉，再点一下回小写。 */
    @Test fun `one tap locks, typing keeps it, next tap unlocks`() {
        val shift = KeyboardShiftState()
        shift.doubleTap()
        assertEquals(ShiftMode.CAPS, shift.mode.value)
        repeat(3) { shift.onCharacterTyped() }
        assertEquals(ShiftMode.CAPS, shift.mode.value)
        shift.doubleTap()
        assertEquals(ShiftMode.OFF, shift.mode.value)
        // 句首自动大写（单次大写）时点一下也是锁定。
        shift.setShifted(true)
        assertEquals(ShiftMode.SINGLE, shift.mode.value)
        shift.doubleTap()
        assertEquals(ShiftMode.CAPS, shift.mode.value)
    }
}
