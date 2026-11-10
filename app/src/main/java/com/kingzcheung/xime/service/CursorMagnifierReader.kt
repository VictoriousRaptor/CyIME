package com.kingzcheung.xime.service

import android.view.inputmethod.InputConnection
import com.kingzcheung.xime.ui.keyboard.CursorMagnifierText

/** Called off the UI thread with the captured host connection. Request only a small window. */
internal fun readCursorMagnifierText(connection: InputConnection, activeEnd: Int? = null): CursorMagnifierText? = runCatching {
    connection.readSurroundingWindow(64, 64, 0)?.let { window ->
        val active = activeEnd?.minus(window.startOffset)?.takeIf {
            window.startOffset >= 0 && (it == window.selectionStart || it == window.selectionEnd)
        } ?: window.selectionEnd
        return@runCatching window.text?.let { CursorMagnifierText.from(it, active) }
    }
    // Android 9–11 and editors without SurroundingText. Do not invent an active end for a selection.
    if (!connection.getSelectedText(0).isNullOrEmpty()) return@runCatching null
    val before = connection.getTextBeforeCursor(64, 0) ?: return@runCatching null
    val after = connection.getTextAfterCursor(64, 0) ?: return@runCatching null
    CursorMagnifierText.from(before.toString() + after, before.length)
}.getOrNull()
