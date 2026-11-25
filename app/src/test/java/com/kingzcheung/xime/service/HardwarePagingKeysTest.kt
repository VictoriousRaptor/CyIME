package com.kingzcheung.xime.service

import android.view.KeyEvent
import com.kingzcheung.xime.settings.HardwareKeyboardOptions
import org.junit.Assert.assertEquals
import org.junit.Test

class HardwarePagingKeysTest {
    private val previous = listOf(KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_LEFT_BRACKET, KeyEvent.KEYCODE_COMMA)
    private val next = listOf(KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_RIGHT_BRACKET, KeyEvent.KEYCODE_PERIOD)

    @Test fun compositionReservesAllPagingKeysEvenWithoutCandidates() {
        previous.forEach { assertEquals(-1, hardwareCandidatePageDirection(it, hasInput = true, modified = false)) }
        next.forEach { assertEquals(1, hardwareCandidatePageDirection(it, hasInput = true, modified = false)) }
    }

    @Test fun symbolsOnlyReturnWhenCompositionIsEmpty() {
        (previous + next).forEach { assertEquals(0, hardwareCandidatePageDirection(it, hasInput = false, modified = false)) }
        for ((key, text) in listOf(KeyEvent.KEYCODE_MINUS to "-", KeyEvent.KEYCODE_EQUALS to "=",
            KeyEvent.KEYCODE_LEFT_BRACKET to "[", KeyEvent.KEYCODE_RIGHT_BRACKET to "]",
            KeyEvent.KEYCODE_COMMA to ",", KeyEvent.KEYCODE_PERIOD to ".")) {
            assertEquals(text, keyCodeToKey(key, false))
        }
        assertEquals("<", keyCodeToKey(KeyEvent.KEYCODE_COMMA, true))
        assertEquals(">", keyCodeToKey(KeyEvent.KEYCODE_PERIOD, true))
    }

    @Test fun controlAltAndMetaChordsAreNotPagingCommands() {
        (previous + next).forEach { assertEquals(0, hardwareCandidatePageDirection(it, true, modified = true)) }
        assertEquals(0, hardwareCandidatePageDirection(KeyEvent.KEYCODE_A, true, false))
    }

    @Test fun groupsCanBeSelectedIndependentlyInAllEightCombinations() {
        for (mask in 0..7) {
            val options = HardwareKeyboardOptions(
                pageMinusEquals = mask and 1 != 0,
                pageBrackets = mask and 2 != 0,
                pageCommaPeriod = mask and 4 != 0,
            )
            for (group in previous.indices) {
                val enabled = mask and (1 shl group) != 0
                assertEquals("previous group $group mask $mask", if (enabled) -1 else 0,
                    hardwareCandidatePageDirection(previous[group], true, false, options))
                assertEquals("next group $group mask $mask", if (enabled) 1 else 0,
                    hardwareCandidatePageDirection(next[group], true, false, options))
                for (key in listOf(previous[group], next[group])) {
                    assertEquals(0, hardwareCandidatePageDirection(key, false, false, options))
                    assertEquals(0, hardwareCandidatePageDirection(key, true, true, options))
                }
            }
        }
    }

    @Test fun angleBracketsFollowTheCommaPeriodChoiceAndKeepLiteralText() {
        // Shift changes the symbol, not the physical key's paging group.
        for ((key, direction, symbol) in listOf(
            Triple(KeyEvent.KEYCODE_COMMA, -1, "<"),
            Triple(KeyEvent.KEYCODE_PERIOD, 1, ">"),
        )) {
            assertEquals(symbol, keyCodeToKey(key, true))
            assertEquals(direction, hardwareCandidatePageDirection(key, true, false))
            assertEquals(0, hardwareCandidatePageDirection(key, true, false,
                HardwareKeyboardOptions(pageCommaPeriod = false)))
        }
    }

    /** #549：关掉的翻页键不进翻页分支，和别的符号键一样交给方案标点表；外层判定要看用户设置。 */
    @Test fun theOuterPagingCheckReadsTheUsersOptions() {
        val src = java.io.File("src/main/java/com/kingzcheung/xime/service/XimeInputMethodService.kt").readText()
        val outer = src.substringAfter("val pagingKey = hardwareCandidatePageDirection(").substringBefore("!= 0")
        org.junit.Assert.assertTrue(outer, "options = uiState.value.hardwareOptions" in outer)
        val off = HardwareKeyboardOptions(pageCommaPeriod = false)
        assertEquals(0, hardwareCandidatePageDirection(KeyEvent.KEYCODE_COMMA, true, false, off))
        assertEquals(0, hardwareCandidatePageDirection(KeyEvent.KEYCODE_PERIOD, true, false, off))
    }
}
