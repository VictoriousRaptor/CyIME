package com.kingzcheung.xime.service

import android.graphics.Rect
import android.view.inputmethod.InputConnection
import android.widget.EditText

/** Reuse the line-layout filters, retaining monitoring while the magnifier is held. */
internal fun requestCursorMagnifierUpdates(connection: InputConnection): Boolean =
    requestLineLayout(connection, monitor = true) || runCatching {
        connection.requestCursorUpdates(InputConnection.CURSOR_UPDATE_IMMEDIATE or InputConnection.CURSOR_UPDATE_MONITOR)
    }.getOrDefault(false)

/** Internal editors do not send CursorAnchorInfo to their own IME. Read their actual layout. */
internal fun internalCursorMagnifierAnchor(editor: EditText, caret: Int): HardwareCursorAnchor? {
    val layout = editor.layout ?: return null
    if (caret !in 0..editor.text.length || caret !in 0..layout.text.length) return null
    val visible = Rect()
    if (!editor.getGlobalVisibleRect(visible)) return null
    val line = layout.getLineForOffset(caret)
    val point = floatArrayOf(
        editor.totalPaddingLeft + layout.getPrimaryHorizontal(caret) - editor.scrollX,
        (editor.totalPaddingTop + layout.getLineTop(line) - editor.scrollY).toFloat(),
        editor.totalPaddingLeft + layout.getPrimaryHorizontal(caret) - editor.scrollX,
        (editor.totalPaddingTop + layout.getLineBottom(line) - editor.scrollY).toFloat(),
    )
    val matrix = android.graphics.Matrix()
    if (com.kingzcheung.xime.platform.AndroidPlatform.viewGlobalMatrix) editor.transformMatrixToGlobal(matrix)
    else {
        val origin = IntArray(2).also(editor::getLocationOnScreen)
        matrix.setTranslate(origin[0].toFloat(), origin[1].toFloat())
    }
    matrix.mapPoints(point)
    val x = (point[0] + point[2]) / 2
    val top = minOf(point[1], point[3])
    val bottom = maxOf(point[1], point[3])
    if (!x.isFinite() || !top.isFinite() || !bottom.isFinite() || bottom <= top ||
        x < visible.left || x > visible.right || bottom < visible.top || top > visible.bottom) return null
    return HardwareCursorAnchor(x, top, x, bottom)
}
