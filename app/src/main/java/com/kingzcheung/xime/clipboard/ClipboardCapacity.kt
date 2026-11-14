package com.kingzcheung.xime.clipboard

/**
 * How much clipboard history is kept: a fixed ceiling on the stored text (the user asked on
 * 2026-10-22 for a sensible fixed limit instead of measuring the phone), and never more than
 * [DISK_SHARE] of the free space. When a new record does not fit, the oldest history (not fixed, not
 * in a group) makes way, whatever 「数量上限」 and 「保留时间」 say (user 11-12: 「不限」 yet 「剪贴板已满」);
 * only when fixed and grouped records alone leave no room is the new one not saved.
 */
internal object ClipboardCapacity {
    /** All stored text (fixed and history) together: about ten thousand everyday copies. */
    const val CEILING_BYTES = 2L * 1024 * 1024

    /** Share of the free storage the history may use at most (a nearly full phone). */
    const val DISK_SHARE = 0.02

    /**
     * Android reads a database row through a 2 MB window; a longer record would be saved but could
     * never be read back. This is the system's limit, kept with room for the other columns.
     */
    const val ROW_BYTES = 2 * 1024 * 1024 - 64 * 1024

    /**
     * Copies received from another device have their own room, outside [CEILING_BYTES]: the oldest
     * received copy makes way for a new one, so they never push out or block this phone's own copies
     * or fixed records (user 2026-10-30). Same numbers as CyIME for Windows.
     */
    const val DEVICE_COUNT = 100
    const val DEVICE_BYTES = 1L * 1024 * 1024

    fun capacity(freeDiskBytes: Long): Long = minOf(CEILING_BYTES, (freeDiskBytes * DISK_SHARE).toLong())

    /** Bytes one record costs: its text and its search key, as stored (UTF-8). */
    fun recordBytes(text: String, searchKey: String): Long =
        text.encodeToByteArray().size.toLong() + searchKey.length

    /** False for a record the database could not read back. */
    fun fitsRow(text: String): Boolean = text.length <= ROW_BYTES && text.encodeToByteArray().size <= ROW_BYTES

    fun accepts(storedBytes: Long, addingBytes: Long, capacityBytes: Long): Boolean =
        storedBytes + addingBytes <= capacityBytes

    /**
     * A copy larger than this share of the room (a long log, say) is kept only if it fits as is: it
     * never empties the history to make room for itself.
     */
    const val PUSH_OUT_SHARE = 4

    fun pushesOut(addingBytes: Long, capacityBytes: Long): Boolean = addingBytes <= capacityBytes / PUSH_OUT_SHARE

    /**
     * How many of the oldest history records ([sizes] in bytes, oldest first) must go for [addingBytes]
     * to fit; null when removing all of them is still not enough, or when a record too large to
     * push out history does not fit as is.
     */
    fun oldestToRemove(sizes: List<Long>, storedBytes: Long, addingBytes: Long, capacityBytes: Long): Int? {
        if (accepts(storedBytes, addingBytes, capacityBytes)) return 0
        if (!pushesOut(addingBytes, capacityBytes)) return null
        var stored = storedBytes
        sizes.forEachIndexed { index, size ->
            if (accepts(stored, addingBytes, capacityBytes)) return index
            stored -= size
        }
        return if (accepts(stored, addingBytes, capacityBytes)) sizes.size else null
    }
}

/**
 * Room left in the history. [refused] is set when a copy was not saved because fixed and grouped
 * records fill the room, and cleared once records are removed; the board then says the clipboard is full.
 */
internal data class ClipboardRoom(val storedBytes: Long = 0, val capacityBytes: Long = Long.MAX_VALUE,
    val refused: Boolean = false) {
    val full get() = refused || storedBytes >= capacityBytes
}
