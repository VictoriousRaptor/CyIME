package com.kingzcheung.xime.service

import org.junit.Assert.assertEquals
import org.junit.Test

/** 繁体输出时联想：上文转简体去查，联想词一次转成繁体，转完同形的只留一个。 */
class TraditionalAssociationTest {
    private val table = mapOf('们' to '們', '说' to '說', '话' to '話', '发' to '發', '后' to '後', '後' to '後')
    private var calls = 0
    private val bridge = PredictionManager.ScriptBridge(
        toSimplified = { text -> text.map { c -> table.entries.firstOrNull { it.value == c }?.key ?: c }.joinToString("") },
        toTraditional = { text -> calls++; text.map { table[it] ?: it }.joinToString("") },
    )

    @Test fun wordsAreConvertedInOneCall() {
        assertEquals(listOf("們", "說話", "發"), bridge.traditionalWords(listOf("们", "说话", "发")))
        assertEquals(1, calls)
    }

    @Test fun wordsThatBecomeTheSameAreShownOnce() {
        assertEquals(listOf("後來", "後"), bridge.traditionalWords(listOf("后來", "後來", "后")))
    }

    @Test fun contextIsLookedUpInSimplified() {
        assertEquals("我们说", bridge.toSimplified("我們說"))
    }

    @Test fun emptyListNeedsNoConversion() {
        assertEquals(emptyList<String>(), bridge.traditionalWords(emptyList()))
        assertEquals(0, calls)
    }
}
