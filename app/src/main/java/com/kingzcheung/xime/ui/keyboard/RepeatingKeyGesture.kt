package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.keyboard.RepeatInput
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

class DeleteDragSession(val move: (Int) -> Unit, val finish: () -> Unit)

internal data class RepeatingKeyActions(
    val onTap: () -> Unit,
    val onPress: () -> Unit,
    val onRelease: () -> Unit,
    val onPreview: (SwipeState) -> Unit,
    val onRepeat: (() -> Unit)? = null,
    val onUp: (() -> Unit)? = null,
    val onDown: (() -> Unit)? = null,
    val onLeft: (() -> Unit)? = null,
    val upLabel: String? = null,
    val downLabel: String? = null,
    // 上下动作对调后，预览仍从原提示所在一侧出现（“清空”标在上方，下拉时也从上方移入）。
    val swappedVertical: Boolean = false,
    val startDeleteDrag: (() -> DeleteDragSession)? = null,
    /** 按住后每隔多久再来一下；删除键按「长按删除速度」，其他键 30 ms。 */
    val repeatIntervalMs: Long = 30L,
    /** 上下滑松手生效的距离；删除键按「清空滑动距离」，其他键 80dp。 */
    val actionDistanceDp: Float = 80f,
)

/** A single owner for hold and swipe: touch slop must not cancel repeated deletion. */
internal suspend fun PointerInputScope.detectRepeatingKeyGestures(
    currentActions: () -> RepeatingKeyActions,
) = coroutineScope {
    val scope = this
    // 灵敏度比 1.15.41 略低（用户 11-01）：横移 16dp 内不打断点按和长按，30dp 起删，每 16dp 多删一个。
    val dragCancel = maxOf(viewConfiguration.touchSlop, 16.dp.toPx())
    val dragStart = 30.dp.toPx()
    val dragStep = 16.dp.toPx()
    val backtrackSlop = 4.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown()
        val actions = currentActions()
        val actionDistance = actions.actionDistanceDp.dp.toPx()
        // 提示字出现的距离跟着按比例走：默认 80dp 时仍是 50dp。
        val previewDistance = minOf(50.dp.toPx(), actionDistance * 0.625f)
        val repeat = RepeatInput()
        var repeated = false
        var crossed = false
        var drag: DeleteDragSession? = null
        // 右滑是对称的向后删除（Delete）：起手方向定下后本次触摸不再换向。
        var forwardDrag = false
        var lastCount = 0
        var released = false
        var verticalClaimed = false
        down.consume()
        // 按键落点统计（只有自用包在记）：删除键这类只记落点，不跟手势。
        com.kingzcheung.xime.stats.TouchStats.landing(down.position.x, down.position.y, size.width, size.height, density,
            down.uptimeMillis, tracked = false)
        actions.onPress()
        com.kingzcheung.xime.stats.TouchStats.landed()
        actions.onPreview(SwipeState(isPressed = true))
        // 和字母键排同一队（[KeyPressOrder]）：这个键还按着、后按的键先抬手出字时，还是点按（没连发、没滑动）就先出这一下，
        // 之后只等手指抬起；这个键要动作时，比它早按下还没抬的键先出。
        var settled = false
        val press = KeyPressOrder.begin {
            if (!settled && !repeated && !crossed) {
                settled = true
                repeat.stop()
                com.kingzcheung.xime.stats.TouchStats.count(com.kingzcheung.xime.stats.TouchStatsData.Count.EARLY_REPEAT)
                actions.onTap()
                actions.onPreview(SwipeState())
            }
        }
        val timer = actions.onRepeat?.let { onRepeat -> scope.launch {
            delay(viewConfiguration.longPressTimeoutMillis)
            if (settled) return@launch
            repeated = true
            KeyPressOrder.settleOlderThan(press)
            while (repeat.isActive) {
                actions.onPress()
                repeat.dispatch(onRepeat)
                delay(actions.repeatIntervalMs)
            }
        } }
        try {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (settled) {
                    change.consume()
                    if (!change.pressed) break
                    continue
                }
                if (change.isConsumed) break
                val dx = change.position.x - down.position.x
                val dy = change.position.y - down.position.y
                val vertical = abs(dy) > abs(dx) * 1.1f
                if (actions.startDeleteDrag != null && drag == null && !repeated) {
                    if (vertical && abs(dy) >= previewDistance) verticalClaimed = true
                    val horizontal = abs(dx) > abs(dy) * 1.4f
                    if (!verticalClaimed && horizontal && abs(dx) > dragCancel) {
                        // A deliberate sideways move below threshold is neither a tap nor a hold.
                        timer?.cancel()
                        crossed = true
                        if (abs(dx) >= dragStart) {
                            repeat.stop()
                            forwardDrag = dx > 0
                            KeyPressOrder.settleOlderThan(press)
                            drag = actions.startDeleteDrag.invoke()
                            actions.onPreview(SwipeState(isPressed = true))
                        }
                    }
                }
                if (drag != null) {
                    // Absolute distance, not the number of MOVE events; moving back retraces exactly.
                    fun stops(distance: Float) = if (distance < dragStart) 0 else 1 + ((distance - dragStart) / dragStep).toInt()
                    // A tiny wobble on a boundary must not alternately delete and reinsert a character.
                    val distance = if (forwardDrag) dx else -dx
                    val count = maxOf(stops(distance), minOf(lastCount, stops(distance + backtrackSlop)))
                    if (count != lastCount) { drag.move(if (forwardDrag) -count else count); lastCount = count }
                    change.consume()
                    if (!change.pressed) { released = true; break }
                    continue
                }
                val up = vertical && dy < -actionDistance && actions.onUp != null
                val downSwipe = vertical && dy > actionDistance && actions.onDown != null
                val left = actions.startDeleteDrag == null && -dx > abs(dy) * 1.1f && dx < -actionDistance && actions.onLeft != null
                fun flickFrom(start: Float) = keyFlickPreview(dx, dy, start, actionDistance,
                    actions.upLabel?.removePrefix("上滑").takeIf { actions.onUp != null },
                    actions.downLabel?.removePrefix("下滑").takeIf { actions.onDown != null })
                    ?.let { it.copy(origin = if ((dy < 0f) != actions.swappedVertical) KeyFlickOrigin.TOP else KeyFlickOrigin.BOTTOM) }
                // Repeat deletion still survives small drift; only the label starts moving at touch
                // slop, the same as character keys, instead of jumping in late at the preview distance.
                if (flickFrom(previewDistance) != null) {
                    repeat.stop()
                    timer?.cancel()
                }
                val flick = flickFrom(if (repeated) previewDistance else viewConfiguration.touchSlop)
                if (abs(dx) > actionDistance || abs(dy) > actionDistance) {
                    crossed = true
                    repeat.stop()
                    timer?.cancel()
                }
                val hint = when {
                    vertical && dy < -previewDistance && actions.onUp != null -> actions.upLabel
                    vertical && dy > previewDistance && actions.onDown != null -> actions.downLabel
                    else -> null
                }
                actions.onPreview(SwipeState(isPressed = !crossed, isSwiping = hint != null,
                    swipeText = hint, isSwipeDown = dy > 0, isDanger = up || downSwipe, keyFlick = flick))
                change.consume()
                if (!change.pressed) {
                    // Stop queued repetitions before committing a release action.
                    repeat.stop()
                    timer?.cancel()
                    KeyPressOrder.settleOlderThan(press)
                    when {
                        up -> actions.onUp?.invoke()
                        downSwipe -> actions.onDown?.invoke()
                        left -> actions.onLeft?.invoke()
                        !repeated && !crossed -> actions.onTap()
                    }
                    break
                }
            }
        } finally {
            KeyPressOrder.end(press)
            try {
                if (!released && lastCount != 0) drag?.move?.invoke(0)
            } finally {
                drag?.finish?.invoke()
                repeat.stop()
                timer?.cancel()
                actions.onRelease()
                actions.onPreview(SwipeState())
            }
        }
    }
}
