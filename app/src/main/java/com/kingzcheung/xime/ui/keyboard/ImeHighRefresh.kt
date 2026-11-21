package com.kingzcheung.xime.ui.keyboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * 拖动悬浮键盘、语音悬浮条、实体键盘浮条和调节界面时，让输入法窗口按屏幕最高刷新率跟手（用户 11-19：悬浮窗拖动帧数太低）。
 *
 * 输入法窗口自己不投刷新率票，有的系统的动态刷新率就按低档给（设置页 11-16 是同一个问题）。
 * 只在手指拖着、松手归位动画还在走的这段时间要最高档，松手就撤票、交还系统：
 * 平时打字不多耗电，所以不挂开关。
 *
 * 多处可以同时要（比如拖悬浮条时调节界面也开着），按次数记，最后一个撤了才撤票。只在主线程用。
 */
internal object ImeHighRefresh {
    private var holders = 0

    /** 输入法服务挂上：true = 投最高刷新率的票，false = 撤票。 */
    var apply: ((Boolean) -> Unit)? = null
        set(value) {
            field = value
            if (holders > 0) value?.invoke(true)
        }

    fun acquire() {
        if (holders++ == 0) apply?.invoke(true)
    }

    fun release() {
        if (holders > 0 && --holders == 0) apply?.invoke(false)
    }

    internal val active: Boolean get() = holders > 0

    internal fun resetForTest() { holders = 0; apply = null }

    data class Mode(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

    /**
     * 同分辨率里刷新率最高的显示模式。和设置页不同，当前已经是最高档也照样返回它：
     * 拖动开始那一下系统常因触摸临时升到高档，过一会又降回去，要一直投票才稳。
     */
    fun highestModeId(current: Mode, modes: List<Mode>): Int? =
        modes.filter { it.width == current.width && it.height == current.height }
            .maxByOrNull { it.refreshRate }?.id
}

/** [active] 为真的这段时间输入法窗口按最高刷新率跟手，见 [ImeHighRefresh]。 */
@Composable
internal fun HighRefreshWhile(active: Boolean) {
    if (!active) return
    DisposableEffect(Unit) {
        ImeHighRefresh.acquire()
        onDispose { ImeHighRefresh.release() }
    }
}
