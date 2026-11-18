package com.kingzcheung.xime.service

import com.kingzcheung.xime.rime.RimeEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SentenceSelectionMappingTest {
    private fun session(input: String, text: String, bounds: List<Int>, start: Int, end: Int) =
        SentenceEditSession(1, "double_pinyin_flypy", RimeEngine.SentenceEditSnapshot(input, text, bounds, 1), start, end)

    @Test fun mapsSelectionToRawAndFullPinyinWithoutChangingText() {
        val selected = session("nihk", "你好", listOf(0, 2, 4), 1, 2)
        assertEquals("hk", "nihk".substring(selected.displaySelection("nihk")!!))
        assertEquals("hk", "ni'hk".substring(selected.displaySelection("ni'hk")!!))
        assertEquals("hao", "ni'hao".substring(selected.displaySelection("ni'hao")!!))
    }

    @Test fun mapsSingleLetterCodeAndRejectsUnrelatedDisplay() {
        val selected = session("hnk", "好你", listOf(0, 1, 3), 0, 1)
        assertEquals("h", "h'nk".substring(selected.displaySelection("h'nk")!!))
        assertNull(selected.displaySelection("different"))
    }
    @Test fun originalReadingsFollowTheRemainingUnchangedCode() {
        val selected = session("nihkhv", "你好回", listOf(0, 2, 4, 6), 0, 3)
        assertEquals(0, selected.originalRemainingStart("nihkhv", ""))
        assertEquals(1, selected.originalRemainingStart("hkhv", "ni"))
        assertEquals(2, selected.originalRemainingStart("hv", "nihk"))
        assertNull(selected.originalRemainingStart("hkhv", "xx"))
        assertNull(selected.originalRemainingStart("zz", "ni"))
    }

    @Test fun originalCodeMatchingIgnoresSeparators() {
        val selected = session("ni'hk'hv", "你好回", listOf(0, 3, 6, 8), 0, 3)
        assertEquals(1, selected.originalRemainingStart("hkhv", "ni"))
        assertEquals(2, selected.originalRemainingStart("hv", "ni'hk"))
    }
}
