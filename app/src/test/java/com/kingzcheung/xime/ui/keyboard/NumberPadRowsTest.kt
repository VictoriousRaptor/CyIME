package com.kingzcheung.xime.ui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NumberPadRowsTest {
    @Test fun defaultKeeps123OnTop() {
        assertEquals(listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9")), numberPadRows(false))
    }

    @Test fun top789FlipsRowsButKeepsLeftToRight() {
        assertEquals(listOf(listOf("7", "8", "9"), listOf("4", "5", "6"), listOf("1", "2", "3")), numberPadRows(true))
    }

    @Test fun newSettingDefaultsToOldLayout() {
        assertFalse(KeyboardInputPreferences().numberPadTop789)
    }
}
