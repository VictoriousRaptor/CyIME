package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.settings.LetterSwipeBindings
import com.kingzcheung.xime.ui.settings.t9SwipeActionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** #297：九键数字键下方角标那一滑可以设成全选、复制、粘贴等；默认不设，和以前一样。 */
class T9SwipeActionTest {
    @Test fun parseKeepsOnlyDigitLowerSlotsWithKnownActions() {
        val parsed = LetterSwipeBindings.parseT9(setOf("2.lower\tcopy", "3.lower\tpaste", "0.lower\tcopy",
            "2.upper\tcopy", "4.lower\tunknown", "5.lower\ttext:hi", "9.lower\tclipboard"))
        assertEquals(mapOf("2.lower" to "copy", "3.lower" to "paste", "9.lower" to "clipboard"), parsed)
    }

    @Test fun boundKeyRunsTheActionAndShowsItsName() {
        val base = T9KeySwipes(symbolInputText = "2", symbolInputDownText = "@", swipeDownText = "@", swipeDownKeyLabel = "@")
        val ran = mutableListOf<String>()
        val bound = base.withLowerAction("copy", showKeyLabel = true) { ran += it }
        assertEquals("2", bound.symbolInputText)
        assertNull(bound.symbolInputDownText)
        assertEquals("复制", bound.swipeDownText)
        assertEquals("复制", bound.swipeDownKeyLabel)
        bound.onSwipeDown!!.invoke()
        assertEquals(listOf("copy"), ran)
        // 角标提示关着：键上不写，滑动照样执行。
        assertNull(base.withLowerAction("copy", showKeyLabel = false) {}.swipeDownKeyLabel)
    }

    @Test fun unboundKeyIsUnchanged() {
        val base = T9KeySwipes(symbolInputText = "2")
        assertSame(base, base.withLowerAction(null, true) {})
        assertSame(base, base.withLowerAction("copy", true, null))
    }

    @Test fun summaryListsBoundKeysInOrder() {
        assertNull(t9SwipeActionSummary(emptyMap()))
        assertEquals("2 复制　5 粘贴", t9SwipeActionSummary(mapOf("5.lower" to "paste", "2.lower" to "copy")))
    }
}
