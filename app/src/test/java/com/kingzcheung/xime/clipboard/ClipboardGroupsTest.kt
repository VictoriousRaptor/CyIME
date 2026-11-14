package com.kingzcheung.xime.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardGroupsTest {
    private val phrases = ClipboardGroup("g1", "常用语")
    private val work = ClipboardGroup("g2", "工作")
    private val state = ClipboardGroupState(listOf(phrases, work), mapOf("text:1" to "g1", "image:content://a" to "g2"))
    private fun text(id: Long, value: String) = ClipboardCard.Text(ClipboardItem(id, value))

    @Test fun groupsComeAfterTheBuiltInCategoriesAndHiddenOnesLeave() {
        assertEquals(ClipboardFilter.entries.map { it.name } + listOf("group:g1", "group:g2"), clipboardTabs(state).map { it.key })
        val hidden = state.withHidden(ClipboardFilter.EMAIL, true).withHidden(ClipboardFilter.ALL, true)
        val keys = clipboardTabs(hidden).map { it.key }
        assertFalse("EMAIL" in keys)
        assertEquals("全部 stays", "ALL", keys.first())
    }

    @Test fun aGroupKeepsTheOrderItsRecordsWereDraggedTo() {
        val grouped = state.assigned(listOf("text:2", "text:3", "text:4"), "g1")
        // Never sorted: newest first, as before.
        val shown = listOf(text(4, "d"), text(3, "c"), text(2, "b"), text(1, "a"))
        assertEquals(shown, grouped.inRecordOrder("g1", shown))
        val sorted = grouped.withRecordOrder("g1", listOf("text:1", "text:3", "text:2", "text:4", "image:content://a"))
        // Another group's record is not kept in this group's order.
        assertEquals(listOf("text:1", "text:3", "text:2", "text:4"), sorted.recordOrder["g1"])
        assertEquals(listOf("text:1", "text:3", "text:2", "text:4"), sorted.inRecordOrder("g1", shown).map { it.key })
        // A record put in since comes first; one taken out leaves the order.
        val later = sorted.assigned(listOf("text:5"), "g1").assigned(listOf("text:3"), null)
        assertEquals(listOf("text:1", "text:2", "text:4"), later.recordOrder["g1"])
        assertEquals(listOf("text:5", "text:1", "text:2", "text:4"),
            later.inRecordOrder("g1", listOf(text(5, "e"), text(4, "d"), text(2, "b"), text(1, "a"))).map { it.key })
        // Deleting the group drops its order; the order survives a save and reload.
        assertNull(later.withoutGroup("g1").recordOrder["g1"])
        val saved = ClipboardGroupCodec.encodeRecordOrder(later.recordOrder)
        assertEquals(later.recordOrder, ClipboardGroupCodec.decodeRecordOrder(saved, listOf(phrases, work)))
        assertTrue(ClipboardGroupCodec.decodeRecordOrder(saved, listOf(work)).isEmpty())
        assertTrue(ClipboardGroupCodec.decodeRecordOrder(null, listOf(phrases)).isEmpty())
    }

    @Test fun aGroupTabShowsOnlyItsRecords() {
        val tab = ClipboardTab.Group(phrases)
        assertTrue(clipboardMatches(text(1, "好的"), tab, state))
        assertFalse(clipboardMatches(text(2, "好的"), tab, state))
        assertFalse(clipboardMatches(ClipboardCard.Image(ClipboardImage("content://b", "image/png", 0L)), tab, state))
        // Built-in categories still match by content, groups or not.
        assertTrue(clipboardMatches(text(1, "https://example.com"), ClipboardTab.Category(ClipboardFilter.LINK), state))
    }

    @Test fun mixedOrderSurvivesHidingRenamingAndMembershipChanges() {
        val moved = state.reordered(listOf("ALL", "group:g2", "IMAGE", "group:g2", "missing", "group:g1"))
        assertEquals(listOf("ALL", "group:g2", "IMAGE", "group:g1", "TEXT", "LINK", "ADDRESS", "PHONE", "EMAIL"),
            clipboardTabs(moved).map { it.key })
        assertEquals(listOf(work, phrases), moved.groups)
        assertEquals(state.members, moved.members)
        val hidden = moved.withHidden(ClipboardFilter.IMAGE, true).renamed("g2", "项目")
        assertEquals("项目", clipboardTabs(hidden)[1].label)
        assertEquals("group:g1", clipboardTabs(hidden)[2].key)
        assertEquals("IMAGE", clipboardTabs(hidden.withHidden(ClipboardFilter.IMAGE, false))[2].key)
        val changed = hidden.withoutGroup("g1").withGroup(ClipboardGroup("g3", "家人"))
        assertFalse("group:g1" in changed.tabOrder)
        assertEquals("group:g3", clipboardTabs(changed).last().key)
        assertEquals("g2", changed.members["image:content://a"])
    }

    @Test fun swipeAndResolveCrossFromCategoriesIntoGroups() {
        val tabs = clipboardTabs(state)
        val image = ClipboardTab.Category(ClipboardFilter.IMAGE)
        assertEquals("group:g1", tabs.afterSwipe(image, 1).key)
        assertEquals("group:g2", tabs.afterSwipe(ClipboardTab.Group(work), 1).key)
        assertEquals("IMAGE", tabs.afterSwipe(ClipboardTab.Group(phrases), -1).key)
        // A renamed group keeps its tab; a deleted one falls back to 全部.
        assertEquals("工作日", clipboardTabs(state.renamed("g2", "工作日")).resolve(ClipboardTab.Group(work)).label)
        assertEquals("ALL", clipboardTabs(state.withoutGroup("g2")).resolve(ClipboardTab.Group(work)).key)
    }

    @Test fun oneGroupPerRecordAndDeletingAGroupKeepsNothingBehind() {
        val moved = state.assigned(listOf("text:1"), "g2")
        assertEquals("g2", moved.members["text:1"])
        assertNull(state.assigned(listOf("text:1"), null).members["text:1"])
        // A group that does not exist (deleted meanwhile) takes the record out instead.
        assertNull(state.assigned(listOf("text:1"), "gone").members["text:1"])
        val deleted = state.withoutGroup("g1")
        assertEquals(listOf(work), deleted.groups)
        assertEquals(mapOf("image:content://a" to "g2"), deleted.members)
        assertEquals(1, state.count("g1"))
    }

    @Test fun namesAreCheckedAgainstGroupsAndCategories() {
        assertNotNull(clipboardGroupNameError("  ", state.groups))
        assertNotNull(clipboardGroupNameError("地址", state.groups))
        assertNotNull(clipboardGroupNameError("常用语", state.groups))
        assertNull(clipboardGroupNameError("常用语", state.groups, renaming = "g1"))
        assertNull(clipboardGroupNameError(" 家人 ", state.groups))
        assertNotNull(clipboardGroupNameError("一二三四五六七八九十一", state.groups))
        assertEquals("家人", cleanClipboardGroupName("\t家人\n"))
    }

    @Test fun preferencesRoundTrip() {
        val groups = ClipboardGroupCodec.decodeGroups(ClipboardGroupCodec.encodeGroups(state.groups))
        assertEquals(state.groups, groups)
        assertEquals(state.members, ClipboardGroupCodec.decodeMembers(ClipboardGroupCodec.encodeMembers(state.members), groups))
        // Members of a group no longer listed, and broken lines, are dropped.
        assertEquals(mapOf("text:1" to "g1"),
            ClipboardGroupCodec.decodeMembers(setOf("g1\ttext:1", "g9\ttext:2", "broken", "g1\t"), listOf(phrases)))
        assertEquals(setOf(ClipboardFilter.LINK), ClipboardGroupCodec.decodeHidden(setOf("LINK", "ALL", "NOPE")))
        assertTrue(ClipboardGroupCodec.decodeGroups(null).isEmpty())
    }
}
