package com.kingzcheung.xime.ui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

/** 按下的先后队列本身：谁要生效，比它早按下的先结算；结算过、结束了的不再动。 */
class KeyPressOrderTest {
    private val out = StringBuilder()

    private fun press(name: String) = KeyPressOrder.begin { out.append(name) }

    @Test fun olderPressesSettleFirstInPressOrder() {
        val a = press("a"); val b = press("b"); val c = press("c")
        KeyPressOrder.settleOlderThan(c)
        assertEquals("ab", out.toString())
        // 结算过的不再结算；c 自己不在「比它早」里。
        KeyPressOrder.settleOlderThan(c)
        KeyPressOrder.settleAll()
        assertEquals("ab", out.toString())
        KeyPressOrder.end(a); KeyPressOrder.end(b); KeyPressOrder.end(c)
        assertEquals(0, KeyPressOrder.pendingCount)
    }

    @Test fun newerPressesAreLeftAlone() {
        val a = press("a"); val b = press("b")
        KeyPressOrder.settleOlderThan(a)
        assertEquals("", out.toString())
        assertEquals("后按下的还在排队", 1, KeyPressOrder.pendingCount)
        KeyPressOrder.end(a)
        KeyPressOrder.settleOlderThan(b)
        assertEquals("", out.toString())
        KeyPressOrder.end(b)
        assertEquals(0, KeyPressOrder.pendingCount)
    }

    @Test fun endedPressIsNeverSettled() {
        val a = press("a"); val b = press("b"); val c = press("c")
        KeyPressOrder.end(a)
        KeyPressOrder.settleOlderThan(c)
        assertEquals("b", out.toString())
        KeyPressOrder.end(b); KeyPressOrder.end(c)
        assertEquals(0, KeyPressOrder.pendingCount)
    }

    @Test fun settleAllTakesEveryPendingPress() {
        val a = press("a"); val b = press("b")
        KeyPressOrder.settleAll()
        assertEquals("ab", out.toString())
        KeyPressOrder.settleAll()
        assertEquals("ab", out.toString())
        KeyPressOrder.end(a); KeyPressOrder.end(b)
        assertEquals(0, KeyPressOrder.pendingCount)
    }

    /** 结算时那个键的动作里又有键要生效（重入）：每个按下只结算一次，正要生效的那个不会被别人结算。 */
    @Test fun settlingFromInsideASettleRunsEachPressOnce() {
        val a = KeyPressOrder.begin { out.append("a"); KeyPressOrder.settleAll() }
        val b = press("b")
        val c = press("c")
        KeyPressOrder.settleOlderThan(c)
        assertEquals("ab", out.toString())
        KeyPressOrder.end(a); KeyPressOrder.end(b); KeyPressOrder.end(c)
        assertEquals(0, KeyPressOrder.pendingCount)
    }

    /** 结算一个键时，另一个排在后面、还没轮到的键结束了（键盘换页）：不再结算它。 */
    @Test fun pressEndedDuringSettlingIsSkipped() {
        lateinit var b: KeyPressOrder.Press
        val a = KeyPressOrder.begin { out.append("a"); KeyPressOrder.end(b) }
        b = press("b")
        val c = press("c")
        KeyPressOrder.settleOlderThan(c)
        assertEquals("a", out.toString())
        KeyPressOrder.end(a); KeyPressOrder.end(c)
        assertEquals(0, KeyPressOrder.pendingCount)
    }
}
