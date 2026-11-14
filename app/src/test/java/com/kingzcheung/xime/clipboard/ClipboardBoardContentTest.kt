package com.kingzcheung.xime.clipboard

import org.junit.Assert.*
import org.junit.Test

class ClipboardBoardContentTest {
    private fun text(id: Long, timestamp: Long = id) = ClipboardCard.Text(
        ClipboardItem(id, "记录 $id", timestamp = timestamp),
    )

    @Test fun hundredPinsCollapseToOneRowWithoutRemovingTheLatestCopies() {
        val fixed = (1L..100L).map { text(it) }
        val recent = listOf(text(102), text(101))
        val cards = fixed + recent
        val pins = fixed.mapTo(mutableSetOf()) { it.key }

        val content = clipboardBoardContent(cards, pins, columns = 2, pinsExpanded = false)

        assertEquals(100, content.pinnedCount)
        assertEquals(fixed.take(2), content.pinned)
        assertEquals(recent, content.recent)
        assertEquals(listOf("text:1", "text:2", "text:102", "text:101"), content.visible.map { it.key })
        assertEquals("Presentation must not mutate stored history", 102, cards.size)
    }

    @Test fun oneRecordPerRowPutsRecentPicturesInOneRowWhereTheNewestWas() {
        fun image(n: Int) = ClipboardCard.Image(ClipboardImage("content://media/external/images/media/$n", "image/png", n.toLong()))
        val recent = listOf(text(9), image(8), text(7), image(6), text(5))
        val strip = clipboardImageStrip(recent)
        assertEquals(listOf(text(9)), strip.before)
        assertEquals(listOf(image(8), image(6)), strip.images)
        assertEquals(listOf(text(7), text(5)), strip.after)
        // Keyboard focus walks the records in the order shown.
        assertEquals(listOf(text(9), image(8), image(6), text(7), text(5)), strip.ordered)
        assertEquals(ClipboardImageStrip(listOf(text(2), text(1)), emptyList(), emptyList()),
            clipboardImageStrip(listOf(text(2), text(1))))
        // A tab of nothing but pictures keeps its columns; text tabs follow the switch.
        assertFalse(clipboardListLayout(true, listOf(image(1), image(2))))
        assertTrue(clipboardListLayout(true, recent))
        assertFalse(clipboardListLayout(false, recent))
    }

    @Test fun pinsAreNotFoldedWhenNothingUnpinnedIsBelow() {
        // Reported case: a category whose four records are all pinned showed only one row.
        val fixed = (1L..4L).map { text(it) }
        val pins = fixed.mapTo(mutableSetOf()) { it.key }

        val content = clipboardBoardContent(fixed, pins, columns = 2, pinsExpanded = false)

        assertEquals(fixed, content.pinned)
        assertFalse("no fold toggle without unpinned records", content.pinsCollapsible)
        val withRecent = clipboardBoardContent(fixed + text(5), pins, columns = 2, pinsExpanded = false)
        assertEquals(fixed.take(2), withRecent.pinned)
        assertTrue(withRecent.pinsCollapsible)
    }

    @Test fun expandingAndCollapsingPreserveAllIdentitiesAndOrdering() {
        val image = ClipboardCard.Image(ClipboardImage("content://image/pinned", "image/png", 10))
        val cards = listOf(image, text(2), text(3), text(100))
        val pins = cards.take(3).mapTo(mutableSetOf()) { it.key }

        val collapsed = clipboardBoardContent(cards, pins, columns = 1, pinsExpanded = false)
        val expanded = clipboardBoardContent(cards, pins, columns = 1, pinsExpanded = true)
        val collapsedAgain = clipboardBoardContent(cards, pins, columns = 1, pinsExpanded = false)

        assertEquals(cards, expanded.visible)
        assertSame(image, expanded.pinned.first())
        assertEquals(collapsed, collapsedAgain)
        assertEquals(listOf(text(100)), expanded.recent)
    }

    @Test fun changingWidthOnlyChangesTheVisiblePinRow() {
        val cards = (1L..9L).map { text(it) }
        val pins = cards.take(8).mapTo(mutableSetOf()) { it.key }
        for ((width, expectedCount) in listOf(240f to 1, 360f to 2, 1000f to 5)) {
            val content = clipboardBoardContent(cards, pins, clipboardColumnCount(width), pinsExpanded = false)
            assertEquals(expectedCount, content.pinned.size)
            assertEquals(listOf(text(9)), content.recent)
            assertEquals(8, content.pinnedCount)
        }
    }

    @Test fun currentCategoryHasItsOwnPinnedCountAndRecentHistory() {
        val image = ClipboardImage("content://image/pinned", "image/png", 3)
        val text = listOf(ClipboardItem(1, "固定文字", timestamp = 1), ClipboardItem(2, "https://example.com", timestamp = 5))
        val pins = setOf("text:1", "image:${image.uri}")

        val images = clipboardBoardContent(clipboardCards(text, listOf(image), ClipboardFilter.IMAGE, pins), pins, 1, false)
        assertEquals(1, images.pinnedCount)
        assertEquals(listOf("image:${image.uri}"), images.visible.map { it.key })
        assertTrue(images.recent.isEmpty())

        val links = clipboardBoardContent(clipboardCards(text, listOf(image), ClipboardFilter.LINK, pins), pins, 1, false)
        assertEquals(0, links.pinnedCount)
        assertEquals(listOf("text:2"), links.visible.map { it.key })
    }

    @Test fun keyboardNavigationUsesOnlyTheCollapsedProjection() {
        val cards = (1L..100L).map { text(it) } + text(101)
        val pins = cards.take(100).mapTo(mutableSetOf()) { it.key }
        val keys = clipboardBoardContent(cards, pins, 2, false).visible.map { it.key }

        assertEquals("text:101", nextClipboardKey(keys, "text:2", ClipboardNavigation.RIGHT, 2, emptyList()))
        assertEquals("text:101", nextClipboardKey(keys, "text:1", ClipboardNavigation.DOWN, 2, emptyList()))
        assertEquals("text:101", nextClipboardKey(keys, "text:101", ClipboardNavigation.RIGHT, 2, emptyList()))
        assertEquals("text:1", nextClipboardKey(keys, "text:101", ClipboardNavigation.UP, 2, emptyList()))
    }

    @Test fun pinPreviewLeavesMostOfTheViewportForRecentCopiesAtEveryHeight() {
        for (height in listOf(0f, 90f, 156f, 296f, 1000f)) {
            val preview = clipboardPinnedPreviewHeight(height)
            assertTrue("Pinned preview cannot exceed 35% at $height", preview <= height * .35f)
            assertTrue("Tablet preview has a readable upper bound", preview <= 112f)
            assertTrue(preview >= 0f)
        }
        assertEquals(0f, clipboardPinnedPreviewHeight(-24f), 0f)
    }
}
