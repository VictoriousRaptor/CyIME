package com.kingzcheung.xime.service

import android.graphics.RectF
import android.view.inputmethod.CursorAnchorInfo
import com.kingzcheung.xime.platform.AndroidPlatform

/** Bounds of the caret in screen pixels, before clipping to the IME's own viewport. */
data class HardwareCursorAnchor(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** CursorAnchorInfo character indices are absolute editor offsets, not composing-text offsets. */
internal fun resolveHardwareCursorAnchor(info: CursorAnchorInfo, activeEnd: Int? = null): HardwareCursorAnchor? {
    fun hiddenOnly(flags: Int): Boolean =
        flags and CursorAnchorInfo.FLAG_HAS_INVISIBLE_REGION != 0 &&
            flags and CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION == 0

    fun toScreen(x: Float, top: Float, bottom: Float): HardwareCursorAnchor? {
        if (!x.isFinite() || !top.isFinite() || !bottom.isFinite() || bottom <= top) return null
        val points = floatArrayOf(x, top, x, bottom)
        info.matrix.mapPoints(points)
        if (points.any { !it.isFinite() }) return null
        return HardwareCursorAnchor(
            minOf(points[0], points[2]), minOf(points[1], points[3]),
            maxOf(points[0], points[2]), maxOf(points[1], points[3]),
        )
    }

    fun characterCaret(bounds: RectF, flags: Int, trailing: Boolean): HardwareCursorAnchor? {
        if (hiddenOnly(flags) || !bounds.left.isFinite() || !bounds.right.isFinite() ||
            bounds.right < bounds.left) return null
        val rtl = flags and CursorAnchorInfo.FLAG_IS_RTL != 0
        val x = if (trailing != rtl) bounds.right else bounds.left
        return toScreen(x, bounds.top, bounds.bottom)
    }

    // Some editors omit visibility flags (0), while still supplying usable geometry.
    // An explicitly clipped caret must not be replaced by an unrelated visible character.
    val active = activeEnd?.takeIf { it == info.selectionStart || it == info.selectionEnd }
    val useMarker = active == null || active == info.selectionEnd
    if (useMarker && hiddenOnly(info.insertionMarkerFlags)) return null
    val markerX = info.insertionMarkerHorizontal
    val markerTop = info.insertionMarkerTop
    val markerBottom = info.insertionMarkerBottom
    if (useMarker && (markerX.isInfinite() || markerTop.isInfinite() || markerBottom.isInfinite())) return null
    if (useMarker && markerX.isFinite() && markerTop.isFinite() && markerBottom.isFinite() && markerBottom != markerTop) {
        return toScreen(markerX, markerTop, markerBottom)
    }

    // Prefer the active end of a selection. Use start only when end was not supplied.
    val caret = active ?: info.selectionEnd.takeIf { it >= 0 } ?: info.selectionStart.takeIf { it >= 0 }
        ?: return null
    info.getCharacterBounds(caret)?.let { bounds ->
        return characterCaret(bounds, info.getCharacterBoundsFlags(caret), trailing = false)
    }
    if (caret == 0) return null
    val previous = caret - 1
    val bounds = info.getCharacterBounds(previous) ?: return null
    return characterCaret(bounds, info.getCharacterBoundsFlags(previous), trailing = true)
}

/** Bounds belong to the editor's coordinate system, not the IME window. */
internal fun resolveHardwareEditorBounds(info: CursorAnchorInfo): HardwareCursorAnchor? {
    if (!AndroidPlatform.cursorAnchorFilter) return null
    val local = info.editorBoundsInfo?.editorBounds ?: return null
    val screen = RectF(local)
    info.matrix.mapRect(screen)
    if (!screen.left.isFinite() || !screen.top.isFinite() || !screen.right.isFinite() || !screen.bottom.isFinite() ||
        screen.right <= screen.left || screen.bottom <= screen.top) return null
    return HardwareCursorAnchor(screen.left, screen.top, screen.right, screen.bottom)
}
