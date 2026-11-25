package com.kingzcheung.xime.service

/**
 * 键盘调节期间报给应用的避让顶线（contentTopInsets / visibleTopInsets，窗口坐标 px）。
 *
 * 调节层是全屏画布，原来一进调节就把顶线报成屏幕底（等于告诉应用「键盘没了」）。有的应用一收到
 * 「键盘高度变成 0」就当键盘被收起、自己把输入法关掉：红米 Android 12 用户录屏（11-23）里点「键盘调节」，
 * 调节界面出来约 0.2 秒，键盘退回原样，接着整个收起；没有崩溃（重新点输入框马上出来，不用重新加载）。
 *
 * 现在调节期间顶线停在进调节前最后一次报的值：应用看到的键盘没变，页面不动。确认或取消后照常重算。
 * 还没报过（刚弹出就进调节）时用 [fallbackTopPx]，和原来一样。
 */
internal class ResizeInsetsFreeze {
    private var lastContentTop = -1
    private var lastVisibleTop = -1
    private var frozenContentTop = -1
    private var frozenVisibleTop = -1

    /** 不在调节时每次算完都记下报给应用的顶线，并结束上一次调节的冻结。 */
    fun noteReported(contentTopPx: Int, visibleTopPx: Int) {
        lastContentTop = contentTopPx
        lastVisibleTop = visibleTopPx
        frozenContentTop = -1
        frozenVisibleTop = -1
    }

    /** 调节期间要报的顶线：第一次调用时定下来，整个调节过程不变。返回 (content, visible)。 */
    fun duringResize(fallbackTopPx: Int): Pair<Int, Int> {
        if (frozenContentTop < 0) {
            val known = lastContentTop >= 0 && lastVisibleTop >= 0
            frozenContentTop = if (known) lastContentTop else fallbackTopPx
            frozenVisibleTop = if (known) lastVisibleTop else fallbackTopPx
        }
        return frozenContentTop to frozenVisibleTop
    }

    /** 键盘收起后忘掉：下一次弹出可能换了应用、换了方向，上一次的顶线不能再用。 */
    fun forget() {
        lastContentTop = -1
        lastVisibleTop = -1
        frozenContentTop = -1
        frozenVisibleTop = -1
    }
}
