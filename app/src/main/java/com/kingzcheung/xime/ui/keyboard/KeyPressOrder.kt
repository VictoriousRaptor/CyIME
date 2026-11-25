package com.kingzcheung.xime.ui.keyboard

/**
 * 几根手指交叠时，按键按按下的先后生效。
 *
 * 每个键各管各的手势，抬手时才出字。两只拇指打快了会交叠：按着 n 还没抬，又按了 i 并先抬起，以前出来的是 in；
 * 按着空格没抬就点了下一个字母，字母先进了正在打的拼音；按着删除没抬就点了下一个字母，删掉的是刚打的这个字母。
 * 现在一个键要生效时，比它早按下、还没结算的键先按点按生效，自己再生效。
 *
 * 只排按下的先后，不改每个键自己的判定：早按下的那个键已经不是点按（长按到点了、滑出方向了、拖走了）时不提前生效，
 * 照旧等它自己抬手。只在主线程（手势所在的线程）上用。
 */
internal object KeyPressOrder {
    /** 一次还没结算的按下。[settleNow] 让它现在就按点按生效；已经不是点按、或者已经生效过时什么都不做。 */
    class Press internal constructor(private val settleNow: () -> Unit) {
        internal var ended = false
        internal fun settle() { if (!ended) settleNow() }
    }

    private val pending = ArrayList<Press>(4)
    /** 开始了还没结束的按下有几个（出没出过字都算），只给按键落点统计用。 */
    private var held = 0

    /** 手指按下：排到最后。这次按下结束时（生效没生效都算）必须调 [end]。 */
    fun begin(settleNow: () -> Unit): Press {
        com.kingzcheung.xime.stats.TouchStats.pressBegan(held)
        held++
        return Press(settleNow).also { pending.add(it) }
    }

    /** 这次按下结束了：出队，之后不会再被结算。 */
    fun end(press: Press) {
        if (!press.ended) held--
        press.ended = true
        pending.remove(press)
    }

    /** [press] 要生效了：它自己出队，比它早按下的先结算（按按下的先后）。之后仍要调 [end]。 */
    fun settleOlderThan(press: Press) {
        val index = pending.indexOf(press)
        if (index < 0) return
        if (index == 0) { pending.removeAt(0); return }
        val older = ArrayList(pending.subList(0, index))
        pending.subList(0, index + 1).clear()
        older.forEach { it.settle() }
    }

    /** 不排队的键（回车、Shift）要生效了：还按着没结算的都先结算。 */
    fun settleAll() {
        if (pending.isEmpty()) return
        val all = ArrayList(pending)
        pending.clear()
        all.forEach { it.settle() }
    }

    /** 还在排队的按下有几个（测试用）。 */
    internal val pendingCount: Int get() = pending.size
}
