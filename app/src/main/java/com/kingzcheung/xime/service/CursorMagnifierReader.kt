package com.kingzcheung.xime.service

import android.view.inputmethod.InputConnection
import com.kingzcheung.xime.ui.keyboard.CursorMagnifierText

/** Read off the UI thread; selection endpoints are absolute and the line end is exclusive. */
internal fun readCursorMagnifierText(
    connection: InputConnection,
    activeEnd: Int? = null,
    line: IntRange? = null,
    selection: IntRange? = null,
): CursorMagnifierText? {
    if (line == null || line.isEmpty() || selection == null || selection.isEmpty() ||
        line.first < 0 || line.last == Int.MAX_VALUE || selection.first < 0 ||
        selection.last.toLong() - selection.first > 16) return null
    val caret = activeEnd ?: selection.last
    if ((caret != selection.first && caret != selection.last) || caret !in line.first..(line.last + 1)) return null
    fun preview(text: CharSequence, at: Int, start: Int): CursorMagnifierText? =
        CursorMagnifierText.from(text, at, (line.first - start).coerceIn(0, text.length),
            (line.last + 1 - start).coerceIn(0, text.length))
    return runCatching {
        // SurroundingText includes the entire selection, irrespective of before/after limits.
        // Large selections are served from the cursor controller's existing snapshot instead.
        connection.readSurroundingWindow(8, 8, 0)?.let { window ->
            val text = window.text ?: return@runCatching null
            if (window.startOffset < 0 || text.length > 32 ||
                window.selectionStart !in 0..text.length || window.selectionEnd !in 0..text.length ||
                window.startOffset.toLong() + minOf(window.selectionStart, window.selectionEnd) != selection.first.toLong() ||
                window.startOffset.toLong() + maxOf(window.selectionStart, window.selectionEnd) != selection.last.toLong()) return@runCatching null
            return@runCatching preview(text, caret - window.startOffset, window.startOffset)
        }
        // Legacy calls cannot read a bounded window within a selection. Never fetch it in full.
        if (selection.first != selection.last) return@runCatching null
        val before = connection.getTextBeforeCursor(8, 0) ?: return@runCatching null
        val after = connection.getTextAfterCursor(8, 0) ?: return@runCatching null
        if (before.length > 8 || after.length > 8 || before.length > caret) return@runCatching null
        preview(before.toString() + after, before.length, caret - before.length)
    }.getOrNull()
}
