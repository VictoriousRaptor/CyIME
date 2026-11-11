package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.ceil

internal val LocalT9GlideKeys = staticCompositionLocalOf<MutableMap<Char, Rect>?> { null }

/**
 * Decides when a touch becomes a glide (it reached another digit key) and whether it left the digit grid.
 * The digits it crosses are no longer what gets typed: the whole path goes to [t9GlideTyping]'s callback,
 * which matches it against the dictionary (a line from 9 to 4 passes over 8 or 5 but means 94).
 * Sample segments as well as events so the same path on a slower touchscreen behaves the same.
 */
internal class DigitStroke(private val keys: Map<Char, Rect>, private val start: Offset, first: Char) {
    private var previous = start
    private val keySize = keys.values.minOf { it.minDimension }
    private val step = (keySize / 4f).coerceAtLeast(1f)
    val digits = StringBuilder().append(first)
    var cancelled = false
        private set
    /** 手指离按下点最远走了多远（键盘坐标）。 */
    private var reach = 0f

    /**
     * 算不算滑行：进了另一个数字键的中间，而且离按下点至少 [CLAIM_KEY_FRACTION] 个键高（用户 11-09：九键开了
     * 滑行根本打不了字，滑一点就判成滑行）。以前只看进没进另一个键，手指落在键边上一抖就够了，点按被吞掉；
     * 现在从键边起滑要多走大半个键。从键中间滑到相邻键中间本来就超过这个距离，正常滑行和以前一样。
     */
    val claimed: Boolean get() = !cancelled && digits.length > 1 && reach >= keySize * CLAIM_KEY_FRACTION

    fun move(end: Offset) {
        reach = maxOf(reach, (end - start).getDistance())
        val count = ceil((end - previous).getDistance() / step).toInt().coerceAtLeast(1)
        for (i in 1..count) {
            val point = lerp(previous, end, i.toFloat() / count)
            val hit = keys.entries.firstOrNull { it.value.contains(point) }
            if (hit == null) {
                // Small gaps between keys are traversable. A visit to the rail or a function key cancels.
                if (keys.values.none { it.inflate(step / 2).contains(point) }) { cancelled = true; break }
            } else if (hit.key != digits.last() && hit.value.deflate(hit.value.minDimension * CORE_INSET).contains(point)) {
                digits.append(hit.key)
            }
        }
        previous = end
    }

    companion object {
        /** 进了键的这一圈以内才算到了这个键（四边各缩进键高的这个比例）。 */
        const val CORE_INSET = 0.18f
        /** 离按下点至少这么多个键高才算滑行。 */
        const val CLAIM_KEY_FRACTION = 0.6f
    }
}

@Composable
internal fun Modifier.t9GlideTyping(
    keys: Map<Char, Rect>?, trail: GlideTrail, rootOrigin: () -> Offset,
    admission: () -> Long?, onStroke: (xs: FloatArray, ys: FloatArray) -> Unit,
): Modifier {
    val origin = rememberUpdatedState(rootOrigin)
    val ticket = rememberUpdatedState(admission)
    val submit = rememberUpdatedState(onStroke)
    // 「长按输入」时数字按满设定时长就已上屏，之后再拖动不能又滑出一串键。
    val holdMs = rememberUpdatedState(LocalKeyboardInputPreferences.current.glideHoldMs)
    if (keys == null) return this
    return pointerInput(keys, trail) {
        awaitEachGesture {
            val down = awaitFirstDown(false, PointerEventPass.Initial)
            val offset = origin.value()
            val first = keys.entries.firstOrNull { it.value.contains(down.position + offset) } ?: return@awaitEachGesture
            val editor = ticket.value() ?: return@awaitEachGesture
            val stroke = DigitStroke(keys.toMap(), down.position + offset, first.key)
            val path = arrayListOf(down.position)
            val times = arrayListOf(down.uptimeMillis)
            var moved = false
            var claimed = false
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.isConsumed) break
                    if (event.changes.any { it.id != down.id && it.pressed }) {
                        // Once the first button yielded to the glide, discard the whole cancelled
                        // stroke, including the second down. Before takeover, allow normal two-thumb taps.
                        if (claimed) {
                            event.changes.forEach { it.consume() }
                            while (true) {
                                val remaining = awaitPointerEvent(PointerEventPass.Initial)
                                remaining.changes.forEach { it.consume() }
                                if (remaining.changes.none { it.pressed }) break
                            }
                        }
                        break
                    }
                    if (!moved && change.uptimeMillis - down.uptimeMillis >= holdMs.value) break
                    moved = moved || (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                    for ((position, time) in change.historical.map { it.position to it.uptimeMillis } + (change.position to change.uptimeMillis)) {
                        if (path.last() != position) {
                            path += position
                            times += time
                            stroke.move(position + offset)
                            if (stroke.cancelled) break
                        }
                    }
                    claimed = claimed || stroke.claimed
                    if (claimed) change.consume() // the original button drops its tap/flick on this same event
                    if (stroke.cancelled || origin.value() != offset || ticket.value() != editor) break
                    if (!change.pressed) {
                        // Root coordinates, same as the key rectangles.
                        if (claimed) {
                            val xs = FloatArray(path.size) { path[it].x + offset.x }
                            val ys = FloatArray(path.size) { path[it].y + offset.y }
                            // 「贡献滑行轨迹」页开着时才有人收，平时直接返回。
                            if (com.kingzcheung.xime.glide.GlideSampleRecorder.listener != null)
                                com.kingzcheung.xime.glide.GlideSampleRecorder.record("t9", keys.mapValues { (_, r) ->
                                    com.kingzcheung.xime.keyboard.LetterKeyRect(r.left, r.top, r.width, r.height) }, xs, ys, times.toLongArray())
                            submit.value(xs, ys)
                        }
                        break
                    }
                    if (claimed) trail.points = path.toList()
                }
            } finally { trail.points = emptyList() }
        }
    }
}
