package com.kingzcheung.xime.clipboard.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import com.kingzcheung.xime.clipboard.ClipboardCapacity
import kotlinx.coroutines.flow.Flow

/**
 * Characters ignored at both ends when two clips are compared: copying "地址" and "地址\n" (or with a
 * full-width / no-break space) is the same record (user 10-30: 剪贴板去重).
 */
internal const val CLIP_TRIM = "' ' || char(9) || char(10) || char(13) || char(12288) || char(160)"

@Dao
interface ClipboardDao {

    @Query("SELECT * FROM clipboard_entries WHERE isQuickSend = 0 ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<ClipboardEntry>>

    /**
     * Every fixed or grouped record plus the newest [limit] other ones. The board grows [limit] page by
     * page as it scrolls, so a history of thousands is never read into memory at once.
     */
    @Query("SELECT * FROM clipboard_entries WHERE isQuickSend = 0 AND (isPinned = 1 OR grouped = 1 OR id IN " +
        "(SELECT id FROM clipboard_entries WHERE isQuickSend = 0 AND isPinned = 0 AND grouped = 0 ORDER BY timestamp DESC LIMIT :limit)) " +
        "ORDER BY timestamp DESC")
    fun observeWindow(limit: Int): Flow<List<ClipboardEntry>>

    /** History records: neither fixed nor in a group. */
    @Query("SELECT COUNT(*) FROM clipboard_entries WHERE isPinned = 0 AND grouped = 0 AND isQuickSend = 0")
    fun observeUnpinnedCount(): Flow<Int>

    /** Text fragment or pinyin initials, anywhere; fixed records first, then newest. */
    @Query("SELECT * FROM clipboard_entries WHERE isQuickSend = 0 AND sensitive = 0 AND " +
        "(text LIKE :pattern ESCAPE '\\' OR searchKey LIKE :pattern ESCAPE '\\') " +
        "ORDER BY isPinned DESC, timestamp DESC LIMIT :limit")
    suspend fun search(pattern: String, limit: Int): List<ClipboardEntry>

    /** Pinyin initials only (a word still being typed); fixed records first, then newest. */
    @Query("SELECT * FROM clipboard_entries WHERE isQuickSend = 0 AND sensitive = 0 AND searchKey LIKE :pattern " +
        "ORDER BY isPinned DESC, timestamp DESC LIMIT :limit")
    suspend fun searchKey(pattern: String, limit: Int): List<ClipboardEntry>

    /** Initials as 9-key digits ([pattern] holds digits): the search key read through the keypad. */
    @Query("SELECT * FROM clipboard_entries WHERE isQuickSend = 0 AND sensitive = 0 AND " +
        com.kingzcheung.xime.clipboard.ClipboardSearch.T9_KEY_SQL +
        " LIKE :pattern ORDER BY isPinned DESC, timestamp DESC LIMIT :limit")
    suspend fun searchKeyDigits(pattern: String, limit: Int): List<ClipboardEntry>

    @Query("SELECT id, substr(text, 1, 2000) AS text FROM clipboard_entries WHERE searchKey = '' AND isQuickSend = 0 AND sensitive = 0 LIMIT :limit")
    suspend fun missingSearchKeys(limit: Int): List<SearchKeySource>

    @Query("UPDATE clipboard_entries SET searchKey = :key WHERE id = :id")
    suspend fun setSearchKey(id: Long, key: String)

    /** Compatibility feed for the existing plugin API: fixed text and its optional trigger code. */
    @Query("SELECT * FROM clipboard_entries WHERE isPinned = 1 AND isQuickSend = 0 ORDER BY timestamp DESC")
    fun observeQuickSend(): Flow<List<ClipboardEntry>>

    @Query("SELECT * FROM clipboard_entries WHERE text = :text AND isQuickSend = 0 ORDER BY isPinned ASC, timestamp DESC, id DESC LIMIT 1")
    suspend fun findByText(text: String): ClipboardEntry?

    /** The record holding this text, ends' whitespace ignored: a history copy first, else a fixed one. */
    @Query("SELECT * FROM clipboard_entries WHERE isQuickSend = 0 AND trim(text, " + CLIP_TRIM + ") = trim(:text, " + CLIP_TRIM + ") " +
        "ORDER BY isPinned ASC, timestamp DESC, id DESC LIMIT 1")
    suspend fun findSameText(text: String): ClipboardEntry?

    @Query("SELECT * FROM clipboard_entries WHERE isQuickSend = 0 AND isPinned = 1 AND code = :code AND " +
        "trim(text, " + CLIP_TRIM + ") = trim(:text, " + CLIP_TRIM + ") ORDER BY timestamp DESC LIMIT 1")
    suspend fun findPinnedSameText(text: String, code: String): ClipboardEntry?

    /** Other history copies of this text; fixed and grouped records are never removed here. */
    @Query("DELETE FROM clipboard_entries WHERE isQuickSend = 0 AND isPinned = 0 AND grouped = 0 AND id != :keepId AND " +
        "trim(text, " + CLIP_TRIM + ") = trim(:text, " + CLIP_TRIM + ")")
    suspend fun deleteHistoryCopies(text: String, keepId: Long)

    /**
     * One record per text: drops history copies of a fixed text and, among history copies, all but
     * the newest. Fixed records (and their codes) are kept as they are.
     */
    @Query("DELETE FROM clipboard_entries WHERE isQuickSend = 0 AND isPinned = 0 AND grouped = 0 AND (" +
        "trim(text, " + CLIP_TRIM + ") IN (SELECT trim(text, " + CLIP_TRIM + ") FROM clipboard_entries WHERE isQuickSend = 0 AND (isPinned = 1 OR grouped = 1)) " +
        "OR id NOT IN (SELECT id FROM (SELECT id, MAX(timestamp) FROM clipboard_entries WHERE isQuickSend = 0 AND isPinned = 0 AND grouped = 0 " +
        "GROUP BY trim(text, " + CLIP_TRIM + "))))")
    suspend fun deleteDuplicateHistory(): Int

    /** A copy differing only in surrounding whitespace replaces the history record's text. */
    @Query("UPDATE clipboard_entries SET text = :text, searchKey = :searchKey, timestamp = :timestamp, consumed = 0 " +
        "WHERE id = :id AND isQuickSend = 0")
    suspend fun replaceCopiedText(id: Long, text: String, searchKey: String, timestamp: Long)

    @Query("UPDATE clipboard_entries SET isPinned = 1, code = :code WHERE id = :id AND isQuickSend = 0")
    suspend fun pinWithCode(id: Long, code: String)

    /** Atomic and idempotent; no deduplication may discard different codes on equal text. */
    @Query("UPDATE clipboard_entries SET isPinned = 1, isQuickSend = 0 WHERE isQuickSend = 1")
    suspend fun migrateQuickSendToPinned()

    @Query("SELECT * FROM clipboard_entries WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): ClipboardEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: ClipboardEntry): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<ClipboardEntry>)

    @Query("UPDATE clipboard_entries SET timestamp = :timestamp WHERE id = :id")
    suspend fun updateTimestamp(id: Long, timestamp: Long)

    @Query("UPDATE clipboard_entries SET isPinned = :pinned WHERE id = :id AND isQuickSend = 0")
    suspend fun setClipboardPinned(id: Long, pinned: Boolean)

    // 再次复制是新的用户动作，同一条历史记录也应重新出现在预览栏。
    @Query("UPDATE clipboard_entries SET timestamp = :timestamp, consumed = 0 WHERE id = :id AND isQuickSend = 0")
    suspend fun refreshCopiedItem(id: Long, timestamp: Long)


    @Query("DELETE FROM clipboard_entries WHERE isQuickSend = 0 AND id = :id")
    suspend fun deleteClipboardById(id: Long)

    @Query("DELETE FROM clipboard_entries WHERE isQuickSend = 0 AND id IN (:ids)")
    suspend fun deleteClipboardByIds(ids: List<Long>)

    // Clearing and trimming history leaves fixed records and records in a group alone.
    @Query("DELETE FROM clipboard_entries WHERE isPinned = 0 AND grouped = 0 AND isQuickSend = 0")
    suspend fun clearAllClipboard()

    @Query("DELETE FROM clipboard_entries WHERE isPinned = 0 AND grouped = 0 AND isQuickSend = 0")
    suspend fun clearUnpinned()

    @Query("SELECT COUNT(*) FROM clipboard_entries WHERE isPinned = 0 AND grouped = 0 AND isQuickSend = 0")
    suspend fun countUnpinned(): Int

    @Query("DELETE FROM clipboard_entries WHERE isPinned = 0 AND grouped = 0 AND isQuickSend = 0 AND id IN (SELECT id FROM clipboard_entries WHERE isPinned = 0 AND grouped = 0 AND isQuickSend = 0 ORDER BY timestamp ASC LIMIT :limit)")
    suspend fun trimUnpinned(limit: Int)

    @Query("SELECT COUNT(*) FROM clipboard_entries WHERE isPinned = 0 AND grouped = 0 AND isQuickSend = 0 AND timestamp < :cutoff")
    suspend fun countHistoryBefore(cutoff: Long): Int

    @Query("DELETE FROM clipboard_entries WHERE isPinned = 0 AND grouped = 0 AND isQuickSend = 0 AND timestamp < :cutoff")
    suspend fun deleteHistoryBefore(cutoff: Long): Int

    /**
     * The user's history limits: records copied before [cutoff] go, then the oldest beyond [maxCount]
     * (0 = no cap). Fixed and grouped records are never counted or removed. Returns how many went.
     */
    @Transaction
    suspend fun trimHistory(maxCount: Int, cutoff: Long): Int {
        var removed = deleteHistoryBefore(cutoff)
        if (maxCount > 0) {
            val over = countUnpinned() - maxCount
            if (over > 0) { trimUnpinned(over); removed += over }
        }
        return removed
    }

    // An edit clears the search key; the backfill recomputes it from the new text.
    @Query("UPDATE clipboard_entries SET text = :text, code = COALESCE(:code, code), timestamp = :now, searchKey = '' WHERE id = :id AND isQuickSend = 0")
    suspend fun updateClipboardItem(id: Long, text: String, code: String?, now: Long)

    @Query("UPDATE clipboard_entries SET consumed = 1 WHERE id = :id")
    suspend fun markConsumed(id: Long)

    @Query("DELETE FROM clipboard_entries")
    suspend fun deleteAll()

    @Transaction
    suspend fun upsertAndTrim(text: String, now: Long, maxItems: Int, searchKey: String = "") {
        if (upsert(text, now, searchKey)) {
            val unpinned = countUnpinned()
            if (unpinned > maxItems) {
                trimUnpinned(unpinned - maxItems)
            }
        }
    }

    /**
     * A copy already in history (ends' whitespace ignored) moves to the top, taking the new copy's exact
     * text; a fixed record keeps its text. A new one is added. One record per text remains. True when added.
     */
    @Transaction
    suspend fun upsert(text: String, now: Long, searchKey: String = "", fromDevice: Boolean = false,
        sensitive: Boolean = false): Boolean {
        val existing = findSameText(text)
        val keep = when {
            existing == null -> insert(ClipboardEntry(text = text, timestamp = now, searchKey = searchKey,
                fromDevice = fromDevice, sensitive = sensitive))
            !existing.isPinned && existing.text != text -> existing.id.also { replaceCopiedText(it, text, searchKey, now) }
            else -> existing.id.also { refreshCopiedItem(it, now) }
        }
        // Copied here again: a received record becomes this device's own. A received copy of text
        // already here only moves it up.
        if (existing != null && existing.fromDevice && !fromDevice) setFromDevice(keep, false)
        // The latest copy decides: copied as a password, the record is hidden (and expires); copied
        // again from an ordinary place, an unfixed one is ordinary again. A fixed one stays hidden.
        if (existing != null && existing.sensitive != sensitive && (sensitive || !existing.isPinned)) {
            setSensitive(keep, sensitive)
            if (sensitive) setSearchKey(keep, "")
        }
        deleteHistoryCopies(text, keep)
        return existing == null
    }

    @Query("UPDATE clipboard_entries SET fromDevice = :fromDevice WHERE id = :id")
    suspend fun setFromDevice(id: Long, fromDevice: Boolean)

    @Query("UPDATE clipboard_entries SET sensitive = :sensitive WHERE id = :id")
    suspend fun setSensitive(id: Long, sensitive: Boolean)

    /** Passwords copied before [cutoff] (an hour ago), or all of them when the switch is turned off; fixed or grouped ones stay. */
    @Query("DELETE FROM clipboard_entries WHERE sensitive = 1 AND isPinned = 0 AND grouped = 0 AND isQuickSend = 0 AND timestamp < :cutoff")
    suspend fun deleteSensitiveBefore(cutoff: Long): Int

    @Query("SELECT COUNT(*) FROM clipboard_entries WHERE fromDevice = 1 AND isPinned = 0 AND grouped = 0 AND isQuickSend = 0")
    suspend fun countReceived(): Int

    @Query("SELECT COALESCE(SUM(length(CAST(text AS BLOB)) + length(searchKey)), 0) FROM clipboard_entries " +
        "WHERE fromDevice = 1 AND isPinned = 0 AND grouped = 0 AND isQuickSend = 0")
    suspend fun receivedBytes(): Long

    @Query("DELETE FROM clipboard_entries WHERE id IN (SELECT id FROM clipboard_entries " +
        "WHERE fromDevice = 1 AND isPinned = 0 AND grouped = 0 AND isQuickSend = 0 ORDER BY timestamp ASC, id ASC LIMIT :limit)")
    suspend fun deleteOldestReceived(limit: Int)

    /**
     * A copy received from another device. Received records that are not fixed (or grouped) keep their own room
     * ([maxCount] records, [maxBytes]); only the oldest of them make way, never this device's own
     * copies or fixed records. True when added.
     */
    @Transaction
    suspend fun addReceived(text: String, now: Long, searchKey: String, maxCount: Int, maxBytes: Long): Boolean {
        val added = upsert(text, now, searchKey, fromDevice = true)
        countReceived().let { if (it > maxCount) deleteOldestReceived(it - maxCount) }
        while (receivedBytes() > maxBytes && countReceived() > 0) deleteOldestReceived(1)
        return added
    }

    /**
     * Fixing text: the same fixed text and code only moves up; text already in history is fixed in
     * place (no second copy); otherwise a new fixed record. Different codes on equal text stay apart.
     */
    @Transaction
    suspend fun addPinned(text: String, code: String, now: Long, searchKey: String = ""): Long {
        val id = findPinnedSameText(text, code)?.id?.also { refreshCopiedItem(it, now) }
            ?: findSameText(text)?.takeIf { !it.isPinned }?.id?.also {
                replaceCopiedText(it, text, searchKey, now)
                pinWithCode(it, code)
            }
            ?: insertPinnedText(text, code, now, searchKey)
        deleteHistoryCopies(text, id)
        return id
    }

    /**
     * Bytes of text and search keys stored (fixed and history), re-read whenever records change.
     * Received records that are not fixed or grouped have their own room and are not counted here.
     */
    @Query("SELECT COALESCE(SUM(length(CAST(text AS BLOB)) + length(searchKey)), 0) FROM clipboard_entries " +
        "WHERE isQuickSend = 0 AND (fromDevice = 0 OR isPinned = 1 OR grouped = 1)")
    fun observeStoredBytes(): Flow<Long>

    @Query("SELECT COALESCE(SUM(length(CAST(text AS BLOB)) + length(searchKey)), 0) FROM clipboard_entries " +
        "WHERE isQuickSend = 0 AND (fromDevice = 0 OR isPinned = 1 OR grouped = 1)")
    suspend fun storedBytes(): Long

    /** This phone's own history (not fixed, not grouped, not received), oldest first, with its size. */
    @Query("SELECT id, length(CAST(text AS BLOB)) + length(searchKey) AS bytes FROM clipboard_entries " +
        "WHERE isPinned = 0 AND grouped = 0 AND isQuickSend = 0 AND fromDevice = 0 AND id != :keep " +
        "ORDER BY timestamp ASC, id ASC")
    suspend fun ownHistorySizes(keep: Long): List<ClipboardRecordSize>

    /**
     * Room for [adding] more bytes within [capacity]: the oldest own history goes first, never fixed or
     * grouped records, received copies (they have their own room) or record [keep]. False, with nothing
     * removed, when fixed and grouped records alone leave no room.
     */
    @Transaction
    suspend fun makeRoom(adding: Long, capacity: Long, keep: Long = -1): Boolean {
        val stored = storedBytes()
        if (ClipboardCapacity.accepts(stored, adding, capacity)) return true
        val history = ownHistorySizes(keep)
        val remove = ClipboardCapacity.oldestToRemove(history.map { it.bytes }, stored, adding, capacity) ?: return false
        history.take(remove).map { it.id }.chunked(500).forEach { deleteClipboardByIds(it) }
        return true
    }

    suspend fun insertPinnedText(text: String, code: String, now: Long, searchKey: String = ""): Long =
        insert(ClipboardEntry(text = text, code = code, timestamp = now, isPinned = true, searchKey = searchKey))

    /**
     * Text added from a group's tab: the record holding this text (fixed or not) moves up, otherwise a
     * new one; it is not fixed. The caller puts it into the group. Returns its id.
     */
    @Transaction
    suspend fun addToGroup(text: String, code: String, now: Long, searchKey: String = ""): Long {
        val existing = findSameText(text)
        val id = existing?.id?.also { refreshCopiedItem(it, now) }
            ?: insert(ClipboardEntry(text = text, code = code, timestamp = now, searchKey = searchKey, grouped = true))
        setGrouped(listOf(id), true)
        deleteHistoryCopies(text, id)
        return id
    }

    @Query("UPDATE clipboard_entries SET grouped = :grouped WHERE id IN (:ids)")
    suspend fun setGrouped(ids: List<Long>, grouped: Boolean)

    @Query("UPDATE clipboard_entries SET grouped = 0 WHERE grouped = 1")
    suspend fun ungroupAll()

    /** The [grouped] marks follow the group preferences: exactly [ids] are in a group. */
    @Transaction
    suspend fun syncGrouped(ids: List<Long>) {
        ungroupAll()
        ids.chunked(500).forEach { setGrouped(it, true) }
    }

    @Query("SELECT id FROM clipboard_entries WHERE id IN (:ids)")
    suspend fun existingIds(ids: List<Long>): List<Long>

    /**
     * Before 11-02 a record put into a group was fixed with it. Those records are unfixed once and
     * stay in their group; a fixed record with a trigger code stays fixed.
     */
    @Query("UPDATE clipboard_entries SET isPinned = 0 WHERE grouped = 1 AND isPinned = 1 AND code = '' AND isQuickSend = 0")
    suspend fun unpinGrouped(): Int

    /**
     * One record from an import file, keeping its own time. Records already here stay as they are
     * (ends' whitespace ignored, as for copies); a fixed one found only in history is fixed in place.
     * A new row is written only when [canAdd] (room left); [ClipboardImportRow.id] is 0 when none was.
     */
    @Transaction
    suspend fun importRecord(text: String, code: String, timestamp: Long, pinned: Boolean, searchKey: String,
        canAdd: Boolean): ClipboardImportRow {
        if (pinned) {
            findPinnedSameText(text, code)?.let { return ClipboardImportRow(it.id, ClipboardImportRow.EXISTING) }
            findSameText(text)?.takeIf { !it.isPinned }?.let {
                pinWithCode(it.id, code)
                deleteHistoryCopies(text, it.id)
                return ClipboardImportRow(it.id, ClipboardImportRow.EXISTING)
            }
        } else {
            findSameText(text)?.let { return ClipboardImportRow(it.id, ClipboardImportRow.EXISTING) }
        }
        if (!canAdd) return ClipboardImportRow(0, ClipboardImportRow.NO_ROOM)
        val id = insert(ClipboardEntry(text = text, code = if (pinned) code else "", timestamp = timestamp,
            isPinned = pinned, searchKey = searchKey))
        return ClipboardImportRow(id, ClipboardImportRow.ADDED)
    }
}

/** Where one imported record went: [id] of its row, and whether it was [ADDED], already there, or left out. */
data class ClipboardImportRow(val id: Long, val outcome: Int) {
    companion object {
        const val ADDED = 0
        const val EXISTING = 1
        const val NO_ROOM = 2
    }
}

/** The part of a record the search-key backfill needs. */
data class SearchKeySource(val id: Long, val text: String)

/** A history record's stored size, for making room. */
data class ClipboardRecordSize(val id: Long, val bytes: Long)
