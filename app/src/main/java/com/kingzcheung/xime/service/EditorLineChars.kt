package com.kingzcheung.xime.service

import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.InputConnection
import com.kingzcheung.xime.platform.AndroidPlatform

/**
 * 向输入框要一次排版，用于移光标“从中间滑到边缘走一行”：只要宽度、字号和光标，不常驻监听。
 * 返回输入框是否接受了请求；不接受时移光标退回按距离计算。
 */
internal fun requestLineLayout(connection: InputConnection, monitor: Boolean = false): Boolean = runCatching {
    val mode = InputConnection.CURSOR_UPDATE_IMMEDIATE or if (monitor) InputConnection.CURSOR_UPDATE_MONITOR else 0
    val characterBounds = if (monitor) InputConnection.CURSOR_UPDATE_FILTER_CHARACTER_BOUNDS else 0
    when {
        AndroidPlatform.cursorAnchorLines -> connection.requestCursorUpdates(mode,
            InputConnection.CURSOR_UPDATE_FILTER_EDITOR_BOUNDS or InputConnection.CURSOR_UPDATE_FILTER_TEXT_APPEARANCE or
                InputConnection.CURSOR_UPDATE_FILTER_INSERTION_MARKER or InputConnection.CURSOR_UPDATE_FILTER_VISIBLE_LINE_BOUNDS or characterBounds)
        AndroidPlatform.cursorAnchorFilter -> connection.requestCursorUpdates(mode,
            InputConnection.CURSOR_UPDATE_FILTER_EDITOR_BOUNDS or InputConnection.CURSOR_UPDATE_FILTER_INSERTION_MARKER or characterBounds)
        else -> connection.requestCursorUpdates(mode)
    }
}.getOrDefault(false)

/**
 * 输入框一行能放几个字。行宽优先取输入框宽（短句所在的行窄，不能当一行的长度），没有再取最宽的可见行；
 * 字号优先取输入框报告的字号，没有再按光标高度约 1.2 倍字号估。坐标都在输入框自己的坐标系里，比值不受缩放影响。
 */
internal fun cursorAnchorCharsPerLine(info: CursorAnchorInfo, sample: CharSequence): Float? {
    val editorWidth = if (AndroidPlatform.cursorAnchorFilter) info.editorBoundsInfo?.editorBounds?.width()?.takeIf { it > 0f } else null
    val lineWidth = editorWidth ?: if (AndroidPlatform.cursorAnchorLines) info.visibleLineBounds.maxOfOrNull { it.width() } else null
    val reportedSize = if (AndroidPlatform.cursorAnchorLines) info.textAppearanceInfo?.textSize?.takeIf { it > 0f } else null
    val markerHeight = info.insertionMarkerBottom - info.insertionMarkerTop
    val textSize = reportedSize ?: (markerHeight / 1.2f).takeIf { it.isFinite() && it > 0f }
    return estimateCharsPerLine(lineWidth ?: return null, textSize ?: return null, sample)
}

/** 行宽 ÷ 平均字宽；字宽按光标附近的实际文字估：汉字等全角约一个字号宽，字母数字约 0.55，空格约 0.3。 */
internal fun estimateCharsPerLine(lineWidthPx: Float, textSizePx: Float, sample: CharSequence): Float? {
    if (!(lineWidthPx > 0f) || !(textSizePx > 0f)) return null
    var ems = 0f
    var count = 0
    var index = 0
    while (index < sample.length) {
        val codePoint = Character.codePointAt(sample, index)
        index += Character.charCount(codePoint)
        if (codePoint == '\n'.code || codePoint == '\r'.code) continue
        ems += characterEm(codePoint)
        count++
    }
    val em = if (count == 0) 1f else ems / count
    return (lineWidthPx / (textSizePx * em)).takeIf { it.isFinite() && it >= 2f }
}

private fun characterEm(codePoint: Int): Float = when {
    Character.isWhitespace(codePoint) -> 0.3f
    isWideCharacter(codePoint) -> 1f
    else -> 0.55f
}

private fun isWideCharacter(codePoint: Int): Boolean =
    codePoint in 0x1100..0x115F || codePoint in 0x2E80..0xA4CF || codePoint in 0xAC00..0xD7A3 ||
        codePoint in 0xF900..0xFAFF || codePoint in 0xFE30..0xFE4F || codePoint in 0xFF00..0xFF60 ||
        codePoint in 0xFFE0..0xFFE6 || codePoint in 0x1F300..0x1FAFF || codePoint >= 0x20000
