package com.kingzcheung.xime.ui.keyboard

import android.os.SystemClock
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import com.kingzcheung.xime.util.FileLogger
import kotlin.math.abs

/**
 * 长按选项、滑动符号气泡和方向盘是单独的系统浮窗。Compose 按「键盘窗口里的坐标」给出位置，
 * 系统摆浮窗时用的参照却不一定是键盘窗口（窗口高度、导航栏、全屏/非全屏输入法窗口都可能让两者差一截），
 * 差了就整个气泡挪到别处（用户 11-12：14 键长按 QW 出 1，浮标画到了最下面一排下面）。
 *
 * 做法：浮窗摆好后量一次它实际在屏幕上的位置，和该在的位置（键盘窗口在屏幕上的原点 + 算出的位置）比；
 * 差了就记下差值并马上把这个浮窗挪回去，之后同一个键盘窗口里的浮窗直接带上这个差值。
 * 补过还对不上（系统不按这个挪）就撤回，不越补越远。每次对不上写一条 popup-window 日志。
 */
internal class PopupWindowPlacement(private val anchorView: View) {
    /** 上一次算出的位置（键盘窗口坐标，未补差值）。 */
    private var placed: IntOffset? = null

    /** 在 PopupPositionProvider 里调用：记下算出的位置，返回补过差值的窗口位置。读的是快照状态，差值变了浮窗跟着重摆。 */
    fun place(position: IntOffset): IntOffset {
        placed = position
        return position + PopupWindowCorrection.offsetFor(windowOrigin(anchorView))
    }

    /** 这个浮窗本该在屏幕上的左上角。 */
    fun expectedOnScreen(): IntOffset? = placed?.let { windowOrigin(anchorView) + it }

    val origin: IntOffset get() = windowOrigin(anchorView)
}

/** 同一个键盘窗口（屏幕原点相同）里所有显示用浮窗共用的差值。 */
internal object PopupWindowCorrection {
    private data class Entry(val origin: IntOffset, val offset: IntOffset)

    private var entry by mutableStateOf<Entry?>(null)

    fun offsetFor(origin: IntOffset): IntOffset = entry?.takeIf { it.origin == origin }?.offset ?: IntOffset.Zero

    fun set(origin: IntOffset, offset: IntOffset) {
        entry = if (offset == IntOffset.Zero) null else Entry(origin, offset)
    }

    internal fun reset() { entry = null }
}

/**
 * 浮窗实际在 [actual]、应该在 [expected]、现在补的是 [current]：差不到 1 像素返回 null，
 * 否则返回新的差值（在现有差值上再补差的部分）。
 */
internal fun popupWindowCorrection(expected: IntOffset, actual: IntOffset, current: IntOffset): IntOffset? {
    val dx = expected.x - actual.x
    val dy = expected.y - actual.y
    if (abs(dx) <= 1 && abs(dy) <= 1) return null
    return IntOffset(current.x + dx, current.y + dy)
}

/** 放在浮窗内容里：摆好后量一次，对不上就补；补过仍对不上就撤回。 */
@Composable
internal fun VerifyPopupWindowPlacement(placement: PopupWindowPlacement) {
    val popupView = LocalView.current
    LaunchedEffect(placement, popupView) {
        val origin = placement.origin
        val start = PopupWindowCorrection.offsetFor(origin)
        repeat(2) { attempt ->
            // 补过以后先让浮窗按新差值重摆（重组、重新布局各要一帧），不把挪动前的位置当成补了没用。
            if (attempt > 0) repeat(2) { withFrameNanos { } }
            // 系统挪窗口要过几帧才生效：等位置连着几帧不变再量，不拿还没摆好的位置去补。
            val actual = popupView.settledScreenLocation() ?: return@LaunchedEffect
            val expected = placement.expectedOnScreen() ?: return@LaunchedEffect
            val current = PopupWindowCorrection.offsetFor(origin)
            val next = popupWindowCorrection(expected, actual, current) ?: return@LaunchedEffect
            logPopupMismatch(expected, actual, current, origin)
            if (attempt == 0) PopupWindowCorrection.set(origin, next)
            else { PopupWindowCorrection.set(origin, start); return@LaunchedEffect }
        }
    }
}

/** 连着 [POPUP_STABLE_READS] 帧位置相同才算摆好；[POPUP_MAX_FRAMES] 帧内没稳下来就不量。 */
private suspend fun View.settledScreenLocation(): IntOffset? {
    var last: IntOffset? = null
    var same = 0
    repeat(POPUP_MAX_FRAMES) {
        withFrameNanos { }
        if (!isAttachedToWindow || width == 0 || height == 0) { last = null; same = 0; return@repeat }
        val now = screenLocation()
        if (now == last) { if (++same >= POPUP_STABLE_READS - 1) return now } else { last = now; same = 0 }
    }
    return null
}

private const val POPUP_STABLE_READS = 3
private const val POPUP_MAX_FRAMES = 12

private val screenTmp = IntArray(2)
private val windowTmp = IntArray(2)

/** [view] 所在窗口的左上角在屏幕上的位置。 */
internal fun windowOrigin(view: View): IntOffset {
    view.getLocationOnScreen(screenTmp)
    view.getLocationInWindow(windowTmp)
    return IntOffset(screenTmp[0] - windowTmp[0], screenTmp[1] - windowTmp[1])
}

private fun View.screenLocation(): IntOffset {
    getLocationOnScreen(screenTmp)
    return IntOffset(screenTmp[0], screenTmp[1])
}

private const val TAG = "PressBubble"
private const val LOG_INTERVAL_MS = 2_000L
private var lastLogAt = 0L

private fun logPopupMismatch(expected: IntOffset, actual: IntOffset, correction: IntOffset, origin: IntOffset) {
    val now = SystemClock.uptimeMillis()
    if (now - lastLogAt < LOG_INTERVAL_MS) return
    lastLogAt = now
    FileLogger.w(TAG, "popup-window: expected=$expected actual=$actual correction=$correction keyboardWindow=$origin")
}
