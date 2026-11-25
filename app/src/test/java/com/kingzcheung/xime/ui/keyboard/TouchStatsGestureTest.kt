package com.kingzcheung.xime.ui.keyboard

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.stats.TouchStats
import com.kingzcheung.xime.stats.TouchStatsData
import com.kingzcheung.xime.stats.TouchStatsData.Count
import com.kingzcheung.xime.stats.TouchStatsData.Kind
import com.kingzcheung.xime.stats.TouchStatsData.Outcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * 按键手势里的统计接入点：用应用自己的按键手势代码（字母键 detectExclusiveKeyGestures、删除键 detectRepeatingKeyGestures）
 * 摆几个键，注入触摸，看记下来的计数。键的按下回调照输入法服务的做法报键名（[TouchStats.keyDown]），
 * 删除键出字时报 [TouchStats.keyEmit]。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w800dp-h1280dp-xhdpi")
class TouchStatsGestureTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val typed = StringBuilder()
    private val key = 100f   // px
    private var slop = 0f
    private var density = 1f
    /** 上层从这根手指挪过这么远起把它拿走（像滑行、键盘上滑动移光标）；0 是不拿。 */
    private var parentTakesAfter = 0f

    private sealed interface Spec { val name: String }
    private class Letter(override val name: String, val glide: Boolean = false,
        val configure: (KeyGestureActions) -> KeyGestureActions = { it }) : Spec
    private class Delete(override val name: String = "delete") : Spec

    @Before fun setUp() {
        TouchStats.clear()
        TouchStats.gate = { true }
    }

    @After fun tearDown() {
        TouchStats.gate = null
        TouchStats.clear()
    }

    private fun show(vararg keys: Spec) {
        rule.setContent {
            slop = LocalViewConfiguration.current.touchSlop
            density = LocalDensity.current.density
            val side = with(LocalDensity.current) { key.toDp() }
            Row(Modifier.pointerInput(Unit) {
                awaitPointerEventScope {
                    var start: Offset? = null
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.first()
                        if (!change.pressed) { start = null; continue }
                        val from = start ?: change.position.also { start = it }
                        if (parentTakesAfter > 0f && abs(change.position.y - from.y) > parentTakesAfter) change.consume()
                    }
                }
            }) {
                for (spec in keys) when (spec) {
                    is Letter -> {
                        val a = spec.configure(KeyGestureActions(text = spec.name, onTap = { typed.append(spec.name) },
                            onPress = { TouchStats.keyDown(spec.name) }, onRelease = {}, onPreview = {}))
                        Box(Modifier.size(side).pointerInput(Unit) {
                            detectExclusiveKeyGestures(reserveDragForGlide = spec.glide, letterGlide = spec.glide) { a }
                        })
                    }
                    is Delete -> {
                        val a = RepeatingKeyActions(onTap = { TouchStats.keyEmit(spec.name); typed.append('<') },
                            onPress = { TouchStats.keyDown(spec.name) }, onRelease = {}, onPreview = {}, onRepeat = { typed.append('-') })
                        Box(Modifier.size(side).pointerInput(Unit) { detectRepeatingKeyGestures { a } })
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun show(vararg letters: String) = show(*letters.map { Letter(it) }.toTypedArray())

    private fun at(index: Int, dx: Float = 0f, dy: Float = 0f) = Offset(key * index + key / 2f + dx, key / 2f + dy)

    private fun touches(gap: Long = 40L, block: TouchInjectionScope.(step: () -> Unit) -> Unit) {
        rule.onRoot().performTouchInput { block { advanceEventTime(gap) } }
        rule.waitForIdle()
    }

    private val board get() = TouchStats.snapshot().boards.getValue(TouchStatsData.UNKNOWN_BOARD)
    private fun outcomes() = board.outcomes.withIndex().filter { it.value > 0 }.associate { it.index to it.value }

    /** 一下点按：落点记在按下的那一格，键的大小按 dp 记。 */
    @Test fun tapRecordsWhereItLanded() {
        show("n", "i")
        touches { step -> down(0, Offset(key + 95f, 5f)); step(); up(0) }
        assertEquals("i", typed.toString())
        assertEquals(mapOf(Outcome.TAP to 1L), outcomes())
        val i = board.keys.getValue("i")
        assertEquals(mapOf(0 * 10 + 9 to 1L), i.land.counts)
        assertEquals(key / density, i.maxWidth)
        assertEquals(1, board.counts[Count.KEY_DOWNS])
        assertEquals(1, board.counts[Count.CHAR_PRESSES])
        assertEquals(listOf(1L, 0L, 0L, 0L), board.overlap.toList())
    }

    /** 按着 n 没抬又点了 i：n 提前出字记成「提前」，顺序没有反，i 按下时有一个键还按着。 */
    @Test fun nestedPressIsAnEarlyTap() {
        show("n", "i")
        touches { step -> down(0, at(0)); step(); down(1, at(1)); step(); up(1); step(); up(0) }
        assertEquals("ni", typed.toString())
        assertEquals(mapOf(Outcome.TAP to 1L, Outcome.TAP_EARLY to 1L), outcomes())
        assertEquals(0, board.inversions.sum())
        assertEquals(listOf(1L, 1L, 0L, 0L), board.overlap.toList())
        assertEquals(1, board.keys.getValue("n").land.total)
        assertEquals(1, board.keys.getValue("i").land.total)
    }

    /** 同一个键上两根手指换手：前一下记成「换手」。 */
    @Test fun sameKeyHandoverIsCounted() {
        show("u")
        touches { step -> down(0, at(0)); step(); down(1, at(0, dx = 10f)); step(); up(0); step(); up(1) }
        assertEquals("uu", typed.toString())
        assertEquals(mapOf(Outcome.TAP to 1L, Outcome.TAP_HANDOVER to 1L), outcomes())
        assertEquals(2, board.keys.getValue("u").land.total)
    }

    /** 横着拖出取消距离：没出字，原因是拖远了。 */
    @Test fun draggedFarIsLost() {
        show("n", "i", "x", "y")
        touches { step -> down(0, at(0)); step(); moveTo(0, at(0, dx = 60f * density + 5f)); step(); up(0) }
        assertEquals("", typed.toString())
        assertEquals(mapOf(Outcome.NONE_FAR to 1L), outcomes())
        assertEquals(1, board.keys.getValue("n").lost.total)
        assertEquals(0, board.counts[Count.LOST_TAPS])
    }

    /** 留给滑行的键上拖远了又没成滑行：没出字，原因单记。 */
    @Test fun farDragOnGlideKeyIsLostAsDrift() {
        show(Letter("n", glide = true), Letter("i", glide = true), Letter("x", glide = true), Letter("y", glide = true))
        touches { step -> down(0, at(0)); step(); moveTo(0, at(0, dx = 2f * key)); step(); up(0) }
        assertEquals("", typed.toString())
        assertEquals(mapOf(Outcome.NONE_DRIFT to 1L), outcomes())
    }

    /** 手指被上层拿走（滑行、移光标）：没出字，原因是被拿走；手指几乎没动、时间短的算「像点按」。 */
    @Test fun takenByTheParentIsLost() {
        show("n", "i")
        parentTakesAfter = 3f
        touches { step -> down(0, at(0)); step(); moveTo(0, at(0, dy = 5f)); step(); up(0) }
        assertEquals("", typed.toString())
        assertEquals(mapOf(Outcome.NONE_TAKEN to 1L), outcomes())
        assertEquals(1, board.counts[Count.LOST_TAPS])
    }

    /** 长按菜单选了一项、上滑出了符号：各记各的，不算点按。 */
    @Test fun longPressAndSwipeAreNotTaps() {
        show(Letter("n") { it.copy(longPressItems = listOf("N"), onLongPressSelect = { s -> typed.append("[$s]") }) },
            Letter("i") { it.copy(upText = "^", onUp = { s -> typed.append(s) }) })
        touches { step ->
            down(0, at(0)); advanceEventTime(600); up(0); step()
            down(0, at(1)); step(); moveTo(0, at(1, dy = -(50f * density + 10f))); step(); up(0)
        }
        assertEquals("[N]^", typed.toString())
        assertEquals(mapOf(Outcome.LONG to 1L, Outcome.SWIPE to 1L), outcomes())
        assertEquals(0, board.keys.getValue("n").land.total)
    }

    /** 长按到点就执行的动作（长按输入角标）：记成长按。 */
    @Test fun longPressActionIsLong() {
        show(Letter("n") { it.copy(onLongPress = { typed.append("!") }) })
        touches { down(0, at(0)); advanceEventTime(900); up(0) }
        assertEquals("!", typed.toString())
        assertEquals(mapOf(Outcome.LONG to 1L), outcomes())
    }

    /** 滑过了阈值、那个方向没有动作：没出字。 */
    @Test fun blockedSwipeIsLost() {
        show(Letter("n") { it.copy(blockUpSwipe = true) })
        touches { step -> down(0, at(0)); step(); moveTo(0, at(0, dy = -(50f * density + 10f))); step(); up(0) }
        assertEquals("", typed.toString())
        assertEquals(mapOf(Outcome.NONE_BLOCKED to 1L), outcomes())
    }

    /** 删除键还按着就点了字母：删除提前出，记一次；顺序没有反。 */
    @Test fun heldDeleteIsCountedWhenItGoesFirst() {
        show(Delete(), Letter("n"))
        touches { step -> down(0, at(0)); step(); down(1, at(1)); step(); up(1); step(); up(0) }
        assertEquals("<n", typed.toString())
        assertEquals(1, board.counts[Count.EARLY_REPEAT])
        assertEquals(1, board.counts[Count.DELETES])
        assertEquals(0, board.inversions.sum())
        assertEquals(listOf(1L, 1L, 0L, 0L), board.overlap.toList())
    }

    /** 删除键的落点：记在它出字的那一下上（不跟手势，不算有落点的字母类按下）。 */
    @Test fun deleteKeyLandingIsRecorded() {
        show(Letter("m"), Delete())
        touches { step -> down(0, Offset(key + 12f, 80f)); step(); up(0) }
        assertEquals("<", typed.toString())
        assertEquals(mapOf(8 * 10 + 1 to 1L), board.keys.getValue("delete").land.counts)
        assertEquals(key / density, board.keys.getValue("delete").maxWidth)
        assertEquals(0, board.counts[Count.CHAR_PRESSES])
        assertEquals(1, board.counts[Count.DELETES])
    }

    /** 点了字母马上点删除、再点别的字母：记一次「后悔」和一对「按成的>想按的」。 */
    @Test fun deleteAndRetypeIsPaired() {
        show(Letter("o"), Letter("p"), Delete())
        touches { step ->
            down(0, at(0, dx = 45f)); step(); up(0); step()
            down(0, at(2)); step(); up(0); step()
            down(0, at(1)); step(); up(0)
        }
        assertEquals("o<p", typed.toString())
        assertEquals(1, board.counts[Count.REGRETS])
        assertEquals(mapOf("o>p" to mapOf(5 * 10 + 9 to 1L)), board.retyped.mapValues { it.value.counts })
        assertEquals(0, board.inversions[Kind.CHAR * Kind.SIZE + Kind.DELETE])
    }

    /** 没在记的时候（公开的包、无痕），手势照常出字，什么都不记。 */
    @Test fun nothingIsRecordedWhenOff() {
        TouchStats.gate = null
        show("n", "i")
        touches { step -> down(0, at(0)); step(); down(1, at(1)); step(); up(1); step(); up(0) }
        assertEquals("ni", typed.toString())
        assertEquals(true, TouchStats.snapshot().isEmpty())
    }
}
