package com.kingzcheung.xime.ui.menubar

import com.kingzcheung.xime.clipboard.ClipboardCard
import com.kingzcheung.xime.clipboard.ClipboardItem
import org.junit.Assert.assertEquals
import org.junit.Test

/** 用户 11-11：多选的全选圈放到每一栏，固定栏和下面那栏各选各的。 */
class ClipboardSelectAllTest {
    private fun text(id: Long) = ClipboardCard.Text(ClipboardItem(id, "记录$id", timestamp = id))
    private val cards = listOf(text(1), text(2), text(3))
    private val pins = setOf("text:1")

    @Test fun eachSectionSelectsOnlyItsOwnRecords() {
        assertEquals(setOf("text:1"), clipboardSectionKeys(cards, pins, fixed = true))
        assertEquals(setOf("text:2", "text:3"), clipboardSectionKeys(cards, pins, fixed = false))
        assertEquals(emptySet<String>(), clipboardSectionKeys(emptyList(), pins, fixed = false))
    }

    @Test fun togglingOneSectionLeavesTheOtherAlone() {
        val recent = clipboardSectionKeys(cards, pins, fixed = false)
        // Some records picked by hand, one of them fixed: select-all of the lower section keeps the fixed one.
        val picked = toggleClipboardSection(setOf("text:1", "text:2"), recent)
        assertEquals(setOf("text:1", "text:2", "text:3"), picked)
        // Tapping it again clears only that section.
        assertEquals(setOf("text:1"), toggleClipboardSection(picked, recent))
        // An empty section changes nothing.
        assertEquals(setOf("text:2"), toggleClipboardSection(setOf("text:2"), emptySet()))
    }
}
