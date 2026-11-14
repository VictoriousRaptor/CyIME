package com.kingzcheung.xime.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardCapacityTest {
    @Test fun aFixedCeilingUnlessThePhoneIsNearlyFull() {
        assertEquals(ClipboardCapacity.CEILING_BYTES, ClipboardCapacity.capacity(100L shl 30))
        // 2 % of 30 MB free is the tighter bound.
        assertEquals(600_000L, ClipboardCapacity.capacity(30_000_000L))
    }

    @Test fun recordsCountTheirUtf8TextAndSearchKey() {
        assertEquals(6L + 2, ClipboardCapacity.recordBytes("地址", "dz"))
        assertTrue(ClipboardCapacity.accepts(90, 10, 100))
        assertFalse(ClipboardCapacity.accepts(95, 10, 100))
    }

    @Test fun onlyRowsTheDatabaseCannotReadBackAreRefused() {
        assertTrue(ClipboardCapacity.fitsRow("a".repeat(ClipboardCapacity.ROW_BYTES)))
        assertFalse(ClipboardCapacity.fitsRow("a".repeat(ClipboardCapacity.ROW_BYTES + 1)))
        // Three bytes per character in UTF-8: a long Chinese copy is measured in bytes.
        assertFalse(ClipboardCapacity.fitsRow("地".repeat(ClipboardCapacity.ROW_BYTES / 3 + 1)))
    }

    @Test fun theOldestHistoryMakesWayForANewCopy() {
        // Fits as is: nothing goes.
        assertEquals(0, ClipboardCapacity.oldestToRemove(listOf(10, 10), 50, 10, 100))
        // 95 stored, 10 more: the oldest 10 bytes go.
        assertEquals(1, ClipboardCapacity.oldestToRemove(listOf(10, 10, 10), 95, 10, 100))
        assertEquals(2, ClipboardCapacity.oldestToRemove(listOf(5, 5, 30), 100, 10, 100))
        // Fixed and grouped records alone fill the room: history is not enough.
        assertEquals(null, ClipboardCapacity.oldestToRemove(listOf(5), 100, 10, 100))
        // A copy over a quarter of the room never empties the history for itself.
        assertEquals(null, ClipboardCapacity.oldestToRemove(listOf(50, 40), 90, 30, 100))
        assertEquals(0, ClipboardCapacity.oldestToRemove(listOf(50), 50, 30, 100))
    }

    @Test fun fullWhenARecordWasRefusedOrNothingIsLeft() {
        assertFalse(ClipboardRoom(10, 100).full)
        assertTrue(ClipboardRoom(10, 100, refused = true).full)
        assertTrue(ClipboardRoom(100, 100).full)
    }
}
