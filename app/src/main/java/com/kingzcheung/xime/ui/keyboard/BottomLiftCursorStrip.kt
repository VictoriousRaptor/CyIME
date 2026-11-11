package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * 固定键盘的底部留白（「调节键盘」里往上拖出来的那一条）。
 * 打开「底部留白滑动移动光标」后，在这一条上左右滑动移动光标，按住 Shift 滑是扩选（同长按空格）；
 * 点一下或竖着滑什么都不做，和以前一样。关着时就是原来的空白（用户 10-31 要求，默认关）。
 * 每一格走的是和空格同一条移光标路径（拖动中只读一次输入框，排着的格子合并执行）。
 */
@Composable
internal fun BottomLiftSpacer(heightDp: Int, cursorEnabled: Boolean, onCursorMove: ((Int) -> Unit)?,
    onCursorModeChange: (Boolean) -> Unit = {}) {
    if (heightDp <= 0) return
    val base = Modifier.fillMaxWidth().height(heightDp.dp)
    if (!cursorEnabled || onCursorMove == null) {
        Spacer(base)
        return
    }
    val prefs = LocalKeyboardInputPreferences.current
    val currentMove by rememberUpdatedState(onCursorMove)
    val currentModeChange by rememberUpdatedState(onCursorModeChange)
    val cursorUnitPx = rememberCursorUnitPx()
    val rootView = LocalView.current.rootView
    Spacer(base.testTag("bottom-lift-cursor").pointerInput(prefs.cursorStepDp, prefs.cursorSwipeLines, cursorUnitPx) {
        val fallbackStepPx = prefs.cursorStepDp * cursorUnitPx
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val slop = viewConfiguration.touchSlop
            var active = false
            val steps = CursorStepAccumulator(fallbackStepPx)
            var lastX = down.position.x
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        if (active) {
                            change.consume()
                            SpaceAfterCursorGuard.mark(change.uptimeMillis)
                        }
                        break
                    }
                    val dx = change.position.x - down.position.x
                    val dy = change.position.y - down.position.y
                    if (!active) {
                        if (change.isConsumed) break
                        // 先竖着走的不是移光标（比如从这里往上划），放手不管。
                        if (abs(dy) > slop && abs(dy) > abs(dx)) break
                        if (abs(dx) <= slop) continue
                        active = true
                        currentModeChange(true)
                        lastX = change.position.x
                        SpaceCursorShift.begin()
                        CursorLineMetrics.request()
                    }
                    change.consume()
                    steps.stepPx = lineCursorStepPx(rootView.width / 2f, prefs.cursorSwipeLines,
                        CursorLineMetrics.charsPerLine, fallbackStepPx, cursorUnitPx)
                    val n = steps.move(change.position.x - lastX)
                    lastX = change.position.x
                    if (n != 0) {
                        currentMove(n)
                        SpaceAfterCursorGuard.mark(change.uptimeMillis)
                    }
                }
            } finally {
                if (active) {
                    SpaceCursorShift.end()
                    currentModeChange(false)
                }
            }
        }
    })
}
