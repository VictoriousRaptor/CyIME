package com.kingzcheung.xime.ui.menubar

import com.kingzcheung.xime.clipboard.ClipboardCard
import com.kingzcheung.xime.clipboard.ClipboardItem
import org.junit.Assert.assertEquals
import org.junit.Test

/** 用户 11-11 转述：多选全选后删除，固定的也被选上，要一条条点掉。全选不含固定的。 */
class ClipboardSelectAllTest {
    private fun text(id: Long) = ClipboardCard.Text(ClipboardItem(id, "记录$id", timestamp = id))

    @Test fun selectAllLeavesFixedRecordsOut() {
        val cards = listOf(text(1), text(2), text(3))
        assertEquals(setOf("text:2", "text:3"), clipboardSelectAllKeys(cards, pins = setOf("text:1")))
    }

    @Test fun onlyFixedRecordsInViewAreAllSelected() {
        val cards = listOf(text(1), text(2))
        assertEquals(setOf("text:1", "text:2"), clipboardSelectAllKeys(cards, pins = setOf("text:1", "text:2")))
    }

    @Test fun nothingInViewSelectsNothing() {
        assertEquals(emptySet<String>(), clipboardSelectAllKeys(emptyList(), pins = setOf("text:1")))
    }
}
