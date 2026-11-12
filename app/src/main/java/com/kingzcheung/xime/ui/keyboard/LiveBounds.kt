package com.kingzcheung.xime.ui.keyboard

import android.os.SystemClock
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import com.kingzcheung.xime.util.FileLogger
import kotlin.math.abs

/**
 * 按键浮标定位用的位置：按下这一刻现量按键和键盘在根布局里的位置，不用上次布局回调记下的
 * boundsInRoot。记下的值只在布局回调时更新，还会被外层裁掉一截；它和现在的位置对不上时，
 * 浮标就画到别的行（用户 11-11：手写笔点 14 键 GH，浮窗出在最下面一排；11-06 手指按住 j 也出过一次）。
 * 拿不到布局（还没摆上或已拆下）时用记下的值，和以前一样。
 */
internal class LiveBounds {
    var coordinates: LayoutCoordinates? = null

    fun current(cached: Rect, what: String): Rect {
        val node = coordinates?.takeIf { it.isAttached } ?: return cached
        val live = node.findRootCoordinates().localBoundingBoxOf(node, clipBounds = false)
        if (cached != Rect.Zero && !live.near(cached)) logMismatch(what, cached, live)
        return live
    }
}

private fun Rect.near(other: Rect) = abs(left - other.left) <= 1f && abs(top - other.top) <= 1f &&
    abs(right - other.right) <= 1f && abs(bottom - other.bottom) <= 1f

private const val TAG = "PressBubble"
private const val LOG_INTERVAL_MS = 2_000L
private var lastLogAt = 0L

/** 记下的位置和现量的对不上：写进日志（关于 → 日志查看器能看到），两秒最多一条。 */
private fun logMismatch(what: String, cached: Rect, live: Rect) {
    val now = SystemClock.uptimeMillis()
    if (now - lastLogAt < LOG_INTERVAL_MS) return
    lastLogAt = now
    FileLogger.w(TAG, "press-bubble: $what cached=$cached live=$live")
}
