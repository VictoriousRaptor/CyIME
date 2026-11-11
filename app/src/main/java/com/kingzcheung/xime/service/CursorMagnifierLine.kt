package com.kingzcheung.xime.service

import android.graphics.Matrix
import android.graphics.RectF
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.TextBoundsInfo
import android.view.inputmethod.TextBoundsInfoResult
import androidx.annotation.RequiresApi
import com.kingzcheung.xime.platform.AndroidPlatform
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.math.abs

/** Returns only actual visual-line offsets; unknown layout never becomes an estimated wrap. */
internal suspend fun readCursorMagnifierLine(
    connection: InputConnection,
    caret: Int,
    anchor: HardwareCursorAnchor,
    cursorInfo: CursorAnchorInfo? = null,
): IntRange? {
    if (caret < 0 || caret == Int.MAX_VALUE || !anchor.left.isFinite() || !anchor.right.isFinite() ||
        !anchor.top.isFinite() || !anchor.bottom.isFinite() || anchor.bottom <= anchor.top) return null
    if (cursorInfo != null && caret != cursorInfo.selectionStart && caret != cursorInfo.selectionEnd) return null
    if (AndroidPlatform.textBoundsInfo) {
        val x = anchor.left / 2f + anchor.right / 2f
        val y = anchor.top / 2f + anchor.bottom / 2f
        val line = withTimeoutOrNull(160L) {
            suspendCancellableCoroutine<IntRange?> { continuation ->
                try {
                    // A thin area inside the caret line avoids requesting adjacent lines or the editor.
                    connection.requestTextBoundsInfo(RectF(x - 1f, y - 1f, x + 1f, y + 1f), Executor { it.run() }) { result ->
                        if (continuation.isActive) continuation.resume(runCatching {
                            if (result.resultCode == TextBoundsInfoResult.CODE_SUCCESS)
                                result.textBoundsInfo?.let { cursorMagnifierLine(it, caret, anchor) } else null
                        }.getOrNull())
                    }
                } catch (_: RuntimeException) {
                    if (continuation.isActive) continuation.resume(null)
                } catch (_: AbstractMethodError) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
        if (line != null) return line
    }
    return cursorInfo?.let { cursorMagnifierCharacterLine(it, caret, anchor) }
}

@RequiresApi(34)
private fun cursorMagnifierLine(info: TextBoundsInfo, caret: Int, anchor: HardwareCursorAnchor): IntRange? {
    val finder = info.lineSegmentFinder
    val matrix = Matrix().also(info::getMatrix)
    val bounds = RectF()
    val middle = anchor.top / 2f + anchor.bottom / 2f
    fun candidate(start: Int): Pair<IntRange, Float>? {
        if (start < info.startIndex || start < 0) return null
        val end = finder.nextEndBoundary(start)
        if (end <= start || end > info.endIndex || caret !in start..end) return null
        info.getCharacterBounds(caret.coerceIn(start, end - 1), bounds)
        matrix.mapRect(bounds)
        val center = bounds.top / 2f + bounds.bottom / 2f
        if (!center.isFinite() || bounds.bottom <= bounds.top || center !in anchor.top..anchor.bottom) return null
        return (start until end) to abs(center - middle)
    }
    val currentStart = finder.previousStartBoundary(caret + 1)
    val current = candidate(currentStart)
    // At a shared soft-wrap offset the caret's actual y decides which visual line owns it.
    val previous = if (currentStart == caret) candidate(finder.previousStartBoundary(caret)) else null
    return if (previous != null && (current == null || previous.second < current.second)) previous.first else current?.first
}

/** Older editors may provide character geometry even without the newer line-range API. */
private fun cursorMagnifierCharacterLine(info: CursorAnchorInfo, caret: Int, anchor: HardwareCursorAnchor): IntRange? = runCatching {
    if (caret != info.selectionStart && caret != info.selectionEnd) return@runCatching null
    val matrix = info.matrix
    fun onLine(index: Int): Boolean {
        val flags = info.getCharacterBoundsFlags(index)
        if (flags and CursorAnchorInfo.FLAG_HAS_INVISIBLE_REGION != 0 &&
            flags and CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION == 0) return false
        val bounds = info.getCharacterBounds(index) ?: return false
        matrix.mapRect(bounds)
        val center = bounds.top / 2f + bounds.bottom / 2f
        return center.isFinite() && bounds.bottom > bounds.top && center >= anchor.top && center < anchor.bottom
    }
    var start = caret
    while (start > 0 && caret - start < 8 && onLine(start - 1)) start--
    var end = caret
    while (end < Int.MAX_VALUE && end - caret < 8 && onLine(end)) end++
    if (start == end) null else start until end
}.getOrNull()
