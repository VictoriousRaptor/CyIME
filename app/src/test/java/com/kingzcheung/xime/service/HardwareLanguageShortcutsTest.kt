package com.kingzcheung.xime.service

import android.view.KeyEvent.*
import com.kingzcheung.xime.settings.HardwareKeyboardOptions
import org.junit.Assert.*
import org.junit.Test

class HardwareLanguageShortcutsTest {
    private val s = HardwareLanguageShortcuts()
    private val options = HardwareKeyboardOptions()
    private fun down(key: Int, time: Long = 0, repeat: Int = 0, ctrl: Boolean = false, alt: Boolean = false,
        meta: Boolean = false, shifted: Boolean = false, settings: HardwareKeyboardOptions = options) =
        s.down(key, time, repeat, ctrl, alt, meta, shifted, settings)
    @Test fun ctrlSpaceFiresOnceUntilRelease() {
        assertTrue(down(KEYCODE_SPACE, ctrl = true).switchLanguage)
        assertEquals(HardwareShortcutResult(consume = true), down(KEYCODE_SPACE, repeat = 1, ctrl = true))
        assertTrue(s.up(KEYCODE_SPACE, 300, false, options).consume)
        assertTrue(down(KEYCODE_SPACE, ctrl = true).switchLanguage)
    }
    @Test fun tapEitherShiftButDoNotStealShiftChords() {
        for (shift in listOf(KEYCODE_SHIFT_LEFT, KEYCODE_SHIFT_RIGHT)) {
            assertTrue(down(shift, shifted = true).consume)
            assertTrue(s.up(shift, 100, false, options).switchLanguage)
            down(shift, shifted = true)
            assertFalse(down(KEYCODE_A, shifted = true).consume)
            assertFalse(s.up(shift, 100, false, options).switchLanguage)
            s.up(KEYCODE_A, 100, false, options)
        }
    }
    @Test fun heldCancelledOrBothShiftDoNotSwitch() {
        down(KEYCODE_SHIFT_LEFT)
        assertFalse(s.up(KEYCODE_SHIFT_LEFT, 600, false, options).switchLanguage)
        down(KEYCODE_SHIFT_LEFT)
        assertFalse(s.up(KEYCODE_SHIFT_LEFT, 100, true, options).switchLanguage)
        down(KEYCODE_SHIFT_LEFT); down(KEYCODE_SHIFT_RIGHT, shifted = true)
        assertFalse(s.up(KEYCODE_SHIFT_LEFT, 100, false, options).switchLanguage)
        assertFalse(s.up(KEYCODE_SHIFT_RIGHT, 100, false, options).switchLanguage)
    }
    @Test fun preferencesAndOtherModifiersRemainIndependent() {
        assertFalse(down(KEYCODE_SPACE, ctrl = true, settings = options.copy(ctrlSpace = false)).consume)
        assertFalse(down(KEYCODE_SHIFT_LEFT, settings = options.copy(shiftTap = false)).consume)
        assertFalse(down(KEYCODE_SPACE, ctrl = true, alt = true).consume)
        assertFalse(down(KEYCODE_SPACE, ctrl = true, shifted = true).consume)
        assertFalse(down(KEYCODE_SPACE, ctrl = true, meta = true).consume)
    }
    @Test fun resetPreventsSwitchingAcrossEditors() {
        down(KEYCODE_SHIFT_LEFT); s.reset()
        assertEquals(HardwareShortcutResult(), s.up(KEYCODE_SHIFT_LEFT, 100, false, options))
    }
    @Test fun shiftPressedAfterAnotherHeldKeyIsStillAChord() {
        down(KEYCODE_A)
        down(KEYCODE_SHIFT_LEFT, shifted = true)
        assertFalse(s.up(KEYCODE_SHIFT_LEFT, 100, false, options).switchLanguage)
    }

    @Test fun toolShortcutsOffLeavesTheChordsToTheApp() {
        val off = options.copy(toolShortcuts = false)
        assertEquals(HardwareShortcutResult(), down(KEYCODE_PERIOD, ctrl = true, settings = off))
        assertEquals(HardwareShortcutResult(), down(KEYCODE_V, alt = true, settings = off))
        assertEquals(HardwareShortcutResult(), down(KEYCODE_H, alt = true, settings = off))
        assertFalse(s.up(KEYCODE_V, 100, false, off).consume)
        // 语言切换不受这个开关影响。
        assertTrue(down(KEYCODE_SPACE, ctrl = true, settings = off).switchLanguage)
    }

    @Test fun toolShortcutsFireOnceAndConsumeReleaseRegardlessOfModifierOrder() {
        for ((key, tool) in listOf(KEYCODE_PERIOD to HardwareToolShortcut.PUNCTUATION,
                KEYCODE_V to HardwareToolShortcut.CLIPBOARD, KEYCODE_H to HardwareToolShortcut.MICROPHONE)) {
            s.reset()
            assertEquals(tool, down(key, ctrl = key == KEYCODE_PERIOD, alt = key != KEYCODE_PERIOD).tool)
            assertEquals(HardwareShortcutResult(consume = true), down(key, repeat = 1))
            assertTrue(s.up(key, 100, false, options).consume)
            assertFalse(down(key).consume)
        }
    }
    @Test fun toolShortcutsDoNotStealOtherChordsOrTriggerShiftLanguageSwitch() {
        assertFalse(down(KEYCODE_V, ctrl = true).consume)
        assertFalse(down(KEYCODE_H, alt = true, ctrl = true).consume)
        assertFalse(down(KEYCODE_PERIOD, ctrl = true, meta = true).consume)
        s.reset()
        down(KEYCODE_SHIFT_LEFT, shifted = true)
        assertFalse(down(KEYCODE_V, alt = true, shifted = true).consume)
        assertFalse(s.up(KEYCODE_SHIFT_LEFT, 100, false, options).switchLanguage)
        s.reset()
        assertNull(down(KEYCODE_H, repeat = 1, alt = true).tool)
    }

    /** GitHub #295: on keyboard phones Alt+V/H types a digit from the Alt layer, not a tool shortcut. */
    @Test fun altLayerCharacterIsNotAToolShortcut() {
        for (key in listOf(KEYCODE_V, KEYCODE_H)) {
            assertEquals(HardwareShortcutResult(), s.down(key, 0, 0, false, true, false, false, options, altTypesChar = true))
            assertFalse(s.up(key, 100, false, options).consume)
        }
        assertEquals(HardwareToolShortcut.CLIPBOARD, s.down(KEYCODE_V, 0, 0, false, true, false, false, options).tool)
    }
}
